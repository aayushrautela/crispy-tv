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

    let suffix = parseEpisodeSuffix(trimmed)
    if let season = suffix.season,
       let episode = suffix.episode {
        let baseId = canonicalizeBaseId(suffix.baseId)
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

    let normalized = canonicalizeBaseId(stripSeriesPrefix(trimmed))

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

public func formatIdForIdPrefixes(
    _ input: String,
    mediaType: String,
    idPrefixes: [String] = []
) -> String? {
    let raw = input.trimmingCharacters(in: .whitespacesAndNewlines)
    if raw.isEmpty {
        return nil
    }

    let suffix = parseEpisodeSuffix(raw)
    let baseRaw = suffix.baseId.trimmingCharacters(in: .whitespacesAndNewlines)
    if baseRaw.isEmpty {
        return nil
    }

    let normalizedBase = canonicalizeBaseId(baseRaw)
    let episodeSuffix: String
    if let season = suffix.season, let episode = suffix.episode {
        episodeSuffix = ":\(season):\(episode)"
    } else {
        episodeSuffix = ""
    }

    let providerKind = mediaType.caseInsensitiveCompare("movie") == .orderedSame ? "movie" : "show"

    var candidates: [String] = []
    var seen = Set<String>()

    if isImdbId(normalizedBase) {
        appendUnique("\(normalizedBase)\(episodeSuffix)", to: &candidates, seen: &seen)
        appendUnique("imdb:\(normalizedBase)\(episodeSuffix)", to: &candidates, seen: &seen)
        appendUnique("imdb:\(providerKind):\(normalizedBase)\(episodeSuffix)", to: &candidates, seen: &seen)
    }

    if let tmdbId = tmdbNumericIdOrNil(normalizedBase) {
        appendUnique("tmdb:\(tmdbId)\(episodeSuffix)", to: &candidates, seen: &seen)
        appendUnique("tmdb:\(providerKind):\(tmdbId)\(episodeSuffix)", to: &candidates, seen: &seen)
    }

    let needsNumericInference =
        isNumeric(normalizedBase) &&
        !isImdbId(normalizedBase) &&
        tmdbNumericIdOrNil(normalizedBase) == nil &&
        !containsSchemePrefix(normalizedBase)
    if needsNumericInference {
        let inferredProvider = inferNumericProvider(idPrefixes)
        if inferredProvider == nil && !idPrefixes.isEmpty {
            return nil
        }
        if let inferredProvider {
            appendUnique("\(inferredProvider):\(normalizedBase)\(episodeSuffix)", to: &candidates, seen: &seen)
            appendUnique("\(inferredProvider):\(providerKind):\(normalizedBase)\(episodeSuffix)", to: &candidates, seen: &seen)
        }
    }

    appendUnique("\(normalizedBase)\(episodeSuffix)", to: &candidates, seen: &seen)

    if !idPrefixes.isEmpty {
        for candidate in candidates {
            if idPrefixes.contains(where: { candidate.hasPrefix($0) }) {
                return candidate
            }
        }
        return nil
    }

    return candidates.first
}

private struct EpisodeSuffix {
    let baseId: String
    let season: Int?
    let episode: Int?
}

private func parseEpisodeSuffix(_ value: String) -> EpisodeSuffix {
    let normalized = stripSeriesPrefix(value.trimmingCharacters(in: .whitespacesAndNewlines))
    let parts = normalized.split(separator: ":").map(String.init)
    if parts.count < 3 {
        return EpisodeSuffix(baseId: normalized, season: nil, episode: nil)
    }

    guard let season = Int(parts[parts.count - 2]), season > 0,
          let episode = Int(parts[parts.count - 1]), episode > 0
    else {
        return EpisodeSuffix(baseId: normalized, season: nil, episode: nil)
    }

    let baseId = stripSeriesPrefix(parts.dropLast(2).joined(separator: ":").trimmingCharacters(in: .whitespacesAndNewlines))
    return EpisodeSuffix(baseId: baseId, season: season, episode: episode)
}

private func canonicalizeBaseId(_ value: String) -> String {
    let trimmed = stripSeriesPrefix(value.trimmingCharacters(in: .whitespacesAndNewlines))
    if trimmed.isEmpty {
        return ""
    }

    if let imdb = extractImdbId(trimmed) {
        return imdb
    }

    if let tmdb = extractTmdbId(trimmed) {
        return "tmdb:\(tmdb)"
    }

    return trimmed
}

private func stripSeriesPrefix(_ value: String) -> String {
    guard value.lowercased().hasPrefix("series:") else {
        return value
    }
    return String(value.dropFirst("series:".count))
}

private func extractImdbId(_ value: String) -> String? {
    let normalized = stripSeriesPrefix(value.trimmingCharacters(in: .whitespacesAndNewlines))
    if isImdbId(normalized) {
        return normalized.lowercased()
    }

    let parts = normalized.split(separator: ":").map(String.init)
    guard let first = parts.first, first.caseInsensitiveCompare("imdb") == .orderedSame else {
        return nil
    }

    guard let token = parts.reversed().first(where: { isImdbId($0) }) else {
        return nil
    }

    return token.lowercased()
}

private func extractTmdbId(_ value: String) -> String? {
    let normalized = stripSeriesPrefix(value.trimmingCharacters(in: .whitespacesAndNewlines))
    guard normalized.lowercased().hasPrefix("tmdb:") else {
        return nil
    }

    let parts = normalized.split(separator: ":").map(String.init)
    guard parts.count >= 2 else {
        return nil
    }

    if isNumeric(parts[1]) {
        return parts[1]
    }

    if parts.count > 2, isNumeric(parts[2]) {
        return parts[2]
    }

    return nil
}

private func tmdbNumericIdOrNil(_ value: String) -> String? {
    guard value.lowercased().hasPrefix("tmdb:") else {
        return nil
    }
    let remainder = String(value.dropFirst("tmdb:".count))
    let numeric = remainder.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
    return isNumeric(numeric) ? numeric : nil
}

private func inferNumericProvider(_ idPrefixes: [String]) -> String? {
    var providers: [String] = []
    for prefix in idPrefixes {
        let lower = prefix.lowercased()
        let provider: String?
        if lower.hasPrefix("tmdb:") {
            provider = "tmdb"
        } else if lower.hasPrefix("trakt:") {
            provider = "trakt"
        } else if lower.hasPrefix("tvdb:") {
            provider = "tvdb"
        } else if lower.hasPrefix("simkl:") {
            provider = "simkl"
        } else {
            provider = nil
        }

        if let provider, !providers.contains(provider) {
            providers.append(provider)
        }
    }

    return providers.count == 1 ? providers[0] : nil
}

private func appendUnique(_ value: String, to values: inout [String], seen: inout Set<String>) {
    if seen.insert(value).inserted {
        values.append(value)
    }
}

private func isImdbId(_ value: String) -> Bool {
    value.range(of: "^tt\\d+$", options: [.regularExpression, .caseInsensitive]) != nil
}

private func isNumeric(_ value: String) -> Bool {
    !value.isEmpty && value.range(of: "^\\d+$", options: .regularExpression) != nil
}

private func containsSchemePrefix(_ value: String) -> Bool {
    value.range(of: "^[a-zA-Z][a-zA-Z0-9+.-]*:", options: .regularExpression) != nil
}
