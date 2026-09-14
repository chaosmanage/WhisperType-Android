package com.whispertype.android.core.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertionTextTest {

    @Test
    fun `appends a single trailing space to plain text`() {
        assertEquals("Hey, lets meet tomorrow. ", InsertionText.withTrailingSpace("Hey, lets meet tomorrow."))
    }

    @Test
    fun `leaves an existing trailing space unchanged`() {
        assertEquals("hello ", InsertionText.withTrailingSpace("hello "))
    }

    @Test
    fun `leaves trailing newline and tab unchanged`() {
        assertEquals("hello\n", InsertionText.withTrailingSpace("hello\n"))
        assertEquals("hello\t", InsertionText.withTrailingSpace("hello\t"))
    }

    @Test
    fun `leaves blank text unchanged`() {
        assertEquals("", InsertionText.withTrailingSpace(""))
        assertEquals("   ", InsertionText.withTrailingSpace("   "))
    }

    @Test
    fun `is idempotent on repeated application`() {
        val once = InsertionText.withTrailingSpace("hello")
        assertEquals("hello ", once)
        assertEquals(once, InsertionText.withTrailingSpace(once))
    }
}
