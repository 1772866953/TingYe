package com.tingye.app

import com.tingye.app.data.naturalCompare
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalSortTest {
    @Test fun chapterNumbersSortNaturally() {
        val actual = listOf("第10章.mp3", "第2章.mp3", "第1章.mp3").sortedWith(::naturalCompare)
        assertEquals(listOf("第1章.mp3", "第2章.mp3", "第10章.mp3"), actual)
    }

    @Test fun largeNumbersDoNotOverflow() {
        val actual = listOf("第999999999999999999999章", "第2章").sortedWith(::naturalCompare)
        assertEquals("第2章", actual.first())
    }
}
