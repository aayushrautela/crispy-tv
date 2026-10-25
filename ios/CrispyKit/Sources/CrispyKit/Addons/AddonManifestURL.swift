import Foundation

/// The rules that turn what a user typed into a manifest URL.
///
/// An addon is installed by its manifest URL, and a user rarely has one to
/// paste: they have a share link, or a host, or a configure page. The rules are
/// pure, so they are also the part of the addon screen worth testing.
public enum AddonManifestURL {
    /// The manifest URL a user meant, or nil when what they typed cannot be one.
    ///
    /// A bare host is taken as `https://`, the `stremio://` scheme a share link
    /// uses becomes `https://`, and a path that does not already end in the
    /// manifest's own file name gets it appended — the manifest is the addon
    /// protocol's entry point, so a URL that names an addon names it.
    public static func normalize(_ raw: String) -> String? {
        var value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }
        if value.lowercased().hasPrefix("stremio://") {
            value = "https://" + value.dropFirst("stremio://".count)
        } else if !hasAuthorityScheme(value) {
            value = "https://" + value
        }
        guard var components = URLComponents(string: value), components.host?.nilIfBlank != nil else { return nil }
        var segments = components.percentEncodedPath.split(separator: "/").map(String.init)
        if segments.last?.lowercased() != "manifest.json" {
            segments.append("manifest.json")
        }
        components.percentEncodedPath = "/" + segments.joined(separator: "/")
        if components.percentEncodedQuery?.isEmpty == true {
            components.percentEncodedQuery = nil
        }
        return components.url?.absoluteString
    }

    /// The id an addon is known by when its manifest did not say: its host.
    public static func addonId(of manifestUrl: String) -> String? {
        guard let normalized = normalize(manifestUrl) else { return nil }
        return URLComponents(string: normalized)?.host?.nilIfBlank
    }

    /// Whether two manifest URLs name the same addon.
    ///
    /// Case is the only difference worth forgiving: an addon's own URL is
    /// otherwise the identity of its installation.
    public static func matches(_ left: String, _ right: String) -> Bool {
        let lhs = left.trimmingCharacters(in: .whitespacesAndNewlines)
        let rhs = right.trimmingCharacters(in: .whitespacesAndNewlines)
        return lhs.caseInsensitiveCompare(rhs) == .orderedSame
    }

    /// The directory an addon's own files sit in: its manifest URL without the
    /// manifest's file name, without the query and without the fragment.
    public static func base(of manifestUrl: String) -> String? {
        let trimmed = manifestUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: trimmed) else { return nil }
        var segments = components.percentEncodedPath.split(separator: "/").map(String.init)
        if segments.last?.lowercased() == "manifest.json" {
            segments.removeLast()
        }
        components.percentEncodedPath = "/" + segments.joined(separator: "/")
        components.percentEncodedQuery = nil
        components.fragment = nil
        return components.url?.absoluteString
    }

    /// The absolute URL an asset the manifest named (its logo, one day its
    /// images) is fetched from.
    ///
    /// A manifest names its assets the way HTML does: an absolute URL, a
    /// protocol-relative one, a root-relative one, or a path beside the
    /// manifest.
    public static func resolveAsset(base: String, raw: String) -> String? {
        let asset = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !asset.isEmpty else { return nil }
        if hasSchemePrefix(asset) { return asset }
        if asset.hasPrefix("//") {
            guard let scheme = URLComponents(string: base)?.scheme else { return nil }
            return scheme + ":" + asset
        }
        if asset.hasPrefix("/") {
            guard let components = URLComponents(string: base), let scheme = components.scheme, let host = components.host else {
                return nil
            }
            let port = components.port.map { ":\($0)" } ?? ""
            return scheme + "://" + host + port + asset
        }
        var directory = base
        while directory.hasSuffix("/") {
            directory.removeLast()
        }
        return directory.isEmpty ? asset : directory + "/" + asset
    }

    /// Whether the value starts with a scheme followed by `://`.
    private static func hasAuthorityScheme(_ value: String) -> Bool {
        guard let colon = value.firstIndex(of: ":") else { return false }
        return value[value.index(after: colon)...].hasPrefix("//") && hasSchemePrefix(value)
    }

    /// Whether the value starts with a scheme, whether or not an authority
    /// follows. An asset may be a `data:` URL, which has no authority.
    private static func hasSchemePrefix(_ value: String) -> Bool {
        guard let colon = value.firstIndex(of: ":") else { return false }
        let scheme = value[value.startIndex..<colon]
        guard let first = scheme.first, first.isLetter else { return false }
        return scheme.dropFirst().allSatisfy { character in
            character.isLetter || character.isNumber || character == "+" || character == "." || character == "-"
        }
    }
}
