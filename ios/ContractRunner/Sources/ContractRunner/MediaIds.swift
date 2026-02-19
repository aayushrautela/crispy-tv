import Foundation

public struct NuvioMediaId: Equatable {
    public let contentId: String
    public let videoId: String?
    public let isEpisode: Bool
    public let season: Int?
    public let episode: Int?
    public let kind: String
    public let addonLookupId: String

    public init(
        contentId: String,
        videoId: String?,
        isEpisode: Bool,
        season: Int?,
        episode: Int?,
        kind: String,
        addonLookupId: String
    ) {
        self.contentId = contentId
        self.videoId = videoId
        self.isEpisode = isEpisode
        self.season = season
        self.episode = episode
        self.kind = kind
        self.addonLookupId = addonLookupId
    }
}

public func normalizeNuvioMediaId(_ raw: String) -> NuvioMediaId {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
        return NuvioMediaId(
            contentId: "",
            videoId: nil,
            isEpisode: false,
            season: nil,
            episode: nil,
            kind: "content",
            addonLookupId: ""
        )
    }

    let normalized = stripSeriesPrefix(trimmed)
    let parts = normalized.split(separator: ":").map(String.init)
    if parts.count >= 3,
       let season = Int(parts[parts.count - 2]), season > 0,
       let episode = Int(parts[parts.count - 1]), episode > 0 {
        let baseId = stripSeriesPrefix(parts.dropLast(2).joined(separator: ":").trimmingCharacters(in: .whitespacesAndNewlines))
        if !baseId.isEmpty {
            let videoId = "\(baseId):\(season):\(episode)"
            return NuvioMediaId(
                contentId: baseId,
                videoId: videoId,
                isEpisode: true,
                season: season,
                episode: episode,
                kind: "episode",
                addonLookupId: videoId
            )
        }
    }

    return NuvioMediaId(
        contentId: normalized,
        videoId: nil,
        isEpisode: false,
        season: nil,
        episode: nil,
        kind: "content",
        addonLookupId: normalized
    )
}

private func stripSeriesPrefix(_ value: String) -> String {
    guard value.hasPrefix("series:") else {
        return value
    }
    let remainder = String(value.dropFirst("series:".count))
    return remainder.isEmpty ? value : remainder
}
