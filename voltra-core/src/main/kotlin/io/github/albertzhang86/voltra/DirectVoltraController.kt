package io.github.albertzhang86.voltra

import io.github.albertzhang86.voltra.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Up to two independent connections, or one native Twin controller session. Commands require device
 * confirmation and fresh state. Lifecycle methods use the owning dispatcher; a disconnect never
 * replays a command.
 */
class DirectVoltraController(
    private val transport: VoltraTransport,
    parentScope: CoroutineScope,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clientName: String = "VOLTRA Client",
) : VoltraController {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val mutableState = MutableStateFlow(VoltraState(twinSupported = true))
    override val state = mutableState.asStateFlow()
    private val entries = mutableMapOf<String, Entry>()
    private var closed = false
    private val twinCommands = Mutex()
    private val knownDevices = mutableMapOf<String, VoltraDevice>()
    private var nativePair: NativePair? = null
    private var twinPending = false
    private var twinOperation: Job? = null
    private var twinOperationIds = emptySet<String>()
    private var connectionGeneration = 0
    private var sequence = 0

    private data class NativePair(val controllerId: String, val peer: VoltraDevice)

    private class Entry(val device: VoltraDevice) {
        var snapshot = TrainerState(device)
        var endpoint: EndpointStatus? = null
        var endpointAt: Long? = null
        var expectedTwinDisconnect = false
        var twinReply: CompletableDeferred<TwinCommandReply>? = null
        var twinSequence = -1
        var link: VoltraLink? = null
        var job: Job? = null
        val commands = Mutex()
        val commandJobs = mutableSetOf<Job>()
        val revisions = mutableMapOf<Int, Long>()
        val parameterAt = mutableMapOf<Parameter, Long>()
        var statusAt: Long? = null
        var modeAt: Long? = null
        var forceAt: Long? = null
        var forceWeightLb: Double? = null
        var cableVelocityMmPerSecond: Int? = null
        var runtimeWeightAt: Long? = null
        var runtimeWeightLb: Double? = null
        var targetAt: Long? = null
        var targetWeightLb: Double? = null
        var deviceSet: Int? = null
        var lastRep = 0
        var repBaseline = 0
        var waitingForFirstRep = false
    }

    override fun scan(): Flow<List<VoltraDevice>> = flow {
        check(!closed) { "Controller is closed." }
        emitAll(transport.scan().onEach { devices -> devices.forEach { knownDevices[it.id] = it } })
    }

    override suspend fun connect(device: VoltraDevice) =
        withContext(scope.coroutineContext) {
            check(!closed)
            check(entries[device.id] == null) { "This trainer is already connected." }
            check(state.value.twin == null) { "Exit Twin mode before connecting another trainer." }
            check(entries.size < 2) { "Disconnect a trainer before adding another." }
            knownDevices[device.id] = device
            val entry = Entry(device)
            entries[device.id] = entry
            publish()
            entry.job =
                scope.launch {
                    try {
                        entry.link = withTimeout(15_000) { transport.connect(device) }
                        coroutineScope {
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                val assembler = FrameAssembler()
                                requireNotNull(entry.link).packets.collect { packet ->
                                    assembler.accept(packet).forEach { frame ->
                                        VoltraDecoder.decode(frame)?.let { update(entry, it) }
                                    }
                                }
                                error("VOLTRA disconnected. Reconnect to continue.")
                            }
                            withTimeout(30_000) {
                                val link = requireNotNull(entry.link)
                                link.write(VoltraProtocol.identity(clientName))
                                delay(
                                    3_000
                                ) // Allow the trainer's first-connection acceptance prompt.
                                VoltraProtocol.initialFrames.forEach {
                                    link.write(it)
                                    delay(25)
                                }
                                link.write(VoltraProtocol.subscribe())
                                while (
                                    current(entry)?.fitnessMode == null ||
                                        VoltraProtocol.MODE !in entry.revisions
                                ) {
                                    poll(entry)
                                    delay(500)
                                }
                            }
                            change(entry) { it.copy(status = ConnectionStatus.READY, error = null) }
                            var lastPoll = Long.MIN_VALUE / 2
                            while (isActive) {
                                val now = monotonicMillis()
                                change(entry) {
                                    val weight = activeReading(entry, now)
                                    val fresh =
                                        listOf(entry.statusAt, entry.modeAt).all { at ->
                                            at != null && now - at <= 3500
                                        }
                                    it.copy(
                                        fresh = fresh,
                                        deviceReps = it.deviceReps.takeIf { fresh },
                                        twinStatusKnown =
                                            endpointFresh(entry) &&
                                                entry.endpoint?.topologyKnown == true,
                                        activeWeightLb = weight?.pounds,
                                        activeWeightSource = weight?.source,
                                        measuredForceLb = freshForce(entry, now),
                                        cableMoving = freshCableMoving(entry, now),
                                        runtimeWireWeightLb = freshRuntimeWeight(entry, now),
                                        resistanceTargetLb =
                                            weight?.pounds.takeIf {
                                                weight?.source == ActiveWeightSource.TARGET_FORCE
                                            },
                                        parameters =
                                            it.parameters.filterKeys { parameter ->
                                                entry.parameterAt[parameter]?.let {
                                                    now - it <= 3500
                                                } == true
                                            },
                                    )
                                }
                                if (now - lastPoll >= 1_000) {
                                    poll(entry)
                                    lastPoll = now
                                }
                                // Keep coarse wire tension as separate telemetry; it is never
                                // a fallback for resistance. Do not poll native Twin endpoints.
                                if (telemetryAvailable(entry, monotonicMillis())) {
                                    requireNotNull(entry.link)
                                        .write(
                                            VoltraProtocol.read(VoltraProtocol.RUNTIME_WIRE_WEIGHT)
                                        )
                                }
                                if (entry.statusAt?.let { now - it > 10_000 } == true)
                                    error("VOLTRA stopped reporting status. Reconnect to continue.")
                                delay(250)
                            }
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        fail(
                            entry,
                            Exception(
                                "VOLTRA connection timed out. If connected to Cortex, Beyond+ or a knob, disconnect it there, then retry."
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        fail(entry, failure)
                    } finally {
                        entry.link?.close()
                        if (entry.expectedTwinDisconnect && entries[device.id] === entry) {
                            entries.remove(device.id)
                            publish()
                        }
                    }
                }
        }

    private suspend fun poll(entry: Entry) {
        val link = requireNotNull(entry.link)
        link.write(VoltraProtocol.read(VoltraProtocol.MODE))
        link.write(VoltraProtocol.read(VoltraProtocol.FITNESS))
        // Single-register reads avoid assuming different firmware versions share parameter widths.
        link.write(VoltraProtocol.read(VoltraProtocol.BATTERY))
        link.write(VoltraProtocol.read(VoltraProtocol.WEIGHT_UNIT))
        link.write(VoltraProtocol.endpointStatus())
        Parameter.forMode(current(entry)?.mode).forEach {
            link.write(VoltraProtocol.read(it.register))
        }
    }

    private fun update(entry: Entry, update: ProtocolUpdate) {
        if (entries[entry.device.id] !== entry) return
        val now = monotonicMillis()
        val nextMode =
            if (VoltraProtocol.MODE in update.registers) {
                TrainingMode.fromWire(update.registers.getValue(VoltraProtocol.MODE))
            } else entry.snapshot.mode
        val nextFitness = update.registers[VoltraProtocol.FITNESS] ?: entry.snapshot.fitnessMode
        val modeChanged = VoltraProtocol.MODE in update.registers && nextMode != entry.snapshot.mode
        if (
            modeChanged ||
                (VoltraProtocol.FITNESS in update.registers &&
                    update.registers[VoltraProtocol.FITNESS] != entry.snapshot.fitnessMode)
        ) {
            clearActiveWeight(entry)
        }
        update.registers.keys.forEach { entry.revisions[it] = (entry.revisions[it] ?: 0) + 1 }
        if (VoltraProtocol.FITNESS in update.registers) entry.statusAt = now
        if (VoltraProtocol.MODE in update.registers) entry.modeAt = now
        update.twinReply
            ?.takeIf { it.sequence == entry.twinSequence }
            ?.let { entry.twinReply?.complete(it) }
        update.endpoint?.let { endpoint ->
            // Android IDs are MAC addresses. Opaque transport IDs remain supported for other hosts.
            val addressId = entry.device.id.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"))
            if (addressId && !entry.device.id.equals(endpoint.localAddress, ignoreCase = true))
                return
            knownDevices[endpoint.localAddress] = entry.device
            val previous = entry.endpoint
            if (
                !endpointFresh(entry) ||
                    previous?.localAddress != endpoint.localAddress ||
                    previous.peerAddress != endpoint.peerAddress ||
                    previous.pairingStatus != endpoint.pairingStatus
            ) {
                clearActiveWeight(entry)
            }
            entry.endpoint = endpoint
            entry.endpointAt = now
            if (endpoint.paired) {
                val peer =
                    entries.values
                        .firstOrNull { it.endpoint?.localAddress == endpoint.peerAddress }
                        ?.device
                        ?: knownDevices[endpoint.peerAddress]
                        ?: VoltraDevice(endpoint.peerAddress, "VOLTRA peer")
                nativePair = NativePair(entry.device.id, peer)
            } else if (endpoint.independent && nativePair?.controllerId == entry.device.id) {
                nativePair = null
            }
        }
        if (!telemetryAvailable(entry, now, nextMode)) {
            clearActiveWeight(entry)
        } else if (update.cableForceLb != null) {
            // Force and motion must come from one notification. A legacy force-only sample
            // clears motion, so it cannot borrow a previous B4 velocity.
            entry.forceWeightLb = update.cableForceLb?.takeIf { it in 0.0..1000.0 }
            entry.forceAt = now.takeIf { entry.forceWeightLb != null }
            entry.cableVelocityMmPerSecond =
                update.cableVelocityMmPerSecond.takeIf { entry.forceWeightLb != null }
        }
        if (
            telemetryAvailable(entry, now, nextMode) &&
                VoltraProtocol.RUNTIME_WIRE_WEIGHT in update.registers
        ) {
            // Negative signed values and out-of-range data are unavailable, never a huge
            // unsigned weight. Invalid readback also clears any previous runtime value.
            entry.runtimeWeightLb = update.runtimeWeightLb?.takeIf { it in 0.0..1000.0 }
            entry.runtimeWeightAt = now.takeIf { entry.runtimeWeightLb != null }
        }
        if (update.resistanceTargetReported) {
            entry.targetWeightLb =
                update.resistanceTargetLb?.takeIf {
                    it in 0.0..1000.0 &&
                        telemetryAvailable(entry, now, nextMode) &&
                        nextFitness in listOf(5, 21)
                }
            entry.targetAt = now.takeIf { entry.targetWeightLb != null }
        }
        var repAccepted = false
        if (update.deviceSet != null && update.rep != null) {
            val previousSet = entry.deviceSet
            val forwardDistance = previousSet?.let { (update.deviceSet - it) and 0xffff } ?: 0
            // Ignore a delayed event from an older device set, including around uint16 rollover.
            if (previousSet == null || forwardDistance < 0x8000) {
                if (previousSet != update.deviceSet) {
                    entry.deviceSet = update.deviceSet
                    entry.repBaseline = 0
                    entry.lastRep = 0
                }
                if (entry.waitingForFirstRep) {
                    entry.repBaseline = (update.rep - 1).coerceAtLeast(0)
                    entry.waitingForFirstRep = false
                }
                entry.lastRep = maxOf(entry.lastRep, update.rep)
                repAccepted = true
            }
        }
        change(entry) { old ->
            // A newly confirmed mode needs fresh parameter readback. A rapid return to a
            // previous mode must not reuse its still-young cache and skip required setup.
            if (modeChanged) entry.parameterAt.clear()
            val parameters = if (modeChanged) mutableMapOf() else old.parameters.toMutableMap()
            Parameter.entries.forEach { parameter ->
                update.registers[parameter.register]?.let { value ->
                    if (runCatching { parameter.validate(value) }.isSuccess) {
                        parameters[parameter] = value
                        entry.parameterAt[parameter] = now
                    }
                }
            }
            val twinReported =
                entry.endpoint?.let { !it.independent }
                    ?: (update.registers[VoltraProtocol.TWIN_STATUS]?.let { it == 2 }
                        ?: old.twinReported)
            val weight = activeReading(entry, now, twinReported, nextMode, nextFitness)
            val fresh = listOf(entry.statusAt, entry.modeAt).all { it != null && now - it <= 3500 }
            old.copy(
                parameters = parameters,
                mode = nextMode,
                fitnessMode = update.registers[VoltraProtocol.FITNESS] ?: old.fitnessMode,
                weightUnit =
                    if (VoltraProtocol.WEIGHT_UNIT in update.registers)
                        WeightUnit.fromWire(update.registers.getValue(VoltraProtocol.WEIGHT_UNIT))
                    else old.weightUnit,
                batteryPercent =
                    update.endpoint?.localBatteryPercent
                        ?: update.registers[VoltraProtocol.BATTERY]?.takeIf { it in 0..100 }
                        ?: old.batteryPercent,
                twinReported = twinReported,
                twinStatusKnown = endpointFresh(entry) && entry.endpoint?.topologyKnown == true,
                activeWeightLb = weight?.pounds,
                activeWeightSource = weight?.source,
                measuredForceLb = freshForce(entry, now, nextMode),
                cableMoving = freshCableMoving(entry, now, nextMode, nextFitness),
                runtimeWireWeightLb = freshRuntimeWeight(entry, now, nextMode),
                resistanceTargetLb =
                    weight?.pounds.takeIf { weight?.source == ActiveWeightSource.TARGET_FORCE },
                reps =
                    if (twinReported) null
                    else if (repAccepted) (entry.lastRep - entry.repBaseline).coerceAtLeast(0)
                    else old.reps,
                deviceReps =
                    if (twinReported || !fresh) null
                    else if (repAccepted) entry.lastRep else old.deviceReps,
                fresh = fresh,
            )
        }
    }

    override suspend fun setMode(deviceId: String, mode: TrainingMode) =
        command(deviceId) { entry ->
            check(requireNotNull(current(entry)).canChangeMode) {
                "Unload VOLTRA before changing mode; a fresh independent connection is required."
            }
            confirmedWrite(entry, VoltraProtocol.MODE, mode.wireValue, 2) { it.mode == mode }
        }

    override suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int) =
        command(deviceId) { entry ->
            parameter.validate(value)
            val snapshot = requireNotNull(current(entry))
            check(snapshot.canEdit(parameter)) {
                "This setting is unavailable in the trainer's current state."
            }
            if (parameter in listOf(Parameter.WEIGHT, Parameter.ECCENTRIC)) {
                val weight =
                    if (parameter == Parameter.WEIGHT) value
                    else snapshot.parameters[Parameter.WEIGHT]
                val eccentric =
                    if (parameter == Parameter.ECCENTRIC) value
                    else snapshot.parameters[Parameter.ECCENTRIC]
                require(weight != null && eccentric != null && weight + eccentric in 5..200) {
                    "Combined weight must stay within 5–200 lb."
                }
            }
            confirmedWrite(entry, parameter.register, value, parameter.width) {
                it.parameters[parameter] == value
            }
        }

    private suspend fun command(deviceId: String, block: suspend (Entry) -> Unit) =
        withContext(scope.coroutineContext) {
            val entry = requireNotNull(entries[deviceId]) { "Connect VOLTRA first." }
            val operation = currentCoroutineContext().job
            entry.commandJobs += operation
            try {
                entry.commands.withLock {
                    check(entries[deviceId] === entry) { "VOLTRA disconnected." }
                    check(!twinPending) { "Wait for the Twin change to finish." }
                    val pair = state.value.twin
                    check(
                        pair == null ||
                            (pair.controllerId == deviceId && pair.health == TwinHealth.READY)
                    ) {
                        "A fresh Twin controller connection is required."
                    }
                    block(entry)
                }
            } finally {
                entry.commandJobs -= operation
            }
        }

    private suspend fun confirmedWrite(
        entry: Entry,
        register: Int,
        value: Int,
        width: Int,
        matches: (TrainerState) -> Boolean,
    ) {
        val version = entry.revisions[register] ?: 0
        change(entry) { it.copy(pending = true, error = null) }
        try {
            withTimeout(4_000) {
                requireNotNull(entry.link).write(VoltraProtocol.write(register, value, width))
                while (true) {
                    check(entries[entry.device.id] === entry) { "VOLTRA disconnected." }
                    val reported = requireNotNull(current(entry))
                    if ((entry.revisions[register] ?: 0) > version && matches(reported)) break
                    requireNotNull(entry.link).write(VoltraProtocol.read(register))
                    delay(200)
                }
            }
        } catch (failure: TimeoutCancellationException) {
            error("VOLTRA did not confirm the change. Check the trainer before retrying.")
        } finally {
            change(entry) { it.copy(pending = false) }
        }
    }

    override suspend fun createTwin(controllerId: String, peerId: String): Unit =
        withContext(scope.coroutineContext) {
            val generation = connectionGeneration
            twinCommands.withLock {
                check(generation == connectionGeneration) { "Device connections changed." }
                check(state.value.canCreateTwin) { "Connect two idle, independent trainers first." }
                require(controllerId != peerId)
                val controller = requireNotNull(entries[controllerId])
                val peer = requireNotNull(entries[peerId])
                val controllerAddress = requireNotNull(controller.endpoint).localAddress
                val peerAddress = requireNotNull(peer.endpoint).localAddress
                require(controllerAddress != peerAddress)
                val operation = currentCoroutineContext().job
                twinOperation = operation
                twinOperationIds = setOf(controllerId, peerId)
                twinPending = true
                entries.values.forEach(::clearActiveWeight)
                publish()
                try {
                    // Pairing transfers the peer's connection to the controller. Its BLE
                    // disconnect is expected; the controller must subsequently identify it.
                    peer.expectedTwinDisconnect = true
                    twinRequest(peer, 1, controllerAddress)
                    withTimeout(15_000) {
                        state.first {
                            val pair = it.twin
                            pair?.controllerId == controllerId &&
                                pair.peerId == peerId &&
                                pair.health == TwinHealth.READY &&
                                controller.endpoint?.peerAddress == peerAddress
                        }
                    }
                    entries.remove(peerId)?.let {
                        it.job?.cancel()
                        it.link?.close()
                    }
                } catch (failure: TimeoutCancellationException) {
                    error("Twin pairing was not confirmed. Check both trainers before retrying.")
                } finally {
                    peer.expectedTwinDisconnect = false
                    finishTwinOperation(operation)
                }
            }
        }

    override suspend fun exitTwin() =
        withContext(scope.coroutineContext) {
            val generation = connectionGeneration
            twinCommands.withLock {
                check(generation == connectionGeneration) { "Device connections changed." }
                check(state.value.canExitTwin) { "An idle, confirmed Twin connection is required." }
                val pair = requireNotNull(nativePair)
                val controller = requireNotNull(entries[pair.controllerId])
                val endpoint = requireNotNull(controller.endpoint)
                val previousEndpointAt = controller.endpointAt
                val operation = currentCoroutineContext().job
                twinOperation = operation
                twinOperationIds = setOf(pair.controllerId, pair.peer.id)
                twinPending = true
                entries.values.forEach(::clearActiveWeight)
                publish()
                try {
                    twinRequest(controller, 2, endpoint.peerAddress)
                    withTimeout(15_000) {
                        state.first {
                            it.twin == null &&
                                controller.endpoint?.independent == true &&
                                controller.endpointAt != previousEndpointAt
                        }
                    }
                    // Keep the operation cancellable until peer discovery/reconnect completes.
                    // A forgotten peer must not be automatically restored by this delayed scan.
                    if (entries[pair.peer.id] == null) {
                        val found =
                            withTimeoutOrNull(12_000) {
                                transport
                                    .scan()
                                    .map { devices ->
                                        devices.firstOrNull {
                                            it.id.equals(pair.peer.id, ignoreCase = true)
                                        }
                                    }
                                    .filterNotNull()
                                    .firstOrNull()
                            }
                        check(found != null) {
                            "Twin exited. Use Find VOLTRA to reconnect the second trainer."
                        }
                        connect(found)
                    }
                } catch (failure: TimeoutCancellationException) {
                    error("Twin exit was not confirmed. Check the trainers before retrying.")
                } finally {
                    finishTwinOperation(operation)
                }
            }
        }

    private suspend fun twinRequest(entry: Entry, operation: Int, address: String) {
        val requestSequence = (++sequence and 0xffff)
        val reply = CompletableDeferred<TwinCommandReply>()
        entry.twinSequence = requestSequence
        entry.twinReply = reply
        try {
            requireNotNull(entry.link)
                .write(VoltraProtocol.twinCommand(operation, address, requestSequence))
            val result = withTimeout(5_000) { reply.await() }
            check(result.operation == operation && result.result == 0) {
                "VOLTRA rejected the Twin change (${result.result})."
            }
        } finally {
            entry.twinReply = null
            entry.twinSequence = -1
        }
    }

    override fun beginSet() {
        entries.values.forEach { entry ->
            entry.repBaseline = entry.lastRep
            entry.waitingForFirstRep = entry.deviceSet == null
            change(entry) { it.copy(reps = if (it.reps == null) null else 0) }
        }
    }

    override fun disconnect(deviceId: String) {
        connectionGeneration++
        val pair = nativePair
        val ids =
            if (pair != null && deviceId in listOf(pair.controllerId, pair.peer.id)) {
                nativePair = null
                listOf(pair.controllerId, pair.peer.id)
            } else listOf(deviceId)
        if (ids.any { it in twinOperationIds }) {
            twinOperation?.cancel()
            twinOperation = null
            twinOperationIds = emptySet()
            twinPending = false
        }
        ids.forEach { id ->
            entries.remove(id)?.let {
                it.job?.cancel()
                it.commandJobs.toList().forEach { command -> command.cancel() }
                it.link?.close()
                it.twinReply?.cancel()
            }
        }
        publish()
    }

    override fun forget(deviceId: String) {
        val affected = twinOperationIds.takeIf { deviceId in it } ?: setOf(deviceId)
        affected.forEach(::disconnect)
        knownDevices.entries.removeAll { (address, device) ->
            address == deviceId || device.id == deviceId
        }
    }

    private fun finishTwinOperation(operation: Job) {
        if (twinOperation !== operation) return
        twinOperation = null
        twinOperationIds = emptySet()
        twinPending = false
        publish()
    }

    override fun close() {
        if (closed) return
        closed = true
        entries.keys.toList().forEach(::disconnect)
        ownerJob.cancel()
    }

    private fun current(entry: Entry) =
        state.value.trainers.find { it.device.id == entry.device.id }

    private fun change(entry: Entry, transform: (TrainerState) -> TrainerState) {
        if (entries[entry.device.id] !== entry) return
        entry.snapshot = transform(entry.snapshot)
        publish()
    }

    private fun endpointFresh(entry: Entry) =
        entry.endpointAt?.let { monotonicMillis() - it <= 3500 } == true

    private data class ActiveReading(val pounds: Double, val source: ActiveWeightSource)

    private fun activeReading(
        entry: Entry,
        now: Long,
        twinReported: Boolean = entry.snapshot.twinReported,
        mode: TrainingMode? = entry.snapshot.mode,
        fitnessMode: Int? = entry.snapshot.fitnessMode,
    ): ActiveReading? {
        if (twinReported || !telemetryAvailable(entry, now, mode)) return null
        if (fitnessMode == 4) return ActiveReading(0.0, ActiveWeightSource.UNLOADED)
        if (fitnessMode !in listOf(5, 21)) return null
        if (entry.targetAt?.let { now - it <= 1500 } == true)
            entry.targetWeightLb?.let {
                return ActiveReading(it, ActiveWeightSource.TARGET_FORCE)
            }
        return null
    }

    private fun freshForce(
        entry: Entry,
        now: Long,
        mode: TrainingMode? = entry.snapshot.mode,
    ): Double? =
        entry.forceWeightLb.takeIf {
            telemetryAvailable(entry, now, mode) && entry.forceAt?.let { now - it <= 1500 } == true
        }

    private fun freshCableMoving(
        entry: Entry,
        now: Long,
        mode: TrainingMode? = entry.snapshot.mode,
        fitnessMode: Int? = entry.snapshot.fitnessMode,
    ): Boolean? {
        if (fitnessMode !in listOf(5, 21) || !telemetryAvailable(entry, now, mode)) return null
        if (entry.forceWeightLb == null || entry.forceAt?.let { now - it <= 500 } != true)
            return null
        // A small deadband avoids treating stationary sensor noise as a pull. Both
        // directions count; position alone cannot distinguish a hold from movement.
        return entry.cableVelocityMmPerSecond?.let { kotlin.math.abs(it) >= 10 }
    }

    private fun freshRuntimeWeight(
        entry: Entry,
        now: Long,
        mode: TrainingMode? = entry.snapshot.mode,
    ): Double? =
        entry.runtimeWeightLb.takeIf {
            telemetryAvailable(entry, now, mode) &&
                entry.runtimeWeightAt?.let { now - it <= 1500 } == true
        }

    private fun telemetryAvailable(
        entry: Entry,
        now: Long,
        mode: TrainingMode? = entry.snapshot.mode,
    ): Boolean =
        entry.snapshot.connected &&
            mode != null &&
            !twinPending &&
            listOf(entry.statusAt, entry.modeAt, entry.endpointAt).all {
                it != null && now - it <= 3500
            } &&
            entry.endpoint?.independent == true

    private fun clearActiveWeight(entry: Entry) {
        entry.forceAt = null
        entry.forceWeightLb = null
        entry.cableVelocityMmPerSecond = null
        entry.runtimeWeightAt = null
        entry.runtimeWeightLb = null
        entry.targetAt = null
        entry.targetWeightLb = null
    }

    /** A native Twin has one BLE session; the peer is reported by that controller. */
    private fun publish() {
        val snapshots =
            entries.values
                .map {
                    if (twinPending)
                        it.snapshot.copy(
                            activeWeightLb = null,
                            activeWeightSource = null,
                            measuredForceLb = null,
                            runtimeWireWeightLb = null,
                            resistanceTargetLb = null,
                            cableMoving = null,
                            deviceReps = null,
                        )
                    else it.snapshot
                }
                .toMutableList()
        val pair = nativePair
        val controller = pair?.let { entries[it.controllerId] }
        val logicalPair =
            if (pair != null && controller != null) {
                val endpoint = controller.endpoint
                val ready =
                    controller.snapshot.connected &&
                        controller.snapshot.fresh &&
                        endpointFresh(controller) &&
                        endpoint?.paired == true &&
                        endpoint.peerAddress != EndpointStatus.ZERO_ADDRESS
                val primary =
                    controller.snapshot.copy(
                        twinReported = true,
                        twinStatusKnown = ready,
                        twinVerified = ready,
                        twinRole = TwinRole.CONTROLLER,
                        activeWeightLb = null,
                        activeWeightSource = null,
                        measuredForceLb = null,
                        cableMoving = null,
                        runtimeWireWeightLb = null,
                        resistanceTargetLb = null,
                        reps = null,
                        deviceReps = null,
                    )
                val peer =
                    TrainerState(
                        device = pair.peer,
                        status =
                            if (ready) ConnectionStatus.READY else ConnectionStatus.DISCONNECTED,
                        mode = primary.mode,
                        parameters = primary.parameters,
                        batteryPercent = endpoint?.peerBatteryPercent,
                        fitnessMode = primary.fitnessMode,
                        fresh = ready,
                        pending = primary.pending,
                        twinReported = true,
                        twinStatusKnown = ready,
                        twinRole = TwinRole.PEER,
                        twinVerified = ready,
                        modeChangeAllowed = false,
                        editableParameters = emptySet(),
                    )
                snapshots.removeAll { it.device.id in listOf(pair.controllerId, pair.peer.id) }
                snapshots.add(0, primary)
                snapshots.add(1, peer)
                TwinPair(
                    pair.controllerId,
                    pair.peer.id,
                    if (ready) TwinHealth.READY else TwinHealth.DEGRADED,
                )
            } else null
        mutableState.value =
            VoltraState(
                trainers = snapshots,
                twin = logicalPair,
                twinPending = twinPending,
                twinSupported = true,
            )
    }

    private fun fail(entry: Entry, error: Exception) {
        if (entry.expectedTwinDisconnect) return
        entry.twinReply?.completeExceptionally(error)
        change(entry) {
            it.copy(
                status = ConnectionStatus.FAILED,
                fresh = false,
                activeWeightLb = null,
                activeWeightSource = null,
                measuredForceLb = null,
                cableMoving = null,
                runtimeWireWeightLb = null,
                resistanceTargetLb = null,
                reps = null,
                deviceReps = null,
                pending = false,
                error = error.message ?: "VOLTRA connection failed.",
            )
        }
    }
}
