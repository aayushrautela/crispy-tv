package com.crispy.tv.optimistic

import com.crispy.tv.domain.optimistic.EpisodeWatchedMutation
import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.RatingMutation
import com.crispy.tv.domain.optimistic.RetryPolicy
import com.crispy.tv.domain.optimistic.SeasonWatchedMutation
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.optimistic.WatchlistMutation
import com.crispy.tv.domain.optimistic.nextBackoffDelayMs
import com.crispy.tv.domain.optimistic.planOutbox
import com.crispy.tv.home.HomeRefreshBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide coordinator for optimistic user mutations.
 *
 * Responsibilities:
 * - Accept a new intent via [enqueue] and surface it immediately (the UI derives
 *   display state from [observeItem] before any network call completes).
 * - Flush due [com.crispy.tv.domain.optimistic.MutationStatus.Pending] mutations
 *   through the [MutationExecutor] with deterministic backoff and idempotent
 *   nonces, coalescing rapid same-target toggles.
 * - On success, drop the mutation and notify other screens via [HomeRefreshBus];
 *   on failure, reschedule with backoff or mark [MutationStatus.Failed] so the
 *   UI can offer a retry. Never swallows [CancellationException].
 */
class UserMutationOutbox(
    private val store: PendingMutationStore,
    private val executor: MutationExecutor,
    private val scope: CoroutineScope,
    private val policy: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long,
    private val pollMs: Long = 250,
) {
    private val mutex = Mutex()
    private var started = false
    private val _byItem = MutableStateFlow<Map<String, List<UserMutation>>>(emptyMap())
    private var processorJob: Job? = null

    fun start() {
        scope.launch {
            // `java.util.concurrent.atomic.AtomicBoolean` has no commonMain equivalent, and
            // this class already owns a Mutex, so the once-only latch is that Mutex. The
            // latch is taken and released before the long poll loop below, so a second
            // start() cannot end up queued behind it rather than returning immediately.
            val alreadyStarted = mutex.withLock { started.also { started = true } }
            if (alreadyStarted) return@launch
            // Only the latch winner may own `processorJob`. A rejected second start
            // must not overwrite it: `stop()` cancels this field, and cancelling the
            // rejected no-op job instead would leave the live processor running with
            // nobody holding a handle to it. That is exactly what the latch test
            // (`start(); start(); stop()`) pins.
            // `coroutineContext` here is the launched child's context (the lambda's
            // receiver is the child's scope, not the outer one), so this is the live
            // processor's own handle — not the jobless outer scope's.
            processorJob = coroutineContext[Job]
            val loaded =
                store.loadAll().map { mutation ->
                    if (mutation.status == MutationStatus.Inflight) {
                        mutation.copyStatus(MutationStatus.Pending)
                    } else {
                        mutation
                    }
                }
            commit(loaded, persist = true)
            processLoop()
        }
    }

    fun stop() {
        processorJob?.cancel()
        // Deliberately not taking the latch: start() and stop() are both called from the
        // composition root on one thread, and holding the Mutex here would mean either
        // making stop() suspend or reading the flag twice.
        started = false
    }

    fun observeItem(itemId: String): StateFlow<List<UserMutation>> =
        _byItem
            .map { it[itemId].orEmpty() }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun mutationsForItem(itemId: String): List<UserMutation> = _byItem.value[itemId].orEmpty()

    fun allMutations(): List<UserMutation> = _byItem.value.values.flatten()

    /** Register a new intent. Rapid same-target toggles coalesce to the latest. */
    fun enqueue(mutation: UserMutation) {
        val now = clock()
        val pending = mutation.copyStatus(MutationStatus.Pending).copyNextAttempt(now)
        scope.launch {
            // The snapshot MUST be taken inside the launch, under the same lock as
            // the commit. An earlier revision snapshotted `_byItem` eagerly here and
            // committed later: two rapid enqueues then computed from the same stale
            // state and the last commit silently dropped the first mutation. The
            // `toggles of different targets both survive` case pins it.
            mutex.withLock {
                val next = coalesce(_byItem.value.values.flatten().filter { it.id != pending.id }, pending)
                commitLocked(next, persist = true)
            }
        }
    }

    /** Re-attempt a mutation that previously failed. */
    fun retry(id: String) {
        val now = clock()
        scope.launch {
            // Same lost-update shape as [enqueue]: snapshot under the commit lock.
            mutex.withLock {
                val next =
                    _byItem.value.values.flatten().map {
                        if (it.id == id) it.copyStatus(MutationStatus.Pending).copyNextAttempt(now) else it
                    }
                commitLocked(next, persist = true)
            }
        }
    }

    private suspend fun processLoop() {
        while (true) {
            val due = planOutbox(allMutations(), clock())
            if (due.isEmpty()) {
                delay(pollMs)
                continue
            }
            for (action in due) {
                val current = allMutations().firstOrNull { it.id == action.mutationId } ?: continue
                runMutation(current)
            }
        }
    }

    private suspend fun runMutation(mutation: UserMutation) {
        val inflight = mutation.copyStatus(MutationStatus.Inflight)
        replaceInMemory(inflight)

        val result =
            try {
                executor.execute(mutation)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }

        val now = clock()
        val all = allMutations().toMutableList()
        val index = all.indexOfFirst { it.id == mutation.id }
        if (index < 0) return

        when {
            result?.success == true -> {
                all.removeAt(index)
                commit(all, persist = true)
                publishMutationSuccessEvents(mutation)
            }
            result?.conflict == true -> {
                all[index] = mutation.copyStatus(
                    MutationStatus.Conflict(serverValue = result.serverValue),
                )
                commit(all, persist = true)
            }
            else -> {
                val nextAttempt = now + nextBackoffDelayMs(mutation.attempt + 1, policy)
                val retryable = mutation.attempt + 1 < policy.maxAttempts
                all[index] =
                    mutation
                        .copyStatus(
                            MutationStatus.Failed(
                                reason = result?.reason ?: "Update failed.",
                                retryable = retryable,
                            ),
                        ).copyNextAttempt(nextAttempt)
                        .copyAttempt(mutation.attempt + 1)
                commit(all, persist = true)
            }
        }
    }

    private fun publishMutationSuccessEvents(mutation: UserMutation) {
        when (mutation) {
            is WatchlistMutation -> {
                HomeRefreshBus.emit(com.crispy.tv.home.HomeRefreshEvent.WatchlistChanged)
            }
            is TitleWatchedMutation,
            is EpisodeWatchedMutation,
            is SeasonWatchedMutation,
            -> {
                HomeRefreshBus.emit(com.crispy.tv.home.HomeRefreshEvent.HistoryChanged)
            }
            is RatingMutation -> {
                HomeRefreshBus.emit(com.crispy.tv.home.HomeRefreshEvent.RatingsChanged)
            }
        }
    }

    private fun coalesce(existing: List<UserMutation>, incoming: UserMutation): List<UserMutation> {
        val kept = existing.filterNot { it.kind == incoming.kind && it.entityId == incoming.entityId }
        return (kept + incoming)
    }

    private suspend fun commit(mutations: List<UserMutation>, persist: Boolean) {
        mutex.withLock { commitLocked(mutations, persist) }
    }

    /** The commit itself, for call sites that already hold [mutex] ([enqueue], [retry]). */
    private suspend fun commitLocked(mutations: List<UserMutation>, persist: Boolean) {
        _byItem.value = mutations.groupBy { it.titleItemId }
        if (persist) {
            store.saveAll(mutations)
        }
    }

    private suspend fun replaceInMemory(mutation: UserMutation) {
        mutex.withLock {
            val all = _byItem.value.values.flatten().toMutableList()
            val idx = all.indexOfFirst { it.id == mutation.id }
            if (idx >= 0) all[idx] = mutation else all.add(mutation)
            _byItem.value = all.groupBy { it.titleItemId }
        }
    }
}

private fun UserMutation.copyStatus(status: MutationStatus): UserMutation =
    when (this) {
        is WatchlistMutation -> copy(status = status)
        is TitleWatchedMutation -> copy(status = status)
        is RatingMutation -> copy(status = status)
        is EpisodeWatchedMutation -> copy(status = status)
        is SeasonWatchedMutation -> copy(status = status)
    }

private fun UserMutation.copyNextAttempt(nextAttemptAtMs: Long): UserMutation =
    when (this) {
        is WatchlistMutation -> copy(nextAttemptAtMs = nextAttemptAtMs)
        is TitleWatchedMutation -> copy(nextAttemptAtMs = nextAttemptAtMs)
        is RatingMutation -> copy(nextAttemptAtMs = nextAttemptAtMs)
        is EpisodeWatchedMutation -> copy(nextAttemptAtMs = nextAttemptAtMs)
        is SeasonWatchedMutation -> copy(nextAttemptAtMs = nextAttemptAtMs)
    }

private fun UserMutation.copyAttempt(attempt: Int): UserMutation =
    when (this) {
        is WatchlistMutation -> copy(attempt = attempt)
        is TitleWatchedMutation -> copy(attempt = attempt)
        is RatingMutation -> copy(attempt = attempt)
        is EpisodeWatchedMutation -> copy(attempt = attempt)
        is SeasonWatchedMutation -> copy(attempt = attempt)
    }
