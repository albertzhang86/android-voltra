package io.github.albertzhang86.voltra

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MixedVoltraControllerTest {
    @Test
    fun `loaded mixed knob request confirms both STOPs before releasing peer and never reloads`() =
        runTest {
            fun loaded(device: VoltraDevice) =
                trainer(device)
                    .copy(fitnessMode = 5, loadControlSupported = true, unloadAllowed = true)
            val direct = FakeController(VoltraState(trainers = listOf(loaded(LEFT))))
            val knob = FakeKnobBridge(knobState(loaded(RIGHT), candidate = LEFT).bidirectional())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            runCurrent()
            assertFalse(mixed.state.value.canCreateTwin)
            assertTrue(mixed.state.value.canCreateTwinAfterUnloading)
            assertTrue(knob.offerReadiness.last())
            direct.afterDisconnect = { assertTrue(knob.state.value.trainers.single().unloaded) }
            knob.beforeCreate = {
                assertTrue(direct.state.value.trainers.isEmpty())
                assertTrue(knob.state.value.trainers.single().unloaded)
            }
            val request = KnobTwinRequest(11, 22, 1, LEFT.id)
            knob.twinRequests.emit(request)
            runCurrent()
            assertEquals(listOf("unload:${LEFT.id}", "disconnect:${LEFT.id}"), direct.calls)
            assertEquals(listOf("unload:${RIGHT.id}", "twin:${RIGHT.id}:${LEFT.id}"), knob.calls)
            assertEquals(KnobTwinRequestStatus.SUCCEEDED, knob.reports.last().second)
            knob.twinRequests.emit(request)
            runCurrent()
            assertEquals(2, direct.calls.size)
            assertEquals(2, knob.calls.size)
            mixed.close()
        }

    @Test
    fun `manual preparation blocks competing next-set controls between STOPs until pairing finishes`() =
        runTest {
            fun loaded(device: VoltraDevice) =
                trainer(device)
                    .copy(fitnessMode = 5, loadControlSupported = true, unloadAllowed = true)
            val direct = FakeController(VoltraState(trainers = listOf(loaded(LEFT))))
            val knob = FakeKnobBridge(knobState(loaded(RIGHT), candidate = LEFT).bidirectional())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            val continueSecondStop = CompletableDeferred<Unit>()
            var motorCalls = 0
            knob.beforeSetLoaded = {
                motorCalls++
                continueSecondStop.await()
            }
            runCurrent()
            knob.twinRequests.emit(KnobTwinRequest(11, 22, 1, LEFT.id))
            runCurrent()
            assertTrue(direct.state.value.trainers.single().unloaded)
            assertTrue(knob.state.value.trainers.single().loaded)
            assertTrue(mixed.state.value.twinPending)
            assertFalse(mixed.state.value.canCreateTwinAfterUnloading)
            // No child pending flag protects this gap: the aggregate operation owner must do so.
            assertTrue(mixed.state.value.trainers.none { it.pending })
            assertFailure { mixed.setMode(RIGHT.id, TrainingMode.BAND) }
            assertFailure { mixed.setParameter(LEFT.id, Parameter.WEIGHT, 45) }
            assertFailure { mixed.setLoaded(RIGHT.id, true) }
            assertFailure { mixed.beginSet() }
            assertFailure { mixed.createTwin(RIGHT.id, LEFT.id) }
            assertFailure { mixed.exitTwin() }
            assertFailure { mixed.createTwinAfterUnloading(RIGHT.id, LEFT.id) }
            assertFailure { mixed.disconnect(LEFT.id) }
            assertFailure { mixed.forget(KNOB.id) }
            assertEquals(1, motorCalls)
            assertEquals(listOf("unload:${LEFT.id}"), direct.calls)
            assertTrue(knob.calls.isEmpty())
            continueSecondStop.complete(Unit)
            runCurrent()
            assertFalse(mixed.state.value.twinPending)
            assertEquals(KnobTwinRequestStatus.SUCCEEDED, knob.reports.last().second)
            assertEquals(listOf("unload:${RIGHT.id}", "twin:${RIGHT.id}:${LEFT.id}"), knob.calls)
            assertEquals(listOf("unload:${LEFT.id}", "disconnect:${LEFT.id}"), direct.calls)
            mixed.close()
        }

    @Test
    fun `cancelled manual preparation releases reservation without replay or loading`() = runTest {
        fun loaded(device: VoltraDevice) =
            trainer(device).copy(fitnessMode = 5, loadControlSupported = true, unloadAllowed = true)
        val direct = FakeController(VoltraState(trainers = listOf(loaded(LEFT))))
        val knob = FakeController(knobState(loaded(RIGHT), candidate = LEFT).guarded())
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        knob.beforeSetLoaded = { awaitCancellation() }
        val operation = launch { mixed.createTwinAfterUnloading(RIGHT.id, LEFT.id) }
        runCurrent()
        assertTrue(mixed.state.value.twinPending)
        operation.cancel()
        operation.join()
        assertFalse(mixed.state.value.twinPending)
        assertTrue(knob.calls.isEmpty())
        assertEquals(listOf("unload:${LEFT.id}"), direct.calls)
        mixed.setParameter(LEFT.id, Parameter.WEIGHT, 40)
        assertEquals("parameter:${LEFT.id}:WEIGHT:40", direct.calls.last())
        mixed.close()
    }

    @Test
    fun `manual preparation cannot take ownership during an already dispatched setting command`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            val finishSetting = CompletableDeferred<Unit>()
            direct.beforeSetParameter = { finishSetting.await() }
            val setting = launch { mixed.setParameter(LEFT.id, Parameter.WEIGHT, 40) }
            runCurrent()
            assertFailure { mixed.createTwinAfterUnloading(RIGHT.id, LEFT.id) }
            assertFalse(mixed.state.value.twinPending)
            assertTrue(knob.calls.isEmpty())
            finishSetting.complete(Unit)
            setting.join()
            mixed.createTwinAfterUnloading(RIGHT.id, LEFT.id)
            assertEquals("twin:${RIGHT.id}:${LEFT.id}", knob.calls.single())
            mixed.close()
        }

    @Test
    fun `discovery switching preserves both connections and routes controls by trainer identity`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT)))
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            mixed.selectRoute(VoltraConnectionRoute.KNOB)
            assertTrue(mixed.state.value.hasMixedConnections)
            assertEquals(
                VoltraConnectionRoute.DIRECT,
                mixed.state.value.routeFor(LEFT.id.lowercase()),
            )
            mixed.setMode(LEFT.id.lowercase(), TrainingMode.BAND)
            mixed.selectRoute(VoltraConnectionRoute.DIRECT)
            mixed.setParameter(RIGHT.id.lowercase(), Parameter.WEIGHT, 45)
            assertEquals(listOf("mode:${LEFT.id}:BAND"), direct.calls)
            assertEquals(listOf("parameter:${RIGHT.id}:WEIGHT:45"), knob.calls)
            assertEquals(2, mixed.state.value.trainers.size)
            assertFalse(mixed.state.value.canCreateTwin)
            mixed.close()
        }

    @Test
    fun `state value reads fresh children before asynchronous collectors run`() = runTest {
        val direct = FakeController()
        val knob = FakeController(knobState())
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        direct.state.value = VoltraState(trainers = listOf(trainer(LEFT)))
        knob.state.value = knobState(trainer(RIGHT))
        // Deliberately do not runCurrent: commands and callers must see both current children.
        assertEquals(listOf(LEFT, RIGHT), mixed.state.first().trainers.map { it.device })
        assertEquals(listOf(LEFT, RIGHT), mixed.state.value.trainers.map { it.device })
        assertEquals(mixed.currentState, mixed.state.replayCache.single())
        mixed.setParameter(RIGHT.id, Parameter.WEIGHT, 30)
        assertEquals(listOf("parameter:${RIGHT.id}:WEIGHT:30"), knob.calls)
        mixed.close()
    }

    @Test
    fun `discovery scans selected route and omits trainers already owned by knob`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT)))
        direct.devices.value = listOf(LEFT, RIGHT.copy(id = RIGHT.id.lowercase()), THIRD)
        knob.devices.value = listOf(KNOB)
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        val results = mutableListOf<List<VoltraDevice>>()
        val scan = backgroundScope.launch { mixed.scan().collect { results += it } }
        runCurrent()
        assertEquals(listOf(LEFT, THIRD), results.last())
        mixed.selectRoute(VoltraConnectionRoute.KNOB)
        runCurrent()
        assertEquals(listOf(KNOB), results.last())
        assertTrue(direct.calls.isEmpty())
        assertTrue(knob.calls.isEmpty())
        scan.cancel()
        mixed.close()
    }

    @Test
    fun `offline direct reconnect preserves exact owner and knob discovery without replaying control`() =
        runTest {
            for (status in listOf(ConnectionStatus.FAILED, ConnectionStatus.DISCONNECTED)) {
                val offline =
                    trainer(LEFT).copy(status = status, fresh = false, error = "Disconnected (133)")
                val direct = FakeController(VoltraState(trainers = listOf(offline)))
                val knob = FakeController(knobState(trainer(RIGHT)))
                val mixed =
                    MixedVoltraController(direct, knob, backgroundScope, VoltraConnectionRoute.KNOB)
                val knobBefore = knob.state.value
                mixed.connect(LEFT.copy(id = LEFT.id.lowercase(), name = "Caller spelling"))
                assertEquals(listOf("disconnect:${LEFT.id}", "connect:${LEFT.id}"), direct.calls)
                assertTrue(knob.calls.isEmpty())
                assertEquals(knobBefore, knob.state.value)
                assertTrue(mixed.state.value.viaKnob)
                assertEquals(LEFT, mixed.state.value.trainers.first().device)
                assertEquals(VoltraConnectionRoute.DIRECT, mixed.state.value.routeFor(LEFT.id))
                assertFailure { mixed.connect(LEFT) }
                assertEquals(2, direct.calls.size)
                advanceTimeBy(5000)
                runCurrent()
                assertEquals(2, direct.calls.size)
                mixed.close()
            }
        }

    @Test
    fun `reconnect never replaces active or connecting owner or steals a knob trainer`() = runTest {
        for (status in listOf(ConnectionStatus.READY, ConnectionStatus.CONNECTING)) {
            val direct =
                FakeController(VoltraState(trainers = listOf(trainer(LEFT).copy(status = status))))
            val knob = FakeController(knobState(trainer(RIGHT)))
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            assertFailure { mixed.connect(LEFT) }
            assertFailure { mixed.connect(RIGHT) }
            assertTrue(direct.calls.isEmpty())
            assertTrue(knob.calls.isEmpty())
            mixed.close()
        }
    }

    @Test
    fun `cancelled recovery never dispatches its replacement connection`() = runTest {
        val direct =
            FakeController(
                VoltraState(
                    trainers =
                        listOf(trainer(LEFT).copy(status = ConnectionStatus.FAILED, fresh = false))
                )
            )
        val knob = FakeController(knobState(trainer(RIGHT)))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        val reconnect =
            launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { mixed.connect(LEFT) }
        direct.afterDisconnect = { reconnect.cancel() }
        reconnect.start()
        runCurrent()
        reconnect.join()
        assertTrue(reconnect.isCancelled)
        assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
        assertTrue(knob.calls.isEmpty())
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(1, direct.calls.size)
        mixed.close()
    }

    @Test
    fun `reconnect rechecks ownership after clearing a failed row`() = runTest {
        val direct =
            FakeController(
                VoltraState(
                    trainers =
                        listOf(trainer(LEFT).copy(status = ConnectionStatus.FAILED, fresh = false))
                )
            )
        val knob = FakeController(knobState(trainer(RIGHT)))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        direct.afterDisconnect = { knob.state.value = twinState(RIGHT, LEFT, viaKnob = true) }
        assertFailure { mixed.connect(LEFT) }
        assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
        assertTrue(knob.calls.isEmpty())
        assertEquals(RIGHT.id, mixed.state.value.twin?.controllerId)
        mixed.close()
    }

    @Test
    fun `remembered knob reconnect ignores discovery tab and preserves direct trainer`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob =
            FakeController(knobState().copy(knob = KnobState(KNOB, ConnectionStatus.DISCONNECTED)))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        mixed.connect(KNOB)
        assertEquals(listOf("connect:${KNOB.id}"), knob.calls)
        assertTrue(direct.calls.isEmpty())
        assertFalse(mixed.state.value.viaKnob)
        mixed.close()
    }

    @Test
    fun `disconnecting and forgetting a route leaves the other trainer untouched`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT)))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        mixed.disconnect(LEFT.id.lowercase())
        assertEquals(listOf(RIGHT), mixed.state.value.trainers.map { it.device })
        assertTrue(knob.calls.isEmpty())
        direct.state.value = VoltraState(trainers = listOf(trainer(LEFT)))
        mixed.forget(KNOB.id.lowercase())
        assertEquals(listOf(LEFT), mixed.state.value.trainers.map { it.device })
        assertEquals(listOf("forget:${KNOB.id}"), knob.calls)
        assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
        mixed.close()
    }

    @Test
    fun `knob trainer forget respects knob policy instead of forgetting a direct device`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT)))
            knob.rejectTrainerForget = true
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            assertFailure { mixed.forget(RIGHT.id.lowercase()) }
            assertTrue(direct.calls.isEmpty())
            assertEquals(listOf("forget:${RIGHT.id}"), knob.calls)
            assertEquals(2, mixed.state.value.trainers.size)
            mixed.close()
        }

    @Test
    fun `at most two trainers may be connected through either route`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT)))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        assertFailure { mixed.connect(THIRD) }
        assertTrue(direct.calls.isEmpty())
        knob.state.value = knobState().copy(knob = KnobState(KNOB, ConnectionStatus.DISCONNECTED))
        direct.state.value = VoltraState(trainers = listOf(trainer(LEFT), trainer(RIGHT)))
        assertFailure { mixed.connect(KNOB) }
        assertTrue(knob.calls.isEmpty())
        mixed.close()
    }

    @Test
    fun `same physical trainer cannot be connected again with different address casing`() =
        runTest {
            val direct = FakeController()
            val knob = FakeController(knobState(trainer(RIGHT)))
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            assertFailure { mixed.connect(RIGHT.copy(id = RIGHT.id.lowercase())) }
            assertTrue(direct.calls.isEmpty())
            mixed.close()
        }

    @Test
    fun `unexpected duplicate ownership blocks writes until knob is disconnected`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT)))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        knob.state.value = knobState(trainer(LEFT.copy(id = LEFT.id.lowercase())))
        val conflict = mixed.state.value
        assertNotNull(conflict.connectionError)
        assertEquals(1, conflict.trainers.size)
        assertFalse(conflict.trainers.single().canEdit(Parameter.WEIGHT))
        assertNull(conflict.trainers.single().activeWeightLb)
        assertNull(conflict.trainers.single().deviceReps)
        assertFailure { mixed.setParameter(LEFT.id, Parameter.WEIGHT, 40) }
        assertFailure { mixed.disconnect(LEFT.id) }
        assertTrue(direct.calls.isEmpty())
        assertTrue(knob.calls.isEmpty())
        mixed.disconnect(KNOB.id)
        assertNull(mixed.state.value.connectionError)
        assertEquals(12.0, mixed.state.value.trainers.single().activeWeightLb!!, 0.0)
        mixed.setParameter(LEFT.id, Parameter.WEIGHT, 40)
        assertEquals(listOf("parameter:${LEFT.id}:WEIGHT:40"), direct.calls)
        mixed.close()
    }

    @Test
    fun `unexpected knob Twin never hides a third trainer or allows unsafe commands`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT)))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        knob.state.value = twinState(RIGHT, THIRD, viaKnob = true)
        val conflict = mixed.state.value
        assertEquals(3, conflict.trainers.size)
        assertNotNull(conflict.connectionError)
        assertEquals(TwinHealth.DEGRADED, conflict.twin?.health)
        assertFalse(conflict.canCreateTwin)
        assertFalse(conflict.canExitTwin)
        assertFailure { mixed.setMode(LEFT.id, TrainingMode.BAND) }
        assertFailure { mixed.exitTwin() }
        assertTrue(direct.calls.isEmpty())
        assertTrue(knob.calls.isEmpty())
        mixed.disconnect(LEFT.id)
        assertNull(mixed.state.value.connectionError)
        assertTrue(mixed.state.value.canExitTwin)
        mixed.close()
    }

    @Test
    fun `legacy knob without peer guard cannot create mixed Twin`() = runTest {
        val direct =
            FakeController(VoltraState(trainers = listOf(trainer(LEFT)), twinSupported = true))
        val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        assertFalse(mixed.state.value.canCreateTwin)
        assertEquals(VoltraConnectionRoute.DIRECT, mixed.state.value.routeFor(LEFT.id.lowercase()))
        assertFailure { mixed.createTwin(RIGHT.id, LEFT.id) }
        assertTrue(direct.calls.isEmpty())
        assertTrue(knob.calls.isEmpty())
        mixed.close()
    }

    @Test
    fun `direct Twin create and exit use owning route when knob discovery is selected`() = runTest {
        val direct =
            FakeController(
                VoltraState(trainers = listOf(trainer(LEFT), trainer(RIGHT)), twinSupported = true)
            )
        val knob = FakeController(knobState())
        val mixed = MixedVoltraController(direct, knob, backgroundScope, VoltraConnectionRoute.KNOB)
        assertTrue(mixed.state.value.canCreateTwin)
        mixed.createTwin(LEFT.id.lowercase(), RIGHT.id.lowercase())
        assertTrue(mixed.state.value.canExitTwin)
        mixed.exitTwin()
        assertEquals(listOf("twin:${LEFT.id}:${RIGHT.id}", "exitTwin"), direct.calls)
        assertTrue(knob.calls.isEmpty())
        mixed.close()
    }

    @Test
    fun `knob candidate creates Twin from direct discovery without claiming candidate directly`() =
        runTest {
            val direct = FakeController()
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT))
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            assertTrue(mixed.state.value.canCreateTwin)
            assertEquals(VoltraConnectionRoute.KNOB, mixed.state.value.routeFor(LEFT.id))
            mixed.createTwin(RIGHT.id, LEFT.id)
            assertTrue(mixed.state.value.canExitTwin)
            mixed.exitTwin()
            assertTrue(direct.calls.isEmpty())
            assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}", "exitTwin"), knob.calls)
            mixed.close()
        }

    @Test
    fun `knob Twin exit availability uses knob gate even on direct discovery tab`() = runTest {
        val direct = FakeController()
        val knob = FakeController(twinState(LEFT, RIGHT, viaKnob = true))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        knob.state.value =
            knob.state.value.copy(knob = knob.state.value.knob?.copy(twinExitAllowed = false))
        assertFalse(mixed.state.value.viaKnob)
        assertFalse(mixed.state.value.canExitTwin)
        assertFailure { mixed.exitTwin() }
        assertTrue(knob.calls.isEmpty())
        mixed.close()
    }

    @Test
    fun `restoring an unrelated direct peer never changes the selected knob continuity`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        val beforeTwin = mixed.state.value.telemetryEpochFor(RIGHT.id)
        mixed.createTwin(LEFT.id, RIGHT.id)
        assertTrue(mixed.state.value.telemetryEpochFor(RIGHT.id) > beforeTwin)
        direct.afterConnect = {
            direct.state.value = VoltraState(
                trainers = listOf(trainer(LEFT).copy(status = ConnectionStatus.CONNECTING, fresh = false))
            )
        }
        mixed.exitTwin()
        val selected = mixed.state.value.telemetryEpochFor(RIGHT.id)
        val aggregate = mixed.state.value.telemetryEpoch
        for (restored in listOf(
            trainer(LEFT).copy(fresh = false, mode = null, modeWireValue = 0),
            trainer(LEFT).copy(mode = null, modeWireValue = 0),
            trainer(LEFT),
            trainer(LEFT).copy(status = ConnectionStatus.FAILED, fresh = false),
            trainer(LEFT),
        )) {
            direct.state.value = VoltraState(trainers = listOf(restored))
            assertEquals(selected, mixed.state.value.telemetryEpochFor(RIGHT.id.lowercase()))
        }
        assertTrue(mixed.state.value.telemetryEpoch > aggregate)
        val peerEpoch = mixed.state.value.telemetryEpochFor(LEFT.id)
        knob.state.value = knob.state.value.copy(telemetryEpoch = 5)
        assertTrue(mixed.state.value.telemetryEpochFor(RIGHT.id) > selected)
        assertEquals(peerEpoch, mixed.state.value.telemetryEpochFor(LEFT.id))
        val renewed = mixed.state.value.telemetryEpochFor(RIGHT.id)
        knob.state.value = knobState(trainer(RIGHT).copy(mode = TrainingMode.BAND, modeWireValue = 2))
        assertTrue(mixed.state.value.telemetryEpochFor(RIGHT.id) > renewed)
        val changedMode = mixed.state.value.telemetryEpochFor(RIGHT.id)
        knob.state.value = knobState()
        assertTrue(mixed.state.value.telemetryEpochFor(RIGHT.id) > changedMode)
        val removed = mixed.state.value.telemetryEpochFor(RIGHT.id)
        knob.state.value = knobState(trainer(RIGHT))
        assertTrue(mixed.state.value.telemetryEpochFor(RIGHT.id) > removed)
        mixed.close()
    }

    @Test
    fun `telemetry epoch tracks route continuity without reset for discovery or set start`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT)))
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            val before = mixed.state.value.telemetryEpoch
            mixed.selectRoute(VoltraConnectionRoute.KNOB)
            mixed.beginSet()
            assertEquals(before, mixed.state.value.telemetryEpoch)
            assertEquals(listOf("beginSet"), direct.calls)
            assertEquals(listOf("beginSet"), knob.calls)
            knob.state.value = knob.state.value.copy(telemetryEpoch = 8)
            val reset = mixed.state.value.telemetryEpoch
            assertTrue(reset > before)
            knob.state.value = knobState(trainer(THIRD)).copy(telemetryEpoch = 0)
            val reconnected = mixed.state.value.telemetryEpoch
            assertTrue(reconnected > reset)
            mixed.endSet()
            assertEquals(reconnected, mixed.state.value.telemetryEpoch)
            assertEquals("endSet", direct.calls.last())
            assertEquals("endSet", knob.calls.last())
            mixed.close()
        }

    @Test
    fun `close cancels both children once and late child snapshots cannot restore readings`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT)))
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            val epoch = mixed.state.value.telemetryEpoch
            mixed.close()
            mixed.close()
            direct.state.value = VoltraState(trainers = listOf(trainer(LEFT)))
            knob.state.value = knobState(trainer(RIGHT))
            runCurrent()
            assertTrue(mixed.state.value.trainers.isEmpty())
            assertNull(mixed.state.value.knob)
            assertTrue(mixed.state.value.telemetryEpoch > epoch)
            assertEquals(listOf("close"), direct.calls)
            assertEquals(listOf("close"), knob.calls)
            assertFailure { mixed.selectRoute(VoltraConnectionRoute.KNOB) }
            assertFailure { mixed.setParameter(LEFT.id, Parameter.WEIGHT, 40) }
        }

    @Test
    fun `direct only wrapper rejects unavailable knob discovery and retains direct support`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val mixed = MixedVoltraController(direct, null, backgroundScope)
            assertFailure { mixed.selectRoute(VoltraConnectionRoute.KNOB) }
            assertFalse(mixed.state.value.viaKnob)
            mixed.setParameter(LEFT.id, Parameter.WEIGHT, 40)
            assertEquals(listOf("parameter:${LEFT.id}:WEIGHT:40"), direct.calls)
            mixed.close()
        }

    @Test
    fun `empty simulated routes remain simulated before any devices connect`() = runTest {
        val direct = FakeController(VoltraState(isSimulated = true))
        val knob = FakeController(knobState().copy(isSimulated = true))
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        assertTrue(mixed.state.value.isSimulated)
        // The controller flag also defines simulation for fixtures without per-trainer flags.
        direct.state.value = VoltraState(trainers = listOf(trainer(LEFT)), isSimulated = true)
        assertTrue(mixed.state.value.isSimulated)
        mixed.close()
    }

    @Test
    fun `closing controller stops a live scan owned by the caller`() = runTest {
        val direct = FakeController()
        val mixed = MixedVoltraController(direct, null, backgroundScope)
        var completed = false
        val scan =
            backgroundScope.launch {
                mixed.scan().collect()
                completed = true
            }
        runCurrent()
        assertFalse(completed)
        mixed.close()
        runCurrent()
        assertTrue(completed)
        assertTrue(scan.isCompleted)
    }

    @Test
    fun `mixed Twin hands direct peer to knob and restores direct route after exit`() = runTest {
        val direct =
            FakeController(VoltraState(trainers = listOf(trainer(LEFT)), twinSupported = true))
        val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        assertTrue(mixed.state.value.canCreateTwin)
        mixed.createTwin(LEFT.id, RIGHT.id)
        assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
        assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}"), knob.calls)
        assertEquals(RIGHT.id, mixed.state.value.twin?.controllerId)
        assertEquals(VoltraConnectionRoute.KNOB, mixed.state.value.routeFor(LEFT.id))
        assertFalse(mixed.state.value.hasMixedConnections)
        mixed.createTwin(LEFT.id, RIGHT.id)
        assertEquals(1, knob.calls.size)
        mixed.setParameter(RIGHT.id, Parameter.WEIGHT, 35)
        assertEquals("parameter:${RIGHT.id}:WEIGHT:35", knob.calls.last())
        mixed.exitTwin()
        assertEquals("connect:${LEFT.id}", direct.calls.last())
        assertNull(mixed.state.value.twin)
        assertTrue(mixed.state.value.hasMixedConnections)
        assertEquals(VoltraConnectionRoute.DIRECT, mixed.state.value.routeFor(LEFT.id))
        mixed.close()
    }

    @Test
    fun `mixed handoff waits exact peer and never sends for wrong nearby candidate`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT), candidate = THIRD).guarded())
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
        assertEquals(listOf("disconnect:${LEFT.id}", "connect:${LEFT.id}"), direct.calls)
        assertTrue(knob.calls.isEmpty())
        assertNull(mixed.state.value.connectionError)
        knob.state.value = knobState(trainer(RIGHT)).guarded()
        backgroundScope.launch {
            delay(50)
            knob.state.value = knobState(trainer(RIGHT), candidate = LEFT).guarded()
        }
        mixed.createTwin(LEFT.id, RIGHT.id)
        assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}"), knob.calls)
        mixed.close()
    }

    @Test
    fun `failed native create requires new independent report before restoring direct peer`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
            knob.createFailure = IllegalStateException("Native pairing failed")
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            backgroundScope.launch {
                delay(50)
                assertTrue(direct.state.value.trainers.isEmpty())
                knob.state.value = knob.state.value.copy(topologyRevision = 1)
            }
            assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
            assertEquals(listOf("disconnect:${LEFT.id}", "connect:${LEFT.id}"), direct.calls)
            assertTrue(mixed.state.value.canCreateTwin)
            knob.createFailure = null
            mixed.createTwin(LEFT.id, RIGHT.id)
            assertEquals(2, knob.calls.size)
            mixed.close()
        }

    @Test
    fun `uncertain native result blocks resend and does not reconnect into potential Twin`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
            knob.createFailure = IllegalStateException("Result lost")
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
            assertNotNull(mixed.state.value.connectionError)
            assertFalse(mixed.state.value.canCreateTwin)
            assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
            assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
            assertEquals(1, knob.calls.size)
            knob.state.value = twinState(RIGHT, LEFT, true).guarded()
            assertNull(mixed.state.value.connectionError)
            mixed.createTwin(LEFT.id, RIGHT.id)
            assertEquals(1, knob.calls.size)
            mixed.exitTwin()
            assertEquals("connect:${LEFT.id}", direct.calls.last())
            mixed.close()
        }

    @Test
    fun `pending handoff serializes topology and blocks setting writes and new connections`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT)).guarded())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            val operation = backgroundScope.async { mixed.createTwin(LEFT.id, RIGHT.id) }
            runCurrent()
            assertTrue(mixed.state.value.twinPending)
            assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
            assertFailure { mixed.setMode(RIGHT.id, TrainingMode.BAND) }
            assertFailure { mixed.connect(THIRD) }
            mixed.close()
            runCurrent()
            assertTrue(operation.isCancelled)
            assertEquals(listOf("disconnect:${LEFT.id}", "close"), direct.calls)
            assertEquals(listOf("close"), knob.calls)
        }

    @Test
    fun `mixed handoff releases direct ownership before requesting the exact native peer`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT)).guarded())
            direct.afterDisconnect = {
                assertTrue(direct.state.value.trainers.isEmpty())
                knob.state.value =
                    knobState(trainer(RIGHT), candidate = LEFT.copy(id = LEFT.id.lowercase()))
                        .guarded()
            }
            knob.beforeCreate = {
                assertTrue(direct.state.value.trainers.isEmpty())
                assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
            }
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            mixed.createTwin(LEFT.id, RIGHT.id)
            assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id.lowercase()}"), knob.calls)
            assertEquals(RIGHT.id, mixed.state.value.twin?.controllerId)
            assertTrue(mixed.state.value.canExitTwin)
            mixed.close()
        }

    @Test
    fun `loaded stale pending and unverified trainers cannot start a mixed handoff`() = runTest {
        val blocked =
            listOf(
                trainer(LEFT).copy(fitnessMode = 5),
                trainer(LEFT).copy(fresh = false),
                trainer(LEFT).copy(pending = true),
                trainer(LEFT).copy(twinStatusKnown = false),
                trainer(LEFT).copy(twinReported = true),
            )
        for (snapshot in blocked) {
            val direct = FakeController(VoltraState(trainers = listOf(snapshot)))
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            assertFalse(mixed.state.value.canCreateTwin)
            assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
            assertTrue(direct.calls.isEmpty())
            assertTrue(knob.calls.isEmpty())
            mixed.close()
        }
    }

    @Test
    fun `changing selected knob trainer during handoff never pairs or restores unknown topology`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT)).guarded())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            backgroundScope.launch {
                delay(50)
                knob.state.value = knobState(trainer(THIRD), candidate = LEFT).guarded()
            }
            assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
            assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
            assertTrue(knob.calls.isEmpty())
            assertNotNull(mixed.state.value.connectionError)
            assertFailure { mixed.createTwin(THIRD.id, LEFT.id) }
            assertTrue(knob.calls.isEmpty())
            mixed.close()
        }

    @Test
    fun `lost knob can reconnect through uncertain Twin without replaying control`() = runTest {
        for (paired in listOf(false, true)) {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
            knob.createFailure = IllegalStateException("Native result unavailable")
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
            knob.state.value =
                knobState().copy(
                    knob = KnobState(KNOB, ConnectionStatus.FAILED),
                    telemetryEpoch = 1,
                )
            assertNotNull(mixed.state.value.connectionError)
            assertEquals(KNOB.id, mixed.state.value.recoverableKnobId)
            assertFailure { mixed.connect(LEFT) }
            assertFailure { mixed.connect(THIRD) }
            assertFailure { mixed.connect(KNOB.copy(id = "different-knob")) }
            knob.afterConnect = {
                knob.state.value =
                    knobState().copy(
                        knob = KnobState(KNOB, ConnectionStatus.CONNECTING),
                        telemetryEpoch = 2,
                    )
            }
            mixed.selectRoute(VoltraConnectionRoute.DIRECT)
            mixed.connect(KNOB.copy(id = KNOB.id.lowercase(), name = "Caller spelling"))
            assertNotNull(mixed.state.value.connectionError)
            assertNull(mixed.state.value.recoverableKnobId)
            assertFailure { mixed.connect(KNOB) }
            assertFailure { mixed.setLoaded(RIGHT.id, true) }
            assertFailure { mixed.createTwin(RIGHT.id, LEFT.id) }
            knob.state.value =
                knobState(trainer(RIGHT).copy(fresh = false)).copy(telemetryEpoch = 2)
            assertNotNull(mixed.state.value.connectionError)
            knob.state.value = knobState(trainer(THIRD)).copy(telemetryEpoch = 2)
            assertNotNull(mixed.state.value.connectionError)
            knob.state.value = twinState(RIGHT, THIRD, true).copy(telemetryEpoch = 2)
            assertNotNull(mixed.state.value.connectionError)
            knob.state.value =
                if (paired)
                    twinState(
                            RIGHT.copy(id = RIGHT.id.lowercase()),
                            LEFT.copy(id = LEFT.id.lowercase()),
                            true,
                        )
                        .copy(telemetryEpoch = 2)
                else
                    knobState(trainer(RIGHT).copy(fitnessMode = 5)).copy(telemetryEpoch = 2)
            assertNull(mixed.state.value.connectionError)
            assertNull(mixed.state.value.recoverableKnobId)
            assertEquals(paired, mixed.state.value.twin != null)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
            assertEquals(
                listOf(
                    "twin:${RIGHT.id}:${LEFT.id}",
                    "disconnect:${KNOB.id}",
                    "connect:${KNOB.id}",
                ),
                knob.calls,
            )
            assertFalse(mixed.state.value.viaKnob)
            if (paired) {
                assertTrue(mixed.state.value.canExitTwin)
                mixed.exitTwin()
                assertEquals("exitTwin", knob.calls.last())
                assertEquals("connect:${LEFT.id}", direct.calls.last())
                assertNull(mixed.state.value.twin)
                assertTrue(mixed.state.value.hasMixedConnections)
            } else {
                mixed.connect(LEFT)
                assertEquals("connect:${LEFT.id}", direct.calls.last())
                assertTrue(mixed.state.value.hasMixedConnections)
            }
            mixed.close()
        }
    }

    @Test
    fun `disconnecting an uncertain reconnect keeps topology blocked until a new retry confirms it`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
        knob.createFailure = IllegalStateException("Native result unavailable")
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
        knob.afterConnect = {
            knob.state.value =
                knobState().copy(knob = KnobState(KNOB, ConnectionStatus.CONNECTING))
        }
        mixed.connect(KNOB)
        mixed.disconnect(KNOB.id.lowercase())
        assertNotNull(mixed.state.value.connectionError)
        assertEquals(KNOB.id, mixed.state.value.recoverableKnobId)
        assertEquals(ConnectionStatus.DISCONNECTED, mixed.state.value.knob?.status)
        assertFailure { mixed.connect(LEFT) }
        assertFailure { mixed.setLoaded(RIGHT.id, true) }
        // Cancelling must revoke the pending independent-snapshot recovery authorization too.
        knob.state.value = knobState(trainer(RIGHT))
        assertNotNull(mixed.state.value.connectionError)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
        assertEquals(
            listOf(
                "twin:${RIGHT.id}:${LEFT.id}",
                "disconnect:${KNOB.id}",
                "connect:${KNOB.id}",
                "disconnect:${KNOB.id}",
            ),
            knob.calls,
        )
        knob.afterConnect = {}
        mixed.connect(KNOB)
        assertNull(mixed.state.value.connectionError)
        assertNull(mixed.state.value.recoverableKnobId)
        assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
        assertEquals("connect:${KNOB.id}", knob.calls.last())
        mixed.close()
    }

    @Test
    fun `cancelled uncertain knob recovery never reconnects or clears the control block`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
        knob.createFailure = IllegalStateException("Native result unavailable")
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
        val reconnect = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { mixed.connect(KNOB) }
        knob.afterDisconnect = { reconnect.cancel() }
        reconnect.start()
        runCurrent()
        reconnect.join()
        assertTrue(reconnect.isCancelled)
        assertNotNull(mixed.state.value.connectionError)
        assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
        assertEquals(
            listOf("twin:${RIGHT.id}:${LEFT.id}", "disconnect:${KNOB.id}"),
            knob.calls,
        )
        assertFailure { mixed.connect(LEFT) }
        mixed.close()
    }

    @Test
    fun `reconnecting the knob clears uncertain handoff without silently reconnecting the peer`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
            knob.createFailure = IllegalStateException("Native result unavailable")
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            assertFailure { mixed.createTwin(LEFT.id, RIGHT.id) }
            assertNotNull(mixed.state.value.connectionError)
            mixed.disconnect(KNOB.id)
            mixed.connect(KNOB)
            assertNull(mixed.state.value.connectionError)
            assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
            assertEquals(listOf(RIGHT), mixed.state.value.trainers.map { it.device })
            mixed.connect(LEFT)
            assertTrue(mixed.state.value.hasMixedConnections)
            assertEquals("connect:${LEFT.id}", direct.calls.last())
            mixed.close()
        }

    @Test
    fun `Twin exit waits for independent knob confirmation before restoring the direct peer`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            mixed.createTwin(LEFT.id, RIGHT.id)
            knob.afterExit = { knob.state.value = knob.state.value.copy(twinPending = true) }
            backgroundScope.launch {
                delay(50)
                assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
                assertTrue(mixed.state.value.twinPending)
                knob.state.value = knob.state.value.copy(twinPending = false)
            }
            mixed.exitTwin()
            assertEquals(listOf("disconnect:${LEFT.id}", "connect:${LEFT.id}"), direct.calls)
            assertTrue(mixed.state.value.hasMixedConnections)
            assertNull(mixed.state.value.twin)
            mixed.close()
        }

    @Test
    fun `owned peer remains visible but not ready when a trainer is loaded and never pairs automatically`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).bidirectional())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            runCurrent()
            assertEquals(LEFT, knob.offers.last())
            advanceTimeBy(1_500)
            runCurrent()
            assertTrue(direct.calls.isEmpty())
            assertTrue(knob.calls.isEmpty())
            assertNull(mixed.state.value.twin)
            direct.state.value =
                direct.state.value.copy(trainers = listOf(trainer(LEFT).copy(fitnessMode = 5)))
            advanceTimeBy(750)
            runCurrent()
            assertEquals(LEFT, knob.offers.last())
            assertFalse(knob.offerReadiness.last())
            assertTrue(direct.calls.isEmpty())
            assertTrue(knob.calls.isEmpty())
            mixed.close()
        }

    @Test
    fun `heartbeat timeout does not stop offers after an explicit knob reconnect`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).bidirectional())
        var failNextOffer = true
        knob.beforeOffer = {
            if (failNextOffer) {
                failNextOffer = false
                knob.state.value = knobState().copy(knob = KnobState(KNOB, ConnectionStatus.FAILED))
                withTimeout(1) { awaitCancellation() }
            }
        }
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        mixed.connect(KNOB)
        knob.state.value = knobState(trainer(RIGHT), candidate = LEFT).bidirectional()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf(LEFT, LEFT), knob.offers)
        assertTrue(knob.offerReadiness.last())
        assertEquals(listOf("connect:${KNOB.id}"), knob.calls)
        assertTrue(direct.calls.isEmpty())
        assertNull(mixed.state.value.twin)
        // Recovering the heartbeat never initiates pairing by itself.
        mixed.createTwin(RIGHT.id, LEFT.id)
        assertEquals(1, knob.calls.count { it.startsWith("twin:") })
        mixed.close()
    }

    @Test
    fun `request acknowledgement and failure status timeouts preserve the new request listener`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).bidirectional())
            knob.beforeReport = { request, _ ->
                if (request.id == 1) withTimeout(1) { awaitCancellation() }
            }
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            runCurrent()
            val first = KnobTwinRequest(11, 22, 1, LEFT.id)
            knob.twinRequests.emit(first)
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                listOf(
                    first to KnobTwinRequestStatus.ACCEPTED,
                    first to KnobTwinRequestStatus.FAILED,
                ),
                knob.reports,
            )
            assertTrue(direct.calls.isEmpty())
            assertTrue(knob.calls.isEmpty())
            knob.twinRequests.emit(first)
            runCurrent()
            assertEquals(2, knob.reports.size)
            val next = first.copy(id = 2)
            knob.twinRequests.emit(next)
            runCurrent()
            assertEquals(next to KnobTwinRequestStatus.SUCCEEDED, knob.reports.last())
            assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
            assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}"), knob.calls)
            mixed.close()
        }

    @Test
    fun `a lost success status does not replay native pairing or terminate future requests`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).bidirectional())
            knob.beforeReport = { request, status ->
                if (request.id == 1 && status == KnobTwinRequestStatus.SUCCEEDED)
                    withTimeout(1) { awaitCancellation() }
            }
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            runCurrent()
            val first = KnobTwinRequest(11, 22, 1, LEFT.id)
            knob.twinRequests.emit(first)
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            knob.twinRequests.emit(first)
            runCurrent()
            assertEquals(1, knob.calls.count { it.startsWith("twin:") })
            assertEquals(2, knob.reports.size)
            mixed.exitTwin()
            knob.state.value = knobState(trainer(RIGHT), candidate = LEFT).bidirectional()
            advanceTimeBy(750)
            runCurrent()
            val next = first.copy(id = 2)
            knob.twinRequests.emit(next)
            runCurrent()
            assertEquals(next to KnobTwinRequestStatus.SUCCEEDED, knob.reports.last())
            assertEquals(2, knob.calls.count { it.startsWith("twin:") })
            mixed.close()
        }

    @Test
    fun `closing the controller still cancels suspended bridge collectors without replay`() =
        runTest {
            for (blockOffer in listOf(true, false)) {
                val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
                val knob =
                    FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).bidirectional())
                var cancelled = false
                val block: suspend () -> Unit = {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
                if (blockOffer) knob.beforeOffer = block
                else knob.beforeReport = { _, _ -> block() }
                val mixed = MixedVoltraController(direct, knob, backgroundScope)
                runCurrent()
                if (!blockOffer) {
                    knob.twinRequests.emit(KnobTwinRequest(11, 22, 1, LEFT.id))
                    runCurrent()
                }
                mixed.close()
                runCurrent()
                assertTrue(cancelled)
                val offers = knob.offers.size
                val reports = knob.reports.size
                advanceTimeBy(5_000)
                knob.twinRequests.emit(KnobTwinRequest(11, 22, 2, LEFT.id))
                runCurrent()
                assertEquals(offers, knob.offers.size)
                assertEquals(reports, knob.reports.size)
                assertEquals(listOf("close"), direct.calls)
                assertEquals(listOf("close"), knob.calls)
            }
        }

    @Test
    fun `explicit knob request creates one native pair and repeated request ids never replay`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).bidirectional())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            runCurrent()
            val request = KnobTwinRequest(11, 22, 3, LEFT.id.lowercase())
            knob.twinRequests.emit(request)
            runCurrent()
            assertEquals(
                listOf(
                    request to KnobTwinRequestStatus.ACCEPTED,
                    request to KnobTwinRequestStatus.SUCCEEDED,
                ),
                knob.reports,
            )
            assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
            assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}"), knob.calls)
            assertEquals(RIGHT.id, mixed.state.value.twin?.controllerId)
            knob.twinRequests.emit(request)
            knob.twinRequests.emit(request.copy(id = 2))
            runCurrent()
            assertEquals(2, knob.reports.size)
            assertEquals(1, knob.calls.size)
            mixed.exitTwin()
            assertEquals(VoltraConnectionRoute.DIRECT, mixed.state.value.routeFor(LEFT.id))
            advanceTimeBy(750)
            runCurrent()
            knob.twinRequests.emit(request)
            runCurrent()
            assertNull(mixed.state.value.twin)
            assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}", "exitTwin"), knob.calls)
            mixed.close()
        }

    @Test
    fun `wrong peer requests fail without releasing either trainer`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).bidirectional())
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        runCurrent()
        val request = KnobTwinRequest(1, 1, 1, THIRD.id)
        knob.twinRequests.emit(request)
        runCurrent()
        assertEquals(listOf(request to KnobTwinRequestStatus.FAILED), knob.reports)
        assertTrue(direct.calls.isEmpty())
        assertTrue(knob.calls.isEmpty())
        assertTrue(mixed.state.value.hasMixedConnections)
        mixed.close()
    }

    @Test
    fun `an old offer cannot release a replacement direct trainer before the next offer refresh`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).bidirectional())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            runCurrent()
            assertEquals(LEFT, knob.offers.last())
            direct.state.value = VoltraState(trainers = listOf(trainer(THIRD)))
            val request = KnobTwinRequest(1, 1, 1, LEFT.id)
            knob.twinRequests.emit(request)
            runCurrent()
            assertEquals(listOf(request to KnobTwinRequestStatus.FAILED), knob.reports)
            assertTrue(direct.calls.isEmpty())
            assertTrue(knob.calls.isEmpty())
            assertEquals(THIRD, direct.state.value.trainers.single().device)
            mixed.close()
        }

    @Test
    fun `legacy bridge without bidirectional support receives no eligible peer offer`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).guarded())
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        runCurrent()
        assertNull(knob.offers.last())
        val request = KnobTwinRequest(1, 1, 1, LEFT.id)
        knob.twinRequests.emit(request)
        runCurrent()
        assertEquals(listOf(request to KnobTwinRequestStatus.FAILED), knob.reports)
        assertTrue(direct.calls.isEmpty())
        assertTrue(knob.calls.isEmpty())
        mixed.close()
    }

    @Test
    fun `failed knob request needs an explicit newer request before another native attempt`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeKnobBridge(knobState(trainer(RIGHT), candidate = LEFT).bidirectional())
            knob.createFailure = IllegalStateException("Native pairing failed")
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            runCurrent()
            val request = KnobTwinRequest(1, 1, 1, LEFT.id)
            backgroundScope.launch {
                delay(50)
                knob.state.value = knob.state.value.copy(topologyRevision = 1)
            }
            knob.twinRequests.emit(request)
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            assertEquals(
                listOf(
                    request to KnobTwinRequestStatus.ACCEPTED,
                    request to KnobTwinRequestStatus.FAILED,
                ),
                knob.reports,
            )
            assertEquals(listOf("disconnect:${LEFT.id}", "connect:${LEFT.id}"), direct.calls)
            knob.createFailure = null
            advanceTimeBy(750)
            runCurrent()
            knob.twinRequests.emit(request)
            runCurrent()
            assertNull(mixed.state.value.twin)
            assertEquals(1, knob.calls.size)
            val retry = request.copy(id = 2)
            knob.twinRequests.emit(retry)
            runCurrent()
            assertEquals(2, knob.calls.size)
            assertEquals(retry to KnobTwinRequestStatus.SUCCEEDED, knob.reports.last())
            assertEquals(RIGHT.id, mixed.state.value.twin?.controllerId)
            mixed.close()
        }

    @Test
    fun `peer authorization stays fresh after direct release while waiting for the exact candidate`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeKnobBridge(knobState(trainer(RIGHT)).bidirectional())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            runCurrent()
            val operation = backgroundScope.async { mixed.createTwin(LEFT.id, RIGHT.id) }
            runCurrent()
            assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(LEFT, knob.offers.last())
            assertTrue(knob.offerReadiness.last())
            assertTrue(knob.calls.isEmpty())
            assertTrue(mixed.state.value.twinPending)
            knob.state.value = knobState(trainer(RIGHT), candidate = LEFT).bidirectional()
            operation.await()
            advanceTimeBy(750)
            runCurrent()
            assertNull(knob.offers.last())
            assertFalse(knob.offerReadiness.last())
            assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}"), knob.calls)
            mixed.close()
        }

    @Test
    fun `local knob exit restores its remembered direct peer exactly once and blocks racing settings`() =
        runTest {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            mixed.createTwin(LEFT.id, RIGHT.id)
            knob.exitTwin() // The user presses Exit Twin on the knob, without a TV exit command.
            val pending = mixed.state.value
            assertTrue(pending.twinPending)
            assertTrue(pending.trainers.all { !it.fresh && it.pending })
            assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
            assertFailure { mixed.setParameter(RIGHT.id, Parameter.WEIGHT, 35) }
            assertFailure { mixed.connect(THIRD) }
            runCurrent()
            assertFalse(mixed.state.value.twinPending)
            assertTrue(mixed.state.value.hasMixedConnections)
            assertEquals(VoltraConnectionRoute.DIRECT, mixed.state.value.routeFor(LEFT.id))
            repeat(3) {
                knob.state.value =
                    knob.state.value.copy(topologyRevision = knob.state.value.topologyRevision + 1)
                runCurrent()
            }
            assertEquals(listOf("disconnect:${LEFT.id}", "connect:${LEFT.id}"), direct.calls)
            assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}", "exitTwin"), knob.calls)
            mixed.close()
        }

    @Test
    fun `local exit restoration requires a later fresh independent snapshot in the same knob session`() =
        runTest {
            val invalid: List<(VoltraState) -> VoltraState> =
                listOf(
                    { it.copy(topologyRevision = 0) },
                    {
                        it.copy(
                            trainers = it.trainers.map { trainer -> trainer.copy(fresh = false) }
                        )
                    },
                    { it.copy(twinPending = true) },
                    {
                        it.copy(
                            trainers = it.trainers.map { trainer -> trainer.copy(pending = true) }
                        )
                    },
                    {
                        it.copy(
                            trainers =
                                it.trainers.map { trainer -> trainer.copy(twinStatusKnown = false) }
                        )
                    },
                    {
                        it.copy(
                            trainers = it.trainers.map { trainer -> trainer.copy(fitnessMode = 5) }
                        )
                    },
                    { it.copy(knob = it.knob?.copy(status = ConnectionStatus.FAILED)) },
                    {
                        it.copy(
                            knob = it.knob?.copy(device = VoltraDevice("other-knob", "Other knob"))
                        )
                    },
                    { it.copy(telemetryEpoch = 1) },
                    { it.copy(trainers = listOf(trainer(THIRD))) },
                    {
                        twinState(RIGHT, LEFT, true)
                            .copy(
                                twin = TwinPair(RIGHT.id, LEFT.id, TwinHealth.DEGRADED),
                                topologyRevision = 1,
                            )
                    },
                )
            for (change in invalid) {
                val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
                val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
                val mixed = MixedVoltraController(direct, knob, backgroundScope)
                mixed.createTwin(LEFT.id, RIGHT.id)
                knob.state.value = change(knobState(trainer(RIGHT)).copy(topologyRevision = 1))
                runCurrent()
                assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
                assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}"), knob.calls)
                mixed.close()
            }
        }

    @Test
    fun `queued local exit restore rechecks ownership and cannot add a third trainer`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        mixed.createTwin(LEFT.id, RIGHT.id)
        knob.exitTwin()
        assertTrue(mixed.state.value.twinPending)
        direct.state.value = VoltraState(trainers = listOf(trainer(THIRD)))
        runCurrent()
        assertEquals(listOf("disconnect:${LEFT.id}"), direct.calls)
        assertEquals(THIRD, direct.state.value.trainers.single().device)
        assertFalse(mixed.state.value.twinPending)
        mixed.close()
    }

    @Test
    fun `closing or disconnecting the knob cancels a queued local exit restoration`() = runTest {
        for (close in listOf(true, false)) {
            val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
            val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            mixed.createTwin(LEFT.id, RIGHT.id)
            knob.exitTwin()
            assertTrue(mixed.state.value.twinPending)
            if (close) mixed.close() else mixed.disconnect(KNOB.id)
            runCurrent()
            assertTrue(direct.calls.none { it.startsWith("connect:") })
            if (!close) mixed.close()
        }
    }

    @Test
    fun `a failed local exit reconnect is not repeated by later snapshots`() = runTest {
        val direct = FakeController(VoltraState(trainers = listOf(trainer(LEFT))))
        val knob = FakeController(knobState(trainer(RIGHT), candidate = LEFT).guarded())
        val mixed = MixedVoltraController(direct, knob, backgroundScope)
        mixed.createTwin(LEFT.id, RIGHT.id)
        direct.connectFailure = IllegalStateException("Direct connection unavailable")
        knob.exitTwin()
        runCurrent()
        repeat(3) {
            knob.state.value =
                knob.state.value.copy(topologyRevision = knob.state.value.topologyRevision + 1)
            runCurrent()
        }
        assertEquals(listOf("disconnect:${LEFT.id}", "connect:${LEFT.id}"), direct.calls)
        assertFalse(mixed.state.value.twinPending)
        assertEquals(listOf("twin:${RIGHT.id}:${LEFT.id}", "exitTwin"), knob.calls)
        mixed.close()
    }

    @Test
    fun `a standalone knob pair has no former direct connection to restore after local exit`() =
        runTest {
            val direct = FakeController()
            val knob = FakeController(twinState(RIGHT, LEFT, true))
            val mixed = MixedVoltraController(direct, knob, backgroundScope)
            knob.exitTwin()
            runCurrent()
            assertTrue(direct.calls.isEmpty())
            assertFalse(mixed.state.value.twinPending)
            assertEquals(listOf(RIGHT), mixed.state.value.trainers.map { it.device })
            assertEquals(listOf("exitTwin"), knob.calls)
            mixed.close()
        }

    private suspend fun assertFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected a connection or ownership failure")
        } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {}
    }

    private open class FakeController(initial: VoltraState = VoltraState()) : VoltraController {
        override val state = MutableStateFlow(initial)
        val devices = MutableStateFlow<List<VoltraDevice>>(emptyList())
        val calls = mutableListOf<String>()
        var rejectTrainerForget = false
        var createFailure: Exception? = null
        var connectFailure: Exception? = null
        var beforeCreate: suspend () -> Unit = {}
        var beforeSetLoaded: suspend () -> Unit = {}
        var beforeSetParameter: suspend () -> Unit = {}
        var afterConnect: () -> Unit = {}
        var afterDisconnect: () -> Unit = {}
        var afterExit: () -> Unit = {}

        override fun scan() = devices

        override suspend fun connect(device: VoltraDevice) {
            calls += "connect:${device.id}"
            connectFailure?.let { throw it }
            state.value =
                if (device == KNOB) knobState(trainer(RIGHT))
                else state.value.copy(trainers = state.value.trainers + trainer(device))
            afterConnect()
        }

        override fun disconnect(deviceId: String) {
            calls += "disconnect:$deviceId"
            disconnectState(deviceId)
            afterDisconnect()
        }

        override fun forget(deviceId: String) {
            calls += "forget:$deviceId"
            require(!rejectTrainerForget || state.value.trainers.none { it.device.id == deviceId })
            disconnectState(deviceId)
        }

        private fun disconnectState(deviceId: String) {
            state.value =
                if (state.value.viaKnob)
                    knobState()
                        .copy(knob = state.value.knob?.copy(status = ConnectionStatus.DISCONNECTED))
                else
                    state.value.copy(
                        trainers = state.value.trainers.filterNot { it.device.id == deviceId }
                    )
        }

        override suspend fun setMode(deviceId: String, mode: TrainingMode) {
            calls += "mode:$deviceId:$mode"
        }

        override suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int) {
            beforeSetParameter()
            calls += "parameter:$deviceId:$parameter:$value"
        }

        override suspend fun setLoaded(deviceId: String, loaded: Boolean) {
            beforeSetLoaded()
            check(!loaded) { "Manual Twin preparation must never load." }
            calls += "unload:$deviceId"
            state.value =
                state.value.copy(
                    trainers =
                        state.value.trainers.map {
                            if (it.device.id == deviceId || state.value.twin != null)
                                it.copy(fitnessMode = 4)
                            else it
                        }
                )
        }

        override suspend fun createTwin(controllerId: String, peerId: String) {
            beforeCreate()
            calls += "twin:$controllerId:$peerId"
            createFailure?.let { throw it }
            val controller = state.value.trainers.first { it.device.id == controllerId }.device
            val peer =
                state.value.trainers.firstOrNull { it.device.id == peerId }?.device
                    ?: requireNotNull(state.value.knob?.twinCandidate)
            state.value = twinState(controller, peer, state.value.viaKnob)
        }

        override suspend fun exitTwin() {
            calls += "exitTwin"
            val selected = requireNotNull(state.value.twin).controllerId
            state.value =
                state.value.copy(
                    twin = null,
                    trainers =
                        state.value.trainers
                            .filter { !state.value.viaKnob || it.device.id == selected }
                            .map {
                                it.copy(twinReported = false, twinVerified = false, twinRole = null)
                            },
                    topologyRevision = state.value.topologyRevision + 1,
                )
            afterExit()
        }

        override fun beginSet() {
            calls += "beginSet"
            state.value =
                state.value.copy(trainers = state.value.trainers.map { it.copy(reps = 0) })
        }

        override fun endSet() {
            calls += "endSet"
        }

        override fun close() {
            calls += "close"
        }
    }

    private class FakeKnobBridge(initial: VoltraState) : FakeController(initial), KnobTwinBridge {
        override val twinRequests = MutableSharedFlow<KnobTwinRequest>(extraBufferCapacity = 8)
        val offers = mutableListOf<VoltraDevice?>()
        val offerReadiness = mutableListOf<Boolean>()
        val reports = mutableListOf<Pair<KnobTwinRequest, KnobTwinRequestStatus>>()
        var beforeOffer: suspend () -> Unit = {}
        var beforeReport: suspend (KnobTwinRequest, KnobTwinRequestStatus) -> Unit = { _, _ -> }

        override suspend fun offerTwinPeer(peer: VoltraDevice?, ready: Boolean) {
            offers += peer
            offerReadiness += ready
            beforeOffer()
        }

        override suspend fun reportTwinRequest(
            request: KnobTwinRequest,
            status: KnobTwinRequestStatus,
        ) {
            reports += request to status
            beforeReport(request, status)
        }
    }

    companion object {
        private fun VoltraState.guarded() = copy(knob = knob?.copy(twinIdentityGuarded = true))

        private fun VoltraState.bidirectional() =
            copy(knob = knob?.copy(twinIdentityGuarded = true, mixedTwinHandoffSupported = true))

        private val LEFT = VoltraDevice("AA:BB:CC:00:00:01", "Left VOLTRA")
        private val RIGHT = VoltraDevice("AA:BB:CC:00:00:02", "Right VOLTRA")
        private val THIRD = VoltraDevice("AA:BB:CC:00:00:03", "Third VOLTRA")
        private val KNOB = VoltraDevice("AA:BB:CC:00:00:FF", "VOLTRA Remote")

        private fun trainer(device: VoltraDevice) =
            TrainerState(
                device,
                status = ConnectionStatus.READY,
                mode = TrainingMode.WEIGHT,
                modeWireValue = 1,
                parameters = mapOf(Parameter.WEIGHT to 30),
                fresh = true,
                fitnessMode = 4,
                twinStatusKnown = true,
                activeWeightLb = 12.0,
                deviceReps = 7,
                reps = 3,
            )

        private fun knobState(vararg trainers: TrainerState, candidate: VoltraDevice? = null) =
            VoltraState(
                trainers = trainers.toList(),
                viaKnob = true,
                twinSupported = true,
                knob = KnobState(KNOB, ConnectionStatus.READY, twinCandidate = candidate),
            )

        private fun twinState(controller: VoltraDevice, peer: VoltraDevice, viaKnob: Boolean) =
            VoltraState(
                trainers =
                    listOf(
                        trainer(controller)
                            .copy(
                                twinReported = true,
                                twinVerified = true,
                                twinRole = TwinRole.CONTROLLER,
                            ),
                        trainer(peer)
                            .copy(
                                twinReported = true,
                                twinVerified = true,
                                twinRole = TwinRole.PEER,
                            ),
                    ),
                twin = TwinPair(controller.id, peer.id),
                twinSupported = true,
                viaKnob = viaKnob,
                knob =
                    if (viaKnob) KnobState(KNOB, ConnectionStatus.READY, twinExitAllowed = true)
                    else null,
            )
    }
}
