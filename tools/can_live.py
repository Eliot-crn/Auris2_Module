#!/usr/bin/env python3
"""Tableau de bord CAN en direct (lecture seule) pour Auris2_Sniffer.
Lit le format candump du port série (ou d'un fichier) et résume les ID."""
import argparse, json, re, shutil, sys, time

# Noms : commaai/opendbc _toyota_2017.dbc, hérité_prius3 / à_valider sur Auris
NOMS = {0x024: "KINEMATICS", 0x025: "STEER_ANGLE_SENSOR", 0x0A6: "BRAKE", 0x0AA: "WHEEL_SPEEDS",
        0x0B4: "SPEED", 0x127: "GEAR_PACKET_HYBRID", 0x1C4: "ENGINE_RPM", 0x245: "GAS_PEDAL_HYBRID",
        0x260: "STEER_TORQUE_SENSOR", 0x3B0: "AIR_CONDITIONER_2", 0x3BC: "GEAR_PACKET"}
# ECU diag (source CarCoding, à vérifier) : requête -> nom ; réponse = requête + 8
ECU = {0x7DF: "OBD fonctionnel", 0x7E0: "Moteur", 0x7E2: "Hybride KWP", 0x7D2: "Hybride UDS",
       0x7E3: "Batterie HV", 0x7B0: "ABS", 0x7A0: "EPS", 0x780: "SRS"}
for _r, _n in list(ECU.items()):
    if _r != 0x7DF:
        NOMS[_r] = "REQ " + _n
        NOMS[_r + 8] = "REP " + _n
NOMS[0x7DF] = "REQ " + ECU[0x7DF]

LIGNE = re.compile(r"^\(\s*(\d+)\.(\d+)\)\s+\S+\s+([0-9A-Fa-f]+)#([0-9A-Fa-f]*|R)$")


def parse_line(line):
    """None si vide ou '#'; (t, id, ext, data) sinon; ValueError si corrompue."""
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    m = LIGNE.match(line)
    if not m or len(m.group(3)) not in (3, 8) or (len(m.group(4)) % 2 and m.group(4) != "R"):
        raise ValueError(line)
    d = m.group(4)
    data = b"" if d == "R" else bytes.fromhex(d)
    if len(data) > 8:
        raise ValueError(line)
    return float(m.group(1) + "." + m.group(2)), int(m.group(3), 16), len(m.group(3)) == 8, data


def decode_diag(id_, data):
    """Retourne (ecu_requete, service_requete, pid, positive, nrc) ou None (CF/FC/inconnu)."""
    if not data:
        return None
    pci = data[0] >> 4
    if pci == 0:
        i = 1
    elif pci == 1 and len(data) > 2:
        i = 2
    else:
        return None
    if len(data) <= i:
        return None
    svc = data[i]
    rep = id_ - 8 in ECU
    ecu = id_ - 8 if rep else id_
    if svc == 0x7F:
        return ecu, data[i + 1] if len(data) > i + 1 else 0, None, False, data[i + 2] if len(data) > i + 2 else 0
    req = svc - 0x40 if svc >= 0x40 else svc
    k = i + 1
    if req in (0x01, 0x02, 0x09, 0x21):
        pid, n = (data[k], 1) if len(data) > k else (None, 0)
    elif req == 0x22:
        pid, n = ((data[k] << 8) | data[k + 1], 2) if len(data) > k + 1 else (None, 0)
    else:
        pid, n = None, 0
    return ecu, req, pid, svc >= 0x40, data[k + n:]


class Stats:
    def __init__(self):
        self.ids, self.pids, self.total, self.invalid, self.statut = {}, {}, 0, 0, ""
        self.t0 = None

    def add_line(self, line):
        if line.strip().startswith("#"):
            self.statut = line.strip()
        try:
            p = parse_line(line)
        except ValueError:
            self.invalid += 1
            return
        if p:
            self.add(*p)

    def add(self, t, id_, ext, data):
        self.total += 1
        s = self.ids.get((id_, ext))
        if s is None:
            s = self.ids[(id_, ext)] = dict(count=0, first=t, last=t, dlc=set(), data=data, mask=bytearray(8),
                                           mn=list(data), mx=list(data), vals=[set() for _ in range(8)])
        else:
            for i, b in enumerate(data):
                if i < len(s["data"]) and s["data"][i] != b:
                    s["mask"][i] = 1
                if i < len(s["mn"]):
                    s["mn"][i], s["mx"][i] = min(s["mn"][i], b), max(s["mx"][i], b)
                else:
                    s["mn"].append(b); s["mx"].append(b)
        s["count"] += 1
        s["last"] = t
        s["data"] = data
        s["dlc"].add(len(data))
        for i, b in enumerate(data):
            if len(s["vals"][i]) < 256:
                s["vals"][i].add(b)
        if not ext and (id_ == 0x7DF or 0x700 <= id_ <= 0x7FF):
            self.add_pid(id_, data)

    def add_pid(self, id_, data):
        r = decode_diag(id_, data)
        if not r:
            return
        ecu, svc, pid, pos, extra = r
        e = self.pids.setdefault((ecu, svc, pid), dict(req=0, rep=0, last="", neg=0, nrc=None))
        if isinstance(extra, int):  # réponse négative (extra = NRC)
            e["neg"] += 1
            e["nrc"] = extra
        elif pos:
            e["rep"] += 1
            e["last"] = extra.hex().upper()
        else:
            e["req"] += 1

    def rows(self):
        out = []
        for (id_, ext), s in sorted(self.ids.items()):
            dt = s["last"] - s["first"]
            per = dt / (s["count"] - 1) * 1000 if s["count"] > 1 and dt > 0 else 0
            nom = NOMS.get(id_, "?") if not ext else "?"
            out.append((id_, ext, nom, s, per, 1000 / per if per else 0))
        return out

    def table(self):
        l = ["ID        Nom                  Nb      Hz     DLC  Données(dernières)    Changent"]
        for id_, ext, nom, s, per, hz in self.rows():
            ch = "".join("*" if s["mask"][i] else "." for i in range(len(s["data"])))
            l.append("%-9s %-20s %-7d %-6.1f %-4s %-21s %s" % (
                ("%08X" if ext else "%03X") % id_, nom, s["count"], hz,
                ",".join(map(str, sorted(s["dlc"]))), s["data"].hex().upper(), ch))
        return l

    def pid_table(self):
        if not self.pids:
            return []
        l = ["", "PID diag : ECU(req) svc pid    req rep neg(nrc) dernière réponse"]
        for (ecu, svc, pid), e in sorted(self.pids.items(), key=lambda kv: (kv[0][0], kv[0][1], kv[0][2] or 0)):
            l.append("%03X %-12s %02X %-6s %-4d %-4d %d(%s) %s" % (
                ecu, ECU.get(ecu, "?"), svc, "-" if pid is None else "%X" % pid, e["req"], e["rep"], e["neg"],
                "-" if e["nrc"] is None else "%02X" % e["nrc"], e["last"]))
        return l

    def head(self):
        return "%s\ntotal=%d  ID=%d  lignes invalides=%d" % (self.statut or "# (pas de statut ESP32)", self.total,
                                                          len(self.ids), self.invalid)

    def variables(self):
        l = ["", "Octets variables (broadcast) : octet min..max distincts"]
        for id_, ext, nom, s, _, _ in self.rows():
            if id_ == 0x7DF or 0x700 <= id_ <= 0x7FF:
                continue
            v = ["b%d %02X..%02X n=%d" % (i, s["mn"][i], s["mx"][i], len(s["vals"][i]))
                 for i in range(len(s["data"])) if s["mask"][i]]
            if v:
                l.append("%03X %-20s %s" % (id_, nom, " | ".join(v)))
        return l

    def summary(self):
        return "\n".join([self.head()] + self.table() + self.pid_table() + self.variables())

    def as_json(self):
        return dict(total=self.total, invalid=self.invalid, statut=self.statut, ids=[
            dict(id=id_, ext=ext, nom=nom, count=s["count"], hz=round(hz, 2), dlc=sorted(s["dlc"]),
                 data=s["data"].hex(), changed=[i for i in range(8) if s["mask"][i]],
                 min=s["mn"], max=s["mx"], distinct=[len(v) for v in s["vals"][:len(s["data"])]],
                 classe="diag" if id_ == 0x7DF or 0x700 <= id_ <= 0x7FF else "broadcast")
            for id_, ext, nom, s, _, hz in self.rows()],
            pids=[dict(ecu=k[0], service=k[1], pid=k[2], **e) for k, e in self.pids.items()])

    def live(self):
        h = shutil.get_terminal_size().lines - 1
        l = (self.head().split("\n") + self.table() + self.pid_table())[:max(h, 5)]
        sys.stdout.write("\033[H\033[J" + "\n".join(l) + "\n")
        sys.stdout.flush()


def selftest():
    assert parse_line("(1.000123) can0 0B4#0102030405060708") == (1.000123, 0xB4, False, bytes(range(1, 9)))
    assert parse_line("(2.5) can0 18DAF110#AABB") == (2.5, 0x18DAF110, True, b"\xaa\xbb")
    assert parse_line("(3.000001) can0 123#R") == (3.000001, 0x123, False, b"")
    assert parse_line("# fps=1") is None and parse_line("  ") is None
    for bad in ("garbage", "(1.0) can0 12#00", "(1.0) can0 123#0", "(1.0) can0 123#ZZ"):
        try:
            parse_line(bad)
            assert False, bad
        except ValueError:
            pass
    s = Stats()
    s.add(1.0, 0x7E2, False, bytes.fromhex("0221C30000000000"))
    s.add(1.1, 0x7EA, False, bytes.fromhex("0661C3AABBCCDD00"))
    s.add(1.2, 0x7EA, False, bytes.fromhex("037F211200000000"))
    e = s.pids[(0x7E2, 0x21, 0xC3)]
    assert (e["req"], e["rep"], e["last"]) == (1, 1, "AABBCCDD00"), e
    n = s.pids[(0x7E2, 0x21, None)]
    assert (n["neg"], n["nrc"]) == (1, 0x12), n
    print("selftest OK")


def main():
    ap = argparse.ArgumentParser(description="Tableau de bord CAN (lecture seule)")
    ap.add_argument("--port", default="/dev/ttyACM0")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--file", help="rejouer un log candump au lieu du port")
    ap.add_argument("--log", help="enregistrer les lignes brutes")
    ap.add_argument("--duration", type=float, default=0, help="secondes (0 = infini)")
    ap.add_argument("--no-live", action="store_true", help="résumé final seulement")
    ap.add_argument("--json", help="écrire le résumé final en JSON")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.file:
        src = open(a.file, encoding="ascii", errors="replace")
    else:
        try:
            import serial
        except ImportError:
            sys.exit("pyserial absent : pip install pyserial")
        src = serial.Serial(a.port, a.baud, timeout=0.2)  # lecture seule : aucun write
    log = open(a.log, "w") if a.log else None
    st, t0, tl = Stats(), time.time(), 0
    try:
        while not (a.duration and time.time() - t0 >= a.duration):
            if a.file:
                line = src.readline()
                if not line:
                    break
            else:
                line = src.readline().decode("ascii", "replace")
            if line.strip():
                if log:
                    log.write(line if line.endswith("\n") else line + "\n")
                st.add_line(line)
            if not a.no_live and time.time() - tl >= 0.5:
                tl = time.time()
                st.live()
    except KeyboardInterrupt:
        pass
    if log:
        log.close()
    print(st.summary())
    if a.json:
        with open(a.json, "w") as f:
            json.dump(st.as_json(), f, indent=1)


if __name__ == "__main__":
    main()
