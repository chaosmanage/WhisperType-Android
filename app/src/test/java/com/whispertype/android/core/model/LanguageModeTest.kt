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
    fun `English Medium refines into publication-grade writing`() {
        val instruction = LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.MEDIUM)
        assertNotNull(instruction)
        assertTrue(instruction.contains("refine it into exceptional, publication-grade writing"))
        assertTrue(instruction.contains("professional editor"))
        assertTrue(instruction.contains("preserve every idea and every point"))
    }

    @Test
    fun `English High transforms into masterfully crafted prose`() {
        val instruction = LanguageMode.ENGLISH.liveInstruction(TranscriptionStyle.HIGH)
        assertNotNull(instruction)
        assertTrue(instruction.contains("transform it into masterfully crafted, publication-grade prose"))
        assertTrue(instruction.contains("professional editor would publish"))
        assertTrue(instruction.contains("preserve every idea and every point"))
    }

    @Test
    fun `Hinglish None keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.NONE)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never output a single Devanagari"))
        assertTrue(instruction.contains("Latin (Roman) script"))
        assertTrue(instruction.contains("exactly as spoken"))
    }

    @Test
    fun `Hinglish Low keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.LOW)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never output a single Devanagari"))
        assertTrue(instruction.contains("basic sentence punctuation"))
    }

    @Test
    fun `Hinglish Medium keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.MEDIUM)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never output a single Devanagari"))
        assertTrue(instruction.contains("refine it into exceptional, publication-grade writing"))
    }

    @Test
    fun `Hinglish High keeps Latin-script rule`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.HIGH)
        assertNotNull(instruction)
        assertTrue(instruction.contains("never output a single Devanagari"))
        assertTrue(instruction.contains("transform it into masterfully crafted, publication-grade prose"))
    }

    @Test
    fun `Hinglish defaults to Medium style`() {
        assertEquals(
            LanguageMode.HINGLISH.liveInstruction(TranscriptionStyle.MEDIUM),
            LanguageMode.HINGLISH.liveInstruction(),
        )
    }

    @Test
    fun `Hinglish never translates English speech into Hinglish`() {
        for (style in TranscriptionStyle.entries) {
            val instruction = LanguageMode.HINGLISH.liveInstruction(style)
            assertTrue(instruction.contains("NEVER translate the user's speech"), "$style")
            assertTrue(instruction.contains("never convert English words"), "$style")
            assertTrue(instruction.contains("Only the Hindi words"), "$style")
        }
    }

    @Test
    fun `every style carries the appropriate verbatim or content clause`() {
        for (mode in LanguageMode.entries) {
            for (style in TranscriptionStyle.entries) {
                val instruction = mode.liveInstruction(style)
                val strict = style == TranscriptionStyle.NONE || style == TranscriptionStyle.LOW
                if (strict) {
                    assertTrue(instruction.contains("every single word"), "$mode $style")
                    assertTrue(instruction.contains("NEVER summarize"), "$mode $style")
                } else {
                    assertTrue(instruction.contains("preserve every idea and every point"), "$mode $style")
                    assertTrue(instruction.contains("never omit"), "$mode $style")
                    assertTrue(
                        instruction.contains("never change, invent, or drop facts", ignoreCase = true),
                        "$mode $style",
                    )
                }
                assertTrue(instruction.contains("Output ONLY the repeated text"), "$mode $style")
                if (mode == LanguageMode.HINGLISH) {
                    assertTrue(instruction.contains("never output a single Devanagari"), "$mode $style")
                }
            }
        }
    }
}
