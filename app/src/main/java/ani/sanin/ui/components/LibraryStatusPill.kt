package ani.sanin.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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

/**
 * Hand focus to the next focusable outside the pill in [direction], so DPAD can leave the
 * capsule for the chrome around it. Returns whether a target was found, so the key is only
 * swallowed when focus actually moved (otherwise focus would be trapped on the pill).
 */
private fun leaveFocus(host: View?, direction: Int): Boolean {
    val target = (host?.parent as? View)?.focusSearch(direction) ?: return false
    return target.requestFocus()
}

@Composable
fun LibraryStatusPill(
    tabs: List<LibraryStatusTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
    compact: Boolean = true,
) {
    val context = LocalContext.current
    // The pill can sit under an AppBar theme overlay (fragment_library.xml applies
    // Theme.Sanin.AppBarOverlay to the toolbar). That overlay does not carry the accent
    // applied to the Activity at runtime, so resolving colour attrs from the view context
    // fell back to the Material default (purple). Resolve from the owning Activity's theme.
    val colorContext = remember(context) {
        var ctx: Context = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) break
            ctx = ctx.baseContext
        }
        ctx
    }
    val dark = isDarkTheme()
    val accent = Color(colorContext.getThemeColor(R.attr.colorPrimary))
    val textPrimary = Color(colorContext.getThemeColor(R.attr.colorOnSurface))
    val textSecondary = Color(colorContext.getThemeColor(R.attr.colorOnSurfaceVariant))
    val textOnAccent = Color(colorContext.getThemeColor(R.attr.colorOnPrimary))
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
    var pillFocused by remember { mutableStateOf(false) }
    // Forward focus from the host ComposeView (XML nextFocus) into the Compose focusable.
    LaunchedEffect(view) {
        view?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth())
            // Light mode draws a drop shadow, so keep enough room around the capsule that
            // the host ComposeView does not clip it.
            .padding(
                start = if (fillWidth) 16.dp else 6.dp,
                top = if (isLightTheme) 6.dp else 0.dp,
                end = if (fillWidth) 16.dp else 6.dp,
                bottom = if (isLightTheme) 6.dp else 0.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth())
                .onSizeChanged { containerWidthPx = it.width }
                .then(
                    if (isLightTheme) {
                        Modifier
                            .shadow(
                                elevation = 6.dp,
                                shape = tabShape,
                                clip = false,
                            )
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        lerp(Color.White, accent, 0.06f),
                                        lerp(Color.White, accent, 0.035f),
                                        lerp(Color.White, accent, 0.018f),
                                    ),
                                ),
                                shape = tabShape,
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        accent.copy(alpha = 0.32f),
                                        accent.copy(alpha = 0.18f),
                                        accent.copy(alpha = 0.09f),
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
                        if (pillFocused) {
                            // Focus ring matches the indicator's live size, radius and shape,
                            // so it tracks whichever pill variant (library / catalogue /
                            // extensions) is hosting it.
                            drawRoundRect(
                                color = accent,
                                topLeft = Offset(drawX, animatedY),
                                size = Size(drawWidth, animatedHeight),
                                cornerRadius = CornerRadius(radiusPx, radiusPx),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                }
                .focusRequester(focusRequester)
                .onFocusChanged { pillFocused = it.hasFocus }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (selectedIndex > 0) {
                                onTabSelected(selectedIndex - 1)
                                true
                            } else {
                                leaveFocus(view, View.FOCUS_LEFT)
                            }
                        }
                        Key.DirectionRight -> {
                            if (selectedIndex < tabs.lastIndex) {
                                onTabSelected(selectedIndex + 1)
                                true
                            } else {
                                leaveFocus(view, View.FOCUS_RIGHT)
                            }
                        }
                        Key.DirectionUp -> leaveFocus(view, View.FOCUS_UP)
                        Key.DirectionDown -> leaveFocus(view, View.FOCUS_DOWN)
                        Key.Enter, Key.DirectionCenter -> {
                            onTabSelected(selectedIndex)
                            true
                        }
                        else -> false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                LibraryStatusTabView(
                    tab = tab,
                    isSelected = index == selectedIndex,
                    isLightTheme = isLightTheme,
                    compact = compact,
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
    compact: Boolean,
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
            .padding(
                horizontal = if (compact) 14.dp else 16.dp,
                vertical = if (compact) 7.dp else 10.dp,
            ),
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
                    fontSize = if (compact) 13.sp else 14.sp,
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