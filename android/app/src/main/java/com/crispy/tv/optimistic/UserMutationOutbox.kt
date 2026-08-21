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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pollMs: Long = 250,
) {
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val _byItem = MutableStateFlow<Map<String, List<UserMutation>>>(emptyMap())
    private var processorJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        processorJob =
            scope.launch {
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
        started.set(false)
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
        val pending =
            mutation.copyStatus(MutationStatus.Pending).copyNextAttempt(now)
        val next =
            coalesce(_byItem.value.values.flatten().filter { it.id != pending.id }, pending)
        scope.launch { commit(next, persist = true) }
    }

    /** Re-attempt a mutation that previously failed. */
    fun retry(id: String) {
        val now = clock()
        val next =
            _byItem.value.values.flatten().map {
                if (it.id == id) it.copyStatus(MutationStatus.Pending).copyNextAttempt(now) else it
            }
        scope.launch { commit(next, persist = true) }
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
                HomeRefreshBus.emit(com.crispy.tv.home.HomeRefreshEvent.WatchlistChanged)
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

    private fun coalesce(existing: List<UserMutation>, incoming: UserMutation): List<UserMutation> {
        val kept = existing.filterNot { it.kind == incoming.kind && it.entityId == incoming.entityId }
        return (kept + incoming)
    }

    private suspend fun commit(mutations: List<UserMutation>, persist: Boolean) {
        mutex.withLock {
            _byItem.value = mutations.groupBy { it.titleItemId }
            if (persist) {
                store.saveAll(mutations)
            }
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

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
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
}
