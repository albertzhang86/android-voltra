package io.github.albertzhang86.voltra.protocol

/** Live endpoint identities and batteries, reported by the connected trainer (command A7). */
data class EndpointStatus(
    val localAddress: String,
    val peerAddress: String,
    val localBatteryPercent: Int?,
    val peerBatteryPercent: Int?,
    val pairingStatus: Int,
) {
    val independent: Boolean
        get() = pairingStatus == 0 && peerAddress == ZERO_ADDRESS

    /** 0x12 was verified against an unloaded native pair/exit cycle and Cortex's Twin status. */
    val paired: Boolean
        get() = pairingStatus == 0x12 && peerAddress != ZERO_ADDRESS && peerAddress != localAddress

    val topologyKnown: Boolean
        get() = independent || paired

    companion object {
        const val ZERO_ADDRESS = "00:00:00:00:00:00"

        internal fun decode(frame: ByteArray): EndpointStatus? {
            val payload =
                when {
                    frame.size == 36 && frame[2].toInt() and 0xfc == 8 && frame[11] == 0.toByte() ->
                        12
                    frame.size == 35 && frame[2].toInt() and 0xfc == 4 -> 11
                    else -> return null
                }
            fun unsigned(index: Int) = frame[index].toInt() and 255
            fun address(index: Int) =
                (index until index + 6).joinToString(":") {
                    unsigned(it).toString(16).padStart(2, '0').uppercase()
                }
            val local = address(payload + 3)
            if (local == ZERO_ADDRESS) return null
            return EndpointStatus(
                local,
                address(payload + 9),
                unsigned(payload).takeIf { it <= 100 },
                unsigned(payload + 15).takeIf { it <= 100 },
                unsigned(payload + 2),
            )
        }
    }
}

data class TwinCommandReply(val sequence: Int, val operation: Int, val result: Int)
