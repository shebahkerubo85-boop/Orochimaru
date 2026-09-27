package ani.sanin.cloudstream

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * A provider logo sampled for its baked-in background colour.
 *
 * @param start left (or top, when [vertical]) background colour
 * @param end right (or bottom) background colour
 * @param vertical true when the logo grades top-to-bottom rather than left-to-right
 * @param contentScale how much to enlarge the mark so it reads the same size on every card
 */
data class StreamingTint(
    val start: Int,
    val end: Int,
    val vertical: Boolean,
    val contentScale: Float
) {
    val isFlat: Boolean get() = start == end
}

/**
 * Reads the background a TMDB provider logo carries, sampled from its own edges.
 *
 * Every TMDB provider logo is a 332x332 image with the brand's background baked in and
 * effectively no transparent border — Netflix is near-black, Prime Video white, Crunchyroll
 * orange. Reading those pixels lets a WIDE card be filled with the same colour, so the
 * square logo drawn on top blends in with no inner edge. Guessing it (a blur, a tint, a
 * hand-written table) always leaves that edge — the "box inside a box".
 *
 * Sampled from all FOUR edge midpoints because these logos grade in different directions.
 * Reading only one axis paints a flat card against a graded logo and the square's edges
 * show. [StreamingTint.vertical] records which axis won so the card's gradient runs the
 * same way. A flat logo yields the same colour on every edge and the gradient is flat,
 * which is correct for Netflix, Prime Video and Crunchyroll.
 *
 * Results are cached per logo url for the session: a logo does not change under us and the
 * rail rebuilds on every scroll, so a service seen once paints correctly on its FIRST frame
 * instead of flashing the neutral plate.
 */
object StreamingLogoTint {
    private val cache = HashMap<String, StreamingTint?>()
    private val inFlight = HashSet<String>()

    /** Already-sampled tint, or null if this logo has not been read yet. */
    fun cached(url: String): StreamingTint? = synchronized(cache) { cache[url] }

    fun isKnown(url: String): Boolean = synchronized(cache) { cache.containsKey(url) }

    /**
     * Claims the right to read [url]. Returns false when another bind already has it in
     * flight or it is already cached, so concurrent binds do not download the same logo
     * twice.
     */
    fun beginLoad(url: String): Boolean = synchronized(cache) {
        if (inFlight.contains(url) || cache.containsKey(url)) return false
        inFlight.add(url)
        true
    }

    fun put(url: String, tint: StreamingTint?) = synchronized(cache) {
        cache[url] = tint
        inFlight.remove(url)
    }

    /** Releases an in-flight claim after a failed load so a later bind can retry. */
    fun failLoad(url: String) = synchronized(cache) { inFlight.remove(url) }

    /** Not the very edge (some assets antialias their outer row) and not a corner. */
    private const val INSET = 6

    /** Target share of the card for a logo's mark. */
    private const val TARGET_FILL = 0.80f

    /**
     * Never shrink, and never blow a mark up so far that a busy logo turns to mush. 1.35
     * covers the widest real gap (Crunchyroll's mark fills ~60% of its square).
     */
    private const val MAX_SCALE = 1.35f

    /** Samples [bmp] for a brand tint, or null when its edges are transparent. */
    fun sample(bmp: Bitmap): StreamingTint? {
        val w = bmp.width
        val h = bmp.height
        if (w <= INSET * 2 || h <= INSET * 2) return null
        fun at(x: Int, y: Int): Int? {
            if (x < 0 || y < 0 || x >= w || y >= h) return null
            val c = bmp.getPixel(x, y)
            // A transparent edge means the logo has no background of its own, so there is
            // nothing to match and the neutral plate is the honest fallback.
            if (Color.alpha(c) < 250) return null
            return c
        }
        val left = at(INSET, h / 2) ?: return null
        val right = at(w - 1 - INSET, h / 2) ?: return null
        val top = at(w / 2, INSET) ?: return null
        val bottom = at(w / 2, h - 1 - INSET) ?: return null
        // Whichever axis varies more is the one the logo actually grades along.
        val vertical = delta(top, bottom) > delta(left, right)
        val scale = contentScale(bmp, left)
        return if (vertical) {
            StreamingTint(top, bottom, vertical = true, contentScale = scale)
        } else {
            StreamingTint(left, right, vertical = false, contentScale = scale)
        }
    }

    /**
     * Measures the mark's bounding box against [background] and returns the scale that
     * brings it to [TARGET_FILL]. A graded background differs from the sampled edge colour
     * everywhere, so its box is the whole square and the scale comes out 1.0 — the right
     * answer for a logo that already fills its frame.
     */
    private fun contentScale(bmp: Bitmap, background: Int): Float {
        val w = bmp.width
        val h = bmp.height
        val br = Color.red(background)
        val bg = Color.green(background)
        val bb = Color.blue(background)
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        // Every second pixel: this runs once per logo per session and a 1px boundary
        // error moves the scale by well under a percent.
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                if (Color.alpha(c) >= 250) {
                    val d = abs(Color.red(c) - br) + abs(Color.green(c) - bg) + abs(Color.blue(c) - bb)
                    if (d > 60) {
                        if (x < x0) x0 = x
                        if (x > x1) x1 = x
                        if (y < y0) y0 = y
                        if (y > y1) y1 = y
                    }
                }
                x += 2
            }
            y += 2
        }
        if (x1 <= x0 || y1 <= y0) return 1f
        val side = if (w > h) w else h
        val mark = if ((x1 - x0) > (y1 - y0)) (x1 - x0) else (y1 - y0)
        val fill = mark.toFloat() / side
        if (fill <= 0f) return 1f
        return (TARGET_FILL / fill).coerceIn(1f, MAX_SCALE)
    }

    /** Rough channel distance — only ever compared against another delta. */
    private fun delta(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))
}
