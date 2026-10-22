import Foundation

/// One resource an addon's manifest declares.
///
/// The addon protocol writes a resource either as a bare name or as an object
/// that narrows the name with the media types and the id prefixes it serves.
/// Reading both shapes is one rule, so it lives here and both the stream
/// resolver and the install preview read it from here.
public struct AddonManifestResource: Equatable, Sendable {
    public let name: String
    public let types: [String]
    public let idPrefixes: [String]

    public init(name: String, types: [String] = [], idPrefixes: [String] = []) {
        self.name = name
        self.types = types
        self.idPrefixes = idPrefixes
    }
}

/// The parts of an addon's manifest the app reads.
public struct AddonManifest: Equatable, Sendable {
    public let id: String?
    public let name: String?
    public let description: String?
    public let version: String?
    public let logo: String?
    public let types: [String]
    public let idPrefixes: [String]
    public let resources: [AddonManifestResource]

    public init(
        id: String? = nil,
        name: String? = nil,
        description: String? = nil,
        version: String? = nil,
        logo: String? = nil,
        types: [String] = [],
        idPrefixes: [String] = [],
        resources: [AddonManifestResource] = []
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.version = version
        self.logo = logo
        self.types = types
        self.idPrefixes = idPrefixes
        self.resources = resources
    }

    /// The resource names, in the order the manifest wrote them and each once.
    ///
    /// A manifest may narrow one resource with several declarations; the preview
    /// shows the name once, while the resolver still reads every declaration.
    public var resourceNames: [String] {
        var names: [String] = []
        for resource in resources where !names.contains(resource.name) {
            names.append(resource.name)
        }
        return names
    }

    /// Reads a manifest leniently: a missing or mistyped field is absent, never
    /// a failure. A manifest is a description of an addon, not a contract the
    /// app may refuse to read.
    public static func parse(_ json: [String: Any]) -> AddonManifest {
        AddonManifest(
            id: json.jsonString("id"),
            name: json.jsonString("name"),
            description: json.jsonString("description"),
            version: json.jsonString("version"),
            logo: json.jsonString("logo"),
            types: json.jsonStringList("types"),
            idPrefixes: json.jsonStringList("idPrefixes"),
            resources: parseResources(json["resources"])
        )
    }

    /// Reads `resources` in both of the shapes the protocol allows.
    ///
    /// A JSON string is a resource name; a JSON object is a name with the types
    /// and the id prefixes it serves; anything else — a number, a boolean — is
    /// not a resource and is dropped.
    private static func parseResources(_ value: Any?) -> [AddonManifestResource] {
        guard let array = value as? [Any] else { return [] }
        var resources: [AddonManifestResource] = []
        for element in array {
            if let name = element as? String {
                if let name = name.nilIfBlank {
                    resources.append(AddonManifestResource(name: name))
                }
                continue
            }
            guard let object = element as? [String: Any], let name = object.jsonString("name") else { continue }
            resources.append(
                AddonManifestResource(
                    name: name,
                    types: object.jsonStringList("types"),
                    idPrefixes: object.jsonStringList("idPrefixes")
                )
            )
        }
        return resources
    }
}
