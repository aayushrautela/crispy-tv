import Foundation
import XCTest
@testable import CrispyKit

/// The playback source's own rules: what travels with a stream.
final class PlaybackSourceTests: XCTestCase {
    func testResumingKeepsTheHeadersAndTheSubtitles() {
        let subtitle = PlaybackSubtitle(
            url: makeURL("https://cdn.example/en.vtt"),
            label: "English",
            language: "en"
        )
        let source = PlaybackSource(
            url: makeURL("https://cdn.example/movie.mkv"),
            headers: ["Referer": "https://addon.example/"],
            subtitles: [subtitle]
        )

        let resumed = source.resuming(at: 42_000)

        XCTAssertEqual(resumed.resumePositionMs, 42_000)
        XCTAssertEqual(resumed.url, source.url)
        XCTAssertEqual(resumed.headers, ["Referer": "https://addon.example/"])
        XCTAssertEqual(resumed.subtitles, [subtitle])
    }

    func testAnAddonStreamCarriesItsSubtitlesIntoTheSource() {
        let stream = StreamSource(
            id: "s1",
            addonId: "org.example.addon",
            addonName: "Example",
            name: "Example",
            title: "Example 1080p",
            url: "https://cdn.example/movie.mkv",
            infoHash: nil,
            fileIdx: nil,
            headers: ["User-Agent": "Example/1.0"],
            subtitles: [
                StreamSubtitleOption(
                    url: "https://cdn.example/en.vtt",
                    language: "en",
                    name: "English"
                ),
                StreamSubtitleOption(url: "https://cdn.example/es.vtt", language: "es"),
            ]
        )

        let source = stream.playbackSource
        XCTAssertEqual(source?.headers, ["User-Agent": "Example/1.0"])
        XCTAssertEqual(source?.subtitles.count, 2)
        XCTAssertEqual(source?.subtitles.first?.label, "English")
        XCTAssertEqual(source?.subtitles.first?.language, "en")
        XCTAssertEqual(source?.subtitles.last?.label, nil)
        XCTAssertEqual(source?.subtitles.last?.language, "es")
    }

    func testASubtitleWithoutAParsableURLIsDropped() {
        let stream = StreamSource(
            id: "s1",
            addonId: "org.example.addon",
            addonName: "Example",
            name: "Example",
            title: "Example 1080p",
            url: "https://cdn.example/movie.mkv",
            infoHash: nil,
            fileIdx: nil,
            subtitles: [
                StreamSubtitleOption(url: "not a url", language: "en"),
                StreamSubtitleOption(url: "https://cdn.example/en.vtt", language: "en"),
            ]
        )

        let source = stream.playbackSource

        XCTAssertEqual(source?.subtitles.count, 1)
        XCTAssertEqual(source?.subtitles.first?.url.absoluteString, "https://cdn.example/en.vtt")
    }

    func testASubtitleNeedsAScheme() {
        XCTAssertNil(PlaybackSubtitle(rawUrl: "/subtitles/en.vtt"))
        XCTAssertNil(PlaybackSubtitle(rawUrl: "   "))
        XCTAssertNotNil(PlaybackSubtitle(rawUrl: " https://cdn.example/en.vtt "))
    }

    private func makeURL(_ value: String) -> URL {
        guard let url = URL(string: value) else {
            preconditionFailure("\(value) is not a URL")
        }
        return url
    }
}
