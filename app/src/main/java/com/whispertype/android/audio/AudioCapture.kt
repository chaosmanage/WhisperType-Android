package com.whispertype.android.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import com.whispertype.android.core.audio.GemAudioFormat
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.DictationFailure
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Low-level PCM16 producer. Implementations are not required to be thread-safe. */
interface PcmSource {
    /** Reads up to [out].size bytes; returns the count read, 0 if none, or < 0 on error. */
    fun read(out: ByteArray): Int

    /** Releases underlying resources; must be called exactly once. */
    fun release()
}

sealed interface AudioStartResult {
    data object Started : AudioStartResult
    data class Failed(val failure: DictationFailure) : AudioStartResult
}

/**
 * [AudioPipeline] implementation: [PcmSource] -> [Chunker] -> [BoundedAudioQueue],
 * with a ~20 Hz amplitude [StateFlow].
 *
 * Release C6: the producer owns the [Chunker] exclusively — no external caller
 * reads `remaining()`. [requestStop] unblocks the producer's blocking read and
 * lets it process already-returned bytes, emit all complete frames, emit the
 * zero-padded partial frame from `remaining()`, and close the chunk channel.
 * [awaitQuiescence] joins that orderly drain with a bounded timeout; [stop] is
 * the hard-cancel fallback.
 *
 * Read/permission/init failures surface as typed failures on [failures], never
 * as raw throws.
 */
class AudioCapture(
    private val sampleRateHz: Int = GemAudioFormat.SAMPLE_RATE_HZ,
    private val sourceFactory: () -> PcmSource? = { createDefaultSource() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AudioPipeline {
    private val queue = BoundedAudioQueue<AudioChunk>(QUEUE_CAPACITY)

    /** Bounded FIFO of 20 ms frames (capacity 64). Closed by the producer on shutdown. */
    override val chunks: ReceiveChannel<AudioChunk> = queue.channel

    private val _amplitude = MutableStateFlow(0f)

    /** Smoothed input level in [0, 1], updated at ~20 Hz. */
    override val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _failures = MutableSharedFlow<DictationFailure>(replay = 1, extraBufferCapacity = 4)

    /** Typed terminal failures (mic init/read); the latest is replayed to late subscribers. */
    override val failures: SharedFlow<DictationFailure> = _failures.asSharedFlow()

    private val chunker = Chunker(sampleRateHz)

    private val started = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile
    private var source: PcmSource? = null

    @Volatile
    private var producerJob: Job? = null

    /** Starts capture. Safe to call once; subsequent calls return [AudioStartResult.Started]. */
    override fun start(): AudioStartResult {
        if (!started.compareAndSet(false, true)) return AudioStartResult.Started
        val acquired = sourceFactory()
        if (acquired == null) {
            val failure = DictationFailure(MIC_INIT, "Microphone could not start", recoverable = true)
            _failures.tryEmit(failure)
            return AudioStartResult.Failed(failure)
        }
        source = acquired
        producerJob = scope.launch { producerLoop(acquired) }
        return AudioStartResult.Started
    }

    /** Marks stop requested and releases the source so the blocking read unblocks. Idempotent. */
    override fun requestStop() {
        if (!stopRequested.compareAndSet(false, true)) return
        source?.release()
    }

    /** Joins the orderly producer drain with a bounded timeout. Returns true when quiesced. */
    override suspend fun awaitQuiescence(timeoutMs: Long): Boolean {
        val job = producerJob ?: return true
        return withTimeoutOrNull(timeoutMs) { job.join() } != null
    }

    /** Idempotent hard stop: cancels the producer, releases the source, closes the queue. */
    override fun stop() {
        stopRequested.set(true)
        producerJob?.cancel()
        if (released.compareAndSet(false, true)) source?.release()
        queue.close()
    }

    private suspend fun producerLoop(source: PcmSource) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var amplitudeTick = 0
        try {
            while (!stopRequested.get()) {
                val read = source.read(buffer)
                if (read < 0) {
                    _failures.tryEmit(DictationFailure(MIC_READ, "Microphone read failed", recoverable = true))
                    break
                }
                if (read > 0) {
                    val valid = if (read < buffer.size) buffer.copyOf(read) else buffer
                    for (chunk in chunker.push(valid)) {
                        queue.send(chunk)
                    }
                    // UI amplitude is sampled at ~16.7 Hz (every 3rd 20 ms frame);
                    // capture and transmission stay at the full 50 Hz cadence.
                    if (++amplitudeTick % AMPLITUDE_SAMPLE_EVERY == 0) {
                        _amplitude.value = smoothedAmplitude(valid)
                    }
                }
                // No artificial delay: AudioRecord's blocking read paces the stream
                // at exactly real time. Any gap here would reach the Gemini Live
                // ASR as choppy audio and break its voice-activity detection
                // (inputTranscription silently never fires).
            }
            // Orderly shutdown: emit the zero-padded partial frame exactly once,
            // then close the channel so the ordered sender drains and stops.
            if (stopRequested.get()) {
                chunker.remaining()?.let { queue.send(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _failures.tryEmit(DictationFailure(MIC_READ, "Microphone read failed", recoverable = true))
        } finally {
            queue.close()
            if (released.compareAndSet(false, true)) source.release()
        }
    }

    private fun smoothedAmplitude(pcm16: ByteArray): Float {
        val current = _amplitude.value
        val raw = amplitudeOf(pcm16)
        return current + AMPLITUDE_ALPHA * (raw - current)
    }

    /** RMS over a small slice of the frame, normalized to [0, 1]. */
    private fun amplitudeOf(pcm16: ByteArray): Float {
        val maxSamples = minOf(AMPLITUDE_SLICE_SAMPLES, pcm16.size / 2)
        var sum = 0.0
        var i = 0
        while (i < maxSamples) {
            val sample = (pcm16[i * 2].toInt() and 0xFF) or (pcm16[i * 2 + 1].toInt() shl 8)
            sum += (sample.toLong() * sample).toDouble()
            i++
        }
        if (i == 0) return 0f
        val rms = sqrt(sum / i)
        return (rms / MAX_PCM16_AMPLITUDE).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /**
         * Builds a mono, 16-bit, 16 kHz [AudioRecord]-backed source, or null when
         * the microphone cannot be initialized.
         *
         * Permission is enforced by the caller ([FlowRuntimeService] checks
         * RECORD_AUDIO before starting capture); init/read failures surface as
         * typed failures, never raw throws.
         */
        @SuppressLint("MissingPermission")
        fun createDefaultSource(): PcmSource? {
            val minBuffer = AudioRecord.getMinBufferSize(
                GemAudioFormat.SAMPLE_RATE_HZ,
                GemAudioFormat.CHANNEL_IN,
                GemAudioFormat.AUDIO_FORMAT,
            )
            val bufferSize = if (minBuffer > 0) minBuffer else DEFAULT_BUFFER_BYTES
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    GemAudioFormat.SAMPLE_RATE_HZ,
                    GemAudioFormat.CHANNEL_IN,
                    GemAudioFormat.AUDIO_FORMAT,
                    bufferSize,
                )
            }.getOrNull() ?: return null
            return initializedRecordSource(record)
        }

        /**
         * 0.6.0: builds a mono, 16-bit, 16 kHz [AudioRecord]-backed source pinned
         * to a specific input [device] (e.g. a connected bluetooth headset), or
         * null when the device cannot be initialized. Uses the
         * [MediaRecorder.AudioSource.VOICE_COMMUNICATION] source so the system
         * routes to the headset profile (SCO) when the device is a bluetooth
         * headset, and [AudioRecord.setPreferredDevice] pins the recording to it.
         * Init/read failures surface as typed failures, never raw throws.
         */
        @SuppressLint("MissingPermission")
        fun createDeviceSource(device: AudioDeviceInfo): PcmSource? {
            val minBuffer = AudioRecord.getMinBufferSize(
                GemAudioFormat.SAMPLE_RATE_HZ,
                GemAudioFormat.CHANNEL_IN,
                GemAudioFormat.AUDIO_FORMAT,
            )
            val bufferSize = if (minBuffer > 0) minBuffer else DEFAULT_BUFFER_BYTES
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    GemAudioFormat.SAMPLE_RATE_HZ,
                    GemAudioFormat.CHANNEL_IN,
                    GemAudioFormat.AUDIO_FORMAT,
                    bufferSize,
                )
            }.getOrNull() ?: return null
            val preferred = runCatching { record.setPreferredDevice(device) }.getOrDefault(false)
            if (!preferred) {
                record.release()
                return null
            }
            return initializedRecordSource(record)
        }

        /** Shared state/start validation and [PcmSource] adapter for a built record. */
        private fun initializedRecordSource(record: AudioRecord): PcmSource? {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                return null
            }
            return object : PcmSource {
                override fun read(out: ByteArray): Int =
                    record.read(out, 0, out.size, AudioRecord.READ_BLOCKING)

                override fun release() {
                    runCatching { record.stop() }
                    record.release()
                }
            }
        }

        private const val QUEUE_CAPACITY = 64
        /** One 20 ms frame (640 bytes at 16 kHz mono PCM16): reads are blocking and
         *  pace the stream at exactly real time. */
        private const val READ_BUFFER_BYTES = 640
        private const val AMPLITUDE_SLICE_SAMPLES = 256
        private const val AMPLITUDE_ALPHA = 0.5f
        /** Publish the waveform state every Nth 20 ms frame (~16.7 Hz at 50 Hz reads). */
        private const val AMPLITUDE_SAMPLE_EVERY = 3
        private const val MAX_PCM16_AMPLITUDE = 32768.0
        private const val DEFAULT_BUFFER_BYTES = 4096
        private const val MIC_INIT = "MIC_INIT"
        private const val MIC_READ = "MIC_READ"
    }
}
