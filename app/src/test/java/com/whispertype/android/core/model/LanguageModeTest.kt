package com.whispertype.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageModeTest {

    @Test
    fun `English None asks for a verbatim echo`() {
        val instruction = LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.NONE)
        assertNotNull(instruction)
        assertTrue(instruction.contains("Repeat the user's speech back exactly as spoken"))
        assertTrue(instruction.contains("filler"))
        assertTrue(instruction.contains("Output ONLY the repeated text and nothing else"))
    }

    @Test
    fun `English Low uses light cleanup instruction`() {
        val instruction = LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.LOW)
        assertNotNull(instruction)
        assertTrue(instruction.contains("basic sentence punctuation and capitalization"))
        assertTrue(instruction.contains("Output ONLY the repeated text and nothing else"))
    }

    @Test
    fun `English Medium lightly polishes and removes frequent fillers`() {
        val instruction = LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.MEDIUM)
        assertNotNull(instruction)
        assertTrue(instruction.contains("lightly polish"))
        assertTrue(instruction.contains("frequent fillers"))
        assertTrue(instruction.contains("grammar"))
    }

    @Test
    fun `English High fully polishes and removes all fillers`() {
        val instruction = LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.HIGH)
        assertNotNull(instruction)
        assertTrue(instruction.contains("fully polish"))
        assertTrue(instruction.contains("filler"))
        assertTrue(instruction.contains("well-formed sentences"))
    }

    @Test
    fun `Hinglish None keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.NONE)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never in Devanagari"))
        assertTrue(instruction.contains("Roman (Latin) script"))
        assertTrue(instruction.contains("exactly as spoken"))
    }

    @Test
    fun `Hinglish Low keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.LOW)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never in Devanagari"))
        assertTrue(instruction.contains("basic sentence punctuation"))
    }

    @Test
    fun `Hinglish Medium keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.MEDIUM)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never in Devanagari"))
        assertTrue(instruction.contains("lightly polish"))
    }

    @Test
    fun `Hinglish High keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.HIGH)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never in Devanagari"))
        assertTrue(instruction.contains("fully polish"))
    }

    @Test
    fun `Hinglish defaults to Medium style`() {
        assertEquals(
            LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.MEDIUM),
            LanguageMode.HINGLISH.liveInstruction(),
        )
    }
}
