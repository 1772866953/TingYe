package com.tingye.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LevelingAudioProcessor : BaseAudioProcessor() {
    @Volatile var enabled = false
    private var leveler: SpeechLeveler? = null
    private var rate = 0
    private var channels = 0
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        // Remain in the chain when disabled: toggling never rebuilds the sink or seeks the player.
        return inputAudioFormat
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (rate != inputAudioFormat.sampleRate || channels != inputAudioFormat.channelCount || leveler == null) {
            rate = inputAudioFormat.sampleRate; channels = inputAudioFormat.channelCount
            leveler = SpeechLeveler(rate, channels)
        } else leveler?.flush()
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        leveler!!.process(inputBuffer.order(ByteOrder.nativeOrder()), output, enabled)
        output.flip()
    }
    override fun onReset() { leveler = null; rate = 0; channels = 0 }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LevelingRenderersFactory(context: Context, private val processor: LevelingAudioProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(processor)).build()
        // Encoded passthrough/offload bypasses processors (including on Bluetooth/HDMI outputs).
        // Decode to PCM consistently, so switching leveling never needs an audible sink restart.
        return object : ForwardingAudioSink(sink) {
            override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatSupport(format: Format): Int = if (format.sampleMimeType == MimeTypes.AUDIO_RAW)
                super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
    }
}
