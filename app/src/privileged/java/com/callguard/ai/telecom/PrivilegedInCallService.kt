package com.callguard.ai.telecom

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import java.util.IdentityHashMap

/**
 * InCallService for the OEM/system distribution.
 *
 * This is intentionally absent from the public flavor. The service makes the
 * privileged build eligible to act as the default dialer and exposes live Call
 * objects to the process-local audio bridge.
 */
class PrivilegedInCallService : InCallService() {
    private val callbacks = IdentityHashMap<Call, Call.Callback>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val token = PrivilegedCallRegistry.register(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(changedCall: Call, state: Int) {
                PrivilegedCallRegistry.update(changedCall)
                if (state == Call.STATE_DISCONNECTED) {
                    PrivilegedCallRegistry.unregister(changedCall)
                }
            }

            override fun onDetailsChanged(changedCall: Call, details: Call.Details) {
                PrivilegedCallRegistry.update(changedCall)
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback, mainHandler)

        if (call.state == Call.STATE_RINGING) {
            showCallUi(token)
        }
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let(call::unregisterCallback)
        PrivilegedCallRegistry.unregister(call)
        super.onCallRemoved(call)
    }

    private fun showCallUi(token: String) {
        startActivity(
            Intent(this, PrivilegedDialerActivity::class.java)
                .putExtra(PrivilegedDialerActivity.EXTRA_CALL_TOKEN, token)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
        )
    }
}
