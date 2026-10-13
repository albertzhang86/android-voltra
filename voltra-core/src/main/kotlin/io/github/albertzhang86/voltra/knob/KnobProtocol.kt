package io.github.albertzhang86.voltra.knob

import io.github.albertzhang86.voltra.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Versioned TV/knob bridge, separate from Beyond Power's trainer protocol. */
object KnobProtocol {
    const val SERVICE = "aed90001-4174-4ab6-a910-72457c592900"
    const val COMMAND = "aed90002-4174-4ab6-a910-72457c592900"
    const val UPDATES = "aed90003-4174-4ab6-a910-72457c592900"
    const val MIN_MTU = 185
    const val MAGIC = 0x4b
    const val VERSION = 1
    const val SNAPSHOT = 1
    const val SET_MODE = 2
    const val SET_PARAMETER = 3
    const val RESULT = 4

    data class Snapshot(
        val epoch: Int,
        val session: Int,
        val uptime: Int,
        val slot: Int?,
        val knobBattery: Int?,
        val trainer: TrainerState?,
        val editable: Set<Parameter>,
        val modeEditable: Boolean,
    )

    data class Result(val epoch: Int, val session: Int, val id: Int, val code: Int)

    fun snapshot(bytes: ByteArray): Snapshot {
        require(bytes.size >= 82 && header(bytes, SNAPSHOT)) { "Unsupported knob snapshot." }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bytes[3].toInt() and 255
        val count = bytes[31].toInt() and 255
        require(count <= Parameter.entries.size && bytes.size == 82 + count * 6)
        val values = linkedMapOf<Parameter, Int>()
        repeat(count) { index ->
            val offset = 82 + index * 6
            val parameter =
                Parameter.entries.singleOrNull {
                    it.register == (b.getShort(offset).toInt() and 65535)
                } ?: error("Unknown knob parameter.")
            require(parameter !in values)
            val value = b.getInt(offset + 2)
            parameter.validate(value)
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
                TrainerState(
                    device = VoltraDevice(address, text(32, 32).ifBlank { "VOLTRA ${slot + 1}" }),
                    status =
                        if (flags and 1 != 0) ConnectionStatus.READY
                        else ConnectionStatus.CONNECTING,
                    mode = TrainingMode.fromWire(bytes[19].toInt() and 255),
                    parameters = values,
                    batteryPercent = battery(18),
                    activeWeightLb = force.takeIf { it >= 0 && fresh }?.div(10.0),
                    reps = (b.getShort(26).toInt() and 65535).takeIf { it != 65535 && fresh },
                    fitnessMode = (b.getShort(20).toInt() and 65535).takeUnless { it == 65535 },
                    fresh = fresh,
                    pending = flags and 4 != 0,
                    twinStatusKnown = flags and 8 != 0,
                    twinReported = flags and 16 != 0,
                    editableParameters = editable,
                    modeChangeAllowed = bytes[30] == 1.toByte(),
                )
            }
        return Snapshot(
            b.getInt(4),
            b.getInt(8),
            b.getInt(12),
            slot,
            battery(17),
            trainer,
            editable,
            bytes[30] == 1.toByte(),
        )
    }

    fun command(
        snapshot: Snapshot,
        id: Int,
        mode: TrainingMode? = null,
        parameter: Parameter? = null,
        value: Int = 0,
    ): ByteArray {
        require(id in 1..65535 && ((mode != null) xor (parameter != null)))
        parameter?.validate(value)
        return ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(MAGIC.toByte())
                put(VERSION.toByte())
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

    fun result(bytes: ByteArray): Result {
        require(bytes.size == 16 && header(bytes, RESULT))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val code = bytes[3].toInt() and 255
        require(code in 0..3)
        return Result(b.getInt(4), b.getInt(8), b.getShort(12).toInt() and 65535, code)
    }

    fun header(bytes: ByteArray, type: Int): Boolean =
        bytes.size >= 4 &&
            bytes[0] == MAGIC.toByte() &&
            bytes[1] == VERSION.toByte() &&
            bytes[2] == type.toByte()
}
