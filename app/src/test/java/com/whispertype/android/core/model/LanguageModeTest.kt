package com.whispertype.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LanguageModeTest {

    @Test
    fun `English uses no live instruction`() {
        assertNull(LanguageMode.ENGLISH.liveInstruction())
    }

    @Test
    fun `Hinglish uses a Latin-script instruction`() {
        val instruction = LanguageMode.HINGLISH.liveInstruction()
        assertNotNull(instruction)
        assertEquals(true, instruction.contains("Latin script"))
        assertEquals(true, instruction.contains("never in Devanagari"))
    }
}
