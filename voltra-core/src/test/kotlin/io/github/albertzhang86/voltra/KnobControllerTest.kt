package io.github.albertzhang86.voltra

import io.github.albertzhang86.voltra.knob.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KnobControllerTest {
    private fun activitySnapshot(session: Int = 7, uptime: Int = 1000, activity: Int = 0): ByteArray {
        val old = twinSnapshot(uptime = uptime)
        return ByteArray(old.size + 8).also { bytes ->
            old.copyInto(bytes, 0, 0, 134)
            old.copyInto(bytes, 142, 134)
            bytes[1] = 3; bytes[138] = 1; bytes[139] = activity.toByte()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, session)
                .putShort(135, 200)
        }
    }

    @Test fun `setup display spans trainer reconnects without changing control readiness`() = runTest {
        val link = Link(); val controller = controller(link)
        controller.connect(knob); runCurrent()
        link.incoming.send(activitySnapshot()); runCurrent()
        assertEquals(ConnectionStatus.READY, controller.state.value.knob!!.status)
        val outer = controller.beginSetupActivity(SetupActivity.CREATE_TWIN); runCurrent()
        val inner = controller.beginSetupActivity(SetupActivity.CONFIGURE); runCurrent()
        assertEquals(SetupActivity.CREATE_TWIN, controller.state.value.knob!!.setupActivity)
        link.incoming.send(activitySnapshot(session = 8).also { it[16] = 255.toByte() })
        runCurrent(); advanceTimeBy(750); runCurrent()
        assertTrue(controller.state.value.trainers.isEmpty())
        assertFalse(controller.state.value.canCreateTwin)
        assertEquals(SetupActivity.CREATE_TWIN, controller.state.value.knob!!.setupActivity)
        val update = ByteBuffer.wrap(link.writes.last()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(11, update[2].toInt()); assertEquals(8, update.getInt(8))
        assertEquals(0, update.getShort(16).toInt()); assertEquals(2, update.getShort(18).toInt())
        inner.close(); runCurrent()
        assertEquals(SetupActivity.CREATE_TWIN, controller.state.value.knob!!.setupActivity)
        outer.close(); runCurrent(); outer.close(); runCurrent()
        assertNull(controller.state.value.knob!!.setupActivity)
        assertEquals(0, ByteBuffer.wrap(link.writes.last()).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt())
        assertTrue(link.writes.all { it[2].toInt() == 11 })
        controller.close()
    }

    @Test fun `older firmware receives no setup display commands`() = runTest {
        val link = Link(); val controller = controller(link)
        controller.connect(knob); runCurrent()
        link.incoming.send(twinSnapshot()); runCurrent()
        val activity = controller.beginSetupActivity(SetupActivity.EXIT_TWIN)
        runCurrent(); advanceTimeBy(750); runCurrent(); activity.close(); runCurrent()
        assertTrue(link.writes.isEmpty())
        controller.close()
    }

    @Test fun `native setup display is optional and never claims a connected trainer`() {
        val bytes = activitySnapshot(activity = 3).also { it[16] = 255.toByte() }
        val decoded = KnobProtocol.snapshot(bytes)
        assertTrue(decoded.setupActivitySupported)
        assertEquals(SetupActivity.EXIT_TWIN, decoded.setupActivity)
        assertNull(decoded.trainer)
        bytes[139] = 99; assertNull(KnobProtocol.snapshot(bytes).setupActivity)
        bytes[138] = 0; bytes[139] = 2; assertNull(KnobProtocol.snapshot(bytes).setupActivity)
    }

    @Test
    fun `version three carries device units and limits while commands retain version two`() {
        val old = twinSnapshot()
        val bytes = ByteArray(old.size + 8)
        old.copyInto(bytes, 0, 0, 134)
        old.copyInto(bytes, 142, 134)
        bytes[1] = 3
        bytes[134] = 1
        bytes[137] = 1
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(135, 229)
        bytes[133] = ((bytes[133].toInt() and 12.inv()) or 4).toByte()
        val snapshot = KnobProtocol.snapshot(bytes)
        assertEquals(LengthUnit.INCHES, snapshot.trainer!!.lengthUnit)
        assertEquals(WeightUnit.POUNDS, snapshot.trainer.weightUnit)
        assertEquals(229, snapshot.trainer.resistanceLimits.maximumWeightLb)
        val command = KnobProtocol.command(snapshot, 1, parameter = Parameter.WEIGHT, value = 229)
        assertEquals(2, command[1].toInt())
        assertEquals(229, ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getInt(20))
        assertThrows(IllegalArgumentException::class.java) { KnobProtocol.command(snapshot, 2, parameter = Parameter.WEIGHT, value = 230) }
        val legacy = KnobProtocol.snapshot(old)
        assertNull(legacy.trainer!!.lengthUnit)
        assertEquals(ResistanceLimits(), legacy.trainer.resistanceLimits)
    }

    @Test
    fun `firmware 2 bridge loads and unloads with confirmation for every supported mode`() = runTest {
        for (mode in TrainingMode.entries) {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            fun snapshot(loaded: Boolean, uptime: Int) = motorSnapshot(loaded, uptime).also {
                it[19] = mode.wireValue.toByte()
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, if (loaded) 1 else 0)
            }
            link.incoming.send(snapshot(false, 1000))
            runCurrent()
            assertTrue(controller.state.value.trainers.single().canLoad)
            val load = async { controller.setLoaded(trainer, true) }
            runCurrent()
            link.incoming.send(ack(link.writes.single(), 1))
            runCurrent()
            assertFalse(load.isCompleted)
            link.incoming.send(snapshot(true, 1200))
            runCurrent()
            load.await()
            assertEquals(1, controller.state.value.trainers.single().fitnessMode)
            assertTrue(controller.state.value.trainers.single().canUnload)
            val unload = async { controller.setLoaded(trainer, false) }
            runCurrent()
            link.incoming.send(ack(link.writes.last(), 1))
            link.incoming.send(snapshot(false, 1400))
            runCurrent()
            unload.await()
            assertEquals(0, controller.state.value.trainers.single().fitnessMode)
            assertTrue(controller.state.value.trainers.single().unloaded)
            assertEquals(2, link.writes.size)
            controller.close()
        }
    }


    @Test
    fun `selected mode is confirmed through the bridge without legacy motor ready status`() = runTest {
        for (mode in TrainingMode.entries) {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            fun report(rawMode: Int, uptime: Int) = twinSnapshot(uptime = uptime).also {
                it[19] = rawMode.toByte()
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 0)
            }
            link.incoming.send(report(0, 1000))
            runCurrent()
            val selecting = async { controller.setMode(trainer, mode) }
            runCurrent()
            val command = link.writes.single()
            assertEquals(KnobProtocol.SET_MODE, command[2].toInt())
            link.incoming.send(ack(command, 0)) // Admission is not mode confirmation.
            link.incoming.send(report(0, 1100))
            runCurrent()
            assertFalse(selecting.isCompleted)
            link.incoming.send(ack(command, 1))
            link.incoming.send(report(mode.wireValue, 1200))
            runCurrent()
            selecting.await()
            val state = controller.state.value.trainers.first()
            assertTrue(state.modeConfirmed)
            assertEquals(mode, state.mode)
            assertEquals(0, state.fitnessMode)
            assertFalse(state.loaded)
            assertFalse(state.canLoad)
            assertFalse(state.canPrepareLoad)
            assertFalse(state.copy(fresh = false).modeConfirmed)
            assertFalse(state.copy(modeWireValue = 99).modeConfirmed)
            assertEquals(1, link.writes.size)
            controller.close()
        }
    }

    @Test
    fun `producer token retains rapid native mode changes but not ordinary loading`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        val original = controller.state.value
        val token = original.telemetryEpochFor(trainer)
        // Do not observe state between the two mode reports.
        link.incoming.send(snapshot(uptime = 1100).also { it[19] = 0 })
        link.incoming.send(snapshot(uptime = 1200))
        runCurrent()
        val changed = controller.state.value
        assertEquals(TrainingMode.WEIGHT, changed.trainers.single().mode)
        assertEquals(original.telemetryEpoch, changed.telemetryEpoch)
        assertTrue(changed.telemetryEpochFor(trainer) > token)
        val modeToken = changed.telemetryEpochFor(trainer)
        link.incoming.send(snapshot(uptime = 1300).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 5)
        })
        link.incoming.send(snapshot(uptime = 1400))
        runCurrent()
        assertEquals(modeToken, controller.state.value.telemetryEpochFor(trainer))
        link.incoming.send(snapshot(session = 8, uptime = 1500))
        runCurrent()
        assertTrue(controller.state.value.telemetryEpochFor(trainer) > modeToken)
        assertEquals(token, original.telemetryEpochFor(trainer))
        controller.close()
    }

    private fun motorSnapshot(
        loaded: Boolean = false,
        uptime: Int = 1000,
        paired: Boolean = false,
    ): ByteArray =
        twinSnapshot(paired = paired, uptime = uptime).also {
            it[19] = 1
            if (!paired) it.fill(0, 82, 132)
            ByteBuffer.wrap(it)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(20, (if (loaded) 5 else 4).toShort())
                .putShort(
                    28,
                    (15 or
                            KnobProtocol.MOTOR_SUPPORTED or
                            if (loaded) KnobProtocol.UNLOAD_ALLOWED else KnobProtocol.LOAD_ALLOWED)
                        .toShort(),
                )
        }

    private fun preparationSnapshot(paired: Boolean = true): ByteArray =
        motorSnapshot(paired = paired).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(
                28,
                (15 or KnobProtocol.MOTOR_SUPPORTED or KnobProtocol.LOAD_PREPARATION_ALLOWED)
                    .toShort(),
            )
        }

    @Test
    fun `Twin preparation capability admits one request but requires confirmed load`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(preparationSnapshot())
        runCurrent()
        val selected = controller.state.value.trainers.first()
        assertFalse(selected.canLoad)
        assertTrue(selected.canPrepareLoad)
        assertFalse(controller.state.value.trainers.last().canPrepareLoad)
        val loading = async { controller.setLoaded(trainer, true) }
        runCurrent()
        val request = link.writes.single()
        assertEquals(KnobProtocol.SET_LOADED, request[2].toInt())
        link.incoming.send(ack(request, 1))
        runCurrent()
        assertFalse(loading.isCompleted)
        assertTrue(controller.state.value.trainers.all { it.unloaded })
        link.incoming.send(motorSnapshot(loaded = true, paired = true, uptime = 1200))
        runCurrent()
        loading.await()
        assertTrue(controller.state.value.trainers.all { it.loaded })
        assertEquals(1, link.writes.size)
        controller.close()
    }

    @Test
    fun `preparation capability never bypasses topology session or readiness guards`() {
        val trainer = requireNotNull(KnobProtocol.snapshot(preparationSnapshot()).trainer)
        assertTrue(trainer.canPrepareLoad)
        val invalid = listOf(
            trainer.copy(loadControlSupported = false),
            trainer.copy(status = ConnectionStatus.DISCONNECTED),
            trainer.copy(fresh = false),
            trainer.copy(pending = true),
            trainer.copy(mode = null),
            trainer.copy(fitnessMode = 5),
            trainer.copy(twinStatusKnown = false),
            trainer.copy(twinVerified = false),
            trainer.copy(twinRole = TwinRole.PEER),
        )
        invalid.forEach { assertFalse(it.canPrepareLoad) }
        val legacy = motorSnapshot(paired = true)
        assertFalse(KnobProtocol.snapshot(legacy).trainer!!.canPrepareLoad)
        assertTrue(KnobProtocol.snapshot(legacy).trainer!!.canLoad)
        val unsupported = preparationSnapshot().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(28, KnobProtocol.LOAD_PREPARATION_ALLOWED.toShort())
        }
        assertFalse(KnobProtocol.snapshot(unsupported).trainer!!.canPrepareLoad)
    }

    @Test
    fun `Twin preparation rejected by cable guard never retries or reports loaded`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(preparationSnapshot())
        runCurrent()
        val loading = async { runCatching { controller.setLoaded(trainer, true) } }
        runCurrent()
        link.incoming.send(ack(link.writes.single(), 3).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(14, 2)
        })
        runCurrent()
        assertTrue(loading.await().exceptionOrNull()!!.message!!.contains("zero position"))
        advanceTimeBy(6500)
        runCurrent()
        assertEquals(1, link.writes.size)
        assertFalse(controller.state.value.trainers.any { it.loaded })
        controller.close()
    }

    /** A native candidate is not a connected peer; firmware validates it during the handshake. */
    private fun manualTwinSnapshot(
        loaded: Boolean,
        paired: Boolean = false,
        twinRequestAllowed: Boolean = true,
        uptime: Int = 1000,
    ): ByteArray =
        motorSnapshot(loaded, uptime, paired).also {
            if (!paired) {
                "Second trainer".toByteArray().copyInto(it, 82)
                "01:02:03:04:05:07".toByteArray().copyInto(it, 114)
            }
            it[133] = (if (!twinRequestAllowed) 0 else if (paired) 2 else 1).toByte()
        }

    @Test
    fun `loaded knob and nearby peer unload before native create then load confirmed pair once`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(manualTwinSnapshot(loaded = true))
            runCurrent()
            val peer = requireNotNull(controller.state.value.knob!!.twinCandidate).id
            assertEquals(1, controller.state.value.trainers.size)
            assertFalse(controller.state.value.canCreateTwin)
            assertTrue(controller.state.value.canCreateTwinAfterUnloading)
            val create = async { controller.createTwinAfterUnloading(trainer, peer) }
            runCurrent()
            val stop = link.writes.single()
            assertEquals(KnobProtocol.SET_LOADED, stop[2].toInt())
            assertEquals(0, ByteBuffer.wrap(stop).order(ByteOrder.LITTLE_ENDIAN).getInt(20))
            link.incoming.send(ack(stop, 1))
            runCurrent()
            assertFalse(create.isCompleted)
            assertEquals(1, link.writes.size)
            // Fitness can confirm STOP before discovery/Twin capability becomes ready again.
            link.incoming.send(manualTwinSnapshot(false, twinRequestAllowed = false, uptime = 1100))
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, link.writes.size)
            link.incoming.send(manualTwinSnapshot(false, uptime = 1200))
            runCurrent()
            advanceTimeBy(25)
            runCurrent()
            val nativeCreate = link.writes.last()
            assertEquals(
                listOf(KnobProtocol.SET_LOADED, KnobProtocol.CREATE_TWIN),
                link.writes.map { it[2].toInt() },
            )
            link.incoming.send(ack(nativeCreate, 1))
            runCurrent()
            assertFalse(create.isCompleted)
            link.incoming.send(manualTwinSnapshot(false, paired = true, uptime = 1300))
            runCurrent()
            create.await()
            assertTrue(controller.state.value.trainers.all { it.unloaded })
            assertFalse(controller.state.value.trainers.single { it.device.id == peer }.canLoad)
            val load = async { controller.setLoaded(trainer, true) }
            runCurrent()
            val go = link.writes.last()
            assertEquals(KnobProtocol.SET_LOADED, go[2].toInt())
            assertEquals(1, ByteBuffer.wrap(go).order(ByteOrder.LITTLE_ENDIAN).getInt(20))
            link.incoming.send(ack(go, 1))
            runCurrent()
            assertFalse(load.isCompleted)
            link.incoming.send(manualTwinSnapshot(true, paired = true, uptime = 1400))
            runCurrent()
            load.await()
            assertTrue(controller.state.value.trainers.all { it.loaded })
            controller.setLoaded(trainer, true)
            assertEquals(3, link.writes.size)
            assertFalse(link.closed)
            controller.close()
        }

    @Test
    fun `loaded native Twin exits through controller STOP and can load independent controller`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(manualTwinSnapshot(true, paired = true))
            runCurrent()
            assertFalse(controller.state.value.canExitTwin)
            assertTrue(controller.state.value.canExitTwinAfterUnloading)
            assertFalse(controller.state.value.trainers.last().canUnload)
            val exit = async { controller.exitTwinAfterUnloading() }
            runCurrent()
            val stop = link.writes.single()
            assertEquals(KnobProtocol.SET_LOADED, stop[2].toInt())
            assertEquals(0, ByteBuffer.wrap(stop).order(ByteOrder.LITTLE_ENDIAN).getInt(20))
            link.incoming.send(ack(stop, 1))
            link.incoming.send(
                manualTwinSnapshot(false, paired = true, twinRequestAllowed = false, uptime = 1100)
            )
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, link.writes.size)
            assertTrue(controller.state.value.trainers.all { it.unloaded })
            link.incoming.send(manualTwinSnapshot(false, paired = true, uptime = 1200))
            runCurrent()
            advanceTimeBy(25)
            runCurrent()
            val nativeExit = link.writes.last()
            assertEquals(
                listOf(KnobProtocol.SET_LOADED, KnobProtocol.EXIT_TWIN),
                link.writes.map { it[2].toInt() },
            )
            link.incoming.send(ack(nativeExit, 1))
            runCurrent()
            assertFalse(exit.isCompleted)
            link.incoming.send(manualTwinSnapshot(false, uptime = 1300))
            runCurrent()
            exit.await()
            assertNull(controller.state.value.twin)
            assertEquals(1, controller.state.value.trainers.size)
            val load = async { controller.setLoaded(trainer, true) }
            runCurrent()
            val go = link.writes.last()
            assertEquals(1, ByteBuffer.wrap(go).order(ByteOrder.LITTLE_ENDIAN).getInt(20))
            link.incoming.send(motorSnapshot(true, uptime = 1400))
            runCurrent()
            assertFalse(load.isCompleted)
            link.incoming.send(ack(go, 1))
            runCurrent()
            load.await()
            assertTrue(controller.state.value.trainers.single().loaded)
            assertEquals(3, link.writes.size)
            assertFalse(link.closed)
            controller.close()
        }

    @Test
    fun `manual native Twin never retries rejected STOP or advances on acknowledgement alone`() =
        runTest {
            for (rejected in listOf(true, false)) {
                val link = Link()
                val controller = controller(link)
                controller.connect(knob)
                runCurrent()
                link.incoming.send(manualTwinSnapshot(true, paired = true))
                runCurrent()
                val exit = async { runCatching { controller.exitTwinAfterUnloading() } }
                runCurrent()
                link.incoming.send(ack(link.writes.single(), if (rejected) 2 else 1))
                runCurrent()
                // A final result without a new unloaded readback cannot authorize topology writes.
                if (!rejected) {
                    advanceTimeBy(6500)
                    runCurrent()
                }
                assertTrue(exit.await().isFailure)
                assertEquals(1, link.writes.size)
                assertEquals(KnobProtocol.SET_LOADED, link.writes.single()[2].toInt())
                assertEquals(!rejected, link.revoked)
                controller.close()
            }
        }

    @Test
    fun `independent motor load does not bind an unconnected Twin candidate`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        val ready = motorSnapshot().also { "01:02:03:04:05:07".toByteArray().copyInto(it, 114) }
        link.incoming.send(ready)
        runCurrent()
        val load = async { controller.setLoaded(trainer, true) }
        runCurrent()
        link.incoming.send(motorSnapshot(loaded = true, uptime = 1200))
        runCurrent()
        link.incoming.send(ack(link.writes.single(), 1))
        runCurrent()
        load.await()
        assertTrue(controller.state.value.trainers.single().loaded)
        assertFalse(link.closed)
        controller.close()
    }

    @Test
    fun `cancelled pending motor revokes link and never replays on later readiness`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(motorSnapshot())
        runCurrent()
        val load = async { controller.setLoaded(trainer, true) }
        runCurrent()
        assertEquals(1, link.writes.size)
        load.cancelAndJoin()
        runCurrent()
        assertTrue(link.revoked)
        assertTrue(link.closed)
        assertTrue(controller.state.value.trainers.isEmpty())
        advanceTimeBy(6500)
        runCurrent()
        assertEquals(1, link.writes.size)
        controller.close()
    }

    @Test
    fun `motor capability is explicit and old bridges cannot load`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        assertFalse(controller.state.value.trainers.single().canLoad)
        assertTrue(runCatching { controller.setLoaded(trainer, true) }.isFailure)
        assertTrue(link.writes.isEmpty())
        // Already unloaded is a harmless confirmed no-op even with older firmware.
        controller.setLoaded(trainer, false)
        assertTrue(link.writes.isEmpty())
        controller.close()
    }

    @Test
    fun `motor load writes once and needs result plus new confirmed status`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(motorSnapshot())
        runCurrent()
        assertTrue(controller.state.value.trainers.single().canLoad)
        val load = async { controller.setLoaded(trainer, true) }
        runCurrent()
        val frame = link.writes.single()
        val command = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(KnobProtocol.SET_LOADED, frame[2].toInt())
        assertEquals(0, command.getShort(18).toInt())
        assertEquals(1, command.getInt(20))
        assertFalse(controller.state.value.trainers.single().loaded)
        link.incoming.send(ack(frame, 1))
        runCurrent()
        assertFalse(load.isCompleted)
        link.incoming.send(motorSnapshot(loaded = true, uptime = 1200))
        runCurrent()
        load.await()
        assertTrue(controller.state.value.trainers.single().loaded)
        controller.setLoaded(trainer, true)
        assertEquals(1, link.writes.size)
        controller.close()
    }

    @Test
    fun `motor unload works for verified Twin controller only`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(motorSnapshot(loaded = true, paired = true))
        runCurrent()
        val peer = controller.state.value.twin!!.peerId
        assertFalse(controller.state.value.trainers.single { it.device.id == peer }.canUnload)
        assertTrue(runCatching { controller.setLoaded(peer, false) }.isFailure)
        assertTrue(link.writes.isEmpty())
        val unload = async { controller.setLoaded(trainer, false) }
        runCurrent()
        val frame = link.writes.single()
        assertEquals(0, ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(20))
        link.incoming.send(motorSnapshot(paired = true, uptime = 1200))
        runCurrent()
        assertFalse(unload.isCompleted)
        link.incoming.send(ack(frame, 1))
        runCurrent()
        unload.await()
        assertTrue(controller.state.value.trainers.all { it.unloaded })
        controller.close()
    }

    @Test
    fun `motor rejection explains cable guard and never replays`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(motorSnapshot())
        runCurrent()
        val load = async { runCatching { controller.setLoaded(trainer, true) } }
        runCurrent()
        link.incoming.send(
            ack(link.writes.single(), 2).also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(14, 2)
            }
        )
        runCurrent()
        assertTrue(load.await().exceptionOrNull()!!.message!!.contains("zero position"))
        advanceTimeBy(6500)
        runCurrent()
        assertEquals(1, link.writes.size)
        assertFalse(controller.state.value.trainers.single().loaded)
        controller.close()
    }

    @Test
    fun `motor cancellation session change and unknown topology never send a second command`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(motorSnapshot().also { it[3] = 3 })
            runCurrent()
            assertTrue(runCatching { controller.setLoaded(trainer, true) }.isFailure)
            assertTrue(link.writes.isEmpty())
            link.incoming.send(motorSnapshot())
            runCurrent()
            val load = async { runCatching { controller.setLoaded(trainer, true) } }
            runCurrent()
            link.incoming.send(
                motorSnapshot(uptime = 1100).also {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 8)
                }
            )
            runCurrent()
            assertTrue(load.await().isFailure)
            assertEquals(1, link.writes.size)
            controller.close()
        }

    @Test
    fun `motor success cannot confirm a different mode or stale status`() = runTest {
        for (changedMode in listOf(false, true)) {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(motorSnapshot())
            runCurrent()
            val load = async { runCatching { controller.setLoaded(trainer, true) } }
            runCurrent()
            link.incoming.send(ack(link.writes.single(), 1))
            runCurrent()
            if (changedMode) link.incoming.send(motorSnapshot(true, 1100).also { it[19] = 2 })
            else link.incoming.send(motorSnapshot(true, 1000))
            runCurrent()
            advanceTimeBy(6500)
            runCurrent()
            assertTrue(load.await().isFailure)
            assertEquals(1, link.writes.size)
            controller.close()
        }
    }

    @Test
    fun `knob Twin requests are session bound deduplicated and never replayed`() = runTest {
        val link = Link()
        val controller = controller(link)
        val events = mutableListOf<KnobTwinRequest>()
        backgroundScope.launch { controller.twinRequests.collect { events += it } }
        controller.connect(knob)
        runCurrent()
        val packet = twinSnapshot().also { it[3] = (it[3].toInt() or 0x60).toByte() }
        link.incoming.send(packet)
        runCurrent()
        fun event(id: Int, epochDelta: Int = 0, sessionDelta: Int = 0): ByteArray {
            val decoded = KnobProtocol.snapshot(packet)
            return ByteBuffer.allocate(20)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put(0x4b)
                    put(2)
                    put(8)
                    put(0)
                    putInt(decoded.epoch + epochDelta)
                    putInt(decoded.session + sessionDelta)
                    putShort(id.toShort())
                    put(byteArrayOf(1, 2, 3, 4, 5, 7))
                }
                .array()
        }
        for (bytes in
            listOf(event(1), event(1), event(2, epochDelta = 1), event(2, sessionDelta = 1))) link
            .incoming
            .send(bytes)
        runCurrent()
        assertEquals(listOf(1), events.map { it.id })
        advanceTimeBy(2250)
        runCurrent()
        link.incoming.send(event(2))
        runCurrent()
        assertEquals(1, events.size)
        link.incoming.send(packet)
        runCurrent()
        link.incoming.send(event(2))
        runCurrent()
        assertEquals(listOf(1, 2), events.map { it.id })
        assertTrue(link.writes.isEmpty())
        controller.close()
    }

    @Test
    fun `trusted peer offer expires and status references request without native pairing writes`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            val packet = twinSnapshot().also { it[3] = (it[3].toInt() or 0x60).toByte() }
            link.incoming.send(packet)
            runCurrent()
            val snapshot = KnobProtocol.snapshot(packet)
            assertTrue(controller.state.value.knob!!.mixedTwinHandoffSupported)
            controller.offerTwinPeer(snapshot.peer)
            val offer = ByteBuffer.wrap(link.writes.single()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(7, offer.get(2).toInt())
            assertEquals(snapshot.uptime + 1500, offer.getInt(12))
            assertEquals(0, offer.getShort(16).toInt())
            assertEquals(1, offer.get(3).toInt())
            assertArrayEquals(
                byteArrayOf(1, 2, 3, 4, 5, 7),
                link.writes.single().copyOfRange(18, 24),
            )
            val request = KnobTwinRequest(snapshot.epoch, snapshot.session, 17, snapshot.peer!!.id)
            controller.reportTwinRequest(request, KnobTwinRequestStatus.ACCEPTED)
            assertEquals(9, link.writes.last()[2].toInt())
            val status = ByteBuffer.wrap(link.writes.last()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, status.getShort(18).toInt())
            assertEquals(1, status.getShort(16).toInt())
            assertEquals(17, status.getInt(20))
            controller.reportTwinRequest(
                request.copy(epoch = snapshot.epoch + 1),
                KnobTwinRequestStatus.SUCCEEDED,
            )
            assertEquals(2, link.writes.size)
            controller.offerTwinPeer(null)
            assertArrayEquals(ByteArray(6), link.writes.last().copyOfRange(18, 24))
            assertTrue(link.writes.none { it[2].toInt() in 5..6 })
            controller.close()
        }

    @Test
    fun `guarded Twin commands bind the exact peer without reusing legacy weight flags`() {
        val legacy = twinSnapshot().also { it[133] = (it[133].toInt() or 0x10).toByte() }
        assertFalse(KnobProtocol.snapshot(legacy).twinIdentityGuarded)
        assertArrayEquals(
            ByteArray(6),
            KnobProtocol.twinCommand(KnobProtocol.snapshot(twinSnapshot()), 1, true)
                .copyOfRange(18, 24),
        )
        val guarded = twinSnapshot().also { it[3] = (it[3].toInt() or 0x20).toByte() }
        assertTrue(KnobProtocol.snapshot(guarded).twinIdentityGuarded)
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 7),
            KnobProtocol.twinCommand(KnobProtocol.snapshot(guarded), 2, true).copyOfRange(18, 24),
        )
        val paired = twinSnapshot(paired = true).also { it[3] = (it[3].toInt() or 0x20).toByte() }
        assertArrayEquals(
            ByteArray(6),
            KnobProtocol.twinCommand(KnobProtocol.snapshot(paired), 3, false).copyOfRange(18, 24),
        )
    }

    @Test
    fun `handoff candidate requires explicit command flag and exact peer guard`() {
        val packet = twinSnapshot().also { it[3] = (it[3].toInt() or 0xe0).toByte() }
        val snapshot = KnobProtocol.snapshot(packet)
        assertTrue(snapshot.twinPeerHandoff)
        val command = KnobProtocol.twinCommand(snapshot, 1, true)
        assertEquals(1, command[3].toInt())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 7), command.copyOfRange(18, 24))
        assertTrue(
            runCatching {
                    KnobProtocol.twinCommand(snapshot.copy(twinIdentityGuarded = false), 1, true)
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    KnobProtocol.twinCommand(
                        snapshot.copy(mixedTwinHandoffSupported = false),
                        1,
                        true,
                    )
                }
                .isFailure
        )
    }

    @Test
    fun `ownership heartbeat stays writable during native Twin and does not consume control ids`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            val packet = twinSnapshot().also { it[3] = (it[3].toInt() or 0xe0).toByte() }
            link.incoming.send(packet)
            runCurrent()
            val snapshot = KnobProtocol.snapshot(packet)
            repeat(100) { controller.offerTwinPeer(snapshot.peer, ready = it % 2 == 0) }
            assertTrue(
                link.writes.all {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getShort(16).toInt() == 0
                }
            )
            assertEquals(0, link.writes.last()[3].toInt()) // Ownership retained, readiness revoked.
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 7), link.writes.last().copyOfRange(18, 24))
            val deadlines =
                link.writes.map { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt(12) }
            assertTrue(deadlines.zipWithNext().all { (before, after) -> after - before > 0 })
            val create = async { controller.createTwin(trainer, snapshot.peer!!.id) }
            runCurrent()
            val command = link.writes.last()
            assertEquals(5, command[2].toInt())
            assertEquals(
                1,
                ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getShort(16).toInt(),
            )
            controller.offerTwinPeer(snapshot.peer)
            assertEquals(7, link.writes.last()[2].toInt())
            assertFalse(create.isCompleted)
            link.incoming.send(ack(command, 1))
            link.incoming.send(
                twinSnapshot(paired = true, uptime = 1100).also {
                    it[3] = (it[3].toInt() or 0x60).toByte()
                }
            )
            runCurrent()
            create.await()
            controller.close()
        }

    private val knob = VoltraDevice("knob", "Test knob")
    private val trainer = "01:02:03:04:05:06"

    private fun movingSnapshot(): ByteArray =
        twinSnapshot().also {
            it[19] = 1
            it[133] = (1 or 8 or 0x20 or 0x80).toByte()
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 5)
        }

    @Test
    fun `device rep count requires a ready fresh independent trainer`() {
        val packet = snapshot(reps = 8)
        assertEquals(8, KnobProtocol.snapshot(packet).trainer!!.deviceReps)
        for (flags in listOf(9, 10, 3, 27)) {
            packet[3] = flags.toByte() // Stale, not ready, unknown topology, or Twin.
            assertNull(KnobProtocol.snapshot(packet).trainer!!.deviceReps)
        }
        assertNull(KnobProtocol.snapshot(snapshot(reps = 65535)).trainer!!.deviceReps)
        assertEquals(0, KnobProtocol.snapshot(snapshot(reps = 0)).trainer!!.deviceReps)
    }

    @Test
    fun `motion flag exposes measured force only in confirmed independent loaded states`() {
        val packet = movingSnapshot()
        for (mode in TrainingMode.entries) {
            packet[19] = mode.wireValue.toByte()
            for (fitness in listOf(1, 5, 21)) {
                ByteBuffer.wrap(packet)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(20, fitness.toShort())
                val decoded = KnobProtocol.snapshot(packet)
                val trainer = decoded.trainer!!
                assertEquals(true, trainer.cableMoving)
                assertEquals(12.3, trainer.measuredForceLb!!, 0.001)
                assertNull(trainer.activeWeightLb)
                assertNull(trainer.activeWeightSource)
                assertNull(trainer.resistanceTargetLb)
                assertNull(trainer.runtimeWireWeightLb)
                assertEquals(WeightUnit.KILOGRAMS, trainer.weightUnit)
                assertTrue(decoded.knobUsbPowerConnected)
                assertTrue(decoded.canCreateTwin)
            }
        }
        for (fitness in listOf(4, 0, 0x26, 0x27, 65535)) {
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, fitness.toShort())
            assertNull(KnobProtocol.snapshot(packet).trainer!!.cableMoving)
            assertNull(KnobProtocol.snapshot(packet).trainer!!.measuredForceLb)
        }
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 5)
        packet[19] = 0
        assertNull(KnobProtocol.snapshot(packet).trainer!!.cableMoving)
        packet[19] = 1
        for (flags in listOf(9, 10, 3, 27)) {
            packet[3] = flags.toByte() // stale, not ready, unknown topology, or Twin
            assertNull(KnobProtocol.snapshot(packet).trainer!!.cableMoving)
            assertNull(KnobProtocol.snapshot(packet).trainer!!.measuredForceLb)
        }
        packet[3] = 11
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(22, -1)
        assertNull(KnobProtocol.snapshot(packet).trainer!!.cableMoving)
    }

    @Test
    fun `motion source flags reject ambiguity and old bridge readings never imply motion`() {
        for (flags in listOf(0x80 or 0x40, 0x80 or 0x10, 0x80 or 0x40 or 0x10)) {
            val packet = movingSnapshot().also { it[133] = flags.toByte() }
            assertTrue(runCatching { KnobProtocol.snapshot(packet) }.isFailure)
        }
        for (flags in listOf(0, 0x10, 0x40)) {
            val packet = movingSnapshot().also { it[133] = flags.toByte() }
            assertNull(KnobProtocol.snapshot(packet).trainer!!.cableMoving)
        }
        assertNull(KnobProtocol.snapshot(snapshot()).trainer!!.cableMoving)
    }

    @Test
    fun `bridge movement expires before connection status and clears on legacy updates or failure`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(movingSnapshot())
            runCurrent()
            assertEquals(true, controller.state.value.trainers.single().cableMoving)
            advanceTimeBy(751)
            runCurrent()
            assertTrue(controller.state.value.trainers.single().fresh)
            assertNull(controller.state.value.trainers.single().cableMoving)
            link.incoming.send(movingSnapshot())
            runCurrent()
            assertEquals(true, controller.state.value.trainers.single().cableMoving)
            link.incoming.send(snapshot())
            runCurrent()
            assertNull(controller.state.value.trainers.single().cableMoving)
            link.incoming.send(movingSnapshot())
            runCurrent()
            link.incoming.close(IllegalStateException("Disconnected"))
            runCurrent()
            assertTrue(controller.state.value.trainers.all { it.cableMoving == null })
            controller.close()
        }

    @Test
    fun `USB power is explicit and independent of battery percentage and trainer selection`() {
        assertFalse(KnobProtocol.snapshot(snapshot()).knobUsbPowerConnected)
        val packet =
            twinSnapshot().also {
                it[17] = 255.toByte()
                it[133] = (1 or 8 or 16).toByte()
            }
        assertFalse(KnobProtocol.snapshot(packet).knobUsbPowerConnected)
        packet[133] = (packet[133].toInt() or 0x20).toByte()
        val powered = KnobProtocol.snapshot(packet)
        assertTrue(powered.knobUsbPowerConnected)
        assertNull(powered.knobBattery)
        assertEquals(WeightUnit.KILOGRAMS, powered.trainer!!.weightUnit)
        assertEquals(ActiveWeightSource.UNLOADED, powered.trainer.activeWeightSource)
        assertEquals(12.3, powered.trainer.runtimeWireWeightLb!!, 0.001)
        assertTrue(powered.canCreateTwin)
        packet[16] = 255.toByte()
        assertTrue(KnobProtocol.snapshot(packet).knobUsbPowerConnected)
        assertNull(KnobProtocol.snapshot(packet).trainer)
    }

    @Test
    fun `knob USB power clears when telemetry stops or connection fails`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        val packet = twinSnapshot().also { it[133] = (it[133].toInt() or 0x20).toByte() }
        link.incoming.send(packet)
        runCurrent()
        assertTrue(controller.state.value.knob!!.usbPowerConnected)
        advanceTimeBy(2251)
        runCurrent()
        assertFalse(controller.state.value.knob!!.usbPowerConnected)
        link.incoming.send(packet)
        runCurrent()
        assertTrue(controller.state.value.knob!!.usbPowerConnected)
        link.incoming.close(IllegalStateException("Disconnected"))
        runCurrent()
        assertFalse(controller.state.value.knob!!.usbPowerConnected)
        controller.close()
    }

    @Test
    fun `bridge preserves captured flagged ready status and obeys control capability gates`() {
        for (packet in listOf(snapshot(), twinSnapshot().also { it[19] = 1 })) {
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 0x0104)
            val trainer = KnobProtocol.snapshot(packet).trainer!!
            assertEquals(0x0104, trainer.fitnessMode)
            assertTrue(trainer.unloaded)
            assertFalse(trainer.loaded)
            assertTrue(trainer.canChangeMode)
            assertTrue(trainer.canEdit(Parameter.WEIGHT))
            assertEquals(0.0, trainer.activeWeightLb!!, 0.001)
            assertEquals(ActiveWeightSource.UNLOADED, trainer.activeWeightSource)
            assertNull(trainer.cableMoving)
            packet[30] = 0
            assertFalse(KnobProtocol.snapshot(packet).trainer!!.canChangeMode)
            packet[30] = 1
            packet[3] = (packet[3].toInt() and 2.inv()).toByte()
            val stale = KnobProtocol.snapshot(packet).trainer!!
            assertFalse(stale.canChangeMode)
            assertFalse(stale.canEdit(Parameter.WEIGHT))
            assertNull(stale.activeWeightLb)
        }
        for (fitness in listOf(0x0204, 0xff04, 0x0105, 0x0126)) {
            val packet =
                snapshot().also {
                    ByteBuffer.wrap(it)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(20, fitness.toShort())
                }
            val trainer = KnobProtocol.snapshot(packet).trainer!!
            assertEquals(fitness, trainer.fitnessMode)
            assertFalse(trainer.unloaded)
            assertFalse(trainer.loaded)
            assertFalse(trainer.canChangeMode)
            assertFalse(trainer.canEdit(Parameter.WEIGHT))
            assertNull(trainer.activeWeightLb)
        }
    }

    @Test
    fun `flagged ready pair retains native reps without exposing moving force`() {
        val packet = pairTelemetrySnapshot(reps = 5)
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 0x0104)
        val decoded = KnobProtocol.snapshot(packet)
        assertEquals(0x0104, decoded.trainer!!.fitnessMode)
        assertTrue(decoded.trainer.canChangeMode)
        assertEquals(5, decoded.twinDeviceReps)
        assertNull(decoded.twinMeasuredForceLb)
        for (fitness in listOf(0x0204, 0xff04, 0x0105, 0x0126)) {
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, fitness.toShort())
            val unknown = KnobProtocol.snapshot(packet)
            assertFalse(unknown.trainer!!.canChangeMode)
            assertNull(unknown.twinDeviceReps)
            assertNull(unknown.twinMeasuredForceLb)
        }
    }

    @Test
    fun `legacy bridge force and runtime readings never become loaded resistance`() {
        for (runtime in listOf(false, true)) {
            val packet =
                twinSnapshot().also {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 5)
                    it[133] = (1 or 8 or if (runtime) 16 else 0).toByte()
                }
            val trainer = KnobProtocol.snapshot(packet).trainer!!
            assertNull(trainer.activeWeightLb)
            assertNull(trainer.activeWeightSource)
            assertNull(trainer.resistanceTargetLb)
            assertEquals(
                12.3,
                (if (runtime) trainer.runtimeWireWeightLb else trainer.measuredForceLb)!!,
                0.001,
            )
            assertEquals(WeightUnit.KILOGRAMS, trainer.weightUnit)
        }
        val old =
            snapshot().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 5) }
        assertNull(KnobProtocol.snapshot(old).trainer!!.activeWeightLb)
        assertEquals(12.3, KnobProtocol.snapshot(old).trainer!!.measuredForceLb!!, 0.001)
    }

    @Test
    fun `target extension preserves flags and active resistance only in confirmed load states`() {
        val packet =
            twinSnapshot().also {
                it[19] = 1
                it[133] = (1 or 8 or 0x20 or 0x40).toByte()
            }
        for (fitness in listOf(1, 5, 21)) {
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, fitness.toShort())
            val decoded = KnobProtocol.snapshot(packet)
            val trainer = decoded.trainer!!
            assertEquals(12.3, trainer.activeWeightLb!!, 0.001)
            assertEquals(12.3, trainer.resistanceTargetLb!!, 0.001)
            assertEquals(ActiveWeightSource.TARGET_FORCE, trainer.activeWeightSource)
            assertNull(trainer.measuredForceLb)
            assertNull(trainer.runtimeWireWeightLb)
            assertEquals(WeightUnit.KILOGRAMS, trainer.weightUnit)
            assertTrue(decoded.knobUsbPowerConnected)
            assertTrue(decoded.canCreateTwin)
        }
        for (fitness in listOf(2, 6, 65535)) {
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, fitness.toShort())
            assertNull(KnobProtocol.snapshot(packet).trainer!!.activeWeightLb)
        }
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 5)
        packet[19] = 0
        assertNull(KnobProtocol.snapshot(packet).trainer!!.activeWeightLb)
        packet[19] = 1
        packet[3] = (packet[3].toInt() and 8.inv()).toByte()
        assertNull(KnobProtocol.snapshot(packet).trainer!!.activeWeightLb)
        packet[3] = 11
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 4)
        assertEquals(0.0, KnobProtocol.snapshot(packet).trainer!!.activeWeightLb!!, 0.001)
        assertEquals(
            ActiveWeightSource.UNLOADED,
            KnobProtocol.snapshot(packet).trainer!!.activeWeightSource,
        )
        assertNull(KnobProtocol.snapshot(packet).trainer!!.resistanceTargetLb)
        packet[3] = (packet[3].toInt() and 2.inv()).toByte()
        assertNull(KnobProtocol.snapshot(packet).trainer!!.activeWeightLb)
        val paired = twinSnapshot(paired = true).also { it[133] = (2 or 8 or 0x40).toByte() }
        assertNull(KnobProtocol.snapshot(paired).trainer!!.activeWeightLb)
        assertNull(KnobProtocol.snapshot(paired).trainer!!.resistanceTargetLb)
        val ambiguous = twinSnapshot().also { it[133] = (0x40 or 0x10).toByte() }
        assertTrue(runCatching { KnobProtocol.snapshot(ambiguous) }.isFailure)
    }

    @Test
    fun `optional v2 unit flags preserve raw weights and Twin capabilities`() {
        for ((flag, unit) in
            listOf(0 to null, 4 to WeightUnit.POUNDS, 8 to WeightUnit.KILOGRAMS, 12 to null)) {
            val bytes = twinSnapshot().also { it[133] = (1 or flag).toByte() }
            val decoded = KnobProtocol.snapshot(bytes)
            assertEquals(unit, decoded.trainer?.weightUnit)
            assertEquals(30, decoded.trainer?.setWeightLb)
            assertEquals(0.0, decoded.trainer!!.activeWeightLb!!, 0.001)
            assertEquals(12.3, decoded.trainer.measuredForceLb!!, 0.001)
            assertTrue(decoded.canCreateTwin)
        }
        assertNull(KnobProtocol.snapshot(snapshot()).trainer?.weightUnit)
    }

    private fun snapshot(
        weight: Int = 30,
        session: Int = 7,
        uptime: Int = 1000,
        reps: Int = 10,
    ): ByteArray =
        ByteBuffer.allocate(106)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(0, 0x4b)
                put(1, 1)
                put(2, 1)
                put(3, 11)
                putInt(4, 123)
                putInt(8, session)
                putInt(12, uptime)
                put(16, 0)
                put(17, 80.toByte())
                put(18, 90.toByte())
                put(19, 1)
                putShort(20, 4)
                putInt(22, 123)
                putShort(26, reps.toShort())
                putShort(28, 15)
                put(30, 1)
                put(31, 4)
                position(32)
                put("Test trainer".toByteArray())
                position(64)
                put(trainer.toByteArray())
                position(82)
                listOf(
                        Parameter.WEIGHT to weight,
                        Parameter.CHAINS to 0,
                        Parameter.INVERSE_CHAINS to 0,
                        Parameter.ECCENTRIC to 0,
                    )
                    .forEach {
                        putShort(it.first.register.toShort())
                        putInt(it.second)
                    }
            }
            .array()

    private fun twinSnapshot(
        paired: Boolean = false,
        pending: Boolean = false,
        uptime: Int = 1000,
    ): ByteArray {
        val previous = snapshot(uptime = uptime)
        val bytes = ByteArray(previous.size + 52)
        previous.copyInto(bytes, 0, 0, 82)
        previous.copyInto(bytes, 134, 82, previous.size)
        bytes[1] = 2
        bytes[3] = (11 or (if (paired) 16 else 0) or (if (pending) 4 else 0)).toByte()
        bytes[19] = 0
        "Second trainer".toByteArray().copyInto(bytes, 82)
        "01:02:03:04:05:07".toByteArray().copyInto(bytes, 114)
        bytes[132] = 72
        bytes[133] = (if (pending) 0 else if (paired) 2 else 1).toByte()
        return bytes
    }

    private fun pairTelemetrySnapshot(
        force: Int = 245,
        reps: Int = 6,
        loaded: Boolean = true,
        pending: Boolean = false,
        peer: String = "AA:BB:CC:DD:EE:02",
        mode: Int = 1,
    ): ByteArray =
        twinSnapshot(paired = true, pending = pending).also {
            "AA:BB:CC:DD:EE:01".toByteArray().copyInto(it, 64)
            peer.toByteArray().copyInto(it, 114)
            it[19] = mode.toByte()
            ByteBuffer.wrap(it)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(20, (if (loaded) 5 else 4).toShort())
                .putInt(22, force)
                .putShort(26, reps.toShort())
            it[133] = (2 or 8 or 0x80).toByte()
        }

    @Test
    fun `paired classified telemetry is logical and never copied into physical trainer sensors`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(pairTelemetrySnapshot())
            runCurrent()
            val state = controller.state.value
            assertEquals(24.5, state.twin!!.measuredForceLb!!, 0.001)
            assertEquals(true, state.twin!!.cableMoving)
            assertEquals(6, state.twin!!.deviceReps)
            assertEquals(6, state.twin!!.reps)
            assertNull(state.twin!!.activeWeightLb)
            state.trainers.forEach {
                assertNull(it.activeWeightLb)
                assertNull(it.measuredForceLb)
                assertNull(it.cableMoving)
                assertNull(it.deviceReps)
                assertNull(it.reps)
            }
            controller.beginSet()
            assertEquals(0, controller.state.value.twin!!.reps)
            assertEquals(6, controller.state.value.twin!!.deviceReps)
            link.incoming.send(pairTelemetrySnapshot(reps = 8))
            runCurrent()
            assertEquals(2, controller.state.value.twin!!.reps)
            assertEquals(8, controller.state.value.twin!!.deviceReps)
            // The trainer begins a new native set; do not subtract the previous set's baseline.
            link.incoming.send(pairTelemetrySnapshot(reps = 1))
            runCurrent()
            assertEquals(1, controller.state.value.twin!!.reps)
            controller.close()
        }

    @Test
    fun `Twin telemetry requires exact verified fresh topology and classified loaded movement`() {
        val valid = pairTelemetrySnapshot()
        assertEquals(24.5, KnobProtocol.snapshot(valid).twinMeasuredForceLb!!, 0.001)
        assertEquals(6, KnobProtocol.snapshot(valid).twinDeviceReps)
        val invalid =
            listOf<(ByteArray) -> Unit>(
                { it[3] = (it[3].toInt() and 1.inv()).toByte() },
                { it[3] = (it[3].toInt() and 2.inv()).toByte() },
                { it[3] = (it[3].toInt() and 8.inv()).toByte() },
                { it[3] = (it[3].toInt() and 16.inv()).toByte() },
                { it[3] = (it[3].toInt() or 4).toByte() },
                { it[19] = 0 },
                { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 2) },
            )
        invalid.forEach { change ->
            val parsed = KnobProtocol.snapshot(valid.copyOf().also(change))
            assertNull(parsed.twinMeasuredForceLb)
            assertNull(parsed.twinDeviceReps)
        }
        // Unload removes moving force without discarding the real device's final count.
        val unloaded = KnobProtocol.snapshot(pairTelemetrySnapshot(loaded = false))
        assertNull(unloaded.twinMeasuredForceLb)
        assertEquals(6, unloaded.twinDeviceReps)
        for (flag in listOf(0, 0x10, 0x40)) {
            val unclassified = valid.copyOf().also { it[133] = (2 or 8 or flag).toByte() }
            assertNull(KnobProtocol.snapshot(unclassified).twinMeasuredForceLb)
        }
        val unavailable = KnobProtocol.snapshot(pairTelemetrySnapshot(force = -1, reps = 65535))
        assertNull(unavailable.twinMeasuredForceLb)
        assertNull(unavailable.twinDeviceReps)
        assertTrue(
            runCatching {
                    KnobProtocol.snapshot(
                        valid.copyOf().also { "AA:BB:CC:DD:EE:01".toByteArray().copyInto(it, 114) }
                    )
                }
                .isFailure
        )
    }

    @Test
    fun `pair motion expires after 500 ms and stale status clears pair counts`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(pairTelemetrySnapshot())
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertNull(controller.state.value.twin!!.measuredForceLb)
        assertNull(controller.state.value.twin!!.cableMoving)
        assertEquals(6, controller.state.value.twin!!.deviceReps)
        advanceTimeBy(1_750)
        runCurrent()
        assertEquals(TwinHealth.DEGRADED, controller.state.value.twin!!.health)
        assertNull(controller.state.value.twin!!.deviceReps)
        assertNull(controller.state.value.twin!!.reps)
        link.incoming.send(pairTelemetrySnapshot())
        runCurrent()
        assertEquals(24.5, controller.state.value.twin!!.measuredForceLb!!, 0.001)
        controller.disconnect(knob.id)
        assertNull(controller.state.value.twin)
        controller.close()
    }

    @Test
    fun `pair source changes never inherit the prior pair or independent set baseline`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(pairTelemetrySnapshot(reps = 10))
        runCurrent()
        controller.beginSet()
        link.incoming.send(pairTelemetrySnapshot(reps = 12))
        runCurrent()
        assertEquals(2, controller.state.value.twin!!.reps)
        link.incoming.send(pairTelemetrySnapshot(reps = 3, peer = "AA:BB:CC:DD:EE:03"))
        runCurrent()
        assertEquals(3, controller.state.value.twin!!.reps)
        controller.beginSet()
        link.incoming.send(pairTelemetrySnapshot(reps = 2, peer = "AA:BB:CC:DD:EE:03", mode = 2))
        runCurrent()
        assertEquals(2, controller.state.value.twin!!.reps)
        link.incoming.send(twinSnapshot())
        runCurrent()
        assertNull(controller.state.value.twin)
        assertEquals(10, controller.state.value.trainers.single().reps)
        controller.close()
    }

    @Test
    fun `first Twin rep after an unknown baseline counts once without changing raw count`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(pairTelemetrySnapshot(reps = 65535))
            runCurrent()
            controller.beginSet()
            assertNull(controller.state.value.twin!!.reps)
            link.incoming.send(pairTelemetrySnapshot(reps = 8))
            runCurrent()
            assertEquals(1, controller.state.value.twin!!.reps)
            assertEquals(8, controller.state.value.twin!!.deviceReps)
            link.incoming.send(pairTelemetrySnapshot(reps = 8))
            runCurrent()
            assertEquals(1, controller.state.value.twin!!.reps)
            controller.close()
        }

    @Test
    fun `Firmware 2 Twin count reset preserves all three completed reps`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        // The physical 2026-10-28 capture held the previous set's 4 until the
        // next set's first completed rep. There was no intervening zero packet.
        link.incoming.send(pairTelemetrySnapshot(reps = 4, loaded = false))
        runCurrent()
        controller.beginSet()
        link.incoming.send(pairTelemetrySnapshot(reps = 4))
        runCurrent()
        assertEquals(0, controller.state.value.twin!!.reps)
        for (reps in 1..3) {
            repeat(2) {
                link.incoming.send(pairTelemetrySnapshot(reps = reps))
                runCurrent()
                assertEquals(reps, controller.state.value.twin!!.reps)
                assertEquals(reps, controller.state.value.twin!!.deviceReps)
            }
        }
        link.incoming.send(pairTelemetrySnapshot(reps = 3, loaded = false))
        runCurrent()
        assertEquals(3, controller.state.value.twin!!.reps)
        assertEquals(3, controller.state.value.twin!!.deviceReps)
        controller.close()
    }

    @Test
    fun `missing pair count preserves the known set baseline without inventing reps`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(pairTelemetrySnapshot(reps = 10))
        runCurrent()
        controller.beginSet()
        link.incoming.send(pairTelemetrySnapshot(reps = 12))
        runCurrent()
        assertEquals(2, controller.state.value.twin!!.reps)
        link.incoming.send(pairTelemetrySnapshot(reps = 65535))
        runCurrent()
        assertNull(controller.state.value.twin!!.reps)
        assertNull(controller.state.value.twin!!.deviceReps)
        link.incoming.send(snapshot().also { it[16] = 255.toByte() })
        runCurrent()
        assertTrue(controller.state.value.trainers.isEmpty())
        link.incoming.send(pairTelemetrySnapshot(reps = 12))
        runCurrent()
        assertEquals(2, controller.state.value.twin!!.reps)
        link.incoming.send(pairTelemetrySnapshot(reps = 13))
        runCurrent()
        assertEquals(3, controller.state.value.twin!!.reps)
        assertEquals(13, controller.state.value.twin!!.deviceReps)
        controller.close()
    }

    @Test
    fun `v1 snapshots never read absent v2 pair telemetry extensions`() {
        val legacy = snapshot()
        assertEquals(1, legacy[1].toInt())
        val decoded = KnobProtocol.snapshot(legacy)
        assertNull(decoded.peer)
        assertNull(decoded.twinMeasuredForceLb)
        assertNull(decoded.twinDeviceReps)
        assertEquals(10, decoded.trainer!!.deviceReps)
        val minimal =
            legacy.copyOf(82).also {
                it[31] = 0
                it[28] = 0
                it[29] = 0
            }
        assertNull(KnobProtocol.snapshot(minimal).twinMeasuredForceLb)
    }

    @Test
    fun `v2 paired snapshot supplies verified identity and both batteries without inventing telemetry`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(twinSnapshot(paired = true))
            runCurrent()
            val state = controller.state.value
            assertEquals(2, state.trainers.size)
            assertEquals(trainer, state.twin?.controllerId)
            assertEquals("01:02:03:04:05:07", state.twin?.peerId)
            assertEquals(72, state.trainers.last().batteryPercent)
            assertTrue(state.canExitTwin)
            assertNull(state.twin?.activeWeightLb)
            assertNull(state.trainers.first().activeWeightLb)
            assertFalse(state.trainers.last().canEdit(Parameter.WEIGHT))
            advanceTimeBy(2250)
            runCurrent()
            assertEquals(TwinHealth.DEGRADED, controller.state.value.twin?.health)
            assertFalse(controller.state.value.canExitTwin)
            controller.close()
        }

    @Test
    fun `cancelled accepted Twin revokes its session before close without resending`() = runTest {
        for (create in listOf(true, false)) {
            val link = Link()
            val revokeConfirmed = CompletableDeferred<Unit>()
            link.beforeRevoke = { revokeConfirmed.await() }
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(twinSnapshot(paired = !create))
            runCurrent()
            val request = async {
                if (create) controller.createTwin(trainer, "01:02:03:04:05:07")
                else controller.exitTwin()
            }
            runCurrent()
            link.incoming.send(ack(link.writes.single(), 0))
            runCurrent()
            request.cancel()
            runCurrent()
            assertEquals(listOf("revoke-start"), link.lifecycle)
            assertFalse(link.closed)
            assertFalse(request.isCompleted)
            revokeConfirmed.complete(Unit)
            request.join()
            runCurrent()
            assertEquals(
                listOf("revoke-start", "revoke-confirmed", "close"),
                link.lifecycle.take(3),
            )
            assertTrue(controller.state.value.trainers.isEmpty())
            assertFalse(controller.state.value.twinPending)
            assertEquals(ConnectionStatus.DISCONNECTED, controller.state.value.knob!!.status)
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(1, link.writes.size)
            assertTrue(
                link.incoming.trySend(twinSnapshot(paired = create, uptime = 2000)).isFailure
            )
            controller.close()
        }
    }

    @Test
    fun `timed out Twin confirmation revokes once and never retries topology`() = runTest {
        for (create in listOf(true, false)) {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(twinSnapshot(paired = !create))
            runCurrent()
            val request = async {
                runCatching {
                    if (create) controller.createTwin(trainer, "01:02:03:04:05:07")
                    else controller.exitTwin()
                }
            }
            runCurrent()
            // Even a success result cannot confirm a change without its new native topology.
            link.incoming.send(ack(link.writes.single(), 1))
            runCurrent()
            advanceTimeBy(45_100)
            runCurrent()
            assertTrue(request.await().exceptionOrNull()!!.message!!.contains("not confirmed"))
            assertEquals(
                listOf("revoke-start", "revoke-confirmed", "close"),
                link.lifecycle.take(3),
            )
            assertEquals(1, link.lifecycle.count { it == "revoke-start" })
            assertEquals(1, link.writes.size)
            assertTrue(controller.state.value.trainers.isEmpty())
            assertFalse(controller.state.value.twinPending)
            controller.close()
        }
    }

    @Test
    fun `knob pair and exit require result plus matching fresh topology`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(twinSnapshot())
        runCurrent()
        assertEquals(1, controller.state.value.trainers.size)
        assertTrue(controller.state.value.canCreateTwin)
        val create = async { controller.createTwin(trainer, "01:02:03:04:05:07") }
        runCurrent()
        assertEquals(5, link.writes.single()[2].toInt())
        link.incoming.send(ack(link.writes.single(), 1))
        runCurrent()
        assertFalse(create.isCompleted)
        link.incoming.send(twinSnapshot(paired = true, uptime = 1100))
        runCurrent()
        create.await()
        val exit = async { controller.exitTwin() }
        runCurrent()
        assertEquals(6, link.writes.last()[2].toInt())
        link.incoming.send(ack(link.writes.last(), 1))
        runCurrent()
        assertFalse(exit.isCompleted)
        link.incoming.send(twinSnapshot(uptime = 1200))
        runCurrent()
        exit.await()
        assertNull(controller.state.value.twin)
        controller.close()
    }

    @Test
    fun `uppercase handoff identities match lowercase native confirmation without timing out`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            val controllerId = "80:65:99:92:7E:D1"
            val peerId = "80:65:99:92:7E:E2"
            fun packet(paired: Boolean, peer: String = peerId, local: String = controllerId) =
                twinSnapshot(paired = paired).also {
                    local.toByteArray().copyInto(it, 64)
                    peer.toByteArray().copyInto(it, 114)
                    it[3] = (it[3].toInt() or 0x60 or (if (paired) 0 else 0x80)).toByte()
                }
            link.incoming.send(packet(false))
            runCurrent()
            val epoch = controller.state.value.telemetryEpoch
            // Callers may use canonical MAC casing independently from snapshot spelling.
            val create = async {
                controller.createTwin(controllerId.lowercase(), peerId.lowercase())
            }
            runCurrent()
            val command = link.writes.single()
            assertEquals(5, command[2].toInt())
            assertEquals(1, command[3].toInt())
            link.incoming.send(ack(command, 1))
            runCurrent()
            assertFalse(create.isCompleted)
            // A genuinely different peer still cannot confirm the request.
            link.incoming.send(packet(true, "80:65:99:92:7e:e3", controllerId.lowercase()))
            runCurrent()
            assertFalse(create.isCompleted)
            link.incoming.send(packet(true, peerId.lowercase(), controllerId.lowercase()))
            runCurrent()
            assertTrue(
                "Confirmed native pair must finish without waiting 45 seconds",
                create.isCompleted,
            )
            create.await()
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(epoch, controller.state.value.telemetryEpoch)
            assertFalse(controller.state.value.twinPending)
            assertTrue(controller.state.value.trainers.none { it.pending })
            assertEquals(1, link.writes.size)
            assertEquals(peerId.lowercase(), controller.state.value.twin?.peerId)
            controller.close()
        }

    @Test
    fun `controller address casing preserves reps pending settings and lifecycle identity`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            val trainerId = "AA:BB:CC:DD:EE:FF"
            fun packet(id: String, weight: Int = 30, reps: Int = 10) =
                snapshot(weight = weight, reps = reps).also { id.toByteArray().copyInto(it, 64) }
            link.incoming.send(packet(trainerId))
            runCurrent()
            controller.beginSet()
            val epoch = controller.state.value.telemetryEpoch
            val setting = async {
                controller.setParameter(trainerId.lowercase(), Parameter.WEIGHT, 35)
            }
            runCurrent()
            val command = link.writes.single()
            link.incoming.send(ack(command, 1))
            link.incoming.send(packet(trainerId.lowercase(), weight = 35, reps = 12))
            runCurrent()
            assertTrue(setting.isCompleted)
            setting.await()
            assertEquals(epoch, controller.state.value.telemetryEpoch)
            assertEquals(2, controller.state.value.trainers.single().reps)
            // A relayed trainer cannot be forgotten using a different spelling either.
            assertTrue(runCatching { controller.forget(trainerId) }.isFailure)
            controller.disconnect(knob.id.uppercase())
            assertEquals(ConnectionStatus.DISCONNECTED, controller.state.value.knob?.status)
            assertTrue(link.closed)
            controller.close()
        }

    @Test
    fun `queued Twin request never switches its intended controller after a session change`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(twinSnapshot().also { it[19] = 1 })
            runCurrent()
            val setting = async {
                runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 35) }
            }
            runCurrent()
            val create = async {
                runCatching { controller.createTwin(trainer, "01:02:03:04:05:07") }
            }
            runCurrent()
            link.incoming.send(
                twinSnapshot().also {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 8)
                    "01:02:03:04:05:08".toByteArray().copyInto(it, 64)
                }
            )
            runCurrent()
            assertTrue(setting.await().isFailure)
            assertTrue(create.await().isFailure)
            assertEquals(1, link.writes.size)
            controller.close()
        }

    private fun ack(command: ByteArray, code: Int): ByteArray =
        ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(0x4b)
                put(command[1])
                put(4)
                put(code.toByte())
                put(command, 4, 8)
                put(command, 16, 2)
                putShort(0)
            }
            .array()

    private class Link : VoltraLink {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var closed = false
        var revoked = false
        val lifecycle = mutableListOf<String>()
        var beforeRevoke: suspend () -> Unit = {}
        override val packets = incoming.receiveAsFlow()

        override suspend fun write(frame: ByteArray) {
            writes += frame
        }

        override suspend fun revoke() {
            lifecycle += "revoke-start"
            beforeRevoke()
            revoked = true
            lifecycle += "revoke-confirmed"
            close()
        }

        override fun close() {
            lifecycle += "close"
            closed = true
            incoming.close()
        }
    }

    private fun TestScope.controller(link: Link): KnobVoltraController =
        KnobVoltraController(
            object : VoltraTransport {
                override fun scan() = flowOf(listOf(knob))

                override suspend fun connect(device: VoltraDevice) = link
            },
            backgroundScope,
            now = { testScheduler.currentTime },
        )

    @Test
    fun `snapshot rejects truncated packets invalid values and unknown protocol`() {
        val packet = snapshot()
        assertEquals(0.0, KnobProtocol.snapshot(packet).trainer!!.activeWeightLb!!, 0.01)
        assertEquals(12.3, KnobProtocol.snapshot(packet).trainer!!.measuredForceLb!!, 0.01)
        assertTrue(runCatching { KnobProtocol.snapshot(packet.copyOf(100)) }.isFailure)
        assertTrue(runCatching { KnobProtocol.snapshot(snapshot(weight = 500)) }.isFailure)
        packet[1] = 2
        assertTrue(runCatching { KnobProtocol.snapshot(packet) }.isFailure)
    }

    @Test
    fun `failed bridge can reconnect directly without stale Twin state or replaying commands`() =
        runTest {
            val first = Link()
            val second = Link()
            var attempts = 0
            val controller =
                KnobVoltraController(
                    object : VoltraTransport {
                        override fun scan() = flowOf(listOf(knob))

                        override suspend fun connect(device: VoltraDevice) =
                            if (++attempts == 1) first else second
                    },
                    backgroundScope,
                    now = { testScheduler.currentTime },
                )
            controller.connect(knob)
            runCurrent()
            first.incoming.send(twinSnapshot(paired = true))
            runCurrent()
            val beforeFailure = controller.state.value.telemetryEpoch
            first.incoming.close(
                IllegalStateException("Knob disconnected (8). Reconnect to continue.")
            )
            runCurrent()
            assertEquals(ConnectionStatus.FAILED, controller.state.value.knob!!.status)
            assertTrue(first.closed)
            assertNull(controller.state.value.twin)
            assertNull(controller.state.value.knob!!.batteryPercent)
            assertFalse(controller.state.value.canExitTwin)
            val afterFailure = controller.state.value.telemetryEpoch
            assertTrue(afterFailure > beforeFailure)
            controller.connect(knob)
            runCurrent()
            assertTrue(controller.state.value.telemetryEpoch >= afterFailure)
            assertEquals(ConnectionStatus.CONNECTING, controller.state.value.knob!!.status)
            assertTrue(controller.state.value.trainers.isEmpty())
            assertNull(controller.state.value.knob!!.error)
            second.incoming.send(snapshot(reps = 25))
            runCurrent()
            assertEquals(ConnectionStatus.READY, controller.state.value.knob!!.status)
            assertEquals(25, controller.state.value.trainers.single().reps)
            assertTrue(controller.state.value.telemetryEpoch >= afterFailure)
            assertTrue(second.writes.isEmpty())
            controller.close()
        }

    @Test
    fun `remembered knob reconnects without discovery and waits for fresh readings`() = runTest {
        val link = Link()
        var attempts = 0
        val remembered = mutableListOf<VoltraDevice>()
        val controller =
            KnobVoltraController(
                object : VoltraTransport {
                    override fun scan(): Flow<List<VoltraDevice>> = error("Reconnect must not scan")

                    override suspend fun connect(device: VoltraDevice): VoltraLink {
                        assertEquals(knob, device)
                        attempts++
                        return link
                    }
                },
                backgroundScope,
                now = { testScheduler.currentTime },
                rememberedDevice = knob,
                onConnected = remembered::add,
            )
        runCurrent()
        assertEquals(ConnectionStatus.DISCONNECTED, controller.state.value.knob!!.status)
        assertEquals(0, attempts)
        controller.connect(knob)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(ConnectionStatus.CONNECTING, controller.state.value.knob!!.status)
        assertTrue(controller.state.value.trainers.isEmpty())
        assertTrue(remembered.isEmpty())
        link.incoming.send(snapshot())
        runCurrent()
        link.incoming.send(snapshot(uptime = 1100))
        runCurrent()
        assertEquals(listOf(knob), remembered)
        assertEquals(ConnectionStatus.READY, controller.state.value.knob!!.status)
        assertTrue(link.writes.isEmpty())
        controller.disconnect(knob.id)
        assertEquals(ConnectionStatus.DISCONNECTED, controller.state.value.knob!!.status)
        assertNull(controller.state.value.knob!!.batteryPercent)
        assertTrue(controller.state.value.trainers.isEmpty())
        assertTrue(link.writes.isEmpty())
        controller.close()
    }

    @Test
    fun `invalid first reading never remembers a new knob`() = runTest {
        val link = Link()
        val remembered = mutableListOf<VoltraDevice>()
        val controller =
            KnobVoltraController(
                object : VoltraTransport {
                    override fun scan() = flowOf(listOf(knob))

                    override suspend fun connect(device: VoltraDevice) = link
                },
                backgroundScope,
                onConnected = remembered::add,
            )
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot().copyOf(80))
        runCurrent()
        assertEquals(ConnectionStatus.FAILED, controller.state.value.knob!!.status)
        assertTrue(remembered.isEmpty())
        controller.disconnect(knob.id)
        assertNull(controller.state.value.knob)
        controller.close()
    }

    @Test
    fun `forget cancels acknowledged and queued changes and late snapshots cannot remember knob`() =
        runTest {
            val link = Link()
            var saved: VoltraDevice? = null
            val controller =
                KnobVoltraController(
                    object : VoltraTransport {
                        override fun scan() = flowOf(listOf(knob))

                        override suspend fun connect(device: VoltraDevice) = link
                    },
                    backgroundScope,
                    onConnected = { saved = it },
                    onForgotten = { if (saved?.id == it) saved = null },
                )
            controller.connect(knob)
            runCurrent()
            link.incoming.send(snapshot())
            runCurrent()
            assertEquals(knob, saved)
            val setting = async {
                runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 35) }
            }
            runCurrent()
            link.incoming.send(ack(link.writes.single(), 1))
            runCurrent()
            assertFalse(setting.isCompleted) // ACK arrived, but the readback has not.
            val queued = async {
                runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 40) }
            }
            runCurrent()
            link.incoming.send(snapshot(weight = 35, uptime = 1100)) // Buffered late reading.
            controller.forget(knob.id)
            runCurrent()
            assertTrue(setting.await().isFailure)
            assertTrue(queued.await().isFailure)
            assertNull(saved)
            assertNull(controller.state.value.knob)
            assertTrue(controller.state.value.trainers.isEmpty())
            assertTrue(link.closed)
            assertEquals(1, link.writes.size)
            controller.close()
        }

    @Test
    fun `forget removes restored reconnect target but rejects a relayed trainer`() = runTest {
        val link = Link()
        val forgotten = mutableListOf<String>()
        val controller =
            KnobVoltraController(
                object : VoltraTransport {
                    override fun scan() = flowOf(listOf(knob))

                    override suspend fun connect(device: VoltraDevice) = link
                },
                backgroundScope,
                rememberedDevice = knob,
                onForgotten = forgotten::add,
            )
        controller.forget(knob.id)
        assertNull(controller.state.value.knob)
        assertEquals(listOf(knob.id), forgotten)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        assertTrue(runCatching { controller.forget(trainer) }.isFailure)
        assertEquals(ConnectionStatus.READY, controller.state.value.knob?.status)
        assertFalse(link.closed)
        assertTrue(link.writes.isEmpty())
        controller.close()
    }

    @Test
    fun `transport timeout becomes a recoverable failure instead of remaining connecting`() =
        runTest {
            val controller =
                KnobVoltraController(
                    object : VoltraTransport {
                        override fun scan() = flowOf(listOf(knob))

                        override suspend fun connect(device: VoltraDevice): VoltraLink =
                            withTimeout(1000) { awaitCancellation() }
                    },
                    backgroundScope,
                )
            controller.connect(knob)
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(ConnectionStatus.FAILED, controller.state.value.knob!!.status)
            assertTrue(controller.state.value.knob!!.error!!.contains("Knob connection timed out"))
            controller.close()
        }

    @Test
    fun `cancelling pairing clears the knob without publishing a failure`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        controller.disconnect(knob.id)
        advanceTimeBy(90_001)
        runCurrent()
        assertNull(controller.state.value.knob)
        assertTrue(link.closed)
        controller.close()
    }

    @Test
    fun `pairing allows code entry time but closes a silent connection`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(link.closed)
        assertEquals(ConnectionStatus.CONNECTING, controller.state.value.knob!!.status)
        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(link.closed)
        assertEquals(ConnectionStatus.FAILED, controller.state.value.knob!!.status)
        controller.close()
    }

    @Test
    fun `settings wait for confirmation and readback never update optimistically`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        val edit = async { controller.setParameter(trainer, Parameter.WEIGHT, 35) }
        runCurrent()
        assertEquals(30, controller.state.value.trainers.single().setWeightLb)
        val command = link.writes.single()
        assertEquals(3000, ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
        link.incoming.send(ack(command, 0))
        runCurrent()
        assertFalse(edit.isCompleted)
        link.incoming.send(ack(command, 1))
        runCurrent()
        assertFalse(edit.isCompleted)
        link.incoming.send(snapshot(weight = 35, uptime = 1100))
        runCurrent()
        edit.await()
        assertEquals(35, controller.state.value.trainers.single().setWeightLb)
        controller.close()
    }

    @Test
    fun `stale bridge telemetry clears force and blocks commands`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        assertEquals(10, controller.state.value.trainers.single().deviceReps)
        advanceTimeBy(2251)
        runCurrent()
        assertFalse(controller.state.value.trainers.single().fresh)
        assertNull(controller.state.value.trainers.single().activeWeightLb)
        assertNull(controller.state.value.trainers.single().deviceReps)
        assertTrue(runCatching { controller.setMode(trainer, TrainingMode.BAND) }.isFailure)
        assertTrue(link.writes.isEmpty())
        controller.close()
    }

    @Test
    fun `fresh same-trainer session resets advance telemetry epoch but ordinary reps do not`() =
        runTest {
            val link = Link()
            val controller = controller(link)
            controller.connect(knob)
            runCurrent()
            link.incoming.send(snapshot(reps = 10))
            runCurrent()
            val originalEpoch = controller.state.value.telemetryEpoch
            controller.beginSet()
            link.incoming.send(snapshot(reps = 11))
            runCurrent()
            assertEquals(1, controller.state.value.trainers.single().reps)
            assertEquals(originalEpoch, controller.state.value.telemetryEpoch)

            // The TV-to-knob link and trainer ID stay unchanged and every snapshot is fresh.
            // A new bridge trainer session invalidates the app's per-set baseline nevertheless.
            link.incoming.send(snapshot(session = 8, reps = 20))
            runCurrent()
            assertEquals(originalEpoch + 1, controller.state.value.telemetryEpoch)
            assertEquals(trainer, controller.state.value.trainers.single().device.id)
            assertTrue(controller.state.value.trainers.single().fresh)
            assertEquals(20, controller.state.value.trainers.single().reps)

            link.incoming.send(
                snapshot(session = 8, reps = 21).also {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 124)
                }
            )
            runCurrent()
            assertEquals(originalEpoch + 2, controller.state.value.telemetryEpoch)
            link.incoming.send(
                snapshot(session = 8, reps = 22).also {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 124)
                    "01:02:03:04:05:07".toByteArray().copyInto(it, 64)
                }
            )
            runCurrent()
            assertEquals(originalEpoch + 3, controller.state.value.telemetryEpoch)
            controller.disconnect(knob.id)
            assertTrue(controller.state.value.telemetryEpoch > originalEpoch + 3)
            controller.close()
        }

    @Test
    fun `trainer session change invalidates pending command and resets rep baseline`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        val edit = async { runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 35) } }
        runCurrent()
        link.incoming.send(snapshot(session = 8, reps = 2))
        runCurrent()
        assertTrue(edit.await().isFailure)
        assertEquals(2, controller.state.value.trainers.single().reps)
        link.incoming.send(snapshot(session = 8, reps = 4))
        runCurrent()
        assertEquals(4, controller.state.value.trainers.single().reps)
        controller.beginSet()
        assertEquals(0, controller.state.value.trainers.single().reps)
        assertEquals(4, controller.state.value.trainers.single().deviceReps)
        controller.close()
    }

    @Test
    fun `unknown first count and trainer reset never consume a hardware rep`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot(reps = 65535))
        runCurrent()
        assertNull(controller.state.value.trainers.single().reps)
        link.incoming.send(snapshot(reps = 1))
        runCurrent()
        assertEquals(1, controller.state.value.trainers.single().reps)
        link.incoming.send(snapshot(reps = 10))
        runCurrent()
        controller.beginSet()
        assertEquals(0, controller.state.value.trainers.single().reps)
        link.incoming.send(snapshot(reps = 1))
        runCurrent()
        assertEquals(1, controller.state.value.trainers.single().reps)
        link.incoming.send(snapshot(reps = 2))
        runCurrent()
        assertEquals(2, controller.state.value.trainers.single().reps)
        controller.close()
    }

    @Test
    fun `explicit set offset survives unknown and stale readings`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot(reps = 10))
        runCurrent()
        controller.beginSet()
        link.incoming.send(snapshot(reps = 11))
        runCurrent()
        assertEquals(1, controller.state.value.trainers.single().reps)
        assertEquals(11, controller.state.value.trainers.single().deviceReps)
        link.incoming.send(snapshot(reps = 0).also { it[3] = 9 })
        runCurrent()
        assertNull(controller.state.value.trainers.single().reps)
        assertNull(controller.state.value.trainers.single().deviceReps)
        link.incoming.send(snapshot(reps = 65535))
        runCurrent()
        assertNull(controller.state.value.trainers.single().reps)
        assertNull(controller.state.value.trainers.single().deviceReps)
        link.incoming.send(snapshot().also { it[16] = 255.toByte() })
        runCurrent()
        assertTrue(controller.state.value.trainers.isEmpty())
        link.incoming.send(snapshot(reps = 12))
        runCurrent()
        assertEquals(2, controller.state.value.trainers.single().reps)
        assertEquals(12, controller.state.value.trainers.single().deviceReps)
        controller.close()
    }

    @Test
    fun `set started without a fresh count includes its first fresh rep`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot(reps = 10))
        runCurrent()
        advanceTimeBy(2251)
        runCurrent()
        controller.beginSet()
        assertNull(controller.state.value.trainers.single().reps)
        link.incoming.send(snapshot(reps = 0).also { it[3] = 9 })
        runCurrent()
        link.incoming.send(snapshot(reps = 11))
        runCurrent()
        assertEquals(1, controller.state.value.trainers.single().reps)
        link.incoming.send(snapshot(reps = 12))
        runCurrent()
        assertEquals(2, controller.state.value.trainers.single().reps)
        controller.close()
    }

    @Test
    fun `disconnect clears readings and never replays an in flight command`() = runTest {
        val link = Link()
        val controller = controller(link)
        controller.connect(knob)
        runCurrent()
        link.incoming.send(snapshot())
        runCurrent()
        val edit = async { runCatching { controller.setParameter(trainer, Parameter.WEIGHT, 35) } }
        runCurrent()
        controller.disconnect(knob.id)
        runCurrent()
        assertTrue(edit.await().isFailure)
        assertTrue(link.closed)
        assertTrue(controller.state.value.trainers.isEmpty())
        assertEquals(ConnectionStatus.DISCONNECTED, controller.state.value.knob!!.status)
        assertEquals(1, link.writes.size)
        controller.close()
    }

    @Test
    fun `bridge idle mode is distinct from unsupported mode for both unloaded states`() {
        for (packet in listOf(snapshot(), twinSnapshot())) {
            for (fitness in listOf(0, 4)) {
                ByteBuffer.wrap(packet)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(20, fitness.toShort())
                packet[19] = 0
                val idle = KnobProtocol.snapshot(packet).trainer!!
                assertNull(idle.mode)
                assertEquals(0, idle.modeWireValue)
                assertTrue(idle.canChangeMode)
                assertFalse(idle.copy(fresh = false).canChangeMode)
                assertFalse(idle.copy(twinStatusKnown = false).canChangeMode)
                assertFalse(idle.copy(fitnessMode = 5).canChangeMode)
                assertFalse(idle.copy(modeChangeAllowed = false).canChangeMode)
                packet[19] = 99
                val unsupported = KnobProtocol.snapshot(packet).trainer!!
                assertEquals(99, unsupported.modeWireValue)
                assertFalse(unsupported.canChangeMode)
            }
        }
    }

    @Test
    fun `bridge restrictions narrow direct capability gates`() {
        val state = KnobProtocol.snapshot(snapshot()).trainer!!
        assertTrue(state.canEdit(Parameter.WEIGHT))
        assertFalse(state.copy(editableParameters = emptySet()).canEdit(Parameter.WEIGHT))
        assertFalse(state.copy(modeChangeAllowed = false).canChangeMode)
        assertFalse(state.copy(twinReported = true).canEdit(Parameter.WEIGHT))
    }
}
