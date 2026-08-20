package ani.sanin.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import ani.sanin.R
import ani.sanin.cloudstream.CsRepos
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

        animeExpanded.visibility = View.GONE
        movieExpanded.visibility = View.GONE

        animeButton.setOnClickListener {
            val isVisible = animeExpanded.visibility == View.VISIBLE
            if (isVisible) collapseSection(animeExpanded, animeArrow)
            else {
                expandSection(animeExpanded, animeArrow)
                collapseSection(movieExpanded, movieArrow)
            }
        }

        movieButton.setOnClickListener {
            val isVisible = movieExpanded.visibility == View.VISIBLE
            if (isVisible) collapseSection(movieExpanded, movieArrow)
            else {
                expandSection(movieExpanded, movieArrow)
                collapseSection(animeExpanded, animeArrow)
            }
        }

        aniListCheck.setOnClickListener {
            if (aniListCheck.isChecked) {
                malCheck.isChecked = false
                PrefManager.setVal(PrefName.SelectedMediaType, 0)
                PrefManager.setVal(PrefName.SelectedTracker, 0)
                (activity as? MainActivity)?.setContentMode("anime")
                collapseSection(animeExpanded, animeArrow)
            }
        }

        malCheck.setOnClickListener {
            if (malCheck.isChecked) {
                aniListCheck.isChecked = false
                PrefManager.setVal(PrefName.SelectedMediaType, 0)
                PrefManager.setVal(PrefName.SelectedTracker, 1)
                (activity as? MainActivity)?.setContentMode("anime")
                collapseSection(animeExpanded, animeArrow)
            }
        }

        simklCheck.setOnClickListener {
            if (simklCheck.isChecked) {
                PrefManager.setVal(PrefName.SelectedMediaType, 1)
                PrefManager.setVal(PrefName.SelectedTracker, 2)
                (activity as? MainActivity)?.setContentMode("movie_tv")
                collapseSection(movieExpanded, movieArrow)
            }
        }

        // Plugin spinner: TMDB first, then installed plugins
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

        // Suppress listener during restore
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

        // Restore last selected source
        val savedSource = PrefManager.getVal<String>(PrefName.ContentSource)
        val restoreIdx = pluginIds.indexOfFirst { it.equals(savedSource, ignoreCase = true) }
        if (restoreIdx >= 0) pluginSpinner.setSelection(restoreIdx)

        // Now allow real user selections to trigger mode change
        pluginSpinner.post { suppressSpinner = false }

        // Restore UI state
        val savedType = PrefManager.getVal<Int>(PrefName.SelectedMediaType)
        val savedTracker = PrefManager.getVal<Int>(PrefName.SelectedTracker)

        when (savedTracker) {
            0 -> aniListCheck.isChecked = true
            1 -> malCheck.isChecked = true
            2 -> simklCheck.isChecked = true
        }

        if (savedType == 0 && (savedTracker == 0 || savedTracker == 1)) {
            expandSection(animeExpanded, animeArrow)
        } else if (savedType == 1 && savedTracker == 2) {
            expandSection(movieExpanded, movieArrow)
        }
    }

    private fun expandSection(section: View, arrow: ImageView) {
        section.visibility = View.VISIBLE
        arrow.animate().rotation(180f).setDuration(200).start()
    }

    private fun collapseSection(section: View, arrow: ImageView) {
        section.visibility = View.GONE
        arrow.animate().rotation(0f).setDuration(200).start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _view = null
    }
}
