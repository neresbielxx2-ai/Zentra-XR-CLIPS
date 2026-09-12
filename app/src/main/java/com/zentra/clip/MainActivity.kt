package com.zentra.clip

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var statusChip: TextView
    private lateinit var appIcon: ImageView
    private lateinit var appLabel: TextView
    private lateinit var appPackage: TextView
    private lateinit var chipDuration: TextView
    private lateinit var chipRes: TextView
    private lateinit var chipFps: TextView
    private lateinit var chipAudio: TextView
    private lateinit var btnStart: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (RecordingService.isRunning) {
                updateStatusText()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateState()
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                launchRecording(data)
            } else {
                Toast.makeText(this, "Captura de tela não autorizada", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusChip = findViewById(R.id.statusChip)
        appIcon = findViewById(R.id.appIcon)
        appLabel = findViewById(R.id.appLabel)
        appPackage = findViewById(R.id.appPackage)
        chipDuration = findViewById(R.id.chipDuration)
        chipRes = findViewById(R.id.chipRes)
        chipFps = findViewById(R.id.chipFps)
        chipAudio = findViewById(R.id.chipAudio)
        btnStart = findViewById(R.id.btnStart)

        findViewById<ImageButton>(R.id.btnTopSettings).setOnClickListener { goSettings() }
        findViewById<View>(R.id.cardApp).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        val chips = listOf(chipDuration, chipRes, chipFps, chipAudio)
        for (c in chips) c.setOnClickListener { goSettings() }
        findViewById<MaterialButton>(R.id.btnClips).setOnClickListener {
            startActivity(Intent(this, ClipsActivity::class.java))
        }
        btnStart.setOnClickListener { onStartClicked() }

        autoSelectZentraXr()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, stateReceiver,
            IntentFilter(RecordingService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateState()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ auto-select

    private fun autoSelectZentraXr() {
        if (Prefs.autoSelectDone(this)) return
        Prefs.setAutoSelectDone(this, true)
        Thread {
            val app = findZentraXr()
            if (app != null) {
                Prefs.setSelectedApp(this, app.packageName, app.label)
                runOnUiThread { refresh() }
            }
        }.start()
    }

    private fun findZentraXr(): AppItem? {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ris = pm.queryIntentActivities(intent, 0)
        for (ri in ris) {
            val pkg = ri.activityInfo.packageName
            val label = try { ri.loadLabel(pm)?.toString() ?: continue } catch (e: Exception) { continue }
            val norm = label.lowercase().replace(" ", "")
            if ((norm.contains("zentra") && norm.contains("xr")) || pkg.lowercase().contains("zentraxr")) {
                val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null } ?: continue
                return AppItem(label, pkg, icon)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ fluxo

    private fun goSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun onStartClicked() {
        if (RecordingService.isRunning) {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            Toast.makeText(this, "Gravação finalizada", Toast.LENGTH_SHORT).show()
            return
        }
        if (Prefs.selectedPackage(this) == null) {
            Toast.makeText(this, "Escolha um aplicativo primeiro", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AppPickerActivity::class.java))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showOverlayDialog()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun showOverlayDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissão necessária")
            .setMessage("Para o botão flutuante funcionar sobre outros apps, o Zentra Clip precisa da permissão “Sobrepor outras janelas”. Toque em Abrir e autorize o Zentra Clip.")
            .setPositiveButton("Abrir") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun requestProjection() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        try {
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Captura de tela indisponível", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchRecording(data: Intent) {
        val pkg = Prefs.selectedPackage(this)
        if (pkg.isNullOrEmpty()) return
        val svc = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_RESULT_CODE, RESULT_OK)
            .putExtra(RecordingService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, svc)

        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
        Toast.makeText(this, "Gravando! Toque no botão flutuante para salvar clipes", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------- UI

    private fun refresh() {
        chipDuration.text = "${Prefs.durationSec(this)} s"
        chipRes.text = if (Prefs.resolution(this) >= 1080) "1080p" else "720p"
        chipFps.text = "${Prefs.fps(this)} fps"
        chipAudio.text = when (Prefs.audioMode(this)) {
            Prefs.AUDIO_MIC -> "Mic"
            Prefs.AUDIO_BOTH -> "Int+Mic"
            Prefs.AUDIO_NONE -> "Mudo"
            else -> "Interno"
        }

        val pkg = Prefs.selectedPackage(this)
        val label = Prefs.selectedLabel(this)
        if (pkg != null) {
            appLabel.text = label ?: pkg
            appPackage.text = pkg
            try {
                appIcon.setImageDrawable(packageManager.getApplicationIcon(pkg))
            } catch (e: Exception) {
                Prefs.setSelectedApp(this, "", "")
            }
        }
    }

    private fun updateState() {
        if (RecordingService.isRunning) {
            btnStart.text = getString(R.string.btn_stop)
            updateStatusText()
            handler.removeCallbacks(timerRunnable)
            handler.postDelayed(timerRunnable, 1000)
        } else {
            btnStart.text = getString(R.string.btn_start)
            statusChip.text = getString(R.string.status_ready)
            statusChip.setTextColor(ContextCompat.getColor(this, R.color.sub))
            handler.removeCallbacks(timerRunnable)
        }
    }

    private fun updateStatusText() {
        if (!RecordingService.isRunning) return
        val elapsed = (System.currentTimeMillis() - RecordingService.startedAtMs) / 1000
        val min = elapsed / 60
        val sec = elapsed % 60
        statusChip.text = String.format("● Gravando • %d:%02d", min, sec)
        statusChip.setTextColor(ContextCompat.getColor(this, R.color.red))
    }
}
