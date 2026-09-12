package com.zentra.clip

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnAudioInternal: MaterialButton
    private lateinit var btnAudioMic: MaterialButton
    private lateinit var btnAudioBoth: MaterialButton
    private lateinit var btnAudioNone: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val tgDuration = findViewById<MaterialButtonToggleGroup>(R.id.tgDuration)
        val tgRes = findViewById<MaterialButtonToggleGroup>(R.id.tgRes)
        val tgFps = findViewById<MaterialButtonToggleGroup>(R.id.tgFps)

        tgDuration.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) Prefs.setDurationSec(this, if (checkedId == R.id.btn60) 60 else 30)
        }
        tgRes.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) Prefs.setResolution(this, if (checkedId == R.id.btn1080) 1080 else 720)
        }
        tgFps.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) Prefs.setFps(this, if (checkedId == R.id.btnFps60) 60 else 30)
        }

        btnAudioInternal = findViewById(R.id.btnAudioInternal)
        btnAudioMic = findViewById(R.id.btnAudioMic)
        btnAudioBoth = findViewById(R.id.btnAudioBoth)
        btnAudioNone = findViewById(R.id.btnAudioNone)

        btnAudioInternal.setOnClickListener {
            Prefs.setAudioMode(this, Prefs.AUDIO_INTERNAL); styleAudio()
        }
        btnAudioMic.setOnClickListener {
            Prefs.setAudioMode(this, Prefs.AUDIO_MIC); styleAudio()
        }
        btnAudioBoth.setOnClickListener {
            Prefs.setAudioMode(this, Prefs.AUDIO_BOTH); styleAudio()
        }
        btnAudioNone.setOnClickListener {
            Prefs.setAudioMode(this, Prefs.AUDIO_NONE); styleAudio()
        }

        findViewById<View>(R.id.cardSound).setOnClickListener {
            startActivity(Intent(this, SoundSettingsActivity::class.java))
        }

        tgDuration.check(if (Prefs.durationSec(this) >= 60) R.id.btn60 else R.id.btn30)
        tgRes.check(if (Prefs.resolution(this) >= 1080) R.id.btn1080 else R.id.btn720)
        tgFps.check(if (Prefs.fps(this) >= 60) R.id.btnFps60 else R.id.btnFps30)
        styleAudio()
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.soundCurrent).text = when (Prefs.soundMode(this)) {
            Prefs.SOUND_NONE -> "Sem som"
            Prefs.SOUND_CUSTOM -> "Som personalizado"
            else -> "Som padrão do Zentra Clip"
        }
        styleAudio()
    }

    private fun styleAudio() {
        val mode = Prefs.audioMode(this)
        styleAudioButton(btnAudioInternal, mode == Prefs.AUDIO_INTERNAL)
        styleAudioButton(btnAudioMic, mode == Prefs.AUDIO_MIC)
        styleAudioButton(btnAudioBoth, mode == Prefs.AUDIO_BOTH)
        styleAudioButton(btnAudioNone, mode == Prefs.AUDIO_NONE)
    }

    private fun styleAudioButton(b: MaterialButton, selected: Boolean) {
        if (selected) {
            b.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
            b.setTextColor(getColor(R.color.white))
            b.strokeWidth = 0
        } else {
            b.backgroundTintList = ColorStateList.valueOf(getColor(R.color.surface2))
            b.setTextColor(getColor(R.color.sub))
            b.strokeWidth = dp(1f)
            b.strokeColor = ColorStateList.valueOf(getColor(R.color.stroke))
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
