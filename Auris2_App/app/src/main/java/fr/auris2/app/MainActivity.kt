package fr.auris2.app

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.TypedValue
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.bluetooth.*
import android.bluetooth.le.*
import android.app.UiModeManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.*
import android.os.ParcelUuid
import android.provider.MediaStore
import android.view.*
import android.widget.*
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission") // permissions vérifiées avant chaque action
@Suppress("DEPRECATION")
class MainActivity : android.app.Activity() {
    companion object {
        val SERVICE: UUID = UUID.fromString("8f1a0001-3c2b-4d5e-9f00-a0b1c2d3e4f5")
        val CHAR: UUID = UUID.fromString("8f1a0002-3c2b-4d5e-9f00-a0b1c2d3e4f5")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val FIN = ByteArray(0) // marqueur de fin pour le thread d'enregistrement
    }

    private val ui = Handler(Looper.getMainLooper())
    private val model = Model()          // accès sous synchronized(model)
    private var etat = "Déconnecté"      // texte d'état (écrit depuis plusieurs threads)
    @Volatile private var voulu = false  // l'utilisateur veut être connecté
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCb: ScanCallback? = null

    // enregistrement
    @Volatile private var rec: Recorder? = null

    private lateinit var tvEtat: TextView
    private lateinit var tvCpt: TextView
    private lateinit var tvRec: TextView
    private lateinit var tvTab: TextView
    private lateinit var tvDossier: TextView
    private lateinit var btnCo: Button
    private lateinit var btnRec: Button
    private lateinit var dot: View
    private lateinit var pages: List<View>   // Bord, Trames, Réglages, Voiture
    private lateinit var corps: View         // tout sauf le menu (flouté quand le menu est ouvert)
    private lateinit var voile: View
    private lateinit var bulle: MorphPanel
    private lateinit var icone: MenuIcon
    private lateinit var entrees: List<TextView>
    private lateinit var prefs: android.content.SharedPreferences
    private var onglet = 0
    private var menuOuvert = false
    private var menuAnim: ValueAnimator? = null
    private var demo = false
    private var tuiles = listOf<Tuile>() // dans l'ordre de TILES

    private class Tuile(val vue: View, val tv: TextView) { var txt = "--"; var num = 0f; var anim: ValueAnimator? = null }

    /** Tuiles du tableau de bord : (libellé, unité, valeur) ; appelé sous synchronized(model). */
    private val TILES: List<Triple<String, String, (Values, Long) -> String>> = listOf(
        Triple("Vitesse", "km/h") { v, n -> f(v.get(v.speed, n), "%.1f") },
        Triple("Compteur", "km/h") { v, n -> f(v.get(v.dash, n), "%.0f") },
        Triple("Rapport", "") { v, n -> v.gearLetter(n) ?: "--" },
        Triple("Régime", "tr/min") { v, n -> f(v.get(v.rpm, n), "%.0f") },
        Triple("Thermique", "") { v, n -> onoff(v, v.rpm, n) },
        Triple("Accélérateur", "%") { v, n -> f(v.get(v.gas, n), "%.1f") },
        Triple("Frein", "") { v, n -> onoff(v, v.brake, n) },
        Triple("Effort frein", "brut") { v, n -> f(v.get(v.brakeForce, n), "%.0f") },
        Triple("Volant", "") { v, n -> v.steerText(n) ?: "--" },
        Triple("Feux", "") { v, n -> v.lightsText(n) ?: "--" },
        Triple("Clim", "") { v, n -> onoff(v, v.hvac, n) },
        Triple("Ventilation", "/7") { v, n -> f(v.get(v.fan, n), "%.0f") },
        Triple("Porte cond.", "") { v, n -> ouvert(v, v.door, n, "ouverte", "fermée") },
        Triple("Coffre", "") { v, n -> ouvert(v, v.trunk, n, "ouvert", "fermé") },
        Triple("Roue AvG", "km/h") { v, n -> roue(v, 0, n) },
        Triple("Roue AvD", "km/h") { v, n -> roue(v, 1, n) },
        Triple("Roue ArG", "km/h") { v, n -> roue(v, 2, n) },
        Triple("Roue ArD", "km/h") { v, n -> roue(v, 3, n) },
    )

    private fun onoff(v: Values, x: Val, n: Long) = v.get(x, n)?.let { if (it > 0) "ON" else "OFF" } ?: "--"
    private fun ouvert(v: Values, x: Val, n: Long, o: String, c: String) = if (v.get(x, n) == null) "--" else if (v.open(x, n)) o else c
    private fun roue(v: Values, i: Int, n: Long) = if (v.get(v.wheel, n) == null) "--" else "%.1f".format(Locale.US, v.wheels[i])

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("auris2", MODE_PRIVATE)
        if (!prefs.contains("theme")) prefs.edit().putString("theme", "sombre").apply() // 1er lancement : sombre
        appliquerTheme()
        construire()
        ui.post(object : Runnable { override fun run() { rafraichir(); ui.postDelayed(this, 200) } })
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); construire() } // changement jour/nuit

    private fun appliquerTheme() {
        val m = when (prefs.getString("theme", "sombre")) {
            "clair" -> UiModeManager.MODE_NIGHT_NO
            "systeme" -> UiModeManager.MODE_NIGHT_AUTO
            else -> UiModeManager.MODE_NIGHT_YES
        }
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setApplicationNightMode(m)
    }

    // ---------- Interface (construite en code, style « liquid glass », couleurs dans res/values*/colors.xml) ----------
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun c(id: Int) = getColor(id)
    private fun fond(fill: Int, r: Int = 22) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(r).toFloat() }
    private fun verre(r: Int = 22) = GlassDrawable(c(R.color.glass), c(R.color.glass_edge), c(R.color.sheen), dp(r).toFloat(), dp(1).toFloat())
    private fun txt(size: Float, color: Int = c(R.color.text), bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color)
        if (mono) typeface = Typeface.MONOSPACE else if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Retour tactile : léger enfoncement, rebond au relâchement, vibration courte. Ne consomme pas le toucher. */
    @SuppressLint("ClickableViewAccessibility")
    private fun tactile(v: View) = v.setOnTouchListener { x, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                x.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(OvershootInterpolator(3f)).start()
        }
        false
    }

    private fun bouton(label: String, plein: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; minHeight = dp(52); stateListAnimator = null
        setTextColor(if (plein) c(R.color.on_accent) else c(R.color.accent))
        background = if (plein) fond(c(R.color.accent)) else verre()
        setOnClickListener { onClick() }; tactile(this)
    }
    private fun lp(w: Int, h: Int, wt: Float = 0f, m: Int = 0) = LinearLayout.LayoutParams(w, h, wt).apply { setMargins(m, m, m, m) }
    private fun carte(v: View) = FrameLayout(this).apply { background = verre(); setPadding(dp(14), dp(12), dp(14), dp(12)); addView(v) }

    private fun construire() {
        val nuit = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(c(R.color.bg)))
        menuAnim?.cancel(); menuOuvert = false
        // en-tête (la place de gauche est celle de la bulle du menu)
        dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c(R.color.off)) } }
        val logo = ImageView(this).apply { setImageResource(R.drawable.logo); contentDescription = "Logo Auris2" }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(76), dp(8), dp(16), dp(8)); minimumHeight = dp(64)
            addView(txt(22f, bold = true).apply { text = "Auris2" }, lp(0, -2, 1f))
            addView(dot, lp(dp(12), dp(12)).apply { rightMargin = dp(12) })
            addView(logo, lp(dp(44), dp(44)))
        }
        // vue Bord
        tvEtat = txt(16f); tvRec = txt(13f, c(R.color.text_dim), mono = true)
        btnCo = bouton(if (voulu) "Déconnecter" else "Connecter", true) { basculeConnexion() }
        btnRec = bouton(if (rec != null) "Arrêter" else "Enregistrer", false) { basculeRec() }
        val grille = GridLayout(this).apply { columnCount = 2 }
        tuiles = TILES.map { (nom, unite, _) ->
            val v = txt(26f, c(R.color.accent), bold = true).apply {
                text = "--"; alpha = 0.4f; maxLines = 1; gravity = Gravity.CENTER_VERTICAL
                setAutoSizeTextTypeUniformWithConfiguration(12, 26, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            val tuile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(10)); background = verre()
                addView(LinearLayout(context).apply {
                    addView(txt(13f, c(R.color.text_dim)).apply { text = nom }, lp(0, -2, 1f))
                    addView(txt(12f, c(R.color.text_dim)).apply { text = unite })
                })
                addView(v, lp(-1, dp(38))) // taille fixe : animer la valeur ne relance aucun layout
                visibility = if (nom in masquees()) View.GONE else View.VISIBLE
                layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0; setMargins(dp(5), dp(5), dp(5), dp(5))
                }
            }
            grille.addView(tuile); Tuile(tuile, v)
        }
        val bord = scroll(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(11), dp(4), dp(11), dp(16))
            addView(LinearLayout(context).apply {
                addView(btnCo, lp(0, -2, 1f, dp(5))); addView(btnRec, lp(0, -2, 1f, dp(5)))
            })
            addView(tvEtat, lp(-1, -2).apply { setMargins(dp(5), dp(8), dp(5), 0) })
            addView(tvRec, lp(-1, -2).apply { setMargins(dp(5), dp(4), dp(5), dp(6)) })
            addView(grille)
        })
        // vue Trames
        tvCpt = txt(14f, mono = true); tvTab = txt(11f, mono = true)
        val trames = scroll(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(carte(tvCpt)); addView(carte(HorizontalScrollView(context).apply { addView(tvTab) }), lp(-1, -2).apply { topMargin = dp(12) })
        })
        // vue Réglages
        val reglages = scroll(reglagesVue())
        // vue Voiture (ouverte depuis le menu)
        val voiture = CarView(this, verre()).apply {
            demo = this@MainActivity.demo
            source = { s -> synchronized(model) { model.values.snapshot(s, SystemClock.elapsedRealtime()) } }
        }
        val btnDemo = txt(14f, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0); isClickable = true }
        fun majDemo() {
            btnDemo.text = if (demo) "Démo : ON" else "Démo"
            btnDemo.setTextColor(c(if (demo) R.color.on_accent else R.color.text))
            btnDemo.background = if (demo) fond(c(R.color.accent), 24) else verre(24)
        }
        majDemo(); tactile(btnDemo)
        btnDemo.setOnClickListener { demo = !demo; voiture.demo = demo; majDemo() }
        val pageVoiture = FrameLayout(this).apply {
            addView(voiture)
            addView(btnDemo, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.END).apply { topMargin = dp(58); rightMargin = dp(16) })
        }
        pages = listOf(bord, trames, reglages, pageVoiture)
        val contenu = FrameLayout(this).apply { pages.forEach { addView(it) } }
        corps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header); addView(contenu, lp(-1, 0, 1f))
        }
        // menu : bulle de verre ronde en haut à gauche, qui s'étend en panneau
        voile = View(this).apply {
            setBackgroundColor(c(R.color.scrim)); alpha = 0f; visibility = View.GONE
            contentDescription = "Fermer le menu"; setOnClickListener { menu(false) }
        }
        icone = MenuIcon(this, c(R.color.text)).apply {
            contentDescription = "Menu"; isClickable = true; isFocusable = true
            setOnClickListener { menu(!menuOuvert) }; tactile(this)
        }
        entrees = listOf("Bord", "Trames", "Réglages", "Voiture en 3D").mapIndexed { i, nom ->
            txt(16f).apply {
                text = nom; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(20), 0); alpha = 0f; isClickable = true
                setOnClickListener { menu(false); choisir(i) }; tactile(this)
            }
        }
        bulle = MorphPanel(this, verre(24), dp(48).toFloat()).apply {
            pivotX = 0f; pivotY = 0f
            addView(icone, FrameLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; entrees.forEach { addView(it, lp(-1, dp(48))) }
            }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = dp(48); bottomMargin = dp(8) })
        }
        val dedans = FrameLayout(this).apply {
            addView(corps); addView(voile)
            addView(bulle, FrameLayout.LayoutParams(dp(216), -2).apply { leftMargin = dp(16); topMargin = dp(8) })
        }
        dedans.setOnApplyWindowInsetsListener { v, i -> // bord à bord (API 35+) : seul le fond passe sous les barres
            val s = i.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom); i
        }
        setContentView(FrameLayout(this).apply {
            if (!nuit) addView(GlassBackground(context, c(R.color.bg), intArrayOf(c(R.color.blob1), c(R.color.blob2), c(R.color.blob3))))
            addView(dedans)
        })
        // après setContentView : avant, la DecorView n'existe pas (plantage crash_20261109_100952)
        window.decorView.windowInsetsController?.let {
            val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            it.setSystemBarsAppearance(if (nuit) 0 else m, m)
        }
        choisir(onglet, false)
    }

    private fun scroll(v: View) = ScrollView(this).apply { addView(v); isVerticalScrollBarEnabled = false }

    /** Change de vue : fondu + glissement dans le sens du déplacement, tuiles qui apparaissent en cascade. */
    private fun choisir(i: Int, anime: Boolean = true) {
        val prev = onglet; onglet = i
        val sens = if (i >= prev) 1f else -1f
        pages.forEachIndexed { k, p ->
            p.animate().cancel()
            when {
                k == i -> {
                    p.visibility = View.VISIBLE
                    if (anime && prev != i) {
                        p.alpha = 0f; p.translationX = sens * dp(48)
                        p.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).withEndAction(null).start()
                    } else { p.alpha = 1f; p.translationX = 0f }
                }
                k == prev && anime && p.visibility == View.VISIBLE ->
                    p.animate().alpha(0f).translationX(-sens * dp(48)).setDuration(180).withEndAction { p.visibility = View.GONE }.start()
                else -> p.visibility = View.GONE
            }
        }
        if (i == 0 && (prev != 0 || !anime)) entreeTuiles()
    }

    private fun entreeTuiles() {
        var k = 0
        for (t in tuiles) if (t.vue.visibility == View.VISIBLE) {
            t.vue.alpha = 0f; t.vue.translationY = dp(22).toFloat(); t.vue.scaleX = 0.94f; t.vue.scaleY = 0.94f
            t.vue.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(28L * k++).setDuration(340).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    /** Ouvre / ferme le menu : la bulle s'étend (léger rebond), les entrées arrivent en cascade, le reste se floute. */
    private fun menu(ouvrir: Boolean) {
        if (ouvrir == menuOuvert) return
        menuOuvert = ouvrir
        menuAnim?.cancel()
        voile.visibility = View.VISIBLE
        menuAnim = ValueAnimator.ofFloat(bulle.p + (bulle.scaleX - 1f), if (ouvrir) 1f else 0f).apply {
            duration = 360; interpolator = if (ouvrir) OvershootInterpolator(1.4f) else DecelerateInterpolator(2f)
            addUpdateListener { poserMenu(it.animatedValue as Float) }
            start()
        }
    }

    private fun poserMenu(v: Float) {
        val p = v.coerceIn(0f, 1f)
        val rebond = 1f + maxOf(0f, v - 1f) // le dépassement de l'interpolateur devient un léger gonflement
        bulle.p = p; bulle.scaleX = rebond; bulle.scaleY = rebond; icone.p = p; voile.alpha = p
        entrees.forEachIndexed { k, e ->
            val a = ((p - 0.25f - 0.1f * k) / 0.4f).coerceIn(0f, 1f)
            e.alpha = a; e.translationX = (a - 1f) * dp(14)
        }
        corps.setRenderEffect(if (p < 0.03f) null else RenderEffect.createBlurEffect(p * dp(12), p * dp(12), Shader.TileMode.CLAMP))
        if (p <= 0f) voile.visibility = View.GONE
    }

    private fun masquees(): Set<String> = prefs.getStringSet("masquees", emptySet()) ?: emptySet()

    private fun reglagesVue(): View {
        fun section(t: String, vararg vs: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = verre(); setPadding(dp(16), dp(14), dp(16), dp(12))
            addView(txt(18f, c(R.color.accent), bold = true).apply { text = t; setPadding(0, 0, 0, dp(6)) })
            vs.forEach { addView(it) }
            layoutParams = lp(-1, -2).apply { bottomMargin = dp(12) }
        }
        val teinte = ColorStateList.valueOf(c(R.color.accent))
        val rg = RadioGroup(this)
        val cur = prefs.getString("theme", "sombre")
        listOf("sombre" to "Sombre", "clair" to "Clair", "systeme" to "Système").forEach { (k, l) ->
            rg.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = l; tag = k; textSize = 16f; minHeight = dp(48)
                setTextColor(c(R.color.text)); buttonTintList = teinte; isChecked = k == cur
            })
        }
        rg.setOnCheckedChangeListener { g, id ->
            prefs.edit().putString("theme", g.findViewById<View>(id).tag as String).apply(); appliquerTheme()
        }
        tvDossier = txt(14f, c(R.color.text_dim), mono = true)
        majDossier()
        val cases = TILES.mapIndexed { i, (nom, _, _) ->
            CheckBox(this).apply {
                text = nom; textSize = 16f; minHeight = dp(48); setTextColor(c(R.color.text)); buttonTintList = teinte
                isChecked = nom !in masquees()
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putStringSet("masquees", if (on) masquees() - nom else masquees() + nom).apply()
                    tuiles[i].vue.visibility = if (on) View.VISIBLE else View.GONE
                }
            }
        }
        val boutons = LinearLayout(this).apply {
            addView(bouton("Choisir le dossier", true) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 2)
            }, lp(0, -2, 1f, dp(4)))
            addView(bouton("Par défaut", false) { prefs.edit().remove("dossier").apply(); majDossier() }, lp(0, -2, 1f, dp(4)))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(16))
            addView(section("Thème", rg))
            addView(section("Signaux affichés", *cases.toTypedArray()))
            addView(section("Dossier des .log", tvDossier, boutons))
            addView(bouton("À propos", false) { aPropos() }, lp(-1, -2))
        }
    }

    private fun aPropos() {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val d = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d, d, d, 0)
            addView(ImageView(context).apply { setImageResource(R.drawable.logo) }, lp(dp(56), dp(56)))
            addView(TextView(context).apply { text = "Auris2"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD })
            addView(TextView(context).apply {
                text = "Version ${pi.versionName} (${pi.longVersionCode})\n© 2026 Eliot Cornu. Tous droits réservés.\n\n" +
                    "Appli compagnon du module ESP32-C6 qui écoute le bus CAN OBD2 de la Toyota Auris 2 Hybride en lecture seule " +
                    "et reçoit les trames en Bluetooth BLE : décodage, tableau de bord, vue voiture, enregistrement .log candump.\n\n" +
                    "Android minimum : 12 (API 31).\nAucune donnée n'est envoyée sur Internet.\n\n" +
                    "Ne pas manipuler l'appli en conduisant."
            })
        }
        android.app.AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("OK", null).show()
    }

    private fun dossier(): Uri? = prefs.getString("dossier", null)?.let(Uri::parse)

    private fun majDossier() {
        tvDossier.text = dossier()?.let { "Dossier : " + Uri.decode(DocumentsContract.getTreeDocumentId(it)) } ?: "Par défaut : Download/Auris2"
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val u = data?.data
        if (req != 2 || res != RESULT_OK || u == null) return
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("dossier", u.toString()).apply()
        } catch (e: Exception) { tvDossier.text = "Dossier refusé : ${e.message}"; return }
        majDossier()
    }

    // ---------- Connexion ----------
    private fun perms() = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    private fun permsOk() = perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun basculeConnexion() {
        if (voulu) { deconnecter(); return }
        if (!permsOk()) { requestPermissions(perms(), 1); return }
        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (ad == null || !ad.isEnabled) { setEtat("Bluetooth désactivé"); return }
        voulu = true; btnCo.text = "Déconnecter"
        scan(ad)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (permsOk()) basculeConnexion() else setEtat("Permissions Bluetooth refusées")
    }

    private fun scan(ad: BluetoothAdapter) {
        setEtat("Recherche de Auris2...")
        val sc = ad.bluetoothLeScanner ?: run { setEtat("Scanner indisponible"); return }
        scanner = sc
        val cb = object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) {
                arretScan()
                ui.post { if (voulu) connecter(r.device) }
            }
            override fun onScanFailed(e: Int) { setEtat("Échec du scan ($e)"); ui.post { deconnecter(false) } }
        }
        scanCb = cb
        val filtres = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build(),
            ScanFilter.Builder().setDeviceName("Auris2").build())
        sc.startScan(filtres, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        ui.postDelayed({ if (scanCb === cb) { arretScan(); setEtat("Auris2 introuvable"); deconnecter(false) } }, 15000)
    }

    private fun arretScan() { scanCb?.let { try { scanner?.stopScan(it) } catch (_: Exception) {} }; scanCb = null }

    private fun connecter(d: BluetoothDevice) {
        setEtat("Connexion...")
        synchronized(model) { model.newSession() }
        gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    /** utilisateur = true : arrêt voulu ; sinon (échec) on remet juste l'état à zéro. */
    private fun deconnecter(msg: Boolean = true) {
        voulu = false; arretScan()
        ui.removeCallbacksAndMessages(reco)
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }; gatt = null
        btnCo.text = "Connecter"
        if (msg) setEtat("Déconnecté")
    }

    private val reco = Object()
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, s: Int) {
            if (g !== gatt) { g.close(); return }
            if (s == BluetoothProfile.STATE_CONNECTED) {
                setEtat("Connecté, négociation MTU...")
                if (!g.requestMtu(247)) g.discoverServices()
            } else { // perte du lien : fermeture propre puis nouvelle recherche si voulu
                g.close(); gatt = null
                setEtat("Lien perdu, reconnexion...")
                ui.postAtTime({
                    if (voulu) scan((getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter)
                }, reco, SystemClock.uptimeMillis() + 1500)
            }
        }

        // le CCCD n'est écrit qu'après le MTU (le firmware envoie jusqu'à 244 o)
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SERVICE)?.getCharacteristic(CHAR)
            val d = c?.getDescriptor(CCCD)
            if (c == null || d == null) { setEtat("Service Auris2 absent"); return }
            g.setCharacteristicNotification(c, true)
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            setEtat(if (status == BluetoothGatt.GATT_SUCCESS) "Connecté à Auris2" else "Activation notifications échouée ($status)")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) = recu(v)
        @Deprecated("API < 33") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) recu(c.value)
        }
    }

    private fun recu(v: ByteArray) {
        val p = parsePacket(v) ?: return
        val now = SystemClock.elapsedRealtime()
        synchronized(model) { model.onPacket(p, now) }
        rec?.let { r -> val t = System.currentTimeMillis(); for (f in p.frames) r.add(f, t) }
    }

    private fun setEtat(s: String) { etat = s }

    // ---------- Enregistrement ----------
    private fun basculeRec() {
        val r = rec
        if (r != null) {
            r.stop(); rec = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnRec.text = "Enregistrer"
        } else {
            rec = Recorder(this); (rec)!!.start()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnRec.text = "Arrêter"
        }
    }

    override fun onDestroy() { rec?.stop(); rec = null; deconnecter(false); super.onDestroy() }

    // ---------- Affichage (~5 Hz) ----------
    private fun f(v: Double?, fmt: String) = if (v == null) "--" else fmt.format(Locale.US, v)

    /** Nouvelle valeur d'une tuile : les nombres défilent de l'ancienne à la nouvelle, les états rebondissent, "--" est grisé. */
    private fun majTuile(t: Tuile, s: String) {
        if (s == t.txt) return
        val avant = t.txt.toFloatOrNull(); val apres = s.toFloatOrNull()
        t.txt = s
        t.tv.animate().alpha(if (s == "--") 0.4f else 1f).setDuration(200).start()
        t.anim?.cancel()
        if (avant != null && apres != null) {
            val fmt = "%." + s.substringAfter('.', "").length + "f"
            t.anim = ValueAnimator.ofFloat(t.num, apres).apply {
                duration = 180; interpolator = DecelerateInterpolator()
                addUpdateListener { t.num = it.animatedValue as Float; t.tv.text = fmt.format(Locale.US, t.num) }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { t.tv.text = t.txt } })
                start()
            }
        } else {
            t.num = apres ?: 0f; t.tv.text = s
            if (s != "--" && s.none { it.isDigit() }) { // changement d'état (ON/OFF, rapport, ouvrant...) : petit rebond, pas pour l'angle volant
                t.tv.pivotX = 0f; t.tv.scaleX = 1.18f; t.tv.scaleY = 1.18f
                t.tv.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
            }
        }
    }

    private fun rafraichir() {
        val now = SystemClock.elapsedRealtime()
        val vals: List<String>; val cpt: String; val tab: String
        synchronized(model) {
            model.tick(now)
            vals = TILES.map { it.third(model.values, now) }
            cpt = "Trames ${model.frames}  (%.0f/s)\nIDs distincts ${model.ids.size}\nPaquets BLE ${model.packets}  perdus ${model.lost}".format(Locale.US, model.fps)
            val sb = StringBuilder("ID        Nom                   Nb      Hz  Données\n")
            for (s in model.ids.values.sortedWith(compareBy({ it.ext }, { it.id }))) {
                sb.append("%-9s %-20s %6d %6.1f  %s\n".format(Locale.US, if (s.ext) "%08X".format(s.id) else "%03X".format(s.id),
                    nomId(s.id, s.ext).take(20), s.count, s.hz, hex(s.data)))
                if (isDiagId(s.id, s.ext)) diagTexte(s.id, s.data)?.let { sb.append("          > ").append(it).append('\n') }
            }
            tab = sb.toString()
        }
        tuiles.forEachIndexed { i, t -> majTuile(t, vals[i]) }
        tvEtat.text = etat; tvCpt.text = cpt; tvTab.text = tab
        val ok = etat.startsWith("Connecté à")
        (dot.background as GradientDrawable).setColor(c(if (ok) R.color.ok else if (voulu) R.color.warn else R.color.off))
        dot.contentDescription = "Connexion : $etat"
        tvRec.text = rec?.let { "REC ${it.dest}\nTrames enregistrées : ${it.n.get()}" } ?: (lastRec ?: "")
    }

    private var lastRec: String? = null

    /** Écriture candump bufferisée sur un thread dédié : dossier choisi (SAF) sinon MediaStore Download/Auris2. */
    inner class Recorder(private val ctx: Context) {
        val n = AtomicInteger()
        val nom = "auris2_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".log"
        @Volatile var dest = nom // emplacement réel, affiché pendant l'enregistrement
        private val q = LinkedBlockingQueue<Any>()
        private val cd = CandumpWriter()

        fun add(f: Frame, unixMs: Long) { q.put(cd.line(f, unixMs)) } // appelé depuis un seul thread BLE

        fun stop() { q.put(FIN) }

        fun start() = Thread {
            val cr = ctx.contentResolver
            var os: OutputStream? = null
            var pending: Uri? = null // fichier MediaStore à finaliser
            var prefixe = ""
            val tree = dossier()
            if (tree != null) try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                DocumentsContract.createDocument(cr, parent, "text/plain", nom)?.let { u ->
                    os = cr.openOutputStream(u); dest = Uri.decode(DocumentsContract.getDocumentId(u))
                }
            } catch (e: Exception) { os = null }
            if (os == null) {
                if (tree != null) prefixe = "Dossier choisi inaccessible, repli : "
                try {
                    pending = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, nom)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Auris2")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    })
                    os = pending?.let { cr.openOutputStream(it) }
                } catch (e: Exception) { os = null }
                dest = "Download/Auris2/$nom"
            }
            val out = os
            if (out == null) { dest = "ERREUR : fichier non créé"; ui.post { lastRec = "Erreur : fichier non créé" }; return@Thread }
            if (prefixe.isNotEmpty()) dest = prefixe + dest
            try {
                out.bufferedWriter().use { w ->
                    while (true) {
                        val x = q.take()
                        if (x === FIN) break
                        w.write(x as String); w.write("\n"); n.incrementAndGet()
                    }
                }
                pending?.let { cr.update(it, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
                ui.post { lastRec = "Fichier : $dest (${n.get()} trames)" }
            } catch (e: Exception) { ui.post { lastRec = "Erreur d'écriture : ${e.message}" } }
        }.start()
    }
}
