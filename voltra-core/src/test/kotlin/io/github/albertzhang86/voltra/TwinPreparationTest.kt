package io.github.albertzhang86.voltra

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TwinPreparationTest {
    @Test
    fun `manual create confirms both unloads before native create without loading`() = runTest {
        val controller = Fake()
        assertFalse(controller.state.value.canCreateTwin)
        assertTrue(controller.state.value.canCreateTwinAfterUnloading)
        controller.createTwinAfterUnloading("left", "right")
        assertEquals(listOf("unload:left", "unload:right", "create:left:right"), controller.calls)
    }

    @Test
    fun `all participants are validated before the first STOP`() = runTest {
        for (bad in
            listOf(
                ready("right").copy(fresh = false),
                ready("right").copy(unloadAllowed = false),
                ready("right").copy(fitnessMode = 0x105),
                ready("right").copy(twinStatusKnown = false),
            )) {
            val controller =
                Fake(VoltraState(trainers = listOf(ready("left"), bad), twinSupported = true))
            assertFalse(controller.state.value.canCreateTwinAfterUnloading)
            assertTrue(
                runCatching { controller.createTwinAfterUnloading("left", "right") }.isFailure
            )
            assertTrue(controller.calls.isEmpty())
        }
    }

    @Test
    fun `manual exit unloads only native controller and waits for confirmed pair readiness`() =
        runTest {
            val controller = Fake(pairState())
            assertFalse(controller.state.value.canExitTwin)
            assertTrue(controller.state.value.canExitTwinAfterUnloading)
            controller.exitTwinAfterUnloading()
            assertEquals(listOf("unload:left", "exit"), controller.calls)
        }

    @Test
    fun `unloaded topology requests do not send motor commands`() = runTest {
        val controller =
            Fake(
                VoltraState(
                    trainers = listOf(ready("left", false), ready("right", false)),
                    twinSupported = true,
                )
            )
        controller.createTwinAfterUnloading("left", "right")
        controller.exitTwinAfterUnloading()
        assertEquals(listOf("create:left:right", "exit"), controller.calls)
    }

    @Test
    fun `failed or unconfirmed STOP never reaches next trainer or pairing`() = runTest {
        for (fail in listOf(true, false)) {
            val controller = Fake()
            controller.stopFails = fail
            controller.confirmStop = fail
            assertTrue(
                runCatching { controller.createTwinAfterUnloading("left", "right") }.isFailure
            )
            assertEquals(listOf("unload:left"), controller.calls)
        }
    }

    @Test
    fun `identity mode route epoch or relevance change between unloads stops sequence`() = runTest {
        for (change in 0..4) {
            val controller = Fake()
            var relevant = true
            controller.afterStop = {
                val state = controller.state.value
                controller.state.value =
                    when (change) {
                        0 ->
                            state.copy(
                                trainers =
                                    state.trainers.map {
                                        if (it.device.id == "right")
                                            it.copy(device = VoltraDevice("other", "Other"))
                                        else it
                                    }
                            )
                        1 ->
                            state.copy(
                                trainers =
                                    state.trainers.map {
                                        if (it.device.id == "right")
                                            it.copy(mode = TrainingMode.BAND)
                                        else it
                                    }
                            )
                        2 ->
                            state.copy(trainerRoutes = mapOf("right" to VoltraConnectionRoute.KNOB))
                        3 -> state.copy(telemetryEpoch = 1)
                        else -> {
                            relevant = false
                            state
                        }
                    }
            }
            assertTrue(
                runCatching { controller.createTwinAfterUnloading("left", "right") { relevant } }
                    .isFailure
            )
            assertEquals(listOf("unload:left"), controller.calls)
        }
    }

    @Test
    fun `relevance cancellation stays active during native handshake waiting`() = runTest {
        val controller = Fake()
        var relevant = true
        controller.waitInCreate = true
        val operation = async {
            runCatching { controller.createTwinAfterUnloading("left", "right") { relevant } }
        }
        runCurrent()
        assertEquals("create:left:right", controller.calls.last())
        relevant = false
        advanceTimeBy(30)
        runCurrent()
        assertTrue(operation.await().isFailure)
        assertTrue(controller.createCancelled)
        assertTrue(controller.calls.none { it.startsWith("load:") })
    }

    @Test
    fun `requested identities must match actual pair before any unload`() = runTest {
        val controller = Fake()
        controller.state.value =
            controller.state.value.copy(
                knob =
                    KnobState(
                        VoltraDevice("knob", "Knob"),
                        ConnectionStatus.READY,
                        twinCandidate = VoltraDevice("third", "Third"),
                    )
            )
        assertTrue(runCatching { controller.createTwinAfterUnloading("left", "third") }.isFailure)
        assertTrue(controller.calls.isEmpty())
    }

    private class Fake(
        initial: VoltraState =
            VoltraState(trainers = listOf(ready("left"), ready("right")), twinSupported = true)
    ) : VoltraController {
        override val state = MutableStateFlow(initial)
        val calls = mutableListOf<String>()
        var stopFails = false
        var confirmStop = true
        var afterStop: () -> Unit = {}
        var waitInCreate = false
        var createCancelled = false

        override fun scan() = flowOf(emptyList<VoltraDevice>())

        override suspend fun connect(device: VoltraDevice) = Unit

        override fun disconnect(deviceId: String) = Unit

        override suspend fun setMode(deviceId: String, mode: TrainingMode) = Unit

        override suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int) = Unit

        override suspend fun setLoaded(deviceId: String, loaded: Boolean) {
            calls += "${if(loaded) "load" else "unload"}:$deviceId"
            check(!stopFails) { "STOP rejected" }
            if (confirmStop)
                state.value =
                    state.value.copy(
                        trainers =
                            state.value.trainers.map {
                                if (it.device.id == deviceId || state.value.twin != null)
                                    it.copy(fitnessMode = 4)
                                else it
                            },
                        knob = state.value.knob?.copy(twinExitAllowed = true),
                    )
            afterStop()
        }

        override suspend fun createTwin(controllerId: String, peerId: String) {
            check(state.value.canCreateTwin)
            calls += "create:$controllerId:$peerId"
            if (waitInCreate)
                try {
                    awaitCancellation()
                } finally {
                    createCancelled = true
                }
            state.value = pairState(loaded = false)
        }

        override suspend fun exitTwin() {
            check(state.value.canExitTwin)
            calls += "exit"
        }

        override fun beginSet() = Unit

        override fun close() = Unit
    }

    companion object {
        private fun ready(id: String, loaded: Boolean = true) =
            TrainerState(
                VoltraDevice(id, id),
                ConnectionStatus.READY,
                mode = TrainingMode.WEIGHT,
                fitnessMode = if (loaded) 5 else 4,
                fresh = true,
                twinStatusKnown = true,
                loadControlSupported = true,
                loadAllowed = true,
                unloadAllowed = true,
            )

        private fun pairState(loaded: Boolean = true) =
            VoltraState(
                trainers =
                    listOf(
                        ready("left", loaded)
                            .copy(
                                twinReported = true,
                                twinVerified = true,
                                twinRole = TwinRole.CONTROLLER,
                            ),
                        ready("right", loaded)
                            .copy(
                                twinReported = true,
                                twinVerified = true,
                                twinRole = TwinRole.PEER,
                                loadControlSupported = false,
                            ),
                    ),
                twin = TwinPair("left", "right"),
                twinSupported = true,
                viaKnob = true,
                knob =
                    KnobState(
                        VoltraDevice("knob", "Knob"),
                        ConnectionStatus.READY,
                        twinExitAllowed = !loaded,
                    ),
            )
    }
}
