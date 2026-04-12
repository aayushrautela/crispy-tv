import XCTest
@testable import ContractRunner

final class WatchCollectionsContractTests: XCTestCase {
    func testWatchCollectionFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "watch_collections_contract")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one watch_collections_contract fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            XCTAssertEqual("watch_collections_contract", try requireString(root, "suite", fixture: fixtureURL), "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let payload = try requireObject(input, "payload", fixture: fixtureURL)

            let actual = normalizeWatchCollectionEnvelope(payload: payload)
            let expectedValid = try requireBool(expected, "valid", fixture: fixtureURL)
            let expectedNormalized = try optionalObject(expected, "normalized").map { try parseEnvelope($0, fixture: fixtureURL) }

            XCTAssertEqual(expectedValid, actual != nil, "\(caseId): valid")
            XCTAssertEqual(expectedNormalized, actual, "\(caseId): normalized")
        }
    }

    private func parseEnvelope(_ object: [String: Any], fixture: URL) throws -> WatchCollectionContractEnvelope {
        let kind = try requireString(object, "kind", fixture: fixture)
        return WatchCollectionContractEnvelope(
            profileId: try requireString(object, "profile_id", fixture: fixture),
            kind: kind,
            source: try requireString(object, "source", fixture: fixture),
            generatedAt: try requireString(object, "generated_at", fixture: fixture),
            items: try requireArray(object, "items", fixture: fixture).map { try parseItem($0, fixture: fixture, kind: kind) },
            pageInfo: try parsePageInfo(try requireObject(object, "page_info", fixture: fixture), fixture: fixture)
        )
    }

    private func parseItem(_ value: Any, fixture: URL, kind: String) throws -> WatchCollectionContractItem {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): item must be an object")
        }
        switch kind {
        case "continue-watching":
            return .continueWatching(try parseContinueWatchingItem(object, fixture: fixture))
        case "history":
            return .history(try parseHistoryItem(object, fixture: fixture))
        case "watchlist":
            return .watchlist(try parseWatchlistItem(object, fixture: fixture))
        case "ratings":
            return .rating(try parseRatingItem(object, fixture: fixture))
        default:
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): unsupported kind \(kind)")
        }
    }

    private func parseContinueWatchingItem(_ object: [String: Any], fixture: URL) throws -> ContractContinueWatchingItem {
        return ContractContinueWatchingItem(
            id: try requireString(object, "id", fixture: fixture),
            media: try parseLandscapeCard(try requireObject(object, "media", fixture: fixture), fixture: fixture),
            progress: try optionalObject(object, "progress").map { try parseWatchProgress($0, fixture: fixture) },
            lastActivityAt: try requireString(object, "last_activity_at", fixture: fixture),
            origins: try stringArray(try requireArray(object, "origins", fixture: fixture), fixture: fixture, key: "origins"),
            dismissible: try requireBool(object, "dismissible", fixture: fixture)
        )
    }

    private func parseHistoryItem(_ object: [String: Any], fixture: URL) throws -> ContractHistoryItem {
        return ContractHistoryItem(
            id: try requireString(object, "id", fixture: fixture),
            media: try parseRegularCard(try requireObject(object, "media", fixture: fixture), fixture: fixture),
            watchedAt: try requireString(object, "watched_at", fixture: fixture),
            origins: try stringArray(try requireArray(object, "origins", fixture: fixture), fixture: fixture, key: "origins")
        )
    }

    private func parseWatchlistItem(_ object: [String: Any], fixture: URL) throws -> ContractWatchlistItem {
        return ContractWatchlistItem(
            id: try requireString(object, "id", fixture: fixture),
            media: try parseRegularCard(try requireObject(object, "media", fixture: fixture), fixture: fixture),
            addedAt: try requireString(object, "added_at", fixture: fixture),
            origins: try stringArray(try requireArray(object, "origins", fixture: fixture), fixture: fixture, key: "origins")
        )
    }

    private func parseRatingItem(_ object: [String: Any], fixture: URL) throws -> ContractRatingItem {
        return ContractRatingItem(
            id: try requireString(object, "id", fixture: fixture),
            media: try parseRegularCard(try requireObject(object, "media", fixture: fixture), fixture: fixture),
            rating: try parseRatingState(try requireObject(object, "rating", fixture: fixture), fixture: fixture),
            origins: try stringArray(try requireArray(object, "origins", fixture: fixture), fixture: fixture, key: "origins")
        )
    }

    private func parseRatingState(_ object: [String: Any], fixture: URL) throws -> ContractRatingState {
        return ContractRatingState(
            value: try requireDouble(object, "value", fixture: fixture),
            ratedAt: try requireString(object, "rated_at", fixture: fixture)
        )
    }

    private func parseWatchProgress(_ object: [String: Any], fixture: URL) throws -> ContractWatchProgress {
        return ContractWatchProgress(
            positionSeconds: optionalDouble(object, "position_seconds"),
            durationSeconds: optionalDouble(object, "duration_seconds"),
            progressPercent: try requireDouble(object, "progress_percent", fixture: fixture),
            lastPlayedAt: optionalString(object, "last_played_at")
        )
    }

    private func parsePageInfo(_ object: [String: Any], fixture: URL) throws -> ContractPageInfo {
        return ContractPageInfo(
            nextCursor: optionalString(object, "next_cursor"),
            hasMore: try requireBool(object, "has_more", fixture: fixture)
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

private func requireDouble(_ object: [String: Any], _ key: String, fixture: URL) throws -> Double {
    guard let value = object[key] else {
        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing double \(key)")
    }
    if let doubleValue = value as? Double {
        return doubleValue
    }
    if let number = value as? NSNumber {
        return number.doubleValue
    }
    throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing double \(key)")
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
