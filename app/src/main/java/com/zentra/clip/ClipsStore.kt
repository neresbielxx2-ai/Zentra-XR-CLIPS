package com.zentra.clip

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Biblioteca de clipes: salva, lista, renomeia e exclui vídeos em
 * Movies/Zentra Clip via MediaStore (sem permissões de armazenamento no Android 10+).
 */
object ClipsStore {

    const val DIR = "Movies/Zentra Clip"

    data class ClipItem(
        val id: Long,
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val size: Long,
        val dateAddedMs: Long
    )

    private fun collection(): Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Copia o arquivo temporário (MP4) para a galeria e devolve a Uri final. */
    fun save(ctx: Context, tmp: File): Uri? {
        return try {
            val name = "ZentraClip_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, DIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(collection(), values) ?: return null
            resolver.openOutputStream(uri)?.use { os ->
                tmp.inputStream().use { ins -> ins.copyTo(os) }
            }
            val done = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            null
        }
    }

    fun list(ctx: Context): List<ClipItem> {
        val out = ArrayList<ClipItem>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        try {
            ctx.contentResolver.query(
                collection(),
                proj,
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                arrayOf("$DIR%"),
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val dateMs = c.getLong(iDate) * 1000L
                    out.add(
                        ClipItem(
                            id = id,
                            uri = Uri.withAppendedPath(collection(), id.toString()),
                            name = c.getString(iName) ?: "clip.mp4",
                            durationMs = c.getLong(iDur),
                            size = c.getLong(iSize),
                            dateAddedMs = dateMs
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun rename(ctx: Context, id: Long, newName: String): Boolean {
        val clean = newName.trim()
            .removeSuffix(".mp4")
            .removeSuffix(".MP4")
            .removeSuffix(".Mp4")
        if (clean.isEmpty()) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$clean.mp4")
            }
            ctx.contentResolver.update(
                collection(),
                values,
                MediaStore.Video.Media._ID + "=?",
                arrayOf(id.toString())
            ) > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Exclusão direta (arquivos criados pelo próprio app). Lança SecurityException quando o sistema pedir confirmação. */
    fun deleteDirect(ctx: Context, uri: Uri): Boolean {
        return ctx.contentResolver.delete(uri, null, null) > 0
    }

    fun openShareIntent(item: ClipItem): Intent {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "video/mp4"
        i.putExtra(Intent.EXTRA_STREAM, item.uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return i
    }
}
