import Foundation

public let minProgressPositionMs: Int = 1000
public let completionPercent: Double = 85.0

public struct ResolvedProgressWrite {
    public let storedPositionMs: Int
    public let eventPositionMs: Int
    public let isCompleted: Bool

    public init(storedPositionMs: Int, eventPositionMs: Int, isCompleted: Bool) {
        self.storedPositionMs = storedPositionMs
        self.eventPositionMs = eventPositionMs
        self.isCompleted = isCompleted
    }
}

/// Mirrors `PlaybackProgressPolicy.resolveProgressWrite` (Android core-domain).
/// Drop transient sub-second engine positions; pin completed items to full duration.
public func resolveProgressWrite(positionMs: Int, durationMs: Int) -> ResolvedProgressWrite? {
    let currentSeconds = Double(max(0, positionMs)) / 1000.0
    let durationSeconds = durationMs > 0 ? Double(durationMs) / 1000.0 : 0.0
    let isCompleted = durationSeconds > 0.0 &&
        (currentSeconds / durationSeconds) * 100.0 >= completionPercent
    if !isCompleted && positionMs < minProgressPositionMs {
        return nil
    }
    let pinnedMs: Int = (isCompleted && durationMs > 0) ? durationMs : max(0, positionMs)
    return ResolvedProgressWrite(
        storedPositionMs: pinnedMs,
        eventPositionMs: pinnedMs,
        isCompleted: isCompleted
    )
}
