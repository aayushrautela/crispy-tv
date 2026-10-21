import Foundation

/// The kind of track a selection refers to.
public enum PlaybackTrackKind: String, Equatable, Sendable, CaseIterable {
    case audio
    case video
    case subtitle
}

/// One track of the media that is playing.
///
/// The contract has no vocabulary for tracks, so a track is transport state
/// like the playhead: the engine reports what the media carries, and the
/// chrome asks for a different one.
public struct PlaybackTrack: Identifiable, Equatable, Sendable {
    /// The engine's identifier for the track.
    public let id: String
    public let kind: PlaybackTrackKind
    public let title: String?
    public let language: String?
    /// The track the media marks as its default.
    public let isDefault: Bool
    /// A track the media forces on, such as a forced subtitle.
    public let isForced: Bool
    /// Whether this track is the one that is playing.
    public let isSelected: Bool

    public init(
        id: String,
        kind: PlaybackTrackKind,
        title: String? = nil,
        language: String? = nil,
        isDefault: Bool = false,
        isForced: Bool = false,
        isSelected: Bool = false
    ) {
        self.id = id
        self.kind = kind
        self.title = title
        self.language = language
        self.isDefault = isDefault
        self.isForced = isForced
        self.isSelected = isSelected
    }
}

/// A track choice the engine is asked to apply.
public enum PlaybackTrackSelection: Equatable, Sendable {
    /// Play this track.
    case track(PlaybackTrack)
    /// Turn a kind of track off, such as the subtitles.
    case disabled(PlaybackTrackKind)
}
