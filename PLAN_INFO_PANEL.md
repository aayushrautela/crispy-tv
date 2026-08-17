# Player Info Panel — Redesign Plan

Status: approved. Build mode.

## Goal
Rebuild `PlayerInfoSheet` so it reads like a focused title panel: a clean title
area (logo or text) + structured metadata (meta row, genres, episode context,
overview, cast, directors/creators). No backdrop image, no action buttons, no
extra ratings fetch, no seasons/episodes list (those move to a future dedicated
Episodes surface).

## Hero / title area
- If `details.logoUrl` is present -> `AsyncImage`, `ContentScale.Fit`,
  `fillMaxWidth(0.81f)`, `height = min(120.dp, maxHeight * 0.34f)`. Centered.
- Else -> `Text(title, headlineMedium, palette.onPageBackground, maxLines = 3,
  textAlign = Center)`.
- Close `IconButton` overlaid `TopEnd` with a `palette.pageBackground`
  (alpha 0.55f) scrim circle (CircleShape).
- Background = flat `palette.pageBackground`. No fade gradient (no backdrop).

## Content (scrollable LazyColumn, inside the half-width surface)
1. Title area (logo or text fallback) — non-scrolling visual.
2. Meta row (local private mirror of DetailsHeader `HeaderMetaRow`):
   - TMDB score: Star icon `Color(0xFFFFD54F)` + `normalizeRatingText(details.rating)`.
   - year (plain text).
   - **certification in a bordered box**: `Surface(shape = MaterialTheme.shapes.small,
     color = palette.pillBackground, contentColor = palette.onPillBackground,
     border = BorderStroke(1.dp, palette.onPillBackground.copy(alpha = 0.3f)))`,
     text padded `horizontal = 8.dp, vertical = 4.dp`.
   - runtime (plain text).
   - Row `horizontalArrangement = spacedBy(12.dp)`, `horizontalScroll` if needed, centered.
3. Genres — outline chips (`Surface` pillBackground + border, shapes.small) in a
   horizontally-scrollable `Row`. Hidden if empty.
4. Episode context — only when `details.seasonNumber != null && details.episodeNumber != null`:
   "Now Playing · S{season} E{episode}" plus the matching episode title resolved
   from `details.videos` by `currentEpisodeId`. Plain, `palette.onPageBackground`
   alpha 0.8. Hidden for movies.
5. Overview — reuse `ExpandableDescription` (already `internal`, same app module),
   centered, `palette.onPageBackground` alpha 0.9.
6. Cast — header "Cast" (`palette.accent`, titleSmall) + vertical list of top 5
   names (`bodyMedium`). Hidden if empty.
7. Directors / Creators — one line below cast: "Directed by X" (movies:
   `details.directors`) or "Created by Y" (shows: `details.creators`), when
   present and relevant, plain `labelMedium` alpha 0.7.

## Stripped (removed)
- Backdrop image + bottom fade gradient.
- AI Insights / Watch Now buttons, Watchlist/Watched/Rate/Share quick actions.
- Seasons chips + Episodes list (future dedicated Episodes surface;
  `MediaVideo.thumbnailUrl` already exists for stills).
- Extra ratings fetch (IMDb/Trakt/RT/…). Only TMDB score from `details.rating`.

## Files
1. `android/app/src/main/java/com/crispy/tv/playerui/PlayerInfoSheet.kt`
   - Rewrite body: title area + LazyColumn per above.
   - Private local mirror of the maturity-box meta row (no change to DetailsHeader).
   - Reuse `ExpandableDescription` (cross-package `internal` is fine in `:app`).
   - Extract `EpisodeRow` / `episodeRowMeta` into new `PlayerEpisodeRow.kt` for
     future reuse.
   - New signature: `PlayerInfoSheet(visible, details, palette, onClose, currentEpisodeId?)`.
2. `android/app/src/main/java/com/crispy/tv/playerui/PlayerOverlay.kt`
   - Slim the `PlayerInfoSheet(...)` call: drop `seasons`, `selectedSeason`,
     `seasonEpisodes`, `episodesIsLoading`, `episodesStatusMessage`,
     `onSeasonSelected`, `onEpisodeSelected`. Pass `currentEpisodeId` if available.
3. `android/app/src/main/java/com/crispy/tv/playerui/PlayerSessionViewModel.kt`
   - Untouched (season/episode state stays for future Episodes surface; no ratings fetch).
4. No backend/data changes.

## Trade-offs / notes
- Title fallback uses `headlineMedium` (smaller than HeroSection's `headlineLarge`)
  so a long multi-line title wraps gracefully in the narrow panel.
- `ExpandableDescription` is `internal` but both `playerui` and `details` are in
  the same Gradle module (`:android:app`), so it is reachable.
- `formatEpisodeDate` stays in `PlayerInfoSheet.kt` (also used by `PlayerStreamsSheet.kt`).
- Verification: CI compile gate (no local JDK available); `:android:tv` and tvOS
  placeholder compile sanity.
