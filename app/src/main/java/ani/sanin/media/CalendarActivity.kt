package ani.sanin.media

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private var allCalendarData: Map<String, MutableList<Media>> = emptyMap()

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

        binding.calendarTodayBtn.setOnClickListener { goToToday() }
        FocusEffectUtil.applyFocusListener(binding.calendarTodayBtn)

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
            if (data != null) {
                allCalendarData = data
                refreshDisplay(data)
            }
        }
    }

    private fun goToToday() {
        val today = Calendar.getInstance()
        currentWeekStart = (today.clone() as Calendar).apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        selectedDate = today.clone() as Calendar
        buildWeekStrip()
        updateWeekLabel()
        updateDayLabel()
        allCalendarData.let { refreshDisplay(it) }
    }

    private fun setupWeekNav() {
        binding.calendarPrevWeek.setOnClickListener {
            currentWeekStart.add(Calendar.DAY_OF_YEAR, -7)
            selectedDate = currentWeekStart.clone() as Calendar
            buildWeekStrip()
            updateWeekLabel()
            updateDayLabel()
            allCalendarData.let { refreshDisplay(it) }
        }
        FocusEffectUtil.applyFocusListener(binding.calendarPrevWeek)

        binding.calendarNextWeek.setOnClickListener {
            currentWeekStart.add(Calendar.DAY_OF_YEAR, 7)
            selectedDate = currentWeekStart.clone() as Calendar
            buildWeekStrip()
            updateWeekLabel()
            updateDayLabel()
            allCalendarData.let { refreshDisplay(it) }
        }
        FocusEffectUtil.applyFocusListener(binding.calendarNextWeek)
    }

    private fun buildWeekStrip() {
        val strip = binding.calendarWeekStrip
        strip.removeAllViews()
        val todayIso = dateFmt.format(Date())
        val selectedIso = dateFmt.format(selectedDate.time)
        val dayNames = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
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
                layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply { topMargin = dpToPx(2) }
                when {
                    isSel -> {
                        setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.bg_black))
                        setBackgroundResource(R.drawable.bg_calendar_day_selected)
                    }
                    isToday -> {
                        setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.bg_white))
                        setBackgroundResource(R.drawable.bg_calendar_day_today)
                    }
                    else -> {
                        setTextColor(ContextCompat.getColor(this@CalendarActivity, R.color.bg_white))
                        setBackgroundColor(0)
                        alpha = 0.5f
                    }
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
        allCalendarData.let { refreshDisplay(it) }
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
        val selectedIso = dateFmt.format(selectedDate.time)
        val allEpisodes = mutableListOf<Media>()

        // Collect episodes for selected day
        for ((key, list) in data) {
            val keyDate = try { dateFmt.parse(key) } catch (_: Exception) { null }
            val isoMatch = key == selectedIso || (keyDate != null && dateFmt.format(keyDate) == selectedIso)
            if (isoMatch) allEpisodes.addAll(list)
        }

        // Fallback: try matching by formatted date string
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
            allEpisodes.filter { it.userProgress != null || it.userStatus != null }
        } else allEpisodes

        // === Portrait cards for scheduled episodes ===
        val epContainer = binding.calendarDayEpisodes
        epContainer.removeAllViews()
        if (filtered.isEmpty()) {
            binding.calendarEmpty.visibility = View.VISIBLE
            epContainer.visibility = View.GONE
        } else {
            binding.calendarEmpty.visibility = View.GONE
            epContainer.visibility = View.VISIBLE
            for (media in filtered) {
                val v = LayoutInflater.from(this).inflate(R.layout.item_calendar_poster, epContainer, false)
                v.findViewById<android.widget.ImageView>(R.id.calendarPoster).loadImage(media.cover)
                v.findViewById<TextView>(R.id.calendarTitle).text = media.name

                val badge = v.findViewById<TextView>(R.id.calendarBadge)
                val rel = media.relation ?: ""
                val epNum = Regex("""Episode\s+(\d+)""").find(rel)?.groupValues?.get(1)
                val timeStr = if (rel.contains("\n")) rel.lines().getOrNull(1)?.trim() else null
                val isMovie = media.tmdbType == "movie"

                badge.text = when {
                    isMovie -> "Movie"
                    epNum != null && !timeStr.isNullOrBlank() -> "Ep $epNum \u00b7 $timeStr"
                    epNum != null -> "Ep $epNum"
                    else -> "New"
                }

                FocusEffectUtil.applyFocusListener(v)
                epContainer.addView(v)
            }
        }

        // === Upcoming list episodes (landscape) — only when list-only is ON ===
        if (showOnlyList) {
            val todayIso = dateFmt.format(Date())
            val upcomingList = mutableListOf<Media>()
            for ((key, list) in data) {
                if (key > todayIso) {
                    for (media in list) {
                        if (media.userProgress != null || media.userStatus != null) {
                            upcomingList.add(media)
                        }
                    }
                }
            }
            val distinct = upcomingList.distinctBy { it.id }.take(20)

            if (distinct.isNotEmpty()) {
                binding.calendarUpcomingSection.visibility = View.VISIBLE
                val container = binding.calendarUpcomingRecycler
                container.removeAllViews()
                for (media in distinct) {
                    val v = LayoutInflater.from(this).inflate(R.layout.item_calendar_landscape, container, false)
                    // Episode thumbnail fallback to poster
                    v.findViewById<android.widget.ImageView>(R.id.calendarLandscapeImg).loadImage(media.cover)

                    // Episode title below image
                    val rel = media.relation ?: ""
                    val epTitle = rel.lines().firstOrNull()?.trim()
                    val titleTv = v.findViewById<TextView>(R.id.calendarLandscapeTitle)
                    val animeTv = v.findViewById<TextView>(R.id.calendarLandscapeAnime)

                    titleTv.text = if (!epTitle.isNullOrBlank()) epTitle else media.name
                    animeTv.text = media.name

                    FocusEffectUtil.applyFocusListener(v)
                    container.addView(v)
                }
            } else {
                binding.calendarUpcomingSection.visibility = View.GONE
            }
        } else {
            binding.calendarUpcomingSection.visibility = View.GONE
        }
    }

    private fun showFilterSheet() {
        val listOnly = PrefManager.getVal<Boolean>(PrefName.CalendarListOnly)
        this.customAlertDialog().apply {
            setTitle(R.string.release_calendar)
            multiChoiceItems(arrayOf("List only"), booleanArrayOf(listOnly)) { checked ->
                PrefManager.setVal(PrefName.CalendarListOnly, checked[0])
            }
            setPosButton(R.string.ok) {
                allCalendarData.let { refreshDisplay(it) }
            }
            setNegButton(R.string.cancel)
        }.show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
