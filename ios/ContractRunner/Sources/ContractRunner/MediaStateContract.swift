import Foundation

public struct MediaStateNormalized: Equatable {
    public let cardFamily: String?
    public let mediaKey: String?
    public let mediaType: String?
    public let itemId: String?
    public let provider: String?
    public let providerId: String?
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
        provider: String? = nil,
        providerId: String? = nil,
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
        self.provider = provider
        self.providerId = providerId
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
    case "regular_card":
        return normalizeCard(payload: payload, requireBackdrop: false)
    case "landscape_card":
        return normalizeCard(payload: payload, requireBackdrop: true)
    case "metadata_card":
        return normalizeMetadataCard(payload)
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

private func normalizeCard(payload: [String: Any], requireBackdrop: Bool) -> MediaStateNormalized? {
    guard let mediaKey = stringValue(payload, "mediaKey"),
          let mediaType = stringValue(payload, "mediaType"),
          let provider = stringValue(payload, "provider"),
          let providerId = stringValue(payload, "providerId"),
          let title = stringValue(payload, "title") else {
        return nil
    }
    let images = objectValue(payload, "images")
    guard let posterUrl = stringValue(payload, "posterUrl") ?? images.flatMap({ stringValue($0, "posterUrl") }) else {
        return nil
    }
    let backdropUrl = stringValue(payload, "backdropUrl") ?? images.flatMap({ stringValue($0, "backdropUrl") })
    if requireBackdrop && backdropUrl == nil {
        return nil
    }
    return MediaStateNormalized(
        cardFamily: requireBackdrop ? "landscape" : "regular",
        mediaKey: mediaKey,
        mediaType: mediaType,
        itemId: nil,
        provider: provider,
        providerId: providerId,
        title: title,
        posterUrl: posterUrl,
        backdropUrl: backdropUrl,
        subtitle: stringValue(payload, "subtitle")
    )
}

private func normalizeMetadataCard(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let mediaKey = stringValue(payload, "mediaKey"),
          let mediaType = stringValue(payload, "mediaType"),
          let provider = stringValue(payload, "provider"),
          let providerId = stringValue(payload, "providerId") else {
        return nil
    }
    let images = objectValue(payload, "images")
    guard let title = stringValue(payload, "title") ?? stringValue(payload, "subtitle") else {
        return nil
    }
    guard let posterUrl = stringValue(payload, "posterUrl") ?? images.flatMap({ stringValue($0, "posterUrl") }) else {
        return nil
    }
    let backdropUrl = stringValue(payload, "backdropUrl") ?? images.flatMap({ stringValue($0, "backdropUrl") })
    return MediaStateNormalized(
        cardFamily: "regular",
        mediaKey: mediaKey,
        mediaType: mediaType,
        itemId: nil,
        provider: provider,
        providerId: providerId,
        title: title,
        posterUrl: posterUrl,
        backdropUrl: backdropUrl,
        subtitle: stringValue(payload, "subtitle") ?? stringValue(payload, "summary") ?? stringValue(payload, "overview")
    )
}

private func normalizeContinueWatching(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let id = stringValue(payload, "id"),
          let media = objectValue(payload, "media"),
          let normalizedMedia = normalizeCard(payload: media, requireBackdrop: true),
          payload.keys.contains("progress"),
          let lastActivityAt = stringValue(payload, "lastActivityAt"),
          let originsValue = payload["origins"] as? [Any],
          let dismissible = payload["dismissible"] as? Bool else {
        return nil
    }
    let origins = originsValue.compactMap { value -> String? in
        guard let string = value as? String else { return nil }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
    let progress = objectValue(payload, "progress")
    let progressPercent = doubleValue(progress, "progressPercent") ?? 0
    return MediaStateNormalized(
        cardFamily: normalizedMedia.cardFamily,
        mediaKey: normalizedMedia.mediaKey,
        mediaType: normalizedMedia.mediaType,
        itemId: id,
        provider: normalizedMedia.provider,
        providerId: normalizedMedia.providerId,
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
    guard let media = objectValue(payload, "media"),
          let normalizedMedia = normalizeCard(payload: media, requireBackdrop: false),
          let originsValue = payload["origins"] as? [Any] else {
        return nil
    }
    let origins = originsValue.compactMap { value -> String? in
        guard let string = value as? String else { return nil }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
    return MediaStateNormalized(
        cardFamily: normalizedMedia.cardFamily,
        mediaKey: normalizedMedia.mediaKey,
        mediaType: normalizedMedia.mediaType,
        itemId: nil,
        provider: normalizedMedia.provider,
        providerId: normalizedMedia.providerId,
        title: normalizedMedia.title,
        posterUrl: normalizedMedia.posterUrl,
        backdropUrl: normalizedMedia.backdropUrl,
        subtitle: normalizedMedia.subtitle,
        progressPercent: nil,
        watchedAt: stateKey == "watchedAt" ? stringValue(payload, stateKey) : nil,
        lastActivityAt: nil,
        origins: origins,
        dismissible: nil
    )
}

private func normalizeLibraryItem(_ payload: [String: Any]) -> MediaStateNormalized? {
    guard let itemId = stringValue(payload, "id"),
          let media = objectValue(payload, "media"),
          let _ = objectValue(payload, "state"),
          let normalizedMedia = normalizeCard(payload: media, requireBackdrop: false),
          let originsValue = payload["origins"] as? [Any] else {
        return nil
    }
    let origins = originsValue.compactMap { value -> String? in
        guard let string = value as? String else { return nil }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
    return MediaStateNormalized(
        cardFamily: normalizedMedia.cardFamily,
        mediaKey: normalizedMedia.mediaKey,
        mediaType: normalizedMedia.mediaType,
        itemId: itemId,
        provider: normalizedMedia.provider,
        providerId: normalizedMedia.providerId,
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

private func objectValue(_ object: [String: Any], _ key: String) -> [String: Any]? {
    return object[key] as? [String: Any]
}
