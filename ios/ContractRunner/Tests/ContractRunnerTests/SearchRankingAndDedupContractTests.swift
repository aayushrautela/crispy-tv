import XCTest
@testable import ContractRunner

final class SearchRankingAndDedupContractTests: XCTestCase {
    func testSearchRankingFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "search_ranking_and_dedup")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one search_ranking_and_dedup fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("search_ranking_and_dedup", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let results =
                try requireArray(input, "results", fixture: fixtureURL).enumerated().map { index, element in
                    guard let result = element as? [String: Any] else {
                        throw ContractTestError.invalidFixture(
                            "\(fixtureURL.lastPathComponent): input.results[\(index)] must be object"
                        )
                    }
                    return TmdbSearchResultInput(
                        mediaType: try requireString(result, "media_type", fixture: fixtureURL),
                        id: try requireInt(result, "id", fixture: fixtureURL),
                        title: optionalString(result, "title"),
                        name: optionalString(result, "name"),
                        releaseDate: optionalString(result, "release_date"),
                        firstAirDate: optionalString(result, "first_air_date"),
                        posterPath: optionalString(result, "poster_path"),
                        profilePath: optionalString(result, "profile_path"),
                        voteAverage: optionalDouble(result, "vote_average")
                    )
                }

            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let expectedItems =
                try requireArray(expected, "items", fixture: fixtureURL).enumerated().map { index, element in
                    guard let item = element as? [String: Any] else {
                        throw ContractTestError.invalidFixture(
                            "\(fixtureURL.lastPathComponent): expected.items[\(index)] must be object"
                        )
                    }
                    return NormalizedSearchItem(
                        id: try requireString(item, "id", fixture: fixtureURL),
                        type: try requireString(item, "type", fixture: fixtureURL),
                        title: try requireString(item, "title", fixture: fixtureURL),
                        year: optionalInt(item, "year"),
                        imageUrl: optionalString(item, "image_url"),
                        rating: optionalDouble(item, "rating")
                    )
                }

            let actualItems = normalizeTmdbSearchResults(results)

            XCTAssertEqual(expectedItems, actualItems, "\(caseId): items")
        }
    }
}

private func optionalDouble(_ object: [String: Any], _ key: String) -> Double? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    if let double = value as? Double {
        return double
    }
    if let number = value as? NSNumber {
        return number.doubleValue
    }
    if let string = value as? String {
        return Double(string)
    }
    return nil
}
