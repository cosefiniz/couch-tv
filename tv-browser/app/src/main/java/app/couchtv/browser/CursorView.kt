package app.couchtv.browser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Draws the D-pad mouse pointer (or the scroll-mode badge) on top of the page. */
class CursorView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var cx = 0f
        private set
    var cy = 0f
        private set
    private var placed = false

    var shown = false
        set(v) {
            if (field != v) {
                field = v; invalidate()
            }
        }
    var scrollMode = false
        set(v) {
            field = v; invalidate()
        }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL
        setShadowLayer(3f * d, 1f * d, 1.5f * d, 0x99000000.toInt())
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE
        strokeWidth = 1.6f * d; strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3D8BFD.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * d
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD93D8BFD.toInt(); style = Paint.Style.FILL
        setShadowLayer(4f * d, 0f, 1.5f * d, 0x99000000.toInt())
    }
    private val badgeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d
    }
    private val arrow = Path()
    private val tri = Path()
    private var pulse = 0f

    init {
        isFocusable = false
        isClickable = false
        val s = 1.35f * d
        val pts = floatArrayOf(0f, 0f, 0f, 18f, 4.5f, 14f, 7.5f, 21f, 10.6f, 19.7f, 7.7f, 13f, 13f, 13f)
        arrow.moveTo(pts[0] * s, pts[1] * s)
        var i = 2
        while (i < pts.size) {
            arrow.lineTo(pts[i] * s, pts[i + 1] * s); i += 2
        }
        arrow.close()
    }

    fun moveTo(x: Float, y: Float) {
        cx = x; cy = y; placed = true
        invalidate()
    }

    /** Puts the pointer in the middle of [r] the first time, and keeps it inside [r] afterwards. */
    fun keepInside(r: RectF) {
        if (r.isEmpty) return
        if (!placed) {
            moveTo(r.centerX(), r.centerY()); return
        }
        val x = cx.coerceIn(r.left, r.right - 1)
        val y = cy.coerceIn(r.top, r.bottom - 1)
        if (x != cx || y != cy) moveTo(x, y)
    }

    fun click() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            addUpdateListener {
                pulse = it.animatedValue as Float
                if (pulse >= 1f) pulse = 0f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        if (!shown || !placed) return
        if (scrollMode) {
            val r = 24f * d
            c.drawCircle(cx, cy, r, badge)
            c.drawCircle(cx, cy, r, badgeEdge)
            val a = 6f * d
            val o = 14f * d
            drawTri(c, cx, cy - o, 0f, -a, a)
            drawTri(c, cx, cy + o, 0f, a, a)
            drawTri(c, cx - o, cy, -a, 0f, a)
            drawTri(c, cx + o, cy, a, 0f, a)
        } else {
            c.save()
            c.translate(cx, cy)
            c.drawPath(arrow, fill)
            c.drawPath(arrow, outline)
            c.restore()
        }
        if (pulse > 0f) {
            ring.alpha = ((1f - pulse) * 255).toInt()
            c.drawCircle(cx, cy, (6f + 22f * pulse) * d, ring)
        }
    }

    /** Small triangle pointing in direction (dx, dy) with its tip at (x+dx, y+dy). */
    private fun drawTri(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, a: Float) {
        tri.reset()
        if (dx == 0f) {
            tri.moveTo(x, y + dy); tri.lineTo(x - a, y); tri.lineTo(x + a, y)
        } else {
            tri.moveTo(x + dx, y); tri.lineTo(x, y - a); tri.lineTo(x, y + a)
        }
        tri.close()
        c.drawPath(tri, fill)
    }
}
