import XCTest
@testable import CrispyKit

/// The playback preference store: what it starts with, what it keeps, and what
/// it refuses to keep.
@MainActor
final class PlaybackSettingsStoreTests: XCTestCase {
    func testAnUntouchedStoreHasTheDefaults() {
        let store = PlaybackSettingsStore(defaults: makeDefaults())
        XCTAssertNil(store.settings.defaultAudioLanguage)
        XCTAssertNil(store.settings.defaultSubtitleLanguage)
        XCTAssertEqual(store.settings.playbackSpeed, 1)
        XCTAssertFalse(store.settings.autoSelectStream)
    }

    func testEveryPreferenceSurvivesASecondStore() {
        let defaults = makeDefaults()
        let store = PlaybackSettingsStore(defaults: defaults)
        store.setDefaultAudioLanguage("en")
        store.setDefaultSubtitleLanguage("es")
        store.setPlaybackSpeed(1.25)
        store.setAutoSelectStream(true)

        let reopened = PlaybackSettingsStore(defaults: defaults)
        XCTAssertEqual(reopened.settings.defaultAudioLanguage, "en")
        XCTAssertEqual(reopened.settings.defaultSubtitleLanguage, "es")
        XCTAssertEqual(reopened.settings.playbackSpeed, 1.25)
        XCTAssertTrue(reopened.settings.autoSelectStream)
    }

    func testALanguageIsStoredTrimmed() {
        let store = PlaybackSettingsStore(defaults: makeDefaults())
        store.setDefaultAudioLanguage("  spa \n")
        XCTAssertEqual(store.settings.defaultAudioLanguage, "spa")
    }

    func testABlankLanguageIsTheSameAsNoLanguage() {
        let defaults = makeDefaults()
        let store = PlaybackSettingsStore(defaults: defaults)
        store.setDefaultAudioLanguage("en")
        store.setDefaultAudioLanguage("   ")
        XCTAssertNil(store.settings.defaultAudioLanguage)
        XCTAssertNil(defaults.object(forKey: "default_audio_language"))
    }

    func testAutomaticRemovesTheStoredLanguage() {
        let defaults = makeDefaults()
        let store = PlaybackSettingsStore(defaults: defaults)
        store.setDefaultSubtitleLanguage("fr")
        XCTAssertNotNil(defaults.object(forKey: "default_subtitle_language"))

        store.setDefaultSubtitleLanguage(nil)
        XCTAssertNil(store.settings.defaultSubtitleLanguage)
        XCTAssertNil(defaults.object(forKey: "default_subtitle_language"))
    }

    func testARateThePlayerCannotRunAtFallsBackToNormal() {
        let store = PlaybackSettingsStore(defaults: makeDefaults())
        for speed in [0, -2, Double.infinity, Double.nan] {
            store.setPlaybackSpeed(speed)
            XCTAssertEqual(store.settings.playbackSpeed, 1, "\(speed) was kept")
        }
    }

    func testAStoredJunkRateReadsAsNormal() {
        let defaults = makeDefaults()
        defaults.set(0.0, forKey: "playback_speed")
        XCTAssertEqual(PlaybackSettingsStore(defaults: defaults).settings.playbackSpeed, 1)
    }

    /// A store of its own per test, so a stored key cannot leak between cases.
    private func makeDefaults() -> UserDefaults {
        let name = "com.crispy.rewrite.tests.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: name) else {
            preconditionFailure("A UserDefaults suite could not be created for \(name)")
        }
        defaults.removePersistentDomain(forName: name)
        return defaults
    }
}
