package ani.sanin.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR

/**
 * The flowing line that runs from each franchise card to the next.
 *
 * ## Why it is drawn rather than composed
 *
 * The line curves between two rows that are only known once they have been measured and laid
 * out, so it cannot be a drawable on either of them. Drawing it here, behind the rows, is what
 * lets the cards paint over its ends, so the line appears to grow out of one card and arrive at
 * the next rather than stopping short of them or starting on top of them.
 *
 * ## Why it crosses the gap and not the text
 *
 * Each segment spans only the clear band between one card column's foot and the next one's head,
 * and that band is the whole of its run. It never crosses a synopsis, which is what means the
 * synopsis needs no background to hide a line passing behind it. The earlier arrangement drew the
 * line down the whole height of the row and masked it against the page colour where the words
 * were; that put a block of page colour behind every paragraph and read, on screen, as the line
 * ending at the synopsis rather than at the next card. Routing through the gap removes both the
 * block and the ambiguity.
 *
 * Routing between actual glyphs was the alternative and was rejected: TextView exposes no glyph
 * positions, so it would mean measuring every run via `Layout.getLineForOffset`, and that
 * breaks the moment the text reflows, the font scale changes or the string is translated.
 *
 * ## How the curve is built
 *
 * Each segment is a cubic that leaves the previous card's edge horizontally, arcs through the
 * gap, and arrives at the next card's edge horizontally. Because the two cards alternate sides,
 * the arc leans one way going down and the other coming back, which is what produces the
 * zigzag rather than a single sweep. The lean is a share of the horizontal distance travelled,
 * so the zigzag survives a full-width crossing and a narrow one alike.
 */
class FranchiseConnectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /**
     * One card's position in the flow, in this view's coordinates.
     *
     * Recorded after layout rather than passed in, since a row's real position depends on the
     * measured heights of every row above it.
     */
    private data class Node(
        val centerX: Float,
        val top: Float,
        val bottom: Float,
    )

    private val nodes = ArrayList<Node>()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()

    /**
     * Thickness in dp.
     *
     * Thicker than it was, because the line is the only thing that says these rows are one
     * sequence rather than a list, and at 1.5dp over a poster it was easy to lose against a
     * busy frame. Still not a pipe: it crosses artwork, and the thicker it gets the more it
     * competes with the picture it is supposed to be tying together.
     */
    private val strokeWidth = 2.5f.dp()

    /**
     * How far the curve reaches horizontally beyond the card edges.
     *
     * This is what gives the line its lean. Too small and the zigzag flattens into a straight
     * vertical run; too large and it leaves the row's own padding and collides with the card
     * beside it. [drawSegment] caps it at half the horizontal distance travelled, so this is
     * the ceiling rather than the value whenever the cards are closer together than 56dp apart.
     */
    private val sway = 28f.dp()

    init {
        // Not clickable and not focusable: this is decoration that happens to sit under rows the
        // user can focus, and a focusable overlay would swallow dpad events aimed at the cards.
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * The theme's primary colour, for the line to follow.
     *
     * Read at draw time rather than once in the constructor: the same view is reused across
     * theme changes, and a colour captured at inflate would keep drawing the old theme's accent.
     */
    private fun resolveColor(): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(MaterialR.attr.colorPrimary, typedValue, true)
        val base = when {
            typedValue.resourceId != 0 -> ContextCompat.getColor(context, typedValue.resourceId)
            else -> typedValue.data
        }
        return Color.argb(150, Color.red(base), Color.green(base), Color.blue(base))
    }

    /**
     * Records one card's laid-out position.
     *
     * Filtered-out rows are not nodes at all: the caller omits them, so the line only ever joins
     * cards that are on screen.
     */
    fun addNode(centerX: Float, top: Float, bottom: Float) {
        nodes += Node(centerX, top, bottom)
    }

    /** Clears the recorded positions, for a re-filter that rebuilds the rows. */
    fun resetNodes() {
        nodes.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.size < 2) return

        val color = resolveColor()
        stroke.strokeWidth = strokeWidth
        arrow.color = color

        for (i in 0 until nodes.size - 1) {
            drawSegment(canvas, nodes[i], nodes[i + 1], color)
        }
    }

    /**
     * Draws one cubic from the bottom of [from] to the top of [to].
     *
     * Leaves and arrives horizontally so the line meets each card square-on, then leans toward
     * whichever side the *next* card is on. That single asymmetry is what makes consecutive
     * segments mirror each other.
     */
    private fun drawSegment(canvas: Canvas, from: Node, to: Node, color: Int) {
        val startY = from.bottom
        val endY = to.top
        val span = endY - startY
        // A zero or negative span means two cards overlap, which can only happen transiently
        // before layout settles. Drawing nothing is better than a loop.
        if (span <= 1f) return

        val startX = from.centerX
        val endX = to.centerX

        // The lean is capped at a share of the distance the line actually has to travel. A fixed
        // 28dp was tuned when the line ran the whole height of the row, where it was a small bow
        // on a long curve; now that the line crosses only the gap between two cards, the same fixed
        // lean is a big sideways throw relative to the run, and it drags the curve out past the
        // card edge it is supposed to be pointing at. Half the travel keeps the arc inside the
        // space it has.
        val reach = kotlin.math.abs(endX - startX)
        val limit = reach / 2f
        val lean = (if (endX >= startX) sway else -sway).coerceIn(-limit, limit)

        path.reset()
        path.moveTo(startX, startY)
        // First control point pushes out horizontally from the source card.
        path.cubicTo(
            startX + lean,
            startY + span * 0.35f,
            endX - lean,
            endY - span * 0.35f,
            endX,
            endY
        )

        stroke.color = color
        canvas.drawPath(path, stroke)

        // Every segment ends at a card the user can tap, so every segment gets a head: the
        // chevron is what says the order runs downward rather than the line merely existing.
        drawArrowHead(canvas, endX, endY, color, lean)
    }

    /**
     * A small chevron at the head of a segment.
     *
     * The tip sits on the next card's head and both barbs trail back up into the gap behind it,
     * so the whole head is in the clear band and none of it is swallowed by the card it points
     * at. It is rotated to the tangent of the curve rather than drawn upright, so it reads as
     * continuing the flow instead of as a separate marker sitting on top of the card.
     */
    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, color: Int, lean: Float) {
        val size = strokeWidth * 4f
        val angle = Math.toRadians(if (lean >= 0) 55.0 else 125.0).toFloat()

        path.reset()
        // Two barbs swept back from the tip, rotated by the tangent angle.
        val l = size
        val w = size * 0.6f
        val x1 = x - (l * kotlin.math.cos(angle) - w * kotlin.math.sin(angle))
        val y1 = y - (l * kotlin.math.sin(angle) + w * kotlin.math.cos(angle))
        val x2 = x - (l * kotlin.math.cos(angle) + w * kotlin.math.sin(angle))
        val y2 = y - (l * kotlin.math.sin(angle) - w * kotlin.math.cos(angle))
        path.moveTo(x, y)
        path.lineTo(x1, y1)
        path.lineTo(x2, y2)
        path.close()

        arrow.color = color
        canvas.drawPath(path, arrow)
    }

    private fun Float.dp() = this * resources.displayMetrics.density
}