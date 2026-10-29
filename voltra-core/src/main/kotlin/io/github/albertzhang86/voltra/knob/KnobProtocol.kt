package io.github.albertzhang86.voltra.knob

import io.github.albertzhang86.voltra.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Versioned TV/knob bridge, separate from Beyond Power's trainer protocol. */
object KnobProtocol {
    const val SERVICE = "aed90001-4174-4ab6-a910-72457c592900"
    const val COMMAND = "aed90002-4174-4ab6-a910-72457c592900"
    const val UPDATES = "aed90003-4174-4ab6-a910-72457c592900"
    const val MIN_MTU = 247
    const val MAGIC = 0x4b
    const val VERSION = 2
    const val SNAPSHOT = 1
    const val SET_MODE = 2
    const val SET_PARAMETER = 3
    const val RESULT = 4
    const val CREATE_TWIN = 5
    const val EXIT_TWIN = 6
    const val TWIN_PEER_OFFER = 7
    const val TWIN_REQUEST = 8
    const val TWIN_REQUEST_STATUS = 9
    const val SET_LOADED = 10
    const val SETUP_ACTIVITY = 11
    const val MOTOR_SUPPORTED = 0x8000
    const val LOAD_ALLOWED = 0x4000
    const val UNLOAD_ALLOWED = 0x2000
    const val LOAD_PREPARATION_ALLOWED = 0x1000

    data class Snapshot(
        val epoch: Int,
        val session: Int,
        val uptime: Int,
        val slot: Int?,
        val knobBattery: Int?,
        val trainer: TrainerState?,
        val editable: Set<Parameter>,
        val modeEditable: Boolean,
        val version: Int = 1,
        val peer: VoltraDevice? = null,
        val peerBattery: Int? = null,
        // Firmware request readiness may include a loaded trainer that it can safely unload.
        // Public strict Twin actions additionally require confirmed unloaded TrainerState.
        val canCreateTwin: Boolean = false,
        val canExitTwin: Boolean = false,
        val knobUsbPowerConnected: Boolean = false,
        val twinIdentityGuarded: Boolean = false,
        val mixedTwinHandoffSupported: Boolean = false,
        val twinPeerHandoff: Boolean = false,
        val twinMeasuredForceLb: Double? = null,
        val twinDeviceReps: Int? = null,
        val setupActivitySupported: Boolean = false,
        val setupActivity: SetupActivity? = null,
    )

    data class Result(
        val epoch: Int,
        val session: Int,
        val id: Int,
        val code: Int,
        val reason: Int = 0,
    )

    fun snapshot(bytes: ByteArray): Snapshot {
        require(bytes.size >= 82 && header(bytes, SNAPSHOT)) { "Unsupported knob snapshot." }
        val version = bytes[1].toInt()
        val base = when (version) { 3 -> 142; 2 -> 134; else -> 82 }
        require(bytes.size >= base)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bytes[3].toInt() and 255
        val count = bytes[31].toInt() and 255
        require(count <= Parameter.entries.size && bytes.size == base + count * 6)
        val values = linkedMapOf<Parameter, Int>()
        repeat(count) { index ->
            val offset = base + index * 6
            val parameter =
                Parameter.entries.singleOrNull {
                    it.register == (b.getShort(offset).toInt() and 65535)
                } ?: error("Unknown knob parameter.")
            require(parameter !in values)
            val value = b.getInt(offset + 2)
            parameter.validateWireValue(value)
            values[parameter] = value
        }
        val slot = (bytes[16].toInt() and 255).takeUnless { it == 255 }
        require(slot == null || slot in 0..1)
        fun battery(offset: Int): Int? =
            (bytes[offset].toInt() and 255).let {
                require(it in 0..100 || it == 255)
                it.takeUnless { value -> value == 255 }
            }
        fun text(offset: Int, size: Int): String =
            bytes
                .copyOfRange(offset, offset + size)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
        val peer =
            if (version >= 2)
                text(114, 18)
                    .takeIf { it.isNotBlank() }
                    ?.let { address ->
                        require(
                            address.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")) &&
                                address != "00:00:00:00:00:00"
                        )
                        VoltraDevice(address, text(82, 32).ifBlank { "VOLTRA peer" })
                    }
            else null
        val paired = version >= 2 && flags and 16 != 0 && peer != null
        val verified = paired && flags and 8 != 0 && flags and 2 != 0
        if (paired) require(!peer!!.id.equals(text(64, 18), ignoreCase = true))
        val mask = b.getShort(28).toInt() and 65535
        val editable = Parameter.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet()
        require(editable.all { it in values })
        val fresh = flags and 2 != 0
        val trainer =
            slot?.let {
                val address = text(64, 18)
                require(address.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")))
                val force = b.getInt(22)
                require(force == -1 || force in 0..10000)
                val modeWireValue = bytes[19].toInt() and 255
                val mode = TrainingMode.fromWire(modeWireValue)
                val fitnessMode = (b.getShort(20).toInt() and 65535).takeUnless { it == 65535 }
                val sourceFlags = if (version >= 2) bytes[133].toInt() and 255 else 0
                val isTarget = sourceFlags and 0x40 != 0
                val isRuntime = sourceFlags and 0x10 != 0
                val isMovingForce = sourceFlags and 0x80 != 0
                // Each source flag gives offset 22 a distinct meaning.
                require(listOf(isTarget, isRuntime, isMovingForce).count { it } <= 1)
                val independentFresh =
                    fresh && flags and 1 != 0 && flags and 8 != 0 && flags and 16 == 0
                val reading = force.takeIf { it >= 0 && independentFresh }?.div(10.0)
                val target =
                    reading.takeIf { isTarget && mode != null && isFitnessLoaded(fitnessMode) }
                val movingForce =
                    reading.takeIf { isMovingForce && mode != null && isFitnessLoaded(fitnessMode) }
                val activeWeight =
                    when {
                        independentFresh && isFitnessReady(fitnessMode) -> 0.0
                        else -> target
                    }
                val deviceReps =
                    (b.getShort(26).toInt() and 65535).takeIf { it != 65535 && independentFresh }
                TrainerState(
                    device = VoltraDevice(address, text(32, 32).ifBlank { "VOLTRA ${slot + 1}" }),
                    status =
                        if (flags and 1 != 0) ConnectionStatus.READY
                        else ConnectionStatus.CONNECTING,
                    mode = mode,
                    modeWireValue = modeWireValue,
                    parameters = values,
                    batteryPercent = battery(18),
                    activeWeightLb = activeWeight,
                    activeWeightSource =
                        activeWeight?.let {
                            if (isFitnessReady(fitnessMode)) ActiveWeightSource.UNLOADED
                            else ActiveWeightSource.TARGET_FORCE
                        },
                    measuredForceLb =
                        if (isMovingForce) movingForce
                        else reading.takeIf { !isTarget && !isRuntime },
                    cableMoving = true.takeIf { movingForce != null },
                    runtimeWireWeightLb = reading.takeIf { isRuntime },
                    resistanceTargetLb = target,
                    reps = deviceReps,
                    deviceReps = deviceReps,
                    fitnessMode = fitnessMode,
                    fresh = fresh,
                    pending = flags and 4 != 0,
                    twinStatusKnown = flags and 8 != 0,
                    twinReported = flags and 16 != 0,
                    twinVerified = verified,
                    twinRole = if (paired) TwinRole.CONTROLLER else null,
                    editableParameters = editable,
                    modeChangeAllowed = bytes[30] == 1.toByte(),
                    loadControlSupported = version >= 2 && mask and MOTOR_SUPPORTED != 0,
                    loadAllowed =
                        version >= 2 && mask and MOTOR_SUPPORTED != 0 && mask and LOAD_ALLOWED != 0,
                    unloadAllowed =
                        version >= 2 &&
                            mask and MOTOR_SUPPORTED != 0 &&
                            mask and UNLOAD_ALLOWED != 0,
                    loadPreparationAllowed =
                        version >= 2 &&
                            mask and MOTOR_SUPPORTED != 0 &&
                            mask and LOAD_PREPARATION_ALLOWED != 0,
                    lengthUnit = if (version >= 3) LengthUnit.fromWire(bytes[134].toInt() and 255) else null,
                    resistanceLimits = if (version >= 3) ResistanceLimits(
                        b.getShort(135).toInt() and 65535,
                        LimitSource.entries.getOrNull(bytes[137].toInt() and 255) ?: error("Unknown capacity source."),
                    ) else ResistanceLimits(),
                    weightUnit =
                        if (version >= 2) {
                            // Optional v2 extension; zero means older firmware / unknown unit.
                            WeightUnit.fromWire(((bytes[133].toInt() ushr 2) and 3) - 1)
                        } else null,
                )
            }
        // v2 already carries classified force/count fields and both native identities. Older
        // readers safely ignore them while paired. Keep logical pair telemetry out of physical
        // TrainerState sensors; only the firmware may classify a native combined measurement.
        val pairReady =
            paired &&
                trainer?.let {
                    it.connected &&
                        it.fresh &&
                        it.twinVerified &&
                        !it.pending &&
                        it.mode != null &&
                        (isFitnessReady(it.fitnessMode) || it.loaded)
                } == true
        val pairForce =
            b.getInt(22)
                .takeIf {
                    pairReady &&
                        it >= 0 &&
                        bytes[133].toInt() and 0x80 != 0 &&
                        isFitnessLoaded(trainer?.fitnessMode)
                }
                ?.div(10.0)
        val pairReps = (b.getShort(26).toInt() and 65535).takeIf { pairReady && it != 65535 }
        return Snapshot(
            b.getInt(4),
            b.getInt(8),
            b.getInt(12),
            slot,
            battery(17),
            trainer,
            editable,
            bytes[30] == 1.toByte(),
            version,
            peer,
            if (version >= 2) battery(132) else null,
            version >= 2 && bytes[133].toInt() and 1 != 0 && peer != null && !paired,
            version >= 2 && bytes[133].toInt() and 2 != 0 && verified,
            version >= 2 && bytes[133].toInt() and 0x20 != 0,
            version >= 2 && flags and 0x20 != 0,
            version >= 2 && flags and 0x40 != 0,
            version >= 2 && flags and 0x80 != 0,
            pairForce,
            pairReps,
            version >= 3 && bytes[138].toInt() and 1 != 0,
            if (version >= 3 && bytes[138].toInt() and 1 != 0)
                SetupActivity.fromWire(bytes[139].toInt() and 255) else null,
        )
    }

    /** Expiring display heartbeat, independent of the motor-command sequence. */
    fun setupActivity(snapshot: Snapshot, activity: SetupActivity?, deadline: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC.toByte()); put(VERSION.toByte()); put(SETUP_ACTIVITY.toByte()); put(0)
            putInt(snapshot.epoch); putInt(snapshot.session); putInt(deadline)
            putShort(0); putShort((activity?.wireValue ?: 0).toShort()); putInt(0)
        }.array()

    fun command(
        snapshot: Snapshot,
        id: Int,
        mode: TrainingMode? = null,
        parameter: Parameter? = null,
        value: Int = 0,
    ): ByteArray {
        require(id in 1..65535 && ((mode != null) xor (parameter != null)))
        parameter?.let { snapshot.trainer?.resistanceLimits?.validate(it, value) ?: it.validate(value) }
        return ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(MAGIC.toByte())
                put(minOf(snapshot.version, VERSION).toByte())
                put((if (mode != null) SET_MODE else SET_PARAMETER).toByte())
                put(0)
                putInt(snapshot.epoch)
                putInt(snapshot.session)
                putInt(snapshot.uptime + 2000)
                putShort(id.toShort())
                putShort((parameter?.register ?: 0).toShort())
                putInt(mode?.wireValue ?: value)
            }
            .array()
    }

    fun motorCommand(snapshot: Snapshot, id: Int, loaded: Boolean): ByteArray {
        require(snapshot.version >= VERSION && id in 1..65535)
        val trainer = requireNotNull(snapshot.trainer)
        require(if (loaded) trainer.canLoad || trainer.canPrepareLoad else trainer.canUnload)
        return ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(MAGIC.toByte())
                put(VERSION.toByte())
                put(SET_LOADED.toByte())
                put(0)
                putInt(snapshot.epoch)
                putInt(snapshot.session)
                putInt(snapshot.uptime + 2000)
                putShort(id.toShort())
                putShort(0)
                putInt(if (loaded) 1 else 0)
            }
            .array()
    }

    fun twinCommand(snapshot: Snapshot, id: Int, create: Boolean): ByteArray {
        require(snapshot.version >= 2 && id in 1..65535)
        require(if (create) snapshot.canCreateTwin else snapshot.canExitTwin)
        require(
            !snapshot.twinPeerHandoff ||
                (snapshot.twinIdentityGuarded && snapshot.mixedTwinHandoffSupported)
        )
        return ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(MAGIC.toByte())
                put(VERSION.toByte())
                put((if (create) CREATE_TWIN else EXIT_TWIN).toByte())
                put((if (create && snapshot.twinPeerHandoff) 1 else 0).toByte())
                putInt(snapshot.epoch)
                putInt(snapshot.session)
                putInt(snapshot.uptime + 2000)
                putShort(id.toShort())
                if (create && snapshot.twinIdentityGuarded) {
                    val address = requireNotNull(snapshot.peer).id.split(':')
                    require(address.size == 6 && address.all { it.length == 2 })
                    address.forEach { put(it.toInt(16).toByte()) }
                } else {
                    putShort(0)
                    putInt(0)
                }
            }
            .array()
    }

    fun result(bytes: ByteArray): Result {
        require(bytes.size == 16 && header(bytes, RESULT))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val code = bytes[3].toInt() and 255
        require(code in 0..3)
        return Result(
            b.getInt(4),
            b.getInt(8),
            b.getShort(12).toInt() and 65535,
            code,
            b.getShort(14).toInt() and 65535,
        )
    }

    fun twinRequest(bytes: ByteArray): KnobTwinRequest {
        require(bytes.size == 20 && header(bytes, TWIN_REQUEST) && bytes[3] == 0.toByte())
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val id = b.getShort(12).toInt() and 65535
        require(id != 0 && bytes.copyOfRange(14, 20).any { it != 0.toByte() })
        val peer = bytes.copyOfRange(14, 20).joinToString(":") { "%02X".format(it.toInt() and 255) }
        return KnobTwinRequest(b.getInt(4), b.getInt(8), id, peer)
    }

    fun twinPeerOffer(
        snapshot: Snapshot,
        peer: VoltraDevice?,
        ready: Boolean = true,
        deadline: Int = snapshot.uptime + 1500,
    ): ByteArray =
        bridgeCommand(snapshot, 0, TWIN_PEER_OFFER).apply {
            this[3] = (if (peer != null && ready) 1 else 0).toByte()
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(12, deadline)
            peer
                ?.id
                ?.split(':')
                ?.also { require(it.size == 6 && it.all { part -> part.length == 2 }) }
                ?.forEachIndexed { index, part -> this[18 + index] = part.toInt(16).toByte() }
        }

    fun twinRequestStatus(
        snapshot: Snapshot,
        id: Int,
        request: KnobTwinRequest,
        status: KnobTwinRequestStatus,
    ): ByteArray =
        bridgeCommand(snapshot, id, TWIN_REQUEST_STATUS).also {
            ByteBuffer.wrap(it)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(18, status.wireValue.toShort())
                .putInt(20, request.id)
        }

    private fun bridgeCommand(snapshot: Snapshot, id: Int, type: Int): ByteArray {
        require(snapshot.mixedTwinHandoffSupported)
        require(if (type == TWIN_PEER_OFFER) id == 0 else id in 1..65535)
        return ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(MAGIC.toByte())
                put(VERSION.toByte())
                put(type.toByte())
                put(0)
                putInt(snapshot.epoch)
                putInt(snapshot.session)
                putInt(snapshot.uptime + 2000)
                putShort(id.toShort())
                putShort(0)
                putInt(0)
            }
            .array()
    }

    fun header(bytes: ByteArray, type: Int): Boolean =
        bytes.size >= 4 &&
            bytes[0] == MAGIC.toByte() &&
            (bytes[1] == VERSION.toByte() || bytes[1] == 1.toByte() || (type == SNAPSHOT && bytes[1] == 3.toByte())) &&
            bytes[2] == type.toByte()
}
