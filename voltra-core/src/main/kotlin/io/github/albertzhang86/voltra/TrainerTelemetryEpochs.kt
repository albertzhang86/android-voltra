package io.github.albertzhang86.voltra

import java.util.Locale

/** Producer-side revisions preserve a source change even when StateFlow conflates its snapshots. */
internal class TrainerTelemetryEpochs<T> {
    private var revision = 0L
    private var previous = emptyMap<String, T>()
    private val epochs = mutableMapOf<String, Long>()

    fun update(sources: Map<String, T>): Map<String, Long> {
        val current = sources.mapKeys { it.key.lowercase(Locale.ROOT) }
        val changed = (previous.keys + current.keys).filter { previous[it] != current[it] }
        if (changed.isNotEmpty()) {
            revision++
            changed.forEach { epochs[it] = revision }
        }
        previous = current
        // Keep removed identities as tombstones, so reconnecting cannot revive an old token.
        return epochs.toMap()
    }
}

/** Readiness and native topology define a telemetry source; ordinary settings/load edits do not. */
internal data class TrainerTelemetrySource(
    val status: ConnectionStatus,
    val fresh: Boolean,
    val mode: TrainingMode?,
    val modeWire: Int?,
    val twinKnown: Boolean,
    val twinReported: Boolean,
    val twinVerified: Boolean,
    val twinRole: TwinRole?,
)

internal fun TrainerState.telemetrySource() =
    TrainerTelemetrySource(
        status,
        fresh,
        mode,
        modeWireValue,
        twinStatusKnown,
        twinReported,
        twinVerified,
        twinRole,
    )
