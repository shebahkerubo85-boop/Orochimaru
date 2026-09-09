package ani.sanin.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsThemeBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.restartApp
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog

class SettingsThemeActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsThemeBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apply {
            settingsThemeLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            onBackPressedDispatcher.addCallback(context) {
                finish()
            }
            themeSettingsBack.isFocusable = true
            themeSettingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
            FocusEffectUtil.applyFocusListener(themeSettingsBack)

            val themeModes = arrayOf("Light", "Dark")
            val accentColors = arrayOf(
                0 to "Sanin", 1 to "Ocean", 2 to "Blood", 3 to "Lime",
                4 to "Sun", 5 to "Kurama", 6 to "Saikou", 7 to "Indigo",
                8 to "Monochrome"
            )

            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = 1,
                        name = "Theme Mode",
                        desc = "Light or Dark mode",
                        icon = R.drawable.ic_round_brightness_medium_24,
                        onClick = {
                            customAlertDialog().apply {
                                setTitle("Theme Mode")
                                singleChoiceItems(themeModes, PrefManager.getVal<Int>(PrefName.DarkMode)) { index ->
                                    PrefManager.setVal(PrefName.DarkMode, index)
                                    AppCompatDelegate.setDefaultNightMode(
                                        if (index == 1) AppCompatDelegate.MODE_NIGHT_YES
                                        else AppCompatDelegate.MODE_NIGHT_NO
                                    )
                                    recreate()
                                }
                                show()
                            }
                        },
                    ),
                    Settings(
                        type = 1,
                        name = "Accent Color",
                        desc = "Choose your app accent color",
                        icon = R.drawable.ic_palette,
                        onClick = {
                            customAlertDialog().apply {
                                setTitle("Accent Color")
                                val labels = accentColors.map { it.second }.toTypedArray()
                                singleChoiceItems(labels, PrefManager.getVal<Int>(PrefName.AccentColor)) { index ->
                                    PrefManager.setVal(PrefName.AccentColor, accentColors[index].first)
                                    restartApp()
                                }
                                show()
                            }
                        },
                    ),
                    Settings(
                        type = 1,
                        name = "OLED Mode",
                        desc = "OLED background effects",
                        icon = R.drawable.ic_round_brightness_4_24,
                        onClick = {
                            customAlertDialog().apply {
                                setTitle("OLED Background Mode")
                                val labels = arrayOf(
                                    "Off\nNormal theme background",
                                    "Pure AMOLED\nPure black background",
                                    "Glow Spots\nBlack + radial glow orbs",
                                    "Gradient\nBlack + primary color gradient",
                                    "Vignette\nColored vignette from edges"
                                )
                                singleChoiceItems(labels, PrefManager.getVal<Int>(PrefName.OledMode)) { index ->
                                    PrefManager.setVal(PrefName.OledMode, index)
                                    restartApp()
                                }
                                show()
                            }
                        },
                    ),
                )
            )
            settingsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }
        }
    }


}
