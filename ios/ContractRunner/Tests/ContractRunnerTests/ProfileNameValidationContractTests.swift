import Foundation
import Testing
@testable import ContractRunner

struct ProfileNameValidationContractTests {
    @Test("profile name validation fixtures")
    func profileNameValidationFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "profile_name_validation")
        for fixture in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixture)
            #expect(try requireString(root, "suite", fixture: fixture) == "profile_name_validation")

            let input = try requireObject(root, "input", fixture: fixture)
            let expected = try requireObject(root, "expected", fixture: fixture)

            let raw = try requireString(input, "raw_name", fixture: fixture)
            let result = validateProfileName(raw)

            let expectedValid = try requireBool(expected, "is_valid", fixture: fixture)
            let expectedNormalized = optionalString(expected, "normalized_name")
            let expectedError = optionalString(expected, "error")

            switch result {
            case let .valid(normalized):
                #expect(expectedValid, "Expected invalid in \(fixture.lastPathComponent)")
                #expect(normalized == expectedNormalized)
            case let .invalid(reason):
                #expect(!expectedValid, "Expected valid in \(fixture.lastPathComponent)")
                #expect(reason == expectedError)
            }
        }
    }
}
