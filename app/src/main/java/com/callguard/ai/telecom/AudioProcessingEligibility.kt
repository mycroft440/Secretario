package com.callguard.ai.telecom

/**
 * Pure policy for deciding whether Android's public API 37 audio-processing
 * state machine may be used for a Connection.
 *
 * Important: this only describes state-control eligibility. It does not grant
 * access to carrier PCM. A compatible external-call provider must still supply
 * the audio transport used by the AI pipeline.
 */
object AudioProcessingEligibility {
    const val MIN_API_LEVEL = 37

    fun canUseExternalCallAudioProcessing(
        sdkInt: Int,
        isExternalCall: Boolean
    ): Boolean = sdkInt >= MIN_API_LEVEL && isExternalCall

    fun publicPstnBidirectionalPcmAvailable(): Boolean = false
}
