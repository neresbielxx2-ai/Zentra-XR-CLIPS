package com.zentra.clip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Som do clip: padrão embutido, sem som ou arquivo personalizado
 * (MP3 importado ou áudio extraído de um vídeo).
 */
object SoundManager {

    private const val DIR_NAME = "sounds"
    private var player: MediaPlayer? = null

    fun playDefault(ctx: Context) = playRes(ctx)

    fun playFile(ctx: Context, path: String) = playPath(ctx, path)

    /** Toca o som atualmente configurado (ou o pendente de importação). */
    fun playSelected(ctx: Context, pendingPath: String? = null) {
        when (Prefs.soundMode(ctx)) {
            Prefs.SOUND_NONE -> return
            Prefs.SOUND_DEFAULT -> playRes(ctx)
            else -> {
                val p = pendingPath ?: Prefs.soundPath(ctx)
                if (!p.isNullOrEmpty()) playPath(ctx, p) else playRes(ctx)
            }
        }
    }

    fun stop() {
        synchronized(this) {
            player?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            player = null
        }
    }

    private fun playRes(ctx: Context) {
        synchronized(this) {
            releaseInternal()
            try {
                val mp = MediaPlayer.create(ctx, R.raw.clip_default)
                if (mp != null) {
                    mp.setOnCompletionListener { it.release() }
                    mp.start()
                    player = mp
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun playPath(ctx: Context, path: String) {
        synchronized(this) {
            releaseInternal()
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(path)
                mp.prepare()
                mp.setOnCompletionListener { it.release() }
                mp.start()
                player = mp
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseInternal() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }

    private fun soundsDir(ctx: Context): File {
        val d = File(ctx.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Remove todos os arquivos de som exceto o mantido. */
    fun replaceCustom(ctx: Context, keepPath: String) {
        soundsDir(ctx).listFiles()?.forEach { if (it.absolutePath != keepPath) it.delete() }
    }

    /** Copia um MP3 (ou qualquer áudio) selecionado para o armazenamento interno. */
    fun importAudioFile(ctx: Context, uri: Uri): String? {
        return try {
            val name = queryDisplayName(ctx, uri) ?: "import.mp3"
            val ext = name.substringAfterLast('.', "mp3").lowercase().take(5).ifEmpty { "mp3" }
            val out = File(soundsDir(ctx), "custom_${System.currentTimeMillis()}.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                out.outputStream().use { os -> ins.copyTo(os) }
            } ?: return null
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Decodifica a faixa de áudio de um vídeo para WAV PCM 16 bits. */
    fun extractAudioFromVideo(ctx: Context, videoUri: Uri): String? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            val ext = MediaExtractor()
            extractor = ext
            ext.setDataSource(ctx, videoUri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            ext.selectTrack(trackIndex)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxBytes = sampleRate * channels * 2 * 12 // ~12 segundos

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = dec.getInputBuffer(inIdx)
                        if (ib == null) {
                            inputDone = true
                        } else {
                            val size = ext.readSampleData(ib, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                                ext.advance()
                            }
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val ob = dec.getOutputBuffer(outIdx)
                            if (ob != null) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                val arr = ByteArray(info.size)
                                ob.get(arr)
                                pcm.write(arr)
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        if (pcm.size() > maxBytes) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    else -> {}
                }
            }

            val out = File(soundsDir(ctx), "custom_video_${System.currentTimeMillis()}.wav")
            WavWriter.write(out, pcm.toByteArray(), sampleRate, channels)
            out.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}

object WavWriter {
    fun write(file: File, pcm: ByteArray, sampleRate: Int, channels: Int) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { o ->
            val dataLen = pcm.size
            writeAscii(o, "RIFF")
            writeIntLe(o, 36 + dataLen)
            writeAscii(o, "WAVE")
            writeAscii(o, "fmt ")
            writeIntLe(o, 16)
            writeShortLe(o, 1) // PCM
            writeShortLe(o, channels)
            writeIntLe(o, sampleRate)
            writeIntLe(o, sampleRate * channels * 2)
            writeShortLe(o, channels * 2)
            writeShortLe(o, 16)
            writeAscii(o, "data")
            writeIntLe(o, dataLen)
            o.write(pcm)
        }
    }

    private fun writeAscii(o: DataOutputStream, s: String) {
        o.write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun writeIntLe(o: DataOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v shr 8) and 0xFF)
        o.write((v shr 16) and 0xFF)
        o.write((v shr 24) and 0xFF)
    }

    private fun writeShortLe(o: DataOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v shr 8) and 0xFF)
    }
}
