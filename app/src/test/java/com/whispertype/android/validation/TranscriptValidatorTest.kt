package com.whispertype.android.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptValidatorTest {

    private val validator = TranscriptValidator

    @Test
    fun validDictationPasses() {
        assertEquals(
            TranscriptValidationResult.Valid,
            validator.validate("okay so we need to fix the release build before Friday"),
        )
    }

    @Test
    fun validDictationKeepsUrlsEmailsAndNumbers() {
        assertEquals(
            TranscriptValidationResult.Valid,
            validator.validate("please check the link https://example.com/abc?x=1 and email k@example.com"),
        )
    }

    @Test
    fun shortUtterancesPass() {
        assertEquals(TranscriptValidationResult.Valid, validator.validate("ok"))
        assertEquals(TranscriptValidationResult.Valid, validator.validate("yes"))
        assertEquals(TranscriptValidationResult.Valid, validator.validate("42"))
    }

    @Test
    fun emptyOutputRejected() {
        val result = validator.validate("")
        assertRejected(result, "emptyOutput")
    }

    @Test
    fun whitespaceOnlyRejected() {
        val result = validator.validate("   \t\n ")
        assertRejected(result, "whitespaceOnly")
    }

    @Test
    fun punctuationOnlyRejected() {
        val result = validator.validate("...!!!???;;")
        assertRejected(result, "punctuationOnly")
    }

    @Test
    fun symbolOnlyRejected() {
        val result = validator.validate("\$\$\$\$&&&\u20AC\u20AC\u20AC")
        assertRejected(result, "symbolOnly")
    }

    @Test
    fun repeatedDotsRejected() {
        val result = validator.validate("wait wait.. um.. hold on")
        assertRejected(result, "repeatedDotsOrPipes")
    }

    @Test
    fun repeatedPipesRejected() {
        val result = validator.validate("and then|| and then")
        assertRejected(result, "repeatedDotsOrPipes")
    }

    @Test
    fun conversationalPreamblesRejected() {
        assertRejected(validator.validate("Sure, here is the transcript."), "conversationalPreamble")
        assertRejected(validator.validate("Here is the transcript of the call."), "conversationalPreamble")
        assertRejected(validator.validate("The transcript is ready."), "conversationalPreamble")
        assertRejected(validator.validate("Certainly! I can help with that."), "conversationalPreamble")
        assertRejected(validator.validate("Got it, let me summarize."), "conversationalPreamble")
        assertRejected(validator.validate("Okay, so here is what happened."), "conversationalPreamble")
        assertRejected(validator.validate("Of course, I understand."), "conversationalPreamble")
        assertRejected(validator.validate("No problem, happy to assist."), "conversationalPreamble")
        assertRejected(validator.validate("\u2014 the meeting ran long"), "conversationalPreamble")
    }

    @Test
    fun legitTextStartingWithPreambleWordNotRejected() {
        assertEquals(TranscriptValidationResult.Valid, validator.validate("sure is better than maybe"))
        assertEquals(TranscriptValidationResult.Valid, validator.validate("okay sounds good to me"))
    }

    @Test
    fun implausibleLengthExpansionRejected() {
        val rules = CorruptionRules.standard(5, 40)
        val result = validator.validate("one two three four five six seven eight nine ten", rules)
        assertRejected(result, "implausibleLengthExpansion")
        assertEquals(
            TranscriptValidationResult.Valid,
            validator.validate("one two three", rules),
        )
    }

    @Test
    fun defaultLengthCeilingPassesLongRealisticDictation() {
        val sentence = "we need to double check the payment flow before we ship the next release "
        val repeated = sentence.repeat(8)
        assertEquals(TranscriptValidationResult.Valid, validator.validate(repeated))
    }

    @Test
    fun repeatedCharacterRunRejected() {
        assertRejected(validator.validate("hhhhhhhhhhh"), "repeatedCharacterRun")
        assertRejected(validator.validate("aaaaaaaaaaaaaaaaa"), "repeatedCharacterRun")
    }

    @Test
    fun controlCharactersRejected() {
        assertRejected(validator.validate("hello\u0007world"), "controlCharacters")
        assertRejected(validator.validate("hello\u001Bworld"), "controlCharacters")
        assertRejected(validator.validate("hello\uFFFDworld"), "controlCharacters")
    }

    @Test
    fun newlinesAndTabsAreAllowed() {
        assertEquals(TranscriptValidationResult.Valid, validator.validate("first line\nsecond line\ttabbed"))
    }

    @Test
    fun firstMatchingRuleWins() {
        val result = validator.validate("")
        assertRejected(result, "emptyOutput")
        val result2 = validator.validate("   ")
        assertRejected(result2, "whitespaceOnly")
    }

    @Test
    fun codeIdentifiersAndHinglishMixPass() {
        assertEquals(
            TranscriptValidationResult.Valid,
            validator.validate("the function fetchUserData is called after submit button click"),
        )
    }

    @Test
    fun duplicatedCandidateContentDetectsRepetition() {
        assertTrue(CorruptionRules.duplicatedCandidateContent("hello", "hellohello"))
        assertTrue(CorruptionRules.duplicatedCandidateContent("abc", "abcabcabc"))
        assertTrue(CorruptionRules.duplicatedCandidateContent("xyzxyz", "xyz"))
        assertFalse(CorruptionRules.duplicatedCandidateContent("hello", "hallo"))
        assertFalse(CorruptionRules.duplicatedCandidateContent("", "hello"))
        assertFalse(CorruptionRules.duplicatedCandidateContent("hello", "hello world"))
    }

    @Test
    fun implausibleLengthRuleIsConfigurable() {
        val rule = CorruptionRules.implausibleLengthExpansion(maxExpansionWords = 3, maxExpansionChars = 30)
        assertTrue(rule.predicate("one two three four"))
        assertTrue(rule.predicate("a".repeat(31)))
        assertFalse(rule.predicate("one two"))
    }

    private fun assertRejected(result: TranscriptValidationResult, ruleName: String) {
        assertTrue("expected rejection, got $result", result is TranscriptValidationResult.Rejected)
        val rejected = result as TranscriptValidationResult.Rejected
        assertEquals(ruleName, rejected.ruleName)
        assertTrue(rejected.reason.isNotBlank())
    }
}
