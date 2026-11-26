package com.nuvio.tv.core.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Real-time telemetry snapshot emitted by [DjDeckAudioProcessor] for Deck A / Deck B meters.
 */
data class DjMeterSnapshot(
    val vuLevel: Float = 0f,
    val bassEnergy: Float = 0f,
    val midEnergy: Float = 0f,
    val highEnergy: Float = 0f,
    val beatPulse: Float = 0f,
    val beatCount: Long = 0L,
    val liveWaveform: List<Float> = emptyList(),
)

/**
 * Media3 [BaseAudioProcessor] providing real-time 3-band EQ (Bass Swap & Filter Sweep),
 * level metering, kick-transient detection, and a 32-bin live PCM waveform per DJ deck.
 */
@UnstableApi
class DjDeckAudioProcessor(
    val deckId: DjDeckId,
) : BaseAudioProcessor() {

    companion object {
        private const val MAX_CHANNELS = 8
        private const val LOW_CROSSOVER_HZ = 250.0
        private const val HIGH_CROSSOVER_HZ = 2800.0
        private const val WAVEFORM_SIZE = 32
        private const val GAIN_MIN = 0f
        private const val GAIN_MAX = 1.5f
    }

    @Volatile
    var lowGain: Float = 1.0f

    @Volatile
    var midGain: Float = 1.0f

    @Volatile
    var highGain: Float = 1.0f

    private val meterLock = Any()
    private var vuLevel: Float = 0f
    private var bassEnergy: Float = 0f
    private var midEnergy: Float = 0f
    private var highEnergy: Float = 0f
    private var bassMovingAvg: Float = 0.05f
    private var beatPulse: Float = 0f
    private var beatCount: Long = 0L
    private val waveformRing = FloatArray(WAVEFORM_SIZE)
    private var waveformWriteIdx = 0
    private var beatCooldownFrames = 0

    // Single-pole IIR crossover state (owned by Media3 audio thread once configured)
    private var configuredRate: Int = 44100
    private var configuredChannels: Int = 2
    private val lpLow = FloatArray(MAX_CHANNELS)
    private val lpHighMid = FloatArray(MAX_CHANNELS)
    private var alphaLow: Float = 0.035f
    private var alphaHighMid: Float = 0.32f

    fun resetEq() {
        lowGain = 1.0f
        midGain = 1.0f
        highGain = 1.0f
    }

    fun snapshot(): DjMeterSnapshot {
        synchronized(meterLock) {
            val out = ArrayList<Float>(WAVEFORM_SIZE)
            for (i in 0 until WAVEFORM_SIZE) {
                val idx = (waveformWriteIdx + i) % WAVEFORM_SIZE
                out.add(waveformRing[idx])
            }
            return DjMeterSnapshot(
                vuLevel = vuLevel,
                bassEnergy = bassEnergy,
                midEnergy = midEnergy,
                highEnergy = highEnergy,
                beatPulse = beatPulse,
                beatCount = beatCount,
                liveWaveform = out,
            )
        }
    }

    override fun isActive(): Boolean = super.isActive()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> {
                configuredRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
                configuredChannels = inputAudioFormat.channelCount.coerceIn(1, MAX_CHANNELS)
                recomputeCoefficients()
                clearFilterState()
                inputAudioFormat
            }
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val inputSize = inputBuffer.remaining()
        val format = inputAudioFormat
        val encoding = format.encoding
        val channels = format.channelCount.coerceIn(1, MAX_CHANNELS)
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> {
                inputBuffer.position(inputBuffer.limit())
                return
            }
        }
        val frameBytes = channels * bytesPerSample
        val frameCount = inputSize / frameBytes
        if (frameCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val src = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val out = replaceOutputBuffer(frameCount * frameBytes).order(ByteOrder.LITTLE_ENDIAN)

        val lg = lowGain.coerceIn(GAIN_MIN, GAIN_MAX)
        val mg = midGain.coerceIn(GAIN_MIN, GAIN_MAX)
        val hg = highGain.coerceIn(GAIN_MIN, GAIN_MAX)
        val aLow = alphaLow
        val aHigh = alphaHighMid

        var peak = 0f
        var sumBass = 0f
        var sumMid = 0f
        var sumHigh = 0f

        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val x = if (encoding == C.ENCODING_PCM_16BIT) {
                    src.short.toFloat() / 32768f
                } else {
                    src.float
                }

                // 3-band split: lpLow -> bass, lpHighMid - lpLow -> mids, x - lpHighMid -> highs
                lpLow[c] += aLow * (x - lpLow[c])
                val low = lpLow[c]
                lpHighMid[c] += aHigh * (x - lpHighMid[c])
                val lowMid = lpHighMid[c]
                val mid = lowMid - low
                val high = x - lowMid

                val mixed = low * lg + mid * mg + high * hg
                val limited = if (abs(mixed) > 0.92f) tanh(mixed.toDouble()).toFloat() else mixed
                val clamped = limited.coerceIn(-1f, 1f)

                if (encoding == C.ENCODING_PCM_16BIT) {
                    val sample16 = (clamped * 32767f).roundToInt().coerceIn(-32768, 32767)
                    out.putShort(sample16.toShort())
                } else {
                    out.putFloat(clamped)
                }

                val amp = abs(clamped)
                if (amp > peak) peak = amp
                sumBass += abs(low)
                sumMid += abs(mid)
                sumHigh += abs(high)
            }
        }

        inputBuffer.position(inputBuffer.position() + frameCount * frameBytes)
        out.flip()

        val denom = (frameCount * channels).coerceAtLeast(1).toFloat()
        updateMeters(
            peak = peak,
            bass = (sumBass / denom).coerceIn(0f, 1f),
            mid = (sumMid / denom).coerceIn(0f, 1f),
            high = (sumHigh / denom).coerceIn(0f, 1f),
            frameCount = frameCount,
        )
    }

    override fun onFlush() {
        clearFilterState()
    }

    override fun onReset() {
        clearFilterState()
        resetEq()
        synchronized(meterLock) {
            vuLevel = 0f
            bassEnergy = 0f
            midEnergy = 0f
            highEnergy = 0f
            bassMovingAvg = 0.05f
            beatPulse = 0f
            beatCount = 0L
            beatCooldownFrames = 0
            waveformRing.fill(0f)
            waveformWriteIdx = 0
        }
    }

    private fun recomputeCoefficients() {
        val dt = 1.0 / configuredRate.toDouble()
        val rcLow = 1.0 / (2.0 * PI * LOW_CROSSOVER_HZ)
        val rcHigh = 1.0 / (2.0 * PI * HIGH_CROSSOVER_HZ)
        alphaLow = (dt / (rcLow + dt)).toFloat().coerceIn(0.002f, 0.95f)
        alphaHighMid = (dt / (rcHigh + dt)).toFloat().coerceIn(0.02f, 0.95f)
    }

    private fun clearFilterState() {
        lpLow.fill(0f)
        lpHighMid.fill(0f)
    }

    private fun updateMeters(
        peak: Float,
        bass: Float,
        mid: Float,
        high: Float,
        frameCount: Int,
    ) {
        synchronized(meterLock) {
            vuLevel = (vuLevel * 0.65f + peak * 0.35f).coerceIn(0f, 1f)
            bassEnergy = (bassEnergy * 0.68f + bass * 0.32f).coerceIn(0f, 1f)
            midEnergy = (midEnergy * 0.68f + mid * 0.32f).coerceIn(0f, 1f)
            highEnergy = (highEnergy * 0.68f + high * 0.32f).coerceIn(0f, 1f)

            bassMovingAvg = bassMovingAvg * 0.94f + bass * 0.06f
            beatCooldownFrames = (beatCooldownFrames - frameCount).coerceAtLeast(0)

            val transient = bass > bassMovingAvg * 1.42f && bass > 0.055f
            if (transient && beatCooldownFrames == 0) {
                beatPulse = 1f
                beatCount += 1L
                // ~240ms refractory window prevents double-triggering inside a single kick drum hit
                beatCooldownFrames = (configuredRate * 0.24f).roundToInt()
            } else {
                beatPulse = (beatPulse * 0.82f).let { if (it < 0.02f) 0f else it }
            }

            waveformRing[waveformWriteIdx] = (vuLevel * 0.85f + beatPulse * 0.15f).coerceIn(0.04f, 1f)
            waveformWriteIdx = (waveformWriteIdx + 1) % WAVEFORM_SIZE
        }
    }
}
