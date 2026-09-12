package com.zentra.clip

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Serviço de gravação com buffer circular:
 *  - captura a tela via MediaProjection (superfície -> codificador H.264 por hardware)
 *  - captura áudio (interno via AudioPlaybackCapture e/ou microfone) -> AAC
 *  - mantém somente os últimos N segundos de frames codificados em RAM
 *  - ao tocar no botão flutuante, remuxa o buffer para um MP4 em Movies/Zentra Clip
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "ZentraClip"

        const val ACTION_START = "com.zentra.clip.START"
        const val ACTION_STOP = "com.zentra.clip.STOP"
        const val ACTION_SAVE = "com.zentra.clip.SAVE"
        const val ACTION_STATE = "com.zentra.clip.STATE"

        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"

        private const val NOTIF_ID = 42
        private const val NOTIF_SAVED_ID = 43
        private const val CHANNEL_REC = "zentra_recording"
        private const val CHANNEL_EVENTS = "zentra_events"

        private const val MAX_VIDEO_BUFFER_BYTES = 90L * 1024 * 1024
        private const val MAX_AUDIO_BUFFER_BYTES = 16L * 1024 * 1024

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var startedAtMs = 0L
            private set
    }

    private class Chunk(val data: ByteArray, val ptsUs: Long, val flags: Int) {
        val isKeyframe = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
    }

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int, val video: Boolean)

    private class AudioCapture(val record: AudioRecord, val channels: Int) {
        fun release() {
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
        }
    }

    // ---- estado de gravação ----
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var audioCaptures: List<AudioCapture> = emptyList()
    private var audioThread: Thread? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private val lock = Any()
    private val videoChunks = ArrayList<Chunk>()
    private val audioChunks = ArrayList<Chunk>()
    private var videoBytes = 0L
    private var audioBytes = 0L
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    @Volatile
    private var recording = false

    @Volatile
    private var saving = false

    private var outW = 0
    private var outH = 0
    private var vdW = 0
    private var vdH = 0
    private var fps = 30
    private var videoBitrate = 6_000_000
    private var dpi = 320

    // ---- botão flutuante ----
    private var windowMgr: WindowManager? = null
    private var fabView: View? = null
    private var fabParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            ACTION_SAVE -> saveClip()
            ACTION_START -> startRecording(intent)
            else -> if (!recording) stopSelf()
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ start

    private fun startRecording(intent: Intent) {
        if (recording) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultData == null) {
            stopSelf()
            return
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection", e)
            // cumpre o contrato de foreground antes de encerrar (evita crash do sistema)
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, buildRecordingNotification(), 0)
            } catch (_: Exception) {}
            toastMain("Não foi possível iniciar a captura de tela")
            stopEverything()
            return
        }
        projection = proj

        createChannels()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildRecordingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        computeEncodingConfig()

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { if (recording) stopEverything() }
            }
        }, mainHandler)

        try {
            startVideo(proj)
        } catch (e: Exception) {
            Log.e(TAG, "startVideo", e)
            toastMain("Codificador de vídeo indisponível neste aparelho")
            stopEverything()
            return
        }

        startAudio()
        registerOrientationListener()

        recording = true
        isRunning = true
        startedAtMs = System.currentTimeMillis()
        broadcastState()
        showFab()
        toastMain("Gravando! Toque no botão flutuante para salvar o clipe")
    }

    private fun computeEncodingConfig() {
        val metrics: DisplayMetrics = resources.displayMetrics
        dpi = metrics.densityDpi
        val screen = screenSize()
        val sw = screen[0]
        val sh = screen[1]
        val shortSide = min(sw, sh)
        val target = if (Prefs.resolution(this) >= 1080) 1080 else 720
        val scale = target.toFloat() / shortSide.toFloat()
        outW = max(2, (sw * scale / 2f).roundToInt() * 2)
        outH = max(2, (sh * scale / 2f).roundToInt() * 2)
        fps = if (Prefs.fps(this) >= 60) 60 else 30
        videoBitrate = when {
            target >= 1080 && fps >= 60 -> 10_000_000
            target >= 1080 -> 7_000_000
            fps >= 60 -> 6_000_000
            else -> 4_000_000
        }
    }

    private fun startVideo(proj: MediaProjection) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoderSurface = surface

        val ht = HandlerThread("ZentraVideoEncoder")
        ht.start()
        workerThread = ht
        workerHandler = Handler(ht.looper)

        encoder.setCallback(VideoOutCallback(), workerHandler)
        encoder.start()
        videoEncoder = encoder

        vdW = outW
        vdH = outH
        virtualDisplay = proj.createVirtualDisplay(
            "ZentraClip", outW, outH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, workerHandler
        )
    }

    private inner class VideoOutCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf == null) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    synchronized(lock) { videoFormat = codec.outputFormat }
                } else if (info.size > 0) {
                    val arr = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(arr)
                    synchronized(lock) {
                        videoChunks.add(Chunk(arr, info.presentationTimeUs, info.flags))
                        videoBytes += arr.size
                        trimVideoLocked()
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.e(TAG, "video output", e)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(lock) { videoFormat = format }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "video codec error", e)
            mainHandler.post {
                toastMain("Erro no codificador de vídeo")
                stopEverything()
            }
        }
    }

    // ------------------------------------------------------------------ áudio

    private fun startAudio() {
        val mode = Prefs.audioMode(this)
        if (mode == Prefs.AUDIO_NONE) return
        val proj = projection ?: return

        val captures = ArrayList<AudioCapture>()

        fun make(playback: Boolean): AudioCapture? {
            val sampleRate = 48000
            val masks = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
            for (mask in masks) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    if (minBuf <= 0) continue
                    val af = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build()
                    val builder = AudioRecord.Builder()
                        .setAudioFormat(af)
                        .setBufferSizeInBytes(max(minBuf * 2, 32768))
                    if (playback) {
                        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build()
                        builder.setAudioPlaybackCaptureConfig(cfg)
                    } else {
                        builder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    }
                    val rec = builder.build()
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        val ch = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                        return AudioCapture(rec, ch)
                    }
                    rec.release()
                } catch (e: Exception) {
                    Log.w(TAG, "audio capture playback=$playback", e)
                }
            }
            return null
        }

        when (mode) {
            Prefs.AUDIO_INTERNAL -> make(true)?.let { captures.add(it) }
            Prefs.AUDIO_MIC -> make(false)?.let { captures.add(it) }
            Prefs.AUDIO_BOTH -> {
                make(true)?.let { captures.add(it) }
                make(false)?.let { captures.add(it) }
            }
        }

        if (captures.isEmpty()) {
            toastMain("Sem áudio: fonte indisponível neste momento")
            return
        }

        val sampleRate = 48000
        val channels = if (captures.size == 1) captures[0].channels else 2

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels >= 2) 128_000 else 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        audioEncoder = encoder
        audioCaptures = captures

        audioThread = Thread({ audioLoop(sampleRate, channels) }, "ZentraAudio").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    private fun audioLoop(sampleRate: Int, channels: Int) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {}
        val enc = audioEncoder ?: return
        val captures = audioCaptures
        for (cap in captures) {
            try { cap.record.startRecording() } catch (_: Exception) {}
        }
        if (captures.isEmpty()) return

        val baseNs = System.nanoTime()
        var framesFed = 0L
        val frameChunk = 1024
        val chan0 = captures[0].channels
        val buf0 = ShortArray(frameChunk * chan0)
        val buf1: ShortArray? = if (captures.size > 1) ShortArray(frameChunk * captures[1].channels) else null
        val chan1 = if (captures.size > 1) captures[1].channels else 0
        val mixed = ShortArray(frameChunk * channels)
        val info = MediaCodec.BufferInfo()

        try {
            while (recording) {
                var framesRead = 0
                if (captures.size == 1) {
                    val n = captures[0].record.read(buf0, 0, buf0.size)
                    if (n < 0) break
                    val f0 = n / chan0
                    framesRead = f0
                    if (f0 > 0) {
                        if (channels == chan0) {
                            System.arraycopy(buf0, 0, mixed, 0, f0 * chan0)
                        } else {
                            for (i in 0 until f0) {
                                val s = buf0[i * chan0]
                                mixed[i * 2] = s
                                mixed[i * 2 + 1] = s
                            }
                        }
                    }
                } else {
                    val n0 = captures[0].record.read(buf0, 0, buf0.size)
                    val n1 = captures[1].record.read(buf1!!, 0, buf1.size)
                    if (n0 < 0 || n1 < 0) break
                    val f0 = n0 / chan0
                    val f1 = n1 / chan1
                    framesRead = min(f0, f1)
                    for (i in 0 until framesRead) {
                        for (ch in 0 until channels) {
                            val v0 = buf0[i * chan0 + min(ch, chan0 - 1)].toInt()
                            val v1 = if (i < f1) buf1!![i * chan1 + min(ch, chan1 - 1)].toInt() else 0
                            mixed[i * channels + ch] = ((v0 + v1) / 2).toShort()
                        }
                    }
                }

                if (framesRead <= 0) {
                    Thread.sleep(6)
                    continue
                }

                val ptsUs = (baseNs + framesFed * 1_000_000_000L / sampleRate) / 1000L
                framesFed += framesRead

                var queued = false
                while (recording && !queued) {
                    val inIdx = enc.dequeueInputBuffer(20_000)
                    if (inIdx >= 0) {
                        val ib = enc.getInputBuffer(inIdx)
                        if (ib != null) {
                            ib.order(ByteOrder.LITTLE_ENDIAN)
                            ib.clear()
                            ib.asShortBuffer().put(mixed, 0, framesRead * channels)
                            enc.queueInputBuffer(inIdx, 0, framesRead * channels * 2, ptsUs, 0)
                            queued = true
                        } else {
                            queued = true
                        }
                    }
                }
                drainAudio(enc, info)
            }
        } catch (e: InterruptedException) {
            // interrompido ao parar
        } catch (e: Exception) {
            Log.e(TAG, "audioLoop", e)
        }
    }

    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (recording) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(lock) { audioFormat = enc.outputFormat }
                }
                idx >= 0 -> {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        synchronized(lock) { audioFormat = enc.outputFormat }
                    } else if (info.size > 0) {
                        val ob = enc.getOutputBuffer(idx)
                        if (ob != null) {
                            val arr = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.get(arr)
                            synchronized(lock) {
                                audioChunks.add(Chunk(arr, info.presentationTimeUs, info.flags))
                                audioBytes += arr.size
                                trimAudioLocked()
                            }
                        }
                    }
                    try { enc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> return
            }
        }
    }

    // --------------------------------------------------------------- trimming

    private fun trimVideoLocked() {
        if (videoChunks.isEmpty()) return
        val keepUs = (Prefs.durationSec(this).toLong() + 4L) * 1_000_000L
        val last = videoChunks.last().ptsUs
        val minPts = last - keepUs
        while (videoChunks.size > 1 && videoChunks[1].isKeyframe && videoChunks[1].ptsUs < minPts) {
            videoBytes -= videoChunks.removeAt(0).data.size
        }
        while (videoBytes > MAX_VIDEO_BUFFER_BYTES && videoChunks.size > 1 && videoChunks[1].isKeyframe) {
            videoBytes -= videoChunks.removeAt(0).data.size
        }
    }

    private fun trimAudioLocked() {
        if (audioChunks.isEmpty()) return
        val keepUs = (Prefs.durationSec(this).toLong() + 5L) * 1_000_000L
        val last = audioChunks.last().ptsUs
        val minPts = last - keepUs
        while (audioChunks.size > 1 && audioChunks[1].ptsUs < minPts) {
            audioBytes -= audioChunks.removeAt(0).data.size
        }
        while (audioBytes > MAX_AUDIO_BUFFER_BYTES && audioChunks.size > 1) {
            audioBytes -= audioChunks.removeAt(0).data.size
        }
    }

    // -------------------------------------------------------------- save clip

    private fun saveClip() {
        if (!recording || saving) return
        SoundManager.playSelected(this)

        val durationUs = Prefs.durationSec(this).toLong() * 1_000_000L
        var base = 0L
        var vList: List<Chunk> = emptyList()
        var aList: List<Chunk> = emptyList()
        var vFmt: MediaFormat? = null
        var aFmt: MediaFormat? = null

        synchronized(lock) {
            val vf = videoFormat
            if (vf == null || videoChunks.isEmpty()) {
                toastMain("Aguarde alguns segundos de gravação…")
                return
            }
            vFmt = vf
            aFmt = audioFormat
            val lastPts = videoChunks.last().ptsUs
            val saveStart = lastPts - durationUs
            var idx = videoChunks.indexOfFirst { it.isKeyframe && it.ptsUs >= saveStart }
            if (idx < 0) idx = videoChunks.indexOfFirst { it.isKeyframe }
            if (idx < 0) idx = 0
            base = videoChunks[idx].ptsUs
            if (audioChunks.isNotEmpty()) base = minOf(base, audioChunks.first().ptsUs)
            vList = ArrayList(videoChunks.subList(idx, videoChunks.size))
            aList = if (aFmt != null) ArrayList(audioChunks.filter { it.ptsUs >= base }) else emptyList()
        }

        val clipLenUs = (vList.lastOrNull()?.ptsUs ?: base) - base
        if (clipLenUs < 800_000) {
            toastMain("Clipe muito curto — grave mais um pouco")
            return
        }

        saving = true
        val fV = vList
        val fA = aList
        val fBase = base
        val fVFmt = vFmt
        val fAFmt = aFmt
        saveExecutor.execute {
            val ok = try {
                writeClip(fV, fA, fVFmt!!, if (fA.isEmpty()) null else fAFmt, fBase)
            } catch (e: Exception) {
                Log.e(TAG, "writeClip", e)
                false
            }
            saving = false
            val msg = if (ok) "Clipe salvo em Meus Clips" else "Falha ao salvar o clipe"
            mainHandler.post { toastMain(msg) }
        }
    }

    private fun writeClip(
        v: List<Chunk>,
        a: List<Chunk>,
        vFmt: MediaFormat,
        aFmt: MediaFormat?,
        baseUs: Long
    ): Boolean {
        var muxer: MediaMuxer? = null
        var tmp: File? = null
        try {
            tmp = File(cacheDir, "clip_${System.currentTimeMillis()}.mp4")
            val m = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val vTrack = m.addTrack(vFmt)
            val aTrack = if (aFmt != null && a.isNotEmpty()) m.addTrack(aFmt) else -1
            m.start()

            val samples = ArrayList<Sample>(v.size + a.size)
            for (c in v) samples.add(Sample(c.data, c.ptsUs, c.flags, true))
            if (aTrack >= 0) for (c in a) samples.add(Sample(c.data, c.ptsUs, c.flags, false))
            samples.sortBy { it.ptsUs }

            val buffer = java.nio.ByteBuffer.allocate(2_500_000)
            val info = MediaCodec.BufferInfo()
            var written = 0
            var firstVideoDone = false
            for (s in samples) {
                val pts = s.ptsUs - baseUs
                if (pts < 0) continue
                if (s.video && !firstVideoDone) {
                    if ((s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) continue
                    firstVideoDone = true
                }
                buffer.clear()
                buffer.put(s.data)
                buffer.flip()
                info.set(0, s.data.size, pts, s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(if (s.video) vTrack else aTrack, buffer, info)
                written++
            }
            if (written < 3) {
                try { m.release() } catch (_: Exception) {}
                muxer = null
                tmp.delete()
                return false
            }
            m.stop()
            m.release()
            muxer = null

            val uri = ClipsStore.save(this, tmp)
            tmp.delete()
            if (uri != null) showSavedNotification()
            return uri != null
        } catch (e: Exception) {
            Log.e(TAG, "writeClip", e)
            try { muxer?.release() } catch (_: Exception) {}
            tmp?.delete()
            return false
        }
    }

    // ------------------------------------------------------------------- stop

    private fun stopEverything() {
        val wasRecording = recording
        recording = false
        isRunning = false

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null

        audioThread?.join(700)
        audioThread = null
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null

        synchronized(lock) {
            videoChunks.clear()
            audioChunks.clear()
            videoBytes = 0
            audioBytes = 0
            videoFormat = null
            audioFormat = null
        }
        for (cap in audioCaptures) cap.release()
        audioCaptures = emptyList()

        try { projection?.stop() } catch (_: Exception) {}
        projection = null

        removeFab()
        try { workerThread?.quitSafely() } catch (_: Exception) {}
        workerThread = null
        workerHandler = null

        if (wasRecording) broadcastState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        recording = false
        isRunning = false
        removeFab()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        try { encoderSurface?.release() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        for (cap in audioCaptures) cap.release()
        try { projection?.stop() } catch (_: Exception) {}
        try { workerThread?.quitSafely() } catch (_: Exception) {}
        saveExecutor.shutdownNow()
    }

    // --------------------------------------------------------------- rotação

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val vd = virtualDisplay ?: return
            if (!recording) return
            try {
                val screen = screenSize()
                val sw = screen[0]
                val sh = screen[1]
                val shortSide = min(sw, sh)
                val target = if (Prefs.resolution(this@RecordingService) >= 1080) 1080 else 720
                val scale = target.toFloat() / shortSide.toFloat()
                val w = max(2, (sw * scale / 2f).roundToInt() * 2)
                val h = max(2, (sh * scale / 2f).roundToInt() * 2)
                if (w != vdW || h != vdH) {
                    vd.resize(w, h, dpi)
                    vdW = w
                    vdH = h
                }
            } catch (e: Exception) {
                Log.w(TAG, "resize", e)
            }
        }
    }

    private fun registerOrientationListener() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.registerDisplayListener(displayListener, mainHandler)
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------- FAB

    private fun screenSize(): IntArray {
        val wm = windowMgr ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowMgr = wm
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                intArrayOf(b.width(), b.height())
            } else {
                @Suppress("DEPRECATION")
                val d = wm.defaultDisplay
                val m = DisplayMetrics()
                d.getRealMetrics(m)
                intArrayOf(m.widthPixels, m.heightPixels)
            }
        } catch (e: Exception) {
            intArrayOf(1080, 2400)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun showFab() {
        if (!Settings.canDrawOverlays(this)) {
            toastMain("Ative a permissão “Sobrepor outras janelas” para o botão flutuante")
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowMgr = wm
        val size = dp(46f).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val screen = screenSize()
        var x = Prefs.fabX(this)
        var y = Prefs.fabY(this)
        val saved = Prefs.fabX(this) >= 0 && Prefs.fabY(this) >= 0
        if (!saved || x < -size / 3 || y < -size / 3 || x > screen[0] - size / 2 || y > screen[1] - size / 2) {
            x = screen[0] - size - dp(14f).toInt()
            y = screen[1] / 2
        }
        params.x = x
        params.y = y

        val view = FabView(this)
        attachFabTouch(view, params, size, screen[0], screen[1])
        try {
            wm.addView(view, params)
            fabView = view
            fabParams = params
        } catch (e: Exception) {
            Log.e(TAG, "addView fab", e)
        }
    }

    private fun attachFabTouch(view: View, params: WindowManager.LayoutParams, size: Int, screenW: Int, screenH: Int) {
        val dragThreshold = dp(6f)
        var downRawX = 0f
        var downRawY = 0f
        var startPX = 0
        var startPY = 0
        var moved = false
        var longFired = false

        val longPressRunnable = Runnable {
            if (!moved && !longFired && recording) {
                longFired = true
                try { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
                toastMain("Gravação encerrada")
                mainHandler.post { stopEverything() }
            }
        }

        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startPX = params.x
                    startPY = params.y
                    moved = false
                    longFired = false
                    v.postDelayed(longPressRunnable, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                        moved = true
                        v.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        params.x = (startPX + dx).roundToInt().coerceIn(-size / 3, screenW - size * 2 / 3)
                        params.y = (startPY + dy).roundToInt().coerceIn(-size / 3, screenH - size * 2 / 3)
                        try { windowMgr?.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    if (!longFired) {
                        if (!moved) {
                            saveClip()
                        } else {
                            Prefs.setFabPos(this@RecordingService, params.x, params.y)
                        }
                    }
                    longFired = false
                    true
                }
                else -> false
            }
        }
    }

    private fun removeFab() {
        val v = fabView ?: return
        fabView = null
        fabParams = null
        try { windowMgr?.removeView(v) } catch (_: Exception) {}
    }

    private inner class FabView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xDD0D0D1C.toInt()
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = 0xFF7C6CFF.toInt()
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFF4D6A.toInt()
        }
        private var pulse = 0f
        private val animator = ValueAnimator.ofFloat(0f, (2.0 * Math.PI).toFloat()).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            try { animator.start() } catch (_: Exception) {}
        }

        override fun onDetachedFromWindow() {
            try { animator.cancel() } catch (_: Exception) {}
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = cx - dp(2f)
            canvas.drawCircle(cx, cy, r, fillPaint)
            val glow = (0.5 + 0.5 * sin(pulse.toDouble())).toFloat()
            ringPaint.alpha = 110 + (110f * glow).toInt()
            canvas.drawCircle(cx, cy, r - dp(1f), ringPaint)
            canvas.drawCircle(cx, cy, dp(4.5f), dotPaint)
        }
    }

    // ---------------------------------------------------------- notificações

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val rec = NotificationChannel(CHANNEL_REC, "Gravação", NotificationManager.IMPORTANCE_LOW)
            rec.setShowBadge(false)
            nm.createNotificationChannel(rec)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_EVENTS, "Clipes", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun buildRecordingNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val savePi = PendingIntent.getService(
            this, 2,
            Intent(this, RecordingService::class.java).setAction(ACTION_SAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 3,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_REC)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Zentra Clip gravando")
            .setContentText("Toque em “Salvar clipe” para capturar os últimos instantes")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Salvar clipe", savePi)
            .addAction(0, "Parar", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun showSavedNotification() {
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, ClipsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Clipe salvo")
            .setContentText("Disponível em Meus Clips")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_SAVED_ID, n)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ misc

    private fun broadcastState() {
        val i = Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra("running", isRunning)
            .putExtra("startedAt", startedAtMs)
        sendBroadcast(i)
    }

    private fun toastMain(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
