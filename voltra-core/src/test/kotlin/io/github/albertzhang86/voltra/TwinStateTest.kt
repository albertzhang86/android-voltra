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
    fun `captured flagged ready status permits controls without weakening readiness gates`() {
        for (fitness in listOf(4, 0x0104)) {
            val ready = trainer("left").copy(fitnessMode = fitness)
            assertEquals(fitness, ready.fitnessMode)
            assertTrue(ready.unloaded)
            assertFalse(ready.loaded)
            assertTrue(ready.canChangeMode)
            assertTrue(ready.canEdit(Parameter.WEIGHT))
            for (blocked in
                listOf(
                    ready.copy(fresh = false),
                    ready.copy(pending = true),
                    ready.copy(twinStatusKnown = false),
                    ready.copy(status = ConnectionStatus.CONNECTING),
                    ready.copy(twinReported = true, twinVerified = false),
                    ready.copy(twinReported = true, twinVerified = true, twinRole = TwinRole.PEER),
                )) {
                assertFalse(blocked.canChangeMode)
                assertFalse(blocked.canEdit(Parameter.WEIGHT))
            }
        }
        for (unknown in listOf(null, -1, 0x0204, 0xff04, 0x0105, 0x0126, 65535)) {
            val blocked = trainer("left").copy(fitnessMode = unknown)
            assertFalse(blocked.unloaded)
            assertFalse(blocked.loaded)
            assertFalse(blocked.canChangeMode)
            assertFalse(blocked.canEdit(Parameter.WEIGHT))
        }
        assertTrue(trainer("left").copy(fitnessMode = 0).unloaded)
    }

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
    fun `Twin exit accepts MAC casing changes but requires two exact verified roles`() {
        val controllerId = "28:37:2F:60:A1:E6"
        val peerId = "80:65:99:92:7E:E2"
        val controller =
            trainer(controllerId.lowercase())
                .copy(
                    twinReported = true,
                    twinVerified = true,
                    twinRole = TwinRole.CONTROLLER,
                    loadControlSupported = true,
                    unloadAllowed = true,
                )
        val peer =
            trainer(peerId)
                .copy(twinReported = true, twinVerified = true, twinRole = TwinRole.PEER)
        val state =
            VoltraState(
                trainers = listOf(controller, peer),
                twin = TwinPair(controllerId, peerId.lowercase()),
                twinSupported = true,
                knob =
                    KnobState(
                        VoltraDevice("knob", "Knob"),
                        ConnectionStatus.READY,
                        twinExitAllowed = true,
                    ),
                trainerRoutes =
                    mapOf(
                        controllerId to VoltraConnectionRoute.KNOB,
                        peerId.lowercase() to VoltraConnectionRoute.KNOB,
                    ),
            )
        assertTrue(state.canExitTwin)
        val loaded = state.copy(trainers = state.trainers.map { it.copy(fitnessMode = 5) })
        assertFalse(loaded.canExitTwin)
        assertTrue(loaded.canExitTwinAfterUnloading)
        for (invalidPeer in
            listOf(
                peer.copy(device = peer.device.copy(id = "80:65:99:92:7e:e3")),
                peer.copy(device = peer.device.copy(id = controllerId)),
                peer.copy(twinRole = TwinRole.CONTROLLER),
                peer.copy(twinVerified = false),
                peer.copy(fresh = false),
            )) {
            assertFalse(state.copy(trainers = listOf(controller, invalidPeer)).canExitTwin)
        }
        assertTrue(
            runCatching { TwinPair(controllerId, controllerId.lowercase()) }.exceptionOrNull() is
                IllegalArgumentException
        )
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
