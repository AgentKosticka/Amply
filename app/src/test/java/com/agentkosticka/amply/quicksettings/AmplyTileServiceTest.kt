package com.agentkosticka.amply.quicksettings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmplyTileServiceTest {
    @Test fun tileIsActiveWhenAmplyIsNotPaused() {
        assertTrue(amplyTileIsActive(pausedUntilEpochMs = 0L, nowEpochMs = 1_000L))
    }

    @Test fun tileIsInactiveDuringTimedPause() {
        assertFalse(amplyTileIsActive(pausedUntilEpochMs = 2_000L, nowEpochMs = 1_000L))
    }

    @Test fun tileReactivatesAtPauseDeadline() {
        assertTrue(amplyTileIsActive(pausedUntilEpochMs = 2_000L, nowEpochMs = 2_000L))
    }

    @Test fun manualPauseKeepsTileInactive() {
        assertFalse(amplyTileIsActive(pausedUntilEpochMs = Long.MAX_VALUE, nowEpochMs = 2_000L))
    }
}
