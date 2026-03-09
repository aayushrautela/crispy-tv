import XCTest
@testable import ContractRunner

final class NextEpisodeContractTests: XCTestCase {
    func testNextEpisodeFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "next_episode")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one next_episode fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("next_episode", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let episodes = try requireArray(input, "episodes", fixture: fixtureURL).map {
                try parseEpisode($0, fixture: fixtureURL)
            }

            let actual = findNextEpisode(
                currentSeason: try requireInt(input, "current_season", fixture: fixtureURL),
                currentEpisode: try requireInt(input, "current_episode", fixture: fixtureURL),
                episodes: episodes,
                watchedSet: try optionalStringSet(input["watched_set"], fixture: fixtureURL, field: "watched_set"),
                showId: optionalString(input, "show_id"),
                nowMs: Int64(try requireInt(root, "now_ms", fixture: fixtureURL))
            )

            XCTAssertEqual(try parseExpectedResult(expected, fixture: fixtureURL), actual, "\(caseId): result")
        }
    }

    private func parseEpisode(_ value: Any, fixture: URL) throws -> EpisodeInfo {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): episode must be an object")
        }

        return EpisodeInfo(
            season: try requireInt(object, "season", fixture: fixture),
            episode: try requireInt(object, "episode", fixture: fixture),
            title: optionalString(object, "title"),
            released: optionalString(object, "released")
        )
    }

    private func parseExpectedResult(_ object: [String: Any], fixture: URL) throws -> NextEpisodeResult? {
        guard let value = object["result"] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing result")
        }
        if value is NSNull {
            return nil
        }

        guard let result = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): result must be an object or null")
        }

        return NextEpisodeResult(
            season: try requireInt(result, "season", fixture: fixture),
            episode: try requireInt(result, "episode", fixture: fixture),
            title: optionalString(result, "title")
        )
    }
}

private func optionalStringSet(_ value: Any?, fixture: URL, field: String) throws -> Set<String>? {
    guard let value else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    guard let array = value as? [Any] else {
        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): \(field) must be an array")
    }

    var strings: [String] = []
    for entry in array {
        guard let string = entry as? String else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): \(field) entries must be strings")
        }
        strings.append(string)
    }
    return Set(strings)
}
