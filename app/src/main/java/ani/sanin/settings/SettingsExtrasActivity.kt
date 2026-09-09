package ani.sanin.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.restartApp
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.customAlertDialog
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsExtrasActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsSubscreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsSubscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subscreenContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.subscreenBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.subscreenTitle.text = getString(R.string.extras)
        binding.subscreenSubtitle.text = "Extensions, addons, notifications & logs"
        binding.subscreenIcon.setImageResource(R.drawable.ic_settings_tools)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section("Sources", R.drawable.ic_settings_tools, listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.extensions),
                    desc = "Manage installed extensions & repositories",
                    iconRes = R.drawable.ic_settings_tools,
                    onClick = { startActivity(Intent(this, SettingsExtensionsActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.addons),
                    desc = "Plugins and addons",
                    iconRes = R.drawable.ic_settings_tools,
                    onClick = { startActivity(Intent(this, SettingsAddonActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = "Cache",
                    desc = "Clear cache & storage",
                    iconRes = R.drawable.ic_set_backup,
                    onClick = { startActivity(Intent(this, SettingsCacheActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = "Logs",
                    desc = "View app logs & diagnostics",
                    iconRes = R.drawable.ic_settings_content,
                    onClick = { startActivity(Intent(this, SettingsLogActivity::class.java)) },
                ),
            ), defaultExpanded = true),

            SubscreenBuilder.Section("Notifications", R.drawable.ic_settings_display, listOf(
                SubscreenBuilder.Entry(
                    title = "Notification Settings",
                    desc = "Configure notification preferences",
                    iconRes = R.drawable.ic_settings_display,
                    onClick = { startActivity(Intent(this, SettingsNotificationActivity::class.java)) },
                ),
            )),

            SubscreenBuilder.Section("Display", R.drawable.ic_set_theme, listOf(
                SubscreenBuilder.Entry(
                    title = "Immersive Mode",
                    desc = "Hide system bars during playback",
                    switch = PrefManager.getVal<Boolean>(PrefName.ImmersiveMode) to {
                        PrefManager.setVal(PrefName.ImmersiveMode, it); restartApp()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Small View",
                    desc = "Compact video player mode",
                    switch = PrefManager.getVal<Boolean>(PrefName.SmallView) to {
                        PrefManager.setVal(PrefName.SmallView, it); restartApp()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Emoji",
                    desc = "Enable emoji support",
                    switch = PrefManager.getVal<Boolean>(PrefName.Emoji) to {
                        PrefManager.setVal(PrefName.Emoji, it)
                    },
                ),
            )),

            SubscreenBuilder.Section("Home Layout", R.drawable.ic_settings_content, listOf(
                SubscreenBuilder.Entry(
                    title = "Customize Home Sections",
                    desc = "Show/hide & reorder home feed sections",
                    iconRes = R.drawable.ic_settings_content,
                    onClick = { showHomeLayoutDialog() },
                ),
            )),
        ))
    }

    private fun showHomeLayoutDialog() {
        val currentVisibility = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout).toMutableList()
        var currentOrder = PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder).toMutableList()
        val views = resources.getStringArray(R.array.home_layouts)

        if (currentVisibility.size < views.size) {
            repeat(views.size - currentVisibility.size) { currentVisibility.add(true) }
        } else if (currentVisibility.size > views.size) {
            currentVisibility.subList(views.size, currentVisibility.size).clear()
        }

        val allIndices = views.indices.toList()
        if (currentOrder.isEmpty()) {
            currentOrder = allIndices.toMutableList()
        } else {
            val sanitizedOrder = currentOrder.filter { it in allIndices }.distinct().toMutableList()
            val missing = allIndices.filterNot { it in sanitizedOrder }
            sanitizedOrder.addAll(missing)
            currentOrder = sanitizedOrder
        }

        val displayList = currentOrder.toMutableList()

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsExtrasActivity)
            setPadding(0, 32, 0, 0)
            clipToPadding = false
        }
        val adapter = HomeLayoutAdapter(displayList, views, currentVisibility)
        recyclerView.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                val item = displayList.removeAt(from)
                displayList.add(to, item)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh); vh.itemView.elevation = 0f
            }
            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(vh, state)
                if (state == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.elevation = 8f
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)

        customAlertDialog().apply {
            setTitle(getString(R.string.home_layout_show))
            setCustomView(recyclerView)
            setPosButton(R.string.ok) {
                PrefManager.setVal(PrefName.HomeLayout, currentVisibility)
                PrefManager.setVal(PrefName.HomeLayoutOrder, displayList.drop(1))
                restartApp()
            }
            setNegButton(R.string.cancel, null)
            show()
        }
    }

    override fun onResume() {
        ThemeManager(this).applyTheme()
        super.onResume()
    }

    inner class HomeLayoutAdapter(
        private val displayList: MutableList<Int>,
        private val views: Array<String>,
        private val currentVisibility: MutableList<Boolean>,
    ) : RecyclerView.Adapter<HomeLayoutAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dragHandle: ImageView = view.findViewById(R.id.itemHomeLayoutDragHandle)
            val title: TextView = view.findViewById(R.id.itemHomeLayoutTitle)
            val switch: MaterialSwitch = view.findViewById(R.id.itemHomeLayoutSwitch)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_home_layout, parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val idx = displayList[position]
            holder.title.text = views[idx]
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = currentVisibility[idx]
            holder.switch.setOnCheckedChangeListener { _, isChecked -> currentVisibility[idx] = isChecked }
            holder.dragHandle.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        }
        override fun getItemCount() = displayList.size
    }
}
