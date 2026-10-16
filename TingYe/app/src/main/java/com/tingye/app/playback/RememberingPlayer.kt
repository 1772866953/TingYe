package com.tingye.app.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/** Manual chapter navigation restores memory; automatic progression uses ExoPlayer directly. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RememberingPlayer(private val actual: Player, private val memory: ChapterProgressMemory,
    private val undo: ProgressUndoMemory) : ForwardingPlayer(actual) {
    private fun beforeSeek(index: Int, position: Long) {
        if (index !in 0 until actual.mediaItemCount) return
        val from = actual.currentMediaItem ?: return
        val duration = if (index < actual.currentTimeline.windowCount)
            actual.currentTimeline.getWindow(index, Timeline.Window()).durationMs else C.TIME_UNSET
        val target = if (position == C.TIME_UNSET) 0L else
            position.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
        undo.beforeSeek(ProgressPoint(from.mediaId,
            if (actual.playbackState == Player.STATE_ENDED) 0 else actual.currentPosition,
            from.mediaMetadata.artist?.toString() ?: "当前章节"), actual.getMediaItemAt(index).mediaId, target)
    }
    private fun capture() {
        actual.currentMediaItem?.let { memory.record(it.mediaId,
            if (actual.playbackState == Player.STATE_ENDED) 0L else actual.currentPosition) }
    }
    private fun resume(index: Int, autoplay: Boolean = false) {
        if (index !in 0 until actual.mediaItemCount) return
        capture()
        val item = actual.getMediaItemAt(index)
        val duration = if (index < actual.currentTimeline.windowCount)
            actual.currentTimeline.getWindow(index, Timeline.Window()).durationMs else null
        val position = memory.position(item.mediaId, duration)
        beforeSeek(index, position)
        actual.seekTo(index, position)
        if (autoplay) actual.playAfterNavigation()
    }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (positionMs == C.TIME_UNSET && mediaItemIndex != actual.currentMediaItemIndex) resume(mediaItemIndex)
        else { beforeSeek(mediaItemIndex, positionMs); capture(); actual.seekTo(mediaItemIndex, positionMs) }
    }
    override fun seekTo(positionMs: Long) { beforeSeek(actual.currentMediaItemIndex, positionMs); capture(); actual.seekTo(positionMs) }
    override fun seekToDefaultPosition(mediaItemIndex: Int) = resume(mediaItemIndex)
    override fun seekToDefaultPosition() { beforeSeek(actual.currentMediaItemIndex, 0); capture(); actual.seekToDefaultPosition() }
    override fun seekBack() = seekAndPlay(actual.currentPosition - actual.seekBackIncrement)
    override fun seekForward() = seekAndPlay(actual.currentPosition + actual.seekForwardIncrement)
    override fun seekToNextMediaItem() { if (actual.hasNextMediaItem()) resume(actual.nextMediaItemIndex, autoplay = true) }
    override fun seekToPreviousMediaItem() { if (actual.hasPreviousMediaItem()) resume(actual.previousMediaItemIndex, autoplay = true) }
    override fun seekToNext() = seekToNextMediaItem()
    override fun seekToPrevious() {
        if (actual.currentPosition <= actual.maxSeekToPreviousPosition && actual.hasPreviousMediaItem()) seekToPreviousMediaItem()
        else seekAndPlay(0L)
    }
}
