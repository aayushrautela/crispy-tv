import Foundation

/// The ISO 639 language vocabulary the app matches track tags against.
///
/// This is the Swift twin of the Android `LanguageLabels.kt` table, and it
/// exists for the same reason: a track tag arrives in whichever spelling the
/// source happened to use — `eng` from an FFmpeg-derived track, `en` from an
/// addon's subtitle list — and both have to agree on one stored preference and
/// one label.
///
/// The platform slot Android needs (`Locale.forLanguageTag` is genuinely
/// Android work) does not exist here: Foundation answers the same question
/// through `Locale.localizedString(forLanguageCode:)`, so the label is computed
/// in this file rather than passed in.
public enum LanguageTags {
    /// The ISO 639-2/B three-letter code to the 639-1 two-letter code, for the
    /// codes a media backend actually sends.
    ///
    /// Both spellings appear in the wild for several languages (`spa`/`es` but
    /// also `fre`/`fr` *and* `ger`/`de`; `ell`/`el` and `gre`/`el`; `rum`/`ro`
    /// and `ron`/`ro`; `ice`/`is` and `isl`/`is`), so the table carries every
    /// variant rather than canonicalising the input -- which would need a
    /// second table of the variants it had failed to include.
    private static let iso639_2To1: [String: String] = [
        "eng": "en", "spa": "es", "fre": "fr", "deu": "de", "ger": "de",
        "ita": "it", "por": "pt", "rus": "ru", "jpn": "ja", "kor": "ko",
        "chi": "zh", "zho": "zh", "ara": "ar", "hin": "hi", "nld": "nl",
        "dut": "nl", "tur": "tr", "pol": "pl", "vie": "vi", "tha": "th",
        "ind": "id", "msa": "ms", "may": "ms", "tam": "ta", "tel": "te",
        "ben": "bn", "mal": "ml", "guj": "gu", "kan": "kn", "mar": "mr",
        "pan": "pa", "urd": "ur", "fas": "fa", "per": "fa", "heb": "he",
        "swe": "sv", "nor": "no", "dan": "da", "fin": "fi", "ell": "el",
        "gre": "el", "ces": "cs", "cze": "cs", "hun": "hu", "ron": "ro",
        "rum": "ro", "bul": "bg", "ukr": "uk", "hrv": "hr", "srp": "sr",
        "slv": "sl", "lit": "lt", "lav": "lv", "est": "et", "cat": "ca",
        "gle": "ga", "isl": "is", "ice": "is", "mkd": "mk", "mac": "mk",
        "sqi": "sq", "alb": "sq", "afr": "af", "aka": "ak", "amh": "am",
        "bod": "bo", "bos": "bs", "mya": "my", "cmn": "zh", "cym": "cy",
        "eus": "eu", "fao": "fo", "glg": "gl", "hat": "ht",
        "hau": "ha", "hye": "hy", "ibo": "ig", "jav": "jv", "kat": "ka",
        "kaz": "kk", "khm": "km", "kin": "rw", "kir": "ky", "lao": "lo",
        "mon": "mn", "mri": "mi", "nya": "ny", "ori": "or",
        "pus": "ps", "que": "qu", "sun": "su", "swa": "sw", "tgk": "tg",
        "tuk": "tk", "uig": "ug", "uzb": "uz", "wol": "wo", "yor": "yo",
        "zul": "zu",
    ]

    /// The code to store for a track's language tag.
    ///
    /// A three-letter tag the table knows becomes its two-letter twin, an
    /// unknown tag is kept as it is, and a blank tag becomes `und` — the code
    /// for "undetermined", so an unlabelled track still has a value to compare
    /// against instead of an empty string that could mean anything.
    public static func normalize(_ code: String?) -> String {
        let raw = trimmed(code).lowercased()
        if raw.isEmpty {
            return "und"
        }
        if raw.count == 3 {
            return iso639_2To1[raw] ?? raw
        }
        return raw
    }

    /// The label to show for a track's language tag, or "Unknown" when there is
    /// nothing to show.
    ///
    /// A keyword a backend sends instead of a tag — `none`/`off`, `forced`,
    /// `default`, `device`, `original`, `und` — has its own label and never
    /// reaches Foundation's language names. Anything else is reduced to a
    /// two-letter code first, and the language name is used only when it is not
    /// the code echoed back: a tag Foundation has no name for renders as the
    /// uppercased code, which is more honest than rendering `Zz`.
    public static func label(for code: String?) -> String {
        let raw = trimmed(code)
        guard !raw.isEmpty else { return "Unknown" }
        switch raw.lowercased() {
        case "none", "off":
            return "Off"
        case "forced":
            return "Forced"
        case "default":
            return "Default"
        case "device":
            return "Device language"
        case "original":
            return "Original"
        case "und":
            return "Undetermined"
        default:
            let two = raw.count == 3 ? (iso639_2To1[raw.lowercased()] ?? raw) : raw
            let upper = raw.uppercased()
            let name = englishName(two)
            if name.isEmpty || name.caseInsensitiveCompare(two) == .orderedSame {
                return upper
            }
            return name
        }
    }

    /// The languages the preference pickers offer, sorted by their English
    /// name.
    ///
    /// The list is the table's own two-letter codes, so the picker can never
    /// offer a language the matcher would not recognise, and it grows with the
    /// table rather than beside it.
    public static var selectableLanguages: [LanguageOption] {
        var seen: Set<String> = []
        let codes = iso639_2To1.values.filter { seen.insert($0).inserted }
        return codes
            .map { LanguageOption(code: $0, name: label(for: $0)) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private static func trimmed(_ code: String?) -> String {
        (code ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Foundation's English name for a language code, or an empty string when
    /// it has none.
    private static func englishName(_ code: String) -> String {
        Locale(identifier: "en").localizedString(forLanguageCode: code) ?? ""
    }
}

/// One language the settings pickers offer.
public struct LanguageOption: Identifiable, Equatable, Sendable {
    /// The stored code — ISO 639-1, because every writer normalises to it.
    public let code: String
    /// The English name to show for it.
    public let name: String

    public var id: String { code }

    public init(code: String, name: String) {
        self.code = code
        self.name = name
    }
}
