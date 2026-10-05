package com.secretvault.app.processing

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.secretvault.app.core.processing.MuteAudioProcessor
import com.secretvault.app.core.processing.VideoSegment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MuteAudioProcessorTest {
    // 1000 frames/s, mono 16-bit: frame n is at n ms and holds the value n + 1 (never 0).
    private val format = AudioFormat(1000, 1, C.ENCODING_PCM_16BIT)

    private fun run(processor: MuteAudioProcessor, frames: Int, chunk: Int): ShortArray {
        processor.configure(format); processor.flush()
        val out = mutableListOf<Short>()
        var next = 0
        while (next < frames) {
            val count = minOf(chunk, frames - next)
            val input = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
            repeat(count) { input.putShort((next + it + 1).toShort()) }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.nativeOrder())
            while (output.hasRemaining()) out += output.short
            next += count
        }
        return out.toShortArray()
    }

    @Test fun silencesOnlyInsideRangesAcrossBufferBoundaries() {
        val result = run(MuteAudioProcessor(listOf(VideoSegment(3, 6), VideoSegment(8, 9))), frames = 10, chunk = 4)
        assertArrayEquals(shortArrayOf(1, 2, 3, 0, 0, 0, 7, 8, 0, 10), result)
    }

    @Test fun inactiveWithoutRanges() {
        val processor = MuteAudioProcessor(emptyList())
        processor.configure(format)
        assertFalse(processor.isActive)
    }
}
