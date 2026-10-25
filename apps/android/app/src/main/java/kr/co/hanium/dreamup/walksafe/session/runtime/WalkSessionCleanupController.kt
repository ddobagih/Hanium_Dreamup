package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeStopCleanupCheckpoints
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*

/** Owns cleanup stage ordering and exact checkpoints; subsystem owners perform native effects. */
internal class WalkSessionCleanupController(
    private val walk: WalkSessionRuntimeAccess,
    private val authority: GatewayWalkAuthorityController,
    private val gateway: GatewayWalkSessionController,
    private val gatewaySession: () -> GatewayFieldSession?,
    private val resume: WalkSessionResumeController,
    private val admission: WalkCleanupAdmission,
    private val native: WalkCleanupNative,
    private val navigation: WalkCleanupNavigation,
    private val feedback: WalkCleanupFeedback,
    private val reports: WalkCleanupReports,
    private val debug: Boolean,
) {
    fun performWalkSessionSafetyStopCleanup(
        reason: String,
        persistInterruptionMarker: Boolean,
        checkpoints: WalkRuntimeStopCleanupCheckpoints?,
        onCameraResult: ((Boolean) -> Unit)?,
        onLocationResult: ((Boolean) -> Unit)? = null,
    ): Boolean {
        if (admission.developmentBypassEnabled() && reason in setOf(
                "camera_release_timed_out", "detector_runtime_failed",
                "offline_korean_tts_runtime_failure", "required_app_permission_denied",
            )
        ) {
            admission.updateNavigationStatus("navigation=test_continues unavailable=$reason")
            return true
        }
        var complete = true
        fun stage(key: String, idempotent: Boolean = false, action: () -> Unit) {
            val succeeded = if (checkpoints == null) {
                action()
                true
            } else {
                checkpoints.runStage(key, idempotent, action)
            }
            if (!succeeded) complete = false
        }
        stage("session_transition") {
            val walkSnapshot = if (walk.available()) walk.snapshot() else null
            val gatewaySessionForEnd = walkSnapshot?.takeIf {
                it.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)
            }?.let { gatewaySession() }
            val endOperation = walkSnapshot?.takeIf {
                it.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)
            }?.let { authority.beginEnd(it.epoch) }
            if (walk.available()) {
                walk.transition(WalkSessionEvent.SafetyStopRequested)
            }
            if (endOperation != null && gatewaySessionForEnd != null) {
                gateway.endGatewayWalkBestEffort(gatewaySessionForEnd, endOperation)
            }
        }
        if (persistInterruptionMarker && walk.available()) {
            stage("interruption_marker", idempotent = true) {
                val persisted = admission.persistInterruptionMarker()
                if (checkpoints != null && !persisted) {
                    throw IllegalStateException("walk interruption marker was not persisted")
                }
            }
        }
        stage("official_environment") { admission.invalidateOfficialEnvironment("safety_stop:$reason") }
        stage("phone_mounting") { admission.invalidatePhoneMounting("safety_stop:$reason") }
        stage("resume_state") {
            resume.clearResumeState()
        }
        if (checkpoints == null) {
            cancelWalkSessionOutputs(reason)
            native.stopCameraFallbackSession(updateUi = false)
        } else {
            if (!cancelWalkSessionOutputsForSafetyStop(reason, checkpoints, onLocationResult)) complete = false
            if (checkpoints.beginAsync("camera_release")) {
                try {
                    native.stopCameraFallbackSession(
                        updateUi = false,
                        onReleased = {
                            if (checkpoints.finishAsync("camera_release", true)) onCameraResult?.invoke(true)
                        },
                        onReleaseFailed = {
                            if (checkpoints.finishAsync("camera_release", false)) onCameraResult?.invoke(false)
                        },
                    )
                } catch (_: Exception) {
                    checkpoints.finishAsync("camera_release", false)
                    onCameraResult?.invoke(false)
                }
            } else if (checkpoints.isComplete("camera_release")) {
                onCameraResult?.invoke(true)
            } else if (checkpoints.isUncertain("camera_release")) {
                onCameraResult?.invoke(false)
            }
        }
        stage("depth_session") {
            val stopped = native.stopDepthSession(closeSession = true)
            if (checkpoints != null && !stopped) {
                throw IllegalStateException("depth_session_stop_failed", native.lastDepthCloseFailure())
            }
        }
        stage("startup_ui") {
            admission.refreshReadinessIfInitialized()
        }
        return complete
    }

    fun startFreshWalk(reason: String) {
        if (admission.stopCleanupBlocksNewWalk()) {
            if (admission.stopCleanupExhausted()) {
                admission.showRestartNotice()
            } else {
                admission.updateStatus("보행 안전 중지", "안전 정리가 끝나기 전에는 새 보행을 시작할 수 없습니다.")
            }
            return
        }
        walk.transition(WalkSessionEvent.NewWalkRequested)
        admission.persistInterruptionMarker()
        admission.invalidateOfficialEnvironment("new_walk:$reason")
        admission.invalidatePhoneMounting("new_walk:$reason")
        resume.clearResumeState()
        cancelWalkSessionOutputs(reason)
        admission.resetTransientStateForNewWalk()
    }

    fun enterWalkSessionForegroundRecheckAndCancelOutputs(reason: String) {
        if (debug) runCatching {
            admission.debug("WalkSafeNavigation", "output=foreground_recheck reason=$reason")
        }
        if (walk.available()) {
            walk.transition(WalkSessionEvent.RecheckRequested)
            admission.persistInterruptionMarker()
        }
        admission.invalidateOfficialEnvironment("foreground_recheck:$reason")
        admission.invalidatePhoneMounting("foreground_recheck:$reason")
        resume.clearResumeState()
        cancelWalkSessionOutputs(reason)
        native.stopCameraFallbackSession(updateUi = false)
        native.stopDepthSession(closeSession = true)
    }

    fun cancelWalkSessionOutputs(reason: String) {
        cancelWalkSessionOutputsInternal(reason, null)
    }

    fun cancelWalkSessionOutputsForSafetyStop(
        reason: String,
        checkpoints: WalkRuntimeStopCleanupCheckpoints,
        onLocationResult: ((Boolean) -> Unit)? = null,
    ): Boolean = cancelWalkSessionOutputsInternal(reason, checkpoints, onLocationResult)

    private fun cancelWalkSessionOutputsInternal(
        reason: String,
        checkpoints: WalkRuntimeStopCleanupCheckpoints?,
        onLocationResult: ((Boolean) -> Unit)? = null,
    ): Boolean {
        var complete = true
        fun stage(key: String, action: () -> Unit) {
            val succeeded = if (checkpoints == null) {
                action()
                true
            } else {
                checkpoints.runStage("outputs:$key", action = action)
            }
            if (!succeeded) complete = false
        }
        stage("debug_log") {
            if (debug) runCatching {
                admission.debug("WalkSafeNavigation", "output=session_outputs_cancelled reason=$reason")
            }
        }
        stage("feedback_generation") {
            feedback.advanceGenerationAndResetReferences()
        }
        stage("guidance_preparation") {
            feedback.cancelGuidancePreparation()
        }
        stage("explicit_report") { reports.invalidatePendingExplicitReport() }
        stage("ar_recheck") { native.invalidateArRecheck() }
        stage("startup_state") {
            admission.clearStartupState()
        }
        stage("voice_service") {
            val stopped = native.stopVoiceService()
            if (checkpoints != null) check(stopped) { "voice_service_stop_failed" }
        }
        stage("voice_recognition") { native.cancelRecognition() }
        stage("report_uploads") {
            reports.cancelPrivacyUploads()
        }
        var navigationCancellation = AndroidNavigationCancellation(false, false)
        stage("navigation_requests") {
            navigationCancellation = navigation.cancelRequestsForPause()
            navigation.clearDestinationSearchState(navigationCancellation.destinationSearchCancelled)
        }
        stage("route_state") {
            navigation.cancelWalkSessionRouteState(reason, checkpoints, navigationCancellation)
        }
        stage("frame_state") { navigation.invalidateFrameState() }
        stage("position_field") {
            val stopped = navigation.stopPositionFieldSession()
            if (checkpoints != null) check(stopped) { "position_field_stop_failed" }
        }
        if (checkpoints == null) {
            stage("location_stop") {
                if (!navigation.locationCollectionAllowsWork()) navigation.stopLocationUpdates()
            }
        } else {
            val tailComplete = navigation.runLocationStopCleanupCheckpoint(
                checkpoints, onLocationResult ?: {},
            ) {
                if (!navigation.locationCollectionAllowsWork()) {
                    navigation.stopLocationUpdates()
                    navigation.locationCleanupSucceeded()
                } else true
            }
            if (!tailComplete) complete = false
        }
        stage("step_tracking") { navigation.stopStepTracking() }
        stage("mounting_probe") {
            native.stopMountingProbe()
        }
        stage("compass") { navigation.retainLocationCompassOrStop() }
        stage("priority_user_training") { feedback.cancelPriorityTraining() }
        stage("feedback_pending") { feedback.cancelPendingDeliveries() }
        stage("talkback") {
            feedback.cancelTalkBack()
        }
        stage("feedback_terminal") { feedback.cancelTerminal() }
        stage("feedback_delivery_state") { feedback.resetDeliveryState() }
        stage("feedback_actuator") {
            feedback.closeActuator()
        }
        stage("camera_lifecycle") {
            native.moveCameraToCreated()
        }
        stage("surface") {
            native.pauseSurface()
        }
        stage("screen") { native.syncScreenPolicy() }
        stage("activity_original_uploads") { reports.cancelOriginalUploads() }
        stage("field_log") {
            reports.recordOutputsCancelled(reason)
        }
        stage("location_restart") { navigation.startLocationUpdatesIfAllowed() }
        return complete
    }
}
