import Foundation
import Testing
@testable import ContractRunner

struct ProfileSortingContractTests {
    @Test("profile sorting fixtures")
    func profileSortingFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "profile_sorting")
        for fixture in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixture)
            #expect(try requireString(root, "suite", fixture: fixture) == "profile_sorting")

            let input = try requireObject(root, "input", fixture: fixture)
            let expected = try requireObject(root, "expected", fixture: fixture)

            let profileValues = try requireArray(input, "profiles", fixture: fixture)
            let profiles: [ProfileSortInput] = try profileValues.map { any in
                guard let object = any as? [String: Any] else {
                    throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): profile must be object")
                }
                return ProfileSortInput(
                    id: try requireString(object, "id", fixture: fixture),
                    name: try requireString(object, "name", fixture: fixture),
                    isKids: try requireBool(object, "is_kids", fixture: fixture),
                    lastUsedMs: optionalInt64(object, "last_used_ms")
                )
            }

            let actual = sortProfiles(profiles)
            let expectedIds = try requireArray(expected, "ordered_ids", fixture: fixture).map { any in
                guard let value = any as? String else {
                    throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): ordered id must be string")
                }
                return value
            }
            #expect(actual == expectedIds, "Order mismatch in \(fixture.lastPathComponent)")
        }
    }
}

private func optionalInt64(_ object: [String: Any], _ key: String) -> Int64? {
    guard let value = object[key] else { return nil }
    if value is NSNull { return nil }
    if let number = value as? NSNumber {
        return number.int64Value
    }
    return nil
}
