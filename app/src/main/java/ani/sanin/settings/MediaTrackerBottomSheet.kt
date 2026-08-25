package ani.sanin.settings

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.content.res.Configuration
import com.google.android.material.bottomsheet.BottomSheetBehavior
import ani.sanin.R
import ani.sanin.cloudstream.CsRepos
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.mal.MAL
import ani.sanin.connections.simkl.Simkl
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil
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
        val pluginSpinner = v.findViewById<Spinner>(R.id.sheetMoviePluginSpinner)

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
            malCheck.nextFocusUpId = animeButton.id
            malCheck.nextFocusDownId = View.NO_ID
            movieButton.nextFocusUpId = View.NO_ID
            movieButton.nextFocusDownId = View.NO_ID
        }

        fun updateMovieExpandedFocusChain() {
            movieButton.nextFocusUpId = View.NO_ID
            movieButton.nextFocusDownId = simklCheck.id
            simklCheck.nextFocusUpId = movieButton.id
            simklCheck.nextFocusDownId = pluginSpinner.id
            pluginSpinner.nextFocusUpId = simklCheck.id
            pluginSpinner.nextFocusDownId = View.NO_ID
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
            when {
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    collapseSection(animeExpanded, animeArrow)
                    updateCollapsedFocusChain()
                    animeButton.requestFocus()
                    true
                }
                else -> false
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
                    pluginSpinner.requestFocus()
                    true
                }
                else -> false
            }
        }

        pluginSpinner.isFocusable = true
        pluginSpinner.isFocusableInTouchMode = false
        FocusEffectUtil.applyFocusListener(pluginSpinner)
        pluginSpinner.setOnKeyListener { _, keyCode, event ->
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

        // --- Plugin spinner ---
        val installedSources = CsRepos.installed(requireContext())
        val pluginNames = mutableListOf("TMDB")
        val pluginIds = mutableListOf("tmdb")
        installedSources.forEach { src ->
            pluginNames.add(src.name)
            pluginIds.add(src.id)
        }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            pluginNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        pluginSpinner.adapter = adapter

        var suppressSpinner = true
        pluginSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long) {
                PrefManager.setVal(PrefName.ContentSource, pluginIds[index])
                if (!suppressSpinner) {
                    (activity as? MainActivity)?.setContentMode("movie_tv")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val savedSource = PrefManager.getVal<String>(PrefName.ContentSource)
        val restoreIdx = pluginIds.indexOfFirst { it.equals(savedSource, ignoreCase = true) }
        if (restoreIdx >= 0) pluginSpinner.setSelection(restoreIdx)
        pluginSpinner.post { suppressSpinner = false }

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
        section.visibility = View.VISIBLE
        arrow.animate().rotation(180f).setDuration(200).start()
    }

    private fun collapseSection(section: View, arrow: ImageView) {
        section.visibility = View.GONE
        arrow.animate().rotation(0f).setDuration(200).start()
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
