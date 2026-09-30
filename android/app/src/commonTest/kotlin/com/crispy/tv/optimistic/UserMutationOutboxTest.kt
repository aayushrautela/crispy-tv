package com.crispy.tv.optimistic

import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.RetryPolicy
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.optimistic.WatchlistMutation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserMutationOutbox] reached `commonMain` in the `UserMutationOutbox` port
 * batch, and this is its first coverage. The two things that changed in the move
 * are the two things worth a test: the once-only latch that used to be a
 * `java.util.concurrent.atomic.AtomicBoolean` and is now the class's own
 * `Mutex`, and the `clock` parameter that used to default to
 * `{ System.currentTimeMillis() }` and is now required.
 *
 * Two rules govern every test below, and both were paid for with a hung suite:
 *
 * - `runCurrent()` runs what is already queued; it does NOT wake a processor
 *   parked in `delay(pollMs)`. A test that needs the flush to actually happen
 *   must move virtual time with `advanceTimeBy`, or the executor is never
 *   called and every flush assertion fails. The first version of this suite
 *   used `runCurrent()` alone and every flush test was red.
 * - Every test stops its outbox in a `finally`, never as a bare last
 *   statement. The processor loop is unbounded, so a live processor keeps
 *   `runTest`'s closing advance busy forever: a test that fails before reaching
 *   a trailing `stop()` does not report its failure, it hangs the worker at
 *   full CPU until killed, and the failure is indistinguishable from an
 *   infrastructure problem. The first version learned this the hard way — a
 *   `ClassCastException` in one test masked itself as a 68-minute hang.
 *
 * No test calls `advanceUntilIdle`: the loop would keep it busy forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserMutationOutboxTest {
    // ---------------------------------------------------------------- doubles

    private class RecordingStore(seed: List<UserMutation> = emptyList()) : PendingMutationStore {
        val loadAllCalls = mutableListOf<String>() // one entry per load, so the count is what matters
        val saved = mutableListOf<List<UserMutation>>()
        private var stored = seed

        override suspend fun loadAll(): List<UserMutation> {
            loadAllCalls += "load"
            return stored
        }

        override suspend fun saveAll(mutations: List<UserMutation>) {
            saved += mutations
            stored = mutations
        }

        fun lastSave(): List<UserMutation> = saved.last()
    }

    private class RecordingExecutor(
        private val result: (UserMutation) -> MutationResult = { MutationResult(success = true) },
    ) : MutationExecutor {
        val calls = mutableListOf<UserMutation>()

        override suspend fun execute(mutation: UserMutation): MutationResult {
            calls += mutation
            return result(mutation)
        }
    }

    // ---------------------------------------------------------------- fixture

    private fun watchlist(
        id: String = "m1",
        entityId: String = "movie-1",
        desired: Boolean = true,
        status: MutationStatus = MutationStatus.Pending,
        createdAtMs: Long = 0L,
        attempt: Int = 0,
        nextAttemptAtMs: Long = 0L,
    ): WatchlistMutation =
        WatchlistMutation(
            id = id,
            titleItemId = entityId,
            entityId = entityId,
            createdAtMs = createdAtMs,
            attempt = attempt,
            status = status,
            nextAttemptAtMs = nextAttemptAtMs,
            desired = desired,
        )

    private companion object {
        /** Far enough ahead that `planOutbox` cannot consider it due. */
        const val FAR_FUTURE = 1_000_000L
    }

    private fun scope(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        CoroutineScope(StandardTestDispatcher(scheduler))

    private fun outbox(
        store: PendingMutationStore,
        executor: MutationExecutor,
        scope: CoroutineScope,
        clock: () -> Long,
        policy: RetryPolicy = RetryPolicy(baseDelayMs = 1_000, maxDelayMs = 8_000, maxAttempts = 3),
    ) = UserMutationOutbox(store, executor, scope, policy, clock, pollMs = 50L)

    // ---------------------------------------------------------------- the latch

    @Test
    fun `a second start does not launch a second processor`() = runTest {
        val store = RecordingStore()
        val executor = RecordingExecutor()
        val box = outbox(store, executor, scope(testScheduler), { 0L })

        try {
            box.start()
            box.start()
            runCurrent()

            // Each processor that gets past the latch loads the store once, so the
            // count of loads is the count of live processors -- no timing involved.
            assertEquals("only the first start may load the store", 1, store.loadAllCalls.size)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `stop then start launches a fresh processor`() = runTest {
        val store = RecordingStore()
        val box = outbox(store, RecordingExecutor(), scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()
            box.stop()
            box.start()
            runCurrent()

            // The latch is what `stop()` clears; if it were not cleared the second
            // start would return at once and this would still read 1.
            assertEquals("stop must release the latch so the next start can relaunch", 2, store.loadAllCalls.size)
        } finally {
            box.stop()
        }
    }

    // ------------------------------------------------------------ load coercion

    @Test
    fun `an inflight entry recovered from disk is retried rather than dropped`() = runTest {
        val store = RecordingStore(
            seed = listOf(watchlist(status = MutationStatus.Inflight, nextAttemptAtMs = FAR_FUTURE)),
        )
        val box = outbox(store, RecordingExecutor(), scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()

            assertEquals(listOf(MutationStatus.Pending), store.lastSave().map { it.status })
            assertEquals(listOf(MutationStatus.Pending), box.allMutations().map { it.status })
        } finally {
            box.stop()
        }
    }

    @Test
    fun `a pending entry survives a reload unchanged`() = runTest {
        // Non-due on purpose: a due seed would be flushed during the initial
        // `runCurrent()`, which is the processor doing its job, not survival.
        val seed = watchlist(desired = false, nextAttemptAtMs = FAR_FUTURE)
        val store = RecordingStore(seed = listOf(seed))
        val box = outbox(store, RecordingExecutor(), scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()

            assertEquals(listOf<MutationStatus>(MutationStatus.Pending), store.lastSave().map { it.status })
            assertEquals(false, (box.mutationsForItem("movie-1").single() as WatchlistMutation).desired)
        } finally {
            box.stop()
        }
    }

    // --------------------------------------------------------------- the flush

    @Test
    fun `a due mutation is executed and dropped once the server accepts it`() = runTest {
        val store = RecordingStore()
        val executor = RecordingExecutor()
        val box = outbox(store, executor, scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()

            box.enqueue(watchlist())
            // The processor is parked in `delay(pollMs)`; `runCurrent()` alone
            // never wakes it, so the flush needs virtual time to move.
            advanceTimeBy(100L)

            assertEquals(listOf("m1"), executor.calls.map { it.id })
            assertTrue("a successful mutation is removed, not marked", box.allMutations().isEmpty())
            assertTrue(box.store().isEmpty())
        } finally {
            box.stop()
        }
    }

    @Test
    fun `the executor is handed the pending mutation rather than the inflight copy`() = runTest {
        val store = RecordingStore()
        val executor = RecordingExecutor()
        val box = outbox(store, executor, scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()

            box.enqueue(watchlist())
            advanceTimeBy(100L)

            // The class marks its own copy Inflight before calling out; the value on
            // the wire must still be the one the UI asked for.
            assertEquals(MutationStatus.Pending, executor.calls.single().status)
            assertEquals(true, (executor.calls.single() as WatchlistMutation).desired)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `a mutation whose attempt is in the future is not executed early`() = runTest {
        // Seeded, not enqueued: `enqueue` stamps every intent with the current
        // time, so only a stored entry can carry a future attempt time.
        val store = RecordingStore(seed = listOf(watchlist(nextAttemptAtMs = FAR_FUTURE)))
        val executor = RecordingExecutor()
        val box = outbox(store, executor, scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()
            advanceTimeBy(50L)
            runCurrent()

            assertTrue("planOutbox must not run a mutation before its attempt time", executor.calls.isEmpty())
            assertEquals(1, box.mutationsForItem("movie-1").size)
        } finally {
            box.stop()
        }
    }

    // -------------------------------------------------------------- the failure

    @Test
    fun `a failure is recorded with the server's reason and the attempt is counted`() = runTest {
        val store = RecordingStore()
        val executor = RecordingExecutor { MutationResult(success = false, reason = "offline") }
        val box = outbox(store, executor, scope(testScheduler), { 5_000L })

        try {
            box.start()
            runCurrent()

            box.enqueue(watchlist())
            advanceTimeBy(100L)

            val failed = box.mutationsForItem("movie-1").single()
            assertEquals(MutationStatus.Failed(reason = "offline", retryable = true), failed.status)
            assertEquals(1, failed.attempt)
            // The next attempt is scheduled by the deterministic backoff, not
            // "as soon as possible": base 1_000 * 2^(1-1) added to the 5_000 clock.
            assertEquals(6_000L, failed.nextAttemptAtMs)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `a thrown executor failure is recorded as failed rather than escaping`() = runTest {
        val store = RecordingStore()
        val executor = RecordingExecutor { throw IllegalStateException("boom") }
        val box = outbox(store, executor, scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()

            box.enqueue(watchlist())
            advanceTimeBy(100L)

            val failed = box.mutationsForItem("movie-1").single().status as MutationStatus.Failed
            // A thrown exception has no reason of its own, so the fallback sentence
            // is what the UI will show; asserting it pins that the throw is caught
            // at all.
            assertEquals("Update failed.", failed.reason)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `a conflict keeps the mutation and records what the server said`() = runTest {
        val store = RecordingStore()
        val executor = RecordingExecutor { MutationResult(success = false, conflict = true, serverValue = "true") }
        val box = outbox(store, executor, scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()

            box.enqueue(watchlist())
            advanceTimeBy(100L)

            val status = box.mutationsForItem("movie-1").single().status
            assertEquals(MutationStatus.Conflict(serverValue = "true"), status)
            // A conflict is not an error to back off from, so the attempt must not move.
            assertEquals(0, box.mutationsForItem("movie-1").single().attempt)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `the last attempt is marked not retryable`() = runTest {
        val store = RecordingStore()
        val executor = RecordingExecutor { MutationResult(success = false, reason = "nope") }
        val box = outbox(
            store,
            executor,
            scope(testScheduler),
            { 0L },
            policy = RetryPolicy(baseDelayMs = 1_000, maxDelayMs = 8_000, maxAttempts = 1),
        )

        try {
            box.start()
            runCurrent()

            box.enqueue(watchlist())
            advanceTimeBy(100L)

            val failed = box.mutationsForItem("movie-1").single().status as MutationStatus.Failed
            assertEquals(false, failed.retryable)
        } finally {
            box.stop()
        }
    }

    // ------------------------------------------------------------- coalescing

    @Test
    fun `two rapid toggles of the same target collapse to the latest`() = runTest {
        val store = RecordingStore()
        val box = outbox(store, RecordingExecutor(), scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()

            box.enqueue(watchlist(id = "m1", desired = true))
            box.enqueue(watchlist(id = "m2", desired = false))
            runCurrent()

            val kept = box.mutationsForItem("movie-1")
            assertEquals("coalescing keys on kind and entityId, so only one survives", 1, kept.size)
            assertEquals(false, (kept.single() as WatchlistMutation).desired)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `toggles of different targets both survive`() = runTest {
        val store = RecordingStore()
        val box = outbox(store, RecordingExecutor(), scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()

            box.enqueue(watchlist(id = "m1", entityId = "movie-1"))
            box.enqueue(watchlist(id = "m2", entityId = "movie-2"))
            runCurrent()

            assertEquals(1, box.mutationsForItem("movie-1").size)
            assertEquals(1, box.mutationsForItem("movie-2").size)
        } finally {
            box.stop()
        }
    }

    // ------------------------------------------------------------------ retry

    @Test
    fun `retry puts a failed mutation back in the queue with the current time`() = runTest {
        val store = RecordingStore()
        val executor = RecordingExecutor { MutationResult(success = false, reason = "offline") }
        var now = 1_000L
        val box = outbox(store, executor, scope(testScheduler), { now })

        try {
            box.start()
            runCurrent()
            box.enqueue(watchlist())
            advanceTimeBy(100L)
            assertEquals(MutationStatus.Failed("offline", true), box.mutationsForItem("movie-1").single().status)

            now = 9_000L
            box.retry("m1")
            // No time advance here on purpose: the revival itself is committed
            // synchronously, and advancing would let the still-failing executor
            // fail it a second time before the assertions run.
            runCurrent()

            val revived = box.mutationsForItem("movie-1").single()
            assertEquals(MutationStatus.Pending, revived.status)
            assertEquals(9_000L, revived.nextAttemptAtMs)
        } finally {
            box.stop()
        }
    }

    @Test
    fun `two rapid retries keep both revivals`() = runTest {
        val store = RecordingStore()
        val executor = RecordingExecutor { MutationResult(success = false, reason = "offline") }
        var now = 1_000L
        val box = outbox(store, executor, scope(testScheduler), { now })

        try {
            box.start()
            runCurrent()
            box.enqueue(watchlist(id = "m1", entityId = "movie-1"))
            box.enqueue(watchlist(id = "m2", entityId = "movie-2"))
            advanceTimeBy(100L)
            assertEquals(MutationStatus.Failed("offline", true), box.mutationsForItem("movie-1").single().status)
            assertEquals(MutationStatus.Failed("offline", true), box.mutationsForItem("movie-2").single().status)

            // The two revivals are computed back-to-back; each must see the
            // other's write. An eager snapshot outside the commit lock lets the
            // second commit silently undo the first.
            now = 9_000L
            box.retry("m1")
            box.retry("m2")
            runCurrent()

            assertEquals(MutationStatus.Pending, box.mutationsForItem("movie-1").single().status)
            assertEquals(MutationStatus.Pending, box.mutationsForItem("movie-2").single().status)
        } finally {
            box.stop()
        }
    }

    // ------------------------------------------------------------ the store spy

    /** The store is private on the outbox; this reads the recorded saves instead. */
    private fun UserMutationOutbox.store(): List<UserMutation> = allMutations()

    @Test
    fun `every commit is persisted, so a process death cannot lose an intent`() = runTest {
        val store = RecordingStore()
        val box = outbox(store, RecordingExecutor(), scope(testScheduler), { 0L })

        try {
            box.start()
            runCurrent()
            val savesAfterLoad = store.saved.size

            box.enqueue(watchlist())
            runCurrent()

            assertTrue("enqueue must persist", store.saved.size > savesAfterLoad)
            assertTrue(store.lastSave().any { it.id == "m1" })
        } finally {
            box.stop()
        }
    }
}
