import ContractRunner
import Foundation
import XCTest
@testable import CrispyKit

/// The outbox coordinator's tests.
///
/// The outbox runs on the main actor against injected collaborators, so the
/// tests hand it an in-memory store, a recording executor and a clock a local
/// variable can move — nothing here waits on real time beyond the poll tick.
@MainActor
final class UserMutationOutboxTests: XCTestCase {
    private final class TimeBox {
        var now: Int64 = 0
    }

    private final class InMemoryStore: PendingMutationStoring {
        var initial: [any UserMutation] = []
        private(set) var saves: [[any UserMutation]] = []

        func loadAll() async -> [any UserMutation] {
            initial
        }

        func saveAll(_ mutations: [any UserMutation]) async {
            saves.append(mutations)
        }
    }

    private final class RecordingExecutor: MutationExecuting {
        private(set) var executed: [String] = []
        var result: MutationResult = MutationResult(success: true)
        var thrownError: Error?

        func execute(_ mutation: any UserMutation) async throws -> MutationResult {
            executed.append(mutation.id)
            if let thrownError { throw thrownError }
            return result
        }
    }

    private func makeOutbox(
        clock: TimeBox,
        store: InMemoryStore,
        executor: RecordingExecutor,
        policy: RetryPolicy = RetryPolicy(baseDelayMs: 1, maxDelayMs: 2, maxAttempts: 8)
    ) -> UserMutationOutbox {
        UserMutationOutbox(
            store: store,
            executor: executor,
            policy: policy,
            clock: { clock.now },
            pollIntervalMs: 1
        )
    }

    private func waitUntil(
        clock: TimeBox? = nil,
        advanceMs: Int64 = 0,
        _ condition: @MainActor () -> Bool
    ) async {
        for _ in 0..<400 {
            if let clock, advanceMs > 0 { clock.now += advanceMs }
            if condition() { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTFail("condition not met within the wait budget")
    }

    private func watchlist(
        id: String,
        entityId: String,
        createdAtMs: Int64,
        desired: Bool = true
    ) -> WatchlistMutation {
        WatchlistMutation(
            id: id, titleItemId: entityId, entityId: entityId, createdAtMs: createdAtMs,
            attempt: 0, status: .pending, nextAttemptAtMs: 0, desired: desired
        )
    }

    func testEnqueuePersistsImmediatelyWithTheOutboxSchedulingFields() async {
        let clock = TimeBox()
        clock.now = 100
        let store = InMemoryStore()
        let executor = RecordingExecutor()
        let outbox = makeOutbox(clock: clock, store: store, executor: executor)

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))

        XCTAssertEqual(outbox.mutations.count, 1)
        XCTAssertEqual(outbox.mutations[0].id, "a")
        XCTAssertEqual(outbox.mutations[0].status, .pending)
        XCTAssertEqual(outbox.mutations[0].attempt, 0)
        XCTAssertEqual(outbox.mutations[0].nextAttemptAtMs, 100, "the outbox stamps its own now")
        XCTAssertEqual(store.saves.last?.count, 1, "enqueue persists before any drain")
        XCTAssertTrue(executor.executed.isEmpty, "nothing runs before startOnce")
    }

    func testDifferentTargetsBothSurvive() async {
        let clock = TimeBox()
        clock.now = 10
        let outbox = makeOutbox(clock: clock, store: InMemoryStore(), executor: RecordingExecutor())

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))
        clock.now = 20
        await outbox.enqueue(watchlist(id: "b", entityId: "tt2", createdAtMs: 2))

        XCTAssertEqual(
            outbox.mutations.map(\.id),
            ["a", "b"],
            "the Android lesson: a rapid-fire enqueue of a different target never drops the first"
        )
    }

    func testSameTargetCoalescesToTheLatestToggle() async {
        let clock = TimeBox()
        clock.now = 10
        let outbox = makeOutbox(clock: clock, store: InMemoryStore(), executor: RecordingExecutor())

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1, desired: true))
        clock.now = 20
        await outbox.enqueue(watchlist(id: "b", entityId: "tt1", createdAtMs: 2, desired: false))

        XCTAssertEqual(outbox.mutations.count, 1)
        XCTAssertEqual(outbox.mutations[0].id, "b", "ten taps are one request per target")
        let entry = outbox.mutations[0] as? WatchlistMutation
        XCTAssertEqual(entry?.desired, false)
        XCTAssertEqual(entry?.createdAtMs, 2, "the surviving entry carries its own timestamp")
    }

    func testDueMutationExecutesAndSuccessRemovesIt() async {
        let clock = TimeBox()
        clock.now = 100
        let store = InMemoryStore()
        let executor = RecordingExecutor()
        let outbox = makeOutbox(clock: clock, store: store, executor: executor)

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))
        outbox.startOnce()

        await waitUntil { executor.executed.count == 1 }
        XCTAssertEqual(executor.executed, ["a"])
        await waitUntil { outbox.mutations.isEmpty }
        await waitUntil { store.saves.last?.isEmpty == true }

        // A removed mutation never fires again, however far the clock runs.
        clock.now += 60_000
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(executor.executed.count, 1)
    }

    func testLabelledFailureSchedulesBackoff() async {
        let clock = TimeBox()
        clock.now = 100
        let executor = RecordingExecutor()
        executor.result = MutationResult(success: false, reason: "backend unavailable")
        let policy = RetryPolicy(baseDelayMs: 1, maxDelayMs: 2, maxAttempts: 8)
        let outbox = makeOutbox(clock: clock, store: InMemoryStore(), executor: executor, policy: policy)

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))
        outbox.startOnce()

        await waitUntil { executor.executed.count == 1 }
        await waitUntil { outbox.mutations.count == 1 && outbox.mutations[0].attempt == 1 }

        let entry = outbox.mutations[0]
        XCTAssertEqual(entry.attempt, 1)
        XCTAssertEqual(entry.nextAttemptAtMs, 100 + nextBackoffDelayMs(attempt: 1, policy: policy))
        if case let .failed(reason, retryable) = entry.status {
            XCTAssertEqual(reason, "backend unavailable")
            XCTAssertTrue(retryable, "attempt 1 of 8 still has room")
        } else {
            XCTFail("expected a failed status, got \(entry.status)")
        }
        XCTAssertEqual(outbox.failedMutations.map(\.id), ["a"])
    }

    func testThrownErrorBecomesTheDefaultReason() async {
        let clock = TimeBox()
        clock.now = 100
        let executor = RecordingExecutor()
        executor.thrownError = URLError(.notConnectedToInternet)
        let policy = RetryPolicy(baseDelayMs: 1, maxDelayMs: 2, maxAttempts: 8)
        let outbox = makeOutbox(clock: clock, store: InMemoryStore(), executor: executor, policy: policy)

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))
        outbox.startOnce()

        await waitUntil { outbox.mutations.count == 1 && outbox.mutations[0].attempt == 1 }
        if case let .failed(reason, retryable) = outbox.mutations[0].status {
            XCTAssertEqual(reason, "Update failed.")
            XCTAssertTrue(retryable)
        } else {
            XCTFail("expected a failed status, got \(outbox.mutations[0].status)")
        }
    }

    func testConflictIsRecordedAndNeitherRetriedNorCountedFailed() async {
        let clock = TimeBox()
        clock.now = 100
        let executor = RecordingExecutor()
        executor.result = MutationResult(success: false, conflict: true, serverValue: "server says liked")
        let outbox = makeOutbox(clock: clock, store: InMemoryStore(), executor: executor)

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))
        outbox.startOnce()

        await waitUntil { executor.executed.count == 1 }
        await waitUntil { outbox.mutations.count == 1 && outbox.mutations[0].attempt == 0 }

        XCTAssertEqual(outbox.mutations[0].status, .conflict(serverValue: "server says liked"))
        XCTAssertTrue(outbox.failedMutations.isEmpty, "a conflict waits for a decision, not a retry")
        // A conflict is not planned again, however far the clock runs.
        clock.now += 60_000
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(executor.executed.count, 1)
    }

    /// The Android lesson the exhaustion case teaches: a failure is never
    /// re-planned on its own — `planOutbox` only plans `pending` — so the
    /// retry chain is driven by `retry`, and the attempt counter walks to the
    /// limit one retry at a time, where the last failure is not retryable.
    func testTheRetryChainEndsAtTheLastAttempt() async {
        let clock = TimeBox()
        clock.now = 100
        let executor = RecordingExecutor()
        executor.result = MutationResult(success: false, reason: "nope")
        let policy = RetryPolicy(baseDelayMs: 1, maxDelayMs: 2, maxAttempts: 8)
        let outbox = makeOutbox(clock: clock, store: InMemoryStore(), executor: executor, policy: policy)

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))
        outbox.startOnce()

        // The first failure happens on the drain's own; everything after it
        // waits for a human, exactly like Android's outbox.
        await waitUntil { outbox.mutations.count == 1 && outbox.mutations[0].attempt == 1 }
        clock.now += 60_000
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(executor.executed.count, 1, "a failed mutation is never re-planned by the drain")

        for attempt in 2...8 {
            await outbox.retry(id: "a")
            await waitUntil { outbox.mutations.count == 1 && outbox.mutations[0].attempt == attempt }
        }

        XCTAssertEqual(executor.executed.count, 8)
        let entry = outbox.mutations[0]
        XCTAssertEqual(entry.attempt, 8)
        if case let .failed(_, retryable) = entry.status {
            XCTAssertFalse(retryable, "attempt 8 of 8 is the last one")
        } else {
            XCTFail("expected a failed status, got \(entry.status)")
        }
        XCTAssertEqual(outbox.failedMutations.map(\.id), ["a"], "it now waits for a human")
    }

    func testRetryRequeuesOneMutationNow() async {
        let clock = TimeBox()
        clock.now = 100
        let executor = RecordingExecutor()
        executor.result = MutationResult(success: false, reason: "nope")
        let policy = RetryPolicy(baseDelayMs: 1, maxDelayMs: 2, maxAttempts: 8)
        let outbox = makeOutbox(clock: clock, store: InMemoryStore(), executor: executor, policy: policy)

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))
        outbox.startOnce()
        await waitUntil { executor.executed.count == 1 && outbox.mutations[0].attempt == 1 }

        clock.now = 500_000
        await outbox.retry(id: "a")

        XCTAssertEqual(outbox.mutations[0].status, .pending)
        XCTAssertEqual(outbox.mutations[0].nextAttemptAtMs, 500_000, "retry ignores whatever the backoff said")

        await waitUntil(clock: clock, advanceMs: 1_000) { executor.executed.count == 2 }
    }

    func testRetryFailedRequeuesEveryFailure() async {
        let clock = TimeBox()
        clock.now = 100
        let executor = RecordingExecutor()
        executor.result = MutationResult(success: false, reason: "nope")
        let policy = RetryPolicy(baseDelayMs: 1, maxDelayMs: 2, maxAttempts: 8)
        let outbox = makeOutbox(clock: clock, store: InMemoryStore(), executor: executor, policy: policy)

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))
        await outbox.enqueue(watchlist(id: "b", entityId: "tt2", createdAtMs: 2))
        outbox.startOnce()
        await waitUntil { executor.executed.count == 2 && outbox.failedMutations.count == 2 }

        clock.now = 500_000
        // The drain is stopped first: a requeued entry is due immediately, so
        // a live loop would execute — and re-fail — it before the assertion.
        outbox.stop()
        await outbox.retryFailed()

        XCTAssertTrue(outbox.failedMutations.isEmpty, "every failure is pending again")
        for mutation in outbox.mutations {
            XCTAssertEqual(mutation.status, .pending)
            XCTAssertEqual(mutation.nextAttemptAtMs, 500_000)
        }

        // And the next start drains them once the backend answers.
        executor.result = MutationResult(success: true)
        outbox.startOnce()
        await waitUntil { outbox.mutations.isEmpty }
        XCTAssertEqual(executor.executed.count, 4)
    }

    func testStartOnceIsIdempotent() async {
        let clock = TimeBox()
        clock.now = 100
        let executor = RecordingExecutor()
        let outbox = makeOutbox(clock: clock, store: InMemoryStore(), executor: executor)

        outbox.startOnce()
        outbox.startOnce()

        await outbox.enqueue(watchlist(id: "a", entityId: "tt1", createdAtMs: 1))
        await waitUntil { executor.executed.count == 1 }

        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(executor.executed.count, 1, "a second start never doubles the drain")
    }

    func testInflightOnLoadReadsAsPendingAndStaysPutUntilDue() async {
        let clock = TimeBox()
        clock.now = 0
        let store = InMemoryStore()
        store.initial = [watchlist(id: "a", entityId: "tt1", createdAtMs: 1)]
        let loaded = store.initial[0] as! WatchlistMutation
        store.initial = [WatchlistMutation(
            id: loaded.id, titleItemId: loaded.titleItemId, entityId: loaded.entityId,
            createdAtMs: loaded.createdAtMs, attempt: 2, status: .inflight,
            nextAttemptAtMs: 1_000_000, desired: loaded.desired
        )]
        let executor = RecordingExecutor()
        let outbox = makeOutbox(clock: clock, store: store, executor: executor)

        outbox.startOnce()
        await waitUntil { !outbox.mutations.isEmpty }

        XCTAssertEqual(outbox.mutations[0].status, .pending, "the backend never saw an inflight entry")
        XCTAssertEqual(outbox.mutations[0].nextAttemptAtMs, 1_000_000)
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertTrue(executor.executed.isEmpty, "not due yet: the drain plans, it does not fire")
    }
}
