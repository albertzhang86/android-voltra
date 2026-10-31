package io.github.albertzhang86.voltra.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GattWriteFailureTest {
    @Test
    fun `missing acknowledgement is a write failure rather than owner cancellation`() = runBlocking {
        val failure = runCatching {
            awaitGattWriteAcknowledgement("VOLTRA", timeoutMs = 20) { awaitCancellation() }
        }.exceptionOrNull()
        assertTrue(failure is GattWriteFailure)
        assertTrue(failure?.message.orEmpty().contains("acknowledgement timed out"))
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun `caller deadline is not misreported as the acknowledgement deadline`() = runBlocking {
        val failure = runCatching {
            withTimeout(20) {
                awaitGattWriteAcknowledgement("VOLTRA", timeoutMs = 10_000) { awaitCancellation() }
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        val report = describeGattWriteFailure("VOLTRA", failure as Exception)
        assertTrue(report.message.orEmpty().contains("cancelled"))
        assertSame(failure, report.cause)
    }

    @Test
    fun `explicit cancellation remains cancellation and retains its cause`() = runBlocking {
        val cancellation = CancellationException("owner closed")
        val entered = CompletableDeferred<Unit>()
        var failure: Exception? = null
        val owner = launch {
            try {
                awaitGattWriteAcknowledgement("VOLTRA") {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } catch (caught: Exception) {
                failure = caught
                throw caught
            }
        }
        entered.await()
        owner.cancel(cancellation)
        owner.join()
        assertTrue(owner.isCancelled)
        assertTrue(failure is CancellationException)
        assertEquals("owner closed", failure?.message)
        assertSame(failure, describeGattWriteFailure("VOLTRA", requireNotNull(failure)).cause)
    }

    @Test
    fun `native rejection preserves status and does not wait for a callback`() = runBlocking {
        var awaited = false
        val failure = runCatching {
            requireGattWriteAccepted("VOLTRA", 201)
            awaitGattWriteAcknowledgement("VOLTRA") { awaited = true }
        }.exceptionOrNull()
        assertFalse(awaited)
        assertTrue(failure?.message.orEmpty().contains("status 201"))
        assertSame(failure, describeGattWriteFailure("VOLTRA", failure as Exception))
    }

    @Test
    fun `callback failure preserves the original status and exception`() = runBlocking {
        val callbackFailure = GattWriteFailure("VOLTRA callback failed (status 133)")
        val failure = runCatching {
            awaitGattWriteAcknowledgement("VOLTRA") { throw callbackFailure }
        }.exceptionOrNull()
        assertSame(callbackFailure, failure)
        assertSame(callbackFailure, describeGattWriteFailure("VOLTRA", callbackFailure))
    }

    @Test
    fun `successful write acknowledgement completes normally`() = runBlocking {
        requireGattWriteAccepted("VOLTRA", 0)
        awaitGattWriteAcknowledgement("VOLTRA") { Unit }
        assertTrue(currentCoroutineContext().isActive)
    }
}
