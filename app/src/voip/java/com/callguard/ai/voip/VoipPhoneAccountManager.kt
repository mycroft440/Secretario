package com.callguard.ai.voip

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

object VoipPhoneAccountManager {
    const val EXTRA_SESSION_TOKEN = "com.callguard.ai.voip.SESSION_TOKEN"
    const val EXTRA_AI_SUMMARY = "com.callguard.ai.voip.AI_SUMMARY"
    private const val ACCOUNT_ID = "callguard-universal-voip-v1"

    fun handle(context: Context): PhoneAccountHandle = PhoneAccountHandle(
        ComponentName(context, VoipConnectionService::class.java),
        ACCOUNT_ID
    )

    @Suppress("DEPRECATION")
    fun register(context: Context): Result<PhoneAccountHandle> = runCatching {
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: error("TelecomManager indisponível.")
        val handle = handle(context)
        val account = PhoneAccount.builder(handle, "CallGuard AI — Universal")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_SIP))
            .build()
        telecom.registerPhoneAccount(account)
        handle
    }

    @Suppress("DEPRECATION")
    fun presentIncomingCall(
        context: Context,
        token: String,
        caller: String,
        summary: String
    ): Result<Unit> = runCatching {
        require(token.length <= 100) { "Token de chamada inválido." }
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: error("TelecomManager indisponível.")
        val handle = register(context).getOrThrow()
        val extras = Bundle().apply {
            putString(EXTRA_SESSION_TOKEN, token)
            putString(EXTRA_AI_SUMMARY, summary.take(500))
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts("tel", caller.filter { it.isDigit() || it == '+' }.take(40), null)
            )
        }
        telecom.addNewIncomingCall(handle, extras)
    }
}
