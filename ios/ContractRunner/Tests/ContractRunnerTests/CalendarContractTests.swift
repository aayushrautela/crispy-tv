import XCTest
@testable import ContractRunner

final class CalendarContractTests: XCTestCase {
    func testCalendarFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "calendar_contract")
            .filter { $0.path.contains("/v3/") }
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one calendar_contract v3 fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            XCTAssertEqual("calendar_contract", try requireString(root, "suite", fixture: fixtureURL), "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let payload = try requireObject(input, "payload", fixture: fixtureURL)

            let actual = normalizeCalendarEnvelope(payload: payload)
            let expectedValid = try requireBool(expected, "valid", fixture: fixtureURL)

            XCTAssertEqual(expectedValid, actual != nil, "\(caseId): valid")
        }
    }
}
