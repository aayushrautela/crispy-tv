import Foundation
import Testing
@testable import ContractRunner

struct SignupProfileContractTests {
    @Test("signup profile fixtures")
    func signupProfileFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "signup_profile")
        for fixture in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixture)
            #expect(try requireString(root, "suite", fixture: fixture) == "signup_profile")

            let input = try requireObject(root, "input", fixture: fixture)
            let expected = try requireObject(root, "expected", fixture: fixture)

            let rawName = try requireString(input, "raw_name", fixture: fixture)
            let rawLanguage = optionalString(input, "raw_language")
            let rawRegion = optionalString(input, "raw_region")
            let rawAvatarUrl = optionalString(input, "raw_avatar_url")

            let result = validateSignupProfile(
                rawName: rawName,
                rawLanguage: rawLanguage,
                rawRegion: rawRegion,
                rawAvatarUrl: rawAvatarUrl
            )

            let expectedComplete = try requireBool(expected, "is_complete", fixture: fixture)
            let expectedMissing = optionalArray(expected, "missing")?.map { any in
                guard let value = any as? String else {
                    throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): missing entry must be string")
                }
                return value
            } ?? []
            let expectedName = optionalString(expected, "normalized_name")
            let expectedLanguage = optionalString(expected, "normalized_language")
            let expectedRegion = optionalString(expected, "normalized_region")
            let expectedAvatarUrl = optionalString(expected, "normalized_avatar_url")

            #expect(result.isComplete == expectedComplete, "isComplete mismatch in \(fixture.lastPathComponent)")
            #expect(result.missing == expectedMissing, "missing mismatch in \(fixture.lastPathComponent)")
            #expect(result.normalizedName == expectedName, "name mismatch in \(fixture.lastPathComponent)")
            #expect(result.normalizedLanguage == expectedLanguage, "language mismatch in \(fixture.lastPathComponent)")
            #expect(result.normalizedRegion == expectedRegion, "region mismatch in \(fixture.lastPathComponent)")
            #expect(result.normalizedAvatarUrl == expectedAvatarUrl, "avatarUrl mismatch in \(fixture.lastPathComponent)")
        }
    }
}

private func optionalArray(_ object: [String: Any], _ key: String) -> [Any]? {
    guard let value = object[key] else { return nil }
    if value is NSNull { return nil }
    return value as? [Any]
}
