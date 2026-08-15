package com.callguard.ai.audio

/**
 * Boundary for the future carrier-call audio bridge.
 *
 * Android's public CallScreeningService can allow, silence, or reject the call,
 * but it does not expose the bidirectional carrier audio stream to an ordinary app.
 * Keeping this interface separate prevents the UI/AI code from pretending that
 * the missing platform capability already exists.
 */
interface CarrierAudioBridge {
    val availability: Availability

    enum class Availability {
        PUBLIC_ANDROID_UNAVAILABLE,
        OEM_PRIVILEGED_AVAILABLE,
        VOIP_OR_SIP_AVAILABLE
    }

    suspend fun start(callId: String): Result<Unit>
    suspend fun stop(callId: String)
}

class PublicAndroidCarrierAudioBridge : CarrierAudioBridge {
    override val availability = CarrierAudioBridge.Availability.PUBLIC_ANDROID_UNAVAILABLE

    override suspend fun start(callId: String): Result<Unit> = Result.failure(
        UnsupportedOperationException(
            "A API pública do Android não fornece o áudio bidirecional da chamada da operadora."
        )
    )

    override suspend fun stop(callId: String) = Unit
}
