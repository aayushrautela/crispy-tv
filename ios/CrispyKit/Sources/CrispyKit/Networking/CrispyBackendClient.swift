import Foundation

public struct CrispyBackendError: Error {
public let httpCode: Int
public let code: String?
public let message: String?
public let category: String?
public let retryable: Bool
public let requestId: String?
public let details: String?
}

/// URLSession port of the Android `CrispyBackendClient`, restricted to the
/// endpoints the first-pass pages consume. Response envelopes and field names
/// mirror `CrispyBackendParsers.kt` exactly.
public final class CrispyBackendClient {
    private let httpClient: CrispyHttpClient
    private let baseURL: String

public init(httpClient: CrispyHttpClient, backendURL: String) {
        self.httpClient = httpClient
        self.baseURL = backendURL.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

public func isConfigured() -> Bool {
        !baseURL.isEmpty
    }

    // MARK: - Account

public func getMe(accessToken: String) async throws -> MeResponse {
        let json = try await getJson(path: "/v1/me", accessToken: accessToken)
        guard let user = json.jsonObject("user") else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Backend /v1/me did not return a user.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        return MeResponse(
            user: parseUser(user),
            profiles: json.jsonArray("profiles").compactMap(parseProfile)
        )
    }

public func listProfiles(accessToken: String) async throws -> [BackendProfile] {
        let json = try await getJson(path: "/v1/profiles", accessToken: accessToken)
        return json.jsonArray("profiles").compactMap(parseProfile)
    }

public func bootstrapAccount(
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

public func createProfile(
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

public func getHome(accessToken: String, profileId: String) async throws -> ProfileHomeResponse? {
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

public func listContinueWatching(accessToken: String, profileId: String, limit: Int = 20, cursor: String? = nil) async throws -> ClientMediaCardQueryResult {
        try await listWatchCollection(accessToken: accessToken, profileId: profileId, path: "continue-watching", limit: limit, cursor: cursor)
    }

public func dismissContinueWatching(accessToken: String, profileId: String, itemId: String) async throws -> WatchActionResponse {
        let json = try await deleteJson(path: "/v1/profiles/\(profileId)/watch/continue-watching/\(itemId)", accessToken: accessToken)
        return parseWatchActionResponse(json)
    }

public func listWatchHistory(accessToken: String, profileId: String, limit: Int = 50, cursor: String? = nil) async throws -> ClientMediaCardQueryResult {
        try await listWatchCollection(accessToken: accessToken, profileId: profileId, path: "history", limit: limit, cursor: cursor)
    }

public func listWatchlist(accessToken: String, profileId: String, limit: Int = 50, cursor: String? = nil) async throws -> ClientMediaCardQueryResult {
        try await listWatchCollection(accessToken: accessToken, profileId: profileId, path: "watchlist", limit: limit, cursor: cursor)
    }

public func listRatings(accessToken: String, profileId: String, limit: Int = 50, cursor: String? = nil) async throws -> ClientMediaCardQueryResult {
        try await listWatchCollection(accessToken: accessToken, profileId: profileId, path: "ratings", limit: limit, cursor: cursor)
    }

    // MARK: - Search

public func searchTitles(accessToken: String, query: String, limit: Int = 20) async throws -> SearchResultsResponse {
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

public func searchSuggestions(accessToken: String, query: String, limit: Int = 8) async throws -> SearchSuggestionsResponse {
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

    // MARK: - Accounts / settings

    public func updateProfile(
        accessToken: String,
        profileId: String,
        name: String? = nil,
        isKids: Bool? = nil,
        avatarUrl: String? = nil,
        sortOrder: Int? = nil
    ) async throws -> BackendProfile {
        var payload: [String: Any] = [:]
        if let name = name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty { payload["name"] = name }
        if let isKids { payload["isKids"] = isKids }
        if let avatarUrl = avatarUrl?.nilIfBlank { payload["avatarUrl"] = avatarUrl }
        if let sortOrder { payload["sortOrder"] = sortOrder }
        let json = try await patchJson(path: "/v1/profiles/\(profileId)", accessToken: accessToken, payload: payload)
        guard let profileJson = json.jsonObject("profile"), let profile = parseProfile(profileJson) else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Backend did not return an updated profile.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        return profile
    }

    public func getAvatars(accessToken: String) async throws -> [AvatarItem] {
        let json = try await getJson(path: "/v1/avatars", accessToken: accessToken)
        let array = json.jsonArray("avatars").isEmpty ? json.jsonArray("items") : json.jsonArray("avatars")
        return array.compactMap { item in
            guard let id = item.jsonString("id") else { return nil }
            return AvatarItem(id: id, url: item.jsonString("url"))
        }
    }

    public func listImportConnections(accessToken: String, profileId: String) async throws -> [ProviderState] {
        let json = try await getJson(path: "/v1/profiles/\(profileId)/import-connections", accessToken: accessToken)
        return json.jsonArray("providerStates").map { item in
            ProviderState(
                provider: item.jsonString("provider") ?? "",
                connectionState: item.jsonString("connectionState") ?? "",
                primaryAction: item.jsonString("primaryAction") ?? "",
                canDisconnect: item.jsonBool("canDisconnect", defaultValue: false),
                externalUsername: item.jsonString("externalUsername"),
                statusLabel: item.jsonString("statusLabel") ?? "",
                statusMessage: item.jsonString("statusMessage")
            )
        }
    }

    public func disconnectImportConnection(accessToken: String, profileId: String, provider: String) async throws -> ProviderState {
        let json = try await deleteJson(path: "/v1/profiles/\(profileId)/import-connections/\(provider)", accessToken: accessToken)
        guard let stateJson = json.jsonObject("providerState") else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Backend did not return a provider state.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        return ProviderState(
            provider: stateJson.jsonString("provider") ?? provider,
            connectionState: stateJson.jsonString("connectionState") ?? "",
            primaryAction: stateJson.jsonString("primaryAction") ?? "",
            canDisconnect: stateJson.jsonBool("canDisconnect", defaultValue: false),
            externalUsername: stateJson.jsonString("externalUsername"),
            statusLabel: stateJson.jsonString("statusLabel") ?? "",
            statusMessage: stateJson.jsonString("statusMessage")
        )
    }

    public func putWatchlist(accessToken: String, profileId: String, itemId: String) async throws -> WatchActionResponse {
        try await executeWatchlistMutation(method: "PUT", accessToken: accessToken, profileId: profileId, itemId: itemId)
    }

    public func deleteWatchlist(accessToken: String, profileId: String, itemId: String) async throws -> WatchActionResponse {
        try await executeWatchlistMutation(method: "DELETE", accessToken: accessToken, profileId: profileId, itemId: itemId)
    }

    private func executeWatchlistMutation(method: String, accessToken: String, profileId: String, itemId: String) async throws -> WatchActionResponse {
        let normalizedItemId = itemId.trimmingCharacters(in: .whitespacesAndNewlines)
        let response: CrispyHttpResponse
        if method == "PUT" {
            response = try await httpClient.putJson(
                url: try requireURL(queryComponents(path: "/v1/profiles/\(profileId)/watch/watchlist/\(normalizedItemId)").url),
                jsonBody: "{}",
                headers: authHeaders(accessToken)
            )
        } else {
            response = try await httpClient.delete(
                url: try requireURL(queryComponents(path: "/v1/profiles/\(profileId)/watch/watchlist/\(normalizedItemId)").url),
                headers: authHeaders(accessToken)
            )
        }
        return parseWatchActionResponse(try perform(response))
    }

    public func deleteAccount(accessToken: String) async throws {
        let response = try await httpClient.delete(
            url: try requireURL(queryComponents(path: "/v1/account").url),
            headers: authHeaders(accessToken)
        )
        _ = try perform(response)
    }

    // MARK: - Calendar

    public func getCalendarThisWeek(accessToken: String, profileId: String) async throws -> [SearchMediaItem] {
        let json = try await getJson(path: "/v1/profiles/\(profileId)/calendar/this-week", accessToken: accessToken)
        return json.jsonArray("items").compactMap(parseSearchMediaItem)
    }

    public func getUpNext(accessToken: String, profileId: String, limit: Int = 20) async throws -> [UpNextEntry] {
        let json = try await getJson(path: "/v1/profiles/\(profileId)/watch/episodic-follow", accessToken: accessToken)
        return json.jsonArray("items").compactMap { item in
            let show = item.jsonObject("show")
            guard let showItemId = show?.jsonString("itemId"),
                  let showTitle = show?.jsonString("title") else { return nil }
            let images = show?.jsonObject("images")
            let season = item.jsonInt("nextEpisodeSeasonNumber")
            let episode = item.jsonInt("nextEpisodeEpisodeNumber")
            var badge: String?
            if let season, let episode {
                badge = "S\(season)E\(episode)"
            } else if let season {
                badge = "S\(season)"
            }
            return UpNextEntry(
                showItemId: showItemId,
                showTitle: showTitle,
                backdropUrl: images?.jsonObject("backdrop")?.jsonString("medium") ?? images?.jsonObject("backdrop")?.jsonString("large"),
                posterUrl: images?.jsonObject("poster")?.jsonString("medium") ?? images?.jsonObject("poster")?.jsonString("large"),
                logoUrl: images?.jsonObject("logo")?.jsonString("medium"),
                badge: badge,
                airDate: item.jsonString("nextEpisodeAirDate")
            )
        }
    }

    // MARK: - Metadata / details

public func getMetadataItemDetail(accessToken: String, itemId: String) async throws -> MetadataTitleDetail {
        let json = try await getJson(path: "/v1/metadata/items/\(itemId.trimmingCharacters(in: .whitespacesAndNewlines))", accessToken: accessToken)
        guard let itemJson = json.jsonObject("Item") else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Backend item detail is missing Item.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        return MetadataTitleDetail(
            item: try parseMetadataItem(itemJson),
            cast: parsePersonRefs(json.jsonArray("Cast")),
            directors: parsePersonRefs(json.jsonArray("Directors")),
            creators: parsePersonRefs(json.jsonArray("Creators"))
        )
    }

public func getMetadataItemExtras(accessToken: String, itemId: String) async throws -> MetadataTitleExtras {
        let json = try await getJson(path: "/v1/metadata/items/\(itemId.trimmingCharacters(in: .whitespacesAndNewlines))/extras", accessToken: accessToken)
        return MetadataTitleExtras(
            seasons: json.jsonArray("Seasons").compactMap(parseMetadataSeason),
            similar: json.jsonArray("Similar").compactMap(parseMetadataCard)
        )
    }

public func getSeriesEpisodes(accessToken: String, seriesItemId: String, season: Int?) async throws -> [MetadataEpisode] {
        var query: [URLQueryItem] = []
        if let season {
            query.append(URLQueryItem(name: "season", value: String(season)))
        }
        let json = try await getJson(path: "/v1/metadata/shows/\(seriesItemId.trimmingCharacters(in: .whitespacesAndNewlines))/episodes", queryItems: query, accessToken: accessToken)
        return json.jsonArray("Items").compactMap(parseMetadataEpisodeFromView)
    }

public func getMetadataPersonDetail(accessToken: String, personId: String) async throws -> PersonDetail {
        let json = try await getJson(path: "/v1/metadata/people/\(personId.trimmingCharacters(in: .whitespacesAndNewlines))", accessToken: accessToken)
        guard let personIdValue = json.jsonString("personId"), let name = json.jsonString("name") else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Backend person detail is missing required fields.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        return PersonDetail(
            personId: personIdValue,
            name: name,
            knownForDepartment: json.jsonString("knownForDepartment"),
            biography: json.jsonString("biography"),
            birthday: json.jsonString("birthday"),
            placeOfBirth: json.jsonString("placeOfBirth"),
            profileUrl: json.jsonString("profileUrl"),
            knownFor: json.jsonArray("knownFor").compactMap(parseKnownForItem)
        )
    }

    // MARK: - Watch mutations

public func markWatched(accessToken: String, profileId: String, itemId: String) async throws -> WatchActionResponse {
        try await postWatchMutation(accessToken: accessToken, profileId: profileId, path: "mark-watched", itemId: itemId)
    }

public func unmarkWatched(accessToken: String, profileId: String, itemId: String) async throws -> WatchActionResponse {
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
            providerIds: MediaExternalIds.parse(json.jsonObject("ProviderIds")),
            seriesItemId: json.jsonString("SeriesId")
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

    // MARK: Metadata parsers (mirror CrispyBackendParsers.kt)

    private func parseMetadataItem(_ json: [String: Any]) throws -> MetadataItem {
        guard let itemId = json.jsonString("Id"),
              let type = json.jsonString("Type"),
              let name = json.jsonString("Name") else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "BaseItemDto is missing required identity fields.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        let imageTags = json.jsonObject("ImageTags")
        return MetadataItem(
            itemId: itemId,
            itemType: normalizeMediaType(type),
            title: name,
            subtitle: json.jsonString("EpisodeTitle") ?? nil,
            overview: json.jsonString("Overview"),
            images: MetadataImagesDto.parse(imageTags),
            releaseDate: json.jsonString("PremiereDate"),
            releaseYear: json.jsonInt("ProductionYear"),
            runtimeMinutes: runtimeMinutes(fromTicks: json.jsonInt("RunTimeTicks")),
            rating: json.jsonDouble("CommunityRating"),
            certification: json.jsonString("Certification"),
            status: json.jsonString("Status"),
            genres: json.jsonStringList("Genres"),
            seasonNumber: json.jsonInt("ParentIndexNumber"),
            episodeNumber: json.jsonInt("IndexNumber")
        )
    }

    private func parseMetadataEpisodeFromView(_ json: [String: Any]) -> MetadataEpisode? {
        guard let itemId = json.jsonString("Id") else { return nil }
        let imageTags = json.jsonObject("ImageTags")
        return MetadataEpisode(
            itemId: itemId,
            seasonNumber: json.jsonInt("ParentIndexNumber"),
            episodeNumber: json.jsonInt("IndexNumber"),
            title: json.jsonString("EpisodeTitle") ?? json.jsonString("Name") ?? "Episode",
            summary: json.jsonString("Overview"),
            airDate: json.jsonString("AirDate") ?? json.jsonString("PremiereDate"),
            runtimeMinutes: runtimeMinutes(fromTicks: json.jsonInt("RunTimeTicks")),
            rating: json.jsonDouble("CommunityRating"),
            stillUrl: MetadataImagesDto.parse(imageTags).stillUrl,
            showItemId: json.jsonString("SeriesId")
        )
    }

    private func parseMetadataSeason(_ json: [String: Any]) -> MetadataSeason? {
        guard let itemId = json.jsonString("Id"), let seasonNumber = json.jsonInt("IndexNumber") else { return nil }
        return MetadataSeason(
            itemId: itemId,
            seasonNumber: seasonNumber,
            title: json.jsonString("Name"),
            summary: json.jsonString("Overview"),
            posterUrl: ResponsiveImageSetDto.parse(json.jsonObject("ImageTags")?.jsonObject("Primary")).medium
        )
    }

    private func parseMetadataCard(_ json: [String: Any]) -> MetadataCard? {
        guard let itemId = json.jsonString("Id"),
              let type = json.jsonString("Type"),
              let name = json.jsonString("Name") else { return nil }
        return MetadataCard(
            itemId: itemId,
            itemType: normalizeMediaType(type),
            title: name,
            images: MetadataImagesDto.parse(json.jsonObject("ImageTags")),
            releaseYear: json.jsonInt("ProductionYear"),
            rating: json.jsonDouble("CommunityRating")
        )
    }

    private func parsePersonRefs(_ array: [[String: Any]]) -> [MetadataPersonRef] {
        var seen = Set<String>()
        return array.compactMap { item in
            guard let personId = item.jsonString("personId"), let name = item.jsonString("name") else { return nil }
            guard seen.insert(personId).inserted else { return nil }
            return MetadataPersonRef(
                personId: personId,
                name: name,
                role: item.jsonString("role"),
                department: item.jsonString("department"),
                profileUrl: item.jsonString("profileUrl")
            )
        }
    }

    private func parseKnownForItem(_ json: [String: Any]) -> PersonKnownForItem? {
        guard let itemId = json.jsonString("itemId"),
              let mediaType = json.jsonString("mediaType"),
              let title = json.jsonString("title") else { return nil }
        return PersonKnownForItem(
            itemId: itemId,
            mediaType: mediaType,
            title: title,
            posterUrl: ResponsiveImageSetDto.parse(json.jsonObject("poster")).medium,
            backdropUrl: ResponsiveImageSetDto.parse(json.jsonObject("backdrop")).medium,
            logoUrl: ResponsiveImageSetDto.parse(json.jsonObject("logo")).medium,
            rating: json.jsonDouble("rating"),
            releaseYear: json.jsonInt("releaseYear")
        )
    }

    private func runtimeMinutes(fromTicks ticks: Int?) -> Int? {
        guard let ticks, ticks > 0 else { return nil }
        return ticks / 600_000_000
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

    public     func patchJson(path: String, accessToken: String, payload: [String: Any]) async throws -> [String: Any] {
        let response = try await httpClient.patchJson(
            url: try requireURL(queryComponents(path: path).url),
            jsonBody: try JsonParser.encodeObject(payload),
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
