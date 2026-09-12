package com.zentra.clip

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipsActivity : AppCompatActivity() {

    private val clips = ArrayList<ClipsStore.ClipItem>()
    private lateinit var adapter: ClipAdapter
    private lateinit var emptyView: TextView

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) reload()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        emptyView = findViewById(R.id.emptyView)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = ClipAdapter()
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        Thread {
            val list = ClipsStore.list(this)
            runOnUiThread {
                clips.clear()
                clips.addAll(list)
                adapter.notifyDataSetChanged()
                emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    // ----------------------------------------------------------------- ações

    private fun showMenu(v: View, item: ClipsStore.ClipItem) {
        val pm = PopupMenu(this, v)
        pm.menu.add("Renomear")
        pm.menu.add("Compartilhar")
        pm.menu.add("Excluir")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.title) {
                "Renomear" -> renameDialog(item)
                "Compartilhar" -> share(item)
                "Excluir" -> confirmDelete(item)
            }
            true
        }
        pm.show()
    }

    private fun renameDialog(item: ClipsStore.ClipItem) {
        val input = EditText(this)
        input.setText(item.name.removeSuffix(".mp4"))
        input.setSingleLine(true)
        input.setTextColor(getColor(R.color.text))
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(this)
        wrap.setPadding(pad, pad / 2, pad, 0)
        wrap.addView(
            input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        AlertDialog.Builder(this)
            .setTitle("Renomear clipe")
            .setView(wrap)
            .setPositiveButton("Salvar") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    if (ClipsStore.rename(this, item.id, n)) reload()
                    else Toast.makeText(this, "Não foi possível renomear", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun share(item: ClipsStore.ClipItem) {
        try {
            val i = ClipsStore.openShareIntent(item)
            i.clipData = android.content.ClipData.newRawUri("clip", item.uri)
            startActivity(Intent.createChooser(i, "Compartilhar clipe"))
        } catch (e: Exception) {
            Toast.makeText(this, "Nenhum app para compartilhar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(item: ClipsStore.ClipItem) {
        AlertDialog.Builder(this)
            .setTitle("Excluir clipe")
            .setMessage("Excluir “${item.name}” permanentemente?")
            .setPositiveButton("Excluir") { _, _ -> performDelete(item) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun performDelete(item: ClipsStore.ClipItem) {
        try {
            if (ClipsStore.deleteDirect(this, item.uri)) {
                reload()
                return
            }
        } catch (e: SecurityException) {
            // segue para confirmação do sistema
        } catch (e: Exception) {
            // segue para confirmação do sistema
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(item.uri))
                deleteLauncher.launch(
                    IntentSenderRequest.Builder(pi.intentSender).build()
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Não foi possível excluir", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Não foi possível excluir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPlayer(item: ClipsStore.ClipItem) {
        val dialog = Dialog(this, R.style.Theme_Zentra_Player)
        dialog.setContentView(R.layout.dialog_player)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val vv = dialog.findViewById<VideoView>(R.id.videoView)
        dialog.findViewById<TextView>(R.id.playerTitle).text = item.name
        dialog.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { dialog.dismiss() }

        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoURI(item.uri)
        vv.setOnPreparedListener { mp ->
            mp.isLooping = true
            vv.start()
        }
        vv.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Não foi possível reproduzir", Toast.LENGTH_SHORT).show()
            true
        }
        dialog.setOnDismissListener {
            vv.stopPlayback()
            SoundManager.stop()
        }
        dialog.show()
    }

    private fun formatInfo(item: ClipsStore.ClipItem): String {
        val dur = if (item.durationMs > 0)
            String.format(Locale.getDefault(), "%d:%02d", item.durationMs / 60000, (item.durationMs % 60000) / 1000)
        else "—"
        val mb = String.format(Locale.getDefault(), "%.1f MB", item.size / 1024f / 1024f)
        val date = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(item.dateAddedMs))
        return "$dur • $mb • $date"
    }

    private fun loadThumb(uri: Uri): Bitmap? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, uri)
            val b = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release()
            b
        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------------------- adapter

    inner class ClipAdapter : RecyclerView.Adapter<ClipAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.tvName)
            val info: TextView = v.findViewById(R.id.tvInfo)
            val more: ImageButton = v.findViewById(R.id.btnMore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_clip, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = clips.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = clips[position]
            holder.name.text = item.name
            holder.info.text = formatInfo(item)
            holder.thumb.setImageDrawable(null)
            holder.thumb.tag = item.id
            Thread {
                val bmp = loadThumb(item.uri)
                if (bmp != null) {
                    runOnUiThread {
                        if (holder.thumb.tag == item.id) holder.thumb.setImageBitmap(bmp)
                    }
                }
            }.start()
            holder.itemView.setOnClickListener { openPlayer(item) }
            holder.more.setOnClickListener { v -> showMenu(v, item) }
        }
    }
}
