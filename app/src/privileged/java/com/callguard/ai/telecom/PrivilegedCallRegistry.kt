package com.callguard.ai.telecom

import android.telecom.Call
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local registry owned by the privileged InCallService.
 * Tokens are opaque and never persisted; they only join a Telecom Call to the
 * privileged audio bridge for the lifetime of the current process/session.
 */
object PrivilegedCallRegistry {
    data class Snapshot(
        val token: String,
        val number: String?,
        val state: Int
    )

    private val lock = Any()
    private val callsByToken = LinkedHashMap<String, Call>()
    private val tokensByCall = IdentityHashMap<Call, String>()
    private val _calls = MutableStateFlow<List<Snapshot>>(emptyList())

    val calls: StateFlow<List<Snapshot>> = _calls.asStateFlow()

    fun register(call: Call): String = synchronized(lock) {
        tokensByCall[call]?.let { return@synchronized it }
        val token = UUID.randomUUID().toString()
        tokensByCall[call] = token
        callsByToken[token] = call
        publishLocked()
        token
    }

    fun update(call: Call) = synchronized(lock) {
        if (tokensByCall.containsKey(call)) publishLocked()
    }

    fun unregister(call: Call) = synchronized(lock) {
        val token = tokensByCall.remove(call) ?: return@synchronized
        callsByToken.remove(token)
        publishLocked()
    }

    fun get(token: String): Call? = synchronized(lock) { callsByToken[token] }

    private fun publishLocked() {
        _calls.value = callsByToken.map { (token, call) ->
            Snapshot(
                token = token,
                number = call.details.handle?.schemeSpecificPart,
                state = call.state
            )
        }
    }
}
