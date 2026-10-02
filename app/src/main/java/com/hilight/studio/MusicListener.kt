package com.hilight.studio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Music sync: listens to whatever is playing, finds the beat with [MusicAnalyzer] and keeps the
 * light's beat clock ([Store.syncBeat]) locked to it, brighter when the music is louder and dark
 * when it stops.
 *
 * "Phone audio" reads the phone's own output mix through [Visualizer]; when that is unavailable or
 * stays empty while music is playing, it falls back to the microphone, which also hears music from
 * a speaker in the room. Audio is analysed as it arrives and nothing is stored. Listening ends when
 * the app leaves the screen ([Store.stopPreview]) or the session reaches its time limit.
 */
class MusicListener internal constructor(
    private val app: Context,
    private val store: Store,
    private val main: Handler,
) {
    enum class Source { PHONE, MIC }

    enum class Status { IDLE, WAITING_FOR_SOUND, FINDING_BEAT, DANCING, FINISHED, FAILED }

    data class Ui(
        val listening: Boolean = false,
        val source: Source = Source.PHONE,
        /** Phone audio could not be read, so the microphone is being used instead. */
        val fellBack: Boolean = false,
        val status: Status = Status.IDLE,
        val bpm: Int? = null,
        val intensity: Double = 0.0,
        /** The user's timing nudge on top of the source's own latency allowance. */
        val nudgeMs: Long = 0,
    )

    sealed interface Start {
        data object Ok : Start
        data object NeedsPermission : Start
        data class Blocked(val reason: Suppression) : Start
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val audio = app.getSystemService(AudioManager::class.java)
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    @Volatile private var generation = 0

    // Worker-thread state.
    private val analyzer = MusicAnalyzer()
    private var visualizer: Visualizer? = null
    private var visualizerSplitter: BandSplitter? = null
    private var visualizerEmptySinceMs: Long? = null
    private var mic: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false
    private var lastPostMs = 0L

    // Main-thread state.
    private var sentOnce = false
    private var lastSentMs = 0L

    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts listening. The light joins in once the beat is found. */
    fun start(source: Source): Start {
        if (!hasPermission()) return Start.NeedsPermission
        store.previewSuppressionReason()?.let { return Start.Blocked(it) }
        stop()
        val gen = ++generation
        sentOnce = false
        lastSentMs = 0
        _ui.update { Ui(listening = true, source = source, status = Status.WAITING_FOR_SOUND, nudgeMs = it.nudgeMs) }
        val thread = HandlerThread("hilight-music").also { it.start() }
        worker = thread
        val handler = Handler(thread.looper)
        workerHandler = handler
        handler.post {
            analyzer.reset()
            when (source) {
                Source.PHONE -> if (!openVisualizer(gen)) openMic(gen, fellBack = true)
                Source.MIC -> openMic(gen, fellBack = false)
            }
        }
        return Start.Ok
    }

    /** Stops listening and the light. */
    fun stop() = finish(Status.IDLE)

    /** Moves the light later (positive) or earlier (negative) against the music. */
    fun nudge(deltaMs: Long) {
        _ui.update { it.copy(nudgeMs = (it.nudgeMs + deltaMs).coerceIn(-MAX_NUDGE_MS, MAX_NUDGE_MS)) }
    }

    private fun finish(status: Status) {
        generation++
        val handler = workerHandler
        val thread = worker
        workerHandler = null
        worker = null
        if (handler != null && thread != null) {
            handler.post {
                closeVisualizer()
                closeMic()
                thread.quitSafely()
            }
        }
        val wasListening = _ui.value.listening
        _ui.update { it.copy(listening = false, status = if (wasListening) status else it.status, intensity = 0.0) }
        if (sentOnce) store.stopBeat()
        sentOnce = false
    }

    // ------------------------------------------------------------------ worker thread: sources

    @SuppressLint("MissingPermission") // record-audio is checked in start()
    private fun openVisualizer(gen: Int): Boolean = try {
        val v = Visualizer(0)
        v.enabled = false
        v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1_024)
        v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
        v.setDataCaptureListener(
            object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer, waveform: ByteArray, samplingRate: Int) {
                    onWaveform(gen, waveform, samplingRate)
                }

                override fun onFftDataCapture(vis: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
            },
            Visualizer.getMaxCaptureRate(),
            true,
            false,
        )
        v.enabled = true
        visualizer = v
        visualizerEmptySinceMs = null
        true
    } catch (e: Exception) {
        Log.w(TAG, "phone audio unavailable, using the microphone", e)
        closeVisualizer()
        false
    }

    private fun onWaveform(gen: Int, waveform: ByteArray, samplingRateMilliHz: Int) {
        if (gen != generation || waveform.isEmpty()) return
        val rate = (samplingRateMilliHz / 1_000).coerceAtLeast(8_000)
        val splitter = visualizerSplitter ?: BandSplitter(rate).also { visualizerSplitter = it }
        val samples = FloatArray(waveform.size) { ((waveform[it].toInt() and 0xFF) - 128) / 128f }
        val now = SystemClock.elapsedRealtime()
        // An output mix that stays empty while something is playing is one Android will not share
        // (or a device without the effect): switch to the microphone rather than sit dark.
        if (samples.all { it == 0f } && audio?.isMusicActive == true) {
            val since = visualizerEmptySinceMs ?: now.also { visualizerEmptySinceMs = it }
            if (now - since >= PHONE_AUDIO_EMPTY_MS) {
                closeVisualizer()
                analyzer.reset()
                openMic(gen, fellBack = true)
                return
            }
        } else {
            visualizerEmptySinceMs = null
        }
        val (bass, rest) = splitter.split(samples)
        feed(gen, now - waveform.size * 500L / rate, bass, rest)
    }

    @SuppressLint("MissingPermission") // checked in start(); a revoked grant fails construction below
    private fun openMic(gen: Int, fellBack: Boolean) {
        val record = try {
            val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val format = AudioFormat.Builder()
                .setSampleRate(MIC_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioRecord.Builder()
                .setAudioSource(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, MIC_HOP * 4 * 8))
                .build()
                .also { if (it.state != AudioRecord.STATE_INITIALIZED) error("microphone did not initialise") }
        } catch (e: Exception) {
            Log.w(TAG, "microphone unavailable", e)
            main.post { if (gen == generation) finish(Status.FAILED) }
            return
        }
        mic = record
        main.post { if (gen == generation) _ui.update { it.copy(source = Source.MIC, fellBack = fellBack) } }
        val handler = workerHandler ?: return
        micRunning = true
        record.startRecording()
        micThread = Thread({
            val splitter = BandSplitter(MIC_RATE)
            val buffer = FloatArray(MIC_HOP)
            while (micRunning) {
                val n = record.read(buffer, 0, MIC_HOP, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (n < 0) break
                    continue
                }
                val t = SystemClock.elapsedRealtime() - n * 500L / MIC_RATE
                val (bass, rest) = splitter.split(buffer, n)
                handler.post { feed(gen, t, bass, rest) }
            }
        }, "hilight-mic").also { it.start() }
    }

    private fun closeVisualizer() {
        visualizer?.let { v -> runCatching { v.enabled = false }; runCatching { v.release() } }
        visualizer = null
        visualizerSplitter = null
    }

    private fun closeMic() {
        micRunning = false
        mic?.let { r -> runCatching { r.stop() } }
        micThread?.join(500)
        micThread = null
        mic?.release()
        mic = null
    }

    // ------------------------------------------------------------------ analysis -> light

    private fun feed(gen: Int, tMs: Long, bass: Double, rest: Double) {
        if (gen != generation) return
        analyzer.onBlock(tMs, bass, rest)
        if (tMs - lastPostMs < POST_EVERY_MS) return
        lastPostMs = tMs
        val beat = analyzer.beat
        val intensity = analyzer.intensity
        val silent = analyzer.silent
        main.post { onAnalysis(gen, beat, intensity, silent) }
    }

    private fun onAnalysis(gen: Int, beat: MusicAnalyzer.Beat?, intensity: Double, silent: Boolean) {
        if (gen != generation) return
        val session = store.beat.value
        if (sentOnce && session == null) {
            // The time limit, Stop elsewhere, or a notification alert took over the light.
            finish(Status.FINISHED)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val energy = MusicSync.energyFor(intensity, silent)
        val ui = _ui.value
        val lead = (if (ui.source == Source.PHONE) PHONE_LEAD_MS else MIC_LEAD_MS) + ui.nudgeMs
        val next = when {
            beat != null -> MusicSync.plan(session?.grid, beat, energy, now, lead, lastSentMs)
            // Lost the beat in silence: keep the clock, go dark until the music is back.
            session != null && silent && session.grid.energy != 0.0 && now - lastSentMs >= MusicSync.MIN_RESYNC_GAP_MS ->
                session.grid.copy(startedAtMs = now, offsetMs = session.grid.clockAt(now), energy = 0.0)
            else -> null
        }
        if (next != null) {
            val blocked = store.syncBeat(next)
            if (blocked != null) {
                finish(Status.FAILED)
                return
            }
            sentOnce = true
            lastSentMs = now
        }
        _ui.update {
            it.copy(
                status = when {
                    silent -> Status.WAITING_FOR_SOUND
                    beat == null -> Status.FINDING_BEAT
                    else -> Status.DANCING
                },
                bpm = beat?.bpm,
                intensity = if (silent) 0.0 else intensity,
            )
        }
    }

    companion object {
        private const val TAG = "HiLightMusic"
        const val MIC_RATE = 44_100
        const val MIC_HOP = 512
        const val POST_EVERY_MS = 50L
        const val PHONE_AUDIO_EMPTY_MS = 3_000L
        /**
         * How much later than the detected beat the light should start. Phone audio is captured
         * before it reaches the speaker; the microphone hears it a little after. Both allow for
         * the light's own fade-in.
         */
        const val PHONE_LEAD_MS = 30L
        const val MIC_LEAD_MS = -40L
        const val NUDGE_STEP_MS = 25L
        const val MAX_NUDGE_MS = 300L
    }
}
