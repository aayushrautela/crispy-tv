import Foundation

public struct MediaStateNormalized: Equatable {
    public let cardFamily: String?
    public let itemId: String?
    public let mediaType: String?
    public let title: String?
    public let posterUrl: String?
    public let backdropUrl: String?
    public let subtitle: String?
    public let progressPercent: Double?
    public let watchedAt: String?
    public let lastActivityAt: String?
    public let origins: [String]?
    public let dismissible: Bool?
    public let layout: String?
    public let routeKind: String?

    public init(
        cardFamily: String? = nil,
        itemId: String? = nil,
        mediaType: String? = nil,
        title: String? = nil,
        posterUrl: String? = nil,
        backdropUrl: String? = nil,
        subtitle: String? = nil,
        progressPercent: Double? = nil,
        watchedAt: String? = nil,
        lastActivityAt: String? = nil,
        origins: [String]? = nil,
        dismissible: Bool? = nil,
        layout: String? = nil,
        routeKind: String? = nil
    ) {
        self.cardFamily = cardFamily
        self.itemId = itemId
        self.mediaType = mediaType
        self.title = title
        self.posterUrl = posterUrl
        self.backdropUrl = backdropUrl
        self.subtitle = subtitle
        self.progressPercent = progressPercent
        self.watchedAt = watchedAt
        self.lastActivityAt = lastActivityAt
        self.origins = origins
        self.dismissible = dismissible
        self.layout = layout
        self.routeKind = routeKind
    }
}

public func normalizeMediaStateCard(payload: [String: Any], kind: String) -> MediaStateNormalized? {
    switch kind.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "media_item", "search_result", "recommendation_item":
        return normalizeBaseItemDto(payload)
    case "continue_watching_item":
        return normalizeContinueWatching(payload)
    case "watched_item":
        return normalizeWatchedItem(payload)
    case "watchlist_item":
        return normalizeWatchlistItem(payload)
    case "rating_item":
        return normalizeRatingItem(payload)
    case "library_item":
        return normalizeLibraryItem(payload)
    case "home_snapshot_section":
        return normalizeHomeSnapshotSection(payload)
    case "title_route":
        return normalizeTitleRoute(payload)
    default:
        return nil
    }
}

private func normalizeBaseItemDto(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let itemId = stringValue(payload, "Id"),
          let mediaType = stringValue(payload, "Type"),
          let title = stringValue(payload, "Name") else {
        return nil
    }
    let imageTags = objectValue(payload, "ImageTags")
    return MediaStateNormalized(
        cardFamily: "media_item",
        itemId: itemId,
        mediaType: mediaType,
        title: title,
        posterUrl: imageTagMedium(imageTags, "Primary"),
        backdropUrl: backdropMedium(imageTags),
        subtitle: nullableStringValue(payload, "EpisodeTitle") ?? nullableStringValue(payload, "Overview")
    )
}

private func userDataFrom(_ payload: [String: Any]) -> [String: Any]? {
    return objectValue(payload, "UserData")
}

private func normalizeContinueWatching(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let normalized = normalizeBaseItemDto(payload) else { return nil }
    let ud = userDataFrom(payload)
    return MediaStateNormalized(
        cardFamily: normalized.cardFamily,
        itemId: normalized.itemId,
        mediaType: normalized.mediaType,
        title: normalized.title,
        posterUrl: normalized.posterUrl,
        backdropUrl: normalized.backdropUrl,
        subtitle: normalized.subtitle,
        progressPercent: doubleValue(ud, "PlayedPercentage") ?? 0,
        lastActivityAt: stringValue(ud, "LastPlayedDate"),
        origins: [],
        dismissible: ud?["DismissedFromContinueWatching"] as? Bool ?? false
    )
}

private func normalizeWatchedItem(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let normalized = normalizeBaseItemDto(payload) else { return nil }
    let ud = userDataFrom(payload)
    return MediaStateNormalized(
        cardFamily: normalized.cardFamily,
        itemId: normalized.itemId,
        mediaType: normalized.mediaType,
        title: normalized.title,
        posterUrl: normalized.posterUrl,
        backdropUrl: normalized.backdropUrl,
        subtitle: normalized.subtitle,
        watchedAt: stringValue(ud, "LastPlayedDate"),
        origins: []
    )
}

private func normalizeWatchlistItem(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let normalized = normalizeBaseItemDto(payload) else { return nil }
    return MediaStateNormalized(
        cardFamily: normalized.cardFamily,
        itemId: normalized.itemId,
        mediaType: normalized.mediaType,
        title: normalized.title,
        posterUrl: normalized.posterUrl,
        backdropUrl: normalized.backdropUrl,
        subtitle: normalized.subtitle,
        origins: []
    )
}

private func normalizeRatingItem(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let normalized = normalizeBaseItemDto(payload) else { return nil }
    return MediaStateNormalized(
        cardFamily: normalized.cardFamily,
        itemId: normalized.itemId,
        mediaType: normalized.mediaType,
        title: normalized.title,
        posterUrl: normalized.posterUrl,
        backdropUrl: normalized.backdropUrl,
        subtitle: normalized.subtitle,
        origins: []
    )
}

private func normalizeLibraryItem(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let normalized = normalizeBaseItemDto(payload) else { return nil }
    return MediaStateNormalized(
        cardFamily: normalized.cardFamily,
        itemId: normalized.itemId,
        mediaType: normalized.mediaType,
        title: normalized.title,
        posterUrl: normalized.posterUrl,
        backdropUrl: normalized.backdropUrl,
        subtitle: normalized.subtitle,
        origins: []
    )
}

private func normalizeHomeSnapshotSection(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let layout = stringValue(payload, "layout"),
          ["regular", "landscape", "collection", "hero"].contains(layout),
          let items = payload["items"] as? [Any], !items.isEmpty else {
        return nil
    }
    return MediaStateNormalized(layout: layout)
}

private func normalizeTitleRoute(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let itemId = stringValue(payload, "Id"),
          let path = stringValue(payload, "path"),
          path == "/v1/metadata/items/\(itemId)" else {
        return nil
    }
    return MediaStateNormalized(itemId: itemId, routeKind: "title")
}

private func stringValue(_ object: [String: Any]?, _ key: String) -> String? {
    guard let value = object?[key] as? String else {
        return nil
    }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
}

private func nullableStringValue(_ object: [String: Any], _ key: String) -> String? {
    guard let value = object[key] else {
        return nil
    }
    if value is NSNull {
        return nil
    }
    guard let text = value as? String else {
        return nil
    }
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
}

private func doubleValue(_ object: [String: Any]?, _ key: String) -> Double? {
    guard let value = object?[key] else {
        return nil
    }
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

private func objectValue(_ object: [String: Any]?, _ key: String) -> [String: Any]? {
    return object?[key] as? [String: Any]
}

private func imageTagMedium(_ tags: [String: Any]?, _ key: String) -> String? {
    guard let tag = tags?[key] else { return nil }
    if let string = tag as? String { return string }
    if let dict = tag as? [String: Any] { return nullableStringValue(dict, "medium") }
    return nil
}

private func backdropMedium(_ tags: [String: Any]?) -> String? {
    guard let backdrops = tags?["Backdrop"] as? [Any], let first = backdrops.first else { return nil }
    if let string = first as? String { return string }
    if let dict = first as? [String: Any] { return nullableStringValue(dict, "medium") }
    return nil
}
