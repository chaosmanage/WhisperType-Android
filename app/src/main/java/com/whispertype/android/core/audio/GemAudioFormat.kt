package com.whispertype.android.core.audio

/**
 * Canonical audio pipeline format for the Gemini Live backend. Chunk sizes are
 * always derived from the sample rate and the 20 ms frame duration — never from
 * a magic byte count. [SAMPLE_RATE_HZ] is the Gemini Live supported input rate.
 *
 * [CHANNEL_IN] and [AUDIO_FORMAT] mirror the android.media.AudioFormat values
 * (CHANNEL_IN_MONO == 16, ENCODING_PCM_16BIT == 2); this module stays
 * framework-free, and the platform audio layer validates both against
 * AudioFormat when it configures capture.
 */
object GemAudioFormat {
    /** Officially supported Gemini Live input sample rate (Hz). */
    const val SAMPLE_RATE_HZ = 16000

    /** Exact frame duration in milliseconds. */
    const val FRAME_MILLIS = 20

    /** Logical channel count. */
    const val CHANNELS = 1

    /** AudioRecord input channel mask (mono); mirrors AudioFormat.CHANNEL_IN_MONO. */
    const val CHANNEL_IN = 16

    /** AudioRecord PCM encoding (16-bit signed little-endian); mirrors AudioFormat.ENCODING_PCM_16BIT. */
    const val AUDIO_FORMAT = 2

    /** Bytes per single PCM16 sample. */
    const val CHAR_BYTES = 2

    /** Bytes per 20 ms frame at [SAMPLE_RATE_HZ]: 20/1000 * 16000 * 2. */
    val bytesPerFrame: Int get() = FRAME_MILLIS * SAMPLE_RATE_HZ * CHAR_BYTES / 1000
}
