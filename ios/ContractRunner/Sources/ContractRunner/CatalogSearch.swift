import Foundation

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
    public let mediaType: String
    public let itemKey: String
    public let title: String
    public let year: Int?
    public let imageUrl: String?
    public let rating: Double?

    public init(mediaType: String, itemKey: String, title: String, year: Int?, imageUrl: String?, rating: Double?) {
        self.mediaType = mediaType
        self.itemKey = itemKey
        self.title = title
        self.year = year
        self.imageUrl = imageUrl
        self.rating = rating
    }
}

private let tmdbImageBaseUrl = "https://image.tmdb.org/t/p/"

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

        let itemKey = "tmdb:\(type):\(tmdbId)"
        let key = itemKey
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
                mediaType: type,
                itemKey: itemKey,
                title: title,
                year: year,
                imageUrl: imageUrl,
                rating: rating
            )
        )
    }

    return normalized
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
