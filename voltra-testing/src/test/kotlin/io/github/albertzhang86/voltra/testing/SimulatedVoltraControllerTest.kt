package io.github.albertzhang86.voltra.testing

import io.github.albertzhang86.voltra.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedVoltraControllerTest {
    @Test
    fun `Twin band maximum is shared and total simulated force stays within that maximum`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        SimulatedVoltraController.devices.forEach { controller.connect(it) }
        controller.createTwin("demo-left", "demo-right")
        controller.setMode("demo-left", TrainingMode.BAND)
        controller.setParameter("demo-left", Parameter.BAND_MAXIMUM, 40)
        controller.beginSet()
        advanceTimeBy(5_100)
        runCurrent()
        val state = controller.state.value
        assertEquals(40, state.twin?.setWeightLb)
        assertTrue(state.trainers.all { it.setWeightLb == 40 })
        assertTrue(requireNotNull(state.twin?.activeWeightLb) in 0.0..40.0)
        controller.close()
    }

    @Test
    fun `finished demo sets become unloaded so the next exercise can enter and exit Twin`() =
        runTest {
            for (viaKnob in listOf(false, true)) {
                val controller: VoltraController =
                    if (viaKnob) SimulatedKnobController(backgroundScope)
                    else SimulatedVoltraController(backgroundScope)
                if (viaKnob) controller.connect(SimulatedKnobController.DEVICE)
                else SimulatedVoltraController.devices.forEach { controller.connect(it) }
                runCurrent()
                controller.setParameter("demo-left", Parameter.WEIGHT, 45)
                controller.beginSet()
                advanceTimeBy(5_100)
                runCurrent()
                assertTrue(controller.state.value.trainers.all { it.loaded && it.reps == 1 })
                assertFalse(controller.state.value.canCreateTwin)
                controller.endSet()
                runCurrent()
                val finished = controller.state.value.trainers
                assertTrue(finished.all { it.unloaded && it.activeWeightLb == 0.0 })
                assertTrue(finished.all { it.measuredForceLb == null && it.cableMoving == null })
                assertEquals(45, finished.first().setWeightLb)
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(
                    finished.map { it.reps },
                    controller.state.value.trainers.map { it.reps },
                )
                assertEquals(
                    finished.map { it.deviceReps },
                    controller.state.value.trainers.map { it.deviceReps },
                )
                assertTrue(controller.state.value.canCreateTwin)
                controller.createTwin("demo-left", "demo-right")
                controller.beginSet()
                runCurrent()
                assertFalse(controller.state.value.canExitTwin)
                advanceTimeBy(5_100)
                runCurrent()
                assertEquals(1, controller.state.value.twin?.reps)
                controller.endSet()
                runCurrent()
                assertTrue(controller.state.value.canExitTwin)
                controller.exitTwin()
                runCurrent()
                assertNull(controller.state.value.twin)
                assertTrue(controller.state.value.trainers.all { it.unloaded })
                controller.beginSet()
                advanceTimeBy(5_100)
                runCurrent()
                assertTrue(controller.state.value.trainers.all { it.loaded && it.reps == 1 })
                controller.close()
            }
        }

    @Test
    fun `Twin telemetry has one rep count and both batteries`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        SimulatedVoltraController.devices.forEach { controller.connect(it) }
        controller.createTwin("demo-right", "demo-left")
        advanceTimeBy(5_100)
        runCurrent()
        val pair = requireNotNull(controller.state.value.twin)
        assertEquals("demo-right", pair.controllerId)
        assertEquals(TwinHealth.READY, pair.health)
        assertEquals(1, pair.reps)
        assertTrue(controller.state.value.trainers.all { it.deviceReps == null })
        assertEquals(60, pair.setWeightLb)
        assertNotNull(pair.activeWeightLb)
        assertEquals(listOf(86, 72), controller.state.value.trainers.map { it.batteryPercent })
        assertFalse(controller.state.value.trainers.first().canEdit(Parameter.WEIGHT))
        controller.beginSet()
        assertEquals(0, controller.state.value.twin?.reps)
        controller.close()
    }

    @Test
    fun `peer loss preserves degraded Twin and blocks control until reconnection`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        SimulatedVoltraController.devices.forEach { controller.connect(it) }
        controller.createTwin("demo-left", "demo-right")
        controller.disconnect("demo-right")
        advanceTimeBy(1_000)
        runCurrent()
        val state = controller.state.value
        assertEquals(TwinHealth.DEGRADED, state.twin?.health)
        assertNull(state.twin?.activeWeightLb)
        assertNull(state.twin?.reps)
        assertTrue(state.trainers.all { it.deviceReps == null })
        assertFalse(state.canCreateTwin)
        assertFalse(state.canExitTwin)
        assertFalse(state.trainers.first().canEdit(Parameter.WEIGHT))
        assertTrue(
            runCatching { controller.setParameter("demo-left", Parameter.WEIGHT, 45) }.isFailure
        )
        assertTrue(runCatching { controller.exitTwin() }.isFailure)
        controller.connect(SimulatedVoltraController.devices.last())
        assertTrue(controller.state.value.canExitTwin)
        controller.exitTwin()
        assertTrue(controller.state.value.trainers.all { !it.twinReported && it.twinRole == null })
        controller.close()
    }

    @Test
    fun `disconnect during pair does not create a phantom Twin`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        SimulatedVoltraController.devices.forEach { controller.connect(it) }
        val pair = async { runCatching { controller.createTwin("demo-left", "demo-right") } }
        runCurrent()
        controller.disconnect("demo-right")
        controller.connect(SimulatedVoltraController.devices.last())
        advanceTimeBy(350)
        runCurrent()
        assertTrue(pair.await().isFailure)
        assertNull(controller.state.value.twin)
        assertFalse(controller.state.value.twinPending)
        controller.close()
    }

    @Test
    fun `knob demo supports pair modes and independent controls after exit`() = runTest {
        val knob = SimulatedKnobController(backgroundScope)
        knob.connect(SimulatedKnobController.DEVICE)
        runCurrent()
        assertTrue(knob.state.value.canCreateTwin)
        knob.createTwin("demo-left", "demo-right")
        for (mode in TrainingMode.entries) {
            knob.setMode("demo-left", mode)
            runCurrent()
            assertTrue(knob.state.value.trainers.all { it.mode == mode })
        }
        knob.exitTwin()
        knob.setMode("demo-right", TrainingMode.DAMPER)
        runCurrent()
        assertEquals(TrainingMode.ISOKINETIC, knob.state.value.trainers.first().mode)
        assertEquals(TrainingMode.DAMPER, knob.state.value.trainers.last().mode)
        knob.disconnect(SimulatedKnobController.DEVICE.id)
        runCurrent()
        assertNull(knob.state.value.knob)
        assertTrue(knob.state.value.trainers.isEmpty())
        knob.close()
    }

    @Test
    fun `two devices are independent until explicitly paired`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        SimulatedVoltraController.devices.forEach { controller.connect(it) }
        controller.setParameter("demo-left", Parameter.WEIGHT, 50)
        assertEquals(listOf(50, 30), controller.state.value.trainers.map { it.setWeightLb })
        assertNull(controller.state.value.twin)
        controller.createTwin("demo-left", "demo-right")
        assertEquals(listOf(50, 50), controller.state.value.trainers.map { it.setWeightLb })
        controller.setParameter("demo-left", Parameter.WEIGHT, 60)
        assertEquals(listOf(60, 60), controller.state.value.trainers.map { it.setWeightLb })
        assertTrue(
            runCatching { controller.setParameter("demo-right", Parameter.WEIGHT, 40) }.isFailure
        )
        controller.exitTwin()
        controller.setParameter("demo-right", Parameter.WEIGHT, 40)
        assertEquals(listOf(60, 40), controller.state.value.trainers.map { it.setWeightLb })
        controller.close()
    }

    @Test
    fun `all four modes preserve individual batteries and support live readings`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        SimulatedVoltraController.devices.forEach { controller.connect(it) }
        for (mode in TrainingMode.entries) {
            controller.setMode("demo-left", mode)
            assertEquals(mode, controller.state.value.trainers.first().mode)
            assertEquals(TrainingMode.WEIGHT, controller.state.value.trainers.last().mode)
        }
        assertTrue(
            controller.state.value.trainers.all {
                it.activeWeightLb == 0.0 && it.activeWeightSource == ActiveWeightSource.UNLOADED
            }
        )
        controller.beginSet()
        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(listOf(86, 72), controller.state.value.trainers.map { it.batteryPercent })
        assertTrue(
            controller.state.value.trainers.all {
                it.reps == 1 &&
                    it.activeWeightLb != null &&
                    it.isSimulated &&
                    it.activeWeightSource == ActiveWeightSource.TARGET_FORCE &&
                    it.loaded
            }
        )
        controller.beginSet()
        assertTrue(controller.state.value.trainers.all { it.reps == 0 })
        controller.close()
    }

    @Test
    fun `starting an app set preserves the independent demo device counter`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        controller.connect(SimulatedVoltraController.devices.first())
        assertEquals(0, controller.state.value.trainers.single().deviceReps)
        controller.beginSet()
        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(1, controller.state.value.trainers.single().deviceReps)
        assertEquals(1, controller.state.value.trainers.single().reps)

        controller.beginSet()
        assertEquals(1, controller.state.value.trainers.single().deviceReps)
        assertEquals(0, controller.state.value.trainers.single().reps)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(1, controller.state.value.trainers.single().deviceReps)
        assertEquals(0, controller.state.value.trainers.single().reps)
        advanceTimeBy(4_900)
        runCurrent()
        assertEquals(2, controller.state.value.trainers.single().deviceReps)
        assertEquals(1, controller.state.value.trainers.single().reps)
        controller.disconnect(SimulatedVoltraController.devices.first().id)
        assertTrue(controller.state.value.trainers.isEmpty())
        controller.close()
    }

    @Test
    fun `starting a set just before a device rep keeps the full planned cadence`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        controller.connect(SimulatedVoltraController.devices.first())
        advanceTimeBy(4_750)
        runCurrent()
        controller.beginSet()
        advanceTimeBy(250)
        runCurrent()
        assertEquals(1, controller.state.value.trainers.single().deviceReps)
        assertEquals(0, controller.state.value.trainers.single().reps)
        advanceTimeBy(4_749)
        runCurrent()
        assertEquals(0, controller.state.value.trainers.single().reps)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, controller.state.value.trainers.single().reps)
        assertEquals(1, controller.state.value.trainers.single().deviceReps)
        controller.close()
    }

    @Test
    fun `all modes publish motion only during loaded independent demo pulls`() = runTest {
        for (mode in TrainingMode.entries) {
            val controller = SimulatedVoltraController(backgroundScope)
            val device = SimulatedVoltraController.devices.first()
            controller.connect(device)
            controller.setMode(device.id, mode)
            assertNull(controller.state.value.trainers.single().measuredForceLb)
            assertNull(controller.state.value.trainers.single().cableMoving)

            controller.beginSet()
            assertNull(controller.state.value.trainers.single().measuredForceLb)
            advanceTimeBy(251)
            runCurrent()
            val moving = controller.state.value.trainers.single()
            assertTrue(moving.isSimulated && moving.loaded && moving.cableMoving == true)
            assertNotNull(moving.measuredForceLb)
            assertEquals(mode, moving.mode)

            advanceTimeBy(4_000)
            runCurrent()
            val resting = controller.state.value.trainers.single()
            assertTrue(resting.loaded)
            assertNull(resting.measuredForceLb)
            assertNull(resting.cableMoving)

            controller.beginSet()
            advanceTimeBy(251)
            runCurrent()
            assertNotNull(controller.state.value.trainers.single().measuredForceLb)
            controller.disconnect(device.id)
            assertTrue(controller.state.value.trainers.isEmpty())
            controller.connect(device)
            advanceTimeBy(251)
            runCurrent()
            assertNull(controller.state.value.trainers.single().measuredForceLb)
            assertNull(controller.state.value.trainers.single().cableMoving)
            controller.close()
        }
    }

    @Test
    fun `forget demo Twin clears both local devices and cannot revive queued changes`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        SimulatedVoltraController.devices.forEach { controller.connect(it) }
        controller.createTwin("demo-left", "demo-right")
        val edit = async {
            runCatching { controller.setParameter("demo-left", Parameter.WEIGHT, 55) }
        }
        runCurrent()
        val queued = async {
            runCatching { controller.setParameter("demo-left", Parameter.WEIGHT, 60) }
        }
        runCurrent()
        controller.forget("demo-right")
        assertTrue(controller.state.value.trainers.isEmpty())
        assertNull(controller.state.value.twin)
        SimulatedVoltraController.devices.forEach { controller.connect(it) }
        advanceTimeBy(400)
        runCurrent()
        assertTrue(edit.await().isFailure)
        assertTrue(queued.await().isFailure)
        assertEquals(listOf(30, 30), controller.state.value.trainers.map { it.setWeightLb })
        controller.close()
    }

    @Test
    fun `forget demo knob clears Twin and lets user explicitly connect again`() = runTest {
        val knob = SimulatedKnobController(backgroundScope)
        knob.connect(SimulatedKnobController.DEVICE)
        knob.createTwin("demo-left", "demo-right")
        runCurrent()
        knob.forget(SimulatedKnobController.DEVICE.id)
        runCurrent()
        assertNull(knob.state.value.knob)
        assertNull(knob.state.value.twin)
        assertTrue(knob.state.value.trainers.isEmpty())
        knob.connect(SimulatedKnobController.DEVICE)
        runCurrent()
        assertEquals(ConnectionStatus.READY, knob.state.value.knob?.status)
        assertTrue(knob.state.value.canCreateTwin)
        assertNull(knob.state.value.twin)
        knob.close()
    }

    @Test
    fun `closing during an edit does not resurrect devices`() = runTest {
        val controller = SimulatedVoltraController(backgroundScope)
        controller.connect(SimulatedVoltraController.devices.first())
        val edit = async {
            runCatching { controller.setParameter("demo-left", Parameter.WEIGHT, 55) }
        }
        runCurrent()
        controller.close()
        advanceTimeBy(200)
        runCurrent()
        assertTrue(edit.await().isFailure)
        assertTrue(controller.state.value.trainers.isEmpty())
        assertTrue(
            runCatching { controller.connect(SimulatedVoltraController.devices.first()) }.isFailure
        )
    }
}
