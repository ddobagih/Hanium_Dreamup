package kr.co.hanium.dreamup.walksafe.inference

enum class RuntimeBackend(val delegate: String) { CPU("cpu"), GPU("gpu") }

/** The thread count concerns model operations, including GPU's remaining CPU operations. */
data class RuntimeCandidate(val backend: RuntimeBackend, val numThreads: Int) {
    init { require(numThreads > 0) }
}

enum class RuntimeFeatureScope { CAMERA_TRACKING, CAMERA_TRACKING_METRIC_DEPTH }

/** Immutable conditions captured with each observation; never inferred from device name. */
data class RuntimeComparisonEnvironment(
    val bindingHash: String,
    val featureScope: RuntimeFeatureScope,
    val availableProcessors: Int,
    val thermalStatus: Int,
    val powerSaveMode: Boolean,
    val cameraState: String,
    val arCoreState: String,
    val cadenceMs: Long,
    val workloadVersion: String,
)

enum class RuntimeExecutionFailure { UNSUPPORTED, BACKEND_FALLBACK, OUTPUT_MISMATCH, EXECUTION_ERROR }

/** Both outputs must cover the versioned positive, empty and threshold-near fixture cases. */
data class RuntimeFixtureEvidence(
    val version: String,
    val hash: String,
    val requiredCaseCount: Int,
    val completedCaseCount: Int,
    val outputEquivalent: Boolean,
    val baselineActualBackend: RuntimeBackend?,
    val candidateActualBackend: RuntimeBackend?,
    val failure: RuntimeExecutionFailure? = null,
    val requiredCaseIds: Set<String> = emptySet(),
    val completedCaseIds: Set<String> = emptySet(),
)

enum class RuntimeComparisonOrder { AB, BA, CONFIRMATION }

data class RuntimePairedSample(
    val fixtureCaseId: String,
    val baselineMs: Double,
    val candidateMs: Double,
    val baselineEnvironment: RuntimeComparisonEnvironment,
    val candidateEnvironment: RuntimeComparisonEnvironment,
    val baselineCompleted: Boolean = true,
    val candidateCompleted: Boolean = true,
    val speechActive: Boolean = false,
)

data class RuntimeComparisonBlock(
    val order: RuntimeComparisonOrder,
    val pairs: List<RuntimePairedSample>,
    /** Absolute differences of repeated A observations around the block. */
    val aaVariationMs: List<Double>,
)

/** Live camera measurements only; fixture timings must never populate this type. */
data class RuntimeLiveMetrics(
    val environment: RuntimeComparisonEnvironment,
    val actualBackend: RuntimeBackend,
    val captureToCompleteMs: List<Double>,
    val observationDurationMs: Long,
    val validCompletions: Int,
    val submittedFrames: Int,
    val staleFrames: Int,
    val droppedFrames: Int,
    val cameraFrameIntervalMs: Double,
    val trackingCostMs: Double,
    val uiFrameDelayMs: Double,
    val depthTimestampFresh: Boolean,
    val positiveDepthSamples: Int,
    val poseValid: Boolean,
    val adaptivePacing: Boolean,
    val uniqueCameraFrames: Int,
    val longestCompletionGapMs: Double,
)

data class RuntimeMeasurementSummary(
    val ab: RuntimeComparisonBlock,
    val ba: RuntimeComparisonBlock,
    val confirmation: RuntimeComparisonBlock,
    val baselineLive: RuntimeLiveMetrics,
    val candidateLive: RuntimeLiveMetrics,
    val fixture: RuntimeFixtureEvidence,
    val clockResolutionMs: Double,
)

enum class RuntimeSelectionStatus { CONFIRMED, INCONCLUSIVE, PARTIAL, REJECTED, UNSUPPORTED }

data class RuntimeSelectionDecision(
    val status: RuntimeSelectionStatus,
    val selectedCandidate: RuntimeCandidate,
    val reason: String,
    val measurement: RuntimeMeasurementSummary?,
    val baselineCandidate: RuntimeCandidate? = null,
    val comparedCandidate: RuntimeCandidate? = null,
)

/** Compact, persistable evidence, issued only after all selection checks pass. */
data class RuntimeConfirmedMeasurement(
    val bindingHash: String,
    val featureScope: RuntimeFeatureScope,
    val actualBackend: RuntimeBackend,
    val comparedPairCount: Int,
    val confirmedPairCount: Int,
    val baselineMedianMs: Double,
    val selectedMedianMs: Double,
    val uncertaintyMs: Double,
    val fixtureVersion: String,
    val fixtureHash: String,
    val liveValidCompletions: Int,
    val liveObservationDurationMs: Long,
    val policyVersion: String = RuntimeSelectionPolicy.VERSION,
)

/** Stores aggregate validation only. Account, image and location data have no place here. */
data class RuntimeTuningProfile(
    val bindingHash: String,
    val candidate: RuntimeCandidate,
    val featureScope: RuntimeFeatureScope,
    val fixtureVersion: String,
    val fixtureHash: String,
    val policyVersion: String,
    val measuredAtEpochMs: Long,
    val measurement: RuntimeConfirmedMeasurement,
) {
    fun isValid(expectedBindingHash: String, availableProcessors: Int): Boolean =
        RuntimeSelectionPolicy.isValidProfile(this, expectedBindingHash, availableProcessors)

    companion object {
        /** PARTIAL and INCONCLUSIVE can never be converted into an active profile. */
        fun fromConfirmed(
            decision: RuntimeSelectionDecision,
            measuredAtEpochMs: Long,
        ): RuntimeTuningProfile? = RuntimeSelectionPolicy.profileFromConfirmed(decision, measuredAtEpochMs)
    }
}

data class RuntimeCandidateOutcome(val candidate: RuntimeCandidate, val status: RuntimeSelectionStatus)

/** Finite work history plus cursors, not an eagerly allocated 1..N list. */
data class RuntimeCandidateQueueSnapshot(
    val availableProcessors: Int,
    val baseline: RuntimeCandidate,
    val gpuSupported: Boolean,
    val initialGpuThreads: Int,
    val preferredBackend: RuntimeBackend?,
    val cpuAnchor: Int,
    val gpuAnchor: Int,
    val cpuCursor: Int,
    val gpuCursor: Int,
    val initialGpuVisited: Boolean,
    val baselineVisited: Boolean,
    val nextAlternatingBackend: RuntimeBackend,
    val outcomes: List<RuntimeCandidateOutcome>,
)
