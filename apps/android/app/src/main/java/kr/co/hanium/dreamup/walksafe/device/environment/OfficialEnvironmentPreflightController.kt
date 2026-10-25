package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason
import kr.co.hanium.dreamup.walksafe.device.CameraPreflightStabilityDecision
import kr.co.hanium.dreamup.walksafe.device.CameraPreflightStabilityGate
import kr.co.hanium.dreamup.walksafe.device.OfficialEnvironmentGpsSample
import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import java.util.concurrent.atomic.AtomicBoolean

internal enum class OfficialEnvironmentPreflightPhase { IDLE, MEASURING, TIMED_OUT, FAILED }

/** Attempt ownership and bounded cleanup. SDK resources stay in their existing source owners. */
internal class OfficialEnvironmentPreflightController(
    private val evidence: OfficialEnvironmentEvidenceStore,
    private val facts: OfficialEnvironmentPreflightFactsReader,
    cameraSource: () -> OfficialEnvironmentCameraSource,
    gpsSource: () -> OfficialEnvironmentGpsSource,
    private val effects: OfficialEnvironmentPreflightEffects,
    private val queue: OfficialEnvironmentPreflightQueue,
    private val stability: CameraPreflightStabilityGate,
    private val nowMs: () -> Long,
    private val config: Config,
) {
    private val camera by lazy(LazyThreadSafetyMode.NONE, cameraSource)
    private val gps by lazy(LazyThreadSafetyMode.NONE, gpsSource)
    data class Config(
        val initialCameraIntervalMs: Long,
        val passedCameraIntervalMs: Long,
        val maximumCameraDurationMs: Long,
        val maximumGpsDurationMs: Long,
        val analyzerReleaseBarrierTimeoutMs: Long,
    )

    @Volatile var phase = OfficialEnvironmentPreflightPhase.IDLE
        private set
    private var gpsGeneration = 0L
    private var gpsRequest: OfficialEnvironmentGpsRequest? = null
    @Volatile var cameraGeneration = 0L
        private set
    @Volatile var cameraActive = false
        private set
    @Volatile private var cameraHasPassed = false
    private var cameraLastAnalysisMs = 0L
    private var timeout: Runnable? = null
    var cameraCleanupPending = false
        private set
    private var afterCameraRelease: (() -> Unit)? = null
    private var readyGraceGeneration = -1L

    val separateGpsActive: Boolean get() = gpsRequest != null

    fun markFailed() { phase = OfficialEnvironmentPreflightPhase.FAILED }
    fun prepareAttempt() { phase = OfficialEnvironmentPreflightPhase.IDLE; readyGraceGeneration = -1L }
    fun discardAfterRelease() { afterCameraRelease = null }
    fun resetReadyGrace() { readyGraceGeneration = -1L }
    fun invalidateGeneration() { gpsGeneration += 1L }

    fun claimReadyGrace(): Boolean {
        if (readyGraceGeneration == cameraGeneration) return false
        readyGraceGeneration = cameraGeneration
        return true
    }

    fun resetCameraObservations() {
        stability.reset()
        cameraHasPassed = false
        evidence.clearCameraDiagnostics()
    }

    fun cameraAnalysisIntervalMs(): Long = if (cameraHasPassed) config.passedCameraIntervalMs else config.initialCameraIntervalMs

    fun observeCameraStability(assessment: CameraFrameQualityAssessment): CameraPreflightStabilityDecision {
        val decision = stability.observe(assessment.status, assessment.reason.isTransientPreflightQualityFailure())
        if (decision == CameraPreflightStabilityDecision.ACCEPT && assessment.status == EnvironmentEvidenceStatus.PASS) cameraHasPassed = true
        return decision
    }

    fun isCameraCurrent(epoch: WalkRuntimeEpoch, generation: Long): Boolean {
        if (!cameraActive || generation != cameraGeneration || !facts.foreground()) return false
        val lifecycle = facts.lifecycle()
        return lifecycle.epoch == epoch &&
            lifecycle.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)
    }

    fun startCamera(epoch: WalkRuntimeEpoch): Boolean {
        if (cameraActive || cameraCleanupPending || !facts.foreground()) return false
        val lifecycle = facts.lifecycle()
        if (lifecycle.epoch != epoch ||
            lifecycle.state !in setOf(WalkSessionState.READY, WalkSessionState.PAUSED) || !facts.cameraProfilePresent()
        ) return false
        val failure = camera.startFailureReason()
        if (failure != null) {
            phase = OfficialEnvironmentPreflightPhase.FAILED
            effects.observeUnavailableCamera(epoch, nowMs(), null, failure)
            effects.showStatus("카메라·장착 센서 점검 불가", "카메라 권한과 중력 센서를 확인한 뒤 실제 상태 점검을 다시 실행하세요.")
            effects.cameraUnavailableDiagnostic(failure)
            return false
        }
        val generation = ++cameraGeneration
        cameraActive = true
        cameraLastAnalysisMs = 0L
        stability.reset()
        cameraHasPassed = false
        phase = OfficialEnvironmentPreflightPhase.MEASURING
        camera.resumeLifecycle()
        scheduleCameraTimeout(epoch, generation, config.maximumCameraDurationMs)
        camera.start(epoch, generation,
            isCurrent = { isCameraCurrent(epoch, generation) },
            acceptAnalysisAt = { at ->
                if (at - cameraLastAnalysisMs < cameraAnalysisIntervalMs()) false
                else { cameraLastAnalysisMs = at; true }
            },
            onBindFailed = {
                if (isCameraCurrent(epoch, generation)) {
                    stopCamera()
                    phase = OfficialEnvironmentPreflightPhase.FAILED
                    effects.observeUnavailableCamera(epoch, nowMs(), false, null)
                    evidence.markCameraFailure("CAMERA_BIND_FAILED")
                    effects.showStatus("보행 전 카메라 점검 실패", "카메라를 다른 앱이 사용 중인지 확인한 뒤 다시 점검하세요.")
                    effects.refreshEnvironmentAndReadiness()
                }
            },
        )
        return true
    }

    fun scheduleCameraTimeout(epoch: WalkRuntimeEpoch, generation: Long, delayMs: Long) {
        timeout?.let(queue::removeUi)
        lateinit var action: Runnable
        action = Runnable {
            if (timeout !== action || !isCameraCurrent(epoch, generation)) return@Runnable
            stopCamera()
            synchronized(evidence.observationLock) {
                if (!facts.nativeCheckRequested()) evidence.clearGpsEvidenceOnly()
                evidence.clearCameraEvidence()
            }
            phase = OfficialEnvironmentPreflightPhase.TIMED_OUT
            effects.refreshEnvironmentAndReadiness()
            effects.showStatus("보행 전 상태 점검 시간 초과", "마지막 실제 측정 원인은 화면에 유지했습니다. 환경과 장착을 확인한 뒤 다시 점검하세요.")
        }
        timeout = action
        queue.postUi(delayMs, action)
    }

    fun stopCamera(releaseMountingSensor: Boolean = true, onReleased: (() -> Unit)? = null) {
        if (onReleased != null) afterCameraRelease = onReleased
        var stopped = false
        synchronized(evidence.observationLock) {
            if (cameraActive) { cameraActive = false; cameraGeneration += 1L; stopped = true }
        }
        if (releaseMountingSensor) effects.stopMountingSensorIfInitialized()
        if (!stopped) {
            stopGps()
            if (!cameraCleanupPending) { val after = afterCameraRelease; afterCameraRelease = null; after?.invoke() }
            return
        }
        if (queue.uiReady()) timeout?.let(queue::removeUi)
        timeout = null
        val resources = camera.detachOwnedResources()
        resources?.clearAnalyzer()
        stopGps()
        val releaseClaim = AtomicBoolean(false)
        val barrierTimedOut = AtomicBoolean(false)
        lateinit var release: Runnable
        lateinit var deadline: Runnable
        release = Runnable {
            if (!releaseClaim.compareAndSet(false, true)) return@Runnable
            queue.removeRelease(deadline)
            resources?.unbindOwned()
            camera.moveLifecycleToCreatedIfInitialized()
            camera.awaitClosed(resources) { closed ->
                cameraCleanupPending = false
                val after = afterCameraRelease
                afterCameraRelease = null
                if (closed && !barrierTimedOut.get()) after?.invoke()
                else {
                    camera.markSharedReleaseBlocked()
                    effects.cancelRuntimeCameraHandoff()
                    phase = OfficialEnvironmentPreflightPhase.FAILED
                    evidence.markCameraFailure("CAMERA_RELEASE_TIMED_OUT")
                    if (facts.walkRuntimeActive()) effects.enterSafetyStop("camera_release_timed_out")
                    else if (facts.uiAlive()) {
                        effects.refreshEnvironmentAndReadiness()
                        effects.showStatus("카메라 정리 실패", "카메라 자원이 닫히지 않아 다음 단계를 시작하지 않았습니다. 앱을 다시 시작한 뒤 점검하세요.")
                    }
                }
            }
        }
        deadline = Runnable { barrierTimedOut.set(true); release.run() }
        if (resources == null || !resources.hasAnalysis && !resources.hasCamera) { release.run(); return }
        cameraCleanupPending = true
        queue.postRelease(config.analyzerReleaseBarrierTimeoutMs, deadline)
        camera.awaitAnalyzerBarrier(release, onRejected = { barrierTimedOut.set(true); queue.runOnUiThread(release) })
    }

    fun requestGps(epoch: WalkRuntimeEpoch, clearExistingEvidence: Boolean = true) {
        stopGps()
        if (clearExistingEvidence) evidence.clearGpsEvidence()
        val generation = ++gpsGeneration
        val cameraRequired = facts.cameraEnabled()
        if (!facts.foreground() || !facts.locationPermission() || !facts.locationServiceEnabled() || cameraRequired && !cameraActive) return
        val lifecycle = facts.lifecycle()
        if (lifecycle.epoch != epoch || lifecycle.state !in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)
        ) return
        val requestedProfile = facts.profile()
        val requestedOwner = facts.gpsOwner()
        lateinit var request: OfficialEnvironmentGpsRequest
        fun requestCurrent(): Boolean {
            val gateway = facts.gpsOwner()
            return isGpsCurrent(epoch, generation, request) && facts.profile() == requestedProfile &&
                gateway == requestedOwner && gateway.usable && facts.locationPermission() && facts.locationServiceEnabled()
        }
        request = gps.createRequest(requestedProfile, isCurrent = ::requestCurrent,
            onSample = { sample -> if (requestCurrent()) observeGpsSample(epoch, sample, requestedProfile) },
            onTemporarilyUnavailable = { if (requestCurrent()) effects.gpsTemporarilyUnavailableDiagnostic(generation) },
            onFailure = { failGps(epoch, generation, request) },
        )
        gpsRequest = request
        if (!cameraRequired) { phase = OfficialEnvironmentPreflightPhase.MEASURING; scheduleGpsTimeout(epoch, generation, request) }
        request.start()
    }

    fun beginNativeGpsOnlyWait(epoch: WalkRuntimeEpoch) {
        phase = OfficialEnvironmentPreflightPhase.MEASURING
        scheduleGpsTimeout(epoch, gpsGeneration, null)
    }

    fun failGps(epoch: WalkRuntimeEpoch, generation: Long, request: OfficialEnvironmentGpsRequest) {
        if (!isGpsCurrent(epoch, generation, request)) { request.cancel(); return }
        evidence.markGpsPreflightFailure("LOCATION_REQUEST_FAILED", epoch, nowMs(), facts.profile())
        stopCamera()
        phase = OfficialEnvironmentPreflightPhase.FAILED
        effects.refreshEnvironmentAndReadiness()
        effects.showStatus("위치 점검 실패", "Android 위치 서비스를 확인한 뒤 실제 상태 점검을 다시 실행하세요.")
    }

    fun scheduleGpsTimeout(epoch: WalkRuntimeEpoch, generation: Long, request: OfficialEnvironmentGpsRequest?) {
        timeout?.let(queue::removeUi)
        lateinit var action: Runnable
        action = Runnable {
            if (timeout !== action) return@Runnable
            timeout = null
            val current = if (request != null) isGpsCurrent(epoch, generation, request)
                else generation == gpsGeneration && facts.nativeGpsCheckCurrent(epoch)
            if (!current) return@Runnable
            if (request != null) stopGps()
            phase = OfficialEnvironmentPreflightPhase.TIMED_OUT
            effects.refreshEnvironmentAndReadiness()
            effects.showStatus("보행 전 위치 점검 시간 초과", "현재 위치 품질을 확인하지 못했습니다. 위치 서비스와 주변 환경을 확인한 뒤 다시 점검하세요.")
        }
        timeout = action
        queue.postUi(config.maximumGpsDurationMs, action)
    }

    fun observeGpsSample(epoch: WalkRuntimeEpoch, sample: OfficialEnvironmentGpsSample, profile: ApprovedOfficialEnvironmentProfile?) {
        val now = nowMs()
        val candidate = evidence.assessGpsPreflightSample(epoch, now, sample, profile) ?: return
        val selected = evidence.acceptGpsPreflightSample(candidate, sample.horizontalAccuracyMeters, now)
        effects.gpsSampleDiagnostic(sample, candidate, evidence.gpsEvidence !== candidate, now)
        if (selected.status == EnvironmentEvidenceStatus.PASS && !facts.cameraEnabled()) {
            timeout?.let(queue::removeUi)
            timeout = null
            stopGps()
            phase = OfficialEnvironmentPreflightPhase.IDLE
        }
        effects.refreshEnvironmentAndReadiness()
    }

    fun stopGps() { gpsGeneration += 1L; val owned = gpsRequest; gpsRequest = null; owned?.cancel() }

    private fun isGpsCurrent(epoch: WalkRuntimeEpoch, generation: Long, request: OfficialEnvironmentGpsRequest): Boolean {
        val lifecycle = facts.lifecycle()
        return gpsRequest === request && generation == gpsGeneration && (cameraActive || !facts.cameraEnabled()) && lifecycle.epoch == epoch &&
            lifecycle.foreground && lifecycle.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED) && facts.foreground()
    }
}

internal data class OfficialEnvironmentPreflightLifecycleFacts(val epoch: WalkRuntimeEpoch, val state: WalkSessionState, val foreground: Boolean)
/** Uses gateway generation plus the existing session object's reference identity. */
internal class OfficialEnvironmentGpsOwner(val generation: Long, val sessionIdentity: Any?, val usable: Boolean = true) {
    override fun equals(other: Any?): Boolean = other is OfficialEnvironmentGpsOwner &&
        generation == other.generation && sessionIdentity === other.sessionIdentity
    override fun hashCode(): Int = 31 * generation.hashCode() + System.identityHashCode(sessionIdentity)
}
internal interface OfficialEnvironmentPreflightFactsReader {
    fun lifecycle(): OfficialEnvironmentPreflightLifecycleFacts
    fun foreground(): Boolean
    fun cameraProfilePresent(): Boolean
    fun cameraEnabled(): Boolean
    fun locationPermission(): Boolean
    fun locationServiceEnabled(): Boolean
    fun profile(): ApprovedOfficialEnvironmentProfile?
    fun gpsOwner(): OfficialEnvironmentGpsOwner
    fun nativeCheckRequested(): Boolean
    fun nativeGpsCheckCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun walkRuntimeActive(): Boolean
    fun uiAlive(): Boolean
}

internal interface OfficialEnvironmentPreflightQueue {
    fun uiReady(): Boolean
    fun removeUi(action: Runnable)
    fun postUi(delayMs: Long, action: Runnable)
    fun removeRelease(action: Runnable)
    fun postRelease(delayMs: Long, action: Runnable)
    fun runOnUiThread(action: Runnable)
}

internal interface OfficialEnvironmentCameraSource {
    fun startFailureReason(): String?
    fun resumeLifecycle()
    fun start(epoch: WalkRuntimeEpoch, generation: Long, isCurrent: () -> Boolean, acceptAnalysisAt: (Long) -> Boolean, onBindFailed: () -> Unit)
    fun stopMountingSensorIfInitialized()
    fun detachOwnedResources(): OfficialEnvironmentCameraResources?
    fun moveLifecycleToCreatedIfInitialized()
    fun awaitClosed(resources: OfficialEnvironmentCameraResources?, completion: (Boolean) -> Unit)
    fun awaitAnalyzerBarrier(release: Runnable, onRejected: () -> Unit)
    fun markSharedReleaseBlocked()
}

internal interface OfficialEnvironmentCameraResources {
    val hasAnalysis: Boolean
    val hasCamera: Boolean
    fun clearAnalyzer()
    fun unbindOwned()
}

internal interface OfficialEnvironmentGpsRequest { fun start(); fun cancel() }
internal interface OfficialEnvironmentGpsSource {
    fun createRequest(profile: ApprovedOfficialEnvironmentProfile?, isCurrent: () -> Boolean,
        onSample: (OfficialEnvironmentGpsSample) -> Unit, onTemporarilyUnavailable: () -> Unit,
        onFailure: () -> Unit): OfficialEnvironmentGpsRequest
}

internal interface OfficialEnvironmentPreflightEffects {
    fun stopMountingSensorIfInitialized()
    fun observeUnavailableCamera(epoch: WalkRuntimeEpoch, observedAtMs: Long, frameAvailable: Boolean?, reason: String?)
    fun cameraUnavailableDiagnostic(reason: String)
    fun refreshEnvironmentAndReadiness()
    fun showStatus(title: String, detail: String)
    fun cancelRuntimeCameraHandoff()
    fun enterSafetyStop(reason: String)
    fun gpsTemporarilyUnavailableDiagnostic(generation: Long)
    fun gpsSampleDiagnostic(sample: OfficialEnvironmentGpsSample, candidate: kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence, retainedRecentPass: Boolean, nowMs: Long)
}

private fun CameraFrameQualityReason.isTransientPreflightQualityFailure(): Boolean = when (this) {
    CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE,
    CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE,
    CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE,
    CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY -> true
    else -> false
}
