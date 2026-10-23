import ContractRunner
import Foundation
import XCTest
@testable import CrispyKit

/// The file-backed pending-mutation store's tests.
///
/// The store is the front half of the offline outbox: one JSON file read
/// tolerantly on load and replaced wholesale on save. These tests exercise
/// the file round trip for every mutation kind, and the decode table's
/// tolerances directly — an `inflight` entry reading as `pending`, an unknown
/// kind dropping, a `nil` rating surviving as an explicit null, an episode
/// number of 0 staying a real 0.
final class PendingMutationStoreTests: XCTestCase {
    private func makeStore() -> (FileBackedPendingMutationStore, URL) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("pending-mutations-tests-\(UUID().uuidString)", isDirectory: true)
        let url = directory.appendingPathComponent("pending_mutations.json", isDirectory: false)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return (FileBackedPendingMutationStore(fileURL: url), url)
    }

    private func watchlist(
        id: String = "w1",
        entityId: String = "tt1",
        createdAtMs: Int64 = 1,
        status: MutationStatus = .pending,
        desired: Bool = true
    ) -> WatchlistMutation {
        WatchlistMutation(
            id: id, titleItemId: "tt1", entityId: entityId, createdAtMs: createdAtMs,
            attempt: 0, status: status, nextAttemptAtMs: 5, desired: desired
        )
    }

    private func titleWatched(
        id: String = "t1",
        status: MutationStatus = .pending,
        contentType: MediaContentType = .series,
        desired: Bool = false
    ) -> TitleWatchedMutation {
        TitleWatchedMutation(
            id: id, titleItemId: "tt1", entityId: "tt1", createdAtMs: 2,
            attempt: 1, status: status, nextAttemptAtMs: 6,
            contentType: contentType, desired: desired
        )
    }

    private func rating(
        id: String = "r1",
        status: MutationStatus = .pending,
        desired: Bool? = nil
    ) -> RatingMutation {
        RatingMutation(
            id: id, titleItemId: "tt1", entityId: "tt1", createdAtMs: 3,
            attempt: 2, status: status, nextAttemptAtMs: 7, desired: desired
        )
    }

    private func episodeWatched(
        id: String = "e1",
        status: MutationStatus = .pending,
        episode: Int? = 2
    ) -> EpisodeWatchedMutation {
        EpisodeWatchedMutation(
            id: id, titleItemId: "tt1", entityId: "tt1", createdAtMs: 4,
            attempt: 0, status: status, nextAttemptAtMs: 8,
            itemId: "tt1", season: 1, episode: episode, videoId: "v1", desired: true
        )
    }

    private func seasonWatched(
        id: String = "s1",
        status: MutationStatus = .pending
    ) -> SeasonWatchedMutation {
        SeasonWatchedMutation(
            id: id, titleItemId: "tt1", entityId: "tt1", createdAtMs: 5,
            attempt: 0, status: status, nextAttemptAtMs: 9,
            seasonItemId: "tt1", seasonNumber: 3, desired: false
        )
    }

    func testRoundTripThroughTheFileForAllFiveKinds() async {
        let (store, _) = makeStore()
        let mutations: [any UserMutation] = [
            watchlist(status: .pending, desired: true),
            titleWatched(status: .inflight, contentType: .anime, desired: false),
            rating(status: .failed(reason: "boom", retryable: false), desired: nil),
            episodeWatched(status: .conflict(serverValue: "server says no"), episode: 0),
            seasonWatched(status: .pending),
        ]

        await store.saveAll(mutations)
        let loaded = await store.loadAll()

        XCTAssertEqual(loaded.count, 5)

        let watchlistEntry = loaded.first { $0.id == "w1" } as? WatchlistMutation
        XCTAssertEqual(watchlistEntry?.entityId, "tt1")
        XCTAssertEqual(watchlistEntry?.status, .pending)
        XCTAssertEqual(watchlistEntry?.desired, true)

        // An inflight entry reads back as pending: the backend never saw it.
        let titleEntry = loaded.first { $0.id == "t1" } as? TitleWatchedMutation
        XCTAssertEqual(titleEntry?.status, .pending)
        XCTAssertEqual(titleEntry?.contentType, .anime)
        XCTAssertEqual(titleEntry?.desired, false)
        XCTAssertEqual(titleEntry?.attempt, 1)
        XCTAssertEqual(titleEntry?.nextAttemptAtMs, 6)

        let ratingEntry = loaded.first { $0.id == "r1" } as? RatingMutation
        XCTAssertEqual(
            ratingEntry?.status,
            .failed(reason: "boom", retryable: false),
            "a labelled failed entry keeps its reason and its retryable flag"
        )
        XCTAssertNil(ratingEntry?.desired)

        let episodeEntry = loaded.first { $0.id == "e1" } as? EpisodeWatchedMutation
        XCTAssertEqual(episodeEntry?.status, .conflict(serverValue: "server says no"))
        XCTAssertEqual(episodeEntry?.season, 1)
        XCTAssertEqual(episodeEntry?.episode, 0, "a stored 0 is a real episode number")
        XCTAssertEqual(episodeEntry?.videoId, "v1")
        XCTAssertEqual(episodeEntry?.desired, true)

        let seasonEntry = loaded.first { $0.id == "s1" } as? SeasonWatchedMutation
        XCTAssertEqual(seasonEntry?.seasonNumber, 3)
        XCTAssertEqual(seasonEntry?.desired, false)
    }

    func testSaveCreatesTheParentDirectory() async {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("pending-mutations-tests-\(UUID().uuidString)", isDirectory: true)
        let directory = root.appendingPathComponent("nested", isDirectory: true)
        let url = directory.appendingPathComponent("pending_mutations.json", isDirectory: false)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        let store = FileBackedPendingMutationStore(fileURL: url)

        await store.saveAll([watchlist()])

        let loaded = await store.loadAll()
        XCTAssertEqual(loaded.count, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    }

    func testMissingAndCorruptFileReadAsEmpty() async {
        let (store, url) = makeStore()

        let missing = await store.loadAll()
        XCTAssertTrue(missing.isEmpty)

        try? Data("]{ not json at all[".utf8).write(to: url)
        let corrupt = await store.loadAll()
        XCTAssertTrue(corrupt.isEmpty, "an unreadable file is an empty queue, not a crash")
    }

    func testUnknownKindDropsTheEntry() {
        let encoded = encodePendingMutation(watchlist())
        var bogus = encoded
        bogus["type"] = "nope"
        XCTAssertNil(decodePendingMutation(bogus))
    }

    func testMissingRequiredFieldDropsTheEntry() {
        let encoded = encodePendingMutation(watchlist())
        var noId = encoded
        noId.removeValue(forKey: "id")
        XCTAssertNil(decodePendingMutation(noId))

        var noDesired = encoded
        noDesired.removeValue(forKey: "desired")
        XCTAssertNil(decodePendingMutation(noDesired), "a watchlist entry without a desired is unreadable")
    }

    func testInflightReadsAsPendingOnDecode() {
        let encoded = encodePendingMutation(titleWatched(status: .inflight))
        XCTAssertEqual(encoded["status"] as? String, "inflight", "the file still names it inflight")
        let decoded = decodePendingMutation(encoded)
        XCTAssertEqual(decoded?.status, .pending)
    }

    func testRatingNilDesiredSurvivesAsAnExplicitNull() {
        let encoded = encodePendingMutation(rating(desired: nil))
        XCTAssertTrue(encoded["desired"] is NSNull, "clear is stored as null, not as an absent key")

        let decoded = decodePendingMutation(encoded)
        let ratingEntry = decoded as? RatingMutation
        XCTAssertNil(ratingEntry?.desired)
    }

    func testRatingTrueAndFalseSurvive() {
        let liked = decodePendingMutation(encodePendingMutation(rating(desired: true))) as? RatingMutation
        XCTAssertEqual(liked?.desired, true)

        let disliked = decodePendingMutation(encodePendingMutation(rating(desired: false))) as? RatingMutation
        XCTAssertEqual(disliked?.desired, false)
    }

    func testUnlabelledFailedReadsAsRetryable() {
        var encoded = encodePendingMutation(rating(status: .failed(reason: "boom", retryable: false)))
        encoded.removeValue(forKey: "statusReason")
        encoded.removeValue(forKey: "retryable")

        let decoded = decodePendingMutation(encoded)
        XCTAssertEqual(
            decoded?.status,
            .failed(reason: "", retryable: true),
            "the writer died before it could label the failure, so it stays retryable"
        )
    }

    func testEpisodeZeroIsRealAndAMissingKeyMeansNone() {
        let zeroEncoded = encodePendingMutation(episodeWatched(episode: 0))
        let zeroDecoded = decodePendingMutation(zeroEncoded) as? EpisodeWatchedMutation
        XCTAssertEqual(zeroDecoded?.episode, 0)

        let absentEncoded = encodePendingMutation(episodeWatched(episode: nil))
        XCTAssertNil(absentEncoded["episode"])
        let absentDecoded = decodePendingMutation(absentEncoded) as? EpisodeWatchedMutation
        XCTAssertNil(absentDecoded?.episode)
    }

    func testUnknownContentTypeReadsAsMovie() {
        var encoded = encodePendingMutation(titleWatched(contentType: .series))
        encoded["contentType"] = "mystery"
        let decoded = decodePendingMutation(encoded) as? TitleWatchedMutation
        XCTAssertEqual(decoded?.contentType, .movie)
    }
}
