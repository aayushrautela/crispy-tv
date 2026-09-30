package com.crispy.tv.optimistic

import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.UserMutation

/**
 * Durable, process-death-safe store for pending mutations. Backed by a single
 * JSON file; loading coerces any transient [MutationStatus.Inflight] entry back
 * to [MutationStatus.Pending] so a write interrupted by a crash is retried.
 */
interface PendingMutationStore {
    suspend fun loadAll(): List<UserMutation>

    suspend fun saveAll(mutations: List<UserMutation>)
}
