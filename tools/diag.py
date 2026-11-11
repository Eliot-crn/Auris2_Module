#!/usr/bin/env python3
"""Client diagnostic LECTURE SEULE via Auris2_Diag (pont USB-serie <-> CAN).
Sous-commandes : pids | vin | dtc | scan | raw <id> <hex>. Voir Auris2_Diag.ino (meme liste blanche).
Voiture a l'arret en P. Aucun effacement, aucune ecriture."""
import argparse, queue, sys, threading, time

from can_live import parse_line

SERVICES = (0x01, 0x03, 0x09, 0x21, 0x22)


def autorise(i, d, vu=()):
    """IDENTIQUE a autorise() de Auris2_Diag.ino. Retourne (ok, raison)."""
    if not (i == 0x7DF or 0x700 <= i <= 0x7F7):
        return False, "id hors liste"
    if len(d) != 8:
        return False, "DLC"
    if i in vu:
        return False, "id utilise sur le bus"
    if d[0] == 0x30:
        return (False, "FC vers 7DF") if i == 0x7DF else (True, "")
    n, s = d[0], d[1]
    if not 1 <= n <= 7:
        return False, "PCI non autorise"
    if s not in SERVICES:
        return False, "service non autorise"
    if (s == 0x03 and n != 1) or (s == 0x22 and n != 3) or (s in (0x01, 0x09, 0x21) and n < 2):
        return False, "longueur"
    return True, ""


class Refus(Exception):
    pass


class Bridge:
    def __init__(self, port, logfile=None):
        import serial  # import tardif : le selftest n'en a pas besoin
        self.ser = serial.Serial(port, 115200, timeout=0.2)
        self.frames, self.replies = queue.Queue(), queue.Queue()
        self.vu, self.last_tx = set(), 0.0
        self.log = open(logfile, "a") if logfile else None
        if self.log:
            self.log.write("# diag.py ; lignes precedees de '# TX' = envoyees, les autres = recues\n")
        threading.Thread(target=self._read, daemon=True).start()

    def _logf(self, sens, i, d):
        if self.log:
            self.log.write(("# TX\n" if sens == "TX" else "") + "(%.3f) can0 %03X#%s\n" % (time.time(), i, d.hex().upper()))
            self.log.flush()

    def _read(self):
        buf = b""
        while True:
            buf += self.ser.read(256)
            while b"\n" in buf:
                raw, buf = buf.split(b"\n", 1)
                line = raw.decode("ascii", "replace").strip()
                if line == "OK" or line.startswith("REFUS"):
                    self.replies.put(line)
                    continue
                try:
                    p = parse_line(line)
                except ValueError:
                    continue
                if p and not p[2]:
                    self.vu.add(p[1])
                    self.frames.put((p[1], p[3]))
                    self._logf("RX", p[1], p[3])

    def send(self, i, d):
        ok, why = autorise(i, d, self.vu)  # barriere PC ; le firmware re-verifie
        if not ok:
            raise Refus("PC: " + why)
        time.sleep(max(0, self.last_tx + 0.06 - time.time()))  # <= ~16 TX/s (firmware : 20/s)
        self.last_tx = time.time()
        while not self.replies.empty():
            self.replies.get()
        self._logf("TX", i, d)
        self.ser.write(b"TX %03X %s\n" % (i, d.hex().upper().encode()))
        try:
            r = self.replies.get(timeout=1)
        except queue.Empty:
            raise Refus("pas de reponse du module (bon sketch ?)")
        if r != "OK":
            raise Refus(r)

    def recv(self, timeout):
        try:
            return self.frames.get(timeout=max(0.0, timeout))
        except queue.Empty:
            return None

    def flush(self):
        while not self.frames.empty():
            self.frames.get()


class Reasm:
    """Reassemblage ISO-TP : feed(d) -> payload complet ou None ; need_fc vrai apres un First Frame."""
    def __init__(self):
        self.buf, self.total, self.seq, self.need_fc = b"", 0, 0, False

    def feed(self, d):
        t = d[0] >> 4
        if t == 0:
            return bytes(d[1:1 + (d[0] & 15)])
        if t == 1:
            self.total, self.buf, self.seq, self.need_fc = ((d[0] & 15) << 8) | d[1], bytes(d[2:8]), 1, True
        elif t == 2 and self.total and (d[0] & 15) == self.seq:
            self.buf += bytes(d[1:8])
            self.seq = (self.seq + 1) & 15
        else:
            return None
        if self.total and len(self.buf) >= self.total:
            return self.buf[:self.total]
        return None


def request(br, tx_id, payload, timeout=1.0, pending=5.0):
    """Requete ISO-TP -> (id_reponse, payload) ou None. Reponse sur tx_id+8 (0x7E8..0x7EF pour 7DF)."""
    rx = range(0x7E8, 0x7F0) if tx_id == 0x7DF else (tx_id + 8,)
    br.flush()
    br.send(tx_id, bytes([len(payload)]) + payload + bytes(7 - len(payload)))
    end, rid, ra = time.time() + timeout, None, Reasm()
    while True:
        f = br.recv(end - time.time())
        if f is None:
            return None
        i, d = f
        if i not in rx or (rid is not None and i != rid) or not d:
            continue
        r = ra.feed(d)
        if ra.need_fc:
            ra.need_fc, rid = False, i
            br.send(i - 8, bytes([0x30]) + bytes(7))
            end = max(end, time.time() + timeout)
        if r is None:
            continue
        if r[:1] == b"\x7f" and len(r) >= 3 and r[1] == payload[0] and r[2] == 0x78:
            end = time.time() + pending
            continue
        if r[:1] == bytes([payload[0] + 0x40]) or (r[:1] == b"\x7f" and len(r) >= 2 and r[1] == payload[0]):
            return i, r


# --- decodage PID standards (mode 01) ---
def _u16(d): return d[0] * 256 + d[1]
PIDS = {
    0x04: ("charge moteur", lambda d: d[0] * 100 / 255, "%"),
    0x05: ("temp. liquide refroidissement", lambda d: d[0] - 40, "degC"),
    0x06: ("correction carburant CT B1", lambda d: (d[0] - 128) * 100 / 128, "%"),
    0x07: ("correction carburant LT B1", lambda d: (d[0] - 128) * 100 / 128, "%"),
    0x0B: ("pression admission", lambda d: d[0], "kPa"),
    0x0C: ("regime", lambda d: _u16(d) / 4, "tr/min"),
    0x0D: ("vitesse", lambda d: d[0], "km/h"),
    0x0E: ("avance allumage", lambda d: d[0] / 2 - 64, "deg"),
    0x0F: ("temp. air admission", lambda d: d[0] - 40, "degC"),
    0x10: ("debit d'air", lambda d: _u16(d) / 100, "g/s"),
    0x11: ("papillon", lambda d: d[0] * 100 / 255, "%"),
    0x1F: ("temps depuis demarrage", lambda d: _u16(d), "s"),
    0x2F: ("niveau carburant", lambda d: d[0] * 100 / 255, "%"),
    0x33: ("pression baro", lambda d: d[0], "kPa"),
    0x42: ("tension module", lambda d: _u16(d) / 1000, "V"),
    0x46: ("temp. ambiante", lambda d: d[0] - 40, "degC"),
    0x5B: ("batterie hybride restante", lambda d: d[0] * 100 / 255, "%"),
    0x5C: ("temp. huile", lambda d: d[0] - 40, "degC"),
}


def decode_pid(pid, data):
    if pid not in PIDS or len(data) < (2 if pid in (0x0C, 0x10, 0x1F, 0x42) else 1):
        return ""
    n, f, u = PIDS[pid]
    return "%s = %.2f %s" % (n, f(data), u)


def supported(base, data):
    """Bitmap 4 octets du PID base -> liste des PID supportes (base+1..base+32)."""
    v = int.from_bytes(data[:4], "big")
    return [base + 1 + k for k in range(32) if v >> (31 - k) & 1]


def decode_dtc(r):
    b = r[1:]
    if len(b) % 2:
        b = b[1:]  # octet de comptage
    out = []
    for k in range(0, len(b) - 1, 2):
        v = b[k] * 256 + b[k + 1]
        if v:
            out.append("%s%d%03X" % ("PCBU"[v >> 14], v >> 12 & 3, v & 0xFFF))
    return out


# --- sous-commandes ---
def cmd_pids(br):
    sup, base = [], 0
    while base <= 0xC0:
        r = request(br, 0x7E0, bytes([1, base]))
        if not r or r[1][0] != 0x41 or len(r[1]) < 6:
            print("PID %02X: pas de reponse positive (%s)" % (base, r[1].hex().upper() if r else "timeout"))
            break
        s = supported(base, r[1][2:6])
        print("supportes %02X: %s" % (base, " ".join("%02X" % p for p in s)))
        sup += s
        if base + 0x20 not in s:
            break
        base += 0x20
    for p in sup:
        if p % 0x20 == 0:
            continue
        r = request(br, 0x7E0, bytes([1, p]))
        print("%02X  %-34s %s" % (p, r[1].hex().upper() if r else "timeout", decode_pid(p, r[1][2:]) if r and r[1][0] == 0x41 else ""))


def cmd_vin(br):
    r = request(br, 0x7DF, bytes([9, 2]))
    print(r[1][3:].decode("ascii", "replace") if r and r[1][:2] == b"\x49\x02" else r)


def cmd_dtc(br):
    r = request(br, 0x7DF, bytes([3]))
    if not r or r[1][0] != 0x43:
        print(r)
        return
    print("ECU %03X: %s" % (r[0], " ".join(decode_dtc(r[1])) or "aucun code"))


def cmd_scan(br):
    skip = {0x7DF}
    for i in range(0x700, 0x7F8):
        if i in skip or i in br.vu:
            continue
        for pl in (bytes([0x21, 1]), bytes([0x22, 0xF1, 0x90])):
            try:
                r = request(br, i, pl, timeout=0.15)
            except Refus as e:
                print("%03X: %s" % (i, e))
                break
            time.sleep(0.05)
            if r:
                print("ECU %03X -> %03X : %s" % (i, r[0], r[1].hex().upper()))
                skip.add(i + 8)
                break


# --- selftest hors-ligne ---
class Fake:
    """Pont simule : script(id, data) -> liste de trames (id, data) a recevoir."""
    def __init__(self, script):
        self.script, self.q, self.sent, self.vu = script, [], [], set()

    def flush(self): self.q.clear()

    def send(self, i, d):
        ok, why = autorise(i, d)
        if not ok:
            raise Refus(why)
        self.sent.append((i, d))
        self.q += self.script(i, d)

    def recv(self, timeout):
        return self.q.pop(0) if self.q else None


def selftest():
    cas = [
        (0x7E0, "0201050000000000", True), (0x7DF, "0201050000000000", True), (0x7E0, "0322F19000000000", True),
        (0x7E0, "0122000000000000", False), (0x7E0, "0103000000000000", True), (0x7E0, "0203000000000000", False),
        (0x7E0, "022E010000000000", False), (0x7E0, "1003000000000000", False), (0x7E0, "0210030000000000", False),
        (0x7E0, "0114000000000000", False), (0x7E0, "0227000000000000", False), (0x7E0, "0211010000000000", False),
        (0x6C0, "0201050000000000", False), (0x7F8, "0201050000000000", False), (0x7E0, "3000000000000000", True),
        (0x7DF, "3000000000000000", False), (0x7E0, "21FF000000000000", False), (0x7E0, "0001050000000000", False),
        (0x7E0, "0801050000000000", False), (0x7E0, "0231010000000000", False), (0x7E0, "0285000000000000", False),
    ]
    for i, h, att in cas:
        assert autorise(i, bytes.fromhex(h))[0] == att, (hex(i), h)
    assert not autorise(0x7E1, bytes.fromhex("0201050000000000"), {0x7E1})[0]
    assert not autorise(0x7E0, bytes(7))[0]
    # reassemblage FF + 2 CF (VIN : 49 02 01 + 17 car = 20 octets)
    pl = b"\x49\x02\x01" + b"VNKKTUD320A123456"
    ra = Reasm()
    assert ra.feed(bytes([0x10, 20]) + pl[:6]) is None and ra.need_fc
    assert ra.feed(bytes([0x21]) + pl[6:13]) is None
    assert ra.feed(bytes([0x22]) + pl[13:] + b"\0") == pl
    # request() complet avec pont simule : FF -> FC -> CF
    def script(i, d):
        if d[0] == 0x30:
            return [(0x7E8, bytes([0x21]) + pl[6:13]), (0x7E8, bytes([0x22]) + pl[13:] + b"\0")]
        return [(0x7E8, bytes([0x10, 20]) + pl[:6])] if i == 0x7DF else []
    fk = Fake(script)
    r = request(fk, 0x7DF, bytes([9, 2]))
    assert r == (0x7E8, pl) and fk.sent[-1] == (0x7E0, bytes([0x30]) + bytes(7)), (r, fk.sent)
    # 7F 01 78 (pending) puis reponse positive
    fk = Fake(lambda i, d: [(0x7E8, bytes([3, 0x7F, 1, 0x78, 0, 0, 0, 0])), (0x7E8, bytes([4, 0x41, 0x0C, 0x1A, 0xF8, 0, 0, 0]))])
    assert request(fk, 0x7E0, bytes([1, 0x0C]))[1] == bytes([0x41, 0x0C, 0x1A, 0xF8])
    # decodage
    assert decode_pid(0x0C, bytes([0x1A, 0xF8])) == "regime = 1726.00 tr/min"
    assert decode_pid(0x05, bytes([0x7B])) == "temp. liquide refroidissement = 83.00 degC"
    assert supported(0, bytes([0xBE, 0x1F, 0xA8, 0x13]))[:4] == [1, 3, 4, 5]
    assert decode_dtc(bytes([0x43, 0x01, 0x01, 0x33])) == ["P0133"]
    assert decode_dtc(bytes([0x43, 0x00])) == []
    print("selftest OK")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("cmd", nargs="?", choices=["pids", "vin", "dtc", "scan", "raw"])
    ap.add_argument("args", nargs="*", help="raw: <id hex> <octets hex service+params, ex 0105>")
    ap.add_argument("--port", default="/dev/ttyACM0")
    ap.add_argument("--log")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if not a.cmd:
        ap.error("sous-commande requise")
    br = Bridge(a.port, a.log)
    time.sleep(1.5)  # laisse arriver les trames 7xx deja presentes (anti-collision cote PC)
    try:
        if a.cmd == "raw":
            r = request(br, int(a.args[0], 16), bytes.fromhex(a.args[1]))
            print(r[1].hex().upper() if r else "timeout")
        else:
            globals()["cmd_" + a.cmd](br)
    except Refus as e:
        sys.exit("REFUS: %s" % e)


if __name__ == "__main__":
    main()
