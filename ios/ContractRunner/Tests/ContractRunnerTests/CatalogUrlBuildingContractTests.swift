import XCTest
@testable import ContractRunner

final class CatalogUrlBuildingContractTests: XCTestCase {
    func testCatalogUrlBuildingFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "catalog_url_building")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one catalog_url_building fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("catalog_url_building", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let actual = buildCatalogUrls(
                baseUrl: try requireString(input, "base_url", fixture: fixtureURL),
                encodedQuery: optionalString(input, "encoded_query"),
                mediaType: try requireString(input, "media_type", fixture: fixtureURL),
                catalogId: try requireString(input, "catalog_id", fixture: fixtureURL),
                skip: try requireInt(input, "skip", fixture: fixtureURL),
                limit: try requireInt(input, "limit", fixture: fixtureURL),
                filters: try requireArray(input, "filters", fixture: fixtureURL).map { try parseFilter($0, fixture: fixtureURL) }
            )

            XCTAssertEqual(try stringArrayValue(try requireArray(expected, "urls", fixture: fixtureURL), fixture: fixtureURL, field: "urls"), actual, "\(caseId): urls")
        }
    }

    private func parseFilter(_ value: Any, fixture: URL) throws -> CatalogFilter {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): filter must be an object")
        }

        return CatalogFilter(
            key: try requireString(object, "key", fixture: fixture),
            value: try requireString(object, "value", fixture: fixture)
        )
    }
}

private func stringArrayValue(_ array: [Any], fixture: URL, field: String) throws -> [String] {
    var strings: [String] = []
    for value in array {
        guard let string = value as? String else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): \(field) entries must be strings")
        }
        strings.append(string)
    }
    return strings
}
