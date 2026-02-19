import Foundation

public enum HouseholdRole: String {
    case owner
    case member
}

public struct RawAddonInstall: Equatable {
    public let url: String?
    public let enabled: Bool?
    public let name: String?

    public init(url: String?, enabled: Bool?, name: String?) {
        self.url = url
        self.enabled = enabled
        self.name = name
    }
}

public struct CloudAddonInstall: Equatable {
    public let url: String
    public let enabled: Bool
    public let name: String?

    public init(url: String, enabled: Bool, name: String?) {
        self.url = url
        self.enabled = enabled
        self.name = name
    }
}

public struct SyncPlannerInput: Equatable {
    public let role: HouseholdRole
    public let pullRequested: Bool
    public let flushRequested: Bool
    public let debounceMs: Int64
    public let nowMs: Int64?
    public let householdDirty: Bool
    public let householdChangedAtMs: Int64?
    public let profileDirty: Bool
    public let profileChangedAtMs: Int64?
    public let profileId: String
    public let addons: [RawAddonInstall]
    public let settings: [String: String]
    public let catalogPrefs: [String: String]
    public let traktAuth: [String: String]
    public let simklAuth: [String: String]

    public init(
        role: HouseholdRole,
        pullRequested: Bool = false,
        flushRequested: Bool = false,
        debounceMs: Int64 = 2000,
        nowMs: Int64? = nil,
        householdDirty: Bool,
        householdChangedAtMs: Int64? = nil,
        profileDirty: Bool,
        profileChangedAtMs: Int64? = nil,
        profileId: String,
        addons: [RawAddonInstall],
        settings: [String: String],
        catalogPrefs: [String: String],
        traktAuth: [String: String],
        simklAuth: [String: String]
    ) {
        self.role = role
        self.pullRequested = pullRequested
        self.flushRequested = flushRequested
        self.debounceMs = debounceMs
        self.nowMs = nowMs
        self.householdDirty = householdDirty
        self.householdChangedAtMs = householdChangedAtMs
        self.profileDirty = profileDirty
        self.profileChangedAtMs = profileChangedAtMs
        self.profileId = profileId
        self.addons = addons
        self.settings = settings
        self.catalogPrefs = catalogPrefs
        self.traktAuth = traktAuth
        self.simklAuth = simklAuth
    }
}

public enum SyncRpcCall: Equatable {
    case getHouseholdAddons
    case replaceHouseholdAddons(addons: [CloudAddonInstall])
    case upsertProfileData(
        profileId: String,
        settings: [String: String],
        catalogPrefs: [String: String],
        traktAuth: [String: String],
        simklAuth: [String: String]
    )

    public var name: String {
        switch self {
        case .getHouseholdAddons:
            return "get_household_addons"
        case .replaceHouseholdAddons:
            return "replace_household_addons"
        case .upsertProfileData:
            return "upsert_profile_data"
        }
    }
}

public func planSyncRpcCalls(input: SyncPlannerInput) -> [SyncRpcCall] {
    var calls: [SyncRpcCall] = []

    if input.pullRequested && !input.householdDirty {
        calls.append(.getHouseholdAddons)
    }

    if input.householdDirty && input.role == .owner && isDebounceSatisfied(
        nowMs: input.nowMs,
        changedAtMs: input.householdChangedAtMs,
        debounceMs: input.debounceMs,
        flushRequested: input.flushRequested
    ) {
        calls.append(.replaceHouseholdAddons(addons: normalizeAddonsForCloud(input.addons)))
    }

    if input.profileDirty && isDebounceSatisfied(
        nowMs: input.nowMs,
        changedAtMs: input.profileChangedAtMs,
        debounceMs: input.debounceMs,
        flushRequested: input.flushRequested
    ) {
        calls.append(
            .upsertProfileData(
                profileId: input.profileId.trimmed(),
                settings: normalizeStringMap(input.settings),
                catalogPrefs: normalizeStringMap(input.catalogPrefs),
                traktAuth: normalizeStringMap(input.traktAuth),
                simklAuth: normalizeStringMap(input.simklAuth)
            )
        )
    }

    return calls
}

private func isDebounceSatisfied(nowMs: Int64?, changedAtMs: Int64?, debounceMs: Int64, flushRequested: Bool) -> Bool {
    if flushRequested {
        return true
    }

    guard let nowMs, let changedAtMs else {
        return true
    }

    return (nowMs - changedAtMs) >= debounceMs
}

public func normalizeAddonsForCloud(_ addons: [RawAddonInstall]) -> [CloudAddonInstall] {
    struct Sortable {
        let urlLower: String
        let urlRaw: String
        let index: Int
        let addon: CloudAddonInstall
    }

    var sortable: [Sortable] = []
    sortable.reserveCapacity(addons.count)

    for (index, raw) in addons.enumerated() {
        let url = (raw.url ?? "")
            .trimmed()
            .trimmingTrailingSlashes()

        if url.isEmpty {
            continue
        }

        let name = raw.name?.trimmed()
        let normalizedName = (name == nil || name == "") ? nil : name

        let addon = CloudAddonInstall(
            url: url,
            enabled: raw.enabled ?? true,
            name: normalizedName
        )

        sortable.append(
            Sortable(
                urlLower: url.lowercased(),
                urlRaw: url,
                index: index,
                addon: addon
            )
        )
    }

    sortable.sort { a, b in
        if a.urlLower != b.urlLower { return a.urlLower < b.urlLower }
        if a.urlRaw != b.urlRaw { return a.urlRaw < b.urlRaw }
        return a.index < b.index
    }

    return sortable.map { $0.addon }
}

public func normalizeStringMap(_ raw: [String: String]) -> [String: String] {
    var output: [String: String] = [:]

    for (key, value) in raw {
        let normalizedKey = key.trimmed()
        if normalizedKey.isEmpty {
            continue
        }
        output[normalizedKey] = value.trimmed()
    }

    return output
}

private extension String {
    func trimmed() -> String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func trimmingTrailingSlashes() -> String {
        var cursor = self
        while cursor.hasSuffix("/") {
            cursor.removeLast()
        }
        return cursor
    }
}
