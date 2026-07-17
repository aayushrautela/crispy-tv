import Foundation
import Testing
@testable import ContractRunner

struct ProfileSettingsSyncContractTests {
    @Test("profile settings sync fixtures")
    func profileSettingsSyncFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "profile_settings_sync")
        for fixture in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixture)
            #expect(try requireString(root, "suite", fixture: fixture) == "profile_settings_sync")

            let input = try requireObject(root, "input", fixture: fixture)
            let expected = try requireObject(root, "expected", fixture: fixture)

            let local = try parseSettings(requireObject(input, "local", fixture: fixture), fixture: fixture)
            let server = try parseSettings(requireObject(input, "server", fixture: fixture), fixture: fixture)
            let merged = mergeProfileSettings(local, server)

            let expectedMerged = try parseSettings(requireObject(expected, "merged", fixture: fixture), fixture: fixture)
            #expect(merged == expectedMerged, "Merged mismatch in \(fixture.lastPathComponent)")
        }
    }

    private func parseSettings(_ object: [String: Any], fixture: URL) throws -> ProfileSettings {
        return ProfileSettings(
            displayName: optionalString(object, "display_name"),
            avatarUrl: optionalString(object, "avatar_url"),
            syncProvider: optionalString(object, "sync_provider"),
            onboardingStep: optionalString(object, "onboarding_step"),
            onboardingCompletedAtMs: optionalInt64(object, "onboarding_completed_at_ms")
        )
    }

    private func optionalInt64(_ object: [String: Any], _ key: String) -> Int64? {
        guard let value = object[key] else { return nil }
        if value is NSNull { return nil }
        if let number = value as? NSNumber {
            return number.int64Value
        }
        return nil
    }
}
