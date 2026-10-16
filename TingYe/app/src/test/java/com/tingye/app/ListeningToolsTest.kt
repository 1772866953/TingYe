package com.tingye.app

import com.tingye.app.data.*
import org.junit.Assert.*
import org.junit.Test

class ListeningToolsTest {
    private fun chapter(duration: Long?) = Chapter(bookId = 1, title = "chapter", uri = "uri", orderIndex = 0, durationMs = duration)

    @Test fun remainingRespectsProgressSpeedAndSkippedEdges() {
        val value = remainingListening(listOf(chapter(100_000), chapter(100_000)), 0, 40_000, 2f, 10, 20)
        assertEquals(55_000L, value.wallTimeMs) // 40s of current + 70s of next, at 2x.
        assertEquals(0, value.unknownChapters)
    }
    @Test fun shortChaptersDoNotApplyImpossibleEdgeSkips() {
        assertEquals(4_000L, remainingListening(listOf(chapter(4_000)), 0, 0, 1f, 10, 20).wallTimeMs)
    }
    @Test fun missingDurationsAreNeverReportedAsExact() {
        val value = remainingListening(listOf(chapter(-1), chapter(null), chapter(30_000)), 0, 0, 1f)
        assertEquals(2, value.unknownChapters)
        assertEquals(30_000L, value.wallTimeMs)
    }
    @Test fun completedAndOvershotProgressCannotProduceNegativeTime() {
        assertEquals(0L, remainingListening(listOf(chapter(30_000)), 0, 90_000, 1f).wallTimeMs)
        assertEquals(0L, remainingListening(listOf(chapter(30_000)), 0, 0, 1f, complete = true).wallTimeMs)
    }
    @Test fun fadeIsContinuousMonotonicAndRestoresFullVolumeWhenDisabled() {
        assertEquals(1f, sleepVolumeFraction(30_000, 30_000), 0.0001f)
        assertEquals(0.5f, sleepVolumeFraction(15_000, 30_000), 0.0001f)
        assertEquals(0f, sleepVolumeFraction(0, 30_000), 0.0001f)
        assertEquals(1f, sleepVolumeFraction(0, 0), 0.0001f)
        val values = (0..30).map { sleepVolumeFraction(it * 1000L, 30_000) }
        assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
    }
    @Test fun searchFilterAndNaturalTitleOrderWorkTogether() {
        val books = listOf(Book(id = 1, title = "Book10", status = 1, updatedAt = 30),
            Book(id = 2, title = "Book2", status = 1, updatedAt = 10),
            Book(id = 3, title = "Other", status = 2, updatedAt = 20))
        assertEquals(listOf(2L, 1L), browseBooks(books, " BOOK ", ShelfFilter.LISTENING, ShelfSort.TITLE).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), browseBooks(books, "", ShelfFilter.ALL, ShelfSort.ADDED).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), browseBooks(books, "", ShelfFilter.ALL, ShelfSort.RECENT).map { it.id })
    }
}
