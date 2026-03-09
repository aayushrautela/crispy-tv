import Foundation

public struct OmdbRatingInput: Equatable {
    public let source: String?
    public let value: String?

    public init(source: String?, value: String?) {
        self.source = source
        self.value = value
    }
}

public struct OmdbRating: Equatable {
    public let source: String
    public let value: String

    public init(source: String, value: String) {
        self.source = source
        self.value = value
    }
}

public struct OmdbDetails: Equatable {
    public let ratings: [OmdbRating]
    public let metascore: String?
    public let imdbRating: String?
    public let imdbVotes: String?
    public let type: String?

    public init(
        ratings: [OmdbRating] = [],
        metascore: String? = nil,
        imdbRating: String? = nil,
        imdbVotes: String? = nil,
        type: String? = nil
    ) {
        self.ratings = ratings
        self.metascore = metascore
        self.imdbRating = imdbRating
        self.imdbVotes = imdbVotes
        self.type = type
    }
}

public func normalizeOmdbImdbId(_ value: String?) -> String? {
    let normalized = value?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
    guard normalized.range(of: "^tt\\d+$", options: [.regularExpression, .caseInsensitive]) != nil else {
        return nil
    }
    return normalized
}

public func normalizeOmdbDetails(
    ratings: [OmdbRatingInput],
    metascore: String?,
    imdbRating: String?,
    imdbVotes: String?,
    type: String?
) -> OmdbDetails {
    let normalizedMetascore = normalizedOmdbField(metascore)
    let normalizedImdbRating = normalizedOmdbField(imdbRating)
    let normalizedImdbVotes = normalizedOmdbField(imdbVotes)
    let normalizedType = normalizedOmdbField(type)

    var dedupedRatings: [OmdbRating] = []
    var seenSources = Set<String>()

    for rating in ratings {
        guard let source = normalizedOmdbField(rating.source),
              let value = normalizedOmdbField(rating.value) else {
            continue
        }
        let key = source.lowercased()
        guard seenSources.insert(key).inserted else {
            continue
        }
        dedupedRatings.append(OmdbRating(source: source, value: value))
    }

    let imdbKey = internetMovieDatabaseSource.lowercased()
    if let normalizedImdbRating, seenSources.insert(imdbKey).inserted {
        dedupedRatings.append(
            OmdbRating(source: internetMovieDatabaseSource, value: "\(normalizedImdbRating)/10")
        )
    }

    let metacriticKey = metacriticSource.lowercased()
    if let normalizedMetascore, seenSources.insert(metacriticKey).inserted {
        dedupedRatings.append(
            OmdbRating(source: metacriticSource, value: "\(normalizedMetascore)/100")
        )
    }

    return OmdbDetails(
        ratings: dedupedRatings,
        metascore: normalizedMetascore,
        imdbRating: normalizedImdbRating,
        imdbVotes: normalizedImdbVotes,
        type: normalizedType
    )
}

private func normalizedOmdbField(_ value: String?) -> String? {
    guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
          !trimmed.isEmpty,
          trimmed.caseInsensitiveCompare("N/A") != .orderedSame else {
        return nil
    }
    return trimmed
}

private let internetMovieDatabaseSource = "Internet Movie Database"
private let metacriticSource = "Metacritic"
