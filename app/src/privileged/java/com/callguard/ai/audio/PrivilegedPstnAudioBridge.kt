package com.callguard.ai.audio

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import com.callguard.ai.telecom.PrivilegedBackgroundAudioController
import com.callguard.ai.telecom.PrivilegedCallRegistry
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Real PSTN downlink/uplink bridge for the OEM/system distribution.
 *
 * Android exposes these audio interception methods as @SystemApi and protects
 * them with CALL_AUDIO_INTERCEPTION. This implementation uses reflection only
 * because the normal public android.jar intentionally omits the SystemApi
 * methods. It therefore remains absent from the public flavor and fails closed
 * unless every runtime capability is actually granted by the platform.
 */
class PrivilegedPstnAudioBridge(private val context: Context) : CarrierAudioBridge {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val roleManager = context.getSystemService(RoleManager::class.java)

    override val availability: CarrierAudioBridge.Availability
        get() = if (basicCapabilitiesAvailable()) {
            CarrierAudioBridge.Availability.OEM_PRIVILEGED_AVAILABLE
        } else {
            CarrierAudioBridge.Availability.PUBLIC_ANDROID_UNAVAILABLE
        }

    override suspend fun open(callId: String): Result<CarrierAudioBridge.Session> = runCatching {
        check(basicCapabilitiesAvailable()) {
            "O aparelho/instalação não concedeu os requisitos de áudio PSTN privilegiado."
        }

        val call = PrivilegedCallRegistry.get(callId)
            ?: error("A chamada não está registrada no InCallService privilegiado.")
        val controller = PrivilegedBackgroundAudioController(call)
        check(controller.isRuntimeApiPresent()) {
            "A ROM não expõe a SystemApi de background call audio."
        }

        var enteredAudioProcessing = false
        var downlink: AudioRecord? = null
        var uplink: AudioTrack? = null
        val previousMode = audioManager.mode

        try {
            controller.enter().getOrThrow()
            enteredAudioProcessing = true
            awaitState(call, Call.STATE_AUDIO_PROCESSING)

            audioManager.mode = AudioManager.MODE_CALL_SCREENING
            check(audioManager.mode == AudioManager.MODE_CALL_SCREENING) {
                "O aparelho recusou MODE_CALL_SCREENING."
            }
            check(isPstnCallAudioInterceptable()) {
                "O hardware/Audio HAL não expõe PSTN uplink/downlink interceptáveis."
            }

            downlink = createDownlinkRecord()
            uplink = createUplinkTrack()
            downlink.startRecording()
            uplink.play()

            SessionImpl(
                call = call,
                controller = controller,
                audioManager = audioManager,
                previousAudioMode = previousMode,
                downlink = downlink,
                uplink = uplink
            )
        } catch (error: Throwable) {
            runCatching { downlink?.stop() }
            runCatching { downlink?.release() }
            runCatching { uplink?.stop() }
            runCatching { uplink?.release() }
            runCatching { audioManager.mode = previousMode }
            if (enteredAudioProcessing && call.state == Call.STATE_AUDIO_PROCESSING) {
                // Never strand a real caller in an invisible state if setup fails.
                controller.exitAndRing()
            }
            throw error
        }
    }

    private fun basicCapabilitiesAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) != true) return false
        if (
            context.checkSelfPermission(CALL_AUDIO_INTERCEPTION_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (audioManager?.isCallScreeningModeSupported != true) return false

        return runCatching {
            AudioManager::class.java.getMethod("isPstnCallAudioInterceptable")
            AudioManager::class.java.getMethod(
                "getCallDownlinkExtractionAudioRecord",
                AudioFormat::class.java
            )
            AudioManager::class.java.getMethod(
                "getCallUplinkInjectionAudioTrack",
                AudioFormat::class.java
            )
        }.isSuccess
    }

    private fun isPstnCallAudioInterceptable(): Boolean = invokeSystemApi {
        AudioManager::class.java
            .getMethod("isPstnCallAudioInterceptable")
            .invoke(audioManager) as Boolean
    }

    private fun createDownlinkRecord(): AudioRecord {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_HZ)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return invokeSystemApi {
            AudioManager::class.java
                .getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
                .invoke(audioManager, format) as AudioRecord
        }
    }

    private fun createUplinkTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_HZ)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return invokeSystemApi {
            AudioManager::class.java
                .getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
                .invoke(audioManager, format) as AudioTrack
        }
    }

    private suspend fun awaitState(call: Call, expectedState: Int) {
        if (call.state == expectedState) return
        withTimeout(STATE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                lateinit var callback: Call.Callback
                callback = object : Call.Callback() {
                    override fun onStateChanged(changedCall: Call, state: Int) {
                        when {
                            state == expectedState -> {
                                changedCall.unregisterCallback(callback)
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            state == Call.STATE_DISCONNECTED -> {
                                changedCall.unregisterCallback(callback)
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        IllegalStateException("A chamada foi encerrada durante a preparação da triagem.")
                                    )
                                }
                            }
                        }
                    }
                }
                call.registerCallback(callback, handler)
                continuation.invokeOnCancellation {
                    handler.post { runCatching { call.unregisterCallback(callback) } }
                }
            }
        }
    }

    private inline fun <T> invokeSystemApi(block: () -> T): T {
        try {
            return block()
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        } catch (error: ReflectiveOperationException) {
            throw UnsupportedOperationException(
                "A SystemApi de interceptação de áudio não está acessível nesta ROM.",
                error
            )
        }
    }

    private class SessionImpl(
        private val call: Call,
        private val controller: PrivilegedBackgroundAudioController,
        private val audioManager: AudioManager,
        private val previousAudioMode: Int,
        private val downlink: AudioRecord,
        private val uplink: AudioTrack
    ) : CarrierAudioBridge.Session {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val frames = Channel<PcmFrame>(capacity = Channel.BUFFERED)
        private val released = AtomicBoolean(false)
        private val terminalActionTaken = AtomicBoolean(false)
        private val readerJob: Job

        override val inboundAudio: Flow<PcmFrame> = frames.receiveAsFlow()

        init {
            readerJob = scope.launch {
                val buffer = ShortArray(FRAME_SAMPLES)
                while (isActive && !released.get()) {
                    val count = downlink.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING
                    )
                    if (count > 0) {
                        frames.trySend(
                            PcmFrame(
                                samples = buffer.copyOf(count),
                                sampleRateHz = SAMPLE_RATE_HZ,
                                timestampMs = SystemClock.elapsedRealtime()
                            )
                        )
                    } else if (count < 0) {
                        frames.close(IllegalStateException("Falha ao ler downlink PCM: $count"))
                        break
                    }
                }
            }
        }

        override suspend fun playToCaller(frame: PcmFrame): Result<Unit> = runCatching {
            check(!released.get()) { "A sessão de áudio já foi fechada." }
            require(frame.sampleRateHz == SAMPLE_RATE_HZ) {
                "PCM de uplink deve estar em $SAMPLE_RATE_HZ Hz."
            }
            val written = withContext(Dispatchers.IO) {
                uplink.write(
                    frame.samples,
                    0,
                    frame.samples.size,
                    AudioTrack.WRITE_BLOCKING
                )
            }
            check(written == frame.samples.size) {
                "Escrita incompleta no uplink PCM: $written/${frame.samples.size}."
            }
        }

        override suspend fun connectToUser(): Result<Unit> = runCatching {
            check(terminalActionTaken.compareAndSet(false, true)) {
                "A sessão já recebeu uma ação terminal."
            }
            releaseAudio()
            controller.exitAndRing().getOrThrow()
        }

        override suspend fun terminate(): Result<Unit> = runCatching {
            check(terminalActionTaken.compareAndSet(false, true)) {
                "A sessão já recebeu uma ação terminal."
            }
            releaseAudio()
            call.disconnect()
        }

        override fun close() {
            val alreadyTerminal = terminalActionTaken.get()
            releaseAudio()
            if (!alreadyTerminal && call.state == Call.STATE_AUDIO_PROCESSING) {
                // Fail safe: closing without a decision returns the caller to the user.
                controller.exitAndRing()
            }
        }

        private fun releaseAudio() {
            if (!released.compareAndSet(false, true)) return
            readerJob.cancel()
            scope.cancel()
            frames.close()
            runCatching { downlink.stop() }
            runCatching { downlink.release() }
            runCatching { uplink.stop() }
            runCatching { uplink.release() }
            runCatching { audioManager.mode = previousAudioMode }
        }
    }

    companion object {
        private const val CALL_AUDIO_INTERCEPTION_PERMISSION =
            "android.permission.CALL_AUDIO_INTERCEPTION"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
        private const val STATE_TIMEOUT_MS = 4_000L
    }
}
