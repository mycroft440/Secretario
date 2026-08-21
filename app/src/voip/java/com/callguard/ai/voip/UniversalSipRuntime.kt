package com.callguard.ai.voip

import android.content.Context
import com.callguard.ai.data.CallCategory
import com.callguard.ai.data.CallDecision
import com.callguard.ai.data.CallRecord
import com.callguard.ai.data.CallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AudioMediaPlayer
import org.pjsip.pjsua2.AudioMediaRecorder
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallInfo
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsip_status_code
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_call_media_status
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class VoipRuntimeStatus(
    val running: Boolean = false,
    val registered: Boolean = false,
    val detail: String = "Desativado"
)

data class VoipCallSnapshot(
    val token: String,
    val caller: String,
    val transcript: String? = null,
    val summary: String? = null,
    val awaitingUser: Boolean = false
)

/** PJSIP/PJSUA2 runtime used only by the Android 10+ VoIP flavor. */
object UniversalSipRuntime {
    private val lock = Any()
    private var endpoint: Endpoint? = null
    private var account: CallGuardSipAccount? = null
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private val sessions = ConcurrentHashMap<String, SipCallSession>()
    private val callsById = ConcurrentHashMap<Int, CallGuardSipCall>()

    private val _status = MutableStateFlow(VoipRuntimeStatus())
    val status: StateFlow<VoipRuntimeStatus> = _status

    private val _calls = MutableStateFlow<Map<String, VoipCallSnapshot>>(emptyMap())
    val calls: StateFlow<Map<String, VoipCallSnapshot>> = _calls

    fun start(context: Context, settings: SipAccountSettings): Result<Unit> = synchronized(lock) {
        runCatching {
            require(settings.isComplete) { "Configuração SIP incompleta." }
            stopLocked()

            val ep = Endpoint()
            ep.libCreate()
            ep.libInit(EpConfig())

            val transport = when (settings.transport) {
                SipTransport.UDP -> pjsip_transport_type_e.PJSIP_TRANSPORT_UDP
                SipTransport.TCP -> pjsip_transport_type_e.PJSIP_TRANSPORT_TCP
                SipTransport.TLS -> error(
                    "TLS SIP ainda não está habilitado no build universal; use UDP/TCP nesta versão."
                )
            }
            ep.transportCreate(transport, TransportConfig().apply { port = 0 })

            val accConfig = AccountConfig().apply {
                idUri = "sip:${settings.username}@${settings.domain}"
                regConfig.registrarUri = settings.registrarUri
                sipConfig.authCreds.add(
                    AuthCredInfo("Digest", "*", settings.username, 0, settings.password)
                )
                settings.proxyUri?.takeIf { it.isNotBlank() }?.let { sipConfig.proxies.add(it) }
            }
            val sipAccount = CallGuardSipAccount().also { it.create(accConfig, true) }

            ep.libStart()
            // Null device keeps the media clock alive while isolating the user
            // from caller audio during the local-AI triage phase.
            ep.audDevManager().setNullDev()

            endpoint = ep
            account = sipAccount
            appContext = context.applicationContext
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            _status.value = VoipRuntimeStatus(
                running = true,
                registered = true,
                detail = "SIP ativo • aguardando chamadas"
            )
        }.onFailure {
            stopLocked()
            _status.value = VoipRuntimeStatus(false, false, "Falha SIP: ${it.message}")
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        scope?.cancel()
        scope = null
        sessions.values.forEach { runCatching { it.closeWithoutDecision() } }
        sessions.clear()
        callsById.clear()
        _calls.value = emptyMap()
        runCatching { endpoint?.hangupAllCalls() }
        runCatching { account?.delete() }
        account = null
        runCatching { endpoint?.libDestroy() }
        runCatching { endpoint?.delete() }
        endpoint = null
        appContext = null
        _status.value = VoipRuntimeStatus()
    }

    internal fun acceptIncoming(account: Account, callId: Int) {
        if (endpoint == null) return
        if (sessions.isNotEmpty() || callsById.isNotEmpty()) {
            runCatching {
                val busy = CallGuardSipCall(account, callId)
                val prm = CallOpParam(true).apply {
                    statusCode = pjsip_status_code.PJSIP_SC_BUSY_HERE
                }
                busy.answer(prm)
                busy.delete()
            }
            return
        }

        val call = CallGuardSipCall(account, callId)
        callsById[callId] = call
        runCatching {
            val prm = CallOpParam(true).apply { statusCode = pjsip_status_code.PJSIP_SC_OK }
            call.answer(prm)
        }.onFailure {
            callsById.remove(callId)
            runCatching { call.delete() }
            _status.value = _status.value.copy(detail = "Falha ao atender SIP para triagem: ${it.message}")
        }
    }

    internal fun onMediaReady(call: CallGuardSipCall, audioMedia: AudioMedia) {
        if (sessions.values.any { it.callId == call.nativeCallId }) return
        val context = appContext ?: return
        val ep = endpoint ?: return
        val ci = runCatching { call.info }.getOrNull() ?: return
        val token = UUID.randomUUID().toString()
        val caller = extractCaller(ci)
        val session = SipCallSession(token, call.nativeCallId, caller, call, audioMedia, ep)
        sessions[token] = session
        _calls.value = _calls.value + (token to VoipCallSnapshot(token, caller))
        _status.value = _status.value.copy(detail = "IA atendendo $caller")

        scope?.launch {
            UniversalVoiceTriageController(context).triage(session)
        }
    }

    internal fun onDisconnected(call: CallGuardSipCall) {
        callsById.remove(call.nativeCallId)
        val entry = sessions.entries.firstOrNull { it.value.callId == call.nativeCallId }
        if (entry != null) {
            sessions.remove(entry.key)?.closeWithoutDecision()
            _calls.value = _calls.value - entry.key
        }
        runCatching { call.delete() }
        if (sessions.isEmpty()) {
            _status.value = _status.value.copy(detail = "SIP ativo • aguardando chamadas")
        }
    }

    fun session(token: String): SipCallSession? = sessions[token]

    fun updateCall(token: String, transcript: String?, summary: String?, awaitingUser: Boolean) {
        val old = _calls.value[token] ?: return
        _calls.value = _calls.value + (
            token to old.copy(
                transcript = transcript ?: old.transcript,
                summary = summary ?: old.summary,
                awaitingUser = awaitingUser
            )
        )
    }

    fun presentToUser(context: Context, token: String, summary: String): Result<Unit> {
        val session = sessions[token]
            ?: return Result.failure(IllegalStateException("Sessão SIP ausente."))
        updateCall(token, null, summary, true)
        return VoipPhoneAccountManager.presentIncomingCall(
            context = context,
            token = token,
            caller = session.caller,
            summary = summary
        )
    }

    suspend fun handoffToUser(token: String): Result<Unit> {
        val session = sessions[token]
            ?: return Result.failure(IllegalStateException("Sessão SIP ausente."))
        val result = session.handoffToUser()
        if (result.isSuccess) updateCall(token, null, _calls.value[token]?.summary, false)
        return result
    }

    suspend fun reject(token: String): Result<Unit> {
        val session = sessions[token] ?: return Result.success(Unit)
        val result = session.terminate()
        sessions.remove(token)
        _calls.value = _calls.value - token
        return result
    }

    private fun extractCaller(info: CallInfo): String {
        val remote = info.remoteUri.orEmpty()
        return Regex("sip:([^@;>]+)", RegexOption.IGNORE_CASE)
            .find(remote)?.groupValues?.getOrNull(1)
            ?.take(80)
            ?: remote.take(80).ifBlank { "Número desconhecido" }
    }

    internal class CallGuardSipAccount : Account() {
        override fun onIncomingCall(prm: OnIncomingCallParam) {
            acceptIncoming(this, prm.callId)
        }
    }

    internal class CallGuardSipCall(
        account: Account,
        val nativeCallId: Int
    ) : Call(account, nativeCallId) {
        override fun onCallState(prm: OnCallStateParam?) {
            val ci = runCatching { info }.getOrNull() ?: return
            if (ci.state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) onDisconnected(this)
        }

        override fun onCallMediaState(prm: OnCallMediaStateParam?) {
            val ci = runCatching { info }.getOrNull() ?: return
            ci.media.forEachIndexed { index, media ->
                if (
                    media.type == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    media.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                ) {
                    runCatching { getAudioMedia(index) }.onSuccess { onMediaReady(this, it) }
                }
            }
        }
    }
}

class SipCallSession internal constructor(
    val token: String,
    val callId: Int,
    val caller: String,
    private val call: Call,
    private val callAudio: AudioMedia,
    private val endpoint: Endpoint
) {
    private val closed = AtomicBoolean(false)
    private val handedOff = AtomicBoolean(false)

    suspend fun playWav(file: File, durationMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(!closed.get()) { "Chamada encerrada." }
            val player = AudioMediaPlayer()
            try {
                player.createPlayer(file.absolutePath, 0)
                player.startTransmit(callAudio)
                delay(durationMs.coerceIn(300L, 15_000L))
                runCatching { player.stopTransmit(callAudio) }
            } finally {
                runCatching { player.delete() }
            }
        }
    }

    suspend fun recordWav(file: File, durationMs: Long): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            check(!closed.get()) { "Chamada encerrada." }
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            val recorder = AudioMediaRecorder()
            try {
                recorder.createRecorder(file.absolutePath, 0, 0, 0)
                callAudio.startTransmit(recorder)
                delay(durationMs.coerceIn(1_000L, 15_000L))
                runCatching { callAudio.stopTransmit(recorder) }
            } finally {
                runCatching { recorder.delete() }
            }
            require(file.isFile && file.length() > 44) { "Nenhum áudio útil foi gravado." }
            file
        }
    }

    suspend fun handoffToUser(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(!closed.get()) { "Chamada encerrada." }
            if (!handedOff.compareAndSet(false, true)) return@runCatching

            val audio = endpoint.audDevManager()
            val devices = audio.enumDev2()
            val capture = devices.firstOrNull { it.inputCount > 0 }?.id
                ?: error("Nenhum microfone disponível.")
            val playback = devices.firstOrNull { it.outputCount > 0 }?.id
                ?: error("Nenhuma saída de áudio disponível.")
            audio.setCaptureDev(capture)
            audio.setPlaybackDev(playback)
            audio.captureDevMedia.startTransmit(callAudio)
            callAudio.startTransmit(audio.playbackDevMedia)
        }
    }

    suspend fun terminate(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!closed.compareAndSet(false, true)) return@runCatching
            val prm = CallOpParam(true).apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }
            call.hangup(prm)
        }
    }

    fun closeWithoutDecision() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            val prm = CallOpParam(true).apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }
            call.hangup(prm)
        }
    }

    fun recordHistory(
        decision: CallDecision,
        category: CallCategory,
        label: String,
        transcript: String?,
        summary: String?,
        callerName: String? = null,
        confidence: Float? = null
    ) {
        CallRepository.upsert(
            CallRecord(
                id = System.currentTimeMillis(),
                phoneNumber = caller,
                label = label,
                decision = decision,
                category = category,
                transcript = transcript,
                summary = summary,
                callerName = callerName,
                confidence = confidence,
                isDemo = false
            )
        )
    }
}
