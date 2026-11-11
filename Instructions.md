# Auris2_Module — Instructions

## But
Créer un module qui sniffe (écoute) la ligne CAN DIAG de la prise OBD2 d'une Toyota Auris 2 Hybride et transmet toutes les trames en Bluetooth BLE vers l'appli Android (dossier `Auris2_App`) qui décode et affiche les infos. L'écran OLED n'affiche que le débit CAN (trames/s), l'état de l'appairage Bluetooth, l'état du réseau CAN et les erreurs.

## Environnement
- Arduino IDE, code en `.ino`

## Composants
| Composant | Détails |
|---|---|
| Microcontrôleur | Seeed Studio XIAO ESP32-C6 |
| Transceiver CAN | SN65HVD230 (CAN Bus, 3,3 V) |
| Écran | OLED 0,96", 128 x 64, SSD1315, I2C |

## Notes techniques
- OBD2 : CAN-H = broche 6, CAN-L = broche 14, masse = broches 4/5, +12 V = broche 16.
- Débit CAN attendu sur l'OBD2 Toyota : 500 kbit/s.
- L'ESP32-C6 a un contrôleur CAN intégré (TWAI) : le SN65HVD230 se branche directement sur ses broches TX/RX (broches à définir).
- Utiliser le mode « listen only » du TWAI pour ne jamais perturber le bus de la voiture.
- Écran : SSD1315 piloté avec la lib U8g2 (constructeur SSD1306 128x64 compatible), I2C adresse 0x3C, SDA = D4, SCL = D5.
- Alimentation depuis le +12 V de l'OBD2 : prévoir un convertisseur 12 V → 5 V (non listé dans les composants).
