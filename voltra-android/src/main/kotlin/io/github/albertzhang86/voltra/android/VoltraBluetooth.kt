package io.github.albertzhang86.voltra.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

/** The consumer owns permission prompts and Bluetooth settings screens. */
object VoltraBluetooth {
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missingPermissions(context: Context) =
        permissions().filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    @android.annotation.SuppressLint("MissingPermission")
    fun problem(context: Context, scanning: Boolean = true): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            return "Bluetooth LE is unavailable on this TV."
        if (missingPermissions(context).isNotEmpty())
            return "Allow Bluetooth access to connect VOLTRA."
        return try {
            if (context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true)
                "Turn on Bluetooth to connect VOLTRA."
            else if (scanning && Build.VERSION.SDK_INT <= 30 && !locationEnabled(context))
                "Turn on Location for Bluetooth discovery on this Android version."
            else null
        } catch (_: SecurityException) {
            "Bluetooth permission was revoked."
        }
    }

    @Suppress("DEPRECATION")
    private fun locationEnabled(context: Context) =
        if (Build.VERSION.SDK_INT >= 28)
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        else
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
}
