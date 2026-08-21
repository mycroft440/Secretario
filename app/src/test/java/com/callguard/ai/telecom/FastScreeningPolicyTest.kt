package com.callguard.ai.telecom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastScreeningPolicyTest {
    @Test
    fun `demo operator number is not a production blocking rule`() {
        assertFalse(FastScreeningPolicy.evaluate("389393939", verificationFailed = false).block)
    }

    @Test
    fun `demo robot number is not a production blocking rule`() {
        assertFalse(FastScreeningPolicy.evaluate("838383893", verificationFailed = false).block)
    }

    @Test
    fun `carrier verification failure is risk signal not automatic block`() {
        val result = FastScreeningPolicy.evaluate("11999999999", verificationFailed = true)
        assertFalse(result.block)
        assertTrue(result.suspicious)
    }

    @Test
    fun `unknown number is allowed until a supported triage decision exists`() {
        assertFalse(FastScreeningPolicy.evaluate("838383898", verificationFailed = false).block)
    }
}
