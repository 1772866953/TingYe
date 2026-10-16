package com.tingye.app

import com.tingye.app.playback.ChapterProgressMemory
import org.junit.Assert.*
import org.junit.Test

class ChapterProgressMemoryTest {
    @Test fun chaptersAndBooksKeepIndependentPositions() {
        val memory = ChapterProgressMemory()
        memory.seed("1:1", 12000)
        memory.record("1:2", 34000)
        memory.record("2:3", 56000)
        assertEquals(12000, memory.position("1:1"))
        assertEquals(34000, memory.position("1:2"))
        assertEquals(56000, memory.position("2:3"))
    }
    @Test fun staleDiskSnapshotCannotUndoRapidChapterSwitch() {
        val memory = ChapterProgressMemory()
        memory.seed("1:1", 10000)
        memory.record("1:1", 25000)
        memory.seed("1:1", 10000)
        assertEquals(25000, memory.position("1:1"))
        memory.record("1:1", 0)
        assertEquals(0, memory.position("1:1"))
    }
    @Test fun finishedAndInvalidPositionsStartAtZero() {
        val memory = ChapterProgressMemory()
        memory.record("1:1", 30000)
        assertEquals(0, memory.position("1:1", 30000))
        memory.record("1:1", -1)
        assertEquals(0, memory.position("1:1"))
    }
}
