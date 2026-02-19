import XCTest
@testable import ContractRunner

final class CatalogUrlBuildingContractTests: XCTestCase {
    func testCatalogUrlFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "catalog_url_building")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one catalog_url_building fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("catalog_url_building", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let filters =
                try requireArray(input, "filters", fixture: fixtureURL).enumerated().map { index, element in
                    guard let filter = element as? [String: Any] else {
                        throw ContractTestError.invalidFixture(
                            "\(fixtureURL.lastPathComponent): input.filters[\(index)] must be object"
                        )
                    }
                    return CatalogFilter(
                        key: try requireString(filter, "key", fixture: fixtureURL),
                        value: try requireString(filter, "value", fixture: fixtureURL)
                    )
                }

            let request = CatalogRequestInput(
                baseUrl: try requireString(input, "base_url", fixture: fixtureURL),
                mediaType: try requireString(input, "media_type", fixture: fixtureURL),
                catalogId: try requireString(input, "catalog_id", fixture: fixtureURL),
                page: try requireInt(input, "page", fixture: fixtureURL),
                pageSize: try requireInt(input, "page_size", fixture: fixtureURL),
                filters: filters,
                encodedAddonQuery: optionalString(input, "encoded_addon_query")
            )

            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let expectedUrls = try stringArray(try requireArray(expected, "urls", fixture: fixtureURL), fixture: fixtureURL, key: "expected.urls")
            let actualUrls = buildCatalogRequestUrls(request)

            XCTAssertEqual(expectedUrls, actualUrls, "\(caseId): urls")
        }
    }
}
