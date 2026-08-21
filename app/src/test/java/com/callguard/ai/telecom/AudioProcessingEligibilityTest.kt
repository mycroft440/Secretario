package com.callguard.ai.telecom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProcessingEligibilityTest {
    @Test
    fun `api 37 external call is eligible`() {
        assertTrue(
            AudioProcessingEligibility.canUseExternalCallAudioProcessing(
                sdkInt = 37,
                isExternalCall = true
            )
        )
    }

    @Test
    fun `api 36 external call is not eligible`() {
        assertFalse(
            AudioProcessingEligibility.canUseExternalCallAudioProcessing(
                sdkInt = 36,
                isExternalCall = true
            )
        )
    }

    @Test
    fun `ordinary pstn call is not eligible even on api 37`() {
        assertFalse(
            AudioProcessingEligibility.canUseExternalCallAudioProcessing(
                sdkInt = 37,
                isExternalCall = false
            )
        )
    }

    @Test
    fun `public pstn bidirectional pcm remains unavailable`() {
        assertFalse(AudioProcessingEligibility.publicPstnBidirectionalPcmAvailable())
    }
}
