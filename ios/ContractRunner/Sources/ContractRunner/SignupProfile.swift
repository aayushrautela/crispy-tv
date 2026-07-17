import Foundation

public struct SignupProfileResult {
    public let isComplete: Bool
    public let missing: [String]
    public let normalizedName: String?
    public let normalizedLanguage: String?
    public let normalizedRegion: String?
    public let normalizedAvatarUrl: String?

    public init(
        isComplete: Bool,
        missing: [String],
        normalizedName: String?,
        normalizedLanguage: String?,
        normalizedRegion: String?,
        normalizedAvatarUrl: String?
    ) {
        self.isComplete = isComplete
        self.missing = missing
        self.normalizedName = normalizedName
        self.normalizedLanguage = normalizedLanguage
        self.normalizedRegion = normalizedRegion
        self.normalizedAvatarUrl = normalizedAvatarUrl
    }
}

public func validateSignupProfile(
    rawName: String,
    rawLanguage: String?,
    rawRegion: String?,
    rawAvatarUrl: String?
) -> SignupProfileResult {
    var missing: [String] = []

    let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
    let normalizedName = name.isEmpty ? nil : name
    if normalizedName == nil {
        missing.append("name")
    }

    let normalizedLanguage = rawLanguage.flatMap { normalizeLanguageCode($0) }
    if normalizedLanguage == nil {
        missing.append("interfaceLanguage")
    }

    let normalizedRegion = rawRegion.flatMap { normalizeCountryCode($0) }

    let trimmedAvatar = rawAvatarUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
    let normalizedAvatarUrl = trimmedAvatar?.isEmpty == false ? trimmedAvatar! : nil
    if let url = normalizedAvatarUrl, !isValidSignupAvatarUrl(url) {
        missing.append("avatarUrl")
    }

    let avatarResult = (missing.contains("avatarUrl")) ? nil : normalizedAvatarUrl

    return SignupProfileResult(
        isComplete: missing.isEmpty,
        missing: missing,
        normalizedName: normalizedName,
        normalizedLanguage: normalizedLanguage,
        normalizedRegion: normalizedRegion,
        normalizedAvatarUrl: avatarResult
    )
}

public func isValidSignupAvatarUrl(_ url: String) -> Bool {
    guard url.lowercased().hasPrefix("https://") else { return false }
    guard let parsed = URL(string: url) else { return false }
    guard let host = parsed.host, host.lowercased() == "api.dicebear.com" else { return false }
    let segments = parsed.path.split(separator: "/").filter { !$0.isEmpty }
    guard segments.count == 3 else { return false }
    let version = String(segments[0])
    let style = String(segments[1])
    let format = String(segments[2]).lowercased()
    guard version.lowercased() == "v9" else { return false }
    guard ["svg", "png", "webp", "avif"].contains(format) else { return false }
    return SUPPORTED_DICEBEAR_STYLES.contains { $0.apiValue.lowercased() == style.lowercased() }
}
