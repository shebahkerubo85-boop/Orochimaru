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
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MediaTrackerBottomSheet : BottomSheetDialogFragment() {

    var selectedMediaType: Int = 0
    var selectedTracker: Int = 0

    private var _view: View? = null

    private val sheetMediaAnimeSection: View
        get() = _view!!.findViewById(R.id.sheetMediaAnimeSection)

    private val sheetMovieTVSection: View
        get() = _view!!.findViewById(R.id.sheetMovieTVSection)

    private val sheetMediaTrackerIcon: ImageView
        get() = _view!!.findViewById(R.id.sheetMediaTrackerIcon)

    private val sheetMediaTrackerName: TextView
        get() = _view!!.findViewById(R.id.sheetMediaTrackerName)

    private val sheetMediaAniListCheckbox: CheckBox
        get() = _view!!.findViewById(R.id.sheetMediaAniListCheckbox)

    private val sheetMediaMALCheckbox: CheckBox
        get() = _view!!.findViewById(R.id.sheetMediaMALCheckbox)

    private val sheetMovieTVSimklCheckbox: CheckBox
        get() = _view!!.findViewById(R.id.sheetMovieTVSimklCheckbox)

    private val sheetMovieTVPluginSpinner: Spinner
        get() = _view!!.findViewById(R.id.sheetMovieTVPluginSpinner)

    private val sheetLoginGuard: View
        get() = _view!!.findViewById(R.id.sheetLoginGuard)

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
        sheetMediaAnimeSection.visibility = if (selectedMediaType == 0) View.VISIBLE else View.GONE
        sheetMovieTVSection.visibility = if (selectedMediaType == 1) View.VISIBLE else View.GONE

        sheetMediaAniListCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sheetMediaMALCheckbox.isChecked = false
                selectedTracker = 0
                saveState()
                updateTrackerDisplay()
            }
        }

        sheetMediaMALCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sheetMediaAniListCheckbox.isChecked = false
                selectedTracker = 1
                saveState()
                updateTrackerDisplay()
            }
        }

        sheetMovieTVSimklCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedTracker = 2
                saveState()
                updateTrackerDisplay()
            }
        }

        val adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.plugin_names,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sheetMovieTVPluginSpinner.adapter = adapter
        sheetMovieTVPluginSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, index: Int, id: Long) {
                PrefManager.setVal(PrefName.ContentSource, parent?.getItemAtPosition(index).toString().lowercase())
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        updateTrackerDisplay()
    }

    private fun updateTrackerDisplay() {
        val trackerName = when (selectedTracker) {
            0 -> requireContext().getString(R.string.anilist)
            1 -> "MyAnimeList"
            2 -> "Simkl"
            else -> "AniList"
        }
        sheetMediaTrackerName.text = trackerName

        val iconRes = when (selectedTracker) {
            0 -> R.drawable.ic_anilist
            2 -> R.drawable.ic_simkl
            else -> R.drawable.ic_anilist
        }
        sheetMediaTrackerIcon.setImageResource(iconRes)

        val isLoggedIn = when (selectedTracker) {
            0 -> Anilist.getSavedToken()
            1 -> !MAL.username.isNullOrEmpty()
            2 -> !Simkl.token.isNullOrEmpty()
            else -> false
        }
        sheetLoginGuard.visibility = if (!isLoggedIn) View.VISIBLE else View.GONE
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
