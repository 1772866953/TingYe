package com.tingye.app

import com.tingye.app.playback.SpeechLeveler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class SpeechLevelerTest {
    private val rate = 16_000
    private fun tone(amplitude: Double, seconds: Int, channels: Int = 1): ShortArray =
        ShortArray(rate * seconds * channels) { i ->
            (amplitude * 32767 * sin(2 * PI * 440 * (i / channels) / rate) *
                if (channels == 2 && i % 2 == 1) 0.5 else 1.0).roundToInt().toShort()
        }
    private fun process(leveler: SpeechLeveler, samples: ShortArray, enabled: Boolean, chunk: Int = samples.size): ShortArray {
        val result = ShortArray(samples.size)
        var offset = 0
        while (offset < samples.size) {
            val count = min(chunk, samples.size - offset)
            val input = ByteBuffer.allocate(count * 2).order(ByteOrder.nativeOrder())
            repeat(count) { input.putShort(samples[offset + it]) }; input.flip()
            val output = ByteBuffer.allocate(count * 2).order(ByteOrder.nativeOrder())
            leveler.process(input, output, enabled); output.flip()
            repeat(count) { result[offset + it] = output.short }
            assertFalse(input.hasRemaining())
            offset += count
        }
        return result
    }
    private fun rms(samples: ShortArray, start: Int = samples.size - rate): Double =
        sqrt(samples.drop(start).sumOf { (it.toDouble() / 32768).pow(2) } / (samples.size - start))

    @Test fun disabledIsExactEvenForFullScaleSamples() {
        val input = ShortArray(20_000) { ((it * 997) % 65536 - 32768).toShort() }
        assertArrayEquals(input, process(SpeechLeveler(rate, 1), input, false, 113))
    }
    @Test fun reducesDifferenceBetweenQuietAndLoudRecording() {
        val quiet = process(SpeechLeveler(rate, 1), tone(0.05, 8), true)
        val loud = process(SpeechLeveler(rate, 1), tone(0.45, 8), true)
        val gapDb = 20 * log10(rms(loud) / rms(quiet))
        assertTrue("Level gap $gapDb dB", abs(gapDb) < 1.8)
    }
    @Test fun noiseAndSilenceDoNotBuildGain() {
        val noise = tone(0.002, 4)
        assertArrayEquals(noise, process(SpeechLeveler(rate, 1), noise, true))
        assertArrayEquals(ShortArray(rate), process(SpeechLeveler(rate, 1), ShortArray(rate), true))
    }
    @Test fun limiterHandlesSuddenPeakAndPreservesStereoBalance() {
        val leveler = SpeechLeveler(rate, 2)
        process(leveler, tone(0.03, 8, 2), true)
        val output = process(leveler, shortArrayOf(32767, 16384, -32768, -16384), true)
        assertTrue(output.all { abs(it.toInt()) <= 32114 })
        assertEquals(output[0].toDouble() / 2, output[1].toDouble(), 1.0)
        assertEquals(output[2].toDouble() / 2, output[3].toDouble(), 1.0)
    }
    @Test fun arbitraryBufferSizesProduceIdenticalOutput() {
        val input = tone(0.05, 3, 2)
        assertArrayEquals(process(SpeechLeveler(rate, 2), input, true),
            process(SpeechLeveler(rate, 2), input, true, 226))
    }
    @Test fun switchingOffStillProtectsPeaksWhileBoostDecays() {
        val leveler = SpeechLeveler(rate, 1)
        process(leveler, tone(0.03, 8), true)
        val output = process(leveler, shortArrayOf(32767, -32768, 32767, -32768), false)
        assertTrue(output.all { abs(it.toInt()) <= 32114 })
    }
    @Test fun gainAndToggleDoNotIntroduceAbruptStepsAndOffReturnsToOriginal() {
        val leveler = SpeechLeveler(rate, 1)
        val quiet = ShortArray(rate * 8) { 1600 }
        val on = process(leveler, quiet, true)
        assertTrue((1 until on.size).all { abs(on[it - 1] - on[it]) <= 1 })
        val off = process(leveler, quiet, false)
        assertTrue(abs(off[0] - on.last()) <= 1)
        assertTrue((1 until off.size).all { abs(off[it - 1] - off[it]) <= 1 })
        assertEquals(1600, off.last().toInt())
    }
    @Test fun seekFlushRetainsGainWithoutMixingPartialAnalysis() {
        val leveler = SpeechLeveler(rate, 1)
        val before = process(leveler, ShortArray(rate * 8) { 1600 }, true)
        leveler.flush()
        val after = process(leveler, shortArrayOf(1600), true)
        assertTrue(abs(before.last() - after[0]) <= 1)
    }
}
