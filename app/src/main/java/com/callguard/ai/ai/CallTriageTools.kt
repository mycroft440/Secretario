package com.callguard.ai.ai

import com.callguard.ai.data.CallCategory
import com.callguard.ai.data.CallDecision
import com.callguard.ai.data.TriageResult
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.util.concurrent.atomic.AtomicReference

/**
 * Tools exposed to FunctionGemma. The model does not directly block Android calls;
 * it selects one of these domain actions. The application validates and executes it.
 */
class CallTriageTools : ToolSet {
    private val latest = AtomicReference<TriageResult?>(null)

    fun consumeLatest(): TriageResult? = latest.getAndSet(null)

    @Tool(
        description = "Allow a real and useful call to ring. Use for a known person with a new number, delivery, job, health, requested service, emergency or other clearly legitimate purpose."
    )
    fun allowCall(
        @ToolParam(description = "Caller name when stated, or empty string if unknown") callerName: String,
        @ToolParam(description = "Category: REAL_PERSON, DELIVERY, JOB, HEALTH, SERVICE or UNKNOWN") category: String,
        @ToolParam(description = "Short Portuguese summary of why the person is calling") summary: String,
        @ToolParam(description = "Confidence from 0.0 to 1.0") confidence: Double
    ): Map<String, Any> {
        val result = TriageResult(
            decision = CallDecision.ALLOWED,
            category = category.toCategory(),
            callerName = callerName.ifBlank { null },
            summary = summary,
            confidence = confidence.coerceIn(0.0, 1.0).toFloat()
        )
        latest.set(result)
        return mapOf("accepted" to true)
    }

    @Tool(
        description = "Block an unwanted call. Use for telemarketing, operator sales, prerecorded robot, scam-like solicitation, or a silent call."
    )
    fun blockCall(
        @ToolParam(description = "Category: OPERATOR, MARKETING, ROBOT_OR_SILENT or UNKNOWN") category: String,
        @ToolParam(description = "Short Portuguese reason for blocking") reason: String,
        @ToolParam(description = "Confidence from 0.0 to 1.0") confidence: Double
    ): Map<String, Any> {
        val result = TriageResult(
            decision = CallDecision.BLOCKED,
            category = category.toCategory(),
            callerName = null,
            summary = reason,
            confidence = confidence.coerceIn(0.0, 1.0).toFloat()
        )
        latest.set(result)
        return mapOf("accepted" to true)
    }

    @Tool(
        description = "Ask one short follow-up question only when the caller's purpose is ambiguous. Prefer asking for the reason of the call."
    )
    fun askForClarification(
        @ToolParam(description = "Short question in Brazilian Portuguese") question: String
    ): Map<String, Any> {
        latest.set(
            TriageResult(
                decision = CallDecision.PENDING,
                category = CallCategory.UNKNOWN,
                callerName = null,
                summary = "Motivo ainda não está claro.",
                confidence = 0.0f,
                askAgain = true,
                followUpQuestion = question
            )
        )
        return mapOf("accepted" to true)
    }

    private fun String.toCategory(): CallCategory = runCatching {
        CallCategory.valueOf(trim().uppercase())
    }.getOrDefault(CallCategory.UNKNOWN)
}
