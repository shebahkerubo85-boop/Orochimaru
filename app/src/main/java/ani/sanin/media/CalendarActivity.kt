package ani.sanin.media

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.databinding.ActivityCalendarBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.navBarHeight
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CalendarActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalendarBinding
    private val model: OtherDetailsViewModel by viewModels()
    private var currentWeekStart = Calendar.getInstance()
    private var selectedDate = Calendar.getInstance()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fullDayFmt = SimpleDateFormat("EEEE, MMMM d", Locale.US)
    private val monthDayFmt = SimpleDateFormat("MMM d", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.calendarRoot.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        binding.calendarBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        FocusEffectUtil.applyFocusListener(binding.calendarBack)

        binding.calendarSettings.setOnClickListener { showFilterSheet() }
        FocusEffectUtil.applyFocusListener(binding.calendarSettings)

        // Start week on Monday
        currentWeekStart.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        currentWeekStart.set(Calendar.HOUR_OF_DAY, 0)
        currentWeekStart.set(Calendar.MINUTE, 0)
        currentWeekStart.set(Calendar.SECOND, 0)
        currentWeekStart.set(Calendar.MILLISECOND, 0)
        selectedDate = currentWeekStart.clone() as Calendar

        setupWeekNav()
        buildWeekStrip()
        updateWeekLabel()
        updateDayLabel()

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { model.loadCalendar() }
                    live.postValue(false)
                }
            }
        }

        model.getCalendar().observe(this) { data ->
            if (data != null) refreshDisplay(data)
        }
    }

    private fun setupWeekNav() {
        binding.calendarPrevWeek.setOnClickListener {
            currentWeekStart.add(Calendar.DAY_OF_YEAR, -7)
            selectedDate = currentWeekStart.clone() as Calendar
            buildWeekStrip()
            updateWeekLabel()
            updateDayLabel()
            model.getCalendar()?.let { refreshDisplay(it) }
        }
        FocusEffectUtil.applyFocusListener(binding.calendarPrevWeek)

        binding.calendarNextWeek.setOnClickListener {
            currentWeekStart.add(Calendar.DAY_OF_YEAR, 7)
            selectedDate = currentWeekStart.clone() as Calendar
            buildWeekStrip()
            updateWeekLabel()
            updateDayLabel()
            model.getCalendar()?.let { refreshDisplay(it) }
        }
        FocusEffectUtil.applyFocusListener(binding.calendarNextWeek)
    }

    private fun buildWeekStrip() {
        val strip = binding.calendarWeekStrip
        strip.removeAllViews()
        val todayIso = dateFmt.format(Date())
        val selectedIso = dateFmt.format(selectedDate.time)
        val dayNames = arrayOf("M", "T", "W", "T", "F", "S", "S")
        val screenW = resources.displayMetrics.widthPixels
        val dayW = (screenW - dpToPx(24)) / 7

        for (i in 0 until 7) {
            val day = (currentWeekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
            val iso = dateFmt.format(day.time)
            val dayNum = day.get(Calendar.DAY_OF_MONTH).toString()
            val isToday = iso == todayIso
            val isSel = iso == selectedIso

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dayW, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(0, dpToPx(4), 0, dpToPx(4))
            }

            val nameTv = TextView(this).apply {
                text = dayNames[i]
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.bg_white))
                alpha = if (isSel || isToday) 1f else 0.4f
            }

            val numTv = TextView(this).apply {
                text = dayNum
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.create(resources.getFont(R.font.poppins_bold), Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
                    topMargin = dpToPx(2)
                }
                if (isSel) {
                    setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.bg_black))
                    setBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorPrimary))
                } else {
                    setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.bg_white))
                    if (!isToday) alpha = 0.5f
                }
            }

            col.addView(nameTv)
            col.addView(numTv)
            col.setOnClickListener { selectDay(iso) }
            strip.addView(col)
        }
    }

    private fun selectDay(iso: String) {
        val cal = Calendar.getInstance()
        try { cal.time = dateFmt.parse(iso) ?: Date() } catch (_: Exception) { return }
        selectedDate = cal
        buildWeekStrip()
        updateDayLabel()
        model.getCalendar()?.let { refreshDisplay(it) }
    }

    private fun updateWeekLabel() {
        val end = (currentWeekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
        binding.calendarWeekLabel.text = "${monthDayFmt.format(currentWeekStart.time)} - ${monthDayFmt.format(end.time)}"
    }

    private fun updateDayLabel() {
        binding.calendarDayLabel.text = fullDayFmt.format(selectedDate.time)
        binding.calendarDayLabel.visibility = View.VISIBLE
    }

    private fun refreshDisplay(data: Map<String, MutableList<Media>>) {
        val showOnlyList = PrefManager.getVal<Boolean>(PrefName.CalendarListOnly)

        // Flatten all entries and find episodes for selected day by ISO
        val selectedIso = dateFmt.format(selectedDate.time)
        val allEpisodes = mutableListOf<Media>()
        for ((key, list) in data) {
            // Key could be ISO date or formatted date string
            val keyDate = try { dateFmt.parse(key) } catch (_: Exception) { null }
            val isoMatch = key == selectedIso || (keyDate != null && dateFmt.format(keyDate) == selectedIso)
            if (isoMatch) allEpisodes.addAll(list)
        }

        // If the data uses full formatted keys (e.g., "September 1, 2026") instead of ISO,
        // try matching by comparing day-of-year in current week
        if (allEpisodes.isEmpty()) {
            val selectedDoy = selectedDate.get(Calendar.DAY_OF_YEAR)
            val selectedYear = selectedDate.get(Calendar.YEAR)
            for ((key, list) in data) {
                val keyDate = try {
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL, Locale.US).parse(key)
                } catch (_: Exception) { null }
                if (keyDate != null) {
                    val keyCal = Calendar.getInstance().apply { time = keyDate }
                    if (keyCal.get(Calendar.DAY_OF_YEAR) == selectedDoy && keyCal.get(Calendar.YEAR) == selectedYear) {
                        allEpisodes.addAll(list)
                    }
                }
            }
        }

        val filtered = if (showOnlyList) {
            allEpisodes.filter { media ->
                val isMovieMode = PrefManager.getVal<String>(PrefName.ContentMode) == "movie_tv"
                if (isMovieMode) media.status != null else media.anime?.userProgress != null || media.status != null
            }
        } else allEpisodes

        // Day episodes
        val epContainer = binding.calendarDayEpisodes
        epContainer.removeAllViews()
        if (filtered.isEmpty()) {
            binding.calendarEmpty.visibility = View.VISIBLE
            epContainer.visibility = View.GONE
        } else {
            binding.calendarEmpty.visibility = View.GONE
            epContainer.visibility = View.VISIBLE
            for (media in filtered) {
                val v = LayoutInflater.from(this).inflate(R.layout.item_calendar_episode, epContainer, false)
                v.findViewById<android.widget.ImageView>(R.id.calendarEpPoster).loadImage(media.cover)
                v.findViewById<TextView>(R.id.calendarEpTitle).text = media.name
                v.findViewById<TextView>(R.id.calendarEpInfo).text = media.relation ?: ""
                FocusEffectUtil.applyFocusListener(v)
                epContainer.addView(v)
            }
        }

        // Upcoming shelf: entries for future dates
        val todayIso = dateFmt.format(Date())
        val upcoming = data.entries
            .filter { it.key > todayIso }
            .flatMap { it.value }
            .distinctBy { it.id }
            .take(20)
        binding.calendarUpcomingSection.visibility = if (upcoming.isNotEmpty()) View.VISIBLE else View.GONE

        // Missing shelf: episodes not airing this week
        val weekIsos = (0..6).map { offset ->
            dateFmt.format((currentWeekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }.time)
        }
        val scheduledIds = data.entries.filter { it.key in weekIsos }.flatMap { it.value }.map { it.id }.toSet()
        val allThisWeek = data.values.flatten().distinctBy { it.id }
        val missing = allThisWeek.filter { it.id !in scheduledIds }.take(20)
        binding.calendarMissingSection.visibility = if (missing.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showFilterSheet() {
        // Build a simple filter dialog
        val builder = this.customAlertDialog()
        val listOnly = PrefManager.getVal<Boolean>(PrefName.CalendarListOnly)
        builder.setTitle(R.string.release_calendar)
        val items = arrayOf("List only")
        val checked = booleanArrayOf(listOnly)
        builder.multiChoiceItems(items, checked) { _, which, isChecked ->
            if (which == 0) PrefManager.setVal(PrefName.CalendarListOnly, isChecked)
        }
        builder.setPosButton(R.string.ok) {
            model.getCalendar()?.let { refreshDisplay(it) }
        }
        builder.setNegButton(R.string.cancel)
        builder.show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
