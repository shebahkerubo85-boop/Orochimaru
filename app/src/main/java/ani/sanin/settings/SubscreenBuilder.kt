package ani.sanin.settings

import android.animation.ObjectAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.android.material.slider.Slider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ani.sanin.R
import ani.sanin.setSafeOnClickListener
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Programmatic builder for card-based settings subscreens.
 * Each section gets a top-rim card header + collapsible card body.
 */
object SubscreenBuilder {

    /** A collapsible section containing settings entries. */
    data class Section(
        val title: String,
        val iconRes: Int,
        val entries: List<Entry>,
        val defaultExpanded: Boolean = false,
        val desc: String? = null,
    )

    data class Slider(
        val value: Float,
        val valueFrom: Float,
        val valueTo: Float,
        val step: Float = 1f,
        val suffix: String = "",
        val onValueChange: (Float) -> Unit,
    )

    /** A single setting entry. */
    data class Entry(
        val title: String,
        val desc: String? = null,
        val iconRes: Int = 0,
        val onClick: ((Context) -> Unit)? = null,
        val onLongClick: ((Context) -> Unit)? = null,
        /** For switch entries: (initialValue, onToggle) */
        val switch: Pair<Boolean, (Boolean) -> Unit>? = null,
        /** For choice entries: title, options, currentIndex, onSelect */
        val choice: Choice? = null,
        /** For slider entries */
        val slider: Slider? = null,
    )

    data class Choice(
        val title: String,
        val options: Array<String>,
        val currentIndex: Int,
        val onSelect: (Int) -> Unit,
    )

    /**
     * Populate a container with collapsible card sections.
     * @param context Activity context
     * @param container The LinearLayout inside the subscreen content area
     * @param sections List of sections to render
     */
    fun build(
        context: Context,
        container: LinearLayout,
        sections: List<Section>,
    ) {
        val inflater = LayoutInflater.from(context)
        container.removeAllViews()

        sections.forEachIndexed { sIdx, section ->
            val sectionView = inflater.inflate(R.layout.item_settings_section, container, false)
            val header = sectionView.findViewById<LinearLayout>(R.id.sectionHeader)
            val icon = sectionView.findViewById<ImageView>(R.id.sectionIcon)
            val title = sectionView.findViewById<TextView>(R.id.sectionTitle)
            val desc = sectionView.findViewById<TextView>(R.id.sectionDesc)
            val chevron = sectionView.findViewById<ImageView>(R.id.sectionChevron)
            val items = sectionView.findViewById<LinearLayout>(R.id.sectionItems)

            icon.setImageResource(section.iconRes)
            title.text = section.title
            if (section.desc != null) {
                desc.text = section.desc
                desc.visibility = View.VISIBLE
            }

            // Populate entries
            section.entries.forEach { entry ->
                if (entry.switch != null) {
                    val switchView = inflater.inflate(R.layout.item_settings_section_switch, items, false)
                    val sIcon = switchView.findViewById<ImageView>(R.id.switchIcon)
                    val sTitle = switchView.findViewById<TextView>(R.id.switchTitle)
                    val sToggle = switchView.findViewById<MaterialSwitch>(R.id.switchToggle)

                    if (entry.iconRes != 0) sIcon.setImageResource(entry.iconRes) else sIcon.visibility = View.GONE
                    sTitle.text = entry.title
                    sToggle.isChecked = entry.switch!!.first
                    sToggle.setOnCheckedChangeListener { _, isChecked -> entry.switch.second(isChecked) }
                    sTitle.setOnClickListener {
                        sToggle.isChecked = !sToggle.isChecked
                    }
                    if (entry.onLongClick != null) {
                        switchView.setOnLongClickListener { entry.onLongClick!!.invoke(context); true }
                    }
                    FocusEffectUtil.applyFocusListener(switchView)
                    items.addView(switchView)
                } else if (entry.slider != null) {
                    val sliderView = inflater.inflate(R.layout.item_settings_section_slider, items, false)
                    val slTitle = sliderView.findViewById<TextView>(R.id.sliderTitle)
                    val sl = sliderView.findViewById<Slider>(R.id.slider)
                    val slValue = sliderView.findViewById<TextView>(R.id.sliderValue)
                    slTitle.text = entry.title
                    sl.valueFrom = entry.slider.valueFrom
                    sl.valueTo = entry.slider.valueTo
                    sl.stepSize = entry.slider.step
                    sl.value = entry.slider.value
                    slValue.text = "${entry.slider.value.toInt()}${entry.slider.suffix}"
                    sl.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                            slValue.text = "${value.toInt()}${entry.slider.suffix}"
                            entry.slider.onValueChange(value)
                        }
                    }
                    FocusEffectUtil.applyFocusListener(sliderView)
                    items.addView(sliderView)
                } else {
                    val entryView = inflater.inflate(R.layout.item_settings_section_entry, items, false)
                    val eIcon = entryView.findViewById<ImageView>(R.id.entryIcon)
                    val eTitle = entryView.findViewById<TextView>(R.id.entryTitle)
                    val eDesc = entryView.findViewById<TextView>(R.id.entryDesc)
                    val eChevron = entryView.findViewById<ImageView>(R.id.entryChevron)

                    if (entry.iconRes != 0) eIcon.setImageResource(entry.iconRes) else eIcon.visibility = View.GONE
                    eTitle.text = entry.title
                    if (entry.desc != null) {
                        eDesc.text = entry.desc
                        eDesc.visibility = View.VISIBLE
                    }
                    if (entry.onClick != null) {
                        entryView.setSafeOnClickListener { entry.onClick!!.invoke(context) }
                    }
                    if (entry.onLongClick != null) {
                        entryView.setOnLongClickListener { entry.onLongClick!!.invoke(context); true }
                    }
                    if (entry.choice != null) {
                        entryView.setSafeOnClickListener {
                            val c = entry.choice!!
                            context.customAlertDialog().apply {
                                setTitle(c.title)
                                singleChoiceItems(c.options, c.currentIndex) { idx -> c.onSelect(idx) }
                                show()
                            }
                        }
                    } else {
                        eChevron.visibility = View.GONE
                    }
                    FocusEffectUtil.applyFocusListener(entryView)
                    items.addView(entryView)
                }
            }

            // Expand/collapse
            val expanded = mutableSetOf<Int>()
            if (section.defaultExpanded) expanded.add(sIdx)

            fun toggle() {
                val isExpanding = sIdx !in expanded
                if (isExpanding) expanded.add(sIdx) else expanded.remove(sIdx)

                ObjectAnimator.ofFloat(chevron, "rotation",
                    if (isExpanding) 0f else 180f,
                    if (isExpanding) 180f else 0f,
                ).apply { duration = 250; interpolator = AccelerateDecelerateInterpolator(); start() }

                if (isExpanding) {
                    items.visibility = View.VISIBLE
                    items.alpha = 0f
                    items.animate().alpha(1f).setDuration(200).start()
                } else {
                    items.animate().alpha(0f).setDuration(150).withEndAction {
                        items.visibility = View.GONE
                    }.start()
                }
            }

            header.setSafeOnClickListener { toggle() }
            FocusEffectUtil.applyFocusListener(header)
            chevron.rotation = if (section.defaultExpanded) 180f else 0f
            items.visibility = if (section.defaultExpanded) View.VISIBLE else View.GONE

            container.addView(sectionView)

            // Add spacing between sections
            if (sIdx < sections.lastIndex) {
                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (8 * context.resources.displayMetrics.density).toInt()
                    )
                }
                container.addView(spacer)
            }
        }
    }
}
