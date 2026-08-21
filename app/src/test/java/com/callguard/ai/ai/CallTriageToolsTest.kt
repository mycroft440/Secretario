package com.callguard.ai.ai

import com.callguard.ai.data.CallCategory
import com.callguard.ai.data.CallDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTriageToolsTest {
    private val tools = CallTriageTools()

    @Test
    fun `marketing cannot be allowed even if model proposes allowCall`() {
        val result = tools.resolveManualCall(
            "allowCall",
            mapOf(
                "callerName" to "",
                "category" to "MARKETING",
                "summary" to "Oferta comercial",
                "confidence" to 0.99
            )
        )

        assertEquals(CallDecision.PENDING, result.decision)
        assertEquals(CallCategory.UNKNOWN, result.category)
        assertTrue(result.askAgain)
    }

    @Test
    fun `legitimate delivery can be allowed above threshold`() {
        val result = tools.resolveManualCall(
            "allowCall",
            mapOf(
                "callerName" to "João",
                "category" to "DELIVERY",
                "summary" to "Entrega na portaria",
                "confidence" to 0.91
            )
        )

        assertEquals(CallDecision.ALLOWED, result.decision)
        assertEquals(CallCategory.DELIVERY, result.category)
        assertEquals("João", result.callerName)
    }

    @Test
    fun `low confidence marketing is not auto blocked`() {
        val result = tools.resolveManualCall(
            "blockCall",
            mapOf(
                "category" to "MARKETING",
                "reason" to "Parece oferta",
                "confidence" to 0.72
            )
        )

        assertEquals(CallDecision.PENDING, result.decision)
        assertTrue(result.askAgain)
    }

    @Test
    fun `high confidence robot can be blocked`() {
        val result = tools.resolveManualCall(
            "blockCall",
            mapOf(
                "category" to "ROBOT_OR_SILENT",
                "reason" to "Mensagem gravada",
                "confidence" to 0.98
            )
        )

        assertEquals(CallDecision.BLOCKED, result.decision)
        assertEquals(CallCategory.ROBOT_OR_SILENT, result.category)
    }

    @Test
    fun `unknown tool never executes a terminal decision`() {
        val result = tools.resolveManualCall("deleteEverything", emptyMap())

        assertEquals(CallDecision.PENDING, result.decision)
        assertTrue(result.askAgain)
    }
}
