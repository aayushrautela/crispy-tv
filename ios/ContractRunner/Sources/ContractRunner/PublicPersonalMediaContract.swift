import Foundation

public struct ContractMediaExternalIds: Equatable {
    public let tmdb: Int?
    public let imdb: String?
    public let tvdb: Int?

    public init(tmdb: Int?, imdb: String?, tvdb: Int?) {
        self.tmdb = tmdb
        self.imdb = imdb
        self.tvdb = tvdb
    }
}

public struct ContractUserItemData: Equatable {
    public let itemId: String?
    public let isFavorite: Bool?
    public let played: Bool?
    public let playCount: Int?
    public let playbackPositionSeconds: Double?
    public let runtimeSeconds: Double?
    public let playedPercentage: Double?
    public let lastPlayedDate: String?
    public let rating: Double?
    public let dismissedFromContinueWatching: Bool?

    public init(
        itemId: String? = nil,
        isFavorite: Bool? = nil,
        played: Bool? = nil,
        playCount: Int? = nil,
        playbackPositionSeconds: Double? = nil,
        runtimeSeconds: Double? = nil,
        playedPercentage: Double? = nil,
        lastPlayedDate: String? = nil,
        rating: Double? = nil,
        dismissedFromContinueWatching: Bool? = nil
    ) {
        self.itemId = itemId
        self.isFavorite = isFavorite
        self.played = played
        self.playCount = playCount
        self.playbackPositionSeconds = playbackPositionSeconds
        self.runtimeSeconds = runtimeSeconds
        self.playedPercentage = playedPercentage
        self.lastPlayedDate = lastPlayedDate
        self.rating = rating
        self.dismissedFromContinueWatching = dismissedFromContinueWatching
    }
}

public struct ContractMediaItem: Equatable {
    public let itemId: String
    public let mediaType: String
    public let title: String
    public let originalTitle: String?
    public let overview: String?
    public let posterUrl: String?
    public let backdropUrl: String?
    public let logoUrl: String?
    public let stillUrl: String?
    public let releaseDate: String?
    public let releaseYear: Int?
    public let rating: Double?
    public let genres: [String]
    public let runtimeMinutes: Int?
    public let status: String?
    public let certification: String?
    public let externalIds: ContractMediaExternalIds
    public let seasonNumber: Int?
    public let episodeNumber: Int?
    public let absoluteEpisodeNumber: Int?
    public let episodeTitle: String?
    public let airDate: String?
    public let tagline: String?
    public let seriesId: String?
    public let seriesName: String?
    public let seasonId: String?
    public let seasonName: String?
    public let userData: ContractUserItemData?

    public init(
        itemId: String,
        mediaType: String,
        title: String,
        originalTitle: String? = nil,
        overview: String? = nil,
        posterUrl: String? = nil,
        backdropUrl: String? = nil,
        logoUrl: String? = nil,
        stillUrl: String? = nil,
        releaseDate: String? = nil,
        releaseYear: Int? = nil,
        rating: Double? = nil,
        genres: [String] = [],
        runtimeMinutes: Int? = nil,
        status: String? = nil,
        certification: String? = nil,
        externalIds: ContractMediaExternalIds,
        seasonNumber: Int? = nil,
        episodeNumber: Int? = nil,
        absoluteEpisodeNumber: Int? = nil,
        episodeTitle: String? = nil,
        airDate: String? = nil,
        tagline: String? = nil,
        seriesId: String? = nil,
        seriesName: String? = nil,
        seasonId: String? = nil,
        seasonName: String? = nil,
        userData: ContractUserItemData? = nil
    ) {
        self.itemId = itemId
        self.mediaType = mediaType
        self.title = title
        self.originalTitle = originalTitle
        self.overview = overview
        self.posterUrl = posterUrl
        self.backdropUrl = backdropUrl
        self.logoUrl = logoUrl
        self.stillUrl = stillUrl
        self.releaseDate = releaseDate
        self.releaseYear = releaseYear
        self.rating = rating
        self.genres = genres
        self.runtimeMinutes = runtimeMinutes
        self.status = status
        self.certification = certification
        self.externalIds = externalIds
        self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber
        self.absoluteEpisodeNumber = absoluteEpisodeNumber
        self.episodeTitle = episodeTitle
        self.airDate = airDate
        self.tagline = tagline
        self.seriesId = seriesId
        self.seriesName = seriesName
        self.seasonId = seasonId
        self.seasonName = seasonName
        self.userData = userData
    }
}

public struct ContractBaseItemDtoQueryResult: Equatable {
    public let items: [ContractMediaItem]
    public let startIndex: Int
    public let totalRecordCount: Int
    public let nextCursor: String?
    public let hasMore: Bool

    public init(items: [ContractMediaItem], startIndex: Int, totalRecordCount: Int, nextCursor: String?, hasMore: Bool) {
        self.items = items
        self.startIndex = startIndex
        self.totalRecordCount = totalRecordCount
        self.nextCursor = nextCursor
        self.hasMore = hasMore
    }
}

public struct ContractCalendarEnvelope: Equatable {
    public let profileId: String
    public let source: String
    public let kind: String?
    public let generatedAt: String
    public let items: [ContractMediaItem]

    public init(profileId: String, source: String, kind: String?, generatedAt: String, items: [ContractMediaItem]) {
        self.profileId = profileId
        self.source = source
        self.kind = kind
        self.generatedAt = generatedAt
        self.items = items
    }
}

public func normalizeBaseItemDtoQueryResult(payload: [String: Any]) -> ContractBaseItemDtoQueryResult? {
    guard hasExactKeys(payload, expected: ["Items", "StartIndex", "TotalRecordCount", "NextCursor", "HasMore"]),
          let itemValues = payload["Items"] as? [Any],
          let items = mapStrict(itemValues, transform: { value -> ContractMediaItem? in
              guard let object = value as? [String: Any] else { return nil }
              return parseMediaItem(object)
          }),
          let hasMore = payload["HasMore"] as? Bool else {
        return nil
    }
    let startIndex = nullableInt(payload, "StartIndex") ?? 0
    let totalRecordCount = nullableInt(payload, "TotalRecordCount") ?? items.count
    let nextCursor = optionalString(payload, "NextCursor")
    return ContractBaseItemDtoQueryResult(items: items, startIndex: startIndex, totalRecordCount: totalRecordCount, nextCursor: nextCursor, hasMore: hasMore)
}

public func normalizeCalendarEnvelope(payload: [String: Any]) -> ContractCalendarEnvelope? {
    guard let profileId = requiredString(payload, "profileId"),
          let source = requiredString(payload, "source"),
          let generatedAt = requiredString(payload, "generatedAt"),
          let itemValues = payload["items"] as? [Any],
          let items = mapStrict(itemValues, transform: { value -> ContractMediaItem? in
              guard let object = value as? [String: Any] else { return nil }
              return parseMediaItem(object)
          }) else {
        return nil
    }
    let kind = optionalString(payload, "kind")
    return ContractCalendarEnvelope(profileId: profileId, source: source, kind: kind, generatedAt: generatedAt, items: items)
}

private func parseMediaItem(_ payload: [String: Any]) -> ContractMediaItem? {
    guard let itemId = requiredString(payload, "Id"),
          let type = requiredString(payload, "Type"),
          let name = requiredString(payload, "Name") else {
        return nil
    }

    let imageTags = payload["ImageTags"] as? [String: Any]
    let providerIds = payload["ProviderIds"] as? [String: Any]
    let externalIds = ContractMediaExternalIds(
        tmdb: optionalString(providerIds ?? [:], "Tmdb").flatMap { Int($0) },
        imdb: optionalString(providerIds ?? [:], "Imdb"),
        tvdb: optionalString(providerIds ?? [:], "Tvdb").flatMap { Int($0) }
    )
    let userData = optionalObject(payload, "UserData").flatMap(parseContractUserItemData)
    let taglines = stringArray(payload["Taglines"] as? [Any] ?? [], key: "Taglines") ?? []

    return ContractMediaItem(
        itemId: itemId,
        mediaType: parseContractMediaItemType(type),
        title: name,
        originalTitle: optionalString(payload, "OriginalTitle"),
        overview: optionalString(payload, "Overview"),
        posterUrl: imageTagMedium(imageTags, "Primary"),
        backdropUrl: backdropMedium(imageTags),
        logoUrl: imageTagMedium(imageTags, "Logo"),
        stillUrl: imageTagMedium(imageTags, "Thumb"),
        releaseDate: optionalString(payload, "PremiereDate"),
        releaseYear: nullableInt(payload, "ProductionYear"),
        rating: nullableDouble(payload, "CommunityRating"),
        genres: stringArray(payload["Genres"] as? [Any] ?? [], key: "Genres") ?? [],
        runtimeMinutes: nullableDouble(payload, "RunTimeTicks").flatMap { $0 > 0 ? Int($0 / 600_000_000) : nil },
        status: optionalString(payload, "Status"),
        certification: optionalString(payload, "Certification"),
        externalIds: externalIds,
        seasonNumber: nullableInt(payload, "ParentIndexNumber"),
        episodeNumber: nullableInt(payload, "IndexNumber"),
        absoluteEpisodeNumber: nullableInt(payload, "AbsoluteIndexNumber"),
        episodeTitle: optionalString(payload, "EpisodeTitle"),
        airDate: optionalString(payload, "AirDate"),
        tagline: taglines.first,
        seriesId: optionalString(payload, "SeriesId"),
        seriesName: optionalString(payload, "SeriesName"),
        seasonId: optionalString(payload, "SeasonId"),
        seasonName: optionalString(payload, "SeasonName"),
        userData: userData
    )
}

private func parseContractMediaItemType(_ type: String) -> String {
    switch type.trimmingCharacters(in: .whitespacesAndNewlines) {
    case "Movie": return "movie"
    case "Series": return "show"
    case "Season": return "season"
    case "Episode": return "episode"
    case "Unknown": return "unknown"
    default: return "unknown"
    }
}

private func parseContractUserItemData(_ payload: [String: Any]) -> ContractUserItemData? {
    if payload.isEmpty { return nil }
    return ContractUserItemData(
        itemId: optionalString(payload, "ItemId"),
        isFavorite: payload["IsFavorite"] as? Bool,
        played: payload["Played"] as? Bool,
        playCount: (payload["PlayCount"] as? NSNumber)?.intValue,
        playbackPositionSeconds: doubleValue(payload["PlaybackPositionTicks"] as Any).map { $0 / 10_000_000 },
        runtimeSeconds: doubleValue(payload["RuntimeTicks"] as Any).map { $0 / 10_000_000 },
        playedPercentage: doubleValue(payload["PlayedPercentage"] as Any),
        lastPlayedDate: optionalString(payload, "LastPlayedDate"),
        rating: doubleValue(payload["Rating"] as Any),
        dismissedFromContinueWatching: payload["DismissedFromContinueWatching"] as? Bool
    )
}

private func imageTagMedium(_ tags: [String: Any]?, _ key: String) -> String? {
    guard let tag = tags?[key] else { return nil }
    if let string = tag as? String { return string }
    if let dict = tag as? [String: Any] { return optionalString(dict, "medium") }
    return nil
}

private func backdropMedium(_ tags: [String: Any]?) -> String? {
    guard let backdrops = tags?["Backdrop"] as? [Any], let first = backdrops.first else { return nil }
    if let string = first as? String { return string }
    if let dict = first as? [String: Any] { return optionalString(dict, "medium") }
    return nil
}

private func hasExactKeys(_ object: [String: Any], expected: Set<String>) -> Bool {
    return Set(object.keys) == expected
}

private func requiredString(_ object: [String: Any], _ key: String) -> String? {
    return object[key] as? String
}

private func optionalString(_ object: [String: Any], _ key: String) -> String? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    return value as? String
}

private func nullableInt(_ object: [String: Any], _ key: String) -> Int? {
    guard let value = object[key] else { return nil }
    if value is NSNull { return nil }
    return intValue(value)
}

private func intValue(_ value: Any) -> Int? {
    if let int = value as? Int {
        return int
    }
    if let number = value as? NSNumber {
        return number.intValue
    }
    if let string = value as? String {
        return Int(string.trimmingCharacters(in: .whitespacesAndNewlines))
    }
    return nil
}

private func doubleValue(_ value: Any) -> Double? {
    if let double = value as? Double {
        return double
    }
    if let number = value as? NSNumber {
        return number.doubleValue
    }
    if let string = value as? String {
        return Double(string.trimmingCharacters(in: .whitespacesAndNewlines))
    }
    return nil
}

private func mapStrict<T>(_ values: [Any], transform: (Any) -> T?) -> [T]? {
    var output: [T] = []
    for value in values {
        guard let transformed = transform(value) else {
            return nil
        }
        output.append(transformed)
    }
    return output
}

private func stringArray(_ values: [Any], key: String) -> [String]? {
    var output: [String] = []
    for value in values {
        if let string = value as? String {
            output.append(string)
        } else {
            return nil
        }
    }
    return output
}

private func optionalObject(_ object: [String: Any], _ key: String) -> [String: Any]? {
    guard let value = object[key] else { return nil }
    if value is NSNull { return nil }
    return value as? [String: Any]
}

private func nullableDouble(_ object: [String: Any], _ key: String) -> Double? {
    guard let value = object[key] else { return nil }
    if value is NSNull { return nil }
    return doubleValue(value)
}
