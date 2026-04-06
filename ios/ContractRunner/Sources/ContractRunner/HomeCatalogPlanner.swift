import Foundation

public struct HomeCatalogItem: Equatable {
    public let mediaKey: String
    public let title: String
    public let posterUrl: String?
    public let backdropUrl: String?
    public let addonId: String
    public let type: String
    public let rating: String?
    public let year: String?
    public let description: String?

    public init(
        mediaKey: String,
        title: String,
        posterUrl: String?,
        backdropUrl: String?,
        addonId: String,
        type: String,
        rating: String? = nil,
        year: String? = nil,
        description: String? = nil
    ) {
        self.mediaKey = mediaKey
        self.title = title
        self.posterUrl = posterUrl
        self.backdropUrl = backdropUrl
        self.addonId = addonId
        self.type = type
        self.rating = rating
        self.year = year
        self.description = description
    }
}

public enum HomeCatalogSource: String, Equatable {
    case personal = "personal"
    case publicFeed = "public"

    public static func fromRaw(_ raw: String?) -> HomeCatalogSource? {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case HomeCatalogSource.personal.rawValue, "personal_home_feed":
            return .personal
        case HomeCatalogSource.publicFeed.rawValue, "public_home_feed":
            return .publicFeed
        default:
            return nil
        }
    }
}

public enum HomeCatalogPresentation: String, Equatable {
    case hero = "hero"
    case pill = "pill"
    case rail = "rail"

    public static func fromRaw(_ raw: String?) -> HomeCatalogPresentation {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case HomeCatalogPresentation.hero.rawValue:
            return .hero
        case HomeCatalogPresentation.pill.rawValue:
            return .pill
        default:
            return .rail
        }
    }
}

public struct HomeCatalogList: Equatable {
    public let kind: String
    public let variantKey: String
    public let source: HomeCatalogSource
    public let presentation: HomeCatalogPresentation
    public let layout: String?
    public let name: String
    public let heading: String
    public let title: String
    public let subtitle: String
    public let items: [HomeCatalogItem]
    public let mediaTypes: Set<String>

    public init(
        kind: String,
        variantKey: String = "default",
        source: HomeCatalogSource,
        presentation: HomeCatalogPresentation = .rail,
        layout: String? = nil,
        name: String = "",
        heading: String = "",
        title: String = "",
        subtitle: String = "",
        items: [HomeCatalogItem],
        mediaTypes: Set<String> = []
    ) {
        self.kind = kind
        self.variantKey = variantKey
        self.source = source
        self.presentation = presentation
        self.layout = layout
        self.name = name
        self.heading = heading
        self.title = title
        self.subtitle = subtitle
        self.items = items
        self.mediaTypes = mediaTypes
    }

    public var catalogId: String {
        buildHomeCatalogId(source: source, kind: kind, variantKey: variantKey)
    }

    public var displayTitle: String {
        firstNonBlank(heading, title, name, kind) ?? kind
    }
}

public struct HomeCatalogSnapshot: Equatable {
    public let profileId: String?
    public let lists: [HomeCatalogList]
    public let statusMessage: String

    public init(profileId: String?, lists: [HomeCatalogList], statusMessage: String) {
        self.profileId = profileId
        self.lists = lists
        self.statusMessage = statusMessage
    }
}

public struct HomeCatalogHeroItem: Equatable {
    public let mediaKey: String
    public let title: String
    public let description: String
    public let rating: String?
    public let year: String?
    public let genres: [String]
    public let backdropUrl: String
    public let addonId: String
    public let type: String

    public init(
        mediaKey: String,
        title: String,
        description: String,
        rating: String?,
        year: String? = nil,
        genres: [String] = [],
        backdropUrl: String,
        addonId: String,
        type: String
    ) {
        self.mediaKey = mediaKey
        self.title = title
        self.description = description
        self.rating = rating
        self.year = year
        self.genres = genres
        self.backdropUrl = backdropUrl
        self.addonId = addonId
        self.type = type
    }
}

public struct HomeCatalogHeroResult: Equatable {
    public let items: [HomeCatalogHeroItem]
    public let statusMessage: String

    public init(items: [HomeCatalogHeroItem] = [], statusMessage: String = "") {
        self.items = items
        self.statusMessage = statusMessage
    }
}

public struct HomeCatalogSection: Equatable {
    public let catalogId: String
    public let source: HomeCatalogSource
    public let presentation: HomeCatalogPresentation
    public let layout: String?
    public let variantKey: String
    public let name: String
    public let heading: String
    public let title: String
    public let subtitle: String

    public init(
        catalogId: String,
        source: HomeCatalogSource,
        presentation: HomeCatalogPresentation,
        layout: String? = nil,
        variantKey: String = "default",
        name: String = "",
        heading: String = "",
        title: String = "",
        subtitle: String = ""
    ) {
        self.catalogId = catalogId
        self.source = source
        self.presentation = presentation
        self.layout = layout
        self.variantKey = variantKey
        self.name = name
        self.heading = heading
        self.title = title
        self.subtitle = subtitle
    }

    public var displayTitle: String {
        firstNonBlank(heading, title, name, catalogId) ?? catalogId
    }
}

public struct HomeCatalogDiscoverRef: Equatable {
    public let section: HomeCatalogSection
    public let addonName: String
    public let genres: [String]

    public init(section: HomeCatalogSection, addonName: String, genres: [String] = []) {
        self.section = section
        self.addonName = addonName
        self.genres = genres
    }
}

public struct HomeCatalogFeedPlan: Equatable {
    public let heroResult: HomeCatalogHeroResult
    public let sections: [HomeCatalogSection]
    public let sectionsStatusMessage: String

    public init(
        heroResult: HomeCatalogHeroResult = HomeCatalogHeroResult(),
        sections: [HomeCatalogSection] = [],
        sectionsStatusMessage: String = ""
    ) {
        self.heroResult = heroResult
        self.sections = sections
        self.sectionsStatusMessage = sectionsStatusMessage
    }
}

public struct HomeCatalogPageResult: Equatable {
    public let items: [HomeCatalogItem]
    public let statusMessage: String
    public let attemptedUrls: [String]

    public init(items: [HomeCatalogItem] = [], statusMessage: String = "", attemptedUrls: [String] = []) {
        self.items = items
        self.statusMessage = statusMessage
        self.attemptedUrls = attemptedUrls
    }
}

public struct HomeCatalogIdentifier: Equatable {
    public let source: HomeCatalogSource
    public let kind: String
    public let variantKey: String

    public init(source: HomeCatalogSource, kind: String, variantKey: String) {
        self.source = source
        self.kind = kind
        self.variantKey = variantKey
    }
}

public func planHomeFeed(
    snapshot: HomeCatalogSnapshot,
    heroLimit: Int = 10,
    sectionLimit: Int = .max
) -> HomeCatalogFeedPlan {
    guard !snapshot.lists.isEmpty else {
        return HomeCatalogFeedPlan(
            heroResult: HomeCatalogHeroResult(statusMessage: snapshot.statusMessage),
            sections: [],
            sectionsStatusMessage: snapshot.statusMessage
        )
    }

    return HomeCatalogFeedPlan(
        heroResult: buildHeroResult(snapshot: snapshot, limit: heroLimit),
        sections: buildHomeCatalogSections(lists: snapshot.lists, limit: sectionLimit),
        sectionsStatusMessage: ""
    )
}

public func planPersonalHomeFeed(
    snapshot: HomeCatalogSnapshot,
    heroLimit: Int = 10,
    sectionLimit: Int = .max
) -> HomeCatalogFeedPlan {
    planHomeFeed(snapshot: snapshot, heroLimit: heroLimit, sectionLimit: sectionLimit)
}

public func listDiscoverCatalogs(
    snapshot: HomeCatalogSnapshot,
    mediaType: String? = nil,
    limit: Int = .max
) -> ([HomeCatalogDiscoverRef], String) {
    let normalizedType = mediaType?
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
        .nilIfBlank()

    if let normalizedType, normalizedType != "movie", normalizedType != "series" {
        return ([], "Unsupported media type: \(mediaType ?? "")")
    }

    guard !snapshot.lists.isEmpty else {
        return ([], snapshot.statusMessage)
    }

    let filteredLists = snapshot.lists.filter { list in
        list.presentation == .rail && (normalizedType == nil || list.supportsMediaType(normalizedType!))
    }

    guard !filteredLists.isEmpty else {
        let suffix = normalizedType.map { " for \($0)" } ?? ""
        return ([], "No discover catalogs found\(suffix).")
    }

    let targetCount = max(limit, 1)
    let limitedLists = targetCount >= filteredLists.count ? filteredLists : Array(filteredLists.prefix(targetCount))
    return (
        limitedLists.map { list in
            HomeCatalogDiscoverRef(section: list.toSection(), addonName: "Supabase", genres: [])
        },
        ""
    )
}

public func buildCatalogPage(
    snapshot: HomeCatalogSnapshot,
    sectionCatalogId: String,
    page: Int,
    pageSize: Int
) -> HomeCatalogPageResult {
    let targetPage = max(page, 1)
    let targetSize = max(pageSize, 1)

    guard !snapshot.lists.isEmpty else {
        return HomeCatalogPageResult(items: [], statusMessage: snapshot.statusMessage, attemptedUrls: [])
    }

    guard let identifier = parseHomeCatalogId(sectionCatalogId) else {
        return HomeCatalogPageResult(items: [], statusMessage: "Catalog not found.", attemptedUrls: [])
    }

    guard let list = snapshot.lists.first(where: {
        $0.source == identifier.source &&
            $0.kind.caseInsensitiveCompare(identifier.kind) == .orderedSame &&
            $0.variantKey.caseInsensitiveCompare(identifier.variantKey) == .orderedSame
    }) else {
        return HomeCatalogPageResult(items: [], statusMessage: "Catalog not found.", attemptedUrls: [])
    }

    let attempted = [
        "supabase:\(identifier.source.rawValue):\(snapshot.profileId ?? ""):\(identifier.kind):\(identifier.variantKey):page=\(targetPage)"
    ]
    let startIndexLong = Int64(targetPage - 1) * Int64(targetSize)
    guard startIndexLong < Int64(list.items.count) else {
        return HomeCatalogPageResult(items: [], statusMessage: "No more items available.", attemptedUrls: attempted)
    }

    let startIndex = Int(startIndexLong)
    let endIndex = min(startIndex + targetSize, list.items.count)
    let items = startIndex < endIndex ? Array(list.items[startIndex..<endIndex]) : []
    let statusMessage: String
    if !items.isEmpty {
        statusMessage = ""
    } else if targetPage <= 1 {
        statusMessage = "No catalog items available."
    } else {
        statusMessage = "No more items available."
    }

    return HomeCatalogPageResult(items: items, statusMessage: statusMessage, attemptedUrls: attempted)
}

public func buildHomeCatalogId(
    source: HomeCatalogSource,
    kind: String,
    variantKey: String = "default"
) -> String {
    let normalizedVariantKey = variantKey.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank() ?? defaultVariantKey
    return "\(source.rawValue):\(kind.trimmingCharacters(in: .whitespacesAndNewlines)):\(normalizedVariantKey)"
}

public func parseHomeCatalogId(_ catalogId: String) -> HomeCatalogIdentifier? {
    let trimmed = catalogId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else {
        return nil
    }

    let parts = trimmed.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
    guard parts.count == 3 else {
        return nil
    }
    guard let source = HomeCatalogSource.fromRaw(parts[0]) else {
        return nil
    }
    let kind = parts[1].trimmingCharacters(in: .whitespacesAndNewlines)
    guard !kind.isEmpty else {
        return nil
    }
    let variantKey = parts[2].trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank() ?? defaultVariantKey
    return HomeCatalogIdentifier(source: source, kind: kind, variantKey: variantKey)
}

public func resolveHomeCatalogSource(catalogId: String) -> HomeCatalogSource {
    parseHomeCatalogId(catalogId)?.source ?? .personal
}

private func buildHeroResult(snapshot: HomeCatalogSnapshot, limit: Int) -> HomeCatalogHeroResult {
    let targetCount = max(limit, 1)
    let heroList = snapshot.lists.first(where: { $0.presentation == .hero }) ?? snapshot.lists[0]
    let fallbackDescription = firstNonBlank(
        heroList.subtitle,
        heroList.heading,
        heroList.title,
        heroList.name,
        "Recommended for you."
    ) ?? "Recommended for you."
    let heroItems = heroList.items.compactMap { item -> HomeCatalogHeroItem? in
        let backdrop = item.backdropUrl ?? item.posterUrl
        guard let backdrop, !backdrop.isEmpty else {
            return nil
        }
        return HomeCatalogHeroItem(
            mediaKey: item.mediaKey,
            title: item.title,
            description: item.description ?? fallbackDescription,
            rating: item.rating,
            year: item.year,
            genres: [],
            backdropUrl: backdrop,
            addonId: item.addonId,
            type: item.type
        )
    }
    let limitedItems = Array(heroItems.prefix(targetCount))

    if limitedItems.isEmpty {
        return HomeCatalogHeroResult(
            items: [],
            statusMessage: snapshot.statusMessage.nilIfBlank() ?? "No featured items available."
        )
    }
    return HomeCatalogHeroResult(items: limitedItems, statusMessage: "")
}

private func buildHomeCatalogSections(lists: [HomeCatalogList], limit: Int) -> [HomeCatalogSection] {
    let filteredLists = lists.filter { $0.presentation != .hero }
    guard !filteredLists.isEmpty else {
        return []
    }
    let targetCount = max(limit, 1)
    let limitedLists = targetCount >= filteredLists.count ? filteredLists : Array(filteredLists.prefix(targetCount))
    return limitedLists.map { $0.toSection() }
}

private extension HomeCatalogList {
    func toSection() -> HomeCatalogSection {
        HomeCatalogSection(
            catalogId: catalogId,
            source: source,
            presentation: presentation,
            layout: layout,
            variantKey: variantKey,
            name: name,
            heading: heading,
            title: title,
            subtitle: subtitle
        )
    }

    func supportsMediaType(_ mediaType: String) -> Bool {
        mediaTypes.contains(where: { $0.caseInsensitiveCompare(mediaType) == .orderedSame }) ||
            items.contains(where: { $0.type.caseInsensitiveCompare(mediaType) == .orderedSame })
    }
}

private extension String {
    func nilIfBlank() -> String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

private func firstNonBlank(_ values: String?...) -> String? {
    for value in values {
        if let normalized = value?.trimmingCharacters(in: .whitespacesAndNewlines), !normalized.isEmpty {
            return normalized
        }
    }
    return nil
}

private let defaultVariantKey = "default"
