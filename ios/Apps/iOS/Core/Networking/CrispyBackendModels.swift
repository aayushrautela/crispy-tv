import Foundation

// MARK: - Account

struct BackendUser: Equatable {
    let id: String
    let email: String?
}

struct BackendProfile: Equatable, Identifiable {
    let id: String
    let name: String
    let avatarUrl: String?
    let isKids: Bool
    let sortOrder: Int

    var initials: String? {
        let parts = name.split(whereSeparator: { $0 == " " }).prefix(2)
        let joined = parts.compactMap { $0.first.map(String.init) }.joined()
        return joined.isEmpty ? nil : joined.uppercased()
    }
}

struct MeResponse: Equatable {
    let user: BackendUser
    let profiles: [BackendProfile]
}

// MARK: - Shared card shapes

struct ResponsiveImageSetDto: Equatable {
    let small: String?
    let medium: String?
    let large: String?

    var isEmpty: Bool { small == nil && medium == nil && large == nil }

    static func parse(_ json: [String: Any]?) -> ResponsiveImageSetDto {
        ResponsiveImageSetDto(
            small: json?.jsonString("small"),
            medium: json?.jsonString("medium"),
            large: json?.jsonString("large")
        )
    }

    static func empty() -> ResponsiveImageSetDto {
        ResponsiveImageSetDto(small: nil, medium: nil, large: nil)
    }
}

struct MediaExternalIds: Equatable {
    let tmdb: Int?
    let imdb: String?
    let tvdb: Int?

    static func parse(_ json: [String: Any]?) -> MediaExternalIds {
        MediaExternalIds(
            tmdb: json?.jsonInt("tmdb"),
            imdb: json?.jsonString("imdb"),
            tvdb: json?.jsonInt("tvdb")
        )
    }

    static func empty() -> MediaExternalIds {
        MediaExternalIds(tmdb: nil, imdb: nil, tvdb: nil)
    }
}

struct ClientProgress: Equatable {
    let played: Bool
    let playCount: Int
    let positionSeconds: Int?
    let durationSeconds: Int?
    let percent: Double?
    let lastPlayedAt: String?
    let watchlisted: Bool
    let userRating: Double?

    static func parse(_ json: [String: Any]?) -> ClientProgress? {
        guard let json, !json.isEmpty else { return nil }
        return ClientProgress(
            played: json.jsonBool("played", defaultValue: false),
            playCount: json.jsonInt("playCount") ?? 0,
            positionSeconds: json.jsonInt("positionSeconds"),
            durationSeconds: json.jsonInt("durationSeconds"),
            percent: json.jsonDouble("percent"),
            lastPlayedAt: json.jsonString("lastPlayedAt"),
            watchlisted: json.jsonBool("watchlisted", defaultValue: false),
            userRating: json.jsonDouble("userRating")
        )
    }
}

struct ClientParentRef: Equatable {
    let seriesItemId: String?
    let seriesTitle: String?
    let seasonItemId: String?
    let seasonNumber: Int?
    let episodeNumber: Int?

    static func parse(_ json: [String: Any]?) -> ClientParentRef? {
        guard let json, !json.isEmpty else { return nil }
        return ClientParentRef(
            seriesItemId: json.jsonString("seriesItemId"),
            seriesTitle: json.jsonString("seriesTitle"),
            seasonItemId: json.jsonString("seasonItemId"),
            seasonNumber: json.jsonInt("seasonNumber"),
            episodeNumber: json.jsonInt("episodeNumber")
        )
    }
}

struct ClientImages: Equatable {
    let poster: ResponsiveImageSetDto
    let backdrop: ResponsiveImageSetDto
    let logo: ResponsiveImageSetDto
    let still: ResponsiveImageSetDto

    static func parse(_ json: [String: Any]?) -> ClientImages {
        ClientImages(
            poster: ResponsiveImageSetDto.parse(json?.jsonObject("poster")),
            backdrop: ResponsiveImageSetDto.parse(json?.jsonObject("backdrop")),
            logo: ResponsiveImageSetDto.parse(json?.jsonObject("logo")),
            still: ResponsiveImageSetDto.parse(json?.jsonObject("still"))
        )
    }
}

struct ClientMediaCard: Equatable, Identifiable {
    let itemId: String
    let mediaType: String
    let title: String
    let overview: String?
    let year: Int?
    let releaseDate: String?
    let rating: Double?
    let maturityRating: String?
    let genres: [String]
    let runtimeSeconds: Int?
    let images: ClientImages
    let progress: ClientProgress?
    let parent: ClientParentRef?
    let providerIds: MediaExternalIds

    var id: String { itemId }

    var posterUrl: String? { images.poster.medium ?? images.poster.large ?? images.poster.small }
    var backdropUrl: String? { images.backdrop.medium ?? images.backdrop.large ?? images.backdrop.small }
    var logoUrl: String? { images.logo.medium ?? images.logo.large ?? images.logo.small }

    static func parse(_ json: [String: Any]) throws -> ClientMediaCard {
        guard let itemId = json.jsonString("itemId"),
              let mediaType = json.jsonString("mediaType"),
              let title = json.jsonString("title") else {
            throw CrispyParseError.notAnObject
        }
        return ClientMediaCard(
            itemId: itemId,
            mediaType: mediaType,
            title: title,
            overview: json.jsonString("overview"),
            year: json.jsonInt("year"),
            releaseDate: json.jsonString("releaseDate"),
            rating: json.jsonDouble("rating"),
            maturityRating: json.jsonString("maturityRating"),
            genres: json.jsonStringList("genres"),
            runtimeSeconds: json.jsonInt("runtimeSeconds"),
            images: ClientImages.parse(json.jsonObject("images")),
            progress: ClientProgress.parse(json.jsonObject("progress")),
            parent: ClientParentRef.parse(json.jsonObject("parent")),
            providerIds: MediaExternalIds.parse(json.jsonObject("providerIds"))
        )
    }
}

struct ClientMediaCardQueryResult: Equatable {
    let items: [ClientMediaCard]
    let startIndex: Int
    let totalRecordCount: Int
    let nextCursor: String?
    let hasMore: Bool
}

// MARK: - Home

struct ProfileHomeSection: Equatable {
    let listKey: String
    let title: String
    let subtitle: String?
    let layout: String
    let items: [ClientMediaCard]
    let meta: [String: String]
}

struct ProfileHomeResponse: Equatable {
    let profileId: String
    let generatedAt: String?
    let expiresAt: String?
    let sections: [ProfileHomeSection]
}

// MARK: - Search

struct PersonSearchResultItem: Equatable, Identifiable {
    let personId: String
    let name: String
    let knownForDepartment: String?
    let profileUrl: String?

    var id: String { personId }
}

/// Jellyfin BaseItemDto-shaped result used by /v1/search/titles.
struct SearchMediaItem: Equatable, Identifiable {
    let itemId: String
    let itemType: String
    let title: String
    let posterUrl: String?
    let backdropUrl: String?
    let logoUrl: String?
    let year: Int?
    let rating: Double?
    let genres: [String]
    let maturityRating: String?
    let overview: String?
    let providerIds: MediaExternalIds

    var id: String { itemId }
}

struct SearchResultsResponse: Equatable {
    let query: String
    let movies: [SearchMediaItem]
    let series: [SearchMediaItem]
    let people: [PersonSearchResultItem]

    var allTitles: [SearchMediaItem] {
        let merged = movies + series
        return merged.sorted { ($0.rating ?? 0) > ($1.rating ?? 0) }
    }
}

struct SearchSuggestionItem: Equatable, Identifiable {
    let itemId: String
    let itemType: String
    let title: String
    let year: Int?
    let posterUrl: String?

    var id: String { itemId }
}

struct SearchSuggestionsResponse: Equatable {
    let suggestions: [SearchSuggestionItem]
}

// MARK: - Watch actions

struct WatchActionResponse: Equatable {
    let accepted: Bool
    let mode: String
    let reason: String?
}
