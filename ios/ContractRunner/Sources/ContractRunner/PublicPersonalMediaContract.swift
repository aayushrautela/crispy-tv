import Foundation

public struct ContractRegularCard: Equatable {
    public let mediaType: String
    public let mediaKey: String
    public let provider: String
    public let providerId: String
    public let title: String
    public let posterUrl: String
    public let releaseYear: Int?
    public let rating: Double?
    public let genre: String?
    public let subtitle: String?

    public init(
        mediaType: String,
        mediaKey: String,
        provider: String,
        providerId: String,
        title: String,
        posterUrl: String,
        releaseYear: Int?,
        rating: Double?,
        genre: String?,
        subtitle: String?
    ) {
        self.mediaType = mediaType
        self.mediaKey = mediaKey
        self.provider = provider
        self.providerId = providerId
        self.title = title
        self.posterUrl = posterUrl
        self.releaseYear = releaseYear
        self.rating = rating
        self.genre = genre
        self.subtitle = subtitle
    }
}

public struct ContractLandscapeCard: Equatable {
    public let mediaType: String
    public let mediaKey: String
    public let provider: String
    public let providerId: String
    public let title: String
    public let posterUrl: String
    public let backdropUrl: String
    public let releaseYear: Int?
    public let rating: Double?
    public let genre: String?
    public let seasonNumber: Int?
    public let episodeNumber: Int?
    public let episodeTitle: String?
    public let airDate: String?
    public let runtimeMinutes: Int?

    public init(
        mediaType: String,
        mediaKey: String,
        provider: String,
        providerId: String,
        title: String,
        posterUrl: String,
        backdropUrl: String,
        releaseYear: Int?,
        rating: Double?,
        genre: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        airDate: String?,
        runtimeMinutes: Int?
    ) {
        self.mediaType = mediaType
        self.mediaKey = mediaKey
        self.provider = provider
        self.providerId = providerId
        self.title = title
        self.posterUrl = posterUrl
        self.backdropUrl = backdropUrl
        self.releaseYear = releaseYear
        self.rating = rating
        self.genre = genre
        self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber
        self.episodeTitle = episodeTitle
        self.airDate = airDate
        self.runtimeMinutes = runtimeMinutes
    }
}

public struct ContractWatchProgress: Equatable {
    public let positionSeconds: Double?
    public let durationSeconds: Double?
    public let progressPercent: Double
    public let lastPlayedAt: String?

    public init(positionSeconds: Double?, durationSeconds: Double?, progressPercent: Double, lastPlayedAt: String?) {
        self.positionSeconds = positionSeconds
        self.durationSeconds = durationSeconds
        self.progressPercent = progressPercent
        self.lastPlayedAt = lastPlayedAt
    }
}

public struct ContractPageInfo: Equatable {
    public let nextCursor: String?
    public let hasMore: Bool

    public init(nextCursor: String?, hasMore: Bool) {
        self.nextCursor = nextCursor
        self.hasMore = hasMore
    }
}

public struct ContractContinueWatchingItem: Equatable {
    public let id: String
    public let media: ContractLandscapeCard
    public let progress: ContractWatchProgress?
    public let lastActivityAt: String
    public let origins: [String]
    public let dismissible: Bool
}

public struct ContractHistoryItem: Equatable {
    public let id: String
    public let media: ContractRegularCard
    public let watchedAt: String
    public let origins: [String]
}

public struct ContractWatchlistItem: Equatable {
    public let id: String
    public let media: ContractRegularCard
    public let addedAt: String
    public let origins: [String]
}

public struct ContractRatingState: Equatable {
    public let value: Double
    public let ratedAt: String
}

public struct ContractRatingItem: Equatable {
    public let id: String
    public let media: ContractRegularCard
    public let rating: ContractRatingState
    public let origins: [String]
}

public enum WatchCollectionContractItem: Equatable {
    case continueWatching(ContractContinueWatchingItem)
    case history(ContractHistoryItem)
    case watchlist(ContractWatchlistItem)
    case rating(ContractRatingItem)
}

public struct WatchCollectionContractEnvelope: Equatable {
    public let profileId: String
    public let kind: String
    public let source: String
    public let generatedAt: String
    public let items: [WatchCollectionContractItem]
    public let pageInfo: ContractPageInfo

    public init(
        profileId: String,
        kind: String,
        source: String,
        generatedAt: String,
        items: [WatchCollectionContractItem],
        pageInfo: ContractPageInfo
    ) {
        self.profileId = profileId
        self.kind = kind
        self.source = source
        self.generatedAt = generatedAt
        self.items = items
        self.pageInfo = pageInfo
    }
}

public struct CalendarContractItem: Equatable {
    public let bucket: String
    public let media: ContractLandscapeCard
    public let relatedShow: ContractRegularCard
    public let airDate: String?
    public let watched: Bool
}

public struct CalendarContractEnvelope: Equatable {
    public let profileId: String
    public let source: String
    public let generatedAt: String
    public let kind: String?
    public let items: [CalendarContractItem]

    public init(profileId: String, source: String, generatedAt: String, kind: String?, items: [CalendarContractItem]) {
        self.profileId = profileId
        self.source = source
        self.generatedAt = generatedAt
        self.kind = kind
        self.items = items
    }
}

public func normalizeWatchCollectionEnvelope(payload: [String: Any]) -> WatchCollectionContractEnvelope? {
    guard hasExactKeys(payload, expected: ["profileId", "kind", "source", "generatedAt", "items", "pageInfo"]),
          let profileId = requiredString(payload, "profileId"),
          let kind = requiredString(payload, "kind"),
          ["continue-watching", "history", "watchlist", "ratings"].contains(kind),
          let source = requiredString(payload, "source"),
          source == "canonical_watch",
          let generatedAt = requiredString(payload, "generatedAt"),
          let itemValues = payload["items"] as? [Any],
          let pageInfoObject = payload["pageInfo"] as? [String: Any],
          let pageInfo = parsePageInfo(pageInfoObject) else {
        return nil
    }

    let items: [WatchCollectionContractItem]?
    switch kind {
    case "continue-watching":
        items = mapStrict(itemValues) { value in
            guard let object = value as? [String: Any], let item = parseContinueWatchingItem(object) else { return nil }
            return .continueWatching(item)
        }
    case "history":
        items = mapStrict(itemValues) { value in
            guard let object = value as? [String: Any], let item = parseHistoryItem(object) else { return nil }
            return .history(item)
        }
    case "watchlist":
        items = mapStrict(itemValues) { value in
            guard let object = value as? [String: Any], let item = parseWatchlistItem(object) else { return nil }
            return .watchlist(item)
        }
    case "ratings":
        items = mapStrict(itemValues) { value in
            guard let object = value as? [String: Any], let item = parseRatingItem(object) else { return nil }
            return .rating(item)
        }
    default:
        items = nil
    }

    guard let normalizedItems = items else {
        return nil
    }

    return WatchCollectionContractEnvelope(
        profileId: profileId,
        kind: kind,
        source: source,
        generatedAt: generatedAt,
        items: normalizedItems,
        pageInfo: pageInfo
    )
}

public func normalizeCalendarEnvelope(payload: [String: Any], route: String) -> CalendarContractEnvelope? {
    let expectedKeys: Set<String>
    switch route {
    case "calendar":
        expectedKeys = ["profileId", "source", "generatedAt", "items"]
    case "this-week":
        expectedKeys = ["profileId", "source", "kind", "generatedAt", "items"]
    default:
        return nil
    }

    guard hasExactKeys(payload, expected: expectedKeys),
          let profileId = requiredString(payload, "profileId"),
          let source = requiredString(payload, "source"),
          source == "canonical_calendar",
          let generatedAt = requiredString(payload, "generatedAt"),
          let itemValues = payload["items"] as? [Any] else {
        return nil
    }

    let kind: String?
    switch route {
    case "this-week":
        guard let rawKind = requiredString(payload, "kind"), rawKind == "this-week" else {
            return nil
        }
        kind = rawKind
    default:
        kind = nil
    }

    guard let items = mapStrict(itemValues, transform: { value in
        guard let object = value as? [String: Any] else { return nil }
        return parseCalendarItem(object)
    }) else {
        return nil
    }

    return CalendarContractEnvelope(
        profileId: profileId,
        source: source,
        generatedAt: generatedAt,
        kind: kind,
        items: items
    )
}

private func parseContinueWatchingItem(_ payload: [String: Any]) -> ContractContinueWatchingItem? {
    guard hasExactKeys(payload, expected: ["id", "media", "progress", "lastActivityAt", "origins", "dismissible"]),
          let id = requiredString(payload, "id"),
          let mediaObject = payload["media"] as? [String: Any],
          let media = parseLandscapeCard(mediaObject),
          let lastActivityAt = requiredString(payload, "lastActivityAt"),
          let originsRaw = payload["origins"] as? [Any],
          let origins = stringArray(originsRaw, key: "origins"),
          let dismissible = payload["dismissible"] as? Bool else {
        return nil
    }
    let progress = optionalObject(payload, "progress").flatMap(parseWatchProgress)
    if payload.keys.contains("progress") && payload["progress"] is NSNull == false && progress == nil {
        return nil
    }
    return ContractContinueWatchingItem(
        id: id,
        media: media,
        progress: progress,
        lastActivityAt: lastActivityAt,
        origins: origins,
        dismissible: dismissible
    )
}

private func parseHistoryItem(_ payload: [String: Any]) -> ContractHistoryItem? {
    guard hasExactKeys(payload, expected: ["id", "media", "watchedAt", "origins"]),
          let id = requiredString(payload, "id"),
          let mediaObject = payload["media"] as? [String: Any],
          let media = parseRegularCard(mediaObject),
          let watchedAt = requiredString(payload, "watchedAt"),
          let originsRaw = payload["origins"] as? [Any],
          let origins = stringArray(originsRaw, key: "origins") else {
        return nil
    }
    return ContractHistoryItem(id: id, media: media, watchedAt: watchedAt, origins: origins)
}

private func parseWatchlistItem(_ payload: [String: Any]) -> ContractWatchlistItem? {
    guard hasExactKeys(payload, expected: ["id", "media", "addedAt", "origins"]),
          let id = requiredString(payload, "id"),
          let mediaObject = payload["media"] as? [String: Any],
          let media = parseRegularCard(mediaObject),
          let addedAt = requiredString(payload, "addedAt"),
          let originsRaw = payload["origins"] as? [Any],
          let origins = stringArray(originsRaw, key: "origins") else {
        return nil
    }
    return ContractWatchlistItem(id: id, media: media, addedAt: addedAt, origins: origins)
}

private func parseRatingItem(_ payload: [String: Any]) -> ContractRatingItem? {
    guard hasExactKeys(payload, expected: ["id", "media", "rating", "origins"]),
          let id = requiredString(payload, "id"),
          let mediaObject = payload["media"] as? [String: Any],
          let media = parseRegularCard(mediaObject),
          let ratingObject = payload["rating"] as? [String: Any],
          let rating = parseRatingState(ratingObject),
          let originsRaw = payload["origins"] as? [Any],
          let origins = stringArray(originsRaw, key: "origins") else {
        return nil
    }
    return ContractRatingItem(id: id, media: media, rating: rating, origins: origins)
}

private func parseRatingState(_ payload: [String: Any]) -> ContractRatingState? {
    guard hasExactKeys(payload, expected: ["value", "ratedAt"]),
          let value = doubleValue(payload["value"]),
          let ratedAt = requiredString(payload, "ratedAt") else {
        return nil
    }
    return ContractRatingState(value: value, ratedAt: ratedAt)
}

private func parseWatchProgress(_ payload: [String: Any]) -> ContractWatchProgress? {
    guard hasExactKeys(payload, expected: ["positionSeconds", "durationSeconds", "progressPercent", "lastPlayedAt"]),
          let progressPercent = doubleValue(payload["progressPercent"]) else {
        return nil
    }
    return ContractWatchProgress(
        positionSeconds: nullableDouble(payload, "positionSeconds"),
        durationSeconds: nullableDouble(payload, "durationSeconds"),
        progressPercent: progressPercent,
        lastPlayedAt: optionalString(payload, "lastPlayedAt")
    )
}

private func parsePageInfo(_ payload: [String: Any]) -> ContractPageInfo? {
    guard hasExactKeys(payload, expected: ["nextCursor", "hasMore"]),
          let hasMore = payload["hasMore"] as? Bool else {
        return nil
    }
    return ContractPageInfo(nextCursor: optionalString(payload, "nextCursor"), hasMore: hasMore)
}

private func parseCalendarItem(_ payload: [String: Any]) -> CalendarContractItem? {
    guard hasExactKeys(payload, expected: ["bucket", "media", "relatedShow", "airDate", "watched"]),
          let bucket = requiredString(payload, "bucket"),
          ["up_next", "this_week", "upcoming", "recently_released", "no_scheduled"].contains(bucket),
          let mediaObject = payload["media"] as? [String: Any],
          let media = parseLandscapeCard(mediaObject),
          let relatedShowObject = payload["relatedShow"] as? [String: Any],
          let relatedShow = parseRegularCard(relatedShowObject),
          let watched = payload["watched"] as? Bool else {
        return nil
    }
    return CalendarContractItem(bucket: bucket, media: media, relatedShow: relatedShow, airDate: optionalString(payload, "airDate"), watched: watched)
}

private func parseRegularCard(_ payload: [String: Any]) -> ContractRegularCard? {
    guard hasExactKeys(payload, expected: ["mediaType", "mediaKey", "provider", "providerId", "title", "posterUrl", "releaseYear", "rating", "genre", "subtitle"]),
          let mediaType = requiredString(payload, "mediaType"),
          let mediaKey = requiredString(payload, "mediaKey"),
          let provider = requiredString(payload, "provider"),
          let providerId = requiredString(payload, "providerId"),
          let title = requiredString(payload, "title"),
          let posterUrl = requiredString(payload, "posterUrl") else {
        return nil
    }
    return ContractRegularCard(
        mediaType: mediaType,
        mediaKey: mediaKey,
        provider: provider,
        providerId: providerId,
        title: title,
        posterUrl: posterUrl,
        releaseYear: nullableInt(payload, "releaseYear"),
        rating: nullableDouble(payload, "rating"),
        genre: optionalString(payload, "genre"),
        subtitle: optionalString(payload, "subtitle")
    )
}

private func parseLandscapeCard(_ payload: [String: Any]) -> ContractLandscapeCard? {
    guard hasExactKeys(payload, expected: ["mediaType", "mediaKey", "provider", "providerId", "title", "posterUrl", "backdropUrl", "releaseYear", "rating", "genre", "seasonNumber", "episodeNumber", "episodeTitle", "airDate", "runtimeMinutes"]),
          let mediaType = requiredString(payload, "mediaType"),
          let mediaKey = requiredString(payload, "mediaKey"),
          let provider = requiredString(payload, "provider"),
          let providerId = requiredString(payload, "providerId"),
          let title = requiredString(payload, "title"),
          let posterUrl = requiredString(payload, "posterUrl"),
          let backdropUrl = requiredString(payload, "backdropUrl") else {
        return nil
    }
    return ContractLandscapeCard(
        mediaType: mediaType,
        mediaKey: mediaKey,
        provider: provider,
        providerId: providerId,
        title: title,
        posterUrl: posterUrl,
        backdropUrl: backdropUrl,
        releaseYear: nullableInt(payload, "releaseYear"),
        rating: nullableDouble(payload, "rating"),
        genre: optionalString(payload, "genre"),
        seasonNumber: nullableInt(payload, "seasonNumber"),
        episodeNumber: nullableInt(payload, "episodeNumber"),
        episodeTitle: optionalString(payload, "episodeTitle"),
        airDate: optionalString(payload, "airDate"),
        runtimeMinutes: nullableInt(payload, "runtimeMinutes")
    )
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

private func optionalObject(_ object: [String: Any], _ key: String) -> [String: Any]? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    return value as? [String: Any]
}

private func stringArray(_ values: [Any], key: String) -> [String]? {
    var output: [String] = []
    for value in values {
        guard let text = value as? String else {
            return nil
        }
        output.append(text)
    }
    return output
}

private func nullableInt(_ object: [String: Any], _ key: String) -> Int? {
    guard let value = object[key] else { return nil }
    if value is NSNull { return nil }
    return intValue(value)
}

private func nullableDouble(_ object: [String: Any], _ key: String) -> Double? {
    guard let value = object[key] else { return nil }
    if value is NSNull { return nil }
    return doubleValue(value)
}

private func doubleValue(_ value: Any?) -> Double? {
    if let double = value as? Double { return double }
    if let int = value as? Int { return Double(int) }
    if let number = value as? NSNumber { return number.doubleValue }
    return nil
}

private func intValue(_ value: Any?) -> Int? {
    if let int = value as? Int { return int }
    if let number = value as? NSNumber { return number.intValue }
    return nil
}

private func mapStrict<T>(_ values: [Any], transform: (Any) -> T?) -> [T]? {
    var results: [T] = []
    for value in values {
        guard let result = transform(value) else { return nil }
        results.append(result)
    }
    return results
}
