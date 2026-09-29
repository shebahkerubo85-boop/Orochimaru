package ani.sanin.ui.components

import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.animation.TimeInterpolator
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectManager
import ani.sanin.util.NavPillCustomizer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

private const val INDICATOR_WIDTH = "indicator_width"
private const val INDICATOR_HEIGHT = "indicator_height"

class EchoNavPillController(
    private val container: FrameLayout,
    private val backgroundPill: View?,
    private val row: LinearLayout?,
    private val pillList: LinearLayout?,
    private val pills: List<ImageButton>,
    private val labels: List<String>,
    private val onSearch: (() -> Unit)?,
    private val floatToStartOnCollapse: Boolean = false
) {
    private val density = container.resources.displayMetrics.density
    private val thresholdPx = 52f * density
    private val searchGapPx = (8f * density).roundToInt()
    private val tightPaddingPx = (3f * density).roundToInt()
    private val labelGapPx = (5f * density).roundToInt()
    private val pillH = NavPillCustomizer.getHeightDp()
    private val iconRadiusPx = pillH * density / 2f
    private val indicatorRadiusPx = pillH * density / 2f
    /** Translucent tint of the pill it sits on, so it reads on black and on white. */
    private val indicatorColor: Int
        get() = if (NavPillCustomizer.isDarkTheme()) 0x40FFFFFF.toInt() else 0x33000000
    /** Collapsed non-glass button fill: a solid pill, matching the expanded one. */
    private val surfaceColor: Int get() = NavPillCustomizer.getPillFillColor()

    private var selectedIndex = 0
    private var collapsed = false
    private var accumulated = 0f
    private var lastDirection = 0f
    private var savedPillPadding: IntArray? = null
    private var morphVersion = 0
    private var containerWidthAnimator: ValueAnimator? = null
    private var indicatorAnimator: ValueAnimator? = null
    private val scrollViews = LinkedHashSet<View>()
    private val trackedLists = mutableSetOf<RecyclerView>()
    private val lastScrollY = HashMap<View, Int>()
    private var observedRoot: View? = null
    private val treeScrollListener = ViewTreeObserver.OnScrollChangedListener {
        dispatchScrollDeltas()
    }

    val isCollapsed: Boolean get() = collapsed
    val isScrollCapable: Boolean get() = pillList != null

    private val springInterpolator = object : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            return 1f - exp(-t * 5f) * cos(t * 9f)
        }
    }

    private val indicator: View by lazy {
        View(container.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = indicatorRadiusPx
                setColor(indicatorColor)
            }
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * The label is a real child of the pill_list, not a free-floating overlay. That
     * way the LinearLayout reserves genuine horizontal space for it and pushes the
     * sibling pills out of the way by itself. The overlay version painted wider and
     * looked right but the icons never moved, so the text landed on the next icon.
     */
    private val labelView: TextView? by lazy {
        if (labels.isEmpty()) return@lazy null
        // Horizontal rail only. The vertical/TV rail keeps its original behaviour; the
        // only change there is that the gradient is gone.
        if (pillList?.orientation == LinearLayout.VERTICAL) return@lazy null
        TextView(container.context).apply {
            setTextColor(NavPillCustomizer.getIconColor())
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12f, container.resources.displayMetrics
            )
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                container.context, R.font.poppins_semi_bold
            )
            includeFontPadding = false
            isSingleLine = true
            // Never let a long label stretch the bar past the screen margins.
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = labelGapPx
            }
        }
    }

    /** Width the label currently occupies, so it can be animated to 0 on collapse. */
    private var labelWidthPx = 0



    private val searchButton: ImageButton? by lazy {
        if (row == null || onSearch == null) return@lazy null
        ImageButton(container.context).apply {
            setImageResource(R.drawable.ic_round_search_24)
            setBackgroundResource(android.R.color.transparent)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = container.context.getString(R.string.search)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { onSearch() }
        }
    }

    fun attach(initialIndex: Int) {
        selectedIndex = initialIndex
        if (indicator.parent == null) {
            container.addView(indicator, 1)
        }
        labelView?.let { lbl ->
            if (lbl.parent == null) {
                // Sits in pill_list, immediately after the selected pill, so the layout
                // engine gives it real space and shifts the following pills along.
                val anchor = pillList?.indexOfChild(pills.getOrNull(selectedIndex)) ?: -1
                if (pillList != null && anchor >= 0) {
                    pillList.addView(lbl, anchor + 1)
                } else {
                    pillList?.addView(lbl)
                }
            }
        }
        searchButton?.let { sb ->
            if (sb.parent == null) {
                val pillSize = (pillH * density).roundToInt()
                val lp = LinearLayout.LayoutParams(pillSize, pillSize)
                lp.leftMargin = searchGapPx
                row?.addView(sb, lp)
                sb.updateSurface(surfaceColor)
            }
        }
        container.post {
            // Two passes: the first gives the label its width, which changes the
            // container's width and therefore the pills' positions; the second reads
            // the settled geometry so the indicator lands on the right spot.
            positionLabelOverlay()
            repinContainerWidth()
            container.post {
                positionIndicator(selectedIndex, animate = false)
            }
        }
    }

    fun select(index: Int, animate: Boolean = true) {
        if (index !in pills.indices) return
        val oldIndex = selectedIndex
        selectedIndex = index
        // A tab switch can bring a freshly created fragment view (and its scroll
        // container) into the tree, so re-scan for anything we have not seen yet.
        observedRoot?.let { collectScrollables(it, 0) }
        if (collapsed) {
            if (index != oldIndex) refreshCollapsedSelection(oldIndex, index)
            return
        }
        if (index == oldIndex) {
            positionLabelOverlay()
            repinContainerWidth()
            positionIndicator(index, animate)
        } else {
            container.post {
                positionLabelOverlay()
                repinContainerWidth()
                // The label's new width shifts the pills, so read the settled geometry.
                container.post { positionIndicator(index, animate) }
            }
        }
    }

    /**
     * The container width is pinned after a morph, so it has to be re-pinned whenever
     * the label width changes or the settings change, otherwise the bar keeps a stale
     * size. Falls back to WRAP_CONTENT so the layout can size itself naturally.
     */
    private fun repinContainerWidth() {
        if (collapsed) return
        if (labelView == null) {
            // Vertical rail or no labels: let the layout wrap its content as before.
            val lp = container.layoutParams ?: return
            if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                container.layoutParams = lp
            }
            return
        }
        container.post {
            val want = container.measuredWidth.coerceAtMost(maxContainerWidthPx())
            if (want <= 0) return@post
            val lp = container.layoutParams ?: return@post
            if (lp.width != want) {
                lp.width = want
                container.layoutParams = lp
            }
        }
    }

    fun setCollapsed(collapsed: Boolean) {
        if (!isScrollCapable) return
        if (this.collapsed == collapsed) return
        if (collapsed) shrink() else expand()
    }

    /**
     * Tracks scrolling without stealing listeners from anyone else.
     *
     * [android.widget.ScrollView.setOnScrollChangeListener] holds a single listener, so
     * hooking it directly would silently break MediaInfoFragment's corner-radius
     * animation and GlassEffectDrawable's cache invalidation. Instead we only *add*
     * things: RecyclerView gets an extra OnScrollListener (which supports many), while
     * plain scroll views are sampled through one window-wide OnScrollChangedListener.
     */
    fun startScrollTracking(root: View) {
        if (!isScrollCapable) return
        if (observedRoot === root) return
        stopScrollTracking()
        observedRoot = root
        root.viewTreeObserver.addOnScrollChangedListener(treeScrollListener)
        collectScrollables(root, 0)
        root.postDelayed({ collectScrollables(root, 0) }, 150)
        root.postDelayed({ collectScrollables(root, 0) }, 450)
        root.postDelayed({ collectScrollables(root, 0) }, 1000)
        root.postDelayed({ collectScrollables(root, 0) }, 2000)
    }

    fun stopScrollTracking() {
        val root = observedRoot ?: return
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnScrollChangedListener(treeScrollListener)
        observedRoot = null
        scrollViews.clear()
        lastScrollY.clear()
        trackedLists.clear()
    }

    private fun collectScrollables(v: View, depth: Int) {
        if (depth > 12) return
        when {
            v is RecyclerView -> if (trackedLists.add(v)) {
                v.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        forwardScroll(rv, dy)
                    }
                })
            }
            // ScrollView keeps only one scroll listener, so sample it globally instead.
            v is NestedScrollView || v is android.widget.ScrollView -> {
                if (v.isShown) scrollViews.add(v)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectScrollables(v.getChildAt(i), depth + 1)
        }
    }

    private fun dispatchScrollDeltas() {
        val iterator = scrollViews.iterator()
        while (iterator.hasNext()) {
            val view = iterator.next()
            if (!view.isShown) {
                iterator.remove()
                continue
            }
            val y = view.scrollY
            val previous = lastScrollY.put(view, y) ?: y
            forwardScroll(view, y - previous)
        }
    }

    private fun forwardScroll(scroller: View, dy: Int) {
        if (dy == 0) return
        // Ignore rubber-band overscroll: pulling down at the top reports a positive
        // delta, which would otherwise shrink the pill on an upward scroll.
        if (dy > 0 && !scroller.canScrollVertically(1)) return
        if (dy < 0 && !scroller.canScrollVertically(-1)) return
        onScroll(dy.toFloat())
    }

    fun onScroll(dyPixels: Float) {        if (!isScrollCapable) return
        if (dyPixels == 0f) return
        val dir = if (dyPixels > 0f) 1f else -1f
        if (dir != lastDirection) {
            accumulated = 0f
            lastDirection = dir
        }
        accumulated += dyPixels
        if (!collapsed && accumulated >= thresholdPx) {
            accumulated = 0f
            setCollapsed(true)
        } else if (collapsed && accumulated <= -thresholdPx) {
            accumulated = 0f
            setCollapsed(false)
        }
    }

    /**
     * Re-tints the label to match the Icon Tint setting. The activities tint the icon
     * buttons directly, so without this the text would lag behind them whenever the
     * setting changes while a tab is already showing.
     */
    fun refreshLabelTint() {
        labelView?.setTextColor(NavPillCustomizer.getIconColor())
    }

    /**
     * Re-applies the glass/background arrangement for the current state. Call this
     * whenever the rail is re-shown (e.g. onResume) so a collapsed rail keeps its
     * two independent glass buttons instead of regaining the shared pill.
     */
    fun syncGlass() {
        // Called from onResume and whenever the appearance settings are re-applied, so
        // this is where a changed pill height, icon size or tint gets picked up.
        if (!isScrollCapable) {
            applySharedContainerGlass()
            return
        }
        if (collapsed) applyCollapsedGlass() else applyExpandedGlass()
        if (collapsed) {
            val want = container.measuredWidth.coerceAtMost(maxContainerWidthPx())
            val lp = container.layoutParams
            if (want > 0 && lp.width != want) {
                lp.width = want
                container.layoutParams = lp
            }
        } else {
            positionLabelOverlay()
            repinContainerWidth()
        }
    }

    private fun applySharedContainerGlass() {
        if (GlassEffectManager.isComponentEnabled(GlassComponent.NavPills)) {
            GlassEffectManager.applyGlass(
                container,
                GlassComponent.NavPills,
                NavPillCustomizer.getCornerRadiusDp().toFloat()
            )
        } else {
            // No glass: paint one solid fill so the pill actually has a body. Leaving
            // the transparent bg_clay_pill here is what made the icons look detached.
            GlassEffectManager.removeGlass(container)
            NavPillCustomizer.applyPillBackground(container)
        }
    }

    private fun applyCollapsedGlass() {
        // The two collapsed icons are independent surfaces: drop the shared pill
        // entirely and put the glass (or a plain surface) on each button itself.
        GlassEffectManager.removeGlass(container)
        container.background = null
        // No shared pill to clip to; the outline would shave the button circles.
        container.clipToOutline = false
        listOfNotNull(pills.getOrNull(selectedIndex), searchButton)
            .forEach { applyButtonSurface(it) }
    }

    private fun applyExpandedGlass() {
        pills.forEach {
            GlassEffectManager.removeGlass(it)
            it.setBackgroundResource(android.R.color.transparent)
        }
        searchButton?.let {
            GlassEffectManager.removeGlass(it)
            it.setBackgroundResource(android.R.color.transparent)
        }
        container.clipToOutline = true
        applySharedContainerGlass()
    }

    private fun applyButtonSurface(btn: ImageButton) {
        GlassEffectManager.removeGlass(btn)
        if (GlassEffectManager.isComponentEnabled(GlassComponent.NavPills)) {
            GlassEffectManager.applyGlass(btn, GlassComponent.NavPills, pillH / 2f)
        } else {
            btn.updateSurface(surfaceColor)
        }
    }

    /**
     * When collapsing to a single pill the rail detaches from the bottom-center and
     * floats to the bottom-left. Layout margins are deliberately left untouched so the
     * pill keeps breathing room instead of touching the screen edges.
     */
    private fun applyCollapseGravity(collapsedNow: Boolean) {
        if (!floatToStartOnCollapse) return
        val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return
        val target = if (collapsedNow) {
            android.view.Gravity.BOTTOM or android.view.Gravity.START
        } else {
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        }
        if (lp.gravity == target) return
        lp.gravity = target
        container.layoutParams = lp
    }

    private fun shrink() {
        val pl = pillList ?: return
        val selected = pills.getOrNull(selectedIndex) ?: pills.firstOrNull() ?: return
        val others = pills.filter { it !== selected }
        val startWidth = container.width

        morphVersion++
        val version = morphVersion
        containerWidthAnimator?.cancel()
        collapsed = true
        applyCollapseGravity(true)

        labelView?.let { lbl ->
            fadeAlpha(lbl, 0f)
            lbl.visibility = View.INVISIBLE
            // Zero the axes the label occupies on this rail, so the list does not keep
            // a hole where the text was and the floating pill stays centred.
            lbl.layoutParams = lbl.layoutParams.apply {
                width = 0
                height = 0
                if (this is LinearLayout.LayoutParams) {
                    leftMargin = 0
                    topMargin = 0
                }
            }
            labelWidthPx = 0
        }
        fadeAlpha(indicator, 0f)
        backgroundPill?.let { fadeAlpha(it, 0f) }
        others.forEach { fadeOut(it) }

        searchButton?.let { sb ->
            sb.visibility = View.VISIBLE
            sb.alpha = 0f
            sb.scaleX = 0.3f
            sb.scaleY = 0.3f
        }

        applyCollapsedGlass()
        savedPillPadding = intArrayOf(pl.paddingLeft, pl.paddingTop, pl.paddingRight, pl.paddingBottom)
        if (pl.orientation == LinearLayout.HORIZONTAL) {
            pl.setPadding(tightPaddingPx, pl.paddingTop, tightPaddingPx, pl.paddingBottom)
        }

        container.postDelayed({
            if (version != morphVersion || !collapsed) return@postDelayed
            labelView?.let { it.visibility = View.GONE }
            others.forEach { it.visibility = View.GONE }
            pl.requestLayout()
            pl.post {
                if (version != morphVersion || !collapsed) return@post
                // The container wraps its content, so measuring after the list has
                // reflowed gives the settled width. Cap it so a long label can never
                // push the bar past the screen margins.
                val target = container.measuredWidth.coerceAtMost(maxContainerWidthPx())
                animateContainerWidth(startWidth, target) {
                    if (version != morphVersion || !collapsed) return@animateContainerWidth
                    searchButton?.animate()
                        ?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
                }
            }
        }, 170)
    }

    private fun expand() {
        if (savedPillPadding == null) return
        val pl = pillList ?: return
        val selected = pills.getOrNull(selectedIndex) ?: pills.firstOrNull() ?: return
        val others = pills.filter { it !== selected }
        val startWidth = container.width

        morphVersion++
        val version = morphVersion
        containerWidthAnimator?.cancel()
        collapsed = false
        applyCollapseGravity(false)

        others.forEach { it.visibility = View.VISIBLE }
        others.forEach { it.alpha = 0f }
        others.forEach { it.scaleX = 0.3f }
        others.forEach { it.scaleY = 0.3f }
        labelView?.let { lbl ->
            lbl.visibility = View.VISIBLE
            lbl.alpha = 0f
        }
        indicator.alpha = 0f
        backgroundPill?.let { it.alpha = 0f }
        savedPillPadding?.let { p ->
            pl.setPadding(p[0], p[1], p[2], p[3])
        }
        savedPillPadding = null
        applyExpandedGlass()

        container.post {
            if (version != morphVersion || collapsed) return@post
            positionLabelOverlay()
            pl.requestLayout()
            pl.post {
                if (version != morphVersion || collapsed) return@post
                val targetWidth = container.measuredWidth
                animateContainerWidth(startWidth, targetWidth) {
                    if (version != morphVersion || collapsed) return@animateContainerWidth
                    positionLabelOverlay()
                    positionIndicator(selectedIndex, animate = false)
                    indicator.alpha = 1f
                    others.forEach { it.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start() }
                    backgroundPill?.animate()?.alpha(1f)?.setDuration(200)?.start()
                    labelView?.let { lbl ->
                        lbl.visibility = View.VISIBLE
                        lbl.animate().alpha(1f).setDuration(200).start()
                    }
                    searchButton?.let { sb ->
                        sb.animate()
                            ?.alpha(0f)?.scaleX(0.3f)?.scaleY(0.3f)
                            ?.setDuration(160)
                            ?.withEndAction {
                                if (!collapsed) sb.visibility = View.GONE
                            }
                            ?.start()
                    }
                }
            }
        }
    }

    /**
     * Moves the label into the layout right after the selected pill and sizes it to its
     * text. Because it is a genuine child of pill_list, the sibling pills reflow to make
     * room, so nothing can overlap. Horizontal rail only.
     */
    private fun positionLabelOverlay() {
        val lbl = labelView ?: return
        val parent = lbl.parent as? LinearLayout ?: return
        val pill = pills.getOrNull(selectedIndex) ?: return
        lbl.text = labels.getOrNull(selectedIndex) ?: ""
        // The label is built once, but the Icon Tint setting can change underneath us.
        lbl.setTextColor(NavPillCustomizer.getIconColor())

        val wantAnchor = parent.indexOfChild(pill)
        if (wantAnchor >= 0 && parent.indexOfChild(lbl) != wantAnchor + 1) {
            parent.removeView(lbl)
            parent.addView(lbl, wantAnchor + 1)
        }

        // The pill may not be laid out yet (first attach), so fall back to its
        // configured height rather than measuring against zero.
        val pillSize = if (pill.height > 0) pill.height else (pillH * density).roundToInt()
        val lp = lbl.layoutParams
        // Restore both axes: collapse zeroed them, and the rail only needs the width.
        if (lp is LinearLayout.LayoutParams) {
            lp.leftMargin = labelGapPx
            lp.topMargin = 0
        }
        // Never let a long label stretch the bar past the screen margins.
        val roomForText = (maxContainerWidthPx() - pillSize - labelGapPx).coerceAtLeast(0)
        lbl.measure(
            View.MeasureSpec.makeMeasureSpec(roomForText, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(pillSize, View.MeasureSpec.EXACTLY)
        )
        lp.width = lbl.measuredWidth
        lp.height = pillSize
        lbl.layoutParams = lp
        labelWidthPx = lbl.measuredWidth
        parent.requestLayout()
    }

    /**
     * Hard ceiling for the bar: the screen width less the container's own side margins
     * and the navigation-bar inset. The label ellipsizes past this rather than letting
     * the pill run off the edge.
     */
    private fun maxContainerWidthPx(): Int {
        val screen = container.resources.displayMetrics.widthPixels
        val lp = container.layoutParams
        val margins = if (lp is ViewGroup.MarginLayoutParams) lp.leftMargin + lp.rightMargin else 0
        // ViewCompat, not rootWindowInsets.getInsets(): the latter is API 30+ and
        // minSdk here is 23.
        val insets = ViewCompat.getRootWindowInsets(container)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.left ?: 0
        return (screen - margins - insets).coerceAtLeast((pillH * density).roundToInt() * 2)
    }

    private fun refreshCollapsedSelection(from: Int, to: Int) {
        val old = pills.getOrNull(from)
        val new = pills.getOrNull(to) ?: return
        old?.let {
            GlassEffectManager.removeGlass(it)
            it.visibility = View.GONE
            it.setBackgroundResource(android.R.color.transparent)
        }
        new.visibility = View.VISIBLE
        new.alpha = 1f
        new.scaleX = 1f
        new.scaleY = 1f
        applyButtonSurface(new)
    }

    private fun positionIndicator(index: Int, animate: Boolean) {
        val pill = pills.getOrNull(index) ?: pills.firstOrNull() ?: return
        val cLoc = IntArray(2)
        val pLoc = IntArray(2)
        container.getLocationInWindow(cLoc)
        pill.getLocationInWindow(pLoc)
        val vx = (pLoc[0] - cLoc[0]).toFloat()
        val vy = (pLoc[1] - cLoc[1]).toFloat()
        // The label is a sibling that already occupies its own layout space, so the
        // indicator spans the icon and the text as one continuous pill. The vertical
        // rail is left exactly as it was, minus the gradient.
        val vertical = pillList?.orientation == LinearLayout.VERTICAL
        val stretch = if (vertical) 0 else labelView?.let { lbl ->
            if (lbl.visibility == View.VISIBLE && !collapsed) labelWidthPx + labelGapPx else 0
        } ?: 0
        val width = pill.width + stretch
        val height = pill.height
        if (animate) {
            animateIndicator(vx, vy, width, height)
        } else {
            indicator.translationX = vx
            indicator.translationY = vy
            val lp = indicator.layoutParams
            lp.width = width
            lp.height = height
            indicator.layoutParams = lp
            indicator.visibility = View.VISIBLE
        }
    }

    private fun animateIndicator(vx: Float, vy: Float, width: Int, height: Int) {
        indicatorAnimator?.cancel()
        indicator.visibility = View.VISIBLE
        indicator.animate()
            .translationX(vx)
            .translationY(vy)
            .setDuration(340)
            .setInterpolator(springInterpolator)
            .start()
        indicatorAnimator = ValueAnimator.ofPropertyValuesHolder(
            PropertyValuesHolder.ofInt(INDICATOR_WIDTH, indicator.width, width),
            PropertyValuesHolder.ofInt(INDICATOR_HEIGHT, indicator.height, height)
        ).apply {
            duration = 340
            interpolator = springInterpolator
            addUpdateListener { anim ->
                val lp = indicator.layoutParams
                lp.width = anim.getAnimatedValue(INDICATOR_WIDTH) as Int
                lp.height = anim.getAnimatedValue(INDICATOR_HEIGHT) as Int
                indicator.layoutParams = lp
            }
            start()
        }
    }

    private fun animateContainerWidth(from: Int, to: Int, done: () -> Unit) {
        containerWidthAnimator?.cancel()
        val lp = container.layoutParams
        lp.width = from.coerceAtLeast(1)
        container.layoutParams = lp
        containerWidthAnimator = ValueAnimator.ofInt(from.coerceAtLeast(1), to.coerceAtLeast(1)).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.width = anim.animatedValue as Int
                container.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Settle on the target width rather than WRAP_CONTENT: switching back
                    // makes the bar re-measure mid-morph and visibly jump.
                    lp.width = to.coerceAtLeast(1)
                    container.layoutParams = lp
                    done()
                }
            })
            start()
        }
    }

    private fun fadeOut(view: View) {
        view.animate().cancel()
        view.animate()
            .alpha(0f).scaleX(0.3f).scaleY(0.3f)
            .setDuration(170)
            .start()
    }

    private fun fadeAlpha(view: View, to: Float) {
        view.animate().cancel()
        view.animate().alpha(to).setDuration(170).start()
    }

    private fun ImageButton.updateSurface(color: Int) {
        background = GradientDrawable().apply {
            cornerRadius = iconRadiusPx
            setColor(color)
        }
    }
}