import Foundation

public enum AccountContracts {
    public static let contractVersion: Int = 1
    public static let supportedSyncProviders: Set<String> = ["trakt", "simkl"]
    public static let oauthCallbackScheme = "crispytv"
    public static let oauthCallbackHost = "oauth-callback"

    public static let avatarCatalogPath = "/v1/avatars"
    public static let avatarCount = 20
}

/// Built-in avatar catalog. Profiles select one of these ids (e.g. "avatar_03");
/// the PNG is served at GET /v1/avatars/:id. Avatars are never remote URLs.
public let SUPPORTED_AVATAR_IDS: [String] = (1...AccountContracts.avatarCount).map {
    String(format: "avatar_%02d", $0)
}

public func isSupportedAvatarId(_ value: String?) -> Bool {
    guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
        return false
    }
    return SUPPORTED_AVATAR_IDS.contains(value)
}

public func builtInAvatarUrl(baseUrl: String, id: String) -> String {
    let trimmedBase = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")).trimmingCharacters(in: .whitespaces)
    let trimmedId = id.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    return "\(trimmedBase)\(AccountContracts.avatarCatalogPath)/\(trimmedId)"
}

public struct SupportedLanguage: Equatable {
    public let code: String
    public let name: String
}

public let SUPPORTED_LANGUAGES: [SupportedLanguage] = [
    .init(code: "en", name: "English"),
    .init(code: "es", name: "Spanish"),
    .init(code: "fr", name: "French"),
    .init(code: "de", name: "German"),
    .init(code: "it", name: "Italian"),
    .init(code: "pt", name: "Portuguese"),
    .init(code: "pt-BR", name: "Portuguese (Brazil)"),
    .init(code: "nl", name: "Dutch"),
    .init(code: "ru", name: "Russian"),
    .init(code: "pl", name: "Polish"),
    .init(code: "tr", name: "Turkish"),
    .init(code: "ar", name: "Arabic"),
    .init(code: "hi", name: "Hindi"),
    .init(code: "bn", name: "Bengali"),
    .init(code: "ta", name: "Tamil"),
    .init(code: "te", name: "Telugu"),
    .init(code: "mr", name: "Marathi"),
    .init(code: "ja", name: "Japanese"),
    .init(code: "ko", name: "Korean"),
    .init(code: "zh", name: "Chinese"),
    .init(code: "zh-CN", name: "Chinese (Simplified)"),
    .init(code: "zh-TW", name: "Chinese (Traditional)"),
    .init(code: "th", name: "Thai"),
    .init(code: "vi", name: "Vietnamese"),
    .init(code: "id", name: "Indonesian"),
    .init(code: "ms", name: "Malay"),
    .init(code: "sv", name: "Swedish"),
    .init(code: "no", name: "Norwegian"),
    .init(code: "da", name: "Danish"),
    .init(code: "fi", name: "Finnish"),
    .init(code: "cs", name: "Czech"),
    .init(code: "sk", name: "Slovak"),
    .init(code: "hu", name: "Hungarian"),
    .init(code: "ro", name: "Romanian"),
    .init(code: "uk", name: "Ukrainian"),
    .init(code: "el", name: "Greek"),
    .init(code: "he", name: "Hebrew"),
    .init(code: "fa", name: "Persian"),
    .init(code: "ur", name: "Urdu"),
]

public func normalizeLanguageCode(_ value: String?) -> String? {
    guard let value else { return nil }
    let candidate = value.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "_", with: "-")
    guard !candidate.isEmpty else { return nil }
    return SUPPORTED_LANGUAGES.first { $0.code.lowercased() == candidate.lowercased() }?.code
}

public struct SupportedCountry: Equatable {
    public let code: String
    public let name: String
}

public let SUPPORTED_COUNTRIES: [SupportedCountry] = [
    .init(code: "US", name: "United States"),
    .init(code: "CA", name: "Canada"),
    .init(code: "MX", name: "Mexico"),
    .init(code: "BR", name: "Brazil"),
    .init(code: "AR", name: "Argentina"),
    .init(code: "CL", name: "Chile"),
    .init(code: "CO", name: "Colombia"),
    .init(code: "PE", name: "Peru"),
    .init(code: "GB", name: "United Kingdom"),
    .init(code: "IE", name: "Ireland"),
    .init(code: "FR", name: "France"),
    .init(code: "ES", name: "Spain"),
    .init(code: "PT", name: "Portugal"),
    .init(code: "IT", name: "Italy"),
    .init(code: "DE", name: "Germany"),
    .init(code: "AT", name: "Austria"),
    .init(code: "CH", name: "Switzerland"),
    .init(code: "NL", name: "Netherlands"),
    .init(code: "BE", name: "Belgium"),
    .init(code: "LU", name: "Luxembourg"),
    .init(code: "SE", name: "Sweden"),
    .init(code: "NO", name: "Norway"),
    .init(code: "DK", name: "Denmark"),
    .init(code: "FI", name: "Finland"),
    .init(code: "IS", name: "Iceland"),
    .init(code: "PL", name: "Poland"),
    .init(code: "CZ", name: "Czechia"),
    .init(code: "SK", name: "Slovakia"),
    .init(code: "HU", name: "Hungary"),
    .init(code: "RO", name: "Romania"),
    .init(code: "BG", name: "Bulgaria"),
    .init(code: "HR", name: "Croatia"),
    .init(code: "RS", name: "Serbia"),
    .init(code: "SI", name: "Slovenia"),
    .init(code: "GR", name: "Greece"),
    .init(code: "TR", name: "Turkey"),
    .init(code: "UA", name: "Ukraine"),
    .init(code: "RU", name: "Russia"),
    .init(code: "LV", name: "Latvia"),
    .init(code: "LT", name: "Lithuania"),
    .init(code: "EE", name: "Estonia"),
    .init(code: "IN", name: "India"),
    .init(code: "PK", name: "Pakistan"),
    .init(code: "BD", name: "Bangladesh"),
    .init(code: "LK", name: "Sri Lanka"),
    .init(code: "NP", name: "Nepal"),
    .init(code: "AE", name: "United Arab Emirates"),
    .init(code: "SA", name: "Saudi Arabia"),
    .init(code: "QA", name: "Qatar"),
    .init(code: "KW", name: "Kuwait"),
    .init(code: "BH", name: "Bahrain"),
    .init(code: "OM", name: "Oman"),
    .init(code: "JO", name: "Jordan"),
    .init(code: "LB", name: "Lebanon"),
    .init(code: "TH", name: "Thailand"),
    .init(code: "VN", name: "Vietnam"),
    .init(code: "PH", name: "Philippines"),
    .init(code: "ID", name: "Indonesia"),
    .init(code: "AU", name: "Australia"),
    .init(code: "NZ", name: "New Zealand"),
]

public func normalizeCountryCode(_ value: String?) -> String? {
    guard let value else { return nil }
    let candidate = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !candidate.isEmpty else { return nil }
    let upper = candidate.uppercased()
    return SUPPORTED_COUNTRIES.first { $0.code == upper }?.code
}
