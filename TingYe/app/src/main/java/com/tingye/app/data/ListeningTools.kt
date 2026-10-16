package com.tingye.app.data

enum class ShelfSort(val label: String) { RECENT("最近收听"), ADDED("最近添加"), TITLE("书名顺序") }
enum class ShelfFilter(val label: String, val status: Int?) {
    ALL("全部", null), NEW("未开始", 0), LISTENING("收听中", 1), FINISHED("已听完", 2)
}

fun browseBooks(books: List<Book>, query: String, filter: ShelfFilter, sort: ShelfSort): List<Book> {
    val matching = books.filter { (filter.status == null || it.status == filter.status) &&
        it.title.contains(query.trim(), ignoreCase = true) }
    return when (sort) {
        ShelfSort.RECENT -> matching.sortedWith(compareByDescending<Book> { it.updatedAt }.thenByDescending { it.id })
        ShelfSort.ADDED -> matching.sortedByDescending { it.id }
        ShelfSort.TITLE -> matching.sortedWith { a, b -> naturalCompare(a.title, b.title) }
    }
}

data class RemainingListening(val wallTimeMs: Long, val unknownChapters: Int)

/** Skipped intros/outros and playback speed both reduce actual listening time. */
fun remainingListening(chapters: List<Chapter>, index: Int, positionMs: Long, speed: Float,
    introSeconds: Int = 0, outroSeconds: Int = 0, complete: Boolean = false): RemainingListening {
    if (complete || chapters.isEmpty()) return RemainingListening(0, 0)
    var remaining = 0L
    var unknown = 0
    val start = index.coerceIn(0, chapters.lastIndex)
    for (i in start..chapters.lastIndex) {
        val duration = chapters[i].durationMs?.takeIf { it > 0 }
        if (duration == null) { unknown++; continue }
        val intro = introSeconds.coerceAtLeast(0) * 1000L
        val outro = outroSeconds.coerceAtLeast(0) * 1000L
        val canSkip = duration > intro + outro + 5_000L
        val begin = maxOf(if (i == start) positionMs.coerceAtLeast(0) else 0L,
            if (canSkip) intro else 0L)
        val end = duration - if (canSkip) outro else 0L
        remaining += (end - begin).coerceAtLeast(0)
    }
    return RemainingListening((remaining / speed.coerceIn(0.5f, 3f)).toLong(), unknown)
}

/** Gentle smoothstep; no volume jump where the fade starts. */
fun sleepVolumeFraction(remainingWallMs: Long, fadeMs: Long): Float {
    if (fadeMs <= 0) return 1f
    val t = (remainingWallMs.toFloat() / fadeMs).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
