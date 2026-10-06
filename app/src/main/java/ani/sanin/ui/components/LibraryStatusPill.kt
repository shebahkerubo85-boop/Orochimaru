package ani.sanin.ui.components

import android.view.View
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ani.sanin.getThemeColor
import ani.sanin.isDarkTheme
import com.google.android.material.R
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Ported pill (library status tabs) mirroring the Aurora tab capsule from the Tadami
 * Aniyomi fork: a rounded capsule container with a self-animating selection pill using
 * asymmetric edge springs, DPAD/focus support for TV.
 */

data class LibraryStatusTab(val label: String, val count: Int)

private const val AURORA_TAB_LEADING_STIFFNESS = 500f
private const val AURORA_TAB_TRAILING_STIFFNESS = 250f
private const val AURORA_TAB_SPRING_DAMPING = 0.78f

private fun resolveAsymmetricTabEdgeStiffness(isMovingRight: Boolean): Pair<Float, Float> {
    return if (isMovingRight) {
        AURORA_TAB_TRAILING_STIFFNESS to AURORA_TAB_LEADING_STIFFNESS
    } else {
        AURORA_TAB_LEADING_STIFFNESS to AURORA_TAB_TRAILING_STIFFNESS
    }
}

private fun auroraTabEdgeSpring(stiffness: Float): SpringSpec<Float> {
    return spring(
        dampingRatio = AURORA_TAB_SPRING_DAMPING,
        stiffness = stiffness,
    )
}

private fun resolveAsymmetricTabStretchRadiusFactor(
    drawWidth: Float,
    restWidth: Float,
): Float {
    if (restWidth <= 0f || drawWidth <= 0f) return 1f
    val stretch = (drawWidth / restWidth).coerceIn(0.7f, 2.4f)
    return (1f / stretch.pow(0.35f)).coerceIn(0.55f, 1f)
}

@Composable
private fun rememberAsymmetricTabMovingRight(selectedIndex: Int): Boolean {
    val holder = remember {
        object {
            var previous = selectedIndex
            var movingRight = true
        }
    }
    if (selectedIndex != holder.previous) {
        holder.movingRight = selectedIndex > holder.previous
        holder.previous = selectedIndex
    }
    return holder.movingRight
}

@Composable
private fun rememberAsymmetricTabEdgeSprings(selectedIndex: Int): Pair<SpringSpec<Float>, SpringSpec<Float>> {
    val movingRight = rememberAsymmetricTabMovingRight(selectedIndex)
    return remember(movingRight) {
        val (left, right) = resolveAsymmetricTabEdgeStiffness(movingRight)
        auroraTabEdgeSpring(left) to auroraTabEdgeSpring(right)
    }
}

@Composable
fun LibraryStatusPill(
    tabs: List<LibraryStatusTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isDarkTheme()
    val accent = Color(context.getThemeColor(R.attr.colorPrimary))
    val textPrimary = Color(context.getThemeColor(R.attr.colorOnSurface))
    val textSecondary = Color(context.getThemeColor(R.attr.colorOnSurfaceVariant))
    val textOnAccent = Color(context.getThemeColor(R.attr.colorOnPrimary))
    val isLightTheme = !dark

    val scrollState = rememberScrollState()
    val tabWidths = remember { mutableStateMapOf<Int, Float>() }
    val tabHeights = remember { mutableStateMapOf<Int, Float>() }
    val tabPositionsX = remember { mutableStateMapOf<Int, Float>() }
    val tabPositionsY = remember { mutableStateMapOf<Int, Float>() }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // Auto-scroll to center the selected tab (skip the first two).
    LaunchedEffect(selectedIndex, containerWidthPx) {
        if (containerWidthPx <= 0) return@LaunchedEffect

        if (selectedIndex <= 1) {
            if (scrollState.value != 0) {
                scrollState.animateScrollTo(0, animationSpec = tween(durationMillis = 350))
            }
            return@LaunchedEffect
        }

        val leftPaddingPx = with(density) { 6.dp.toPx() }
        val spacingPx = with(density) { 8.dp.toPx() }
        val accumulatedWidth = (0 until selectedIndex).sumOf { (tabWidths[it] ?: 0f).roundToInt() }
        val currentTabWidth = (tabWidths[selectedIndex] ?: 0f).roundToInt()
        if (currentTabWidth == 0) return@LaunchedEffect

        val tabCenter = leftPaddingPx + accumulatedWidth + selectedIndex * spacingPx + currentTabWidth / 2f
        val targetScroll = (tabCenter - containerWidthPx / 2f).coerceAtLeast(0f).toInt()

        if (scrollState.value != targetScroll) {
            scrollState.animateScrollTo(targetScroll, animationSpec = tween(durationMillis = 350))
        }
    }

    val tabShape = CircleShape
    val selectedTabBrush = remember(accent, dark) {
        Brush.verticalGradient(
            colors = listOf(
                if (dark) {
                    lerp(accent, Color.White, 0.18f).copy(alpha = 0.32f)
                } else {
                    accent.copy(alpha = 0.20f)
                },
                if (dark) {
                    accent.copy(alpha = 0.18f)
                } else {
                    Color.White.copy(alpha = 0.40f)
                },
            ),
        )
    }
    val selectedTabBorderColor = remember(accent, dark) {
        if (dark) accent.copy(alpha = 0.25f) else accent.copy(alpha = 0.28f)
    }
    val tabContainerColor = if (dark) Color.White.copy(alpha = 0.05f) else Color.Transparent

    val activeWidth = tabWidths[selectedIndex] ?: 0f
    val activeHeight = tabHeights[selectedIndex] ?: 0f
    val activeX = tabPositionsX[selectedIndex] ?: 0f
    val activeY = tabPositionsY[selectedIndex] ?: 0f

    val activeLeft = activeX
    val activeRight = activeX + activeWidth

    val (leftSpring, rightSpring) = rememberAsymmetricTabEdgeSprings(selectedIndex)
    val bodySpring = remember { auroraTabEdgeSpring(AURORA_TAB_LEADING_STIFFNESS) }

    val animatedLeft by animateFloatAsState(
        targetValue = activeLeft,
        animationSpec = leftSpring,
        label = "tabLeft",
    )
    val animatedRight by animateFloatAsState(
        targetValue = activeRight,
        animationSpec = rightSpring,
        label = "tabRight",
    )
    val animatedHeight by animateFloatAsState(
        targetValue = activeHeight,
        animationSpec = bodySpring,
        label = "tabHeight",
    )
    val animatedY by animateFloatAsState(
        targetValue = activeY,
        animationSpec = bodySpring,
        label = "tabY",
    )

    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }
    // Forward focus from the host ComposeView (XML nextFocus) into the Compose focusable.
    LaunchedEffect(view) {
        view?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusRequester.requestFocus()
        }
    }

    val leaveLeft = {
        val v = view
        (v?.parent as? View)?.focusSearch(View.FOCUS_LEFT)?.requestFocus()
    }
    val leaveRight = {
        val v = view
        (v?.parent as? View)?.focusSearch(View.FOCUS_RIGHT)?.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { containerWidthPx = it.width }
                .then(
                    if (isLightTheme) {
                        Modifier
                            .drawBehind {
                                val radius = size.height / 2f
                                val cornerRadius = CornerRadius(radius, radius)

                                val neutralOffsetY = 3.dp.toPx()
                                val warmOffsetY = 5.dp.toPx()

                                val neutralInset = 1.dp.toPx()
                                val warmInset = 3.dp.toPx()

                                drawRoundRect(
                                    color = Color.Black.copy(alpha = 0.035f),
                                    topLeft = Offset(x = neutralInset, y = neutralOffsetY),
                                    size = Size(width = size.width - neutralInset * 2, height = size.height),
                                    cornerRadius = cornerRadius,
                                )
                                drawRoundRect(
                                    color = accent.copy(alpha = 0.025f),
                                    topLeft = Offset(x = warmInset, y = warmOffsetY),
                                    size = Size(width = size.width - warmInset * 2, height = size.height),
                                    cornerRadius = cornerRadius,
                                )
                            }
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.78f),
                                        Color.White.copy(alpha = 0.68f),
                                        Color.White.copy(alpha = 0.60f),
                                    ),
                                ),
                                shape = tabShape,
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.75f),
                                        Color.White.copy(alpha = 0.28f),
                                        Color.White.copy(alpha = 0.12f),
                                    ),
                                ),
                                shape = tabShape,
                            )
                    } else {
                        Modifier
                            .background(tabContainerColor, tabShape)
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.00f to Color.White.copy(alpha = 0.38f),
                                        0.50f to Color.White.copy(alpha = 0.18f),
                                        0.75f to Color.Transparent,
                                        1.00f to Color.Transparent,
                                    ),
                                ),
                                shape = tabShape,
                            )
                    },
                )
                .padding(6.dp)
                .horizontalScroll(scrollState)
                .drawBehind {
                    if (animatedRight > animatedLeft && animatedHeight > 0f) {
                        val minWidth = minOf(activeWidth, animatedHeight)
                        val drawWidth = (animatedRight - animatedLeft).coerceAtLeast(minWidth)
                        val drawX = if (animatedRight - animatedLeft < minWidth) {
                            animatedLeft - (minWidth - (animatedRight - animatedLeft)) / 2f
                        } else {
                            animatedLeft
                        }

                        val radiusPx = (animatedHeight / 2f) *
                            resolveAsymmetricTabStretchRadiusFactor(drawWidth, activeWidth.coerceAtLeast(1f))
                        drawRoundRect(
                            brush = selectedTabBrush,
                            topLeft = Offset(drawX, animatedY),
                            size = Size(drawWidth, animatedHeight),
                            cornerRadius = CornerRadius(radiusPx, radiusPx),
                        )
                        drawRoundRect(
                            color = selectedTabBorderColor,
                            topLeft = Offset(drawX, animatedY),
                            size = Size(drawWidth, animatedHeight),
                            cornerRadius = CornerRadius(radiusPx, radiusPx),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (selectedIndex > 0) {
                                    onTabSelected(selectedIndex - 1)
                                } else {
                                    leaveLeft()
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                if (selectedIndex < tabs.lastIndex) {
                                    onTabSelected(selectedIndex + 1)
                                } else {
                                    leaveRight()
                                }
                                true
                            }
                            Key.Enter, Key.DirectionCenter -> {
                                onTabSelected(selectedIndex)
                                true
                            }
                            else -> false
                        }
                    } else false
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                LibraryStatusTabView(
                    tab = tab,
                    isSelected = index == selectedIndex,
                    isLightTheme = isLightTheme,
                    accent = accent,
                    textOnAccent = textOnAccent,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            tabWidths[index] = coords.size.width.toFloat()
                            tabHeights[index] = coords.size.height.toFloat()
                            val pos = coords.positionInParent()
                            tabPositionsX[index] = pos.x
                            tabPositionsY[index] = pos.y
                        },
                )
            }
        }
    }
}

@Composable
private fun LibraryStatusTabView(
    tab: LibraryStatusTab,
    isSelected: Boolean,
    isLightTheme: Boolean,
    accent: Color,
    textOnAccent: Color,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = tab.label,
                color = if (isSelected) {
                    textPrimary
                } else if (isLightTheme) {
                    textSecondary
                } else {
                    textPrimary.copy(alpha = 0.65f)
                },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                textAlign = TextAlign.Center,
            )

            if (tab.count > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                        .background(accent, CircleShape)
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (tab.count > 99) "99+" else tab.count.toString(),
                        color = textOnAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}