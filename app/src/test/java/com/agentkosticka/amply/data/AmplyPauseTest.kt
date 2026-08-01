package com.agentkosticka.amply.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AmplyPauseTest {
    @Test fun defaultPauseIsFiveMinutes() {
        assertEquals(5, DEFAULT_AMPLY_PAUSE_MINUTES)
        assertEquals(400_000L, calculateAmplyPauseUntil(100_000L, DEFAULT_AMPLY_PAUSE_MINUTES))
    }

    @Test fun pauseDurationIsClamped() {
        assertEquals(160_000L, calculateAmplyPauseUntil(100_000L, 0))
        assertEquals(7_300_000L, calculateAmplyPauseUntil(100_000L, 999))
    }

    @Test fun manualPauseHasNoAutomaticExpiry() {
        assertEquals(Long.MAX_VALUE, calculateAmplyPauseUntil(100_000L, AmplyPauseDuration.MANUAL))
    }

    @Test fun legacyDurationMigratesToTypedValue() {
        assertEquals(AmplyPauseDuration.FIFTEEN_MINUTES, AmplyPauseDuration.fromStored(null, 15))
        assertEquals(AmplyPauseDuration.FIVE_MINUTES, AmplyPauseDuration.fromStored(null, 999))
    }
}
