import Foundation

public enum AddonUrlResult {
    case valid(String)
    case invalid
}

public func normalizeAddonUrl(_ raw: String) -> AddonUrlResult {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    guard trimmed.lowercased().hasPrefix("https://") else {
        return .invalid
    }
    let lower = trimmed.lowercased()
    let withoutFragment = lower.split(separator: "#").first.map(String.init) ?? lower
    let noTrailingSlash = withoutFragment.hasSuffix("/")
        ? String(withoutFragment.dropLast())
        : withoutFragment
    let afterHost = noTrailingSlash.dropFirst("https://".count)
    let path = afterHost.split(separator: "?").first.map(String.init) ?? String(afterHost)
    let segments = path.split(separator: "/").filter { !$0.isEmpty }
    guard let last = segments.last, last.hasSuffix(".json") else {
        return .invalid
    }
    return .valid(noTrailingSlash)
}
