package ani.sanin.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat

class MediaTrackerBottomSheet : Fragment() {

    // Tracker state
    var selectedMediaType: Int = 0  // 0=anime, 1=movie
    var selectedTracker: Int = 0    // 0=AniList, 1=MAL, 2=Simkl

    private var _view: View? = null

    private val sheetMediaAnimeSection: View
        get = _view!!.findViewById(R.id.sheetMediaAnimeSection)

    private val sheetMediaTrackerContainer: View
        get = _view!!.findViewById(R.id.sheetMediaTrackerContainer)

    private val sheetMediaTrackerIcon: ImageView
        get = _view!!.findViewById(R.id.sheetMediaTrackerIcon)

    private val sheetMediaTrackerName: TextView
        get = _view!!.findViewById(R.id.sheetMediaTrackerName)

    private val sheetMediaAniListCheckbox: CheckBox
        get = _view!!.findViewById(R.id.sheetMediaAniListCheckbox)

    private val sheetMediaMALCheckbox: CheckBox
        get = _view!!.findViewById(R.id.sheetMediaMALCheckbox)

    private val sheetMovieTVSimklCheckbox: CheckBox
        get = _view!!.findViewById(R.id.sheetMovieTVSimklCheckbox)

    private val sheetMovieTVPluginDropdown: View
        get = _view!!.findViewById(R.id.sheetMovieTVPluginDropdown)

    private val sheetMovieTVPluginSpinner: Spinner
        get = _view!!.findViewById(R.id.sheetMovieTVPluginSpinner)

    private val sheetLoginGuard: View
        get = _view!!.findViewById(R.id.sheetLoginGuard)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _view = inflater.inflate(R.layout.bottom_sheet_media_tracker, container, false)
        return _view!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        loadState()
    }

    private fun setupUI() {
        // Set up anime section visibility based on selection
        sheetMediaAnimeSection.visibility = if (selectedMediaType == 0) View.VISIBLE else View.GONE
        sheetMovieTVSection.visibility = if (selectedMediaType == 1) View.VISIBLE else View.GONE

        // Set up tracker click listeners
        sheetMediaAniListCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sheetMediaMALCheckbox.isChecked = false
                selectedTracker = 0  // AniList
            }
        }
        sheetMediaMALCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sheetMediaAniListCheckbox.isChecked = false
                selectedTracker = 1  // MAL
            }
        }

        // Movie/TV Simkl checkbox
        sheetMovieTVSimklCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedTracker = 2  // Simkl
                // Auto-select Simkl plugin
                sheetMovieTVSimklCheckbox.isChecked = true
            }
        }

        // Plugin dropdown for movie mode
        val adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.plugin_names,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_view)
        sheetMovieTVPluginSpinner.adapter = adapter
        sheetMovieTVPluginSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: Position?, index: Int) {
                // Handle plugin selection
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Animate profile pic/banner when home mode is profile
        animateProfileBackground()

        // Set up initial state
        updateTrackerDisplay()
    }

    private fun updateTrackerDisplay() {
        // Update profile banner based on selected tracker
        val trackerName = when (selectedTracker) {
            0 -> requireContext().getString(R.string.anilist)
            1 -> requireContext().getString(R.string.mal)
            2 -> requireContext().getString(R.string.simkl)
            else -> "AniList"
        }
        sheetMediaTrackerName.text = trackerName

        // Set icon based on tracker
        val iconRes = when (selectedTracker) {
            0 -> R.drawable.ic_anilist
            2 -> R.drawable.ic_simkl
            else -> R.drawable.ic_anilist
        }
        sheetMediaTrackerIcon.setImageResource(iconRes)

        // Show/hide login guard if not authenticated
        val isLoggedIn = checkTrackerLogin(selectedTracker)
        sheetLoginGuard.visibility = if (!isLoggedIn) View.VISIBLE else View.GONE
    }

    private fun checkTrackerLogin(trackerId: Int): Boolean {
        return when (selectedTracker) {
            0 -> Anilist.isLoggedIn(requireContext())
            1 -> MAL.isLoggedIn(requireContext())
            2 -> Simkl.isLoggedIn(requireContext())
            else -> false
        }
    }

    private fun animateProfileBackground() {
        // Animate profile pic/banner when home mode is profile
        val profileView = sheetMediaTrackerIcon
        // This would use the same animation toggle and settings as home mode
        // For now, simple fade-in
        profileView.alpha = 0f
        profileView.animate().alpha(1f).setDuration(300)
    }

    private fun loadState() {
        // Load persisted state from SharedPreferences
        val prefManager = PrefManager(requireContext())
        selectedMediaType = prefManager.getVal(PrefName.SelectedMediaType) ?: 0
        selectedTracker = prefManager.getVal(PrefName.SelectedTracker) ?: 0
        updateTrackerDisplay()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _view = null
    }
}
