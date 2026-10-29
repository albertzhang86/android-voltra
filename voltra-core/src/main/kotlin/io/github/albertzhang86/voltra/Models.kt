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

/** Trainer display preference; wire positions remain cm and velocities remain mm/s. */
enum class LengthUnit(val symbol: String, val speedSymbol: String) {
    CENTIMETERS("cm", "m/s"),
    INCHES("in", "in/s");

    fun displayCentimeters(value: Double): Double =
        if (this == INCHES) value / 2.54 else value

    fun displayMillimetersPerSecond(value: Int): Double =
        if (this == INCHES) value / 25.4 else value / 1000.0

    companion object {
        fun fromWire(value: Int): LengthUnit? = when (value) {
            0 -> CENTIMETERS
            1 -> INCHES
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

    /** Transport envelope, not permission to command a particular trainer. */
    val wireRange: IntRange get() = when (this) {
        WEIGHT -> 5..250
        BAND_MAXIMUM -> 15..250
        ECCENTRIC -> -245..245
        else -> range
    }

    fun validateWireValue(value: Int) {
        require(value in wireRange && (this != RETURN_SPEED || value == 0 || value >= 100)) {
            "Invalid $label."
        }
    }

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

/** The link that owns a trainer, independent of the route currently selected for discovery. */
enum class VoltraConnectionRoute {
    DIRECT,
    KNOB,
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
    val lengthUnit: LengthUnit? = null,
    val resistanceLimits: ResistanceLimits = ResistanceLimits(),
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
    /** Device-reported mode register; zero is idle, distinct from an unsupported or unread mode. */
    val modeWireValue: Int? = null,
    /** Explicit motor capability. Old bridges and third-party connectors default to unsupported. */
    val loadControlSupported: Boolean = false,
    /** Fresh device-side admission, including cable-zero verification for loading. */
    val loadAllowed: Boolean = false,
    val unloadAllowed: Boolean = false,
    /** A bridge may refresh cable evidence before checking whether one load command is safe. */
    val loadPreparationAllowed: Boolean = false,
) {
    val connected
        get() = status == ConnectionStatus.READY

    /**
     * A mode register report confirms selection independently of motor readiness.
     * Firmware 2.0 reports fitnessMode=0 while unloaded, including on its home
     * screen. Preserve it; load permission also needs mode and connector guards.
     * Both transports must include a recent mode report in [fresh].
     */
    val modeConfirmed
        get() = connected && fresh && mode != null &&
            (modeWireValue == null || modeWireValue == mode.wireValue)

    val unloaded
        get() = isFitnessReady(fitnessMode)

    val loaded
        get() = isFitnessLoaded(fitnessMode)

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
        modeConfirmed &&
            !pending &&
            twinStatusKnown &&
            (!twinReported || (twinVerified && twinRole == TwinRole.CONTROLLER)) &&
            parameter in parameters &&
            (editableParameters == null || parameter in editableParameters) &&
            parameter in Parameter.forMode(mode) &&
            (unloaded || (parameter == Parameter.WEIGHT && loaded))

    private val motorControllerReady
        get() =
            modeConfirmed &&
                !pending &&
                twinStatusKnown &&
                (!twinReported || (twinVerified && twinRole == TwinRole.CONTROLLER))

    val canLoad
        get() =
            motorControllerReady &&
                loadControlSupported &&
                loadAllowed &&
                isFitnessReady(fitnessMode)

    /** Admits preparation only; the connector must verify fresh cable/settings before loading. */
    val canPrepareLoad
        get() =
            motorControllerReady &&
                loadControlSupported &&
                loadPreparationAllowed &&
                isFitnessReady(fitnessMode)

    val canUnload
        get() =
            motorControllerReady &&
                loadControlSupported &&
                unloadAllowed &&
                (loaded || fitnessMode in listOf(0x26, 0x27))

    val canChangeMode
        get() =
            (mode != null || modeWireValue == 0) &&
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
    /**
     * Verified logical pair force; never copied to either physical member or inferred from weight.
     */
    val measuredForceLb: Double? = null,
    /** True only while the same classified pair force sample confirms loaded cable movement. */
    val cableMoving: Boolean? = null,
    /** Native logical pair count, independent of the app set's [reps] baseline. */
    val deviceReps: Int? = null,
) {
    init {
        require(!controllerId.equals(peerId, true))
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
    /** Owning route for each trainer; a knob's unconnected Twin candidate may also be included. */
    val trainerRoutes: Map<String, VoltraConnectionRoute> = emptyMap(),
    /** A topology conflict that must be resolved before settings or Twin commands are allowed. */
    val connectionError: String? = null,
    /**
     * Increases for each received topology snapshot; permits confirmation after a command fails.
     */
    val topologyRevision: Long = 0,
    /** Exact existing bridge that may reconnect to resolve an uncertain Twin outcome. */
    val recoverableKnobId: String? = null,
    /** Per-trainer source revisions, including removed identities. Unrelated trainers are isolated. */
    val trainerTelemetryEpochs: Map<String, Long> = emptyMap(),
) {
    /** A set observes its bound trainer or native pair; legacy connectors use the aggregate epoch. */
    fun telemetryEpochFor(deviceId: String, peerId: String? = null): Long {
        val ids = listOfNotNull(deviceId, peerId)
        val epochs = ids.map { id ->
            trainerTelemetryEpochs.entries.firstOrNull { it.key.equals(id, ignoreCase = true) }?.value
                ?: return telemetryEpoch
        }
        return epochs.max()
    }

    fun routeFor(deviceId: String): VoltraConnectionRoute =
        trainerRoutes.entries.firstOrNull { it.key.equals(deviceId, ignoreCase = true) }?.value
            ?: if (viaKnob) VoltraConnectionRoute.KNOB else VoltraConnectionRoute.DIRECT

    val hasMixedConnections: Boolean
        get() = trainers.map { routeFor(it.device.id) }.distinct().size > 1

    val canCreateTwin: Boolean
        get() =
            connectionError == null &&
                twinSupported &&
                twin == null &&
                !twinPending &&
                ((trainers.size == 2 &&
                    !trainers[0].device.id.equals(trainers[1].device.id, ignoreCase = true)) ||
                    (trainers.size == 1 &&
                        routeFor(trainers.single().device.id) == VoltraConnectionRoute.KNOB &&
                        knob?.twinCandidate?.let {
                            routeFor(it.id) == VoltraConnectionRoute.KNOB &&
                                !it.id.equals(trainers.single().device.id, ignoreCase = true)
                        } == true)) &&
                trainers.all {
                    it.connected &&
                        it.fresh &&
                        it.unloaded &&
                        !it.pending &&
                        it.twinStatusKnown &&
                        !it.twinReported
                }

    /** Planning readiness only; native pairing still requires [canCreateTwin] after STOP. */
    val canCreateTwinAfterUnloading: Boolean
        get() =
            canCreateTwin ||
                (trainers.isNotEmpty() &&
                    trainers.all { it.unloaded || it.canUnload } &&
                    copy(
                            trainers =
                                trainers.map { if (it.canUnload) it.copy(fitnessMode = 4) else it }
                        )
                        .canCreateTwin)

    /** A loaded native pair is unloaded through its verified controller, never its peer. */
    val canExitTwinAfterUnloading: Boolean
        get() {
            if (canExitTwin) return true
            val pair = twin ?: return false
            val controller =
                trainers.singleOrNull { it.device.id.equals(pair.controllerId, true) }
                    ?: return false
            if (!controller.canUnload) return false
            return copy(
                    trainers = trainers.map { it.copy(fitnessMode = 4) },
                    knob = knob?.copy(twinExitAllowed = true),
                )
                .canExitTwin
        }

    val canExitTwin: Boolean
        get() =
            connectionError == null &&
                twinSupported &&
                twin?.health == TwinHealth.READY &&
                !hasMixedConnections &&
                (routeFor(twin.controllerId) != VoltraConnectionRoute.KNOB ||
                    isSimulated ||
                    knob?.twinExitAllowed == true) &&
                !twinPending &&
                trainers.size == 2 &&
                !twin.controllerId.equals(twin.peerId, ignoreCase = true) &&
                trainers.count { it.device.id.equals(twin.controllerId, ignoreCase = true) } == 1 &&
                trainers.count { it.device.id.equals(twin.peerId, ignoreCase = true) } == 1 &&
                trainers.all {
                    it.connected &&
                        it.fresh &&
                        it.unloaded &&
                        !it.pending &&
                        it.twinStatusKnown &&
                        it.twinReported &&
                        it.twinVerified &&
                        it.twinRole ==
                            if (it.device.id.equals(twin.controllerId, ignoreCase = true))
                                TwinRole.CONTROLLER
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
    /** Firmware verifies the requested peer identity both at admission and execution. */
    val twinIdentityGuarded: Boolean = false,
    /** Trusted TV peer offers and explicit requests initiated by the knob are supported. */
    val mixedTwinHandoffSupported: Boolean = false,
    /** Presentation only; never substitute for trainer freshness, topology or command results. */
    val setupActivity: SetupActivity? = null,
)
