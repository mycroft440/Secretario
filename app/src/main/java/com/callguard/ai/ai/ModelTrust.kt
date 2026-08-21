package com.callguard.ai.ai

/**
 * Trust boundary for production model releases.
 *
 * The list intentionally starts empty: no fine-tuned CallGuard model has been
 * released and evaluated yet. Adding a hash here requires the release to pass
 * the locked evaluation gates documented in training/README.md.
 */
object ModelTrust {
    private val trustedSha256 = emptySet<String>()

    fun isTrusted(sha256: String): Boolean = sha256.lowercase() in trustedSha256
}
