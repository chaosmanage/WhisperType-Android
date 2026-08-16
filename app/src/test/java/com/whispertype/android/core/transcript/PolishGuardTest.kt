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
    fun `HIGH accepts a structured rewrite with bullets`() {
        // 0.8.1: HIGH is an editor, so an enumerated dictation may come back as
        // a bulleted list. The guard must accept it: every fact traces back.
        val raw = "so we need to fix three things first the login is broken second the payments " +
            "page crashes on load and third the emails are not going out so can we please get " +
            "these done this week"
        val polished = "Please address these three issues this week:\n" +
            "- Login is broken.\n" +
            "- The payments page crashes on load.\n" +
            "- Emails are not being sent."
        assertIs<PolishGuard.Verdict.Accept>(verdict(raw, polished, TranscriptionStyle.HIGH))
    }

    @Test
    fun `HIGH rejects a rewrite that invents new facts`() {
        val raw = "so the login page is broken and users cannot sign in"
        val polished = "The login page has been down since Tuesday due to a misconfigured load " +
            "balancer, and roughly forty percent of users are affected."
        val v = verdict(raw, polished, TranscriptionStyle.HIGH)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertTrue(v.code == "invented" || v.code == "too_long", "got ${v.code}")
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

    // ------------------------------------------------------------------
    // Answer detection (the 0.8.0 device regression)
    // ------------------------------------------------------------------

    @Test
    fun `an answer to a dictated English question in Hinglish mode is rejected`() {
        // Shipped broken once: Hinglish mode + an English question came back as
        // an answer in romanized Hindi, and the script-only check accepted it.
        val raw = "can you tell me what the weather is like today"
        val polished = "Maine dekha hai ki aaj ka din bahut garm hai aur sunehra rahega."
        val v = verdict(raw, polished, TranscriptionStyle.MEDIUM, LanguageMode.HINGLISH)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertEquals("retention", v.code)
    }

    @Test
    fun `an answer that invents content is rejected`() {
        // A question that comes back as an answer grows beyond the level's
        // budget. Depending on how much it grows, it is rejected as either
        // `too_long` (the length bound fires first) or `invented` (the
        // new-word bound fires first) - both are correct rejections; the point
        // is that it must never be inserted.
        val raw = "what is the capital of france"
        val polished = "The capital of France is Paris, a city known for the Eiffel Tower and its " +
            "world-famous museums along the Seine."
        val v = verdict(raw, polished, TranscriptionStyle.MEDIUM)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertTrue(
            v.code == "invented" || v.code == "too_long",
            "an answer must be rejected, got ${v.code}",
        )
    }

    @Test
    fun `a concise same-language answer is caught by the new-word bound`() {
        // Every raw word survives (so retention alone passes) but the reply
        // appends an answer word - the invention check is what catches it.
        val raw = "what is the capital of france"
        val polished = "The capital of France is Paris, full stop."
        val v = verdict(raw, polished, TranscriptionStyle.MEDIUM)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertEquals("invented", v.code)
    }

    @Test
    fun `a dictated question transcribed as a question is accepted`() {
        val raw = "can you tell me what the weather is like today"
        listOf(TranscriptionStyle.LOW, TranscriptionStyle.MEDIUM).forEach { style ->
            assertIs<PolishGuard.Verdict.Accept>(
                verdict(raw, "Can you tell me what the weather is like today?", style),
                "level $style",
            )
        }
    }

    @Test
    fun `English spoken in Hinglish mode is validated like English`() {
        val raw = "so i was reviewing the pull request and i think we should merge it tomorrow"
        val polished = "So I was reviewing the pull request, and I think we should merge it tomorrow."
        assertIs<PolishGuard.Verdict.Accept>(
            verdict(raw, polished, TranscriptionStyle.MEDIUM, LanguageMode.HINGLISH),
        )
    }

    @Test
    fun `a cross-script reply that drops the English loanwords is rejected`() {
        val raw = "मुझे कल का plan cancel करना है क्योंकि meeting postpone हो गई"
        val polished = "Mujhe kal ka kaam rok dena hai kyonki baithak aage badh gayi."
        val v = verdict(raw, polished, TranscriptionStyle.MEDIUM, LanguageMode.HINGLISH)
        assertIs<PolishGuard.Verdict.Reject>(v)
        assertEquals("loanwords_lost", v.code)
    }

    @Test
    fun `a cross-script reply that keeps the loanwords is accepted`() {
        val raw = "मुझे कल का plan cancel करना है क्योंकि meeting postpone हो गई"
        val polished = "Mujhe kal ka plan cancel karna hai kyonki meeting postpone ho gayi."
        assertIs<PolishGuard.Verdict.Accept>(
            verdict(raw, polished, TranscriptionStyle.MEDIUM, LanguageMode.HINGLISH),
        )
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
