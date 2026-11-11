package fr.auris2.app

import java.util.Locale

// Logique pure (aucune dépendance Android) : parsing BLE, décodage CAN Toyota, candump.

class Frame(val ts: Long, val id: Int, val ext: Boolean, val rtr: Boolean, val data: ByteArray)

class Packet(val seq: Int, val frames: List<Frame>)

/** Paquet BLE : seq u16 LE puis trames (ts u32, id u32, dlc u8, données). Tronqué/invalide : on garde le début. */
fun parsePacket(b: ByteArray): Packet? {
    if (b.size < 2) return null
    fun u8(i: Int) = b[i].toInt() and 0xFF
    fun u32(i: Int) = (u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)).toLong() and 0xFFFFFFFFL
    val out = ArrayList<Frame>()
    var p = 2
    while (p + 9 <= b.size) {
        val ts = u32(p)
        val raw = u32(p + 4)
        val dlc = u8(p + 8)
        val rtr = raw and 0x40000000L != 0L
        val n = if (rtr) 0 else dlc
        if (dlc > 8) return Packet(u8(0) or (u8(1) shl 8), emptyList()) // corrompu : paquet ignoré, seq conservé
        if (p + 9 + n > b.size) break // dernière trame tronquée : ignorée
        out.add(Frame(ts, (raw and 0x1FFFFFFFL).toInt(), raw and 0x80000000L != 0L, rtr, b.copyOfRange(p + 9, p + 9 + n)))
        p += 9 + n
    }
    return Packet(u8(0) or (u8(1) shl 8), out)
}

// DBC Motorola : start = bit de poids fort, numérotation octet*8 + bit (7..0) (port de getBE du firmware)
fun getBE(d: ByteArray, start: Int, len: Int): Long {
    var v = 0L
    var bit = start
    for (i in 0 until len) {
        v = (v shl 1) or ((d[bit shr 3].toLong() shr (bit and 7)) and 1)
        bit = if ((bit and 7) == 0) bit + 15 else bit - 1
    }
    return v
}

fun getBEs(d: ByteArray, start: Int, len: Int): Long {
    val v = getBE(d, start, len)
    return if (v and (1L shl (len - 1)) != 0L) v or ((1L shl len) - 1).inv() else v
}

// ---- Noms (can_live.py) ----
val ECU = mapOf(0x7DF to "OBD fonctionnel", 0x7E0 to "Moteur", 0x7E2 to "Hybride KWP", 0x7D2 to "Hybride UDS",
    0x7E3 to "Batterie HV", 0x7B0 to "ABS", 0x7A0 to "EPS", 0x780 to "SRS")

val NOMS: Map<Int, String> = HashMap<Int, String>().apply {
    put(0x024, "KINEMATICS"); put(0x025, "STEER_ANGLE_SENSOR"); put(0x0AA, "WHEEL_SPEEDS")
    put(0x0B4, "SPEED"); put(0x127, "GEAR_PACKET_HYBRID"); put(0x1C4, "ENGINE_RPM"); put(0x245, "GAS_PEDAL_HYBRID")
    put(0x260, "STEER_TORQUE_SENSOR"); put(0x3B0, "AIR_CONDITIONER_2"); put(0x3BC, "GEAR_PACKET")
    put(0x224, "FREIN"); put(0x610, "COMPTEUR"); put(0x620, "OUVRANTS"); put(0x622, "FEUX"); put(0x381, "CLIM")
    put(0x230, "FREIN_2"); put(0x2A4, "VITESSE_2"); put(0x361, "ACCEL_2")
    for ((r, n) in ECU) if (r != 0x7DF) { put(r, "REQ $n"); put(r + 8, "REP $n") }
    put(0x7DF, "REQ " + ECU[0x7DF])
}

fun nomId(id: Int, ext: Boolean) = if (ext) "?" else NOMS[id] ?: "?"

class Diag(val ecu: Int, val svc: Int, val pid: Int?, val positive: Boolean, val nrc: Int?, val extra: ByteArray)

fun isDiagId(id: Int, ext: Boolean) = !ext && (id == 0x7DF || id in 0x700..0x7FF)

/** Port de decode_diag : null pour CF/FC/inconnu. */
fun decodeDiag(id: Int, data: ByteArray): Diag? {
    if (data.isEmpty()) return null
    fun u(i: Int) = data[i].toInt() and 0xFF
    val pci = u(0) shr 4
    val i = when {
        pci == 0 -> 1
        pci == 1 && data.size > 2 -> 2
        else -> return null
    }
    if (data.size <= i) return null
    val svc = u(i)
    val ecu = if ((id - 8) in ECU) id - 8 else id
    if (svc == 0x7F) return Diag(ecu, if (data.size > i + 1) u(i + 1) else 0, null, false, if (data.size > i + 2) u(i + 2) else 0, ByteArray(0))
    val req = if (svc >= 0x40) svc - 0x40 else svc
    val k = i + 1
    var pid: Int? = null
    var n = 0
    if (req in listOf(0x01, 0x02, 0x09, 0x21)) { if (data.size > k) { pid = u(k); n = 1 } }
    else if (req == 0x22) { if (data.size > k + 1) { pid = (u(k) shl 8) or u(k + 1); n = 2 } }
    return Diag(ecu, req, pid, svc >= 0x40, null, data.copyOfRange(minOf(k + n, data.size), data.size))
}

fun hex(d: ByteArray) = d.joinToString("") { "%02X".format(it) }

fun diagTexte(id: Int, data: ByteArray): String? {
    val d = decodeDiag(id, data) ?: return null
    val ecu = ECU[d.ecu] ?: "?"
    if (d.nrc != null) return "NEG %s svc %02X erreur %02X".format(ecu, d.svc, d.nrc)
    val sens = if (d.positive) "REP" else "REQ"
    val pid = d.pid?.let { " pid %X".format(it) } ?: ""
    return "$sens $ecu svc ${"%02X".format(d.svc)}$pid ${hex(d.extra)}".trimEnd()
}

// ---- Valeurs décodées (mesurées sur l'Auris, cf. captures/BASE_CAN.md) ----
class Val(val ttl: Long = 1000) { var v = 0.0; var t = -1L }

class Values {
    val speed = Val(); val dash = Val(); val rpm = Val(); val gear = Val(); val gas = Val()
    val brake = Val(); val brakeForce = Val(); val steer = Val(); val wheel = Val() // wheel.t partagé
    val lights = Val(3000); val hvac = Val(6000); val fan = Val(6000); val door = Val(2000); val trunk = Val(2000)
    val wheels = DoubleArray(4) // FL FR RL RR (ordre à vérifier)

    /** Valeur ou null si jamais reçue / périmée (> x.ttl : 1 s, feux 3 s, clim 6 s, ouvrants 2 s). */
    fun get(x: Val, now: Long): Double? = if (x.t < 0 || now - x.t > x.ttl) null else x.v

    /** Ouvrant : périmé > 2 s = fermé. */
    fun open(x: Val, now: Long) = x.t >= 0 && now - x.t <= x.ttl && x.v > 0

    fun gearLetter(now: Long): String? = get(gear, now)?.let {
        when (it.toInt()) { 0 -> "P"; 1 -> "R"; 2 -> "N"; 3 -> "D"; 4 -> "B"; else -> "?" }
    }

    fun lightsText(now: Long): String? = get(lights, now)?.let {
        when (it.toInt()) { 2 -> "Croisement"; 1 -> "Position"; else -> "Éteints/AUTO" }
    }

    /** Gauche = négatif : "12,0° G" / "30,5° D" / "0°". */
    fun steerText(now: Long): String? = get(steer, now)?.let {
        val a = "%.1f".format(Locale.FRANCE, Math.abs(it)) + "°"
        if (it < 0) "$a G" else if (it > 0) "$a D" else "0°"
    }

    fun decode(f: Frame, now: Long) {
        if (f.ext || f.rtr) return
        val d = f.data
        fun bit(i: Int, k: Int) = ((d[i].toInt() shr k) and 1).toDouble()
        // longueur contrôlée PAR signal : n = dernier octet requis + 1 (0x245 a un DLC de 5)
        fun need(n: Int) = d.size >= n
        when (f.id) {
            0x0B4 -> if (need(7)) set(speed, getBE(d, 47, 16) * 0.01, now)
            0x610 -> if (need(3)) set(dash, (d[2].toInt() and 0xFF).toDouble(), now)
            0x1C4 -> if (need(2)) set(rpm, getBEs(d, 7, 16) * 0.78125, now)
            0x127 -> if (need(6)) set(gear, getBE(d, 47, 4).toDouble(), now)
            0x245 -> if (need(3)) set(gas, (d[2].toInt() and 0xFF) * 0.5, now)
            0x224 -> {
                if (need(1)) set(brake, bit(0, 5), now)
                if (need(6)) set(brakeForce, getBE(d, 39, 16).toDouble(), now)
            }
            0x025 -> if (need(2)) set(steer, getBEs(d, 3, 12) * 1.5, now)
            0x0AA -> if (need(8)) {
                val s = intArrayOf(22, 6, 54, 38)
                for (i in 0..3) wheels[i] = getBE(d, s[i], 15) * 0.01 - 67.67
                wheel.t = now
            }
            0x622 -> if (need(3)) set(lights, if (bit(2, 7) > 0) 2.0 else if (bit(2, 6) > 0) 1.0 else 0.0, now)
            0x381 -> {
                if (need(2)) set(hvac, bit(1, 0).let { if ((d[1].toInt() and 0xFF) == 1) 1.0 else 0.0 }, now)
                if (need(3)) set(fan, (d[2].toInt() and 7).toDouble(), now)
            }
            0x620 -> if (need(6)) { set(door, bit(5, 5), now); set(trunk, bit(5, 1), now) }
        }
    }

    private fun set(x: Val, v: Double, now: Long) { x.v = v; x.t = now }
}

// ---- Statistiques ----
class IdStat(val id: Int, val ext: Boolean) {
    var count = 0L; var data = ByteArray(0); var hz = 0.0; internal var prev = 0L
}

class Model {
    val values = Values()
    val ids = HashMap<Long, IdStat>()
    var frames = 0L; var fps = 0.0; var packets = 0L; var lost = 0L
    private var lastSeq = -1
    private var prevFrames = 0L; private var prevTick = 0L

    fun reset() { ids.clear(); frames = 0; fps = 0.0; packets = 0; lost = 0; lastSeq = -1; prevFrames = 0; prevTick = 0 }

    /** Nouvelle connexion : on ne compte pas de trou de seq entre deux sessions. */
    fun newSession() { lastSeq = -1 }

    fun onPacket(p: Packet, now: Long) {
        packets++
        if (lastSeq >= 0) {
            val gap = (p.seq - lastSeq - 1) and 0xFFFF
            if (gap < 0x8000) lost += gap // grand écart = doublon/désordre : ignoré
        }
        lastSeq = p.seq
        for (f in p.frames) {
            frames++
            val s = ids.getOrPut(f.id.toLong() or (if (f.ext) 1L shl 32 else 0L)) { IdStat(f.id, f.ext) }
            s.count++; s.data = f.data
            values.decode(f, now)
        }
    }

    /** À appeler à chaque rafraîchissement UI : calcule trames/s et Hz par ID. */
    fun tick(now: Long) {
        val dt = now - prevTick
        if (prevTick > 0 && dt > 0) {
            fps = (frames - prevFrames) * 1000.0 / dt
            for (s in ids.values) { s.hz = (s.count - s.prev) * 1000.0 / dt }
        }
        for (s in ids.values) s.prev = s.count
        prevFrames = frames; prevTick = now
    }
}

// ---- Format candump ----
fun idHex(f: Frame) = if (f.ext) "%08X".format(f.id) else "%03X".format(f.id)

class CandumpWriter {
    private var base = 0L; private var ts0 = 0L; private var started = false

    /** unixMs = heure d'arrivée (utilisée seulement pour la 1re trame). */
    fun line(f: Frame, unixMs: Long): String {
        if (!started) { started = true; base = unixMs; ts0 = f.ts }
        val ms = base + ((f.ts - ts0) and 0xFFFFFFFFL)
        val data = if (f.rtr) "R" else hex(f.data)
        return "(%d.%06d) can0 %s#%s".format(ms / 1000, (ms % 1000) * 1000, idHex(f), data)
    }
}

// ---- Vue voiture : instantané des signaux + animation (logique pure) ----
/** État dessiné par CarView. NaN / -1 = signal périmé ou jamais reçu (élément grisé). */
class CarState {
    var speed = Double.NaN; var dash = Double.NaN; var rpm = Double.NaN; var gas = Double.NaN
    var brakeForce = Double.NaN; var steer = Double.NaN
    var gear = -1; var brake = -1; var lights = -1; var hvac = -1; var fan = -1; var door = -1; var trunk = -1
    var wheelsOk = false; val wheels = DoubleArray(4)
    /** Clignotants : -1 = non disponible (aucun ID visible sur l'OBD2, cf. BASE_CAN.md), 0 éteints, 1 gauche, 2 droite, 3 détresse. */
    var blink = -1
}

/** Copie sans allocation (appelée à chaque image). Clignotants : aucun signal connu, donc -1. */
fun Values.snapshot(s: CarState, now: Long) {
    fun ok(x: Val) = x.t >= 0 && now - x.t <= x.ttl
    fun d(x: Val) = if (ok(x)) x.v else Double.NaN
    fun i(x: Val) = if (ok(x)) x.v.toInt() else -1
    s.speed = d(speed); s.dash = d(dash); s.rpm = d(rpm); s.gas = d(gas); s.brakeForce = d(brakeForce); s.steer = d(steer)
    s.gear = i(gear); s.brake = i(brake); s.lights = i(lights); s.hvac = i(hvac); s.fan = i(fan); s.door = i(door); s.trunk = i(trunk)
    s.wheelsOk = ok(wheel); for (k in 0..3) s.wheels[k] = wheels[k]
    s.blink = -1
}

/**
 * Rotation affichée d'une roue (deg/s), proportionnelle à la vitesse, négative en marche arrière (rapport R = 1).
 * Le réel fait ~50 deg/s par km/h (pneu 205/55 R16, 1,99 m) : ralenti x5 et borné à 180 km/h,
 * sinon les 5 branches paraissent tourner à l'envers à 60 images/s.
 */
fun wheelSpin(kmh: Double, gear: Int): Double =
    if (kmh.isNaN()) 0.0 else Math.abs(kmh).coerceAtMost(180.0) * 10.0 * (if (gear == 1) -1 else 1)

/** Braquage des roues avant (deg, droite = positif). Butée volant mesurée 495 deg pour ~35 deg aux roues. */
// shortcut: démultiplication 495/35 supposée linéaire et non mesurée aux roues ; à calibrer si la vue doit être exacte.
fun roadWheelAngle(steer: Double): Double = if (steer.isNaN()) 0.0 else (steer * 35.0 / 495.0).coerceIn(-35.0, 35.0)

/** Mode démo : scénario simulé de 40 s en boucle (t en secondes). Seul endroit où les clignotants s'animent. */
fun demoCar(s: CarState, t: Double) {
    val u = ((t % 40.0) + 40.0) % 40.0
    fun entre(a: Double, b: Double) = u >= a && u < b
    val v = when {
        u < 4 -> 0.0
        u < 8 -> 6 * Math.sin(Math.PI * (u - 4) / 4)             // marche arrière
        u < 9 -> 0.0
        u < 18 -> 70 * (1 - Math.cos(Math.PI * (u - 9) / 9)) / 2 // accélération
        u < 24 -> 70.0
        u < 32 -> 70 * (32 - u) / 8                              // freinage
        else -> 0.0
    }
    s.speed = v; s.dash = Math.round(v * 1.04).toDouble()
    s.wheelsOk = true; for (k in 0..3) s.wheels[k] = v
    s.gear = when { u < 4 -> 0; u < 8 -> 1; u < 9 -> 2; u < 24 -> 3; u < 34 -> 4; else -> 0 }
    s.gas = when { entre(4.0, 7.0) -> 12.0; entre(9.0, 18.0) -> 62.0; entre(18.0, 24.0) -> 16.0; else -> 0.0 }
    s.rpm = if (entre(11.0, 18.0)) 1400 + v * 22 else 0.0      // thermique seulement à l'accélération
    s.brake = if (entre(3.0, 4.2) || entre(7.4, 9.2) || u >= 27) 1 else 0
    s.brakeForce = if (s.brake == 1) (if (entre(27.0, 32.0)) 900.0 else 350.0) else 0.0
    s.steer = when { u < 4 -> 0.0; u < 8 -> -300 * Math.sin(Math.PI * (u - 4) / 4); u < 32 -> 120 * Math.sin(u * 0.9); else -> 0.0 }
    s.lights = when { u < 12 -> 0; u < 34 -> 2; else -> 1 }
    s.hvac = if (u < 36) 1 else 0
    s.fan = if (u < 36) 1 + (u / 6).toInt() else 0
    s.door = if (u < 2.5) 1 else 0
    s.trunk = if (entre(0.5, 3.0)) 1 else 0
    s.blink = when { entre(19.0, 23.0) -> 1; entre(28.0, 31.5) -> 2; entre(34.0, 38.5) -> 3; else -> 0 }
}
