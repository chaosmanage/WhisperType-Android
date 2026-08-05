package com.whispertype.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageModeTest {

    @Test
    fun `English None uses no live instruction`() {
        assertNull(LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.NONE))
    }

    @Test
    fun `English Low uses light cleanup instruction`() {
        val instruction = LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.LOW)
        assertNotNull(instruction)
        assertTrue(instruction.contains("light cleanup"))
        assertTrue(instruction.contains("punctuation"))
        assertTrue(instruction.contains("capitalization"))
    }

    @Test
    fun `English Medium uses clean written text instruction`() {
        val instruction = LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.MEDIUM)
        assertNotNull(instruction)
        assertTrue(instruction.contains("clean written text"))
        assertTrue(instruction.contains("proper punctuation"))
        assertTrue(instruction.contains("grammar"))
    }

    @Test
    fun `English High uses polished instruction`() {
        val instruction = LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.HIGH)
        assertNotNull(instruction)
        assertTrue(instruction.contains("polished, well-structured"))
        assertTrue(instruction.contains("logical organization"))
    }

    @Test
    fun `Hinglish None keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.NONE)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never in Devanagari script"))
        assertTrue(instruction.contains("verbatim"))
    }

    @Test
    fun `Hinglish Low keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.LOW)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never in Devanagari script"))
        assertTrue(instruction.contains("light cleanup"))
    }

    @Test
    fun `Hinglish Medium keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.MEDIUM)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never in Devanagari script"))
        assertTrue(instruction.contains("clean written text"))
    }

    @Test
    fun `Hinglish High keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.HIGH)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never in Devanagari script"))
        assertTrue(instruction.contains("polished, well-structured"))
    }

    @Test
    fun `Hinglish defaults to Medium style`() {
        assertEquals(
            LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.MEDIUM),
            LanguageMode.HINGLISH.liveInstruction(),
        )
    }
}
