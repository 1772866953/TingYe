package com.tingye.app.playback

import androidx.media3.common.Player

/** Explicit navigation starts listening; internal seeks and undo keep their state. */
internal fun Player.playAfterNavigation() {
    if (mediaItemCount == 0) return
    if (playbackState == Player.STATE_IDLE) prepare()
    play()
}

internal fun Player.seekAndPlay(positionMs: Long) {
    if (mediaItemCount == 0) return
    val target = positionMs.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
    seekTo(target)
    playAfterNavigation()
}
