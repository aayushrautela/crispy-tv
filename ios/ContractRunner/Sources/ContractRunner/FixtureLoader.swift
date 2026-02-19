import Foundation

public enum FixtureLoader {
    public static func repositoryRoot() throws -> URL {
        if let explicit = ProcessInfo.processInfo.environment["REPO_ROOT"] {
            return URL(fileURLWithPath: explicit)
        }

        var current = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)

        for _ in 0..<8 {
            if FileManager.default.fileExists(atPath: current.appendingPathComponent("settings.gradle.kts").path) {
                return current
            }
            current.deleteLastPathComponent()
        }

        throw NSError(
            domain: "FixtureLoader",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "Could not locate repository root"]
        )
    }

    public static func listFixtureFiles(in subdirectory: String) throws -> [URL] {
        let root = try repositoryRoot()
        let fixturesRoot = root
            .appendingPathComponent("contracts")
            .appendingPathComponent("fixtures")
            .appendingPathComponent(subdirectory)

        guard FileManager.default.fileExists(atPath: fixturesRoot.path) else {
            return []
        }

        let enumerator = FileManager.default.enumerator(at: fixturesRoot, includingPropertiesForKeys: nil)
        var urls: [URL] = []

        while let next = enumerator?.nextObject() as? URL {
            if next.pathExtension == "json" {
                urls.append(next)
            }
        }

        return urls.sorted(by: { $0.path < $1.path })
    }

    public static func readJSONObject(from url: URL) throws -> [String: Any] {
        let data = try Data(contentsOf: url)
        let object = try JSONSerialization.jsonObject(with: data, options: [])
        guard let dict = object as? [String: Any] else {
            throw NSError(
                domain: "FixtureLoader",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "Fixture root must be a JSON object: \(url.path)"]
            )
        }
        return dict
    }
}
