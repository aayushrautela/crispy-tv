import XCTest
@testable import ContractRunner

final class MediaIdsContractTests: XCTestCase {
    func testMediaIdFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "media_ids")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one media_ids fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("media_ids", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let actual = normalizeNuvioMediaId(try requireString(input, "raw", fixture: fixtureURL))

            XCTAssertEqual(
                try requireString(expected, "content_id", fixture: fixtureURL),
                actual.contentId,
                "\(caseId): content_id"
            )
            XCTAssertEqual(
                optionalString(expected, "video_id"),
                actual.videoId,
                "\(caseId): video_id"
            )
            XCTAssertEqual(
                try requireBool(expected, "is_episode", fixture: fixtureURL),
                actual.isEpisode,
                "\(caseId): is_episode"
            )
            XCTAssertEqual(optionalInt(expected, "season"), actual.season, "\(caseId): season")
            XCTAssertEqual(optionalInt(expected, "episode"), actual.episode, "\(caseId): episode")
            XCTAssertEqual(
                try requireString(expected, "kind", fixture: fixtureURL),
                actual.kind,
                "\(caseId): kind"
            )
            XCTAssertEqual(
                try requireString(expected, "addon_lookup_id", fixture: fixtureURL),
                actual.addonLookupId,
                "\(caseId): addon_lookup_id"
            )
        }
    }
}
