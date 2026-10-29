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
) : VoltraController, KnobTwinBridge {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private var rememberedDevice = rememberedDevice
    private var telemetryEpoch = 0L
    private var topologyRevision = 0L
    private val trainerEpochs = TrainerTelemetryEpochs<KnobTelemetrySource>()
    private val mutableState = MutableStateFlow(disconnectedState())
    override val state = mutableState.asStateFlow()
    private val commands = Mutex()
    private val writes = Mutex()
    private val offers = Mutex()
    private val activityWrites = Mutex()
    private val activityScopes = linkedMapOf<Long, SetupActivity>()
    private var activityToken = 0L
    private var activitySent = false
    private var activityEpoch: Int? = null
    private var activityDeadline: Int? = null

    override fun beginSetupActivity(activity: SetupActivity): AutoCloseable {
        val token = ++activityToken
        activityScopes[token] = activity
        updateState { it }
        scope.launch { sendSetupActivity() }
        return AutoCloseable {
            if (activityScopes.remove(token) != null) {
                updateState { it }
                scope.launch { sendSetupActivity() }
            }
        }
    }

    private suspend fun sendSetupActivity() = activityWrites.withLock {
        val current = snapshot ?: return@withLock
        val age = now() - receivedAt
        if (!current.setupActivitySupported || age !in 0..2000 || !job.isActive)
            return@withLock
        val activity = activityScopes.values.firstOrNull()
        if (activity == null && !activitySent) return@withLock
        val deviceNow = current.uptime + age.toInt()
        var deadline = deviceNow + 1500
        if (activityEpoch == current.epoch) {
            activityDeadline?.let { if (deadline - it <= 0) deadline = it + 1 }
        }
        if (deadline - deviceNow > 2000) return@withLock
        try {
            write(KnobProtocol.setupActivity(current, activity, deadline))
            activityEpoch = current.epoch
            activityDeadline = deadline
            activitySent = activity != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Presentation is best-effort. The expiring display lease cannot
            // fail, retry, authorize or conceal the result of a motor command.
        }
    }
    private var lastOfferDeadline: Int? = null
    private var lastOfferEpoch: Int? = null
    private var lastOfferSession: Int? = null
    private val commandJobs = mutableSetOf<Job>()
    private var link: VoltraLink? = null
    private var connection: Job? = null
    private var snapshot: KnobProtocol.Snapshot? = null
    private val snapshots = MutableStateFlow<KnobProtocol.Snapshot?>(null)
    private var receivedAt = 0L
    private var nextId = 1
    private var baseline: Int? = null
    private var waitingForFirstRep = false
    private var repTrainerId: String? = null
    private var repSource: RepSource? = null

    private data class RepSource(val controllerId: String, val peerId: String?, val modeWire: Int?)

    private data class KnobTelemetrySource(
        val generation: Int,
        val transportEpoch: Long,
        val epoch: Int?,
        val session: Int?,
        val trainer: TrainerTelemetrySource,
        val twin: Pair<String, String>?,
    )

    private inline fun updateState(transform: (VoltraState) -> VoltraState) =
        publish(transform(mutableState.value))

    private fun publish(next: VoltraState) {
        mutableState.value =
            next.copy(
                knob = next.knob?.copy(setupActivity = activityScopes.values.firstOrNull()
                    ?: snapshot?.setupActivity?.takeIf {
                        next.knob.status == ConnectionStatus.READY && now() - receivedAt <= 2000
                    }),
                trainerTelemetryEpochs = trainerEpochs.update(
                    next.trainers.associate { trainer ->
                        trainer.device.id to KnobTelemetrySource(
                            generation,
                            telemetryEpoch,
                            snapshot?.epoch,
                            snapshot?.session,
                            trainer.telemetrySource(),
                            next.twin?.let { it.controllerId.lowercase() to it.peerId.lowercase() },
                        )
                    }
                )
            )
    }

    private var pending: Pending? = null
    private var generation = 0
    private var lastTwinRequest: KnobTwinRequest? = null
    private var peerOfferSent = false
    private val mutableTwinRequests = MutableSharedFlow<KnobTwinRequest>(extraBufferCapacity = 8)
    override val twinRequests = mutableTwinRequests.asSharedFlow()

    private data class Pending(
        val epoch: Int,
        val session: Int,
        val id: Int,
        val completion: CompletableDeferred<Unit>,
    )

    init {
        scope.launch {
            while (isActive) {
                delay(750)
                sendSetupActivity()
            }
        }
        scope.launch {
            while (isActive) {
                delay(250)
                if (snapshot != null && now() - receivedAt >= 500) {
                    updateState { state ->
                        state.copy(
                            trainers = state.trainers.map { it.copy(cableMoving = null) },
                            twin = state.twin?.copy(measuredForceLb = null, cableMoving = null),
                        )
                    }
                }
                if (snapshot != null && now() - receivedAt > 2000) {
                    updateState {
                        it.copy(
                            twin =
                                it.twin?.copy(
                                    health = TwinHealth.DEGRADED,
                                    activeWeightLb = null,
                                    measuredForceLb = null,
                                    cableMoving = null,
                                    deviceReps = null,
                                    reps = null,
                                ),
                            knob =
                                it.knob?.copy(
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
                                        cableMoving = null,
                                        runtimeWireWeightLb = null,
                                        resistanceTargetLb = null,
                                        reps = null,
                                        deviceReps = null,
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
        publish(
            VoltraState(
                knob = KnobState(device, ConnectionStatus.CONNECTING),
                viaKnob = true,
                telemetryEpoch = telemetryEpoch,
            )
        )
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
                            KnobProtocol.header(bytes, KnobProtocol.TWIN_REQUEST) -> {
                                val request = KnobProtocol.twinRequest(bytes)
                                val current = snapshot
                                val last = lastTwinRequest
                                if (
                                    current?.mixedTwinHandoffSupported == true &&
                                        now() - receivedAt <= 2000 &&
                                        request.epoch == current.epoch &&
                                        request.session == current.session &&
                                        (last == null ||
                                            last.epoch != request.epoch ||
                                            last.session != request.session ||
                                            request.id > last.id)
                                ) {
                                    lastTwinRequest = request
                                    mutableTwinRequests.tryEmit(request)
                                }
                            }
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
                                                    motorError(result.reason)
                                                        ?: "Knob rejected the change. Check the trainer and retry."
                                                )
                                            )
                                        3 ->
                                            request.completion.completeExceptionally(
                                                IllegalStateException(
                                                    motorError(result.reason)
                                                        ?: "VOLTRA did not confirm the change."
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

    private fun motorError(reason: Int): String? =
        when (reason) {
            1 -> "VOLTRA is busy or not ready for load control."
            2 -> "VOLTRA has not confirmed its cable zero position. Check the cable position before retrying."
            3 -> "VOLTRA mode or Twin connection changed."
            4 -> "Load control expired or its connection was revoked."
            5 -> "The motor command could not be sent. Check VOLTRA before retrying."
            6 -> "VOLTRA did not confirm the motor command."
            7 -> "Cable-zero readings are unavailable. Wait for fresh VOLTRA readings."
            8 -> "VOLTRA settings changed during load preparation."
            9 -> "VOLTRA settings could not be verified before loading."
            else -> null
        }

    private fun failConnection(token: Int, failure: Exception) {
        if (generation != token) return
        telemetryEpoch++
        pending?.completion?.completeExceptionally(failure)
        snapshot = null
        snapshots.value = null
        baseline = null
        waitingForFirstRep = false
        repTrainerId = null
        repSource = null
        updateState {
            it.copy(
                trainers = emptyList(),
                twin = null,
                twinPending = false,
                twinSupported = false,
                telemetryEpoch = telemetryEpoch,
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

    private var motorContextRevision = 0L

    private fun receive(update: KnobProtocol.Snapshot) {
        val old = snapshot
        val oldMotorPeer = old?.peer?.id.takeIf { old?.trainer?.twinReported == true }
        val newMotorPeer = update.peer?.id.takeIf { update.trainer?.twinReported == true }
        if (
            old != null &&
                (old.epoch != update.epoch ||
                    old.session != update.session ||
                    old.trainer?.mode != update.trainer?.mode ||
                    old.trainer?.parameters?.filterKeys {
                        it in Parameter.forMode(old.trainer.mode)
                    } !=
                        update.trainer?.parameters?.filterKeys {
                            it in Parameter.forMode(update.trainer.mode)
                        } ||
                    old.trainer?.twinReported != update.trainer?.twinReported ||
                    !old.trainer?.device?.id.equals(update.trainer?.device?.id, true) ||
                    !oldMotorPeer.equals(newMotorPeer, true))
        )
            motorContextRevision++
        val trainerId = update.trainer?.device?.id
        if (
            old != null &&
                (update.epoch != old.epoch ||
                    update.session != old.session ||
                    (trainerId != null &&
                        repTrainerId != null &&
                        !trainerId.equals(repTrainerId, true)))
        ) {
            telemetryEpoch++
            baseline = null
            waitingForFirstRep = false
            repTrainerId = null
            repSource = null
        }
        // Unknown/temporarily absent snapshots must not erase an explicit set baseline.
        // Compare the last known logical source, including both native pair identities.
        val nextRepSource =
            update.trainer
                ?.takeIf { it.twinStatusKnown && (!it.twinReported || update.peer != null) }
                ?.let {
                    RepSource(
                        it.device.id.lowercase(),
                        update.peer?.id?.lowercase().takeIf { _ -> it.twinReported },
                        it.modeWireValue.takeIf { _ -> it.twinReported },
                    )
                }
        if (nextRepSource != null) {
            if (repSource != null && repSource != nextRepSource) {
                baseline = null
                waitingForFirstRep = false
            }
            repSource = nextRepSource
        }
        if (trainerId != null) repTrainerId = trainerId
        if (
            old == null ||
                update.epoch != old.epoch ||
                update.session != old.session ||
                !update.trainer?.device?.id.equals(old.trainer?.device?.id, true)
        ) {
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
        val raw =
            if (update.trainer?.twinReported == true) update.twinDeviceReps
            else
                update.trainer?.deviceReps.takeIf {
                    update.trainer?.connected == true && update.trainer.fresh
                }
        if (raw != null) {
            if (waitingForFirstRep) {
                // The first available pull after beginSet is one, even with no initial baseline.
                baseline = (raw - 1).coerceAtLeast(0)
                waitingForFirstRep = false
            } else if (baseline != null && raw < requireNotNull(baseline)) {
                baseline = 0
            }
        }
        val relativeReps = raw?.let { (it - (baseline ?: 0)).coerceAtLeast(0) }
        val trainer =
            update.trainer?.let { trainer ->
                trainer.copy(
                    reps = relativeReps.takeUnless { trainer.twinReported },
                    deviceReps = raw.takeUnless { trainer.twinReported },
                    pending = trainer.pending || pending != null,
                )
            }
        val peer =
            update.peer
                ?.takeIf { trainer?.twinReported == true && update.version >= 2 }
                ?.let { device ->
                    requireNotNull(trainer)
                        .copy(
                            device = device,
                            batteryPercent = update.peerBattery,
                            twinRole = TwinRole.PEER,
                            activeWeightLb = null,
                            activeWeightSource = null,
                            measuredForceLb = null,
                            cableMoving = null,
                            runtimeWireWeightLb = null,
                            resistanceTargetLb = null,
                            reps = null,
                            deviceReps = null,
                            editableParameters = emptySet(),
                            modeChangeAllowed = false,
                            loadControlSupported = false,
                            loadAllowed = false,
                            unloadAllowed = false,
                            loadPreparationAllowed = false,
                        )
                }
        val pair =
            if (trainer != null && peer != null)
                TwinPair(
                    trainer.device.id,
                    peer.device.id,
                    if (trainer.connected && trainer.fresh && trainer.twinVerified) TwinHealth.READY
                    else TwinHealth.DEGRADED,
                    reps = relativeReps.takeUnless { trainer.pending },
                    measuredForceLb = update.twinMeasuredForceLb.takeUnless { trainer.pending },
                    cableMoving =
                        true.takeIf { update.twinMeasuredForceLb != null && !trainer.pending },
                    deviceReps = raw.takeUnless { trainer.pending },
                )
            else null
        updateState {
            it.copy(
                twin = pair,
                twinSupported = update.version >= 2,
                topologyRevision = ++topologyRevision,
                telemetryEpoch = telemetryEpoch,
                trainers = listOfNotNull(trainer, peer),
                knob =
                    it.knob?.copy(
                        status = ConnectionStatus.READY,
                        batteryPercent = update.knobBattery,
                        usbPowerConnected = update.knobUsbPowerConnected,
                        twinCandidate = update.peer.takeIf { update.canCreateTwin },
                        twinExitAllowed = update.canExitTwin,
                        twinIdentityGuarded = update.twinIdentityGuarded,
                        mixedTwinHandoffSupported = update.mixedTwinHandoffSupported,
                        error = null,
                    ),
            )
        }
    }

    override fun disconnect(deviceId: String) {
        if (
            !state.value.knob?.device?.id.equals(deviceId, true) &&
                state.value.trainers.none { it.device.id.equals(deviceId, true) }
        )
            return
        ++generation
        telemetryEpoch++
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
        waitingForFirstRep = false
        repTrainerId = null
        repSource = null
        nextId = 1
        peerOfferSent = false
        lastOfferDeadline = null
        lastOfferEpoch = null
        lastOfferSession = null
        publish(disconnectedState())
    }

    private fun disconnectedState() =
        VoltraState(
            viaKnob = true,
            knob = rememberedDevice?.let { KnobState(it, ConnectionStatus.DISCONNECTED) },
            telemetryEpoch = telemetryEpoch,
        )

    override fun forget(deviceId: String) {
        require(state.value.trainers.none { it.device.id.equals(deviceId, true) }) {
            "Manage trainers on the knob. Only the knob connection can be forgotten from the TV."
        }
        if (rememberedDevice?.id.equals(deviceId, true)) rememberedDevice = null
        disconnect(deviceId)
        // Invalidate the connection before persistence is cleared. Late snapshots carry the
        // old generation and cannot save this device again.
        onForgotten(deviceId)
    }

    override suspend fun setMode(deviceId: String, mode: TrainingMode) =
        change(deviceId, mode, null, 0)

    override suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int) =
        change(deviceId, null, parameter, value)

    override suspend fun setLoaded(deviceId: String, loaded: Boolean) = connectionCommand {
        val current = requireNotNull(snapshot) { "Connect the knob first." }
        val trainer =
            state.value.trainers.singleOrNull { it.device.id.equals(deviceId, true) }
                ?: error("Select the knob's active trainer.")
        check(
            job.isActive &&
                now() - receivedAt in 0..1500 &&
                trainer.connected &&
                trainer.fresh &&
                !trainer.pending &&
                trainer.mode != null &&
                trainer.twinStatusKnown &&
                (!trainer.twinReported ||
                    (trainer.twinVerified && trainer.twinRole == TwinRole.CONTROLLER))
        ) {
            "A fresh, confirmed knob controller is required for load control."
        }
        if (if (loaded) trainer.loaded else isFitnessReady(trainer.fitnessMode))
            return@connectionCommand
        check(trainer.loadControlSupported) {
            "Update the knob firmware to enable confirmed load control."
        }
        check(if (loaded) trainer.canLoad || trainer.canPrepareLoad else trainer.canUnload) {
            if (loaded) "VOLTRA is not ready to load. Check its controls and connection."
            else "VOLTRA cannot unload in its current state."
        }
        check(nextId <= 65535) { "Reconnect the knob before sending more changes." }
        val contextRevision = motorContextRevision
        val motorLink = requireNotNull(link)
        val request = Pending(current.epoch, current.session, nextId++, CompletableDeferred())
        pending = request
        updateState {
            it.copy(trainers = it.trainers.map { trainer -> trainer.copy(pending = true) })
        }
        try {
            write(KnobProtocol.motorCommand(current, request.id, loaded))
            withTimeout(6_000) {
                request.completion.await()
                snapshots.first { observed ->
                    if (observed == null) return@first false
                    check(
                        motorContextRevision == contextRevision &&
                            observed.epoch == current.epoch &&
                            observed.session == current.session &&
                            observed.trainer
                                ?.device
                                ?.id
                                .equals(current.trainer?.device?.id, true) &&
                            observed.trainer?.mode == trainer.mode &&
                            observed.trainer?.twinReported == trainer.twinReported &&
                            (!trainer.twinReported ||
                                observed.peer?.id.equals(current.peer?.id, true))
                    ) {
                        "VOLTRA mode or connection changed during load control."
                    }
                    observed.uptime - current.uptime > 0 &&
                        now() - receivedAt in 0..1500 &&
                        observed.trainer?.fresh == true &&
                        !observed.trainer.pending &&
                        if (loaded) observed.trainer.loaded
                        else isFitnessReady(observed.trainer.fitnessMode)
                }
            }
        } catch (failure: TimeoutCancellationException) {
            revokeCommandConnection(motorLink)
            currentCoroutineContext().ensureActive()
            error(
                "VOLTRA did not confirm ${if (loaded) "loading" else "unloading"}. Check the trainer before reconnecting."
            )
        } catch (cancelled: CancellationException) {
            // A cancelled await cannot retract an accepted BLE command. Revoke its session so
            // queued setup cannot later send GO. A GO already transmitted remains uncertain.
            revokeCommandConnection(motorLink)
            throw cancelled
        } finally {
            pending = null
            updateState {
                it.copy(
                    trainers =
                        it.trainers.map { trainer ->
                            trainer.copy(pending = snapshot?.trainer?.pending ?: false)
                        }
                )
            }
        }
    }

    private suspend fun revokeCommandConnection(active: VoltraLink) {
        withContext(NonCancellable) {
            try {
                withTimeout(750) { active.revoke() }
            } catch (_: Exception) {
                /* Closing still revokes the physical session on disconnect. */
            } finally {
                active.close()
            }
        }
        if (link === active) {
            // Preserve the caller's timeout/error while cancelling every other queued operation.
            commandJobs -= currentCoroutineContext().job
            state.value.knob?.device?.id?.let(::disconnect)
        }
    }

    private suspend fun change(
        deviceId: String,
        mode: TrainingMode?,
        parameter: Parameter?,
        value: Int,
    ) = connectionCommand {
        check(job.isActive)
        val current = requireNotNull(snapshot) { "Connect the knob first." }
        val trainer =
            state.value.trainers.singleOrNull { it.device.id.equals(deviceId, true) }
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
        updateState {
            it.copy(trainers = it.trainers.map { trainer -> trainer.copy(pending = true) })
        }
        try {
            write(KnobProtocol.command(current, request.id, mode, parameter, value))
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
            updateState {
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

    override suspend fun offerTwinPeer(peer: VoltraDevice?, ready: Boolean) =
        offers.withLock {
            // ID zero is reserved for expiring ownership/readiness announcements. It neither
            // consumes
            // motor-command IDs nor waits for a long-running native Twin command to finish.
            val current = snapshot ?: return@withLock
            val age = now() - receivedAt
            if (!current.mixedTwinHandoffSupported || age !in 0..2000 || !job.isActive)
                return@withLock
            if (peer == null && !peerOfferSent) return@withLock
            var deadline = current.uptime + age.toInt() + 1500
            if (lastOfferEpoch == current.epoch && lastOfferSession == current.session) {
                lastOfferDeadline?.let { if (deadline - it <= 0) deadline = it + 1 }
            }
            // Leave 500 ms headroom for immediate revoke/refresh calls without extending a stale
            // snapshot indefinitely. Firmware independently rejects >2-second or replayed
            // deadlines.
            if (deadline - (current.uptime + age.toInt()) > 2000) return@withLock
            write(KnobProtocol.twinPeerOffer(current, peer, ready, deadline))
            lastOfferDeadline = deadline
            lastOfferEpoch = current.epoch
            lastOfferSession = current.session
            peerOfferSent = peer != null
        }

    override suspend fun reportTwinRequest(
        request: KnobTwinRequest,
        status: KnobTwinRequestStatus,
    ) =
        commands.withLock {
            val current = snapshot ?: return@withLock
            if (
                !current.mixedTwinHandoffSupported ||
                    request.epoch != current.epoch ||
                    request.session != current.session ||
                    now() - receivedAt > 2000 ||
                    !job.isActive
            )
                return@withLock
            check(nextId <= 65535) { "Reconnect the knob before sending more changes." }
            write(KnobProtocol.twinRequestStatus(current, nextId++, request, status))
        }

    private suspend fun changeTwin(
        create: Boolean,
        controllerId: String? = null,
        peerId: String? = null,
    ) = connectionCommand {
        val current = requireNotNull(snapshot) { "Connect the knob first." }
        if (create)
            require(
                current.trainer?.device?.id.equals(controllerId, true) &&
                    current.peer?.id.equals(peerId, true)
            ) {
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
        val twinLink = requireNotNull(link)
        val request = Pending(current.epoch, current.session, nextId++, CompletableDeferred())
        pending = request
        updateState { it.copy(twinPending = true) }
        try {
            write(KnobProtocol.twinCommand(current, request.id, create))
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
                            update.trainer.twinVerified &&
                                update.peer?.id.equals(current.peer?.id, true)
                        else !update.trainer.twinReported)
                }
                Unit
            }
        } catch (failure: TimeoutCancellationException) {
            revokeCommandConnection(twinLink)
            currentCoroutineContext().ensureActive()
            error("Twin change was not confirmed. Check both trainers before reconnecting.")
        } catch (cancelled: CancellationException) {
            // An accepted Twin request can still be unloading or pairing on the worker.
            // Revoke the logical session before closing, so cancellation cannot leave those
            // queued topology writes authorized. Already transmitted writes remain uncertain.
            revokeCommandConnection(twinLink)
            throw cancelled
        } finally {
            pending = null
            updateState {
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

    private suspend fun write(frame: ByteArray) {
        val active = requireNotNull(link)
        val token = generation
        writes.withLock {
            check(token == generation) { "Knob connection changed." }
            active.write(frame)
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
        val current = snapshot
        val freshTrainer =
            current?.trainer?.takeIf {
                it.connected && it.fresh && !it.pending && now() - receivedAt <= 2000
            }
        val raw =
            if (freshTrainer?.twinReported == true) current?.twinDeviceReps
            else freshTrainer?.deviceReps
        baseline = raw ?: 0
        waitingForFirstRep = raw == null
        updateState {
            it.copy(
                twin = it.twin?.copy(reps = raw?.let { 0 }),
                trainers =
                    it.trainers.map { trainer ->
                        trainer.copy(
                            reps =
                                raw?.let { 0 }
                                    .takeIf {
                                        !trainer.twinReported &&
                                            trainer.device.id.equals(freshTrainer?.device?.id, true)
                                    }
                        )
                    },
            )
        }
    }

    override fun close() {
        state.value.knob?.device?.id?.let(::disconnect)
        job.cancel()
    }
}
