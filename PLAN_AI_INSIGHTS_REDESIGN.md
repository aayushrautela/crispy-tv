# Plan: AI Insights Redesign (crispy-tv Android)

Status: planning / implementation-ready. **No compilation in this environment (Gradle is missing).**
All changes are Kotlin/Compose code edits only. Build, run, and visual QA are the responsibility
of the user or CI — this plan cannot be verified by a local `./gradlew` build here.

## Goal
Migrate the Android AI Insights overlay to the **new server schema** and redesign it as an
Instagram-story-style, full-screen, tap-driven deck with smooth rotating material shapes.

Server schema (authoritative, `crispy-server` `openapi/public-app.v1.yaml`):
```
AiInsightsResponse { slides: AiInsightSlide[] }
AiInsightSlide {
  key:   "the_good_stuff" | "the_catch" | "standout_element" | "trivia"
  label: string
  kind:  "prose" | "standout" | "trivia"
  body:  string | null
  tag:   "PERFORMANCE"|"VISUALS"|"STORY"|"DIRECTION"|"WORLD_BUILDING" | null
  focus: string | null
  context: string | null
  backdrop: ResponsiveImageSet { small, medium, large }   // already modeled on Android
  accent: string (hex)
}
```
Server returns slides in fixed order `[the_good_stuff, the_catch, standout_element, trivia]`
and returns `null` entirely if any required field is missing, so display order/length is predictable
(4 or 0).

## UX research (applied)
- Instagram-story patterns: full-screen vertical; segmented progress bar at top, close X top-right;
  tap right→next, tap left→prev; keep important text in the central ~60–70% safe zone; one message
  per slide; short bold headline + smaller supporting text; strong first-slide hook. One animation
  per slide; subtle fade/slide transitions.
- Compose rotating-shape technique (official Material `graphics-shapes` docs): an `Image` is clipped
  with a custom `Shape` that applies a `rotation` to a `RoundedPolygon`/`Morph`
  (`CustomRotatingMorphShape` pattern). **The image stays put; only the clip outline rotates slowly**,
  revealing a gently changing crop of the static picture. `CornerRounding(smoothing)` yields smooth,
  symmetrical shapes.

## Decisions (confirmed with user)
1. Rotation: backdrop image is clipped by a **slowly rotating smooth symmetric material shape**; the
   image itself does not spin, only the clip rotates. (Positive/negative slides.)
2. Slide order for display (client reorders): **standout_element (p1) → the_good_stuff (p2, positive)
   → the_catch (p3, negative) → trivia (p4, fun fact)**.
3. Navigation: **tap-only**, no auto-advance (text is dense; needs reading time).
4. Platform: **Android only** (no iOS insights code exists).

## Per-slide treatment
| # | Slide (display) | Backdrop | Shape | Big muted outline icon | Text layout |
|---|-----------------|----------|-------|------------------------|-------------|
| 1 | standout_element | simple **rounded-rect card** on top | none (static) | (optional, subtle) | `focus` as headline, `context` supporting, `tag` chip (PERFORMANCE/VISUALS/…) |
| 2 | the_good_stuff (positive) | clipped to **slowly rotating smooth symmetric shape** | one symmetric shape (e.g. 8-vertex `RoundedPolygon` w/ high `smoothing`, or `MaterialShapes.Sunny`) | `Icons.Outlined.ThumbUp` | `body` text below |
| 3 | the_catch (negative) | clipped to a **different** slowly rotating smooth shape | different symmetric shape (e.g. `MaterialShapes.SoftBoom`/`VerySunny`) | `Icons.Outlined.SentimentVeryDissatisfied` | `body` text below |
| 4 | trivia (fun fact) | clipped to a soft rotating shape, dimmed | soft shape | `Icons.Outlined.Lightbulb` | centered pill |

- **Rotation implementation:** a `RotatingMaterialShape : Shape` wrapping a `RoundedPolygon` (or `Morph`)
  plus a `rotation` angle; animated via `rememberInfiniteTransition` `0f→360f`, ~8–10s,
  `LinearEasing`. Applied as the `clip()` on the `AsyncImage` `Modifier` (image content static).
  `graphics-shapes` dependency required (see below).
- **Icons:** large (~96–112.dp), `tint = accent.copy(alpha ≈ 0.16f)` (muted, not standing out), placed
  behind/over the backdrop. `material-icons-extended` is already a dependency.
- **Accent:** each slide's `accent` (hex → `Color`, with try/catch fallback to `palette.accent`) drives
  progress fill, icon tint, ambient gradient, and the `tag` chip.
- **Chrome (story style):** segmented progress bar (one segment per slide) top, close X top-right;
  tap zones = left 1/3 prev, right 2/3 next (existing 0.33 threshold); `AnimatedContent` fade/scale
  between slides; footer with watchlist/share + "Generative AI is experimental" note; empty state kept.
- **Image selection:** `backdrop.large ?: backdrop.medium ?: backdrop.small` (fallback to title
  `backdropUrl`/`posterUrl` if a slide has no `backdrop`).

## Data-layer migration (implemented)
1. ~~`android/app/build.gradle.kts` — add `graphics-shapes`~~ **Not needed**: rotation is achieved
   without a custom `Shape`/Matrix API by rotating the clip container with `graphicsLayer { rotationZ }`
   while the image counter-rotates with overscan (`AiInsightsRotatingBackdrop`). Uses only
   already-present dependencies (`material3` expressive `MaterialShapes`, compose animation).
2. `android/app/.../ai/AiInsightsModels.kt` — replace `AiInsightCard` + `AiInsightsResult` with:
   - `enum class AiInsightSlideKey { THE_GOOD_STUFF, THE_CATCH, STANDOUT_ELEMENT, TRIVIA, UNKNOWN }`
   - `enum class AiInsightSlideKind { PROSE, STANDOUT, TRIVIA }`
   - `enum class AiInsightStandoutTag { PERFORMANCE, VISUALS, STORY, DIRECTION, WORLD_BUILDING, UNKNOWN }`
   - `data class AiInsightSlide(val key, val label, val kind, val body, val tag, val focus, val context, val backdrop: ResponsiveImageSet, val accent: String)`
   - `data class AiInsightsResult(val slides: List<AiInsightSlide>)` (drop `trivia` field)
3. `android/app/.../backend/CrispyBackendClient.kt` (~line 208) — replace `AiInsightsCard` /
   `AiInsightsResponse` with `AiInsightsResponse(val slides: List<AiInsightSlide>)`.
4. `android/app/.../backend/CrispyBackendParsers.kt` (~line 903) — add `parseAiInsightsSlides(json.optJSONArray("slides"))`;
   parse `key`→enum (default `UNKNOWN`), `kind`→enum, `tag`→enum, `accent` hex→`Color`
   (`runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() ?: fallback`),
   `backdrop` via existing `parseResponsiveImageSet`; remove `parseAiInsightsCards`.
5. `android/app/.../backend/CrispyBackendMetadataApi.kt` (~line 142) — parse `"slides"` instead of
   `"insights"`/`"trivia"`.
6. `android/app/.../ai/AiInsightsRepository.kt` — `generate()` maps `payload.slides` directly into
   `AiInsightsResult`.
7. `android/app/.../ai/AiInsightsCacheStore.kt` — re-key `ai_ins_v3_`, serialize new fields
   (`key,label,kind,body,tag,focus,context,backdrop{small,medium,large},accent`); this invalidates the
   old `ai_ins_v2_` cache automatically.
8. Grep sweep `android/` for `AiInsightCard` / `AiInsightsResult` / old `trivia` parsing and update any
   remaining consumers (today: `AiInsightsStoryOverlay`, `AiInsights` references are confined to the
   files above).

## Overlay rewrite (`android/app/.../details/AiInsightsStoryOverlay.kt`)
- New entry signature keeps `result: AiInsightsResult`, `backdropUrls`, `title`, `posterUrl`,
  `backdropUrl`, `palette`, watchlist/share/dismiss callbacks.
- Reorder `result.slides` into display order `[STANDOUT, GOOD, CATCH, TRIVIA]` by `key`.
- `AiInsightsProgressHeader`: segments = slide count; fill uses `slide.accent`.
- `AiInsightsHeroSlide` (p1 standout): rounded-rect `AsyncImage` (static) on top, then `focus` headline /
  `context` / `tag` chip below.
- `AiInsightsDetailSlide` (p2/p3/p4): `AsyncImage` clipped with `RotatingMaterialShape` (slow rotation),
  big muted outline icon behind/over it, text below; p4 uses centered pill text.
- `RotatingMaterialShape` + `rememberRotatingShape(...)` helper (rotation 0→360 over ~9s `LinearEasing`).
- `AiInsightsStoryBackground`: keep accent-tinted radial gradient, driven by current slide `accent`.
- Remove `accentColorForType`, `blobShapeForIndex`, `iconForType` (old `type`-based logic); replace with
  `key`/`kind`-based mapping.
- Keep `AiInsightsEmptyStory` + footer actions, restyled to match.

## Correctness notes (since we cannot compile here)
- Prefer reusing existing helpers (`parseResponsiveImageSet`, `ResponsiveImageSet.isEmpty`,
  `DetailsPaletteColors` fields: `pageBackground`, `onPageBackground`, `accent`, `pillBackground`,
  `onPillBackground`) to avoid new API mismatches.
- Keep `ExperimentalMaterial3ExpressiveApi` opt-in where `MaterialShapes`/`toShape()` is used.
- All new `@Composable` funcs must be called within `MaterialTheme`; rotation uses
  `rememberInfiniteTransition` (safe in composition).
- Avoid introducing APIs not present in the existing imports (e.g. confirm `ThumbUp`/`SentimentVeryDissatisfied`/
  `Lightbulb` exist in `material-icons-extended` — they do).

## Verification (user / CI only — no local build available)
- Code review for type/API correctness against existing `CrispyBackendClient`, `DetailsPaletteColors`,
  `material-icons-extended`.
- User runs `./gradlew :app:assembleDebug` (or CI) and adds/adjusts a parser test for the new
  `slides` schema in `CrispyBackendParsers`.
- Manual QA on emulator/device: screenshot all 4 slides in light + dark; confirm rotation is subtle,
  icons muted, text within safe zone, tap zones (left/right) work, progress + close behave.

## Out of scope
- iOS (no insights UI exists).
- Server-side changes (already done in `crispy-server`).
- Auto-advance timer, share-sheet contents, watchlist backend behavior.
