package com.nuvio.tv.core.assessment

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import kotlinx.coroutines.flow.first
import org.json.JSONObject

object DeviceAssessmentApplier {

    data class ApplyOutcome(val writtenCount: Int)

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun apply(
        dataStore: PlayerSettingsDataStore,
        plan: AssessmentApplyPlan,
        profile: IntentProfileOption?,
        safeLimitMb: Int
    ): ApplyOutcome {
        val before = dataStore.playerSettings.first()
        dataStore.setAssessmentRevertSnapshot(snapshotJson(before))

        var written = 0
        suspend fun step(block: suspend () -> Unit) {
            block()
            written += 1
        }

        // 1. Performance mode first: enabling it rewrites the buffer set.
        plan.nuvioPerformanceModeEnabled?.let { step { dataStore.setNuvioPerformanceModeEnabled(it) } }
        // 2. Masters and budget mode.
        plan.bufferEngineEnabled?.let { step { dataStore.setBufferEngineEnabled(it) } }
        plan.parallelNetworkEnabled?.let { step { dataStore.setParallelNetworkEnabled(it) } }
        plan.bufferBudgetManaged?.let { step { dataStore.setBufferBudgetManaged(it) } }
        // 3. Slider range before the value that needs it.
        plan.allowLargeTargetBuffer?.let { step { dataStore.setAllowLargeTargetBuffer(it) } }
        plan.targetBufferSizeMb?.let { step { dataStore.setBufferTargetSizeMb(it) } }
        // 4. Profile trio (min BEFORE max), then the max cap.
        profile?.let {
            step { dataStore.setBufferMinBufferMs(it.minBufferMs) }
            step { dataStore.setBufferForPlaybackMs(it.initialBufferMs) }
            step { dataStore.setBufferForPlaybackAfterRebufferMs(it.rebufferMs) }
        }
        plan.maxBufferMs?.let { newMax ->
            if (profile == null && before.bufferSettings.minBufferMs > newMax) {
                step { dataStore.setBufferMinBufferMs(newMax) }
            }
            step { dataStore.setBufferMaxBufferMs(newMax) }
        }
        // 5. Parallel pipe.
        plan.useParallelConnections?.let { step { dataStore.setUseParallelConnections(it) } }
        plan.parallelConnectionCount?.let { step { dataStore.setParallelConnectionCount(it) } }
        plan.parallelChunkSizeKb?.let { step { dataStore.setParallelChunkSizeKb(it) } }
        plan.enableHttp2?.let { step { dataStore.setEnableHttp2(it) } }
        // 6. VOD, display, audio route.
        plan.vodCacheEnabled?.let { step { dataStore.setVodCacheEnabled(it) } }
        plan.vodCacheSizeMode?.let { step { dataStore.setVodCacheSizeMode(it) } }
        plan.frameRateMatchingMode?.let { step { dataStore.setFrameRateMatchingMode(it) } }
        plan.resolutionMatchingEnabled?.let { step { dataStore.setResolutionMatchingEnabled(it) } }
        plan.forceOpticalPassthrough?.let { step { dataStore.setForceOpticalPassthrough(it) } }

        val after = dataStore.playerSettings.first()
        val afterOverheadMb = if (after.useParallelConnections) {
            MemoryBudget.parallelOverheadMb(
                after.parallelConnectionCount,
                (after.parallelChunkSizeKb + 1023) / 1024
            )
        } else {
            0
        }
        val maxSafeTargetMb =
            (((safeLimitMb - afterOverheadMb) / MemoryBudget.BUFFER_STEP_MB) * MemoryBudget.BUFFER_STEP_MB)
                .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
        if (after.bufferSettings.targetBufferSizeMb > maxSafeTargetMb) {
            step { dataStore.setBufferTargetSizeMb(maxSafeTargetMb) }
        }

        return ApplyOutcome(writtenCount = written)
    }

    suspend fun revert(dataStore: PlayerSettingsDataStore): Boolean {
        val json = dataStore.assessmentRevertSnapshot.first() ?: return false
        val snap = runCatching { JSONObject(json) }.getOrNull() ?: run {
            dataStore.setAssessmentRevertSnapshot(null)
            return false
        }

        return runCatching {
            dataStore.setNuvioPerformanceModeEnabled(snap.getBoolean("nuvioPerformanceModeEnabled"))
            dataStore.setBufferEngineEnabled(snap.getBoolean("bufferEngineEnabled"))
            dataStore.setParallelNetworkEnabled(snap.getBoolean("parallelNetworkEnabled"))
            dataStore.setBufferBudgetManaged(snap.getBoolean("bufferBudgetManaged"))
            dataStore.setAllowLargeTargetBuffer(snap.getBoolean("allowLargeTargetBuffer"))
            dataStore.setBufferTargetSizeMb(snap.getInt("targetBufferSizeMb"))
            dataStore.setBufferMinBufferMs(snap.getInt("minBufferMs"))
            dataStore.setBufferForPlaybackMs(snap.getInt("bufferForPlaybackMs"))
            dataStore.setBufferForPlaybackAfterRebufferMs(snap.getInt("bufferForPlaybackAfterRebufferMs"))
            dataStore.setBufferMaxBufferMs(snap.getInt("maxBufferMs"))
            dataStore.setUseParallelConnections(snap.getBoolean("useParallelConnections"))
            dataStore.setParallelConnectionCount(snap.getInt("parallelConnectionCount"))
            dataStore.setParallelChunkSizeKb(snap.getInt("parallelChunkSizeKb"))
            dataStore.setEnableHttp2(snap.getBoolean("enableHttp2"))
            dataStore.setVodCacheEnabled(snap.getBoolean("vodCacheEnabled"))
            dataStore.setVodCacheSizeMode(
                snap.optEnum("vodCacheSizeMode", VodCacheSizeMode.entries)
            )
            dataStore.setFrameRateMatchingMode(
                snap.optEnum("frameRateMatchingMode", FrameRateMatchingMode.entries)
            )
            dataStore.setResolutionMatchingEnabled(snap.getBoolean("resolutionMatchingEnabled"))
            dataStore.setForceOpticalPassthrough(snap.getBoolean("forceOpticalPassthrough"))
        }.fold(
            onSuccess = {
                dataStore.setAssessmentRevertSnapshot(null)
                true
            },
            onFailure = {
                dataStore.setAssessmentRevertSnapshot(null)
                false
            }
        )
    }

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(
        key: String,
        values: List<T>,
        fallback: T? = null
    ): T {
        val raw = optString(key, "")
        return values.firstOrNull { it.name == raw }
            ?: fallback
            ?: values.first()
    }

    private fun snapshotJson(s: PlayerSettings): String = JSONObject().apply {
        put("timestampMs", System.currentTimeMillis())
        put("nuvioPerformanceModeEnabled", s.nuvioPerformanceModeEnabled)
        put("bufferEngineEnabled", s.bufferEngineEnabled)
        put("parallelNetworkEnabled", s.parallelNetworkEnabled)
        put("bufferBudgetManaged", s.bufferBudgetManaged)
        put("allowLargeTargetBuffer", s.allowLargeTargetBuffer)
        put("targetBufferSizeMb", s.bufferSettings.targetBufferSizeMb)
        put("minBufferMs", s.bufferSettings.minBufferMs)
        put("maxBufferMs", s.bufferSettings.maxBufferMs)
        put("bufferForPlaybackMs", s.bufferSettings.bufferForPlaybackMs)
        put("bufferForPlaybackAfterRebufferMs", s.bufferSettings.bufferForPlaybackAfterRebufferMs)
        put("useParallelConnections", s.useParallelConnections)
        put("parallelConnectionCount", s.parallelConnectionCount)
        put("parallelChunkSizeKb", s.parallelChunkSizeKb)
        put("enableHttp2", s.enableHttp2)
        put("vodCacheEnabled", s.vodCacheEnabled)
        put("vodCacheSizeMode", s.vodCacheSizeMode.name)
        put("frameRateMatchingMode", s.frameRateMatchingMode.name)
        put("resolutionMatchingEnabled", s.resolutionMatchingEnabled)
        put("forceOpticalPassthrough", s.forceOpticalPassthrough)
    }.toString()
}
