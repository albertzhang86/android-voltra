package io.github.albertzhang86.voltra.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GattEstablishmentTest {
    @Test
    fun `early establishment failure closes old link and waits before one fresh attempt`() = runBlocking {
        for (phase in listOf(GattConnectionPhase.CONNECTING, GattConnectionPhase.MTU)) {
            for (status in listOf(133, 0x3e)) {
                val events = mutableListOf<String>()
                val first = Attempt(1, events) { throw failure(status, phase) }
                val second = Attempt(2, events)
                val result =
                    establishGattConnection(
                        retryEstablishment = true,
                        createAttempt = { number ->
                            events += "create $number"
                            if (number == 1) first else second
                        },
                        beforeRetry = {
                            assertTrue(first.closed)
                            events += "wait"
                        },
                    )

                assertSame(second, result)
                assertEquals(
                    listOf("create 1", "await 1", "close 1", "wait", "create 2", "await 2"),
                    events,
                )
                assertFalse(second.closed)
            }
        }
    }

    @Test
    fun `repeated establishment failure stops after second link and closes both`() = runBlocking {
        val events = mutableListOf<String>()
        val attempts = mutableListOf<Attempt>()
        val original = failure(133, GattConnectionPhase.CONNECTING)
        val result = runCatching {
            establishGattConnection(
                retryEstablishment = true,
                createAttempt = { number ->
                    Attempt(number, events) { throw original }.also { attempts += it }
                },
                beforeRetry = { events += "wait" },
            )
        }
        assertSame(original, result.exceptionOrNull())
        assertEquals(2, attempts.size)
        assertTrue(attempts.all { it.closed })
        assertEquals(1, events.count { it == "wait" })
    }

    @Test
    fun `permission authentication service subscription and later disconnect failures do not retry`() = runBlocking {
        val failures =
            listOf(
                SecurityException("Permission revoked"),
                IllegalStateException("Service unavailable"),
                IllegalStateException("Subscription failed"),
                failure(5, GattConnectionPhase.CONNECTING),
                failure(15, GattConnectionPhase.MTU),
                failure(8, GattConnectionPhase.CONNECTING),
                failure(0, GattConnectionPhase.CONNECTING),
            ) +
                GattConnectionPhase.entries
                    .filter { it !in listOf(GattConnectionPhase.CONNECTING, GattConnectionPhase.MTU) }
                    .flatMap { phase -> listOf(failure(133, phase), failure(0x3e, phase)) }
        for (failure in failures) {
            val events = mutableListOf<String>()
            val attempts = mutableListOf<Attempt>()
            val result = runCatching {
                establishGattConnection(
                    retryEstablishment = true,
                    createAttempt = { number ->
                        Attempt(number, events) { throw failure }.also { attempts += it }
                    },
                    beforeRetry = { error("Must not retry") },
                )
            }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(1, attempts.size)
            assertTrue(attempts.single().closed)
        }
    }

    @Test
    fun `knob policy never retries even an early transient failure`() = runBlocking {
        var attempts = 0
        val failed = Attempt(1, mutableListOf()) { throw failure(133, GattConnectionPhase.CONNECTING) }
        val result = runCatching {
            establishGattConnection(
                retryEstablishment = false,
                createAttempt = {
                    attempts++
                    failed
                },
                beforeRetry = { error("Must not retry knob") },
            )
        }
        assertTrue(result.exceptionOrNull() is GattConnectionFailure)
        assertEquals(1, attempts)
        assertTrue(failed.closed)
    }

    @Test
    fun `cancellation during retry delay does not open another GATT link`() = runBlocking {
        var attempts = 0
        val waiting = CompletableDeferred<Unit>()
        val first = Attempt(1, mutableListOf()) { throw failure(133, GattConnectionPhase.MTU) }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            establishGattConnection(
                retryEstablishment = true,
                createAttempt = {
                    attempts++
                    first
                },
                beforeRetry = {
                    waiting.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
        assertTrue(first.closed)
    }

    @Test
    fun `cancellation while second GATT is opening closes it without another attempt`() = runBlocking {
        val opening = CompletableDeferred<Unit>()
        val attempts = mutableListOf<Attempt>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            establishGattConnection(
                retryEstablishment = true,
                createAttempt = { number ->
                    Attempt(number, mutableListOf()) {
                        if (number == 1) throw failure(133, GattConnectionPhase.CONNECTING)
                        opening.complete(Unit)
                        awaitCancellation()
                    }.also { attempts += it }
                },
                beforeRetry = {},
            )
        }
        opening.await()
        job.cancelAndJoin()
        assertEquals(2, attempts.size)
        assertTrue(attempts.all { it.closed })
    }

    @Test
    fun `one enclosing deadline includes retry delay rather than resetting the budget`() = runBlocking {
        var attempts = 0
        val first = Attempt(1, mutableListOf()) { throw failure(133, GattConnectionPhase.MTU) }
        val result = runCatching {
            withTimeout(50) {
                establishGattConnection(
                    retryEstablishment = true,
                    createAttempt = {
                        attempts++
                        first
                    },
                    beforeRetry = { awaitCancellation() },
                )
            }
        }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(1, attempts)
        assertTrue(first.closed)
    }

    @Test
    fun `ready connection returns immediately and never enters recovery`() = runBlocking {
        var attempts = 0
        val link = Attempt(1, mutableListOf())
        val ready =
            establishGattConnection(
                retryEstablishment = true,
                createAttempt = {
                    attempts++
                    link
                },
                beforeRetry = { error("Ready connection must not retry") },
            )
        assertSame(link, ready)
        assertEquals(1, attempts)
        assertFalse(link.closed)
        // A later owner close does not restart this completed establishment operation.
        ready.close()
        yield()
        assertEquals(1, attempts)
    }

    @Test
    fun `cancellation after ready but before dispatched handoff closes the retained link`() = runBlocking {
        val events = mutableListOf<String>()
        val link = Attempt(1, events)
        val receiving = async {
            val receivingJob = currentCoroutineContext().job
            withGattConnectionOwnership { retain ->
                withContext(Dispatchers.Default) {
                    val ready = establishGattConnection(
                        retryEstablishment = true,
                        createAttempt = { link.also(retain) },
                    )
                    events += "ready returned"
                    // The attempt-level try/catch has finished. Cancellation wins only when
                    // withContext tries to dispatch this successfully created link back.
                    receivingJob.cancel()
                    ready
                }
            }
        }
        receiving.join()
        assertTrue(receiving.isCancelled)
        assertTrue(link.closed)
        assertEquals(listOf("await 1", "ready returned", "close 1"), events)
    }

    @Test
    fun `ownership handoff leaves successful returned link open`() = runBlocking {
        val link = Attempt(1, mutableListOf())
        val result = withGattConnectionOwnership { retain ->
            withContext(Dispatchers.Default) {
                establishGattConnection(
                    retryEstablishment = true,
                    createAttempt = { link.also(retain) },
                )
            }
        }
        assertSame(link, result)
        assertFalse(link.closed)
    }

    private fun failure(status: Int, phase: GattConnectionPhase) =
        GattConnectionFailure(status, phase, "Connection failed")

    private class Attempt(
        private val number: Int,
        private val events: MutableList<String>,
        private val ready: suspend () -> Unit = {},
    ) : GattConnectionAttempt {
        var closed = false
            private set

        override suspend fun awaitReady() {
            events += "await $number"
            ready()
        }

        override fun close() {
            closed = true
            events += "close $number"
        }
    }
}
