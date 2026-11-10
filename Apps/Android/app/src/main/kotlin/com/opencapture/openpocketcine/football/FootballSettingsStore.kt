package com.opencapture.openpocketcine.football

import android.content.Context
import androidx.core.content.edit

/** Bewaart de voetbal-instellingen in een eigen SharedPreferences-bestand. */
object FootballSettingsStore {
    private const val FILE = "openpocketcine.football"

    fun load(context: Context): FootballSettings {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        fun <T : Enum<T>> enum(key: String, values: Array<T>, default: T): T =
            values.firstOrNull { it.name == p.getString(key, null) } ?: default
        return FootballSettings(
            sport = enum("sport", Sport.values(), Sport.VELD),
            position = enum("position", CameraPosition.values(), CameraPosition.MIDDENLIJN),
            pitchSide = enum("pitchSide", PitchSide.values(), PitchSide.LINKS),
            steering = enum("steering", Steering.values(), Steering.CAMERA_TRACK),
            autoZoom = p.getBoolean("autoZoom", true),
            maxZoom = p.getFloat("maxZoom", 3f).toDouble(),
            recordWithTracking = p.getBoolean("recordWithTracking", true),
            showOverlay = p.getBoolean("showOverlay", true),
        )
    }

    fun save(context: Context, s: FootballSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString("sport", s.sport.name)
            putString("position", s.position.name)
            putString("pitchSide", s.pitchSide.name)
            putString("steering", s.steering.name)
            putBoolean("autoZoom", s.autoZoom)
            putFloat("maxZoom", s.maxZoom.toFloat())
            putBoolean("recordWithTracking", s.recordWithTracking)
            putBoolean("showOverlay", s.showOverlay)
        }
    }
}
