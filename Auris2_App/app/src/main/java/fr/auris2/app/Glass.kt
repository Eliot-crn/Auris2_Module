package fr.auris2.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Style « liquid glass » : fond animé flouté + surfaces translucides à reflet et bordure lumineuse.

/** Fond : taches de couleur qui dérivent lentement, floutées par RenderEffect (givre vu à travers les surfaces). */
class GlassBackground(ctx: Context, private val base: Int, private val cols: IntArray) : View(ctx) {
    private val paints = Array(cols.size) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val t0 = SystemClock.uptimeMillis()
    private var r = 1f

    init { setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.MIRROR)) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        r = min(w, h) * 0.55f
        for (i in cols.indices) paints[i].shader = RadialGradient(0f, 0f, r,
            intArrayOf(cols[i], cols[i], cols[i] and 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(base)
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        for (i in paints.indices) {
            val x = width * (0.5f + 0.42f * sin(t * (0.11f + 0.03f * i) + i * 2.1f))
            val y = height * (0.5f + 0.42f * cos(t * (0.09f + 0.04f * i) + i * 1.3f))
            c.save(); c.translate(x, y); c.drawCircle(0f, 0f, r, paints[i]); c.restore()
        }
        postInvalidateDelayed(33) // mouvement lent : 30 images/s suffisent (batterie)
    }
}

/** Surface de verre : remplissage translucide, reflet en haut, bordure lumineuse dégradée. */
class GlassDrawable(fill: Int, private val edge: Int, private val sheen: Int, private val radius: Float, private val sw: Float) : Drawable() {
    private val pf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val ps = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw }
    private val r = RectF()
    /** Taille réellement dessinée (morphing du menu) ; négatif = toute la surface. */
    var rw = -1f; var rh = -1f

    private fun a(col: Int, k: Float) = (col and 0x00FFFFFF) or ((Color.alpha(col) * k).toInt() shl 24)

    override fun onBoundsChange(b: Rect) {
        val l = b.left.toFloat(); val t = b.top.toFloat()
        ps.shader = LinearGradient(l, t, l, t + b.height() * 0.7f, sheen, sheen and 0x00FFFFFF, Shader.TileMode.CLAMP)
        pb.shader = LinearGradient(l, t, b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(edge, a(edge, 0.15f), a(edge, 0.55f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val w = if (rw < 0) b.width().toFloat() else rw
        val h = if (rh < 0) b.height().toFloat() else rh
        r.set(b.left + sw / 2, b.top + sw / 2, b.left + w - sw / 2, b.top + h - sw / 2)
        val rad = min(radius, min(r.width(), r.height()) / 2)
        c.drawRoundRect(r, rad, rad, pf); c.drawRoundRect(r, rad, rad, ps); c.drawRoundRect(r, rad, rad, pb)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Bulle de menu : rond de côté `petit` (p = 0) qui s'étend jusqu'à sa taille complète (p = 1), sans passe de layout. */
class MorphPanel(ctx: Context, private val glass: GlassDrawable, private val petit: Float) : FrameLayout(ctx) {
    var p = 0f
        set(v) { field = v; invalidateOutline(); invalidate() }

    private fun curW() = petit + (width - petit) * p
    private fun curH() = petit + (height - petit) * p

    init {
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, curW().toInt(), curH().toInt(), petit / 2) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { glass.setBounds(0, 0, w, h) }

    override fun onDraw(c: Canvas) { glass.rw = curW(); glass.rh = curH(); glass.draw(c) }

    // la partie repliée ne doit pas capter les touchers
    override fun dispatchTouchEvent(e: MotionEvent) =
        if (e.actionMasked == MotionEvent.ACTION_DOWN && (e.x > curW() || e.y > curH())) false else super.dispatchTouchEvent(e)
}

/** Icône du menu : trois traits (p = 0) qui se croisent en X (p = 1). */
class MenuIcon(ctx: Context, color: Int) : View(ctx) {
    var p = 0f
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; strokeCap = Paint.Cap.ROUND; strokeWidth = 2.2f * ctx.resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val l = width * 0.2f; val g = width * 0.14f * (1 - p)
        paint.alpha = (255 * (1 - p)).toInt(); c.drawLine(cx - l, cy, cx + l, cy, paint); paint.alpha = 255
        c.save(); c.rotate(45 * p, cx, cy); c.drawLine(cx - l, cy - g, cx + l, cy - g, paint); c.restore()
        c.save(); c.rotate(-45 * p, cx, cy); c.drawLine(cx - l, cy + g, cx + l, cy + g, paint); c.restore()
    }
}
