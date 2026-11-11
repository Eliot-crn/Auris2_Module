# Auris2_Module

Module ESP32-C6 qui écoute le bus CAN DIAG (OBD2) d'une Toyota Auris 2 Hybride et envoie les trames en BLE à une appli Android.

## Contenu

- `Auris2_Module_Code/` : firmware principal (Arduino, `.ino`).
- `Auris2_Sniffer/`, `Auris2_Diag/`, `Auris2_TestTX/` : croquis de test (écoute, diagnostic, émission).
- `Auris2_App/` : appli Android (Kotlin, Gradle), voir son README.
- `tools/` : scripts Python (`can_live.py`, `diag.py`).
- `captures/` : captures CAN et `BASE_CAN.md`.
- `Instructions.md` : cahier des charges et composants ; `DATA.md` : contexte CAN du véhicule.

## Compiler

- Firmware : Arduino IDE, carte Seeed XIAO ESP32-C6, ouvrir le `.ino` voulu et téléverser.
- Appli : dans `Auris2_App/`, définir `ANDROID_HOME` et `local.properties` (`sdk.dir`), puis
  `./gradlew --no-daemon testDebugUnitTest assembleDebug`.
- Outils : `python3 tools/can_live.py` (Python 3).

Les identifiants CAN sont à valider sur le véhicule avant tout usage.
