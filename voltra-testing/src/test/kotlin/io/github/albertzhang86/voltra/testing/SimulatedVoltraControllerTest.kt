package io.github.albertzhang86.voltra.testing

import io.github.albertzhang86.voltra.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedVoltraControllerTest {
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
        controller.beginSet()
        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(listOf(86, 72), controller.state.value.trainers.map { it.batteryPercent })
        assertTrue(
            controller.state.value.trainers.all {
                it.reps == 1 && it.activeWeightLb != null && it.isSimulated
            }
        )
        controller.beginSet()
        assertTrue(controller.state.value.trainers.all { it.reps == 0 })
        controller.close()
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
