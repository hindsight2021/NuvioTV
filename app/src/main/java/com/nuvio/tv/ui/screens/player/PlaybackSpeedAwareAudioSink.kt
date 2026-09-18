package com.nuvio.tv.ui.screens.player

import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Audio sink wrapper that forces a decode-to-PCM path when:
 * - Playback speed != 1x for bitstream formats that cannot be tempo-adjusted in passthrough, or
 * - Bluetooth media output is active (Media3 policy: Bluetooth only supports PCM).
 *
 * Bluetooth cannot carry TrueHD / Atmos / DTS-HD passthrough. Forcing PCM lets MediaCodec/FFmpeg
 * decode to the format the BT stack actually accepts; the system then encodes to SBC/AAC/aptX/LDAC.
 *
 * Also supports lower DIRECT start threshold frames on API 31+ to eliminate cold-start passthrough freeze.
 */
internal class PlaybackSpeedAwareAudioSink(
    sink: AudioSink,
    initialForcePcm: Boolean = false,
    forcePcmForBluetooth: Boolean = false,
    private val reducedStartThresholdFrames: Int = 0
) : ForwardingAudioSink(sink) {

    private val baseSink: AudioSink = sink
    private var startThresholdAppliedThisTrack: Boolean = false
    private var cachedAudioTrackField: java.lang.reflect.Field? = null
    private var audioTrackFieldLookupFailed: Boolean = false

    // Set when the sink is built with forcePcm (error recovery). Don't clear on speed reset.
    private val startedWithForcedPcm: Boolean = initialForcePcm

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = initialForcePcm

    @Volatile
    private var bluetoothForcePcm: Boolean = forcePcmForBluetooth

    @Volatile
    private var currentInputFormat: Format? = null

    @Volatile
    private var listener: AudioSink.Listener? = null

    fun setInitialPlaybackSpeed(speed: Float) {
        playbackSpeed = normalizeSpeed(speed)
        markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
    }

    /**
     * Update Bluetooth policy without rebuilding the player.
     * Call [notifyAudioProcessingRequirementChanged] after a change so Media3 reselects
     * decode-to-PCM vs passthrough on the live renderer.
     *
     * @return true when the effective PCM/passthrough policy changed.
     */
    fun setBluetoothForcePcm(enabled: Boolean): Boolean {
        val wasBluetoothForce = bluetoothForcePcm
        val wasSessionForce = forcePcmForCurrentSession
        bluetoothForcePcm = enabled
        if (enabled) {
            forcePcmForCurrentSession = true
        } else if (!startedWithForcedPcm && playbackSpeed == 1f) {
            // Session was not built as PCM-only; leaving Bluetooth can restore passthrough.
            forcePcmForCurrentSession = false
        }
        return wasBluetoothForce != bluetoothForcePcm || wasSessionForce != forcePcmForCurrentSession
    }

    fun isBluetoothForcePcm(): Boolean = bluetoothForcePcm

    val isCurrentlyPassthrough: Boolean
        get() {
            val format = currentInputFormat ?: return false
            return isEncodedPassthroughCandidate(format) && !bluetoothForcePcm && !forcePcmForCurrentSession
        }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentInputFormat = inputFormat
        startThresholdAppliedThisTrack = false
        markPcmFallbackIfNeeded(inputFormat, playbackSpeed)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun flush() {
        startThresholdAppliedThisTrack = false
        super.flush()
    }

    override fun reset() {
        startThresholdAppliedThisTrack = false
        super.reset()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        bufferPresentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (isCurrentlyPassthrough && reducedStartThresholdFrames > 0 &&
            !startThresholdAppliedThisTrack && Build.VERSION.SDK_INT >= 31
        ) {
            try {
                val defaultSink = baseSink as? DefaultAudioSink
                val stTrack = if (defaultSink != null && !audioTrackFieldLookupFailed) {
                    val field = cachedAudioTrackField
                        ?: DefaultAudioSink::class.java.getDeclaredField("audioTrack")
                            .apply { isAccessible = true }
                            .also { cachedAudioTrackField = it }
                    field.get(defaultSink) as? AudioTrack
                } else null
                if (stTrack != null) {
                    val buf = stTrack.bufferSizeInFrames
                    if (buf > 0) {
                        val target = reducedStartThresholdFrames.coerceIn(1, (buf - 1).coerceAtLeast(1))
                        val ret = stTrack.setStartThresholdInFrames(target)
                        Log.w("PlaybackSpeedSink", "STHRESH: applied req=$reducedStartThresholdFrames target=$target ret=$ret buf=$buf")
                        startThresholdAppliedThisTrack = true
                    }
                }
            } catch (t: Throwable) {
                Log.w("PlaybackSpeedSink", "STHRESH: apply failed: ${t.message}")
                audioTrackFieldLookupFailed = true
                startThresholdAppliedThisTrack = true
            }
        }
        return super.handleBuffer(buffer, bufferPresentationTimeUs, encodedAccessUnitCount)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        playbackSpeed = normalizeSpeed(playbackParameters.speed)
        var shouldNotify = markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
        // Going above 1x latches forcePcm for the session. Clear it when back at 1.0x
        // so passthrough can recover (unless recovery built us with forcePcm).
        if (playbackSpeed == 1f && forcePcmForCurrentSession && !startedWithForcedPcm) {
            forcePcmForCurrentSession = false
            shouldNotify = true
        }
        super.setPlaybackParameters(playbackParameters)
        if (shouldNotify) {
            listener?.onAudioCapabilitiesChanged()
        }
    }

    fun notifyAudioProcessingRequirementChanged() {
        listener?.onAudioCapabilitiesChanged()
    }

    override fun getFormatSupport(format: Format): Int {
        if (shouldRejectDirectPlayback(format)) {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        return super.getFormatSupport(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (shouldRejectDirectPlayback(format)) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return super.getFormatOffloadSupport(format)
    }

    fun shouldForcePcmForFormat(format: Format): Boolean {
        return shouldRejectDirectPlayback(format)
    }

    private fun shouldRejectDirectPlayback(format: Format): Boolean {
        if (!isEncodedPassthroughCandidate(format)) {
            return false
        }
        // Bluetooth: always decode to PCM (Media3 DEFAULT_AUDIO_CAPABILITIES policy).
        if (bluetoothForcePcm || forcePcmForCurrentSession) {
            return true
        }
        // Non-1x speed cannot be applied to bitstream passthrough tracks.
        return playbackSpeed != 1f
    }

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || !isEncodedPassthroughCandidate(format)) {
            return false
        }
        if (bluetoothForcePcm) {
            val wasForcingPcm = forcePcmForCurrentSession
            forcePcmForCurrentSession = true
            return !wasForcingPcm
        }
        if (speed == 1f) {
            return false
        }
        val wasForcingPcm = forcePcmForCurrentSession
        forcePcmForCurrentSession = true
        return !wasForcingPcm
    }

    private fun normalizeSpeed(speed: Float): Float {
        return speed.takeIf { it > 0f } ?: 1f
    }

    /**
     * Formats that devices may try to play via passthrough/offload and that Bluetooth cannot carry.
     * Matches Media3 surround encodings that need decode-to-PCM on A2DP/LE Audio.
     */
    private fun isEncodedPassthroughCandidate(format: Format): Boolean {
        val mimeType = format.sampleMimeType
        if (mimeType != null && (
                mimeType == MimeTypes.AUDIO_E_AC3 ||
                    mimeType == MimeTypes.AUDIO_E_AC3_JOC ||
                    mimeType == MimeTypes.AUDIO_AC3 ||
                    mimeType == MimeTypes.AUDIO_AC4 ||
                    mimeType == MimeTypes.AUDIO_TRUEHD ||
                    mimeType == MimeTypes.AUDIO_DTS ||
                    mimeType == MimeTypes.AUDIO_DTS_HD ||
                    mimeType == MimeTypes.AUDIO_DTS_EXPRESS ||
                    mimeType.startsWith("audio/vnd.dts")
                )
        ) {
            return true
        }
        val codecs = format.codecs
        if (codecs != null) {
            return codecs.contains("ac-3", ignoreCase = true) ||
                codecs.contains("ac-4", ignoreCase = true) ||
                codecs.contains("ec-3", ignoreCase = true) ||
                codecs.contains("dts", ignoreCase = true) ||
                codecs.contains("truehd", ignoreCase = true) ||
                codecs.contains("dtshd", ignoreCase = true)
        }
        return false
    }
}
