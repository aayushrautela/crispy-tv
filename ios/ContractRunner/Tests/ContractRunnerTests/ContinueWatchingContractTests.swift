import Foundation
import Testing
@testable import ContractRunner

struct ContinueWatchingContractTests {
    @Test("continue watching fixtures")
    func continueWatchingFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "continue_watching")
        for fixture in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixture)
            #expect(try requireString(root, "suite", fixture: fixture) == "continue_watching")

            let nowMs = Int64(try requireInt(root, "now_ms", fixture: fixture))
            let input = try requireObject(root, "input", fixture: fixture)
            let expected = try requireObject(root, "expected", fixture: fixture)

            let candidateValues = try requireArray(input, "candidates", fixture: fixture)
            let candidates: [ContinueWatchingCandidate] = try candidateValues.map { any in
                guard let object = any as? [String: Any] else {
                    throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): candidate must be object")
                }
                return ContinueWatchingCandidate(
                    contentType: try requireString(object, "content_type", fixture: fixture),
                    contentId: try requireString(object, "content_id", fixture: fixture),
                    episodeKey: optionalString(object, "episode_key"),
                    progressPercent: try requireDouble(object, "progress_percent", fixture: fixture),
                    lastUpdatedMs: Int64(try requireInt(object, "last_updated_ms", fixture: fixture)),
                    isUpNextPlaceholder: optionalBool(object, "is_up_next_placeholder") ?? false
                )
            }

            let maxItems = optionalInt(input, "max_items") ?? 20
            let actual = planContinueWatching(candidates: candidates, nowMs: nowMs, maxItems: maxItems)

            let expectedValues = try requireArray(expected, "items", fixture: fixture)
            let expectedItems: [ContinueWatchingPlanItem] = try expectedValues.map { any in
                guard let object = any as? [String: Any] else {
                    throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): expected item must be object")
                }
                return ContinueWatchingPlanItem(
                    contentType: try requireString(object, "content_type", fixture: fixture),
                    contentId: try requireString(object, "content_id", fixture: fixture),
                    episodeKey: optionalString(object, "episode_key"),
                    progressPercent: try requireDouble(object, "progress_percent", fixture: fixture),
                    lastUpdatedMs: Int64(try requireInt(object, "last_updated_ms", fixture: fixture)),
                    isUpNextPlaceholder: optionalBool(object, "is_up_next_placeholder") ?? false
                )
            }

            #expect(actual.count == expectedItems.count)
            for index in expectedItems.indices {
                let expectedItem = expectedItems[index]
                let actualItem = actual[index]
                #expect(actualItem.contentType == expectedItem.contentType)
                #expect(actualItem.contentId == expectedItem.contentId)
                #expect(actualItem.episodeKey == expectedItem.episodeKey)
                #expect(actualItem.lastUpdatedMs == expectedItem.lastUpdatedMs)
                #expect(actualItem.isUpNextPlaceholder == expectedItem.isUpNextPlaceholder)
                #expect(abs(actualItem.progressPercent - expectedItem.progressPercent) < 0.0001)
            }
        }
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

private func optionalBool(_ object: [String: Any], _ key: String) -> Bool? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    return value as? Bool
}
