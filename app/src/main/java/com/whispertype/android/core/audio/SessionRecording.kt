package com.whispertype.android.core.audio

/**
 * Bounded in-memory PCM16 recording of one dictation session (0.4.2
 * reliability). Every captured 20 ms frame is appended in [streamAudio] so the
 * full user audio is available to the audio-recovery failsafe: when neither the
 * live echo nor the raw ASR plausibly covers the recorded speech, the recording
 * is re-transcribed via the REST API instead of losing the dictation.
 *
 * Bounded by [maxBytes] (~10 minutes of 16 kHz mono at the default); on
 * overflow the recording is dropped (never partially kept) so recovery can
 * never silently produce a truncated transcript.
 */
class SessionRecording(
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {

    private val parts = ArrayList<ByteArray>()
    private var totalBytes: Int = 0

    /** True when the cap was exceeded and the recording was dropped. */
    var overflowed: Boolean = false
        private set

    /** Total captured PCM bytes (0 until audio arrives). */
    val byteCount: Int
        get() = totalBytes

    fun append(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (overflowed) return
        if (totalBytes + pcm.size > maxBytes) {
            overflowed = true
            parts.clear()
            totalBytes = 0
            return
        }
        parts.add(pcm)
        totalBytes += pcm.size
    }

    /** The full session audio as a 16 kHz mono PCM16 WAV, or null when empty or
     *  overflowed. */
    fun toWav(sampleRateHz: Int = 16_000): ByteArray? {
        if (overflowed || totalBytes == 0 || parts.isEmpty()) return null
        val dataSize = totalBytes
        val wav = ByteArray(HEADER_BYTES + dataSize)
        writeHeader(wav, sampleRateHz, dataSize)
        var offset = HEADER_BYTES
        for (part in parts) {
            part.copyInto(wav, offset)
            offset += part.size
        }
        return wav
    }

    private fun writeHeader(wav: ByteArray, sampleRateHz: Int, dataSize: Int) {
        val byteRate = sampleRateHz * 2 // mono 16-bit
        fun putLE(pos: Int, value: Int, bytes: Int) {
            var v = value
            for (i in 0 until bytes) {
                wav[pos + i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
        }
        val riff = "RIFF".toByteArray(Charsets.US_ASCII)
        riff.copyInto(wav, 0)
        putLE(4, 36 + dataSize, 4)
        val wave = "WAVE".toByteArray(Charsets.US_ASCII)
        wave.copyInto(wav, 8)
        val fmt = "fmt ".toByteArray(Charsets.US_ASCII)
        fmt.copyInto(wav, 12)
        putLE(16, 16, 4) // fmt chunk size
        putLE(20, 1, 2) // PCM
        putLE(22, 1, 2) // mono
        putLE(24, sampleRateHz, 4)
        putLE(28, byteRate, 4)
        putLE(32, 2, 2) // block align
        putLE(34, 16, 2) // bits per sample
        val data = "data".toByteArray(Charsets.US_ASCII)
        data.copyInto(wav, 36)
        putLE(40, dataSize, 4)
    }

    companion object {
        const val HEADER_BYTES = 44

        /** ~10 minutes of 16 kHz mono PCM16 (16_000 * 2 * 600). */
        const val DEFAULT_MAX_BYTES: Int = 19_200_000
    }
}
