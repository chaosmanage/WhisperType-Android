package com.whispertype.android.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranscriptEvidenceLedgerTest {

    @Test
    fun `duplicate revision does not change or consume bounded history`() {
        val ledger = TranscriptEvidenceLedger(maxRevisionsPerSource = 2)

        val first = ledger.recordInput("send the report")
        val duplicate = ledger.recordInput("send the report")

        assertTrue(first.changed)
        assertFalse(duplicate.changed)
        assertEquals(TranscriptEvidenceLedger.RecordResult.Status.DUPLICATE, duplicate.status)
        assertEquals(listOf("send the report"), ledger.revisions(TranscriptEvidenceSource.INPUT))
    }

    @Test
    fun `history evicts oldest revisions and rejects oversized text`() {
        val ledger = TranscriptEvidenceLedger(
            maxRevisionsPerSource = 2,
            maxCharsPerRevision = 8,
        )
        ledger.recordEcho("one")
        ledger.recordEcho("two")
        val third = ledger.recordEcho("three")
        val oversized = ledger.recordEcho("123456789")

        assertTrue(third.evictedOldest)
        assertEquals(listOf("two", "three"), ledger.revisions(TranscriptEvidenceSource.ECHO))
        assertEquals(TranscriptEvidenceLedger.RecordResult.Status.TOO_LARGE, oversized.status)
        assertFalse(oversized.accepted)
        assertEquals(2, ledger.retainedRevisionCount)
    }

    @Test
    fun `quality decision fails closed when a reconstructed hypothesis exceeds its bound`() {
        val ledger = TranscriptEvidenceLedger(
            maxRevisionsPerSource = 2,
            maxCharsPerRevision = 12,
        )
        ledger.recordEcho("alpha beta")
        val overflow = ledger.recordEcho("gamma delta")

        assertTrue(overflow.hypothesisOverflowed)
        val decision = assertIs<TranscriptQualityDecision.Insufficient>(
            ledger.qualityDecision(durationMs = 2_000L),
        )
        assertEquals(
            TranscriptQualityDecision.Insufficient.Reason.EVIDENCE_OVERFLOW,
            decision.reason,
        )
    }

    @Test
    fun `delta hypothesis uses maximal overlap for repeated connectors`() {
        val ledger = TranscriptEvidenceLedger(maxRevisionsPerSource = 2)
        ledger.recordEcho("we need bread and milk and")
        ledger.recordEcho("milk and eggs and")
        ledger.recordEcho("eggs and fruit")

        assertEquals(
            listOf("milk and eggs and", "eggs and fruit"),
            ledger.revisions(TranscriptEvidenceSource.ECHO),
        )
        assertTrue(
            "we need bread and milk and eggs and fruit" in
                ledger.hypotheses(TranscriptEvidenceSource.ECHO),
        )
    }

    @Test
    fun `complete echo is selected over input`() {
        val ledger = TranscriptEvidenceLedger()
        ledger.recordInput("um send report AB-42 on Thursday")
        ledger.recordEcho("Send report AB-42 on Thursday.")

        val decision = assertIs<TranscriptQualityDecision.UsePolished>(
            ledger.qualityDecision(durationMs = 3_000L),
        )

        assertEquals(TranscriptEvidenceSource.ECHO, decision.source)
        assertEquals("Send report AB-42 on Thursday.", decision.text)
        assertTrue(decision.assessment.isComplete)
    }

    @Test
    fun `echo missing a mandatory anchor falls back to input`() {
        val ledger = TranscriptEvidenceLedger()
        ledger.recordInput("send report AB-42 to dev@example.com on Thursday")
        ledger.recordEcho("Send the report on Thursday.")

        val decision = assertIs<TranscriptQualityDecision.UseInput>(
            ledger.qualityDecision(durationMs = 3_000L),
        )

        assertEquals(
            TranscriptCompleteness.Diagnosis.MISSING_ANCHOR,
            decision.rejectedPolishedAssessment?.diagnosis,
        )
    }

    @Test
    fun `unrelated equal length echo falls back to input`() {
        val ledger = TranscriptEvidenceLedger()
        ledger.recordInput("alpha beta gamma delta")
        ledger.recordEcho("orange violet silver copper")

        val decision = assertIs<TranscriptQualityDecision.UseInput>(
            ledger.qualityDecision(durationMs = 3_000L),
        )

        assertEquals("alpha beta gamma delta", decision.text)
        assertFalse(decision.rejectedPolishedAssessment?.isComplete ?: true)
    }

    @Test
    fun `repair that covers input outranks a direct echo`() {
        val ledger = TranscriptEvidenceLedger()
        ledger.recordInput("send all five invoices tomorrow")
        ledger.recordEcho("Send invoices.")
        ledger.recordRepair("Send all five invoices tomorrow.")

        val decision = assertIs<TranscriptQualityDecision.UsePolished>(
            ledger.qualityDecision(durationMs = 4_000L),
        )

        assertEquals(TranscriptEvidenceSource.REPAIR, decision.source)
        assertEquals("Send all five invoices tomorrow.", decision.text)
    }

    @Test
    fun `echo only long summary is insufficient by duration evidence`() {
        val ledger = TranscriptEvidenceLedger()
        ledger.recordEcho("Project summary")

        val decision = assertIs<TranscriptQualityDecision.Insufficient>(
            ledger.qualityDecision(durationMs = 60_000L),
        )

        assertEquals(
            TranscriptQualityDecision.Insufficient.Reason.DURATION_IMPLAUSIBLE,
            decision.reason,
        )
        assertEquals(
            TranscriptCompleteness.DurationDiagnosis.TOO_FEW_WORDS,
            decision.durationAssessment?.diagnosis,
        )
    }

    @Test
    fun `echo only cannot be accepted before duration is supplied`() {
        val ledger = TranscriptEvidenceLedger()
        ledger.recordEcho("A plausible transcript")

        val decision = assertIs<TranscriptQualityDecision.Insufficient>(
            ledger.qualityDecision(),
        )

        assertEquals(
            TranscriptQualityDecision.Insufficient.Reason.DURATION_REQUIRED,
            decision.reason,
        )
    }

    @Test
    fun `echo only prefers the strongest retained hypothesis over a short replay`() {
        val ledger = TranscriptEvidenceLedger()
        ledger.recordEcho("this is the complete transcript")
        ledger.recordEcho("transcript")

        val decision = assertIs<TranscriptQualityDecision.UseEchoOnly>(
            ledger.qualityDecision(durationMs = 5_000L),
        )

        assertEquals("this is the complete transcript", decision.text)
    }

    @Test
    fun `clear removes evidence from every source`() {
        val ledger = TranscriptEvidenceLedger()
        ledger.recordInput("raw")
        ledger.recordEcho("echo")
        ledger.recordRepair("repair")

        ledger.clear()

        assertEquals(0, ledger.retainedRevisionCount)
        assertIs<TranscriptQualityDecision.Insufficient>(ledger.qualityDecision())
    }
}
