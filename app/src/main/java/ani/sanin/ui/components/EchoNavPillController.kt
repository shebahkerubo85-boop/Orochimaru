package ani.sanin.ui.components

import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.animation.TimeInterpolator
import android.graphics.drawable.GradientDrawable
import android.util.Log
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
private const val TAG = "EchoNavPill"

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
    /** Distance between the selected pill's edge and its label text. */
    private val labelGapPx = (2f * density).roundToInt()

    /** Breathing room between the indicator and the top/bottom edge of the pill. */
    private val indicatorInsetPx = (3f * density).roundToInt()
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
    private var savedPillPadding: IntArray? = null
    private var morphVersion = 0
    private var containerWidthAnimator: ValueAnimator? = null
    private var indicatorAnimator: ValueAnimator? = null
    private val scrollViews = LinkedHashSet<View>()
    private val trackedLists = mutableSetOf<RecyclerView>()
    private val lastScrollY = HashMap<View, Int>()
    private var observedRoot: View? = null
    private var recollect: Runnable? = null
    private var geometryWatcher: View.OnLayoutChangeListener? = null
    private var repositioning = false
    private var lastAppliedKey: String? = null
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

    /**
     * Re-applies the Icon Tint and Icon Size to the search button, and re-syncs its box to
     * the current Pill Height. [pillH] is captured when the controller is built, so after a
     * settings change it can be stale; reading the setting here keeps the search pill the
     * same size as the others. Called on attach and from [syncGlass], which is the hook
     * that runs on resume and whenever the appearance settings are re-applied.
     */
    private fun applyIconSettingsToSearch() {
        val sb = searchButton ?: return
        val sizePx = (NavPillCustomizer.getHeightDp() * density).roundToInt()
        val lp = sb.layoutParams
        if (lp != null && (lp.width != sizePx || lp.height != sizePx)) {
            lp.width = sizePx
            lp.height = sizePx
            sb.layoutParams = lp
        }
        NavPillCustomizer.applyIconSettings(sb, NavPillCustomizer.getIconPaddingPx(sizePx))
    }

    fun attach(initialIndex: Int) {
        selectedIndex = initialIndex
        // Up front, not only on a collapse transition: restoring straight into the last
        // tab never scrolls, so applyCollapseGravity() would not run until much later and
        // a rail sized from its background would keep the pill stranded at the top.
        normaliseContainerBox()
        installGeometryWatchers()
        if (indicator.parent == null) {
            container.addView(indicator, 1.coerceIn(0, container.childCount))
        }
        labelView?.let { lbl ->
            if (lbl.parent == null) {
                // Sits in pill_list, immediately after the selected pill, so the layout
                // engine gives it real space and shifts the following pills along.
                val anchor = pillList?.indexOfChild(pills.getOrNull(selectedIndex)) ?: -1
                if (pillList != null && anchor >= 0) {
                    pillList.addView(lbl, (anchor + 1).coerceIn(0, pillList.childCount))
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
                // The search icon is one of the nav icons, so it obeys the same Icon Tint
                // and Icon Size settings as the pills. It is built lazily in here, which is
                // AFTER the activity already ran applyToPillList over the XML pills, so
                // without this it kept the drawable's own colour and, having no padding,
                // stretched to the full pill size instead of the configured icon size.
                applyIconSettingsToSearch()
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
            // Post, so the indicator reads settled geometry: positionLabelOverlay only
            // requests layout, so the pills have not moved yet at this point.
            container.post { positionIndicator(index, animate) }
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
     * Restores natural sizing for the expanded rail.
     *
     * This must NOT pin a fixed pixel width. A pinned width is fed straight back in as
     * `measuredWidth` on the next pass, so once the bar had been narrow (collapsed, or a
     * short label) the pin could never grow or shrink back to the content again. The bar
     * stayed wider or narrower than the pills plus label inside it, and because the
     * container is `bottom|center_horizontal` while the inner pill_list is `wrap_content`
     * with `gravity=center`, the mismatch shoved the pills off-centre and clipped the
     * label. That is what made it look media-info specific: it needs a collapse plus a
     * label change, which is exactly what that tab does.
     *
     * WRAP_CONTENT lets the bar size itself, and maxWidth is the layout system enforcing
     * the screen-margin ceiling natively, so a long label ellipsizes rather than escaping.
     */
    private fun repinContainerWidth() {
        if (collapsed) return
        val lp = container.layoutParams ?: return
        if (labelView == null) {
            // Vertical rail or no labels: natural sizing, exactly as before.
            if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                container.layoutParams = lp
            }
            return
        }
        if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            container.layoutParams = lp
        }
    }

    /**
     * Horizontal space in [parent] that [exclude] does not get: the list's own padding
     * plus every sibling's width and left margin. Read from layout params where possible
     * so it is correct before the first layout pass, falling back to the measured width.
     */
    private fun fixedChildWidthPx(parent: LinearLayout, exclude: View): Int {
        var total = parent.paddingLeft + parent.paddingRight
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child === exclude) continue
            val lp = child.layoutParams
            val width = if (lp != null && lp.width > 0) lp.width else child.width
            val margin = if (lp is LinearLayout.LayoutParams) lp.leftMargin else 0
            total += width + margin
        }
        return total
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
        if (observedRoot === root) {
            // Same root: still worth a re-walk, because a fragment switch creates new
            // scroll views under the root that the original walk never saw.
            collectScrollables(root, 0)
            return
        }
        stopScrollTracking()
        observedRoot = root
        root.viewTreeObserver.addOnScrollChangedListener(treeScrollListener)
        collectScrollables(root, 0)
        root.postDelayed({ collectScrollables(root, 0) }, 150)
        root.postDelayed({ collectScrollables(root, 0) }, 450)
        root.postDelayed({ collectScrollables(root, 0) }, 1000)
        root.postDelayed({ collectScrollables(root, 0) }, 2000)
        // Keep re-walking for as long as tracking is live. A tab switch replaces the
        // fragment and creates a new scroll view underneath this root, and nothing
        // re-ran the walk afterwards -- so the newly shown tab's scrolling was ignored
        // until the user tapped a pill, which is what re-ran it. That is precisely the
        // "stop scrolling, tap the tab, scroll again" workaround, so the walk now keeps
        // itself current instead of depending on a tap.
        recollect?.let { root.removeCallbacks(it) }
        val rewalk = object : Runnable {
            override fun run() {
                if (observedRoot !== root) return
                collectScrollables(root, 0)
                root.postDelayed(this, 400)
            }
        }
        recollect = rewalk
        root.postDelayed(rewalk, 400)
    }

    fun stopScrollTracking() {
        val root = observedRoot ?: return
        recollect?.let { root.removeCallbacks(it) }
        recollect = null
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnScrollChangedListener(treeScrollListener)
        observedRoot = null
        scrollViews.clear()
        lastScrollY.clear()
        trackedLists.clear()
    }

    private fun collectScrollables(v: View, depth: Int) {
        // Was 12, which a fragment inside a ViewPager can exceed on its own, so the tab's
        // list was never found and could not collapse the rail at all.
        if (depth > 30) return
        when {
            v is RecyclerView -> if (trackedLists.add(v)) {
                v.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        forwardScroll(rv, dy)
                    }
                })
            }
            // Collect regardless of visibility. Filtering on isShown here meant a tab's
            // scroll view was never tracked if it happened to be hidden when the walk
            // ran, and nothing re-walked the tree afterwards -- so that tab could not
            // collapse the rail at all.
            v is NestedScrollView || v is android.widget.ScrollView -> {
                scrollViews.add(v)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectScrollables(v.getChildAt(i), depth + 1)
        }
    }

    private fun dispatchScrollDeltas() {
        for (view in scrollViews) {
            if (!view.isShown) {
                // Forget the baseline but KEEP the view. Removing it here was permanent:
                // the tree is only re-walked from startScrollTracking() and select(), so a
                // tab that was hidden once (tab switch, fragment swap) stayed deaf to
                // scrolling until the user tapped it. Tapping re-collected and the pill
                // started responding again, which is exactly the "tap the tab, then
                // scroll again" workaround this replaces.
                lastScrollY.remove(view)
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

    fun onScroll(dyPixels: Float) {
        if (!isScrollCapable) return
        if (dyPixels == 0f) return
        // A signed accumulator, deliberately NOT reset when the direction flips.
        //
        // Real scroll streams contain stray opposite-signed deltas all the time:
        // sub-pixel rounding, rubber-band bounce part-way through a fling, and two
        // tracked scroll sources interleaving on one gesture. Wiping progress on the
        // first sign change meant the 52dp threshold could only be met if *every*
        // single delta in the gesture agreed, so the pill often failed to shrink on
        // the way down and failed to expand on the way back.
        accumulated += dyPixels
        if (collapsed) {
            if (accumulated <= -thresholdPx) {
                accumulated = 0f
                setCollapsed(false)
            }
        } else {
            if (accumulated >= thresholdPx) {
                accumulated = 0f
                setCollapsed(true)
            }
        }
        // Cap the surplus so a long scroll cannot bank a large lead that would need
        // just as much travel in reverse to undo. This is what stops flapping.
        accumulated = accumulated.coerceIn(-thresholdPx, thresholdPx)
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
        applyIconSettingsToSearch()
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
            // The label's margin and width were just re-derived from the current pill and
            // icon size, so the indicator has to be rebuilt from them too. Without this it
            // kept its previous width while the label had already changed, and the text
            // stuck out past the pill's background.
            container.post {
                if (collapsed) return@post
                positionIndicator(selectedIndex, animate = false)
            }
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
    /**
     * Keeps the rail's own box honest: wrap_content height, and the pill list centred
     * inside it.
     *
     * The pill list is a plain child of the FrameLayout and activity_media.xml /
     * activity_tmdb_details.xml give it no `layout_gravity`, so FrameLayout places it at
     * TOP|START. That is invisible while the container is exactly as tall as the pill, but
     * the glass background is a `match_parent` child -- so any pass that sizes the rail
     * from its background strands the pill at the top of it while the background keeps
     * painting the full height and the page content sits behind the leftover blur.
     * Centring the list means a container taller than its content can never push the pill
     * to the top.
     *
     * The height is pinned for the same reason: every rail is declared wrap_content in
     * every layout, so restoring it is a no-op in the normal case.
     */
    private fun normaliseContainerBox() {
        val lp = container.layoutParams
        val heightWas = lp?.height
        if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            container.layoutParams = lp
            Log.i(
                TAG,
                "box: re-pinned container height ${heightWas} -> WRAP_CONTENT " +
                    "(container ${container.width}x${container.height}, list ${pillList?.width}x${pillList?.height})"
            )
        }
        val list = pillList ?: return
        val listLp = list.layoutParams ?: return
        if (listLp is FrameLayout.LayoutParams && listLp.gravity != android.view.Gravity.CENTER) {
            val was = listLp.gravity
            listLp.gravity = android.view.Gravity.CENTER
            list.layoutParams = listLp
            Log.i(TAG, "box: centred pill_list, gravity $was -> CENTER")
        }
    }

    /**
     * Watches the rail's own box and the pill list for any later layout change and reacts
     * to it, instead of trusting a one-shot post to have read the final geometry.
     *
     * Two symptoms came out of that: the glass running the full height of the screen while
     * the pill sat centred in it, and an indicator that was briefly correct and then drifted
     * -- whatever re-laid-out the rail afterwards (icon settings, a tab switch, the rail
     * re-showing itself, its scale animation settling) invalidated coordinates that had
     * already been used. `positionIndicator` is now idempotent and re-derived whenever the
     * geometry it depends on actually changes.
     */
    private fun installGeometryWatchers() {
        if (geometryWatcher != null) return
        val watcher = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            onRailLaidOut(v)
        }
        geometryWatcher = watcher
        container.addOnLayoutChangeListener(watcher)
        pillList?.addOnLayoutChangeListener(watcher)
    }

    private fun onRailLaidOut(v: View) {
        val list = pillList
        val contentH = list?.height ?: 0
        val slack = (4f * density).roundToInt()
        val stretched = contentH > 0 && v.height > contentH + slack
        val bg = backgroundPill
        Log.i(
            TAG,
            "laidOut: container=${v.width}x${v.height} lpW=${v.layoutParams?.width} lpH=${v.layoutParams?.height} " +
                "list=${list?.width}x${list?.height} listTop=${list?.top} contentH=$contentH stretched=$stretched " +
                "bg=${bg?.javaClass?.simpleName} bgVis=${bg?.visibility} bgSize=${bg?.width}x${bg?.height} bgAlpha=${bg?.alpha} " +
                "containerBg=${container.background?.javaClass?.simpleName} collapsed=$collapsed"
        )
        if (stretched) normaliseContainerBox()
        if (collapsed || !isScrollCapable) return
        repositionIndicatorIfMoved()
    }

    /** Cheap fingerprint of everything [positionIndicator] reads. */
    private fun indicatorGeometryKey(): String {
        val pill = pills.getOrNull(selectedIndex)
        val lbl = labelView
        val lp = lbl?.layoutParams as? LinearLayout.LayoutParams
        return "${pill?.left}x${pill?.width}|${lbl?.visibility}|$labelWidthPx|" +
            "${lp?.leftMargin}|${container.width}|${list?.width}|${pill?.height}"
    }

    private fun repositionIndicatorIfMoved() {
        if (repositioning) return
        val key = indicatorGeometryKey()
        if (key == lastAppliedKey) return
        val from = lastAppliedKey
        lastAppliedKey = key
        Log.i(TAG, "reposition: geometry moved [$from] -> [$key]")
        repositioning = true
        positionLabelOverlay()
        container.post {
            repositioning = false
            if (collapsed) return@post
            positionIndicator(selectedIndex, animate = false)
        }
    }

    private fun applyCollapseGravity(collapsedNow: Boolean) {
        normaliseContainerBox()
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
        backgroundPill?.let { it.alpha = 0f }
        savedPillPadding?.let { p ->
            pl.setPadding(p[0], p[1], p[2], p[3])
        }
        savedPillPadding = null
        applyExpandedGlass()
        // shrink() fades the indicator out, because the two collapsed buttons carry their
        // own surfaces. Restore it here rather than at the end of the morph: this is the
        // only place it is brought back, and the expanded rail needs it for its pill
        // colour. positionIndicator still re-sizes and re-places it once layout settles.
        indicator.alpha = 1f

        // Bring the icons, the label and the background straight back, in parallel with
        // the width morph. They used to be faded in only once that morph finished, which
        // left the rail looking empty for its whole 220ms. The indicator is deliberately
        // not faded out here, so a pill always sits behind the selected icon and there is
        // never a bare-icon frame.
        others.forEach { it.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start() }
        labelView?.let { lbl -> lbl.animate().alpha(1f).setDuration(200).start() }
        backgroundPill?.animate()?.alpha(1f)?.setDuration(200)?.start()

        container.post {
            if (version != morphVersion || collapsed) return@post
            positionLabelOverlay()
            pl.requestLayout()
            // Restore natural sizing before the width gets measured. The collapse left the
            // container pinned to the small pill width, and reading measuredWidth while it
            // is still pinned would animate straight back to that stale value. This post is
            // also what lets the new layout land before the block below reads the width, so
            // no extra frame is needed for it.
            repinContainerWidth()
            pl.post {
                if (version != morphVersion || collapsed) return@post
                val targetWidth = container.measuredWidth
                animateContainerWidth(startWidth, targetWidth) {
                    if (version != morphVersion || collapsed) return@animateContainerWidth
                    positionLabelOverlay()
                    // Hand the width back to the layout, otherwise the bar stays pinned to
                    // whatever the animation last set and the pills sit off-centre.
                    repinContainerWidth()
                    // Only now read the geometry, one frame later. repinContainerWidth()
                    // hands the width back to WRAP_CONTENT, which queues a re-measure, and
                    // positionLabelOverlay() only requests layout -- so reading
                    // getLocationInWindow() straight afterwards returns the pill positions
                    // from *before* the list reflowed. The indicator was then drawn against
                    // stale coordinates: covering only the icon, or stopping short of half
                    // the label. A frame later those positions are the settled ones.
                    container.post {
                        if (version != morphVersion || collapsed) return@post
                        positionIndicator(selectedIndex, animate = false)
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
     * Left margin for the label, so the text starts [labelGapPx] from the icon glyph.
     *
     * A LinearLayout child's leftMargin is measured from the *end of the previous child*
     * (the pill), not from the pill's left edge. The icon is centred in a square pill
     * with symmetric padding, so its right edge sits at `pillSize / 2 + glyph / 2` from
     * the pill's left edge. To put the text just past that:
     *
     *     margin = (iconRight + gap) - pillSize
     *
     * which is negative, because the glyph's right edge is inside the pill. Returning
     * the un-offset `iconRight + gap` would shove the text a whole pill-width too far
     * right. Clamped so the text can never start left of the icon, and never past the
     * pill's right edge.
     */
    private fun labelLeftMarginPx(pillSize: Int): Int {
        val glyph = NavPillCustomizer.getIconDrawnSizePx(pillSize)
        val iconRight = pillSize / 2f + glyph / 2f
        val margin = iconRight + labelGapPx - pillSize
        return margin.coerceIn(iconRight - pillSize, 0f).roundToInt()
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
        val text = labels.getOrNull(selectedIndex) ?: ""
        // Idempotence guard. Everything below ends in requestLayout(), and the layout
        // watcher reads any layout pass as "the geometry moved, re-position". Without this
        // the two feed each other forever. Cheap enough to check first: it only compares
        // values that are already computed, and skips the measure when nothing differs.
        val curLp = lbl.layoutParams as? LinearLayout.LayoutParams
        val curPillSize = if (pill.height > 0) pill.height else (pillH * density).roundToInt()
        val curAnchor = parent.indexOfChild(pill)
        if (curLp != null && labelWidthPx > 0 && !collapsed &&
            curAnchor >= 0 && parent.indexOfChild(lbl) == curAnchor + 1 &&
            lbl.text.toString() == text && lbl.visibility == View.VISIBLE &&
            curLp.leftMargin == labelLeftMarginPx(curPillSize) &&
            curLp.height == curPillSize && curLp.width == labelWidthPx
        ) return
        lbl.text = text
        // The label is built once, but the Icon Tint setting can change underneath us.
        lbl.setTextColor(NavPillCustomizer.getIconColor())
        // The label is constructed GONE, and until now only expand() ever set it back to
        // VISIBLE. So on a freshly attached rail the text never appeared at all, and
        // positionIndicator() -- which only stretches over the label when it is VISIBLE
        // -- drew a pill covering just the icon. That was both "the pill appears
        // unlabelled, especially on app start" and "the indicator only indicates the
        // icon". Own the visibility here: expanded means visible.
        if (!collapsed) lbl.visibility = View.VISIBLE

        val wantAnchor = parent.indexOfChild(pill)
        if (wantAnchor >= 0 && parent.indexOfChild(lbl) != wantAnchor + 1) {
            // Detach first, then re-read the anchor: removing the label shifts every
            // later child down by one, so an index captured before the removeView is
            // stale and addView throws IndexOutOfBounds.
            parent.removeView(lbl)
            val anchor = parent.indexOfChild(pill)
            if (anchor >= 0) {
                // Clamp as a last resort: addView throws on an out-of-range index, and
                // this runs from a click listener, so a crash here takes down the app.
                parent.addView(lbl, (anchor + 1).coerceIn(0, parent.childCount))
            } else {
                parent.addView(lbl)
            }
        }

        // The pill may not be laid out yet (first attach), so fall back to its
        // configured height rather than measuring against zero.
        val pillSize = if (pill.height > 0) pill.height else (pillH * density).roundToInt()
        val lp = lbl.layoutParams
        // Restore both axes: collapse zeroed them, and the rail only needs the width.
        val leftMargin = labelLeftMarginPx(pillSize)
        if (lp is LinearLayout.LayoutParams) {
            lp.leftMargin = leftMargin
            lp.topMargin = 0
        }
        // Bound the text so the bar, at WRAP_CONTENT, still fits inside the screen
        // margins. Every other child already occupies a fixed width, so the label only
        // gets what is left over. This replaces a maxWidth cap on the container, and is
        // stricter: the old cap only knew about the selected pill, so it ignored the
        // other pills and the search button and let the bar overflow on a narrow screen.
        val roomForText = (maxContainerWidthPx() - fixedChildWidthPx(parent, lbl) - leftMargin)
            .coerceAtLeast(0)
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
        // The label is a sibling that already occupies its own layout space, so the
        // indicator spans the icon and the text as one continuous pill. The vertical
        // rail is left exactly as it was, minus the gradient.
        val vertical = pillList?.orientation == LinearLayout.VERTICAL
        val stretch = if (vertical) 0 else labelView?.let { lbl ->
            if (lbl.visibility == View.VISIBLE && !collapsed) {
                // The label's margin can be negative (it starts inside the pill, next to
                // the glyph), so the stretch is the true remaining distance to the text's
                // right edge and must never be less than the pill's own width.
                val margin = (lbl.layoutParams as? LinearLayout.LayoutParams)?.leftMargin ?: 0
                (margin + labelWidthPx).coerceAtLeast(0)
            } else 0
        } ?: 0
        val width = pill.width + stretch
        // A little breathing room above and below: the indicator is inset vertically
        // from the pill, and nudged down by the same amount so it stays centred on the
        // icon. Horizontal only, so the vertical rail keeps its original full-height
        // indicator.
        val inset = if (vertical) 0 else indicatorInsetPx
        val height = (pill.height - inset * 2).coerceAtLeast(1)
        val vy = (pLoc[1] - cLoc[1]).toFloat() + inset
        Log.i(
            TAG,
            "indicator[$index]: animate=$animate pill=${pill.width}x${pill.height} at(${(pLoc[0] - cLoc[0])},${(pLoc[1] - cLoc[1])}) " +
                "stretch=$stretch margin=${(labelView?.layoutParams as? LinearLayout.LayoutParams)?.leftMargin} " +
                "labelW=$labelWidthPx labelVis=${labelView?.visibility} -> w=$width h=$height vx=$vx vy=$vy " +
                "container=${container.width}x${container.height} list=${pillList?.width}x${pillList?.height}"
        )
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
        applyContainerWidth(from)
        containerWidthAnimator = ValueAnimator.ofInt(from.coerceAtLeast(1), to.coerceAtLeast(1)).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim -> applyContainerWidth(anim.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    applyContainerWidth(to)
                    done()
                }
            })
            start()
        }
    }

    /**
     * Writes the width onto the container's *current* LayoutParams on every frame.
     *
     * Holding one LayoutParams object captured before the animation started was unsafe:
     * the activities' window-insets listener replaces `layoutParams` while this runs, so
     * re-assigning the captured object at the end silently rolled the container back to
     * its pre-inset margins and dropped the gravity the collapse had just applied.
     */
    private fun applyContainerWidth(width: Int) {
        val lp = container.layoutParams ?: return
        lp.width = width.coerceAtLeast(1)
        container.layoutParams = lp
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