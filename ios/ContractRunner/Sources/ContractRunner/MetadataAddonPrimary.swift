import Foundation

public struct AddonMetadataCandidate: Equatable {
    public let addonId: String
    public let mediaId: String
    public let title: String

    public init(addonId: String, mediaId: String, title: String) {
        self.addonId = addonId
        self.mediaId = mediaId
        self.title = title
    }
}

public struct AddonPrimaryMetadata: Equatable {
    public let primaryId: String
    public let title: String
    public let sources: [String]

    public init(primaryId: String, title: String, sources: [String]) {
        self.primaryId = primaryId
        self.title = title
        self.sources = sources
    }
}

public func mergeAddonPrimaryMetadata(
    _ addonResults: [AddonMetadataCandidate],
    preferredAddonId: String? = nil
) -> AddonPrimaryMetadata {
    precondition(!addonResults.isEmpty, "addonResults must not be empty")

    let preferred = preferredAddonId?.trimmingCharacters(in: .whitespacesAndNewlines)
    let ranked = addonResults.enumerated().sorted { lhs, rhs in
        let lhsRank = sourceRank(lhs.element.addonId, preferredAddonId: preferred)
        let rhsRank = sourceRank(rhs.element.addonId, preferredAddonId: preferred)
        if lhsRank != rhsRank {
            return lhsRank < rhsRank
        }
        if lhs.offset != rhs.offset {
            return lhs.offset < rhs.offset
        }
        return lhs.element.addonId.localizedCaseInsensitiveCompare(rhs.element.addonId) == .orderedAscending
    }

    let winner = ranked[0].element
    var seen = Set<String>()
    var sources: [String] = []
    for entry in ranked {
        if seen.insert(entry.element.addonId).inserted {
            sources.append(entry.element.addonId)
        }
    }

    return AddonPrimaryMetadata(primaryId: winner.mediaId, title: winner.title, sources: sources)
}

private func sourceRank(_ addonId: String, preferredAddonId: String?) -> Int {
    if let preferredAddonId,
       !preferredAddonId.isEmpty,
       addonId.caseInsensitiveCompare(preferredAddonId) == .orderedSame {
        return 0
    }

    if addonId.range(of: "cinemeta", options: [.caseInsensitive]) != nil {
        return 1
    }

    return 2
}
