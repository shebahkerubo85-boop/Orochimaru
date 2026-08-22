package ani.sanin.ui.components

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focusable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ani.sanin.R
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectManager

data class NavTab(val key: String, val label: String, val iconRes: Int)

val HOME_TABS = listOf(
    NavTab("home", "Home", R.drawable.ic_round_home_24),
    NavTab("anime", "Anime", R.drawable.ic_round_movie_filter_24),
    NavTab("discovery", "Discovery", R.drawable.ic_round_filter_list_24),
    NavTab("library", "Library", R.drawable.ic_round_library_books_24)
)

val MEDIA_TABS = listOf(
    NavTab("info", "Info", R.drawable.ic_round_info_24),
    NavTab("watch", "Watch", R.drawable.ic_round_play_arrow_24),
    NavTab("comments", "Comments", R.drawable.ic_round_comment_24)
)

/**
 * Shared pill navigation bar.
 *
 * @param vertical when true the pills are stacked (left rail, used on landscape phones + TV);
 *                 when false they are laid out in a row (bottom bar, used on portrait phones).
 * @param tabs the ordered list of tabs to render.
 */
@Composable
fun NavigationPills(
    viewModel: NavigationPillsViewModel,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    tabs: List<NavTab> = HOME_TABS,
    collapsed: Boolean = false
) {
    val currentTab by viewModel.currentTab.collectAsState()
    val isExpanded by viewModel.isExpanded.collectAsState()

    val pillThickness by animateDpAsState(
        targetValue = if (collapsed) 3.dp else if (isExpanded) 56.dp else 48.dp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow),
        label = "pillThickness"
    )

    val density = LocalDensity.current
    val measuredX = remember { mutableStateListOf<Float?>().apply { repeat(tabs.size) { add(null) } } }
    val measuredY = remember { mutableStateListOf<Float?>().apply { repeat(tabs.size) { add(null) } } }
    val measuredW = remember { mutableStateListOf<Float?>().apply { repeat(tabs.size) { add(null) } } }
    val measuredH = remember { mutableStateListOf<Float?>().apply { repeat(tabs.size) { add(null) } } }

    val indicatorOffsetPx = if (vertical) measuredY.getOrNull(currentTab) else measuredX.getOrNull(currentTab)
    val indicatorSizePx = if (vertical) measuredH.getOrNull(currentTab) else measuredW.getOrNull(currentTab)

    val indicatorOffset by animateDpAsState(
        targetValue = with(density) { (indicatorOffsetPx ?: 0f).toDp() },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "indicatorOffset"
    )
    val indicatorSize by animateDpAsState(
        targetValue = with(density) { (indicatorSizePx ?: (pillThickness.value * density.density)).toDp() },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "indicatorSize"
    )

    val view = LocalView.current
    val glassEnabled = remember { GlassEffectManager.isComponentEnabled(GlassComponent.NavPills) }
    val textColor = if (glassEnabled) {
        val brightness = GlassEffectManager.getAverageBrightness(GlassComponent.NavPills)
        if (brightness > 0.5f) Color(0xFF1A1A1A) else Color.White.copy(alpha = 0.9f)
    } else Color.White.copy(alpha = 0.9f)
    val activeTint = if (glassEnabled) {
        val brightness = GlassEffectManager.getAverageBrightness(GlassComponent.NavPills)
        if (brightness > 0.5f) Color(0xFF1A6BFF) else Color(0xFF87CEEB)
    } else Color(0xFF87CEEB)
    val inactiveTint = if (glassEnabled) {
        val brightness = GlassEffectManager.getAverageBrightness(GlassComponent.NavPills)
        if (brightness > 0.5f) textColor.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.7f)
    } else Color.White.copy(alpha = 0.7f)

    Box(
        modifier = modifier
            .then(if (vertical) Modifier.width(pillThickness) else Modifier.fillMaxWidth())
            .then(if (vertical) Modifier.wrapContentHeight() else Modifier.wrapContentHeight())
            .padding(
                horizontal = if (collapsed) 0.dp else if (vertical) 8.dp else 16.dp,
                vertical = if (collapsed) 0.dp else if (vertical) 16.dp else 8.dp
            ),
        contentAlignment = if (vertical) Alignment.TopCenter else Alignment.Center
    ) {
        if (glassEnabled) {
            AndroidView(
                factory = { ctx ->
                    View(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        post {
                            GlassEffectManager.applyGlass(this, GlassComponent.NavPills, 50f)
                        }
                    }
                },
                modifier = Modifier
                    .matchParentSize()
                    .focusable(false)
            )
        }

        // Sliding indicator (borrowed from Echo-Music FloatingNavigationToolbar):
        // a pill background that animates behind the active item.
        Box(
            modifier = Modifier
                .alpha(if (collapsed) 0f else 1f)
                .then(if (vertical) Modifier.offset(y = indicatorOffset) else Modifier.offset(x = indicatorOffset))
                .then(if (vertical) Modifier.height(indicatorSize) else Modifier.width(indicatorSize))
                .then(if (vertical) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                .clip(RoundedCornerShape(50))
                .background(
                    brush = if (glassEnabled) Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.Transparent)
                    ) else Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            activeTint.copy(alpha = 0.8f),
                            activeTint.copy(alpha = 0.2f)
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )

        val pillsRowModifier = Modifier
            .then(if (vertical) Modifier.fillMaxWidth() else Modifier.height(pillThickness))
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) {
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionUp -> {
                            if (currentTab > 0) {
                                viewModel.setTab(currentTab - 1)
                            } else {
                                (view.parent as? View)?.focusSearch(View.FOCUS_LEFT)?.requestFocus()
                            }
                            true
                        }
                        Key.DirectionRight, Key.DirectionDown -> {
                            if (currentTab < tabs.lastIndex) {
                                viewModel.setTab(currentTab + 1)
                            } else {
                                (view.parent as? View)?.focusSearch(View.FOCUS_RIGHT)?.requestFocus()
                            }
                            true
                        }
                        Key.Enter, Key.DirectionCenter -> {
                            viewModel.setTab(currentTab)
                            true
                        }
                        else -> false
                    }
                } else false
            }

        if (vertical) {
            Column(
                modifier = pillsRowModifier,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                tabs.forEachIndexed { index, navTab ->
                    NavigationPill(
                        index = index,
                        iconRes = navTab.iconRes,
                        label = navTab.label,
                        isActive = currentTab == index,
                        isExpanded = isExpanded,
                        vertical = true,
                        collapsed = collapsed,
                        textColor = textColor,
                        activeTint = activeTint,
                        inactiveTint = inactiveTint,
                        onMeasure = { x, y, w, h ->
                            measuredX[index] = x
                            measuredY[index] = y
                            measuredW[index] = w
                            measuredH[index] = h
                        }
                    )
                }
            }
        } else {
            Row(
                modifier = pillsRowModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { index, navTab ->
                    NavigationPill(
                        index = index,
                        iconRes = navTab.iconRes,
                        label = navTab.label,
                        isActive = currentTab == index,
                        isExpanded = isExpanded,
                        vertical = false,
                        collapsed = collapsed,
                        textColor = textColor,
                        activeTint = activeTint,
                        inactiveTint = inactiveTint,
                        onMeasure = { x, y, w, h ->
                            measuredX[index] = x
                            measuredY[index] = y
                            measuredW[index] = w
                            measuredH[index] = h
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun NavigationPill(
    index: Int,
    iconRes: Int,
    label: String,
    isActive: Boolean,
    isExpanded: Boolean,
    vertical: Boolean,
    collapsed: Boolean = false,
    textColor: Color = Color.White,
    activeTint: Color = Color(0xFF87CEEB),
    inactiveTint: Color = Color.White.copy(alpha = 0.7f),
    onMeasure: (x: Float, y: Float, w: Float, h: Float) -> Unit = { _, _, _, _ -> }
) {
    val transition = updateTransition(targetState = isActive, label = "pillPop_$index")
    val popScale by transition.animateFloat(
        transitionSpec = {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        },
        label = "pillPopScale_$index"
    ) { selected -> if (selected) 1.12f else 1f }

    val pillSize by animateDpAsState(
        targetValue = if (isExpanded) 88.dp else 48.dp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow),
        label = "pillSize_$index"
    )

    Box(
        modifier = Modifier
            .then(if (vertical) Modifier.fillMaxWidth() else Modifier.width(pillSize))
            .then(if (vertical) Modifier.height(pillSize) else Modifier.fillMaxHeight())
            .graphicsLayer { scaleX = popScale; scaleY = popScale }
            .clip(RoundedCornerShape(50))
            .onGloballyPositioned { coordinates ->
                val pos = coordinates.positionInParent()
                val size: IntSize = coordinates.size
                onMeasure(pos.x, pos.y, size.width.toFloat(), size.height.toFloat())
            },
        contentAlignment = Alignment.Center
    ) {
        if (collapsed) return@Box
        if (isExpanded) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = label,
                    tint = if (isActive) activeTint else inactiveTint,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = label,
                    color = if (isActive) textColor else textColor.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = if (isActive) activeTint else inactiveTint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
