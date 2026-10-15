package io.github.albertzhang86.voltra

import io.github.albertzhang86.voltra.knob.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KnobControllerTest {
    private val knob = VoltraDevice("knob", "Test knob")
    private val trainer = "01:02:03:04:05:06"

    @Test
    fun `USB power is explicit and independent of battery percentage and trainer selection`() {
        assertFalse(KnobProtocol.snapshot(snapshot()).knobUsbPowerConnected)
        val packet = twinSnapshot().also {
            it[17] = 255.toByte()
            it[133] = (1 or 8 or 16).toByte()
        }
        assertFalse(KnobProtocol.snapshot(packet).knobUsbPowerConnected)
        packet[133] = (packet[133].toInt() or 0x20).toByte()
        val powered = KnobProtocol.snapshot(packet)
        assertTrue(powered.knobUsbPowerConnected)
        assertNull(powered.knobBattery)
        assertEquals(WeightUnit.KILOGRAMS, powered.trainer!!.weightUnit)
        assertEquals(ActiveWeightSource.UNLOADED, powered.trainer.activeWeightSource)
        assertEquals(12.3, powered.trainer.runtimeWireWeightLb!!, 0.001)
        assertTrue(powered.canCreateTwin)
        packet[16] = 255.toByte()
        assertTrue(KnobProtocol.snapshot(packet).knobUsbPowerConnected)
        assertNull(KnobProtocol.snapshot(packet).trainer)
    }

    @Test
    fun `knob USB power clears when telemetry stops or connection fails`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        val packet = twinSnapshot().also { it[133] = (it[133].toInt() or 0x20).toByte() }
        link.incoming.send(packet)
        runCurrent()
        assertTrue(controller.state.value.knob!!.usbPowerConnected)
        advanceTimeBy(2251)
        runCurrent()
        assertFalse(controller.state.value.knob!!.usbPowerConnected)
        link.incoming.send(packet)
        runCurrent()
        assertTrue(controller.state.value.knob!!.usbPowerConnected)
        link.incoming.close(IllegalStateException("Disconnected"))
        runCurrent()
        assertFalse(controller.state.value.knob!!.usbPowerConnected)
        controller.close()
    }

    @Test
    fun `legacy bridge force and runtime readings never become loaded resistance`() {
        for (runtime in listOf(false, true)) {
            val packet = twinSnapshot().also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 5)
                it[133] = (1 or 8 or if (runtime) 16 else 0).toByte()
            }
            val trainer = KnobProtocol.snapshot(packet).trainer!!
            assertNull(trainer.activeWeightLb)
            assertNull(trainer.activeWeightSource)
            assertNull(trainer.resistanceTargetLb)
            assertEquals(12.3, (if (runtime) trainer.runtimeWireWeightLb else trainer.measuredForceLb)!!, 0.001)
            assertEquals(WeightUnit.KILOGRAMS, trainer.weightUnit)
        }
        val old = snapshot().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 5) }
        assertNull(KnobProtocol.snapshot(old).trainer!!.activeWeightLb)
        assertEquals(12.3, KnobProtocol.snapshot(old).trainer!!.measuredForceLb!!, 0.001)
    }

    @Test
    fun `target extension preserves flags and active resistance only in confirmed load states`() {
        val packet = twinSnapshot().also {
            it[19] = 1
            it[133] = (1 or 8 or 0x20 or 0x40).toByte()
        }
        for (fitness in listOf(5, 21)) {
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, fitness.toShort())
            val decoded = KnobProtocol.snapshot(packet)
            val trainer = decoded.trainer!!
            assertEquals(12.3, trainer.activeWeightLb!!, 0.001)
            assertEquals(12.3, trainer.resistanceTargetLb!!, 0.001)
            assertEquals(ActiveWeightSource.TARGET_FORCE, trainer.activeWeightSource)
            assertNull(trainer.measuredForceLb)
            assertNull(trainer.runtimeWireWeightLb)
            assertEquals(WeightUnit.KILOGRAMS, trainer.weightUnit)
            assertTrue(decoded.knobUsbPowerConnected)
            assertTrue(decoded.canCreateTwin)
        }
        for (fitness in listOf(0, 6, 65535)) {
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, fitness.toShort())
            assertNull(KnobProtocol.snapshot(packet).trainer!!.activeWeightLb)
        }
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 5)
        packet[19] = 0
        assertNull(KnobProtocol.snapshot(packet).trainer!!.activeWeightLb)
        packet[19] = 1
        packet[3] = (packet[3].toInt() and 8.inv()).toByte()
        assertNull(KnobProtocol.snapshot(packet).trainer!!.activeWeightLb)
        packet[3] = 11
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 4)
        assertEquals(0.0, KnobProtocol.snapshot(packet).trainer!!.activeWeightLb!!, 0.001)
        assertEquals(ActiveWeightSource.UNLOADED, KnobProtocol.snapshot(packet).trainer!!.activeWeightSource)
        assertNull(KnobProtocol.snapshot(packet).trainer!!.resistanceTargetLb)
        packet[3] = (packet[3].toInt() and 2.inv()).toByte()
        assertNull(KnobProtocol.snapshot(packet).trainer!!.activeWeightLb)
        val paired = twinSnapshot(paired = true).also { it[133] = (2 or 8 or 0x40).toByte() }
        assertNull(KnobProtocol.snapshot(paired).trainer!!.activeWeightLb)
        assertNull(KnobProtocol.snapshot(paired).trainer!!.resistanceTargetLb)
        val ambiguous = twinSnapshot().also { it[133] = (0x40 or 0x10).toByte() }
        assertTrue(runCatching { KnobProtocol.snapshot(ambiguous) }.isFailure)
    }

    @Test
    fun `optional v2 unit flags preserve raw weights and Twin capabilities`() {
        for ((flag, unit) in
            listOf(0 to null, 4 to WeightUnit.POUNDS, 8 to WeightUnit.KILOGRAMS, 12 to null)) {
            val bytes = twinSnapshot().also { it[133] = (1 or flag).toByte() }
            val decoded = KnobProtocol.snapshot(bytes)
            assertEquals(unit, decoded.trainer?.weightUnit)
            assertEquals(30, decoded.trainer?.setWeightLb)
            assertEquals(0.0, decoded.trainer!!.activeWeightLb!!, 0.001)
            assertEquals(12.3, decoded.trainer.measuredForceLb!!, 0.001)
            assertTrue(decoded.canCreateTwin)
        }
        assertNull(KnobProtocol.snapshot(snapshot()).trainer?.weightUnit)
    }

    private fun snapshot(
        weight: Int = 30,
        session: Int = 7,
        uptime: Int = 1000,
        reps: Int = 10,
    ): ByteArray =
        ByteBuffer.allocate(106)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(0, 0x4b)
                put(1, 1)
                put(2, 1)
                put(3, 11)
                putInt(4, 123)
                putInt(8, session)
                putInt(12, uptime)
                put(16, 0)
                put(17, 80.toByte())
                put(18, 90.toByte())
                put(19, 1)
                putShort(20, 4)
                putInt(22, 123)
                putShort(26, reps.toShort())
                putShort(28, 15)
                put(30, 1)
                put(31, 4)
                position(32)
                put("Test trainer".toByteArray())
                position(64)
                put(trainer.toByteArray())
                position(82)
                listOf(
                        Parameter.WEIGHT to weight,
                        Parameter.CHAINS to 0,
                        Parameter.INVERSE_CHAINS to 0,
                        Parameter.ECCENTRIC to 0,
                    )
                    .forEach {
                        putShort(it.first.register.toShort())
                        putInt(it.second)
                    }
            }
            .array()

    private fun twinSnapshot(
        paired: Boolean = false,
        pending: Boolean = false,
        uptime: Int = 1000,
    ): ByteArray {
        val previous = snapshot(uptime = uptime)
        val bytes = ByteArray(previous.size + 52)
        previous.copyInto(bytes, 0, 0, 82)
        previous.copyInto(bytes, 134, 82, previous.size)
        bytes[1] = 2
        bytes[3] = (11 or (if (paired) 16 else 0) or (if (pending) 4 else 0)).toByte()
        bytes[19] = 0
        "Second trainer".toByteArray().copyInto(bytes, 82)
        "01:02:03:04:05:07".toByteArray().copyInto(bytes, 114)
        bytes[132] = 72
        bytes[133] = (if (pending) 0 else if (paired) 2 else 1).toByte()
        return bytes
    }

    @Test
    fun `v2 paired snapshot supplies verified identity and both batteries without inventing telemetry`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(twinSnapshot(paired = true))
            runCurrent()
            val state = controller.state.value
            assertEquals(2, state.trainers.size)
            assertEquals(trainer, state.twin?.controllerId)
            assertEquals("01:02:03:04:05:07", state.twin?.peerId)
            assertEquals(72, state.trainers.last().batteryPercent)
            assertTrue(state.canExitTwin)
            assertNull(state.twin?.activeWeightLb)
            assertNull(state.trainers.first().activeWeightLb)
            assertFalse(state.trainers.last().canEdit(Parameter.WEIGHT))
            advanceTimeBy(2250)
            runCurrent()
            assertEquals(TwinHealth.DEGRADED, controller.state.value.twin?.health)
            assertFalse(controller.state.value.canExitTwin)
            controller.close()
        }

    @Test
    fun `knob pair and exit require result plus matching fresh topology`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(twinSnapshot())
        runCurrent()
        assertEquals(1, controller.state.value.trainers.size)
        assertTrue(controller.state.value.canCreateTwin)
        val create = async { controller.createTwin(trainer, "01:02:03:04:05:07") }
        runCurrent()
        assertEquals(5, link.writes.single()[2].toInt())
        link.incoming.send(ack(link.writes.single(), 1))
        runCurrent()
        assertFalse(create.isCompleted)
        link.incoming.send(twinSnapshot(paired = true, uptime = 1100))
        runCurrent()
        create.await()
        val exit = async { controller.exitTwin() }
        runCurrent()
        assertEquals(6, link.writes.last()[2].toInt())
        link.incoming.send(ack(link.writes.last(), 1))
        runCurrent()
        assertFalse(exit.isCompleted)
        link.incoming.send(twinSnapshot(uptime = 1200))
        runCurrent()
        exit.await()
        assertNull(controller.state.value.twin)
        controller.close()
    }

    @Test
    fun `queued Twin request never switches its intended controller after a session change`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(twinSnapshot().also { it[19] = 1 })
            runCurrent()
            val setting = async {
                runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 35) }
            }
            runCurrent()
            val create = async {
                runCatching { controller.createTwin(trainer, "01:02:03:04:05:07") }
            }
            runCurrent()
            link.incoming.send(
                twinSnapshot().also {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 8)
                    "01:02:03:04:05:08".toByteArray().copyInto(it, 64)
                }
            )
            runCurrent()
            assertTrue(setting.await().isFailure)
            assertTrue(create.await().isFailure)
            assertEquals(1, link.writes.size)
            controller.close()
        }

    private fun ack(command: ByteArray, code: Int): ByteArray =
        ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(0x4b)
                put(command[1])
                put(4)
                put(code.toByte())
                put(command, 4, 8)
                put(command, 16, 2)
                putShort(0)
            }
            .array()

    private class Link : VoltraLink {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var closed = false
        override val packets = incoming.receiveAsFlow()

        override suspend fun write(frame: ByteArray) {
            writes += frame
        }

        override fun close() {
            closed = true
            incoming.close()
        }
    }

    private fun TestScope.controller(link: Link): KnobVoltraController =
        KnobVoltraController(
            object : VoltraTransport {
                override fun scan() = flowOf(listOf(knob))

                override suspend fun connect(device: VoltraDevice) = link
            },
            backgroundScope,
            now = { testScheduler.currentTime },
        )

    @Test
    fun `snapshot rejects truncated packets invalid values and unknown protocol`() {
        val packet = snapshot()
        assertEquals(0.0, KnobProtocol.snapshot(packet).trainer!!.activeWeightLb!!, 0.01)
        assertEquals(12.3, KnobProtocol.snapshot(packet).trainer!!.measuredForceLb!!, 0.01)
        assertTrue(runCatching { KnobProtocol.snapshot(packet.copyOf(100)) }.isFailure)
        assertTrue(runCatching { KnobProtocol.snapshot(snapshot(weight = 500)) }.isFailure)
        packet[1] = 2
        assertTrue(runCatching { KnobProtocol.snapshot(packet) }.isFailure)
    }

    @Test
    fun `failed bridge can reconnect directly without stale Twin state or replaying commands`() =
        runTest {
            val first = Link()
            val second = Link()
            var attempts = 0
            val controller =
                KnobVoltraController(
                    object : VoltraTransport {
                        override fun scan() = flowOf(listOf(knob))

                        override suspend fun connect(device: VoltraDevice) =
                            if (++attempts == 1) first else second
                    },
                    backgroundScope,
                    now = { testScheduler.currentTime },
                )
            controller.connect(knob)
            runCurrent()
            first.incoming.send(twinSnapshot(paired = true))
            runCurrent()
            first.incoming.close(
                IllegalStateException("Knob disconnected (8). Reconnect to continue.")
            )
            runCurrent()
            assertEquals(ConnectionStatus.FAILED, controller.state.value.knob!!.status)
            assertTrue(first.closed)
            assertNull(controller.state.value.twin)
            assertNull(controller.state.value.knob!!.batteryPercent)
            assertFalse(controller.state.value.canExitTwin)
            controller.connect(knob)
            runCurrent()
            assertEquals(ConnectionStatus.CONNECTING, controller.state.value.knob!!.status)
            assertTrue(controller.state.value.trainers.isEmpty())
            assertNull(controller.state.value.knob!!.error)
            second.incoming.send(snapshot(reps = 25))
            runCurrent()
            assertEquals(ConnectionStatus.READY, controller.state.value.knob!!.status)
            assertEquals(0, controller.state.value.trainers.single().reps)
            assertTrue(second.writes.isEmpty())
            controller.close()
        }

    @Test
    fun `remembered knob reconnects without discovery and waits for fresh readings`() = runTest {
        val link = Link()
        var attempts = 0
        val remembered = mutableListOf<VoltraDevice>()
        val controller =
            KnobVoltraController(
                object : VoltraTransport {
                    override fun scan(): Flow<List<VoltraDevice>> = error("Reconnect must not scan")

                    override suspend fun connect(device: VoltraDevice): VoltraLink {
                        assertEquals(knob, device)
                        attempts++
                        return link
                    }
                },
                backgroundScope,
                now = { testScheduler.currentTime },
                rememberedDevice = knob,
                onConnected = remembered::add,
            )
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, controller.state.value.knob!!.status)
        assertEquals(0, attempts)
        controller.connect(knob)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(ConnectionStatus.CONNECTING, controller.state.value.knob!!.status)
        assertTrue(controller.state.value.trainers.isEmpty())
        assertTrue(remembered.isEmpty())
        link.incoming.send(snapshot())
        runCurrent()
        link.incoming.send(snapshot(uptime = 1100))
        runCurrent()
        assertEquals(listOf(knob), remembered)
        assertEquals(ConnectionStatus.READY, controller.state.value.knob!!.status)
        assertTrue(link.writes.isEmpty())
        controller.disconnect(knob.id)
        assertEquals(ConnectionStatus.DISCONNECTED, controller.state.value.knob!!.status)
        assertNull(controller.state.value.knob!!.batteryPercent)
        assertTrue(controller.state.value.trainers.isEmpty())
        assertTrue(link.writes.isEmpty())
        controller.close()
    }

    @Test
    fun `invalid first reading never remembers a new knob`() = runTest {
        val link = Link()
        val remembered = mutableListOf<VoltraDevice>()
        val controller =
            KnobVoltraController(
                object : VoltraTransport {
                    override fun scan() = flowOf(listOf(knob))

                    override suspend fun connect(device: VoltraDevice) = link
                },
                backgroundScope,
                onConnected = remembered::add,
            )
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot().copyOf(80))
        runCurrent()
        assertEquals(ConnectionStatus.FAILED, controller.state.value.knob!!.status)
        assertTrue(remembered.isEmpty())
        controller.disconnect(knob.id)
        assertNull(controller.state.value.knob)
        controller.close()
    }

    @Test
    fun `forget cancels acknowledged and queued changes and late snapshots cannot remember knob`() =
        runTest {
            val link = Link()
            var saved: VoltraDevice? = null
            val controller =
                KnobVoltraController(
                    object : VoltraTransport {
                        override fun scan() = flowOf(listOf(knob))

                        override suspend fun connect(device: VoltraDevice) = link
                    },
                    backgroundScope,
                    onConnected = { saved = it },
                    onForgotten = { if (saved?.id == it) saved = null },
                )
            controller.connect(knob)
            runCurrent()
            link.incoming.send(snapshot())
            runCurrent()
            assertEquals(knob, saved)
            val setting = async {
                runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 35) }
            }
            runCurrent()
            link.incoming.send(ack(link.writes.single(), 1))
            runCurrent()
            assertFalse(setting.isCompleted) // ACK arrived, but the readback has not.
            val queued = async {
                runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 40) }
            }
            runCurrent()
            link.incoming.send(snapshot(weight = 35, uptime = 1100)) // Buffered late reading.
            controller.forget(knob.id)
            runCurrent()
            assertTrue(setting.await().isFailure)
            assertTrue(queued.await().isFailure)
            assertNull(saved)
            assertNull(controller.state.value.knob)
            assertTrue(controller.state.value.trainers.isEmpty())
            assertTrue(link.closed)
            assertEquals(1, link.writes.size)
            controller.close()
        }

    @Test
    fun `forget removes restored reconnect target but rejects a relayed trainer`() = runTest {
        val link = Link()
        val forgotten = mutableListOf<String>()
        val controller =
            KnobVoltraController(
                object : VoltraTransport {
                    override fun scan() = flowOf(listOf(knob))

                    override suspend fun connect(device: VoltraDevice) = link
                },
                backgroundScope,
                rememberedDevice = knob,
                onForgotten = forgotten::add,
            )
        controller.forget(knob.id)
        assertNull(controller.state.value.knob)
        assertEquals(listOf(knob.id), forgotten)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        assertTrue(runCatching { controller.forget(trainer) }.isFailure)
        assertEquals(ConnectionStatus.READY, controller.state.value.knob?.status)
        assertFalse(link.closed)
        assertTrue(link.writes.isEmpty())
        controller.close()
    }

    @Test
    fun `transport timeout becomes a recoverable failure instead of remaining connecting`() =
        runTest {
            val controller =
                KnobVoltraController(
                    object : VoltraTransport {
                        override fun scan() = flowOf(listOf(knob))

                        override suspend fun connect(device: VoltraDevice): VoltraLink =
                            withTimeout(1000) { awaitCancellation() }
                    },
                    backgroundScope,
                )
            controller.connect(knob)
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(ConnectionStatus.FAILED, controller.state.value.knob!!.status)
            assertTrue(controller.state.value.knob!!.error!!.contains("Knob connection timed out"))
            controller.close()
        }

    @Test
    fun `cancelling pairing clears the knob without publishing a failure`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        controller.disconnect(knob.id)
        advanceTimeBy(90_001)
        runCurrent()
        assertNull(controller.state.value.knob)
        assertTrue(link.closed)
        controller.close()
    }

    @Test
    fun `pairing allows code entry time but closes a silent connection`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(link.closed)
        assertEquals(ConnectionStatus.CONNECTING, controller.state.value.knob!!.status)
        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(link.closed)
        assertEquals(ConnectionStatus.FAILED, controller.state.value.knob!!.status)
        controller.close()
    }

    @Test
    fun `settings wait for confirmation and readback never update optimistically`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        val edit = async { controller.setParameter(trainer, Parameter.WEIGHT, 35) }
        runCurrent()
        assertEquals(30, controller.state.value.trainers.single().setWeightLb)
        val command = link.writes.single()
        assertEquals(3000, ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
        link.incoming.send(ack(command, 0))
        runCurrent()
        assertFalse(edit.isCompleted)
        link.incoming.send(ack(command, 1))
        runCurrent()
        assertFalse(edit.isCompleted)
        link.incoming.send(snapshot(weight = 35, uptime = 1100))
        runCurrent()
        edit.await()
        assertEquals(35, controller.state.value.trainers.single().setWeightLb)
        controller.close()
    }

    @Test
    fun `stale bridge telemetry clears force and blocks commands`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        advanceTimeBy(2251)
        runCurrent()
        assertFalse(controller.state.value.trainers.single().fresh)
        assertNull(controller.state.value.trainers.single().activeWeightLb)
        assertTrue(runCatching { controller.setMode(trainer, TrainingMode.BAND) }.isFailure)
        assertTrue(link.writes.isEmpty())
        controller.close()
    }

    @Test
    fun `trainer session change invalidates pending command and resets rep baseline`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        val edit = async { runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 35) } }
        runCurrent()
        link.incoming.send(snapshot(session = 8, reps = 2))
        runCurrent()
        assertTrue(edit.await().isFailure)
        assertEquals(0, controller.state.value.trainers.single().reps)
        link.incoming.send(snapshot(session = 8, reps = 4))
        runCurrent()
        assertEquals(2, controller.state.value.trainers.single().reps)
        controller.beginSet()
        assertEquals(0, controller.state.value.trainers.single().reps)
        controller.close()
    }

    @Test
    fun `disconnect clears readings and never replays an in flight command`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        val edit = async { runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 35) } }
        runCurrent()
        controller.disconnect(knob.id)
        runCurrent()
        assertTrue(edit.await().isFailure)
        assertTrue(link.closed)
        assertTrue(controller.state.value.trainers.isEmpty())
        assertEquals(ConnectionStatus.DISCONNECTED, controller.state.value.knob!!.status)
        assertEquals(1, link.writes.size)
        controller.close()
    }

    @Test
    fun `bridge restrictions narrow direct capability gates`() {
        val state = KnobProtocol.snapshot(snapshot()).trainer!!
        assertTrue(state.canEdit(Parameter.WEIGHT))
        assertFalse(state.copy(editableParameters = emptySet()).canEdit(Parameter.WEIGHT))
        assertFalse(state.copy(modeChangeAllowed = false).canChangeMode)
        assertFalse(state.copy(twinReported = true).canEdit(Parameter.WEIGHT))
    }
}
