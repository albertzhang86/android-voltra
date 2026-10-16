package io.github.albertzhang86.voltra

import kotlinx.coroutines.flow.Flow

/** Optional trusted bridge extension. Offers advertise a peer; only explicit requests may pair. */
interface KnobTwinBridge {
    val twinRequests: Flow<KnobTwinRequest>

    suspend fun offerTwinPeer(peer: VoltraDevice?, ready: Boolean = true)

    suspend fun reportTwinRequest(request: KnobTwinRequest, status: KnobTwinRequestStatus)
}

data class KnobTwinRequest(val epoch: Int, val session: Int, val id: Int, val peerId: String)

enum class KnobTwinRequestStatus(val wireValue: Int) {
    ACCEPTED(1),
    SUCCEEDED(2),
    FAILED(3),
}
