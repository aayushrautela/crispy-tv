import XCTest
@testable import ContractRunner

final class BrowseTitlesContractTests: XCTestCase {
    func testBrowseTitlesFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "browse_titles")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one browse_titles fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("browse_titles", suite, "\(caseId): wrong suite")
            XCTAssertEqual(1, try requireInt(root, "contract_version", fixture: fixtureURL), "\(caseId): wrong contract_version")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let combo = try parseCombo(try requireObject(input, "combo", fixture: fixtureURL), fixture: fixtureURL)
            let page = try requireInt(input, "page", fixture: fixtureURL)
            let responses = try parseResponses(try requireObject(input, "responses", fixture: fixtureURL), fixture: fixtureURL)

            XCTAssertEqual(
                try requireArray(expected, "requests", fixture: fixtureURL).map { try parseRequest($0, fixture: fixtureURL) },
                planBrowseRequests(combo: combo, page: page),
                "\(caseId): requests"
            )

            XCTAssertEqual(
                BrowsePageResult(
                    itemIds: try stringArray(try requireArray(expected, "items", fixture: fixtureURL), fixture: fixtureURL, key: "items"),
                    hasMore: try requireBool(expected, "has_more", fixture: fixtureURL),
                    nextPage: optionalInt(expected, "next_page")
                ),
                mergeBrowsePage(combo: combo, page: page, responses: responses),
                "\(caseId): merged page"
            )
        }
    }

    private func parseCombo(_ object: [String: Any], fixture: URL) throws -> BrowseCombo {
        return BrowseCombo(
            type: try requireString(object, "type", fixture: fixture),
            genre: optionalString(object, "genre"),
            sort: try requireString(object, "sort", fixture: fixture)
        )
    }

    private func parseResponses(_ object: [String: Any], fixture: URL) throws -> [String: BrowseTypeResponse] {
        var responses: [String: BrowseTypeResponse] = [:]
        for key in ["movie", "series"] {
            guard let value = object[key], !(value is NSNull) else {
                continue
            }
            guard let response = value as? [String: Any] else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): \(key) response must be an object")
            }
            responses[key] = BrowseTypeResponse(
                items: try requireArray(response, "items", fixture: fixture).map { value in
                    guard let item = value as? [String: Any] else {
                        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): \(key) item must be an object")
                    }
                    return BrowseTypeItem(
                        itemId: try requireString(item, "item_id", fixture: fixture),
                        type: try requireString(item, "type", fixture: fixture)
                    )
                },
                hasMore: try requireBool(response, "has_more", fixture: fixture)
            )
        }
        return responses
    }

    private func parseRequest(_ value: Any, fixture: URL) throws -> BrowseRequest {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): request must be an object")
        }
        return BrowseRequest(
            type: try requireString(object, "type", fixture: fixture),
            genre: optionalString(object, "genre"),
            sort: try requireString(object, "sort", fixture: fixture),
            page: try requireInt(object, "page", fixture: fixture)
        )
    }
}