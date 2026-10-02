package com.crispy.tv.addons.registry

/**
 * The part of `android.net.Uri` that `MetadataAddonRegistry` reads, in
 * `commonMain`.
 *
 * ## Why this exists rather than a `java.net.URI` or an okio type
 *
 * `Uri` is a class of the Android platform, so no `commonMain` can name it, and
 * there is no URL parser in any `commonMain` in this repository --
 * `core-domain`'s `normalizeAddonUrl` is a much stricter *rule* (it demands
 * `https://` and a `.json` final segment and drops the fragment) and is not a
 * parser. `java.net.URI` is JVM-only for the same reason `java.io.File` was.
 *
 * ## What it is measured against
 *
 * `UriBehaviourHostTest` in this module's `androidHostTest` runs 26 shapes
 * through Robolectric's `android-all`, which *is* the shipping implementation
 * here: `android.net.Uri` is pure Java in AOSP's `libcore`, unlike `org.json`,
 * where AOSP and the Maven artifact disagree and a host test therefore proves
 * nothing. That table is the specification for this class, and the three
 * findings that shaped it are:
 *
 * 1. **`Uri.toString()` is the identity on every one of the 26 shapes.** So this
 *    class does not re-render anything: [raw] is carried through and
 *    `toString()` returns it. That removes the whole class of bug where a port
 *    builds a subtly different string from the same components.
 * 2. **`Uri` normalizes nothing.** `HTTPS://Example.COM` keeps its case in both
 *    the scheme and the host, a fragment survives, and `//double//` keeps both of
 *    its blank segments in `toString()` while `pathSegments` drops them. So
 *    nothing here lowercases, and the segment list and the string disagree on
 *    purpose.
 * 3. **A bare word (`foo`) parses as a *host*.** That case is unreachable here:
 *    `parseManifestSeed` normalizes its input first and every branch of that
 *    normalization emits a `://`, which is why [parse] requires one and returns
 *    `null` without it. Requiring the invariant is what keeps this class 120
 *    lines instead of a general URL parser.
 *
 * ## `baseUrl`
 *
 * Reproduces `Uri.Builder().scheme(..).encodedAuthority(..).appendPath(seg)..`
 * followed by `trimEnd('/')`: `encodedAuthority` is carried verbatim (userinfo
 * and port included, IPv6 brackets included), each *decoded* segment is re-encoded
 * as a path segment, and the query and fragment are dropped.
 */
internal class ManifestUri private constructor(
    val raw: String,
    val scheme: String,
    val encodedAuthority: String,
    val host: String,
    private val encodedSegments: List<String>,
    val encodedQuery: String?,
) {

    /**
     * `Uri.getPathSegments()`: percent-**decoded**, with blank segments dropped.
     *
     * `encodedSegments` keeps both, so [raw] and this list disagree on `//double//`
     * exactly as `Uri`'s do.
     */
    val pathSegments: List<String> by lazy {
        encodedSegments.filter { it.isNotEmpty() }.map(::percentDecode)
    }

    /** `Uri.Builder()...build().toString().trimEnd('/')`, per the class KDoc. */
    fun baseUrl(): String = baseUrlFor(pathSegments)

    /**
     * The same rebuild from a caller-chosen segment list, which is what a trailing
     * `manifest.json` needs: the decision of which segment is the manifest belongs
     * to the caller, so the rebuild takes the list it decided on rather than
     * re-deciding here.
     */
    fun baseUrlFor(segments: List<String>): String {
        val encoded = segments.joinToString("/") { percentEncodeSegment(it) }
        return "$scheme://$encodedAuthority${if (encoded.isEmpty()) "" else "/$encoded"}".trimEnd('/')
    }

    /** The identity, per finding 1. Not `Uri.toString()` re-implemented. */
    override fun toString(): String = raw

    override fun equals(other: Any?): Boolean = other is ManifestUri && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    companion object {
        /**
         * `Uri.parse`, restricted to the invariant the caller guarantees.
         *
         * Returns `null` when there is no `://`, when `scheme` would be empty, or
         * when the host is blank -- which is the same shape as
         * `parseManifestSeed`'s `uri.host?.takeIf { it.isNotBlank() } ?: return null`.
         */
        fun parse(raw: String): ManifestUri? {
            val schemeEnd = raw.indexOf(SCHEME_DELIMITER)
            if (schemeEnd <= 0) return null
            val scheme = raw.substring(0, schemeEnd)

            var rest = raw.substring(schemeEnd + SCHEME_DELIMITER.length)
            val fragment = rest.indexOf('#')
            if (fragment >= 0) rest = rest.substring(0, fragment)

            val queryStart = rest.indexOf('?')
            val encodedQuery = if (queryStart >= 0) rest.substring(queryStart + 1) else null
            val authorityAndPath = if (queryStart >= 0) rest.substring(0, queryStart) else rest

            val pathStart = authorityAndPath.indexOf('/')
            val encodedAuthority =
                if (pathStart >= 0) authorityAndPath.substring(0, pathStart) else authorityAndPath
            val encodedPath = if (pathStart >= 0) authorityAndPath.substring(pathStart + 1) else ""

            val host = hostOf(encodedAuthority)
            if (host.isBlank()) return null

            return ManifestUri(
                raw = raw,
                scheme = scheme,
                encodedAuthority = encodedAuthority,
                host = host,
                encodedSegments = encodedPath.split('/'),
                encodedQuery = encodedQuery,
            )
        }

        private const val SCHEME_DELIMITER = "://"

        /**
         * `Uri.getHost()` on an encoded authority: no userinfo, no port, and an
         * IPv6 literal keeps its brackets. An authority with neither a userinfo
         * nor a port is the whole string.
         */
        private fun hostOf(authority: String): String {
            val afterUserInfo = authority.substringAfterLast('@')
            val bracket = afterUserInfo.indexOf(']')
            return if (afterUserInfo.startsWith("[") && bracket >= 0) {
                afterUserInfo.substring(0, bracket + 1)
            } else {
                afterUserInfo.substringBefore(':')
            }
        }
    }
}

/**
 * RFC 3986's unreserved set, the only characters `Uri.Builder`'s path encoding
 * leaves alone. `~` is in it and `/` is not, which is why a decoded `%2F` is
 * re-encoded as `%2F` rather than splitting the segment.
 */
private fun Char.isUnreserved(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '.' || this == '_' || this == '~'

/**
 * `Uri`'s path-segment decode: `%XX` becomes the byte it names, everything else
 * is literal. **An escape that is not two hex digits stays literal** (`%ZZ` and a
 * trailing `%`), because `Uri` is lenient there and a strict decoder would turn a
 * working manifest URL into an exception.
 *
 * Consecutive escapes are decoded as one UTF-8 run rather than per-escape, so a
 * multi-byte character written as `%C3%A9` reads as `é` and not as two Latin-1
 * characters.
 */
internal fun percentDecode(value: String): String {
    if ('%' !in value) return value

    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val high = hexDigit(value[index + 1])
            val low = hexDigit(value[index + 2])
            if (high >= 0 && low >= 0) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().forEach(bytes::add)
        index++
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * `Uri.Builder`'s path-segment encode: the unreserved set passes through and
 * every byte of anything else becomes `%XX` with upper-case hex.
 */
internal fun percentEncodeSegment(value: String): String {
    if (value.isEmpty()) return value
    val out = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val char = (byte.toInt() and 0xFF).toChar()
        if (char.isUnreserved()) {
            out.append(char)
        } else {
            val unsigned = byte.toInt() and 0xFF
            out.append('%')
                .append(HEX_DIGITS[unsigned shr 4])
                .append(HEX_DIGITS[unsigned and 0xF])
        }
    }
    return out.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun hexDigit(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}