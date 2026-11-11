# Auris2_App

Appli Android (API 31+, cible 36) : reçoit en BLE les trames CAN du module Auris2, les décode et peut les enregistrer.

## Compiler
```
export GRADLE_USER_HOME=/home/eliot/IA/android-tools/gradle-home
export ANDROID_HOME=/home/eliot/IA/android-tools/sdk
./gradlew --no-daemon testDebugUnitTest assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk Auris2_App.apk
```
(`local.properties` doit contenir `sdk.dir=$ANDROID_HOME`.)

## Signaux décodés (octets 0..7, big-endian, mesurés sur l'Auris)
Longueur contrôlée par signal. "--" si périmé > 1 s (feux 3 s, clim/ventilation 6 s ; ouvrants > 2 s = fermé).
- 0x0B4 vitesse : uint16 o5-6 x 0,01 km/h · 0x610 vitesse compteur : o2 (km/h)
- 0x1C4 régime : int16 o0-1 x 0,78125 ; thermique ON = régime > 0
- 0x127 rapport : o5 >> 4 (0=P 1=R 2=N 3=D 4=B) · 0x245 accélérateur : o2 x 0,5 %
- 0x224 frein : o0 bit 5 ; effort uint16 o4-5 (brut) · 0x025 volant : 12 bits bas o0-1 x 1,5 deg (gauche négatif)
- 0x0AA 4 roues (ordre à vérifier) · 0x622 feux : o2 bit 7 croisement, bit 6 position
- 0x381 : o1 == 1 clim en marche, o2 ventilation 0..7 · 0x620 : o5 bit 5 porte conducteur, o5 bit 1 coffre

## Installer
`adb install -r Auris2_App.apk` (adb : `android-tools/sdk/platform-tools`) ou copier l'APK sur le téléphone.

## Interface et réglages (v3.1)
Style « liquid glass » : fond animé en clair (taches de couleur floutées par `RenderEffect`), fond noir uni en sombre (pas d'animation), surfaces translucides à reflet et bordure lumineuse (`Glass.kt`). Couleurs dans `res/values/colors.xml` et `res/values-night/colors.xml` (dont `glass`, `glass_edge`, `sheen`, `blob1..3`).
- 3 vues (plus de barre du bas, navigation par le menu bulle seul) : Bord (tuiles, Connecter/Enregistrer), Trames (tableau brut), Réglages. Transitions en fondu/glissement, tuiles qui apparaissent en cascade, nombres qui défilent, "--" grisé, retour tactile sur les boutons.
- Menu : bulle de verre ronde en haut à gauche, qui s'étend en panneau (le reste de l'écran se floute). Seule navigation : Bord, Trames, Réglages, Voiture en 3D.
- Voiture en 3D (`CarView.kt`) : vue isométrique dessinée en Canvas (caméra arrière gauche), animée par les signaux : roues (0x0AA sinon 0x0B4, sens inversé en R), braquage (0x025), feux et faisceaux (0x622), stop (0x224), recul et rapport (0x127), porte conducteur et hayon (0x620), vibration + fumée quand le thermique tourne (0x1C4), jauges accélérateur / frein / régime, ventilateur de clim (0x381), compteur (0x610). Signal périmé = élément grisé. Bouton « Démo » : scénario simulé de 40 s (`demoCar`).
- Clignotants : dessinés et animés, mais **non disponibles** en réel (invisibles sur l'OBD2, cf. `captures/BASE_CAN.md`) : grisés hors démo. Pour les brancher le jour où un ID est trouvé : remplir `CarState.blink` dans `Values.snapshot` (`Auris2Logic.kt`).
- Réglages (SharedPreferences) : thème Sombre (défaut) / Clair / Système, signaux affichés (une case par tuile), dossier des .log. Bouton « À propos » en bas : logo, version, copyright, description, Android 12 minimum, aucune donnée envoyée sur Internet, avertissement conduite.
- Icône adaptative : `res/mipmap-anydpi/ic_launcher.xml` (logo `res/drawable-nodpi/logo.png`, copie de `Logo.png`).

## Format du log
Dossier choisi dans Réglages (« Choisir le dossier », accès SAF mémorisé) ; par défaut, ou si le dossier est inaccessible (repli signalé à l'écran), Téléchargements/Auris2. Fichier `auris2_AAAAMMJJ_HHMMSS.log`, format candump lu par `tools/can_live.py` :
```
(1700000000.123000) can0 7E8#0441057B
(1700000000.623000) can0 18DAF110#AABB
(1700000000.624000) can0 123#R
```
Horodatage = heure Unix d'arrivée de la 1re trame + écart des `ts_ms` du module. ID sur 3 chiffres (standard) ou 8 (étendu), `#R` pour RTR.
