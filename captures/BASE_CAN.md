# Base CAN — Toyota Auris II Hybride (prise OBD2, 500 kbit/s)

Établie par captures guidées (module en listen-only, USB). Octets numérotés 0..7, bits 7..0.
Statut : ✅ confirmé sur l'Auris · 🔶 probable · ❓ à identifier.

## Contexte
- ~1000 trames/s, 120 IDs au repos (READY, à l'arrêt).
- Dernier octet de nombreux messages = checksum Toyota (varie avec le contenu).
- 0x0A6 (BRAKE_AMOUNT Prius) **absent** sur l'Auris.
- Roulage en mode EV : moteur thermique arrêté, régime 0.
- Batterie HV (SOC, tension, courant) : **pas trouvée** en diffusion ; probablement seulement par requête diag (impossible en listen-only).
- Clignotants : **non visibles** sur la prise OBD2 (testés feux en AUTO, aucun bit au rythme du clignotement).

## Signaux
| ID | Octets/bits | Signal | Formule | Statut | Capture |
|---|---|---|---|---|---|
| 0x245 | b2 | Pédale accélérateur | b2 × 0,5 % (0..100) | ✅ | 02 |
| 0x361 | b2 | Pédale accélérateur (copie) | b2 × 0,5 % | 🔶 | 02 |
| 0x1C4 | b0-b1 | Régime moteur thermique | int16 BE signé × 0,78125 tr/min | ✅ | 02 |
| 0x127 | b4 | Demande accélérateur (6 = pédale enfoncée, 0 = relâchée) | brut | 🔶 | 02 |
| 0x38B | b5 | Monte lentement moteur chaud (0x40→0x4A) : température ? | brut | ❓ | 02 |
| 0x3D3 | b0-b1 | Varie avec le moteur thermique | uint16 BE | ❓ | 02 |
| 0x224 | b0 bit 5 | Frein enfoncé (contacteur) | 1 = enfoncé | ✅ | 03 |
| 0x224 | b4-b5 | Pression / effort de freinage | uint16 BE (max vu 0x0615) | 🔶 | 03 |
| 0x230 | b3 bit 2, b6 bit 2 | Frein enfoncé (copie) | 1 = enfoncé | 🔶 | 03 |
| 0x3BB | b0 bit 0 | Bascule avec le frein | ? | ❓ | 03 |
| 0x025 | b0-b1 (12 bits bas) | Angle volant | int12 signé × 1,5° ; gauche = négatif (butée gauche -495°) | ✅ | 04 |
| 0x260 | b1-b2 | Couple conducteur sur le volant | int16 BE signé | 🔶 | 04 |
| 0x4A1 | b3 | Varie en braquant (assistance EPS ?) | brut | ❓ | 04 |
| 0x127 | b5 bits 7-4 | Rapport (levier) | 0=P 1=R 2=N 3=D 4=B | ✅ | 05 |
| 0x4A1 | b5 | Rapport (copie ?) : A0=P A2=N A3=D A4=B | brut | 🔶 | 05 |
| 0x3BC | b1, b5 | GEAR_PACKET : change avec le levier (b1 08 en N) | brut | ❓ | 05 |
| 0x622 | b2-b3 | État des feux : 0000 = éteints (AUTO de jour), 4810 = position, 8830 → 8870 = croisement (8870 = allumés, ~0,2 s après) | brut | ✅ | 06 |
| 0x622 | b1 bit 7 | Impulsion ~1 s à chaque changement de position des feux | 1 = changement | 🔶 | 06 |
| 0x381 | b2 | Vitesse de ventilation | 0 = arrêt, 1..7 | ✅ | 07 |
| 0x381 | b1 | Climatisation / soufflerie en marche | 1 = marche | ✅ | 07 |
| 0x380 | b0 bit 0, b2 bit 7 | Clim en marche ; b2 bit 7 = 1 environ 2 s après (compresseur ?) | brut | 🔶 | 07 |
| 0x3A1 | b3-b4, b6-b7 | b3-b4 2C50 = clim ON / 2040 = OFF ; b6-b7 monte pendant la marche (régime / puissance compresseur ?) | uint16 BE | ❓ | 07 |
| 0x3B0 | b5 bit 3 | Clim en marche (AIR_CONDITIONER_2) | 1 = marche | 🔶 | 07 |
| 0x3B9 | b0 | Monte lentement (0x46→0x4D en 22 s), indépendant des actions | brut | ❓ | 07 |
| 0x620 | b5 bit 5 | Porte conducteur ouverte | 1 = ouverte | ✅ | 08 |
| 0x620 | b5 bit 1 | Coffre / hayon ouvert | 1 = ouvert | ✅ | 08 |
| 0x626 | b2 (0x30) | Un ouvrant est ouvert (plafonnier ?) | 0x30 = ouvert | 🔶 | 08 |
| 0x6xx | b1 bit 7 | Messages carrosserie : impulsion ~0,3-1 s à chaque changement d'état | 1 = événement | 🔶 | 06, 08 |
| 0x0B4 | b5-b6 | Vitesse véhicule | uint16 BE × 0,01 km/h (sans signe, positive aussi en R) | ✅ | 09 |
| 0x0B4 | b4 | Compteur de distance (≈ 100 unités pour 5 m, ~5 cm/unité estimé) | compteur 8 bits | 🔶 | 09 |
| 0x0AA | b0-1, b2-3, b4-5, b6-7 | Vitesse des 4 roues (ordre des roues à vérifier) | uint16 BE × 0,01 − 67,67 km/h | ✅ | 09 |
| 0x610 | b2 | Vitesse affichée au compteur | km/h entier | ✅ | 09 |
| 0x2A4 | b0-b1 | Proportionnel à la vitesse (≈ 100 × km/h) | uint16 BE | 🔶 | 09 |
| 0x247 | b0-b1 (12 bits bas) | Accélération longitudinale ? (+ en accélérant, − au freinage) ; b0 = 04 en D, 06 en R | int12 signé | ❓ | 09 |
| 0x233 | b0-b1 | Compteur qui avance en roulant (avant et arrière), déborde | int16 | ❓ | 09 |

## Diagnostic (émission) — 2026-11-08 : ÉCHEC, aucun calculateur interrogé
- Sketch Auris2_Diag (liste blanche lecture 01/03/09/21/22) : chaque requête → bus-off (erreurs de bit), aucune réponse ECU.
- Hors voiture : chemin D2→CTX→CRX→D3 OK et 20/20 trames 500 kbit/s en auto-réception OK → module et transceiver sains.
- Sur voiture : sans masse OBD = milliers d'erreurs en réception ; avec masse + 120 Ω = 0 erreur en réception mais bus-off à l'émission ; masse sans 120 Ω = 4,7 M erreurs → essais arrêtés.
- Après coup, écoute seule : 1000 trames/s, 120 IDs, identique au repos → bus et module OK. Aucun voyant ni code défaut sur la voiture.
- Matériel modifié : fil de masse module → OBD broche 4/5 ajouté ; résistance 120 Ω (R2) du module SN65HVD230 retirée (correct pour un nœud branché sur un bus déjà terminé).
- Outils : Auris2_Diag/Auris2_Diag.ino (pont USB↔CAN, liste blanche, commande ST = compteurs d'erreurs), tools/diag.py (pids, vin, dtc, scan, raw), Auris2_TestTX/Auris2_TestTX.ino (test émission hors voiture : GPIO + 20 trames 500 kbit/s en auto-réception).
- Prochaine étape (plus tard) : ELM327 du commerce avec diag.py adapté, ou transceiver TJA1051T/3 + fils OBD courts torsadés.
- Pistes : transceiver 3,3 V marginal sur ce bus, fils OBD longs/non torsadés ; essayer un transceiver TJA1051T/3, ou un ELM327 du commerce.

## Captures
| Fichier | Action |
|---|---|
| 01_repos.log | READY, immobile, rien touché (référence) |
| 02_accel.log | Accélérateur à fond puis relâché en P (moteur thermique démarre, ~1300 tr/min ensuite) |
| 03_frein.log | Pédale de frein enfoncée/relâchée |
| 04_volant.log | Volant en butée puis retour au centre |
| 05_rapports.log | P → R → N → D → B → (N) → P, pied sur le frein |
| 06_feux.log | Clignotants G/D (≈686-690 s, feux en AUTO), puis AUTO → position → croisement → position → AUTO |
| 07_clim.log | Clim ON, ventilation 1→7, OFF, 2 fois (1er cycle presque entièrement avant la capture) |
| 08_portes.log | Porte conducteur déjà ouverte au début → fermée 1009,2 s ; coffre 1012,2-1016,6 s ; porte conducteur 1024,4-1028,8 s (montée à bord) |
| 09_roulage.log | D : accélération franche ~5 m (max 7,9 km/h) + freinage, puis R : recul lent ~5 m, P |
| 11_ecoute_apres_diag.log | Contrôle en écoute seule après les essais d'émission (bus sain) |
