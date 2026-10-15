package io.github.albertzhang86.voltra.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.albertzhang86.voltra.VoltraDevice
import io.github.albertzhang86.voltra.VoltraLink
import io.github.albertzhang86.voltra.VoltraTransport
import io.github.albertzhang86.voltra.knob.KnobProtocol
import java.util.UUID
import kotlinx.coroutines.flow.Flow

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

    override suspend fun connect(device: VoltraDevice): VoltraLink = transport.connect(device)
}
