import Foundation

/// The playback preferences the app honours.
///
/// This is the iOS half of the Android `PlaybackSettings` model: the Android
/// type carries every switch its two engines and its libass path can act on,
/// and this one carries the four things a single mpv engine and one stream
/// picker can. The absent ones are absent on purpose — a setting nothing reads
/// is a switch that lies (see `docs/plans/ios-mpv-player-plan.md`).
public struct PlaybackSettings: Equatable, Sendable {
    /// The language an audio track should be chosen in — ISO 639, two or three
    /// letters, or `nil` for "let mpv decide".
    public var defaultAudioLanguage: String?
    /// The language a subtitle track should be chosen in, or `nil` for
    /// "Automatic".
    public var defaultSubtitleLanguage: String?
    /// The rate the player starts at. Always finite and positive.
    public var playbackSpeed: Double
    /// Whether picking a title plays the best stream instead of waiting for a
    /// tap in the picker.
    public var autoSelectStream: Bool

    public init(
        defaultAudioLanguage: String? = nil,
        defaultSubtitleLanguage: String? = nil,
        playbackSpeed: Double = 1,
        autoSelectStream: Bool = false
    ) {
        self.defaultAudioLanguage = defaultAudioLanguage
        self.defaultSubtitleLanguage = defaultSubtitleLanguage
        self.playbackSpeed = playbackSpeed
        self.autoSelectStream = autoSelectStream
    }

    /// What an install that has never opened the settings screen runs with.
    public static let `default` = PlaybackSettings()
}
