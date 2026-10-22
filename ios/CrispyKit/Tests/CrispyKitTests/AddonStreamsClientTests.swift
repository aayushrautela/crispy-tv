import Foundation
import XCTest
@testable import CrispyKit

/// The stream resolver's tests.
///
/// The resolver talks to addons over HTTP, so the tests hand it a transport
/// that answers from a table: the table is the whole addon world, and the
/// requests the resolver made are recorded, so a test can assert the URL it
/// asked for and not only what it answered.
final class AddonStreamsClientTests: XCTestCase {
    func testTheStreamResourceIsFetchedWithTheEncodedLookupId() async {
        let http = FakeStreamHttp(responses: [
            "https://addon.example/manifest.json": manifestJSON,
            "https://addon.example/stream/series/tt1234567%3A1%3A2.json": streamsJSON,
        ])
        let client = AddonStreamsClient(http: http)

        let streams = await client.resolveStreams(
            addons: [addon()],
            mediaType: .series,
            lookupId: "tt1234567:1:2"
        )

        XCTAssertEqual(streams.count, 1)
        XCTAssertTrue(
            http.requestedURLs.contains("https://addon.example/stream/series/tt1234567%3A1%3A2.json"),
            "asked for \(http.requestedURLs)"
        )
    }

    func testTheManifestQueryTravelsWithTheStreamRequest() async {
        let http = FakeStreamHttp(responses: [
            "https://addon.example/manifest.json?token=abc": manifestJSON,
            "https://addon.example/stream/movie/tt1234567.json?token=abc": streamsJSON,
        ])
        let client = AddonStreamsClient(http: http)

        let streams = await client.resolveStreams(
            addons: [addon(manifestUrl: "https://addon.example/manifest.json?token=abc")],
            mediaType: .movie,
            lookupId: "tt1234567"
        )

        XCTAssertEqual(streams.count, 1)
        XCTAssertTrue(
            http.requestedURLs.contains("https://addon.example/stream/movie/tt1234567.json?token=abc"),
            "asked for \(http.requestedURLs)"
        )
    }

    func testAnAddonThatServesNoStreamsIsSkipped() async {
        let http = FakeStreamHttp(responses: [
            "https://addon.example/manifest.json": metadataOnlyManifestJSON,
        ])
        let client = AddonStreamsClient(http: http)

        let streams = await client.resolveStreams(
            addons: [addon()],
            mediaType: .movie,
            lookupId: "tt1234567"
        )

        XCTAssertTrue(streams.isEmpty)
        XCTAssertFalse(http.requestedURLs.contains { $0.contains("/stream/") })
    }

    func testAnAddonThatDoesNotServeTheMediaTypeIsSkipped() async {
        let http = FakeStreamHttp(responses: [
            "https://addon.example/manifest.json": moviesOnlyManifestJSON,
        ])
        let client = AddonStreamsClient(http: http)

        let streams = await client.resolveStreams(
            addons: [addon()],
            mediaType: .series,
            lookupId: "tt1234567:1:2"
        )

        XCTAssertTrue(streams.isEmpty)
        XCTAssertFalse(http.requestedURLs.contains { $0.contains("/stream/") })
    }

    func testTheStreamIsParsed() async {
        let http = FakeStreamHttp(responses: [
            "https://addon.example/manifest.json": manifestJSON,
            "https://addon.example/stream/movie/tt1234567.json": streamsJSON,
        ])
        let client = AddonStreamsClient(http: http)

        let streams = await client.resolveStreams(
            addons: [addon()],
            mediaType: .movie,
            lookupId: "tt1234567"
        )

        let stream = streams.first
        XCTAssertEqual(stream?.name, "Example")
        XCTAssertEqual(stream?.title, "Example 1080p")
        XCTAssertEqual(stream?.url, "https://cdn.example/movie.mkv")
        XCTAssertEqual(stream?.headers["Referer"], "https://addon.example/")
        XCTAssertEqual(stream?.subtitles.count, 1)
        XCTAssertEqual(stream?.subtitles.first?.language, "en")
        XCTAssertEqual(stream?.qualityHeight, 1080)
    }

    func testATorrentStreamIsNotOffered() async {
        let http = FakeStreamHttp(responses: [
            "https://addon.example/manifest.json": manifestJSON,
            "https://addon.example/stream/movie/tt1234567.json": torrentStreamsJSON,
        ])
        let client = AddonStreamsClient(http: http)

        let streams = await client.resolveStreams(
            addons: [addon()],
            mediaType: .movie,
            lookupId: "tt1234567"
        )

        XCTAssertTrue(streams.isEmpty, "a torrent has no URL the player can open")
    }

    func testTheStreamsAreOrderedByQuality() async {
        let http = FakeStreamHttp(responses: [
            "https://addon.example/manifest.json": manifestJSON,
            "https://addon.example/stream/movie/tt1234567.json": twoQualitiesJSON,
        ])
        let client = AddonStreamsClient(http: http)

        let streams = await client.resolveStreams(
            addons: [addon()],
            mediaType: .movie,
            lookupId: "tt1234567"
        )

        XCTAssertEqual(streams.map { $0.qualityHeight }, [1080, 480])
    }

    func testAnAddonThatFailsDoesNotFailTheResolution() async {
        let http = FakeStreamHttp(responses: [
            "https://broken.example/manifest.json": "",
            "https://addon.example/manifest.json": manifestJSON,
            "https://addon.example/stream/movie/tt1234567.json": streamsJSON,
        ])
        let client = AddonStreamsClient(http: http)

        let streams = await client.resolveStreams(
            addons: [addon(manifestUrl: "https://broken.example/manifest.json"), addon()],
            mediaType: .movie,
            lookupId: "tt1234567"
        )

        XCTAssertEqual(streams.count, 1)
    }

    func testTheLookupIdIsBuiltFromTheImdbId() {
        XCTAssertEqual(
            StreamLookup.lookupId(imdbId: "tt1234567", itemId: "movie:1", season: nil, episode: nil),
            "tt1234567"
        )
        XCTAssertEqual(
            StreamLookup.lookupId(imdbId: "tt1234567", itemId: "movie:1", season: 2, episode: 5),
            "tt1234567:2:5"
        )
        // A season without an episode is no episode, so the item's own id stands.
        XCTAssertEqual(
            StreamLookup.lookupId(imdbId: "tt1234567", itemId: "movie:1", season: 2, episode: nil),
            "tt1234567"
        )
        // A backend id that is not an IMDb id is not an addon's id.
        XCTAssertNil(StreamLookup.lookupId(imdbId: nil, itemId: "movie:1", season: nil, episode: nil))
        XCTAssertEqual(StreamLookup.lookupId(imdbId: nil, itemId: "tt7654321", season: nil, episode: nil), "tt7654321")
    }

    func testThePathSegmentIsEncoded() {
        XCTAssertEqual(StreamLookup.encodePathSegment("tt1234567:1:2"), "tt1234567%3A1%3A2")
        XCTAssertEqual(StreamLookup.encodePathSegment("tt1234567"), "tt1234567")
        XCTAssertEqual(StreamLookup.encodePathSegment("a-b_c.d~e"), "a-b_c.d~e")
    }

    // MARK: - Fixtures

    private func addon(manifestUrl: String = "https://addon.example/manifest.json") -> AddonDescriptor {
        AddonDescriptor(id: "org.example.addon", manifestUrl: manifestUrl, name: "Example")
    }

    private let manifestJSON = """
    {
      "id": "org.example.addon",
      "name": "Example",
      "types": ["movie", "series"],
      "resources": ["stream"]
    }
    """

    private let metadataOnlyManifestJSON = """
    {
      "id": "org.example.addon",
      "name": "Example",
      "types": ["movie"],
      "resources": ["catalog"]
    }
    """

    private let moviesOnlyManifestJSON = """
    {
      "id": "org.example.addon",
      "name": "Example",
      "types": ["movie"],
      "resources": ["stream"]
    }
    """

    private let streamsJSON = """
    {
      "streams": [
        {
          "name": "Example",
          "title": "Example 1080p",
          "url": "https://cdn.example/movie.mkv",
          "behaviorHints": { "headers": { "Referer": "https://addon.example/" } },
          "subtitles": [ { "url": "https://cdn.example/en.vtt", "lang": "en" } ]
        }
      ]
    }
    """

    private let torrentStreamsJSON = """
    {
      "streams": [
        {
          "name": "Example",
          "title": "Example 1080p",
          "infoHash": "0123456789abcdef",
          "fileIdx": 0
        }
      ]
    }
    """

    private let twoQualitiesJSON = """
    {
      "streams": [
        { "name": "Example", "title": "Example 480p", "url": "https://cdn.example/low.mkv" },
        { "name": "Example", "title": "Example 1080p", "url": "https://cdn.example/high.mkv" }
      ]
    }
    """
}

/// The addon's world, answered from a table.
final class FakeStreamHttp: StreamHttpFetching, @unchecked Sendable {
    private let responses: [String: String]
    private let lock = NSLock()
    private var recorded: [String] = []

    init(responses: [String: String]) {
        self.responses = responses
    }

    var requestedURLs: [String] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    func get(url: URL, headers: [String: String], timeout: TimeInterval?) async throws -> CrispyHttpResponse {
        lock.lock()
        recorded.append(url.absoluteString)
        let body = responses[url.absoluteString]
        lock.unlock()
        guard let body else {
            return CrispyHttpResponse(code: 404, body: "")
        }
        return CrispyHttpResponse(code: 200, body: body)
    }
}
