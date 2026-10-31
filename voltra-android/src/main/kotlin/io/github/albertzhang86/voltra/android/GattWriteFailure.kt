package io.github.albertzhang86.voltra.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal class GattWriteFailure(message: String, cause: Exception? = null) :
    IllegalStateException(message, cause)

/** Only this acknowledgement deadline becomes a write failure; caller cancellation propagates. */
internal suspend fun awaitGattWriteAcknowledgement(
    label: String,
    timeoutMs: Long = 3_000,
    awaitAcknowledgement: suspend () -> Unit,
) {
    val acknowledged = withTimeoutOrNull(timeoutMs) {
        awaitAcknowledgement()
        true
    }
    if (acknowledged != true) {
        throw GattWriteFailure(
            "$label Bluetooth write acknowledgement timed out after ${timeoutMs}ms. Reconnect to continue."
        )
    }
}

internal fun requireGattWriteAccepted(label: String, status: Int) {
    // BluetoothStatusCodes.SUCCESS is zero. Keep the native code instead of reducing it to Boolean.
    if (status != 0) {
        throw GattWriteFailure(
            "$label Bluetooth write was rejected by Android (status $status). Reconnect to continue."
        )
    }
}

internal fun describeGattWriteFailure(label: String, cause: Exception): GattWriteFailure =
    when (cause) {
        is GattWriteFailure -> cause
        is CancellationException -> GattWriteFailure(
            "$label Bluetooth write was cancelled before completion. Reconnect to continue.", cause
        )
        is SecurityException -> GattWriteFailure("Bluetooth permission was revoked.", cause)
        else -> GattWriteFailure(
            "$label Bluetooth write failed (${cause.javaClass.simpleName}). Reconnect to continue.", cause
        )
    }
