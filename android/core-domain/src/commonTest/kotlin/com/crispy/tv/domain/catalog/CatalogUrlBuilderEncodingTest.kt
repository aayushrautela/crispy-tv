package com.crispy.tv.domain.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the catalog URL encoder.
 *
 * The contract fixtures only exercise spaces, so they would happily let the
 * encoder drift on `*`, `~` or non-ASCII. The expected values here were taken
 * from `java.net.URLEncoder.encode(value, UTF_8).replace("+", "%20")`, which is
 * what this encoder replaced, so these tests fail if a future "cleanup" swaps in
 * an RFC 3986 encoder.
 */
class CatalogUrlBuilderEncodingTest {

    private fun encodeViaCatalogId(catalogId: String): String {
        val url = buildCatalogUrls(
            baseUrl = "https://catalog.example/addon",
            mediaType = "movie",
            catalogId = catalogId,
        ).first()
        return url
            .removePrefix("https://catalog.example/addon/catalog/movie/")
            .removeSuffix(".json")
    }

    @Test
    fun encodesSpaceAsPercent20NotPlus() {
        assertEquals("popular%20now", encodeViaCatalogId("popular now"))
    }

    @Test
    fun keepsStarLiteralAndEscapesTilde() {
        // The two places this deliberately diverges from RFC 3986.
        assertEquals("a*b%7Ec%2Bd%20e", encodeViaCatalogId("a*b~c+d e"))
        assertEquals("star*only", encodeViaCatalogId("star*only"))
        assertEquals("tilde%7Eonly", encodeViaCatalogId("tilde~only"))
    }

    @Test
    fun leavesUnreservedCharactersAlone() {
        assertEquals("plain", encodeViaCatalogId("plain"))
        assertEquals("AZaz09.-_", encodeViaCatalogId("AZaz09.-_"))
    }

    @Test
    fun escapesReservedSeparators() {
        assertEquals("a%26b%3Dc%3Fd%23e%2Ff", encodeViaCatalogId("a&b=c?d#e/f"))
        assertEquals("100%25", encodeViaCatalogId("100%"))
    }

    @Test
    fun emitsUppercaseHexPerUtf8Byte() {
        assertEquals("Caf%C3%A9", encodeViaCatalogId("Café"))
        assertEquals("%C3%86var", encodeViaCatalogId("Ævar"))
        assertEquals("%E6%97%A5%E6%9C%AC", encodeViaCatalogId("日本"))
    }
}
