package com.whispertype.android.core.transcript

import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.TranscriptionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [PolishGuard] is the enforcement behind the style calibration: the remote
 * model can only be *asked* not to restructure, so anything beyond the level's
 * allowance must be rejected and the raw ASR inserted instead.
 */
class PolishGuardTest {

    private fun verdict(
        raw: String,
        polished: String,
        style: TranscriptionStyle,
        language: LanguageMode = LanguageMode.ENGLISH,
    ) = PolishGuard.evaluate(raw, polished, language, style)

    // ------------------------------------------------------------------
    // English
    // ------------------------------------------------------------------

    @Test
    fun `LOW accepts filler removal and punctuation`() {
        val raw = "um so i was thinking that we we should ship it tomorrow uh if the tests pass"
        val polished = "So I was thinking that we should ship it tomorrow, if the tests pass."
        assertIs<PolishGuard.Verdict.Accept>(verdict(raw, polished, TranscriptionStyle.LOW))
    }

    @Test
    fun `LOW rejects a reworded sentence`() {
        val raw = "um so i was thinking that we should ship it tomorrow if the tests pass"
        val polished = "We will deploy the release once the automated suite has fully validated it."
        val v = verdict(raw, polished, TranscriptionStyle.LOW)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertEquals("retention", v.code)
    }

    @Test
    fun `MEDIUM accepts a grammar fix that preserves structure`() {
        val raw = "we was going to the store and then i seen that the prices was gone up twenty percent"
        val polished = "We were going to the store, and then I saw that the prices had gone up twenty percent."
        assertIs<PolishGuard.Verdict.Accept>(verdict(raw, polished, TranscriptionStyle.MEDIUM))
    }

    @Test
    fun `MEDIUM rejects a wholesale rewrite`() {
        // This is the 0.7.x failure the owner reported: the model rewrote the
        // dictation into publication prose instead of correcting it.
        val raw = "so the build is slow because we run all the tests every time and the cache is " +
            "not working so every commit takes ten minutes"
        val polished = "Continuous integration throughput has degraded substantially. Two " +
            "compounding factors are responsible, and remediation should be prioritised " +
            "immediately by the platform team."
        val v = verdict(raw, polished, TranscriptionStyle.MEDIUM)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertEquals("retention", v.code)
    }

    @Test
    fun `MEDIUM rejects a summary that drops most of the speech`() {
        val raw = "i went to the market in the morning and bought vegetables and then i met " +
            "rahul near the station and we talked about the trip we are planning for december"
        val polished = "I ran errands and met Rahul."
        val v = verdict(raw, polished, TranscriptionStyle.MEDIUM)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertTrue(v.code == "too_short" || v.code == "retention", "unexpected code ${v.code}")
    }

    @Test
    fun `HIGH permits a rewrite that MEDIUM would reject`() {
        val raw = "so basically the build is slow because we run all the tests every time and " +
            "also the cache is not working so like every commit takes ten minutes"
        val polished = "The build is slow for two reasons: we run the full test suite on every " +
            "commit, and the cache is not working. As a result, each commit takes ten minutes."
        assertIs<PolishGuard.Verdict.Accept>(verdict(raw, polished, TranscriptionStyle.HIGH))
    }

    @Test
    fun `blank output is always rejected`() {
        TranscriptionStyle.entries.forEach { style ->
            val v = verdict("some real words here", "   ", style)
            assertIs<PolishGuard.Verdict.Reject>(v)
            assertEquals("blank", v.code)
        }
    }

    @Test
    fun `padding the transcript with invented content is rejected at LOW`() {
        val raw = "send the file when you get a chance"
        val polished = "Send the file when you get a chance. Also, please review the attached " +
            "document, update the tracker, and let me know if you need anything else from me."
        val v = verdict(raw, polished, TranscriptionStyle.LOW)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertEquals("too_long", v.code)
    }

    @Test
    fun `filler removal is never counted against retention`() {
        // Every non-filler word survives, so even an all-filler raw passes LOW.
        val raw = "um uh ah er hmm the meeting is at five"
        val polished = "The meeting is at five."
        assertIs<PolishGuard.Verdict.Accept>(verdict(raw, polished, TranscriptionStyle.LOW))
    }

    // ------------------------------------------------------------------
    // Hinglish (cross-script)
    // ------------------------------------------------------------------

    @Test
    fun `Hinglish accepts romanized output`() {
        val raw = "मैं आज सुबह उठा और सोचा कि मुझे काम पर जल्दी जाना चाहिए"
        val polished = "Main aaj subah utha aur socha ki mujhe kaam par jaldi jaana chahiye."
        assertIs<PolishGuard.Verdict.Accept>(
            verdict(raw, polished, TranscriptionStyle.MEDIUM, LanguageMode.HINGLISH),
        )
    }

    @Test
    fun `Hinglish rejects output that stayed in Devanagari`() {
        val raw = "मैं आज सुबह उठा और सोचा कि मुझे काम पर जल्दी जाना चाहिए"
        val polished = "मैं आज सुबह उठा और सोचा कि मुझे काम पर जल्दी जाना चाहिए।"
        val v = verdict(raw, polished, TranscriptionStyle.MEDIUM, LanguageMode.HINGLISH)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertEquals("not_romanized", v.code)
    }

    @Test
    fun `Hinglish keeps English loanwords without tripping the script check`() {
        val raw = "मैं अभी office जा रहा हूँ फिर मैं आपको call करूंगा"
        val polished = "Main abhi office ja raha hoon, phir main aapko call karunga."
        assertIs<PolishGuard.Verdict.Accept>(
            verdict(raw, polished, TranscriptionStyle.LOW, LanguageMode.HINGLISH),
        )
    }

    @Test
    fun `Hinglish rejects a drastically shortened reply`() {
        val raw = "मैं आज सुबह उठा और सोचा कि मुझे काम पर जल्दी जाना चाहिए लेकिन traffic बहुत था " +
            "इसलिए मैं late हो गया और meeting miss कर दी"
        val polished = "Main late tha."
        val v = verdict(raw, polished, TranscriptionStyle.MEDIUM, LanguageMode.HINGLISH)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertEquals("too_short", v.code)
    }

    @Test
    fun `a mostly-Latin reply with one stray Devanagari word is accepted`() {
        val raw = "मैं ठीक हूँ तुम कैसे हो"
        val polished = "Main theek hoon, tum कैसे ho."
        assertIs<PolishGuard.Verdict.Accept>(
            verdict(raw, polished, TranscriptionStyle.LOW, LanguageMode.HINGLISH),
        )
    }
}
