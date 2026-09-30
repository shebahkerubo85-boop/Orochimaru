package ani.sanin.settings

import android.os.Bundle
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.content.res.Configuration
import com.google.android.material.bottomsheet.BottomSheetBehavior
import ani.sanin.R
import ani.sanin.cloudstream.CsRepos
import ani.sanin.getThemeColor
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.mal.MAL
import ani.sanin.connections.simkl.Simkl
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.MainActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MediaTrackerBottomSheet : BottomSheetDialogFragment() {

    private var _view: View? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _view = inflater.inflate(R.layout.bottom_sheet_media_tracker, container, false)
        return _view!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
    }

    private fun setupUI() {
        val v = _view!!
        val animeButton = v.findViewById<View>(R.id.sheetAnimeButton)
        val movieButton = v.findViewById<View>(R.id.sheetMovieButton)
        val animeExpanded = v.findViewById<View>(R.id.sheetAnimeExpanded)
        val movieExpanded = v.findViewById<View>(R.id.sheetMovieExpanded)
        val animeArrow = v.findViewById<ImageView>(R.id.sheetAnimeArrow)
        val movieArrow = v.findViewById<ImageView>(R.id.sheetMovieArrow)
        val aniListCheck = v.findViewById<CheckBox>(R.id.sheetAnimeAniListCheck)
        val malCheck = v.findViewById<CheckBox>(R.id.sheetAnimeMALCheck)
        val simklCheck = v.findViewById<CheckBox>(R.id.sheetMovieSimklCheck)
        val pluginRowInner = v.findViewById<View>(R.id.sheetMoviePluginRowInner)
        val pluginNameView = v.findViewById<TextView>(R.id.sheetMoviePluginName)
        val pluginArrow = v.findViewById<View>(R.id.sheetMoviePluginArrow)

        // Card banner/scrim/profile/tracker icon views
        val animeBanner = v.findViewById<ImageView>(R.id.sheetAnimeBanner)
        val animeScrim = v.findViewById<View>(R.id.sheetAnimeScrim)
        val animeProfilePic = v.findViewById<ImageView>(R.id.sheetAnimeProfilePic)
        val animeTrackerIcon = v.findViewById<ImageView>(R.id.sheetAnimeTrackerIcon)
        val movieBanner = v.findViewById<ImageView>(R.id.sheetMovieBanner)
        val movieScrim = v.findViewById<View>(R.id.sheetMovieScrim)
        val movieProfilePic = v.findViewById<ImageView>(R.id.sheetMovieProfilePic)
        val movieTrackerIcon = v.findViewById<ImageView>(R.id.sheetMovieTrackerIcon)

        animeExpanded.visibility = View.GONE
        movieExpanded.visibility = View.GONE

        fun updateCollapsedFocusChain() {
            animeButton.nextFocusUpId = View.NO_ID
            animeButton.nextFocusDownId = movieButton.id
            movieButton.nextFocusUpId = animeButton.id
            movieButton.nextFocusDownId = View.NO_ID
        }

        fun updateAnimeExpandedFocusChain() {
            animeButton.nextFocusUpId = View.NO_ID
            animeButton.nextFocusDownId = aniListCheck.id
            aniListCheck.nextFocusUpId = animeButton.id
            aniListCheck.nextFocusDownId = malCheck.id
            malCheck.nextFocusUpId = aniListCheck.id
            malCheck.nextFocusDownId = View.NO_ID
            movieButton.nextFocusUpId = View.NO_ID
            movieButton.nextFocusDownId = View.NO_ID
        }

        fun updateMovieExpandedFocusChain() {
            movieButton.nextFocusUpId = View.NO_ID
            movieButton.nextFocusDownId = simklCheck.id
            simklCheck.nextFocusUpId = movieButton.id
            simklCheck.nextFocusDownId = pluginRowInner.id
            pluginRowInner.nextFocusUpId = simklCheck.id
            pluginRowInner.nextFocusDownId = View.NO_ID
            animeButton.nextFocusUpId = View.NO_ID
            animeButton.nextFocusDownId = View.NO_ID
        }

        // --- Anime button card styling ---
        val savedTracker = PrefManager.getVal<Int>(PrefName.SelectedTracker)
        val savedType = PrefManager.getVal<Int>(PrefName.SelectedMediaType)

        // Anime card: show profile if AniList (0) or MAL (1) is logged in
        if (savedType == 0 && savedTracker == 0 && Anilist.token != null) {
            // AniList logged in
            val bannerUrl = Anilist.bg ?: Anilist.avatar
            if (bannerUrl != null) {
                animeBanner.loadImage(bannerUrl)
                animeBanner.visibility = View.VISIBLE
                animeScrim.visibility = View.VISIBLE
            }
            if (Anilist.avatar != null) {
                animeProfilePic.loadImage(Anilist.avatar)
                animeProfilePic.visibility = View.VISIBLE
            }
            animeTrackerIcon.setImageResource(R.drawable.ic_anilist)
            animeTrackerIcon.visibility = View.VISIBLE
        } else if (savedType == 0 && savedTracker == 1 && MAL.token != null) {
            // MAL logged in — use avatar as banner too (MAL has no bg)
            val bannerUrl = MAL.avatar
            if (bannerUrl != null) {
                animeBanner.loadImage(bannerUrl)
                animeBanner.visibility = View.VISIBLE
                animeScrim.visibility = View.VISIBLE
            }
            if (MAL.avatar != null) {
                animeProfilePic.loadImage(MAL.avatar)
                animeProfilePic.visibility = View.VISIBLE
            }
            animeTrackerIcon.setImageResource(R.drawable.ic_myanimelist)
            animeTrackerIcon.visibility = View.VISIBLE
        }

        // --- Movie card styling: Simkl ---
        if (savedType == 1 && savedTracker == 2 && Simkl.token != null) {
            val bannerUrl = Simkl.avatar
            if (bannerUrl != null) {
                movieBanner.loadImage(bannerUrl)
                movieBanner.visibility = View.VISIBLE
                movieScrim.visibility = View.VISIBLE
            }
            if (Simkl.avatar != null) {
                movieProfilePic.loadImage(Simkl.avatar)
                movieProfilePic.visibility = View.VISIBLE
            }
            movieTrackerIcon.setImageResource(R.drawable.ic_simkl)
            movieTrackerIcon.visibility = View.VISIBLE
        }

        // --- Expand/collapse logic ---
        animeButton.setOnClickListener {
            val isVisible = animeExpanded.visibility == View.VISIBLE
            if (isVisible) {
                collapseSection(animeExpanded, animeArrow)
                updateCollapsedFocusChain()
            }
            else {
                expandSection(animeExpanded, animeArrow)
                collapseSection(movieExpanded, movieArrow)
                updateAnimeExpandedFocusChain()
                aniListCheck.requestFocus()
            }
        }

        movieButton.setOnClickListener {
            val isVisible = movieExpanded.visibility == View.VISIBLE
            if (isVisible) {
                collapseSection(movieExpanded, movieArrow)
                updateCollapsedFocusChain()
            }
            else {
                expandSection(movieExpanded, movieArrow)
                collapseSection(animeExpanded, animeArrow)
                updateMovieExpandedFocusChain()
                simklCheck.requestFocus()
            }
        }

        animeButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                animeExpanded.visibility == View.VISIBLE
            ) {
                aniListCheck.requestFocus()
                true
            } else {
                false
            }
        }

        movieButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                movieExpanded.visibility == View.VISIBLE
            ) {
                simklCheck.requestFocus()
                true
            } else {
                false
            }
        }

        aniListCheck.setOnKeyListener { _, keyCode, event ->
            when {
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    collapseSection(animeExpanded, animeArrow)
                    updateCollapsedFocusChain()
                    animeButton.requestFocus()
                    true
                }
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    malCheck.requestFocus()
                    true
                }
                else -> false
            }
        }

        malCheck.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                aniListCheck.requestFocus()
                true
            } else {
                false
            }
        }

        simklCheck.setOnKeyListener { _, keyCode, event ->
            when {
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    collapseSection(movieExpanded, movieArrow)
                    updateCollapsedFocusChain()
                    movieButton.requestFocus()
                    true
                }
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    pluginRowInner.requestFocus()
                    true
                }
                else -> false
            }
        }

        FocusEffectUtil.applyFocusListener(pluginRowInner)
        pluginRowInner.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_UP && simklCheck.requestFocus().let { true }
        }

        // --- Tracker checkboxes → instant mode switch ---
        aniListCheck.setOnClickListener {
            if (aniListCheck.isChecked) {
                if (Anilist.token == null) {
                    aniListCheck.isChecked = false
                    ani.sanin.toast("Login to AniList first")
                    return@setOnClickListener
                }
                malCheck.isChecked = false
                PrefManager.setVal(PrefName.SelectedMediaType, 0)
                PrefManager.setVal(PrefName.SelectedTracker, 0)
                PrefManager.setVal(PrefName.RescueMode, false)
                (activity as? MainActivity)?.setContentMode("anime")
                collapseSection(animeExpanded, animeArrow)
                updateCollapsedFocusChain()
                animeButton.requestFocus()
            }
        }

        malCheck.setOnClickListener {
            if (malCheck.isChecked) {
                if (MAL.token == null) {
                    malCheck.isChecked = false
                    ani.sanin.toast("Login to My Anime List first")
                    return@setOnClickListener
                }
                aniListCheck.isChecked = false
                PrefManager.setVal(PrefName.SelectedMediaType, 0)
                PrefManager.setVal(PrefName.SelectedTracker, 1)
                (activity as? MainActivity)?.setContentMode("anime")
                collapseSection(animeExpanded, animeArrow)
                updateCollapsedFocusChain()
                animeButton.requestFocus()
            }
        }

        simklCheck.setOnClickListener {
            if (simklCheck.isChecked) {
                if (Simkl.token == null) {
                    simklCheck.isChecked = false
                    ani.sanin.toast("Login to Simkl first")
                    return@setOnClickListener
                }
                PrefManager.setVal(PrefName.SelectedMediaType, 1)
                PrefManager.setVal(PrefName.SelectedTracker, 2)
                (activity as? MainActivity)?.setContentMode("movie_tv")
                collapseSection(movieExpanded, movieArrow)
                updateCollapsedFocusChain()
                movieButton.requestFocus()
            }
        }

        // --- Plugin source picker (single-choice dialog, same as player speed) ---
        val installedSources = CsRepos.installed(requireContext())
        val pluginNames = mutableListOf("Simkl")
        val pluginIds = mutableListOf("simkl")
        installedSources.forEach { src ->
            pluginNames.add(src.name)
            pluginIds.add(src.id)
        }

        fun showSourcePicker() {
            val savedSource = PrefManager.getVal<String>(PrefName.ContentSource)
            val restoreIdx = pluginIds.indexOfFirst { it.equals(savedSource, ignoreCase = true) }
            val currentIdx = if (restoreIdx >= 0) restoreIdx else 0
            pluginNameView.text = pluginNames[currentIdx]
            // Dialog layout: [List header] [Simkl] [divider] [Plugin header] [plug 1..n]
            // so Simkl is position 1 and plugin i (>=1) is position i + 3.
            val adapterPos = if (currentIdx == 0) 1 else currentIdx + 3
            val picker = SourcePickerAdapter(requireContext(), pluginNames, adapterPos)
            requireContext().customAlertDialog().apply {
                setTitle(R.string.home_metadata)
                setWidthPx((240 * requireContext().resources.displayMetrics.density).toInt())
                // The framework only ticks a radio for rows it wires up itself, and our
                // BaseAdapter builds its own CheckedTextViews, so the ListView's checked
                // set is never populated. Letting the adapter own the checked state is
                // what actually moves the indicator.
                attach { d ->
                    d.listView?.choiceMode = android.widget.ListView.CHOICE_MODE_NONE
                }
                singleChoiceAdapter(picker, adapterPos) { pos ->
                    picker.setChecked(pos)
                    val idx = if (pos == 1) 0 else pos - 3
                    PrefManager.setVal(PrefName.ContentSource, pluginIds[idx])
                    pluginNameView.text = pluginNames[idx]
                    (activity as? MainActivity)?.setContentMode("movie_tv")
                }
                show()
            }
        }

        pluginNameView.text = pluginNames.firstOrNull { it.equals(
            PrefManager.getVal<String>(PrefName.ContentSource), ignoreCase = true
        ) } ?: pluginNames[0]
        pluginRowInner.setOnClickListener { showSourcePicker() }
        pluginNameView.setOnClickListener { showSourcePicker() }
        pluginArrow.setOnClickListener { showSourcePicker() }

        // --- Restore checkbox state (but always start collapsed) ---
        when (savedTracker) {
            0 -> aniListCheck.isChecked = true
            1 -> malCheck.isChecked = true
            2 -> simklCheck.isChecked = true
        }

        updateCollapsedFocusChain()

        FocusEffectUtil.applyFocusListener(animeButton)
        FocusEffectUtil.applyFocusListener(movieButton)
        FocusEffectUtil.applyFocusListener(aniListCheck)
        FocusEffectUtil.applyFocusListener(malCheck)
        FocusEffectUtil.applyFocusListener(simklCheck)
        animeButton.post { animeButton.requestFocus() }
    }

    private fun expandSection(section: View, arrow: ImageView) {
        AnimUtils.rollExpand(section)
        arrow.animate().rotation(180f).setDuration(AnimUtils.duration(200)).start()
    }

    private fun collapseSection(section: View, arrow: ImageView) {
        AnimUtils.rollCollapse(section)
        arrow.animate().rotation(0f).setDuration(AnimUtils.duration(200)).start()
    }


    override fun onStart() {
        super.onStart()
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isTv || isLandscape) {
            val sheet = requireView().parent as? View ?: return
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _view = null
    }
}

/**
 * Source picker rows for the dialog. Layout:
 *   position 0  -> "List" section header (small, primary color, left aligned)
 *   position 1  -> Simkl
 *   position 2  -> low-contrast divider
 *   position 3  -> "Plugin" section header
 *   positions 4+ -> installed plugin sources
 */
private class SourcePickerAdapter(
    private val context: Context,
    private val names: List<String>,
    checkedPosition: Int
) : android.widget.BaseAdapter() {

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_DIVIDER = 1
        private const val TYPE_HEADER = 2
    }

    /**
     * A fresh radio drawable for one row.
     *
     * This used to be a `by lazy` shared by every row, which is why the selection was invisible:
     * a Drawable carries its own checked/unchecked state, so rows sharing one instance each
     * overwrote it as they were drawn and the last one bound won. The ListView draws top to
     * bottom, so the final row left it unchecked and no tick ever appeared -- including on a
     * freshly opened dialog, where the checked row was correct in the adapter but had nothing
     * to render with.
     *
     * Each row now gets its own instance, and [bindRadioState] sets the state on it directly
     * rather than relying on the TextView's drawable-state propagation.
     */
    private fun newRadioIndicator(): android.graphics.drawable.Drawable? {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.listChoiceIndicatorSingle))
        return try {
            a.getDrawable(0)?.mutate()
        } finally {
            a.recycle()
        }
    }

    private fun bindRadioState(row: android.widget.CheckedTextView, checked: Boolean) {
        row.isChecked = checked
        val d = row.compoundDrawables?.getOrNull(0) ?: return
        d.setState(
            if (checked) intArrayOf(android.R.attr.state_checked)
            else intArrayOf(-android.R.attr.state_checked)
        )
        d.setBounds(0, 0, d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1))
    }

    private val rowMaxWidth = (context.resources.displayMetrics.density * 280).toInt()

    /**
     * The adapter owns the checked row. The framework's single-choice handling never
     * ticks a row that a BaseAdapter built itself, so asking the ListView what is
     * checked (the previous approach) always came back with nothing and the radio
     * indicator stayed blank.
     */
    private var checkedPosition = checkedPosition

    /**
     * Moves the indicator, so the tick follows the tap immediately.
     *
     * This exists for the case where the dialog is configured not to dismiss on select. It has
     * to notify, otherwise the change is invisible: mutating checkedPosition alone leaves the
     * bound rows holding their previous drawable state with no reason to re-bind.
     */
    fun setChecked(position: Int) {
        if (position == checkedPosition) return
        checkedPosition = position
        notifyDataSetChanged()
    }

    override fun getCount(): Int = names.size + 3

    override fun getItem(position: Int): Any = when {
        position == 1 -> names[0]
        position >= 4 -> names[position - 3]
        else -> "" // header/divider rows; disabled, never bound from a name
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 3

    override fun getItemViewType(position: Int): Int = when (position) {
        0, 3 -> TYPE_HEADER
        2 -> TYPE_DIVIDER
        else -> TYPE_ROW
    }

    override fun isEnabled(position: Int): Boolean =
        position == 1 || position >= 4

    private fun realIndex(position: Int): Int = when (position) {
        1 -> 0
        else -> position - 3
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val primary = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        return when (position) {
            0, 3 -> {
                val header = convertView as? TextView ?: TextView(context).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 12f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    isEnabled = false
                }
                header.text = if (position == 0) "List" else "Plugin"
                header.setTextColor(primary)
                val dp = context.resources.displayMetrics.density
                header.setPadding(
                    (dp * 20).toInt(),
                    if (position == 3) (dp * 12).toInt() else 0, // breathing room below the divider
                    (dp * 8).toInt(),
                    0
                )
                header
            }

            2 -> {
                // A fresh divider is always built — its convertView is never
                // recycled into a text row because of the distinct view type.
                // White at ~17% alpha reads as a faint grey line on the
                // AMOLED-black dialog surface (that's what the player's own
                // dialog looks like), without clashing on light surfaces.
                View(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (context.resources.displayMetrics.density * 1).toInt().coerceAtLeast(1)
                    )
                    background = android.graphics.drawable.ColorDrawable(0x2BFFFFFF)
                }
            }

            else -> {
                val row = convertView as? CheckedTextView
                    ?: CheckedTextView(context).apply {
                        // radio indicator sits on the left, right against the name.
                        // Per-row instance: see newRadioIndicator() for why this cannot be shared.
                        setCompoundDrawablesWithIntrinsicBounds(newRadioIndicator(), null, null, null)
                        compoundDrawablePadding = (context.resources.displayMetrics.density * 2).toInt()
                        gravity = Gravity.CENTER_VERTICAL
                    }
                row.setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                row.text = names[realIndex(position)]
                row.maxWidth = rowMaxWidth
                row.ellipsize = android.text.TextUtils.TruncateAt.END
                row.setSingleLine(true)
                row.setPadding(0, 0, 0, 0)
                // Checked state comes from the adapter, not from the ListView: this
                // BaseAdapter builds its own rows, so the framework never ticks them.
                bindRadioState(row, position == checkedPosition)
                row
            }
        }
    }
}
