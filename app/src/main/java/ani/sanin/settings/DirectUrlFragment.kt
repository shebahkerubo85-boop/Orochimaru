package ani.sanin.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import ani.sanin.R
import ani.sanin.getThemeColor
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.TvKeyboardUtil
import ani.sanin.util.customAlertDialog

/**
 * Direct URL tab: shows the 3 named Link slots (Option 2) plus any auto-detected
 * site configs (Option 1). Every entry is a big couch-readable card: large title,
 * URL below in primary color, with configure / rename / delete actions.
 */
class DirectUrlFragment : Fragment() {

    private var root: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        scroll.addView(root)
        return scroll
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Called by the host activity when a config is added so the list updates. */
    fun refreshList() {
        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        val ctx = requireContext()
        val container = root ?: return
        container.removeAllViews()

        val slotHeader = header(ctx, getString(R.string.direct_url_slots_title))
        container.addView(slotHeader)

        (1..DirectUrlManager.SLOT_COUNT).forEach { slot ->
            container.addView(slotCard(ctx, slot, DirectUrlManager.getSlotConfig(ctx, slot)))
        }

        val autoConfigs = DirectUrlManager.getConfigs(ctx).filter { it.slotIndex == null }
        if (autoConfigs.isNotEmpty()) {
            container.addView(header(ctx, getString(R.string.direct_url_auto_title)))
            autoConfigs.forEach { config ->
                container.addView(configCard(ctx, config))
            }
        } else {
            container.addView(
                TextView(ctx).apply {
                    text = getString(R.string.no_direct_urls)
                    textSize = 16f
                    setPadding(0, dp(20), 0, dp(8))
                    gravity = Gravity.CENTER
                    setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                }
            )
        }
    }

    private fun header(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title
        textSize = 18f
        setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(8))
        setTextColor(getThemeColor(com.google.android.material.R.attr.colorPrimary))
    }

    private fun slotCard(ctx: Context, slot: Int, config: DirectUrlManager.DirectUrlConfig?): View {
        val name = DirectUrlManager.slotName(slot)
        return bigCard(ctx, name, config?.url, hint = getString(R.string.direct_url_tap_configure), slot = slot) {
            if (config != null) {
                openMenu(config)
            } else {
                UrlPlayBottomSheet.newInstance(slot).apply { onSaved = { refresh() } }
                    .show(parentFragmentManager, "direct_url_slot")
            }
        }
    }

    private fun configCard(ctx: Context, config: DirectUrlManager.DirectUrlConfig): View =
        bigCard(ctx, config.name, config.url, hint = null, slot = null) {
            openMenu(config)
        }

    private fun bigCard(
        ctx: Context,
        name: String,
        url: String?,
        hint: String?,
        slot: Int?,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(96)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            isFocusable = true
            isClickable = true
            isLongClickable = true
            background = roundedCard()
            setOnClickListener { onClick() }
            setOnLongClickListener {
                Toast.makeText(ctx, name, Toast.LENGTH_SHORT).show()
                true
            }
        }
        FocusEffectUtil.applyFocusListener(card)

        val title = TextView(ctx).apply {
            text = name
            textSize = 22f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        card.addView(title)

        val urlText = url?.takeIf { it.isNotBlank() } ?: hint.orEmpty()
        if (urlText.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = urlText
                textSize = 14f
                maxLines = 1
                setTextColor(getThemeColor(com.google.android.material.R.attr.colorPrimary))
                setPadding(0, dp(4), 0, 0)
            })
        }

        val spacer = LinearLayout(ctx)
        card.addView(spacer, LinearLayout.LayoutParams(0, dp(8), 1f))

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (url != null) {
            actions.addView(actionButton(ctx, getString(R.string.direct_url_edit)) {
                UrlPlayBottomSheet.newInstance(slot).apply { onSaved = { refresh() } }
                    .show(parentFragmentManager, "direct_url_edit")
            })
        } else {
            actions.addView(actionButton(ctx, getString(R.string.direct_url_save)) { onClick() })
        }
        card.addView(actions)
        return card
    }

    private fun roundedCard(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(ContextCompat.getColor(requireContext(), R.color.bg_opp))
        setStroke(dp(1), android.graphics.Color.argb(70, 255, 255, 255))
    }

    private fun actionButton(ctx: Context, text: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            textSize = 14f
            isFocusable = true
            setOnClickListener { onClick() }
            setTextColor(getThemeColor(com.google.android.material.R.attr.colorPrimary))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            FocusEffectUtil.applyFocusListener(this)
        }

    /** Open an edit menu: configure URL, rename, delete. */
    private fun openMenu(config: DirectUrlManager.DirectUrlConfig) {
        val ctx = requireContext()
        val options = arrayOf(
            getString(R.string.direct_url_edit),
            getString(R.string.direct_url_rename),
            getString(R.string.delete_direct_url)
        )
        customAlertDialog().apply {
            setTitle(config.name)
            singleChoiceItems(options) { index ->
                when (index) {
                    0 -> UrlPlayBottomSheet.newInstance(config.slotIndex).apply {
                        onSaved = { refresh() }
                    }.show(parentFragmentManager, "direct_url_edit")
                    1 -> openRenameDialog(config.name)
                    else -> {
                        DirectUrlManager.removeConfig(ctx, config.name)
                        refresh()
                    }
                }
            }
            show()
        }
    }



    private fun openRenameDialog(configName: String) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(configName)
            inputType = InputType.TYPE_CLASS_TEXT
            selectAllOnFocus = true
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        customAlertDialog().apply {
            setTitle(getString(R.string.direct_url_rename))
            setCustomView(input)
            setPosButton(getString(R.string.ok)) {
                val newName = input.text.toString().trim()
                if (newName.isNotBlank() && newName != configName) {
                    val all = DirectUrlManager.getConfigs(ctx)
                    val target = all.firstOrNull { it.name == configName } ?: return@setPosButton
                    DirectUrlManager.removeConfig(ctx, configName)
                    DirectUrlManager.saveConfig(ctx, target.copy(name = newName))
                    refresh()
                }
            }
            setNegButton(getString(R.string.cancel)) {}
            setOnShowListener {
                TvKeyboardUtil.setupTvInput(input)
            }
            show()
        }
    }
}
