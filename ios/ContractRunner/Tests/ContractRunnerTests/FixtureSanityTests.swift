import XCTest
@testable import ContractRunner

final class FixtureSanityTests: XCTestCase {
    func testFixtureDirectoriesAndRequiredFields() throws {
        let suites = [
            "player_machine",
            "media_ids",
            "id_prefixes",
            "catalog_url_building",
            "search_ranking_and_dedup",
            "metadata_addon_primary",
            "metadata_tmdb_enhancer",
            "storage_v1",
        ]

        for suite in suites {
            let fixtures = try FixtureLoader.listFixtureFiles(in: suite)
            XCTAssertFalse(fixtures.isEmpty, "No fixtures found for suite \(suite)")

            for url in fixtures {
                let root = try FixtureLoader.readJSONObject(from: url)

                XCTAssertNotNil(root["contract_version"], "Missing contract_version in \(url.path)")
                XCTAssertNotNil(root["suite"], "Missing suite in \(url.path)")
                XCTAssertNotNil(root["case_id"], "Missing case_id in \(url.path)")

                if let version = root["contract_version"] as? Int {
                    XCTAssertGreaterThan(version, 0, "contract_version must be > 0 in \(url.path)")
                } else {
                    XCTFail("contract_version must be integer in \(url.path)")
                }
            }
        }
    }
}
