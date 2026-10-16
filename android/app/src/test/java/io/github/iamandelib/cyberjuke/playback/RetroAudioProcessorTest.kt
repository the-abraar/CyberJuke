package io.github.iamandelib.cyberjuke.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroAudioProcessorTest {
    private val stereo44k = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    /** Interleaved stereo 16-bit sine, the same on both channels. */
    private fun sine(frames: Int, hz: Double = 440.0, amp: Double = 0.8, rate: Int = 44_100): ShortArray =
        ShortArray(frames * 2) { i -> (sin(2 * PI * hz * (i / 2) / rate) * amp * 32767).toInt().toShort() }

    private fun RetroAudioProcessor.run(samples: ShortArray): ShortArray {
        val buf = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        buf.asShortBuffer().put(samples)
        queueInput(buf)
        val out = output.order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(out.remaining()).also { out.get(it) }
    }

    private fun processor(enabled: Boolean) = RetroAudioProcessor().apply {
        configure(stereo44k)
        this.enabled = enabled
        flush(StreamMetadata.DEFAULT)
    }

    @Test fun offIsABitExactCopy() {
        val input = sine(4_410)
        assertArrayEquals(input, processor(enabled = false).run(input))
    }

    @Test fun onQuantisesAndHoldsSamples() {
        val p = processor(enabled = true)
        val out = p.run(sine(44_100)).filterIndexed { i, _ -> i % 2 == 0 } // left channel
        val tail = out.drop(4_410) // past the low-pass settling
        assertTrue("at most 2^BITS distinct levels, got ${tail.toSet().size}", tail.toSet().size <= 1 shl RetroAudioProcessor.BITS)
        // sample-and-hold at ~11 kHz from 44.1 kHz: runs of about 4 equal samples
        var runs = 1
        for (i in 1 until tail.size) if (tail[i] != tail[i - 1]) runs++
        val meanRun = tail.size.toDouble() / runs
        assertTrue("mean held run $meanRun", meanRun in 3.0..6.0)
    }

    @Test fun togglingRampsInsteadOfJumping() {
        val p = processor(enabled = false)
        val input = sine(4_410)
        p.run(input)
        p.enabled = true
        val out = p.run(input) // same signal continues; only the effect fades in
        val diff = IntArray(out.size) { kotlin.math.abs(out[it] - input[it]) }
        assertTrue("first frames stay near dry: ${diff.take(10)}", diff.take(10).all { it < 330 }) // < 1% full scale
        assertTrue("effect is fully in after the ramp", diff.drop(2 * 2_205).any { it > 1_000 }) // past 50 ms
    }

    @Test fun flushClearsHeldState() {
        val p = processor(enabled = true)
        p.run(sine(4_410, amp = 0.95))
        p.flush(StreamMetadata.DEFAULT)
        val afterSeek = p.run(ShortArray(200)) // silence after a seek
        assertTrue("no old sample after flush: ${afterSeek.maxOf { kotlin.math.abs(it.toInt()) }}", afterSeek.all { it.toInt() == 0 })
    }

    @Test fun floatPcmWorksAndOtherEncodingsAreSkipped() {
        val p = RetroAudioProcessor()
        assertEquals(C.ENCODING_PCM_FLOAT, p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)).encoding)
        // NOT_SET (inactive), never a throw: DefaultAudioSink turns a throw into a playback error
        val other = RetroAudioProcessor()
        assertEquals(AudioFormat.NOT_SET, other.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_8BIT)))
        other.flush(StreamMetadata.DEFAULT)
        assertFalse(other.isActive)
    }

    @Test fun formatChangeTakesEffectOnlyAtFlush() {
        val p = processor(enabled = true)
        p.configure(AudioFormat(8_000, 2, C.ENCODING_PCM_16BIT)) // pending: hold step would become > 1
        val stillOld = p.run(sine(44_100)).filterIndexed { i, _ -> i % 2 == 0 }.drop(4_410)
        var runs = 1
        for (i in 1 until stillOld.size) if (stillOld[i] != stillOld[i - 1]) runs++
        assertTrue("draining buffers keep the 44.1 kHz hold", stillOld.size.toDouble() / runs in 3.0..6.0)
    }
}
