package com.yourapp.yamahaarranger.ui

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Master DSP settings (native EQ -> chorus -> reverb on the final mix). */
data class FxSettings(
    val enabled: Boolean = true,
    val reverbLevel: Int = 0,      // 0..127
    val reverbTime: Int = 60,      // 0..127
    val reverbDamp: Int = 50,      // 0..127
    val chorusLevel: Int = 0,      // 0..127
    val chorusRate: Int = 40,      // 0..127
    val chorusDepth: Int = 60,     // 0..127
    val eqLow: Int = 0,            // -12..12 dB
    val eqMid: Int = 0,
    val eqHigh: Int = 0
) {
    companion object {
        /** Quick reverb characters shown as chips in the DSP dialog. */
        val REVERB_TYPES: List<Triple<String, Int, Int>> = listOf(   // name, time, damp
            Triple("ROOM", 30, 70),
            Triple("STAGE", 55, 55),
            Triple("HALL", 85, 40),
            Triple("CATHEDRAL", 120, 25)
        )
    }
}

@Singleton
class FxPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("yamaha_arranger_fx", Context.MODE_PRIVATE)

    fun load(): FxSettings {
        val d = FxSettings()
        return FxSettings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            reverbLevel = prefs.getInt("reverbLevel", d.reverbLevel).coerceIn(0, 127),
            reverbTime = prefs.getInt("reverbTime", d.reverbTime).coerceIn(0, 127),
            reverbDamp = prefs.getInt("reverbDamp", d.reverbDamp).coerceIn(0, 127),
            chorusLevel = prefs.getInt("chorusLevel", d.chorusLevel).coerceIn(0, 127),
            chorusRate = prefs.getInt("chorusRate", d.chorusRate).coerceIn(0, 127),
            chorusDepth = prefs.getInt("chorusDepth", d.chorusDepth).coerceIn(0, 127),
            eqLow = prefs.getInt("eqLow", d.eqLow).coerceIn(-12, 12),
            eqMid = prefs.getInt("eqMid", d.eqMid).coerceIn(-12, 12),
            eqHigh = prefs.getInt("eqHigh", d.eqHigh).coerceIn(-12, 12)
        )
    }

    fun save(s: FxSettings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putInt("reverbLevel", s.reverbLevel).putInt("reverbTime", s.reverbTime).putInt("reverbDamp", s.reverbDamp)
            .putInt("chorusLevel", s.chorusLevel).putInt("chorusRate", s.chorusRate).putInt("chorusDepth", s.chorusDepth)
            .putInt("eqLow", s.eqLow).putInt("eqMid", s.eqMid).putInt("eqHigh", s.eqHigh)
            .apply()
    }
}

/** Favorite and recently used styles, keyed by absolute file path. */
@Singleton
class StylePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("yamaha_arranger_styles", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = prefs.getStringSet(KEY_FAV, emptySet())?.toSet() ?: emptySet()

    /** Returns the new favorites set. */
    fun toggleFavorite(path: String): Set<String> {
        val set = favorites().toMutableSet()
        if (!set.add(path)) set.remove(path)
        prefs.edit().putStringSet(KEY_FAV, set).apply()
        return set
    }

    /** Most recent first. */
    fun recent(): List<String> =
        prefs.getString(KEY_RECENT, "").orEmpty().split('\n').filter { it.isNotBlank() }

    /** Returns the new recent list (most recent first, max [MAX_RECENT]). */
    fun markUsed(path: String): List<String> {
        val list = (listOf(path) + recent().filter { it != path }).take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
        return list
    }

    private companion object {
        const val KEY_FAV = "favorites"
        const val KEY_RECENT = "recent"
        const val MAX_RECENT = 40
    }
}
