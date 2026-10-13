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

    init {
        scope.launch {
            while (isActive) {
                delay(250)
                tick++
                mutableState.update { old ->
                    old.copy(
                        trainers =
                            old.trainers.mapIndexed { index, trainer ->
                                val base = trainer.setWeightLb ?: 30
                                trainer.copy(
                                    activeWeightLb = base * (0.75 + 0.25 * sin(tick / 3.0 + index)),
                                    reps = tick / 20,
                                )
                            }
                    )
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
                            reps = 0,
                            fitnessMode = 4,
                            fresh = true,
                            twinStatusKnown = true,
                            isSimulated = true,
                        )
            )
        }
    }

    override fun disconnect(deviceId: String) {
        mutableState.update {
            it.copy(trainers = it.trainers.filterNot { s -> s.device.id == deviceId }, twin = null)
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
        commands.withLock {
            check(job.isActive)
            val pair = state.value.twin
            require(pair == null || deviceId == pair.controllerId)
            require(state.value.trainers.any { it.device.id == deviceId })
            val ids = if (pair == null) setOf(deviceId) else setOf(pair.controllerId, pair.peerId)
            val updated =
                state.value.trainers
                    .filter { it.device.id in ids }
                    .associate { it.device.id to transform(it) }
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
                check(job.isActive && state.value.trainers.map { it.device.id }.containsAll(ids))
                mutableState.update {
                    it.copy(
                        trainers =
                            it.trainers.map { s ->
                                updated[s.device.id]?.copy(
                                    activeWeightLb = s.activeWeightLb,
                                    reps = s.reps,
                                    pending = false,
                                ) ?: s
                            }
                    )
                }
            } finally {
                mutableState.update {
                    it.copy(trainers = it.trainers.map { s -> s.copy(pending = false) })
                }
            }
        }

    override suspend fun createTwin(controllerId: String, peerId: String) =
        commands.withLock {
            check(job.isActive && state.value.twin == null)
            require(
                controllerId != peerId &&
                    state.value.trainers
                        .map { it.device.id }
                        .containsAll(listOf(controllerId, peerId))
            )
            check(state.value.trainers.all { it.canChangeMode })
            mutableState.update { it.copy(twinPending = true) }
            try {
                delay(300)
                check(job.isActive && state.value.trainers.size == 2)
                val controller = state.value.trainers.first { it.device.id == controllerId }
                mutableState.update {
                    it.copy(
                        twin = TwinPair(controllerId, peerId),
                        trainers =
                            it.trainers.map { s ->
                                s.copy(mode = controller.mode, parameters = controller.parameters)
                            },
                    )
                }
            } finally {
                mutableState.update { it.copy(twinPending = false) }
            }
        }

    override suspend fun exitTwin() =
        commands.withLock {
            check(job.isActive && state.value.twin != null)
            mutableState.update { it.copy(twinPending = true) }
            try {
                delay(300)
                mutableState.update { it.copy(twin = null) }
            } finally {
                mutableState.update { it.copy(twinPending = false) }
            }
        }

    override fun beginSet() {
        tick = 0
        mutableState.update { it.copy(trainers = it.trainers.map { s -> s.copy(reps = 0) }) }
    }

    override fun close() {
        job.cancel()
        mutableState.value = VoltraState(isSimulated = true)
    }

    companion object {
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
