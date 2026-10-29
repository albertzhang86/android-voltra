package io.github.albertzhang86.voltra

import io.github.albertzhang86.voltra.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test
    fun `native firmware 2 load cycle preserves raw words and rejects unknown command encodings`() {
        val loaded = requireNotNull(VoltraDecoder.decode(VoltraProtocol.hex("5513040310aa92012000100100893e010042fc")))
        val unloaded = requireNotNull(VoltraDecoder.decode(VoltraProtocol.hex("5513040310aaa7012000100100893e0000917d")))
        assertEquals(1, loaded.registers[VoltraProtocol.FITNESS])
        assertEquals(0, unloaded.registers[VoltraProtocol.FITNESS])
        assertTrue(MotorState.loaded(loaded.registers[VoltraProtocol.FITNESS]))
        assertTrue(MotorState.unloaded(unloaded.registers[VoltraProtocol.FITNESS]))
        assertTrue(VoltraProtocol.motorCommand(0, true).contentEquals(VoltraProtocol.write(VoltraProtocol.FITNESS, 1, 2)))
        assertTrue(VoltraProtocol.motorCommand(1, false).contentEquals(VoltraProtocol.write(VoltraProtocol.FITNESS, 0, 2)))
        for (unknown in listOf(null, -1, 2, 3, 0x100, 0x101, 0x105, 0x204, 65535)) {
            assertFalse(MotorState.unloaded(unknown))
            assertFalse(MotorState.loaded(unknown))
            assertNull(MotorState.commandValue(unknown, true))
            assertNull(MotorState.commandValue(unknown, false))
        }
    }

    @Test
    fun `firmware 2 settings cascade preserves selected mode and raw idle motor report`() {
        // VTR-161230 capture, 2026-10-28. Its screen showed Weight Training.
        val packet = VoltraProtocol.hex(
            "552e04a710aa0d002000100900863e2d00873e1a00883e1300893e0000025109035108b04f01e14e01245100ff35"
        )
        assertTrue(VoltraProtocol.valid(packet))
        val decoded = requireNotNull(VoltraDecoder.decode(packet))
        assertEquals(1, decoded.registers[VoltraProtocol.MODE])
        assertEquals(0, decoded.registers[VoltraProtocol.FITNESS])
        assertEquals(45, decoded.registers[Parameter.WEIGHT.register])
        assertNull(decoded.activeWeightLb)
        // Unknown semantics are not promoted to writable parameters or motor capability.
        assertTrue(Parameter.entries.none { it.register in listOf(0x5102, 0x4ee1, 0x5124) })
        val unsupportedField = packet.copyOf().also { it[29] = 0xff.toByte() }
        assertNull(VoltraDecoder.decode(VoltraProtocol.seal(unsupportedField)))
    }

    @Test
    fun `captured cable query and SETUP both report two centimeters beyond saved zero`() {
        // VTR-161230 USB capture, 2026-10-17. The user reported the cable fully
        // returned. Preserve actual packets: this is device evidence, not a
        // synthesized zero or a weight/length-unit conversion.
        for (hex in listOf(
            "551b08d610aa002020000f000300823e02006a5000001150008a5b",
            "5518088310aa150020000f0002006a500000823e02000946",
        )) {
            val packet = VoltraProtocol.hex(hex)
            assertTrue(VoltraProtocol.valid(packet))
            val decoded = requireNotNull(VoltraDecoder.decode(packet))
            assertEquals(2, decoded.registers[VoltraProtocol.CABLE_POSITION])
            assertEquals(0, decoded.registers[VoltraProtocol.CABLE_OFFSET])
            assertFalse(decoded.registers.containsKey(Parameter.WEIGHT.register))
            assertNull(decoded.activeWeightLb)
        }
    }

    @Test
    fun `captured flagged ready notification preserves raw fitness and idle mode`() {
        val packet = VoltraProtocol.hex("551604fc10aa0c1c2000100200893e0401b04f007153")
        assertTrue(VoltraProtocol.valid(packet))
        val decoded = VoltraDecoder.decode(packet)!!
        assertEquals(0x0104, decoded.registers[VoltraProtocol.FITNESS])
        assertEquals(0, decoded.registers[VoltraProtocol.MODE])
        assertFalse(decoded.registers.containsKey(0x53b7))
    }

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
            assertEquals(3, decoded.cableLengthMm)
            assertEquals(0, decoded.cableVelocityMmPerSecond)
        }
        for (source in 0..2) {
            val required = if (source == 0) 6 else 10
            for (size in listOf(required, required + 2)) {
                val decoded =
                    VoltraDecoder.decode(terseForce(427, source, size, targetTenths = 600))!!
                assertEquals(42.7, decoded.cableForceLb!!, 0.001)
                assertTrue(decoded.resistanceTargetReported)
                if (size == required) assertNull(decoded.resistanceTargetLb)
                else assertEquals(60.0, decoded.resistanceTargetLb!!, 0.001)
            }
        }
    }

    @Test
    fun `terse runtime decodes signed velocity and unsigned length from the same force sample`() {
        for (source in 0..2) {
            val size = if (source == 0) 8 else 12
            for (velocity in listOf(-32768, -400, 0, 400, 32767)) {
                val decoded =
                    VoltraDecoder.decode(
                        terseForce(
                            427,
                            source,
                            size,
                            targetTenths = 300,
                            velocityMmPerSecond = velocity,
                            lengthMm = 65535,
                        )
                    )!!
                assertEquals(velocity, decoded.cableVelocityMmPerSecond)
                assertEquals(65535, decoded.cableLengthMm)
                assertEquals(42.7, decoded.cableForceLb!!, 0.001)
                assertEquals(30.0, decoded.resistanceTargetLb!!, 0.001)
            }
        }
        val legacy = VoltraDecoder.decode(force(427))!!
        assertNull(legacy.cableVelocityMmPerSecond)
        assertNull(legacy.cableLengthMm)
    }

    @Test
    fun `missing and invalid target remain unavailable while zero is a real target`() {
        for (raw in listOf(10001, 65535)) {
            val decoded = VoltraDecoder.decode(terseForce(427, targetTenths = raw))!!
            assertNull(decoded.resistanceTargetLb)
            assertTrue(decoded.resistanceTargetReported)
            assertEquals(42.7, decoded.cableForceLb!!, 0.001)
        }
        assertEquals(
            0.0,
            VoltraDecoder.decode(terseForce(427, targetTenths = 0))!!.resistanceTargetLb!!,
            0.001,
        )
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
    fun `pull indices and return counts normalize separately without sixteen bit overflow`() {
        for (phase in 1..2) {
            for (raw in listOf(0, 3, 257, 65535)) {
                val update = VoltraDecoder.decode(rep(1025, raw, phase))!!
                assertEquals(raw + if (phase == 1) 1 else 0, update.rep)
                assertEquals(1025, update.deviceSet)
            }
        }
    }

    @Test
    fun `captured first rep is one and summary is not normalized as a per-rep index`() {
        // SDK on-device capture: 50 lb, device set 3, followed by its one-rep summary.
        // The pull payload's index is 0; the summary's count is already 1.
        val perRep =
            "554a04c610aa4a072000aa823b011a03000000f4010000000000005203810282038102cb00000075000000f40100004a060000010000520375000000bc0000004a060000000000006f1f"
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        val summary =
            "556e043c10aa8b072000aa855f010203f40100000000000003000100f9018203cb000000f501110275000000f8017b0156000000f50120013f00000052030000e8030000f40100004d170000d6ac0400bc000000b8000000bd000000b90000004a060000000000006d0b000034a5"
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        assertEquals(3, VoltraDecoder.decode(perRep)!!.deviceSet)
        assertEquals(1, VoltraDecoder.decode(perRep)!!.rep)
        assertTrue(VoltraProtocol.valid(summary))
        assertNull(VoltraDecoder.decode(summary))
    }

    @Test
    fun `unrecognized rep phases and damaged rep frames are ignored`() {
        for (phase in listOf(0, 3, 255)) {
            assertNull(VoltraDecoder.decode(rep(1025, 257, phase)))
        }
        val damaged = rep(1025, 257, phase = 2).also { it[17] = 0 }
        assertNull(VoltraDecoder.decode(damaged))
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

internal fun terseForce(
    tenths: Int,
    source: Int = 0,
    payloadSize: Int = 8,
    targetTenths: Int = 0,
    velocityMmPerSecond: Int = 0,
    lengthMm: Int = 0,
): ByteArray =
    VoltraProtocol.seal(
        ByteArray(13 + payloadSize).also {
            it[0] = 0x55
            it[2] = 4
            it[8] = source.toByte()
            it[10] = 0xb4.toByte()
            put(it, 11, tenths, 2)
            if (payloadSize >= 6) {
                put(it, 13, lengthMm, 2)
                put(it, 15, velocityMmPerSecond, 2)
            }
            val required = if (source == 0) 6 else 10
            if (payloadSize == required + 2) put(it, 11 + required, targetTenths, 2)
        }
    )

internal fun rep(set: Int, rawCounter: Int, phase: Int = 1): ByteArray =
    VoltraProtocol.seal(
        ByteArray(74).also {
            it[0] = 0x55
            it[2] = 4
            it[10] = 0xaa.toByte()
            it[11] = 0x82.toByte()
            it[12] = 0x3b
            it[13] = phase.toByte()
            put(it, 15, set, 2)
            put(it, 17, rawCounter, 2)
        }
    )

internal fun put(bytes: ByteArray, offset: Int, value: Int, width: Int) {
    repeat(width) { bytes[offset + it] = (value ushr (it * 8)).toByte() }
}
