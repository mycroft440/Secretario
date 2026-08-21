package com.callguard.ai.telecom

import android.telecom.Call
import androidx.annotation.RequiresApi
import java.lang.reflect.InvocationTargetException

/**
 * Adapter for the @SystemApi Call background-audio methods used by Android's
 * system/default dialer path. The public SDK hides these methods, so the OEM
 * flavor invokes them reflectively and fails closed if the platform does not
 * expose/permit them.
 *
 * A normal third-party APK is not expected to pass this boundary. The caller
 * must be the platform-authorized default dialer/system integration.
 */
@RequiresApi(30)
class PrivilegedBackgroundAudioController(private val call: Call) {
    fun enter(): Result<Unit> = runCatching {
        check(call.state == Call.STATE_RINGING || call.state == Call.STATE_ACTIVE) {
            "A chamada precisa estar RINGING ou ACTIVE para entrar em background audio processing."
        }
        invokeNoArg("enterBackgroundAudioProcessing")
    }

    fun exitAndRing(): Result<Unit> = runCatching {
        check(call.state == Call.STATE_AUDIO_PROCESSING) {
            "A chamada precisa estar em STATE_AUDIO_PROCESSING para voltar a tocar."
        }
        invokeBoolean("exitBackgroundAudioProcessing", true)
    }

    fun exitToActive(): Result<Unit> = runCatching {
        check(call.state == Call.STATE_AUDIO_PROCESSING) {
            "A chamada precisa estar em STATE_AUDIO_PROCESSING para voltar a ACTIVE."
        }
        invokeBoolean("exitBackgroundAudioProcessing", false)
    }

    fun isRuntimeApiPresent(): Boolean = runCatching {
        Call::class.java.getMethod("enterBackgroundAudioProcessing")
        Call::class.java.getMethod(
            "exitBackgroundAudioProcessing",
            Boolean::class.javaPrimitiveType
        )
    }.isSuccess

    private fun invokeNoArg(name: String) {
        invokeReflective { Call::class.java.getMethod(name).invoke(call) }
    }

    private fun invokeBoolean(name: String, value: Boolean) {
        invokeReflective {
            Call::class.java.getMethod(name, Boolean::class.javaPrimitiveType).invoke(call, value)
        }
    }

    private inline fun invokeReflective(block: () -> Unit) {
        try {
            block()
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        } catch (error: ReflectiveOperationException) {
            throw UnsupportedOperationException(
                "A ROM não expõe a SystemApi de background call audio para esta edição.",
                error
            )
        }
    }
}
