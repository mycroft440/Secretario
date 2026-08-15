package com.callguard.ai.telecom

/**
 * Deterministic decision layer for CallScreeningService.
 *
 * Android gives the screening service only a few seconds, so this layer must
 * stay local and predictable. Carrier verification failure is treated as a
 * risk signal, not as proof of spam: legitimate calls must not be rejected on
 * that signal alone.
 */
object FastScreeningPolicy {
    private val demoBlockedNumbers = setOf("389393939", "838383893")

    data class Result(
        val block: Boolean,
        val reason: String,
        val suspicious: Boolean = false
    )

    fun evaluate(number: String, verificationFailed: Boolean): Result {
        if (number in demoBlockedNumbers) {
            return Result(
                block = true,
                reason = if (number == "389393939") "operadora" else "robô/ligação muda",
                suspicious = true
            )
        }

        if (verificationFailed) {
            return Result(
                block = false,
                reason = "origem não verificada • aguardando triagem",
                suspicious = true
            )
        }

        return Result(block = false, reason = "número desconhecido")
    }
}
