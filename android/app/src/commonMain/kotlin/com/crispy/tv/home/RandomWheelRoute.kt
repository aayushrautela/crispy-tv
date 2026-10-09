package com.crispy.tv.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
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
import androidx.compose.ui.util.lerp
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
import com.crispy.tv.ui.components.CrispySectionAppBarTitle
import com.crispy.tv.ui.components.CrispySegmentedButton
import com.crispy.tv.ui.components.CrispySegmentedButtonRow
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.genreIcon
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_arrow_back
import com.crispy.tv.ui.resources.ic_dice
import com.crispy.tv.ui.resources.ic_layers
import com.crispy.tv.ui.resources.ic_play_arrow_filled
import com.crispy.tv.ui.theme.Dimensions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
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

/**
 * The id [RandomChipRow] gives the `All` choice.
 *
 * A sentinel rather than a nullable id because the shared row's options are keyed by a non-null
 * `String` and the library page's three sections already are. It cannot collide with a genre id
 * because the ids are genre *names* -- `topGenres` groups on a lowercased key, so `Sci-Fi` and
 * `sci-fi` are one chip -- and no genre is named `all`.
 */
private const val RANDOM_ALL_GENRES_ID = "all"

/**
 * How much of the drum's foot the spin button's own band takes: its 32.dp lead-in
 * plus the 64.dp button plus its 8.dp bottom margin.
 *
 * The drum's viewport is padded up by exactly this much so that it ends where this band begins,
 * which leaves the wheel settling on the viewport's own centre -- the middle of the rows anybody
 * can read. The band itself sits entirely *below* the drum and is one of these tall, so the rows
 * and nothing else decide where the list ends. It used to be twice this tall, reaching back into
 * the drum to wipe the rows the button would otherwise sit on -- but the rows now fade out at the
 * drum's own edge, and a scrim reaching back dimmed the bottom rows while their mirrors at the
 * top stayed clear, which is half of why the block read as lopsided.
 *
 * Keep the three numbers in step with the overlay below.
 */
private val RandomSpinOverlayHeight = 32.dp + 64.dp + 8.dp

// The 3D face. Tilt, arc and vertical shift are all read off ONE number -- how far round the
// cylinder the row sits -- so the shape cannot disagree with itself, and the curve has no cap
// to run into before the edge of the viewport. Scale and alpha are read off that same number as
// a share of the way to the drum's edge, so the two ends of the drum shrink and fade by exactly
// the same amount: the block closes on itself instead of being cut off by whatever surrounds it.
private const val RandomCameraDistance = 12f
private const val RandomScaleFloor = 0.62f
private const val RandomScaleLocalGain = 0.14f
private const val RandomScaleLocalBase = 0.86f
private const val RandomLandingGain = 0.05f

/** Degrees to radians, the only conversion this file needs: everything else is in radians. */
private val RandomDegToRad = (PI / 180.0).toFloat()

/** A quarter turn, which is where a row's tilt stops meaning anything -- see [randomWheelFace]. */
private val RandomQuarterTurn = (PI / 2).toFloat()

/**
 * How far round the drum its visible face spans, in radians.
 *
 * Forty-five degrees, and it is the whole tuning knob. The centred row sits on the cylinder's
 * tangent and each row further out is tilted a little more, so the wheel reads as a knob turned a
 * little rather than a list bent in the middle.
 *
 * The number is an angle and not a distance in rows, because a phone viewport only spans three
 * or four rows of a 100.dp drum -- so the rows nobody can see are the ones that decide the shape,
 * and the angle is the only thing that survives being sampled coarsely. A quarter turn is the hard
 * ceiling, and 45 degrees sits under it with room to spare, past which a row's own text crowds
 * its cover art.
 *
 * Tuned between two rendered rejections: thirty degrees carried a row one step out only 9% of its
 * own width round the drum -- the arc is there but too shallow to read as a C -- and sixty carried
 * it 16%, with a two-rows-out bulge of 0.6 of a row height, which read as overly round. Forty-five
 * is the midpoint: 13% a row out, half a row height two rows out.
 */
private val RandomEdgeTiltRad = 45f * RandomDegToRad

/**
 * The radius of the cylinder the drum's rows sit on, in pixels.
 *
 * Derived from the viewport rather than fixed, which is the point of the whole file's geometry:
 * a radius measured in *rows* makes the wheel's shape depend on how many rows the screen happens
 * to show, so a phone showing three rows sees the flattest part of the circle and a tablet
 * showing nine sees it bend harder. [RandomEdgeTiltRad] is the angle the drum spans either way,
 * so it is the same curve at every screen size, and the rows past the edge of the viewport keep
 * curving rather than stopping at the last one anybody can see.
 */
internal fun randomWheelRadiusPx(halfSpanPx: Float): Float =
    (halfSpanPx / sin(RandomEdgeTiltRad)).coerceAtLeast(1f)

/**
 * The angle [randomWheelFace] reads for a row standing at the drum's edge, in radians.
 *
 * [randomWheelRadiusPx] sizes the radius from [RandomEdgeTiltRad], but it derives it as a
 * *vertical* projection and the face then walks that radius by arc length -- so the row a
 * half-span out is at `halfSpan / radius`, which is [RandomEdgeTiltRad]'s own sine rather than
 * the forty-five degrees the radius was derived from.
 *
 * Both the fade and the shrink are measured against this rather than against a count of rows,
 * which is what makes the drum self-contained: the last row is gone at the edge of the drum on a
 * phone and on a tablet alike. Measured in rows it was gone five rows out -- a distance a phone
 * never reaches, so its outermost row was clipped away still carrying 40% of its opacity, and
 * where the block ended was whatever the screen happened to cut.
 */
private val RandomFaceEdgeAngleRad = sin(RandomEdgeTiltRad)

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
 * becomes the pick, and **Play** -- which sits on the centred row -- is what carries it to the
 * details page. The first version navigated in a `LaunchedEffect` the instant the wheel
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
 * ## The layout is the reference project's, the chrome is the app's
 *
 * The full-height drum with the Spin button floating over its faded foot, and the Play button
 * sitting on the centred row, are transcribed from the reference project's wheel. The header
 * and the genre row are not: the header is the search page's -- [StandardTopAppBar] with
 * [CrispySectionAppBarTitle] reading "Feeling Lucky" -- and the genre row is the library page's
 * [CrispySegmentedButtonRow]. Only the vocabulary is ours throughout: [CrispyIcon] and the
 * project's drawables instead of Material Icons.
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
    val dice = remember { Animatable(0f) }

    val chosenIndex by remember {
        derivedStateOf {
            if (listState.isScrollInProgress) -1 else listState.randomCenteredIndex()
        }
    }
    // Kept as a State and read only inside layer blocks below, so the drum opening is a
    // redraw per frame rather than a recomposition of this route: the graphics-layer lambda
    // invalidates the layer, while reading the value in the composable body would recompose
    // every visible row -- and re-run its image request -- sixty times a second. This is the
    // same reason the face reads the list's own layout inside the layer.
    val unfoldState = animateFloatAsState(
        targetValue = if (unfolded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 260f),
        label = "randomUnfold",
    )
    // A kick of velocity on landing: the slot pops and settles. Fired from [spin] once the
    // wheel has stopped, rather than on every pick change -- which would also fire when a chip
    // change re-seats the drum. It decays rather than latches, so it cannot leave a permanent
    // pop on the winner.
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
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }
    }

    fun spin() {
        if (pool.isEmpty()) return
        isSpinning = true
        scope.launch {
            try {
                val steps = randomSpinSteps(pool.size, Random.Default)
                val durationMs = randomSpinDurationMs(steps)
                // The die turns with the wheel and slows with it.
                launch { dice.animateTo(dice.value + 720f, tween(durationMs, easing = CubicBezierEasing(0.12f, 0.72f, 0.18f, 1f))) }
                listState.randomSpinBy(
                    steps = steps,
                    rowPx = rowPx,
                    durationMs = durationMs,
                )
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                launch {
                    landing.snapTo(0f)
                    landing.animateTo(
                        0f,
                        spring(dampingRatio = 0.35f, stiffness = 420f),
                        initialVelocity = 7f,
                    )
                }
            } finally {
                // A thumb on the wheel cancels the spin mid-turn, which is the wheel doing
                // what a wheel does; the button comes back.
                isSpinning = false
            }
        }
    }

    /**
     * Turns the wheel so [index] sits in the centre slot: a relative scroll from where the
     * wheel is, over a spring, which is what makes a tap read as a turn rather than a jump.
     * Relative matters because the drum holds hundreds of laps: an absolute lap-plus-offset
     * target would fling across the whole list instead of turning to the tapped row.
     */
    fun turnTo(index: Int) {
        if (pool.isEmpty()) return
        isSpinning = true
        scope.launch {
            try {
                listState.animateScrollBy(
                    listState.randomDistanceFromCenter(index, rowPx) * rowPx,
                    spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                )
            } finally {
                isSpinning = false
            }
        }
    }

    // The search page's structure: a full-bleed app bar on a Scaffold, with the page content
    // padded below it. The bar carries the system-bars inset itself, so the content only pads
    // the navigation bars at the foot -- and the drum below is full-bleed like the reference
    // project's, with each row carrying its own horizontal padding.
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            StandardTopAppBar(
                title = { CrispySectionAppBarTitle(label = "Feeling Lucky") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        CrispyIcon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            autoMirror = true,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SmallSpacing),
        ) {
        RandomChipRow(
            genres = genres,
            selectedGenre = selectedGenre,
            onGenreSelected = { selectedGenre = it },
        )

        // The drum takes the rest of the column, so it is as tall as the device rather than a
        // fixed three rows -- and `contentPadding` makes the list's own first and last slots sit
        // on the centre line, which is what lets an end slot land in the middle. The viewport
        // stops where the floating spin button starts (see [RandomSpinOverlayHeight]), so the
        // settle line is the visible middle, not the middle of a taller box whose foot is covered.
        // The padding sits on the viewport, not on this Box: padding the Box instead pushes the
        // overlay up with it and leaves the drum's foot -- the band the button is meant to sit
        // on -- empty below it.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = RandomSpinOverlayHeight),
                contentAlignment = Alignment.Center,
            ) {
                // [maxHeight] is the *padded* height -- the padding above is part of this
                // viewport's own modifier chain, so the button's band is already out of it and
                // subtracting it again would shrink the drum's ends off the centre line.
                val edge = ((maxHeight - RandomRowHeight) / 2).coerceAtLeast(0.dp)
                // The cylinder the drum's rows sit on, sized from what is actually on screen so
                // the curve is the same curve on a phone and on a tablet.
                val radiusPx = with(localDensity) { randomWheelRadiusPx(maxHeight.toPx() / 2f) }
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
                            val candidate = pool[index % pool.size]
                            RandomWheelRow(
                                candidate = candidate,
                                isCentred = index == chosenIndex,
                                onPlay = { onPlay(candidate) },
                                // The tapped slot's own index, laps included: the turn is a relative
                                // scroll from where the wheel is, so it must start there.
                                onClick = { turnTo(index) },
                                modifier = Modifier
                                    .height(RandomRowHeight)
                                    .graphicsLayer {
                                        // Read inside the layer rather than in the composable body on purpose. The body's
                                        // offset changes on every scroll frame, and reading it there would recompose every
                                        // visible row -- and re-run its image request -- sixty times a second mid-spin,
                                        // which is what the reference project learned the hard way.
                                        applyRandomWheelFace(
                                            randomWheelFace(
                                                distanceInRows = listState.randomDistanceFromCenter(index, rowPx),
                                                rowPx = rowPx,
                                                radiusPx = radiusPx,
                                                unfold = unfoldState.value,
                                                landing = landing.value,
                                            ),
                                        )
                                    },
                            )
                        }
                    }
                }
            }

            // The button's band, parked *below* the drum rather than laid over it. The rows close
            // on themselves -- [randomWheelFace] has faded the last of them out before the
            // viewport's edge -- so there are no rows here left to hide, and a scrim reaching
            // back into the drum would dim the bottom rows while their mirrors at the top stayed
            // clear. What is left is a short lead-in so the button does not sit on a hard line.
            // The scrim has no click of its own, so a drag that starts on it still turns the
            // wheel.
            val page = MaterialTheme.colorScheme.background
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(RandomSpinOverlayHeight)
                    .background(
                        Brush.verticalGradient(
                            0f to page.copy(alpha = 0f),
                            0.225f to page.copy(alpha = 0.9f),
                            0.5f to page,
                            1f to page,
                        )
                    )
                    .padding(horizontal = 20.dp)
                    .padding(top = 32.dp, bottom = 8.dp),
            ) {
                Button(
                    onClick = { spin() },
                    enabled = pool.isNotEmpty() && !isSpinning,
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    CrispyIcon(
                        painter = painterResource(Res.drawable.ic_dice),
                        contentDescription = null,
                        modifier = Modifier
                            .size(26.dp)
                            .graphicsLayer { rotationZ = dice.value },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Spin",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
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
    // metadata -- so a button and a hero row read the same genre the same way.
    //
    // The library page's connected group, at its intrinsic widths: genre labels are variable-length
    // and there are up to four of them, so an equal share of a phone's width would truncate
    // `Documentary`.
    val options = remember(genres) {
        buildList {
            add(CrispySegmentedButton(id = RANDOM_ALL_GENRES_ID, label = "All", icon = Res.drawable.ic_layers))
            genres.forEach { genre ->
                add(CrispySegmentedButton(id = genre.genre, label = genre.genre, icon = genreIcon(genre.genre)))
            }
        }
    }
    CrispySegmentedButtonRow(
        options = options,
        selectedId = selectedGenre ?: RANDOM_ALL_GENRES_ID,
        onSelect = { id -> onGenreSelected(id.takeUnless { it == RANDOM_ALL_GENRES_ID }) },
        modifier = Modifier.padding(horizontal = 20.dp),
        fillWidth = false,
    )
}

/**
 * One drum row: a circular cover beside the title and the type/genre line, and -- once the
 * wheel settles on it -- a Play button.
 *
 * The pill behind the row is drawn in [drawBehind] and clipped to a stadium, exactly as the
 * reference project draws it: the row reads as a pill that grows around the centred slot.
 */
@Composable
private fun RandomWheelRow(
    candidate: HomeRandomCandidate,
    isCentred: Boolean,
    onPlay: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept as a State and read only in draw and layer lambdas, so the pill growing is a redraw,
    // not a recomposition per frame.
    val pill by animateFloatAsState(
        targetValue = if (isCentred) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "randomPill",
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
    val pillColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .drawBehind {
                val p = pill
                if (p > 0.01f) {
                    drawRoundRect(
                        color = pillColor.copy(alpha = p),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
            }
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(RandomDiscSize)
                .graphicsLayer {
                    // The centred cover grows with its pill, the way the reference disc does.
                    // `pill` is read inside the layer, so the growth is a redraw per frame
                    // rather than a recomposition.
                    val grow = lerp(0.88f, 1f, pill)
                    scaleX = grow
                    scaleY = grow
                }
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
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(
            visible = isCentred,
            enter = scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                initialScale = 0.6f,
            ) + fadeIn(tween(150)),
            exit = scaleOut(targetScale = 0.6f) + fadeOut(tween(120)),
        ) {
            Surface(
                onClick = onPlay,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CrispyIcon(
                        painter = painterResource(Res.drawable.ic_play_arrow_filled),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Play",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** The five layer properties one drum row is drawn with, kept together so the shape can be read. */
internal data class RandomWheelFace(
    val tiltDeg: Float,
    val arcPx: Float,
    val shiftYPx: Float,
    val scale: Float,
    val alpha: Float,
)

/**
 * Where one row sits on the drum, from its own distance in rows from the centre slot.
 *
 * ## Every term comes out of one angle
 *
 * [angle] is how far round the cylinder the row sits, and tilt, arc and vertical shift are all
 * read off it, so the shape cannot disagree with itself. Nothing clamps it before a quarter
 * turn, which a row only reaches well outside the viewport, so no two visible rows can end up
 * sharing a tilt and an offset -- the flat ends of a bend, which is what the top of a tall
 * viewport used to look like.
 *
 * Everything but the *sign* of the tilt and the direction of the shift is read off [angle], and
 * [angle] depends on the distance's magnitude alone. That is the whole of the drum's symmetry:
 * a row one out above the centre and a row one out below it are drawn the same distance from it,
 * curving the same way, fading and shrinking the same amount. The shift is the one term that has
 * to carry the sign itself -- see [shiftYPx].
 *
 * ## `open` and `progress` are two things, and they used to be one
 *
 * `local` was the fold *and* a falloff with distance: `(unfold * 1.6 - a * 0.18)`, which reaches
 * 1 about three rows out and then decays. Past that it dragged every row back towards its
 * neighbours in the resting drum, not just while opening -- measured on a 970dp viewport, the
 * rows four, five and six out were drawn at 352, 350 and 312 against a 100dp pitch, so row six
 * sat *behind* row four and the top of the drum was a flat, out-of-order smear. [open] is the
 * drum opening and nothing else; [progress] is how far out the row sits, as a share of the way
 * to the drum's own edge, and both the fade and the shrink are read off it -- so the two ends of
 * the drum are given the same treatment by construction rather than by two falloffs agreeing.
 *
 * [unfold] springs 0 -> 1 and the fold is positional, so a closed drum has every row drawn on
 * the centre slot and they fan out as it opens. Fading the rows where they stood, which is what
 * an earlier version did by multiplying [unfold] into the distance, opens like a crossfade and
 * not like a drum.
 */
internal fun randomWheelFace(
    distanceInRows: Float,
    rowPx: Float,
    radiusPx: Float,
    unfold: Float,
    landing: Float,
): RandomWheelFace {
    val d = distanceInRows
    val a = abs(d)
    val angle = ((a * rowPx) / radiusPx).coerceAtMost(RandomQuarterTurn)
    val open = unfold.coerceIn(0f, 1f)
    // How far out the row sits, as a share of the way to the drum's own edge. `angle` depends on
    // `a` alone, so a row above the centre and its mirror below are given the same number here.
    val progress = (angle / RandomFaceEdgeAngleRad).coerceIn(0f, 1f)
    return RandomWheelFace(
        // A quarter turn: the row at the cylinder's tangent, which no visible row reaches.
        tiltDeg = -sign(d) * angle / RandomDegToRad,
        // The sagitta of this slice of the circle, so the curvature is the same everywhere
        // along the drum the way a knob's face is.
        arcPx = radiusPx * (1f - cos(angle)),
        // The cylinder's own spacing, and the fold. `sin` is concave, so a row set out `a` rows
        // from the centre belongs nearer the centre than the flat list put it -- which is what
        // keeps the rows the same distance apart as they foreshorten instead of fanning apart
        // into gaps at the ends.
        //
        // It carries `sign(d)` because the row's own place in the list carries the sign: this
        // term is added to where the list already put the row, so the same unsigned pull on both
        // sides moves a row below the centre toward it and a row above the centre *away* from
        // it. That asymmetry is what made the bottom two rows sit tight against each other while
        // the top two fanned open -- the drum was half a circle and half a fan.
        shiftYPx = -(d * rowPx) * (1f - open) +
            sign(d) * (radiusPx * sin(angle) - a * rowPx) * open,
        scale = (1f - (1f - RandomScaleFloor) * progress) *
            (RandomScaleLocalBase + RandomScaleLocalGain * open) *
            (1f + RandomLandingGain * landing * (1f - a).coerceAtLeast(0f)),
        // Gone at the drum's edge rather than five rows out: the fade and the shrink both end
        // where the drum does, on every screen size, so the block closes on itself.
        alpha = (1f - progress) * open,
    )
}

/**
 * [face] onto the layer.
 *
 * The camera distance is the one term that is not geometry: it is how hard the perspective is,
 * and it is read off the layer's own density rather than passed in.
 */
private fun GraphicsLayerScope.applyRandomWheelFace(face: RandomWheelFace) {
    transformOrigin = TransformOrigin(0.1f, 0.5f)
    // Required for `alpha` to layer against the rows behind it rather than multiply into them.
    compositingStrategy = CompositingStrategy.ModulateAlpha
    rotationX = face.tiltDeg
    cameraDistance = RandomCameraDistance * density
    translationX = face.arcPx
    translationY = face.shiftYPx
    // Never zero: the floor above keeps the falloff positive, `open` is clamped to [0, 1] so its
    // term is at least its base, and the landing term only ever grows it. `alpha` is what makes a
    // row disappear, so there is nothing here for a graphics layer to divide by.
    scaleX = face.scale
    scaleY = face.scale
    alpha = face.alpha
}

/** The item whose middle sits nearest the viewport's centre, or -1 before the list has measured. */
private fun LazyListState.randomCenteredIndex(): Int {
    val info = layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo
        .minByOrNull { item -> abs(item.offset + item.size / 2f - middle) }
        ?.index
        ?: firstVisibleItemIndex
}

/**
 * How far [index] sits from the centre slot, in rows.
 *
 * A row that is not in `visibleItemsInfo` has not been measured, so it reports a distance far
 * enough out to be past the drum's edge -- the same answer an off-screen row would have got. Such
 * a row is outside the viewport and clipped whether it has an alpha or not, so the exact value is
 * irrelevant beyond a few rows -- which is why the caller can be this loose about it.
 */
private fun LazyListState.randomDistanceFromCenter(index: Int, rowPx: Float): Float {
    if (rowPx <= 0f) return 6f
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        ?: return if (index < firstVisibleItemIndex) -6f else 6f
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return (item.offset + item.size / 2f - middle) / rowPx
}

/**
 * Travel `steps` rows, overshoot by a fifth of a row, then settle back onto a detent.
 *
 * The distance is `steps` full rows plus the fraction already travelled past the visible
 * middle, so the pick lands exactly on the settle line. Without that correction the wheel
 * rests a fraction of a row off and the face reads lopsided. The overshoot is what makes it
 * read as a wheel rather than a list: it leaves the slot with the row still leaning, and the
 * spring brings it upright as it arrives. The fling behaviour's own detent would also snap
 * here, but only after a user-initiated fling, so the snap is explicit.
 */
private suspend fun LazyListState.randomSpinBy(
    steps: Int,
    rowPx: Float,
    durationMs: Int,
) {
    val start = randomCenteredIndex()
    val distance = (steps + randomDistanceFromCenter(start, rowPx)) * rowPx
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
