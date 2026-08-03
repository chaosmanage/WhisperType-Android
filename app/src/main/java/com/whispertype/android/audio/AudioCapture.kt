package com.whispertype.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Streams microphone audio as 20 ms mono PCM16 [AudioChunk]s on a dedicated
 * daemon thread. Runtime microphone permission enforcement belongs to the
 * caller, hence the suppressed RECORD_AUDIO-gated platform calls.
 */
@SuppressLint("MissingPermission")
open class AudioCapture(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sampleRateHz: Int = SAMPLE_RATE_HZ,
    private val onError: ((code: String, message: String, recoverable: Boolean) -> Unit)? = null,
) {

    sealed interface AudioStartResult {
        data object Started : AudioStartResult
        data class Failed(
            val code: String,
            val message: String,
            val recoverable: Boolean,
        ) : AudioStartResult
    }

    /** Test seam replacing the [AudioRecord] constructor for fake-based flow tests. */
    internal var recordFactory: ((sampleRate: Int, channel: Int, encoding: Int, bufferSize: Int) -> AudioRecord)? = null

    @Volatile
    private var running = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var recordReleased = false

    private var captureThread: Thread? = null
    private var sequenceNumber = 0L

    /**
     * Begins capture, invoking [onChunk] on the capture thread for each
     * 20 ms buffer. Calling while already running is a no-op.
     */
    open fun start(onChunk: (AudioChunk) -> Unit): AudioStartResult {
        if (running) return AudioStartResult.Started
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRateHz, CHANNEL, ENCODING)
        val bufferSize = maxOf(CHUNK_BYTES, minBufferSize) * 2
        val record = try {
            recordFactory?.invoke(sampleRateHz, CHANNEL, ENCODING, bufferSize)
                ?: AudioRecord(MediaRecorder.AudioSource.MIC, sampleRateHz, CHANNEL, ENCODING, bufferSize)
        } catch (e: SecurityException) {
            return AudioStartResult.Failed("MIC_PERMISSION", "Microphone permission denied", true)
        } catch (e: Exception) {
            return AudioStartResult.Failed("MIC_UNAVAILABLE", e.message ?: "Failed to create AudioRecord", true)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return AudioStartResult.Failed("MIC_UNAVAILABLE", "Audio input unavailable", true)
        }
        audioRecord = record
        recordReleased = false
        sequenceNumber = 0L
        running = true
        val thread = Thread({ captureLoop(record, onChunk) }, CAPTURE_THREAD_NAME).apply { isDaemon = true }
        captureThread = thread
        thread.start()
        return AudioStartResult.Started
    }

    /** Stops capture and releases the input. Safe to call repeatedly. */
    open fun stop() {
        running = false
        val record = audioRecord
        if (record != null) {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
        }
        captureThread?.join(JOIN_TIMEOUT_MILLIS)
        if (record != null) releaseIfNeeded(record)
        audioRecord = null
        captureThread = null
    }

    private fun captureLoop(record: AudioRecord, onChunk: (AudioChunk) -> Unit) {
        try {
            try {
                record.startRecording()
            } catch (e: SecurityException) {
                onError?.invoke("MIC_PERMISSION", e.message ?: "Microphone permission denied", true)
                return
            } catch (e: IllegalStateException) {
                onError?.invoke("MIC_LOST", e.message ?: "Failed to start recording", true)
                return
            }
            while (running) {
                val buffer = ByteArray(CHUNK_BYTES)
                val read = record.read(buffer, 0, CHUNK_BYTES)
                when {
                    read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        onError?.invoke("MIC_LOST", "Audio input stream lost", true)
                        break
                    }
                    read < 0 -> continue
                    read == 0 -> continue
                    else -> onChunk(
                        AudioChunk(
                            sequenceNumber = sequenceNumber++,
                            pcm16Bytes = buffer.copyOf(),
                            capturedAtMillis = clock(),
                        ),
                    )
                }
            }
        } finally {
            releaseIfNeeded(record)
        }
    }

    private fun releaseIfNeeded(record: AudioRecord) {
        if (!recordReleased) {
            recordReleased = true
            record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val CHUNK_BYTES = 640
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CAPTURE_THREAD_NAME = "wt-audio-capture"
        private const val JOIN_TIMEOUT_MILLIS = 2000L
    }
}
