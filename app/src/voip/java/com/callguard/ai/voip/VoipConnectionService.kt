package com.callguard.ai.voip

import android.net.Uri
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object VoipTelecomConnectionRegistry {
    private val connections = ConcurrentHashMap<String, VoipTelecomConnection>()

    fun put(token: String, connection: VoipTelecomConnection) {
        connections[token] = connection
    }

    fun remove(token: String) {
        connections.remove(token)
    }

    fun answer(token: String): Boolean = connections[token]?.answerFromUi() == true
    fun reject(token: String): Boolean = connections[token]?.rejectFromUi() == true
}

class VoipConnectionService : ConnectionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ): Connection {
        val token = request.extras?.getString(VoipPhoneAccountManager.EXTRA_SESSION_TOKEN)
        val summary = request.extras?.getString(VoipPhoneAccountManager.EXTRA_AI_SUMMARY).orEmpty()
        val session = token?.let(UniversalSipRuntime::session)
        if (token.isNullOrBlank() || session == null) {
            return Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.ERROR, "Sessão SIP não encontrada")
            )
        }

        return VoipTelecomConnection(
            token = token,
            caller = session.caller,
            summary = summary,
            scope = scope
        ).also { connection ->
            VoipTelecomConnectionRegistry.put(token, connection)
            connection.connectionProperties = Connection.PROPERTY_SELF_MANAGED
            @Suppress("DEPRECATION")
            connection.setAudioModeIsVoip(true)
            val digits = session.caller.filter { it.isDigit() || it == '+' }
            connection.setAddress(
                Uri.fromParts("tel", digits.ifBlank { "unknown" }, null),
                TelecomManager.PRESENTATION_ALLOWED
            )
            connection.setCallerDisplayName(
                summary.takeIf { it.isNotBlank() } ?: session.caller,
                TelecomManager.PRESENTATION_ALLOWED
            )
            connection.setRinging()
            VoipIncomingCallNotifier.show(this, token, session.caller, summary)
        }
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ) {
        val token = request.extras?.getString(VoipPhoneAccountManager.EXTRA_SESSION_TOKEN) ?: return
        scope.launch { UniversalSipRuntime.reject(token) }
        VoipIncomingCallNotifier.cancel(this, token)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class VoipTelecomConnection internal constructor(
    private val token: String,
    private val caller: String,
    private val summary: String,
    private val scope: CoroutineScope
) : Connection() {
    @Volatile
    private var terminal = false

    override fun onAnswer() {
        answerFromUi()
    }

    override fun onReject() {
        rejectFromUi()
    }

    override fun onDisconnect() {
        rejectFromUi()
    }

    fun answerFromUi(): Boolean {
        if (terminal) return false
        terminal = true
        VoipIncomingCallNotifier.cancel(applicationContext(), token)
        scope.launch {
            UniversalSipRuntime.handoffToUser(token)
                .onSuccess { setActive() }
                .onFailure {
                    setDisconnected(DisconnectCause(DisconnectCause.ERROR, it.message))
                    destroySafely()
                }
        }
        return true
    }

    fun rejectFromUi(): Boolean {
        if (terminal) return false
        terminal = true
        VoipIncomingCallNotifier.cancel(applicationContext(), token)
        scope.launch {
            UniversalSipRuntime.reject(token)
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroySafely()
        }
        return true
    }

    override fun onAbort() {
        rejectFromUi()
    }

    private fun destroySafely() {
        VoipTelecomConnectionRegistry.remove(token)
        destroy()
    }

    private fun applicationContext() = VoipAppContext.require()
}

/** Process-local context holder used by Connection callbacks, initialized by the VoIP service. */
object VoipAppContext {
    @Volatile private var context: android.content.Context? = null
    fun initialize(context: android.content.Context) { this.context = context.applicationContext }
    fun require(): android.content.Context = requireNotNull(context) { "VoIP context not initialized" }
}
