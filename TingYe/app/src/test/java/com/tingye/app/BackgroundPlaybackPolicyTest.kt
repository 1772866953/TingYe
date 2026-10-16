package com.tingye.app

import com.tingye.app.playback.canRecoverPlayback
import com.tingye.app.playback.playbackCheckDelay
import org.junit.Assert.*
import org.junit.Test

class BackgroundPlaybackPolicyTest {
    @Test fun pauseWithoutDeadlinesHasNoPeriodicWakeups() {
        assertNull(playbackCheckDelay(false, false, null, null))
        assertEquals(2500L, playbackCheckDelay(false, false, null, 2500))
    }
    @Test fun activeTimersAndUndoRespectTheirNearestDeadline() {
        assertEquals(1000L, playbackCheckDelay(false, false, 60_000, null))
        assertEquals(37L, playbackCheckDelay(false, false, 37, 500))
        assertEquals(1L, playbackCheckDelay(false, false, 0, null))
    }
    @Test fun ordinaryPlaybackChecksSlowlyAndBoundariesStayPrecise() {
        assertEquals(1000L, playbackCheckDelay(true, false, null, null))
        assertEquals(100L, playbackCheckDelay(true, true, null, null))
        assertEquals(25L, playbackCheckDelay(true, true, 25, null))
    }
    @Test fun onlyRecentActivePlaybackInTheSameBootMayRestart() {
        assertTrue(canRecoverPlayback(true, 2, 2, 1000, 61_000, null))
        assertFalse(canRecoverPlayback(false, 2, 2, 1000, 2000, null))
        assertFalse(canRecoverPlayback(true, 2, 3, 1000, 2000, null))
        assertFalse(canRecoverPlayback(true, -1, -1, 1000, 2000, null))
        assertFalse(canRecoverPlayback(true, 2, 2, 1000, 61_001, null))
        assertFalse(canRecoverPlayback(true, 2, 2, 1000, 999, null))
    }
    @Test fun expiredSleepTimerMustNotRestartPlayback() {
        assertFalse(canRecoverPlayback(true, 2, 2, 1000, 2000, 2000))
        assertTrue(canRecoverPlayback(true, 2, 2, 1000, 2000, 2001))
    }
}
