package io.github.albertzhang86.voltra

import io.github.albertzhang86.voltra.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Two independent connections with per-device command confirmation and freshness. Call lifecycle
 * methods on the owning dispatcher. No command is replayed after a disconnect.
 */
class DirectVoltraController(
    private val transport: VoltraTransport,
    parentScope: CoroutineScope,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val clientName: String = "VOLTRA Client",
) : VoltraController {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val mutableState = MutableStateFlow(VoltraState())
    override val state = mutableState.asStateFlow()
    private val entries = mutableMapOf<String, Entry>()
    private var closed = false

    private class Entry(val device: VoltraDevice) {
        var link: VoltraLink? = null
        var job: Job? = null
        val commands = Mutex()
        val revisions = mutableMapOf<Int, Long>()
        val parameterAt = mutableMapOf<Parameter, Long>()
        var statusAt: Long? = null
        var modeAt: Long? = null
        var twinAt: Long? = null
        var forceAt: Long? = null
        var deviceSet: Int? = null
        var lastRep = 0
        var repBaseline = 0
        var waitingForFirstRep = false
    }

    override fun scan(): Flow<List<VoltraDevice>> = flow {
        check(!closed) { "Controller is closed." }
        emitAll(transport.scan())
    }

    override suspend fun connect(device: VoltraDevice) =
        withContext(scope.coroutineContext) {
            check(!closed)
            check(entries[device.id] == null) { "This trainer is already connected." }
            check(entries.size < 2) { "Disconnect a trainer before adding another." }
            val entry = Entry(device)
            entries[device.id] = entry
            mutableState.update {
                it.copy(
                    trainers =
                        it.trainers.filterNot { old -> old.device.id == device.id } +
                            TrainerState(device)
                )
            }
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
                                    it.copy(
                                        fresh =
                                            listOf(entry.statusAt, entry.modeAt).all { at ->
                                                at != null && now - at <= 3500
                                            },
                                        twinStatusKnown =
                                            entry.twinAt?.let { now - it <= 3500 } == true,
                                        activeWeightLb =
                                            it.activeWeightLb.takeIf {
                                                entry.forceAt?.let { at -> now - at <= 1500 } ==
                                                    true
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
                                if (entry.statusAt?.let { now - it > 10_000 } == true)
                                    error("VOLTRA stopped reporting status. Reconnect to continue.")
                                delay(250)
                            }
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        fail(
                            entry,
                            Exception(
                                "VOLTRA connection timed out. Accept the connection on the trainer and retry."
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        fail(entry, failure)
                    } finally {
                        entry.link?.close()
                        if (entries[device.id] === entry) entries.remove(device.id)
                    }
                }
        }

    private suspend fun poll(entry: Entry) {
        val link = requireNotNull(entry.link)
        link.write(VoltraProtocol.read(VoltraProtocol.MODE))
        link.write(VoltraProtocol.read(VoltraProtocol.FITNESS))
        // Single-register reads avoid assuming different firmware versions share parameter widths.
        link.write(VoltraProtocol.read(VoltraProtocol.BATTERY))
        link.write(VoltraProtocol.read(VoltraProtocol.TWIN_STATUS))
        Parameter.forMode(current(entry)?.mode).forEach {
            link.write(VoltraProtocol.read(it.register))
        }
    }

    private fun update(entry: Entry, update: ProtocolUpdate) {
        if (entries[entry.device.id] !== entry) return
        val now = monotonicMillis()
        update.registers.keys.forEach { entry.revisions[it] = (entry.revisions[it] ?: 0) + 1 }
        if (VoltraProtocol.FITNESS in update.registers) entry.statusAt = now
        if (VoltraProtocol.MODE in update.registers) entry.modeAt = now
        if (VoltraProtocol.TWIN_STATUS in update.registers) entry.twinAt = now
        if (update.activeWeightLb != null) entry.forceAt = now
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
            val parameters = old.parameters.toMutableMap()
            Parameter.entries.forEach { parameter ->
                update.registers[parameter.register]?.let { value ->
                    if (runCatching { parameter.validate(value) }.isSuccess) {
                        parameters[parameter] = value
                        entry.parameterAt[parameter] = now
                    }
                }
            }
            val twinReported =
                update.registers[VoltraProtocol.TWIN_STATUS]?.let { it == 2 } ?: old.twinReported
            old.copy(
                parameters = parameters,
                mode =
                    if (VoltraProtocol.MODE in update.registers)
                        TrainingMode.fromWire(update.registers.getValue(VoltraProtocol.MODE))
                    else old.mode,
                fitnessMode = update.registers[VoltraProtocol.FITNESS] ?: old.fitnessMode,
                batteryPercent =
                    update.registers[VoltraProtocol.BATTERY]?.takeIf { it in 0..100 }
                        ?: old.batteryPercent,
                twinReported = twinReported,
                twinStatusKnown = entry.twinAt?.let { now - it <= 3500 } == true,
                activeWeightLb =
                    if (twinReported) null else update.activeWeightLb ?: old.activeWeightLb,
                reps =
                    if (twinReported) null
                    else if (repAccepted) (entry.lastRep - entry.repBaseline).coerceAtLeast(0)
                    else old.reps,
                fresh = listOf(entry.statusAt, entry.modeAt).all { it != null && now - it <= 3500 },
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
            entry.commands.withLock { block(entry) }
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

    // Native Twin uses a separate protocol/telemetry schema. Do not emulate it with two writes.
    override suspend fun createTwin(controllerId: String, peerId: String): Unit =
        error("Native Twin pairing is not available: verified pairing commands are required.")

    override suspend fun exitTwin(): Unit =
        error("Native Twin unpairing is not available: verified unpairing commands are required.")

    override fun beginSet() {
        entries.values.forEach { entry ->
            entry.repBaseline = entry.lastRep
            entry.waitingForFirstRep = entry.deviceSet == null
            change(entry) { it.copy(reps = if (it.reps == null) null else 0) }
        }
    }

    override fun disconnect(deviceId: String) {
        val entry = entries.remove(deviceId)
        entry?.job?.cancel()
        entry?.link?.close()
        mutableState.update { old ->
            old.copy(trainers = old.trainers.filterNot { it.device.id == deviceId })
        }
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
        mutableState.update {
            it.copy(
                trainers =
                    it.trainers.map { old ->
                        if (old.device.id == entry.device.id) transform(old) else old
                    }
            )
        }
    }

    private fun fail(entry: Entry, error: Exception) =
        change(entry) {
            it.copy(
                status = ConnectionStatus.FAILED,
                fresh = false,
                activeWeightLb = null,
                reps = null,
                pending = false,
                error = error.message ?: "VOLTRA connection failed.",
            )
        }
}
