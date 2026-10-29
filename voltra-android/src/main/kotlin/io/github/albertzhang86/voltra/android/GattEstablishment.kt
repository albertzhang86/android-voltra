package io.github.albertzhang86.voltra.android

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal enum class GattConnectionPhase {
    CONNECTING,
    MTU,
    SERVICES,
    SUBSCRIPTION,
    READY,
    REVOKING,
    CLOSED,
}

/** A connection-state callback failure, distinct from authentication or later GATT operations. */
internal class GattConnectionFailure(
    val status: Int,
    val phase: GattConnectionPhase,
    message: String,
) : IllegalStateException(message) {
    // Android's generic GATT error and HCI connection-failed-to-establish. An ACL can appear
    // connected briefly before establishment fails; recovery stops once discovery begins.
    val canRetryEstablishment: Boolean
        get() =
            phase in setOf(GattConnectionPhase.CONNECTING, GattConnectionPhase.MTU) &&
                status in setOf(133, 0x3e)
}

internal interface GattConnectionAttempt {
    suspend fun awaitReady()

    fun close()
}

/** Retain the native resource until dispatch and timeout boundaries have returned it to the caller. */
internal suspend fun <T : GattConnectionAttempt> withGattConnectionOwnership(
    open: suspend (retain: (T) -> Unit) -> T
): T {
    var retained: T? = null
    try {
        val connection = open { retained = it }
        currentCoroutineContext().ensureActive()
        return connection
    } catch (failure: Exception) {
        retained?.close()
        throw failure
    }
}

/**
 * One fresh GATT attempt may recover an early radio establishment failure. The caller applies one
 * timeout around this entire operation. No protocol writes occur until the returned link is ready.
 */
internal suspend fun <T : GattConnectionAttempt> establishGattConnection(
    retryEstablishment: Boolean,
    createAttempt: (Int) -> T,
    beforeRetry: suspend () -> Unit = { delay(1_000) },
    onRetry: (GattConnectionFailure) -> Unit = {},
): T {
    suspend fun attempt(number: Int): T {
        currentCoroutineContext().ensureActive()
        val connection = createAttempt(number)
        try {
            connection.awaitReady()
            return connection
        } catch (failure: Exception) {
            connection.close()
            throw failure
        }
    }

    return try {
        attempt(1)
    } catch (failure: GattConnectionFailure) {
        if (!retryEstablishment || !failure.canRetryEstablishment) throw failure
        currentCoroutineContext().ensureActive()
        onRetry(failure)
        beforeRetry()
        currentCoroutineContext().ensureActive()
        attempt(2)
    }
}
