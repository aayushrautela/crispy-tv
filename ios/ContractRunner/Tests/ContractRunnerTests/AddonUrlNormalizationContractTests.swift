import Foundation
import Testing
@testable import ContractRunner

struct AddonUrlNormalizationContractTests {
    @Test("addon url normalization fixtures")
    func addonUrlNormalizationFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "addon_url_normalization")
        for fixture in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixture)
            #expect(try requireString(root, "suite", fixture: fixture) == "addon_url_normalization")

            let input = try requireObject(root, "input", fixture: fixture)
            let expected = try requireObject(root, "expected", fixture: fixture)

            let raw = try requireString(input, "raw_url", fixture: fixture)
            let result = normalizeAddonUrl(raw)

            let expectedValid = try requireBool(expected, "is_valid", fixture: fixture)
            let expectedNormalized = optionalString(expected, "normalized_url")

            switch result {
            case let .valid(normalized):
                #expect(expectedValid, "Expected invalid in \(fixture.lastPathComponent)")
                #expect(normalized == expectedNormalized)
            case .invalid:
                #expect(!expectedValid, "Expected valid in \(fixture.lastPathComponent)")
                #expect(expectedNormalized == nil)
            }
        }
    }
}
