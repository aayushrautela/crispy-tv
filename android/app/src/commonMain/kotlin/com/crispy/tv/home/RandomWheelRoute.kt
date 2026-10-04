package com.crispy.tv.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.crispy.tv.domain.home.HOME_RANDOM_GENRE_LIMIT
import com.crispy.tv.domain.home.HOME_RANDOM_MIN_RATING
import com.crispy.tv.domain.home.HOME_RANDOM_POOL_CAP
import com.crispy.tv.domain.home.HomeRandomCandidate
import com.crispy.tv.domain.home.HomeRandomGenre
import com.crispy.tv.domain.home.randomPool
import com.crispy.tv.domain.home.topGenres
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.components.genreIcon
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_close
import com.crispy.tv.ui.resources.ic_layers
import com.crispy.tv.ui.theme.Dimensions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// The drum is the pool repeated many times over, so that a spin of a couple of thousand pixels
// never reaches either end of the list and a flick never runs out of content. Only the rows on
// screen are composed, so the item count costs nothing until the list scrolls.
private const val RandomWheelLaps = 400
private const val RandomUnfoldDelayMs = 320L
private const val RandomTurnDurationMs = 320

private val RandomRowHeight = 100.dp
private val RandomDiscSize = 72.dp
private val RandomCentredRowWidth = 300.dp
private val RandomRestingRowWidth = 220.dp
private val RandomChipOuterCorner = 18.dp
private val RandomChipJoinCorner = 4.dp
private val RandomChipHeight = 40.dp
private val RandomChipIconSize = 18.dp

// The 3D face. The numbers are transcribed from the reference project's drum, which was tuned by
// eye against a running app; nothing here is derived from first principles. They are all
// expressed in ROW UNITS -- `a` is the row's distance from the centre in rows, not a fraction of
// one -- which is what makes the arc reach its cap within the handful of rows a phone can show.
private const val RandomRotationPerRow = 14f
private const val RandomRotationLimit = 72f
private const val RandomCameraDistance = 12f
private const val RandomScaleFalloff = 0.07f
private const val RandomScaleFloor = 0.62f
private const val RandomScaleLocalGain = 0.14f
private const val RandomScaleLocalBase = 0.86f
private const val RandomAlphaFalloff = 0.2f
private const val RandomLocalPerUnfold = 1.6f
private const val RandomLocalPerRow = 0.18f
private const val RandomLandingGain = 0.05f
private val RandomArcStep = 7.dp
private val RandomArcLimit = 56.dp

/** How long the wheel holds off re-ticking after a detent, so a fast spin does not buzz continuously. */
private val RandomDetentInterval = 40.milliseconds

/**
 * The word a drum row shows for a backend media type.
 *
 * `CachingHomeCatalogService.toCatalogType()` reduces the backend's media type to exactly three
 * words -- `movie`, `show`, `anime` -- so mirroring its branches is the whole rule, and `anime`
 * reads as [ShowLabel] because TMDB publishes no anime genre: there it is `Animation` or
 * `Action & Adventure`, and a row reading "Anime" would imply a genre this catalogue does not
 * have. Only the displayed word is folded. Navigation still uses the raw
 * [HomeRandomCandidate.type], so folding here cannot change which route a pick opens.
 */
internal fun randomTypeLabel(type: String): String =
    when (type.trim().lowercase()) {
        MovieLabel.lowercase() -> MovieLabel
        AnimeLabel, "episode", "show", "tv", "series" -> ShowLabel
        // Mirrors `toCatalogType`'s `else -> "movie"`: the backend emits nothing else, and a
        // blank type is a movie by that same rule rather than a third kind of thing.
        else -> MovieLabel
    }

private const val MovieLabel = "Movie"
private const val ShowLabel = "Show"
private const val AnimeLabel = "anime"

/**
 * How many rows a spin travels.
 *
 * A pool of 8 to 20 rows gives a spin long enough to read as a spin, and the extra
 * [poolSize]-weighted steps stop a two-item pool from landing as fast as a fifty-item one --
 * which matters because the duration is derived from the step count below. [random] is a
 * parameter so a test can pin the answer; there is exactly one production call site and it
 * passes `Random.Default`.
 */
internal fun randomSpinSteps(poolSize: Int, random: Random): Int {
    val turns = poolSize.coerceIn(8, 20)
    return turns + random.nextInt(1, poolSize.coerceAtLeast(2) + 1)
}

/** The spin's length, in milliseconds. Capped so a long pool cannot outstay the 3s ceiling. */
internal fun randomSpinDurationMs(steps: Int): Int = (1700 + steps * 24).coerceAtMost(3000)

/**
 * The random-pick wheel: a full-screen destination holding a drum of covers that spins and lands.
 *
 * ## It is a destination, not an overlay
 *
 * The first version was a `remember`ed boolean in [HomeRoute] drawing a clipped panel over a
 * sibling scrim, and every complaint it earned came from that one choice: it had no status-bar
 * inset, it read as a sheet parked at the top edge rather than a page, and it inherited none of
 * the destination transitions the search screen gets for free. This is a nav route with
 * `NavigationRole.Overlay`, which is what "an overlay like search page" means in this codebase.
 *
 * ## It does not navigate on landing
 *
 * Landing changes nothing but what the centre row reads. The wheel settles, the centred item
 * becomes the pick, and **Play** -- which has been visible the whole time -- is what carries it to
 * the details page. The first version navigated in a `LaunchedEffect` the instant the wheel
 * stopped, so the result was never seen.
 *
 * **The `armed` gate the first version needed is gone with it.** It existed only to stop the
 * resting drum from being read as a pick, and a resting drum *is* a pick once nothing fires
 * automatically.
 *
 * ## Covers load on demand
 *
 * Through the ordinary [rememberCrispyImageModel] path, like every other Crispy image. The
 * reference project prefetched the whole pool at the drawn pixel size so a mid-spin arrival was a
 * memory hit; that is not reproducible here, because coil3 3.5.0 exposes no
 * `PlatformContext.imageLoader` from `commonMain` (`DetailsSeedColorLoader`'s KDoc is right about
 * it). Adding a second request path to buy a cache hit would duplicate the builder that keeps the
 * cache correct, so there is no second path. At most the rows on screen are in flight.
 *
 * ## The buttons are ours
 *
 * `FilledTonalButton`, `Button`, `FilterChip`, `IconButton` + `CrispyIcon` -- the vocabulary the
 * rest of the app uses. Only the geometry and the feel came from the reference project: the
 * full-height drum, the row-unit face maths, and the per-slot detent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RandomWheelRoute(
    viewModelFactory: ViewModelProvider.Factory,
    onPlay: (HomeRandomCandidate) -> Unit,
    onClose: () -> Unit,
) {
    val wheel: RandomWheelViewModel = viewModel(factory = viewModelFactory)
    val uiState by wheel.state.collectAsState()

    val candidates = uiState.candidates
    val genres = remember(candidates) {
        candidates.topGenres(HOME_RANDOM_MIN_RATING, HOME_RANDOM_GENRE_LIMIT)
    }
    // Re-keyed on [candidates] so a home refresh cannot leave a chip pointing at a genre the
    // new snapshot no longer has.
    var selectedGenre by remember(candidates) { mutableStateOf<String?>(null) }
    val pool = remember(candidates, selectedGenre) {
        candidates.randomPool(selectedGenre, HOME_RANDOM_MIN_RATING, HOME_RANDOM_POOL_CAP, Random.Default)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val localDensity = LocalDensity.current
    val rowPx = with(localDensity) { RandomRowHeight.toPx() }

    var isSpinning by remember { mutableStateOf(false) }
    var unfolded by remember { mutableStateOf(false) }

    val chosenIndex by remember {
        derivedStateOf {
            if (listState.isScrollInProgress) -1 else listState.randomCenteredIndex()
        }
    }
    /** Whatever is sitting in the centre slot. There is no other notion of a pick. */
    val picked: HomeRandomCandidate? by remember(pool) {
        derivedStateOf {
            val index = chosenIndex
            if (index < 0 || pool.isEmpty()) null else pool[index % pool.size]
        }
    }

    val unfold by animateFloatAsState(
        targetValue = if (unfolded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 260f),
        label = "randomUnfold",
    )
    // A short pulse on the centre row when the wheel stops, which is what gives the landing its
    // settle. It decays rather than latches, so it cannot leave a permanent pop on the winner.
    val landing = remember { Animatable(0f) }

    val openingIndex = (RandomWheelLaps / 2) * pool.size

    // The pool changed, which includes the first composition: rest the drum at the opening index
    // with its rows still folded onto that slot.
    LaunchedEffect(pool) {
        isSpinning = false
        unfolded = false
        if (pool.isNotEmpty()) {
            listState.scrollToItem(openingIndex)
            delay(RandomUnfoldDelayMs)
            unfolded = true
        }
    }

    LaunchedEffect(picked) {
        if (picked == null || !unfolded) return@LaunchedEffect
        landing.snapTo(1f)
        landing.animateTo(0f, tween(durationMillis = RandomTurnDurationMs))
    }

    /**
     * One tick per detent, throttled, and only while the wheel is actually moving.
     *
     * The flow reads `-1` at rest, so a drum parked between spins emits nothing rather than a
     * steady tick. Throttling is what keeps a fast spin from degenerating into one continuous
     * vibration: at speed the centre slot changes more often than a 40ms gap, and dropping the
     * surplus is what makes the ticks read as passing slots.
     */
    LaunchedEffect(listState) {
        val clock = TimeSource.Monotonic
        var lastTick = clock.markNow() - RandomDetentInterval
        snapshotFlow {
            if (listState.isScrollInProgress) listState.randomCenteredIndex() else -1
        }
            .distinctUntilChanged()
            .collect { index ->
                if (index < 0) return@collect
                val now = clock.markNow()
                if (now - lastTick >= RandomDetentInterval) {
                    lastTick = now
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
    }

    fun spin() {
        if (pool.isEmpty()) return
        isSpinning = true
        scope.launch {
            val steps = randomSpinSteps(pool.size, Random.Default)
            listState.randomSpinBy(
                steps = steps,
                rowPx = rowPx,
                durationMs = randomSpinDurationMs(steps),
            )
            isSpinning = false
        }
    }

    fun turnTo(poolIndex: Int) {
        if (pool.isEmpty()) return
        isSpinning = true
        scope.launch {
            // This LazyListState has no animating `scrollToItem` overload, so the turn is a
            // single `animateScrollBy` over whole rows: every row is `RandomRowHeight` tall, so
            // a row count converts to pixels exactly and no item offset is ever needed.
            val fromRow = listState.firstVisibleItemIndex
            listState.animateScrollBy(
                (openingIndex + poolIndex - fromRow).toFloat() * rowPx,
                tween(durationMillis = RandomTurnDurationMs),
            )
            isSpinning = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            // The whole point of the page, and the one thing the first version omitted entirely:
            // a destination has to clear the system bars. Background first so it reaches the
            // edges, insets after so only the content is pushed in.
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                start = Dimensions.PageHorizontalPaddingCompact,
                end = Dimensions.PageHorizontalPaddingCompact,
                top = Dimensions.CardInternalPadding,
                bottom = Dimensions.SectionSpacing,
            ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.SmallSpacing),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SmallSpacing),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Spin",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Let the wheel pick what plays next",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                CrispyIcon(
                    painter = painterResource(Res.drawable.ic_close),
                    contentDescription = "Close",
                )
            }
        }

        RandomChipRow(
            genres = genres,
            selectedGenre = selectedGenre,
            onGenreSelected = { selectedGenre = it },
        )

        Spacer(modifier = Modifier.height(Dimensions.SmallSpacing))

        // The drum takes the rest of the column, so it is as tall as the device rather than a
        // fixed three rows -- and `contentPadding` makes the list's own first and last slots sit
        // on the centre line, which is what lets an end slot land in the middle.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val edge = ((maxHeight - RandomRowHeight) / 2).coerceAtLeast(0.dp)
            when {
                uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                pool.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Dimensions.CardInternalPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Nothing to spin from yet.",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = edge),
                    flingBehavior = rememberSnapFlingBehavior(
                        lazyListState = listState,
                        snapPosition = SnapPosition.Center,
                    ),
                ) {
                    items(
                        count = RandomWheelLaps * pool.size,
                        key = { index -> index },
                        // One composition serves every slot. Without it the list composes a fresh
                        // row for each of 400 laps' worth of keys it never reuses.
                        contentType = { 0 },
                    ) { index ->
                        RandomWheelRow(
                            candidate = pool[index % pool.size],
                            index = index,
                            listState = listState,
                            rowPx = rowPx,
                            density = localDensity.density,
                            unfold = unfold,
                            landing = landing.value,
                            isCentred = index == chosenIndex,
                            onClick = { turnTo(index % pool.size) },
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.SmallSpacing)) {
            FilledTonalButton(
                onClick = ::spin,
                enabled = pool.isNotEmpty() && !isSpinning,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isSpinning) "Spinning…" else "Spin")
            }
            Button(
                onClick = { picked?.let(onPlay) },
                enabled = picked != null && !isSpinning,
                modifier = Modifier.weight(1f),
            ) {
                Text("Play")
            }
        }
    }
}

@Composable
private fun RandomChipRow(
    genres: List<HomeRandomGenre>,
    selectedGenre: String?,
    onGenreSelected: (String?) -> Unit,
) {
    // `All` carries the layers glyph, which is the same glyph the discover sheet gives its
    // "All genres" row, and every genre carries the same `genreIcon` the hero carousel gives its
    // metadata -- so a chip and a hero row read the same genre the same way.
    val options: List<Pair<String?, DrawableResource>> = buildList {
        add(null to Res.drawable.ic_layers)
        genres.forEach { add(it.genre to genreIcon(it.genre)) }
    }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEachIndexed { index, (genre, icon) ->
            FilterChip(
                selected = genre != null && genre.equals(selectedGenre, ignoreCase = true),
                onClick = { onGenreSelected(genre) },
                modifier = Modifier.height(RandomChipHeight),
                label = {
                    Text(
                        text = genre ?: "All",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    CrispyIcon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(RandomChipIconSize),
                    )
                },
                shape = randomChipShape(index, options.size),
                border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

/**
 * The connected-segment shape, written out rather than taken from a button-group helper.
 *
 * The row reads as one control because its neighbours share an edge, and that is four corner
 * radii. There is no behaviour here worth an API this project has never exercised.
 */
private fun randomChipShape(index: Int, count: Int): Shape {
    val start = if (index == 0) RandomChipOuterCorner else RandomChipJoinCorner
    val end = if (index == count - 1) RandomChipOuterCorner else RandomChipJoinCorner
    return RoundedCornerShape(topStart = start, topEnd = end, bottomEnd = end, bottomStart = start)
}

/**
 * One drum row: a circular cover beside the title and the type/genre line.
 *
 * [rowPx] is passed as a number because [GraphicsLayerScope] is not a composable, so it cannot
 * read `LocalDensity` for itself, and [unfold] and [landing] are passed already animated because
 * one animation drives every visible row rather than each row running its own.
 */
@Composable
private fun RandomWheelRow(
    candidate: HomeRandomCandidate,
    index: Int,
    listState: LazyListState,
    rowPx: Float,
    density: Float,
    unfold: Float,
    landing: Float,
    isCentred: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillWidth by animateDpAsState(
        targetValue = if (isCentred) RandomCentredRowWidth else RandomRestingRowWidth,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "randomRowWidth",
    )
    val coverModel = rememberCrispyImageModel(
        url = candidate.artworkUrl,
        width = RandomDiscSize,
        height = RandomDiscSize,
    )
    val meta = listOfNotNull(
        randomTypeLabel(candidate.type),
        candidate.genre?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RandomRowHeight)
            .graphicsLayer {
                // Read inside the layer rather than in the composable body on purpose. The body's
                // offset changes on every scroll frame, and reading it there would recompose every
                // visible row -- and re-run its image request -- sixty times a second mid-spin,
                // which is what the reference project learned the hard way.
                randomWheelFace(
                    distanceInRows = listState.randomDistanceFromCenter(index, rowPx),
                    unfold = unfold,
                    landing = landing,
                    density = density,
                    rowPx = rowPx,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .width(pillWidth)
                .clip(CircleShape)
                .background(
                    if (isCentred) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0f)
                    },
                )
                .clickable(onClick = onClick)
                .padding(horizontal = RandomRowHeight / 8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.SmallSpacing * 2),
        ) {
            Box(
                modifier = Modifier
                    .size(RandomDiscSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The drum's 3D face, from the row's own distance in rows from the centre slot.
 *
 * ## Every term is in row units
 *
 * [distanceInRows] is a count of rows, not a fraction of the screen, and nothing divides it down.
 * The first version divided it by three to "soften" the curve, which put the arc cap 8.5 rows
 * out of reach on a screen showing three -- the wheel looked like a straight list because the
 * maths said it was one. `a` here is the row's distance in rows, exactly as the reference
 * project's drum computes it, so a row one slot out already bends by a full arc step.
 *
 * [unfold] springs 0 -> 1. The geometry -- tilt, arc, falloff -- is computed from [distanceInRows]
 * *unattenuated*, exactly as the reference project's drum does, so a row keeps its bend while the
 * drum opens rather than growing one from zero. The fold itself is positional: [unfold] only
 * damps `local`, and `local` drives `translationY` below, so at zero every row is pulled onto the
 * centre slot and they fan out as the drum opens. Fading the rows where they stood, which is what
 * an earlier version did by multiplying [unfold] into the distance, opens like a crossfade and
 * not like a drum.
 */
private fun GraphicsLayerScope.randomWheelFace(
    distanceInRows: Float,
    unfold: Float,
    landing: Float,
    density: Float,
    rowPx: Float,
) {
    val d = distanceInRows
    val a = abs(d)
    // How "present" a row is: it opens with the drum and is damped the further it sits from the
    // centre, which is what stops the outer slots from reading as a flat band behind the winner.
    val local = (unfold * RandomLocalPerUnfold - a * RandomLocalPerRow).coerceIn(0f, 1f)
    val nearMiddle = (1f - a).coerceAtLeast(0f)
    val arcPx = (a * a * RandomArcStep.toPx()).coerceAtMost(RandomArcLimit.toPx())
    val faceScale = (1f - RandomScaleFalloff * a).coerceAtLeast(RandomScaleFloor)

    transformOrigin = TransformOrigin(0.1f, 0.5f)
    // Required for `alpha` to layer against the rows behind it rather than multiply into them.
    compositingStrategy = CompositingStrategy.ModulateAlpha
    // The fold. `local` is 0 at `unfold` 0, which puts every row exactly on the centre slot, and
    // reaches 1 within about three rows of it at rest -- so this is ~0 for the rows you actually
    // read and carries the opening entirely.
    translationY = -d * rowPx * (1f - local)
    rotationX = (-d * RandomRotationPerRow)
        .coerceIn(-RandomRotationLimit, RandomRotationLimit)
    cameraDistance = RandomCameraDistance * density
    translationX = arcPx
    // Never zero: the floor above keeps the falloff positive, `local` is clamped to [0, 1] so its
    // term is at least its base, and the landing term only ever grows it. `alpha` below is what
    // makes a row disappear, so there is nothing here for a graphics layer to divide by.
    scaleX = faceScale *
        (RandomScaleLocalBase + RandomScaleLocalGain * local) *
        (1f + RandomLandingGain * landing * nearMiddle)
    scaleY = scaleX
    alpha = ((1f - RandomAlphaFalloff * a).coerceIn(0f, 1f) * local)
}

/** The item whose middle sits nearest the viewport's centre, or -1 before the list has measured. */
private fun LazyListState.randomCenteredIndex(): Int {
    val centre = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
    return layoutInfo.visibleItemsInfo
        .minByOrNull { item -> abs(item.offset + item.size / 2 - centre) }
        ?.index
        ?: -1
}

/**
 * How far [index] sits from the centre slot, in rows.
 *
 * A row that is not in `visibleItemsInfo` has not been measured, so it reports a distance large
 * enough that the face is fully folded and fully transparent -- the same answer an off-screen row
 * would have got. [RandomArcLimit]'s cap makes the exact value irrelevant beyond a few rows, which
 * is why the caller can be this loose about it.
 */
private fun LazyListState.randomDistanceFromCenter(index: Int, rowPx: Float): Float {
    if (rowPx <= 0f) return 8f
    val centre = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        ?: return 8f
    return (item.offset + item.size / 2 - centre) / rowPx
}

/**
 * Travel `steps` rows, overshoot by a fifth of a row, then settle back onto a detent.
 *
 * The overshoot is what makes it read as a wheel rather than a list: it leaves the slot with the
 * row still leaning, and the spring brings it upright as it arrives. The fling behaviour's own
 * detent would also snap here, but only after a user-initiated fling, so the snap is explicit.
 */
private suspend fun LazyListState.randomSpinBy(
    steps: Int,
    rowPx: Float,
    durationMs: Int,
) {
    val distance = steps * rowPx
    val overshoot = rowPx * 0.2f
    animateScrollBy(
        distance + overshoot,
        animationSpec = tween(
            durationMillis = durationMs,
            easing = CubicBezierEasing(0.12f, 0.72f, 0.18f, 1f),
        ),
    )
    animateScrollBy(
        -overshoot,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
    )
}
