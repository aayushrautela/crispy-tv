import Foundation
import XCTest
@testable import CrispyKit

/// The local watch-progress store's tests.
///
/// The store is `UserDefaults` standing in for Android's `KeyValueStore`, so
/// every test gets its own suite and a clock a local variable can move: the
/// store stamps `now` itself, and the tests move the variable to check the
/// stamping rather than reading the wall clock.
@MainActor
final class WatchProgressStoreTests: XCTestCase {
    private final class TimeBox {
        var now: Int64 = 1_000
    }

    private func makeStore() -> (WatchProgressStore, UserDefaults, TimeBox, String) {
        let name = "crispy.tests.watchProgress.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: name) else {
            preconditionFailure("no UserDefaults suite named \(name)")
        }
        addTeardownBlock { defaults.removePersistentDomain(forName: name) }
        let clock = TimeBox()
        return (WatchProgressStore(defaults: defaults, clock: { clock.now }), defaults, clock, name)
    }

    private func progress(
        currentTime: Double = 42.5,
        duration: Double = 100,
        lastUpdated: Int64 = 999,
        remoteImdbId: String? = "tt1234567",
        addonId: String? = "cinemeta"
    ) -> WatchProgress {
        WatchProgress(
            currentTimeSeconds: currentTime,
            durationSeconds: duration,
            lastUpdatedEpochMs: lastUpdated,
            remoteImdbId: remoteImdbId,
            addonId: addonId
        )
    }

    func testRoundTrip() {
        let (store, _, clock, _) = makeStore()

        store.setWatchProgress(id: "tt1", type: "movie", progress: progress())

        let read = store.getWatchProgress(id: "tt1", type: "movie")
        XCTAssertEqual(read?.currentTimeSeconds, 42.5)
        XCTAssertEqual(read?.durationSeconds, 100)
        XCTAssertEqual(read?.lastUpdatedEpochMs, clock.now, "the store stamps its own now over the passed timestamp")
        XCTAssertEqual(read?.remoteImdbId, "tt1234567")
        XCTAssertEqual(read?.addonId, "cinemeta")
    }

    func testEpisodeKeysAreSeparateEntries() {
        let (store, _, _, _) = makeStore()

        store.setWatchProgress(id: "tt1", type: "show", progress: progress(), episodeId: "tt1:1:2")

        XCTAssertNotNil(store.getWatchProgress(id: "tt1", type: "show", episodeId: "tt1:1:2"))
        XCTAssertNil(store.getWatchProgress(id: "tt1", type: "show"), "the show-level entry is a different key")
        XCTAssertNil(store.getWatchProgress(id: "tt1", type: "movie"), "the type segment is part of the key")
    }

    func testMalformedBlobReadsAsNil() {
        let (store, defaults, _, _) = makeStore()
        let key = watchProgressPrefKey(id: "tt1", type: "movie", episodeId: nil)

        defaults.set("{not json", forKey: key)
        XCTAssertNil(store.getWatchProgress(id: "tt1", type: "movie"))

        defaults.set("[1,2,3]", forKey: key)
        XCTAssertNil(store.getWatchProgress(id: "tt1", type: "movie"), "a JSON array is not an entry")
    }

    func testNumericRemoteImdbIdKeepsItsStringification() {
        let (store, defaults, _, _) = makeStore()
        defaults.set(
            "{\"currentTime\":1,\"duration\":2,\"lastUpdated\":3,\"remoteImdbId\":12345}",
            forKey: watchProgressPrefKey(id: "tt1", type: "movie", episodeId: nil)
        )

        let read = store.getWatchProgress(id: "tt1", type: "movie")
        XCTAssertEqual(read?.remoteImdbId, "12345", "the Android rule: a numeric id stringifies")
    }

    func testBlankMetadataDropsOutAndNullReadsAsNothing() {
        let (store, defaults, _, _) = makeStore()
        defaults.set(
            "{\"currentTime\":1,\"duration\":2,\"lastUpdated\":3,\"remoteImdbId\":\"  \",\"addonId\":null}",
            forKey: watchProgressPrefKey(id: "tt1", type: "movie", episodeId: nil)
        )

        let read = store.getWatchProgress(id: "tt1", type: "movie")
        XCTAssertNil(read?.remoteImdbId, "blank is missing")
        XCTAssertNil(read?.addonId, "JSON null is missing")
    }

    func testContentDurationRoundTrip() {
        let (store, defaults, _, _) = makeStore()

        XCTAssertNil(store.getContentDurationSeconds(id: "tt1", type: "movie"), "absent is nil")

        store.setContentDuration(id: "tt1", type: "movie", durationSeconds: 90.5)
        XCTAssertEqual(store.getContentDurationSeconds(id: "tt1", type: "movie"), 90.5)

        defaults.set("   ", forKey: contentDurationPrefKey(id: "tt1", type: "movie", episodeId: nil))
        XCTAssertNil(store.getContentDurationSeconds(id: "tt1", type: "movie"), "blank is nil")

        defaults.set("abc", forKey: contentDurationPrefKey(id: "tt1", type: "movie", episodeId: nil))
        XCTAssertNil(store.getContentDurationSeconds(id: "tt1", type: "movie"), "unparsable is nil")
    }

    func testUpdateProgressDurationSkipsInsideTheThreshold() {
        let (store, _, clock, _) = makeStore()
        store.setWatchProgress(id: "tt1", type: "movie", progress: progress(currentTime: 30, duration: 120))
        let stampedAt = clock.now

        store.updateProgressDuration(id: "tt1", type: "movie", newDurationSeconds: 130)

        let read = store.getWatchProgress(id: "tt1", type: "movie")
        XCTAssertEqual(read?.currentTimeSeconds, 30, "ten seconds is wobble, not a different cut")
        XCTAssertEqual(read?.durationSeconds, 120)
        XCTAssertEqual(read?.lastUpdatedEpochMs, stampedAt, "a skipped update does not re-stamp")
    }

    func testUpdateProgressDurationScalesThePercentPreservingIt() {
        let (store, _, clock, _) = makeStore()
        store.setWatchProgress(id: "tt1", type: "movie", progress: progress(currentTime: 30, duration: 120))
        clock.now = 5_000

        store.updateProgressDuration(id: "tt1", type: "movie", newDurationSeconds: 240)

        let read = store.getWatchProgress(id: "tt1", type: "movie")
        XCTAssertEqual(read?.currentTimeSeconds, 60, "25 percent of 240")
        XCTAssertEqual(read?.durationSeconds, 240)
        XCTAssertEqual(read?.lastUpdatedEpochMs, 5_000)
        XCTAssertEqual(read?.remoteImdbId, "tt1234567", "the metadata survives the re-stamp")
        XCTAssertEqual(read?.addonId, "cinemeta")
    }

    func testUpdateProgressDurationWithoutAnEntryIsNoOp() {
        let (store, _, _, _) = makeStore()

        store.updateProgressDuration(id: "tt1", type: "movie", newDurationSeconds: 240)

        XCTAssertNil(store.getWatchProgress(id: "tt1", type: "movie"))
    }

    func testKeyShapes() {
        XCTAssertEqual(wpKeyString(id: "tt1", type: "movie", episodeId: nil), "movie:tt1")
        XCTAssertEqual(wpKeyString(id: "tt1", type: "movie", episodeId: "tt1:1:2"), "movie:tt1:tt1:1:2")
        XCTAssertEqual(
            wpKeyString(id: "tt1", type: "movie", episodeId: "   "),
            "movie:tt1",
            "a blank episode suffix is no suffix"
        )
        XCTAssertEqual(
            watchProgressPrefKey(id: "tt1", type: "movie", episodeId: "tt1:1:2"),
            "@watch_progress:movie:tt1:tt1:1:2"
        )
        XCTAssertEqual(
            contentDurationPrefKey(id: "tt1", type: "movie", episodeId: "tt1:1:2"),
            "@content_duration:movie:tt1:tt1:1:2"
        )
        XCTAssertEqual(watchProgressKeyPrefix, "@watch_progress:")
        XCTAssertEqual(contentDurationKeyPrefix, "@content_duration:")
    }

    func testProgressPercentIsZeroWithoutADuration() {
        XCTAssertEqual(
            WatchProgress(currentTimeSeconds: 30, durationSeconds: 0, lastUpdatedEpochMs: 1).progressPercentOrZero(),
            0
        )
        XCTAssertEqual(
            WatchProgress(currentTimeSeconds: 30, durationSeconds: 120, lastUpdatedEpochMs: 1).progressPercentOrZero(),
            25
        )
    }
}
