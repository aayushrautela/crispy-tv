import Foundation
import Testing
@testable import ContractRunner

struct TraktScrobblePolicyContractTests {
    @Test("trakt scrobble policy fixtures")
    func traktScrobblePolicyFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "trakt_scrobble_policy")
        for fixture in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixture)
            #expect(try requireString(root, "suite", fixture: fixture) == "trakt_scrobble_policy")

            let input = try requireObject(root, "input", fixture: fixture)
            let expected = try requireObject(root, "expected", fixture: fixture)

            let stageRaw = try requireString(input, "stage", fixture: fixture).lowercased()
            let stage: TraktScrobbleStage
            if stageRaw == "start" {
                stage = .start
            } else if stageRaw == "stop" {
                stage = .stop
            } else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): invalid stage")
            }

            let progress = try requireDouble(input, "progress_percent", fixture: fixture)
            let actual = decideTraktScrobble(stage: stage, progressPercent: progress)

            #expect(actual.endpoint == try requireString(expected, "endpoint", fixture: fixture))
            #expect(actual.marksWatched == try requireBool(expected, "marks_watched", fixture: fixture))
            #expect(actual.updatesPlaybackProgress == try requireBool(expected, "updates_playback_progress", fixture: fixture))
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
