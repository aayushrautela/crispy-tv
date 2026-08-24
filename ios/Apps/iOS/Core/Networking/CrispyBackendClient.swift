import Foundation

struct CrispyBackendError: Error {
    let httpCode: Int
    let code: String?
    let message: String?
    let category: String?
    let retryable: Bool
    let requestId: String?
    let details: String?
}

/// URLSession port of the Android `CrispyBackendClient`, restricted to the
/// endpoints the first-pass pages consume. Response envelopes and field names
/// mirror `CrispyBackendParsers.kt` exactly.
final class CrispyBackendClient {
    private let httpClient: CrispyHttpClient
    private let baseURL: String

    init(httpClient: CrispyHttpClient, backendURL: String) {
        self.httpClient = httpClient
        self.baseURL = backendURL.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    func isConfigured() -> Bool {
        !baseURL.isEmpty
    }

    // MARK: - Account

    func getMe(accessToken: String) async throws -> MeResponse {
        let json = try await getJson(path: "/v1/me", accessToken: accessToken)
        guard let user = json.jsonObject("user") else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Backend /v1/me did not return a user.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        return MeResponse(
            user: parseUser(user),
            profiles: json.jsonArray("profiles").compactMap(parseProfile)
        )
    }

    func listProfiles(accessToken: String) async throws -> [BackendProfile] {
        let json = try await getJson(path: "/v1/profiles", accessToken: accessToken)
        return json.jsonArray("profiles").compactMap(parseProfile)
    }

    func bootstrapAccount(
        accessToken: String,
        name: String,
        interfaceLanguage: String,
        avatarUrl: String,
        region: String?
    ) async throws -> BackendProfile {
        var payload: [String: Any] = [
            "name": name.trimmingCharacters(in: .whitespacesAndNewlines),
            "interfaceLanguage": interfaceLanguage.trimmingCharacters(in: .whitespacesAndNewlines),
            "avatarUrl": avatarUrl.trimmingCharacters(in: .whitespacesAndNewlines),
        ]
        if let region = region?.nilIfBlank {
            payload["region"] = region
        }
        let json = try await postJson(path: "/v1/account/bootstrap", accessToken: accessToken, payload: payload)
        guard let profileJson = json.jsonObject("profile"), let profile = parseProfile(profileJson) else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Bootstrap did not return a profile.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        return profile
    }

    func createProfile(
        accessToken: String,
        name: String,
        sortOrder: Int?,
        isKids: Bool,
        avatarUrl: String?
    ) async throws -> BackendProfile {
        var payload: [String: Any] = ["name": name.trimmingCharacters(in: .whitespacesAndNewlines)]
        if let sortOrder { payload["sortOrder"] = sortOrder }
        if isKids { payload["isKids"] = true }
        if let avatarUrl = avatarUrl?.nilIfBlank { payload["avatarUrl"] = avatarUrl }
        let json = try await postJson(path: "/v1/profiles", accessToken: accessToken, payload: payload)
        guard let profileJson = json.jsonObject("profile"), let profile = parseProfile(profileJson) else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Create-profile did not return a profile.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        return profile
    }

    // MARK: - Home

    func getHome(accessToken: String, profileId: String) async throws -> ProfileHomeResponse? {
        let json = try await getJson(path: "/v1/profiles/\(profileId)/home", accessToken: accessToken)
        guard let parsedProfileId = json.jsonString("profileId") else { return nil }
        return ProfileHomeResponse(
            profileId: parsedProfileId,
            generatedAt: json.jsonString("generatedAt"),
            expiresAt: json.jsonString("expiresAt"),
            sections: parseProfileHomeSections(json.jsonArray("sections"))
        )
    }

    // MARK: - Watch collections

    func listContinueWatching(accessToken: String, profileId: String, limit: Int = 20, cursor: String? = nil) async throws -> ClientMediaCardQueryResult {
        try await listWatchCollection(accessToken: accessToken, profileId: profileId, path: "continue-watching", limit: limit, cursor: cursor)
    }

    func dismissContinueWatching(accessToken: String, profileId: String, itemId: String) async throws -> WatchActionResponse {
        let json = try await deleteJson(path: "/v1/profiles/\(profileId)/watch/continue-watching/\(itemId)", accessToken: accessToken)
        return parseWatchActionResponse(json)
    }

    func listWatchHistory(accessToken: String, profileId: String, limit: Int = 50, cursor: String? = nil) async throws -> ClientMediaCardQueryResult {
        try await listWatchCollection(accessToken: accessToken, profileId: profileId, path: "history", limit: limit, cursor: cursor)
    }

    func listWatchlist(accessToken: String, profileId: String, limit: Int = 50, cursor: String? = nil) async throws -> ClientMediaCardQueryResult {
        try await listWatchCollection(accessToken: accessToken, profileId: profileId, path: "watchlist", limit: limit, cursor: cursor)
    }

    func listRatings(accessToken: String, profileId: String, limit: Int = 50, cursor: String? = nil) async throws -> ClientMediaCardQueryResult {
        try await listWatchCollection(accessToken: accessToken, profileId: profileId, path: "ratings", limit: limit, cursor: cursor)
    }

    // MARK: - Search

    func searchTitles(accessToken: String, query: String, limit: Int = 20) async throws -> SearchResultsResponse {
        let json = try await getJson(
            path: "/v1/search/titles",
            queryItems: [
                URLQueryItem(name: "query", value: query.trimmingCharacters(in: .whitespacesAndNewlines)),
                URLQueryItem(name: "limit", value: String(limit)),
            ],
            accessToken: accessToken
        )
        return SearchResultsResponse(
            query: json.jsonString("query") ?? "",
            movies: json.jsonArray("movies").compactMap(parseSearchMediaItem),
            series: json.jsonArray("series").compactMap(parseSearchMediaItem),
            people: json.jsonArray("people").compactMap(parsePersonSearchResult)
        )
    }

    func searchSuggestions(accessToken: String, query: String, limit: Int = 8) async throws -> SearchSuggestionsResponse {
        let json = try await getJson(
            path: "/v1/search/suggestions",
            queryItems: [
                URLQueryItem(name: "query", value: query.trimmingCharacters(in: .whitespacesAndNewlines)),
                URLQueryItem(name: "filter", value: "all"),
                URLQueryItem(name: "limit", value: String(limit)),
            ],
            accessToken: accessToken
        )
        return SearchSuggestionsResponse(
            suggestions: json.jsonArray("suggestions").compactMap(parseSearchSuggestion)
        )
    }

    // MARK: - Watch mutations

    func markWatched(accessToken: String, profileId: String, itemId: String) async throws -> WatchActionResponse {
        try await postWatchMutation(accessToken: accessToken, profileId: profileId, path: "mark-watched", itemId: itemId)
    }

    func unmarkWatched(accessToken: String, profileId: String, itemId: String) async throws -> WatchActionResponse {
        try await postWatchMutation(accessToken: accessToken, profileId: profileId, path: "unmark-watched", itemId: itemId)
    }

    // MARK: - Parsers

    private func parseUser(_ json: [String: Any]) -> BackendUser {
        BackendUser(
            id: json.jsonString("id") ?? "",
            email: json.jsonString("email")
        )
    }

    private func parseProfile(_ json: [String: Any]) -> BackendProfile? {
        guard let id = json.jsonString("id"), let name = json.jsonString("name") else { return nil }
        return BackendProfile(
            id: id,
            name: name,
            avatarUrl: json.jsonString("avatarUrl"),
            isKids: json.jsonBool("isKids", defaultValue: false),
            sortOrder: json.jsonInt("sortOrder") ?? 0
        )
    }

    private func parseProfileHomeSections(_ array: [[String: Any]]) -> [ProfileHomeSection] {
        array.compactMap { section in
            guard let listKey = section.jsonString("listKey"),
                  let title = section.jsonString("title") else { return nil }
            let presentation = section.jsonString("sectionType") ?? section.jsonString("layout") ?? "contentRail"
            return ProfileHomeSection(
                listKey: listKey,
                title: title,
                subtitle: section.jsonString("subtitle"),
                layout: presentation,
                items: section.jsonArray("items").compactMap { try? ClientMediaCard.parse($0) },
                meta: section.jsonStringMap("meta")
            )
        }
    }

    private func parseSearchMediaItem(_ json: [String: Any]) -> SearchMediaItem? {
        guard let itemId = json.jsonString("Id"),
              let type = json.jsonString("Type"),
              let name = json.jsonString("Name") else { return nil }
        let imageTags = json.jsonObject("ImageTags")
        return SearchMediaItem(
            itemId: itemId,
            itemType: normalizeMediaType(type),
            title: name,
            posterUrl: ResponsiveImageSetDto.parse(imageTags?.jsonObject("Primary")).medium ?? ResponsiveImageSetDto.parse(imageTags?.jsonObject("Primary")).large,
            backdropUrl: ResponsiveImageSetDto.parse((imageTags?.jsonObject("Backdrop") as? [[String: Any]])?.first).medium,
            logoUrl: ResponsiveImageSetDto.parse(imageTags?.jsonObject("Logo")).medium,
            year: json.jsonInt("ProductionYear"),
            rating: json.jsonDouble("CommunityRating"),
            genres: json.jsonStringList("Genres"),
            maturityRating: json.jsonString("OfficialRating"),
            overview: json.jsonString("Overview"),
            providerIds: MediaExternalIds.parse(json.jsonObject("ProviderIds"))
        )
    }

    private func parsePersonSearchResult(_ json: [String: Any]) -> PersonSearchResultItem? {
        guard let personId = json.jsonString("personId"), let name = json.jsonString("name") else { return nil }
        return PersonSearchResultItem(
            personId: personId,
            name: name,
            knownForDepartment: json.jsonString("knownForDepartment"),
            profileUrl: json.jsonString("profileUrl")
        )
    }

    private func parseSearchSuggestion(_ json: [String: Any]) -> SearchSuggestionItem? {
        guard let itemId = json.jsonString("Id"),
              let type = json.jsonString("Type"),
              let title = json.jsonString("Name") else { return nil }
        let primary = json.jsonObject("ImageTags")?.jsonObject("Primary")
        return SearchSuggestionItem(
            itemId: itemId,
            itemType: normalizeMediaType(type),
            title: title,
            year: json.jsonInt("ProductionYear"),
            posterUrl: primary?.jsonString("medium") ?? primary?.jsonString("large") ?? primary?.jsonString("small")
        )
    }

    private func parseWatchActionResponse(_ json: [String: Any]) -> WatchActionResponse {
        WatchActionResponse(
            accepted: json.jsonBool("accepted", defaultValue: true),
            mode: json.jsonString("mode") ?? "",
            reason: json.jsonString("reason")
        )
    }

    private func normalizeMediaType(_ raw: String) -> String {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines) {
        case "Movie": return "movie"
        case "Series": return "show"
        case "Season": return "season"
        case "Episode": return "episode"
        default: return "unknown"
        }
    }

    // MARK: - Transport plumbing

    private func listWatchCollection(
        accessToken: String,
        profileId: String,
        path: String,
        limit: Int,
        cursor: String?
    ) async throws -> ClientMediaCardQueryResult {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "limit", value: String(max(limit, 1))),
            URLQueryItem(name: "extended", value: "true"),
        ]
        if let cursor = cursor?.trimmingCharacters(in: .whitespacesAndNewlines), !cursor.isEmpty {
            items.append(URLQueryItem(name: "cursor", value: cursor))
        }
        let json = try await getJson(
            path: "/v1/profiles/\(profileId)/watch/\(path)",
            queryItems: items,
            accessToken: accessToken
        )
        return ClientMediaCardQueryResult(
            items: json.jsonArray("Items").compactMap { try? ClientMediaCard.parse($0) },
            startIndex: json.jsonInt("StartIndex") ?? 0,
            totalRecordCount: json.jsonInt("TotalRecordCount") ?? 0,
            nextCursor: json.jsonString("NextCursor"),
            hasMore: json.jsonBool("HasMore", defaultValue: !(json.jsonString("NextCursor") ?? "").isEmpty)
        )
    }

    private func postWatchMutation(accessToken: String, profileId: String, path: String, itemId: String) async throws -> WatchActionResponse {
        let response = try await postJson(
            path: "/v1/profiles/\(profileId)/watch/\(path)",
            accessToken: accessToken,
            payload: ["itemId": itemId.trimmingCharacters(in: .whitespacesAndNewlines)]
        )
        return parseWatchActionResponse(response)
    }

    private func authHeaders(_ accessToken: String) -> [String: String] {
        [
            "Authorization": "Bearer \(accessToken.trimmingCharacters(in: .whitespacesAndNewlines))",
            "Content-Type": "application/json",
            "Accept": "application/json",
        ]
    }

    private func queryComponents(path: String) throws -> URLComponents {
        guard let url = URL(string: baseURL + path) else {
            throw CrispyHttpError.invalidResponse
        }
        return URLComponents(url: url, resolvingAgainstBaseURL: false) ?? URLComponents()
    }

    private func requireURL(_ url: URL?) throws -> URL {
        guard let url else { throw CrispyHttpError.invalidResponse }
        return url
    }

    private func perform(_ response: CrispyHttpResponse) throws -> [String: Any] {
        if (200...299).contains(response.code) {
            return try dataEnvelope(response.body)
        }
        throw errorEnvelope(code: response.code, body: response.body)
    }

    private func getJson(path: String, accessToken: String) async throws -> [String: Any] {
        try await getJson(path: path, queryItems: [], accessToken: accessToken)
    }

    private func getJson(path: String, queryItems: [URLQueryItem], accessToken: String) async throws -> [String: Any] {
        var components = try queryComponents(path: path)
        if !queryItems.isEmpty {
            components.queryItems = (components.queryItems ?? []) + queryItems
        }
        let response = try await httpClient.get(url: try requireURL(components.url), headers: authHeaders(accessToken))
        return try perform(response)
    }

    private func postJson(path: String, accessToken: String, payload: [String: Any]) async throws -> [String: Any] {
        let response = try await httpClient.postJson(
            url: try requireURL(queryComponents(path: path).url),
            jsonBody: try JsonParser.encodeObject(payload),
            headers: authHeaders(accessToken)
        )
        return try perform(response)
    }

    private func deleteJson(path: String, accessToken: String) async throws -> [String: Any] {
        let response = try await httpClient.delete(
            url: try requireURL(queryComponents(path: path).url),
            headers: authHeaders(accessToken)
        )
        return try perform(response)
    }

    private func dataEnvelope(_ body: String) throws -> [String: Any] {
        let json = try JsonParser.parseObject(body)
        guard let data = json.jsonObject("data") else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Response missing 'data' envelope", category: nil, retryable: false, requestId: nil, details: nil)
        }
        return data
    }

    private func errorEnvelope(code: Int, body: String) -> Error {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let json = try? JsonParser.parseObject(trimmed) else {
            return CrispyBackendError(httpCode: code, code: nil, message: "HTTP \(code)", category: nil, retryable: false, requestId: nil, details: nil)
        }
        let error = json.jsonObject("error")
        return CrispyBackendError(
            httpCode: code,
            code: error?.jsonString("code"),
            message: error?.jsonString("message") ?? json.jsonString("message") ?? "HTTP \(code)",
            category: error?.jsonString("category"),
            retryable: error?.jsonBool("retryable", defaultValue: false) ?? false,
            requestId: error?.jsonString("requestId") ?? json.jsonString("requestId"),
            details: nil
        )
    }
}
