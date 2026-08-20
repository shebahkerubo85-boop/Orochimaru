package ani.sanin.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.mal.MAL
import ani.sanin.connections.simkl.Simkl
import ani.sanin.cloudstream.CsRepos
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MediaTrackerBottomSheet : BottomSheetDialogFragment() {

    private var _view: View? = null

    private var selectedMediaType: Int = 0
    private var selectedTracker: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _view = inflater.inflate(R.layout.bottom_sheet_media_tracker, container, false)
        return _view!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadState()
        setupUI()
    }

    private fun setupUI() {
        val animeButton = _view!!.findViewById<View>(R.id.sheetAnimeButton)
        val movieButton = _view!!.findViewById<View>(R.id.sheetMovieButton)
        val animeExpanded = _view!!.findViewById<View>(R.id.sheetAnimeExpanded)
        val movieExpanded = _view!!.findViewById<View>(R.id.sheetMovieExpanded)
        val animeArrow = _view!!.findViewById<ImageView>(R.id.sheetAnimeArrow)
        val movieArrow = _view!!.findViewById<ImageView>(R.id.sheetMovieArrow)

        val aniListCheck = _view!!.findViewById<CheckBox>(R.id.sheetAnimeAniListCheck)
        val malCheck = _view!!.findViewById<CheckBox>(R.id.sheetAnimeMALCheck)
        val simklCheck = _view!!.findViewById<CheckBox>(R.id.sheetMovieSimklCheck)
        val pluginSpinner = _view!!.findViewById<Spinner>(R.id.sheetMoviePluginSpinner)

        // Set initial expand state
        animeExpanded.visibility = View.GONE
        movieExpanded.visibility = View.GONE
        animeArrow.rotation = 0f
        movieArrow.rotation = 0f

        // Anime button toggle
        animeButton.setOnClickListener {
            val isVisible = animeExpanded.visibility == View.VISIBLE
            animeExpanded.visibility = if (isVisible) View.GONE else View.VISIBLE
            animeArrow.animate().rotation(if (isVisible) 0f else 180f).setDuration(200).start()
            if (!isVisible) {
                selectedMediaType = 0
                saveState()
            }
        }

        // Movie button toggle
        movieButton.setOnClickListener {
            val isVisible = movieExpanded.visibility == View.VISIBLE
            movieExpanded.visibility = if (isVisible) View.GONE else View.VISIBLE
            movieArrow.animate().rotation(if (isVisible) 0f else 180f).setDuration(200).start()
            if (!isVisible) {
                selectedMediaType = 1
                saveState()
            }
        }

        // Anime tracker checkboxes (radio behavior)
        aniListCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                malCheck.isChecked = false
                selectedTracker = 0
                saveState()
            }
        }

        malCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                aniListCheck.isChecked = false
                selectedTracker = 1
                saveState()
            }
        }

        // Movie tracker - Simkl is default
        simklCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedTracker = 2
                saveState()
            }
        }

        // Plugin spinner
        val installedPlugins = CsRepos.installed(requireContext()).map { it.name }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            installedPlugins
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        pluginSpinner.adapter = adapter
        pluginSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long) {
                val selected = adapter.getItem(index)
                PrefManager.setVal(PrefName.ContentSource, selected?.toString()?.lowercase() ?: "")
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Restore UI state
        if (selectedMediaType == 0) {
            animeExpanded.visibility = View.VISIBLE
            animeArrow.rotation = 180f
        } else {
            movieExpanded.visibility = View.VISIBLE
            movieArrow.rotation = 180f
        }

        // Restore tracker state
        when (selectedTracker) {
            0 -> aniListCheck.isChecked = true
            1 -> malCheck.isChecked = true
            2 -> simklCheck.isChecked = true
        }
    }

    private fun loadState() {
        selectedMediaType = PrefManager.getVal<Int>(PrefName.SelectedMediaType)
        selectedTracker = PrefManager.getVal<Int>(PrefName.SelectedTracker)
    }

    private fun saveState() {
        PrefManager.setVal(PrefName.SelectedMediaType, selectedMediaType)
        PrefManager.setVal(PrefName.SelectedTracker, selectedTracker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _view = null
    }
}
