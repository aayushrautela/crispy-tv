import XCTest
@testable import ContractRunner

final class UserMutationsContractTests: XCTestCase {
    func testFixturesResolveOptimisticState() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "optimistic_state")
        XCTAssertFalse(fixtures.isEmpty, "No fixtures found for suite optimistic_state")

        for url in fixtures {
            let root = try FixtureLoader.readJSONObject(from: url)
            let caseId = try requireString(root, "case_id", fixture: url)
            XCTAssertEqual("optimistic_state", try requireString(root, "suite", fixture: url), "\(caseId): wrong suite")

            let operation = try requireString(root, "operation", fixture: url)
            let input = try requireObject(root, "input", fixture: url)
            let expected = try requireObject(root, "expected", fixture: url)

            let snapshot = try parseSnapshot(try requireObject(input, "snapshot", fixture: url), fixture: url)
            let mutations = try parseMutations(try requireArray(input, "mutations", fixture: url), fixture: url)

            switch operation {
            case "derive":
                let actual = deriveUserState(snapshot: snapshot, mutations: mutations)
                try assertDerived(caseId, actual, expected, fixture: url)
            case "plan_outbox":
                let nowMs = optionalInt64(input, "now_ms") ?? 0
                let actual = planOutbox(mutations: mutations, nowMs: nowMs)
                let expectedActions = try parseExpectedActions(expected, fixture: url)
                XCTAssertEqual(expectedActions, actual, "\(caseId): actions")
            default:
                throw ContractTestError.invalidFixture("\(url.lastPathComponent): unknown operation \(operation)")
            }
        }
    }

    private func parseSnapshot(_ obj: [String: Any], fixture: URL) throws -> UserStateSnapshot {
        var episodeWatched: [String: Bool] = [:]
        if let ep = obj["episode_watched"] as? [String: Any] {
            for (k, v) in ep { episodeWatched[k] = v as? Bool ?? false }
        }
        var seasonWatched: [Int: Bool] = [:]
        if let sw = obj["season_watched"] as? [String: Any] {
            for (k, v) in sw { if let key = Int(k) { seasonWatched[key] = v as? Bool ?? false } }
        }
        return UserStateSnapshot(
            isInWatchlist: try requireBool(obj, "is_in_watchlist", fixture: fixture),
            isWatched: try requireBool(obj, "is_watched", fixture: fixture),
            isRated: try requireBool(obj, "is_rated", fixture: fixture),
            userRating: optionalInt(obj, "user_rating"),
            episodeWatched: episodeWatched,
            seasonWatched: seasonWatched
        )
    }

    private func parseMutations(_ array: [Any], fixture: URL) throws -> [UserMutation] {
        return try array.enumerated().map { index, value in
            guard let obj = value as? [String: Any] else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): expected object at mutations[\(index)]")
            }
            let kindRaw = try requireString(obj, "kind", fixture: fixture)
            let kind: MutationKind
            switch kindRaw {
            case "watchlist": kind = .watchlist
            case "title_watched": kind = .titleWatched
            case "episode_watched": kind = .episodeWatched
            case "season_watched": kind = .seasonWatched
            case "rating": kind = .rating
            default: throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): invalid kind at mutations[\(index)]")
            }
            let id = try requireString(obj, "id", fixture: fixture)
            let entityId = try requireString(obj, "entity_id", fixture: fixture)
            let createdAtMs = try requireInt(obj, "created_at_ms", fixture: fixture).toInt64()
            let attempt = try requireInt(obj, "attempt", fixture: fixture)
            let nextAttemptAtMs = optionalInt64(obj, "next_attempt_ms") ?? 0
            let status = try parseStatus(obj, fixture: fixture)
            switch kind {
            case .watchlist:
                return WatchlistMutation(id: id, titleItemId: entityId, entityId: entityId, createdAtMs: createdAtMs, attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs, desired: try requireBool(obj, "desired", fixture: fixture))
            case .titleWatched:
                let contentType = MediaContentType(rawValue: optionalString(obj, "content_type") ?? "movie") ?? .movie
                return TitleWatchedMutation(id: id, titleItemId: entityId, entityId: entityId, createdAtMs: createdAtMs, attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs, contentType: contentType, desired: try requireBool(obj, "desired", fixture: fixture))
            case .rating:
                return RatingMutation(id: id, titleItemId: entityId, entityId: entityId, createdAtMs: createdAtMs, attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs, desired: optionalInt(obj, "desired"))
            case .episodeWatched:
                return EpisodeWatchedMutation(id: id, titleItemId: entityId, entityId: entityId, createdAtMs: createdAtMs, attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs, itemId: entityId, season: try requireInt(obj, "season", fixture: fixture), episode: optionalInt(obj, "episode"), videoId: try requireString(obj, "video_id", fixture: fixture), desired: try requireBool(obj, "desired", fixture: fixture))
            case .seasonWatched:
                return SeasonWatchedMutation(id: id, titleItemId: entityId, entityId: entityId, createdAtMs: createdAtMs, attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs, seasonItemId: try requireString(obj, "season_item_id", fixture: fixture), seasonNumber: try requireInt(obj, "season_number", fixture: fixture), desired: try requireBool(obj, "desired", fixture: fixture))
            }
        }
    }

    private func parseStatus(_ obj: [String: Any], fixture: URL) throws -> MutationStatus {
        let raw = try requireString(obj, "status", fixture: fixture)
        switch raw {
        case "pending": return .pending
        case "inflight": return .inflight
        case "failed": return .failed(reason: optionalString(obj, "status_reason") ?? "", retryable: true)
        case "conflict": return .conflict(serverValue: optionalString(obj, "status_server_value"))
        default: throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): invalid status")
        }
    }

    private func parseExpectedActions(_ expected: [String: Any], fixture: URL) throws -> [OutboxAction] {
        let actionsAny = try requireArray(expected, "actions", fixture: fixture)
        return try actionsAny.enumerated().map { index, value in
            guard let obj = value as? [String: Any] else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): expected object at actions[\(index)]")
            }
            let kindRaw = try requireString(obj, "kind", fixture: fixture)
            guard let kind = MutationKind(rawValue: kindRaw) else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): invalid action kind")
            }
            return OutboxAction(mutationId: try requireString(obj, "mutation_id", fixture: fixture), kind: kind)
        }
    }

    private func assertDerived(_ caseId: String, _ actual: DerivedUserState, _ expected: [String: Any], fixture: URL) throws {
        try assertFieldBool("\(caseId): watchlist", actual.watchlist, try requireObject(expected, "watchlist", fixture: fixture))
        try assertFieldBool("\(caseId): title_watched", actual.titleWatched, try requireObject(expected, "title_watched", fixture: fixture))
        try assertFieldRating("\(caseId): rating", actual.rating, try requireObject(expected, "rating", fixture: fixture))

        let expectedEpisodes = try requireObject(expected, "episode_watched", fixture: fixture)
        XCTAssertEqual(expectedEpisodes.keys.sorted(), Array(actual.episodeWatched.keys).sorted(), "\(caseId): episode keys")
        for (key, _) in expectedEpisodes {
            guard let expectedField = expectedEpisodes[key] as? [String: Any],
                  let actualField = actual.episodeWatched[key] else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing episode \(key)")
            }
            try assertFieldBool("\(caseId): episode \(key)", actualField, expectedField)
        }

        let expectedSeasons = try requireObject(expected, "season_watched", fixture: fixture)
        let actualSeasonKeys = actual.seasonWatched.keys.map { String($0) }.sorted()
        XCTAssertEqual(expectedSeasons.keys.sorted(), actualSeasonKeys, "\(caseId): season keys")
        for (key, _) in expectedSeasons {
            guard let expectedField = expectedSeasons[key] as? [String: Any],
                  let seasonKey = Int(key),
                  let actualField = actual.seasonWatched[seasonKey] else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing season \(key)")
            }
            try assertFieldBool("\(caseId): season \(key)", actualField, expectedField)
        }
    }

    private func assertFieldBool(_ label: String, _ field: (Bool, MutationSyncView), _ expected: [String: Any]) throws {
        XCTAssertEqual(try requireBool(expected, "value", fixture: URL(fileURLWithPath: label)), field.0, "\(label): value")
        XCTAssertEqual(try parseSync(try requireString(expected, "sync", fixture: URL(fileURLWithPath: label))), field.1.status, "\(label): sync")
    }

    private func assertFieldRating(_ label: String, _ field: (Int?, MutationSyncView), _ expected: [String: Any]) throws {
        XCTAssertEqual(optionalInt(expected, "value"), field.0, "\(label): value")
        XCTAssertEqual(try parseSync(try requireString(expected, "sync", fixture: URL(fileURLWithPath: label))), field.1.status, "\(label): sync")
    }

    private func parseSync(_ value: String) throws -> FieldSync {
        guard let sync = FieldSync(rawValue: value) else {
            throw ContractTestError.invalidFixture("invalid sync \(value)")
        }
        return sync
    }

    private func optionalInt64(_ object: [String: Any], _ key: String) -> Int64? {
        guard let value = object[key], !(value is NSNull) else {
            return nil
        }
        if let v = value as? Int64 { return v }
        if let v = value as? Int { return Int64(v) }
        if let number = value as? NSNumber { return number.int64Value }
        return nil
    }
}

private extension Int {
    func toInt64() -> Int64 { Int64(self) }
}
