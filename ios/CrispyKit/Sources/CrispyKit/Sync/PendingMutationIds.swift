import Foundation

/// Mutation ids are made by the callers, not the outbox — the Android
/// `UserMutationIds` rule, kept because it is what lets a test build the exact
/// queue state it wants to exercise.
public func newPendingMutationId() -> String {
    UUID().uuidString
}

/// The wall-clock stamp call sites attach, the `TimeSource.nowMs()` of the
/// Android call sites. The outbox has its own injected clock for retry
/// scheduling; this is the value that records when the user acted.
public func clientEpochMs() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}
