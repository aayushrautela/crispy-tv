import Foundation

public struct HomeCatalogItem: Equatable {
    public let id: String
    public let title: String
    public let posterUrl: String?
    public let backdropUrl: String?
    public let addonId: String
    public let type: String
    public let rating: String?
    public let year: String?
    public let description: String?

    public init(
        id: String,
        title: String,
        posterUrl: String?,
        backdropUrl: String?,
        addonId: String,
        type: String,
        rating: String? = nil,
        year: String? = nil,
        description: String? = nil
    ) {
        self.id = id
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

public struct HomeCatalogList: Equatable {
    public let id: String
    public let kind: String?
    public let title: String
    public let subtitle: String?
    public let heading: String?
    public let items: [HomeCatalogItem]
    public let mediaTypes: Set<String>

    public init(
        id: String,
        kind: String?,
        title: String,
        subtitle: String?,
        heading: String?,
        items: [HomeCatalogItem],
        mediaTypes: Set<String> = []
    ) {
        self.id = id
        self.kind = kind
        self.title = title
        self.subtitle = subtitle
        self.heading = heading
        self.items = items
        self.mediaTypes = mediaTypes
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

public enum HomeCatalogSource: String, Equatable {
    case personal = "personal_home_feed"
    case global = "global_home_feed"

    public func catalogId(_ rawCatalogId: String) -> String {
        let normalizedId = rawCatalogId.trimmingCharacters(in: .whitespacesAndNewlines)
        switch self {
        case .personal:
            return normalizedId
        case .global:
            return "\(globalCatalogIdPrefix)\(normalizedId)"
        }
    }
}

public struct HomeCatalogHeroItem: Equatable {
    public let id: String
    public let title: String
    public let description: String
    public let rating: String?
    public let year: String?
    public let genres: [String]
    public let backdropUrl: String
    public let addonId: String
    public let type: String

    public init(
        id: String,
        title: String,
        description: String,
        rating: String?,
        year: String? = nil,
        genres: [String] = [],
        backdropUrl: String,
        addonId: String,
        type: String
    ) {
        self.id = id
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
    public let title: String
    public let catalogId: String
    public let subtitle: String

    public init(title: String, catalogId: String, subtitle: String = "") {
        self.title = title
        self.catalogId = catalogId
        self.subtitle = subtitle
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

public struct HomeCatalogPersonalFeedPlan: Equatable {
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

public func planPersonalHomeFeed(
    snapshot: HomeCatalogSnapshot,
    heroLimit: Int = 10,
    sectionLimit: Int = .max
) -> HomeCatalogPersonalFeedPlan {
    guard !snapshot.lists.isEmpty else {
        return HomeCatalogPersonalFeedPlan(
            heroResult: HomeCatalogHeroResult(statusMessage: snapshot.statusMessage),
            sections: [],
            sectionsStatusMessage: snapshot.statusMessage
        )
    }

    return HomeCatalogPersonalFeedPlan(
        heroResult: buildHeroResult(snapshot: snapshot, limit: heroLimit),
        sections: buildHomeCatalogSections(lists: snapshot.lists, source: .personal, limit: sectionLimit),
        sectionsStatusMessage: ""
    )
}

public func buildGlobalHeaderSections(snapshot: HomeCatalogSnapshot, limit: Int = .max) -> [HomeCatalogSection] {
    guard !snapshot.lists.isEmpty else {
        return []
    }
    return buildHomeCatalogSections(lists: snapshot.lists, source: .global, limit: limit)
}

public func listDiscoverCatalogs(
    snapshot: HomeCatalogSnapshot,
    mediaType: String? = nil,
    limit: Int = .max
) -> ([HomeCatalogDiscoverRef], String) {
    let normalizedType = mediaType?
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
        .nilIfEmpty()

    if let normalizedType, normalizedType != "movie", normalizedType != "series" {
        return ([], "Unsupported media type: \(mediaType ?? "")")
    }

    guard !snapshot.lists.isEmpty else {
        return ([], snapshot.statusMessage)
    }

    let filteredLists = snapshot.lists.filter { list in
        !list.isHeroList && (normalizedType == nil || list.supportsMediaType(normalizedType!))
    }

    guard !filteredLists.isEmpty else {
        let suffix = normalizedType.map { " for \($0)" } ?? ""
        return ([], "No discover catalogs found\(suffix).")
    }

    let targetCount = max(limit, 1)
    let limitedLists = targetCount >= filteredLists.count ? filteredLists : Array(filteredLists.prefix(targetCount))
    let result = limitedLists.map { list in
        HomeCatalogDiscoverRef(
            section: HomeCatalogSection(
                title: list.title,
                catalogId: list.id,
                subtitle: list.subtitle ?? ""
            ),
            addonName: "Supabase",
            genres: []
        )
    }
    return (result, "")
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

    let source = resolveHomeCatalogSource(catalogId: sectionCatalogId)
    let catalogId = normalizeHomeCatalogId(catalogId: sectionCatalogId, source: source)
    guard let list = snapshot.lists.first(where: { $0.id.caseInsensitiveCompare(catalogId) == .orderedSame }) else {
        return HomeCatalogPageResult(items: [], statusMessage: "Catalog not found.", attemptedUrls: [])
    }

    let attempted = ["supabase:\(source.rawValue):\(snapshot.profileId ?? ""):\(list.id):page=\(targetPage)"]
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

public func resolveHomeCatalogSource(catalogId: String) -> HomeCatalogSource {
    if catalogId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix(globalCatalogIdPrefix) {
        return .global
    }
    return .personal
}

public func normalizeHomeCatalogId(catalogId: String, source: HomeCatalogSource) -> String {
    let trimmed = catalogId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard source == .global, trimmed.lowercased().hasPrefix(globalCatalogIdPrefix) else {
        return trimmed
    }
    return String(trimmed.dropFirst(globalCatalogIdPrefix.count))
}

private func buildHeroResult(snapshot: HomeCatalogSnapshot, limit: Int) -> HomeCatalogHeroResult {
    let targetCount = max(limit, 1)
    let heroList = snapshot.lists.first(where: { $0.isHeroList }) ?? snapshot.lists[0]
    let fallbackDescription = heroList.subtitle ?? heroList.heading ?? heroList.title.nilIfEmpty() ?? "Recommended for you."
    let heroItems = heroList.items.compactMap { item -> HomeCatalogHeroItem? in
        let backdrop = item.backdropUrl ?? item.posterUrl
        guard let backdrop, !backdrop.isEmpty else {
            return nil
        }
        return HomeCatalogHeroItem(
            id: item.id,
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
        return HomeCatalogHeroResult(items: [], statusMessage: snapshot.statusMessage.nilIfEmpty() ?? "No featured items available.")
    }
    return HomeCatalogHeroResult(items: limitedItems, statusMessage: "")
}

private func buildHomeCatalogSections(
    lists: [HomeCatalogList],
    source: HomeCatalogSource,
    limit: Int
) -> [HomeCatalogSection] {
    let filteredLists = lists.filter { !$0.isHeroList }
    guard !filteredLists.isEmpty else {
        return []
    }
    let targetCount = max(limit, 1)
    let limitedLists = targetCount >= filteredLists.count ? filteredLists : Array(filteredLists.prefix(targetCount))
    return limitedLists.map { list in
        HomeCatalogSection(title: list.title, catalogId: source.catalogId(list.id), subtitle: list.subtitle ?? "")
    }
}

private extension HomeCatalogList {
    var isHeroList: Bool {
        let normalizedId = id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let normalizedKind = kind?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return normalizedId == heroListId || normalizedId.hasPrefix("hero.") || (normalizedKind?.contains("hero") ?? false)
    }

    func supportsMediaType(_ mediaType: String) -> Bool {
        mediaTypes.contains(where: { $0.caseInsensitiveCompare(mediaType) == .orderedSame }) ||
            items.contains(where: { $0.type.caseInsensitiveCompare(mediaType) == .orderedSame })
    }
}

private extension String {
    func nilIfEmpty() -> String? {
        isEmpty ? nil : self
    }
}

private let heroListId = "hero.shelf"
private let globalCatalogIdPrefix = "global:"
