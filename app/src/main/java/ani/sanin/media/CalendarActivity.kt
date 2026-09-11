package ani.sanin.media

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.VelocityTracker
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
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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

        // List-only toggle
        binding.calendarListToggle.isChecked = PrefManager.getVal<Boolean>(PrefName.CalendarListOnly)
        binding.calendarListToggle.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.CalendarListOnly, isChecked)
            refreshDisplay(allCalendarData)
        }
        FocusEffectUtil.applyFocusListener(binding.calendarListToggle)

        // Today button
        binding.calendarTodayBtn.setOnClickListener { goToToday() }
        FocusEffectUtil.applyFocusListener(binding.calendarTodayBtn)

        // Start on today, not Monday
        currentWeekStart = (Calendar.getInstance().clone() as Calendar).apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        selectedDate = Calendar.getInstance() // today

        setupWeekNav()
        buildWeekStrip()
        updateWeekLabel()
        updateDayLabel()

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                binding.calendarSpinner.visibility = View.VISIBLE
                binding.calendarDayEpisodes.visibility = android.view.View.GONE
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { model.loadCalendar() }
                    live.postValue(false)
                }
            }
        }

        model.getCalendar().observe(this) { data ->
            binding.calendarSpinner.visibility = View.GONE
            if (data != null) {
                allCalendarData = data
                refreshDisplay(data)
            }
        }
        setupDaySwipe()
    }




    private fun setupDaySwipe() {
        val container = binding.calendarDayEpisodes
        var velocityTracker: VelocityTracker? = null
        var downX = 0f
        var downY = 0f
        var isDragging = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        container.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    downX = event.x
                    downY = event.y
                    isDragging = false
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!isDragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        isDragging = true
                    }
                    false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val dx = event.x - downX
                    val threshold = 80 * resources.displayMetrics.density
                    if (isDragging && (Math.abs(dx) > threshold || Math.abs(vx) > 500f)) {
                        if (dx < 0 || vx < -500f) {
                            // Swipe left / fling left → next day
                            selectedDate.add(Calendar.DAY_OF_YEAR, 1)
                            buildWeekStrip()
                            updateDayLabel()
                            refreshDisplay(allCalendarData)
                        } else {
                            // Swipe right / fling right → previous day
                            selectedDate.add(Calendar.DAY_OF_YEAR, -1)
                            buildWeekStrip()
                            updateDayLabel()
                            refreshDisplay(allCalendarData)
                        }
                        velocityTracker?.recycle()
                        velocityTracker = null
                        true
                    } else {
                        velocityTracker?.recycle()
                        velocityTracker = null
                        false
                    }
                }
                else -> false
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
        refreshDisplay(allCalendarData)
    }

    private fun setupWeekNav() {
        binding.calendarPrevWeek.setOnClickListener {
            currentWeekStart.add(Calendar.DAY_OF_YEAR, -7)
            selectedDate = currentWeekStart.clone() as Calendar
            buildWeekStrip()
            updateWeekLabel()
            updateDayLabel()
            refreshDisplay(allCalendarData)
        }
        FocusEffectUtil.applyFocusListener(binding.calendarPrevWeek)

        binding.calendarNextWeek.setOnClickListener {
            currentWeekStart.add(Calendar.DAY_OF_YEAR, 7)
            selectedDate = currentWeekStart.clone() as Calendar
            buildWeekStrip()
            updateWeekLabel()
            updateDayLabel()
            refreshDisplay(allCalendarData)
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

            val onSurface = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
            val nameTv = TextView(this).apply {
                text = dayNames[i]
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(onSurface)
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
                        setTextColor(onSurface)
                        setBackgroundResource(R.drawable.bg_calendar_day_selected)
                    }
                    isToday -> {
                        setTextColor(onSurface)
                        setBackgroundResource(R.drawable.bg_calendar_day_today)
                    }
                    else -> {
                        setTextColor(onSurface)
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
        refreshDisplay(allCalendarData)
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

        // Collect episodes for selected day — ISO match
        for ((key, list) in data) {
            val keyDate = try { dateFmt.parse(key) } catch (_: Exception) { null }
            val isoMatch = key == selectedIso || (keyDate != null && dateFmt.format(keyDate) == selectedIso)
            if (isoMatch) allEpisodes.addAll(list)
        }

        // Fallback: formatted date string match (movie mode uses "September 10, 2026")
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
                v.findViewById<TextView>(R.id.calendarTitle).text = media.userPreferredName.ifBlank { media.name ?: media.nameRomaji }

                val badge = v.findViewById<TextView>(R.id.calendarBadge)
                val rel = media.relation ?: ""
                val epNum = Regex("""Episode\s+(\d+)""").find(rel)?.groupValues?.get(1)
                val sxeNum = Regex("""S(\d+)E(\d+)""").find(rel)?.groupValues?.let { "S${it[1]}E${it[2]}" }
                val timeStr = if (rel.contains("\n")) rel.lines().getOrNull(1)?.trim() else null
                val isMovie = media.tmdbType == "movie" || media.format == "MOVIE"
                val primaryColor = getThemeColor(com.google.android.material.R.attr.colorPrimary)
                val badgeText = when {
                    isMovie -> {
                        val dateLine = rel.lines().firstOrNull { it != "Movie" && it.isNotBlank() }
                        if (dateLine != null && dateLine.length >= 10) {
                            val parts = dateLine.split("-")
                            if (parts.size >= 3) "Movie \u00b7 ${parts[1]}/${parts[2]} \u00b7 12:00AM" else "Movie"
                        } else "Movie"
                    }
                    sxeNum != null && !timeStr.isNullOrBlank() -> "Ssn${sxeNum.substringAfter("S").substringBefore("E")} Ep ${sxeNum.substringAfter("E")} \u00b7 $timeStr"
                    sxeNum != null -> "Ssn${sxeNum.substringAfter("S").substringBefore("E")} Ep ${sxeNum.substringAfter("E")}"
                    epNum != null && !timeStr.isNullOrBlank() -> "Ep $epNum \u00b7 $timeStr"
                    epNum != null -> "Ep $epNum"
                    else -> "New"
                }
                val spannable = SpannableString(badgeText)
                Regex("\\d+").findAll(badgeText).forEach { match ->
                    spannable.setSpan(ForegroundColorSpan(primaryColor), match.range.first, match.range.last + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                badge.text = spannable
// Card click → info screen
                v.setOnClickListener {
                    val isAnime = media.anime != null
                    val intent = if (isAnime) {
                        Intent(this, MediaDetailsActivity::class.java).apply {
                            putExtra("mediaId", media.id)
                        }
                    } else {
                        Intent(this, ani.sanin.cloudstream.TmdbDetailsActivity::class.java).apply {
                            putExtra("mediaId", media.id)
                            putExtra("mediaType", media.tmdbType ?: "tv")
                        }
                    }
                    startActivity(intent)
                }

                FocusEffectUtil.applyFocusListener(v)
                epContainer.addView(v)
            }
        }
    }


    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
