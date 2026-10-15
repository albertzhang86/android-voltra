package io.github.albertzhang86.voltra.knob

import io.github.albertzhang86.voltra.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One knob owns the trainer link. Neither discovery nor reconnect sends trainer commands. */
class KnobVoltraController(
    private val transport: VoltraTransport,
    parentScope: CoroutineScope,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    rememberedDevice: VoltraDevice? = null,
    private val onConnected: (VoltraDevice) -> Unit = {},
    private val onForgotten: (String) -> Unit = {},
) : VoltraController {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private var rememberedDevice = rememberedDevice
    private val mutableState = MutableStateFlow(disconnectedState())
    override val state = mutableState.asStateFlow()
    private val commands = Mutex()
    private val commandJobs = mutableSetOf<Job>()
    private var link: VoltraLink? = null
    private var connection: Job? = null
    private var snapshot: KnobProtocol.Snapshot? = null
    private val snapshots = MutableStateFlow<KnobProtocol.Snapshot?>(null)
    private var receivedAt = 0L
    private var nextId = 1
    private var baseline: Int? = null
    private var pending: Pending? = null
    private var generation = 0

    private data class Pending(
        val epoch: Int,
        val session: Int,
        val id: Int,
        val completion: CompletableDeferred<Unit>,
    )

    init {
        scope.launch {
            while (isActive) {
                delay(250)
                if (snapshot != null && now() - receivedAt > 2000) {
                    mutableState.update {
                        it.copy(
                            twin =
                                it.twin?.copy(
                                    health = TwinHealth.DEGRADED,
                                    activeWeightLb = null,
                                    reps = null,
                                ),
                            knob = it.knob?.copy(
                                twinCandidate = null,
                                twinExitAllowed = false,
                                usbPowerConnected = false,
                            ),
                            trainers =
                                it.trainers.map { trainer ->
                                    trainer.copy(
                                        fresh = false,
                                        activeWeightLb = null,
                                        activeWeightSource = null,
                                        measuredForceLb = null,
                                        runtimeWireWeightLb = null,
                                        resistanceTargetLb = null,
                                        reps = null,
                                    )
                                },
                        )
                    }
                    pending
                        ?.completion
                        ?.completeExceptionally(
                            IllegalStateException(
                                "Knob readings stopped. Reconnect before changing settings."
                            )
                        )
                }
            }
        }
    }

    override fun scan() = transport.scan()

    override suspend fun connect(device: VoltraDevice) {
        check(job.isActive)
        state.value.knob
            ?.takeIf { it.status == ConnectionStatus.FAILED }
            ?.let { disconnect(it.device.id) }
        check(state.value.knob?.status.let { it == null || it == ConnectionStatus.DISCONNECTED }) {
            "Disconnect the current knob first."
        }
        val token = ++generation
        mutableState.value =
            VoltraState(knob = KnobState(device, ConnectionStatus.CONNECTING), viaKnob = true)
        connection =
            scope.launch {
                var opened: VoltraLink? = null
                var firstReadingTimeout: Job? = null
                var pairingTimedOut = false
                try {
                    val connected = transport.connect(device)
                    opened = connected
                    ensureActive()
                    link = connected
                    firstReadingTimeout = launch {
                        // GATT subscription can finish before Android's pairing prompt does.
                        // Allow time to enter the code shown on the knob.
                        delay(90_000)
                        if (generation == token && snapshot == null) {
                            pairingTimedOut = true
                            connected.close()
                        }
                    }
                    connected.packets.collect { bytes ->
                        if (generation != token) return@collect
                        when {
                            KnobProtocol.header(bytes, KnobProtocol.SNAPSHOT) -> {
                                val firstReading = snapshot == null
                                receive(KnobProtocol.snapshot(bytes))
                                if (firstReading && generation == token) {
                                    rememberedDevice = device
                                    onConnected(device)
                                }
                            }
                            KnobProtocol.header(bytes, KnobProtocol.RESULT) -> {
                                val result = KnobProtocol.result(bytes)
                                val request = pending
                                if (
                                    request != null &&
                                        result.epoch == request.epoch &&
                                        result.session == request.session &&
                                        result.id == request.id
                                ) {
                                    when (result.code) {
                                        1 -> request.completion.complete(Unit)
                                        2 ->
                                            request.completion.completeExceptionally(
                                                IllegalStateException(
                                                    "Knob rejected the change. Check the trainer and retry."
                                                )
                                            )
                                        3 ->
                                            request.completion.completeExceptionally(
                                                IllegalStateException(
                                                    "VOLTRA did not confirm the change."
                                                )
                                            )
                                    }
                                }
                            }
                            else -> error("Knob firmware uses an unsupported bridge protocol.")
                        }
                    }
                    error(
                        if (pairingTimedOut)
                            "No readings from the knob. Reconnect with the knob powered on and nearby."
                        else "Knob disconnected. Reconnect to continue."
                    )
                } catch (timeout: TimeoutCancellationException) {
                    failConnection(
                        token,
                        IllegalStateException(
                            "Knob connection timed out. Check that it is powered on and nearby, then reconnect.",
                            timeout,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failConnection(token, failure)
                } finally {
                    firstReadingTimeout?.cancel()
                    opened?.close()
                    if (link === opened) link = null
                }
            }
    }

    private fun failConnection(token: Int, failure: Exception) {
        if (generation != token) return
        pending?.completion?.completeExceptionally(failure)
        snapshot = null
        snapshots.value = null
        baseline = null
        mutableState.update {
            it.copy(
                trainers = emptyList(),
                twin = null,
                twinPending = false,
                twinSupported = false,
                knob =
                    it.knob?.copy(
                        status = ConnectionStatus.FAILED,
                        batteryPercent = null,
                        usbPowerConnected = false,
                        twinCandidate = null,
                        twinExitAllowed = false,
                        error = failure.message ?: "Knob connection failed.",
                    ),
            )
        }
    }

    private fun receive(update: KnobProtocol.Snapshot) {
        val old = snapshot
        if (
            old == null ||
                update.epoch != old.epoch ||
                update.session != old.session ||
                update.trainer?.device?.id != old.trainer?.device?.id
        ) {
            baseline = update.trainer?.reps
            pending
                ?.completion
                ?.completeExceptionally(
                    IllegalStateException(
                        "The knob changed trainer connections. Retry on the selected trainer."
                    )
                )
        }
        snapshot = update
        snapshots.value = update
        receivedAt = now()
        val trainer =
            update.trainer?.let { trainer ->
                val raw = trainer.reps
                if (raw != null && (baseline == null || raw < requireNotNull(baseline)))
                    baseline = raw
                trainer.copy(
                    reps = raw?.let { (it - (baseline ?: it)).coerceAtLeast(0) },
                    pending = trainer.pending || pending != null,
                )
            }
        val peer =
            update.peer
                ?.takeIf { trainer?.twinReported == true && update.version == 2 }
                ?.let { device ->
                    requireNotNull(trainer)
                        .copy(
                            device = device,
                            batteryPercent = update.peerBattery,
                            twinRole = TwinRole.PEER,
                            activeWeightLb = null,
                            activeWeightSource = null,
                            measuredForceLb = null,
                            runtimeWireWeightLb = null,
                            resistanceTargetLb = null,
                            reps = null,
                            editableParameters = emptySet(),
                            modeChangeAllowed = false,
                        )
                }
        val pair =
            if (trainer != null && peer != null)
                TwinPair(
                    trainer.device.id,
                    peer.device.id,
                    if (trainer.connected && trainer.fresh && trainer.twinVerified) TwinHealth.READY
                    else TwinHealth.DEGRADED,
                )
            else null
        mutableState.update {
            it.copy(
                twin = pair,
                twinSupported = update.version == 2,
                trainers = listOfNotNull(trainer, peer),
                knob =
                    it.knob?.copy(
                        status = ConnectionStatus.READY,
                        batteryPercent = update.knobBattery,
                        usbPowerConnected = update.knobUsbPowerConnected,
                        twinCandidate = update.peer.takeIf { update.canCreateTwin },
                        twinExitAllowed = update.canExitTwin,
                        error = null,
                    ),
            )
        }
    }

    override fun disconnect(deviceId: String) {
        if (
            state.value.knob?.device?.id != deviceId &&
                state.value.trainers.none { it.device.id == deviceId }
        )
            return
        ++generation
        connection?.cancel()
        connection = null
        commandJobs.toList().forEach { it.cancel() }
        link?.close()
        link = null
        pending?.completion?.cancel()
        pending = null
        snapshot = null
        snapshots.value = null
        baseline = null
        nextId = 1
        mutableState.value = disconnectedState()
    }

    private fun disconnectedState() =
        VoltraState(
            viaKnob = true,
            knob = rememberedDevice?.let { KnobState(it, ConnectionStatus.DISCONNECTED) },
        )

    override fun forget(deviceId: String) {
        require(state.value.trainers.none { it.device.id == deviceId }) {
            "Manage trainers on the knob. Only the knob connection can be forgotten from the TV."
        }
        if (rememberedDevice?.id == deviceId) rememberedDevice = null
        disconnect(deviceId)
        // Invalidate the connection before persistence is cleared. Late snapshots carry the
        // old generation and cannot save this device again.
        onForgotten(deviceId)
    }

    override suspend fun setMode(deviceId: String, mode: TrainingMode) =
        change(deviceId, mode, null, 0)

    override suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int) =
        change(deviceId, null, parameter, value)

    private suspend fun change(
        deviceId: String,
        mode: TrainingMode?,
        parameter: Parameter?,
        value: Int,
    ) = connectionCommand {
        check(job.isActive)
        val current = requireNotNull(snapshot) { "Connect the knob first." }
        val trainer =
            state.value.trainers.singleOrNull { it.device.id == deviceId }
                ?: error("Select the knob's active trainer.")
        check(now() - receivedAt <= 2000 && trainer.fresh) { "Wait for fresh knob readings." }
        check(
            if (mode != null) trainer.canChangeMode else trainer.canEdit(requireNotNull(parameter))
        ) {
            "This setting cannot be changed in the current trainer state."
        }
        check(nextId <= 65535) { "Reconnect the knob before sending more changes." }
        val request = Pending(current.epoch, current.session, nextId++, CompletableDeferred())
        pending = request
        mutableState.update {
            it.copy(trainers = it.trainers.map { trainer -> trainer.copy(pending = true) })
        }
        try {
            requireNotNull(link)
                .write(KnobProtocol.command(current, request.id, mode, parameter, value))
            withTimeout(5000) {
                request.completion.await()
                snapshots.first { observed ->
                    observed != null &&
                        observed.epoch == current.epoch &&
                        observed.session == current.session &&
                        observed.trainer?.fresh == true &&
                        !observed.trainer.pending &&
                        if (mode != null) observed.trainer.mode == mode
                        else observed.trainer.parameters[parameter] == value
                }
                Unit
            }
        } finally {
            pending = null
            mutableState.update {
                it.copy(
                    trainers =
                        it.trainers.map { trainer ->
                            trainer.copy(pending = snapshot?.trainer?.pending ?: false)
                        }
                )
            }
        }
    }

    override suspend fun createTwin(controllerId: String, peerId: String) =
        changeTwin(true, controllerId, peerId)

    override suspend fun exitTwin() = changeTwin(false)

    private suspend fun changeTwin(
        create: Boolean,
        controllerId: String? = null,
        peerId: String? = null,
    ) = connectionCommand {
        val current = requireNotNull(snapshot) { "Connect the knob first." }
        if (create)
            require(current.trainer?.device?.id == controllerId && current.peer?.id == peerId) {
                "The knob's selected trainers changed. Check the current pair before retrying."
            }
        check(job.isActive && now() - receivedAt <= 2000)
        check(
            if (create) state.value.canCreateTwin && current.canCreateTwin
            else state.value.canExitTwin && current.canExitTwin
        ) {
            "Finish training on both trainers before changing Twin mode."
        }
        check(nextId <= 65535)
        val request = Pending(current.epoch, current.session, nextId++, CompletableDeferred())
        pending = request
        mutableState.update { it.copy(twinPending = true) }
        try {
            requireNotNull(link).write(KnobProtocol.twinCommand(current, request.id, create))
            withTimeout(45_000) {
                request.completion.await()
                snapshots.first { update ->
                    update != null &&
                        update.epoch == current.epoch &&
                        update.session == current.session &&
                        update.trainer?.fresh == true &&
                        update.trainer.twinStatusKnown &&
                        !update.trainer.pending &&
                        (if (create)
                            update.trainer.twinVerified && update.peer?.id == current.peer?.id
                        else !update.trainer.twinReported)
                }
                Unit
            }
        } finally {
            pending = null
            mutableState.update {
                it.copy(
                    twinPending = false,
                    trainers =
                        it.trainers.map { trainer ->
                            trainer.copy(pending = snapshot?.trainer?.pending ?: false)
                        },
                )
            }
        }
    }

    private suspend fun <T> connectionCommand(block: suspend () -> T): T = coroutineScope {
        val token = generation
        val operation = currentCoroutineContext().job
        commandJobs += operation
        try {
            commands.withLock {
                check(token == generation) { "Knob connection changed. Retry the setting." }
                block()
            }
        } finally {
            commandJobs -= operation
        }
    }

    override fun beginSet() {
        baseline = snapshot?.trainer?.reps ?: 0
        mutableState.update {
            it.copy(
                trainers =
                    it.trainers.map { trainer -> trainer.copy(reps = trainer.reps?.let { 0 }) }
            )
        }
    }

    override fun close() {
        state.value.knob?.device?.id?.let(::disconnect)
        job.cancel()
    }
}
