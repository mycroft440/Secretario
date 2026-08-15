package com.callguard.ai.telecom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastScreeningPolicyTest {
    @Test
    fun blocksDemoOperator() {
        assertTrue(FastScreeningPolicy.evaluate("389393939", verificationFailed = false).block)
    }

    @Test
    fun carrierVerificationFailureIsRiskSignalNotAutomaticBlock() {
        val result = FastScreeningPolicy.evaluate("11999999999", verificationFailed = true)
        assertFalse(result.block)
        assertTrue(result.suspicious)
    }

    @Test
    fun doesNotBlockUnknownLegitimateNumber() {
        assertFalse(FastScreeningPolicy.evaluate("838383898", verificationFailed = false).block)
    }
}
