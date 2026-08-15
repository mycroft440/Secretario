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
 * The platform requires a response within 5 seconds. FunctionGemma is therefore
 * deliberately kept OUT of this callback. The AI consumes a transcript later,
 * once a supported audio bridge exists (OEM/privileged or VoIP/SIP path).
 */
class CallGuardScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return

        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val policy = FastScreeningPolicy.evaluate(
            number = number,
            verificationFailed = callDetails.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
        )

        // Important: a public CallScreeningService cannot silence now and later
        // re-enable ringing for the SAME call. Therefore non-blocked calls are
        // allowed to ring normally until the future audio bridge is implemented.
        val response = CallResponse.Builder()
            .setDisallowCall(policy.block)
            .setRejectCall(policy.block)
            .setSilenceCall(false)
            .setSkipNotification(false)
            .setSkipCallLog(false)
            .build()

        respondToCall(callDetails, response)

        val record = if (policy.block) {
            CallRecord(
                id = System.currentTimeMillis(),
                phoneNumber = number.ifBlank { "Número oculto" },
                label = policy.reason,
                decision = CallDecision.BLOCKED,
                category = when (policy.reason) {
                    "operadora" -> CallCategory.OPERATOR
                    "robô/ligação muda" -> CallCategory.ROBOT_OR_SILENT
                    else -> CallCategory.UNKNOWN
                },
                summary = "Bloqueio local realizado antes do toque."
            )
        } else {
            CallRecord(
                id = System.currentTimeMillis(),
                phoneNumber = number.ifBlank { "Número oculto" },
                label = "número desconhecido",
                decision = CallDecision.PENDING,
                category = CallCategory.UNKNOWN,
                summary = "Aguardando uma ponte de áudio compatível para triagem silenciosa por IA."
            )
        }
        CallRepository.add(record)
    }
}
