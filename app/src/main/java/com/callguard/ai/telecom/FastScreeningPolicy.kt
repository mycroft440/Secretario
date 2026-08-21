package com.callguard.ai.telecom

/**
 * Deterministic, synchronous decision layer for CallScreeningService.
 *
 * Demo data must never become a production blocking rule. Until the app has a
 * supported audio path or an explicit user blocklist, unknown calls are allowed.
 * Carrier verification failure is only a risk signal, never proof of spam.
 */
object FastScreeningPolicy {
    data class Result(
        val block: Boolean,
        val reason: String,
        val suspicious: Boolean = false
    )

    fun evaluate(number: String, verificationFailed: Boolean): Result {
        if (verificationFailed) {
            return Result(
                block = false,
                reason = "origem não verificada • aguardando triagem",
                suspicious = true
            )
        }

        return Result(
            block = false,
            reason = if (number.isBlank()) "número indisponível" else "número desconhecido"
        )
    }
}
