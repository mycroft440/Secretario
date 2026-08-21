package com.callguard.ai.audio

import kotlinx.coroutines.flow.Flow

/** PCM mono frame exchanged with a future supported telephony audio path. */
data class PcmFrame(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val timestampMs: Long
)

/**
 * Boundary for the carrier/VoIP audio transport.
 *
 * The AI pipeline depends on this contract, not on hidden Android APIs. A bridge
 * is only considered usable when it can provide inbound PCM, play outbound PCM
 * and either connect an approved caller to the user or terminate the call.
 */
interface CarrierAudioBridge {
    val availability: Availability

    enum class Availability {
        PUBLIC_ANDROID_UNAVAILABLE,
        OEM_PRIVILEGED_AVAILABLE,
        VOIP_OR_SIP_AVAILABLE
    }

    suspend fun open(callId: String): Result<Session>

    interface Session : AutoCloseable {
        val inboundAudio: Flow<PcmFrame>

        suspend fun playToCaller(frame: PcmFrame): Result<Unit>

        /** Transfer/present an approved call to the user using the bridge's supported route. */
        suspend fun connectToUser(): Result<Unit>

        suspend fun terminate(): Result<Unit>

        override fun close()
    }
}

/**
 * Public Android deliberately fails closed at the capability boundary. Public
 * CallScreeningService does not expose a bidirectional carrier-call PCM stream.
 */
class PublicAndroidCarrierAudioBridge : CarrierAudioBridge {
    override val availability = CarrierAudioBridge.Availability.PUBLIC_ANDROID_UNAVAILABLE

    override suspend fun open(callId: String): Result<CarrierAudioBridge.Session> = Result.failure(
        UnsupportedOperationException(
            "A API pública do Android não fornece o áudio bidirecional da chamada da operadora."
        )
    )
}
