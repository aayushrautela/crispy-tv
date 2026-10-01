package com.crispy.tv.ui.navigation

import com.crispy.tv.domain.catalog.encodeUriComponent
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.player.PlaybackIdentity

/**
 * Route builders. These live in `commonMain` because the encoder they need now
 * does too.
 *
 * They were `androidMain` for exactly one reason, recorded here as it was
 * written: `android.net.Uri.encode`, whose allow-list is neither RFC 3986 nor
 * the form-urlencoded encoder `:android:core-domain` uses for catalog URLs, and
 * **no portable encoder matching it byte for byte existed.**
 *
 * `com.crispy.tv.domain.catalog.encodeUriComponent` is that encoder, and it was
 * measured rather than assumed. Its allow-list is `a-z A-Z 0-9 _ - ! . ~ ' ( ) *`,
 * and `UriEncodeAgreesTest` in `:android:core-domain`'s `androidHostTest`
 * asserts it against the real `android.net.Uri.encode` over the whole
 * `0x00..0x7E` range, over multi-byte input, and over a whole route-shaped
 * string. **That suite is the reason this file can move**, and reading the SDK
 * is why it is not obvious: `javap -c` on `android/net/Uri.class` in
 * `android.jar` emits `ldc // String Stub!`, so the platform artifact says the
 * method exists and says nothing about what it does.
 *
 * The catalog encoder is deliberately **not** reused. It is the same mechanism
 * -- one `%XX` per UTF-8 byte, uppercase hex -- over a different table, and the
 * two differ on exactly `! ~ ' ( )`, which the platform encoder keeps literal
 * and the catalog contract escapes. `~` is the character the two KDocs argue
 * about, and the catalog contract needs it escaped, so one function cannot serve
 * both. Swapping them would be a silent deep-link behaviour change: the route
 * keys users already have would resolve differently.
 *
 * These are members of [AppRoutes] rather than loose top-level functions, which
 * is what lets the **22** call sites -- all written
 * `AppRoutes.homeDetailsRoute(...)` and friends -- keep compiling unchanged.
 * That was the reason for the original extension form and it is the reason this
 * form survives the move. *The old KDoc said 19; 22 is the measured count, and
 * the two numbers were never about the same set -- 18 `Uri.encode` call sites
 * live in this file and 22 call sites of the builders live elsewhere.*
 */
fun AppRoutes.homeDetailsRoute(
    itemId: String,
    itemType: String,
    highlightEpisodeId: String? = null,
    autoOpenEpisode: Boolean = false,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    absoluteEpisodeNumber: Int? = null,
    artworkUrl: String? = null,
    sharedElementKey: String? = null,
): String {
    return "$HomeDetailsRoute/${encodeUriComponent(itemType.trim())}/${encodeUriComponent(itemId.trim())}" +
        "?$HomeDetailsHighlightEpisodeIdArg=${encodeUriComponent(highlightEpisodeId.orEmpty())}" +
        "&$HomeDetailsAutoOpenEpisodeArg=${autoOpenEpisode}" +
        "&$HomeDetailsRuntimeSeasonNumberArg=${seasonNumber?.toString().orEmpty()}" +
        "&$HomeDetailsRuntimeEpisodeNumberArg=${episodeNumber?.toString().orEmpty()}" +
        "&$HomeDetailsRuntimeAbsoluteEpisodeArg=${absoluteEpisodeNumber?.toString().orEmpty()}" +
        "&$HomeDetailsArtworkUrlArg=${encodeUriComponent(artworkUrl.orEmpty())}" +
        "&$HomeDetailsSharedElementKeyArg=${encodeUriComponent(sharedElementKey.orEmpty())}"
}

fun AppRoutes.catalogListRoute(section: CatalogSectionRef): String {
    return "$CatalogListRoute/${encodeUriComponent(section.catalogId)}" +
        "?$CatalogTitleArg=${encodeUriComponent(section.displayTitle)}"
}

/**
 * Builds the player destination route. The launch payload mirrors the identity fields the
 * player session actually consumes; optional values travel as blank query parameters.
 */
fun AppRoutes.playerRoute(
    identity: PlaybackIdentity,
    resumePositionMs: Long = 0L,
    chosenStreamStableKey: String? = null,
    chosenProviderId: String? = null,
    chosenStreamHandoffKey: String? = null,
): String {
    return "$PlayerRoute?$PlayerMediaTypeArg=${encodeUriComponent(identity.contentType.name)}" +
        "&$PlayerItemIdArg=${encodeUriComponent(identity.itemId.orEmpty())}" +
        "&$PlayerSeriesItemIdArg=${encodeUriComponent(identity.seriesItemId.orEmpty())}" +
        "&$PlayerImdbIdArg=${encodeUriComponent(identity.imdbId.orEmpty())}" +
        "&$PlayerTmdbIdArg=${identity.tmdbId?.toString().orEmpty()}" +
        "&$PlayerSeasonArg=${identity.season?.toString().orEmpty()}" +
        "&$PlayerEpisodeArg=${identity.episode?.toString().orEmpty()}" +
        "&$PlayerYearArg=${identity.year?.toString().orEmpty()}" +
        "&$PlayerShowTitleArg=${encodeUriComponent(identity.showTitle.orEmpty())}" +
        "&$PlayerShowYearArg=${identity.showYear?.toString().orEmpty()}" +
        "&$PlayerParentMediaTypeArg=${encodeUriComponent(identity.parentMediaType.orEmpty())}" +
        "&$PlayerAbsoluteEpisodeNumberArg=${identity.absoluteEpisodeNumber?.toString().orEmpty()}" +
        "&$PlayerResumePositionMsArg=$resumePositionMs" +
        "&$PlayerChosenStreamKeyArg=${encodeUriComponent(chosenStreamStableKey.orEmpty())}" +
        "&$PlayerChosenProviderIdArg=${encodeUriComponent(chosenProviderId.orEmpty())}" +
        "&$PlayerChosenStreamHandoffKeyArg=${encodeUriComponent(chosenStreamHandoffKey.orEmpty())}"
}

fun AppRoutes.personDetailsRoute(personId: String, profileUrl: String? = null): String {
    return "$PersonDetailsRoute/${encodeUriComponent(personId.trim())}" +
        "?$PersonDetailsProfileUrlArg=${encodeUriComponent(profileUrl.orEmpty())}"
}
