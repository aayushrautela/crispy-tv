import Foundation

/// The HTTP the stream resolver fetches with.
///
/// The seam exists so the resolver can be tested without a network: the tests
/// hand it a transport that answers from a table, and the app hands it the
/// app's own HTTP client.
public protocol StreamHttpFetching: Sendable {
    func get(url: URL, headers: [String: String], timeout: TimeInterval?) async throws -> CrispyHttpResponse
}

extension CrispyHttpClient: StreamHttpFetching {}

/// Resolves the streams an account's addons offer for one item.
///
/// This is the addon's own protocol, the one Stremio addons speak: the addon
/// publishes a manifest, the manifest says which resources it serves, and the
/// stream resource answers with the files it has for an id. The Android app
/// resolves the same way, against the same account's addon list — the difference
/// is only that the iOS app reads that list from the backend instead of from a
/// local registry it keeps in sync with the backend.
public struct AddonStreamsClient {
    private let http: StreamHttpFetching
    private let manifestTimeout: TimeInterval
    private let streamTimeout: TimeInterval

    public init(
        http: StreamHttpFetching,
        manifestTimeout: TimeInterval = 6,
        streamTimeout: TimeInterval = 10
    ) {
        self.http = http
        self.manifestTimeout = manifestTimeout
        self.streamTimeout = streamTimeout
    }

    /// The streams the addons offer for one item, best first.
    ///
    /// The addons are asked in parallel and their answers are merged in the
    /// account's addon order, so the same account and the same addons always
    /// produce the same list.
    ///
    /// Only **direct** streams come back: a stream with an info hash is a
    /// torrent, and iOS has no torrent engine, so offering it would offer
    /// something the player cannot open.
    public func resolveStreams(
        addons: [AddonDescriptor],
        mediaType: StreamMediaType,
        lookupId: String
    ) async -> [StreamSource] {
        let trimmedLookupId = lookupId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedLookupId.isEmpty, !addons.isEmpty else { return [] }
        let encodedLookupId = StreamLookup.encodePathSegment(trimmedLookupId)
        // The task group's closures are `@Sendable`, so everything they read
        // has to be a value rather than this type's own properties.
        let http = http
        let manifestTimeout = manifestTimeout
        let streamTimeout = streamTimeout

        var answers: [(Int, [StreamSource])] = []
        await withTaskGroup(of: (Int, [StreamSource]).self) { group in
            for (index, addon) in addons.enumerated() {
                group.addTask {
                    let streams = await AddonStreamsClient.streams(
                        for: addon,
                        http: http,
                        mediaType: mediaType,
                        encodedLookupId: encodedLookupId,
                        manifestTimeout: manifestTimeout,
                        streamTimeout: streamTimeout
                    )
                    return (index, streams)
                }
            }
            for await answer in group {
                answers.append(answer)
            }
        }

        let merged = answers.sorted { $0.0 < $1.0 }.flatMap { $0.1 }
        return merged.sorted { lhs, rhs in
            let left = lhs.qualityHeight ?? 0
            let right = rhs.qualityHeight ?? 0
            if left != right { return left > right }
            return lhs.title.localizedCaseInsensitiveCompare(rhs.title) == .orderedAscending
        }
    }

    /// The streams of one addon.
    private static func streams(
        for addon: AddonDescriptor,
        http: StreamHttpFetching,
        mediaType: StreamMediaType,
        encodedLookupId: String,
        manifestTimeout: TimeInterval,
        streamTimeout: TimeInterval
    ) async -> [StreamSource] {
        guard let manifestURL = URL(string: addon.manifestUrl) else { return [] }
        let manifests = AddonManifestClient(http: http, timeout: manifestTimeout)
        guard let manifest = await manifests.fetchManifest(url: manifestURL) else { return [] }
        guard let support = StreamSupport.parse(manifest: manifest, manifestURL: manifestURL, addon: addon) else { return [] }
        guard support.supports(mediaType: mediaType, lookupId: encodedLookupId) else { return [] }
        guard let resourceURL = support.resourceURL(mediaType: mediaType, encodedLookupId: encodedLookupId) else { return [] }
        guard let payload = await fetchJSON(url: resourceURL, http: http, timeout: streamTimeout) else { return [] }
        return parseStreams(payload, support: support)
    }

    private static func fetchJSON(
        url: URL,
        http: StreamHttpFetching,
        timeout: TimeInterval
    ) async -> [String: Any]? {
        guard let response = try? await http.get(url: url, headers: AddonManifestClient.requestHeaders, timeout: timeout) else {
            return nil
        }
        guard (200..<300).contains(response.code) else { return nil }
        return try? JsonParser.parseObject(response.body)
    }

    private static func parseStreams(_ payload: [String: Any], support: StreamSupport) -> [StreamSource] {
        payload.jsonArray("streams").compactMap { stream in
            guard let url = stream.jsonString("url") else { return nil }
            let name = stream.jsonString("name") ?? support.providerName
            let title = stream.jsonString("title") ?? name
            let hints = stream.jsonObject("behaviorHints")
            let headers = hints?.jsonStringMap("headers") ?? [:]
            let subtitles = stream.jsonArray("subtitles").compactMap { subtitle -> StreamSubtitleOption? in
                guard let subtitleURL = subtitle.jsonString("url") else { return nil }
                return StreamSubtitleOption(
                    url: subtitleURL,
                    language: subtitle.jsonString("lang") ?? subtitle.jsonString("language"),
                    name: subtitle.jsonString("name") ?? subtitle.jsonString("title")
                )
            }
            return StreamSource(
                id: "\(support.providerId)|\(name)|\(title)|\(url)",
                addonId: support.providerId,
                addonName: support.providerName,
                name: name,
                title: title,
                url: url,
                infoHash: stream.jsonString("infoHash"),
                fileIdx: stream.jsonInt("fileIdx"),
                headers: headers,
                subtitles: subtitles
            )
        }
    }
}

/// The lookup id, and the encoding its path segment needs.
public enum StreamLookup {
    /// The id an addon knows an item by.
    ///
    /// An addon's ids are the IMDb id for a movie, and the IMDb id with the
    /// season and the episode for an episode. The backend's own item id is not
    /// an addon's id, so it is only used when it is an IMDb id.
    public static func lookupId(imdbId: String?, itemId: String?, season: Int?, episode: Int?) -> String? {
        let imdb = imdbId?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let imdb, imdb.lowercased().hasPrefix("tt") {
            guard let season, season > 0, let episode, episode > 0 else { return imdb }
            return "\(imdb):\(season):\(episode)"
        }
        let item = itemId?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let item, item.lowercased().hasPrefix("tt") {
            return item
        }
        return nil
    }

    /// The percent-encoding a path segment needs.
    ///
    /// Only the unreserved characters survive; everything else — the colons of
    /// an episode id, for one — is percent-encoded, byte for byte.
    public static func encodePathSegment(_ value: String) -> String {
        let unreserved = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        )
        return value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
    }
}

/// What an addon's manifest says about the streams it serves.
private struct StreamSupport {
    let providerId: String
    let providerName: String
    let types: Set<StreamMediaType>
    let idPrefixes: [String]
    let baseUrl: String
    let encodedQuery: String?

    /// Reads the manifest, or returns nil when the addon serves no streams.
    ///
    /// A manifest with no `resources` at all is taken to serve everything,
    /// which is what the protocol says and what the Android client does.
    static func parse(
        manifest: AddonManifest,
        manifestURL: URL,
        addon: AddonDescriptor
    ) -> StreamSupport? {
        guard let base = baseUrl(for: manifestURL) else { return nil }
        // The query is kept as it was written: a decoded query would lose the
        // separators of a signed URL.
        let encodedQuery = URLComponents(url: manifestURL, resolvingAgainstBaseURL: false)?.percentEncodedQuery

        let providerId = manifest.id ?? addon.id
        let providerName = manifest.name ?? (addon.name.isEmpty ? providerId : addon.name)

        let manifestTypes = parseTypes(manifest.types)
        let defaultTypes = manifestTypes.isEmpty ? Set(StreamMediaType.allCases) : manifestTypes
        let defaultPrefixes = manifest.idPrefixes

        var supportedTypes = Set<StreamMediaType>()
        var supportedPrefixes: [String] = []
        var declaresStreams = false

        if manifest.resources.isEmpty {
            declaresStreams = true
            supportedTypes = defaultTypes
            supportedPrefixes = defaultPrefixes
        } else {
            // A resource that is a bare name narrows nothing, so it keeps the
            // manifest's own types and prefixes.
            for resource in manifest.resources where resource.name.lowercased() == "stream" {
                declaresStreams = true
                let types = parseTypes(resource.types)
                supportedTypes.formUnion(types.isEmpty ? defaultTypes : types)
                supportedPrefixes.append(contentsOf: resource.idPrefixes.isEmpty ? defaultPrefixes : resource.idPrefixes)
            }
        }

        guard declaresStreams else { return nil }

        return StreamSupport(
            providerId: providerId,
            providerName: providerName,
            types: supportedTypes.isEmpty ? defaultTypes : supportedTypes,
            idPrefixes: supportedPrefixes,
            baseUrl: base,
            encodedQuery: encodedQuery
        )
    }

    /// Whether the addon serves this kind of media for this id.
    func supports(mediaType: StreamMediaType, lookupId: String) -> Bool {
        guard types.contains(mediaType) else { return false }
        guard !idPrefixes.isEmpty else { return true }
        return idPrefixes.contains { prefix in
            lookupId.lowercased().hasPrefix(prefix.lowercased())
        }
    }

    /// The URL the addon's stream resource lives at.
    func resourceURL(mediaType: StreamMediaType, encodedLookupId: String) -> URL? {
        var components = URLComponents(string: baseUrl)
        var segments = components?.percentEncodedPath.split(separator: "/").map(String.init) ?? []
        segments.append("stream")
        segments.append(mediaType.apiPath)
        segments.append("\(encodedLookupId).json")
        components?.percentEncodedPath = "/" + segments.joined(separator: "/")
        if let encodedQuery, !encodedQuery.isEmpty {
            components?.percentEncodedQuery = encodedQuery
        }
        return components?.url
    }

    /// The base the addon's resources sit at: the manifest's own URL without
    /// its `manifest.json` and without its query, which the resource URL keeps.
    private static func baseUrl(for manifestURL: URL) -> String? {
        guard var components = URLComponents(url: manifestURL, resolvingAgainstBaseURL: false) else { return nil }
        var segments = components.percentEncodedPath.split(separator: "/").map(String.init)
        if let last = segments.last, last.lowercased() == "manifest.json" {
            segments.removeLast()
        }
        components.percentEncodedPath = "/" + segments.joined(separator: "/")
        components.percentEncodedQuery = nil
        components.fragment = nil
        return components.url?.absoluteString
    }

    private static func parseTypes(_ values: [String]) -> Set<StreamMediaType> {
        var types = Set<StreamMediaType>()
        for value in values {
            switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "movie": types.insert(.movie)
            case "series", "show", "tv": types.insert(.series)
            case "anime": types.insert(.anime)
            default: break
            }
        }
        return types
    }
}
