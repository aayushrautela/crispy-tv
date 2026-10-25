import Foundation
import Observation

/// The playback preferences, over `UserDefaults`.
///
/// The store is the only writer of these keys, and the snapshot it holds in
/// memory is authoritative — the same shape the Android repository arrived at
/// after its change listener turned out to exist for a second writer that no
/// longer exists.
///
/// The key *values* are the strings the Android repository stores. They are not
/// a compatibility contract — `UserDefaults` and `SharedPreferences` are
/// different stores on different devices — but they cost nothing and they keep
/// the two platforms legible side by side.
///
/// A change made here reaches the player the next time a source is opened: the
/// engine reads the settings once, when it is built.
@MainActor
@Observable
public final class PlaybackSettingsStore {
    /// The current preferences. Written only through the setters.
    public private(set) var settings: PlaybackSettings

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        settings = PlaybackSettingsStore.read(defaults)
    }

    public func setDefaultAudioLanguage(_ language: String?) {
        let normalized = PlaybackSettingsStore.normalizedLanguage(language)
        guard settings.defaultAudioLanguage != normalized else { return }
        settings.defaultAudioLanguage = normalized
        write(normalized, forKey: Key.defaultAudioLanguage)
    }

    public func setDefaultSubtitleLanguage(_ language: String?) {
        let normalized = PlaybackSettingsStore.normalizedLanguage(language)
        guard settings.defaultSubtitleLanguage != normalized else { return }
        settings.defaultSubtitleLanguage = normalized
        write(normalized, forKey: Key.defaultSubtitleLanguage)
    }

    public func setPlaybackSpeed(_ speed: Double) {
        let safe = PlaybackSettingsStore.normalizedSpeed(speed)
        guard settings.playbackSpeed != safe else { return }
        settings.playbackSpeed = safe
        defaults.set(safe, forKey: Key.playbackSpeed)
    }

    public func setAutoSelectStream(_ enabled: Bool) {
        guard settings.autoSelectStream != enabled else { return }
        settings.autoSelectStream = enabled
        defaults.set(enabled, forKey: Key.autoSelectStream)
    }

    /// Stores a value, or clears the key when there is nothing to store — so
    /// "Automatic" is an absent key rather than an empty string that a later
    /// reader would have to know to treat as absent.
    private func write(_ language: String?, forKey key: String) {
        if let language {
            defaults.set(language, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    private enum Key {
        static let defaultAudioLanguage = "default_audio_language"
        static let defaultSubtitleLanguage = "default_subtitle_language"
        static let playbackSpeed = "playback_speed"
        static let autoSelectStream = "auto_select_stream"
    }

    private static func read(_ defaults: UserDefaults) -> PlaybackSettings {
        PlaybackSettings(
            defaultAudioLanguage: normalizedLanguage(
                defaults.string(forKey: Key.defaultAudioLanguage)
            ),
            defaultSubtitleLanguage: normalizedLanguage(
                defaults.string(forKey: Key.defaultSubtitleLanguage)
            ),
            playbackSpeed: normalizedSpeed(storedSpeed(defaults)),
            autoSelectStream: defaults.bool(forKey: Key.autoSelectStream)
        )
    }

    /// `double(forKey:)` answers 0 for a key that was never written, so a
    /// missing speed has to be told apart from a stored zero before the
    /// normaliser sees it — both end at 1, but only one of them is a repair.
    private static func storedSpeed(_ defaults: UserDefaults) -> Double {
        guard defaults.object(forKey: Key.playbackSpeed) != nil else { return 1 }
        return defaults.double(forKey: Key.playbackSpeed)
    }

    /// The guard the Android repository applies for the same reason: a rate the
    /// player cannot run at is not a preference, it is a broken file.
    private static func normalizedSpeed(_ speed: Double) -> Double {
        speed.isFinite && speed > 0 ? speed : 1
    }

    private static func normalizedLanguage(_ language: String?) -> String? {
        language?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
    }
}
