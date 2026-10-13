package io.github.albertzhang86.voltra

import io.github.albertzhang86.voltra.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test
    fun `commands match public knob reference frames byte for byte`() {
        assertArrayEquals(
            VoltraProtocol.hex("55130403aa1000202000110100b04f0100f353"),
            VoltraProtocol.write(VoltraProtocol.MODE, 1, 2),
        )
        assertArrayEquals(
            VoltraProtocol.hex("55130403aa1000202000110100b04f07002307"),
            VoltraProtocol.write(VoltraProtocol.MODE, 7, 2),
        )
        assertArrayEquals(
            VoltraProtocol.hex("55110492aa10002020000f01006c51a9b7"),
            VoltraProtocol.read(VoltraProtocol.TWIN_STATUS),
        )
        assertArrayEquals(
            VoltraProtocol.hex("551504a9aa10012020001101008351ffffffff35eb"),
            VoltraProtocol.subscribe(),
        )
    }

    @Test
    fun `captured initialization frames have valid checksums`() {
        val captured = VoltraProtocol.hex("550f0801aad200002000ff00aa0419")
        assertTrue(VoltraProtocol.valid(captured))
        captured[10] = 0
        assertFalse(VoltraProtocol.valid(captured))
    }

    @Test
    fun `assembler handles noise fragments and multiple frames`() {
        val assembler = FrameAssembler()
        val first = report(VoltraProtocol.MODE, 1, 1)
        val second = report(Parameter.ECCENTRIC.register, -15, 2)
        assertTrue(assembler.accept(byteArrayOf(0, 8) + first.take(6)).isEmpty())
        val frames = assembler.accept(first.drop(6).toByteArray() + second)
        assertEquals(2, frames.size)
        assertArrayEquals(first, frames[0])
        assertArrayEquals(second, frames[1])
    }

    @Test
    fun `damaged packet cannot update state but following packet survives`() {
        val corrupt = report(VoltraProtocol.BATTERY, 20, 1).also { it[15] = 100 }
        val frames = FrameAssembler().accept(corrupt + report(VoltraProtocol.BATTERY, 80, 1))
        assertEquals(1, frames.size)
        assertEquals(80, VoltraDecoder.decode(frames.single())!!.registers[VoltraProtocol.BATTERY])
        assertNull(VoltraDecoder.decode(corrupt))
    }

    @Test
    fun `force is live tenths of pounds not configured weight`() {
        assertEquals(42.7, VoltraDecoder.decode(force(427))!!.activeWeightLb!!, 0.001)
        assertTrue(VoltraDecoder.decode(force(427))!!.registers.isEmpty())
    }

    @Test
    fun `pull counts are sixteen bit and return event is ignored`() {
        assertEquals(257, VoltraDecoder.decode(rep(1025, 257))!!.rep)
        assertEquals(1025, VoltraDecoder.decode(rep(1025, 257))!!.deviceSet)
        assertNull(VoltraDecoder.decode(rep(1025, 257, phase = 2)))
    }

    @Test
    fun `only battery register supplies battery not legacy status length`() {
        for (size in listOf(35, 52)) {
            val bytes =
                ByteArray(size).also {
                    it[0] = 0x55
                    it[2] = 4
                    it[11] = 87
                    it[12] = 42
                }
            assertNull(VoltraDecoder.decode(VoltraProtocol.seal(bytes)))
        }
        assertEquals(
            61,
            VoltraDecoder.decode(report(VoltraProtocol.BATTERY, 61, 1))!!
                .registers[VoltraProtocol.BATTERY],
        )
    }

    @Test
    fun `enum widths vary while eccentric remains signed`() {
        for (width in listOf(1, 2, 4)) {
            assertEquals(
                7,
                VoltraDecoder.decode(report(VoltraProtocol.MODE, 7, width))!!
                    .registers[VoltraProtocol.MODE],
            )
        }
        assertEquals(
            -25,
            VoltraDecoder.decode(report(Parameter.ECCENTRIC.register, -25, 2))!!
                .registers[Parameter.ECCENTRIC.register],
        )
        assertEquals(
            1500,
            VoltraDecoder.decode(report(Parameter.SPEED.register, 1500, 4))!!
                .registers[Parameter.SPEED.register],
        )
    }

    @Test
    fun `unknown registers and malformed widths are ignored`() {
        assertNull(VoltraDecoder.decode(report(0x1234, 1, 2)))
        assertNull(VoltraDecoder.decode(report(VoltraProtocol.MODE, 1, 3)))
    }
}

internal fun report(register: Int, value: Int, width: Int): ByteArray {
    val frame = ByteArray(17 + width)
    frame[0] = 0x55
    frame[2] = 4
    frame[10] = 0x10
    frame[11] = 1
    put(frame, 13, register, 2)
    put(frame, 15, value, width)
    return VoltraProtocol.seal(frame)
}

internal fun force(tenths: Int): ByteArray =
    VoltraProtocol.seal(
        ByteArray(58).also {
            it[0] = 0x55
            it[2] = 4
            put(it, 22, tenths, 2)
        }
    )

internal fun rep(set: Int, count: Int, phase: Int = 1): ByteArray =
    VoltraProtocol.seal(
        ByteArray(74).also {
            it[0] = 0x55
            it[2] = 4
            it[10] = 0xaa.toByte()
            it[11] = 0x82.toByte()
            it[12] = 0x3b
            it[13] = phase.toByte()
            put(it, 15, set, 2)
            put(it, 17, count, 2)
        }
    )

internal fun put(bytes: ByteArray, offset: Int, value: Int, width: Int) {
    repeat(width) { bytes[offset + it] = (value ushr (it * 8)).toByte() }
}
