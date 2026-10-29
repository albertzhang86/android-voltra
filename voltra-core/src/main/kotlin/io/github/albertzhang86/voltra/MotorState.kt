package io.github.albertzhang86.voltra

/**
 * Observed 0x3e89 motor words, independent of the selected training mode.
 * VTR-161230 Firmware 2.0 native load/unload capture (2026-10-28): 1/0.
 * Legacy captures: 5/4, 21 loaded, 0x0104 unloaded, 0x26/0x27 armed.
 * Preserve the raw word. Never strip unknown bits to infer load permission.
 * Unloaded alone is insufficient: controllers also verify mode, topology,
 * configuration, cable-zero readbacks, and command confirmation.
 */
internal object MotorState {
    fun unloaded(raw: Int?) = raw == 0 || raw == 4 || raw == 0x0104
    fun loaded(raw: Int?) = raw == 1 || raw == 5 || raw == 21
    fun armed(raw: Int?) = raw == 0x26 || raw == 0x27

    fun commandValue(raw: Int?, load: Boolean): Int? = when {
        load && unloaded(raw) -> if (raw == 0) 1 else 5
        load -> null
        raw == 0 || raw == 1 -> 0
        unloaded(raw) || loaded(raw) || armed(raw) -> 4
        else -> null
    }
}

internal fun isFitnessReady(fitnessMode: Int?) = MotorState.unloaded(fitnessMode)
internal fun isFitnessLoaded(fitnessMode: Int?) = MotorState.loaded(fitnessMode)
