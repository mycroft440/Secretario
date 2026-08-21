package com.callguard.ai.voip

import android.content.Context
import com.callguard.ai.ai.FunctionGemmaTriageEngine
import com.callguard.ai.ai.ModelInstaller
import com.callguard.ai.data.CallCategory
import com.callguard.ai.data.CallDecision
import com.callguard.ai.data.TriageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * End-to-end local decision loop for SIP-delivered calls.
 * Network transports the call only; STT, TTS and FunctionGemma inference stay on-device.
 */
class UniversalVoiceTriageController(private val context: Context) {
    suspend fun triage(session: SipCallSession) {
        val transcriber = VoskOfflineTranscriber(context)
        val tts = OfflineAndroidTts(context)
        val gemma = FunctionGemmaTriageEngine(context)
        val workDir = File(context.cacheDir, "voip-triage/${session.token}").apply { mkdirs() }

        try {
            if (!VoskPtModelManager.isInstalled(context)) {
                presentFailOpen(session, "STT offline não instalado; ligação apresentada sem bloqueio.")
                return
            }

            val initialPrompt = File(workDir, "prompt-inicial.wav")
            val initialDuration = tts.synthesize(
                "Olá. Este é o assistente de chamadas. Diga seu nome e o motivo da ligação.",
                initialPrompt
            ).getOrElse {
                presentFailOpen(session, "Voz offline indisponível; ligação apresentada sem bloqueio.")
                return
            }
            session.playWav(initialPrompt, initialDuration).getOrElse {
                presentFailOpen(session, "Falha ao falar com o chamador; ligação apresentada.")
                return
            }

            val firstAudio = session.recordWav(File(workDir, "resposta-1.wav"), FIRST_TURN_MS)
                .getOrElse {
                    presentFailOpen(session, "Falha ao ouvir o chamador; ligação apresentada.")
                    return
                }
            val firstText = transcriber.transcribe(firstAudio).getOrElse {
                presentFailOpen(session, "Falha de transcrição; ligação apresentada.")
                return
            }
            UniversalSipRuntime.updateCall(session.token, firstText, null, false)

            var transcript = firstText
            var result = classifySafely(gemma, transcript)

            if (result.decision == CallDecision.PENDING || transcript.isBlank()) {
                val question = result.followUpQuestion
                    ?.takeIf { it.isNotBlank() }
                    ?: "Pode dizer seu nome e explicar brevemente o motivo da ligação?"
                val followPrompt = File(workDir, "pergunta-2.wav")
                val duration = tts.synthesize(question, followPrompt).getOrElse {
                    presentFailOpen(session, "Motivo incerto; ligação apresentada.", transcript)
                    return
                }
                session.playWav(followPrompt, duration).getOrElse {
                    presentFailOpen(session, "Motivo incerto; ligação apresentada.", transcript)
                    return
                }
                val secondAudio = session.recordWav(File(workDir, "resposta-2.wav"), SECOND_TURN_MS)
                    .getOrElse {
                        presentFailOpen(session, "Motivo incerto; ligação apresentada.", transcript)
                        return
                    }
                val secondText = transcriber.transcribe(secondAudio).getOrDefault("")
                transcript = listOf(firstText, secondText).filter { it.isNotBlank() }.joinToString(". ")
                UniversalSipRuntime.updateCall(session.token, transcript, null, false)
                result = classifySafely(gemma, transcript)
            }

            when (result.decision) {
                CallDecision.BLOCKED -> {
                    if (blockIsSafe(result, transcript)) {
                        session.recordHistory(
                            decision = CallDecision.BLOCKED,
                            category = result.category,
                            label = categoryLabel(result.category),
                            transcript = transcript.ifBlank { null },
                            summary = result.summary,
                            callerName = result.callerName,
                            confidence = result.confidence
                        )
                        UniversalSipRuntime.updateCall(
                            session.token,
                            transcript,
                            result.summary,
                            false
                        )
                        session.terminate()
                    } else {
                        presentFailOpen(
                            session,
                            "A IA sugeriu bloqueio, mas faltou evidência determinística; chamada apresentada.",
                            transcript,
                            result
                        )
                    }
                }
                CallDecision.ALLOWED -> presentApproved(session, transcript, result, tts, workDir)
                CallDecision.PENDING -> presentFailOpen(
                    session,
                    "Não foi possível confirmar o motivo com segurança.",
                    transcript,
                    result
                )
            }
        } catch (error: Throwable) {
            presentFailOpen(session, "Falha na triagem local; ligação apresentada por segurança.")
        } finally {
            transcriber.close()
            gemma.close()
            tts.close()
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
        }
    }

    private suspend fun classifySafely(
        engine: FunctionGemmaTriageEngine,
        transcript: String
    ): TriageResult {
        if (transcript.isBlank()) {
            return engine.classifyForLab("")
        }
        val trusted = ModelInstaller.verifiedTrustedMetadata(context) != null
        return if (trusted) engine.classifyForProduction(transcript) else engine.classifyForLab(transcript)
    }

    /**
     * An untrusted/generic local model cannot block on its own. Terminal blocking
     * also requires explicit lexical evidence consistent with the model category.
     */
    private fun blockIsSafe(result: TriageResult, transcript: String): Boolean {
        if (!result.confidence.isFinite() || result.confidence < 0.90f) return false
        val text = transcript.lowercase()
        return when (result.category) {
            CallCategory.MARKETING, CallCategory.OPERATOR -> {
                val terms = listOf(
                    "oferta", "promoção", "promocao", "plano", "contrate",
                    "desconto", "telemarketing", "vantagem", "venda"
                )
                terms.count { it in text } >= 2
            }
            CallCategory.SCAM -> {
                (("senha" in text || "código" in text || "codigo" in text) &&
                    ("banco" in text || "cartão" in text || "cartao" in text || "sms" in text)) ||
                    ("pix" in text && ("liberar" in text || "taxa" in text || "urgente" in text))
            }
            CallCategory.ROBOT_OR_SILENT -> listOf(
                "mensagem gravada", "central automática", "central automatica", "robô", "robo"
            ).any { it in text }
            else -> false
        }
    }

    private suspend fun presentApproved(
        session: SipCallSession,
        transcript: String,
        result: TriageResult,
        tts: OfflineAndroidTts,
        workDir: File
    ) {
        val waiting = File(workDir, "aguarde.wav")
        tts.synthesize("Certo. Só um momento.", waiting).getOrNull()?.let { duration ->
            session.playWav(waiting, duration)
        }
        val summary = result.summary.ifBlank { categoryLabel(result.category) }
        session.recordHistory(
            decision = CallDecision.ALLOWED,
            category = result.category,
            label = categoryLabel(result.category),
            transcript = transcript.ifBlank { null },
            summary = summary,
            callerName = result.callerName,
            confidence = result.confidence
        )
        UniversalSipRuntime.updateCall(session.token, transcript, summary, true)
        present(session, summary)
    }

    private suspend fun presentFailOpen(
        session: SipCallSession,
        reason: String,
        transcript: String? = null,
        modelResult: TriageResult? = null
    ) {
        session.recordHistory(
            decision = CallDecision.PENDING,
            category = modelResult?.category ?: CallCategory.UNKNOWN,
            label = "motivo não confirmado",
            transcript = transcript?.ifBlank { null },
            summary = reason,
            callerName = modelResult?.callerName,
            confidence = modelResult?.confidence
        )
        UniversalSipRuntime.updateCall(session.token, transcript, reason, true)
        present(session, reason)
    }

    private suspend fun present(session: SipCallSession, summary: String) {
        val telecom = UniversalSipRuntime.presentToUser(context, session.token, summary)
        if (telecom.isFailure) {
            // Some OEMs may reject a self-managed Telecom call. Keep the same
            // full-screen UI/ringtone and let its Answer button hand off SIP directly.
            VoipIncomingCallNotifier.show(context, session.token, session.caller, summary)
        }
    }

    private fun categoryLabel(category: CallCategory): String = when (category) {
        CallCategory.REAL_PERSON -> "pessoa real"
        CallCategory.OPERATOR -> "operadora/telemarketing"
        CallCategory.MARKETING -> "marketing"
        CallCategory.SCAM -> "possível golpe/fraude"
        CallCategory.ROBOT_OR_SILENT -> "robô/gravação"
        CallCategory.DELIVERY -> "entrega"
        CallCategory.JOB -> "emprego"
        CallCategory.HEALTH -> "saúde/urgência"
        CallCategory.SERVICE -> "serviço"
        CallCategory.UNKNOWN -> "desconhecido"
    }

    companion object {
        private const val FIRST_TURN_MS = 7_000L
        private const val SECOND_TURN_MS = 6_000L
    }
}
