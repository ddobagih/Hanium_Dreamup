package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController
import kr.co.hanium.dreamup.walksafe.session.*

/** Advances the retained machine only after the existing capability/readiness/confirmation owners admit it. */
internal class WalkSessionAdmissionController(
    private val walk: WalkSessionRuntimeAccess,
    private val authority: GatewayWalkAuthorityController,
    private val gateway: GatewayWalkSessionController,
    private val resume: WalkSessionResumeController,
    private val readiness: WalkResumeReadiness,
    private val state: WalkAdmissionState,
    private val effects: WalkAdmissionEffects,
    private val userInstallableRequirements: Set<WalkSafeStartupRequirement>,
    private val elapsedRealtimeMs: () -> Long,
) {
    fun maybeAdvanceWalkSessionAfterCapabilityCheck() {
        if (gateway.startConfirmationToken != null) return
        if (state.permissionRecoveryBlocksStart() && !state.developmentBypassEnabled()) {
            effects.completePermissionRecoveryRecheck()
            if (state.permissionRecoveryBlocksStart()) return
        }
        if (
            !walk.available() ||
            !state.isForeground() ||
            state.startupConfirmationPending() ||
            state.startupRetryRequiresUserAction() ||
            resume.promptPending ||
            resume.retryRequiresUserAction ||
            gateway.resumeRevalidationPending
        ) return

        val decision = readiness.decision() ?: return
        var session = walk.snapshot()
        if (
            session.state == WalkSessionState.ACTIVE ||
            session.state == WalkSessionState.SAFE_STOP ||
            session.state == WalkSessionState.ENDED
        ) return
        if (
            session.state == WalkSessionState.PAUSED &&
            authority.activeLeaseOrNull(session.epoch) == null
        ) {
            gateway.handleGatewayWalkAuthorityLost("walk_lease_missing_on_resume")
            return
        }

        val blockReason = readiness.blockReason(decision)
        if (blockReason != null) {
            // Before any walk starts, a missing user-installable item (Korean voice data) only
            // blocks the start. Failing closed into SAFE_STOP here would record a walk
            // interruption that never happened and require an extra step after installation.
            // Other unavailable items only limit features (LIMITED tier); an unsupported Android
            // version still fails closed.
            val installableGapBeforeWalk = session.state == WalkSessionState.READY &&
                decision.unavailableRequirements.any(userInstallableRequirements::contains) &&
                WalkSafeStartupRequirement.ANDROID_VERSION !in decision.unavailableRequirements
            if (
                decision.tier == WalkSafeStartupCapabilityTier.BLOCKED &&
                decision.pendingRequirements.isEmpty() &&
                decision.unavailableRequirements.isNotEmpty() &&
                !installableGapBeforeWalk
            ) {
                val action = if (session.state == WalkSessionState.PAUSED) {
                    WalkSessionAction.RESUME_WALK
                } else {
                    WalkSessionAction.START_WALK
                }
                val readiness = readiness.capture(decision, action)
                val event = if (session.state == WalkSessionState.PAUSED) {
                    WalkSessionEvent.RecoveryRecheckCompleted(readiness)
                } else {
                    WalkSessionEvent.InitialCheckCompleted(readiness)
                }
                walk.transition(event)
                effects.persistInterruptionMarker()
            } else {
                effects.maybeRequestMissingPermissions(decision)
            }
            effects.updateStatus("보행 시작 대기", blockReason)
            return
        }

        when {
            session.state == WalkSessionState.READY -> {
                val previousToken = session.confirmationToken
                val nowMs = elapsedRealtimeMs()
                val retained = session.readiness
                val currentReadiness = readiness.capture(
                    decision = decision, action = WalkSessionAction.START_WALK,
                )
                if (retained == null || retained.plan != currentReadiness.plan || !retained.isReadyAt(nowMs) ||
                    nowMs - retained.assessedAtElapsedRealtimeMs > WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS
                ) {
                    walk.transition(
                        WalkSessionEvent.InitialCheckCompleted(
                            currentReadiness,
                        ),
                    )
                    session = walk.snapshot()
                }
                val createdConfirmationToken =
                    session.confirmationToken != null && session.confirmationToken != previousToken
                if (createdConfirmationToken) {
                    effects.refreshReadinessUi()
                    return
                }
                val token = session.confirmationToken
                if (token == null) {
                    effects.updateStatus(
                        "보행 시작 대기",
                        session.readiness?.blockingReasons?.joinToString()
                            ?: "필수 기능 점검을 완료하지 못했습니다.",
                    )
                    return
                }
                if (!readiness.isConfirmed()) {
                    effects.updateStatus(
                        "보행 시작 확인 필요",
                        "필수 기능 점검을 통과했습니다. 안전 제한 안내를 확인한 뒤 시작하세요.",
                    )
                    return
                }
                gateway.requestGatewayWalkStart(token)
            }
            session.state == WalkSessionState.PAUSED &&
                session.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED -> {
                walk.transition(
                    WalkSessionEvent.RecoveryRecheckCompleted(
                        readiness.capture(
                            decision = decision,
                            action = WalkSessionAction.RESUME_WALK,
                        ),
                    ),
                )
                session = walk.snapshot()
                if (
                    session.recoveryStage ==
                    WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION
                ) {
                    effects.refreshReadinessUi()
                    return
                }
            }
            session.state == WalkSessionState.PAUSED &&
                session.recoveryStage == WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION -> {
                if (state.consumeDevelopmentResumeRequest(session.epoch)
                ) {
                    resume.confirmWalkSessionResumeFromButton()
                } else if (readiness.isConfirmed()) {
                    resume.requestWalkSessionResumeConfirmation()
                } else {
                    effects.updateStatus(
                        "보행 재개 확인 필요",
                        "재검사를 통과했습니다. 안전 제한 안내를 확인한 뒤 재개 여부를 말해 주세요.",
                    )
                }
            }
        }
    }
}
