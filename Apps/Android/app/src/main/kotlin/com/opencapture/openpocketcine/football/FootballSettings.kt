package com.opencapture.openpocketcine.football

/** Zaal- of veldvoetbal: bepaalt zoom, speleromvang en hoe snel de camera van doelwit wisselt. */
enum class Sport(val label: String) {
    ZAAL("Zaalvoetbal"),
    VELD("Veldvoetbal"),
}

/** Waar de camera staat: op de middenlijn (midden van het veld recht vooruit) of ergens langs de zijlijn. */
enum class CameraPosition(val label: String) {
    MIDDENLIJN("Middenlijn"),
    ZIJLIJN("Zijlijn (bij hoek / 16)"),
}

/** Bij een zijlijn-positie: aan welke kant ligt het midden van het veld, gezien vanaf de camera. */
enum class PitchSide(val label: String) {
    LINKS("Veld ligt links"),
    RECHTS("Veld ligt rechts"),
}

/** Hoe de camera gestuurd wordt. */
enum class Steering(val label: String, val description: String) {
    CAMERA_TRACK(
        "Camera volgt speler (ActiveTrack)",
        "De app kiest de speler bij het spel en geeft hem aan de eigen tracking van de Pocket. Soepelst beeld.",
    ),
    APP_STEER(
        "App stuurt gimbal",
        "De app draait de gimbal zelf naar het spel. Reageert op het hele spel, iets minder soepel.",
    ),
}

data class FootballSettings(
    val sport: Sport = Sport.VELD,
    val position: CameraPosition = CameraPosition.MIDDENLIJN,
    val pitchSide: PitchSide = PitchSide.LINKS,
    val steering: Steering = Steering.CAMERA_TRACK,
    val autoZoom: Boolean = false,
    /** Maximale zoomfactor die de app mag kiezen. */
    val maxZoom: Double = 3.0,
    /** Opname automatisch starten bij Start en stoppen bij Stop. */
    val recordWithTracking: Boolean = true,
    /** Herkende spelers en het spelpunt over het live-beeld tekenen. */
    val showOverlay: Boolean = true,
    /** Detector op de GPU (ontlast de CPU; het live-beeld blijft soepel). CPU als terugval. */
    val gpuDetector: Boolean = true,
    /** Minimale tijd tussen twee detecties (ms). Hoger = soepeler live-beeld, trager reageren. */
    val detectionIntervalMs: Int = 160,
) {
    /** Afgeleide stuurparameters voor sport + positie. */
    val profile: FootballProfile get() = FootballProfile.of(this)
}

/**
 * Getallen die uit sport en positie volgen. Alles in beeldcoördinaten 0..1 (breedte = 1) of seconden.
 * Zaal: klein veld, spelers groot en dichtbij, snel spel, weinig zoom.
 * Veld: grote afstanden, spelers klein, meer zoom, rustiger wisselen.
 */
data class FootballProfile(
    /** Zoom waarmee gestart wordt. */
    val defaultZoom: Double,
    /** Zoom als het spel kwijt is (uitzoomen om weer te vinden). */
    val lostZoom: Double,
    /** Straal waarbinnen spelers bij elkaar "een groepje" vormen. */
    val clusterRadius: Double,
    /** Zo ver mag het spel van de gevolgde speler af zitten voordat we wisselen. */
    val retargetDistance: Double,
    /** Zo lang moet die afstand aanhouden voordat we wisselen. */
    val retargetHoldSeconds: Double,
    /** Minimale tijd tussen twee wissels. */
    val retargetMinIntervalSeconds: Double,
    /** Na zo lang zonder spelers: kwijt-modus (uitzoomen, richting thuis). */
    val lostAfterSeconds: Double,
    /** Dode zone rond het midden waarin de gimbal niet stuurt (APP_STEER). */
    val steerDeadband: Double,
    /** Versterking stick = gain * fout (APP_STEER). */
    val steerGain: Double,
    /** Maximale stick-uitslag (APP_STEER). */
    val steerMax: Double,
    /** Verticale doelpositie van het spel in beeld (0 = boven). Iets onder het midden houdt lucht weg. */
    val targetY: Double,
    /** Pan-begrenzing links/rechts t.o.v. de startrichting, in graden. */
    val panLimitLeftDeg: Double,
    val panLimitRightDeg: Double,
    /** Bij auto-zoom: speler-hoogte (fractie van beeldhoogte) waarboven we uitzoomen / waaronder we inzoomen. */
    val zoomOutWhenTallerThan: Double,
    val zoomInWhenShorterThan: Double,
) {
    companion object {
        fun of(s: FootballSettings): FootballProfile {
            val base =
                when (s.sport) {
                    Sport.ZAAL ->
                        FootballProfile(
                            defaultZoom = 1.0, lostZoom = 1.0, clusterRadius = 0.30,
                            retargetDistance = 0.22, retargetHoldSeconds = 1.0, retargetMinIntervalSeconds = 2.5,
                            lostAfterSeconds = 2.5, steerDeadband = 0.07, steerGain = 1.6, steerMax = 0.85,
                            targetY = 0.50, panLimitLeftDeg = 80.0, panLimitRightDeg = 80.0,
                            zoomOutWhenTallerThan = 0.60, zoomInWhenShorterThan = 0.18,
                        )
                    Sport.VELD ->
                        FootballProfile(
                            defaultZoom = minOf(3.0, s.maxZoom), lostZoom = minOf(2.0, s.maxZoom), clusterRadius = 0.22,
                            retargetDistance = 0.26, retargetHoldSeconds = 1.2, retargetMinIntervalSeconds = 3.5,
                            lostAfterSeconds = 3.5, steerDeadband = 0.08, steerGain = 1.2, steerMax = 0.65,
                            targetY = 0.48, panLimitLeftDeg = 85.0, panLimitRightDeg = 85.0,
                            zoomOutWhenTallerThan = 0.50, zoomInWhenShorterThan = 0.13,
                        )
                }
            return when (s.position) {
                CameraPosition.MIDDENLIJN -> base
                // Langs de zijlijn bij een hoek of de 16: het veld ligt grotendeels aan één kant. Naar de
                // andere kant (achter de goal / het hek) mag de camera maar een klein stukje draaien.
                CameraPosition.ZIJLIJN ->
                    when (s.pitchSide) {
                        PitchSide.LINKS -> base.copy(panLimitLeftDeg = 110.0, panLimitRightDeg = 35.0)
                        PitchSide.RECHTS -> base.copy(panLimitLeftDeg = 35.0, panLimitRightDeg = 110.0)
                    }
            }
        }
    }
}
