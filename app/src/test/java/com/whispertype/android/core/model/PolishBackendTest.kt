package com.whispertype.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 0.7.0: backend routing rules for session echo stripping and instructions. */
class PolishBackendTest {

    @Test
    fun `LIVE_ECHO requires the echo for Hinglish`() {
        assertEquals(
            true,
            needsLiveEcho(PolishBackend.LIVE_ECHO, LanguageMode.HINGLISH, TranscriptionStyle.MEDIUM),
        )
    }

    @Test
    fun `LIVE_ECHO requires the echo for polished styles even in English`() {
        assertEquals(
            true,
            needsLiveEcho(PolishBackend.LIVE_ECHO, LanguageMode.ENGLISH, TranscriptionStyle.HIGH),
        )
    }

    @Test
    fun `LIVE_ECHO skips the echo for raw English`() {
        assertEquals(
            false,
            needsLiveEcho(PolishBackend.LIVE_ECHO, LanguageMode.ENGLISH, TranscriptionStyle.NONE),
        )
    }

    @Test
    fun `GROQ and NONE never need the live echo`() {
        for (backend in listOf(PolishBackend.GROQ, PolishBackend.NONE, PolishBackend.AUTO)) {
            assertEquals(false, needsLiveEcho(backend, LanguageMode.HINGLISH, TranscriptionStyle.MEDIUM))
            assertEquals(false, needsLiveEcho(backend, LanguageMode.ENGLISH, TranscriptionStyle.HIGH))
        }
    }

    @Test
    fun `the live instruction exists only for LIVE_ECHO`() {
        val instruction = liveInstructionFor(
            PolishBackend.LIVE_ECHO,
            LanguageMode.HINGLISH,
            TranscriptionStyle.MEDIUM,
        )
        assertNotNull(instruction)
        assertNull(liveInstructionFor(PolishBackend.GROQ, LanguageMode.HINGLISH, TranscriptionStyle.MEDIUM))
        assertNull(liveInstructionFor(PolishBackend.NONE, LanguageMode.HINGLISH, TranscriptionStyle.MEDIUM))
        assertNull(liveInstructionFor(PolishBackend.AUTO, LanguageMode.HINGLISH, TranscriptionStyle.MEDIUM))
    }

    @Test
    fun `outcome codes are distinct and typed`() {
        val codes = PolishOutcome.entries.map { it.name }
        assertEquals(codes.size, codes.toSet().size)
    }
}