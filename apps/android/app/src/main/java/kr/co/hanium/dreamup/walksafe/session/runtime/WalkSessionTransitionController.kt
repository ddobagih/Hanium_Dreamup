package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*

/** Publishes through the one retained lifecycle, then performs transition effects in source order. */
internal class WalkSessionTransitionController(
    private val lifecycle: () -> WalkSessionLifecycle,
    private val authority: GatewayWalkAuthorityController,
    private val gateway: GatewayWalkSessionController,
    private val gatewaySession: () -> GatewayFieldSession?,
    private val effects: WalkTransitionEffects,
    private val reports: WalkTransitionReports,
    private val diagnostics: WalkTransitionDiagnostics,
) {
    fun transitionWalkSession(event: WalkSessionEvent): kr.co.hanium.dreamup.walksafe.session.WalkSessionTransition {
        val before = lifecycle().snapshot()
        val gatewaySessionForEnd = if (event == WalkSessionEvent.EndRequested) {
            gatewaySession()
        } else {
            null
        }
        val endOperation = if (event == WalkSessionEvent.EndRequested) {
            authority.beginEnd(before.epoch)
        } else {
            null
        }
        val transition = lifecycle().handle(event, effects::beforePublication)
        effects.refreshCalibrationDiagnostics()
        if (event == WalkSessionEvent.SafetyStopRequested) {
            effects.recordSafetyStopTransition(transition)
        }
        if (transition.changed && transition.current.state == WalkSessionState.SAFE_STOP) {
            effects.hidePermissionDenial()
        }
        if (transition.changed && transition.previous.epoch != transition.current.epoch) {
            effects.onEpochChanged()
        }
        effects.updateRouteSnapshotWalkOwnership(transition)
        if (transition.changed) effects.invalidateGuidanceStopConfirmation()
        if (transition.current.state !in setOf(WalkSessionState.READY, WalkSessionState.ENDED)) {
            effects.cancelDeviceRuntimeCalibration("walk_transition")
        }
        if (
            transition.changed &&
            (
                transition.previous.epoch != transition.current.epoch ||
                    transition.current.state != WalkSessionState.ACTIVE
            )
        ) {
            effects.clearResumeActivation()
            effects.stopHandsFreeVoiceService()
            effects.invalidatePendingExplicitReport()
        }
        reports.updateQueueDrain(transition)
        if (transition.changed && transition.previous.state != transition.current.state) {
            reports.onWalkStateChanged(transition.current.state)
        }
        if (
            transition.changed &&
            transition.current.state == WalkSessionState.PAUSED &&
            transition.previous.epoch != transition.current.epoch
        ) {
            authority.rebindEpoch(
                transition.previous.epoch,
                transition.current.epoch,
            )
            gateway.scheduleGatewayWalkRenewal()
        }
        if (
            transition.changed &&
            transition.current.state == WalkSessionState.SAFE_STOP
        ) {
            gateway.cancelScheduledRenewal()
            // S03-6/S03-21: a readiness-driven stop (location or camera loss, mode change, recheck
            // result) also sends the server walk end. Paths that already began the end or dropped
            // the lease get null from beginEnd and keep the local invalidation only.
            val stopEnd = if (endOperation == null) {
                authority.beginEnd(transition.previous.epoch)
            } else {
                null
            }
            val stopSession = gatewaySession()
            if (stopEnd != null && stopSession != null) {
                gateway.endGatewayWalkBestEffort(stopSession, stopEnd)
            } else {
                authority.invalidate(transition.previous.epoch)
            }
            gateway.clearResumeRevalidation()
        }
        if (transition.changed && diagnostics.available()) {
            diagnostics.recordEvent(
                "walk_session_transition",
                mapOf(
                    "walk_session_id" to transition.current.epoch.walkSessionId,
                    "recovery_generation" to transition.current.epoch.recoveryGeneration,
                    "previous_state" to transition.previous.state.name,
                    "current_state" to transition.current.state.name,
                    "previous_mode" to transition.previous.mode.name,
                    "current_mode" to transition.current.mode.name,
                    "reason" to transition.reason.name,
                    "occurred_at_epoch_ms" to transition.occurredAtEpochMs,
                ),
            )
        }
        val rejection = WalkSessionTransitionDiagnostics.rejectionOrNull(event, transition)
        if (rejection != null && diagnostics.available()) {
            // Rejected admissions are otherwise invisible because they rarely change the snapshot.
            diagnostics.recordEvent(
                "walk_session_transition_rejected",
                mapOf(
                    "walk_session_id" to transition.current.epoch.walkSessionId,
                    "recovery_generation" to transition.current.epoch.recoveryGeneration,
                    "state" to transition.previous.state.name,
                    "recovery_stage" to (transition.previous.recoveryStage?.name ?: "none"),
                    "reason" to transition.reason.name,
                    "rejection" to rejection.name,
                    "occurred_at_epoch_ms" to transition.occurredAtEpochMs,
                ),
            )
        }
        reports.handleRawCollectionTransition(transition, event)
        if (endOperation != null && gatewaySessionForEnd != null) {
            gateway.cancelScheduledRenewal()
            gateway.endGatewayWalkBestEffort(gatewaySessionForEnd, endOperation)
        }
        if (transition.current.state == WalkSessionState.ACTIVE) {
            effects.refreshActiveSpeechStorageBinding()
        }
        // Owner decision 3(b): queued reports are sent only after the walk (FP-032-DETAIL-02).
        if (transition.changed && transition.current.state.allowsPostWalkTransfer) {
            reports.maybeStartPostWalkQueueDrain()
        }
        return transition
    }
}
