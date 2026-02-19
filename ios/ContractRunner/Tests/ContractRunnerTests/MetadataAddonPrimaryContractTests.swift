import XCTest
@testable import ContractRunner

final class MetadataAddonPrimaryContractTests: XCTestCase {
    func testMetadataAddonPrimaryFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "metadata_addon_primary")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one metadata_addon_primary fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("metadata_addon_primary", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let preferredAddonId = optionalString(input, "preferred_addon_id")
            let addonArray = try requireArray(input, "addon_results", fixture: fixtureURL)
            let addonResults: [AddonMetadataCandidate] = try addonArray.map { value in
                guard let entry = value as? [String: Any] else {
                    throw ContractTestError.invalidFixture("\(fixtureURL.lastPathComponent): addon_results entry must be object")
                }
                return AddonMetadataCandidate(
                    addonId: try requireString(entry, "addon_id", fixture: fixtureURL),
                    mediaId: try requireString(entry, "media_id", fixture: fixtureURL),
                    title: try requireString(entry, "title", fixture: fixtureURL)
                )
            }

            let actual = mergeAddonPrimaryMetadata(addonResults, preferredAddonId: preferredAddonId)
            XCTAssertEqual(
                try requireString(expected, "primary_id", fixture: fixtureURL),
                actual.primaryId,
                "\(caseId): primary_id"
            )
            XCTAssertEqual(
                try requireString(expected, "title", fixture: fixtureURL),
                actual.title,
                "\(caseId): title"
            )
            let expectedSources = try stringArray(
                try requireArray(expected, "sources", fixture: fixtureURL),
                fixture: fixtureURL,
                key: "sources"
            )
            XCTAssertEqual(expectedSources, actual.sources, "\(caseId): sources")
        }
    }
}
