import Foundation
import Testing
@testable import ContractRunner

struct PlayerProgressContractTests {
    @Test("player progress fixtures")
    func playerProgressFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "player_progress")
        for fixture in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixture)
            #expect(try requireString(root, "suite", fixture: fixture) == "player_progress")

            let input = try requireObject(root, "input", fixture: fixture)
            let expected = try requireObject(root, "expected", fixture: fixture)

            let positionMs = try requireInt(input, "position_ms", fixture: fixture)
            let durationMs = try requireInt(input, "duration_ms", fixture: fixture)
            let resolved = resolveProgressWrite(positionMs: positionMs, durationMs: durationMs)

            let storeOrDrop = try requireString(expected, "store_or_drop", fixture: fixture)
            let expectedCompleted = try requireBool(expected, "is_completed", fixture: fixture)

            switch storeOrDrop {
            case "drop":
                #expect(resolved == nil, "expected drop in \(fixture.lastPathComponent)")
            case "store":
                guard let resolved else {
                    #expect(Bool(false), "expected store but was dropped in \(fixture.lastPathComponent)")
                    continue
                }
                #expect(resolved.isCompleted == expectedCompleted, "is_completed mismatch in \(fixture.lastPathComponent)")
                if let expectedStored = optionalInt(expected, "stored_position_ms") {
                    #expect(resolved.storedPositionMs == expectedStored, "stored_position_ms mismatch in \(fixture.lastPathComponent)")
                }
                if let expectedEvent = optionalInt(expected, "event_position_ms") {
                    #expect(resolved.eventPositionMs == expectedEvent, "event_position_ms mismatch in \(fixture.lastPathComponent)")
                }
            default:
                #expect(Bool(false), "unknown store_or_drop in \(fixture.lastPathComponent)")
            }
        }
    }
}
