package ani.sanin.ui.splash

import ani.sanin.R
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay

/*
 * ==========================================================
 * SHARED SANIN SPLASH
 * ==========================================================
 *
 * One shared particle materialization engine drives both
 * orientations. Each orientation only supplies its own
 * configuration (assets + logo geometry):
 *
 *   Shared ParticleMaterializationEngine
 *         |
 *         ├── Landscape configuration
 *         |
 *         └── Portrait configuration
 *
 * The engine handles alpha-pixel sampling, particle creation
 * with far-away curved Bezier trajectories, drawing, and the
 * synchronized logo shine. It never animates logo geometry.
 */

internal data class SaninSplashGeometry(
    val wordmarkOffsetY: Dp,
    val emblemOffsetY: Dp,
    val emblemWidth: Dp,
    val emblemHeight: Dp
)

internal sealed interface SaninSplashConfig {
    val backgroundRes: Int

    /**
     * Optional. The splash now shows the logo alone: the wordmark art was removed along
     * with the illustrated backgrounds, so there is no lettering to draw. Kept as a
     * nullable property rather than deleted so a wordmark can be reintroduced without
     * restructuring the four call sites that read it.
     */
    val wordmarkRes: Int?
    val emblemRes: Int
    val particleCount: Int
}

/*
 * Fixed geometry - used by landscape so its exact current
 * layout is preserved.
 */
internal data class FixedSaninSplashConfig(
    override val backgroundRes: Int,
    override val wordmarkRes: Int?,
    override val emblemRes: Int,
    val geometry: SaninSplashGeometry,
    override val particleCount: Int = 220
) : SaninSplashConfig

/*
 * Canvas-relative geometry - used by portrait. Logo centers
 * are fractions of the portrait canvas height and the emblem
 * particle field is sized from the emblem's actual pixels, so
 * particles always land exactly on the drawn logo.
 */
internal data class CanvasSaninSplashConfig(
    override val backgroundRes: Int,
    override val wordmarkRes: Int?,
    override val emblemRes: Int,
    val wordmarkCenterY: Float,
    val emblemCenterY: Float,
    override val particleCount: Int = 220
) : SaninSplashConfig

/*
 * Landscape configuration - identical to the previous
 * dedicated landscape splash.
 */
private val LandscapeConfig = CanvasSaninSplashConfig(
    backgroundRes = R.drawable.sanin_splash_background,
    wordmarkRes = null,
    emblemRes = R.drawable.sanin_emblem,
    // Nothing else is drawn now, so the mark sits dead centre. It used to be 0.68 to
    // leave room for a wordmark above it, which has since been removed.
    wordmarkCenterY = 0.36f,
    emblemCenterY = 0.5f
)

/*
 * Portrait configuration - uses the new portrait assets.
 */
private val PortraitConfig = CanvasSaninSplashConfig(
    backgroundRes = R.drawable.sanin_splash_background_portrait,
    wordmarkRes = null,
    emblemRes = R.drawable.sanin_emblem_portrait,
    wordmarkCenterY = 0.42f,
    emblemCenterY = 0.5f
)

@Composable
fun SaninLandscapeSplash(
    onFinished: () -> Unit
) {
    SaninSplash(
        config = LandscapeConfig,
        onFinished = onFinished
    )
}

@Composable
fun SaninPortraitSplash(
    onFinished: () -> Unit
) {
    SaninSplash(
        config = PortraitConfig,
        onFinished = onFinished
    )
}


/*
 * ==========================================================
 * SHARED MATERIALIZATION ENGINE
 * ==========================================================
 */

@Composable
internal fun SaninSplash(
    config: SaninSplashConfig,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Rasterised rather than decodeResource'd: the emblem is a vector drawable, and
    // BitmapFactory.decodeResource only understands raster resources, so it returned null
    // for the vector and the splash crashed on emblemBitmap.width. Going through the
    // Drawable handles both, so swapping the emblem back to a PNG keeps working.
    val emblemBitmap = remember {
        context.resources.getDrawable(config.emblemRes, context.theme)?.toBitmap()
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    // The splash is now just the logo on black, held for a moment and handed on. It used
    // to run a 2400ms timeline: the background faded up, 220 particles sampled from the
    // emblem's own pixels flew in to form it, and a light sweep crossed it. None of that
    // was asked for, and with the emblem art replaced by the logo it read as the logo
    // assembling itself out of noise rather than simply appearing.
    //
    // A short hold rather than an instant hand-off, so the logo is actually visible: the
    // system splash has only just faded out over this, and cutting straight to content
    // flashes black. 500ms is long enough to register and short enough not to feel slow.
    LaunchedEffect(Unit) {
        delay(500)
        onFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        /*
         * Resolve the orientation-specific logo geometry.
         */
        val geometry = when (config) {
            is FixedSaninSplashConfig -> config.geometry
            is CanvasSaninSplashConfig -> with(density) {
                SaninSplashGeometry(
                    wordmarkOffsetY = maxHeight * (config.wordmarkCenterY - 0.5f),
                    emblemOffsetY = maxHeight * (config.emblemCenterY - 0.5f),
                    emblemWidth = Dp(emblemBitmap.width / density.density),
                    emblemHeight = Dp(emblemBitmap.height / density.density)
                )
            }
        }

        /*
         * Black backdrop, filled rather than ContentScale.None so it always reaches the
         * edges on any aspect ratio.
         */
        Image(
            painter = painterResource(
                config.backgroundRes
            ),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        /*
         * The logo itself. Static: no alpha ramp, no offset, no particle pass and no shine.
         */
        Image(
            painter = painterResource(
                config.emblemRes
            ),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = geometry.emblemOffsetY),
            contentScale = ContentScale.None
        )
    }
}


/*
 * ==========================================================
 * UTILITY
 * ==========================================================
 */

/**
 * Rasterises a drawable at its intrinsic size.
 *
 * VectorDrawable has no pixels of its own, and this reads the emblem's width/height to
 * derive its on-screen size, so it needs a real Bitmap. Drawn at the intrinsic size so
 * emblemWidth/emblemHeight stay in step with what the Drawable reports.
 */
private fun Drawable.toBitmap(): Bitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: 1
    val h = intrinsicHeight.takeIf { it > 0 } ?: 1
    if (this is BitmapDrawable) {
        bitmap?.let { return it }
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bmp
}
