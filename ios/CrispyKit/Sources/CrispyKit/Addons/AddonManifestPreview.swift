import Foundation

/// What the user is about to install, read from the manifest they pointed at.
///
/// Installing is two steps — read the manifest, then decide — because the URL
/// alone does not say what the addon is. The preview is that second step's whole
/// input.
public struct AddonManifestPreview: Equatable, Sendable {
    /// The manifest URL as it will be installed, already normalized.
    public let manifestUrl: String
    public let addonId: String?
    public let name: String
    public let description: String
    public let version: String?
    /// The addon's logo, resolved against the manifest's own directory.
    public let logoUrl: String?
    public let resources: [String]
    public let types: [String]
    /// What the manifest is missing or should not have, in the order to show it.
    public let warnings: [String]

    public init(
        manifestUrl: String,
        addonId: String?,
        name: String,
        description: String,
        version: String?,
        logoUrl: String?,
        resources: [String],
        types: [String],
        warnings: [String]
    ) {
        self.manifestUrl = manifestUrl
        self.addonId = addonId
        self.name = name
        self.description = description
        self.version = version
        self.logoUrl = logoUrl
        self.resources = resources
        self.types = types
        self.warnings = warnings
    }

    /// Reads a manifest into what the install card shows.
    public static func make(manifestUrl: String, manifest: AddonManifest) -> AddonManifestPreview {
        let addonId = manifest.id
        var warnings: [String] = []
        if manifestUrl.lowercased().hasPrefix("http://") {
            warnings.append("This addon uses an insecure HTTP URL.")
        }
        if addonId == nil {
            warnings.append("Manifest is missing a stable addon id.")
        }
        if manifest.resources.isEmpty {
            warnings.append("Manifest does not list addon resources.")
        }
        if manifest.types.isEmpty {
            warnings.append("Manifest does not declare supported media types.")
        }
        let base = AddonManifestURL.base(of: manifestUrl) ?? manifestUrl
        return AddonManifestPreview(
            manifestUrl: manifestUrl,
            addonId: addonId,
            name: manifest.name ?? addonId ?? "Unknown addon",
            description: manifest.description ?? manifestUrl,
            version: manifest.version,
            logoUrl: AddonManifestURL.resolveAsset(base: base, raw: manifest.logo ?? ""),
            resources: manifest.resourceNames,
            types: manifest.types,
            warnings: warnings
        )
    }
}
