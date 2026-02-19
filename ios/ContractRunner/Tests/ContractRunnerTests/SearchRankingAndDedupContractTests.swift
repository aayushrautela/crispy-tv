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
            let addonResults =
                try requireArray(input, "addon_results", fixture: fixtureURL).enumerated().map { addonIndex, addonElement in
                    guard let addonObject = addonElement as? [String: Any] else {
                        throw ContractTestError.invalidFixture(
                            "\(fixtureURL.lastPathComponent): input.addon_results[\(addonIndex)] must be object"
                        )
                    }

                    let metas =
                        try requireArray(addonObject, "metas", fixture: fixtureURL).enumerated().map { metaIndex, metaElement in
                            guard let meta = metaElement as? [String: Any] else {
                                throw ContractTestError.invalidFixture(
                                    "\(fixtureURL.lastPathComponent): input.addon_results[\(addonIndex)].metas[\(metaIndex)] must be object"
                                )
                            }
                            return SearchMetaInput(
                                id: try requireString(meta, "id", fixture: fixtureURL),
                                title: try requireString(meta, "title", fixture: fixtureURL)
                            )
                        }

                    return AddonSearchResult(
                        addonId: try requireString(addonObject, "addon_id", fixture: fixtureURL),
                        metas: metas
                    )
                }

            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let expectedMerged =
                try requireArray(expected, "merged", fixture: fixtureURL).enumerated().map { index, item in
                    guard let meta = item as? [String: Any] else {
                        throw ContractTestError.invalidFixture(
                            "\(fixtureURL.lastPathComponent): expected.merged[\(index)] must be object"
                        )
                    }
                    return [
                        try requireString(meta, "id", fixture: fixtureURL),
                        try requireString(meta, "title", fixture: fixtureURL),
                        try requireString(meta, "addon_id", fixture: fixtureURL)
                    ]
                }

            let actualMerged =
                mergeSearchResults(
                    addonResults,
                    preferredAddonId: optionalString(input, "preferred_addon_id")
                ).map { item in
                    [item.id, item.title, item.addonId]
                }

            XCTAssertEqual(expectedMerged, actualMerged, "\(caseId): merged")
        }
    }
}
