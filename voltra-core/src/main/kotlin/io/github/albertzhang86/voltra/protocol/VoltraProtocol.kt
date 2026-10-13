package io.github.albertzhang86.voltra.protocol

import io.github.albertzhang86.voltra.Parameter

/** MIT protocol references and their pinned revisions are listed in THIRD_PARTY.md. */
object VoltraProtocol {
    const val MODE = 0x4fb0
    const val FITNESS = 0x3e89
    const val BATTERY = 0x4e2d
    const val TWIN_STATUS = 0x516c
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
    val activeWeightLb: Double? = null,
    val deviceSet: Int? = null,
    val rep: Int? = null,
)

object VoltraDecoder {
    fun decode(frame: ByteArray): ProtocolUpdate? {
        if (!VoltraProtocol.valid(frame)) return null
        fun u(offset: Int, width: Int = 1) = VoltraProtocol.unsigned(frame, offset, width)
        if (frame.size == 58 && u(2) == 4 && u(10) != 0x10) {
            return ProtocolUpdate(activeWeightLb = u(22, 2) / 10.0)
        }
        if (u(10) == 0xaa) {
            if (frame.size == 74 && u(11) == 0x82 && u(12) == 0x3b && u(13) == 1) {
                return ProtocolUpdate(deviceSet = u(15, 2), rep = u(17, 2))
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
            val expected =
                parameter?.width
                    ?: when (id) {
                        VoltraProtocol.FITNESS -> 2
                        VoltraProtocol.MODE,
                        VoltraProtocol.BATTERY,
                        VoltraProtocol.TWIN_STATUS -> 1
                        else -> return null
                    }
            // Isolated replies carry their actual width; firmware revisions vary for enum
            // registers.
            val width = if (count == 1) frame.size - 2 - offset else expected
            if (width !in listOf(1, 2, 4) || offset + width > frame.size - 2) return null
            val value = VoltraProtocol.unsigned(frame, offset, width)
            values[id] = if (id == Parameter.ECCENTRIC.register) value.toShort().toInt() else value
            offset += width
        }
        if (offset != frame.size - 2) return null
        return ProtocolUpdate(values)
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
