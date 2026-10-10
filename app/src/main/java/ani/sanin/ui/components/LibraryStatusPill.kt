package ani.sanin.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import kotlin.math.roundToInt

/**
 * Library status tabs (library / extensions / streaming-catalogue) rendered as a scrollable
 * capsule. Each tab is its own DPAD focus target, mirroring the calendar's week strip: the
 * focused tab (even when unselected) draws an accent oval rim at the exact size and shape of
 * the selection indicator, Enter / DPAD-center / click selects it, and Up/Down (or Left/Right
 * at the edges) hands focus back to the XML chrome via the hosting ComposeView.
 */

data class LibraryStatusTab(val label: String, val count: Int)

/**
 * Hand focus to the next focusable outside the pill in [direction], so DPAD can leave the
 * capsule for the chrome around it. Returns whether a target was found, so the key is only
 * swallowed when focus actually moved (otherwise focus would be trapped on the pill).
 *
 * [host] is the ComposeView the pill sits in: the natural search root for leaving it.
 */
private fun leaveFocus(host: View?, direction: Int): Boolean {
    val target = host?.focusSearch(direction) ?: return false
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
    if (tabs.isEmpty()) return

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
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val focusRequesters = remember(tabs.size) { List(tabs.size) { FocusRequester() } }
    val focusedTabs = remember(tabs.size) { mutableStateMapOf<Int, Boolean>() }
    var focusedIndex by remember { mutableIntStateOf(selectedIndex.coerceIn(0, tabs.lastIndex)) }

    // Auto-scroll to center the focused tab (skip the first two).
    LaunchedEffect(focusedIndex, containerWidthPx) {
        if (containerWidthPx <= 0) return@LaunchedEffect

        if (focusedIndex <= 1) {
            if (scrollState.value != 0) {
                scrollState.animateScrollTo(0, animationSpec = tween(durationMillis = 350))
            }
            return@LaunchedEffect
        }

        val leftPaddingPx = with(density) { 6.dp.toPx() }
        val spacingPx = with(density) { 8.dp.toPx() }
        val accumulatedWidth = (0 until focusedIndex).sumOf { (tabWidths[it] ?: 0f).roundToInt() }
        val currentTabWidth = (tabWidths[focusedIndex] ?: 0f).roundToInt()
        if (currentTabWidth == 0) return@LaunchedEffect

        val tabCenter = leftPaddingPx + accumulatedWidth + focusedIndex * spacingPx + currentTabWidth / 2f
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

    val view = LocalView.current
    // LocalView.current is the internal AndroidComposeView the composition runs in; the
    // ComposeView declared in XML is its parent, and that parent is the View the D-pad focus
    // chain actually moves onto. Forwarding from the internal view never fired because the
    // host swallows focus first, which is why the pill showed no ring and ate no keys.
    val host = remember(view) { (view?.parent as? View) ?: view }

    // Forward focus from the host ComposeView (XML nextFocus) into the last focused tab.
    LaunchedEffect(host) {
        host?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val target = focusedIndex.coerceIn(0, tabs.lastIndex)
                focusRequesters[target].requestFocus()
            }
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
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                LibraryStatusTabView(
                    tab = tab,
                    isSelected = index == selectedIndex,
                    isFocused = focusedTabs[index] == true,
                    isLightTheme = isLightTheme,
                    compact = compact,
                    accent = accent,
                    textOnAccent = textOnAccent,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    selectedTabBrush = selectedTabBrush,
                    selectedTabBorderColor = selectedTabBorderColor,
                    modifier = Modifier
                        .focusRequester(focusRequesters[index])
                        .focusable()
                        .onFocusChanged { state ->
                            if (state.hasFocus) focusedIndex = index
                            focusedTabs[index] = state.hasFocus
                        }
                        // Left/Right between tabs is handled by the Compose focus manager;
                        // it only reaches us at the edges, where we hand focus to the
                        // surrounding chrome. Up/Down always leaves the pill. Handled on
                        // KeyDown: the focus manager also acts on KeyDown, so a press that
                        // moves focus internally is consumed before we see it (no double
                        // move), while an edge/external move falls through to us.
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft ->
                                    if (index == 0) leaveFocus(host, View.FOCUS_LEFT) else false
                                Key.DirectionRight ->
                                    if (index == tabs.lastIndex) leaveFocus(host, View.FOCUS_RIGHT) else false
                                Key.DirectionUp -> leaveFocus(host, View.FOCUS_UP)
                                Key.DirectionDown -> leaveFocus(host, View.FOCUS_DOWN)
                                Key.Enter, Key.DirectionCenter -> {
                                    onTabSelected(index)
                                    true
                                }
                                else -> false
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { onTabSelected(index) }
                        }
                        .onGloballyPositioned { coords ->
                            tabWidths[index] = coords.size.width.toFloat()
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
    isFocused: Boolean,
    isLightTheme: Boolean,
    compact: Boolean,
    accent: Color,
    textOnAccent: Color,
    textPrimary: Color,
    textSecondary: Color,
    selectedTabBrush: Brush,
    selectedTabBorderColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            // The selection indicator fills exactly this slot; the focus rim below uses the
            // same oval, so a focused-but-unselected tab shows a ring of the exact size and
            // shape the selector would occupy (like the calendar's day cells).
            .background(
                brush = if (isSelected) selectedTabBrush else SolidColor(Color.Transparent),
                shape = CircleShape,
            )
            .border(
                width = 1.dp,
                color = if (isSelected) selectedTabBorderColor else Color.Transparent,
                shape = CircleShape,
            )
            .drawBehind {
                if (isFocused) {
                    drawRoundRect(
                        color = accent,
                        cornerRadius = CornerRadius(size.width / 2f, size.height / 2f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
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