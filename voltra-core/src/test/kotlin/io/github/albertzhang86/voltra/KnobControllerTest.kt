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

    private fun ack(command: ByteArray, code: Int): ByteArray =
        ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(0x4b)
                put(1)
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
        assertEquals(12.3, KnobProtocol.snapshot(packet).trainer!!.activeWeightLb!!, 0.01)
        assertTrue(runCatching { KnobProtocol.snapshot(packet.copyOf(100)) }.isFailure)
        assertTrue(runCatching { KnobProtocol.snapshot(snapshot(weight = 500)) }.isFailure)
        packet[1] = 2
        assertTrue(runCatching { KnobProtocol.snapshot(packet) }.isFailure)
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
        assertNull(controller.state.value.knob)
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
