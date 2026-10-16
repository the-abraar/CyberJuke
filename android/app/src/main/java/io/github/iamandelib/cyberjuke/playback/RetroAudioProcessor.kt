package io.github.iamandelib.cyberjuke.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * 8-bit mode (8bitplan.md, phase 1): a live "retro console" effect on the decoded PCM.
 * Low-pass, fractional sample-and-hold, bit crush, soft drive, partial mono, gain compensation.
 *
 * Always active so toggling never reconfigures or flushes the sink; [enabled] only moves a 25 ms
 * wet/dry ramp, and fully off is a bit-exact copy. 16-bit and float PCM only; for anything else
 * onConfigure returns NOT_SET so the sink skips the processor (throwing would fail playback).
 * No allocation on the audio thread: per-channel state is sized once, for up to [MAX_CHANNELS].
 */
@UnstableApi
class RetroAudioProcessor : BaseAudioProcessor() {

    /** Set from any thread; the audio thread ramps towards it. */
    @Volatile var enabled = false

    private val lowPass = FloatArray(MAX_CHANNELS)
    private val held = FloatArray(MAX_CHANNELS)
    private val frame = FloatArray(MAX_CHANNELS)
    private var holdPhase = 1f
    private var holdStep = 1f
    private var lowPassCoeff = 1f
    private var mix = 0f
    private var mixStep = 1f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val e = inputAudioFormat.encoding
        if ((e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount > MAX_CHANNELS) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat // rate-dependent values are applied in onFlush, when this format takes effect
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val target = if (enabled) 1f else 0f
        if (mix == 0f && target == 0f) { // off: bit-exact bypass
            out.put(inputBuffer)
            out.flip()
            return
        }
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val channels = inputAudioFormat.channelCount
        val sampleBytes = if (float) 4 else 2
        val frames = bytes / (sampleBytes * channels)
        repeat(frames) {
            for (c in 0 until channels) {
                frame[c] = if (float) inputBuffer.float else inputBuffer.short / 32768f
            }
            mix = if (mix < target) minOf(target, mix + mixStep) else maxOf(target, mix - mixStep)
            holdPhase += holdStep
            val sample = holdPhase >= 1f
            if (sample) holdPhase -= 1f
            for (c in 0 until channels) {
                lowPass[c] += lowPassCoeff * (frame[c] - lowPass[c])
                if (sample) held[c] = crush(lowPass[c])
            }
            for (c in 0 until channels) {
                var wet = held[c]
                if (channels == 2) { // partial mono: keep a little of the side signal
                    val mid = (held[0] + held[1]) / 2
                    val side = (held[0] - held[1]) / 2
                    wet = if (c == 0) mid + side * KEEP_SIDE else mid - side * KEEP_SIDE
                }
                wet = (wet * OUTPUT_GAIN).coerceIn(-LIMIT, LIMIT)
                val y = frame[c] + (wet - frame[c]) * mix
                if (float) out.putFloat(y) else out.putShort((y * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit()) // a partial trailing frame cannot occur; skip it if it does
        out.flip()
    }

    /** Quantise to [BITS] bits (no dither: the stair-steps are the point), then a little soft drive. */
    private fun crush(x: Float): Float {
        val q = (x * LEVELS).roundToInt().coerceIn(-LEVELS, LEVELS - 1) / LEVELS.toFloat()
        return (tanh(q * DRIVE) / tanh(DRIVE)).toFloat()
    }

    override fun onFlush(streamMetadata: StreamMetadata) { // new format takes effect; seek or track change: no stale held sample or filter state
        val rate = inputAudioFormat.sampleRate.toFloat()
        holdStep = TARGET_RATE_HZ / rate // fractional, so 44.1 and 48 kHz land on the same held rate
        lowPassCoeff = (1 - exp(-2 * PI * LOW_PASS_HZ / rate)).toFloat()
        mixStep = 1f / (rate * RAMP_SECONDS)
        clearState()
        mix = if (enabled) 1f else 0f
    }

    override fun onReset() {
        clearState()
        mix = 0f
    }

    private fun clearState() {
        lowPass.fill(0f)
        held.fill(0f)
        holdPhase = 1f
    }

    companion object {
        // Starting values to tune by ear (8bitplan.md section 4); one intensity in v1.
        const val TARGET_RATE_HZ = 11_025f
        const val LOW_PASS_HZ = 5_000.0
        const val BITS = 6
        const val LEVELS = 1 shl (BITS - 1) // 32 steps each side of zero: 64 levels
        const val DRIVE = 1.3
        const val KEEP_SIDE = 0.3f
        const val OUTPUT_GAIN = 0.85f // crushing adds energy; keep the switch level
        const val LIMIT = 0.98f
        const val RAMP_SECONDS = 0.025f
        const val MAX_CHANNELS = 8
    }
}
