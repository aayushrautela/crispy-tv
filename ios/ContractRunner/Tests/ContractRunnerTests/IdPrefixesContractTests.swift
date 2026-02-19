import XCTest
@testable import ContractRunner

final class IdPrefixesContractTests: XCTestCase {
    func testIdPrefixFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "id_prefixes")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one id_prefixes fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("id_prefixes", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)

            let actual = formatIdForIdPrefixes(
                try requireString(input, "raw", fixture: fixtureURL),
                mediaType: try requireString(input, "media_type", fixture: fixtureURL),
                idPrefixes: try stringArray(try requireArray(input, "id_prefixes", fixture: fixtureURL), fixture: fixtureURL, key: "input.id_prefixes")
            )

            XCTAssertEqual(optionalString(expected, "formatted_id"), actual, "\(caseId): formatted_id")
        }
    }
}
