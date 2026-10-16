package com.tingye.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Counts elapsed listening time, independent of seeking or playback speed. */
class ListeningTimeTracker(private val zone: ZoneId = ZoneId.systemDefault()) {
    private var lastElapsed: Long? = null
    private var wasPlaying = false
    fun update(wallTime: Long, elapsed: Long, playing: Boolean): List<ListeningDay> {
        val duration = if (wasPlaying) (elapsed - (lastElapsed ?: elapsed)).coerceAtLeast(0) else 0L
        lastElapsed = elapsed
        wasPlaying = playing
        return splitListeningDays(wallTime, duration, zone)
    }
}

fun splitListeningDays(endMs: Long, durationMs: Long, zone: ZoneId): List<ListeningDay> {
    val result = mutableListOf<ListeningDay>()
    var start = endMs - durationMs.coerceAtLeast(0)
    while (start < endMs) {
        val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val end = minOf(endMs, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        result += ListeningDay(day.toString(), end - start)
        start = end
    }
    return result
}

data class ListeningSummary(val todayMs: Long, val weekMs: Long, val totalMs: Long)
fun listeningDurationLabel(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60000
    return if (minutes == 0L) if (ms > 0) "不足 1 分钟" else "0 分钟"
        else if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分钟"
}
fun listeningSummary(days: List<ListeningDay>, today: LocalDate = LocalDate.now()): ListeningSummary {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong()).toString()
    val date = today.toString()
    return ListeningSummary(days.filter { it.day == date }.sumOf { it.durationMs },
        days.filter { it.day >= monday && it.day <= date }.sumOf { it.durationMs }, days.sumOf { it.durationMs })
}
