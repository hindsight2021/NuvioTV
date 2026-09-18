package com.nuvio.tv.core.assessment

import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.screens.settings.MemoryUsageStatus

/**
 * Confidence tiers for Device Assessment recommendations.
 */
enum class AssessmentTier { MEASURED, CALCULATED, PRIORITY, VERIFY }

enum class ProfileKind { FAST_START, BALANCED, STALL_RESISTANT }

data class AssessmentItem(
    val key: String,
    val title: String,
    val currentValue: String?,
    val recommendedValue: String,
    val grounds: String,
    val tier: AssessmentTier,
    val changeNeeded: Boolean,
    val memoryStatus: MemoryUsageStatus? = null
)

data class IntentProfileOption(
    val kind: ProfileKind,
    val title: String,
    val subtitle: String,
    val initialBufferMs: Int,
    val rebufferMs: Int,
    val minBufferMs: Int,
    val minCappedFromMs: Int? = null
)

data class AssessmentHeaderFacts(
    val deviceRamLabel: String,
    val safeLimitMb: Int,
    val warningLimitMb: Int,
    val displaySummary: String?,
    val streamLabel: String?,
    val streamBitrateMbps: Double?
)

data class AssessmentResult(
    val timestampMs: Long,
    val header: AssessmentHeaderFacts,
    val sweepRan: Boolean = false,
    val sweepVerdictText: String? = null,
    val items: List<AssessmentItem>,
    val profiles: List<IntentProfileOption>,
    val suggestedProfile: ProfileKind?,
    val stabilityCoV: Double? = null,
    val stabilityPassCount: Int = 0,
    val applyPlan: AssessmentApplyPlan,
    val errorText: String? = null
)

/**
 * Machine-actionable side of the assessment items.
 * Strictly maintains 100% exclusion of Dolby Vision and HDR modifications.
 */
data class AssessmentApplyPlan(
    val nuvioPerformanceModeEnabled: Boolean? = null,
    val bufferEngineEnabled: Boolean? = null,
    val parallelNetworkEnabled: Boolean? = null,
    val bufferBudgetManaged: Boolean? = null,
    val allowLargeTargetBuffer: Boolean? = null,
    val targetBufferSizeMb: Int? = null,
    val maxBufferMs: Int? = null,
    val useParallelConnections: Boolean? = null,
    val parallelConnectionCount: Int? = null,
    val parallelChunkSizeKb: Int? = null,
    val enableHttp2: Boolean? = null,
    val vodCacheEnabled: Boolean? = null,
    val vodCacheSizeMode: VodCacheSizeMode? = null,
    val frameRateMatchingMode: FrameRateMatchingMode? = null,
    val resolutionMatchingEnabled: Boolean? = null,
    val forceOpticalPassthrough: Boolean? = null
) {
    val touchedCount: Int
        get() = listOfNotNull(
            nuvioPerformanceModeEnabled, bufferEngineEnabled, parallelNetworkEnabled,
            bufferBudgetManaged, allowLargeTargetBuffer, targetBufferSizeMb,
            maxBufferMs, useParallelConnections, parallelConnectionCount,
            parallelChunkSizeKb, enableHttp2, vodCacheEnabled, vodCacheSizeMode,
            frameRateMatchingMode, resolutionMatchingEnabled, forceOpticalPassthrough
        ).size
}
