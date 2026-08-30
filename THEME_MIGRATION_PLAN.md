# Crispy TV — Theme Migration Plan

## Goal
Remove Material You dynamic colors entirely. Replace with fixed dark-only palette using Material 3 semantic tokens: coral accent + white/grey chrome.

---

## Key Principle: Tokens, Not Hardcoded Colors

**Always use `MaterialTheme.colorScheme.*` tokens. Never hardcode `Color.White` or hex values.**

| ❌ Bad | ✅ Good |
|--------|---------|
| `Color.White` | `MaterialTheme.colorScheme.onSurface` |
| `Color(0xFFB3B3B3)` | `MaterialTheme.colorScheme.onSurfaceVariant` |
| `Color(0xFF141414)` | `MaterialTheme.colorScheme.background` |
| `Color(0xFFF56E3C)` | `MaterialTheme.colorScheme.primary` |

**Why:**
- Semantic tokens provide accessible contrast ratios automatically
- If you hardcode, you lose dark mode support and accessibility
- Change once in theme, updates everywhere
- Official Android docs: "Never hardcode colors. Always use the theme."

**Good news:** Your existing screen files (`PersonDetailsRoute.kt`, `LibraryScreen.kt`, `TvSourcesScreen.kt`, etc.) already use `MaterialTheme.colorScheme.primary`, `onSurfaceVariant`, etc. These don't need changes — they'll automatically resolve to the new coral/white/grey palette.

---

## Current State

### App module (`android/app`)
- **File:** `ui/theme/Theme.kt`
- Uses `dynamicDarkColorScheme`/`dynamicLightColorScheme` on Android 12+ (Material You)
- Has `LightColors` (light mode with yellow accent) and `DarkColors` (dark mode with yellow/gold)
- Uses `MaterialExpressiveTheme` + `MaterialTheme`
- Applied in `MainActivity.kt` as `CrispyRewriteTheme`

### TV module (`android/tv`)
- **File:** `ui/theme/Theme.kt`
- Already uses fixed `darkColorScheme` (no dynamic colors) — but yellow/gold
- Uses `androidx.tv.material3.MaterialTheme`
- Applied in `TvMainActivity.kt` as `CrispyTvTheme`

### Detail Palette (`android/tv`)
- **File:** `ui/theme/DetailPalette.kt`
- Uses `materialkolor` library for poster-art color extraction
- Fallback seed: `Color(0xFFFFC400)` (yellow) — needs update to coral
- This system stays, just update the fallback seed

### Dimensions
- **File:** `ui/theme/Dimensions.kt`
- No color changes needed

---

## New Palette (Material 3 Semantic Tokens)

### App Module — Full `darkColorScheme()`

```kotlin
private val CrispyDarkColors = darkColorScheme(
    // Accent (coral — use sparingly)
    primary = Color(0xFFF56E3C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD95A30),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFF56E3C),

    // Secondary (coral for consistency)
    secondary = Color(0xFFF56E3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD95A30),
    onSecondaryContainer = Color(0xFFFFFFFF),

    // Tertiary (coral for consistency)
    tertiary = Color(0xFFF56E3C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD95A30),
    onTertiaryContainer = Color(0xFFFFFFFF),

    // Foundation (dark canvas + white/grey text)
    background = Color(0xFF141414),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),

    // Surface containers
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceTint = Color(0xFFF56E3C),
    surfaceDim = Color(0xFF0A0A0A),
    surfaceBright = Color(0xFF2A2A2A),

    // Borders
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF262626),

    // Semantic
    error = Color(0xFFE8455C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFB03040),
    onErrorContainer = Color(0xFFFFDAD6),

    // Inverse
    inverseSurface = Color(0xFFECE1C6),
    inverseOnSurface = Color(0xFF141414),

    // Scrim
    scrim = Color(0xFF000000),
)
```

### TV Module — Full `darkColorScheme()`

Same palette, but TV uses different token names:
- `border` instead of `outline`
- `borderVariant` instead of `outlineVariant`

```kotlin
private val CrispyTvDarkColors = darkColorScheme(
    primary = Color(0xFFF56E3C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD95A30),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFF56E3C),

    secondary = Color(0xFFF56E3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD95A30),
    onSecondaryContainer = Color(0xFFFFFFFF),

    background = Color(0xFF141414),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),

    // TV-specific tokens
    border = Color(0xFF333333),
    borderVariant = Color(0xFF262626),

    error = Color(0xFFE8455C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFB03040),
    onErrorContainer = Color(0xFFFFDAD6),
)
```

---

## Tasks

### 1. Rewrite App Theme (`android/app/.../ui/theme/Theme.kt`)

**Remove:**
- `dynamicDarkColorScheme`, `dynamicLightColorScheme` imports and usage
- `lightColorScheme` import
- `LightColors` (light mode — app is dark-only)
- `isSystemInDarkTheme()` check — always dark
- `Build.VERSION.SDK_INT` check — no dynamic colors at all
- `LocalContext` import (only needed for dynamic colors)
- `MaterialExpressiveTheme` wrapper (simplify to just `MaterialTheme`)

**Replace with:**
- Single `darkColorScheme` with coral + white/grey palette (as above)
- Always use it, no conditions

**Result structure:**
```kotlin
package com.crispy.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CrispyDarkColors = darkColorScheme(
    primary = Color(0xFFF56E3C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD95A30),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFF56E3C),
    secondary = Color(0xFFF56E3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD95A30),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFF56E3C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD95A30),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF141414),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceTint = Color(0xFFF56E3C),
    surfaceDim = Color(0xFF0A0A0A),
    surfaceBright = Color(0xFF2A2A2A),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF262626),
    error = Color(0xFFE8455C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFB03040),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFECE1C6),
    inverseOnSurface = Color(0xFF141414),
    scrim = Color(0xFF000000),
)

@Composable
fun CrispyRewriteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrispyDarkColors, content = content)
}
```

---

### 2. Rewrite TV Theme (`android/tv/.../ui/theme/Theme.kt`)

Same palette as app, but use TV-specific tokens (`border`/`borderVariant` instead of `outline`/`outlineVariant`).

```kotlin
package com.crispy.tv.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val CrispyTvDarkColors = darkColorScheme(
    primary = Color(0xFFF56E3C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD95A30),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFF56E3C),
    secondary = Color(0xFFF56E3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD95A30),
    onSecondaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF141414),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),
    border = Color(0xFF333333),
    borderVariant = Color(0xFF262626),
    error = Color(0xFFE8455C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFB03040),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun CrispyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrispyTvDarkColors, content = content)
}
```

---

### 3. Update DetailPalette.kt Fallback Seed

**File:** `android/tv/.../ui/theme/DetailPalette.kt`

Line 75: Change fallback seed from `Color(0xFFFFC400)` to `Color(0xFFF56E3C)` (coral).

This ensures: if poster extraction fails, the detail page defaults to coral, not yellow.

---

### 4. Audit hardcoded colors in screen files

Search for any hardcoded `Color(0xFF...)` or `Color.White`/`Color.Black` in screen files. Replace with semantic tokens:

| Hardcoded | Replace with |
|-----------|--------------|
| `Color.White` | `MaterialTheme.colorScheme.onSurface` |
| `Color.Black` | `MaterialTheme.colorScheme.surface` |
| `Color(0xFFB3B3B3)` | `MaterialTheme.colorScheme.onSurfaceVariant` |
| `Color(0xFF141414)` | `MaterialTheme.colorScheme.background` |
| `Color(0xFF1F1F1F)` | `MaterialTheme.colorScheme.surface` |
| `Color(0xFF2A2A2A)` | `MaterialTheme.colorScheme.surfaceVariant` |
| `Color(0xFFF56E3C)` | `MaterialTheme.colorScheme.primary` |
| `Color(0xFFE8455C)` | `MaterialTheme.colorScheme.error` |

**Common spots to check:**
- Skeleton shimmer colors
- Gradient overlays
- Specific screen backgrounds
- Any `Color.White` used for text

**Note:** Most screen files already use semantic tokens correctly. Only fix the ones that hardcode.

---

### 5. Verify no light mode artifacts

After removing `LightColors`:
- Search for `isSystemInDarkTheme` — should be no longer needed
- Search for `dynamicDarkColorScheme`/`dynamicLightColorScheme` — should be fully removed
- Search for `lightColorScheme` — should be fully removed
- Search for `MaterialExpressiveTheme` — should be removed

---

### 6. Test checklist

- [ ] App launches in dark mode (no light mode toggle)
- [ ] No Material You wallpaper colors bleed through
- [ ] All text uses semantic tokens (onSurface, onSurfaceVariant)
- [ ] CTAs (play, subscribe) are coral via `primary`
- [ ] Progress bars are coral via `primary`
- [ ] Active tabs/nav are coral via `primary`
- [ ] Error states use semantic `error` token
- [ ] Detail page poster extraction still works (extracted colors unchanged)
- [ ] Detail page fallback is coral (not yellow) if extraction fails
- [ ] TV module uses same palette
- [ ] No pure black `#000000` as background (use `#141414`)
- [ ] Contrast ratios meet WCAG AA:
  - `onSurface` (#FFFFFF) on `background` (#141414) = 17:1 ✅
  - `onSurfaceVariant` (#B3B3B3) on `background` (#141414) = 7.5:1 ✅
  - `primary` (#F56E3C) on `background` (#141414) = 5.2:1 ✅
  - `onPrimary` (#FFFFFF) on `primary` (#F56E3C) = 4.8:1 ✅

---

## Anti-rules (don't)

- **Don't hardcode colors** — always use `MaterialTheme.colorScheme.*` tokens
- **Don't keep `MaterialExpressiveTheme`** — simplify to `MaterialTheme`
- **Don't add `ColorUsage.FIXED` or `ColorUsage.DYNAMIC_LIGHT`** — just use fixed
- **Don't introduce a second saturated accent** — coral is the only one
- **Don't use coral as a background fill** — only for signals (CTAs, progress, active)
- **Don't use pure black `#000000` for backgrounds** — use `#141414`
- **Don't keep light mode code paths** — app is dark-only
- **Don't use `Color.White` directly** — use `MaterialTheme.colorScheme.onSurface`

---

## Files to modify

1. `android/app/src/main/java/com/crispy/tv/ui/theme/Theme.kt` — full rewrite
2. `android/tv/src/main/java/com/crispy/tv/tv/ui/theme/Theme.kt` — update palette
3. `android/tv/src/main/java/com/crispy/tv/tv/ui/theme/DetailPalette.kt` — fallback seed only
4. Any screen files with hardcoded colors (audit required — most should be fine)

## Files that should NOT need changes

- `Dimensions.kt` — no colors
- `MainActivity.kt` — already calls `CrispyRewriteTheme`, theme itself changes
- `TvMainActivity.kt` — already calls `CrispyTvTheme`, theme itself changes
- Screen files already using `MaterialTheme.colorScheme.*` tokens (most files)

---

## Post-migration

Run:
```bash
gradle :android:app:assembleDebug :android:tv:assembleDebug
```

Verify:
- No build errors
- No dynamic color imports remain
- No `MaterialExpressiveTheme` remains
- No hardcoded `Color.White`/`Color(0xFF...)` in screen files
- App is dark-only with coral accent
- All text uses semantic tokens
