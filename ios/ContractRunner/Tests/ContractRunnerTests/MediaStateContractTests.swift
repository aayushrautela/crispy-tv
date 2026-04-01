import XCTest
@testable import ContractRunner

final class MediaStateContractTests: XCTestCase {
    func testMediaStateFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "media_state_contract")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one media_state_contract fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            XCTAssertEqual("media_state_contract", try requireString(root, "suite", fixture: fixtureURL), "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let kind = try requireString(input, "kind", fixture: fixtureURL)
            let payload = try requireObject(input, "payload", fixture: fixtureURL)

            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let expectedValid = try requireBool(expected, "valid", fixture: fixtureURL)
            let expectedNormalized = try optionalObject(expected, "normalized").map { try parseNormalized($0, fixture: fixtureURL) }

            let actual = normalizeMediaStateCard(payload: payload, kind: kind)

            XCTAssertEqual(expectedValid, actual != nil, "\(caseId): valid")
            XCTAssertEqual(expectedNormalized, actual, "\(caseId): normalized")
        }
    }

    private func parseNormalized(_ object: [String: Any], fixture: URL) throws -> MediaStateNormalized {
        return MediaStateNormalized(
            cardFamily: try requireString(object, "card_family", fixture: fixture),
            mediaType: optionalString(object, "media_type"),
            itemId: optionalString(object, "item_id"),
            provider: optionalString(object, "provider"),
            providerId: optionalString(object, "provider_id"),
            title: optionalString(object, "title"),
            posterUrl: optionalString(object, "poster_url"),
            backdropUrl: optionalString(object, "backdrop_url"),
            subtitle: optionalString(object, "subtitle"),
            progressPercent: optionalDouble(object, "progress_percent"),
            watchedAt: optionalString(object, "watched_at"),
            lastActivityAt: optionalString(object, "last_activity_at"),
            origins: try optionalArrayOfStrings(object, "origins", fixture: fixture),
            dismissible: optionalBool(object, "dismissible"),
            layout: optionalString(object, "layout")
        )
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

private func optionalBool(_ object: [String: Any], _ key: String) -> Bool? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    return value as? Bool
}

private func optionalArrayOfStrings(_ object: [String: Any], _ key: String, fixture: URL) throws -> [String]? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    guard let array = value as? [Any] else {
        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): \(key) must be array or null")
    }
    return try stringArray(array, fixture: fixture, key: key)
}
