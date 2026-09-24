import Foundation

public struct BrowseCombo: Equatable {
    public let type: String
    public let genre: String?
    public let sort: String

    public init(type: String, genre: String?, sort: String) {
        self.type = type
        self.genre = genre
        self.sort = sort
    }
}

public struct BrowseRequest: Equatable {
    public let type: String
    public let genre: String?
    public let sort: String
    public let page: Int

    public init(type: String, genre: String?, sort: String, page: Int) {
        self.type = type
        self.genre = genre
        self.sort = sort
        self.page = page
    }
}

public struct BrowseTypeItem: Equatable {
    public let itemId: String
    public let type: String

    public init(itemId: String, type: String) {
        self.itemId = itemId
        self.type = type
    }
}

public struct BrowseTypeResponse: Equatable {
    public let items: [BrowseTypeItem]
    public let hasMore: Bool

    public init(items: [BrowseTypeItem] = [], hasMore: Bool = false) {
        self.items = items
        self.hasMore = hasMore
    }
}

public struct BrowsePageResult: Equatable {
    public let itemIds: [String]
    public let hasMore: Bool
    public let nextPage: Int?

    public init(itemIds: [String] = [], hasMore: Bool = false, nextPage: Int? = nil) {
        self.itemIds = itemIds
        self.hasMore = hasMore
        self.nextPage = nextPage
    }
}

public let browseMaxPages = 20

public func planBrowseRequests(combo: BrowseCombo, page: Int) -> [BrowseRequest] {
    guard page >= 0, page < browseMaxPages else {
        return []
    }
    let genre = normalizedBrowseGenre(combo.genre)
    return browseTypeFanOut(combo.type).map { type in
        BrowseRequest(type: type, genre: genre, sort: combo.sort, page: page)
    }
}

public func mergeBrowsePage(
    combo: BrowseCombo,
    page: Int,
    responses: [String: BrowseTypeResponse]
) -> BrowsePageResult {
    guard page >= 0, page < browseMaxPages else {
        return BrowsePageResult()
    }

    let types = browseTypeFanOut(combo.type)
    let itemIds = types.flatMap { type in
        responses[type]?.items.map(\.itemId) ?? []
    }
    let hasMore = types.contains(where: { responses[$0]?.hasMore ?? false }) && page < browseMaxPages - 1
    return BrowsePageResult(
        itemIds: itemIds,
        hasMore: hasMore,
        nextPage: hasMore ? page + 1 : nil
    )
}

private func browseTypeFanOut(_ type: String) -> [String] {
    type == "all" ? ["movie", "series"] : [type]
}

private func normalizedBrowseGenre(_ genre: String?) -> String? {
    guard let trimmed = genre?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
        return nil
    }
    return trimmed
}