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
) : VoltraController {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(VoltraState(viaKnob = true))
    override val state = mutableState.asStateFlow()
    private val commands = Mutex()
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
                            trainers =
                                it.trainers.map { trainer ->
                                    trainer.copy(fresh = false, activeWeightLb = null, reps = null)
                                }
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
        check(state.value.knob == null) { "Disconnect the current knob first." }
        val token = ++generation
        mutableState.value =
            VoltraState(knob = KnobState(device, ConnectionStatus.CONNECTING), viaKnob = true)
        connection =
            scope.launch {
                var opened: VoltraLink? = null
                var firstReadingTimeout: Job? = null
                try {
                    val connected = transport.connect(device)
                    opened = connected
                    ensureActive()
                    link = connected
                    firstReadingTimeout = launch {
                        // GATT subscription can finish before Android's pairing prompt does.
                        // Allow time to enter the code shown on the knob.
                        delay(90_000)
                        if (generation == token && snapshot == null) connected.close()
                    }
                    connected.packets.collect { bytes ->
                        if (generation != token) return@collect
                        when {
                            KnobProtocol.header(bytes, KnobProtocol.SNAPSHOT) ->
                                receive(KnobProtocol.snapshot(bytes))
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
                    error("Knob disconnected.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation != token) return@launch
                    pending?.completion?.completeExceptionally(failure)
                    mutableState.update {
                        it.copy(
                            trainers = emptyList(),
                            knob =
                                it.knob?.copy(
                                    status = ConnectionStatus.FAILED,
                                    error = failure.message ?: "Knob connection failed.",
                                ),
                        )
                    }
                } finally {
                    firstReadingTimeout?.cancel()
                    opened?.close()
                    if (link === opened) link = null
                }
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
        mutableState.update {
            it.copy(
                trainers = listOfNotNull(trainer),
                knob =
                    it.knob?.copy(
                        status = ConnectionStatus.READY,
                        batteryPercent = update.knobBattery,
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
        link?.close()
        link = null
        pending?.completion?.cancel()
        pending = null
        snapshot = null
        snapshots.value = null
        baseline = null
        nextId = 1
        mutableState.value = VoltraState(viaKnob = true)
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
    ) =
        commands.withLock {
            check(job.isActive)
            val current = requireNotNull(snapshot) { "Connect the knob first." }
            val trainer =
                state.value.trainers.singleOrNull { it.device.id == deviceId }
                    ?: error("Select the knob's active trainer.")
            check(now() - receivedAt <= 2000 && trainer.fresh) { "Wait for fresh knob readings." }
            check(
                if (mode != null) trainer.canChangeMode
                else trainer.canEdit(requireNotNull(parameter))
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

    override suspend fun createTwin(controllerId: String, peerId: String): Unit =
        error("Native Twin setup is not supported by the knob bridge.")

    override suspend fun exitTwin(): Unit = error("Exit Twin on the trainers.")

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
