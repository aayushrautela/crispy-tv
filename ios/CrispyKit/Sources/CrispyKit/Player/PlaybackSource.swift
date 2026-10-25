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
    /// The subtitle files that come with the stream, when the stream's source
    /// offered any. The engine adds them after it loads the media.
    public let subtitles: [PlaybackSubtitle]

    public init(
        url: URL,
        headers: [String: String] = [:],
        resumePositionMs: Int64 = 0,
        subtitles: [PlaybackSubtitle] = []
    ) {
        self.url = url
        self.headers = headers
        self.resumePositionMs = resumePositionMs
        self.subtitles = subtitles
    }

    /// The same media, starting somewhere else.
    ///
    /// The headers and the subtitles come along: only the start position is
    /// being changed, and a reopen for a replay has to send the same requests.
    public func resuming(at positionMs: Int64) -> PlaybackSource {
        PlaybackSource(
            url: url,
            headers: headers,
            resumePositionMs: positionMs,
            subtitles: subtitles
        )
    }
}
