package io.github.albertzhang86.voltra.testing

import io.github.albertzhang86.voltra.*
import kotlin.math.sin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit demo only. Never selected as a fallback after a real Bluetooth error. */
class SimulatedVoltraController(parentScope: CoroutineScope) : VoltraController {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState =
        MutableStateFlow(VoltraState(twinSupported = true, isSimulated = true))
    override val state = mutableState.asStateFlow()
    private var tick = 0
    private val commands = Mutex()
    private var generation = 0

    init {
        scope.launch {
            while (isActive) {
                delay(250)
                tick++
                mutableState.update { old ->
                    val trainers =
                        old.trainers.mapIndexed { index, trainer ->
                            val base = trainer.setWeightLb ?: 30
                            val ready = trainer.connected && trainer.fresh && old.twin?.health != TwinHealth.DEGRADED
                            val target = (base * (0.75 + 0.25 * sin(tick / 3.0 + index))).takeIf { ready && trainer.loaded }
                            trainer.copy(
                                activeWeightLb = if (ready && trainer.fitnessMode == 4) 0.0 else target,
                                activeWeightSource = when {
                                    ready && trainer.fitnessMode == 4 -> ActiveWeightSource.UNLOADED
                                    target != null -> ActiveWeightSource.TARGET_FORCE
                                    else -> null
                                },
                                resistanceTargetLb = target,
                                reps =
                                    (tick / 20).takeIf {
                                        trainer.connected &&
                                            trainer.fresh &&
                                            old.twin?.health != TwinHealth.DEGRADED
                                    },
                            )
                        }
                    old.copy(trainers = trainers, twin = telemetry(old.twin, trainers))
                }
            }
        }
    }

    override fun scan(): Flow<List<VoltraDevice>> = flowOf(devices)

    override suspend fun connect(device: VoltraDevice) {
        check(job.isActive)
        require(device in devices)
        require(
            state.value.trainers.size < 2 && state.value.trainers.none { it.device.id == device.id }
        )
        mutableState.update {
            it.copy(
                trainers =
                    it.trainers +
                        TrainerState(
                            device,
                            ConnectionStatus.READY,
                            mode = TrainingMode.WEIGHT,
                            parameters = defaults,
                            batteryPercent = if (device == devices.first()) 86 else 72,
                            activeWeightLb = 0.0,
                            activeWeightSource = ActiveWeightSource.UNLOADED,
                            reps = 0,
                            fitnessMode = 4,
                            fresh = true,
                            twinStatusKnown = true,
                            isSimulated = true,
                        )
            )
        }
        mutableState.update { old ->
            val pair = old.twin
            if (pair == null || old.trainers.size != 2 || old.trainers.any { !it.connected }) old
            else
                old.copy(
                    twin = pair.copy(health = TwinHealth.READY),
                    trainers =
                        old.trainers.map {
                            it.copy(
                                twinReported = true,
                                twinVerified = true,
                                twinRole =
                                    if (it.device.id == pair.controllerId) TwinRole.CONTROLLER
                                    else TwinRole.PEER,
                            )
                        },
                )
        }
    }

    override fun disconnect(deviceId: String) {
        generation++
        mutableState.update {
            val paired =
                it.twin?.let { pair -> deviceId in listOf(pair.controllerId, pair.peerId) } == true
            it.copy(
                trainers =
                    it.trainers
                        .filterNot { s -> s.device.id == deviceId }
                        .map { trainer ->
                            if (paired)
                                trainer.copy(
                                    twinVerified = false,
                                    activeWeightLb = null,
                                    activeWeightSource = null,
                                    resistanceTargetLb = null,
                                    reps = null,
                                )
                            else trainer
                        },
                twin =
                    if (paired)
                        it.twin?.copy(
                            health = TwinHealth.DEGRADED,
                            activeWeightLb = null,
                            reps = null,
                            setWeightLb = null,
                        )
                    else it.twin,
            )
        }
    }

    override fun forget(deviceId: String) {
        generation++
        mutableState.update { current ->
            val pair = current.twin?.takeIf { deviceId in listOf(it.controllerId, it.peerId) }
            val ids = pair?.let { setOf(it.controllerId, it.peerId) } ?: setOf(deviceId)
            current.copy(
                trainers = current.trainers.filterNot { it.device.id in ids },
                twin = current.twin.takeIf { pair == null },
                twinPending = false,
            )
        }
    }

    override suspend fun setMode(deviceId: String, mode: TrainingMode) =
        edit(deviceId) {
            check(it.copy(pending = false).canChangeMode)
            it.copy(mode = mode)
        }

    override suspend fun setParameter(deviceId: String, parameter: Parameter, value: Int) {
        parameter.validate(value)
        edit(deviceId) {
            check(it.copy(pending = false).canEdit(parameter))
            val parameters = it.parameters + (parameter to value)
            if (parameter == Parameter.WEIGHT || parameter == Parameter.ECCENTRIC) {
                require(
                    parameters.getValue(Parameter.WEIGHT) +
                        parameters.getValue(Parameter.ECCENTRIC) in 5..200
                )
            }
            it.copy(parameters = parameters)
        }
    }

    private suspend fun edit(deviceId: String, transform: (TrainerState) -> TrainerState) =
        serialCommand {
            check(job.isActive)
            val pair = state.value.twin
            check(pair == null || pair.health == TwinHealth.READY) {
                "Restore both Twin connections first."
            }
            require(pair == null || deviceId == pair.controllerId)
            require(state.value.trainers.any { it.device.id == deviceId })
            val ids = if (pair == null) setOf(deviceId) else setOf(pair.controllerId, pair.peerId)
            val token = generation
            val changed = transform(state.value.trainers.single { it.device.id == deviceId })
            val updated =
                state.value.trainers
                    .filter { it.device.id in ids }
                    .associate { trainer ->
                        trainer.device.id to
                            trainer.copy(mode = changed.mode, parameters = changed.parameters)
                    }
            mutableState.update {
                it.copy(
                    trainers =
                        it.trainers.map { s ->
                            if (s.device.id in ids) s.copy(pending = true) else s
                        }
                )
            }
            try {
                delay(180)
                check(
                    job.isActive &&
                        token == generation &&
                        state.value.trainers.map { it.device.id }.containsAll(ids)
                )
                mutableState.update {
                    it.copy(
                        trainers =
                            it.trainers.map { s ->
                                updated[s.device.id]?.copy(
                                    activeWeightLb = s.activeWeightLb,
                                    activeWeightSource = s.activeWeightSource,
                                    resistanceTargetLb = s.resistanceTargetLb,
                                    reps = s.reps,
                                    pending = false,
                                ) ?: s
                            }
                    )
                }
                mutableState.update { it.copy(twin = telemetry(it.twin, it.trainers)) }
            } finally {
                mutableState.update {
                    it.copy(trainers = it.trainers.map { s -> s.copy(pending = false) })
                }
            }
        }

    override suspend fun createTwin(controllerId: String, peerId: String) = serialCommand {
        check(job.isActive && state.value.canCreateTwin)
        require(
            controllerId != peerId &&
                state.value.trainers.map { it.device.id }.containsAll(listOf(controllerId, peerId))
        )
        val token = generation
        mutableState.update { it.copy(twinPending = true) }
        try {
            delay(300)
            check(job.isActive && token == generation && state.value.trainers.size == 2)
            val controller = state.value.trainers.first { it.device.id == controllerId }
            mutableState.update {
                it.copy(
                    twin = TwinPair(controllerId, peerId),
                    trainers =
                        it.trainers.map { s ->
                            s.copy(
                                mode = controller.mode,
                                parameters = controller.parameters,
                                twinReported = true,
                                twinVerified = true,
                                twinRole =
                                    if (s.device.id == controllerId) TwinRole.CONTROLLER
                                    else TwinRole.PEER,
                            )
                        },
                )
            }
            mutableState.update { it.copy(twin = telemetry(it.twin, it.trainers)) }
        } finally {
            mutableState.update { it.copy(twinPending = false) }
        }
    }

    override suspend fun exitTwin() = serialCommand {
        check(job.isActive && state.value.canExitTwin) {
            "Both Twin trainers must be connected, fresh and unloaded."
        }
        val token = generation
        mutableState.update { it.copy(twinPending = true) }
        try {
            delay(300)
            check(job.isActive && token == generation)
            mutableState.update {
                it.copy(
                    twin = null,
                    trainers =
                        it.trainers.map { trainer ->
                            trainer.copy(
                                twinReported = false,
                                twinRole = null,
                                twinVerified = false,
                            )
                        },
                )
            }
        } finally {
            mutableState.update { it.copy(twinPending = false) }
        }
    }

    private suspend fun <T> serialCommand(block: suspend () -> T): T {
        val token = generation
        return commands.withLock {
            check(token == generation) { "Device connection changed." }
            block()
        }
    }

    override fun beginSet() {
        tick = 0
        mutableState.update {
            it.copy(
                trainers = it.trainers.map { s ->
                    s.copy(reps = 0, fitnessMode = 5, activeWeightLb = null,
                        activeWeightSource = null, resistanceTargetLb = null)
                },
                twin =
                    it.twin?.let { pair ->
                        pair.copy(reps = if (pair.health == TwinHealth.READY) 0 else null)
                    },
            )
        }
    }

    override fun close() {
        generation++
        job.cancel()
        mutableState.value = VoltraState(isSimulated = true)
    }

    companion object {
        private fun telemetry(pair: TwinPair?, trainers: List<TrainerState>): TwinPair? {
            if (pair == null) return null
            val controller = trainers.find { it.device.id == pair.controllerId }
            val peer = trainers.find { it.device.id == pair.peerId }
            if (
                pair.health != TwinHealth.READY ||
                    controller?.fresh != true ||
                    peer?.fresh != true ||
                    !controller.connected ||
                    !peer.connected
            )
                return pair.copy(
                    health = TwinHealth.DEGRADED,
                    activeWeightLb = null,
                    reps = null,
                    setWeightLb = null,
                )
            return pair.copy(
                activeWeightLb =
                    controller.activeWeightLb?.let { a -> peer.activeWeightLb?.let { a + it } },
                reps = controller.reps,
                setWeightLb = controller.setWeightLb?.let { a -> peer.setWeightLb?.let { a + it } },
            )
        }

        val devices =
            listOf(
                VoltraDevice("demo-left", "VOLTRA Left"),
                VoltraDevice("demo-right", "VOLTRA Right"),
            )
        val defaults =
            mapOf(
                Parameter.WEIGHT to 30,
                Parameter.CHAINS to 0,
                Parameter.INVERSE_CHAINS to 0,
                Parameter.ECCENTRIC to 0,
                Parameter.BAND_MAXIMUM to 40,
                Parameter.DAMPER_LEVEL to 2,
                Parameter.SPEED to 500,
                Parameter.RETURN_TYPE to 0,
                Parameter.RETURN_SPEED to 0,
                Parameter.RETURN_WEIGHT to 20,
                Parameter.LIMIT to 100,
            )
    }
}
