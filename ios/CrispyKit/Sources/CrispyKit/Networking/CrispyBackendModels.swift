import Foundation

// MARK: - Account

public struct BackendUser: Equatable {
public let id: String
public let email: String?
}

public struct BackendProfile: Equatable, Identifiable {
public let id: String
public let name: String
public let avatarUrl: String?
public let isKids: Bool
public let sortOrder: Int

public var initials: String? {
        let parts = name.split(whereSeparator: { $0 == " " }).prefix(2)
        let joined = parts.compactMap { $0.first.map(String.init) }.joined()
        return joined.isEmpty ? nil : joined.uppercased()
    }
}

public struct MeResponse: Equatable {
public let user: BackendUser
public let profiles: [BackendProfile]
}

// MARK: - Shared card shapes

public struct ResponsiveImageSetDto: Equatable {
public let small: String?
public let medium: String?
public let large: String?

public var isEmpty: Bool { small == nil && medium == nil && large == nil }

public static func parse(_ json: [String: Any]?) -> ResponsiveImageSetDto {
        ResponsiveImageSetDto(
            small: json?.jsonString("small"),
            medium: json?.jsonString("medium"),
            large: json?.jsonString("large")
        )
    }

public static func empty() -> ResponsiveImageSetDto {
        ResponsiveImageSetDto(small: nil, medium: nil, large: nil)
    }
}

public struct MediaExternalIds: Equatable {
public let tmdb: Int?
public let imdb: String?
public let tvdb: Int?

public static func parse(_ json: [String: Any]?) -> MediaExternalIds {
        MediaExternalIds(
            tmdb: json?.jsonInt("tmdb"),
            imdb: json?.jsonString("imdb"),
            tvdb: json?.jsonInt("tvdb")
        )
    }

public static func empty() -> MediaExternalIds {
        MediaExternalIds(tmdb: nil, imdb: nil, tvdb: nil)
    }
}

public struct ClientProgress: Equatable {
public let played: Bool
public let playCount: Int
public let positionSeconds: Int?
public let durationSeconds: Int?
public let percent: Double?
public let lastPlayedAt: String?
public let watchlisted: Bool
public let userRating: Double?

public static func parse(_ json: [String: Any]?) -> ClientProgress? {
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

public struct ClientParentRef: Equatable {
public let seriesItemId: String?
public let seriesTitle: String?
public let seasonItemId: String?
public let seasonNumber: Int?
public let episodeNumber: Int?

public static func parse(_ json: [String: Any]?) -> ClientParentRef? {
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

public struct ClientImages: Equatable {
public let poster: ResponsiveImageSetDto
public let backdrop: ResponsiveImageSetDto
public let logo: ResponsiveImageSetDto
public let still: ResponsiveImageSetDto

public static func parse(_ json: [String: Any]?) -> ClientImages {
        ClientImages(
            poster: ResponsiveImageSetDto.parse(json?.jsonObject("poster")),
            backdrop: ResponsiveImageSetDto.parse(json?.jsonObject("backdrop")),
            logo: ResponsiveImageSetDto.parse(json?.jsonObject("logo")),
            still: ResponsiveImageSetDto.parse(json?.jsonObject("still"))
        )
    }
}

public struct ClientMediaCard: Equatable, Identifiable {
public let itemId: String
public let mediaType: String
public let title: String
public let overview: String?
public let year: Int?
public let releaseDate: String?
public let rating: Double?
public let maturityRating: String?
public let genres: [String]
public let runtimeSeconds: Int?
public let images: ClientImages
public let progress: ClientProgress?
public let parent: ClientParentRef?
public let providerIds: MediaExternalIds

public var id: String { itemId }

public var posterUrl: String? { images.poster.medium ?? images.poster.large ?? images.poster.small }
public var backdropUrl: String? { images.backdrop.medium ?? images.backdrop.large ?? images.backdrop.small }
public var logoUrl: String? { images.logo.medium ?? images.logo.large ?? images.logo.small }

public static func parse(_ json: [String: Any]) throws -> ClientMediaCard {
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

public struct ClientMediaCardQueryResult: Equatable {
public let items: [ClientMediaCard]
public let startIndex: Int
public let totalRecordCount: Int
public let nextCursor: String?
public let hasMore: Bool
}

// MARK: - Home

public struct ProfileHomeSection: Equatable {
public let listKey: String
public let title: String
public let subtitle: String?
public let layout: String
public let items: [ClientMediaCard]
public let meta: [String: String]
}

public struct ProfileHomeResponse: Equatable {
public let profileId: String
public let generatedAt: String?
public let expiresAt: String?
public let sections: [ProfileHomeSection]
}

// MARK: - Search

public struct PersonSearchResultItem: Equatable, Identifiable {
public let personId: String
public let name: String
public let knownForDepartment: String?
public let profileUrl: String?

public var id: String { personId }
}

/// Jellyfin BaseItemDto-shaped result used by /v1/search/titles.
public struct SearchMediaItem: Equatable, Identifiable {
public let itemId: String
public let itemType: String
public let title: String
public let posterUrl: String?
public let backdropUrl: String?
public let logoUrl: String?
public let year: Int?
public let rating: Double?
public let genres: [String]
public let maturityRating: String?
public let overview: String?
public let providerIds: MediaExternalIds
public let seriesItemId: String?

public var id: String { itemId }
}

public struct SearchResultsResponse: Equatable {
public let query: String
public let movies: [SearchMediaItem]
public let series: [SearchMediaItem]
public let people: [PersonSearchResultItem]

public var allTitles: [SearchMediaItem] {
        let merged = movies + series
        return merged.sorted { ($0.rating ?? 0) > ($1.rating ?? 0) }
    }
}

public struct SearchSuggestionItem: Equatable, Identifiable {
public let itemId: String
public let itemType: String
public let title: String
public let year: Int?
public let posterUrl: String?

public var id: String { itemId }
}

public struct SearchSuggestionsResponse: Equatable {
public let suggestions: [SearchSuggestionItem]
}

// MARK: - Watch actions

public struct WatchActionResponse: Equatable {
public let accepted: Bool
public let mode: String
public let reason: String?
}

// MARK: - Metadata / details (Jellyfin BaseItemDto shapes, mirrors parseMetadata*)

public struct MetadataImagesDto: Equatable {
public let poster: ResponsiveImageSetDto
public let backdrop: ResponsiveImageSetDto
public let still: ResponsiveImageSetDto
public let logo: ResponsiveImageSetDto

public static func parse(_ json: [String: Any]?) -> MetadataImagesDto {
        MetadataImagesDto(
            poster: ResponsiveImageSetDto.parse(json?.jsonObject("Primary")),
            backdrop: (json?.jsonObject("Backdrop") as? [[String: Any]]).flatMap { $0.first }.map { ResponsiveImageSetDto.parse($0) } ?? .empty(),
            still: ResponsiveImageSetDto.parse(json?.jsonObject("Thumb")),
            logo: ResponsiveImageSetDto.parse(json?.jsonObject("Logo"))
        )
    }

public var posterUrl: String? { poster.medium ?? poster.large ?? poster.small }
public var backdropUrl: String? { backdrop.medium ?? backdrop.large ?? backdrop.small }
public var stillUrl: String? { still.medium ?? still.large ?? still.small }
public var logoUrl: String? { logo.medium ?? logo.large ?? logo.small }
}

/// Detail view for a movie/show; also used for episode rows.
public struct MetadataItem: Equatable, Identifiable {
public let itemId: String
public let itemType: String
public let title: String
public let subtitle: String?
public let overview: String?
public let images: MetadataImagesDto
public let releaseDate: String?
public let releaseYear: Int?
public let runtimeMinutes: Int?
public let rating: Double?
public let certification: String?
public let status: String?
public let genres: [String]
public let seasonNumber: Int?
public let episodeNumber: Int?

public var id: String { itemId }
}

public struct MetadataEpisode: Equatable, Identifiable {
public let itemId: String
public let seasonNumber: Int?
public let episodeNumber: Int?
public let title: String
public let summary: String?
public let airDate: String?
public let runtimeMinutes: Int?
public let rating: Double?
public let stillUrl: String?
public let showItemId: String?

public var id: String { itemId }
}

public struct MetadataSeason: Equatable, Identifiable {
public let itemId: String
public let seasonNumber: Int
public let title: String?
public let summary: String?
public let posterUrl: String?

public var id: String { itemId }

public var displayTitle: String {
        title.nilIfBlank ?? "Season \(seasonNumber)"
    }
}

public struct MetadataPersonRef: Equatable, Identifiable {
public let personId: String
public let name: String
public let role: String?
public let department: String?
public let profileUrl: String?

public var id: String { personId }
}

public struct MetadataCard: Equatable, Identifiable {
public let itemId: String
public let itemType: String
public let title: String
public let images: MetadataImagesDto
public let releaseYear: Int?
public let rating: Double?

public var id: String { itemId }
}

public struct MetadataTitleDetail: Equatable {
public let item: MetadataItem
public let cast: [MetadataPersonRef]
public let directors: [MetadataPersonRef]
public let creators: [MetadataPersonRef]
}

public struct MetadataTitleExtras: Equatable {
public let seasons: [MetadataSeason]
public let similar: [MetadataCard]
}

public struct PersonKnownForItem: Equatable, Identifiable {
public let itemId: String
public let mediaType: String
public let title: String
public let posterUrl: String?
public let backdropUrl: String?
public let logoUrl: String?
public let rating: Double?
public let releaseYear: Int?

public var id: String { itemId }
}

public struct PersonDetail: Equatable {
public let personId: String
public let name: String
public let knownForDepartment: String?
public let biography: String?
public let birthday: String?
public let placeOfBirth: String?
public let profileUrl: String?
public let knownFor: [PersonKnownForItem]
}

// MARK: - Accounts / settings

public struct ProviderState: Equatable, Identifiable {
    public let provider: String
    let connectionState: String
    let primaryAction: String
    let canDisconnect: Bool
    let externalUsername: String?
    let statusLabel: String
    let statusMessage: String?

    public var id: String { provider }
}

public struct AvatarItem: Equatable, Identifiable {
    public let id: String
    public let url: String?
}

public struct UpNextEntry: Equatable, Identifiable {
    public let showItemId: String
    public let showTitle: String
    public let backdropUrl: String?
    public let posterUrl: String?
    public let logoUrl: String?
    public let badge: String?
    public let airDate: String?

    public var id: String { showItemId }
}

public struct MetadataReview: Equatable, Identifiable {
    public let id: String
    public let provider: String
    public let author: String?
    public let username: String?
    public let content: String
    public let rating: Double?
    public let url: String?
}

public struct MetadataVideo: Equatable, Identifiable {
    public let id: String
    public let name: String?
    public let site: String?
    public let key: String
    public let thumbnailUrl: String?
    public let url: String?

    public var playableURL: String? {
        if let url = url?.nilIfBlank { return url }
        if (site ?? "").lowercased() == "youtube", !key.isEmpty {
            return "https://www.youtube.com/watch?v=\(key)"
        }
        return nil
    }
}

public struct TitleRatings: Equatable {
    public let imdb: Double?
    public let tmdb: Double?
    public let trakt: Double?
    public let metacritic: Double?
    public let rottenTomatoes: Double?
    public let audience: Double?

    public var visible: [(String, String)] {
        [
            ("IMDb", imdb).map { ($0.0, formatRating($0.1)) },
            ("TMDB", tmdb).map { ($0.0, formatRating($0.1)) },
            ("Trakt", trakt.map { $0 * 10 }).map { ($0.0, formatRating($0.1)) },
            ("MC", metacritic).map { ($0.0, $0.1.map { String(Int($0)) }) },
            ("RT", rottenTomatoes).map { ($0.0, $0.1.map { String(Int($0)) + "%" }) },
            ("Aud.", audience).map { ($0.0, $0.1.map { String(Int($0)) + "%" }) },
        ]
        .compactMap { pair in
            guard let value = pair.1 else { return nil }
            return (pair.0, value)
        }
    }
}

public struct MetadataCompany: Equatable, Identifiable {
    public let id: String
    public let name: String
    public let logoUrl: String?
}

public struct WatchState: Equatable {
    public let itemId: String
    public let played: Bool
    public let playCount: Int
    public let resumePositionSeconds: Double?
    public let durationSeconds: Double?
    public let progressPercent: Double?
}
