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
    fun `live motion requires loaded same-sample force and signed velocity in all four modes`() =
        runTest {
            for (mode in TrainingMode.entries) {
                val rig = Rig(this)
                rig.link().values[VoltraProtocol.MODE] = mode.wireValue
                rig.link().values[VoltraProtocol.FITNESS] = 5
                rig.ready()
                for (velocity in listOf(-250, -10, -9, 0, 9, 10, 250)) {
                    rig.link().send(terseForce(11, velocityMmPerSecond = velocity, lengthMm = 500))
                    runCurrent()
                    assertEquals(kotlin.math.abs(velocity) >= 10, rig.trainer().cableMoving)
                    assertEquals(1.1, rig.trainer().measuredForceLb!!, 0.001)
                }
                // Extended cable and residual tension at rest must not count as movement.
                rig.link().send(terseForce(11, velocityMmPerSecond = 0, lengthMm = 500))
                runCurrent()
                assertEquals(false, rig.trainer().cableMoving)
                rig.link().send(terseForce(0xffff, velocityMmPerSecond = 250))
                runCurrent()
                assertNull(rig.trainer().measuredForceLb)
                assertNull(rig.trainer().cableMoving)
                rig.controller.close()
            }
        }

    @Test
    fun `legacy force cannot borrow B4 motion and runtime polls cannot keep motion fresh`() =
        runTest {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.FITNESS] = 5
            rig.link().values[VoltraProtocol.RUNTIME_WIRE_WEIGHT] = 1
            rig.ready()
            rig.link().send(terseForce(285, velocityMmPerSecond = -100))
            runCurrent()
            assertEquals(true, rig.trainer().cableMoving)
            rig.link().send(force(11))
            runCurrent()
            assertEquals(1.1, rig.trainer().measuredForceLb!!, 0.001)
            assertNull(rig.trainer().cableMoving)
            rig.link().send(terseForce(285, velocityMmPerSecond = 100))
            runCurrent()
            advanceTimeBy(750)
            runCurrent()
            assertTrue(rig.trainer().fresh)
            assertEquals(1.0, rig.trainer().runtimeWireWeightLb!!, 0.001)
            assertNull(rig.trainer().cableMoving)
            rig.controller.close()
        }

    @Test
    fun `load mode topology disconnect and stale status invalidate motion`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.FITNESS] = 5
        rig.ready()
        fun moving() {
            rig.link().send(terseForce(285, velocityMmPerSecond = 100))
            runCurrent()
            assertEquals(true, rig.trainer().cableMoving)
        }
        moving()
        for (fitness in listOf(4, 0, 0x26, 0x27)) {
            rig.link().send(report(VoltraProtocol.FITNESS, fitness, 2))
            rig.link().send(terseForce(11, velocityMmPerSecond = 100))
            runCurrent()
            assertNull(rig.trainer().cableMoving)
            rig.link().send(report(VoltraProtocol.FITNESS, 5, 2))
            runCurrent()
            assertNull(rig.trainer().cableMoving)
            moving()
        }
        rig.link().send(report(VoltraProtocol.MODE, 4, 2))
        runCurrent()
        assertNull(rig.trainer().cableMoving)
        moving()
        rig.link().send(endpointReport(LEFT_ADDRESS, RIGHT_ADDRESS))
        runCurrent()
        assertTrue(rig.controller.state.value.trainers.all { it.cableMoving == null })
        rig.link().send(endpointReport(LEFT_ADDRESS))
        runCurrent()
        assertNull(rig.trainer().cableMoving)
        moving()
        rig.link().respond = false
        advanceTimeBy(4_000)
        runCurrent()
        rig.link().send(terseForce(285, velocityMmPerSecond = 100))
        runCurrent()
        assertFalse(rig.trainer().fresh)
        assertNull(rig.trainer().cableMoving)
        rig.controller.disconnect("left")
        assertTrue(rig.controller.state.value.trainers.isEmpty())
        rig.controller.close()
    }

    @Test
    fun `explicitly unloaded resistance is zero while residual tension remains separate`() =
        runTest {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.RUNTIME_WIRE_WEIGHT] = 1
            rig.ready()
            rig.link().send(terseForce(11, targetTenths = 300))
            runCurrent()
            assertEquals(0.0, rig.trainer().activeWeightLb!!, 0.001)
            assertEquals(ActiveWeightSource.UNLOADED, rig.trainer().activeWeightSource)
            assertEquals(1.1, rig.trainer().measuredForceLb!!, 0.001)
            assertEquals(1.0, rig.trainer().runtimeWireWeightLb!!, 0.001)
            assertNull(rig.trainer().resistanceTargetLb)
            assertEquals(30, rig.trainer().setWeightLb)
            assertTrue(
                rig.link().writes.any {
                    it.command == 0x0f && it.register == VoltraProtocol.RUNTIME_WIRE_WEIGHT
                }
            )
            rig.link().values.remove(VoltraProtocol.RUNTIME_WIRE_WEIGHT)
            advanceTimeBy(1800)
            runCurrent()
            assertNull(rig.trainer().measuredForceLb)
            assertNull(rig.trainer().runtimeWireWeightLb)
            assertEquals(0.0, rig.trainer().activeWeightLb!!, 0.001)
            rig.controller.close()
        }

    @Test
    fun `loaded resistance uses captured target and expires despite fresh measured wire weight`() =
        runTest {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.FITNESS] = 5
            rig.link().values[VoltraProtocol.RUNTIME_WIRE_WEIGHT] = 1
            rig.ready()
            assertNull(rig.trainer().activeWeightLb)
            // Physical B4 capture: measured tension 1.1 lb; target resistance 6 lb.
            rig.link()
                .send(VoltraProtocol.hex("551904e410aacb6e0200b40b0003000000030000003c0045a8"))
            runCurrent()
            assertEquals(6.0, rig.trainer().activeWeightLb!!, 0.001)
            assertEquals(6.0, rig.trainer().resistanceTargetLb!!, 0.001)
            assertEquals(1.1, rig.trainer().measuredForceLb!!, 0.001)
            assertEquals(ActiveWeightSource.TARGET_FORCE, rig.trainer().activeWeightSource)
            advanceTimeBy(1800)
            runCurrent()
            assertTrue(rig.trainer().fresh)
            assertEquals(1.0, rig.trainer().runtimeWireWeightLb!!, 0.001)
            assertNull(rig.trainer().resistanceTargetLb)
            assertNull(rig.trainer().activeWeightLb)
            assertNull(rig.trainer().activeWeightSource)
            rig.controller.close()
        }

    @Test
    fun `missing or invalid target clears resistance and never falls back to force or base weight`() =
        runTest {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.FITNESS] = 5
            rig.link().values[VoltraProtocol.RUNTIME_WIRE_WEIGHT] = 20
            rig.ready()
            for (packet in
                listOf(terseForce(427, payloadSize = 6), terseForce(427, targetTenths = 0xffff))) {
                rig.link().send(terseForce(427, targetTenths = 200))
                runCurrent()
                assertEquals(20.0, rig.trainer().activeWeightLb!!, 0.001)
                rig.link().send(packet)
                runCurrent()
                assertNull(rig.trainer().activeWeightLb)
                assertNull(rig.trainer().resistanceTargetLb)
                assertEquals(42.7, rig.trainer().measuredForceLb!!, 0.001)
                assertEquals(30, rig.trainer().setWeightLb)
            }
            rig.link().send(terseForce(0xffff, targetTenths = 200))
            runCurrent()
            assertNull(rig.trainer().measuredForceLb)
            assertEquals(20.0, rig.trainer().activeWeightLb!!, 0.001)
            rig.link().send(report(VoltraProtocol.RUNTIME_WIRE_WEIGHT, -1, 2))
            runCurrent()
            assertNull(rig.trainer().runtimeWireWeightLb)
            rig.controller.close()
        }

    @Test
    fun `load transitions discard target and residual readings from the previous load state`() =
        runTest {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.FITNESS] = 5
            rig.ready()
            rig.link().send(terseForce(427, targetTenths = 300))
            runCurrent()
            rig.link().send(report(VoltraProtocol.FITNESS, 4, 2))
            runCurrent()
            assertEquals(0.0, rig.trainer().activeWeightLb!!, 0.001)
            assertEquals(ActiveWeightSource.UNLOADED, rig.trainer().activeWeightSource)
            assertNull(rig.trainer().measuredForceLb)
            assertNull(rig.trainer().resistanceTargetLb)
            rig.link().send(terseForce(11, targetTenths = 300))
            rig.link().send(report(VoltraProtocol.FITNESS, 5, 2))
            runCurrent()
            assertNull(rig.trainer().activeWeightLb)
            assertNull(rig.trainer().measuredForceLb)
            rig.link().send(terseForce(200, targetTenths = 300))
            runCurrent()
            assertEquals(30.0, rig.trainer().activeWeightLb!!, 0.001)
            for (unknown in listOf(0, 6, 65535)) {
                rig.link().send(report(VoltraProtocol.FITNESS, unknown, 2))
                rig.link().send(terseForce(11, targetTenths = 300))
                runCurrent()
                assertNull(rig.trainer().activeWeightLb)
                assertNull(rig.trainer().activeWeightSource)
            }
            rig.controller.close()
        }

    @Test
    fun `mode and endpoint changes invalidate every live source immediately`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.FITNESS] = 5
        rig.ready()
        fun sendReadings() {
            rig.link().send(report(VoltraProtocol.RUNTIME_WIRE_WEIGHT, 20, 2))
            rig.link().send(terseForce(427, targetTenths = 300))
        }
        sendReadings()
        runCurrent()
        rig.link().send(report(VoltraProtocol.MODE, 2, 2))
        runCurrent()
        assertNull(rig.trainer().activeWeightLb)
        assertNull(rig.trainer().measuredForceLb)
        assertNull(rig.trainer().runtimeWireWeightLb)
        sendReadings()
        rig.link().send(endpointReport(LEFT_ADDRESS, RIGHT_ADDRESS))
        sendReadings()
        rig.link().send(endpointReport(LEFT_ADDRESS))
        runCurrent()
        assertNull(rig.trainer().activeWeightLb)
        assertNull(rig.trainer().resistanceTargetLb)
        sendReadings()
        runCurrent()
        assertEquals(30.0, rig.trainer().activeWeightLb!!, 0.001)
        rig.link().send(report(VoltraProtocol.MODE, 8, 1))
        sendReadings()
        runCurrent()
        assertNull(rig.trainer().mode)
        assertNull(rig.trainer().activeWeightLb)
        assertNull(rig.trainer().measuredForceLb)
        rig.controller.close()
    }

    @Test
    fun `healthy status cannot keep resistance visible when endpoint identity expires`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.FITNESS] = 5
        rig.ready()
        rig.link().respondEndpoint = false
        advanceTimeBy(4000)
        runCurrent()
        rig.link().send(terseForce(427, targetTenths = 300))
        runCurrent()
        assertTrue(rig.trainer().fresh)
        assertFalse(rig.trainer().twinStatusKnown)
        assertNull(rig.trainer().activeWeightLb)
        rig.link().send(endpointReport(LEFT_ADDRESS))
        runCurrent()
        assertNull(rig.trainer().activeWeightLb)
        rig.link().send(terseForce(285, targetTenths = 300))
        runCurrent()
        assertEquals(30.0, rig.trainer().activeWeightLb!!, 0.001)
        rig.controller.close()
    }

    @Test
    fun `direct trainers retain their own display units across readbacks`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.WEIGHT_UNIT] = 1
        rig.link("right").values[VoltraProtocol.WEIGHT_UNIT] = 0
        rig.ready(2)
        assertEquals(WeightUnit.KILOGRAMS, rig.trainer().weightUnit)
        assertEquals(WeightUnit.POUNDS, rig.trainer("right").weightUnit)
        rig.link().send(report(VoltraProtocol.WEIGHT_UNIT, 7, 4))
        runCurrent()
        assertNull(rig.trainer().weightUnit)
        assertEquals(WeightUnit.POUNDS, rig.trainer("right").weightUnit)
        assertEquals(30, rig.trainer().setWeightLb)
        rig.controller.close()
    }

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
    fun `mode changes invalidate cached settings until the new mode reports them again`() =
        runTest {
            val rig = Rig(this)
            rig.ready()
            assertEquals(30, rig.trainer().parameters[Parameter.WEIGHT])
            rig.link().send(report(VoltraProtocol.MODE, TrainingMode.BAND.wireValue, 2))
            runCurrent()
            assertEquals(TrainingMode.BAND, rig.trainer().mode)
            assertTrue(rig.trainer().parameters.isEmpty())

            rig.link().send(report(Parameter.BAND_MAXIMUM.register, 90, 2))
            runCurrent()
            assertEquals(mapOf(Parameter.BAND_MAXIMUM to 90), rig.trainer().parameters)
            // Ordinary repeat mode/status reports do not erase current confirmed settings.
            rig.link().send(report(VoltraProtocol.MODE, TrainingMode.BAND.wireValue, 2))
            rig.link().send(report(VoltraProtocol.FITNESS, 5, 2))
            runCurrent()
            assertEquals(90, rig.trainer().parameters[Parameter.BAND_MAXIMUM])

            // Switch back inside the 3.5-second cache lifetime; the former 30 lb is unavailable.
            rig.link().send(report(VoltraProtocol.MODE, TrainingMode.WEIGHT.wireValue, 2))
            runCurrent()
            assertTrue(rig.trainer().parameters.isEmpty())
            assertNull(rig.trainer().setWeightLb)
            rig.link().send(report(Parameter.WEIGHT.register, 55, 2))
            runCurrent()
            assertEquals(mapOf(Parameter.WEIGHT to 55), rig.trainer().parameters)
            assertFalse(rig.trainer().canEdit(Parameter.ECCENTRIC))
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
        assertEquals(28.5, rig.trainer().measuredForceLb!!, 0.001)
        advanceTimeBy(1_800)
        runCurrent()
        assertNull(rig.trainer().measuredForceLb)
        assertEquals(0.0, rig.trainer().activeWeightLb!!, 0.001)
        assertTrue(rig.trainer().fresh)
        rig.controller.close()
    }

    @Test
    fun `stale status disables writes and timeout clears live data`() = runTest {
        val rig = Rig(this)
        rig.ready()
        rig.link().send(rep(4, rawCounter = 7))
        runCurrent()
        assertEquals(8, rig.trainer().deviceReps)
        rig.link().respond = false
        advanceTimeBy(4_000)
        runCurrent()
        assertFalse(rig.trainer().fresh)
        assertNull(rig.trainer().deviceReps)
        assertTrue(
            runCatching { rig.controller.setParameter("left", Parameter.WEIGHT, 50) }.isFailure
        )
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(ConnectionStatus.FAILED, rig.trainer().status)
        assertNull(rig.trainer().activeWeightLb)
        assertNull(rig.trainer().deviceReps)
        rig.controller.disconnect("left")
        assertTrue(rig.controller.state.value.trainers.isEmpty())
        rig.controller.close()
    }

    @Test
    fun `captured eight-rep phase sequence counts each pull and return as one rep`() = runTest {
        val rig = Rig(this)
        rig.ready()
        // Physical TV capture, set 9: pull 0, return 1, ... pull 7, return 8.
        // Each notification repeats safely; delayed pulls cannot decrease the count.
        for (index in 0..7) {
            rig.link().send(rep(9, rawCounter = index, phase = 1))
            runCurrent()
            assertEquals(index + 1, rig.trainer().reps)
            rig.link().send(rep(9, rawCounter = index + 1, phase = 2))
            runCurrent()
            assertEquals(index + 1, rig.trainer().reps)
            rig.link().send(rep(9, rawCounter = index + 1, phase = 2))
            rig.link().send(rep(9, rawCounter = index, phase = 1))
            if (index > 0) rig.link().send(rep(9, rawCounter = index - 1, phase = 1))
            runCurrent()
            assertEquals(index + 1, rig.trainer().reps)
        }
        rig.link().send(rep(9, rawCounter = 99, phase = 3))
        runCurrent()
        assertEquals(8, rig.trainer().reps)
        assertEquals(8, rig.trainer().deviceReps)
        rig.controller.close()
    }

    @Test
    fun `return counts preserve explicit set baselines and device set counter wrap`() = runTest {
        val rig = Rig(this)
        rig.ready()
        rig.link().send(rep(65535, rawCounter = 7, phase = 2))
        runCurrent()
        assertEquals(7, rig.trainer().reps)
        assertEquals(7, rig.trainer().deviceReps)
        rig.controller.beginSet()
        assertEquals(0, rig.trainer().reps)
        assertEquals(7, rig.trainer().deviceReps)
        rig.link().send(rep(65535, rawCounter = 6, phase = 1))
        rig.link().send(rep(65535, rawCounter = 8, phase = 2))
        runCurrent()
        assertEquals(1, rig.trainer().reps)
        assertEquals(8, rig.trainer().deviceReps)
        rig.link().send(rep(0, rawCounter = 0, phase = 1))
        rig.link().send(rep(0, rawCounter = 1, phase = 2))
        runCurrent()
        assertEquals(1, rig.trainer().reps)
        assertEquals(1, rig.trainer().deviceReps)
        rig.link().send(rep(65535, rawCounter = 99, phase = 2))
        rig.link().send(rep(0, rawCounter = 0, phase = 1))
        runCurrent()
        assertEquals(1, rig.trainer().reps)
        assertEquals(1, rig.trainer().deviceReps)
        rig.controller.close()
    }

    @Test
    fun `native Twin never inherits the independent device rep count`() = runTest {
        val rig = Rig(this)
        rig.ready()
        rig.link().send(rep(4, rawCounter = 7))
        runCurrent()
        assertEquals(8, rig.trainer().deviceReps)
        rig.link().send(endpointReport(LEFT_ADDRESS, RIGHT_ADDRESS))
        runCurrent()
        assertNotNull(rig.controller.state.value.twin)
        assertTrue(rig.controller.state.value.trainers.all { it.deviceReps == null })
        rig.link().send(rep(4, rawCounter = 8))
        runCurrent()
        assertTrue(rig.controller.state.value.trainers.all { it.deviceReps == null })
        rig.link().send(endpointReport(LEFT_ADDRESS))
        runCurrent()
        assertNull(rig.trainer().deviceReps)
        rig.link().send(rep(5, rawCounter = 0))
        runCurrent()
        assertEquals(1, rig.trainer().deviceReps)
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
        rig.link().send(rep(5, rawCounter = 0))
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
    fun `idle trainer can select a mode independently of a connected weight trainer`() = runTest {
        for (fitness in listOf(0, 4)) {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.MODE] = 0
            rig.link().values[VoltraProtocol.FITNESS] = fitness
            rig.ready(2)
            assertNull(rig.trainer().mode)
            assertEquals(0, rig.trainer().modeWireValue)
            assertTrue(rig.trainer().parameters.isEmpty())
            assertTrue(rig.trainer().canChangeMode)
            assertEquals(TrainingMode.WEIGHT, rig.trainer("right").mode)
            assertTrue(rig.trainer("right").canChangeMode)

            val selection = async { rig.controller.setMode("left", TrainingMode.BAND) }
            advanceTimeBy(250)
            runCurrent()
            selection.await()
            assertEquals(TrainingMode.BAND, rig.trainer().mode)
            assertEquals(TrainingMode.BAND.wireValue, rig.trainer().modeWireValue)
            assertFalse(rig.trainer().pending)
            assertEquals(TrainingMode.WEIGHT, rig.trainer("right").mode)
            assertTrue(
                rig.link("right").writes.none {
                    it.command == 0x11 && it.register == VoltraProtocol.MODE
                }
            )
            rig.controller.close()
        }
    }

    @Test
    fun `unsupported mode remains read only even with idle fitness status`() = runTest {
        for (fitness in listOf(0, 4)) {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.MODE] = 99
            rig.link().values[VoltraProtocol.FITNESS] = fitness
            rig.ready()
            assertEquals(ConnectionStatus.READY, rig.trainer().status)
            assertNull(rig.trainer().mode)
            assertEquals(99, rig.trainer().modeWireValue)
            assertFalse(rig.trainer().canChangeMode)
            assertTrue(runCatching { rig.controller.setMode("left", TrainingMode.BAND) }.isFailure)
            assertTrue(
                rig.link().writes.none { it.command == 0x11 && it.register == VoltraProtocol.MODE }
            )
            rig.controller.close()
        }
    }

    @Test
    fun `idle mode cannot bypass loaded or armed fitness status`() = runTest {
        for (fitness in listOf(5, 21, 0x26, 0x27)) {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.MODE] = 0
            rig.link().values[VoltraProtocol.FITNESS] = fitness
            rig.ready()
            assertEquals(0, rig.trainer().modeWireValue)
            assertFalse(rig.trainer().canChangeMode)
            assertTrue(runCatching { rig.controller.setMode("left", TrainingMode.BAND) }.isFailure)
            assertTrue(
                rig.link().writes.none { it.command == 0x11 && it.register == VoltraProtocol.MODE }
            )
            rig.controller.close()
        }
    }

    @Test
    fun `idle mode needs both mode readback and known topology`() = runTest {
        for (missingMode in listOf(false, true)) {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.FITNESS] = 0
            if (missingMode) rig.link().values.remove(VoltraProtocol.MODE)
            else {
                rig.link().values[VoltraProtocol.MODE] = 0
                rig.link().respondEndpoint = false
            }
            rig.ready()
            if (missingMode) assertNull(rig.trainer().modeWireValue)
            else assertFalse(rig.trainer().twinStatusKnown)
            assertFalse(rig.trainer().canChangeMode)
            assertTrue(runCatching { rig.controller.setMode("left", TrainingMode.BAND) }.isFailure)
            assertTrue(
                rig.link().writes.none { it.command == 0x11 && it.register == VoltraProtocol.MODE }
            )
            rig.controller.close()
        }
    }

    @Test
    fun `idle mode needs fresh fitness mode and topology readback`() = runTest {
        for (staleRegister in listOf(VoltraProtocol.MODE, VoltraProtocol.FITNESS, null)) {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.MODE] = 0
            rig.ready()
            assertTrue(rig.trainer().canChangeMode)
            if (staleRegister == null) rig.link().respondEndpoint = false
            else rig.link().values.remove(staleRegister)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(0, rig.trainer().modeWireValue)
            assertFalse(rig.trainer().canChangeMode)
            assertTrue(runCatching { rig.controller.setMode("left", TrainingMode.BAND) }.isFailure)
            assertTrue(
                rig.link().writes.none { it.command == 0x11 && it.register == VoltraProtocol.MODE }
            )
            rig.controller.close()
        }
    }

    @Test
    fun `native pair sends controller identity to peer and confirms topology before ready`() =
        runTest {
            val rig = Rig(this)
            rig.ready(2)
            val pair = async { rig.controller.createTwin("left", "right") }
            advanceTimeBy(1500)
            runCurrent()
            pair.await()
            val state = rig.controller.state.value
            assertEquals("left", state.twin?.controllerId)
            assertEquals("right", state.twin?.peerId)
            assertEquals(TwinHealth.READY, state.twin?.health)
            assertTrue(rig.link("right").writes.any { it.command == VoltraProtocol.TWIN_COMMAND })
            assertTrue(rig.link("left").writes.none { it.command == VoltraProtocol.TWIN_COMMAND })
            assertEquals(TwinRole.PEER, rig.trainer("right").twinRole)
            assertFalse(rig.trainer("right").canChangeMode)
            assertTrue(state.canExitTwin)
            // A native single-device force packet must never be doubled into pair telemetry.
            rig.link().send(force(300))
            runCurrent()
            assertNull(rig.controller.state.value.twin?.activeWeightLb)
            assertNull(rig.trainer().activeWeightLb)
            rig.controller.close()
        }

    @Test
    fun `already paired controller discovers peer without a second BLE connection`() = runTest {
        val rig = Rig(this)
        rig.link().peerAddress = RIGHT_ADDRESS
        rig.ready()
        val state = rig.controller.state.value
        assertEquals(2, state.trainers.size)
        assertEquals(RIGHT_ADDRESS, state.twin?.peerId)
        assertEquals(TwinHealth.READY, state.twin?.health)
        assertEquals(70, state.trainers.last().batteryPercent)
        assertTrue(rig.link("right").writes.isEmpty())
        rig.controller.close()
    }

    @Test
    fun `Twin exit confirms independent status then reconnects peer`() = runTest {
        val rig = Rig(this)
        rig.ready(2)
        val pair = async { rig.controller.createTwin("left", "right") }
        advanceTimeBy(1500)
        runCurrent()
        pair.await()
        val exit = async { rig.controller.exitTwin() }
        advanceTimeBy(5500)
        runCurrent()
        exit.await()
        assertNull(rig.controller.state.value.twin)
        assertTrue(
            rig.controller.state.value.trainers.all { it.connected && it.fresh && !it.twinReported }
        )
        assertEquals(2, rig.controller.state.value.trainers.size)
        rig.controller.close()
    }

    @Test
    fun `exit waits for peer advertisement and never replays unpair when peer stays offline`() =
        runTest {
            val rig = Rig(this)
            rig.ready(2)
            val pair = async { rig.controller.createTwin("left", "right") }
            advanceTimeBy(1500)
            runCurrent()
            pair.await()
            rig.advertised.value = emptyList()
            val before = rig.connectionAttempts.size
            val exit = async { runCatching { rig.controller.exitTwin() } }
            advanceTimeBy(4000)
            runCurrent()
            assertNull(rig.controller.state.value.twin)
            assertEquals(before, rig.connectionAttempts.size)
            assertFalse(exit.isCompleted)
            advanceTimeBy(14000)
            runCurrent()
            assertTrue(exit.await().isFailure)
            assertEquals(1, rig.link().writes.count { it.command == VoltraProtocol.TWIN_COMMAND })
            assertNull(rig.controller.state.value.twin)
            assertTrue(rig.trainer().connected)
            rig.controller.close()
        }

    @Test
    fun `lost Twin endpoint status preserves degraded pair and blocks controls`() = runTest {
        val rig = Rig(this)
        rig.link().peerAddress = RIGHT_ADDRESS
        rig.ready()
        rig.link().respondEndpoint = false
        advanceTimeBy(4000)
        runCurrent()
        assertEquals(TwinHealth.DEGRADED, rig.controller.state.value.twin?.health)
        assertFalse(rig.controller.state.value.canExitTwin)
        assertFalse(rig.trainer().canChangeMode)
        assertTrue(
            runCatching { rig.controller.setParameter("left", Parameter.WEIGHT, 45) }.isFailure
        )
        rig.controller.close()
    }

    @Test
    fun `Twin acknowledgment alone never creates a pair`() = runTest {
        val rig = Rig(this)
        rig.ready(2)
        rig.link("right").applyTwin = false
        val pairing = async { runCatching { rig.controller.createTwin("left", "right") } }
        advanceTimeBy(16000)
        runCurrent()
        assertTrue(pairing.await().isFailure)
        assertNull(rig.controller.state.value.twin)
        assertFalse(rig.controller.state.value.twinPending)
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

    @Test
    fun `forget cancels pending settings and removes only the independent target`() = runTest {
        val rig = Rig(this)
        rig.ready(2)
        rig.link().acceptSettings = false
        val update = async {
            runCatching { rig.controller.setParameter("left", Parameter.WEIGHT, 45) }
        }
        runCurrent()
        val writes = rig.link().writes.size
        rig.controller.forget("left")
        runCurrent()
        assertTrue(update.isCompleted)
        assertTrue(update.await().isFailure)
        assertTrue(rig.link().closed)
        assertEquals(listOf("right"), rig.controller.state.value.trainers.map { it.device.id })
        advanceTimeBy(500)
        assertEquals(writes, rig.link().writes.size)
        rig.controller.close()
    }

    @Test
    fun `forget native Twin peer closes local pair without changing hardware topology`() = runTest {
        val rig = Rig(this)
        rig.link().peerAddress = RIGHT_ADDRESS
        rig.ready()
        val writes = rig.link().writes.size
        rig.controller.forget(RIGHT_ADDRESS)
        runCurrent()
        assertTrue(rig.controller.state.value.trainers.isEmpty())
        assertNull(rig.controller.state.value.twin)
        assertTrue(rig.link().closed)
        assertEquals(RIGHT_ADDRESS, rig.link().peerAddress)
        assertEquals(writes, rig.link().writes.size)
        assertTrue(rig.link().writes.none { it.command == VoltraProtocol.TWIN_COMMAND })
        rig.controller.close()
    }

    @Test
    fun `forget during Twin exit discovery prevents delayed peer reconnect`() = runTest {
        val rig = Rig(this)
        rig.ready(2)
        val pair = async { rig.controller.createTwin("left", "right") }
        advanceTimeBy(1500)
        runCurrent()
        pair.await()
        rig.advertised.value = emptyList()
        val exit = async { runCatching { rig.controller.exitTwin() } }
        advanceTimeBy(4000)
        runCurrent()
        assertNull(rig.controller.state.value.twin)
        assertFalse(exit.isCompleted)
        val attempts = rig.connectionAttempts.size
        val writes = rig.link().writes.size
        rig.controller.forget("right")
        rig.advertised.value = listOf(VoltraDevice("right", "Right"))
        runCurrent()
        assertTrue(exit.await().isFailure)
        assertTrue(rig.controller.state.value.trainers.isEmpty())
        assertFalse(rig.controller.state.value.twinPending)
        assertEquals(attempts, rig.connectionAttempts.size)
        assertEquals(writes, rig.link().writes.size)
        rig.controller.close()
    }

    @Test
    fun `forget invalidates a Twin request queued behind an unconfirmed pair`() = runTest {
        val rig = Rig(this)
        rig.ready(2)
        rig.link("right").applyTwin = false
        val pairing = async { runCatching { rig.controller.createTwin("left", "right") } }
        runCurrent()
        val queued = async { runCatching { rig.controller.createTwin("left", "right") } }
        runCurrent()
        assertFalse(pairing.isCompleted)
        assertFalse(queued.isCompleted)
        val peerLink = rig.link("right")
        rig.controller.forget("right")
        runCurrent()
        assertTrue(pairing.await().isFailure)
        val queuedFailure = queued.await().exceptionOrNull()
        assertNotNull(queuedFailure)
        assertEquals("Device connections changed.", queuedFailure!!.message)
        assertEquals(1, peerLink.writes.count { it.command == VoltraProtocol.TWIN_COMMAND })
        assertTrue(rig.controller.state.value.trainers.isEmpty())
        rig.ready(2)
        assertTrue(rig.link("right").writes.none { it.command == VoltraProtocol.TWIN_COMMAND })
        assertNull(rig.controller.state.value.twin)
        rig.controller.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Rig(val scope: TestScope) {
    private val links = mutableMapOf<String, FakeLink>()
    val advertised =
        MutableStateFlow(listOf(VoltraDevice("left", "Left"), VoltraDevice("right", "Right")))
    val connectionAttempts = mutableListOf<String>()

    init {
        links["left"] = newLink(LEFT_ADDRESS)
        links["right"] = newLink(RIGHT_ADDRESS)
    }

    private fun newLink(address: String): FakeLink =
        FakeLink(address).also { link ->
            link.onTwin = { operation, remote ->
                if (operation == 1) {
                    links.values.single { it.address == remote }.peerAddress = link.address
                } else {
                    link.peerAddress = ZERO_ADDRESS
                }
            }
        }

    private val transport =
        object : VoltraTransport {
            override fun scan() = advertised

            override suspend fun connect(device: VoltraDevice) =
                links.getValue(device.id).let {
                    connectionAttempts += device.id
                    if (it.closed) newLink(it.address).also { next -> links[device.id] = next }
                    else it
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

private const val LEFT_ADDRESS = "02:00:00:00:00:01"
private const val RIGHT_ADDRESS = "02:00:00:00:00:02"
private const val ZERO_ADDRESS = "00:00:00:00:00:00"

private class FakeLink(val address: String) : VoltraLink {
    var peerAddress = ZERO_ADDRESS
    var respondEndpoint = true
    var applyTwin = true
    var onTwin: (Int, String) -> Unit = { _, _ -> }
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
        if (command == VoltraProtocol.ENDPOINT_STATUS && respond && respondEndpoint)
            send(endpointReport(address, peerAddress))
        if (command == VoltraProtocol.TWIN_COMMAND && respond) {
            val operation = frame[11].toInt()
            val remote =
                (12..17).joinToString(":") {
                    (frame[it].toInt() and 255).toString(16).padStart(2, '0').uppercase()
                }
            if (applyTwin) onTwin(operation, remote)
            send(
                VoltraProtocol.seal(
                    ByteArray(16).also {
                        it[0] = 0x55
                        it[2] = 8
                        it[6] = frame[6]
                        it[7] = frame[7]
                        it[10] = VoltraProtocol.TWIN_COMMAND.toByte()
                        it[12] = operation.toByte()
                    }
                )
            )
            if (operation == 1 && applyTwin) close()
        }
        if (command == 0x0f && respond)
            values[register]?.let { value ->
                val width =
                    Parameter.entries.find { it.register == register }?.width
                        ?: if (
                            register in
                                listOf(VoltraProtocol.FITNESS, VoltraProtocol.RUNTIME_WIRE_WEIGHT)
                        )
                            2
                        else 1
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

internal fun endpointReport(
    local: String,
    peer: String = "00:00:00:00:00:00",
    response: Boolean = false,
): ByteArray {
    val payload = if (response) 12 else 11
    return VoltraProtocol.seal(
        ByteArray(if (response) 36 else 35).also { frame ->
            frame[0] = 0x55
            frame[2] = if (response) 8 else 4
            frame[10] = VoltraProtocol.ENDPOINT_STATUS.toByte()
            frame[payload] = 80
            frame[payload + 2] = if (peer == "00:00:00:00:00:00") 0 else 0x12
            local.split(':').forEachIndexed { index, part ->
                frame[payload + 3 + index] = part.toInt(16).toByte()
            }
            peer.split(':').forEachIndexed { index, part ->
                frame[payload + 9 + index] = part.toInt(16).toByte()
            }
            frame[payload + 15] = 70
        }
    )
}
