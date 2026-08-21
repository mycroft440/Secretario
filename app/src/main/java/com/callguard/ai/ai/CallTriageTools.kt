package com.callguard.ai.ai

import com.callguard.ai.data.CallCategory
import com.callguard.ai.data.CallDecision
import com.callguard.ai.data.TriageResult
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Tool schema exposed to FunctionGemma plus deterministic application policy.
 * The model proposes an action; it never directly controls Android telephony.
 */
class CallTriageTools : ToolSet {
    companion object {
        private const val MIN_ALLOW_CONFIDENCE = 0.70
        private const val MIN_BLOCK_CONFIDENCE = 0.90

        private val ALLOW_CATEGORIES = setOf(
            CallCategory.REAL_PERSON,
            CallCategory.DELIVERY,
            CallCategory.JOB,
            CallCategory.HEALTH,
            CallCategory.SERVICE
        )
        private val BLOCK_CATEGORIES = setOf(
            CallCategory.OPERATOR,
            CallCategory.MARKETING,
            CallCategory.SCAM,
            CallCategory.ROBOT_OR_SILENT
        )
    }

    @Tool(
        description = "Allow a real and useful call to ring. Use for a known person with a new number, delivery, job, health, requested service, emergency or other clearly legitimate purpose."
    )
    fun allowCall(
        @ToolParam(description = "Caller name when stated, or empty string if unknown") callerName: String,
        @ToolParam(description = "Category: REAL_PERSON, DELIVERY, JOB, HEALTH or SERVICE") category: String,
        @ToolParam(description = "Short Portuguese summary of why the person is calling") summary: String,
        @ToolParam(description = "Confidence from 0.0 to 1.0") confidence: Double
    ): Map<String, Any> = mapOf("accepted" to true)

    @Tool(
        description = "Block an unwanted call. Use only for clear telemarketing, operator sales, scam/fraud solicitation, or a prerecorded robot. Silence must be confirmed by the audio/VAD layer rather than inferred from missing text."
    )
    fun blockCall(
        @ToolParam(description = "Category: OPERATOR, MARKETING, SCAM or ROBOT_OR_SILENT") category: String,
        @ToolParam(description = "Short Portuguese reason for blocking") reason: String,
        @ToolParam(description = "Confidence from 0.0 to 1.0") confidence: Double
    ): Map<String, Any> = mapOf("accepted" to true)

    @Tool(
        description = "Ask one short follow-up question when the caller's purpose or identity claim is ambiguous. Prefer abstaining over an uncertain block."
    )
    fun askForClarification(
        @ToolParam(description = "Short question in Brazilian Portuguese") question: String
    ): Map<String, Any> = mapOf("accepted" to true)

    fun resolveManualCall(name: String, arguments: Map<String, Any?>): TriageResult = when (name) {
        "allowCall" -> resolveAllow(arguments)
        "blockCall" -> resolveBlock(arguments)
        "askForClarification" -> clarification(arguments.string("question"))
        else -> pending("Ferramenta desconhecida proposta pelo modelo.")
    }

    private fun resolveAllow(args: Map<String, Any?>): TriageResult {
        val category = args.string("category").toCategory()
        val confidence = args.number("confidence")
        if (category !in ALLOW_CATEGORIES) {
            return pending("Categoria incompatível com permitir chamada.")
        }
        if (confidence < MIN_ALLOW_CONFIDENCE) {
            return pending("Confiança insuficiente para permitir automaticamente.")
        }
        return TriageResult(
            decision = CallDecision.ALLOWED,
            category = category,
            callerName = args.string("callerName").cleanText(80).ifBlank { null },
            summary = args.string("summary").cleanText(240).ifBlank { "Ligação legítima identificada." },
            confidence = confidence.coerceIn(0.0, 1.0).toFloat()
        )
    }

    private fun resolveBlock(args: Map<String, Any?>): TriageResult {
        val category = args.string("category").toCategory()
        val confidence = args.number("confidence")
        if (category !in BLOCK_CATEGORIES) {
            return pending("Categoria incompatível com bloqueio automático.")
        }
        if (confidence < MIN_BLOCK_CONFIDENCE) {
            return pending("Confiança insuficiente para bloquear automaticamente.")
        }
        return TriageResult(
            decision = CallDecision.BLOCKED,
            category = category,
            callerName = null,
            summary = args.string("reason").cleanText(240).ifBlank { "Ligação indesejada identificada." },
            confidence = confidence.coerceIn(0.0, 1.0).toFloat()
        )
    }

    private fun clarification(question: String): TriageResult = TriageResult(
        decision = CallDecision.PENDING,
        category = CallCategory.UNKNOWN,
        callerName = null,
        summary = "Motivo ainda não está claro.",
        confidence = 0f,
        askAgain = true,
        followUpQuestion = question.cleanText(180).ifBlank {
            "Pode informar brevemente seu nome e o motivo da ligação?"
        }
    )

    private fun pending(reason: String): TriageResult = TriageResult(
        decision = CallDecision.PENDING,
        category = CallCategory.UNKNOWN,
        callerName = null,
        summary = reason,
        confidence = 0f,
        askAgain = true,
        followUpQuestion = "Pode informar brevemente seu nome e o motivo da ligação?"
    )

    private fun Map<String, Any?>.string(key: String): String = this[key]?.toString().orEmpty()

    private fun Map<String, Any?>.number(key: String): Double =
        (this[key] as? Number)?.toDouble()
            ?: this[key]?.toString()?.toDoubleOrNull()
            ?: 0.0

    private fun String.toCategory(): CallCategory = runCatching {
        CallCategory.valueOf(trim().uppercase())
    }.getOrDefault(CallCategory.UNKNOWN)

    private fun String.cleanText(maxLength: Int): String =
        replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)
}
