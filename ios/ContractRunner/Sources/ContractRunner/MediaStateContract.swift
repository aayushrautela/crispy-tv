import Foundation

public struct MediaStateNormalized: Equatable {
    public let cardFamily: String?
    public let mediaKey: String?
    public let mediaType: String?
    public let itemId: String?
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
        mediaKey: String? = nil,
        mediaType: String? = nil,
        itemId: String? = nil,
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
        self.mediaKey = mediaKey
        self.mediaType = mediaType
        self.itemId = itemId
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
        return normalizeMediaItemWrapper(payload)
    case "continue_watching_item":
        return normalizeContinueWatching(payload)
    case "watched_item":
        return normalizeWatchItem(payload, stateKey: "watchedAt")
    case "watchlist_item":
        return normalizeWatchItem(payload, stateKey: "addedAt")
    case "rating_item":
        return normalizeWatchItem(payload, stateKey: "ratedAt")
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

private func normalizeMediaItemWrapper(_ payload: [String: Any]) -> MediaStateNormalized? {
    let media = objectValue(payload, "mediaItem") ?? (payload.keys.contains("Id") ? payload : nil)
    guard let media else {
        return nil
    }
    return normalizeMediaItem(media)
}

private func normalizeMediaItem(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let mediaKey = stringValue(payload, "Id"),
          let mediaType = stringValue(payload, "Type"),
          let title = stringValue(payload, "Name") else {
        return nil
    }
    let imageTags = objectValue(payload, "ImageTags")
    return MediaStateNormalized(
        cardFamily: "media_item",
        mediaKey: mediaKey,
        mediaType: mediaType,
        title: title,
        posterUrl: imageTagMedium(imageTags, "Primary"),
        backdropUrl: backdropMedium(imageTags),
        subtitle: nullableStringValue(payload, "EpisodeTitle") ?? nullableStringValue(payload, "Overview")
    )
}

private func normalizeContinueWatching(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let id = stringValue(payload, "id"),
          let media = objectValue(payload, "mediaItem"),
          let normalizedMedia = normalizeMediaItem(media),
          payload.keys.contains("progress"),
          let lastActivityAt = stringValue(payload, "lastActivityAt"),
          let originsValue = payload["origins"] as? [Any],
          let origins = stringArray(originsValue),
          let dismissible = payload["dismissible"] as? Bool else {
        return nil
    }
    let progress = objectValue(payload, "progress")
    let progressPercent = doubleValue(progress, "progressPercent") ?? 0
    return MediaStateNormalized(
        cardFamily: normalizedMedia.cardFamily,
        mediaKey: normalizedMedia.mediaKey,
        mediaType: normalizedMedia.mediaType,
        itemId: id,
        title: normalizedMedia.title,
        posterUrl: normalizedMedia.posterUrl,
        backdropUrl: normalizedMedia.backdropUrl,
        subtitle: normalizedMedia.subtitle,
        progressPercent: progressPercent,
        lastActivityAt: lastActivityAt,
        origins: origins,
        dismissible: dismissible
    )
}

private func normalizeWatchItem(_ payload: [String: Any], stateKey: String) -> MediaStateNormalized? {
    guard let id = stringValue(payload, "id"),
          let media = objectValue(payload, "mediaItem"),
          let normalizedMedia = normalizeMediaItem(media),
          let originsValue = payload["origins"] as? [Any],
          let origins = stringArray(originsValue) else {
        return nil
    }
    return MediaStateNormalized(
        cardFamily: normalizedMedia.cardFamily,
        mediaKey: normalizedMedia.mediaKey,
        mediaType: normalizedMedia.mediaType,
        itemId: id,
        title: normalizedMedia.title,
        posterUrl: normalizedMedia.posterUrl,
        backdropUrl: normalizedMedia.backdropUrl,
        subtitle: normalizedMedia.subtitle,
        watchedAt: stateKey == "watchedAt" ? stringValue(payload, stateKey) : nil,
        origins: origins
    )
}

private func normalizeLibraryItem(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let itemId = stringValue(payload, "id"),
          let media = objectValue(payload, "mediaItem"),
          let _ = objectValue(payload, "state"),
          let normalizedMedia = normalizeMediaItem(media),
          let originsValue = payload["origins"] as? [Any],
          let origins = stringArray(originsValue) else {
        return nil
    }
    return MediaStateNormalized(
        cardFamily: normalizedMedia.cardFamily,
        mediaKey: normalizedMedia.mediaKey,
        mediaType: normalizedMedia.mediaType,
        itemId: itemId,
        title: normalizedMedia.title,
        posterUrl: normalizedMedia.posterUrl,
        backdropUrl: normalizedMedia.backdropUrl,
        subtitle: normalizedMedia.subtitle,
        origins: origins
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
    guard let mediaKey = stringValue(payload, "mediaKey"),
          let path = stringValue(payload, "path"),
          path == "/v1/metadata/titles/\(mediaKey)" else {
        return nil
    }
    return MediaStateNormalized(mediaKey: mediaKey, routeKind: "title")
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

private func stringArray(_ values: [Any]) -> [String]? {
    var output: [String] = []
    for value in values {
        guard let string = value as? String else {
            return nil
        }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return nil
        }
        output.append(trimmed)
    }
    return output
}