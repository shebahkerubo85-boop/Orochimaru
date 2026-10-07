package ani.sanin.util

import android.content.res.TypedArray
import android.graphics.Color
import android.widget.ImageButton
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object FocusEffectUtil {

    private var lastFocusedView: View? = null
    private val savedForegrounds = mutableMapOf<View, Drawable?>()
    private val savedBackgrounds = mutableMapOf<View, Drawable?>()

    fun applyFocusListener(vararg views: View, fade: Boolean = false, borderDp: Float = 3f, borderColor: Int? = null) {
        for (view in views) {
            removeBorder(view)
            view.onFocusChangeListener = null
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    if (lastFocusedView != v) {
                        resetView(lastFocusedView)
                        lastFocusedView = v
                    }
                    applyBorder(v, v is ImageButton, borderDp, borderColor)
                    applyFocusGain(v)
                    if (fade) {
                        if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                            v.animate().alpha(1f).setDuration(200).start()
                        } else {
                            v.alpha = 1f
                        }
                    }
                } else {
                    removeBorder(v)
                    if (fade) {
                        if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                            v.animate().alpha(0.85f).rotationY(0f).setDuration(200).start()
                        } else {
                            v.alpha = 0.85f
                            v.rotationY = 0f
                        }
                    } else {
                        applyFocusLoss(v)
                    }
                }
            }
            if (view.isFocused) {
                resetView(lastFocusedView)
                lastFocusedView = view
                applyBorder(view, view is ImageButton, borderDp)
                applyFocusGain(view)
            }
        }
    }

    fun applyFocusListener(focusView: View, borderTarget: View, isCircular: Boolean = false, fade: Boolean = false, borderDp: Float = 3f) {
        removeBorder(borderTarget)
        focusView.onFocusChangeListener = null
        focusView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                if (lastFocusedView != v) {
                    resetView(lastFocusedView)
                    lastFocusedView = v
                }
                applyBorder(borderTarget, isCircular, borderDp)
                applyFocusGain(borderTarget)
                if (fade) {
                    if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                        v.animate().alpha(1f).setDuration(200).start()
                    } else {
                        v.alpha = 1f
                    }
                }
            } else {
                removeBorder(borderTarget)
                if (fade) {
                    if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                        v.animate().alpha(0.85f).rotationY(0f).setDuration(200).start()
                    } else {
                        v.alpha = 0.85f
                        v.rotationY = 0f
                    }
                } else {
                    applyFocusLoss(borderTarget)
                }
            }
        }
    }

    /** Replays the same spin as dpad focus for touch taps (buttons beside search bars). */
    fun spinOnTouch(view: View) {
        if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
            view.animate().cancel()
            view.animate().rotationYBy(360f).setDuration(400).start()
        }
    }

    private fun resetView(v: View?) {
        if (v == null) return
        removeBorder(v)
        // Keep the light-mode lens shadow: LensButtonBackground puts a small elevation on
        // those round buttons and zeroing it here would make it vanish after the first
        // time focus moves away.
        if (!isLensButton(v)) v.elevation = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationX = 0f
        v.translationY = 0f
        v.rotationY = 0f
        v.alpha = 1f
    }

    fun getPrimaryColor(context: android.content.Context): Int {
        val ta: TypedArray = context.theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorPrimary))
        val color = ta.getColor(0, Color.WHITE)
        ta.recycle()
        return color
    }

    private fun getPrimaryColor(v: View): Int {
        val ta: TypedArray = v.context.theme.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorPrimary))
        val color = ta.getColor(0, Color.WHITE)
        ta.recycle()
        return color
    }

    private fun applyBorder(v: View, isCircular: Boolean = false, borderDp: Float = 3f, borderColor: Int? = null) {
        val primaryColor = borderColor ?: getPrimaryColor(v)
        val borderWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, borderDp, v.resources.displayMetrics
        ).toInt()
        var cardRadius = if (v is androidx.cardview.widget.CardView) v.radius else 0f
        if (cardRadius == 0f && v is com.google.android.material.chip.Chip) {
            cardRadius = v.chipCornerRadius
        }
        if (cardRadius == 0f) {
            var parent = v.parent
            while (parent is View) {
                if (parent is androidx.cardview.widget.CardView) {
                    cardRadius = parent.radius
                    break
                }
                parent = (parent as? View)?.parent
            }
        }
        val defaultRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, v.resources.displayMetrics
        ).toInt()
        val cornerRadius = if (cardRadius > 0f) cardRadius.toInt() else defaultRadius

        val circular = isCircular || isCircularAvatar(v)
        val borderDrawable = GradientDrawable().apply {
            setShape(if (circular) GradientDrawable.OVAL else GradientDrawable.RECTANGLE)
            setColor(Color.TRANSPARENT)
            setStroke(borderWidthPx, primaryColor)
            if (!circular) setCornerRadius(cornerRadius.toFloat())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            savedForegrounds[v] = v.foreground
            v.foreground = borderDrawable
        } else {
            if (v is androidx.cardview.widget.CardView) return
            val originalBg = v.background
            savedBackgrounds[v] = originalBg
            val layers = arrayOf(
                originalBg ?: GradientDrawable().apply { setColor(Color.TRANSPARENT) },
                borderDrawable
            )
            v.setBackgroundDrawable(LayerDrawable(layers))
        }
    }

    private fun removeBorder(v: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            v.foreground = savedForegrounds.remove(v) ?: savedForegrounds.remove(v)
        } else {
            if (v is androidx.cardview.widget.CardView) return
            val original = savedBackgrounds.remove(v)
            if (original != null) {
                v.setBackgroundDrawable(original)
            } else {
                v.background = null
            }
        }
    }

    private fun shouldSpin(v: View): Boolean {
        if (v is ImageButton) return true
        val id = v.id
        return id == R.id.mainCalendarContainer ||
                id == R.id.mainUserAvatarContainer ||
                id == R.id.discoverAvatar ||
                id == R.id.tmdbDiscoveryAvatar ||
                id == R.id.listAvatar ||
                id == R.id.tmdbLibAvatar ||
                id == R.id.sheetMoviePluginArrow ||
                id == R.id.franchiseRowInfo ||
                id == R.id.exo_tracks
    }

    private fun isCircularAvatar(v: View): Boolean {
        val id = v.id
        return id == R.id.discoverAvatar || id == R.id.tmdbDiscoveryAvatar ||
            id == R.id.listAvatar || id == R.id.tmdbLibAvatar ||
            id == R.id.openSettingsButton || id == R.id.searchIconButton ||
            id == R.id.listSettings || id == R.id.sectionMoreButton ||
            id == R.id.searchFilter || id == R.id.clearHistory ||
            id == R.id.searchAdultCheck || id == R.id.mainCalendarContainer ||
            id == R.id.searchResultGrid || id == R.id.searchResultList ||
            id == R.id.franchiseRowInfo ||
            id == R.id.tmdbSearchFilter || id == R.id.tmdbSearchClearHistory ||
            id == R.id.tmdbSearchResultGrid || id == R.id.tmdbSearchResultList
    }

    private val lensButtonIds = setOf(
        R.id.openSettingsButton, R.id.searchIconButton,
        R.id.listSettings, R.id.sectionMoreButton,
        R.id.searchFilter, R.id.clearHistory, R.id.searchAdultCheck,
        R.id.searchResultGrid, R.id.searchResultList,
        R.id.mainCalendarContainer, R.id.streamingServiceSeeAll,
        R.id.franchiseRowInfo,
        R.id.tmdbSearchFilter, R.id.tmdbSearchClearHistory,
        R.id.tmdbSearchResultGrid, R.id.tmdbSearchResultList
    )

    private fun isLensButton(v: View): Boolean = v.id in lensButtonIds

    /** Re-applies the light-mode lens shadow; no-op in dark mode. */
    private fun applyLensElevation(v: View) {
        if (!isLensButton(v)) return
        val dark = (v.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        v.elevation = if (dark) 0f else 3f * v.resources.displayMetrics.density
    }

    private fun applyFocusGain(v: View) {
        applyLensElevation(v)
        if (shouldSpin(v)) {
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
                v.animate().rotationYBy(360f).setDuration(400).start()
            } else {
                v.rotationY = 0f
            }
        }
    }

    private fun applyFocusLoss(v: View) {
        if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.FocusAnimations)) {
            v.animate().cancel()
            v.animate().rotationY(0f).setDuration(200).start()
        } else {
            v.rotationY = 0f
        }
    }
}
