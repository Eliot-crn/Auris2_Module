package fr.auris2.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Voiture en vue isométrique (caméra arrière gauche : on voit le flanc conducteur, l'arrière et le toit),
 * animée à chaque image avec les signaux décodés. Repère : x vers l'avant, y vers la gauche, z vers le haut (mètres).
 * Aucune allocation dans onDraw : chemins et pinceaux réutilisés, textes recalculés à 10 Hz hors dessin.
 */
class CarView(ctx: Context, private val panneau: GlassDrawable) : View(ctx) {
    companion object {
        const val C = 0.866f; const val S = 0.5f     // projection isométrique
        const val H = 0.88f                          // demi-largeur de caisse
        const val WR = 0.32f                         // rayon de roue
        const val XF = 1.35f; const val XR = -1.25f  // essieux avant / arrière
        const val WY = 0.76f                         // voie (centre du pneu)
        const val XH = 0.92f                         // charnière de la porte conducteur
        const val HX = -1.75f; const val HZ = 1.40f  // charnière du hayon
        const val HY = 0.6f                          // demi-largeur du hayon
        const val N = 20                             // segments par cercle
        val RAPPORTS = arrayOf("P", "R", "N", "D", "B")
    }

    /** Mode démo : données simulées (demoCar) au lieu des signaux. */
    var demo = false
    /** Remplit l'état avec les signaux réels (appelé à chaque image, ne doit rien allouer). */
    var source: ((CarState) -> Unit)? = null

    private val st = CarState()
    private val d = resources.displayMetrics.density
    private val cAccent = ctx.getColor(R.color.accent)
    private val cOnAccent = ctx.getColor(R.color.on_accent)
    private val cText = ctx.getColor(R.color.text)
    private val cDim = ctx.getColor(R.color.text_dim)
    private val cWarn = ctx.getColor(R.color.warn)
    private val cOk = ctx.getColor(R.color.ok)
    private val cGris = (ctx.getColor(R.color.off) and 0x00FFFFFF) or (0x80 shl 24) // signal périmé / non disponible
    private val cPiste = (cDim and 0x00FFFFFF) or (0x33 shl 24)

    private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFaisceau = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloR = halo(0xFFFF3030.toInt()); private val haloB = halo(0xFFFFFFFF.toInt()); private val haloA = halo(0xFFFFB300.toInt())
    private fun halo(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 100f, intArrayOf(col, (col and 0x00FFFFFF) or (0x55 shl 24), col and 0x00FFFFFF),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    }

    // profil latéral (sens horaire depuis le bas arrière) ; passage[i] = l'arête i -> i+1 est un passage de roue
    private val px: FloatArray; private val pz: FloatArray; private val passage: BooleanArray
    private val COS = FloatArray(N) { cos(2 * PI * it / N).toFloat() }
    private val SIN = FloatArray(N) { sin(2 * PI * it / N).toFloat() }
    private val porteX = floatArrayOf(-0.15f, -0.15f, 0.35f, 0.92f, 0.92f); private val porteZ = floatArrayOf(0.27f, 1.42f, 1.385f, 1.085f, 0.27f)
    private val vitreX = floatArrayOf(-0.08f, -0.08f, 0.32f, 0.78f, 0.80f); private val vitreZ = floatArrayOf(1.0f, 1.36f, 1.33f, 1.09f, 1.0f)

    init {
        val xs = ArrayList<Float>(); val zs = ArrayList<Float>(); val ar = ArrayList<Boolean>()
        fun p(x: Float, z: Float, a: Boolean = false) { xs.add(x); zs.add(z); ar.add(a) }
        p(-2.15f, 0.28f); p(-2.17f, 0.55f); p(-2.15f, 0.95f); p(HX, HZ); p(-0.6f, 1.47f); p(0.35f, 1.40f)
        p(1.15f, 0.98f); p(1.95f, 0.80f); p(2.15f, 0.62f); p(2.15f, 0.30f); p(2.0f, 0.2f)
        for (cx in floatArrayOf(XF, XR)) for (k in 0..10) {
            val a = Math.toRadians(-17.5 + 21.5 * k)
            p(cx + 0.4f * cos(a).toFloat(), WR + 0.4f * sin(a).toFloat(), k < 10)
        }
        p(-2.0f, 0.22f)
        px = xs.toFloatArray(); pz = zs.toFloatArray(); passage = ar.toBooleanArray()
        contentDescription = "Vue isométrique de la voiture animée par les signaux"
    }

    // géométrie projetée (recalculée dans onSizeChanged)
    private var sc = 40f; private var ox = 0f; private var oy = 0f
    private var qx = 0f; private var qy = 0f // résultat de pr()
    private var faces = arrayOf<Path>(); private var faceCols = IntArray(0)
    private val flanc = Path(); private val ombre = Path(); private val vitreAr = Path(); private val phare = Path()
    private val trouPorte = Path(); private val trouHayon = Path(); private val plaque = Path()
    private val faisceauG = Path(); private val faisceauD = Path()
    private val feuG = Path(); private val feuD = Path(); private val cliG = Path(); private val cliD = Path(); private val cliCote = Path()
    private val reculG = Path(); private val reculD = Path()
    private val tmp = Path(); private val rect = RectF()

    // valeurs lissées (animation)
    private var last = 0L; private var temps = 0.0
    private var aPorte = 0f; private var aHayon = 0f; private var aBraq = 0f
    private var gGaz = 0f; private var gFrein = 0f; private var gRegime = 0f
    private var iStop = 0f; private var iFeux = 0f; private var iFaisceau = 0f; private var iRecul = 0f; private var iMoteur = 0f
    private val rot = FloatArray(4) // AvG AvD ArG ArD
    private var rotVentil = 0f
    private val fumee = FloatArray(8) { it / 8f }

    // textes (mis à jour à 10 Hz, jamais dans onDraw)
    private var sVit = "--"; private var sCompteur = ""; private var sVolant = ""; private var sGaz = "--"; private var sFrein = "--"
    private var sRegime = "--"; private var sClim = ""; private var sFeux = ""; private var sPorte = ""; private var sCoffre = ""
    private var sRoues = ""; private var sCli = ""
    private val textes = object : Runnable { override fun run() { majTextes(); postDelayed(this, 100) } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(textes) }
    override fun onDetachedFromWindow() { removeCallbacks(textes); super.onDetachedFromWindow() }

    private fun pr(x: Float, y: Float, z: Float) { qx = ox - (x + y) * C * sc; qy = oy + ((y - x) * S - z) * sc }
    private fun Path.pt(x: Float, y: Float, z: Float, premier: Boolean) { pr(x, y, z); if (premier) moveTo(qx, qy) else lineTo(qx, qy) }
    private fun ombrer(col: Int, f: Float) = Color.rgb((Color.red(col) * f).toInt(), (Color.green(col) * f).toInt(), (Color.blue(col) * f).toInt())
    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb((Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt())
    }

    /** Rectangle sur la face arrière (x constant). */
    private fun arriere(p: Path, y1: Float, y2: Float, z1: Float, z2: Float) {
        p.rewind(); p.pt(-2.165f, y1, z1, true); p.pt(-2.165f, y2, z1, false); p.pt(-2.165f, y2, z2, false); p.pt(-2.165f, y1, z2, false); p.close()
    }

    /** Polygone du flanc gauche tourné de `a` rad autour de la charnière de porte (0 = fermé). */
    private fun porte(p: Path, xs: FloatArray, zs: FloatArray, a: Float) {
        p.rewind()
        val ca = cos(a); val sa = sin(a)
        for (i in xs.indices) p.pt(XH - (XH - xs[i]) * ca, H + (XH - xs[i]) * sa, zs[i], i == 0)
        p.close()
    }

    /** Point du hayon tourné de `a` rad autour de sa charnière de toit. */
    private fun Path.hayon(x: Float, y: Float, z: Float, a: Float, premier: Boolean) {
        val dx = x - HX; val dz = z - HZ
        pt(HX + dx * cos(a) + dz * sin(a), y, HZ - dx * sin(a) + dz * cos(a), premier)
    }

    /** Bande du hayon entre les fractions t1..t2 de la lunette (du bas vers la charnière), demi-largeur y. */
    private fun bandeHayon(p: Path, t1: Float, t2: Float, y: Float, a: Float) {
        val x1 = -2.15f + (HX + 2.15f) * t1; val z1 = 0.95f + (HZ - 0.95f) * t1
        val x2 = -2.15f + (HX + 2.15f) * t2; val z2 = 0.95f + (HZ - 0.95f) * t2
        p.rewind(); p.hayon(x1, y, z1, a, true); p.hayon(x2, y, z2, a, false); p.hayon(x2, -y, z2, a, false); p.hayon(x1, -y, z1, a, false); p.close()
    }

    private fun panneauHayon(p: Path, a: Float) {
        p.rewind()
        p.hayon(-2.17f, HY, 0.55f, a, true); p.hayon(-2.15f, HY, 0.95f, a, false); p.hayon(HX, HY, HZ, a, false)
        p.hayon(HX, -HY, HZ, a, false); p.hayon(-2.15f, -HY, 0.95f, a, false); p.hayon(-2.17f, -HY, 0.55f, a, false); p.close()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val haut = 104 * d; val bas = 204 * d
        sc = max(8f, min(w / 7.2f, (h - haut - bas) / 4.6f))
        ox = w / 2f + 0.25f * sc
        oy = haut + 2.99f * sc + max(0f, (h - haut - bas - 4.51f * sc) / 2)
        panneau.setBounds((10 * d).toInt(), (h - bas + 8 * d).toInt(), (w - 10 * d).toInt(), (h - 8 * d).toInt())

        // caisse : une face par arête du profil tournée vers la caméra, de l'avant (loin) vers l'arrière (près)
        val n = px.size
        val ordre = (0 until n).filter { i ->
            val j = (i + 1) % n; (px[j] - px[i]) + (pz[j] - pz[i]) > 0
        }.sortedByDescending { i -> px[i] + px[(i + 1) % n] }
        faces = Array(ordre.size) { Path() }; faceCols = IntArray(ordre.size)
        ordre.forEachIndexed { k, i ->
            val j = (i + 1) % n
            faces[k].apply { pt(px[i], H, pz[i], true); pt(px[j], H, pz[j], false); pt(px[j], -H, pz[j], false); pt(px[i], -H, pz[i], false); close() }
            val dx = px[j] - px[i]; val dz = pz[j] - pz[i]
            faceCols[k] = when {
                passage[i] -> 0xFF0C1018.toInt()
                i == 5 -> 0xFF1B2A44.toInt() // pare-brise
                else -> ombrer(cAccent, 0.62f + 0.38f * dx / Math.hypot(dx.toDouble(), dz.toDouble()).toFloat())
            }
        }
        flanc.rewind(); for (i in 0 until n) flanc.pt(px[i], H, pz[i], i == 0); flanc.close()
        ombre.rewind(); ombre.pt(-2.35f, 1.05f, 0f, true); ombre.pt(2.3f, 1.05f, 0f, false); ombre.pt(2.3f, -1.05f, 0f, false); ombre.pt(-2.35f, -1.05f, 0f, false); ombre.close()
        vitreAr.rewind(); vitreAr.pt(-1.5f, H, 1.0f, true); vitreAr.pt(-1.2f, H, 1.36f, false); vitreAr.pt(-0.25f, H, 1.40f, false); vitreAr.pt(-0.25f, H, 1.0f, false); vitreAr.close()
        phare.rewind(); phare.pt(1.72f, H, 0.66f, true); phare.pt(2.13f, H, 0.56f, false); phare.pt(2.13f, H, 0.63f, false); phare.pt(1.95f, H, 0.78f, false); phare.pt(1.78f, H, 0.78f, false); phare.close()
        cliCote.rewind(); cliCote.pt(1.0f, H, 0.91f, true); cliCote.pt(1.14f, H, 0.91f, false); cliCote.pt(1.14f, H, 0.96f, false); cliCote.pt(1.0f, H, 0.96f, false); cliCote.close()
        porte(trouPorte, porteX, porteZ, 0f); panneauHayon(trouHayon, 0f)
        arriere(feuG, HY, 0.86f, 0.70f, 0.84f); arriere(feuD, -0.86f, -HY, 0.70f, 0.84f)
        arriere(cliG, HY, 0.86f, 0.84f, 0.93f); arriere(cliD, -0.86f, -HY, 0.84f, 0.93f)
        arriere(reculG, 0.62f, 0.80f, 0.60f, 0.67f); arriere(reculD, -0.80f, -0.62f, 0.60f, 0.67f)
        arriere(plaque, -0.26f, 0.26f, 0.36f, 0.47f)
        for ((p, y0) in arrayOf(faisceauG to 0.55f, faisceauD to -0.55f)) {
            p.rewind(); p.pt(2.15f, y0 - 0.25f, 0.02f, true); p.pt(2.15f, y0 + 0.25f, 0.02f, false)
            p.pt(5.2f, y0 + 0.75f, 0.02f, false); p.pt(5.2f, y0 - 0.75f, 0.02f, false); p.close()
        }
        pr(2.15f, 0f, 0f); val x0 = qx; val y0 = qy; pr(5.2f, 0f, 0f)
        pFaisceau.shader = LinearGradient(x0, y0, qx, qy, 0xB0FFF2C0.toInt(), 0x00FFF2C0, Shader.TileMode.CLAMP)
    }

    // ---------- Animation ----------
    private fun vers(cur: Float, cible: Float, k: Float) = cur + (cible - cur) * min(1f, k)

    private fun avancer(dt: Float) {
        val s = st
        aPorte = vers(aPorte, if (s.door == 1) 1.05f else 0f, dt * 4)
        aHayon = vers(aHayon, if (s.trunk == 1) 1.25f else 0f, dt * 3)
        aBraq = vers(aBraq, -Math.toRadians(roadWheelAngle(s.steer)).toFloat(), dt * 10) // gauche = +y
        gGaz = vers(gGaz, if (s.gas.isNaN()) 0f else (s.gas / 100).toFloat(), dt * 10)
        gFrein = vers(gFrein, if (s.brakeForce.isNaN()) 0f else (s.brakeForce / 1600).toFloat(), dt * 10) // max vu 0x0615
        gRegime = vers(gRegime, if (s.rpm.isNaN()) 0f else (s.rpm / 5000).toFloat(), dt * 8)
        iStop = vers(iStop, if (s.brake == 1) 1f else 0f, dt * 16)
        iFeux = vers(iFeux, if (s.lights >= 1) 1f else 0f, dt * 6)
        iFaisceau = vers(iFaisceau, if (s.lights == 2) 1f else if (s.lights == 1) 0.25f else 0f, dt * 5)
        iRecul = vers(iRecul, if (s.gear == 1) 1f else 0f, dt * 10)
        val thermique = s.rpm > 0
        iMoteur = vers(iMoteur, if (thermique) 1f else 0f, dt * 4)
        for (i in 0..3) {
            val v = if (s.wheelsOk) s.wheels[i] else s.speed
            rot[i] = ((rot[i] - Math.toRadians(wheelSpin(v, s.gear)) * dt) % (2 * PI)).toFloat() // marche avant : le haut part vers l'avant
        }
        if (s.hvac == 1 && s.fan > 0) rotVentil = (rotVentil + s.fan * 110f * dt) % 360f
        for (i in fumee.indices) {
            val a = fumee[i] + dt * 0.8f
            fumee[i] = if (a < 1f) a else if (thermique) a - 1f else 1f
        }
    }

    // ---------- Dessin ----------
    private fun disque(cx: Float, cy: Float, r: Float, fx: Float, fy: Float) {
        tmp.rewind()
        for (k in 0 until N) { val u = r * COS[k]; tmp.pt(cx + u * fx, cy + u * fy, WR + r * SIN[k], k == 0) }
        tmp.close()
    }

    private fun roue(c: Canvas, i: Int, wx: Float, wy: Float, braq: Float, visible: Boolean) {
        val fx = cos(braq); val fy = sin(braq); val ax = -fy; val ay = fx
        pf.color = 0xFF14171D.toInt()
        for (k in 0..3) { val o = -0.11f + 0.22f * k / 3f; disque(wx + ax * o, wy + ay * o, WR, fx, fy); c.drawPath(tmp, pf) }
        if (!visible) return
        pl.color = 0xFF3A4252.toInt(); pl.strokeWidth = d; c.drawPath(tmp, pl)
        val ex = wx + ax * 0.115f; val ey = wy + ay * 0.115f
        val ok = st.wheelsOk || !st.speed.isNaN()
        disque(ex, ey, 0.215f, fx, fy); pf.color = if (ok) 0xFFC9D2E0.toInt() else 0xFF6B7587.toInt(); c.drawPath(tmp, pf)
        pr(ex, ey, WR); val x0 = qx; val y0 = qy
        pl.color = 0xFF2A303C.toInt(); pl.strokeWidth = 0.05f * sc
        for (k in 0 until 5) {
            val a = rot[i] + k * (2 * PI.toFloat() / 5); val u = 0.19f * cos(a)
            pr(ex + u * fx, ey + u * fy, WR + 0.19f * sin(a)); c.drawLine(x0, y0, qx, qy, pl)
        }
        disque(ex, ey, 0.05f, fx, fy); pf.color = 0xFF2A303C.toInt(); c.drawPath(tmp, pf)
    }

    private fun lueur(c: Canvas, p: Paint, x: Float, y: Float, z: Float, r: Float, a: Float) {
        if (a < 0.02f) return
        pr(x, y, z); p.alpha = min(255, (255 * a).toInt())
        val k = r * sc / 100f
        c.save(); c.translate(qx, qy); c.scale(k, k); c.drawCircle(0f, 0f, 100f, p); c.restore()
    }

    private fun remplir(c: Canvas, p: Path, col: Int) { pf.color = col; c.drawPath(p, pf) }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else min(0.05f, (now - last) / 1000f)
        last = now; temps += dt
        if (demo) demoCar(st, temps) else source?.invoke(st)
        avancer(dt)
        val s = st
        val cliOn = s.blink > 0 && now % 700 < 350
        val cliGauche = cliOn && s.blink != 2; val cliDroite = cliOn && s.blink != 1

        remplir(c, ombre, 0x30000000)
        if (iFaisceau > 0.02f) { pFaisceau.alpha = (255 * iFaisceau).toInt(); c.drawPath(faisceauG, pFaisceau); c.drawPath(faisceauD, pFaisceau) }
        roue(c, 1, XF, -WY, aBraq, false); roue(c, 3, XR, -WY, 0f, false)

        c.save(); c.translate(0f, iMoteur * 0.7f * d * sin(temps * 55).toFloat()) // vibration du thermique
        for (k in faces.indices) remplir(c, faces[k], faceCols[k])
        remplir(c, flanc, ombrer(cAccent, 0.8f))
        remplir(c, vitreAr, 0xFF1B2A44.toInt())
        // feux avant (retour d'aile) : croisement = blanc vif, position = veilleuse
        remplir(c, phare, if (s.lights < 0) cGris else mix(0xFF9AA6B8.toInt(), 0xFFFFFFFF.toInt(), iFaisceau * 1.5f))
        // arrière : feux rouges (position), stop, recul, clignotants, plaque
        val rouge = if (s.brake < 0 && s.lights < 0) cGris else mix(mix(0xFF5A1414.toInt(), 0xFFB02020.toInt(), iFeux), 0xFFFF3030.toInt(), iStop)
        remplir(c, feuG, rouge); remplir(c, feuD, rouge)
        val recul = if (s.gear < 0) cGris else mix(0xFF8A93A3.toInt(), 0xFFFFFFFF.toInt(), iRecul)
        remplir(c, reculG, recul); remplir(c, reculD, recul)
        remplir(c, cliG, if (s.blink < 0) cGris else if (cliGauche) 0xFFFFB300.toInt() else 0xFF6B4A00.toInt())
        remplir(c, cliD, if (s.blink < 0) cGris else if (cliDroite) 0xFFFFB300.toInt() else 0xFF6B4A00.toInt())
        remplir(c, cliCote, if (s.blink < 0) cGris else if (cliGauche) 0xFFFFB300.toInt() else 0xFF6B4A00.toInt())
        remplir(c, plaque, 0xFFE8ECF2.toInt())
        // hayon (coffre)
        if (aHayon > 0.01f) remplir(c, trouHayon, mix(0x000B0F18, 0xFF0B0F18.toInt(), aHayon * 6))
        panneauHayon(tmp, aHayon); remplir(c, tmp, if (s.trunk < 0) mix(ombrer(cAccent, 0.7f), cGris or 0xFF000000.toInt(), 0.5f) else ombrer(cAccent, 0.7f + 0.2f * aHayon))
        pl.color = 0x66000000; pl.strokeWidth = d; c.drawPath(tmp, pl)
        bandeHayon(tmp, 0.12f, 0.86f, 0.5f, aHayon); remplir(c, tmp, 0xFF1B2A44.toInt())
        bandeHayon(tmp, 0.90f, 0.96f, 0.2f, aHayon); remplir(c, tmp, if (s.brake < 0) cGris else mix(0xFF5A1414.toInt(), 0xFFFF3030.toInt(), iStop))
        c.restore()

        roue(c, 0, XF, WY, aBraq, true); roue(c, 2, XR, WY, 0f, true)

        // porte conducteur : au-delà de 45 deg on voit sa face intérieure
        c.save(); c.translate(0f, iMoteur * 0.7f * d * sin(temps * 55).toFloat())
        if (aPorte > 0.01f) remplir(c, trouPorte, mix(0x000B0F18, 0xFF0B0F18.toInt(), aPorte * 6))
        porte(tmp, porteX, porteZ, aPorte)
        remplir(c, tmp, if (s.door < 0) mix(ombrer(cAccent, 0.8f), cGris or 0xFF000000.toInt(), 0.5f) else if (aPorte > 0.785f) 0xFF2A3345.toInt() else ombrer(cAccent, 0.8f + 0.15f * aPorte))
        pl.color = 0x66000000; pl.strokeWidth = d; c.drawPath(tmp, pl)
        porte(tmp, vitreX, vitreZ, aPorte); remplir(c, tmp, 0xFF1B2A44.toInt())
        c.restore()

        // fumée d'échappement (thermique en marche)
        if (iMoteur > 0.02f) for (i in fumee.indices) {
            val a = fumee[i]
            pr(-2.2f - a * 0.9f, -0.45f + 0.12f * sin(i * 2.4f), 0.25f + a * a * 0.5f)
            pf.color = cDim; pf.alpha = ((1 - a) * 90 * iMoteur).toInt(); c.drawCircle(qx, qy, (0.06f + 0.2f * a) * sc, pf)
        }
        // halos
        val r = 0.25f * iFeux + 0.75f * iStop
        lueur(c, haloR, -2.17f, 0.73f, 0.77f, 0.45f + 0.35f * iStop, r); lueur(c, haloR, -2.17f, -0.73f, 0.77f, 0.45f + 0.35f * iStop, r)
        lueur(c, haloB, -2.17f, 0.71f, 0.63f, 0.5f, iRecul * 0.8f); lueur(c, haloB, -2.17f, -0.71f, 0.63f, 0.5f, iRecul * 0.8f)
        lueur(c, haloB, 1.95f, H, 0.68f, 0.7f, iFaisceau * 0.9f)
        if (cliGauche) { lueur(c, haloA, -2.17f, 0.73f, 0.88f, 0.5f, 0.9f); lueur(c, haloA, 1.07f, H, 0.93f, 0.3f, 0.9f) }
        if (cliDroite) lueur(c, haloA, -2.17f, -0.73f, 0.88f, 0.5f, 0.9f)

        hud(c)
        postInvalidateOnAnimation()
    }

    private fun texte(c: Canvas, t: String, x: Float, y: Float, taille: Float, col: Int, gras: Boolean = false, droite: Boolean = false) {
        pTxt.textSize = taille * d; pTxt.color = col; pTxt.isFakeBoldText = gras
        pTxt.textAlign = if (droite) Paint.Align.RIGHT else Paint.Align.LEFT
        c.drawText(t, x, y, pTxt)
    }

    private fun jauge(c: Canvas, y: Float, nom: String, v: Float, valeur: String, col: Int, perime: Boolean) {
        val w = width.toFloat()
        texte(c, nom, 24 * d, y + 5 * d, 13f, cDim)
        rect.set(126 * d, y - 4 * d, w - 118 * d, y + 4 * d)
        pf.color = cPiste; c.drawRoundRect(rect, 4 * d, 4 * d, pf)
        if (!perime && v > 0.005f) {
            rect.right = rect.left + (rect.right - rect.left) * v.coerceIn(0f, 1f)
            pf.color = col; c.drawRoundRect(rect, 4 * d, 4 * d, pf)
        }
        texte(c, valeur, w - 24 * d, y + 5 * d, 13f, if (perime) cGris else cText, gras = true, droite = true)
    }

    private fun hud(c: Canvas) {
        val s = st; val w = width.toFloat(); val h = height.toFloat()
        // vitesse, compteur, volant
        texte(c, sVit, 16 * d, 50 * d, 44f, if (s.speed.isNaN()) cGris else cAccent, gras = true)
        val lv = pTxt.measureText(sVit)
        texte(c, "km/h", 22 * d + lv, 50 * d, 14f, cDim)
        texte(c, sCompteur, 16 * d, 70 * d, 13f, if (s.dash.isNaN()) cGris else cDim)
        texte(c, sVolant, 16 * d, 88 * d, 13f, if (s.steer.isNaN()) cGris else cDim)
        // rapport P R N D B
        for (i in 0..4) {
            val x = w - 16 * d - (5 - i) * 30 * d
            val actif = s.gear == i
            if (actif) { rect.set(x + 2 * d, 16 * d, x + 28 * d, 52 * d); pf.color = if (i == 1) cWarn else cAccent; c.drawRoundRect(rect, 10 * d, 10 * d, pf) }
            pTxt.textAlign = Paint.Align.CENTER; pTxt.textSize = 18 * d; pTxt.isFakeBoldText = actif
            pTxt.color = if (s.gear < 0) cGris else if (actif) cOnAccent else cDim
            c.drawText(RAPPORTS[i], x + 15 * d, 41 * d, pTxt)
        }
        // panneau de verre du bas
        panneau.draw(c)
        var y = h - 204 * d + 34 * d
        jauge(c, y, "Accélérateur", gGaz, sGaz, cOk, s.gas.isNaN()); y += 26 * d
        jauge(c, y, "Frein", gFrein, sFrein, 0xFFE5484D.toInt(), s.brake < 0); y += 26 * d
        jauge(c, y, "Thermique", gRegime, sRegime, cWarn, s.rpm.isNaN()); y += 30 * d
        // clim : ventilateur qui tourne selon la vitesse de ventilation
        val vx = 34 * d
        pf.color = if (s.hvac < 0) cGris else if (s.hvac == 1) cAccent else cDim
        c.save(); c.rotate(rotVentil, vx, y - 4 * d)
        rect.set(vx - 3 * d, y - 15 * d, vx + 3 * d, y - 4 * d)
        for (k in 0..2) { c.drawOval(rect, pf); c.rotate(120f, vx, y - 4 * d) }
        c.restore()
        texte(c, sClim, 54 * d, y, 13f, if (s.hvac < 0) cGris else cText)
        texte(c, sFeux, w - 24 * d, y, 13f, if (s.lights < 0) cGris else cText, droite = true); y += 24 * d
        texte(c, sPorte, 24 * d, y, 13f, if (s.door < 0) cGris else if (s.door == 1) cWarn else cText)
        texte(c, sCoffre, w - 24 * d, y, 13f, if (s.trunk < 0) cGris else if (s.trunk == 1) cWarn else cText, droite = true); y += 24 * d
        texte(c, sRoues, 24 * d, y, 12f, if (s.wheelsOk) cDim else cGris); y += 22 * d
        texte(c, sCli, 24 * d, y, 12f, if (s.blink < 0) cGris else cDim)
    }

    private fun majTextes() {
        if (!isShown) return
        val s = st
        fun n(v: Double, fmt: String) = if (v.isNaN()) "--" else fmt.format(Locale.US, v)
        fun oo(v: Int, on: String, off: String) = if (v < 0) "--" else if (v > 0) on else off
        sVit = n(s.speed, "%.0f")
        sCompteur = "Compteur " + n(s.dash, "%.0f km/h")
        sVolant = "Volant " + if (s.steer.isNaN()) "--" else if (s.steer == 0.0) "0°" else "%.0f° %s".format(Locale.US, Math.abs(s.steer), if (s.steer < 0) "G" else "D")
        sGaz = n(s.gas, "%.0f %%")
        sFrein = oo(s.brake, "ON", "OFF") + if (s.brakeForce.isNaN()) "" else " · %.0f".format(Locale.US, s.brakeForce)
        sRegime = if (s.rpm.isNaN()) "--" else if (s.rpm > 0) "%.0f tr/min".format(Locale.US, s.rpm) else "OFF (EV)"
        sClim = "Clim " + oo(s.hvac, "ON", "OFF") + " · ventil. " + (if (s.fan < 0) "--" else "${s.fan}/7")
        sFeux = "Feux : " + when (s.lights) { -1 -> "--"; 2 -> "Croisement"; 1 -> "Position"; else -> "Éteints/AUTO" }
        sPorte = "Porte cond. : " + oo(s.door, "ouverte", "fermée")
        sCoffre = "Coffre : " + oo(s.trunk, "ouvert", "fermé")
        sRoues = if (!s.wheelsOk) "Roues km/h  --" else "Roues km/h  AvG %.1f  AvD %.1f  ArG %.1f  ArD %.1f".format(Locale.US, s.wheels[0], s.wheels[1], s.wheels[2], s.wheels[3])
        sCli = "Clignotants : " + when (s.blink) { -1 -> "non disponibles (invisibles sur l'OBD2)"; 1 -> "gauche (démo)"; 2 -> "droite (démo)"; 3 -> "détresse (démo)"; else -> "éteints (démo)" }
    }
}
