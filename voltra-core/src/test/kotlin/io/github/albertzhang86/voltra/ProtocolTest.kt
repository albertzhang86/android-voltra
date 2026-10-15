package io.github.albertzhang86.voltra

import io.github.albertzhang86.voltra.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test
    fun `runtime wire weight is signed whole pounds and separate from base weight or stream force`() {
        for (value in listOf(0, 59, -1, -32768, 32767)) {
            val decoded =
                VoltraDecoder.decode(report(VoltraProtocol.RUNTIME_WIRE_WEIGHT, value, 2))!!
            assertEquals(value.toDouble(), decoded.runtimeWeightLb!!, 0.001)
            assertEquals(value, decoded.registers[VoltraProtocol.RUNTIME_WIRE_WEIGHT])
            assertNull(decoded.activeWeightLb)
            assertFalse(decoded.registers.containsKey(Parameter.WEIGHT.register))
        }
        for (width in listOf(1, 4)) assertNull(
            VoltraDecoder.decode(report(VoltraProtocol.RUNTIME_WIRE_WEIGHT, 59, width))
        )
        val reply =
            ByteArray(20).also {
                it[0] = 0x55
                it[2] = 8
                it[10] = 0x0f
                put(it, 12, 1, 2)
                put(it, 14, VoltraProtocol.RUNTIME_WIRE_WEIGHT, 2)
                put(it, 16, 42, 2)
            }
        assertEquals(
            42.0,
            VoltraDecoder.decode(VoltraProtocol.seal(reply))!!.runtimeWeightLb!!,
            0.001,
        )
    }

    @Test
    fun `unit register decodes reported widths without changing weight units`() {
        for (width in listOf(1, 2, 4)) {
            val decoded = VoltraDecoder.decode(report(VoltraProtocol.WEIGHT_UNIT, 1, width))!!
            assertEquals(
                WeightUnit.KILOGRAMS,
                WeightUnit.fromWire(decoded.registers.getValue(VoltraProtocol.WEIGHT_UNIT)),
            )
            assertNull(decoded.activeWeightLb)
        }
        assertNull(WeightUnit.fromWire(7))
    }

    @Test
    fun `endpoint reply and unsolicited report use their own offsets`() {
        for (response in listOf(false, true)) {
            val status =
                VoltraDecoder.decode(
                        endpointReport("02:00:00:00:00:01", "02:00:00:00:00:02", response)
                    )!!
                    .endpoint!!
            assertEquals("02:00:00:00:00:01", status.localAddress)
            assertEquals("02:00:00:00:00:02", status.peerAddress)
            assertEquals(80, status.localBatteryPercent)
            assertEquals(70, status.peerBatteryPercent)
            assertTrue(status.paired)
            assertFalse(status.independent)
        }
    }

    @Test
    fun `invalid endpoint identity or rejected response cannot establish Twin`() {
        assertNull(VoltraDecoder.decode(endpointReport("00:00:00:00:00:00")))
        val rejected = endpointReport("02:00:00:00:00:01", response = true).also { it[11] = 1 }
        assertNull(VoltraDecoder.decode(VoltraProtocol.seal(rejected)))
        val self =
            VoltraDecoder.decode(endpointReport("02:00:00:00:00:01", "02:00:00:00:00:01"))!!
                .endpoint!!
        assertFalse(self.topologyKnown)
        val unknown =
            endpointReport("02:00:00:00:00:01", "02:00:00:00:00:02").also { it[13] = 0x11 }
        assertFalse(VoltraDecoder.decode(VoltraProtocol.seal(unknown))!!.endpoint!!.topologyKnown)
    }

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
        val unrelated = force(427).also { it[11] = 0x80.toByte() }
        assertNull(VoltraDecoder.decode(VoltraProtocol.seal(unrelated)))
        val unrelatedCommand = force(427).also { it[10] = 0x74 }
        assertNull(VoltraDecoder.decode(VoltraProtocol.seal(unrelatedCommand)))
    }

    @Test
    fun `captured terse runtime separates measured force from optional resistance target`() {
        val captures =
            listOf(
                "551904e410aacb6e0200b40b0003000000030000003c0045a8" to 1.1,
                "551904e410aa2f6f0100b40a0003000000000000003c007142" to 1.0,
                "551504a910aa936f0000b40c00030000003c00d8db" to 1.2,
                "551504a910aaf76f0000b40b00030000003c009387" to 1.1,
            )
        for ((hex, pounds) in captures) {
            val decoded = VoltraDecoder.decode(VoltraProtocol.hex(hex))!!
            assertEquals(pounds, decoded.cableForceLb!!, 0.001)
            assertEquals(6.0, decoded.resistanceTargetLb!!, 0.001)
            assertTrue(decoded.resistanceTargetReported)
            assertTrue(decoded.registers.isEmpty())
            assertNull(decoded.runtimeWeightLb)
        }
        for (source in 0..2) {
            val required = if (source == 0) 6 else 10
            for (size in listOf(required, required + 2)) {
                val decoded = VoltraDecoder.decode(terseForce(427, source, size, targetTenths = 600))!!
                assertEquals(42.7, decoded.cableForceLb!!, 0.001)
                assertTrue(decoded.resistanceTargetReported)
                if (size == required) assertNull(decoded.resistanceTargetLb)
                else assertEquals(60.0, decoded.resistanceTargetLb!!, 0.001)
            }
        }
    }

    @Test
    fun `missing and invalid target remain unavailable while zero is a real target`() {
        for (raw in listOf(10001, 65535)) {
            val decoded = VoltraDecoder.decode(terseForce(427, targetTenths = raw))!!
            assertNull(decoded.resistanceTargetLb)
            assertTrue(decoded.resistanceTargetReported)
            assertEquals(42.7, decoded.cableForceLb!!, 0.001)
        }
        assertEquals(0.0, VoltraDecoder.decode(terseForce(427, targetTenths = 0))!!.resistanceTargetLb!!, 0.001)
        assertFalse(VoltraDecoder.decode(force(427))!!.resistanceTargetReported)
        assertNull(VoltraDecoder.decode(force(427))!!.resistanceTargetLb)
    }

    @Test
    fun `terse runtime rejects unknown layouts Twin truncation and damaged frames`() {
        for (source in listOf(3, 0x10, 0x11, 0x12, 0x20)) {
            assertNull(VoltraDecoder.decode(terseForce(427, source, 18)))
        }
        for (source in 0..2) {
            val allowed = if (source == 0) setOf(6, 8) else setOf(10, 12)
            for (size in 2..14) {
                if (size !in allowed)
                    assertNull(VoltraDecoder.decode(terseForce(427, source, size)))
            }
        }
        for ((index, value) in listOf(2 to 8, 9 to 1)) {
            val frame = terseForce(427).also { it[index] = value.toByte() }
            assertNull(VoltraDecoder.decode(VoltraProtocol.seal(frame)))
        }
        val corrupt = terseForce(427).also { it[11] = 0 }
        assertNull(VoltraDecoder.decode(corrupt))
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
            it[10] = 0xaa.toByte()
            it[11] = 0x81.toByte()
            it[12] = 0x2b
            put(it, 22, tenths, 2)
        }
    )

internal fun terseForce(tenths: Int, source: Int = 0, payloadSize: Int = 8, targetTenths: Int = 0): ByteArray =
    VoltraProtocol.seal(
        ByteArray(13 + payloadSize).also {
            it[0] = 0x55
            it[2] = 4
            it[8] = source.toByte()
            it[10] = 0xb4.toByte()
            put(it, 11, tenths, 2)
            val required = if (source == 0) 6 else 10
            if (payloadSize == required + 2) put(it, 11 + required, targetTenths, 2)
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
