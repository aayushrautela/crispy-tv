import XCTest
@testable import ContractRunner

final class OmdbContractTests: XCTestCase {
    func testOmdbFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "omdb")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one omdb fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("omdb", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let payload = try requireObject(input, "payload", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)

            let actualNormalizedImdbId = normalizeOmdbImdbId(optionalString(input, "imdb_id"))
            let actualDetails = normalizeOmdbDetails(
                ratings: try requireArray(payload, "ratings", fixture: fixtureURL).map { try parseRatingInput($0, fixture: fixtureURL) },
                metascore: optionalString(payload, "metascore"),
                imdbRating: optionalString(payload, "imdb_rating"),
                imdbVotes: optionalString(payload, "imdb_votes"),
                type: optionalString(payload, "type")
            )

            XCTAssertEqual(optionalString(expected, "normalized_imdb_id"), actualNormalizedImdbId, "\(caseId): normalized_imdb_id")
            XCTAssertEqual(
                try parseExpectedDetails(try requireObject(expected, "details", fixture: fixtureURL), fixture: fixtureURL),
                actualDetails,
                "\(caseId): details"
            )
        }
    }

    private func parseRatingInput(_ value: Any, fixture: URL) throws -> OmdbRatingInput {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): rating input must be an object")
        }

        return OmdbRatingInput(
            source: optionalString(object, "source"),
            value: optionalString(object, "value")
        )
    }

    private func parseExpectedDetails(_ object: [String: Any], fixture: URL) throws -> OmdbDetails {
        return OmdbDetails(
            ratings: try requireArray(object, "ratings", fixture: fixture).map { try parseRating($0, fixture: fixture) },
            metascore: optionalString(object, "metascore"),
            imdbRating: optionalString(object, "imdb_rating"),
            imdbVotes: optionalString(object, "imdb_votes"),
            type: optionalString(object, "type")
        )
    }

    private func parseRating(_ value: Any, fixture: URL) throws -> OmdbRating {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): rating must be an object")
        }

        return OmdbRating(
            source: try requireString(object, "source", fixture: fixture),
            value: try requireString(object, "value", fixture: fixture)
        )
    }
}
