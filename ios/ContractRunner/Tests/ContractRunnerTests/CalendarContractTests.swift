import XCTest
@testable import ContractRunner

final class CalendarContractTests: XCTestCase {
    func testCalendarFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "calendar_contract")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one calendar_contract fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            XCTAssertEqual("calendar_contract", try requireString(root, "suite", fixture: fixtureURL), "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let route = try requireString(input, "route", fixture: fixtureURL)
            let payload = try requireObject(input, "payload", fixture: fixtureURL)

            let actual = normalizeCalendarEnvelope(payload: payload, route: route)
            let expectedValid = try requireBool(expected, "valid", fixture: fixtureURL)
            let expectedNormalized = try optionalObject(expected, "normalized").map { try parseEnvelope($0, fixture: fixtureURL) }

            XCTAssertEqual(expectedValid, actual != nil, "\(caseId): valid")
            XCTAssertEqual(expectedNormalized, actual, "\(caseId): normalized")
        }
    }

    private func parseEnvelope(_ object: [String: Any], fixture: URL) throws -> CalendarContractEnvelope {
        return CalendarContractEnvelope(
            profileId: try requireString(object, "profile_id", fixture: fixture),
            source: try requireString(object, "source", fixture: fixture),
            generatedAt: try requireString(object, "generated_at", fixture: fixture),
            kind: optionalString(object, "kind"),
            items: try requireArray(object, "items", fixture: fixture).map { try parseItem($0, fixture: fixture) }
        )
    }

    private func parseItem(_ value: Any, fixture: URL) throws -> CalendarContractItem {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): item must be an object")
        }
        return CalendarContractItem(
            bucket: try requireString(object, "bucket", fixture: fixture),
            media: try parseLandscapeCard(try requireObject(object, "media", fixture: fixture), fixture: fixture),
            relatedShow: try parseRegularCard(try requireObject(object, "related_show", fixture: fixture), fixture: fixture),
            airDate: optionalString(object, "air_date"),
            watched: try requireBool(object, "watched", fixture: fixture)
        )
    }

    private func parseRegularCard(_ object: [String: Any], fixture: URL) throws -> ContractRegularCard {
        return ContractRegularCard(
            mediaType: try requireString(object, "media_type", fixture: fixture),
            mediaKey: try requireString(object, "media_key", fixture: fixture),
            provider: try requireString(object, "provider", fixture: fixture),
            providerId: try requireString(object, "provider_id", fixture: fixture),
            title: try requireString(object, "title", fixture: fixture),
            posterUrl: try requireString(object, "poster_url", fixture: fixture),
            releaseYear: optionalInt(object, "release_year"),
            rating: optionalDouble(object, "rating"),
            genre: optionalString(object, "genre"),
            subtitle: optionalString(object, "subtitle")
        )
    }

    private func parseLandscapeCard(_ object: [String: Any], fixture: URL) throws -> ContractLandscapeCard {
        return ContractLandscapeCard(
            mediaType: try requireString(object, "media_type", fixture: fixture),
            mediaKey: try requireString(object, "media_key", fixture: fixture),
            provider: try requireString(object, "provider", fixture: fixture),
            providerId: try requireString(object, "provider_id", fixture: fixture),
            title: try requireString(object, "title", fixture: fixture),
            posterUrl: try requireString(object, "poster_url", fixture: fixture),
            backdropUrl: try requireString(object, "backdrop_url", fixture: fixture),
            releaseYear: optionalInt(object, "release_year"),
            rating: optionalDouble(object, "rating"),
            genre: optionalString(object, "genre"),
            seasonNumber: optionalInt(object, "season_number"),
            episodeNumber: optionalInt(object, "episode_number"),
            episodeTitle: optionalString(object, "episode_title"),
            airDate: optionalString(object, "air_date"),
            runtimeMinutes: optionalInt(object, "runtime_minutes")
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
    if let doubleValue = value as? Double {
        return doubleValue
    }
    if let number = value as? NSNumber {
        return number.doubleValue
    }
    return nil
}
