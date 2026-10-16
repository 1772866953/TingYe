package com.tingye.app

import com.tingye.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ListeningStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun time(value: String) = java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    @Test fun onlyActualPlayingIntervalsAreCounted() {
        val tracker = ListeningTimeTracker(zone)
        val start = time("2026-10-05T12:00:00")
        assertTrue(tracker.update(start, 1000, false).isEmpty())
        assertTrue(tracker.update(start + 10000, 11000, true).isEmpty())
        assertEquals(5000L, tracker.update(start + 15000, 16000, true).sumOf { it.durationMs })
        assertEquals(1000L, tracker.update(start + 16000, 17000, false).sumOf { it.durationMs })
        assertTrue(tracker.update(start + 26000, 27000, false).isEmpty())
        assertTrue(tracker.update(start + 36000, 37000, true).isEmpty())
        assertEquals(2000L, tracker.update(start + 38000, 39000, false).sumOf { it.durationMs })
    }

    @Test fun deviceClockChangeDoesNotInflateElapsedTime() {
        val tracker = ListeningTimeTracker(zone)
        tracker.update(time("2026-10-05T12:00:00"), 1000, true)
        assertEquals(1000L, tracker.update(time("2026-10-05T13:00:00"), 2000, false).sumOf { it.durationMs })
    }

    @Test fun midnightSplitsTimeIntoCorrectDays() {
        val result = splitListeningDays(time("2026-10-05T00:00:03"), 5000, zone)
        assertEquals(listOf(ListeningDay("2026-10-04", 2000), ListeningDay("2026-10-05", 3000)), result)
    }

    @Test fun weekStartsMondayAndDoesNotIncludeFutureDays() {
        val result = listeningSummary(listOf(ListeningDay("2026-10-04", 1000),
            ListeningDay("2026-10-05", 2000), ListeningDay("2026-10-06", 3000)), LocalDate.parse("2026-10-05"))
        assertEquals(ListeningSummary(2000, 2000, 6000), result)
    }

    @Test fun titleFallbackRejectsEmptyUnknownAndUrls() {
        assertNull(usefulBookTitle(" unknown album "))
        assertNull(usefulBookTitle("content://files/123"))
        assertNull(usefulBookTitle(" "))
        assertEquals("山间故事", usefulBookTitle(" 山间故事 "))
    }

    @Test fun invalidHeadphoneSettingsFallBackToChapterNavigation() {
        assertEquals("chapter", validHeadphoneAction(null))
        assertEquals("chapter", validHeadphoneAction("bad-value"))
        assertEquals("none", validHeadphoneAction("none"))
    }
}
