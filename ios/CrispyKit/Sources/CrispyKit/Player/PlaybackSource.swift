import Foundation

/// Everything an engine needs to start playing one piece of media.
///
/// Positions are milliseconds everywhere in the player, matching the
/// `player_machine` contract, whose phases and timestamps are also in
/// milliseconds. A caller holding a backend `WatchState.resumePositionSeconds`
/// converts once, at the call site.
public struct PlaybackSource: Equatable, Sendable {
    public let url: URL
    /// Request headers the stream needs. Addon streams are often served with
    /// authorization or a user agent, so the engine sends them verbatim.
    public let headers: [String: String]
    /// Where playback should start, in milliseconds. Zero means "from the
    /// beginning".
    public let resumePositionMs: Int64

    public init(url: URL, headers: [String: String] = [:], resumePositionMs: Int64 = 0) {
        self.url = url
        self.headers = headers
        self.resumePositionMs = resumePositionMs
    }

    /// The same media, starting somewhere else.
    public func resuming(at positionMs: Int64) -> PlaybackSource {
        PlaybackSource(url: url, headers: headers, resumePositionMs: positionMs)
    }
}
