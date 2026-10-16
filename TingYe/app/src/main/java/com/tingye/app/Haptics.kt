package com.tingye.app

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

/** One short tactile event per committed action; never tied to animation frames. */
internal class AppHaptics(context: Context) {
    private val resolver = context.contentResolver
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val amplitudeControl = vibrator.hasAmplitudeControl()
    private val motorPresent = vibrator.hasVibrator()
    // Binder/settings access must not hold up a touch frame. Preserve command order.
    private val feedbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val effects = mutableMapOf<Pair<Kind, Int>, VibrationEffect>()
    var strength: Int = 2
    private var lastFeedbackAt = 0L
    private var feedbackSerial = 0L

    fun close() { feedbackScope.cancel() }

    fun selection() = play(Kind.SELECTION)
    fun action() = play(Kind.ACTION)
    fun confirmation() = play(Kind.CONFIRMATION)
    fun preview(level: Int) = play(Kind.ACTION, level)

    // A callback can choose a heavier feedback. The button must not add another tick.
    fun click(action: () -> Unit) {
        val before = feedbackSerial
        action()
        if (before == feedbackSerial) selection()
    }

    private fun play(kind: Kind, level: Int = strength) {
        feedbackSerial++
        if (level !in 1..3 || !motorPresent) return
        val requestedAt = SystemClock.elapsedRealtime()
        feedbackScope.launch {
            // Do not replay an old touch after the device has been busy.
            if (SystemClock.elapsedRealtime() - requestedAt > 80 || requestedAt - lastFeedbackAt < 45) return@launch
            if (Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return@launch
            ensureActive()
            if (SystemClock.elapsedRealtime() - requestedAt > 80) return@launch
            lastFeedbackAt = requestedAt
            emit(kind, level)
        }
    }

    private fun emit(kind: Kind, level: Int) {
        if (amplitudeControl) {
            // A single concentrated pulse: strength changes amplitude, not pulse count.
            val peak = when (level) { 1 -> 105; 2 -> 178; else -> 255 }
            val duration = if (kind == Kind.SELECTION) 12L else 18L
            val effect = effects.getOrPut(kind to level) {
                VibrationEffect.createOneShot(duration, peak)
            }
            vibrator.vibrate(effect)
        } else {
            // Without amplitude control, duration is the only reliable intensity adjustment.
            val duration = when (level) { 1 -> 8L; 2 -> 12L; else -> 16L } +
                if (kind == Kind.SELECTION) 0L else 2L
            vibrator.vibrate(effects.getOrPut(kind to level) {
                VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
            })
        }
    }
    private enum class Kind { SELECTION, ACTION, CONFIRMATION }
}
