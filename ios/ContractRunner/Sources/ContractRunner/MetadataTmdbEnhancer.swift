import Foundation

public enum MetadataMediaType: String {
    case movie
    case series

    public static func fromContractValue(_ value: String) -> MetadataMediaType {
        value.caseInsensitiveCompare("series") == .orderedSame ? .series : .movie
    }
}

public struct MetadataSeason: Equatable {
    public let id: String
    public let name: String
    public let overview: String
    public let seasonNumber: Int
    public let episodeCount: Int
    public let airDate: String?

    public init(
        id: String,
        name: String,
        overview: String,
        seasonNumber: Int,
        episodeCount: Int,
        airDate: String?
    ) {
        self.id = id
        self.name = name
        self.overview = overview
        self.seasonNumber = seasonNumber
        self.episodeCount = episodeCount
        self.airDate = airDate
    }
}

public struct MetadataVideo: Equatable {
    public let season: Int?
    public let episode: Int?
    public let released: String?

    public init(season: Int?, episode: Int?, released: String?) {
        self.season = season
        self.episode = episode
        self.released = released
    }
}

public struct MetadataRecord: Equatable {
    public let id: String
    public let imdbId: String?
    public let cast: [String]
    public let director: [String]
    public let castWithDetails: [String]
    public let similar: [String]
    public let collectionItems: [String]
    public let seasons: [MetadataSeason]
    public let videos: [MetadataVideo]

    public init(
        id: String,
        imdbId: String?,
        cast: [String],
        director: [String],
        castWithDetails: [String],
        similar: [String],
        collectionItems: [String],
        seasons: [MetadataSeason],
        videos: [MetadataVideo]
    ) {
        self.id = id
        self.imdbId = imdbId
        self.cast = cast
        self.director = director
        self.castWithDetails = castWithDetails
        self.similar = similar
        self.collectionItems = collectionItems
        self.seasons = seasons
        self.videos = videos
    }
}

public func needsTmdbMetaEnrichment(_ meta: MetadataRecord, mediaType: MetadataMediaType) -> Bool {
    if meta.castWithDetails.isEmpty {
        return true
    }
    if meta.similar.isEmpty {
        return true
    }
    return mediaType == .movie && meta.collectionItems.isEmpty
}

public func mergeAddonAndTmdbMeta(
    addonMeta: MetadataRecord,
    tmdbMeta: MetadataRecord?,
    mediaType: MetadataMediaType
) -> MetadataRecord {
    guard let tmdbMeta else {
        return addonMeta
    }

    return MetadataRecord(
        id: addonMeta.id.isEmpty ? tmdbMeta.id : addonMeta.id,
        imdbId: nonBlankOrNil(addonMeta.imdbId) ?? nonBlankOrNil(tmdbMeta.imdbId),
        cast: addonMeta.cast.isEmpty ? tmdbMeta.cast : addonMeta.cast,
        director: addonMeta.director.isEmpty ? tmdbMeta.director : addonMeta.director,
        castWithDetails: addonMeta.castWithDetails.isEmpty ? tmdbMeta.castWithDetails : addonMeta.castWithDetails,
        similar: addonMeta.similar.isEmpty ? tmdbMeta.similar : addonMeta.similar,
        collectionItems: mediaType == .movie
            ? (addonMeta.collectionItems.isEmpty ? tmdbMeta.collectionItems : addonMeta.collectionItems)
            : addonMeta.collectionItems,
        seasons: addonMeta.seasons,
        videos: addonMeta.videos
    )
}

public func withDerivedSeasons(_ meta: MetadataRecord, mediaType: MetadataMediaType) -> MetadataRecord {
    guard mediaType == .series, meta.seasons.isEmpty else {
        return meta
    }

    var bySeason: [Int: SeasonAccumulator] = [:]
    for video in meta.videos {
        guard let season = video.season, season > 0,
              let episode = video.episode, episode > 0 else {
            continue
        }

        var bucket = bySeason[season] ?? SeasonAccumulator()
        bucket.episodeCount += 1
        if bucket.airDate == nil {
            bucket.airDate = nonBlankOrNil(video.released)
        }
        bySeason[season] = bucket
    }

    let seasons = bySeason.keys.sorted().compactMap { seasonNumber in
        guard let bucket = bySeason[seasonNumber] else {
            return nil
        }
        return MetadataSeason(
            id: "\(meta.id):season:\(seasonNumber)",
            name: "Season \(seasonNumber)",
            overview: "",
            seasonNumber: seasonNumber,
            episodeCount: bucket.episodeCount,
            airDate: bucket.airDate
        )
    }

    return MetadataRecord(
        id: meta.id,
        imdbId: meta.imdbId,
        cast: meta.cast,
        director: meta.director,
        castWithDetails: meta.castWithDetails,
        similar: meta.similar,
        collectionItems: meta.collectionItems,
        seasons: seasons,
        videos: meta.videos
    )
}

public func bridgeCandidateIds(
    contentId: String,
    season: Int?,
    episode: Int?,
    tmdbMeta: MetadataRecord?
) -> [String] {
    let normalizedContentId = contentId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !normalizedContentId.isEmpty else {
        return []
    }

    var candidates: [String] = [normalizedEpisodeId(normalizedContentId, season: season, episode: episode)]
    if normalizedContentId.lowercased().hasPrefix("tmdb:") {
        let bridgedBase = nonBlankOrNil(tmdbMeta?.imdbId)
            ?? nonBlankOrNil(tmdbMeta?.id).flatMap { candidate -> String? in
                candidate.caseInsensitiveCompare(normalizedContentId) == .orderedSame ? nil : candidate
            }
        if let bridgedBase {
            candidates.append(normalizedEpisodeId(bridgedBase, season: season, episode: episode))
        }
    }

    var seen = Set<String>()
    var deduped: [String] = []
    for candidate in candidates {
        let key = candidate.lowercased()
        if seen.insert(key).inserted {
            deduped.append(candidate)
        }
    }
    return deduped
}

private struct SeasonAccumulator {
    var episodeCount = 0
    var airDate: String?
}

private func normalizedEpisodeId(_ contentId: String, season: Int?, episode: Int?) -> String {
    if let season, season > 0, let episode, episode > 0 {
        return "\(contentId):\(season):\(episode)"
    }
    return contentId
}

private func nonBlankOrNil(_ value: String?) -> String? {
    guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
        return nil
    }
    return trimmed
}
