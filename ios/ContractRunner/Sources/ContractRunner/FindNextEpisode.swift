import Foundation

public struct EpisodeInfo: Equatable {
    public let season: Int
    public let episode: Int
    public let title: String?
    public let released: String?

    public init(season: Int, episode: Int, title: String? = nil, released: String? = nil) {
        self.season = season
        self.episode = episode
        self.title = title
        self.released = released
    }
}

public struct NextEpisodeResult: Equatable {
    public let season: Int
    public let episode: Int
    public let title: String?

    public init(season: Int, episode: Int, title: String? = nil) {
        self.season = season
        self.episode = episode
        self.title = title
    }
}

public func findNextEpisode(
    currentSeason: Int,
    currentEpisode: Int,
    episodes: [EpisodeInfo],
    watchedSet: Set<String>? = nil,
    showId: String? = nil,
    nowMs: Int64? = nil
) -> NextEpisodeResult? {
    guard !episodes.isEmpty else {
        return nil
    }

    let sorted = episodes.sorted { lhs, rhs in
        if lhs.season != rhs.season {
            return lhs.season < rhs.season
        }
        return lhs.episode < rhs.episode
    }

    for episode in sorted {
        if episode.season < currentSeason {
            continue
        }
        if episode.season == currentSeason && episode.episode <= currentEpisode {
            continue
        }

        if let watchedSet, let showId {
            let cleanShowId = showId.hasPrefix("tt") ? showId : "tt\(showId)"
            let key1 = "\(cleanShowId):\(episode.season):\(episode.episode)"
            let key2 = "\(showId):\(episode.season):\(episode.episode)"
            if watchedSet.contains(key1) || watchedSet.contains(key2) {
                continue
            }
        }

        guard isEpisodeReleased(episode.released, nowMs: nowMs) else {
            continue
        }

        return NextEpisodeResult(season: episode.season, episode: episode.episode, title: episode.title)
    }

    return nil
}

private let releaseDateOnlyFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.calendar = utcCalendar
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter
}()

private let internetDateFormatter: ISO8601DateFormatter = {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime]
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    return formatter
}()

private let fractionalInternetDateFormatter: ISO8601DateFormatter = {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    return formatter
}()

private var utcCalendar: Calendar {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .gmt
    return calendar
}

private func isEpisodeReleased(_ released: String?, nowMs: Int64?) -> Bool {
    guard let trimmed = released?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
        return false
    }

    let nowDate = nowMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000.0) } ?? Date()
    if let releaseDate = parseReleaseDate(trimmed) {
        return releaseDate <= nowDate
    }

    let prefix = String(trimmed.prefix(10))
    guard let dateOnly = releaseDateOnlyFormatter.date(from: prefix) else {
        return false
    }

    let components = utcCalendar.dateComponents([.year, .month, .day], from: nowDate)
    guard let nowDay = utcCalendar.date(from: components) else {
        return false
    }
    return dateOnly <= nowDay
}

private func parseReleaseDate(_ value: String) -> Date? {
    if let parsed = internetDateFormatter.date(from: value) {
        return parsed
    }
    return fractionalInternetDateFormatter.date(from: value)
}
