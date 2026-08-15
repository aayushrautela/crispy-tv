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
    if let id = normalizedAvatarUrl, !isSupportedAvatarId(id) {
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
