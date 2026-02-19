import XCTest
@testable import ContractRunner

final class SyncPlannerContractTests: XCTestCase {
    func testFixturesPlanCanonicalRpcCalls() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "sync_planner")
        XCTAssertFalse(fixtures.isEmpty, "No fixtures found for suite sync_planner")

        for url in fixtures {
            let root = try FixtureLoader.readJSONObject(from: url)
            let caseId = try requireString(root, "case_id", fixture: url)
            XCTAssertEqual("sync_planner", try requireString(root, "suite", fixture: url), "\(caseId): wrong suite")

            let inputObject = try requireObject(root, "input", fixture: url)
            let expectedObject = try requireObject(root, "expected", fixture: url)

            let input = try parseInput(inputObject, fixture: url)
            let expectedCalls = try parseExpectedCalls(expectedObject, fixture: url)
            let actualCalls = planSyncRpcCalls(input: input)

            XCTAssertEqual(expectedCalls, actualCalls, "\(caseId): rpc_calls")
        }
    }
}

private func parseInput(_ input: [String: Any], fixture: URL) throws -> SyncPlannerInput {
    let roleRaw = try requireString(input, "role", fixture: fixture)
    let role: HouseholdRole
    switch roleRaw {
    case "owner":
        role = .owner
    case "member":
        role = .member
    default:
        throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): invalid role")
    }

    let addonsAny = try requireArray(input, "addons", fixture: fixture)
    let addons: [RawAddonInstall] = try addonsAny.enumerated().map { index, value in
        guard let obj = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): expected object at addons[\(index)]")
        }

        let url = try requireString(obj, "url", fixture: fixture)
        let enabled: Bool?
        if let raw = obj["enabled"], !(raw is NSNull) {
            guard let boolValue = raw as? Bool else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): enabled must be bool or null")
            }
            enabled = boolValue
        } else {
            enabled = nil
        }

        let name = optionalString(obj, "name")
        return RawAddonInstall(url: url, enabled: enabled, name: name)
    }

    return SyncPlannerInput(
        role: role,
        pullRequested: optionalBool(input, "pull_requested") ?? false,
        flushRequested: optionalBool(input, "flush_requested") ?? false,
        debounceMs: optionalInt64(input, "debounce_ms") ?? 2000,
        nowMs: optionalInt64(input, "now_ms"),
        householdDirty: try requireBool(input, "household_dirty", fixture: fixture),
        householdChangedAtMs: optionalInt64(input, "household_changed_at_ms"),
        profileDirty: try requireBool(input, "profile_dirty", fixture: fixture),
        profileChangedAtMs: optionalInt64(input, "profile_changed_at_ms"),
        profileId: try requireString(input, "profile_id", fixture: fixture),
        addons: addons,
        settings: try requireStringMap(input, key: "settings", fixture: fixture),
        catalogPrefs: try requireStringMap(input, key: "catalog_prefs", fixture: fixture),
        traktAuth: try requireStringMap(input, key: "trakt_auth", fixture: fixture),
        simklAuth: try requireStringMap(input, key: "simkl_auth", fixture: fixture)
    )
}

private func parseExpectedCalls(_ expected: [String: Any], fixture: URL) throws -> [SyncRpcCall] {
    let callsAny = try requireArray(expected, "rpc_calls", fixture: fixture)
    return try callsAny.enumerated().map { index, value in
        guard let obj = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): expected object at rpc_calls[\(index)]")
        }

        let name = try requireString(obj, "name", fixture: fixture)
        let params = try requireObject(obj, "params", fixture: fixture)

        switch name {
        case "get_household_addons":
            return .getHouseholdAddons

        case "replace_household_addons":
            let addonsAny = try requireArray(params, "p_addons", fixture: fixture)
            let addons: [CloudAddonInstall] = try addonsAny.enumerated().map { addonIndex, addonValue in
                guard let addonObj = addonValue as? [String: Any] else {
                    throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): expected object at p_addons[\(addonIndex)]")
                }
                let url = try requireString(addonObj, "url", fixture: fixture)
                let enabled = try requireBool(addonObj, "enabled", fixture: fixture)
                let name = optionalString(addonObj, "name")
                return CloudAddonInstall(url: url, enabled: enabled, name: name)
            }
            return .replaceHouseholdAddons(addons: addons)

        case "upsert_profile_data":
            return .upsertProfileData(
                profileId: try requireString(params, "p_profile_id", fixture: fixture),
                settings: try requireStringMap(params, key: "p_settings", fixture: fixture),
                catalogPrefs: try requireStringMap(params, key: "p_catalog_prefs", fixture: fixture),
                traktAuth: try requireStringMap(params, key: "p_trakt_auth", fixture: fixture),
                simklAuth: try requireStringMap(params, key: "p_simkl_auth", fixture: fixture)
            )

        default:
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): unknown rpc call name \(name)")
        }
    }
}

private func optionalBool(_ object: [String: Any], _ key: String) -> Bool? {
    guard let value = object[key], !(value is NSNull) else {
        return nil
    }
    return value as? Bool
}

private func optionalInt64(_ object: [String: Any], _ key: String) -> Int64? {
    guard let value = object[key], !(value is NSNull) else {
        return nil
    }

    if let v = value as? Int64 {
        return v
    }

    if let v = value as? Int {
        return Int64(v)
    }

    if let n = value as? NSNumber {
        return n.int64Value
    }

    return nil
}

private func requireStringMap(_ object: [String: Any], key: String, fixture: URL) throws -> [String: String] {
    let map = try requireObject(object, key, fixture: fixture)
    var output: [String: String] = [:]
    for (k, v) in map {
        guard let text = v as? String else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): non-string value in \(key).\(k)")
        }
        output[k] = text
    }
    return output
}
