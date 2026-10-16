package com.tingye.app

import com.tingye.app.playback.*
import org.junit.Assert.*
import org.junit.Test

class ProgressUndoMemoryTest {
    private var now = 10_000L
    private var ui: ProgressUndo? = null
    private fun memory() = ProgressUndoMemory({ now }) { ui = it }
    private fun point(id: String = "1:10", at: Long = 12_345) = ProgressPoint(id, at, "第一章")
    @Test fun configuredWindowExpiresAtTheChosenBoundaryAndClampsInvalidValues() {
        val memory = memory()
        memory.setWindowSeconds(0)
        memory.beforeSeek(point(), "1:11", 0)
        val shortToken = ui!!.token
        now += 999; memory.expire(); assertNotNull(ui)
        now++; assertNull(memory.take(shortToken)); assertNull(ui)
        memory.setWindowSeconds(99)
        memory.beforeSeek(point(), "1:11", 0)
        now += 11_999; memory.expire(); assertNotNull(ui)
        now++; memory.expire(); assertNull(ui)
    }
    @Test fun changingWindowRetainsElapsedTimeAndCannotReviveExpiredOffers() {
        val memory = memory()
        memory.setWindowSeconds(3)
        memory.beforeSeek(point(), "1:11", 0)
        val token = ui!!.token
        now += 1500
        memory.setWindowSeconds(6)
        assertEquals(16_000, ui!!.expiresAt)
        assertEquals(token, ui!!.token)
        assertEquals(point(), ui!!.point)
        memory.setWindowSeconds(1)
        assertNull(ui)
        memory.setWindowSeconds(12)
        assertNull(memory.take(token))
    }
    @Test fun exactOriginalPositionAndOneUseToken() {
        val memory = memory()
        memory.beforeSeek(point(), "1:10", 90_000)
        val token = ui!!.token
        assertNull(memory.take(token + 1))
        assertNotNull(ui)
        assertEquals(point(), memory.take(token))
        assertNull(memory.take(token)); assertNull(ui)
    }
    @Test fun anyChapterChangeCanBeRecovered() {
        val memory = memory()
        memory.beforeSeek(point(), "1:11", 5000)
        assertEquals("1:10", memory.take(ui!!.token)!!.mediaId)
    }
    @Test fun smallSeeksAccumulateAndRapidActionsKeepFirstOrigin() {
        val memory = memory()
        memory.beforeSeek(point(at = 0), "1:10", 15_000); assertNull(ui)
        now += 300
        memory.beforeSeek(point(at = 15_000), "1:10", 30_000)
        val stale = ui!!.token
        now += 300
        memory.beforeSeek(point(at = 30_000), "1:11", 5000)
        assertNull(memory.take(stale))
        assertEquals(0, memory.take(ui!!.token)!!.positionMs)
    }
    @Test fun separateActionStartsFromNewPosition() {
        val memory = memory()
        memory.beforeSeek(point(), "1:10", 80_000)
        now += 1600
        memory.beforeSeek(point(at = 81_000), "1:11", 5000)
        assertEquals(81_000, memory.take(ui!!.token)!!.positionMs)
    }
    @Test fun expiredRemovedAndBookChangedOffersCannotRestore() {
        val memory = memory()
        memory.beforeSeek(point(), "1:11", 0)
        val token = ui!!.token
        now += 12_000
        assertNull(memory.take(token)); assertNull(ui)
        memory.beforeSeek(point(), "1:11", 0)
        memory.validate(setOf("1:11")); assertNull(ui)
        memory.beforeSeek(point(), "1:11", 0)
        memory.clear(); assertNull(ui)
    }
    @Test fun noOpDoesNotReplaceOfferAndReturningManuallyCancelsIt() {
        val memory = memory()
        memory.beforeSeek(point(at = 0), "1:10", 40_000)
        val token = ui!!.token
        memory.beforeSeek(point(at = 40_000), "1:10", 40_000)
        assertEquals(token, ui!!.token)
        memory.beforeSeek(point(at = 40_000), "1:10", 0)
        assertNull(ui)
    }
    @Test fun thirtySecondButtonsTolerateControllerTimestampRounding() {
        val memory = memory()
        memory.beforeSeek(point(at = 0), "1:10", 29_000)
        assertNull(ui)
        now += 1600
        memory.beforeSeek(point(at = 30_000), "1:10", 59_994)
        assertEquals(30_000, memory.take(ui!!.token)!!.positionMs)
    }
}
