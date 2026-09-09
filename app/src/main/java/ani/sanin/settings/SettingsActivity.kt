package ani.sanin.settings

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Build.BRAND
import android.os.Build.DEVICE
import android.os.Build.SUPPORTED_ABIS
import android.os.Build.VERSION.CODENAME
import android.os.Build.VERSION.RELEASE
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.BuildConfig
import ani.sanin.R
import ani.sanin.copyToClipboard
import ani.sanin.databinding.ActivitySettingsBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.startMainActivity
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil

/** A collapsible settings section. */
private data class SettingsSection(
    val title: String,
    val iconRes: Int,
    val entries: List<SectionEntry>,
    val defaultExpanded: Boolean = false,
)

/** An item inside a collapsible section. */
private data class SectionEntry(
    val title: String,
    val desc: String? = null,
    val iconRes: Int,
    val onClick: (() -> Unit)? = null,
)

class SettingsActivity : AppCompatActivity() {
    lateinit var binding: ActivitySettingsBinding
    private var cursedCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }

            settingsVersion.text = getString(R.string.version_current, BuildConfig.VERSION_NAME)
            settingsVersion.setOnLongClickListener {
                copyToClipboard(getDeviceInfo(), false)
                toast(getString(R.string.copied_device_info))
                true
            }

            settingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
            FocusEffectUtil.applyFocusListener(settingsBack)

            settingsLogo.setSafeOnClickListener {
                cursedCounter++
                (settingsLogo.drawable as? Animatable)?.start()
                if (cursedCounter % 16 == 0) {
                    val oldVal: Boolean = PrefManager.getVal(PrefName.OC)
                    if (!oldVal) toast(R.string.omega_cursed)
                    else toast(R.string.omega_freed)
                    PrefManager.setVal(PrefName.OC, !oldVal)
                } else {
                    val array = resources.getStringArray(R.array.tips)
                    snackString(array[(Math.random() * array.size).toInt()], this@SettingsActivity)
                }
            }

            val sections = buildSections()
            settingsRecyclerView.layoutManager = LinearLayoutManager(this@SettingsActivity)
            settingsRecyclerView.adapter = SectionAdapter(sections)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (PrefManager.getCustomVal("reload", false)) {
                startMainActivity(this@SettingsActivity)
                PrefManager.setCustomVal("reload", false)
            } else {
                finish()
            }
        }
    }

    private fun buildSections(): List<SettingsSection> = listOf(
        SettingsSection(
            title = "Account",
            iconRes = R.drawable.ic_settings_account,
            defaultExpanded = true,
            entries = listOf(
                SectionEntry(
                    title = getString(R.string.accounts),
                    desc = getString(R.string.accounts_desc),
                    iconRes = R.drawable.ic_settings_account,
                    onClick = { startActivity(Intent(this, SettingsAccountActivity::class.java)) },
                ),
            ),
        ),
        SettingsSection(
            title = "Display",
            iconRes = R.drawable.ic_settings_display,
            defaultExpanded = false,
            entries = listOf(
                SectionEntry(
                    title = getString(R.string.appearance),
                    desc = getString(R.string.appearance_desc),
                    iconRes = R.drawable.ic_settings_display,
                    onClick = { startActivity(Intent(this, SettingsAppearanceActivity::class.java)) },
                ),
                SectionEntry(
                    title = getString(R.string.animation),
                    desc = getString(R.string.animation_desc),
                    iconRes = R.drawable.ic_settings_display,
                    onClick = { startActivity(Intent(this, SettingsAnimationActivity::class.java)) },
                ),
            ),
        ),
        SettingsSection(
            title = "Content",
            iconRes = R.drawable.ic_settings_content,
            defaultExpanded = false,
            entries = listOf(
                SectionEntry(
                    title = getString(R.string.common),
                    desc = getString(R.string.common_desc),
                    iconRes = R.drawable.ic_settings_content,
                    onClick = { startActivity(Intent(this, SettingsCommonActivity::class.java)) },
                ),
                SectionEntry(
                    title = getString(R.string.anime),
                    desc = getString(R.string.anime_desc),
                    iconRes = R.drawable.ic_settings_content,
                    onClick = { startActivity(Intent(this, SettingsAnimeActivity::class.java)) },
                ),
            ),
        ),
        SettingsSection(
            title = "System",
            iconRes = R.drawable.ic_settings_tools,
            defaultExpanded = false,
            entries = listOf(
                SectionEntry(
                    title = getString(R.string.extras),
                    desc = "Extensions, addons, notifications & logs",
                    iconRes = R.drawable.ic_settings_tools,
                    onClick = { startActivity(Intent(this, SettingsExtrasActivity::class.java)) },
                ),
            ),
        ),
    )

    override fun onResume() {
        ThemeManager(this).applyTheme()
        super.onResume()
    }

    /** RecyclerView adapter that renders collapsible sections. */
    private inner class SectionAdapter(
        private val sections: List<SettingsSection>,
    ) : RecyclerView.Adapter<SectionAdapter.SectionVH>() {

        inner class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
            val header: LinearLayout = v.findViewById(R.id.sectionHeader)
            val icon: ImageView = v.findViewById(R.id.sectionIcon)
            val title: TextView = v.findViewById(R.id.sectionTitle)
            val chevron: ImageView = v.findViewById(R.id.sectionChevron)
            val items: LinearLayout = v.findViewById(R.id.sectionItems)
        }

        private val expandedSet = mutableSetOf<Int>().apply {
            sections.forEachIndexed { i, s -> if (s.defaultExpanded) add(i) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_section, parent, false)
            return SectionVH(v)
        }

        override fun onBindViewHolder(holder: SectionVH, position: Int) {
            val section = sections[position]
            val isExpanded = position in expandedSet

            holder.icon.setImageResource(section.iconRes)
            holder.title.text = section.title

            // Chevron rotation
            holder.chevron.rotation = if (isExpanded) 180f else 0f

            // Expand / collapse
            holder.header.setOnClickListener {
                toggleSection(holder, position)
            }
            FocusEffectUtil.applyFocusListener(holder.header)

            // Populate items
            holder.items.removeAllViews()
            val inflater = LayoutInflater.from(this@SettingsActivity)
            section.entries.forEach { entry ->
                val entryView = inflater.inflate(
                    R.layout.item_settings_section_entry, holder.items, false,
                )
                entryView.findViewById<ImageView>(R.id.entryIcon).setImageResource(entry.iconRes)
                entryView.findViewById<TextView>(R.id.entryTitle).text = entry.title
                entryView.findViewById<TextView>(R.id.entryDesc).apply {
                    if (entry.desc != null) {
                        text = entry.desc
                        visibility = View.VISIBLE
                    } else {
                        visibility = View.GONE
                    }
                }
                entryView.setOnClickListener { entry.onClick?.invoke() }
                FocusEffectUtil.applyFocusListener(entryView)
                holder.items.addView(entryView)
            }

            holder.items.visibility = if (isExpanded) View.VISIBLE else View.GONE
        }

        private fun toggleSection(holder: SectionVH, position: Int) {
            val isExpanding = position !in expandedSet
            if (isExpanding) expandedSet.add(position) else expandedSet.remove(position)

            // Animate chevron
            ObjectAnimator.ofFloat(holder.chevron, "rotation",
                if (isExpanding) 0f else 180f,
                if (isExpanding) 180f else 0f,
            ).apply {
                duration = 250
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }

            // Animate content
            if (isExpanding) {
                holder.items.visibility = View.VISIBLE
                holder.items.alpha = 0f
                holder.items.animate().alpha(1f).setDuration(200).start()
            } else {
                holder.items.animate().alpha(0f).setDuration(150).withEndAction {
                    holder.items.visibility = View.GONE
                }.start()
            }
        }

        override fun getItemCount() = sections.size
    }

    companion object {
        fun getDeviceInfo(): String = """
            sanin Version: ${BuildConfig.VERSION_NAME}
            Device: $BRAND $DEVICE
            Architecture: ${getArch()}
            OS Version: $CODENAME $RELEASE ($SDK_INT)
        """.trimIndent()

        private fun getArch(): String {
            SUPPORTED_ABIS.forEach {
                when (it) {
                    "arm64-v8a" -> return "aarch64"
                    "armeabi-v7a" -> return "arm"
                    "x86_64" -> return "x86_64"
                    "x86" -> return "i686"
                }
            }
            return System.getProperty("os.arch") ?: System.getProperty("os.product.cpu.abi")
                ?: "Unknown Architecture"
        }
    }
}
