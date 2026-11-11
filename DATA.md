# Toyota Auris II Hybride (E180) — CAN & diagnostic : contexte projet

> Fichier de contexte pour Claude Code. À placer à la racine du projet (nom `CLAUDE.md` = chargé automatiquement).
> Compilé le 2026-11-06 à partir de sources publiques. Rien ici n'est une donnée constructeur officielle :
> **tout ID / PID / adresse doit être validé sur le véhicule avant usage.**

---

## 1. Véhicule cible

| Élément | Valeur |
|---|---|
| Modèle | Toyota Auris II Hybride (code E180), 2012–2018 |
| Chaîne de traction | Hybrid Synergy Drive, identique à la Prius III (ZVW30) — pièces interchangeables selon la communauté |
| Bus CAN | 500 kbit/s, identifiants 11 bits |
| Diagnostic | ISO 15765-4 (CAN) via prise OBD-II ; requêtes Toyota mode `21` / `22` + OBD standard mode `01` |
| openpilot | **Non supportée** (absente de la liste officielle comma.ai) |

**Règle de travail :** une donnée Prius III est une *hypothèse* pour l'Auris tant qu'elle n'est pas confirmée. Marquer chaque signal `confirmé_auris` ou `hérité_prius3`.

---

## 2. Deux sources de données différentes — ne pas mélanger

| | CAN passif (broadcast) | CAN diagnostic (requête/réponse) |
|---|---|---|
| Principe | Les ECU émettent en continu, on écoute | On interroge un ECU, il répond |
| Format de description | Fichier **DBC** | Liste de **PID** (CSV Torque, tableur) |
| Outil | SocketCAN `candump` en **listen-only**, SavvyCAN, cantools | ELM327, J2534 (Techstream), python-can + isotp |
| Risque | Nul en listen-only | Faible en lecture ; **interdit** : écriture, routines, reflash |

---

## 3. Adresses diagnostic des calculateurs (requête → réponse)

Source : CarCoding (carte multi-générations Toyota). **À vérifier sur Auris II.**

| ECU | Requête | Réponse | Note |
|---|---|---|---|
| Moteur (ECM) | `7E0` | `7E8` | OBD standard + PID Toyota |
| Contrôle hybride (KWP) | `7E2` | `7EA` | Génération Prius III / Auris II |
| Contrôle hybride (UDS) | `7D2` | `7DA` | Plutôt générations récentes |
| Batterie HV | `7E3` | `7EB` | Utilisé dans les PID Prius III (ex. `21C3` en-tête `7E3`, forum Torque) |
| ABS / VSC | `7B0` | `7B8` | Sécurité critique — lecture seule |
| Direction (EPS) | `7A0` | `7A8` | Sécurité critique — lecture seule |
| Airbag (SRS) | `780` | `788` | Sécurité critique — lecture seule |

Convention : réponse = requête + `0x08`. Requête fonctionnelle OBD : `7DF`.

### Exemple ELM327
```
ATZ          ; reset
ATE0         ; echo off
ATSP6        ; ISO 15765-4 CAN 11 bits 500k
ATSH7E2      ; en-tête -> ECU hybride
21C3         ; requête mode 21, PID C3 (exemple : à valider)
```

### Exemple python (python-can + can-isotp), lecture seule
```python
import can, isotp

bus = can.interface.Bus(channel="can0", interface="socketcan")
addr = isotp.Address(isotp.AddressingMode.Normal_11bits, txid=0x7E2, rxid=0x7EA)
stack = isotp.CanStack(bus, address=addr)

stack.send(bytes([0x21, 0xC3]))          # mode 21, PID C3 (exemple)
resp = stack.recv(timeout=1.0)            # attendre la réponse ISO-TP
print(resp.hex() if resp else "pas de réponse")
```

---

## 4. Listes de PID diagnostic

| Source | Contenu | Statut |
|---|---|---|
| [Zan's projects — Torque PIDs Auris Hybrid](http://zansprojects.blogspot.com/2018/12/torque-pids-for-toyota-auris-hybrid.html) · [CSV (Drive)](https://drive.google.com/file/d/1RfDxSlYaySwsT-nfkf29ketA8JJEO1pW/view?usp=sharing) | 212 PID préfixés `Auris-` (SOC, courant/temp. batterie HV, limites de puissance, régime ICE, demande de chauffe, consigne régulateur) | **confirmé_auris** (Auris 2017) |
| [PriusChat — GenIII Prius Custom PIDs](https://priuschat.com/threads/geniii-prius-custom-pids-for-torque-app.98693/) | Liste de base Prius III, 63 jauges | hérité_prius3 |
| [Tableur Vincent1449p](https://docs.google.com/spreadsheet/ccc?key=0Ar6-3mN7UK-CdG9OWlJZT3BjSEpFVjJ4QkJSZ0FLcHc#gid=4) | Base PID Prius la plus complète (en-têtes, formules, unités) | hérité_prius3 |
| [Torque forum — Prius II](https://torque-bhp.com/community/main-forum/prius-ii/) | Placement de l'en-tête ECU dans Torque | référence |
| [Hybrid Systems Monitoring With Torque (PDF)](https://www.scribd.com/document/1055316436/Hybrid-Systems-Monitoring-With-Torque-App) | Tensions des blocs batterie HV | hérité_prius3 |
| [Prius gen2 PIDs (NHW20)](https://github.com/gerdbremer/Prius-gen2-torque-PIDs) · [Prius gen1 (NHW11)](https://github.com/kutasg/Prius-gen1-torque-PIDs) | Comparaison générations | comparaison |
| [PID OBD-II standard (CSV)](https://github.com/AustinMurphy/OBD2-Scantool/blob/master/obd2_std_PIDs.csv) | Mode 01 générique | universel |

### Format CSV Torque (pour le parsing)
Colonnes : `Name, ShortName, ModeAndPID, Equation, Min Value, Max Value, Units, Header`
- `ModeAndPID` ex. `0x21c3` ; `Header` ex. `7E3`
- `Equation` utilise `A, B, C…` = octets de données de la réponse (après l'écho mode/PID), ex. `((A*256)+B)/100`
- Bits : `{A:7}` = bit 7 de l'octet A. Signé : `Signed(A)`.

---

## 5. CAN passif — fichiers DBC

| Source | Contenu |
|---|---|
| [commaai/opendbc](https://github.com/commaai/opendbc) — `opendbc/dbc/generator/toyota/` | `_toyota_2017.dbc` (base), `toyota_nodsu_pt.dbc`, `toyota_new_mc_pt.dbc`, `toyota_tnga_k_pt.dbc`, `toyota_secoc_pt.dbc`, `_toyota_adas_standard.dbc`, `_community.dbc` |
| [PriusChat — Passive CAN messages](https://priuschat.com/threads/passive-can-messages.116038/) | `Prius.dbc` (noms en allemand) : SOC, angle volant, vitesse, vitesses roues, accélérateur, barre énergie, mode traction, levier |
| [Colorado State — Camry 2010](https://www.engr.colostate.edu/~jdaily/tucrrc/ToyotaCAN.html) | Tableau d'IDs + méthode de décodage |
| [awesome-automotive-can-id](https://github.com/iDoka/awesome-automotive-can-id) | Index (Prius 2010 IPA, Prius CAN-Translator, Yaris, Tacoma 2018, Lexus RX) |

### Messages de `_toyota_2017.dbc` (opendbc) — hérités, à valider sur Auris

| ID hex | Nom | Remarque |
|---|---|---|
| `0x024` | KINEMATICS | |
| `0x025` | STEER_ANGLE_SENSOR | |
| `0x0A6` | BRAKE | |
| `0x0AA` | WHEEL_SPEEDS | |
| `0x0B4` | SPEED | |
| `0x127` | GEAR_PACKET_HYBRID | spécifique hybride |
| `0x1C4` | ENGINE_RPM | |
| `0x245` | GAS_PEDAL_HYBRID | spécifique hybride |
| `0x260` | STEER_TORQUE_SENSOR | |
| `0x3B0` | AIR_CONDITIONER_2 | |
| `0x3BC` | GEAR_PACKET | |
| `0x343` / `0x412` / `0x2E6` | ACC_CONTROL / LKAS_HUD / LEAD_INFO | ADAS TSS-P uniquement |

Signaux hybrides (SOC, courant HV) : **absents** de cette base opendbc → passer par le diagnostic (§3–4) ou par le `Prius.dbc` de PriusChat.

### Capture passive (sans risque)
```bash
sudo ip link set can0 type can bitrate 500000 listen-only on
sudo ip link set can0 up
candump -l can0            # log horodaté
canplayer vcan0=can0 < candump-XXXX.log   # rejouer hors voiture
```
Méthode de décodage : une action à la fois (porte, pédale, volant), 3–5 s d'écart, noter l'heure ; puis corréler octets ↔ grandeur connue (|r| > 0,7).

---

## 6. Codes défaut & documentation atelier

- [Hybrid Control System — tous les DTC (PDF PriusChat)](https://attachments.priuschat.com/attachment-files/2025/11/280963_FILE_0203.pdf) — sous-codes type `P3102-524` (Prius 2008, logique identique)
- [Atomic Auto — Toyota Hybrid DTC](https://atomicauto.com/reports/toyota-hybrid-trouble-codes) · [A3 Global — Hybrid DTC](https://a3global.com/hybrid-vehicle-diagnostic-trouble-codes)
- [TIS Document Ripper](https://github.com/threadproc/tis-rip) — schémas et manuels depuis Toyota TIS (abonnement requis)

---

## 7. Outils & références

- [Hybrid Assistant (Android)](https://play.google.com/store/apps/details?id=priusfan.info.bthsd10&hl=en_US) · [guide OBD](http://hybridassistant.blogspot.com/p/obd.html) · [retours Auris](https://www.toyotaownersclub.com/forums/topic/184648-hybrid-assistant-app/)
- [CarCoding — ECU Toyota](https://carcoding.app/en/ecu/brand/toyota/)
- [opendbc `fw_versions.py`](https://github.com/commaai/opendbc/blob/master/opendbc/car/fw_versions.py) — requêtes UDS Toyota d'openpilot
- [RAMN — tutoriel UDS](https://ramn.readthedocs.io/en/latest/userguide/diag_tutorial.html)
- [python-j2534-interface](https://github.com/keenanlaws/python-j2534-interface) — J2534 / Techstream en Python
- [Toyothack + SocketCAN (Fabio Baltieri)](https://fabiobaltieri.com/2013/07/23/hacking-into-a-vehicle-can-bus-toyothack-and-socketcan/)
- [Projet Home Assistant — décodage CAN Toyota](https://community.home-assistant.io/t/project-decoding-my-toyotas-can-bus-from-scratch-to-rebuild-the-factory-app-read-only-in-ha/1020667) — opendbc n'y couvrait que 64/115 IDs

---

## 8. Consignes pour Claude Code

1. **Lecture seule.** Ne jamais générer de code qui écrit sur le bus hors requêtes de lecture diagnostic (`01`, `09`, `21`, `22`, `03`). Interdits sans demande explicite : services UDS `2E`, `31`, `34`–`37`, `27`, `10 02`, `11`, et toute injection de trames sur le CAN passif.
2. Capture CAN : toujours `listen-only on`.
3. Chaque signal ajouté au DBC ou au CSV porte un commentaire de provenance (`confirmé_auris` / `hérité_prius3` / `à_valider`) et la source (URL).
4. Formats de sortie préférés : `.dbc` (lisible par cantools / SavvyCAN), `.csv` Torque, Python 3 avec `python-can`, `can-isotp`, `cantools`.
5. Ne pas inventer d'ID ni de formule : si une donnée manque, l'écrire `TODO` et proposer une méthode de capture pour la trouver.
6. Les sources web ci-dessus peuvent avoir changé : revérifier un lien avant d'en tirer une formule.

## 9. Arborescence suggérée
```
.
├── CLAUDE.md                 # ce fichier
├── dbc/
│   ├── auris2_hybrid.dbc     # DBC fusionné (à construire)
│   └── upstream/             # copies opendbc + Prius.dbc
├── pids/
│   ├── auris_zan.csv         # CSV Zan (212 PID)
│   └── prius3_vincent.csv    # export tableur Prius III
├── logs/                     # captures candump
└── tools/
    ├── read_pid.py           # requêtes diagnostic lecture seule
    └── decode_log.py         # décodage logs via cantools
```
