import ContractRunner
import Foundation
import Observation

/// The offline outbox coordinator, the twin of Android's `UserMutationOutbox`:
/// holds every user mutation the backend has not confirmed, replays them on
/// its own clock, and coalesces rapid-fire toggles so a queue of ten taps is
/// one request per target.
///
/// Main-actor serialisation plays the role of Android's commit lock: every
/// state change (enqueue, outcome, loading) happens in one place, and the
/// coalesced list is always computed from the live queue — the Android lesson
/// pinned by "toggles of different targets both survive", because an eager
/// snapshot taken once outside the lock lost the first of two rapid enqueues.
@MainActor
@Observable
public final class UserMutationOutbox {
    /// The live queue, oldest first — the order `planOutbox` replays in — and
    /// coalesced to one entry per `kind#entityId`.
    public private(set) var mutations: [any UserMutation] = []

    private let store: PendingMutationStoring
    private let executor: MutationExecuting
    private let policy: RetryPolicy
    private let clock: () -> Int64
    private let pollIntervalNanoseconds: UInt64
    private var processor: Task<Void, Never>?
    private var started = false

    public init(
        store: PendingMutationStoring,
        executor: MutationExecuting,
        policy: RetryPolicy = RetryPolicy(),
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        pollIntervalMs: Int64 = 250
    ) {
        self.store = store
        self.executor = executor
        self.policy = policy
        self.clock = clock
        self.pollIntervalNanoseconds = UInt64(pollIntervalMs) * 1_000_000
    }

    /// Idempotent, like Android's `start()`: the composition root calls it,
    /// and a second call waits without disturbing the running drain.
    public func startOnce() {
        guard !started else { return }
        started = true
        Task { await openQueueAndStartDraining() }
    }

    /// Cancels the drain and clears the latch. Enqueues still work afterwards;
    /// they flush on the next `startOnce()`.
    public func stop() {
        processor?.cancel()
        processor = nil
        started = false
    }

    private func openQueueAndStartDraining() async {
        // An entry recorded mid-send never reached the backend, so it is a
        // pending entry again, exactly on load.
        var loaded = await store.loadAll()
        loaded = loaded.map { copy($0, status: .pending) }
        // An enqueue can win the race into the queue while the file is still
        // loading — startOnce hands the load to a Task — so the file's half
        // and the live half merge, and coalescing keeps one entry per target.
        mutations = coalesceMutations(loaded + mutations)
        await persist()
        processor = Task { await processLoop() }
    }

    /// Adds a mutation the caller built (callers own the id and the
    /// timestamp). Coalescing reads the live queue, always.
    public func enqueue(_ mutation: any UserMutation) async {
        let now = clock()
        let pending = copy(mutation, status: .pending, attempt: 0, nextAttemptAtMs: now)
        mutations = coalesceMutations(mutations + [pending])
        await persist()
    }

    /// Requeues one mutation now, whatever its backoff said.
    public func retry(id: String) async {
        guard let index = mutations.firstIndex(where: { $0.id == id }) else { return }
        mutations[index] = copy(mutations[index], status: .pending, nextAttemptAtMs: clock())
        await persist()
    }

    /// Requeues every failed mutation. A screen's Retry button wires to this.
    public func retryFailed() async {
        let now = clock()
        mutations = mutations.map { mutation in
            if case .failed = mutation.status {
                return copy(mutation, status: .pending, nextAttemptAtMs: now)
            }
            return mutation
        }
        await persist()
    }

    /// Everything the queue holds for one item, for a row's sync badge.
    public func mutationsForItem(_ itemId: String) -> [any UserMutation] {
        mutations.filter { mutation in mutation.titleItemId == itemId }
    }

    /// The entries that failed for good and are waiting for a human.
    public var failedMutations: [any UserMutation] {
        mutations.filter { mutation in
            if case .failed = mutation.status { return true }
            return false
        }
    }

    /// The Android plan-due loop: when nothing is due, sleep a tick; when
    /// something is, run it now. Replaying the due slice can sleep again
    /// immediately because a fresh plan is nothing but the survivors.
    private func processLoop() async {
        while !Task.isCancelled {
            let due = planOutbox(mutations: mutations, nowMs: clock())
            if due.isEmpty {
                try? await Task.sleep(nanoseconds: pollIntervalNanoseconds)
                continue
            }
            for action in due {
                if Task.isCancelled { return }
                await runMutation(id: action.mutationId)
            }
        }
    }

    private func runMutation(id: String) async {
        guard let index = mutations.firstIndex(where: { $0.id == id }) else { return }
        // Inflight is memory-only: if the process dies mid-send, the file
        // still says pending, and the next run replays it.
        let mutation = copy(mutations[index], status: .inflight)
        mutations[index] = mutation

        let result: MutationResult
        do {
            result = try await executor.execute(mutation)
        } catch is CancellationError {
            // Never swallow cancellation; the drain stops and the entry stays
            // inflight in memory while the file still says pending.
            return
        } catch {
            result = MutationResult(success: false, reason: nil)
        }
        guard !Task.isCancelled else { return }
        await commitOutcome(mutation, result)
    }

    private func commitOutcome(_ mutation: any UserMutation, _ result: MutationResult) async {
        guard let index = mutations.firstIndex(where: { $0.id == mutation.id }) else { return }
        if result.success {
            mutations.remove(at: index)
        } else if result.conflict {
            mutations[index] = copy(mutation, status: .conflict(serverValue: result.serverValue))
        } else {
            let attempt = mutation.attempt + 1
            let retryable = attempt < policy.maxAttempts
            mutations[index] = copy(
                mutation,
                status: .failed(reason: result.reason.nilIfBlank ?? "Update failed.", retryable: retryable),
                attempt: attempt,
                nextAttemptAtMs: clock() + nextBackoffDelayMs(attempt: attempt, policy: policy)
            )
        }
        await persist()
    }

    private func persist() async {
        await store.saveAll(mutations)
    }

    /// Rebuilds a mutation with new scheduling fields — the Swift spelling of
    /// Kotlin's `copy()` on a data class, one arm per kind.
    private func copy(
        _ mutation: any UserMutation,
        status: MutationStatus? = nil,
        attempt: Int? = nil,
        nextAttemptAtMs: Int64? = nil
    ) -> any UserMutation {
        let statusValue = status ?? mutation.status
        let attemptValue = attempt ?? mutation.attempt
        let nextAttemptValue = nextAttemptAtMs ?? mutation.nextAttemptAtMs
        let id = mutation.id
        let titleItemId = mutation.titleItemId
        let entityId = mutation.entityId
        let createdAtMs = mutation.createdAtMs
        switch mutation {
        case let m as WatchlistMutation:
            return WatchlistMutation(id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs, attempt: attemptValue, status: statusValue, nextAttemptAtMs: nextAttemptValue, desired: m.desired)
        case let m as TitleWatchedMutation:
            return TitleWatchedMutation(id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs, attempt: attemptValue, status: statusValue, nextAttemptAtMs: nextAttemptValue, contentType: m.contentType, desired: m.desired)
        case let m as RatingMutation:
            return RatingMutation(id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs, attempt: attemptValue, status: statusValue, nextAttemptAtMs: nextAttemptValue, desired: m.desired)
        case let m as EpisodeWatchedMutation:
            return EpisodeWatchedMutation(id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs, attempt: attemptValue, status: statusValue, nextAttemptAtMs: nextAttemptValue, itemId: m.itemId, season: m.season, episode: m.episode, videoId: m.videoId, desired: m.desired)
        case let m as SeasonWatchedMutation:
            return SeasonWatchedMutation(id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs, attempt: attemptValue, status: statusValue, nextAttemptAtMs: nextAttemptValue, seasonItemId: m.seasonItemId, seasonNumber: m.seasonNumber, desired: m.desired)
        default:
            preconditionFailure("Unknown UserMutation: \(type(of: mutation))")
        }
    }
}
