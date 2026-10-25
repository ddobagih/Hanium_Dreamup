package kr.co.hanium.dreamup.walksafe.session.readiness

import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.session.PermissionDependencyPolicy
import kr.co.hanium.dreamup.walksafe.session.*

/** Ordered blocking explanation. Suppliers stay lazy so an earlier block never queries later SDK owners. */
internal class WalkReadinessBlockController(
    private val sources: WalkReadinessBlockSources,
    private val koreanTtsBlockDetail: String,
) {
    fun walkSessionReadinessBlockReason(
        decision: WalkSafeStartupCapabilityDecision,
    ): String? {
        if (!sources.onboardingComplete()) {
            return "첫 실행 등록과 안전교육이 완료될 때까지 보행 안내를 시작하지 않습니다."
        }
        if (sources.developmentBypassEnabled()) return null
        if (!decision.mayConfirmAndStart) return decision.noticeKo
        if (
            PermissionDependencyPolicy.evaluate(
                sources.observedPermissions(),
            ).requiresWholeWalkSafetyStop
        ) {
            return "카메라와 정확한 위치 권한이 모두 없어 보행 안내를 시작할 수 없습니다."
        }
        if (
            !sources.voiceFeatureEnabled() ||
                !sources.offlineTtsAllowed(decision)
        ) {
            return koreanTtsBlockDetail
        }
        val onboarding = sources.priorityOnboarding(decision)
        if (!sources.quickStartEnabled() && !onboarding.mayStartWalk) return onboarding.noticeKo
        if (sources.reporterUserId() == null) return "로그인이 완료될 때까지 보행 안내를 시작하지 않습니다."
        if (!sources.gatewayReadyForCurrentActor()) {
            return "게이트웨이 로그인을 확인한 뒤 보행 안내를 시작할 수 있습니다."
        }
        sources.officialEnvironmentBlockReason()?.let { return it }
        sources.phoneMountingBlockReason()?.let { return it }
        val action = if (
            sources.walkState() == WalkSessionState.PAUSED
        ) {
            WalkSessionAction.RESUME_WALK
        } else {
            WalkSessionAction.START_WALK
        }
        val missingPermissions = sources.missingRequiredPermissions(action)
        if (missingPermissions.isNotEmpty()) {
            return "필수 권한이 필요합니다: ${sources.permissionGuidanceLabels(missingPermissions)}"
        }
        // S03-6: both approved modes need camera obstacle guidance and precise location
        // (FP-017-DETAIL-01). Block here so a start or resume never becomes a safety stop.
        // S14-33-F1/FP-039: a model that failed its sample inference starts neither hazard detection nor a walk.
        if (sources.runningDetectorBlocksEntry()) {
            return kr.co.hanium.dreamup.walksafe.device.RunningDetectorReadiness.WALK_BLOCK_NOTICE_KO
        }
        if (!sources.cameraEnabled()) {
            if (sources.detectorLoadAttempted() && !sources.detectorAvailable()) {
                // The model failed to load in this process; the device re-check reloads it.
                return "장애물 탐지 모델을 불러오지 못해 보행 안내를 시작하지 않습니다. " +
                    "'카메라·거리 기능 다시 점검'을 누르거나 앱을 완전히 종료한 뒤 다시 열어 주세요."
            }
            return "카메라 장애물 안내를 사용할 수 없어 보행 안내를 시작하지 않습니다. " +
                "카메라 권한과 카메라·거리 기능 점검 결과를 확인해 주세요."
        }
        if (!sources.locationFeatureEnabled()) {
            return "정확한 위치를 사용할 수 없어 보행 안내를 시작하지 않습니다. " +
                "위치 서비스와 정확한 위치 권한을 확인해 주세요."
        }
        return null
    }
}
