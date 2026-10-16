package com.tingye.app.playback

/** No periodic wakeups when idle; finer checks are confined to a playback boundary. */
internal fun playbackCheckDelay(playing: Boolean, preciseBoundary: Boolean,
    timerRemainingMs: Long?, undoRemainingMs: Long?): Long? {
    val deadlines = listOfNotNull(timerRemainingMs, undoRemainingMs).minOrNull()
    val cadence = if (playing && preciseBoundary) 100L else if (playing || timerRemainingMs != null) 1000L else null
    return listOfNotNull(cadence, deadlines).minOrNull()?.coerceAtLeast(1)
}

internal fun canRecoverPlayback(wanted: Boolean, savedBoot: Int, currentBoot: Int,
    savedElapsed: Long, now: Long, timerDeadline: Long?): Boolean = wanted &&
    savedBoot >= 0 && savedBoot == currentBoot && savedElapsed > 0 && now - savedElapsed in 0..60_000L &&
    (timerDeadline == null || now < timerDeadline)
