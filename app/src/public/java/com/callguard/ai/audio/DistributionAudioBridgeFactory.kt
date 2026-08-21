package com.callguard.ai.audio

import android.content.Context

/** Play-safe distribution: carrier PCM interception is deliberately unavailable. */
object DistributionAudioBridgeFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): CarrierAudioBridge =
        PublicAndroidCarrierAudioBridge()
}
