package io.github.albertzhang86.voltra

import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns independent direct and knob links without making discovery a connection switch.
 *
 * Like the child controllers, lifecycle methods must run on the owning dispatcher. There may be two
 * trainers in total. A native Twin must belong entirely to one route; this controller never creates
 * a software Twin across two owners. Settings retain the child's protocol units.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
class MixedVoltraController(
    private val direct: VoltraController,
    private val knob: VoltraController?,
    parentScope: CoroutineScope,
    initialRoute: VoltraConnectionRoute = VoltraConnectionRoute.DIRECT,
) : VoltraController {
    override fun beginSetupActivity(activity: SetupActivity): AutoCloseable =
        knob?.beginSetupActivity(activity) ?: AutoCloseable {}

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val discoveryRoute = MutableStateFlow(initialRoute)
    private val connections = Mutex()
    private val topology = Mutex()
    private var topologyOperation: Job? = null
    private var manualPreparation: ManualPreparation? = null
    private var manualPreparationJob: Job? = null
    private var activeControlCommands = 0
    private var topologyPending = false
    private var localExitRestoreQueued = false
    private var handoff: Handoff? = null
    private var recoveryBlocked = false
    private var reconnectingRecovery: Handoff? = null
    private var lastOfferedPeer: VoltraDevice? = null
    private var lastHandledKnobRequest: KnobTwinRequest? = null
    private val knownKnobs = mutableSetOf<String>()
    private var closed = false
    private var telemetryEpoch = 0L
    private var continuity: Continuity? = null
    private val trainerEpochs = TrainerTelemetryEpochs<MixedTelemetrySource>()
    private val mutableState = MutableStateFlow(VoltraState())

    /** Reads current child snapshots even before their flow collectors have been scheduled. */
    val currentState: VoltraState
        get() = refresh()

    override val state: StateFlow<VoltraState> =
        object : StateFlow<VoltraState> by mutableState {
            override val value: VoltraState
                get() = currentState

            override val replayCache: List<VoltraState>
                get() = listOf(value)

            override suspend fun collect(collector: FlowCollector<VoltraState>): Nothing {
                refresh()
                mutableState.collect(collector)
            }
        }

    init {
        require(direct !== knob) { "Direct and knob routes need separate controllers." }
        require(initialRoute != VoltraConnectionRoute.KNOB || knob != null) {
            "The knob connection is unavailable."
        }
        refresh()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { direct.state.collect { refresh() } }
        knob?.let { controller ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                controller.state.collect { refresh() }
            }
        }
        (knob as? KnobTwinBridge)?.let { bridge ->
            scope.launch {
                while (isActive) {
                    val current = refresh()
                    // Ownership remains visible while either route is temporarily busy. During
                    // handoff the directly-owned peer is absent, but its exact authorization must
                    // stay fresh until the native transaction finishes.
                    val retained = handoff.takeIf { topologyPending }
                    val peer =
                        (retained?.peer
                                ?: current.trainers
                                    .singleOrNull {
                                        current.routeFor(it.device.id) ==
                                            VoltraConnectionRoute.DIRECT
                                    }
                                    ?.device
                                    .takeIf { current.hasMixedConnections })
                            .takeIf { current.knob?.mixedTwinHandoffSupported == true }
                    val ready =
                        peer != null && (retained != null || current.canCreateTwinAfterUnloading)
                    lastOfferedPeer = peer.takeIf { ready }
                    try {
                        bridge.offerTwinPeer(peer, ready)
                    } catch (_: TimeoutCancellationException) {
                        // A transport write timeout cancels that operation, not this long-lived
                        // owner. Keep offering after an explicit reconnect; never replay control.
                        currentCoroutineContext().ensureActive()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        /* A lost offer expires on the knob; it never pairs. */
                    }
                    delay(750)
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                bridge.twinRequests.collect { request -> handleKnobTwinRequest(bridge, request) }
            }
        }
    }

    /** Changes discovery only. Existing trainers and their set baselines remain connected. */
    fun selectRoute(route: VoltraConnectionRoute) {
        checkOpen()
        controller(route)
        discoveryRoute.value = route
        refresh()
    }

    override fun scan(): Flow<List<VoltraDevice>> = channelFlow {
        checkOpen()
        val scanner = launch {
            discoveryRoute
                .flatMapLatest { route ->
                    controller(route).scan().map { devices ->
                        checkOpen()
                        if (route == VoltraConnectionRoute.KNOB)
                            knownKnobs += devices.map { it.id.identity() }
                        // Do not invite a second connection to a trainer already owned by the knob.
                        // A candidate is merely nearby and remains eligible for a direct
                        // connection.
                        val knobTrainerIds =
                            knob
                                ?.state
                                ?.value
                                ?.trainers
                                .orEmpty()
                                .map { it.device.id.identity() }
                                .toSet()
                        devices.filter {
                            route != VoltraConnectionRoute.DIRECT ||
                                it.id.identity() !in knobTrainerIds
                        }
                    }
                }
                .collect { send(it) }
        }
        launch {
            job.join()
            scanner.cancel()
            channel.close()
        }
    }

    override suspend fun connect(device: VoltraDevice) =
        connections.withLock {
            checkOpen()
            currentCoroutineContext().ensureActive()
            val current = refresh()
            val recovery = handoff.takeIf { recoveryBlocked }
            if (recovery != null && device.id.equals(recovery.knobId, ignoreCase = true)) {
                // Reconnecting the exact bridge is how an uncertain native outcome is read back.
                // Keep all control blocked until its new session confirms the original topology.
                check(current.recoverableKnobId.equals(device.id, ignoreCase = true)) {
                    "Wait for the knob or Twin connection change to finish."
                }
                val bridge = requireNotNull(knob)
                val target =
                    bridge.state.value.knob?.device
                        ?.takeIf { it.id.equals(recovery.knobId, ignoreCase = true) }
                        ?: device.copy(id = recovery.knobId)
                bridge.disconnect(recovery.knobId)
                reconnectingRecovery = recovery
                try {
                    currentCoroutineContext().ensureActive()
                    checkOpen()
                    bridge.connect(target)
                } catch (failure: Exception) {
                    reconnectingRecovery = null
                    throw failure
                } finally {
                    refresh()
                }
                return@withLock
            }
            check(current.connectionError == null) { requireNotNull(current.connectionError) }
            check(current.twin == null && !current.twinPending) {
                "Exit Twin mode before connecting another trainer."
            }
            val occupied = owners(device.id)
            check(occupied.size <= 1) { "This trainer has more than one connection owner." }
            val existing = occupied.singleOrNull()
            val retry =
                existing?.let { owner ->
                    val trainer =
                        owner.controller.state.value.trainers.single {
                            it.device.id.equals(owner.deviceId, true)
                        }
                    check(
                        owner.route == VoltraConnectionRoute.DIRECT &&
                            trainer.status in
                                setOf(ConnectionStatus.FAILED, ConnectionStatus.DISCONNECTED) &&
                            !trainer.pending &&
                            !trainer.twinReported
                    ) {
                        "This trainer is already connected. Disconnect its current route first."
                    }
                    trainer.device
                }
            // An offline row still owns its exact device identity. Reconnect on that route,
            // even if discovery currently shows knobs; never send a trainer MAC to the bridge.
            val route =
                existing?.route
                    ?: if (device.id.identity() in knownKnobs) VoltraConnectionRoute.KNOB
                    else discoveryRoute.value
            if (route == VoltraConnectionRoute.DIRECT) {
                check(current.trainers.size - (if (retry != null) 1 else 0) < 2) {
                    "Disconnect a trainer before adding another."
                }
            } else {
                check(direct.state.value.trainers.size < 2) {
                    "Disconnect a direct trainer before connecting the knob."
                }
            }
            try {
                if (retry != null) {
                    // The failed child entry retains a closed GATT and blocks a fresh attempt.
                    // Removing it never unloads, changes Twin, or replays a previous command.
                    direct.disconnect(retry.id)
                    currentCoroutineContext().ensureActive()
                    checkOpen()
                    val latest = refresh()
                    check(
                        latest.connectionError == null &&
                            latest.twin == null &&
                            !latest.twinPending &&
                            owners(retry.id).isEmpty()
                    ) {
                        "Trainer ownership changed before reconnecting."
                    }
                }
                controller(route).connect(retry ?: device)
            } finally {
                refresh()
            }
        }

    override fun disconnect(deviceId: String) {
        checkOpen()
        check(!topologyPending && manualPreparation == null) {
            "Wait for the Twin connection change to finish."
        }
        val owner = lifecycleOwner(deviceId) ?: return
        try {
            owner.controller.disconnect(owner.deviceId)
            if (owner.route == VoltraConnectionRoute.KNOB) {
                reconnectingRecovery = null
                // Cancel closes the attempted bridge session, not an uncertain native outcome.
                // Keep the original recovery target until a later reconnect verifies topology.
                if (!recoveryBlocked) handoff = null
            }
        } finally {
            refresh()
        }
    }

    override fun forget(deviceId: String) {
        checkOpen()
        check(!topologyPending && manualPreparation == null) {
            "Wait for the Twin connection change to finish."
        }
        val owner =
            lifecycleOwner(deviceId)
                ?: if (deviceId.identity() in knownKnobs && knob != null)
                    Owner(knob, VoltraConnectionRoute.KNOB, deviceId)
                else Owner(direct, VoltraConnectionRoute.DIRECT, deviceId)
        try {
            owner.controller.forget(owner.deviceId)
            if (owner.route == VoltraConnectionRoute.KNOB) {
                knownKnobs -= deviceId.identity()
                recoveryBlocked = false
                reconnectingRecovery = null
                handoff = null
            }
        } finally {
            refresh()
        }
    }

    override suspend fun setMode(deviceId: String, mode: TrainingMode) = controlCommand {
        val owner = commandOwner(deviceId)
        owner.controller.setMode(owner.deviceId, mode)
    }

    override suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int) =
        controlCommand {
            val owner = commandOwner(deviceId)
            owner.controller.setParameter(owner.deviceId, parameter, value)
        }

    override suspend fun setLoaded(deviceId: String, loaded: Boolean) = controlCommand {
        val owner = commandOwner(deviceId)
        owner.controller.setLoaded(owner.deviceId, loaded)
    }

    override suspend fun createTwinAfterUnloading(
        controllerId: String,
        peerId: String,
        stillRelevant: () -> Boolean,
    ) = manualTwinChange { internalController ->
        prepareTwinChange(internalController, controllerId, peerId, true, stillRelevant)
    }

    override suspend fun exitTwinAfterUnloading(stillRelevant: () -> Boolean) =
        manualTwinChange { internalController ->
            prepareTwinChange(internalController, null, null, false, stillRelevant)
        }

    /** Keep one explicit owner from the first STOP through confirmed native topology. */
    private suspend fun manualTwinChange(action: suspend (VoltraController) -> Unit) =
        coroutineScope {
            checkOpen()
            check(manualPreparation == null && activeControlCommands == 0) {
                "Wait for the current trainer operation before changing Twin."
            }
            check(!refresh().twinPending) { "Wait for the current Twin connection change." }
            val owner = ManualPreparation()
            manualPreparation = owner
            manualPreparationJob = currentCoroutineContext().job
            refresh()
            try {
                withContext(owner) {
                    // Only the shared helper may read through our reservation. Child pending,
                    // freshness and native topology guards remain unchanged. This internal view
                    // is never published to observers or used by unrelated command callers.
                    val internalController =
                        object : VoltraController by this@MixedVoltraController {
                            override val state: StateFlow<VoltraState> =
                                object :
                                    StateFlow<VoltraState> by this@MixedVoltraController.state {
                                    override val value: VoltraState
                                        get() = preparationSnapshot()
                                }
                        }
                    action(internalController)
                }
            } finally {
                manualPreparation = null
                manualPreparationJob = null
                refresh()
            }
        }

    private suspend fun controlCommand(action: suspend () -> Unit) {
        commandSnapshot() // Reject unrelated work before it can reach either transport.
        activeControlCommands++
        try {
            action()
        } finally {
            activeControlCommands--
            refresh()
        }
    }

    override suspend fun createTwin(controllerId: String, peerId: String) =
        topologyChange { current ->
            // A late confirmation makes retrying idempotent; never resend a native pairing command.
            if (confirmedPair(current, controllerId, peerId)) return@topologyChange
            check(current.twin == null) {
                "Twin status is not confirmed. Wait for the current pair."
            }
            check(current.canCreateTwin) {
                "Finish training on both trainers before creating Twin."
            }
            val owner =
                owners(controllerId).singleOrNull()
                    ?: error("The selected trainer is no longer connected.")
            val peer =
                owners(peerId).singleOrNull()
                    ?: knob
                        ?.state
                        ?.value
                        ?.knob
                        ?.twinCandidate
                        ?.takeIf { it.id.equals(peerId, ignoreCase = true) }
                        ?.let { Owner(requireNotNull(knob), VoltraConnectionRoute.KNOB, it.id) }
                    ?: error("Connect the second trainer on the same route before creating Twin.")
            if (owner.route != peer.route) {
                val knobOwner = if (owner.route == VoltraConnectionRoute.KNOB) owner else peer
                val directOwner = if (owner.route == VoltraConnectionRoute.DIRECT) owner else peer
                createMixedTwin(knobOwner, directOwner)
            } else {
                owner.controller.createTwin(owner.deviceId, peer.deviceId)
            }
        }

    override suspend fun exitTwin() = topologyChange { current ->
        val pair = requireNotNull(current.twin) { "No Twin connection is active." }
        val owner =
            owners(pair.controllerId).singleOrNull()
                ?: error("The Twin controller is no longer connected.")
        check(current.canExitTwin) { "Finish training on both trainers before exiting Twin." }
        owner.controller.exitTwin()
        handoff?.let { original ->
            if (
                owner.route == VoltraConnectionRoute.KNOB &&
                    pair.controllerId.equals(original.controllerId, true) &&
                    pair.peerId.equals(original.peer.id, true)
            ) {
                awaitKnobState { independentKnob(it, original) }
                restoreDirect(original)
            }
        }
    }

    private suspend fun topologyChange(action: suspend (VoltraState) -> Unit) = coroutineScope {
        checkOpen()
        check(topology.tryLock()) { "A Twin connection change is already in progress." }
        try {
            val current = commandSnapshot()
            check(current.connectionError == null) { requireNotNull(current.connectionError) }
            check(!current.twinPending) { "Wait for the current Twin connection change." }
            topologyOperation = currentCoroutineContext().job
            topologyPending = true
            refresh()
            action(current)
        } finally {
            topologyOperation = null
            topologyPending = false
            topology.unlock()
            refresh()
        }
    }

    private suspend fun createMixedTwin(knobOwner: Owner, directOwner: Owner) {
        val bridge = requireNotNull(knob)
        check(
            bridge.state.value.isSimulated || bridge.state.value.knob?.twinIdentityGuarded == true
        ) {
            "Update the knob firmware before creating Twin from mixed connections."
        }
        val original =
            Handoff(
                knobOwner.deviceId,
                direct.state.value.trainers.single { it.device.id == directOwner.deviceId }.device,
                requireNotNull(bridge.state.value.knob).device.id,
            )
        handoff = original
        var commandSent = false
        try {
            // A VOLTRA accepts one controlling BLE client; release it before the knob connects.
            direct.disconnect(original.peer.id)
            refresh()
            val ready = awaitKnobState { snapshot ->
                checkSameKnobController(snapshot, original)
                independentKnob(snapshot, original) &&
                    snapshot.canCreateTwin &&
                    snapshot.knob?.twinCandidate?.id.equals(original.peer.id, true)
            }
            currentCoroutineContext().ensureActive()
            checkOpen()
            commandSent = true
            bridge.createTwin(original.controllerId, requireNotNull(ready.knob?.twinCandidate).id)
            awaitKnobState { snapshot ->
                checkSameKnobController(snapshot, original)
                confirmedPair(snapshot, original.controllerId, original.peer.id) &&
                    snapshot.twin?.controllerId.equals(original.controllerId, true)
            }
        } catch (failure: Exception) {
            if (
                confirmedPair(bridge.state.value, original.controllerId, original.peer.id) &&
                    bridge.state.value.twin?.controllerId.equals(original.controllerId, true)
            )
                return
            if (!closed && job.isActive) {
                // Never reconnect into an uncertain native pair. A fresh snapshot after failure
                // must prove that the knob is independent and has finished its transaction.
                val failureRevision = bridge.state.value.topologyRevision
                val safe =
                    if (!commandSent) independentKnob(bridge.state.value, original)
                    else
                        withContext(NonCancellable) {
                            withTimeoutOrNull(3_000) {
                                bridge.state.first {
                                    it.topologyRevision > failureRevision &&
                                        independentKnob(it, original)
                                }
                            } != null
                        }
                if (safe && !closed) {
                    try {
                        withContext(NonCancellable) { restoreDirect(original) }
                    } catch (restoreFailure: Exception) {
                        failure.addSuppressed(restoreFailure)
                    }
                } else recoveryBlocked = true
            }
            if (failure is TimeoutCancellationException && currentCoroutineContext().isActive)
                throw IllegalStateException(
                    "Twin was not confirmed. Check both trainers and retry.",
                    failure,
                )
            throw failure
        }
    }

    private suspend fun handleKnobTwinRequest(bridge: KnobTwinBridge, request: KnobTwinRequest) {
        val previous = lastHandledKnobRequest
        if (
            previous != null &&
                previous.epoch == request.epoch &&
                previous.session == request.session &&
                request.id <= previous.id
        )
            return
        lastHandledKnobRequest = request
        var outcome = KnobTwinRequestStatus.FAILED
        try {
            val current = refresh()
            check(
                lastOfferedPeer?.id.equals(request.peerId, true) &&
                    current.canCreateTwinAfterUnloading &&
                    current.hasMixedConnections &&
                    current.knob?.mixedTwinHandoffSupported == true
            ) {
                "The TV peer offer changed. Try again."
            }
            val controller =
                current.trainers.single {
                    current.routeFor(it.device.id) == VoltraConnectionRoute.KNOB
                }
            val peer =
                current.trainers.single {
                    current.routeFor(it.device.id) == VoltraConnectionRoute.DIRECT &&
                        it.device.id.equals(request.peerId, true)
                }
            bridge.reportTwinRequest(request, KnobTwinRequestStatus.ACCEPTED)
            createTwinAfterUnloading(controller.device.id, peer.device.id) {
                !closed &&
                    job.isActive &&
                    knob?.state?.value?.knob?.device?.id.equals(current.knob?.device?.id, true)
            }
            outcome = KnobTwinRequestStatus.SUCCEEDED
        } catch (_: TimeoutCancellationException) {
            // Deduplication already consumed this request. Report failure if possible and keep
            // listening for a new explicit request after the connection recovers.
            currentCoroutineContext().ensureActive()
        } catch (_: CancellationException) {
            // A cancelled manual preparation must not kill this long-lived request collector.
            // Actual owner cancellation still propagates; no control request is replayed.
            currentCoroutineContext().ensureActive()
        } catch (_: Exception) {
            // The knob displays failure. An explicit new request is required; no replay on
            // reconnect.
        } finally {
            if (!closed && job.isActive) {
                try {
                    bridge.reportTwinRequest(request, outcome)
                } catch (_: TimeoutCancellationException) {
                    // The knob's bounded wait handles a lost status; no status/native replay.
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    /* Firmware times out the pending request if the link is lost. */
                }
            }
        }
    }

    private suspend fun awaitKnobState(ready: (VoltraState) -> Boolean): VoltraState =
        withTimeout(15_000) { requireNotNull(knob).state.first(ready) }

    private fun checkSameKnobController(state: VoltraState, original: Handoff) {
        check(
            state.knob?.device?.id.equals(original.knobId, true) &&
                state.knob?.status == ConnectionStatus.READY &&
                state.trainers.any { it.device.id.equals(original.controllerId, true) }
        ) {
            "The knob's selected trainer changed. Check the connection before retrying Twin."
        }
    }

    private fun independentKnob(
        state: VoltraState,
        original: Handoff,
        requireUnloaded: Boolean = true,
    ): Boolean =
        state.knob?.device?.id.equals(original.knobId, true) &&
            state.knob?.status == ConnectionStatus.READY &&
            state.twin == null &&
            !state.twinPending &&
            state.trainers.size == 1 &&
            state.trainers.single().let {
                it.device.id.equals(original.controllerId, true) &&
                    it.connected &&
                    it.fresh &&
                    (!requireUnloaded || it.unloaded) &&
                    !it.pending &&
                    it.twinStatusKnown &&
                    !it.twinReported
            }

    private fun confirmedPair(state: VoltraState, first: String, second: String): Boolean =
        state.twin?.let { pair ->
            pair.health == TwinHealth.READY &&
                !state.twinPending &&
                setOf(pair.controllerId.identity(), pair.peerId.identity()) ==
                    setOf(first.identity(), second.identity()) &&
                state.trainers.size == 2 &&
                state.trainers.all {
                    it.connected &&
                        it.fresh &&
                        it.twinVerified &&
                        it.twinReported &&
                        it.twinStatusKnown
                }
        } == true

    /** A local knob exit has no TV command response, so observe its confirmed native transition. */
    private fun observeLocalTwinExit(directState: VoltraState, knobState: VoltraState?) {
        val original = handoff ?: return
        if (knobState == null || original.localExitRestoreAttempted) return
        if (
            knobState.knob?.device?.id.equals(original.knobId, true) &&
                knobState.knob?.status == ConnectionStatus.READY &&
                confirmedPair(knobState, original.controllerId, original.peer.id) &&
                knobState.twin?.controllerId.equals(original.controllerId, true)
        ) {
            original.confirmedTopologyRevision = knobState.topologyRevision
            original.confirmedKnobEpoch = knobState.telemetryEpoch
            return
        }
        if (original.confirmedTopologyRevision == null || topologyPending || localExitRestoreQueued)
            return
        // Never carry an automatic reconnect across a lost knob session or a different owner.
        if (
            !knobState.knob?.device?.id.equals(original.knobId, true) ||
                knobState.knob?.status != ConnectionStatus.READY ||
                knobState.telemetryEpoch != original.confirmedKnobEpoch ||
                knobState.trainers.none { it.device.id.equals(original.controllerId, true) } ||
                directState.trainers.isNotEmpty()
        ) {
            original.localExitRestoreAttempted = true
            return
        }
        if (!canRestoreLocalExit(original, directState, knobState)) return
        original.localExitRestoreAttempted = true
        localExitRestoreQueued = true
        scope.launch {
            // Publish pending before any direct connect can run, including on Main.immediate.
            yield()
            try {
                topology.withLock {
                    if (!canRestoreLocalExit(original, direct.state.value, knob?.state?.value))
                        return@withLock
                    topologyOperation = currentCoroutineContext().job
                    topologyPending = true
                    try {
                        refresh()
                        if (canRestoreLocalExit(original, direct.state.value, knob?.state?.value))
                            restoreDirect(original)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The direct controller reports connection failure. Further attempts must
                        // be explicit; a stream of independent snapshots must not reconnect again.
                    } finally {
                        topologyOperation = null
                        topologyPending = false
                    }
                }
            } finally {
                localExitRestoreQueued = false
                refresh()
            }
        }
    }

    private fun canRestoreLocalExit(
        original: Handoff,
        directState: VoltraState,
        knobState: VoltraState?,
    ): Boolean =
        !closed &&
            job.isActive &&
            handoff === original &&
            !recoveryBlocked &&
            directState.trainers.isEmpty() &&
            directState.twin == null &&
            !directState.twinPending &&
            directState.connectionError == null &&
            knobState != null &&
            knobState.connectionError == null &&
            knobState.telemetryEpoch == original.confirmedKnobEpoch &&
            original.confirmedTopologyRevision?.let { knobState.topologyRevision > it } == true &&
            independentKnob(knobState, original)

    private suspend fun restoreDirect(original: Handoff) {
        checkOpen()
        check(independentKnob(requireNotNull(knob).state.value, original)) {
            "Wait for the knob to confirm that Twin has ended before reconnecting directly."
        }
        check(owners(original.peer.id).isEmpty()) { "The former direct trainer has another owner." }
        direct.connect(original.peer)
        handoff = null
        recoveryBlocked = false
        reconnectingRecovery = null
        refresh()
    }

    override fun beginSet() {
        checkOpen()
        val current = refresh()
        check(current.connectionError == null) { "Resolve the device connection conflict first." }
        check(!current.twinPending) { "Wait for the Twin connection change before starting a set." }
        direct.beginSet()
        knob?.beginSet()
        refresh()
    }

    override fun endSet() {
        checkOpen()
        direct.endSet()
        knob?.endSet()
        refresh()
    }

    override fun close() {
        if (closed) return
        closed = true
        manualPreparationJob?.cancel()
        topologyOperation?.cancel()
        job.cancel()
        try {
            direct.close()
        } finally {
            try {
                knob?.close()
            } finally {
                mutableState.value =
                    VoltraState(
                        viaKnob = discoveryRoute.value == VoltraConnectionRoute.KNOB,
                        telemetryEpoch = ++telemetryEpoch,
                    )
            }
        }
    }

    private suspend fun commandOwner(deviceId: String): Owner {
        val current = commandSnapshot()
        check(current.connectionError == null) { requireNotNull(current.connectionError) }
        check(!current.twinPending) {
            "Wait for the Twin connection change before adjusting settings."
        }
        return owners(deviceId).singleOrNull() ?: error("This trainer is no longer connected.")
    }

    private suspend fun commandSnapshot(): VoltraState {
        checkOpen()
        val owner = manualPreparation ?: return refresh()
        check(currentCoroutineContext()[ManualPreparation] === owner) {
            "Wait for manual Twin preparation to finish before changing the trainers."
        }
        return preparationSnapshot()
    }

    private fun preparationSnapshot(): VoltraState =
        refresh()
            .copy(
                twinPending =
                    topologyPending ||
                        localExitRestoreQueued ||
                        direct.state.value.twinPending ||
                        knob?.state?.value?.twinPending == true
            )

    private fun lifecycleOwner(deviceId: String): Owner? {
        refresh()
        knob
            ?.state
            ?.value
            ?.knob
            ?.device
            ?.takeIf { it.id.equals(deviceId, ignoreCase = true) }
            ?.let {
                return Owner(requireNotNull(knob), VoltraConnectionRoute.KNOB, it.id)
            }
        val owners = owners(deviceId)
        check(owners.size <= 1) {
            "This trainer appears on both routes. Disconnect the knob connection to resolve it."
        }
        return owners.singleOrNull()
    }

    private fun owners(deviceId: String): List<Owner> =
        children().flatMap { (route, controller) ->
            controller.state.value.trainers
                .filter { it.device.id.equals(deviceId, ignoreCase = true) }
                .map { Owner(controller, route, it.device.id) }
        }

    private fun children(): List<Pair<VoltraConnectionRoute, VoltraController>> =
        listOfNotNull(
            VoltraConnectionRoute.DIRECT to direct,
            knob?.let { VoltraConnectionRoute.KNOB to it },
        )

    private fun controller(route: VoltraConnectionRoute): VoltraController =
        when (route) {
            VoltraConnectionRoute.DIRECT -> direct
            VoltraConnectionRoute.KNOB ->
                requireNotNull(knob) { "The knob connection is unavailable." }
        }

    private fun checkOpen() = check(!closed && job.isActive) { "Controller is closed." }

    private fun refresh(): VoltraState {
        if (closed) return mutableState.value
        var directState = direct.state.value
        val knobState = knob?.state?.value
        // A verified native pair can supersede a FAILED old direct record. A live or connecting
        // direct owner is never hidden or disconnected just because the knob names the same peer.
        knobState
            ?.twin
            ?.takeIf { confirmedPair(knobState, it.controllerId, it.peerId) }
            ?.let { pair ->
                directState.trainers
                    .filter { trainer ->
                        trainer.status in
                            setOf(ConnectionStatus.FAILED, ConnectionStatus.DISCONNECTED) &&
                            listOf(pair.controllerId, pair.peerId).any {
                                it.equals(trainer.device.id, true)
                            }
                    }
                    .forEach { direct.disconnect(it.device.id) }
                directState = direct.state.value
            }
        if (
            recoveryBlocked &&
                knobState != null &&
                handoff?.let {
                    knobState.knob?.device?.id.equals(it.knobId, true) &&
                        knobState.knob?.status == ConnectionStatus.READY &&
                        knobState.connectionError == null &&
                        confirmedPair(knobState, it.controllerId, it.peer.id) &&
                        knobState.twin?.controllerId.equals(it.controllerId, true)
                } == true
        ) {
            recoveryBlocked = false
            reconnectingRecovery = null
        }
        reconnectingRecovery?.let { original ->
            if (
                recoveryBlocked &&
                    handoff === original &&
                    knobState != null &&
                    knobState.connectionError == null &&
                    independentKnob(knobState, original, requireUnloaded = false)
            ) {
                // A new session proved that pairing did not persist. Do not silently reconnect
                // the former direct peer or replay the request; the user can reconnect it next.
                recoveryBlocked = false
                reconnectingRecovery = null
                handoff = null
            }
        }
        observeLocalTwinExit(directState, knobState)
        knobState?.knob?.device?.let { knownKnobs += it.id.identity() }
        val sources =
            directState.trainers.map { VoltraConnectionRoute.DIRECT to it } +
                knobState?.trainers.orEmpty().map { VoltraConnectionRoute.KNOB to it }
        val byIdentity = sources.groupBy { it.second.device.id.identity() }
        val duplicate = byIdentity.values.firstOrNull { it.size > 1 }
        val conflict =
            when {
                recoveryBlocked -> "Twin status is unconfirmed. Reconnect the knob before retrying."
                duplicate != null ->
                    "${duplicate.first().second.device.name} appears on both connections. Disconnect the knob before changing settings."
                byIdentity.size > 2 ->
                    "Only two VOLTRAs can be connected. Disconnect a trainer or the knob before changing settings."
                directState.connectionError != null -> directState.connectionError
                knobState?.connectionError != null -> knobState.connectionError
                else -> null
            }
        val nextContinuity =
            Continuity(childContinuity(directState), knobState?.let(::childContinuity), conflict)
        if (continuity != null && continuity != nextContinuity) telemetryEpoch++
        continuity = nextContinuity
        val boundEpochs =
            trainerEpochs.update(
                sources.associate { (route, trainer) ->
                    val child = if (route == VoltraConnectionRoute.DIRECT) directState else requireNotNull(knobState)
                    val pair = child.twin?.takeIf {
                        trainer.device.id.equals(it.controllerId, true) ||
                            trainer.device.id.equals(it.peerId, true)
                    }
                    trainer.device.id to MixedTelemetrySource(
                        route,
                        child.telemetryEpochFor(trainer.device.id),
                        trainer.telemetrySource(),
                        pair?.let { it.controllerId.identity() to it.peerId.identity() },
                        child.knob?.takeIf { route == VoltraConnectionRoute.KNOB }?.let {
                            it.device.id.identity() to it.status
                        },
                        conflict,
                    )
                }
            )
        val routes = mutableMapOf<String, VoltraConnectionRoute>()
        knobState?.knob?.twinCandidate?.let { routes[it.id] = VoltraConnectionRoute.KNOB }
        // A directly connected trainer overrides the knob's merely nearby candidate identity.
        sources.forEach { (route, trainer) ->
            routes.keys
                .firstOrNull { it.equals(trainer.device.id, ignoreCase = true) }
                ?.let(routes::remove)
            routes[trainer.device.id] = route
        }
        val trainers =
            byIdentity.values.map { group ->
                val trainer = group.first().second
                if (conflict == null && !topologyPending && !localExitRestoreQueued) trainer
                else
                    trainer.copy(
                        pending = trainer.pending || topologyPending || localExitRestoreQueued,
                        fresh = false,
                        modeChangeAllowed = false,
                        editableParameters = emptySet(),
                        activeWeightLb = null,
                        activeWeightSource = null,
                        measuredForceLb = null,
                        runtimeWireWeightLb = null,
                        resistanceTargetLb = null,
                        cableMoving = null,
                        reps = null,
                        deviceReps = null,
                        error = conflict,
                    )
            }
        val occupiedRoutes = sources.map { it.first }.distinct()
        val singleOwner =
            occupiedRoutes.singleOrNull()?.let {
                if (it == VoltraConnectionRoute.DIRECT) directState else knobState
            }
        val pair = directState.twin ?: knobState?.twin
        val state =
            VoltraState(
                trainers = trainers,
                twin =
                    if (conflict == null) pair
                    else
                        pair?.copy(
                            health = TwinHealth.DEGRADED,
                            activeWeightLb = null,
                            measuredForceLb = null,
                            cableMoving = null,
                            deviceReps = null,
                            reps = null,
                        ),
                twinPending =
                    manualPreparation != null ||
                        topologyPending ||
                        localExitRestoreQueued ||
                        directState.twinPending ||
                        knobState?.twinPending == true,
                twinSupported =
                    conflict == null &&
                        (singleOwner?.twinSupported == true ||
                            (occupiedRoutes.size == 2 &&
                                knobState?.twinSupported == true &&
                                (knobState.isSimulated ||
                                    knobState.knob?.twinIdentityGuarded == true))),
                isSimulated =
                    directState.isSimulated && (knobState == null || knobState.isSimulated),
                knob = knobState?.knob,
                viaKnob = discoveryRoute.value == VoltraConnectionRoute.KNOB,
                telemetryEpoch = telemetryEpoch,
                trainerTelemetryEpochs = boundEpochs,
                trainerRoutes = routes,
                connectionError = conflict,
                recoverableKnobId =
                    handoff?.knobId.takeIf {
                        recoveryBlocked &&
                            !topologyPending &&
                            manualPreparation == null &&
                            !localExitRestoreQueued &&
                            knobState != null &&
                            knobState.knob?.status != ConnectionStatus.CONNECTING
                    },
                topologyRevision =
                    maxOf(directState.topologyRevision, knobState?.topologyRevision ?: 0),
            )
        mutableState.value = state
        return state
    }

    private fun childContinuity(state: VoltraState) =
        ChildContinuity(
            state.telemetryEpoch,
            state.trainers
                .map {
                    TrainerContinuity(
                        it.device.id.identity(),
                        it.status,
                        it.fresh,
                        it.modeWireValue,
                    )
                }
                .sortedBy { it.id },
            state.twin?.let { it.controllerId.identity() to it.peerId.identity() },
            state.knob?.let { it.device.id.identity() to it.status },
        )

    private class ManualPreparation : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ManualPreparation>
    }

    private data class Owner(
        val controller: VoltraController,
        val route: VoltraConnectionRoute,
        val deviceId: String,
    )

    private data class Handoff(
        val controllerId: String,
        val peer: VoltraDevice,
        val knobId: String,
        var confirmedTopologyRevision: Long? = null,
        var confirmedKnobEpoch: Long? = null,
        var localExitRestoreAttempted: Boolean = false,
    )

    private data class Continuity(
        val direct: ChildContinuity,
        val knob: ChildContinuity?,
        val error: String?,
    )

    private data class ChildContinuity(
        val epoch: Long,
        val trainers: List<TrainerContinuity>,
        val twin: Pair<String, String>?,
        val knob: Pair<String, ConnectionStatus>?,
    )

    private data class TrainerContinuity(
        val id: String,
        val status: ConnectionStatus,
        val fresh: Boolean,
        val mode: Int?,
    )

    private data class MixedTelemetrySource(
        val route: VoltraConnectionRoute,
        val childEpoch: Long,
        val trainer: TrainerTelemetrySource,
        val twin: Pair<String, String>?,
        val knob: Pair<String, ConnectionStatus>?,
        val conflict: String?,
    )

    private fun String.identity() = lowercase(Locale.ROOT)
}
