package io.github.albertzhang86.voltra

import kotlinx.coroutines.*

/** Shared explicit manual action. The strict create/exit APIs never silently actuate a motor. */
internal suspend fun prepareTwinChange(
    controller: VoltraController,
    controllerId: String?,
    peerId: String?,
    create: Boolean,
    stillRelevant: () -> Boolean,
) = coroutineScope {
    val operation = currentCoroutineContext().job
    fun relevant() {
        operation.ensureActive()
        check(stillRelevant()) { "Twin preparation is no longer current." }
    }
    relevant()
    val initial = controller.state.value
    check(if (create) initial.canCreateTwinAfterUnloading else initial.canExitTwinAfterUnloading) {
        "Connect fresh trainers with confirmed unload control before changing Twin."
    }
    val expectedPair = initial.twin?.let { it.controllerId.lowercase() to it.peerId.lowercase() }
    val identities = initial.trainers.associateBy { it.device.id.lowercase() }
    check(identities.size == initial.trainers.size) { "Trainer identities are ambiguous." }
    if (create) {
        require(controllerId != null && peerId != null && !controllerId.equals(peerId, true))
        val participants =
            if (identities.size == 2) identities.keys
            else identities.keys + listOfNotNull(initial.knob?.twinCandidate?.id?.lowercase())
        require(setOf(controllerId.lowercase(), peerId.lowercase()) == participants) {
            "The selected Twin participants changed."
        }
    }
    fun validated(): VoltraState {
        relevant()
        val current = controller.state.value
        check(
            current.telemetryEpoch == initial.telemetryEpoch &&
                current.connectionError == null &&
                !current.twinPending &&
                current.knob?.device?.id.equals(initial.knob?.device?.id, true) &&
                current.trainers.map { it.device.id.lowercase() }.toSet() == identities.keys &&
                current.twin?.let { it.controllerId.lowercase() to it.peerId.lowercase() } ==
                    expectedPair
        ) {
            "Trainer connections changed during Twin preparation."
        }
        check(
            current.trainers.all { trainer ->
                val expected = identities.getValue(trainer.device.id.lowercase())
                trainer.connected &&
                    trainer.fresh &&
                    trainer.twinStatusKnown &&
                    trainer.mode == expected.mode &&
                    trainer.modeWireValue == expected.modeWireValue &&
                    current.routeFor(trainer.device.id) == initial.routeFor(expected.device.id)
            }
        ) {
            "Wait for fresh readings from the same trainers and modes before changing Twin."
        }
        return current
    }
    suspend fun awaitReady(predicate: (VoltraState) -> Boolean) {
        withTimeout(5_000) {
            while (true) {
                if (predicate(validated())) return@withTimeout
                delay(25)
            }
        }
    }
    // The public callback remains effective while an underlying native handshake is waiting.
    val watcher = launch {
        while (isActive) {
            delay(25)
            if (!stillRelevant()) {
                operation.cancel(CancellationException("Twin preparation is no longer current."))
                return@launch
            }
        }
    }
    try {
        val controlIds =
            if (create) initial.trainers.map { it.device.id }
            else listOf(requireNotNull(initial.twin).controllerId)
        for (id in controlIds) {
            val current = validated()
            val selected = current.trainers.single { it.device.id.equals(id, true) }
            check(current.trainers.none { it.pending }) {
                "A trainer operation is already pending."
            }
            if (!selected.unloaded) {
                check(selected.canUnload) { "This trainer cannot confirm an automatic unload." }
                controller.setLoaded(selected.device.id, false)
                awaitReady { state ->
                    state.trainers
                        .single { it.device.id.equals(id, true) }
                        .let { it.unloaded && !it.pending }
                }
            }
        }
        awaitReady { if (create) it.canCreateTwin else it.canExitTwin }
        validated()
        if (create) controller.createTwin(requireNotNull(controllerId), requireNotNull(peerId))
        else controller.exitTwin()
    } finally {
        watcher.cancel()
    }
}
