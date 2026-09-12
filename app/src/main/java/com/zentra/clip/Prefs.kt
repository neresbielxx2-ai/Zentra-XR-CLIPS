package com.zentra.clip

import android.content.Context

object Prefs {
    const val AUDIO_INTERNAL = "internal"
    const val AUDIO_MIC = "mic"
    const val AUDIO_BOTH = "both"
    const val AUDIO_NONE = "none"

    const val SOUND_DEFAULT = "default"
    const val SOUND_NONE = "none"
    const val SOUND_CUSTOM = "custom"

    private fun sp(c: Context) = c.getSharedPreferences("zentra_prefs", Context.MODE_PRIVATE)

    fun selectedPackage(c: Context): String? = sp(c).getString("sel_pkg", null)
    fun selectedLabel(c: Context): String? = sp(c).getString("sel_label", null)

    fun setSelectedApp(c: Context, pkg: String, label: String) {
        sp(c).edit().putString("sel_pkg", pkg).putString("sel_label", label).apply()
    }

    fun autoSelectDone(c: Context): Boolean = sp(c).getBoolean("auto_sel_done", false)
    fun setAutoSelectDone(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_sel_done", v).apply()

    fun durationSec(c: Context): Int = sp(c).getInt("duration", 30)
    fun setDurationSec(c: Context, v: Int) = sp(c).edit().putInt("duration", v).apply()

    fun resolution(c: Context): Int = sp(c).getInt("resolution", 720)
    fun setResolution(c: Context, v: Int) = sp(c).edit().putInt("resolution", v).apply()

    fun fps(c: Context): Int = sp(c).getInt("fps", 60)
    fun setFps(c: Context, v: Int) = sp(c).edit().putInt("fps", v).apply()

    fun audioMode(c: Context): String = sp(c).getString("audio", AUDIO_INTERNAL) ?: AUDIO_INTERNAL
    fun setAudioMode(c: Context, v: String) = sp(c).edit().putString("audio", v).apply()

    fun soundMode(c: Context): String = sp(c).getString("sound_mode", SOUND_DEFAULT) ?: SOUND_DEFAULT
    fun setSoundMode(c: Context, v: String) = sp(c).edit().putString("sound_mode", v).apply()

    fun soundPath(c: Context): String? = sp(c).getString("sound_path", null)
    fun setSoundPath(c: Context, p: String?) {
        val e = sp(c).edit()
        if (p == null) e.remove("sound_path") else e.putString("sound_path", p)
        e.apply()
    }

    fun fabX(c: Context): Int = sp(c).getInt("fab_x", -1)
    fun fabY(c: Context): Int = sp(c).getInt("fab_y", -1)
    fun setFabPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("fab_x", x).putInt("fab_y", y).apply()
}
