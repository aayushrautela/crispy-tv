import Foundation

/// The seam an addon's manifest is fetched through.
///
/// The screen that installs an addon reads the manifest before it installs
/// anything, and a test cannot make that read happen over a network, so the
/// fetch sits behind this one method.
public protocol AddonManifestFetching {
    func fetchManifest(url: URL) async -> AddonManifest?
}

/// Fetches an addon's manifest, the same way the stream resolver does.
public struct AddonManifestClient: AddonManifestFetching {
    private let http: StreamHttpFetching
    private let timeout: TimeInterval

    public init(http: StreamHttpFetching, timeout: TimeInterval = 6) {
        self.http = http
        self.timeout = timeout
    }

    /// The manifest at the URL, or nil when there is nothing readable there.
    ///
    /// A manifest that cannot be fetched and a manifest that cannot be read are
    /// the same answer to the caller: there is no manifest to install, and the
    /// URL is the thing to try again.
    public func fetchManifest(url: URL) async -> AddonManifest? {
        guard let response = try? await http.get(url: url, headers: Self.requestHeaders, timeout: timeout) else {
            return nil
        }
        guard (200..<300).contains(response.code) else { return nil }
        guard let json = try? JsonParser.parseObject(response.body) else { return nil }
        return AddonManifest.parse(json)
    }

    /// The headers an addon is asked with — the same ones the stream resolver
    /// sends, because it is the same request to a different resource.
    static let requestHeaders: [String: String] = [
        "Accept": "application/json",
        "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
    ]
}
