package com.whispertype.android.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySettingsTest {

    @Test
    fun defaultsMatchContract() {
        val settings = HistorySettings()
        assertFalse(settings.enabled)
        assertEquals(7, settings.retentionDays)
        assertFalse(settings.includeMetadata)
    }

    @Test
    fun oneDayBoundaryIsNotExpired() {
        assertFalse(HistoryPolicy.isExpired(timestampMillis = 0L, retentionDays = 1, nowMillis = 86_400_000L))
    }

    @Test
    fun oneDayPastBoundaryIsExpired() {
        assertTrue(HistoryPolicy.isExpired(timestampMillis = 0L, retentionDays = 1, nowMillis = 86_400_001L))
    }

    @Test
    fun sevenDayBoundaryIsNotExpired() {
        assertFalse(HistoryPolicy.isExpired(timestampMillis = 0L, retentionDays = 7, nowMillis = 7 * 86_400_000L))
    }

    @Test
    fun sevenDayPastBoundaryIsExpired() {
        assertTrue(HistoryPolicy.isExpired(timestampMillis = 0L, retentionDays = 7, nowMillis = 7 * 86_400_000L + 1L))
    }

    @Test
    fun thirtyDayBoundaryIsNotExpired() {
        assertFalse(HistoryPolicy.isExpired(timestampMillis = 0L, retentionDays = 30, nowMillis = 30 * 86_400_000L))
    }

    @Test
    fun thirtyDayPastBoundaryIsExpired() {
        assertTrue(HistoryPolicy.isExpired(timestampMillis = 0L, retentionDays = 30, nowMillis = 30 * 86_400_000L + 1L))
    }

    @Test
    fun validRetentionDaysAcceptsOneSevenThirty() {
        assertTrue(HistoryPolicy.validRetentionDays(1))
        assertTrue(HistoryPolicy.validRetentionDays(7))
        assertTrue(HistoryPolicy.validRetentionDays(30))
    }

    @Test
    fun invalidRetentionDaysRejected() {
        assertFalse(HistoryPolicy.validRetentionDays(0))
        assertFalse(HistoryPolicy.validRetentionDays(14))
        assertFalse(HistoryPolicy.validRetentionDays(365))
    }
}
