package com.callguard.ai.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import com.callguard.ai.data.CallCategory
import com.callguard.ai.data.CallDecision
import com.callguard.ai.data.CallRecord
import com.callguard.ai.data.CallRepository

/**
 * Public Android call-screening hook.
 *
 * Android requires the incoming-call response within five seconds. FunctionGemma,
 * Keystore access and history I/O are deliberately kept out of the critical path.
 */
class CallGuardScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return

        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val verificationFailed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            callDetails.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
        } else {
            // Caller-number verification status was added in API 30. Android 10
            // callers are treated as having no verification signal, never as spam.
            false
        }
        val policy = FastScreeningPolicy.evaluate(
            number = number,
            verificationFailed = verificationFailed
        )

        val response = CallResponse.Builder()
            .setDisallowCall(policy.block)
            .setRejectCall(policy.block)
            .setSilenceCall(false)
            .setSkipNotification(false)
            .setSkipCallLog(false)
            .build()

        // Platform response is always first. Everything below is best-effort history.
        respondToCall(callDetails, response)

        val record = CallRecord(
            id = callDetails.creationTimeMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            phoneNumber = number.ifBlank { "Número indisponível" },
            label = policy.reason,
            decision = if (policy.block) CallDecision.BLOCKED else CallDecision.PENDING,
            category = CallCategory.UNKNOWN,
            summary = if (policy.block) {
                "Bloqueio local determinístico realizado antes do toque."
            } else if (policy.suspicious) {
                "Sinal de risco da operadora registrado; a ligação não foi bloqueada sem evidência suficiente."
            } else {
                "Ligação desconhecida permitida: a triagem silenciosa completa ainda não possui ponte de áudio suportada."
            }
        )
        CallRepository.addAsync(applicationContext, record)
    }
}
