package com.callguard.ai.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import androidx.annotation.RequiresApi

/**
 * Controls the Android 17 Telecom state machine for a compatible EXTERNAL call.
 *
 * This class intentionally does not capture or inject PCM. Android 17 exposes
 * the audio-processing / simulated-ringing states for external Connections,
 * while the actual audio transport must be supplied by the owner of that
 * external call (for example an OEM/companion/VoIP integration).
 */
@RequiresApi(37)
class Android17AudioProcessingController private constructor(
    private val connection: Connection
) {
    /** Enter the hidden-from-user call-screening phase. */
    fun enterCallScreening(): Result<Unit> = runCatching {
        requireExternalCall()
        connection.setAudioProcessing(Call.AUDIO_PROCESSING_USE_CASE_CALL_SCREENING)
    }

    /**
     * Present an approved call to the user. Telecom takes audio focus and may
     * play the ringtone while the network-side call remains active.
     */
    fun presentToUser(): Result<Unit> = runCatching {
        requireExternalCall()
        check(connection.state == Connection.STATE_AUDIO_PROCESSING) {
            "A chamada precisa estar em STATE_AUDIO_PROCESSING antes de SIMULATED_RINGING."
        }
        connection.setSimulatedRinging()
    }

    /** Mark the call active after the user answers through the owning provider. */
    fun markActive(): Result<Unit> = runCatching {
        requireExternalCall()
        check(
            connection.state == Connection.STATE_SIMULATED_RINGING ||
                connection.state == Connection.STATE_AUDIO_PROCESSING
        ) {
            "A chamada não está em um estado válido para ser entregue ao usuário."
        }
        connection.setActive()
    }

    private fun requireExternalCall() {
        check(
            connection.connectionProperties and Connection.PROPERTY_IS_EXTERNAL_CALL != 0
        ) {
            "Android 17 permite setAudioProcessing/setSimulatedRinging apenas em PROPERTY_IS_EXTERNAL_CALL."
        }
    }

    companion object {
        /**
         * Safe factory for code that supports Android 10+ without touching API
         * 37-only methods on older devices.
         */
        fun create(connection: Connection): Result<Android17AudioProcessingController> {
            if (
                !AudioProcessingEligibility.canUseExternalCallAudioProcessing(
                    sdkInt = Build.VERSION.SDK_INT,
                    isExternalCall = connection.connectionProperties and
                        Connection.PROPERTY_IS_EXTERNAL_CALL != 0
                )
            ) {
                return Result.failure(
                    UnsupportedOperationException(
                        "Audio Processing público requer Android 17/API 37 e uma chamada externa compatível."
                    )
                )
            }

            return Result.success(Android17AudioProcessingController(connection))
        }
    }
}
