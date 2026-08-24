import XCTest
@testable import ContractRunner

final class WatchSyncContractTests: XCTestCase {
    func testFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "watch_sync")
        XCTAssertFalse(fixtures.isEmpty, "No fixtures found for suite watch_sync")

        for url in fixtures {
            let root = try FixtureLoader.readJSONObject(from: url)
            let caseId = try requireString(root, "case_id", fixture: url)
            XCTAssertEqual("watch_sync", try requireString(root, "suite", fixture: url), "\(caseId): wrong suite")

            let inputObject = try requireObject(root, "input", fixture: url)
            let expectedObject = try requireObject(root, "expected", fixture: url)
            let profileId = try requireString(inputObject, "profile_id", fixture: url)

            var state = createWatchSyncState(profileId: profileId)
            var actualEffects: [String] = []

            let events = try requireArray(inputObject, "events", fixture: url)
            for entry in events {
                guard let object = entry as? [String: Any] else {
                    throw ContractTestError.invalidFixture("\(url.lastPathComponent): event must be object")
                }
                let type = try requireString(object, "type", fixture: url)
                let event: WatchSyncEvent
                switch type {
                case "surface_visible":
                    event = .surfaceBecameVisible
                case "surface_hidden":
                    event = .surfaceHidden
                case "connection_opened":
                    event = .connectionOpened
                case "connection_closed":
                    event = .connectionClosed
                case "invalidation":
                    event = .invalidationReceived(
                        profileId: try requireString(object, "profile_id", fixture: url),
                        atMs: watchSyncOptionalInt64(object, "at_ms") ?? 0
                    )
                case "max_duration_elapsed":
                    event = .maxDurationElapsed
                default:
                    throw ContractTestError.invalidFixture("\(url.lastPathComponent): unknown watch_sync event type '\(type)'")
                }
                let result = reduceWatchSync(state: state, event: event)
                state = result.state
                actualEffects.append(contentsOf: result.effects.map { $0.contractValue })
            }

            let expectedEffects = try stringArray(
                try requireArray(expectedObject, "effects", fixture: url),
                fixture: url,
                key: "effects"
            )
            XCTAssertEqual(expectedEffects, actualEffects, "\(caseId): effects mismatch")
        }
    }
}

private func watchSyncOptionalInt64(_ object: [String: Any], _ key: String) -> Int64? {
    guard let value = object[key], !(value is NSNull) else {
        return nil
    }
    if let int64Value = value as? Int64 {
        return int64Value
    }
    if let number = value as? NSNumber {
        return number.int64Value
    }
    return nil
}
