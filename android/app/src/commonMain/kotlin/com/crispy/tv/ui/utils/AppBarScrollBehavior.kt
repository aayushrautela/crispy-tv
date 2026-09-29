package com.crispy.tv.ui.utils

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

@Stable
class AppBarScrollBehavior internal constructor() {
    private var offsetPx by mutableFloatStateOf(0f)
    private var heightPx by mutableIntStateOf(0)

    val nestedScrollConnection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val consumed = updateOffset(available.y)
                return Offset(x = 0f, y = consumed)
            }
        }

    fun modifier(): Modifier =
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                heightPx = placeable.height

                val topOffset = offsetPx.roundToInt().coerceIn(-placeable.height, 0)
                val visibleHeight = (placeable.height + topOffset).coerceAtLeast(0)

                layout(placeable.width, visibleHeight) {
                    placeable.placeRelative(x = 0, y = topOffset)
                }
            }
            .clipToBounds()

    private fun updateOffset(delta: Float): Float {
        val height = heightPx
        if (height == 0) return 0f

        val previous = offsetPx
        offsetPx = (offsetPx + delta).coerceIn(-height.toFloat(), 0f)
        return offsetPx - previous
    }
}

@Composable
fun appBarScrollBehavior(): AppBarScrollBehavior = remember { AppBarScrollBehavior() }
