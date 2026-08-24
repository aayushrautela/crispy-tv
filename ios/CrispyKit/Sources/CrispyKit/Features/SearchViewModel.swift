import Foundation
import Observation

/// Port of the Android `SearchViewModel` (backend slice): debounced
/// suggestions, full search, recent-history persistence. AI search arrives
/// with the details milestone.
@MainActor
@Observable
public final class SearchViewModel {
    public private(set) var query = ""
    public private(set) var suggestions: [MediaCard] = []
    public private(set) var results: [MediaCard] = []
    public private(set) var history: [String] = []
    public private(set) var isSearching = false
    public private(set) var statusMessage = ""

    private var suggestionsTask: Task<Void, Never>?
    private let historyStore = SearchHistoryStore()

public func loadHistoryIfNeeded() {
        if history.isEmpty {
            history = historyStore.recentQueries()
        }
    }

public func updateQuery(_ text: String, environment: AppEnvironment) {
        query = text
        results = []
        statusMessage = ""
        suggestionsTask?.cancel()
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            suggestions = []
            return
        }
        suggestionsTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self else { return }
            await self.fetchSuggestions(trimmed, environment: environment)
        }
    }

public func submit(environment: AppEnvironment) async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        suggestionsTask?.cancel()
        suggestions = []
        isSearching = true
        defer { isSearching = false }
        do {
            guard let context = await environment.backendContext() else {
                statusMessage = "Sign in to search."
                return
            }
            let response = try await environment.backend.searchTitles(
                accessToken: context.accessToken,
                query: trimmed,
                limit: 30
            )
            let movies = response.movies.map { MediaCard.from($0) }
            let series = response.series.map { MediaCard.from($0) }
            results = mergeInterleaved(movies: movies, series: series)
            historyStore.recordQuery(trimmed)
            history = historyStore.recentQueries()
            statusMessage = results.isEmpty && response.people.isEmpty ? "No results for “\(trimmed)”." : ""
        } catch {
            statusMessage = error.localizedDescription
        }
    }

public func submitSuggestion(_ suggestion: MediaCard, environment: AppEnvironment) async {
        query = suggestion.title
        await submit(environment: environment)
    }

public func clearHistory() {
        historyStore.clear()
        history = []
    }

    private func fetchSuggestions(_ text: String, environment: AppEnvironment) async {
        guard let context = await environment.backendContext() else { return }
        let response = try? await environment.backend.searchSuggestions(
            accessToken: context.accessToken,
            query: text,
            limit: 8
        )
        if !Task.isCancelled {
            suggestions = (response?.suggestions ?? []).map { MediaCard.from($0) }
        }
    }

    /// Interleave movies and series so no type dominates the top of the grid.
    private func mergeInterleaved(movies: [MediaCard], series: [MediaCard]) -> [MediaCard] {
        var merged: [MediaCard] = []
        merged.reserveCapacity(movies.count + series.count)
        for index in 0..<max(movies.count, series.count) {
            if index < movies.count { merged.append(movies[index]) }
            if index < series.count { merged.append(series[index]) }
        }
        return merged
    }
}

/// Recent search queries persisted to UserDefaults (Android: SharedPreferences
/// SearchHistoryStore).
public struct SearchHistoryStore {
    private let defaults: UserDefaults
    private let key = "crispy.search.history"
    private let limit = 8

public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

public func recentQueries() -> [String] {
        defaults.stringArray(forKey: key) ?? []
    }

public func recordQuery(_ raw: String) {
        let query = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        var current = recentQueries().filter { $0.caseInsensitiveCompare(query) != .orderedSame }
        current.insert(query, at: 0)
        if current.count > limit {
            current = Array(current.prefix(limit))
        }
        defaults.set(current, forKey: key)
    }

public func clear() {
        defaults.removeObject(forKey: key)
    }
}
