package io.github.albertzhang86.voltra.testing

import io.github.albertzhang86.voltra.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MixedSimulationTest {
    @Test
    fun `direct and single-trainer knob demo retain independent modes and weights`() = runTest {
        val direct = SimulatedVoltraController(backgroundScope)
        val knob =
            SimulatedKnobController(
                backgroundScope,
                initialTrainers = listOf(SimulatedVoltraController.devices.last()),
            )
        val combined = MixedVoltraController(direct, knob, backgroundScope)
        combined.connect(SimulatedVoltraController.devices.first())
        combined.selectRoute(VoltraConnectionRoute.KNOB)
        combined.connect(SimulatedKnobController.DEVICE)
        runCurrent()
        assertTrue(combined.currentState.hasMixedConnections)
        assertNull(combined.currentState.connectionError)
        assertTrue(combined.currentState.canCreateTwin)

        combined.setParameter("demo-left", Parameter.WEIGHT, 45)
        combined.setMode("demo-right", TrainingMode.BAND)
        combined.setParameter("demo-right", Parameter.BAND_MAXIMUM, 60)
        runCurrent()
        assertEquals(TrainingMode.WEIGHT, direct.state.value.trainers.single().mode)
        assertEquals(45, direct.state.value.trainers.single().setWeightLb)
        assertEquals(TrainingMode.BAND, knob.state.value.trainers.single().mode)
        assertEquals(60, knob.state.value.trainers.single().setWeightLb)

        combined.selectRoute(VoltraConnectionRoute.DIRECT)
        combined.disconnect(SimulatedKnobController.DEVICE.id)
        runCurrent()
        assertEquals("demo-left", combined.currentState.trainers.single().device.id)
        assertEquals(45, combined.currentState.trainers.single().setWeightLb)
        combined.close()
    }

    @Test
    fun `mixed demo hands peer to native knob Twin then restores direct connection`() = runTest {
        val direct = SimulatedVoltraController(backgroundScope)
        val knob =
            SimulatedKnobController(
                backgroundScope,
                listOf(SimulatedVoltraController.devices.last()),
            )
        val combined = MixedVoltraController(direct, knob, backgroundScope)
        combined.connect(SimulatedVoltraController.devices.first())
        combined.selectRoute(VoltraConnectionRoute.KNOB)
        combined.connect(SimulatedKnobController.DEVICE)
        runCurrent()
        combined.createTwin("demo-left", "demo-right")
        assertEquals("demo-right", combined.currentState.twin?.controllerId)
        assertTrue(direct.state.value.trainers.isEmpty())
        combined.exitTwin()
        runCurrent()
        assertTrue(combined.currentState.hasMixedConnections)
        assertEquals("demo-left", direct.state.value.trainers.single().device.id)
        combined.close()
    }

    @Test
    fun `mode settings follow native Twin ownership and return to independent routes after exit`() =
        runTest {
            val direct = SimulatedVoltraController(backgroundScope)
            val knob =
                SimulatedKnobController(
                    backgroundScope,
                    listOf(SimulatedVoltraController.devices.last()),
                )
            val combined = MixedVoltraController(direct, knob, backgroundScope)
            combined.connect(SimulatedVoltraController.devices.first())
            combined.selectRoute(VoltraConnectionRoute.KNOB)
            combined.connect(SimulatedKnobController.DEVICE)
            runCurrent()
            combined.createTwin("demo-left", "demo-right")
            combined.selectRoute(VoltraConnectionRoute.DIRECT)
            combined.setMode("demo-right", TrainingMode.BAND)
            runCurrent()
            combined.setParameter("demo-right", Parameter.BAND_MAXIMUM, 60)
            runCurrent()
            assertTrue(direct.state.value.trainers.isEmpty())
            assertTrue(
                knob.state.value.trainers.all {
                    it.mode == TrainingMode.BAND && it.setWeightLb == 60
                }
            )
            assertEquals(60, combined.currentState.twin?.setWeightLb)

            combined.setMode("demo-right", TrainingMode.WEIGHT)
            runCurrent()
            combined.setParameter("demo-right", Parameter.WEIGHT, 35)
            runCurrent()
            assertEquals(70, combined.currentState.twin?.setWeightLb)
            combined.exitTwin()
            runCurrent()
            combined.selectRoute(VoltraConnectionRoute.KNOB)
            combined.setParameter("demo-left", Parameter.WEIGHT, 55)
            runCurrent()
            assertEquals(55, direct.state.value.trainers.single().setWeightLb)
            assertEquals(35, knob.state.value.trainers.single().setWeightLb)
            assertEquals(VoltraConnectionRoute.DIRECT, combined.currentState.routeFor("demo-left"))
            assertEquals(VoltraConnectionRoute.KNOB, combined.currentState.routeFor("demo-right"))
            combined.close()
        }

    @Test
    fun `single-trainer knob demo can pair a candidate and return to its controller`() = runTest {
        val knob =
            SimulatedKnobController(
                backgroundScope,
                initialTrainers = listOf(SimulatedVoltraController.devices.last()),
            )
        knob.connect(SimulatedKnobController.DEVICE)
        runCurrent()
        assertEquals(1, knob.state.value.trainers.size)
        assertTrue(knob.state.value.canCreateTwin)
        assertEquals("demo-left", knob.state.value.knob?.twinCandidate?.id)
        knob.createTwin("demo-right", "demo-left")
        runCurrent()
        assertEquals(2, knob.state.value.trainers.size)
        assertEquals("demo-right", knob.state.value.twin?.controllerId)
        knob.exitTwin()
        runCurrent()
        assertNull(knob.state.value.twin)
        assertEquals("demo-right", knob.state.value.trainers.single().device.id)
        assertTrue(knob.state.value.canCreateTwin)
        knob.close()
    }
}
