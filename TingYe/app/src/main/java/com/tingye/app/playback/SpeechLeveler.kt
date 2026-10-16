package com.tingye.app.playback

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Streaming PCM16 leveling. All state belongs to the audio thread; no IO or frame allocations. */
class SpeechLeveler(private val sampleRate: Int, private val channels: Int) {
    init { require(sampleRate > 0 && channels > 0) }
    private val blockFrames = max(1, sampleRate / 10)
    private val rise = 1.0 - exp(-1.0 / (sampleRate * 1.5))
    private val fall = 1.0 - exp(-1.0 / (sampleRate * 0.25))
    private val release = 1.0 - exp(-1.0 / (sampleRate * 0.08))
    private var frames = 0
    private var power = 0.0
    private var speechPower = 0.0
    private var speechActive = false
    private var targetGain = 1.0
    private var gain = 1.0
    private var peakGain = 1.0

    /** Seeks discard partial analysis, retaining gain so a chapter boundary cannot pop. */
    fun flush() { frames = 0; power = 0.0; speechPower = 0.0; speechActive = false }

    fun process(input: ByteBuffer, output: ByteBuffer, enabled: Boolean) {
        require(input.remaining() % (channels * 2) == 0)
        while (input.hasRemaining()) {
            val offset = input.position()
            var peak = 0.0
            var framePower = 0.0
            for (channel in 0 until channels) {
                val value = input.getShort(offset + channel * 2).toDouble() / 32768.0
                peak = max(peak, abs(value))
                framePower += value * value
            }
            power += framePower / channels
            if (++frames == blockFrames) {
                val average = power / frames
                speechActive = average >= 0.000025
                // Do not turn pauses or quiet room noise into amplified hiss (-46 dBFS gate).
                if (speechActive) {
                    speechPower = if (speechPower == 0.0) average else speechPower * 0.8 + average * 0.2
                    // -21 dBFS RMS, limited to -12 / +9 dB. This is speech leveling, not LUFS scanning.
                    targetGain = (0.089125 / sqrt(speechPower)).coerceIn(0.251189, 2.818383)
                }
                frames = 0; power = 0.0
            }
            val target = if (!enabled) 1.0 else if (!speechActive && targetGain > gain) gain else targetGain
            gain += (target - gain) * if (target < gain || !enabled) fall else rise
            if (abs(gain - target) < 0.00001) gain = target
            // Linked channels keep stereo balance. Immediate peak protection, smooth release.
            val ceiling = if ((enabled || gain > 1.0) && peak * gain > 0.98) 0.98 / (peak * gain) else 1.0
            peakGain = if (ceiling < peakGain) ceiling else min(ceiling, peakGain + (1.0 - peakGain) * release)
            if (abs(peakGain - 1.0) < 0.00001) peakGain = 1.0
            val applied = gain * peakGain
            repeat(channels) {
                val original = input.short
                output.putShort(if (applied == 1.0) original else
                    (original * applied).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
    }
}
