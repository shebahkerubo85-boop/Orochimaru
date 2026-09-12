package ani.sanin.profile.notification

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import ani.sanin.getThemeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Dominant-color extraction + the three-layer horizontal gradient used on the
 * episode notification card:
 *
 *   [thumbnail] -> transparent -> dominant color -> amoled/white (by theme)
 *
 * Colour extraction is cached per URL and always degrades to the primary color
 * (or a plain gradient) instead of crashing.
 */
object EpisodeCardGradient {
    private const val MAX_CACHE = 96

    private val dominantCache = object : LinkedHashMap<String, Int>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > MAX_CACHE
    }

    /** Best-effort dominant colour for [url]; null when the image cannot load. */
    suspend fun dominantColor(url: String?): Int? {
        if (url.isNullOrBlank()) return null
        synchronized(dominantCache) {
            dominantCache[url]?.let { return it }
        }
        val color = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val bytes = ani.sanin.client.newCall(request).execute().use { it.body?.bytes() }
                    ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = maxOf(
                        1,
                        minOf(bounds.outWidth / 96, bounds.outHeight / 96).coerceAtLeast(1)
                    )
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: return@withContext null
                val palette = Palette.from(bitmap).generate()
                val swatch = palette.dominantSwatch ?: palette.vibrantSwatch ?: palette.mutedSwatch
                swatch?.rgb ?: try {
                    bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                } catch (_: Exception) {
                    null
                }
            } catch (e: Exception) {
                Log.w("EpisodeCardGradient", "color extraction failed: ${e.message}")
                null
            }
        } ?: return null
        synchronized(dominantCache) { dominantCache[url] = color }
        return color
    }

    /**
     * Three-layer horizontal gradient.
     * [dominant] is the extracted thumbnail colour; the primary theme colour is
     * used as fallback. The right edge is amoled black in dark mode / white in
     * light mode.
     */
    fun build(context: Context, dominant: Int?): GradientDrawable {
        val primary = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val mid = dominant ?: primary
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val right = if (isDark) Color.BLACK else Color.WHITE
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                ColorUtils.setAlphaComponent(mid, 0),
                ColorUtils.setAlphaComponent(mid, 210),
                right
            )
        ).apply { positions = floatArrayOf(0f, 0.55f, 1f) }
    }
}
