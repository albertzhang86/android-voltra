package io.github.albertzhang86.voltra

data class VoltraDevice(val id: String, val name: String)

/** Display preference only. Weight settings and live force remain in protocol pounds. */
enum class WeightUnit(val symbol: String) {
    POUNDS("lb"),
    KILOGRAMS("kg");

    companion object {
        fun fromWire(value: Int): WeightUnit? =
            when (value) {
                0 -> POUNDS
                1 -> KILOGRAMS
                else -> null
            }
    }
}

enum class TrainingMode(val wireValue: Int, val label: String) {
    WEIGHT(1, "Weight training"),
    BAND(2, "Resistance band"),
    DAMPER(4, "Damper"),
    ISOKINETIC(7, "Isokinetic");

    companion object {
        fun fromWire(value: Int) = entries.find { it.wireValue == value }
    }
}

/** Values use protocol units; display conversion belongs to the consumer. */
enum class Parameter(
    val register: Int,
    val width: Int,
    val label: String,
    val range: IntRange,
    val step: Int = 1,
) {
    WEIGHT(0x3e86, 2, "Set weight", 5..200, 5),
    CHAINS(0x3e87, 2, "Chains", 0..100, 5),
    INVERSE_CHAINS(0x53b0, 1, "Inverse chains", 0..1),
    ECCENTRIC(0x3e88, 2, "Eccentric weight", -195..195, 5),
    BAND_MAXIMUM(0x5362, 2, "Maximum weight", 15..200, 5),
    DAMPER_LEVEL(0x5103, 1, "Damper level", 0..9),
    SPEED(0x5350, 4, "Pull speed", 100..2000, 100),
    RETURN_TYPE(0x5410, 1, "Return type", 0..1),
    RETURN_SPEED(0x5411, 2, "Return speed", 0..2000, 100),
    RETURN_WEIGHT(0x5412, 2, "Return weight", 5..100, 5),
    LIMIT(0x5413, 2, "Weight limit", 5..200, 5);

    fun validate(value: Int) {
        require(value in range && (this != RETURN_SPEED || value == 0 || value >= 100)) {
            "Invalid $label."
        }
    }

    companion object {
        val damperFactors = listOf(5, 8, 11, 14, 17, 21, 30, 33, 41, 50)

        fun forMode(mode: TrainingMode?): List<Parameter> =
            when (mode) {
                TrainingMode.WEIGHT -> listOf(WEIGHT, CHAINS, INVERSE_CHAINS, ECCENTRIC)
                TrainingMode.BAND -> listOf(BAND_MAXIMUM)
                TrainingMode.DAMPER -> listOf(DAMPER_LEVEL)
                TrainingMode.ISOKINETIC ->
                    listOf(SPEED, RETURN_TYPE, RETURN_SPEED, RETURN_WEIGHT, LIMIT)
                null -> emptyList()
            }
    }
}

enum class ConnectionStatus {
    CONNECTING,
    READY,
    DISCONNECTED,
    FAILED,
}

enum class TwinRole {
    CONTROLLER,
    PEER,
}

enum class TwinHealth {
    READY,
    DEGRADED,
}

/** Source of active resistance. Legacy measurement values remain for source compatibility. */
enum class ActiveWeightSource {
    CABLE_FORCE,
    RUNTIME_WIRE_WEIGHT,
    TARGET_FORCE,
    UNLOADED,
}

data class TrainerState(
    val device: VoltraDevice,
    val status: ConnectionStatus = ConnectionStatus.CONNECTING,
    val mode: TrainingMode? = null,
    val parameters: Map<Parameter, Int> = emptyMap(),
    val batteryPercent: Int? = null,
    /** Fresh device-reported resistance target, or zero for an explicitly unloaded trainer. */
    val activeWeightLb: Double? = null,
    val reps: Int? = null,
    val fitnessMode: Int? = null,
    val fresh: Boolean = false,
    val pending: Boolean = false,
    val twinReported: Boolean = false,
    val twinStatusKnown: Boolean = false,
    val error: String? = null,
    val isSimulated: Boolean = false,
    /** Optional bridge capabilities narrow the normal device-state gates. */
    val editableParameters: Set<Parameter>? = null,
    val modeChangeAllowed: Boolean = true,
    /** Set only after the connector verifies both identities and the live topology. */
    val twinRole: TwinRole? = null,
    val twinVerified: Boolean = false,
    /** Unit displayed by the trainer, or by the knob when connected through its bridge. */
    val weightUnit: WeightUnit? = null,
    val activeWeightSource: ActiveWeightSource? = null,
    /** Measured cable tension; can remain nonzero while the trainer is unloaded. */
    val measuredForceLb: Double? = null,
    /** Coarse measured wire-weight register, independent of the resistance target. */
    val runtimeWireWeightLb: Double? = null,
    /** Fresh B4 target while explicitly loaded; never inferred from the base setting. */
    val resistanceTargetLb: Double? = null,
    /** Fresh loaded cable motion paired with measured force; null when not classified. */
    val cableMoving: Boolean? = null,
    /** Confirmed trainer count, never rebased by app beginSet; null when unavailable or stale. */
    val deviceReps: Int? = null,
) {
    val connected
        get() = status == ConnectionStatus.READY

    val unloaded
        get() = fitnessMode == 4 || fitnessMode == 0

    val loaded
        get() = fitnessMode == 5 || fitnessMode == 21

    val setWeightLb: Int?
        get() =
            parameters[
                    when (mode) {
                        TrainingMode.BAND -> Parameter.BAND_MAXIMUM
                        TrainingMode.ISOKINETIC -> Parameter.LIMIT
                        else -> Parameter.WEIGHT
                    }]
                .takeUnless { mode == TrainingMode.DAMPER }

    fun canEdit(parameter: Parameter): Boolean =
        connected &&
            fresh &&
            !pending &&
            twinStatusKnown &&
            (!twinReported || (twinVerified && twinRole == TwinRole.CONTROLLER)) &&
            parameter in parameters &&
            (editableParameters == null || parameter in editableParameters) &&
            parameter in Parameter.forMode(mode) &&
            (unloaded || (parameter == Parameter.WEIGHT && fitnessMode in listOf(5, 21)))

    val canChangeMode
        get() =
            (mode != null || fitnessMode == 0) &&
                modeChangeAllowed &&
                connected &&
                fresh &&
                unloaded &&
                !pending &&
                twinStatusKnown &&
                (!twinReported || (twinVerified && twinRole == TwinRole.CONTROLLER))
}

/** A confirmed pair has an explicit controller; two independent devices never imply Twin mode. */
data class TwinPair(
    val controllerId: String,
    val peerId: String,
    val health: TwinHealth = TwinHealth.READY,
    /** Logical pair telemetry. Never add or double unclassified native force readings. */
    val activeWeightLb: Double? = null,
    val reps: Int? = null,
    val setWeightLb: Int? = null,
) {
    init {
        require(controllerId != peerId)
    }
}

data class VoltraState(
    val trainers: List<TrainerState> = emptyList(),
    val twin: TwinPair? = null,
    val twinPending: Boolean = false,
    val twinSupported: Boolean = false,
    val isSimulated: Boolean = false,
    val knob: KnobState? = null,
    val viaKnob: Boolean = false,
    /** Monotonic within a controller when device telemetry continuity resets; not app beginSet. */
    val telemetryEpoch: Long = 0,
) {
    val canCreateTwin: Boolean
        get() =
            twinSupported &&
                twin == null &&
                !twinPending &&
                ((trainers.size == 2 && trainers.map { it.device.id }.distinct().size == 2) ||
                    (viaKnob && trainers.size == 1 && knob?.twinCandidate != null)) &&
                trainers.all {
                    it.connected &&
                        it.fresh &&
                        it.unloaded &&
                        !it.pending &&
                        it.twinStatusKnown &&
                        !it.twinReported
                }

    val canExitTwin: Boolean
        get() =
            twinSupported &&
                twin?.health == TwinHealth.READY &&
                (!viaKnob || isSimulated || knob?.twinExitAllowed == true) &&
                !twinPending &&
                trainers.size == 2 &&
                trainers.map { it.device.id }.toSet() == setOf(twin.controllerId, twin.peerId) &&
                trainers.all {
                    it.connected &&
                        it.fresh &&
                        it.unloaded &&
                        !it.pending &&
                        it.twinStatusKnown &&
                        it.twinReported &&
                        it.twinVerified &&
                        it.twinRole ==
                            if (it.device.id == twin.controllerId) TwinRole.CONTROLLER
                            else TwinRole.PEER
                }
}

data class KnobState(
    val device: VoltraDevice,
    val status: ConnectionStatus,
    val batteryPercent: Int? = null,
    val error: String? = null,
    /** Candidate for pairing, not a connected trainer. */
    val twinCandidate: VoltraDevice? = null,
    val twinExitAllowed: Boolean = false,
    /** USB-host power detected; not proof of battery charging or charge completion. */
    val usbPowerConnected: Boolean = false,
)
