package io.github.albertzhang86.voltra.testing

import io.github.albertzhang86.voltra.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedVoltraControllerTest {
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
        assertTrue(controller.state.value.trainers.all {
            it.activeWeightLb == 0.0 && it.activeWeightSource == ActiveWeightSource.UNLOADED
        })
        controller.beginSet()
        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(listOf(86, 72), controller.state.value.trainers.map { it.batteryPercent })
        assertTrue(
            controller.state.value.trainers.all {
                it.reps == 1 && it.activeWeightLb != null && it.isSimulated &&
                    it.activeWeightSource == ActiveWeightSource.TARGET_FORCE && it.loaded
            }
        )
        controller.beginSet()
        assertTrue(controller.state.value.trainers.all { it.reps == 0 })
        controller.close()
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
