package fr.auris2.app

import org.junit.Assert.*
import org.junit.Test

class Auris2LogicTest {
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

    @Test fun getBE_getBEs() {
        assertEquals(0x1234L, getBE(b(0x12, 0x34, 0, 0, 0, 0, 0, 0), 7, 16))
        assertEquals(-1L, getBEs(b(0x0F, 0xFF, 0, 0, 0, 0, 0, 0), 3, 12))
    }

    @Test fun parsePaquetDeuxTrames() {
        // seq=0x0102 ; trame1 std 0x7E8 ts=1000 dlc4 ; trame2 étendue 0x18DAF110 ts=1500 dlc2
        val pkt = b(0x02, 0x01,
            0xE8, 0x03, 0, 0, 0xE8, 0x07, 0, 0, 4, 0x04, 0x41, 0x05, 0x7B,
            0xDC, 0x05, 0, 0, 0x10, 0xF1, 0xDA, 0x98, 2, 0xAA, 0xBB)
        val p = parsePacket(pkt)!!
        assertEquals(0x0102, p.seq)
        assertEquals(2, p.frames.size)
        val a = p.frames[0]; val e = p.frames[1]
        assertEquals(1000L, a.ts); assertEquals(0x7E8, a.id); assertFalse(a.ext); assertEquals("0441057B", hex(a.data))
        assertTrue(e.ext); assertEquals(0x18DAF110, e.id); assertEquals(1500L, e.ts); assertEquals("AABB", hex(e.data))
    }

    @Test fun paquetTronqueOuCorrompu() {
        val ok = b(0xE8, 0x03, 0, 0, 0xE8, 0x07, 0, 0, 2, 0x11, 0x22)
        val tronque = b(1, 0) + ok + b(0xDC, 0x05, 0, 0, 0x10, 0, 0, 0, 8, 1, 2) // 2e trame incomplète
        val p = parsePacket(tronque)!!
        assertEquals(1, p.frames.size); assertEquals("1122", hex(p.frames[0].data))
        val corrompu = parsePacket(b(5, 0) + ok + b(0, 0, 0, 0, 0x10, 0, 0, 0, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0))!!
        assertEquals(5, corrompu.seq); assertEquals(0, corrompu.frames.size)
        assertNull(parsePacket(b(1)))
    }

    @Test fun candump() {
        val w = CandumpWriter()
        val a = Frame(1000, 0x7E8, false, false, b(4, 0x41, 5, 0x7B))
        val e = Frame(1500, 0x18DAF110, true, false, b(0xAA, 0xBB))
        val r = Frame(1501, 0x123, false, true, ByteArray(0))
        assertEquals("(1700000000.123000) can0 7E8#0441057B", w.line(a, 1700000000123L))
        assertEquals("(1700000000.623000) can0 18DAF110#AABB", w.line(e, 0))
        assertEquals("(1700000000.624000) can0 123#R", w.line(r, 0))
    }

    @Test fun pertesEtDiag() {
        val m = Model()
        m.onPacket(Packet(0xFFFF, emptyList()), 0); m.onPacket(Packet(2, emptyList()), 0)
        assertEquals(2L, m.lost) // 0 et 1 manquants (wrap)
        assertEquals("REP Moteur svc 01 pid 5 7B", diagTexte(0x7E8, b(4, 0x41, 5, 0x7B)))
    }

    private fun dec(id: Int, vararg x: Int): Values { val v = Values(); v.decode(Frame(0, id, false, false, b(*x)), 100); return v }

    @Test fun signauxAuris() {
        assertEquals(100.0, dec(0x245, 0, 0, 0xC8, 0, 0x4C).gas.v, 1e-9) // DLC 5
        val fr = dec(0x224, 0x20, 0, 0, 0, 0x06, 0x15, 0, 0x08)
        assertEquals(1.0, fr.brake.v, 0.0); assertEquals(0x0615.toDouble(), fr.brakeForce.v, 0.0)
        assertEquals(7.61, dec(0x0B4, 0, 0, 0, 0, 0x19, 0x02, 0xF9, 0xD0).speed.v, 1e-9)
        val st = dec(0x025, 0x0E, 0xB8, 0, 2, 0xD0, 1, 0, 0xC6)
        assertEquals(-492.0, st.steer.v, 0.0); assertEquals("492,0° G", st.steerText(100))
        assertEquals(50.0, dec(0x610, 0, 0, 50, 0, 0, 0, 0, 0).dash.v, 0.0)
        assertTrue(dec(0x1C4, 0x05, 0x00, 0, 0, 0, 0, 0, 0).rpm.v > 0)
        assertEquals("D", dec(0x127, 0, 0, 0, 0, 0, 0x30, 0, 0).gearLetter(100))
    }

    @Test fun feuxClimOuvrants() {
        assertEquals("Croisement", dec(0x622, 0x12, 0, 0x88, 0x70, 0, 0, 0, 0).lightsText(100))
        assertEquals("Position", dec(0x622, 0x12, 0, 0x48, 0x10, 0, 0, 0, 0).lightsText(100))
        assertEquals("Éteints/AUTO", dec(0x622, 0x12, 0, 0, 0, 0, 0, 0, 0).lightsText(100))
        val c = dec(0x381, 0, 1, 7, 0, 0x80, 0, 0, 0)
        assertEquals(1.0, c.hvac.v, 0.0); assertEquals(7.0, c.fan.v, 0.0)
        val p = dec(0x620, 0x10, 0, 0, 0x2D, 0xB0, 0x20, 0, 0x50)
        assertTrue(p.open(p.door, 100)); assertFalse(p.open(p.trunk, 100))
        val t = dec(0x620, 0x10, 0, 0, 0x2D, 0xB0, 0x02, 0, 0x50)
        assertTrue(t.open(t.trunk, 100)); assertFalse(t.open(t.door, 100))
        assertFalse(p.open(p.door, 2500)) // périmé > 2 s = fermé
    }

    @Test fun vueVoiture() {
        // roues : proportionnel, borné, inversé en R, 0 si périmé
        assertEquals(500.0, wheelSpin(50.0, 3), 1e-9)
        assertEquals(2 * wheelSpin(30.0, 3), wheelSpin(60.0, 3), 1e-9)
        assertEquals(-50.0, wheelSpin(5.0, 1), 1e-9)
        assertEquals(1800.0, wheelSpin(250.0, 3), 1e-9)
        assertEquals(0.0, wheelSpin(Double.NaN, 3), 0.0)
        // braquage : gauche négatif, butée 495 deg -> 35 deg, borné
        assertEquals(-35.0, roadWheelAngle(-495.0), 1e-9)
        assertEquals(35.0, roadWheelAngle(3000.0), 1e-9)
        assertEquals(0.0, roadWheelAngle(Double.NaN), 0.0)
        // instantané : périmé = NaN / -1, clignotants toujours non disponibles
        val v = Values(); val s = CarState()
        v.snapshot(s, 0)
        assertTrue(s.speed.isNaN()); assertEquals(-1, s.gear); assertEquals(-1, s.door); assertFalse(s.wheelsOk)
        v.decode(Frame(0, 0x127, false, false, b(0, 0, 0, 0, 0, 0x10)), 100)
        v.decode(Frame(0, 0x620, false, false, b(0, 0, 0, 0, 0, 0x20)), 100)
        v.snapshot(s, 200)
        assertEquals(1, s.gear); assertEquals(1, s.door); assertEquals(0, s.trunk); assertEquals(-1, s.blink)
        v.snapshot(s, 5000)
        assertEquals(-1, s.gear); assertEquals(-1, s.door)
        // démo : marche arrière à 6 s, porte ouverte au début, boucle de 40 s
        demoCar(s, 6.0)
        assertEquals(1, s.gear); assertTrue(s.speed > 0); assertTrue(wheelSpin(s.speed, s.gear) < 0)
        demoCar(s, 41.0)
        assertEquals(1, s.door); assertEquals(0.0, s.speed, 0.0)
    }
}
