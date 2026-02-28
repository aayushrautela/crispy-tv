import Foundation

public struct CatalogFilter: Equatable {
    public let key: String
    public let value: String

    public init(key: String, value: String) {
        self.key = key
        self.value = value
    }
}

public struct CatalogRequestInput: Equatable {
    public let baseUrl: String
    public let mediaType: String
    public let catalogId: String
    public let page: Int
    public let pageSize: Int
    public let filters: [CatalogFilter]
    public let encodedAddonQuery: String?

    public init(
        baseUrl: String,
        mediaType: String,
        catalogId: String,
        page: Int,
        pageSize: Int,
        filters: [CatalogFilter] = [],
        encodedAddonQuery: String? = nil
    ) {
        self.baseUrl = baseUrl
        self.mediaType = mediaType
        self.catalogId = catalogId
        self.page = page
        self.pageSize = pageSize
        self.filters = filters
        self.encodedAddonQuery = encodedAddonQuery
    }
}

public struct SearchMetaInput: Equatable {
    public let id: String
    public let title: String

    public init(id: String, title: String) {
        self.id = id
        self.title = title
    }
}

public struct AddonSearchResult: Equatable {
    public let addonId: String
    public let metas: [SearchMetaInput]

    public init(addonId: String, metas: [SearchMetaInput]) {
        self.addonId = addonId
        self.metas = metas
    }
}

public struct RankedSearchMeta: Equatable {
    public let id: String
    public let title: String
    public let addonId: String

    public init(id: String, title: String, addonId: String) {
        self.id = id
        self.title = title
        self.addonId = addonId
    }
}

public struct TmdbSearchResultInput: Equatable {
    public let mediaType: String
    public let id: Int
    public let title: String?
    public let name: String?
    public let releaseDate: String?
    public let firstAirDate: String?
    public let posterPath: String?
    public let profilePath: String?
    public let voteAverage: Double?

    public init(
        mediaType: String,
        id: Int,
        title: String? = nil,
        name: String? = nil,
        releaseDate: String? = nil,
        firstAirDate: String? = nil,
        posterPath: String? = nil,
        profilePath: String? = nil,
        voteAverage: Double? = nil
    ) {
        self.mediaType = mediaType
        self.id = id
        self.title = title
        self.name = name
        self.releaseDate = releaseDate
        self.firstAirDate = firstAirDate
        self.posterPath = posterPath
        self.profilePath = profilePath
        self.voteAverage = voteAverage
    }
}

public struct NormalizedSearchItem: Equatable {
    public let id: String
    public let type: String
    public let title: String
    public let year: Int?
    public let imageUrl: String?
    public let rating: Double?

    public init(id: String, type: String, title: String, year: Int?, imageUrl: String?, rating: Double?) {
        self.id = id
        self.type = type
        self.title = title
        self.year = year
        self.imageUrl = imageUrl
        self.rating = rating
    }
}

private let tmdbImageBaseUrl = "https://image.tmdb.org/t/p/"

public func buildCatalogRequestUrls(_ input: CatalogRequestInput) -> [String] {
    let baseUrl = input.baseUrl.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    precondition(!baseUrl.isEmpty, "baseUrl must not be blank")

    let mediaType = input.mediaType.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
    precondition(!mediaType.isEmpty, "mediaType must not be blank")

    let catalogId = input.catalogId.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
    precondition(!catalogId.isEmpty, "catalogId must not be blank")

    let page = max(1, input.page)
    let pageSize = max(1, input.pageSize)
    let skip = (page - 1) * pageSize

    let addonQueryParts = parseQueryParts(input.encodedAddonQuery)
    let normalizedFilters = input.filters.compactMap { filter -> (String, String)? in
        let key = filter.key.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
        let value = filter.value.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
        if key.isEmpty || value.isEmpty {
            return nil
        }
        return (encodeQueryComponent(key), encodeQueryComponent(value))
    }

    let extraPathPairs = [("skip", "\(skip)"), ("limit", "\(pageSize)")] + normalizedFilters
    let queryPairs = [("skip", "\(skip)"), ("limit", "\(pageSize)")] + addonQueryParts + normalizedFilters

    let encodedType = encodePathSegment(mediaType)
    let encodedCatalogId = encodePathSegment(catalogId)
    let simpleBase = "\(baseUrl)/catalog/\(encodedType)/\(encodedCatalogId).json"
    let simpleUrl = appendQuery(to: simpleBase, pairs: addonQueryParts)
    let pathStyleUrl = appendQuery(
        to: "\(baseUrl)/catalog/\(encodedType)/\(encodedCatalogId)/\(joinPairs(extraPathPairs)).json",
        pairs: addonQueryParts
    )
    let legacyQueryUrl = appendQuery(to: simpleBase, pairs: queryPairs)

    var urls: [String] = []
    if page == 1 && normalizedFilters.isEmpty {
        urls.append(simpleUrl)
    }
    urls.append(pathStyleUrl)
    urls.append(legacyQueryUrl)

    var seen = Set<String>()
    return urls.filter { seen.insert($0).inserted }
}

public func mergeSearchResults(
    _ addonResults: [AddonSearchResult],
    preferredAddonId: String? = nil
) -> [RankedSearchMeta] {
    let preferred = preferredAddonId
        .map { $0.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines) }
        .flatMap { $0.isEmpty ? nil : $0 }

    let rankedAddons = addonResults.enumerated().sorted { lhs, rhs in
        let lhsRank = sourceRank(lhs.element.addonId, preferredAddonId: preferred)
        let rhsRank = sourceRank(rhs.element.addonId, preferredAddonId: preferred)
        if lhsRank != rhsRank {
            return lhsRank < rhsRank
        }
        if lhs.offset != rhs.offset {
            return lhs.offset < rhs.offset
        }
        return lhs.element.addonId.localizedCaseInsensitiveCompare(rhs.element.addonId) == .orderedAscending
    }.map { $0.element }

    var seenIds = Set<String>()
    var merged: [RankedSearchMeta] = []

    for addon in rankedAddons {
        for meta in addon.metas {
            let id = meta.id.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
            if id.isEmpty || !seenIds.insert(id).inserted {
                continue
            }
            let title = meta.title.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
            merged.append(
                RankedSearchMeta(
                    id: id,
                    title: title.isEmpty ? id : title,
                    addonId: addon.addonId
                )
            )
        }
    }

    return merged
}

public func normalizeTmdbSearchResults(_ results: [TmdbSearchResultInput]) -> [NormalizedSearchItem] {
    var seenKeys = Set<String>()
    var normalized: [NormalizedSearchItem] = []
    normalized.reserveCapacity(results.count)

    for result in results {
        let rawMediaType = result.mediaType.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).lowercased()
        let type: String
        switch rawMediaType {
        case "movie":
            type = "movie"
        case "tv":
            type = "series"
        case "person":
            type = "person"
        default:
            continue
        }

        let tmdbId = result.id
        if tmdbId <= 0 {
            continue
        }

        let id = "tmdb:\(tmdbId)"
        let key = "\(type):\(id)"
        if !seenKeys.insert(key).inserted {
            continue
        }

        let primaryTitle = rawMediaType == "movie" ? result.title : result.name
        let fallbackTitle = rawMediaType == "movie" ? result.name : result.title
        let trimmedPrimary = primaryTitle?.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines) ?? ""
        let trimmedFallback = fallbackTitle?.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines) ?? ""
        let title = trimmedPrimary.isEmpty ? trimmedFallback : trimmedPrimary
        if title.isEmpty {
            continue
        }

        let year: Int?
        switch rawMediaType {
        case "movie":
            year = parseYear(result.releaseDate)
        case "tv":
            year = parseYear(result.firstAirDate)
        default:
            year = nil
        }

        let imagePath = rawMediaType == "person" ? result.profilePath : result.posterPath
        let imageSize = rawMediaType == "person" ? "h632" : "w500"
        let imageUrl = tmdbImageUrl(imagePath, size: imageSize)

        let rating = (result.voteAverage?.isFinite == true) ? result.voteAverage : nil

        normalized.append(
            NormalizedSearchItem(
                id: id,
                type: type,
                title: title,
                year: year,
                imageUrl: imageUrl,
                rating: rating
            )
        )
    }

    return normalized
}

private func sourceRank(_ addonId: String, preferredAddonId: String?) -> Int {
    if let preferredAddonId,
       !preferredAddonId.isEmpty,
       addonId.caseInsensitiveCompare(preferredAddonId) == .orderedSame {
        return 0
    }
    if addonId.range(of: "cinemeta", options: [.caseInsensitive]) != nil {
        return 1
    }
    return 2
}

private func parseYear(_ value: String?) -> Int? {
    let raw = value?.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines) ?? ""
    guard raw.count >= 4 else {
        return nil
    }
    let prefix = String(raw.prefix(4))
    guard let year = Int(prefix), (1800...3000).contains(year) else {
        return nil
    }
    return year
}

private func tmdbImageUrl(_ path: String?, size: String) -> String? {
    let raw = path?.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines) ?? ""
    guard !raw.isEmpty else {
        return nil
    }
    let normalizedPath = raw.hasPrefix("/") ? raw : "/\(raw)"
    return "\(tmdbImageBaseUrl)\(size)\(normalizedPath)"
}

private func parseQueryParts(_ raw: String?) -> [(String, String)] {
    guard let raw = raw?.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines), !raw.isEmpty else {
        return []
    }

    return raw.split(separator: "&").compactMap { pair -> (String, String)? in
        let item = String(pair)
        let key = item.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).first
            .map(String.init)?
            .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines) ?? ""
        if key.isEmpty {
            return nil
        }
        let value = item.contains("=")
            ? String(item.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)[1]).trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
            : ""
        return (encodeQueryComponent(key), encodeQueryComponent(value))
    }
}

private func appendQuery(to base: String, pairs: [(String, String)]) -> String {
    guard !pairs.isEmpty else {
        return base
    }
    return "\(base)?\(joinPairs(pairs))"
}

private func joinPairs(_ pairs: [(String, String)]) -> String {
    pairs.map { "\($0.0)=\($0.1)" }.joined(separator: "&")
}

private func encodePathSegment(_ value: String) -> String {
    encodeQueryComponent(value).replacingOccurrences(of: "%2F", with: "/")
}

private func encodeQueryComponent(_ value: String) -> String {
    let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
}
