import ContractRunner
import Foundation
import XCTest
@testable import CrispyKit

/// The record half of record-then-send: one progress event, written to the
/// local store exactly where the resume lookup will read it back.
///
/// The store is `UserDefaults`-backed and the event is a value the test builds
/// by hand, so every case names the key it expects and moves a local clock.
@MainActor
final class WatchProgressRecorderTests: XCTestCase {
    private final class TimeBox {
        var now: Int64 = 1_000
    }

    private func makeStore() -> (WatchProgressStore, UserDefaults, TimeBox, String) {
        let name = "crispy.tests.watchProgressRecorder.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: name) else {
            preconditionFailure("no UserDefaults suite named \(name)")
        }
        addTeardownBlock { defaults.removePersistentDomain(forName: name) }
        let clock = TimeBox()
        return (WatchProgressStore(defaults: defaults, clock: { clock.now }), defaults, clock, name)
    }

    private func event(
        position: Double = 42.5,
        duration: Double? = 100
    ) -> PlaybackEventInput {
        PlaybackEventInput(
            clientEventId: "c1",
            eventType: "playback_progress",
            itemId: "tt1",
            positionSeconds: position,
            durationSeconds: duration
        )
    }

    private func identity(
        contentType: MediaContentType = .movie,
        season: Int? = nil,
        episode: Int? = nil
    ) -> PlaybackIdentity {
        PlaybackIdentity(
            itemId: "tt1",
            title: "The Item",
            showTitle: season == nil ? nil : "The Show",
            season: season,
            episode: episode,
            contentType: contentType
        )
    }

    func testKeyTypeNameMapping() {
        XCTAssertEqual(watchProgressKeyTypeName(.movie), "movie")
        XCTAssertEqual(watchProgressKeyTypeName(.series), "show", "the backend says series, the key says show")
        XCTAssertEqual(watchProgressKeyTypeName(.anime), "anime")
    }

    func testKeyPartsForMovieAndShowAndAnime() {
        let movie = watchProgressKeyParts(identity: identity(contentType: .movie, season: 1, episode: 2))
        XCTAssertEqual(movie?.id, "tt1")
        XCTAssertEqual(movie?.type, "movie")
        XCTAssertNil(movie?.episodeId, "a movie never carries an episode suffix, whatever the identity happens to know")

        let show = watchProgressKeyParts(identity: identity(contentType: .series, season: 1, episode: 2))
        XCTAssertEqual(show?.id, "tt1")
        XCTAssertEqual(show?.type, "show")
        XCTAssertEqual(show?.episodeId, "tt1:1:2")

        let anime = watchProgressKeyParts(identity: identity(contentType: .anime, season: 3, episode: 4))
        XCTAssertEqual(anime?.id, "tt1")
        XCTAssertEqual(anime?.type, "anime")
        XCTAssertEqual(anime?.episodeId, "tt1:3:4")

        let noEpisode = watchProgressKeyParts(identity: identity(contentType: .series, season: 1, episode: nil))
        XCTAssertNil(noEpisode, "an episode without its number has no episode suffix at all")
    }

    func testNilOrBlankIdentityIsNoOp() {
        let (store, defaults, _, _) = makeStore()

        recordWatchProgress(store: store, identity: nil, event: event())
        recordWatchProgress(
            store: store,
            identity: PlaybackIdentity(itemId: "   ", title: "Blank", contentType: .movie),
            event: event()
        )

        XCTAssertTrue(
            defaults.dictionaryRepresentation().keys.allSatisfy { !$0.hasPrefix(watchProgressKeyPrefix) },
            "no identity, no key, no write"
        )
        XCTAssertTrue(
            defaults.dictionaryRepresentation().keys.allSatisfy { !$0.hasPrefix(contentDurationKeyPrefix) }
        )
    }

    func testMovieRecordWritesProgressAndContentDuration() {
        let (store, _, clock, _) = makeStore()

        recordWatchProgress(store: store, identity: identity(), event: event(position: 42.5, duration: 100))

        let read = store.getWatchProgress(id: "tt1", type: "movie")
        XCTAssertEqual(read?.currentTimeSeconds, 42.5)
        XCTAssertEqual(read?.durationSeconds, 100)
        XCTAssertEqual(read?.lastUpdatedEpochMs, clock.now, "the recorder passes 0 and the store stamps now")
        XCTAssertEqual(store.getContentDurationSeconds(id: "tt1", type: "movie"), 100)
    }

    func testSeriesRecordWritesUnderTheShowEpisodeKey() {
        let (store, _, _, _) = makeStore()

        recordWatchProgress(
            store: store,
            identity: identity(contentType: .series, season: 1, episode: 2),
            event: event(position: 10, duration: 30)
        )

        let read = store.getWatchProgress(id: "tt1", type: "show", episodeId: "tt1:1:2")
        XCTAssertEqual(read?.currentTimeSeconds, 10)
        XCTAssertEqual(read?.durationSeconds, 30)
        XCTAssertEqual(store.getContentDurationSeconds(id: "tt1", type: "show", episodeId: "tt1:1:2"), 30)
        XCTAssertNil(store.getWatchProgress(id: "tt1", type: "show"), "the show-level key stays untouched")
    }

    func testRecordMergesMetadataFromTheExistingEntry() {
        let (store, _, _, _) = makeStore()
        store.setWatchProgress(
            id: "tt1",
            type: "movie",
            progress: WatchProgress(
                currentTimeSeconds: 1,
                durationSeconds: 2,
                lastUpdatedEpochMs: 0,
                remoteImdbId: "tt0999999",
                addonId: "someAddon"
            )
        )

        recordWatchProgress(store: store, identity: identity(), event: event(position: 50, duration: 100))

        let read = store.getWatchProgress(id: "tt1", type: "movie")
        XCTAssertEqual(read?.currentTimeSeconds, 50, "the position is the event's")
        XCTAssertEqual(read?.remoteImdbId, "tt0999999", "the metadata is the previous entry's")
        XCTAssertEqual(read?.addonId, "someAddon")
    }

    func testZeroAndNilDurationsSkipTheContentDurationKey() {
        let (store, defaults, _, _) = makeStore()

        recordWatchProgress(store: store, identity: identity(), event: event(position: 5, duration: nil))
        XCTAssertNil(store.getContentDurationSeconds(id: "tt1", type: "movie"))
        XCTAssertEqual(store.getWatchProgress(id: "tt1", type: "movie")?.durationSeconds, 0)

        recordWatchProgress(store: store, identity: identity(), event: event(position: 5, duration: 0))
        XCTAssertTrue(
            defaults.dictionaryRepresentation().keys.allSatisfy { !$0.hasPrefix(contentDurationKeyPrefix) },
            "a zero duration is not a content duration"
        )
        XCTAssertNil(store.getContentDurationSeconds(id: "tt1", type: "movie"))

        recordWatchProgress(store: store, identity: identity(), event: event(position: 5, duration: 90))
        XCTAssertEqual(store.getContentDurationSeconds(id: "tt1", type: "movie"), 90)
    }
}
