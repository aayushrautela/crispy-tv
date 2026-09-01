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
            let nextEpisode = item.jsonObject("nextEpisode")
            let nextParent = nextEpisode?.jsonObject("parent")
            let season = nextParent?.jsonInt("seasonNumber")
            let episode = nextParent?.jsonInt("episodeNumber")
            var badge: String?
            if let season, let episode {
                badge = "S\(season)E\(episode)"
            } else if let season {
                badge = "S\(season)"
            }
            return UpNextEntry(
                showItemId: showItemId,
                showTitle: showTitle,
                artworkUrl: images?.jsonObject("artwork")?.jsonString("medium") ?? images?.jsonObject("artwork")?.jsonString("large"),
                logoUrl: images?.jsonObject("logo")?.jsonString("medium"),
                badge: badge,
                airDate: item.jsonString("nextEpisodeAirDate")
            )
        }
    }

    public func getWatchState(accessToken: String, profileId: String, itemId: String) async throws -> WatchState {
        var components = try queryComponents(path: "/v1/profiles/\(profileId)/watch/state")
        components.queryItems = [
            URLQueryItem(name: "itemId", value: itemId.trimmingCharacters(in: .whitespacesAndNewlines)),
            URLQueryItem(name: "extended", value: "true"),
        ]
        let json = try await getJson(path: "", queryItems: [], url: try requireURL(components.url), accessToken: accessToken)
        let progress = ClientProgress.parse(json.jsonObject("progress"))
        return WatchState(
            itemId: json.jsonString("itemId") ?? itemId,
            played: progress?.played ?? false,
            playCount: progress?.playCount ?? 0,
            resumePositionSeconds: progress?.positionSeconds.map(Double.init),
            durationSeconds: progress?.durationSeconds.map(Double.init),
            progressPercent: progress?.percent ?? resolvedPercent(position: progress?.positionSeconds, duration: progress?.durationSeconds)
        )
    }

    // MARK: - Metadata / details

public func getMetadataItemDetail(accessToken: String, itemId: String) async throws -> MetadataTitleDetail {
        let json = try await getJson(path: "/v1/metadata/items/\(itemId.trimmingCharacters(in: .whitespacesAndNewlines))", accessToken: accessToken)
        guard let itemJson = json.jsonObject("Item") else {
            throw CrispyBackendError(httpCode: 200, code: nil, message: "Backend item detail is missing Item.", category: nil, retryable: false, requestId: nil, details: nil)
        }
        let production = (json.jsonObject("Production")?.jsonArray("companies") ?? []) +
            (json.jsonObject("Production")?.jsonArray("networks") ?? [])
        return MetadataTitleDetail(
            item: try ClientMediaCard.parse(itemJson),
            cast: parsePersonRefs(json.jsonArray("Cast")),
            directors: parsePersonRefs(json.jsonArray("Directors")),
            creators: parsePersonRefs(json.jsonArray("Creators")),
            videos: json.jsonArray("Videos").compactMap(parseMetadataVideo),
            production: production.compactMap(parseMetadataCompany),
            backdrops: json.jsonArray("Backdrops").compactMap { $0 as? String },
            nextEpisode: json.jsonObject("NextEpisode").flatMap { try? ClientMediaCard.parse($0) }
        )
    }

public func getMetadataItemExtras(accessToken: String, itemId: String) async throws -> MetadataTitleExtras {
        let json = try await getJson(path: "/v1/metadata/items/\(itemId.trimmingCharacters(in: .whitespacesAndNewlines))/extras", accessToken: accessToken)
        let collection = (json.jsonObject("Collection")?["Items"] as? [[String: Any]])?.compactMap { try? ClientMediaCard.parse($0) }
        return MetadataTitleExtras(
            seasons: json.jsonArray("Seasons").compactMap { try? ClientMediaCard.parse($0) },
            similar: json.jsonArray("Similar").compactMap { try? ClientMediaCard.parse($0) },
            reviews: json.jsonArray("Reviews").compactMap(parseMetadataReview),
            collection: collection,
            collectionName: json.jsonString("CollectionName")
        )
    }

    func getMetadataItemRatings(accessToken: String, profileId: String, itemId: String) async throws -> TitleRatings {
        let json = try await getJson(path: "/v1/profiles/\(profileId)/metadata/items/\(itemId)/ratings", accessToken: accessToken)
        let r = json.jsonObject("Ratings")
        return TitleRatings(
            imdb: r?.jsonDouble("imdb"),
            tmdb: r?.jsonDouble("tmdb"),
            trakt: r?.jsonDouble("trakt"),
            metacritic: r?.jsonDouble("metacritic"),
            rottenTomatoes: r?.jsonDouble("rottenTomatoes"),
            audience: r?.jsonDouble("audience")
        )
    }

public func getSeriesEpisodes(accessToken: String, seriesItemId: String, season: Int?) async throws -> [ClientMediaCard] {
        var query: [URLQueryItem] = []
        if let season {
            query.append(URLQueryItem(name: "season", value: String(season)))
        }
        let json = try await getJson(path: "/v1/metadata/shows/\(seriesItemId.trimmingCharacters(in: .whitespacesAndNewlines))/episodes", queryItems: query, accessToken: accessToken)
        return json.jsonArray("Items").compactMap { try? ClientMediaCard.parse($0) }
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
            knownForRails: KnownForPartitioner.partition(items: json.jsonArray("knownFor").compactMap(parseKnownForItem))
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
        guard let itemId = json.jsonString("itemId"),
              let mediaType = json.jsonString("mediaType"),
              let title = json.jsonString("title") else { return nil }
        let images = json.jsonObject("images")
        return SearchMediaItem(
            itemId: itemId,
            itemType: mediaType,
            title: title,
            artworkUrl: ResponsiveImageSetDto.parse(images?.jsonObject("artwork")).medium ?? ResponsiveImageSetDto.parse(images?.jsonObject("artwork")).large,
            logoUrl: ResponsiveImageSetDto.parse(images?.jsonObject("logo")).medium,
            year: json.jsonInt("year"),
            rating: json.jsonDouble("rating"),
            genres: json.jsonStringList("genres"),
            maturityRating: json.jsonString("maturityRating"),
            overview: json.jsonString("overview"),
            providerIds: MediaExternalIds.parse(json.jsonObject("providerIds")),
            seriesItemId: json.jsonObject("parent")?.jsonString("seriesItemId")
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
        guard let itemId = json.jsonString("itemId"),
              let mediaType = json.jsonString("mediaType"),
              let title = json.jsonString("title") else { return nil }
        let images = json.jsonObject("images")
        let primary = images?.jsonObject("artwork")
        return SearchSuggestionItem(
            itemId: itemId,
            itemType: mediaType,
            title: title,
            year: json.jsonInt("year"),
            artworkUrl: primary?.jsonString("medium") ?? primary?.jsonString("large") ?? primary?.jsonString("small")
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

    private func parseMetadataReview(_ json: [String: Any]) -> MetadataReview? {
        guard let id = json.jsonString("id"), let content = json.jsonString("content") else { return nil }
        return MetadataReview(
            id: id,
            provider: json.jsonString("provider") ?? "",
            author: json.jsonString("author") ?? json.jsonString("username"),
            username: json.jsonString("username"),
            content: content,
            rating: json.jsonDouble("rating"),
            url: json.jsonString("url")
        )
    }

    private func parseKnownForItem(_ json: [String: Any]) -> PersonKnownForItem? {
        guard let itemId = json.jsonString("itemId"),
              let mediaType = json.jsonString("mediaType"),
              let title = json.jsonString("title") else { return nil }
        let images = json.jsonObject("images")
        return PersonKnownForItem(
            itemId: itemId,
            mediaType: mediaType,
            title: title,
            artworkUrl: ResponsiveImageSetDto.parse(images?.jsonObject("artwork")).medium,
            logoUrl: ResponsiveImageSetDto.parse(images?.jsonObject("logo")).medium,
            rating: json.jsonDouble("rating"),
            releaseYear: json.jsonInt("year"),
            genres: json.jsonArray("genres").compactMap { $0 as? String }
        )
    }

    private func parseMetadataVideo(_ json: [String: Any]) -> MetadataVideo? {
        guard let id = json.jsonString("id"), let key = json.jsonString("key") else { return nil }
        return MetadataVideo(
            id: id,
            name: json.jsonString("name"),
            site: json.jsonString("site"),
            key: key,
            thumbnailUrl: json.jsonString("thumbnailUrl"),
            url: json.jsonString("url")
        )
    }

    private func parseMetadataCompany(_ json: [String: Any]) -> MetadataCompany? {
        guard let id = json.jsonString("id"), let name = json.jsonString("name") else { return nil }
        return MetadataCompany(id: id, name: name, logoUrl: ResponsiveImageSetDto.parse(json.jsonObject("logo")).medium)
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

    private func getJson(path: String, queryItems: [URLQueryItem], url: URL, accessToken: String) async throws -> [String: Any] {
        let response = try await httpClient.get(url: url, headers: authHeaders(accessToken))
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

private func resolvedPercent(position: Int?, duration: Int?) -> Double? {
    guard let position, let duration, duration > 0 else { return nil }
    return min(max(Double(position) / Double(duration) * 100.0, 0), 100)
}
