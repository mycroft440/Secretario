package com.callguard.ai.audio

import android.content.Context
import android.os.Build

/** OEM/system distribution: use PSTN interception only where the platform supports it. */
object DistributionAudioBridgeFactory {
    fun create(context: Context): CarrierAudioBridge =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PrivilegedPstnAudioBridge(context.applicationContext)
        } else {
            PublicAndroidCarrierAudioBridge()
        }
}
