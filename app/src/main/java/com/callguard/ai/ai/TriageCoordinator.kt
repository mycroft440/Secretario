package com.callguard.ai.ai

import android.content.Context
import com.callguard.ai.data.CallDecision
import com.callguard.ai.data.CallRecord
import com.callguard.ai.data.CallRepository
import com.callguard.ai.data.TriageResult

/**
 * Coordinator used by the in-app laboratory. It deliberately permits unverified
 * imported models; production call routing must use classifyForProduction().
 */
class TriageCoordinator(context: Context) : AutoCloseable {
    private val engine = FunctionGemmaTriageEngine(context.applicationContext)

    suspend fun unloadModel() = engine.unload()

    suspend fun classifyAndStore(
        phoneNumber: String,
        transcript: String,
        recordId: Long = System.currentTimeMillis()
    ): TriageResult {
        val normalizedNumber = phoneNumber.trim().take(40).ifBlank { "Número desconhecido" }
        val normalizedTranscript = transcript.trim().take(1_500)
        val result = engine.classifyForLab(normalizedTranscript)
        val label = when (result.decision) {
            CallDecision.ALLOWED -> result.callerName?.let { "ligação real • $it" } ?: result.category.displayName()
            CallDecision.BLOCKED -> result.category.displayName()
            CallDecision.PENDING -> "precisa informar o motivo"
        }
        CallRepository.upsert(
            CallRecord(
                id = recordId,
                phoneNumber = normalizedNumber,
                label = label,
                decision = result.decision,
                category = result.category,
                transcript = normalizedTranscript.ifBlank { null },
                summary = result.summary,
                callerName = result.callerName,
                confidence = result.confidence,
                isDemo = true
            )
        )
        return result
    }

    private fun com.callguard.ai.data.CallCategory.displayName(): String = when (this) {
        com.callguard.ai.data.CallCategory.OPERATOR -> "operadora/telemarketing"
        com.callguard.ai.data.CallCategory.MARKETING -> "marketing"
        com.callguard.ai.data.CallCategory.SCAM -> "possível golpe/fraude"
        com.callguard.ai.data.CallCategory.ROBOT_OR_SILENT -> "robô/ligação muda"
        com.callguard.ai.data.CallCategory.DELIVERY -> "entrega"
        com.callguard.ai.data.CallCategory.JOB -> "emprego"
        com.callguard.ai.data.CallCategory.HEALTH -> "saúde/urgência"
        com.callguard.ai.data.CallCategory.SERVICE -> "serviço"
        com.callguard.ai.data.CallCategory.REAL_PERSON -> "pessoa real"
        com.callguard.ai.data.CallCategory.UNKNOWN -> "desconhecido"
    }

    override fun close() = engine.close()
}
