package com.secretvault.app.core.processing

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Silences [ranges] (output timeline, ms) of the exported audio by writing zeros, which is silence for both 16-bit and
 * float PCM. Time is counted from the first frame it sees, so it must run on the whole output (composition effects).
 */
@UnstableApi
class MuteAudioProcessor(ranges: List<VideoSegment>) : BaseAudioProcessor() {
    private val rangesUs = ranges.map { it.startMs * 1000 until it.endMs * 1000 }
    // ponytail: not reset on flush; Transformer exports straight through. Reset on flush if this is ever used for seeking playback.
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (rangesUs.isEmpty()) AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = inputAudioFormat.bytesPerFrame
        val sampleRate = inputAudioFormat.sampleRate.toLong()
        val output = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= frameBytes) {
            val timeUs = frames * 1_000_000 / sampleRate
            if (rangesUs.any { timeUs in it }) {
                repeat(frameBytes) { output.put(0) }
                inputBuffer.position(inputBuffer.position() + frameBytes)
            } else {
                repeat(frameBytes) { output.put(inputBuffer.get()) }
            }
            frames++
        }
        output.flip()
    }

    override fun onReset() { frames = 0 }
}
