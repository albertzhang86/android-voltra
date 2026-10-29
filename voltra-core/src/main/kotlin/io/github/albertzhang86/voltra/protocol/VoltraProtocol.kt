package io.github.albertzhang86.voltra.protocol

import io.github.albertzhang86.voltra.Parameter
import io.github.albertzhang86.voltra.MotorState

/** Protocol references and their pinned revisions are listed in THIRD_PARTY.md. */
object VoltraProtocol {
    const val MODE = 0x4fb0
    const val FITNESS = 0x3e89
    const val BATTERY = 0x4e2d
    const val WEIGHT_UNIT = 0x506e
    const val LENGTH_UNIT = 0x506f
    /** Device-enforced current allowed force, not an Overdrive enable setting. Read only. */
    const val MAX_ALLOWED_FORCE = 0x5314
    /** Signed 16-bit runtime wire weight in whole pounds, separate from the base-weight setting. */
    const val RUNTIME_WIRE_WEIGHT = 0x3e83
    const val CABLE_POSITION = 0x3e82
    const val CABLE_OFFSET = 0x506a
    /** APP_CUR_SCR_ID, a one-byte enum included with the trainer's cable-position replies. */
    const val SCREEN = 0x5011
    // Existing read-only mode catalogue. These controls are not exposed as writable settings.
    internal val bandReadOnlySettings = setOf(0x53b7, 0x53b6, 0x5361, 0x52e3)
    internal val damperReadOnlySettings = setOf(0x5106)
    const val TWIN_STATUS = 0x516c
    const val ENDPOINT_STATUS = 0xa7
    const val TWIN_COMMAND = 0xa8
    val initialFrames: List<ByteArray>
        get() =
            listOf(
                hex("550f0801aad200002000ff00aa0419"),
                hex("551f044eaa100000200027811065f8c6b9179047b797d3351c04bffb925cd6"),
            )

    fun identity(clientName: String = "VOLTRA Client"): ByteArray {
        require(clientName.length in 1..21 && clientName.all { it.code in 32..126 }) {
            "Client name must be 1–21 ASCII characters."
        }
        val bytes =
            hex(
                "552904c90110000020004f6950686f6e6500000000000000000000000000000084ab1a5f29200172d8"
            )
        bytes.fill(0, 11, 32)
        clientName.toByteArray(Charsets.US_ASCII).copyInto(bytes, 11)
        return seal(bytes)
    }

    fun read(vararg registers: Int): ByteArray {
        require(registers.size in 1..8)
        return envelope(
            0x0f,
            byteArrayOf(registers.size.toByte(), 0) +
                registers.flatMap { listOf(it.toByte(), (it shr 8).toByte()) },
        )
    }

    fun write(register: Int, value: Int, width: Int): ByteArray {
        require(width in listOf(1, 2, 4))
        return envelope(
            0x11,
            byteArrayOf(1, 0, register.toByte(), (register shr 8).toByte()) +
                ByteArray(width) { (value ushr (8 * it)).toByte() },
        )
    }

    fun subscribe() = hex("551504a9aa10012020001101008351ffffffff35eb")

    // Existing trainer load sequence; setup reads both cable registers and never changes offset.
    fun motorSetup() = hex("55130403aa10150020000f02006a50823e8f2f")

    fun motorLoad() = hex("55130403aa1016002000110100893e05008173")

    fun motorUnload() = hex("55130403aa101b002000110100893e040037dd")

    internal fun motorCommand(reportedState: Int?, load: Boolean): ByteArray =
        when (val value = requireNotNull(MotorState.commandValue(reportedState, load)) {
            "Unrecognized VOLTRA motor state."
        }) {
            5 -> motorLoad()
            4 -> motorUnload()
            else -> write(FITNESS, value, 2)
        }

    fun endpointStatus() = envelope(ENDPOINT_STATUS, byteArrayOf())

    /** Pair is sent to the future peer; exit is sent to the connected controller. */
    internal fun twinCommand(operation: Int, address: String, sequence: Int): ByteArray {
        require(operation in 1..2)
        require(address.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")))
        require(address != EndpointStatus.ZERO_ADDRESS)
        return envelope(
                TWIN_COMMAND,
                byteArrayOf(operation.toByte()) + address.split(':').map { it.toInt(16).toByte() },
            )
            .also {
                it[6] = sequence.toByte()
                it[7] = (sequence ushr 8).toByte()
                seal(it)
            }
    }

    private fun envelope(command: Int, payload: ByteArray): ByteArray =
        seal(
            byteArrayOf(0x55, 0, 4, 0, 0xaa.toByte(), 0x10, 0, 0x20, 0x20, 0, command.toByte()) +
                payload +
                byteArrayOf(0, 0)
        )

    fun seal(bytes: ByteArray): ByteArray {
        bytes[1] = bytes.size.toByte()
        bytes[2] = ((bytes[2].toInt() and 0xfc) or (bytes.size shr 8)).toByte()
        bytes[3] = checksum(bytes, 3, 0x77, 0x8c).toByte()
        val crc = checksum(bytes, bytes.size - 2, 0x3692, 0x8408)
        bytes[bytes.size - 2] = crc.toByte()
        bytes[bytes.size - 1] = (crc shr 8).toByte()
        return bytes
    }

    fun valid(bytes: ByteArray): Boolean =
        bytes.size >= 13 &&
            bytes[0] == 0x55.toByte() &&
            length(bytes) == bytes.size &&
            headerValid(bytes) &&
            checksum(bytes, bytes.size - 2, 0x3692, 0x8408) == unsigned(bytes, bytes.size - 2, 2)

    fun length(bytes: ByteArray) = (bytes[1].toInt() and 255) + ((bytes[2].toInt() and 3) shl 8)

    fun headerValid(bytes: ByteArray) = checksum(bytes, 3, 0x77, 0x8c) == (bytes[3].toInt() and 255)

    private fun checksum(bytes: ByteArray, size: Int, seed: Int, polynomial: Int): Int {
        var crc = seed
        repeat(size) { index ->
            crc = crc xor (bytes[index].toInt() and 255)
            repeat(8) { crc = (crc ushr 1) xor if (crc and 1 == 1) polynomial else 0 }
        }
        return crc
    }

    internal fun unsigned(bytes: ByteArray, offset: Int, width: Int): Int =
        (0 until width).fold(0) { value, index ->
            value or ((bytes[offset + index].toInt() and 255) shl (8 * index))
        }

    fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

data class ProtocolUpdate(
    val registers: Map<Int, Int> = emptyMap(),
    /** Legacy name for measured force. Prefer cableForceLb; this is not active resistance. */
    val activeWeightLb: Double? = null,
    val deviceSet: Int? = null,
    val rep: Int? = null,
    val endpoint: EndpointStatus? = null,
    internal val twinReply: TwinCommandReply? = null,
    /** Runtime register readback, not a sample from the high-rate cable-force stream. */
    val runtimeWeightLb: Double? = null,
    val resistanceTargetLb: Double? = null,
    /** True for B4, including packets where the optional target is absent or invalid. */
    val resistanceTargetReported: Boolean = false,
    /** Independent B4 motion from the same notification as cableForceLb. */
    val cableVelocityMmPerSecond: Int? = null,
    val cableLengthMm: Int? = null,
) {
    val cableForceLb: Double?
        get() = activeWeightLb
}

object VoltraDecoder {
    fun decode(frame: ByteArray): ProtocolUpdate? {
        if (!VoltraProtocol.valid(frame)) return null
        fun u(offset: Int, width: Int = 1) = VoltraProtocol.unsigned(frame, offset, width)
        if (u(10) == VoltraProtocol.ENDPOINT_STATUS)
            return EndpointStatus.decode(frame)?.let { ProtocolUpdate(endpoint = it) }
        if (u(10) == VoltraProtocol.TWIN_COMMAND) {
            if (frame.size != 16 || u(2) and 0xfc != 8 || u(11) != 0) return null
            return ProtocolUpdate(twinReply = TwinCommandReply(u(6, 2), u(12), u(13)))
        }
        if (u(10) == 0xb4) {
            // Terse runtime: the first word is measured force in tenths of pounds. The
            // optional trailing word is TARGET resistance, separate from measured tension.
            // Native Twin sources 0x10/0x11/0x12 have additional, unclassified fields.
            if (u(2) != 4 || u(9) != 0) return null
            val payloadSize = frame.size - 13
            val knownLayout =
                when (u(8)) {
                    0 -> payloadSize == 6 || payloadSize == 8
                    1,
                    2 -> payloadSize == 10 || payloadSize == 12
                    else -> false
                }
            if (!knownLayout) return null
            val required = if (u(8) == 0) 6 else 10
            val target = if (payloadSize == required + 2) u(11 + required, 2) else null
            return ProtocolUpdate(
                activeWeightLb = u(11, 2) / 10.0,
                resistanceTargetLb = target?.takeIf { it in 0..10000 }?.div(10.0),
                resistanceTargetReported = true,
                cableVelocityMmPerSecond = u(15, 2).toShort().toInt(),
                cableLengthMm = u(13, 2),
            )
        }
        if (frame.size == 58 && u(2) == 4 && u(10) == 0xaa && u(11) == 0x81 && u(12) == 0x2b) {
            return ProtocolUpdate(activeWeightLb = u(22, 2) / 10.0)
        }
        if (u(10) == 0xaa) {
            // Hardware reports pull indices from zero, but return counts from one:
            // pull 0 / return 1 describe the first rep. Normalize only the pull phase;
            // the controller deduplicates counts instead of counting notifications.
            if (frame.size == 74 && u(11) == 0x82 && u(12) == 0x3b && u(13) in 1..2) {
                return ProtocolUpdate(
                    deviceSet = u(15, 2),
                    rep = u(17, 2) + if (u(13) == 1) 1 else 0,
                )
            }
            return null // Other vendor packets can share lengths with battery messages.
        }
        if (u(10) == 0x0f && u(2) in listOf(8, 9) && frame.size >= 18 && u(11) == 0) {
            return parameters(frame, u(12, 2), 14)
        }
        if (u(10) == 0x10 && frame.size >= 16) return parameters(frame, u(11), 13)
        // Battery is read only from register 0x4e2d; legacy status-frame offsets are not battery.
        return null
    }

    private fun parameters(frame: ByteArray, count: Int, start: Int): ProtocolUpdate? {
        if (count !in 1..32) return null
        var offset = start
        val values = mutableMapOf<Int, Int>()
        repeat(count) {
            if (offset + 2 > frame.size - 2) return null
            val id = VoltraProtocol.unsigned(frame, offset, 2)
            offset += 2
            val parameter = Parameter.entries.find { it.register == id }
            val readOnlyModeField =
                id in VoltraProtocol.bandReadOnlySettings ||
                    id in VoltraProtocol.damperReadOnlySettings
            // The native catalogue uses isolated replies; do not guess widths in a mixed record.
            if (readOnlyModeField && count != 1) return null
            val expected =
                (if (readOnlyModeField) 2 else null)
                    ?: parameter?.width
                    ?: when (id) {
                        VoltraProtocol.MAX_ALLOWED_FORCE,
                        VoltraProtocol.FITNESS,
                        VoltraProtocol.CABLE_POSITION,
                        VoltraProtocol.CABLE_OFFSET,
                        VoltraProtocol.RUNTIME_WIRE_WEIGHT -> 2
                        VoltraProtocol.WEIGHT_UNIT, VoltraProtocol.LENGTH_UNIT -> 4
                        VoltraProtocol.MODE,
                        VoltraProtocol.SCREEN,
                        VoltraProtocol.BATTERY,
                        VoltraProtocol.TWIN_STATUS -> 1
                        // Known one-byte widths, unknown semantics. Firmware 2.0 includes
                        // these in the settings cascade before/after MODE. Never write them.
                        0x5102, 0x4ee1, 0x5124 -> 1
                        else -> return null
                    }
            // Isolated replies carry their actual width; firmware revisions vary for enum
            // registers.
            val width = if (count == 1) frame.size - 2 - offset else expected
            if (width !in listOf(1, 2, 4) || offset + width > frame.size - 2) return null
            if (
                id in
                    listOf(
                        VoltraProtocol.MAX_ALLOWED_FORCE,
                        VoltraProtocol.RUNTIME_WIRE_WEIGHT,
                        VoltraProtocol.CABLE_POSITION,
                        VoltraProtocol.CABLE_OFFSET,
                    ) && width != 2
            )
                return null
            val value = VoltraProtocol.unsigned(frame, offset, width)
            values[id] =
                if (
                    id == Parameter.ECCENTRIC.register ||
                        id == VoltraProtocol.RUNTIME_WIRE_WEIGHT ||
                        id == VoltraProtocol.CABLE_POSITION
                )
                    value.toShort().toInt()
                else value
            offset += width
        }
        if (offset != frame.size - 2) return null
        return ProtocolUpdate(
            values,
            runtimeWeightLb = values[VoltraProtocol.RUNTIME_WIRE_WEIGHT]?.toDouble(),
        )
    }
}

/** Per-connection reassembly; damaged/partial packets never become settings or motor state. */
class FrameAssembler {
    private var pending = byteArrayOf()

    fun accept(chunk: ByteArray): List<ByteArray> {
        if (chunk.size > 4096) {
            pending = byteArrayOf()
            return emptyList()
        }
        pending += chunk
        val frames = mutableListOf<ByteArray>()
        while (pending.size >= 4) {
            if (pending[0] != 0x55.toByte() || !VoltraProtocol.headerValid(pending)) {
                pending = pending.drop(1).toByteArray()
                continue
            }
            val length = VoltraProtocol.length(pending)
            if (length !in 13..1023) {
                pending = pending.drop(1).toByteArray()
                continue
            }
            if (pending.size < length) break
            val frame = pending.copyOfRange(0, length)
            if (VoltraProtocol.valid(frame)) {
                frames.add(frame)
                pending = pending.drop(length).toByteArray()
            } else pending = pending.drop(1).toByteArray()
        }
        return frames
    }
}
