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

public struct ContractMediaItemParent: Equatable {
    public let mediaKey: String
    public let mediaType: String
    public let title: String

    public init(mediaKey: String, mediaType: String, title: String) {
        self.mediaKey = mediaKey
        self.mediaType = mediaType
        self.title = title
    }
}

public struct ContractMediaItem: Equatable {
    public let mediaKey: String
    public let mediaType: String
    public let title: String
    public let originalTitle: String?
    public let subtitle: String?
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
    public let parent: ContractMediaItemParent?
    public let showTmdbId: Int?
    public let seasonNumber: Int?
    public let episodeNumber: Int?
    public let absoluteEpisodeNumber: Int?
    public let episodeTitle: String?
    public let airDate: String?

    public init(
        mediaKey: String,
        mediaType: String,
        title: String,
        originalTitle: String? = nil,
        subtitle: String? = nil,
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
        parent: ContractMediaItemParent? = nil,
        showTmdbId: Int? = nil,
        seasonNumber: Int? = nil,
        episodeNumber: Int? = nil,
        absoluteEpisodeNumber: Int? = nil,
        episodeTitle: String? = nil,
        airDate: String? = nil
    ) {
        self.mediaKey = mediaKey
        self.mediaType = mediaType
        self.title = title
        self.originalTitle = originalTitle
        self.subtitle = subtitle
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
        self.parent = parent
        self.showTmdbId = showTmdbId
        self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber
        self.absoluteEpisodeNumber = absoluteEpisodeNumber
        self.episodeTitle = episodeTitle
        self.airDate = airDate
    }
}

public struct ContractMediaPresentationHint: Equatable {
    public let preferredSize: String?
    public let sectionId: String?
    public let sectionTitle: String?

    public init(preferredSize: String?, sectionId: String?, sectionTitle: String?) {
        self.preferredSize = preferredSize
        self.sectionId = sectionId
        self.sectionTitle = sectionTitle
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
    public let mediaItem: ContractMediaItem
    public let context: [String: String]
    public let presentation: ContractMediaPresentationHint?
    public let progress: ContractWatchProgress?
    public let lastActivityAt: String
    public let origins: [String]
    public let dismissible: Bool
}

public struct ContractHistoryItem: Equatable {
    public let id: String
    public let mediaItem: ContractMediaItem
    public let context: [String: String]
    public let presentation: ContractMediaPresentationHint?
    public let watchedAt: String
    public let lastActivityAt: String?
    public let origins: [String]
}

public struct ContractWatchlistItem: Equatable {
    public let id: String
    public let mediaItem: ContractMediaItem
    public let context: [String: String]
    public let presentation: ContractMediaPresentationHint?
    public let addedAt: String
    public let origins: [String]
}

public struct ContractRatingState: Equatable {
    public let value: Double
    public let ratedAt: String
}

public struct ContractRatingItem: Equatable {
    public let id: String
    public let mediaItem: ContractMediaItem
    public let context: [String: String]
    public let presentation: ContractMediaPresentationHint?
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

public struct CalendarContractContext: Equatable {
    public let bucket: String?
    public let airDate: String?
    public let watched: Bool?
    public let relatedShow: ContractMediaItem?

    public init(bucket: String?, airDate: String?, watched: Bool?, relatedShow: ContractMediaItem?) {
        self.bucket = bucket
        self.airDate = airDate
        self.watched = watched
        self.relatedShow = relatedShow
    }
}

public struct CalendarContractItem: Equatable {
    public let bucket: String
    public let kind: String
    public let mediaItem: ContractMediaItem
    public let context: CalendarContractContext
    public let presentation: ContractMediaPresentationHint?
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

    guard let items = mapStrict(itemValues, transform: { value -> CalendarContractItem? in
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
    guard hasExactKeys(payload, expected: ["id", "kind", "mediaItem", "context", "presentation", "progress", "lastActivityAt", "origins", "dismissible"]),
          let id = requiredString(payload, "id"),
          let mediaItemObject = payload["mediaItem"] as? [String: Any],
          let mediaItem = parseMediaItem(mediaItemObject),
          let contextObject = payload["context"] as? [String: Any],
          let context = stringifyContext(contextObject),
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
    let presentation = optionalObject(payload, "presentation").flatMap(parseMediaPresentationHint)
    return ContractContinueWatchingItem(
        id: id,
        mediaItem: mediaItem,
        context: context,
        presentation: presentation,
        progress: progress,
        lastActivityAt: lastActivityAt,
        origins: origins,
        dismissible: dismissible
    )
}

private func parseHistoryItem(_ payload: [String: Any]) -> ContractHistoryItem? {
    guard hasExactKeys(payload, expected: ["id", "kind", "mediaItem", "context", "presentation", "watchedAt", "origins"]),
          let id = requiredString(payload, "id"),
          let mediaItemObject = payload["mediaItem"] as? [String: Any],
          let mediaItem = parseMediaItem(mediaItemObject),
          let contextObject = payload["context"] as? [String: Any],
          let context = stringifyContext(contextObject),
          let watchedAt = requiredString(payload, "watchedAt"),
          let originsRaw = payload["origins"] as? [Any],
          let origins = stringArray(originsRaw, key: "origins") else {
        return nil
    }
    let presentation = optionalObject(payload, "presentation").flatMap(parseMediaPresentationHint)
    return ContractHistoryItem(
        id: id,
        mediaItem: mediaItem,
        context: context,
        presentation: presentation,
        watchedAt: watchedAt,
        lastActivityAt: nil,
        origins: origins
    )
}

private func parseWatchlistItem(_ payload: [String: Any]) -> ContractWatchlistItem? {
    guard hasExactKeys(payload, expected: ["id", "kind", "mediaItem", "context", "presentation", "addedAt", "origins"]),
          let id = requiredString(payload, "id"),
          let mediaItemObject = payload["mediaItem"] as? [String: Any],
          let mediaItem = parseMediaItem(mediaItemObject),
          let contextObject = payload["context"] as? [String: Any],
          let context = stringifyContext(contextObject),
          let addedAt = requiredString(payload, "addedAt"),
          let originsRaw = payload["origins"] as? [Any],
          let origins = stringArray(originsRaw, key: "origins") else {
        return nil
    }
    let presentation = optionalObject(payload, "presentation").flatMap(parseMediaPresentationHint)
    return ContractWatchlistItem(
        id: id,
        mediaItem: mediaItem,
        context: context,
        presentation: presentation,
        addedAt: addedAt,
        origins: origins
    )
}

private func parseRatingItem(_ payload: [String: Any]) -> ContractRatingItem? {
    guard hasExactKeys(payload, expected: ["id", "kind", "mediaItem", "context", "presentation", "rating", "origins"]),
          let id = requiredString(payload, "id"),
          let mediaItemObject = payload["mediaItem"] as? [String: Any],
          let mediaItem = parseMediaItem(mediaItemObject),
          let contextObject = payload["context"] as? [String: Any],
          let context = stringifyContext(contextObject),
          let ratingObject = payload["rating"] as? [String: Any],
          let rating = parseRatingState(ratingObject),
          let originsRaw = payload["origins"] as? [Any],
          let origins = stringArray(originsRaw, key: "origins") else {
        return nil
    }
    let presentation = optionalObject(payload, "presentation").flatMap(parseMediaPresentationHint)
    return ContractRatingItem(
        id: id,
        mediaItem: mediaItem,
        context: context,
        presentation: presentation,
        rating: rating,
        origins: origins
    )
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
    guard hasExactKeys(payload, expected: ["bucket", "kind", "mediaItem", "context", "presentation", "airDate", "watched"]),
          let bucket = requiredString(payload, "bucket"),
          ["up_next", "this_week", "upcoming", "recently_released", "no_scheduled"].contains(bucket),
          let kind = requiredString(payload, "kind"),
          let mediaItemObject = payload["mediaItem"] as? [String: Any],
          let mediaItem = parseMediaItem(mediaItemObject),
          let contextObject = payload["context"] as? [String: Any],
          let context = stringifyContext(contextObject),
          let watched = payload["watched"] as? Bool else {
        return nil
    }
    let presentation = optionalObject(payload, "presentation").flatMap(parseMediaPresentationHint)
    let context = CalendarContractContext(
        bucket: bucket,
        airDate: optionalString(payload, "airDate"),
        watched: watched,
        relatedShow: optionalObject(contextObject, "relatedShow").flatMap(parseMediaItem)
    )
    return CalendarContractItem(
        bucket: bucket,
        kind: kind,
        mediaItem: mediaItem,
        context: context,
        presentation: presentation,
        airDate: optionalString(payload, "airDate"),
        watched: watched
    )
}

private func parseMediaItem(_ payload: [String: Any]) -> ContractMediaItem? {
    guard let mediaKey = requiredString(payload, "mediaKey"),
          let mediaType = requiredString(payload, "mediaType"),
          let title = requiredString(payload, "title") else {
        return nil
    }

    let externalIds = ContractMediaExternalIds(
        tmdb: nullableInt(payload, "tmdb") ?? nullableInt(payload, "externalIds", "tmdb"),
        imdb: optionalString(payload, "imdb") ?? optionalString(payload, "externalIds", "imdb"),
        tvdb: nullableInt(payload, "tvdb") ?? nullableInt(payload, "externalIds", "tvdb")
    )

    let parent: ContractMediaItemParent?
    if let parentObject = payload["parent"] as? [String: Any],
       let parentMediaKey = requiredString(parentObject, "mediaKey"),
       let parentMediaType = requiredString(parentObject, "mediaType"),
       let parentTitle = requiredString(parentObject, "title") {
        parent = ContractMediaItemParent(
            mediaKey: parentMediaKey,
            mediaType: parentMediaType,
            title: parentTitle
        )
    } else {
        parent = nil
    }

    return ContractMediaItem(
        mediaKey: mediaKey,
        mediaType: mediaType,
        title: title,
        originalTitle: optionalString(payload, "originalTitle"),
        subtitle: optionalString(payload, "subtitle"),
        overview: optionalString(payload, "overview"),
        posterUrl: optionalString(payload, "posterUrl"),
        backdropUrl: optionalString(payload, "backdropUrl"),
        logoUrl: optionalString(payload, "logoUrl"),
        stillUrl: optionalString(payload, "stillUrl"),
        releaseDate: optionalString(payload, "releaseDate"),
        releaseYear: nullableInt(payload, "releaseYear"),
        rating: nullableDouble(payload, "rating"),
        genres: stringArray(payload["genres"] as? [Any] ?? [], key: "genres") ?? [],
        runtimeMinutes: nullableInt(payload, "runtimeMinutes"),
        status: optionalString(payload, "status"),
        certification: optionalString(payload, "certification"),
        externalIds: externalIds,
        parent: parent,
        showTmdbId: nullableInt(payload, "showTmdbId"),
        seasonNumber: nullableInt(payload, "seasonNumber"),
        episodeNumber: nullableInt(payload, "episodeNumber"),
        absoluteEpisodeNumber: nullableInt(payload, "absoluteEpisodeNumber"),
        episodeTitle: optionalString(payload, "episodeTitle"),
        airDate: optionalString(payload, "airDate")
    )
}

private func parseMediaPresentationHint(_ payload: [String: Any]) -> ContractMediaPresentationHint? {
    return ContractMediaPresentationHint(
        preferredSize: optionalString(payload, "preferredSize"),
        sectionId: optionalString(payload, "sectionId"),
        sectionTitle: optionalString(payload, "sectionTitle")
    )
}

private func stringifyContext(_ object: [String: Any]) -> [String: String]? {
    var output: [String: String] = [:]
    for (key, value) in object {
        if value is NSNull {
            output[key] = ""
        } else {
            output[key] = String(describing: value)
        }
    }
    return output
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

private func optionalString(_ object: [String: Any], _ key1: String, _ key2: String) -> String? {
    guard let nested = object[key1] as? [String: Any] else {
        return nil
    }
    return optionalString(nested, key2)
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

private func nullableInt(_ object: [String: Any], _ key1: String, _ key2: String) -> Int? {
    guard let nested = object[key1] as? [String: Any] else {
        return nil
    }
    return nullableInt(nested, key2)
}

private func nullableDouble(_ object: [String: Any], _ key: String) -> Double? {
    guard let value = object[key] else { return nil }
    if value is NSNull { return nil }
    return doubleValue(value)
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