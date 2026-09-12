package com.zentra.clip

import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File

class SoundSettingsActivity : AppCompatActivity() {

    private var pendingPath: String? = null

    private val importMp3 =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importAudio(uri, false)
        }

    private val importVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importAudio(uri, true)
        }

    private lateinit var rgMode: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sound)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        rgMode = findViewById(R.id.rgMode)

        var currentMode = Prefs.soundMode(this)

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rbNone -> Prefs.SOUND_NONE
                R.id.rbCustom -> Prefs.SOUND_CUSTOM
                else -> Prefs.SOUND_DEFAULT
            }
            if (newMode == Prefs.SOUND_CUSTOM && (pendingPath ?: Prefs.soundPath(this)).isNullOrEmpty()) {
                Toast.makeText(this, "Importe um MP3 ou extraia o áudio de um vídeo primeiro", Toast.LENGTH_SHORT).show()
                rgMode.check(if (currentMode == Prefs.SOUND_NONE) R.id.rbNone else R.id.rbDefault)
                return@setOnCheckedChangeListener
            }
            currentMode = newMode
            Prefs.setSoundMode(this, newMode)
        }

        rgMode.check(
            when (currentMode) {
                Prefs.SOUND_NONE -> R.id.rbNone
                Prefs.SOUND_CUSTOM -> R.id.rbCustom
                else -> R.id.rbDefault
            }
        )

        findViewById<MaterialButton>(R.id.btnImportMp3).setOnClickListener {
            try {
                importMp3.launch(arrayOf("audio/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "Seletor indisponível", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.btnImportVideo).setOnClickListener {
            try {
                importVideo.launch(arrayOf("video/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "Seletor indisponível", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnPreview).setOnClickListener { preview() }
        findViewById<MaterialButton>(R.id.btnSaveSound).setOnClickListener { saveSound() }

        updateFileLabel()
    }

    private fun importAudio(uri: Uri, fromVideo: Boolean) {
        Toast.makeText(this, "Processando…", Toast.LENGTH_SHORT).show()
        Thread {
            val path = if (fromVideo) {
                SoundManager.extractAudioFromVideo(this, uri)
            } else {
                SoundManager.importAudioFile(this, uri)
            }
            runOnUiThread {
                if (path == null) {
                    Toast.makeText(this, "Não foi possível extrair/importar o áudio", Toast.LENGTH_LONG).show()
                } else {
                    pendingPath = path
                    updateFileLabel()
                    Toast.makeText(this, "Toque em SALVAR SOM para confirmar", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun preview() {
        val pending = pendingPath
        if (pending != null) {
            SoundManager.playFile(this, pending)
            return
        }
        when (Prefs.soundMode(this)) {
            Prefs.SOUND_NONE -> Toast.makeText(this, "Som desativado", Toast.LENGTH_SHORT).show()
            Prefs.SOUND_CUSTOM -> {
                val p = Prefs.soundPath(this)
                if (!p.isNullOrEmpty()) SoundManager.playFile(this, p)
                else SoundManager.playDefault(this)
            }
            else -> SoundManager.playDefault(this)
        }
    }

    private fun saveSound() {
        val p = pendingPath
        if (p == null) {
            Toast.makeText(this, "Importe um arquivo primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        SoundManager.replaceCustom(this, p)
        Prefs.setSoundPath(this, p)
        Prefs.setSoundMode(this, Prefs.SOUND_CUSTOM)
        pendingPath = null
        rgMode.check(R.id.rbCustom)
        updateFileLabel()
        Toast.makeText(this, "Som salvo ✓", Toast.LENGTH_SHORT).show()
    }

    private fun updateFileLabel() {
        val p = pendingPath ?: Prefs.soundPath(this)
        findViewById<TextView>(R.id.customFileName).text =
            if (p.isNullOrEmpty()) "Nenhum" else File(p).name
    }

    override fun onResume() {
        super.onResume()
        updateFileLabel()
    }
}
