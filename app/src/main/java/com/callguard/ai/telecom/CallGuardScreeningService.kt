package com.callguard.ai.telecom

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
 * Android requires the incoming-call response within five seconds. FunctionGemma
 * is deliberately kept out of this callback. Unknown calls are not blocked until
 * a deterministic rule or a supported audio-assisted triage path can justify it.
 */
class CallGuardScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return

        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val policy = FastScreeningPolicy.evaluate(
            number = number,
            verificationFailed = callDetails.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
        )

        // Respond first; persistence below must never consume the platform timeout.
        val response = CallResponse.Builder()
            .setDisallowCall(policy.block)
            .setRejectCall(policy.block)
            .setSilenceCall(false)
            .setSkipNotification(false)
            .setSkipCallLog(false)
            .build()
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
        CallRepository.add(record)
    }
}
