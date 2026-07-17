import Foundation

public struct ProfileSortInput: Equatable {
    public let id: String
    public let name: String
    public let isKids: Bool
    public let lastUsedMs: Int64?

    public init(id: String, name: String, isKids: Bool, lastUsedMs: Int64?) {
        self.id = id
        self.name = name
        self.isKids = isKids
        self.lastUsedMs = lastUsedMs
    }
}

public func sortProfiles(_ profiles: [ProfileSortInput]) -> [String] {
    let sorted = profiles.sorted { lhs, rhs in
        if lhs.isKids != rhs.isKids {
            return lhs.isKids && !rhs.isKids
        }
        let lhsUsed = lhs.lastUsedMs ?? Int64.min
        let rhsUsed = rhs.lastUsedMs ?? Int64.min
        if lhsUsed != rhsUsed {
            return lhsUsed > rhsUsed
        }
        return lhs.id < rhs.id
    }
    return sorted.map { $0.id }
}
