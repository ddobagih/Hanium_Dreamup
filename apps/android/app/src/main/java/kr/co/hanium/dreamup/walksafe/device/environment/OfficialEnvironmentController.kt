package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentAssessment
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentSupport
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import java.util.Locale

/** Coordinates explicit checks and invalidation while each measured/source owner stays singular. */
internal class OfficialEnvironmentController(
    val evidence: OfficialEnvironmentEvidenceStore,
    val preflight: OfficialEnvironmentPreflightController,
    val runtime: OfficialEnvironmentRuntimeController,
    private val facts: OfficialEnvironmentCheckFactsReader,
    private val effects: OfficialEnvironmentCheckEffects,
    private val nowMs: () -> Long,
    private val bypassEnabled: () -> Boolean,
    private val developmentQuickStartEnabled: Boolean,
    private val supportNoticeKo: String,
) {
    fun confirmConditions() {
        if (bypassEnabled()) return
        if (!facts.foreground() || !facts.lifecycleInitialized()) return
        if (!effects.requireFirstRunOnboardingComplete("official_environment_confirmation")) return
        if (!developmentQuickStartEnabled && !facts.nativeEducationCurrent()) return
        if (!facts.environmentProfilesPresent()) {
            effects.showStatus("실제 상태 점검 기준 없음", "출시 후보 또는 승인된 환경·장착 측정 프로필이 없어 보행을 시작하지 않습니다.")
            return
        }
        val lifecycle = facts.lifecycle()
        if (lifecycle.state !in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)) {
            effects.showStatus("공식 사용환경 확인 불가", "새 보행 준비 또는 일시중지 재검사 상태에서 다시 확인하세요.")
            return
        }
        if (preflight.cameraActive || preflight.cameraCleanupPending) {
            preflight.stopCamera(onReleased = ::confirmConditions)
            return
        }
        effects.resetPrewalkCameraEvidenceForNewAttempt()
        prepareGpsForPreflight()
        preflight.prepareAttempt()
        effects.resetPrewalkGuidanceStage()
        if (developmentQuickStartEnabled && lifecycle.state == WalkSessionState.READY && !facts.nativeCheckRequested()) {
            effects.assumeInitialManualMountConfirmation(lifecycle.epoch)
        }
        val requireLocation = facts.locationEnabled()
        val requireCamera = facts.cameraEnabled()
        if (requireLocation && !facts.locationPermission()) {
            evidence.markGpsPreflightFailure("LOCATION_PERMISSION_MISSING")
            preflight.markFailed()
            effects.requestMissingPermissionsIfDecisionPresent()
            effects.showStatus("정확한 위치 권한 필요", "환경 위치 품질을 확인하려면 정확한 위치 권한을 허용한 뒤 공식 사용환경을 다시 확인하세요.")
        } else if (requireLocation && !facts.locationServiceEnabled()) {
            evidence.markGpsPreflightFailure("LOCATION_SERVICE_DISABLED")
            preflight.markFailed()
            effects.showStatus("Android 위치 서비스 필요", "위치 서비스를 켠 뒤 실제 상태 점검을 다시 실행하세요.")
        } else if (requireCamera) {
            if (preflight.startCamera(lifecycle.epoch) && requireLocation) requestGpsForCheck(lifecycle.epoch)
        } else if (requireLocation) requestGpsForCheck(lifecycle.epoch)
        effects.refreshEnvironmentAndReadiness()
        if (!facts.nativeCheckRequested()) effects.speakInteraction(
            "$supportNoticeKo 사용범위 교육은 현재 환경의 안전을 증명하지 않습니다. 활성화된 기능의 실제 센서 품질과 장착 상태를 점검합니다.",
        )
    }

    fun prepareGpsForPreflight(clock: () -> Long = nowMs) {
        evidence.prepareGpsForPreflight(facts.nativeCheckRequested(), { facts.lifecycle().epoch }, clock,
            preflight::stopGps, facts::continuousGpsEvidence)
    }

    fun requestGpsForCheck(
        epoch: WalkRuntimeEpoch,
        clearExistingEvidence: Boolean = true,
        requestGeneral: (WalkRuntimeEpoch) -> Unit = effects::requestContinuousGpsForNativeCheck,
        requestSeparate: (WalkRuntimeEpoch, Boolean) -> Unit = preflight::requestGps,
    ) {
        if (facts.nativeCheckRequested()) {
            if (facts.nativeGpsCheckCurrent(epoch)) requestGeneral(epoch)
            return
        }
        requestSeparate(epoch, clearExistingEvidence)
    }

    fun readiness(epoch: WalkRuntimeEpoch, assessmentOverride: OfficialEnvironmentAssessment? = null): Pair<WalkSessionReadinessStatus, String> {
        runtime.runtimeReadiness(epoch)?.let { return it }
        val assessment = assessmentOverride ?: facts.currentAssessment(epoch, nowMs())
        if (facts.profile() == null) return WalkSessionReadinessStatus.UNAVAILABLE to "official_environment:approved_profile_unavailable"
        if (assessment.canStartWalk) return WalkSessionReadinessStatus.READY to ""
        return when (assessment.support) {
            OfficialEnvironmentSupport.SUPPORTED -> WalkSessionReadinessStatus.READY to ""
            OfficialEnvironmentSupport.LIMITED -> WalkSessionReadinessStatus.PENDING to
                "official_environment:unknown:" + assessment.blockingFactors.joinToString(",") { it.name.lowercase(Locale.US) }
            OfficialEnvironmentSupport.UNSUPPORTED -> WalkSessionReadinessStatus.UNAVAILABLE to
                "official_environment:unsupported:" + assessment.blockingFactors.joinToString(",") { it.name.lowercase(Locale.US) }
        }
    }

    fun invalidate(reason: String) {
        effects.cancelRuntimeCameraHandoff()
        preflight.discardAfterRelease()
        preflight.stopCamera()
        effects.stopMountingSensorIfInitialized()
        preflight.invalidateGeneration()
        runtime.invalidateWatchdog()
        evidence.invalidate()
        preflight.prepareAttempt()
        effects.resetPrewalkGuidanceStage()
        runtime.clearGuard()
        effects.clearContinuousGpsSourceAndRecovery()
        evidence.gpsQualityGate.reset()
        evidence.resetRuntimeRawCameraAssessment()
        runtime.clearDecisionAndOutput()
        effects.recordInvalidationIfInitialized(reason)
        effects.refreshEnvironmentIfInitialized()
    }
}

internal interface OfficialEnvironmentCheckFactsReader {
    fun foreground(): Boolean
    fun lifecycleInitialized(): Boolean
    fun nativeEducationCurrent(): Boolean
    fun environmentProfilesPresent(): Boolean
    fun lifecycle(): OfficialEnvironmentLifecycleFacts
    fun nativeCheckRequested(): Boolean
    fun nativeGpsCheckCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun locationEnabled(): Boolean
    fun cameraEnabled(): Boolean
    fun locationPermission(): Boolean
    fun locationServiceEnabled(): Boolean
    fun continuousGpsEvidence(epoch: WalkRuntimeEpoch, nowMs: Long): kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence?
    fun currentAssessment(epoch: WalkRuntimeEpoch, nowMs: Long): OfficialEnvironmentAssessment
    fun profile(): ApprovedOfficialEnvironmentProfile?
}

internal interface OfficialEnvironmentCheckEffects {
    fun requireFirstRunOnboardingComplete(reason: String): Boolean
    fun resetPrewalkCameraEvidenceForNewAttempt()
    fun resetPrewalkGuidanceStage()
    fun assumeInitialManualMountConfirmation(epoch: WalkRuntimeEpoch)
    fun requestMissingPermissionsIfDecisionPresent()
    fun requestContinuousGpsForNativeCheck(epoch: WalkRuntimeEpoch)
    fun showStatus(title: String, detail: String)
    fun refreshEnvironmentAndReadiness()
    fun speakInteraction(message: String)
    fun cancelRuntimeCameraHandoff()
    fun stopMountingSensorIfInitialized()
    fun clearContinuousGpsSourceAndRecovery()
    fun recordInvalidationIfInitialized(reason: String)
    fun refreshEnvironmentIfInitialized()
}
