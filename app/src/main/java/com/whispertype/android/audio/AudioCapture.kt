package com.whispertype.android.audio

import android.media.AudioRecord
import android.media.MediaRecorder
import android.annotation.SuppressLint
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * Producer pipeline: [PcmSource] -> [Chunker] -> [BoundedAudioQueue], with a
 * ~20 Hz amplitude [StateFlow]. [stop] is idempotent and releases the source
 * exactly once. Read/permission/init failures surface as typed failures on a
 * failure flow, never as raw throws.
 */
class AudioCapture(
    private val sampleRateHz: Int = GemAudioFormat.SAMPLE_RATE_HZ,
    private val sourceFactory: () -> PcmSource? = { createDefaultSource() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val queue = BoundedAudioQueue<AudioChunk>(QUEUE_CAPACITY)

    /** Bounded FIFO of 20 ms frames (capacity 64). Closed by [stop]. */
    val chunks: ReceiveChannel<AudioChunk> = queue.channel

    private val _amplitude = MutableStateFlow(0f)

    /** Smoothed input level in [0, 1], updated at ~20 Hz. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _failures = MutableSharedFlow<DictationFailure>(replay = 1, extraBufferCapacity = 4)

    /** Typed terminal failures (mic init/read); the latest is replayed to late subscribers. */
    val failures: SharedFlow<DictationFailure> = _failures.asSharedFlow()

    private val chunker = Chunker(sampleRateHz)

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile
    private var source: PcmSource? = null

    @Volatile
    private var producerJob: Job? = null

    /** Starts capture. Safe to call once; subsequent calls return [AudioStartResult.Started]. */
    fun start(): AudioStartResult {
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

    /** Stops the producer, releases the source exactly once, and closes the queue. Idempotent. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        producerJob?.cancel()
        if (released.compareAndSet(false, true)) source?.release()
        queue.close()
    }

    /**
     * Drains any partial final frame from the chunker and trySends it to the queue.
     * Call during finalization, before or without [stop], to avoid dropping trailing audio.
     */
    fun forceRemainingChunk(): AudioChunk? {
        val chunk = chunker.remaining()
        if (chunk != null) queue.trySend(chunk)
        return chunk
    }

    private suspend fun producerLoop(source: PcmSource) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (!stopped.get()) {
                val read = source.read(buffer)
                if (read < 0) {
                    _failures.tryEmit(DictationFailure(MIC_READ, "Microphone read failed", recoverable = true))
                    return
                }
                if (read > 0) {
                    val valid = if (read < buffer.size) buffer.copyOf(read) else buffer
                    for (chunk in chunker.push(valid)) {
                        queue.send(chunk)
                    }
                    _amplitude.value = smoothedAmplitude(valid)
                }
                delay(LOOP_CADENCE_MILLIS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _failures.tryEmit(DictationFailure(MIC_READ, "Microphone read failed", recoverable = true))
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
        private const val READ_BUFFER_BYTES = 5120
        private const val LOOP_CADENCE_MILLIS = 50L
        private const val AMPLITUDE_SLICE_SAMPLES = 256
        private const val AMPLITUDE_ALPHA = 0.5f
        private const val MAX_PCM16_AMPLITUDE = 32768.0
        private const val DEFAULT_BUFFER_BYTES = 4096
        private const val MIC_INIT = "MIC_INIT"
        private const val MIC_READ = "MIC_READ"
    }
}
