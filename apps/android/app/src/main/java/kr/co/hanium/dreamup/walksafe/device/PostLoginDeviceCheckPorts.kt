package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus

/** Copied identity facts. No session/Activity object crosses this boundary. */
internal data class PostLoginDeviceCheckActorFacts(
    val foreground: Boolean,
    val actorId: String?,
    val sessionGeneration: Long?,
    val verifiedActorId: String?,
    val sessionVerified: Boolean,
    val sessionUsable: Boolean,
    val storageBlocked: Boolean,
    val deletionRecoveryOnly: Boolean,
    val quickStartSessionUsable: Boolean = false,
)

internal data class PostLoginDeviceCheckDetectorFacts(
    val loadAttempted: Boolean,
    val loadInFlight: Boolean,
    val configLoaded: Boolean,
    val available: Boolean,
    val readiness: PostLoginDeviceCheckSignal,
)

internal data class PostLoginDeviceCheckHardwareFacts(
    val startup: WalkSafeStartupCapabilityInput,
    val resourceReadiness: WalkSessionReadinessStatus,
    val thermalThrottled: Boolean?,
    val detector: PostLoginDeviceCheckDetectorFacts,
    val missingRequiredPermissions: List<String>,
    val cameraPermission: Boolean,
    val microphonePermission: Boolean,
    val locationServiceEnabled: Boolean,
    val voiceDisclosureAccepted: Boolean,
    val voiceModelPreparationFailed: Boolean,
    val speechRecognitionOverride: Boolean?,
    val koreanSpeechOverride: Boolean?,
)

internal interface PostLoginDeviceCheckFactsReader {
    fun actor(): PostLoginDeviceCheckActorFacts
    fun hardware(): PostLoginDeviceCheckHardwareFacts
    fun resultBinding(actorId: String): PostLoginDeviceCheckResultBinding?
}

internal interface PostLoginDeviceCheckSavedResults {
    fun candidatePresent(): Boolean
    fun restore(binding: PostLoginDeviceCheckResultBinding): PersistedPostLoginDeviceCheckResult?
    fun save(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckResultBinding,
        cameraDependentChecksDeferred: Boolean,
        metricDepthState: PostLoginMetricDepthState,
    ): Boolean
}

/** Adapts the existing durable store without changing its validation or persistence format. */
internal class AndroidPostLoginDeviceCheckSavedResults(
    private val store: () -> AndroidDeviceCheckResultStore?,
    private val acceptsRuntimeChoice: (PersistedPostLoginDeviceCheckResult) -> Boolean,
) : PostLoginDeviceCheckSavedResults {
    constructor(store: () -> AndroidDeviceCheckResultStore?) : this(store, { true })
    override fun candidatePresent() = store()?.hasCurrentPolicyResultCandidate() == true
    override fun restore(binding: PostLoginDeviceCheckResultBinding) =
        store()?.restore(binding)?.takeIf(acceptsRuntimeChoice)
    override fun save(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckResultBinding,
        cameraDependentChecksDeferred: Boolean,
        metricDepthState: PostLoginMetricDepthState,
    ) = store()?.save(snapshot, binding, cameraDependentChecksDeferred, metricDepthState) == true
}

internal fun interface DeviceCheckScheduledTask { fun cancel() }

internal interface PostLoginDeviceCheckScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): DeviceCheckScheduledTask
}

/** Signals are owned by the concrete probe services, never copied into Activity fields. */
internal data class PostLoginDeviceCheckProbeSignals(
    val cameraPipeline: PostLoginDeviceCheckSignal,
    val locationFix: PostLoginDeviceCheckSignal,
    val wakePhrase: PostLoginDeviceCheckSignal,
    val metricDepth: PostLoginMetricDepthState,
)

internal interface PostLoginDeviceCheckProbeSuite {
    val signals: PostLoginDeviceCheckProbeSignals
    val metricPreflightStarted: Boolean
    val capabilityProbeStarted: Boolean
    val storedBindingValidationPending: Boolean
    fun resetForAttempt(binding: PostLoginDeviceCheckBinding)
    fun restartCapabilityAndResources(binding: PostLoginDeviceCheckBinding, onChanged: () -> Unit)
    fun prepareVoiceModelAndDetector()
    fun startAutomaticProbes(binding: PostLoginDeviceCheckBinding)
    fun startCamera(binding: PostLoginDeviceCheckBinding)
    fun startMetricPreflight(binding: PostLoginDeviceCheckBinding)
    fun cancelCalibration(reason: String)
    fun cancelFunctionalProbes()
    fun cancelMetricPreflight(reason: String)
    fun closeCapabilityAndResetResources()
    fun clearStoredBindingValidation()
    fun stopCamera()
    fun restoreMetricDepth(state: PostLoginMetricDepthState)
    fun mayValidateStoredBinding(): Boolean
    fun startStoredBindingValidation(onChanged: () -> Unit): Boolean
    fun mayStartStoredMetricRefresh(binding: PostLoginDeviceCheckBinding): Boolean
    fun startStoredMetricRefresh(binding: PostLoginDeviceCheckBinding)
}

internal interface PostLoginDeviceCheckPermissionPort {
    fun acknowledgeVoiceDisclosure(): Boolean
    fun requestedPermissions(): List<String>
    fun request(binding: PostLoginDeviceCheckBinding, permissions: List<String>): Int?
    fun cancel(requestCode: Int)
    fun ownsDialog(requestCode: Int): Boolean
}

internal data class PostLoginDeviceCheckCapabilityOverrides(
    val metricDistance: Boolean?,
    val obstacleDetection: Boolean?,
    val koreanSpeech: Boolean?,
)

internal enum class PostLoginDeviceCheckPassContinuation {
    COMPLETED,
    TRANSITION_REJECTED,
    SESSION_CHANGED,
}

/** Narrow ordered effects owned by the app's composition root and existing presenters. */
internal interface PostLoginDeviceCheckEffects {
    fun invalidateRouteResponseLease()
    fun invalidateSpeechStorageLease()
    fun applyCapabilityOverrides(overrides: PostLoginDeviceCheckCapabilityOverrides)
    fun clearAttemptPresentation()
    fun showStatus(title: String, detail: String)
    fun announceTerminal(message: String)
    fun failureMessage(failure: PostLoginDeviceCheckFailure?): String
    fun refreshReadinessPresentation()
    fun noteCancellation(reason: String)
    fun recordPermissionObservation(binding: PostLoginDeviceCheckBinding): Boolean
    fun afterPermissionObservation(binding: PostLoginDeviceCheckBinding)
    fun afterPassed(snapshot: PostLoginDeviceCheckSnapshot): PostLoginDeviceCheckPassContinuation
    fun afterRestoredResult()
    fun scheduleCalibration()
    fun metricCapabilityChanged(available: Boolean)
    fun clearCameraCapabilityOverrides()
    fun prepareDetectorRetry()
}

internal data class StoredDeviceCheckMetricRefresh(
    val binding: PostLoginDeviceCheckBinding,
    val state: PostLoginDeviceCheckState,
    val disabledFeatures: Set<PostLoginDeviceCheckFeature>,
    val cameraDependentChecksDeferred: Boolean,
)
