package io.github.albertzhang86.voltra.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.albertzhang86.voltra.VoltraDevice
import io.github.albertzhang86.voltra.VoltraLink
import io.github.albertzhang86.voltra.VoltraTransport
import io.github.albertzhang86.voltra.knob.KnobProtocol
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** Discovers only the TV bridge service; ordinary trainer advertisements never match. */
@SuppressLint("MissingPermission")
class AndroidKnobTransport(context: Context) : VoltraTransport {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("voltra_knob", Context.MODE_PRIVATE)
    private val transport =
        AndroidVoltraTransport(
            context,
            GattProfile(
                service = UUID.fromString(KnobProtocol.SERVICE),
                write = UUID.fromString(KnobProtocol.COMMAND),
                notify = UUID.fromString(KnobProtocol.UPDATES),
                namePrefix = null,
                minimumMtu = KnobProtocol.MIN_MTU,
                connectTimeoutMs = 90_000,
                label = "Knob",
                renewNotificationSubscription = true,
                retryEstablishment = false,
            ),
        )

    /** A reconnect target is useful only while Android still holds its Bluetooth bond. */
    fun rememberedDevice(): VoltraDevice? =
        runCatching {
                val address = preferences.getString("address", null) ?: return@runCatching null
                if (!isBonded(address)) return@runCatching null
                VoltraDevice(address, preferences.getString("name", null) ?: "Voltra Remote")
            }
            .getOrNull()

    /** Call after the first validated bridge snapshot, never just after discovering a name. */
    fun rememberDevice(device: VoltraDevice) {
        // Connection metadata is optional; a permission change must not break a live session.
        runCatching {
            if (isBonded(device.id)) {
                preferences
                    .edit()
                    .putString("address", device.id)
                    .putString("name", device.name)
                    .apply()
            }
        }
    }

    /**
     * Removes only this app's reconnect target. Android's Bluetooth bond is managed in Settings.
     */
    fun forgetDevice(deviceId: String) {
        if (preferences.getString("address", null) == deviceId) {
            preferences.edit().remove("address").remove("name").apply()
        }
    }

    private fun isBonded(address: String): Boolean =
        BluetoothAdapter.checkBluetoothAddress(address) &&
            context
                .getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.getRemoteDevice(address)
                ?.bondState == BluetoothDevice.BOND_BONDED

    override fun scan(): Flow<List<VoltraDevice>> = transport.scan()

    override suspend fun connect(device: VoltraDevice): VoltraLink {
        val link = transport.connect(device)
        return object : VoltraLink {
            private var lastSnapshotTraceAt = 0L
            private var lastSnapshotState: List<Byte>? = null

            override val packets =
                link.packets.onEach { frame ->
                    if (Log.isLoggable(TRACE_TAG, Log.DEBUG)) {
                        val now = SystemClock.elapsedRealtime()
                        val snapshot = KnobProtocol.header(frame, KnobProtocol.SNAPSHOT)
                        // Always capture readiness/session changes, even if they recover inside
                        // the one-second periodic sample. Telemetry values remain rate-limited.
                        val state = if (snapshot && frame.size >= 82) {
                            (3..11).map { frame[it] } + listOf(frame[16], frame[19], frame[20], frame[21]) +
                                frame.slice(64 until minOf(frame.size, 134))
                        } else null
                        if (!snapshot || state != lastSnapshotState || now - lastSnapshotTraceAt >= 1000) {
                            trace("RX", frame)
                            if (snapshot) {
                                lastSnapshotTraceAt = now
                                lastSnapshotState = state
                            }
                        }
                    }
                }

            override suspend fun write(frame: ByteArray) {
                trace("TX", frame)
                link.write(frame)
            }

            override fun close() = link.close()

            override suspend fun revoke() = link.revoke()
        }
    }

    /** Opt-in local protocol diagnostics; disabled unless this Android log tag is enabled. */
    private fun trace(direction: String, frame: ByteArray) {
        if (Log.isLoggable(TRACE_TAG, Log.DEBUG)) {
            Log.d(
                TRACE_TAG,
                "$direction ${frame.joinToString("") { "%02x".format(it.toInt() and 255) }}",
            )
        }
    }

    private companion object {
        const val TRACE_TAG = "VoltraBridge"
    }
}
