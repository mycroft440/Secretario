package com.callguard.ai.ai

import android.content.Context
import com.callguard.ai.data.CallCategory
import com.callguard.ai.data.CallDecision
import com.callguard.ai.data.TriageResult
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class FunctionGemmaTriageEngine(private val context: Context) : AutoCloseable {
    companion object {
        const val MODEL_FILE = "functiongemma-callguard.litertlm"
    }

    private var engine: Engine? = null
    private val tools = CallTriageTools()
    private val inferenceMutex = Mutex()

    val modelFile: File
        get() = File(context.filesDir, "models/$MODEL_FILE")

    val isModelInstalled: Boolean
        get() = modelFile.exists() && modelFile.length() > 1_000_000L

    /**
     * LiteRT-LM model loading can take several seconds. Keep it off the Android
     * main thread and serialize engine access because this MVP uses one engine.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            runCatching { initializeLocked() }
        }
    }

    private suspend fun initializeLocked() {
        if (!isModelInstalled) error("Modelo FunctionGemma não instalado")
        if (engine != null) return

        modelFile.parentFile?.mkdirs()

        // Prefer GPU. If the device OpenCL stack is unavailable/incompatible,
        // retry on CPU so supported phones are not locked out of the app.
        val gpuAttempt = runCatching {
            createEngine(Backend.GPU()).also { it.initialize() }
        }
        engine = gpuAttempt.getOrElse {
            createEngine(Backend.CPU()).also { it.initialize() }
        }
    }

    private fun createEngine(backend: Backend): Engine = Engine(
        EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend,
            cacheDir = context.cacheDir.absolutePath
        )
    )

    suspend fun classify(transcript: String): TriageResult {
        val cleanTranscript = transcript.trim()

        // Deterministic development fallback. It exists only so UI/history can
        // be exercised before a fine-tuned .litertlm is installed.
        if (!isModelInstalled) return demoFallback(cleanTranscript)

        return withContext(Dispatchers.Default) {
            inferenceMutex.withLock {
                if (engine == null) initializeLocked()
                tools.consumeLatest() // Clear any stale tool result.

                val conversation = engine!!.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(
                            "Você é o roteador de triagem CallGuard para ligações brasileiras. " +
                                "Escolha exatamente uma ferramenta e não invente identidade. " +
                                "Bloqueie telemarketing, venda de operadora, robô, gravação automática e silêncio. " +
                                "Permita entrega, emprego, saúde, serviço solicitado e pessoa real com motivo legítimo. " +
                                "Se o motivo não estiver claro, use askForClarification. " +
                                "Não produza uma decisão fora das ferramentas."
                        ),
                        tools = listOf(tool(tools)),
                        automaticToolCalling = true,
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
                    )
                )

                conversation.use {
                    it.sendMessage("Transcrição do chamador: $cleanTranscript")
                }

                tools.consumeLatest() ?: TriageResult(
                    decision = CallDecision.PENDING,
                    category = CallCategory.UNKNOWN,
                    callerName = null,
                    summary = "O modelo não produziu uma decisão estruturada.",
                    confidence = 0f,
                    askAgain = true,
                    followUpQuestion = "Pode dizer seu nome e o motivo da ligação?"
                )
            }
        }
    }

    private fun demoFallback(text: String): TriageResult {
        val t = text.lowercase().trim()
        return when {
            ("roberto" in t && ("troquei" in t || "novo número" in t || "novo numero" in t)) -> TriageResult(
                decision = CallDecision.ALLOWED,
                category = CallCategory.REAL_PERSON,
                callerName = "Roberto",
                summary = "Roberto informou que trocou de número e precisa falar com você.",
                confidence = 0.96f
            )
            listOf("promoção", "promocao", "oferta", "plano", "telemarketing", "vantagem exclusiva").any { it in t } -> TriageResult(
                decision = CallDecision.BLOCKED,
                category = if ("operadora" in t || "plano" in t) CallCategory.OPERATOR else CallCategory.MARKETING,
                callerName = null,
                summary = "Oferta comercial/marketing detectado.",
                confidence = 0.95f
            )
            t.isBlank() || listOf("silêncio", "silencio", "ligação muda", "ligacao muda", "robô", "robo", "mensagem gravada").any { it in t } -> TriageResult(
                decision = CallDecision.BLOCKED,
                category = CallCategory.ROBOT_OR_SILENT,
                callerName = null,
                summary = "Robô ou ligação muda detectada.",
                confidence = 0.98f
            )
            listOf("entregador", "encomenda", "portaria", "pedido").any { it in t } -> TriageResult(
                decision = CallDecision.ALLOWED,
                category = CallCategory.DELIVERY,
                callerName = null,
                summary = "Ligação relacionada a entrega.",
                confidence = 0.92f
            )
            listOf("currículo", "curriculo", "entrevista", "vaga", "recrutamento").any { it in t } -> TriageResult(
                decision = CallDecision.ALLOWED,
                category = CallCategory.JOB,
                callerName = null,
                summary = "Ligação relacionada a emprego/recrutamento.",
                confidence = 0.92f
            )
            listOf("hospital", "emergência", "emergencia", "pronto socorro", "médico", "medico").any { it in t } -> TriageResult(
                decision = CallDecision.ALLOWED,
                category = CallCategory.HEALTH,
                callerName = null,
                summary = "Ligação relacionada a saúde ou urgência.",
                confidence = 0.94f
            )
            else -> TriageResult(
                decision = CallDecision.PENDING,
                category = CallCategory.UNKNOWN,
                callerName = null,
                summary = "Motivo insuficiente para decidir com segurança.",
                confidence = 0.35f,
                askAgain = true,
                followUpQuestion = "Pode informar brevemente seu nome e o motivo da ligação?"
            )
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
