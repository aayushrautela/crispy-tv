package com.crispy.tv.domain.account

object AccountContracts {
    const val CONTRACT_VERSION = 1
    const val OAUTH_CALLBACK_SCHEME = "crispytv"
    const val OAUTH_CALLBACK_HOST = "oauth-callback"

    val SUPPORTED_SYNC_PROVIDERS = setOf("trakt", "simkl")

    const val AVATAR_CATALOG_PATH = "/v1/avatars"
    const val AVATAR_COUNT = 20
}

/**
 * Built-in avatar catalog. Profile avatars are selected from this fixed set and
 * stored as an id (e.g. "avatar_03"); the PNG is served at
 * GET /v1/avatars/:id. Avatars are never remote URLs — see server
 * src/modules/profiles/avatars.ts.
 */
val SUPPORTED_AVATAR_IDS: List<String> =
    (1..AVATAR_COUNT).map { "avatar_%02d".format(it) }

fun isSupportedAvatarId(value: String?): Boolean {
    if (value == null) return false
    val candidate = value.trim()
    return candidate.isNotEmpty() && SUPPORTED_AVATAR_IDS.contains(candidate)
}

fun builtInAvatarUrl(baseUrl: String, id: String): String {
    val trimmedBase = baseUrl.trim().trimEnd('/')
    val trimmedId = id.trim().trimStart('/')
    return "$trimmedBase$AVATAR_CATALOG_PATH/$trimmedId"
}

data class SupportedLanguage(
    val code: String,
    val name: String,
)

val SUPPORTED_LANGUAGES: List<SupportedLanguage> = listOf(
    SupportedLanguage("en", "English"),
    SupportedLanguage("es", "Spanish"),
    SupportedLanguage("fr", "French"),
    SupportedLanguage("de", "German"),
    SupportedLanguage("it", "Italian"),
    SupportedLanguage("pt", "Portuguese"),
    SupportedLanguage("pt-BR", "Portuguese (Brazil)"),
    SupportedLanguage("nl", "Dutch"),
    SupportedLanguage("ru", "Russian"),
    SupportedLanguage("pl", "Polish"),
    SupportedLanguage("tr", "Turkish"),
    SupportedLanguage("ar", "Arabic"),
    SupportedLanguage("hi", "Hindi"),
    SupportedLanguage("bn", "Bengali"),
    SupportedLanguage("ta", "Tamil"),
    SupportedLanguage("te", "Telugu"),
    SupportedLanguage("mr", "Marathi"),
    SupportedLanguage("ja", "Japanese"),
    SupportedLanguage("ko", "Korean"),
    SupportedLanguage("zh", "Chinese"),
    SupportedLanguage("zh-CN", "Chinese (Simplified)"),
    SupportedLanguage("zh-TW", "Chinese (Traditional)"),
    SupportedLanguage("th", "Thai"),
    SupportedLanguage("vi", "Vietnamese"),
    SupportedLanguage("id", "Indonesian"),
    SupportedLanguage("ms", "Malay"),
    SupportedLanguage("sv", "Swedish"),
    SupportedLanguage("no", "Norwegian"),
    SupportedLanguage("da", "Danish"),
    SupportedLanguage("fi", "Finnish"),
    SupportedLanguage("cs", "Czech"),
    SupportedLanguage("sk", "Slovak"),
    SupportedLanguage("hu", "Hungarian"),
    SupportedLanguage("ro", "Romanian"),
    SupportedLanguage("uk", "Ukrainian"),
    SupportedLanguage("el", "Greek"),
    SupportedLanguage("he", "Hebrew"),
    SupportedLanguage("fa", "Persian"),
    SupportedLanguage("ur", "Urdu"),
)

fun normalizeLanguageCode(value: String?): String? {
    if (value == null) return null
    val candidate = value.trim().replace('_', '-')
    if (candidate.isEmpty()) return null
    return SUPPORTED_LANGUAGES.firstOrNull { it.code.equals(candidate, ignoreCase = true) }?.code
}

data class SupportedCountry(
    val code: String,
    val name: String,
)

val SUPPORTED_COUNTRIES: List<SupportedCountry> = listOf(
    SupportedCountry("US", "United States"),
    SupportedCountry("CA", "Canada"),
    SupportedCountry("MX", "Mexico"),
    SupportedCountry("BR", "Brazil"),
    SupportedCountry("AR", "Argentina"),
    SupportedCountry("CL", "Chile"),
    SupportedCountry("CO", "Colombia"),
    SupportedCountry("PE", "Peru"),
    SupportedCountry("GB", "United Kingdom"),
    SupportedCountry("IE", "Ireland"),
    SupportedCountry("FR", "France"),
    SupportedCountry("ES", "Spain"),
    SupportedCountry("PT", "Portugal"),
    SupportedCountry("IT", "Italy"),
    SupportedCountry("DE", "Germany"),
    SupportedCountry("AT", "Austria"),
    SupportedCountry("CH", "Switzerland"),
    SupportedCountry("NL", "Netherlands"),
    SupportedCountry("BE", "Belgium"),
    SupportedCountry("LU", "Luxembourg"),
    SupportedCountry("SE", "Sweden"),
    SupportedCountry("NO", "Norway"),
    SupportedCountry("DK", "Denmark"),
    SupportedCountry("FI", "Finland"),
    SupportedCountry("IS", "Iceland"),
    SupportedCountry("PL", "Poland"),
    SupportedCountry("CZ", "Czechia"),
    SupportedCountry("SK", "Slovakia"),
    SupportedCountry("HU", "Hungary"),
    SupportedCountry("RO", "Romania"),
    SupportedCountry("BG", "Bulgaria"),
    SupportedCountry("HR", "Croatia"),
    SupportedCountry("RS", "Serbia"),
    SupportedCountry("SI", "Slovenia"),
    SupportedCountry("GR", "Greece"),
    SupportedCountry("TR", "Turkey"),
    SupportedCountry("UA", "Ukraine"),
    SupportedCountry("RU", "Russia"),
    SupportedCountry("LV", "Latvia"),
    SupportedCountry("LT", "Lithuania"),
    SupportedCountry("EE", "Estonia"),
    SupportedCountry("IN", "India"),
    SupportedCountry("PK", "Pakistan"),
    SupportedCountry("BD", "Bangladesh"),
    SupportedCountry("LK", "Sri Lanka"),
    SupportedCountry("NP", "Nepal"),
    SupportedCountry("AE", "United Arab Emirates"),
    SupportedCountry("SA", "Saudi Arabia"),
    SupportedCountry("QA", "Qatar"),
    SupportedCountry("KW", "Kuwait"),
    SupportedCountry("BH", "Bahrain"),
    SupportedCountry("OM", "Oman"),
    SupportedCountry("JO", "Jordan"),
    SupportedCountry("LB", "Lebanon"),
    SupportedCountry("TH", "Thailand"),
    SupportedCountry("VN", "Vietnam"),
    SupportedCountry("PH", "Philippines"),
    SupportedCountry("ID", "Indonesia"),
    SupportedCountry("AU", "Australia"),
    SupportedCountry("NZ", "New Zealand"),
)

fun normalizeCountryCode(value: String?): String? {
    if (value == null) return null
    val candidate = value.trim()
    if (candidate.isEmpty()) return null
    val upper = candidate.uppercase()
    return SUPPORTED_COUNTRIES.firstOrNull { it.code == upper }?.code
}

data class SignupMetadata(
    val name: String,
    val interfaceLanguage: String,
    val region: String?,
    val avatarUrl: String?,
    val referralCode: String?,
)

data class SignupValidation(
    val isComplete: Boolean,
    val missing: List<String>,
)

fun validateSignupMetadata(metadata: SignupMetadata): SignupValidation {
    val missing = mutableListOf<String>()
    if (metadata.name.isBlank()) missing.add("name")
    if (normalizeLanguageCode(metadata.interfaceLanguage) == null) missing.add("interfaceLanguage")
    if (metadata.region != null && normalizeCountryCode(metadata.region) == null) missing.add("region")
    if (!isSupportedAvatarId(metadata.avatarUrl)) missing.add("avatarUrl")
    return SignupValidation(
        isComplete = missing.isEmpty(),
        missing = missing.toList(),
    )
}

data class SignupProfileResult(
    val isComplete: Boolean,
    val missing: List<String>,
    val normalizedName: String?,
    val normalizedLanguage: String?,
    val normalizedRegion: String?,
    val normalizedAvatarUrl: String?,
)

fun validateSignupProfile(
    rawName: String,
    rawLanguage: String?,
    rawRegion: String?,
    rawAvatarUrl: String?,
): SignupProfileResult {
    val missing = mutableListOf<String>()

    val name = rawName.trim().ifBlank { null }
    if (name == null) missing.add("name")

    val language = normalizeLanguageCode(rawLanguage)
    if (language == null) missing.add("interfaceLanguage")

    val region = normalizeCountryCode(rawRegion)

    val trimmedAvatar = rawAvatarUrl?.trim()?.ifBlank { null }
    var avatarUrl: String? = trimmedAvatar
    if (trimmedAvatar != null && !isSupportedAvatarId(trimmedAvatar)) {
        missing.add("avatarUrl")
        avatarUrl = null
    }

    return SignupProfileResult(
        isComplete = missing.isEmpty(),
        missing = missing.toList(),
        normalizedName = name,
        normalizedLanguage = language,
        normalizedRegion = region,
        normalizedAvatarUrl = avatarUrl,
    )
}
