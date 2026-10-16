package com.tingye.app.playback

/** Accessed on the player's main looper. Disk snapshots never replace a newer live position. */
class ChapterProgressMemory {
    private val positions = mutableMapOf<String, Long>()
    fun seed(mediaId: String, position: Long) { positions.putIfAbsent(mediaId, position.coerceAtLeast(0)) }
    fun record(mediaId: String, position: Long) { positions[mediaId] = position.coerceAtLeast(0) }
    fun position(mediaId: String, duration: Long? = null): Long {
        val saved = positions[mediaId] ?: 0L
        // A completed chapter starts again from the beginning.
        return if (duration != null && duration > 0 && saved >= duration) 0L else saved
    }
    fun snapshot(): Map<String, Long> = positions.toMap()
    fun clear() { positions.clear() }
}
