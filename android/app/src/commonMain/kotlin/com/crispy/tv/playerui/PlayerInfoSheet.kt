package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.util.normalizeRatingText
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.details.ExpandableDescription
import com.crispy.tv.details.formatRuntimeForHeader
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_close_filled
import com.crispy.tv.ui.resources.ic_star_filled
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun PlayerInfoSheet(
    visible: Boolean,
    details: MediaDetails?,
    palette: DetailsPaletteColors,
    onClose: () -> Unit,
    headerEpisode: MediaVideo? = null,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onClose,
                        ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)) + slideInHorizontally(animationSpec = tween(200)) { fullWidth -> fullWidth },
            exit = fadeOut(animationSpec = tween(180)) + slideOutHorizontally(animationSpec = tween(180)) { fullWidth -> fullWidth },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(0.4f)
                            .widthIn(max = 400.dp),
                    color = palette.pageBackground,
                    contentColor = palette.onPageBackground,
                    shadowElevation = 0.dp,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        InfoSheetContent(
                            details = details,
                            palette = palette,
                            headerEpisode = headerEpisode,
                        )

                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_close_filled),
                                contentDescription = "Close",
                                tint = palette.onPageBackground,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSheetContent(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
    headerEpisode: MediaVideo? = null,
) {
    val showCast = details?.cast?.any { it.isNotBlank() } == true
    val episodeContext = details?.toPlayerEpisodeContext() ?: headerEpisode?.toPlayerEpisodeContext()
    val showEpisode = episodeContext != null
    val creditLine = buildCreditLine(details)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TitleArea(details = details, palette = palette) }
        item { MetaRow(details = details, palette = palette) }
        if (showEpisode) {
            item { EpisodeContextBlock(episodeContext = episodeContext, palette = palette) }
        }
        item { OverviewBlock(episodeContext = episodeContext, details = details, palette = palette) }
        if (showCast) {
            item { CastBlock(details = details, palette = palette) }
        }
        if (creditLine != null) {
            item {
                Text(
                    text = creditLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.onPageBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The artwork to show above the title, or null to render the title as text.
 *
 * `internal` for the reason the other four are. The decision is one rule -- a
 * usable logo beats the title, an unusable one does not -- but "usable" is the
 * part worth pinning, because Coil is handed the value whatever it is: a blank
 * string is a **valid URL as far as `AsyncImage` is concerned**, so without this
 * guard a details object with `logoUrl = "   "` would render an empty image box
 * instead of the title and the user would see nothing at all. That is a real
 * failure mode, not a style question.
 */
internal fun logoUrlFor(details: MediaDetails?): String? =
    details?.logoUrl?.trim()?.takeIf { it.isNotBlank() }

@Composable
private fun TitleArea(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
) {
    val logoUrl = logoUrlFor(details)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (logoUrl != null) {
            Box(modifier = Modifier.fillMaxWidth(0.81f)) {
                AsyncImage(
                    model = logoUrl,
                    // `details?.title` rather than `details.title`: extracting
                    // `logoUrlFor(details)` above moved the null-check into a
                    // function, so this branch no longer smart-casts `details` even
                    // though a non-null `logoUrl` still implies it. Behaviourally
                    // identical -- the safe call can only differ where the branch is
                    // unreachable -- and `AsyncImage` already takes a nullable
                    // `contentDescription`.
                    contentDescription = details?.title,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .heightIn(min = 72.dp, max = 120.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        } else {
            Text(
                text = details?.title ?: "",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onPageBackground,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The five facts the sheet's meta row can show, each normalised or absent. */
internal data class PlayerMetaRow(
    val rating: String?,
    val certification: String?,
    val year: String?,
    val runtime: String?,
    val genres: List<String>,
) {
    /** True when every field is absent, which is what makes the row render nothing. */
    fun isEmpty(): Boolean =
        rating == null && certification == null && year == null && runtime == null && genres.isEmpty()
}

/**
 * Reads the meta row's five facts out of a details object.
 *
 * `internal` for the reason the other five functions in this file are, and this is
 * the clearest case: the *five-way early return* at the old call site was a
 * condition over five values written inline in a composable, so no test could reach
 * it and deleting any one clause of it would have failed nothing. As
 * [PlayerMetaRow.isEmpty] it is one named predicate with a name that says what it
 * means.
 *
 * Three different normalisation policies are in here and they are **not**
 * interchangeable, which is the thing to keep straight:
 * - the rating goes through `normalizeRatingText`, which turns a bare number into
 *   a scaled one and can return null for a value it cannot read;
 * - the runtime goes through `formatRuntimeForHeader`, which is `null` for a blank
 *   string and formatted for the rest;
 * - the year and the certification are trimmed and blank-checked here, and the
 *   genres are filtered rather than mapped, with **a cap of two** -- the third
 *   product number in this file, alongside `castNamesFor`'s five.
 */
internal fun metaRowFor(details: MediaDetails?): PlayerMetaRow =
    PlayerMetaRow(
        rating = normalizeRatingText(details?.rating),
        certification = details?.certification?.trim()?.takeIf { it.isNotBlank() },
        year = details?.year?.trim()?.takeIf { it.isNotBlank() },
        runtime = formatRuntimeForHeader(details?.runtime),
        genres = details?.genres?.filter { it.isNotBlank() }.orEmpty().take(2),
    )

@Composable
private fun MetaRow(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
) {
    val meta = metaRowFor(details)
    val rating = meta.rating
    val certification = meta.certification
    val year = meta.year
    val runtime = meta.runtime
    val genres = meta.genres

    if (meta.isEmpty()) return

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (rating != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_star_filled),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFFFFD54F),
                )
                Text(
                    text = rating,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.onPageBackground,
                )
            }
        }
        if (year != null) {
            Text(
                text = year,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f),
            )
        }
        if (certification != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = palette.pillBackground,
                contentColor = palette.onPillBackground,
                border = BorderStroke(1.dp, palette.onPillBackground.copy(alpha = 0.3f)),
            ) {
                Text(
                    text = certification,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        genres.forEach { genre ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.pillBackground,
                contentColor = palette.onPillBackground,
                border = BorderStroke(1.dp, palette.onPillBackground.copy(alpha = 0.25f)),
            ) {
                Text(
                    text = genre,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (runtime != null) {
            Text(
                text = runtime,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f),
            )
        }
    }
}

@Composable
private fun EpisodeContextBlock(
    episodeContext: PlayerEpisodeContext?,
    palette: DetailsPaletteColors,
) {
    val context = episodeContext ?: return

    Text(
        text = context.seasonEpisodeLabel,
        style = MaterialTheme.typography.bodyMedium,
        color = palette.onPageBackground.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The overview this sheet shows, or null when there is nothing to show.
 *
 * Lifted out of [OverviewBlock]'s body for the same reason `searchItemKey` was: a
 * `?:` chain written inside a `@Composable` cannot be called from a test, so the
 * only way to pin its two fallbacks would have been to render the whole sheet, and
 * deleting an arm would have failed nothing. The rule generalises -- **a decision
 * is not "in the composable" because it renders; it is in the composable only if
 * it needs the composition**, and these two do not.
 *
 * The fallbacks are asymmetric on purpose and both were already there: the
 * episode's overview wins, the show's `description` is next, and blank loses to
 * both. The show's side is trimmed and blank-checked; the episode's side is
 * neither, because [PlayerEpisodeContext] already applies that policy to every
 * field it carries -- so re-checking it here would be a second, divergent copy of
 * one decision. That asymmetry is the thing worth pinning.
 *
 * **The asymmetry is redundant, not merely deliberate, and a mutation proved it.**
 * Adding `?.trim()?.takeIf { it.isNotBlank() }` to the episode's side compiles and
 * every test still passes, because *both* [PlayerEpisodeContext] constructors already
 * apply exactly that policy to `overview` -- `MediaVideo.toPlayerEpisodeContext`
 * trims and blank-checks its own field, and `MediaDetails.toPlayerEpisodeContext`
 * does the same on the episode it picks. So the guard below is the eleventh
 * redundant one in this repository and the first pair in this file. It is kept for
 * the same reasons the others are: the `PlayerEpisodeContext` constructors are two
 * functions away and a future one might not normalise, and this is the statement of
 * what the reader may rely on. Do not read the surviving mutation as a missing
 * test -- there is nothing reachable that distinguishes the two spellings.
 */
internal fun overviewTextFor(
    episodeContext: PlayerEpisodeContext?,
    details: MediaDetails?,
): String? =
    episodeContext?.overview
        ?: details?.description?.trim()?.takeIf { it.isNotBlank() }

/**
 * The cast names to show, in order, or an empty list when there are none.
 *
 * Lifted out of [CastBlock] for the reason above, and because **the cap is a
 * product decision with no other expression**: `take(5)` says the sheet shows at
 * most five people, and that number lived inside a composable where a suite could
 * only reach it by rendering. Blank entries are dropped rather than shown as an
 * empty line, which is why the count is taken *after* the filter and not before:
 * five names must not become three names because two rows were blank.
 */
internal fun castNamesFor(details: MediaDetails?): List<String> =
    details?.cast?.filter { it.isNotBlank() }.orEmpty().take(5)

@Composable
private fun OverviewBlock(
    episodeContext: PlayerEpisodeContext?,
    details: MediaDetails?,
    palette: DetailsPaletteColors,
) {
    val description = overviewTextFor(episodeContext, details) ?: return
    ExpandableDescription(
        text = description,
        textAlign = TextAlign.Center,
        textColor = palette.onPageBackground.copy(alpha = 0.9f),
    )
}

@Composable
private fun CastBlock(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
) {
    val cast = castNamesFor(details)
    if (cast.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.titleSmall,
            color = palette.accent,
        )
        cast.forEach { entry ->
            val (name, character) = parseCastEntry(entry)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onPageBackground,
                )
                if (character != null) {
                    Text(
                        text = "as $character",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onPageBackground.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/**
 * Splits one `"Actor as Character"` credit into its two halves.
 *
 * `internal` for the reason the three functions above are: this is a decision, it
 * is pure, and a `private fun` inside a composable file is a decision no test can
 * reach. The two rules worth naming, because both are easy to "fix" wrongly:
 *
 * - **The first `" as "` wins, not the last.** `substring(0, separator)` with
 *   `indexOf` means a character *named* `As` (`"Ana as Character as The One"`)
 *   splits at the first occurrence and keeps the rest as the character, which is
 *   what the sheet has always shown. `lastIndexOf` would be a behaviour change.
 * - **A name is trimmed but never blank-checked, and the whole entry survives when
 *   there is no separator at all.** `"Ada Lovelace"` is a bare name and renders
 *   fine; there is no state in which this returns an empty name for a non-blank
 *   entry, so a `takeIf` here would be guarding a case that cannot arrive.
 */
internal fun parseCastEntry(entry: String): Pair<String, String?> {
    val separator = entry.indexOf(" as ")
    if (separator < 0) return entry to null
    val name = entry.substring(0, separator).trim()
    val character = entry.substring(separator + 4).trim().takeIf { it.isNotBlank() }
    return name to character
}

/**
 * The credit line under the title, or null when the credits are missing.
 *
 * `internal` for the reason the three above are. Four decisions are packed in here
 * and each is a plausible place to break:
 *
 * - **The item type is read case-insensitively**, because the backend has been
 *   known to send `Movie` as well as `movie`, and a case-sensitive compare would
 *   silently turn a film into "Created by".
 * - **A missing `itemType` is a series**, not a movie, because `null?.equals(...)`
 *   is `false`. That default is what makes the else branch correct on a details
 *   object that never carried the field.
 * - **The verb follows the branch and not the data**: `Directed by` for directors,
 *   `Created by` for creators. A film carrying only `creators` therefore shows
 *   nothing at all, and there is no source edit that could make it show anything
 *   else: `MediaDetails.directors` is `List<String> = emptyList()`, **not** a
 *   nullable, so a `?: details?.creators` added to this arm is dead by construction
 *   rather than merely untested -- the elvis's left operand is never null. That is
 *   the twelfth redundant guard in this repository, and `aMovieDoesNotFallBackToCreators`
 *   pins the behaviour regardless.
 * - **Blank names are dropped before the join and the join is blank-checked after
 *   it**, so a list of nothing but blanks is `null` rather than `"Directed by "`.
 *   The second `takeIf` is load-bearing and is *not* the same as the first: the
 *   filter removes individual blanks, the `takeIf` removes a line made entirely of
 *   separators.
 */
internal fun buildCreditLine(details: MediaDetails?): String? {
    val isMovie = details?.itemType.equals("movie", ignoreCase = true)
    return if (isMovie) {
        details
            ?.directors
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }
            ?.let { "Directed by $it" }
    } else {
        details
            ?.creators
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }
            ?.let { "Created by $it" }
    }
}
