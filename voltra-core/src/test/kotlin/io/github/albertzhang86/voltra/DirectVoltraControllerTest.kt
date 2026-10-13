package io.github.albertzhang86.voltra

import io.github.albertzhang86.voltra.protocol.VoltraProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectVoltraControllerTest {
    @Test
    fun `connecting reads trainer settings without changing load or mode`() = runTest {
        val rig = Rig(this)
        rig.ready()
        assertEquals(ConnectionStatus.READY, rig.trainer().status)
        assertEquals(30, rig.trainer().setWeightLb)
        assertTrue(rig.link().writes.none { it.command == 0x11 && it.register != 0x5183 })
        rig.controller.close()
    }

    @Test
    fun `two trainers change independently and reject a third`() = runTest {
        val rig = Rig(this)
        rig.ready(2)
        val update = async { rig.controller.setParameter("left", Parameter.WEIGHT, 45) }
        advanceTimeBy(300)
        runCurrent()
        update.await()
        assertEquals(45, rig.trainer().setWeightLb)
        assertEquals(30, rig.trainer("right").setWeightLb)
        assertTrue(
            rig.link("right").writes.none {
                it.command == 0x11 && it.register == Parameter.WEIGHT.register
            }
        )
        assertTrue(runCatching { rig.controller.connect(VoltraDevice("third", "Third")) }.isFailure)
        rig.controller.close()
    }

    @Test
    fun `transport write alone is not confirmation`() = runTest {
        val rig = Rig(this)
        rig.ready()
        rig.link().acceptSettings = false
        val update = async {
            runCatching { rig.controller.setParameter("left", Parameter.WEIGHT, 45) }
        }
        runCurrent()
        assertTrue(rig.trainer().pending)
        assertEquals(30, rig.trainer().setWeightLb)
        advanceTimeBy(4_100)
        runCurrent()
        assertTrue(update.await().isFailure)
        assertEquals(30, rig.trainer().setWeightLb)
        assertFalse(rig.trainer().pending)
        rig.controller.close()
    }

    @Test
    fun `real readback clears pending and updates confirmed value`() = runTest {
        val rig = Rig(this)
        rig.ready()
        val update = async { rig.controller.setParameter("left", Parameter.WEIGHT, 45) }
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        update.await()
        assertFalse(rig.trainer().pending)
        assertEquals(45, rig.trainer().setWeightLb)
        rig.controller.close()
    }

    @Test
    fun `force expires independently of healthy status`() = runTest {
        val rig = Rig(this)
        rig.ready()
        rig.link().send(force(285))
        runCurrent()
        assertEquals(28.5, rig.trainer().activeWeightLb!!, 0.001)
        advanceTimeBy(1_800)
        runCurrent()
        assertNull(rig.trainer().activeWeightLb)
        assertTrue(rig.trainer().fresh)
        rig.controller.close()
    }

    @Test
    fun `stale status disables writes and timeout clears live data`() = runTest {
        val rig = Rig(this)
        rig.ready()
        rig.link().respond = false
        advanceTimeBy(4_000)
        runCurrent()
        assertFalse(rig.trainer().fresh)
        assertTrue(
            runCatching { rig.controller.setParameter("left", Parameter.WEIGHT, 50) }.isFailure
        )
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(ConnectionStatus.FAILED, rig.trainer().status)
        assertNull(rig.trainer().activeWeightLb)
        rig.controller.disconnect("left")
        assertTrue(rig.controller.state.value.trainers.isEmpty())
        rig.controller.close()
    }

    @Test
    fun `duplicates returns and older sets do not inflate reps`() = runTest {
        val rig = Rig(this)
        rig.ready()
        rig.controller.beginSet()
        rig.link().send(rep(4, 8))
        runCurrent()
        assertEquals(1, rig.trainer().reps)
        rig.link().send(rep(4, 8))
        rig.link().send(rep(4, 8, 2))
        rig.link().send(rep(3, 99))
        runCurrent()
        assertEquals(1, rig.trainer().reps)
        rig.link().send(rep(4, 9))
        runCurrent()
        assertEquals(2, rig.trainer().reps)
        rig.controller.beginSet()
        rig.link().send(rep(4, 10))
        runCurrent()
        assertEquals(1, rig.trainer().reps)
        rig.link().send(rep(5, 1))
        runCurrent()
        assertEquals(1, rig.trainer().reps)
        rig.controller.close()
    }

    @Test
    fun `loaded trainer rejects mode and modifiers but accepts weight`() = runTest {
        val rig = Rig(this)
        rig.ready()
        rig.link().values[VoltraProtocol.FITNESS] = 5
        rig.link().send(report(VoltraProtocol.FITNESS, 5, 2))
        runCurrent()
        assertTrue(runCatching { rig.controller.setMode("left", TrainingMode.BAND) }.isFailure)
        assertTrue(
            runCatching { rig.controller.setParameter("left", Parameter.CHAINS, 5) }.isFailure
        )
        val update = async { rig.controller.setParameter("left", Parameter.WEIGHT, 40) }
        advanceTimeBy(250)
        runCurrent()
        update.await()
        assertEquals(40, rig.trainer().setWeightLb)
        rig.controller.close()
    }

    @Test
    fun `invalid combined load sends no motor setting`() = runTest {
        val rig = Rig(this)
        rig.ready()
        assertTrue(
            runCatching { rig.controller.setParameter("left", Parameter.ECCENTRIC, -30) }.isFailure
        )
        assertTrue(
            rig.link().writes.none {
                it.command == 0x11 && it.register == Parameter.ECCENTRIC.register
            }
        )
        rig.controller.close()
    }

    @Test
    fun `unsupported mode remains connected read only`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.MODE] = 99
        rig.ready()
        assertEquals(ConnectionStatus.READY, rig.trainer().status)
        assertNull(rig.trainer().mode)
        assertFalse(rig.trainer().canChangeMode)
        rig.controller.close()
    }

    @Test
    fun `Twin is not faked with independent writes and detected native Twin is read only`() =
        runTest {
            val rig = Rig(this)
            rig.ready(2)
            assertFalse(rig.controller.state.value.twinSupported)
            assertTrue(runCatching { rig.controller.createTwin("left", "right") }.isFailure)
            assertTrue(runCatching { rig.controller.exitTwin() }.isFailure)
            rig.link().send(report(VoltraProtocol.TWIN_STATUS, 2, 1))
            rig.link().send(force(300))
            runCurrent()
            assertTrue(rig.trainer().twinReported)
            assertNull(rig.trainer().activeWeightLb)
            assertFalse(rig.trainer().canChangeMode)
            assertTrue(
                runCatching { rig.controller.setParameter("left", Parameter.WEIGHT, 45) }.isFailure
            )
            rig.controller.close()
        }

    @Test
    fun `disconnect cancels pending command and does not replay it on reconnect`() = runTest {
        val rig = Rig(this)
        rig.ready()
        rig.link().acceptSettings = false
        val update = async {
            runCatching { rig.controller.setParameter("left", Parameter.WEIGHT, 45) }
        }
        runCurrent()
        rig.controller.disconnect("left")
        advanceTimeBy(300)
        runCurrent()
        assertTrue(update.await().isFailure)
        rig.ready()
        assertEquals(30, rig.trainer().setWeightLb)
        rig.controller.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Rig(val scope: TestScope) {
    private val links = mutableMapOf("left" to FakeLink(), "right" to FakeLink())
    private val transport =
        object : VoltraTransport {
            override fun scan() =
                flowOf(listOf(VoltraDevice("left", "Left"), VoltraDevice("right", "Right")))

            override suspend fun connect(device: VoltraDevice) =
                links.getValue(device.id).let {
                    if (it.closed) FakeLink().also { next -> links[device.id] = next } else it
                }
        }
    val controller =
        DirectVoltraController(
            transport,
            scope.backgroundScope,
            { scope.testScheduler.currentTime },
        )

    fun link(id: String = "left") = links.getValue(id)

    fun trainer(id: String = "left") = controller.state.value.trainers.first { it.device.id == id }

    suspend fun ready(count: Int = 1) {
        controller.connect(VoltraDevice("left", "Left"))
        if (count == 2) controller.connect(VoltraDevice("right", "Right"))
        scope.advanceTimeBy(4_000)
        scope.runCurrent()
    }
}

private data class Write(val command: Int, val register: Int)

private class FakeLink : VoltraLink {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val packets = channel.receiveAsFlow()
    val values =
        mutableMapOf(
            VoltraProtocol.MODE to 1,
            VoltraProtocol.FITNESS to 4,
            VoltraProtocol.BATTERY to 80,
            VoltraProtocol.TWIN_STATUS to 0,
            Parameter.WEIGHT.register to 30,
            Parameter.ECCENTRIC.register to 0,
            Parameter.CHAINS.register to 0,
            Parameter.INVERSE_CHAINS.register to 0,
        )
    val writes = mutableListOf<Write>()
    var respond = true
    var acceptSettings = true
    var closed = false

    override suspend fun write(frame: ByteArray) {
        check(!closed)
        val command = frame[10].toInt() and 255
        val register = if (frame.size >= 17) VoltraProtocol.unsigned(frame, 13, 2) else 0
        writes += Write(command, register)
        if (command == 0x11 && register in values && acceptSettings) {
            val raw = VoltraProtocol.unsigned(frame, 15, frame.size - 17)
            values[register] =
                if (register == Parameter.ECCENTRIC.register) raw.toShort().toInt() else raw
        }
        if (command == 0x0f && respond)
            values[register]?.let { value ->
                val width =
                    Parameter.entries.find { it.register == register }?.width
                        ?: if (register == VoltraProtocol.FITNESS) 2 else 1
                send(report(register, value, width))
            }
    }

    fun send(frame: ByteArray) {
        channel.trySend(frame).getOrThrow()
    }

    override fun close() {
        closed = true
        channel.close()
    }
}
