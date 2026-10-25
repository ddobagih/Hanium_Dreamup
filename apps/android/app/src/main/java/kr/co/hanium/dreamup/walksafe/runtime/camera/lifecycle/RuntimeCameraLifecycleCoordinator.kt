package kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle

import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import kr.co.hanium.dreamup.walksafe.ArCoreStartGate
import kr.co.hanium.dreamup.walksafe.resolveArCoreStartGate
import kr.co.hanium.dreamup.walksafe.shouldRecheckArCoreAvailability
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.runtime.camera.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import kr.co.hanium.dreamup.walksafe.session.*

/** Actual runtime start, handoff and stop transactions; resources stay in their original owners. */
internal class RuntimeCameraLifecycleCoordinator<S : Any, P : Any, Q>(
    private val metricStateLock: Any,
    private val frameStateLock: Any,
    private val arOwner: RuntimeArSessionOwner<S, P, Q>,
    private val nonePurpose: Q,
    private val runtimePurpose: Q,
    private val fallback: () -> CameraFallbackController,
    private val walk: () -> WalkSessionLifecycle,
    private val admission: RuntimeCameraAdmission,
    private val platform: RuntimeArPlatform<S, P>,
    private val renderer: RuntimeCameraRenderer,
    private val metric: RuntimeCameraMetric,
    private val readiness: RuntimeCameraReadiness,
    private val presentation: RuntimeCameraPresentation,
    private val frames: RuntimeCameraFrameRetirement,
    private val elapsedRealtimeMs: () -> Long,
    private val sourceChanged: () -> Unit = {},
) {
    private val transactions = RuntimeArLifecycleTransactions(metricStateLock, arOwner, runtimePurpose,
        admission, platform, renderer, metric, readiness, presentation, frames, elapsedRealtimeMs, sourceChanged)
    private var installRequested = false
    private var pendingSessionStartAfterInstall = false
    private var arCoreAvailabilityRequestGeneration = 0
    private var arCoreAvailabilityCheckPending = false
    private var pendingCameraFallbackStart: PendingRuntimeCameraFallbackStart? = null

    fun pendingFallback() = pendingCameraFallbackStart
    fun clearPendingFallback() { pendingCameraFallbackStart = null }
    fun writePendingFallback(value: PendingRuntimeCameraFallbackStart?) { pendingCameraFallbackStart = value }
    fun installContinuationPending() = pendingSessionStartAfterInstall
    fun writeInstallContinuationPending(value: Boolean) { pendingSessionStartAfterInstall = value }
    fun clearInstallContinuationIfPending() {
        if (pendingSessionStartAfterInstall) pendingSessionStartAfterInstall = false
    }

    fun startDepthSession() {
        if (!admission.firstRunComplete()) return
        if (!admission.runtimeActive()) return
        if (!admission.requireStartupConfirmation()) return
        if (arOwner.observe().session != null) {
            presentation.status("ARCore Depth 실행 중", "이미 session이 시작돼 있습니다.")
            return
        }
        arCoreAvailabilityCheckPending = false
        val requestGeneration = ++arCoreAvailabilityRequestGeneration
        val lifecycleGeneration = admission.feedbackGeneration()
        val availability = platform.availability()
        if (shouldRecheckArCoreAvailability(availability)) {
            requestArCoreAvailabilityRecheck(requestGeneration, lifecycleGeneration)
            return
        }
        continueDepthSessionStart(availability)
    }

    fun requestArCoreAvailabilityRecheck(
        requestGeneration: Int,
        lifecycleGeneration: Int,
    ) {
        admission.arCoreSupported = false
        arCoreAvailabilityCheckPending = true
        presentation.startAction("ARCore 지원 확인 중", false)
        presentation.status("ARCore 지원 확인 중", "기기 지원 상태를 다시 확인하고 있습니다.")
        platform.availabilityAsync { availability ->
            if (isArCoreAvailabilityCallbackCurrent(requestGeneration, lifecycleGeneration)) {
                arCoreAvailabilityCheckPending = false
                when {
                    arOwner.observe().session != null || admission.fallbackRequested -> Unit
                    !admission.cameraPermission() -> {
                        presentation.startAction("ARCore Depth 시작", true)
                        presentation.status("카메라 권한 필요", "ARCore Depth를 시작하려면 카메라 권한을 허용해야 합니다.")
                    }
                    !admission.reporterRequired("login_required_depth_start") -> {
                        presentation.startAction("ARCore Depth 시작", true)
                    }
                    else -> continueDepthSessionStart(availability)
                }
            }
        }
    }

    fun isArCoreAvailabilityCallbackCurrent(
        requestGeneration: Int,
        lifecycleGeneration: Int,
    ): Boolean {
        return arCoreAvailabilityCheckPending &&
            requestGeneration == arCoreAvailabilityRequestGeneration &&
            lifecycleGeneration == admission.feedbackGeneration() &&
            admission.runtimeActive() &&
            !admission.finishing() &&
            !admission.destroyed()
    }

    fun invalidateArCoreAvailabilityRecheck() {
        val wasPending = arCoreAvailabilityCheckPending
        arCoreAvailabilityRequestGeneration += 1
        arCoreAvailabilityCheckPending = false
        if (wasPending && presentation.actionInitialized()) {
            presentation.startAction("ARCore Depth 시작", true)
        }
    }

    fun handleRuntimeArInstallRequested() {
        val snapshot = metric.postLoginSnapshot()
        val cameraDependentChecksDeferred = metric.deferredCameraChecks()
        installRequested = true
        pendingSessionStartAfterInstall = false
        metric.invalidate("runtime_arcore_install_required")
        metric.prepareStoredRefresh(
            snapshot = snapshot,
            deferred = cameraDependentChecksDeferred,
        )
        presentation.startAction("기기 거리 기능 다시 확인", true)
        presentation.status(
            "ARCore 설치 필요",
            "Google Play Services for AR 설치 후 기기 거리 기능을 다시 확인해 주세요.",
        )
    }

    fun continueDepthSessionStart(availability: ArCoreApk.Availability) {
        if (!admission.firstRunComplete()) return
        if (!admission.runtimeActive()) return
        val runtimeWalkEpoch = walk().currentRuntimeEpochOrNull() ?: return
        if (
            admission.otherCameraOwnerBlocksStart() ||
            arOwner.observe().session != null ||
            arOwner.observe().purpose != nonePurpose
        ) return
        val gate = resolveArCoreStartGate(availability)
        if (
            gate == ArCoreStartGate.READY &&
            platform.consumeForcedCameraFallback()
        ) {
            admission.arCoreSupported = false
            requestCameraFallbackStart(CameraFallbackStartReason.DEBUG_FORCED_SUPPORTED, availability)
            return
        }
        when (gate) {
            ArCoreStartGate.READY -> Unit
            ArCoreStartGate.CAMERA_FALLBACK -> {
                admission.arCoreSupported = false
                requestCameraFallbackStart(CameraFallbackStartReason.ARCORE_AVAILABILITY_UNSUPPORTED, availability)
                return
            }
            ArCoreStartGate.RETRY_LATER -> {
                admission.arCoreSupported = false
                metric.invalidate("runtime_arcore_availability_unknown")
                presentation.startAction("ARCore 다시 확인", true)
                presentation.status(
                    "ARCore 지원 확인 실패",
                    "네트워크 또는 Google Play Services for AR 상태를 확인한 뒤 다시 시도하세요.",
                )
                return
            }
        }
        if (
            admission.fallbackRequested ||
            admission.fallbackRunning ||
            admission.fallbackCleanupPending ||
            admission.fallbackHasCamera
        ) {
            val expectedEpoch = walk().currentRuntimeEpochOrNull() ?: return
            val expectedLifecycleGeneration = admission.feedbackGeneration()
            renderer.stopFallback(
                updateUi = false,
                onReleased = {
                    if (
                        admission.feedbackGeneration() == expectedLifecycleGeneration &&
                        walk().isRuntimeEpochCurrent(expectedEpoch) &&
                        admission.runtimeActive()
                    ) {
                        continueDepthSessionStart(availability)
                    }
                },
            )
            return
        }
        admission.arCoreSupported = true

        transactions.start(availability, runtimeWalkEpoch, { !installRequested },
            ::handleRuntimeArInstallRequested, ::requestCameraFallbackStart,
            { transactions.stopDepthSession(closeSession = true) })
    }

    fun stopDepthSession(closeSession: Boolean = false): Boolean = transactions.stopDepthSession(closeSession)

    fun requestCameraFallbackStart(
        reason: CameraFallbackStartReason,
        availability: ArCoreApk.Availability,
    ) {
        val walkBeforeChange = walk().snapshot()
        val decisionBeforeChange = readiness.decision()
        // Test guidance can have a valid confirmation without a stored confirmation object.
        // Capture the same admission decision before changing the available feature set.
        val capabilityWasConfirmed = admission.startupConfirmed()
        metric.restoreCapability(false)
        val distanceLimitedDecision = readiness.resolveDistanceLimited()
        val automaticallyContinueMetricOnlyDowngrade =
            reason == CameraFallbackStartReason.RUNTIME_METRIC_LOST &&
                walkBeforeChange.state == WalkSessionState.ACTIVE &&
                decisionBeforeChange != null &&
                capabilityWasConfirmed &&
                readiness.allowsMetricDistance(decisionBeforeChange) &&
                distanceLimitedDecision.mayConfirmAndStart &&
                distanceLimitedDecision.pendingRequirements.isEmpty() &&
                distanceLimitedDecision.unavailableRequirements.toSet() ==
                decisionBeforeChange.unavailableRequirements.toSet() +
                WalkSafeStartupRequirement.METRIC_DISTANCE
        if (automaticallyContinueMetricOnlyDowngrade) {
            readiness.confirmDecision(distanceLimitedDecision)
        } else if (walkBeforeChange.state == WalkSessionState.ACTIVE) {
            readiness.enterForegroundRecheckAndCancelOutputs(
                reason = "runtime_mode_changed_to_distance_limited",
            )
        }
        pendingCameraFallbackStart = PendingRuntimeCameraFallbackStart(reason, availability)
        readiness.refreshUi()
        val walkAfterChange = walk().snapshot()
        if (
            walkBeforeChange.state == WalkSessionState.ACTIVE &&
            walkAfterChange.state == WalkSessionState.SAFE_STOP
        ) {
            // S03-7: an active walk never adopts the distance-limited mode silently. The refresh
            // safety-stopped it; keep that stop notice and start no fallback camera.
            pendingCameraFallbackStart = null
            return
        }
        val automaticTransitionReady =
            automaticallyContinueMetricOnlyDowngrade &&
                walkAfterChange.epoch == walkBeforeChange.epoch &&
                walkAfterChange.state == WalkSessionState.ACTIVE &&
                walkAfterChange.mode == WalkSessionMode.DISTANCE_LIMITED &&
                admission.startupConfirmed()
        if (automaticTransitionReady || admission.startupConfirmed()) {
            pendingCameraFallbackStart = null
            if (admission.cameraAnalysisEnabled()) {
                startCameraFallbackSession(reason, availability)
            } else {
                readiness.startWithoutCamera()
            }
            return
        }
        presentation.startAction("거리 제한 모드 시작", true)
        presentation.status("거리 제한 확인 필요", WALKSAFE_LIMITED_DISTANCE_NOTICE_KO)
    }

    fun startCameraFallbackSession(
        reason: CameraFallbackStartReason,
        availability: ArCoreApk.Availability,
    ) {
        if (!admission.firstRunComplete()) return
        if (!admission.runtimeActive()) return
        if (!admission.cameraPermission()) return
        if (admission.fallbackReleaseBlocked) {
            continueAfterCameraFallbackFailure(
                "카메라 자원을 다시 열 수 없습니다.",
            )
            return
        }
        if (
            admission.otherCameraOwnerBlocksStart() ||
            arOwner.observe().session != null ||
            arOwner.observe().purpose != nonePurpose
        ) return
        fallback().request(reason, availability)
        admission.loadDetectorAfterCameraGate()
        synchronized(frameStateLock) {
            frames.clearReportFrameCapture()
        }
        if (!admission.detectorAvailable()) {
            fallback().rejectRequest()
            continueAfterCameraFallbackFailure(
                "대체 장애물 탐지기를 사용할 수 없습니다.",
            )
            return
        }
        readiness.startNavigationIfNeeded()
        fallback().beginRequestedSession()
        presentation.localRouteState("localRoute=disabled:camera_non_metric_advisory")
        presentation.reportStatus("reportCandidate=blocked:camera_non_metric_tier")
        presentation.startAction("카메라 보조 경고 실행 중", false)
        presentation.status(
            status = "카메라 보조 경고 · 거리 제한 모드",
            detail = "미터 거리 안내와 거리 수치가 필요한 자동 위험 판정만 제한됩니다. " +
                "카메라 방향 장애물 안내와 현재 사용 가능한 위치·경로·걸음 수·음성·진동 기능은 계속됩니다.",
        )
        renderer.bindFallbackCurrent()
    }

    fun handleCameraFallbackSourceEvent(event: CameraFallbackEvent) {
        when (event) {
            is CameraFallbackEvent.Bound -> {
                sourceChanged()
                renderer.syncScreenPolicy()
                presentation.previewVisible(true)
                frames.resetPacing()
                presentation.recordEvent("camera_non_metric_session_started", mapOf(
                    "loaded_model" to (admission.loadedModel() ?: "none"),
                    "model_fallback_used" to admission.detectorFallbackUsed(),
                    "metric" to false, "reports_allowed" to false,
                    "reason" to event.reason.fieldValue, "state" to event.availability.name,
                ))
                presentation.status("카메라 보조 경고 · 거리 제한 모드",
                    "미터 거리 안내와 거리 수치가 필요한 자동 위험 판정만 제한됩니다. " +
                        "카메라 방향 장애물 안내와 현재 사용 가능한 위치·경로·걸음 수·음성·진동 기능은 계속됩니다.")
            }
            is CameraFallbackEvent.Stopped -> {
                renderer.syncScreenPolicy()
                if (presentation.previewInitialized()) presentation.previewVisible(false)
                frames.resetPacing()
                frames.resetNonMetricPolicy()
                if (event.wasRequested && presentation.fieldLogInitialized())
                    presentation.recordEvent("camera_non_metric_session_stopped")
                if (event.updateUi && presentation.actionInitialized())
                    presentation.startAction("ARCore Depth 시작", true)
            }
            is CameraFallbackEvent.BindFailed -> {
                if (walk().isRuntimeEpochCurrent(event.lease.epoch) && admission.runtimeActive())
                    continueAfterCameraFallbackFailure("대체 카메라를 시작하지 못했습니다.")
            }
            is CameraFallbackEvent.ReleaseFailed -> {
                if (event.stoppedEpoch != null && walk().isRuntimeEpochCurrent(event.stoppedEpoch)) {
                    continueAfterCameraFallbackFailure("대체 카메라 자원을 정상적으로 닫지 못했습니다.")
                } else if (event.stoppedEpoch == null && walk().currentRuntimeEpochOrNull() == null &&
                    !admission.finishing() && !admission.destroyed()) {
                    presentation.status("카메라 정리 실패", "카메라 보조 기능을 닫지 못했습니다. 앱을 다시 시작한 뒤 점검하세요.")
                }
            }
        }
    }

    fun continueAfterCameraFallbackFailure(detail: String) {
        pendingCameraFallbackStart = null
        metric.restoreCapability(false)
        readiness.setObstacleUnavailable()
        presentation.localRouteState("localRoute=tmap:tactile_not_visible")
        presentation.reportStatus("reportCandidate=blocked:camera_non_metric_unavailable")
        readiness.refreshUi()
        // S03-6: camera guidance is a runtime requirement. When the refresh safety-stopped the
        // walk, keep its stop notice instead of announcing a camera-less continuation.
        if (walk().snapshot().state != WalkSessionState.ACTIVE) return
        readiness.activateMountingRuntime()
        readiness.applyCurrentEnvironmentAssessment()
        readiness.startWithoutCamera()
        presentation.startAction("카메라·거리 기능 제한", false)
        presentation.status(
            "카메라·거리 기능 제한",
            "$detail 카메라 장애물 인식과 미터 거리 안내만 제한하고, " +
                "위치·경로·걸음 수와 현재 사용 가능한 음성·진동 기능은 계속합니다.",
        )
    }
}
