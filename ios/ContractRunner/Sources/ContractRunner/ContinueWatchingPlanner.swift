import Foundation

public struct ContinueWatchingCandidate: Equatable {
    public let contentType: String
    public let contentId: String
    public let episodeKey: String?
    public let progressPercent: Double
    public let lastUpdatedMs: Int64
    public let isUpNextPlaceholder: Bool

    public init(
        contentType: String,
        contentId: String,
        episodeKey: String? = nil,
        progressPercent: Double,
        lastUpdatedMs: Int64,
        isUpNextPlaceholder: Bool = false
    ) {
        self.contentType = contentType
        self.contentId = contentId
        self.episodeKey = episodeKey
        self.progressPercent = progressPercent
        self.lastUpdatedMs = lastUpdatedMs
        self.isUpNextPlaceholder = isUpNextPlaceholder
    }
}

public struct ContinueWatchingPlanItem: Equatable {
    public let contentType: String
    public let contentId: String
    public let episodeKey: String?
    public let progressPercent: Double
    public let lastUpdatedMs: Int64
    public let isUpNextPlaceholder: Bool

    public init(
        contentType: String,
        contentId: String,
        episodeKey: String?,
        progressPercent: Double,
        lastUpdatedMs: Int64,
        isUpNextPlaceholder: Bool
    ) {
        self.contentType = contentType
        self.contentId = contentId
        self.episodeKey = episodeKey
        self.progressPercent = progressPercent
        self.lastUpdatedMs = lastUpdatedMs
        self.isUpNextPlaceholder = isUpNextPlaceholder
    }
}

public func planContinueWatching(
    candidates: [ContinueWatchingCandidate],
    nowMs: Int64,
    maxItems: Int = 20,
    minProgressPercent: Double = 2.0,
    completionPercent: Double = 85.0,
    staleWindowMs: Int64 = 30 * 24 * 60 * 60 * 1000
) -> [ContinueWatchingPlanItem] {
    if candidates.isEmpty || maxItems <= 0 {
        return []
    }

    let staleCutoff = nowMs - staleWindowMs
    let filtered = candidates.compactMap(normalizeCandidate).filter { candidate in
        if candidate.lastUpdatedMs < staleCutoff {
            return false
        }
        if candidate.isUpNextPlaceholder {
            return candidate.progressPercent <= 0
        }
        return candidate.progressPercent >= minProgressPercent && candidate.progressPercent < completionPercent
    }

    var deduped: [String: ContinueWatchingCandidate] = [:]
    for candidate in filtered {
        let key = "\(candidate.contentType):\(candidate.contentId)"
        if let current = deduped[key] {
            deduped[key] = choosePreferred(current: current, incoming: candidate)
        } else {
            deduped[key] = candidate
        }
    }

    return deduped.values
        .sorted { lhs, rhs in
            let lhsInProgress = lhs.progressPercent > 0
            let rhsInProgress = rhs.progressPercent > 0
            if lhsInProgress != rhsInProgress {
                return lhsInProgress && !rhsInProgress
            }
            return lhs.lastUpdatedMs > rhs.lastUpdatedMs
        }
        .prefix(maxItems)
        .map { candidate in
            ContinueWatchingPlanItem(
                contentType: candidate.contentType,
                contentId: candidate.contentId,
                episodeKey: candidate.episodeKey,
                progressPercent: candidate.progressPercent,
                lastUpdatedMs: candidate.lastUpdatedMs,
                isUpNextPlaceholder: candidate.isUpNextPlaceholder
            )
        }
}

private func normalizeCandidate(_ candidate: ContinueWatchingCandidate) -> ContinueWatchingCandidate? {
    let contentType = candidate.contentType.trimmed().lowercased()
    let contentId = candidate.contentId.trimmed()
    if contentType.isEmpty || contentId.isEmpty {
        return nil
    }

    return ContinueWatchingCandidate(
        contentType: contentType,
        contentId: contentId,
        episodeKey: candidate.episodeKey?.trimmed().nilIfEmpty,
        progressPercent: min(max(candidate.progressPercent, 0), 100),
        lastUpdatedMs: candidate.lastUpdatedMs,
        isUpNextPlaceholder: candidate.isUpNextPlaceholder
    )
}

private func choosePreferred(current: ContinueWatchingCandidate, incoming: ContinueWatchingCandidate) -> ContinueWatchingCandidate {
    let sameEpisode = current.episodeKey == incoming.episodeKey
    if sameEpisode {
        return incoming.progressPercent >= current.progressPercent ? incoming : current
    }
    if incoming.lastUpdatedMs > current.lastUpdatedMs {
        return incoming
    }
    if incoming.lastUpdatedMs < current.lastUpdatedMs {
        return current
    }
    return incoming.progressPercent >= current.progressPercent ? incoming : current
}

private extension String {
    func trimmed() -> String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
