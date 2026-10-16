package com.tingye.app.playback

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class ProgressPoint(val mediaId: String, val positionMs: Long, val chapter: String)
data class ProgressUndo(val token: Long, val point: ProgressPoint, val expiresAt: Long)

/** Main-looper owned. Monotonic expiry; consecutive manual actions share the original location. */
class ProgressUndoMemory(private val now: () -> Long, private val publish: (ProgressUndo?) -> Unit) {
    private var original: ProgressPoint? = null
    private var lastAction = Long.MIN_VALUE
    private var pending: ProgressUndo? = null
    private var windowMs = 12_000L
    fun setWindowSeconds(seconds: Int) {
        windowMs = seconds.coerceIn(1, 12) * 1000L
        pending?.let { item ->
            val deadline = lastAction + windowMs
            if (now() >= deadline) clear()
            else if (item.expiresAt != deadline) {
                pending = item.copy(expiresAt = deadline)
                publish(pending)
            }
        }
    }
    fun beforeSeek(from: ProgressPoint, targetId: String, targetPosition: Long) {
        val time = now()
        expire()
        if (from.mediaId == targetId && from.positionMs == targetPosition) return
        if (original == null || time - lastAction > 1_500 ||
            original!!.mediaId.substringBefore(':') != from.mediaId.substringBefore(':')) {
            original = from.copy(positionMs = from.positionMs.coerceAtLeast(0))
            pending = null
            publish(null)
        }
        lastAction = time
        val origin = original!!
        // Controller positions are timestamped snapshots. IPC and audio-frame rounding can
        // turn a 30-second button press into a measured 29,999ms seek, especially at higher speed.
        if (origin.mediaId != targetId || abs(targetPosition - origin.positionMs) >= 29_750) {
            pending = ProgressUndo(tokens.incrementAndGet(), origin, time + windowMs)
            publish(pending)
        } else if (pending != null) {
            // Manually returning to the origin cancels the now-useless offer.
            pending = null; publish(null)
        }
    }
    fun expire() {
        if (pending?.let { now() >= it.expiresAt } == true) clear()
    }
    fun take(token: Long): ProgressPoint? {
        expire()
        val item = pending?.takeIf { it.token == token } ?: return null
        clear()
        return item.point
    }
    fun validate(mediaIds: Set<String>) {
        if (original?.mediaId?.let { it !in mediaIds } == true) clear()
    }
    fun clear() { pending = null; original = null; lastAction = Long.MIN_VALUE; publish(null) }
    companion object { private val tokens = AtomicLong() }
}
