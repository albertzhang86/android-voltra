package io.github.albertzhang86.voltra

import org.junit.Assert.*
import org.junit.Test

class TwinStateTest {
    private fun trainer(id: String) =
        TrainerState(
            VoltraDevice(id, id),
            ConnectionStatus.READY,
            TrainingMode.WEIGHT,
            mapOf(Parameter.WEIGHT to 30),
            fitnessMode = 4,
            fresh = true,
            twinStatusKnown = true,
        )

    @Test
    fun `pairing needs two distinct fresh unloaded independent trainers and a capable connector`() {
        val ready = VoltraState(listOf(trainer("left"), trainer("right")), twinSupported = true)
        assertTrue(ready.canCreateTwin)
        assertFalse(ready.copy(twinSupported = false).canCreateTwin)
        assertFalse(ready.copy(twinPending = true).canCreateTwin)
        assertFalse(ready.copy(trainers = listOf(trainer("left"), trainer("left"))).canCreateTwin)
        for (blocked in
            listOf(
                trainer("right").copy(fresh = false),
                trainer("right").copy(fitnessMode = 5),
                trainer("right").copy(twinReported = true),
                trainer("right").copy(twinStatusKnown = false),
            )) {
            assertFalse(ready.copy(trainers = listOf(trainer("left"), blocked)).canCreateTwin)
        }
    }

    @Test
    fun `a saved role cannot authorize Twin controls or exit`() {
        val main = trainer("left").copy(twinReported = true, twinRole = TwinRole.CONTROLLER)
        val peer = trainer("right").copy(twinReported = true, twinRole = TwinRole.PEER)
        val state = VoltraState(listOf(main, peer), TwinPair("left", "right"), twinSupported = true)
        assertFalse(main.canEdit(Parameter.WEIGHT))
        assertFalse(main.canChangeMode)
        assertFalse(state.canExitTwin)
        val verified = state.copy(trainers = state.trainers.map { it.copy(twinVerified = true) })
        assertTrue(verified.canExitTwin)
        assertTrue(verified.trainers.first().canEdit(Parameter.WEIGHT))
        assertFalse(verified.trainers.last().canEdit(Parameter.WEIGHT))
        assertFalse(verified.copy(twin = TwinPair("left", "different-peer")).canExitTwin)
        assertFalse(
            verified.copy(twin = verified.twin?.copy(health = TwinHealth.DEGRADED)).canExitTwin
        )
    }
}
