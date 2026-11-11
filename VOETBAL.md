# Voetbal-modus (OpenPocketCine + DJI Osmo Pocket 4P)

Android-app die de Pocket 4P **automatisch het spel laat volgen** tijdens een amateurwedstrijd,
zodat je zelf kunt fotograferen. Gebouwd op [OpenPocketCine](https://github.com/erik-sutton95/OpenPocketCine)
(Apache 2.0), dat de verbinding met de Pocket (BLE + Wi‑Fi), het live-beeld, zoom, gimbal en de
ActiveTrack-overdracht al regelt. Deze fork voegt alleen de voetbal-logica toe
(`Apps/Android/app/src/main/kotlin/com/opencapture/openpocketcine/football/`).

## Hoe het werkt

1. Het live-beeld van de Pocket (720p HEVC over Wi‑Fi) wordt in de app op ~10 beelden/s door een
   objectdetector gehaald (MediaPipe EfficientDet‑Lite0: `person` en `sports ball`).
2. `PlayFinder` bepaalt **waar het spel is**: bal als die gezien wordt, anders het
   dichtheidsgewogen zwaartepunt van de spelers (een eenzame keeper telt nauwelijks mee).
   Gedempt in de tijd zodat één gemiste detectie het beeld niet laat schokken.
3. `CameraDirector` stuurt de camera, op twee manieren:
   - **Camera volgt speler** (standaard): de app kiest de speler bij het spel en geeft hem aan de
     eigen ActiveTrack van de Pocket. Die doet het soepele werk; de app wisselt pas van speler als
     het spel een paar tienden van een seconde ver van hem weg zit.
   - **App stuurt gimbal**: de app draait de gimbal zelf naar het spelpunt (regelaar in graden,
     dus 3× is niet zenuwachtiger dan 1×). Teken en snelheid van de joystick-assen worden al
     rijdend geleerd uit de gimbal-hoeken.
   Spel kwijt (geen spelers > paar seconden) ⇒ uitzoomen en rustig terug naar de startrichting.
   Auto-zoom zoomt in als het spel een paar seconden rustig in het midden staat en alle spelers
   na de zoom nog passen; uit als een speler te groot wordt of het spel aan een rand hangt.
4. Instellingen: **Zaal / Veld** (zoom, speleromvang, wisselsnelheid) en **Middenlijn / Zijlijn**
   (bij Zijlijn: aan welke kant het veld ligt ⇒ asymmetrische pan-grens, zodat de camera niet
   het hek of achter de goal in draait). Verder: sturing, auto-zoom, max. zoom, opname mee
   starten/stoppen, herkenning over het beeld tekenen.

## Gebruik

1. Pocket aan, in de app verbinden zoals gewoonlijk (BLE → Wi‑Fi → live-beeld).
2. Zet de Pocket op het statief, **richt hem op het midden van het veld** (dat wordt "thuis").
3. Tik op de **⚽**-knop boven in het live-beeld → kies Sport en Positie → **START**.
   De opname start mee (instelbaar). Stop met **STOP**.
4. Tijdens de wedstrijd laat de ⚽-balk zien wat de app doet (zoekt / volgt / kwijt, aantal
   spelers, of de camera volgt). Groene kaders = spelers, geel = bal, rood kruis = spelpunt.

Tips: 4K 30p, Follow-modus op de Pocket (geen Tilt Locked / FPV), telefoon aan een powerbank,
scherm mag aan blijven. Zet "schakel naar mobiele data" / adaptieve Wi‑Fi op de Samsung uit.

## Bouwen

De APK komt uit GitHub Actions (`.github/workflows/voetbal-apk.yml`). Vaste downloadlink van de nieuwste build:
**https://github.com/aliteker42/pocketvoetbal/releases/latest/download/PocketVoetbal.apk** — bij elke push naar `main` of
`voetbal`, of start "Voetbal APK" handmatig. Na ~30 minuten staat `app-debug.apk` als artefact bij
de run (Actions → run → Artifacts). Installeren op de telefoon via "onbekende bronnen".

Lokaal bouwen vraagt Swift 6.3.3 + Swift Android SDK, NDK 27, Android SDK 37 en de sibling
`../OpenCine` op de pin uit `opencine-pins.txt` (zie `ANDROID.md`).

Het detectiemodel wordt bij de build gedownload (`downloadFootballModel` in
`Apps/Android/app/build.gradle.kts`); zonder internet: `FOOTBALL_MODEL_FILE=/pad/naar/efficientdet_lite0.tflite`.

## Tests

`./gradlew :app:testDebugUnitTest --tests 'com.opencapture.openpocketcine.football.*'` — de pure
logica (`PlayFinder`, `CameraDirector`, leerders, profielen) zonder Android.

## Wat nog niet

- Detectie draait op 320×180 (de bestaande Face-AF-aftakking); verre spelers op 1× zijn klein.
  Bij 2–3× zoom is dat ruim voldoende; bij 1× worden ze soms gemist.
- Balherkenning is wisselvallig op die resolutie; de spelerswolk doet daarom het meeste werk.
- Eén camera, één spelpunt: twee gelijktijdige acties (bijv. counter + opstootje) kiest hij er één.
