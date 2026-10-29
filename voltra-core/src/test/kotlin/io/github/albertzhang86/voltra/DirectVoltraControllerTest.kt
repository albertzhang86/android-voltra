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
    fun `device limits and units update without changing raw resistance or position units`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.MAX_ALLOWED_FORCE] = 229
        rig.link().values[VoltraProtocol.WEIGHT_UNIT] = 1
        rig.link().values[VoltraProtocol.LENGTH_UNIT] = 0
        rig.ready()
        assertEquals(229, rig.trainer().resistanceLimits.maximumWeightLb)
        assertEquals(LimitSource.DEVICE, rig.trainer().resistanceLimits.source)
        assertEquals(WeightUnit.KILOGRAMS, rig.trainer().weightUnit)
        assertEquals(LengthUnit.CENTIMETERS, rig.trainer().lengthUnit)
        rig.controller.setParameter("left", Parameter.ECCENTRIC, 0)
        rig.controller.setParameter("left", Parameter.WEIGHT, 229)
        assertEquals(229, rig.trainer().parameters[Parameter.WEIGHT])
        assertTrue(runCatching { rig.controller.setParameter("left", Parameter.WEIGHT, 230) }.isFailure)
        assertTrue(rig.link().frames.none { it.contentEquals(VoltraProtocol.write(Parameter.WEIGHT.register, 230, 2)) })
        rig.link().send(report(VoltraProtocol.WEIGHT_UNIT, 0, 4))
        rig.link().send(report(VoltraProtocol.LENGTH_UNIT, 1, 4))
        rig.link().send(report(VoltraProtocol.MAX_ALLOWED_FORCE, 230, 2))
        runCurrent()
        assertEquals(WeightUnit.POUNDS, rig.trainer().weightUnit)
        assertEquals(LengthUnit.INCHES, rig.trainer().lengthUnit)
        assertEquals(229, rig.trainer().parameters[Parameter.WEIGHT])
        rig.controller.setParameter("left", Parameter.WEIGHT, 230)
        rig.link().send(report(VoltraProtocol.WEIGHT_UNIT, 0x100, 4))
        rig.link().send(report(VoltraProtocol.LENGTH_UNIT, 0x100, 4))
        rig.link().send(report(VoltraProtocol.MAX_ALLOWED_FORCE, 65535, 2))
        runCurrent()
        assertNull(rig.trainer().weightUnit)
        assertNull(rig.trainer().lengthUnit)
        assertEquals(ResistanceLimits(), rig.trainer().resistanceLimits)
        rig.controller.close()
    }

    @Test
    fun `firmware 2 uses zero one motor words with checked one shot commands and live telemetry`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.FITNESS] = 0
        rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
        rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
        rig.ready()
        assertTrue(rig.trainer().canLoad)
        assertTrue(rig.link().frames.none { it.size >= 17 && it[10].toInt() == 0x11 && VoltraProtocol.unsigned(it, 13, 2) == VoltraProtocol.FITNESS })
        rig.controller.setLoaded("left", true)
        assertEquals(1, rig.trainer().fitnessMode)
        assertTrue(rig.trainer().loaded)
        assertEquals(1, rig.link().frames.count { it.contentEquals(VoltraProtocol.write(VoltraProtocol.FITNESS, 1, 2)) })
        rig.link().send(terseForce(427, targetTenths = 300))
        runCurrent()
        assertEquals(30.0, rig.trainer().activeWeightLb!!, 0.001)
        rig.controller.setLoaded("left", true)
        assertEquals(1, rig.link().frames.count { it.contentEquals(VoltraProtocol.write(VoltraProtocol.FITNESS, 1, 2)) })
        rig.controller.setLoaded("left", false)
        assertEquals(0, rig.trainer().fitnessMode)
        assertEquals(0.0, rig.trainer().activeWeightLb!!, 0.001)
        assertNull(rig.trainer().resistanceTargetLb)
        assertEquals(1, rig.link().frames.count { it.contentEquals(VoltraProtocol.write(VoltraProtocol.FITNESS, 0, 2)) })
        assertTrue(rig.link().frames.none { it.contentEquals(VoltraProtocol.motorLoad()) || it.contentEquals(VoltraProtocol.motorUnload()) })
        rig.controller.close()
    }

    @Test
    fun `firmware 2 never loads when cable is extended or mode is not selected`() = runTest {
        for (mode in listOf(0, 1)) {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.FITNESS] = 0
            rig.link().values[VoltraProtocol.MODE] = mode
            rig.link().values[VoltraProtocol.CABLE_POSITION] = 2
            rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
            rig.ready()
            assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
            assertTrue(rig.link().frames.none { it.size >= 17 && it[10].toInt() == 0x11 && VoltraProtocol.unsigned(it, 13, 2) == VoltraProtocol.FITNESS })
            rig.controller.close()
        }
    }


    @Test
    fun `producer revisions retain rapid selected source changes without disturbing another trainer`() = runTest {
        val rig = Rig(this)
        rig.ready(2)
        val original = rig.controller.state.value
        val leftEpoch = original.telemetryEpochFor("left")
        val rightEpoch = original.telemetryEpochFor("right")
        // Both packets are consumed before the caller observes another state snapshot.
        rig.link().send(report(VoltraProtocol.MODE, 0, 2))
        rig.link().send(report(VoltraProtocol.MODE, 1, 2))
        runCurrent()
        val changed = rig.controller.state.value
        assertEquals(TrainingMode.WEIGHT, changed.trainers.first().mode)
        assertTrue(changed.telemetryEpochFor("left") > leftEpoch)
        assertEquals(rightEpoch, changed.telemetryEpochFor("right"))
        val modeEpoch = changed.telemetryEpochFor("left")
        rig.link().send(report(VoltraProtocol.FITNESS, 5, 2))
        rig.link().send(report(VoltraProtocol.FITNESS, 4, 2))
        runCurrent()
        assertEquals(modeEpoch, rig.controller.state.value.telemetryEpochFor("left"))
        rig.controller.disconnect("left")
        val removed = rig.controller.state.value.telemetryEpochFor("left")
        assertTrue(removed > modeEpoch)
        rig.controller.connect(VoltraDevice("left", "Left"))
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(rig.controller.state.value.telemetryEpochFor("left") > removed)
        assertEquals(rightEpoch, rig.controller.state.value.telemetryEpochFor("right"))
        assertEquals(leftEpoch, original.telemetryEpochFor("left"))
        rig.controller.close()
    }

    @Test
    fun `cancellation as transport returns still closes the link before any protocol writes`() = runTest {
        val link = FakeLink(LEFT_ADDRESS)
        var attempts = 0
        val transport =
            object : VoltraTransport {
                override fun scan() = flowOf(emptyList<VoltraDevice>())

                override suspend fun connect(device: VoltraDevice): VoltraLink {
                    attempts++
                    currentCoroutineContext().cancel(CancellationException("Connection cancelled"))
                    return link
                }
            }
        val controller = DirectVoltraController(transport, backgroundScope)
        controller.connect(VoltraDevice("left", "Left VOLTRA"))
        runCurrent()
        assertTrue(link.closed)
        assertTrue(link.frames.isEmpty())
        assertEquals(1, attempts)
        assertFalse(controller.state.value.trainers.single().connected)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(link.frames.isEmpty())
        controller.close()
    }

    @Test
    fun `mixed reconnect opens one fresh direct transport after failure without touching knob or motors`() =
        runTest {
            val rig = Rig(this)
            rig.ready()
            val knobState =
                MutableStateFlow(
                    VoltraState(
                        viaKnob = true,
                        knob = KnobState(VoltraDevice("knob", "Knob"), ConnectionStatus.READY),
                        trainers =
                            listOf(
                                TrainerState(
                                    VoltraDevice("knob-owned", "Other VOLTRA"),
                                    status = ConnectionStatus.READY,
                                    fresh = true,
                                    twinStatusKnown = true,
                                    mode = TrainingMode.WEIGHT,
                                    modeWireValue = 1,
                                    fitnessMode = 4,
                                )
                            ),
                    )
                )
            val knob =
                object : VoltraController by rig.controller {
                    override val state = knobState

                    override suspend fun connect(device: VoltraDevice) =
                        error("Knob must not reconnect")

                    override fun disconnect(deviceId: String) = error("Knob must stay connected")

                    override fun close() = Unit
                }
            val mixed =
                MixedVoltraController(
                    rig.controller,
                    knob,
                    backgroundScope,
                    VoltraConnectionRoute.KNOB,
                )
            val oldLink = rig.link()
            oldLink.close()
            runCurrent()
            assertEquals(ConnectionStatus.FAILED, rig.trainer().status)
            val untouchedKnob = knob.state.value
            mixed.connect(rig.trainer().device)
            advanceTimeBy(4000)
            runCurrent()
            assertEquals(listOf("left", "left"), rig.connectionAttempts)
            assertNotSame(oldLink, rig.link())
            assertTrue(rig.trainer().connected && rig.trainer().fresh)
            assertEquals(untouchedKnob, knob.state.value)
            assertTrue(mixed.state.value.viaKnob)
            assertEquals(VoltraConnectionRoute.DIRECT, mixed.state.value.routeFor("left"))
            assertFalse(
                rig.link().frames.any {
                    it.contentEquals(VoltraProtocol.motorLoad()) ||
                        it.contentEquals(VoltraProtocol.motorUnload()) ||
                        it[10].toInt() and 255 == VoltraProtocol.TWIN_COMMAND
                }
            )
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(2, rig.connectionAttempts.size)
            mixed.close()
        }

    @Test
    fun `loading rereads the complete Band configuration and rejects a changed native-only field`() =
        runTest {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.MODE] = TrainingMode.BAND.wireValue
            rig.link().values[Parameter.BAND_MAXIMUM.register] = 50
            rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
            rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
            VoltraProtocol.bandReadOnlySettings.forEach { rig.link().values[it] = 0 }
            rig.ready()
            assertTrue(rig.trainer().canLoad)
            rig.link().onSetup = { rig.link().values[0x53b7] = 100 }
            assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
            assertFalse(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorLoad()) })
            rig.controller.close()
        }

    @Test
    fun `missing post-setup setting readback prevents GO despite previous ready configuration`() =
        runTest {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
            rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
            rig.ready()
            assertTrue(rig.trainer().canLoad)
            rig.link().onSetup = { rig.link().values.remove(Parameter.CHAINS.register) }
            assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
            assertFalse(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorLoad()) })
            rig.controller.close()
        }

    @Test
    fun `changed configured resistance during setup prevents GO`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
        rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
        rig.ready()
        rig.link().onSetup = { rig.link().values[Parameter.WEIGHT.register] = 100 }
        assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
        assertFalse(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorLoad()) })
        rig.controller.close()
    }

    @Test
    fun `known armed statuses may unload but unknown loaded flags never authorize motor changes`() =
        runTest {
            for (fitness in listOf(0x26, 0x27, 0x105, 0x204)) {
                val rig = Rig(this)
                rig.link().values[VoltraProtocol.FITNESS] = fitness
                rig.ready()
                if (fitness in listOf(0x26, 0x27)) {
                    assertTrue(rig.trainer().canUnload)
                    rig.controller.setLoaded("left", false)
                    assertTrue(rig.trainer().unloaded)
                    assertEquals(
                        1,
                        rig.link().frames.count { it.contentEquals(VoltraProtocol.motorUnload()) },
                    )
                } else {
                    assertFalse(rig.trainer().canLoad)
                    assertFalse(rig.trainer().canUnload)
                    assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
                    assertTrue(runCatching { rig.controller.setLoaded("left", false) }.isFailure)
                    assertFalse(
                        rig.link().frames.any {
                            it.contentEquals(VoltraProtocol.motorLoad()) ||
                                it.contentEquals(VoltraProtocol.motorUnload())
                        }
                    )
                }
                rig.controller.close()
            }
        }

    @Test
    fun `caller cancellation or endpoint change during setup cannot send GO`() = runTest {
        for (changeEndpoint in listOf(false, true)) {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
            rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
            rig.ready()
            if (changeEndpoint) {
                rig.link().onSetup = {
                    rig.link().send(endpointReport(LEFT_ADDRESS, RIGHT_ADDRESS))
                }
                assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
            } else {
                rig.link().onSetup = { rig.link().respond = false }
                val load = async { rig.controller.setLoaded("left", true) }
                runCurrent()
                assertTrue(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorSetup()) })
                load.cancelAndJoin()
                rig.link().respond = true
                rig.link().send(report(VoltraProtocol.CABLE_POSITION, 0, 2))
                rig.link().send(report(VoltraProtocol.CABLE_OFFSET, 0, 2))
                advanceTimeBy(1200)
                runCurrent()
                assertTrue(load.isCancelled)
            }
            assertFalse(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorLoad()) })
            assertFalse(rig.trainer().pending)
            rig.controller.close()
        }
    }

    @Test
    fun `missing or negative saved offset cannot authorize load`() = runTest {
        for (offset in listOf<Int?>(null, -1, 0xffff)) {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
            offset?.let { rig.link().values[VoltraProtocol.CABLE_OFFSET] = it }
            rig.ready()
            assertFalse(rig.trainer().canLoad)
            assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
            assertFalse(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorLoad()) })
            rig.controller.close()
        }
    }

    @Test
    fun `direct motor load verifies setup then confirms a single GO and STOP`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
        rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
        rig.ready()
        assertTrue(rig.trainer().canLoad)
        rig.controller.setLoaded("left", true)
        assertTrue(rig.trainer().loaded)
        val loads = rig.link().frames.count { it.contentEquals(VoltraProtocol.motorLoad()) }
        assertEquals(1, loads)
        assertEquals(1, rig.link().frames.count { it.contentEquals(VoltraProtocol.motorSetup()) })
        rig.controller.setLoaded("left", true)
        assertEquals(
            loads,
            rig.link().frames.count { it.contentEquals(VoltraProtocol.motorLoad()) },
        )
        rig.controller.setLoaded("left", false)
        assertTrue(rig.trainer().unloaded)
        assertEquals(1, rig.link().frames.count { it.contentEquals(VoltraProtocol.motorUnload()) })
        rig.controller.close()
    }

    @Test
    fun `load preparation refreshes missing or expired cable evidence before one GO`() = runTest {
        for (expired in listOf(false, true)) {
            val rig = Rig(this)
            if (expired) {
                rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
                rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
            }
            rig.ready()
            if (expired) {
                rig.link().values.remove(VoltraProtocol.CABLE_POSITION)
                rig.link().values.remove(VoltraProtocol.CABLE_OFFSET)
                advanceTimeBy(1800)
                runCurrent()
            }
            assertFalse(rig.trainer().canLoad)
            assertTrue(rig.trainer().canPrepareLoad)
            rig.link().onSetup = {
                rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
                rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
            }
            rig.controller.setLoaded("left", true)
            assertTrue(rig.trainer().loaded)
            assertEquals(1, rig.link().frames.count { it.contentEquals(VoltraProtocol.motorSetup()) })
            assertEquals(1, rig.link().frames.count { it.contentEquals(VoltraProtocol.motorLoad()) })
            rig.controller.close()
        }
    }

    @Test
    fun `captured two centimeter SETUP blocks Single and Twin without force or calibration writes`() = runTest {
        for (twin in listOf(false, true)) {
            val rig = Rig(this)
            if (twin) rig.link().peerAddress = RIGHT_ADDRESS
            rig.ready()
            // Real VTR-161230 query and SETUP replies, 2026-10-17. No generated
            // cable replies are configured in the transport for this case.
            rig.link().send(VoltraProtocol.hex(
                "551b08d610aa002020000f000300823e02006a5000001150008a5b"
            ))
            runCurrent()
            assertEquals(twin, rig.controller.state.value.twin != null)
            assertTrue(rig.trainer().canPrepareLoad)
            assertFalse(rig.trainer().canLoad)
            rig.link().onSetup = {
                rig.link().send(VoltraProtocol.hex(
                    "5518088310aa150020000f0002006a500000823e02000946"
                ))
            }
            val before = rig.link().frames.size
            val failure = runCatching { rig.controller.setLoaded("left", true) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("zero position"))
            assertTrue(rig.trainer().unloaded)
            assertFalse(rig.trainer().pending)
            advanceTimeBy(5_000)
            runCurrent()
            val attempted = rig.link().frames.drop(before)
            assertEquals(1, attempted.count { it.contentEquals(VoltraProtocol.motorSetup()) })
            // Status polls are allowed; motor/offset writes and vendor guided
            // load triggers are not fallback actions after an explicit block.
            assertTrue(attempted.all { (it[10].toInt() and 255) in listOf(0x0f, VoltraProtocol.ENDPOINT_STATUS) })
            assertFalse(rig.trainer().loaded)
            rig.controller.close()
        }
    }

    @Test
    fun `nonzero missing malformed and stale cable readings cannot authorize direct loading`() =
        runTest {
            for (position in listOf<Int?>(null, 2, 100, -1, 32767)) {
                val rig = Rig(this)
                position?.let { rig.link().values[VoltraProtocol.CABLE_POSITION] = it }
                rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
                rig.ready()
                assertFalse(rig.trainer().canLoad)
                assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
                assertFalse(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorLoad()) })
                rig.controller.close()
            }
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
            rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
            rig.ready()
            rig.link().values.remove(VoltraProtocol.CABLE_POSITION)
            advanceTimeBy(1800)
            runCurrent()
            assertTrue(rig.trainer().fresh)
            assertFalse(rig.trainer().canLoad)
            assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
            assertFalse(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorLoad()) })
            rig.controller.close()
        }

    @Test
    fun `cable moving during setup prevents GO and missing confirmation never resends`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
        rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
        rig.ready()
        rig.link().onSetup = { rig.link().values[VoltraProtocol.CABLE_POSITION] = 10 }
        assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
        assertFalse(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorLoad()) })
        rig.link().onSetup = {}
        rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
        advanceTimeBy(1000)
        runCurrent()
        rig.link().acceptSettings = false
        assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
        assertEquals(1, rig.link().frames.count { it.contentEquals(VoltraProtocol.motorLoad()) })
        assertFalse(rig.trainer().loaded)
        rig.controller.close()
    }

    @Test
    fun `mode change during cable setup invalidates motor admission`() = runTest {
        val rig = Rig(this)
        rig.link().values[VoltraProtocol.CABLE_POSITION] = 0
        rig.link().values[VoltraProtocol.CABLE_OFFSET] = 0
        rig.ready()
        rig.link().onSetup = { rig.link().send(report(VoltraProtocol.MODE, 2, 1)) }
        assertTrue(runCatching { rig.controller.setLoaded("left", true) }.isFailure)
        assertFalse(rig.link().frames.any { it.contentEquals(VoltraProtocol.motorLoad()) })
        rig.controller.close()
    }

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
            for (unknown in listOf(2, 6, 65535)) {
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
        for (fitness in listOf(0, 4, 0x0104)) {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.MODE] = 0
            rig.link().values[VoltraProtocol.FITNESS] = fitness
            rig.ready(2)
            assertNull(rig.trainer().mode)
            assertEquals(0, rig.trainer().modeWireValue)
            assertEquals(fitness, rig.trainer().fitnessMode)
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
            assertTrue(rig.trainer().modeConfirmed)
            assertEquals(fitness, rig.trainer().fitnessMode)
            if (fitness == 0) {
                assertFalse(rig.trainer().canLoad)
                assertFalse(rig.trainer().canPrepareLoad)
                assertFalse(rig.trainer().loaded)
            }
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
        for (fitness in listOf(0, 4, 0x0104)) {
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
        for (fitness in listOf(5, 21, 0x26, 0x27, 0x0204, 0xff04, 0x0105, 0x0126)) {
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
    fun `flagged ready transition clears loaded telemetry and permits a confirmed mode change`() =
        runTest {
            val rig = Rig(this)
            rig.link().values[VoltraProtocol.FITNESS] = 5
            rig.ready()
            rig.link().send(terseForce(210, targetTenths = 300, velocityMmPerSecond = 100))
            runCurrent()
            assertEquals(true, rig.trainer().cableMoving)
            rig.link().values[VoltraProtocol.FITNESS] = 0x0104
            rig.link().send(report(VoltraProtocol.FITNESS, 0x0104, 2))
            runCurrent()
            assertEquals(0x0104, rig.trainer().fitnessMode)
            assertTrue(rig.trainer().unloaded)
            assertTrue(rig.trainer().canChangeMode)
            assertTrue(rig.trainer().canEdit(Parameter.WEIGHT))
            assertEquals(0.0, rig.trainer().activeWeightLb!!, 0.001)
            assertEquals(ActiveWeightSource.UNLOADED, rig.trainer().activeWeightSource)
            assertNull(rig.trainer().resistanceTargetLb)
            assertNull(rig.trainer().measuredForceLb)
            assertNull(rig.trainer().cableMoving)
            val selection = async { rig.controller.setMode("left", TrainingMode.BAND) }
            advanceTimeBy(250)
            runCurrent()
            selection.await()
            assertEquals(TrainingMode.BAND, rig.trainer().mode)
            assertEquals(0x0104, rig.trainer().fitnessMode)
            assertFalse(rig.trainer().pending)
            rig.link().values[VoltraProtocol.FITNESS] = 0x0204
            rig.link().send(report(VoltraProtocol.FITNESS, 0x0204, 2))
            runCurrent()
            assertFalse(rig.trainer().unloaded)
            assertNull(rig.trainer().activeWeightLb)
            val priorModeWrites =
                rig.link().writes.count { it.command == 0x11 && it.register == VoltraProtocol.MODE }
            assertTrue(
                runCatching { rig.controller.setMode("left", TrainingMode.WEIGHT) }.isFailure
            )
            assertEquals(
                priorModeWrites,
                rig.link().writes.count { it.command == 0x11 && it.register == VoltraProtocol.MODE },
            )
            rig.controller.close()
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
    val frames = mutableListOf<ByteArray>()
    var onSetup: () -> Unit = {}
    var respond = true
    var acceptSettings = true
    var closed = false

    override suspend fun write(frame: ByteArray) {
        check(!closed)
        frames += frame.copyOf()
        if (frame.contentEquals(VoltraProtocol.motorSetup())) onSetup()
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
        if (command == 0x0f && respond) {
            val registers =
                (0 until (frame[11].toInt() and 255)).map {
                    VoltraProtocol.unsigned(frame, 13 + it * 2, 2)
                }
            registers.forEach { register ->
                values[register]?.let { value ->
                    val width =
                        Parameter.entries.find { it.register == register }?.width
                            ?: if (
                                register in
                                    listOf(
                                        VoltraProtocol.MAX_ALLOWED_FORCE,
                                        VoltraProtocol.FITNESS,
                                        VoltraProtocol.RUNTIME_WIRE_WEIGHT,
                                        VoltraProtocol.CABLE_POSITION,
                                        VoltraProtocol.CABLE_OFFSET,
                                    )
                            )
                                2
                            else 1
                    send(report(register, value, width))
                }
            }
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
