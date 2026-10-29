package io.github.albertzhang86.voltra

/** Capacity evidence belongs to a connection, never to a profile or a motor-state heuristic. */
enum class LimitSource { CONSERVATIVE, DEVICE }

data class ResistanceLimits(
    val maximumWeightLb: Int = 200,
    val source: LimitSource = LimitSource.CONSERVATIVE,
) {
    init {
        require(maximumWeightLb in 5..250)
        require(source != LimitSource.CONSERVATIVE || maximumWeightLb == 200)
    }

    /** Only Weight and Band share the expanded capacity; Isokinetic has its own limits. */
    fun range(parameter: Parameter): IntRange = when (parameter) {
        Parameter.WEIGHT -> 5..maximumWeightLb
        Parameter.BAND_MAXIMUM -> 15..maximumWeightLb
        Parameter.ECCENTRIC -> (5 - maximumWeightLb)..(maximumWeightLb - 5)
        else -> parameter.range
    }

    fun validate(parameter: Parameter, value: Int) {
        parameter.validateWireValue(value)
        require(value in range(parameter)) { "${parameter.label} exceeds this VOLTRA's range." }
    }
}
