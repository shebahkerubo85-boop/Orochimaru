package ani.sanin.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import ani.sanin.BANNER_TYPE_CLASSIC
import ani.sanin.BANNER_TYPE_COMPACT
import ani.sanin.BANNER_TYPE_MODERN
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.request.RequestOptions
import jp.wasabeef.glide.transformations.internal.FastBlur
import java.security.MessageDigest

/**
 * Stack blur for banner art, at a fraction of the source resolution.
 *
 * The stock BlurTransformation tries RenderScript first and falls back to the
 * same FastBlur we call here, so on any device where RenderScript is unavailable
 * it pays for a RenderScript.create() that throws plus a releaseAllContexts()
 * teardown, then blurs anyway. RenderScript has been deprecated since API 26, so
 * that is the common path rather than the exception. Going straight to FastBlur
 * gives the same result for a fraction of the setup cost, which matters when a
 * low-RAM TV is decoding a screenful of banners.
 *
 * The bitmap is scaled down by [sampling] before blurring, so the work is
 * divided by sampling squared. A blurred image hides the lost resolution, which
 * is why this is a free trade rather than a quality setting.
 *
 * Note this reaches into the library's `internal` package. It is public API of a
 * pinned dependency (glide-transformations 4.3.0), but a bump to that artifact
 * could move it.
 */
class StackBlurTransformation(
    private val radius: Int,
    private val sampling: Int = 2,
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val divisor = sampling.coerceAtLeast(1)
        val scaled = if (divisor == 1) {
            toTransform
        } else {
            Bitmap.createScaledBitmap(
                toTransform,
                (toTransform.width / divisor).coerceAtLeast(1),
                (toTransform.height / divisor).coerceAtLeast(1),
                true,
            )
        }
        return FastBlur.blur(scaled, radius.coerceIn(1, 25), true)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        // The radius is part of the key, so every position of the blur slider is
        // a separate cached bitmap. Keeping the slider coarse limits how many
        // of those a drag can produce.
        messageDigest.update("$ID$radius$sampling".toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val ID = "ani.sanin.util.StackBlurTransformation."
    }
}

/** Blur down-sample divisor, shared by every banner blur. */
const val BANNER_BLUR_SAMPLING = 2

/**
 * Hard cap on the width of any carousel banner bitmap. A Modern banner is
 * full-bleed, so without a cap it decodes whatever the source happens to be,
 * often 1920px, for every item in the carousel.
 */
const val BANNER_MAX_WIDTH = 720

/**
 * How much of the Classic banner's height gets blurred. The bottom band is where
 * the chips and watch pill sit, so that is the part worth softening; the art
 * above it stays sharp.
 */
const val CLASSIC_BLUR_BAND = 0.25f

/**
 * Blurs only the bottom band of the image, feathering the top edge of the band
 * into the sharp art above it. Used by the Large banner, where a full-image blur
 * would throw away the artwork the banner exists to show.
 */
class BottomBlurTransformation(
    private val radius: Int,
    private val fraction: Float = CLASSIC_BLUR_BAND,
    private val sampling: Int = BANNER_BLUR_SAMPLING,
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val width = toTransform.width
        val height = toTransform.height
        val bandHeight = (height * fraction.coerceIn(0.05f, 1f)).toInt().coerceIn(1, height)
        val bandTop = height - bandHeight

        val band = Bitmap.createBitmap(toTransform, 0, bandTop, width, bandHeight)
        val divisor = sampling.coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(
            band,
            (width / divisor).coerceAtLeast(1),
            (bandHeight / divisor).coerceAtLeast(1),
            true,
        )
        val blurred = FastBlur.blur(small, radius.coerceIn(1, 25), true)
        val bandBlurred = Bitmap.createScaledBitmap(blurred, width, bandHeight, true)

        // Draw the band back in strips so its top edge fades out instead of
        // showing a hard seam against the sharp art above it.
        val canvas = Canvas(toTransform)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val feather = (bandHeight * FEATHER_FRACTION).toInt().coerceAtLeast(1)
        val strips = FEATHER_STRIPS
        val stripHeight = (feather / strips).coerceAtLeast(1)
        for (i in 0 until strips) {
            val from = i * stripHeight
            val to = if (i == strips - 1) feather else (i + 1) * stripHeight
            paint.alpha = (255f * (to.toFloat() / feather)).toInt().coerceIn(0, 255)
            canvas.drawBitmap(
                bandBlurred,
                Rect(0, from, width, to),
                RectF(0f, (bandTop + from).toFloat(), width.toFloat(), (bandTop + to).toFloat()),
                paint,
            )
        }
        paint.alpha = 255
        val solidFrom = feather.coerceAtMost(bandHeight)
        canvas.drawBitmap(
            bandBlurred,
            Rect(0, solidFrom, width, bandHeight),
            RectF(0f, (bandTop + solidFrom).toFloat(), width.toFloat(), height.toFloat()),
            paint,
        )
        return toTransform
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID$radius$fraction$sampling".toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val ID = "ani.sanin.util.BottomBlurTransformation."
        const val FEATHER_STRIPS = 8
        const val FEATHER_FRACTION = 0.45f
    }
}

/**
 * Load options for a carousel banner.
 *
 * Every banner is capped to a sane width, because a carousel will happily decode
 * a full-resolution backdrop per item and that is a lot of bitmap for a 1GB TV.
 * The cap is what keeps a screenful of banners affordable.
 *
 * On top of that, the blur is applied only where it earns its keep: Large blurs
 * just its bottom band, Modern blurs the dimmed backdrop it is built around, and
 * Compact is left sharp.
 */
fun bannerLoadOptions(bannerType: Int): RequestOptions<Drawable> {
    val options = RequestOptions<Drawable>().override(BANNER_MAX_WIDTH)
    if (!PrefManager.getVal<Boolean>(PrefName.BlurBanners)) return options
    val radius = PrefManager.getVal<Float>(PrefName.BlurStrength).toInt()
    if (radius <= 0) return options
    val r = radius.coerceIn(1, 25)
    return when (bannerType) {
        BANNER_TYPE_CLASSIC -> options.transform(BottomBlurTransformation(r))
        BANNER_TYPE_MODERN -> options.transform(StackBlurTransformation(r, BANNER_BLUR_SAMPLING))
        else -> options
    }
}

/** Which of the three banner presentations the carousel is currently showing. */
fun currentBannerType(modern: Boolean, large: Boolean): Int = when {
    modern -> BANNER_TYPE_MODERN
    large -> BANNER_TYPE_CLASSIC
    else -> BANNER_TYPE_COMPACT
}

/**
 * Blur for unwatched episode stills, where the point is to hide spoilers without
 * hiding the shot. This was 15 of a possible 25, which turned every unwatched
 * episode into an unreadable smear; 5 still obscures detail but you can make out
 * the frame. Fixed rather than user-adjustable: it lives with the spoiler toggle
 * in the Anime settings, and the banner blur slider is a different control.
 */
const val EPISODE_BLUR_RADIUS = 5

/** Episode stills are small list thumbnails, so they can afford a heavier down-sample. */
const val EPISODE_BLUR_SAMPLING = 3
