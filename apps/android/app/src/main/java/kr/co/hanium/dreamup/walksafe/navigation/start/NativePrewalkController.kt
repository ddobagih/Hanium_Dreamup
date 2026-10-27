package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.device.PhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.navigation.GuidanceStartSpeechDelivery
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage

/** Explicit preparation, live admission and bounded failure delivery. No source work in construction. */
internal class NativePrewalkController(
    private val workflow: NativeWorkflowController,
    private val scope: Scope,
    private val checks: Checks,
    private val speech: Speech,
    private val actions: Actions,
    private val diagnostics: Diagnostics,
    private val elapsedRealtimeMs: () -> Long,
) {
    var actorId: String? = null
        private set
    private var confirmationPosted = false
    private var continuationPosted = false
    private var diagnosticState: String? = null
    private var sensorRequestId = 0L
    private var permissionRecoveryPreparation = false
    private val failureSpeech = GuidanceStartSpeechDelivery()
    private val adjustmentSpeech = GuidanceStartSpeechDelivery()

    fun continueFeaturePreparation() {
        if (permissionRecoveryPreparation) {
            continueIfReady()
            return
        }
        val page = workflow.pendingNativeUiPage ?: return
        if (!scope.homeAvailable() || !scope.foreground()) return
        if (page in setOf(NativeUiPage.VOICE_COMMAND, NativeUiPage.DESTINATION_SEARCH)) {
            cancel(cancelFeatureEntry = true)
            actions.showPage(page)
            if (page == NativeUiPage.VOICE_COMMAND) actions.ensureVoicePermissionThenListen()
            return
        }
        if (scope.developmentBypass()) return
        if (workflow.nativePrewalkStartEpoch != null) {
            continueIfReady()
            if (workflow.nativePrewalkStartEpoch == null && workflow.pendingNativeUiPage == null &&
                !scope.runtimeActive() && scope.homeAvailable() && workflow.nativeUiPage != NativeUiPage.GUIDANCE) {
                actions.showHomeLockNotice("앱 내의 기능 사용 제한", "기기 장착 상태와 기기 상태를 확인해주세요.")
            }
            return
        }
        if (scope.runtimeActive() || !checks.firstRunComplete() || actions.preparationDialogShowing() || continuationPosted) return
        val actor = scope.reporterId()
        continuationPosted = true
        actions.postStartup {
            continuationPosted = false
            if (workflow.pendingNativeUiPage == page && scope.reporterId() == actor && scope.foreground() && scope.homeAvailable()) {
                beginFromHome()
                actions.renderMain()
            }
        }
    }

    fun beginFromHome(): Boolean = begin(permissionRecovery = false)

    fun beginPermissionRecoveryRecheck(): Boolean {
        if (!scope.foreground() || scope.runtimeActive()) return false
        actions.cancelPendingFeatureEntry()
        return begin(permissionRecovery = true)
    }

    private fun begin(permissionRecovery: Boolean): Boolean {
        if (!scope.homeAvailable()) {
            actions.cancelPendingFeatureEntry()
            actions.showStatus("로그인과 안전교육 확인 필요", "현재 계정의 로그인과 안전교육 상태를 확인하세요.")
            return false
        }
        if (!permissionRecovery) actions.requestDevelopmentGuidanceStart()
        if (!checks.firstRunComplete()) {
            actions.showStatus("기기 재점검 필요", "현재 기기의 권한과 실제 호출어·진동 점검을 다시 완료하세요.")
            if (checks.postLoginState() in setOf(PostLoginDeviceCheckState.NOT_RUN, PostLoginDeviceCheckState.FAIL)) actions.startPostLoginCheck()
            actions.renderMain()
            return false
        }
        if (scope.runtimeActive()) return true
        val developmentBypass = !permissionRecovery && scope.developmentBypass()
        if (developmentBypass) cancel(cancelFeatureEntry = false)
        else if (actions.preparationDialogShowing() || workflow.nativePrewalkStartEpoch != null) return false
        var walk = scope.walk()
        if (walk.state in setOf(WalkSessionState.SAFE_STOP, WalkSessionState.ENDED)) {
            actions.startFreshWalk("native_home_requested_new_walk")
            walk = scope.walk()
        }
        if (walk.state !in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)) {
            actions.cancelPendingFeatureEntry()
            actions.showStatus("보행 준비 상태 확인 필요", "현재 보행을 안전하게 정리한 뒤 다시 시작하세요.")
            return false
        }
        if (developmentBypass) { actions.beginDevelopmentGuidance(walk); return scope.runtimeActive() }
        if (!checks.environmentProfilesPresent() || !checks.officialProfilePresent() || checks.cameraEnabled() && !checks.mountingProfilePresent()) {
            actions.cancelPendingFeatureEntry()
            actions.showStatus("실제 상태 점검 기준 없음", "현재 적용 가능한 환경·고정 측정 기준이 없어 보행을 시작하지 않습니다.")
            return false
        }
        val actor = scope.reporterId() ?: return false
        val epoch = walk.epoch
        actorId = actor
        permissionRecoveryPreparation = permissionRecovery
        workflow.nativePrewalkStartEpoch = epoch
        diagnosticState = null
        confirmationPosted = false
        workflow.nativePhoneMountingCheckRequest = NativePhoneMountingCheckRequest(actor, scope.gatewayGeneration(),
            workflow.pendingUiDestination.takeIf { !permissionRecovery && workflow.pendingNativeUiPage == NativeUiPage.DESTINATION_CONFIRM },
            PhoneMountingCheckRequest(epoch, ++sensorRequestId, elapsedRealtimeMs()))
        actions.clearStartupRetryRequirement()
        actions.showStatus(if (permissionRecovery) "필수 기능 재확인 중" else "안내 준비 중",
            "현재 위치와 카메라 방향·영상 품질을 점검합니다.")
        actions.beginCalibration(requireNotNull(workflow.nativePhoneMountingCheckRequest))
        actions.confirmEnvironment()
        recordGate("MEASUREMENT_REQUESTED", epoch)
        actions.renderMain()
        return false
    }

    fun continueIfReady() {
        val epoch = workflow.nativePrewalkStartEpoch ?: return
        val walk = scope.walk()
        val request = workflow.nativePhoneMountingCheckRequest
        if (!scope.foreground() || actorId != scope.reporterId() || walk.epoch != epoch || !checks.firstRunComplete() ||
            request == null || request.actorId != scope.reporterId() || request.gatewayGeneration != scope.gatewayGeneration() ||
            request.checkRequest.epoch != epoch || request.destination != null && request.destination != workflow.pendingUiDestination ||
            !scope.runtimeActive() && walk.state !in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)) {
            recordGate("OWNER_CANCELLED", epoch)
            cancel(cancelFeatureEntry = true)
            return
        }
        if (scope.runtimeActive()) {
            cancel(cancelFeatureEntry = false, preserveActiveSensorRequest = true)
            actions.renderMain()
            return
        }
        if (checks.locationAccessUnavailable()) {
            // A real access failure needs correction, not an impossible wait for a prompt lease.
            actions.cancelCalibrationForLocationAccess()
            recordGate("LOCATION_ACCESS_UNAVAILABLE", epoch)
            announceFailure(request)
            actions.retryPreparation(request)
            return
        }
        if (!actions.calibrationReady(request)) {
            recordGate("WAIT_DIRECTION_CALIBRATION", epoch)
            return
        }
        if (checks.preflightFailedOrTimedOut() || checks.startupRetryRequired()) {
            recordGate("MEASUREMENT_FAILED_OR_TIMED_OUT", epoch)
            announceFailure(request)
            actions.retryPreparation(request)
            return
        }
        if (checks.environmentReadiness(epoch) != WalkSessionReadinessStatus.READY || checks.mountingReadiness(epoch) != WalkSessionReadinessStatus.READY) {
            recordGate("WAIT_ENVIRONMENT_OR_MOUNTING", epoch)
            announceAdjustment(request)
            return
        }
        val decision = checks.startupDecision() ?: run { recordGate("WAIT_STARTUP_DECISION", epoch); return }
        if (permissionRecoveryPreparation) {
            if (decision.mayConfirmAndStart && checks.readinessBlockReason(decision) == null) {
                actions.completePermissionRecoveryRecheck()
            }
            return
        }
        if (!decision.mayConfirmAndStart || walk.confirmationToken == null || checks.readinessBlockReason(decision) != null ||
            confirmationPosted || actions.preparationDialogShowing() || checks.confirmationPending() || checks.startupConfirmed()) {
            recordGate("WAIT_CONFIRMATION_OR_READINESS", epoch)
            return
        }
        val token = walk.confirmationToken
        recordGate("POST_START_CONFIRMATION", epoch)
        confirmationPosted = true
        actions.postStartup {
            if (workflow.nativePrewalkStartEpoch != epoch || workflow.nativePhoneMountingCheckRequest != request) return@postStartup
            confirmationPosted = false
            val currentWalk = scope.walk()
            if (currentWalk.epoch != epoch || scope.gatewayGeneration() != request.gatewayGeneration ||
                !scope.foreground() || actorId != scope.reporterId() || !checks.firstRunComplete()) return@postStartup
            if (checks.startupDecision() != decision) {
                // The last capability callback may have run while this UI post owned the slot.
                // Reevaluate its live gates instead of requiring another measurement or start tap.
                continueIfReady()
                return@postStartup
            }
            if (checks.readinessBlockReason(decision) != null) return@postStartup
            if (currentWalk.confirmationToken != token) {
                // A real readiness recapture can replace the token during this UI post.
                // Recheck all live gates and post the replacement instead of waiting for another sensor event.
                if (currentWalk.confirmationToken != null) continueIfReady()
                return@postStartup
            }
            val current = {
                workflow.nativePrewalkStartEpoch == epoch && workflow.nativePhoneMountingCheckRequest == request &&
                    scope.foreground() && scope.reporterId() == request.actorId &&
                    scope.gatewayGeneration() == request.gatewayGeneration && scope.walk().epoch == epoch &&
                    scope.walk().confirmationToken == token && checks.startupDecision() == decision &&
                    checks.firstRunComplete() && !checks.preflightFailedOrTimedOut() && !checks.startupRetryRequired() &&
                    checks.readinessBlockReason(decision) == null &&
                    checks.environmentReadiness(epoch) == WalkSessionReadinessStatus.READY &&
                    checks.mountingReadiness(epoch) == WalkSessionReadinessStatus.READY
            }
            actions.requestStartConfirmation(current) {
                if (current()) {
                    diagnostics.directStart(scope.walk().state)
                    actions.completeStartupConfirmation(decision)
                    actions.renderMain()
                }
            }
        }
    }

    fun recordGate(stage: String, epoch: WalkRuntimeEpoch) {
        if (!diagnostics.enabled()) return
        runCatching {
            val state = diagnostics.capture(stage, epoch, actorId, confirmationPosted)
            if (diagnosticState == state) return@runCatching
            diagnosticState = state
            diagnostics.record(state)
        }
    }

    fun cancel(cancelFeatureEntry: Boolean, preserveActiveSensorRequest: Boolean = false) {
        failureSpeech.cancel()
        adjustmentSpeech.cancel()
        actions.cancelCalibration()
        if (!preserveActiveSensorRequest) {
            val hadRequest = workflow.nativePhoneMountingCheckRequest != null
            workflow.nativePhoneMountingCheckRequest = null
            if (hadRequest) actions.discardAndStopEnvironmentCamera()
        }
        workflow.nativePrewalkStartEpoch = null
        permissionRecoveryPreparation = false
        actions.clearPrewalkConsent()
        actorId = null
        confirmationPosted = false
        actions.dismissPreparationDialog()
        if (cancelFeatureEntry) { actions.cancelPreparationSpeech(); actions.cancelPendingFeatureEntry() }
    }

    fun announceAdjustment(request: NativePhoneMountingCheckRequest) {
        val reason = checks.cameraDiagnosticReason()
        fun current(): Boolean = scope.foreground() && workflow.nativePhoneMountingCheckRequest == request &&
            workflow.nativePrewalkStartEpoch == request.checkRequest.epoch && scope.walk().epoch == request.checkRequest.epoch &&
            scope.reporterId() == request.actorId && (request.destination == null || workflow.pendingUiDestination == request.destination) &&
            scope.gatewayGeneration() == request.gatewayGeneration && checks.preflightMeasuring() && !scope.runtimeActive() && checks.cameraDiagnosticReason() == reason
        if (!current() || reason !in setOf(CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE.name,
                CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE.name, CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name,
                CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY.name) ||
            elapsedRealtimeMs() - request.checkRequest.requestedAtElapsedRealtimeMs < 6_000L || speech.commandPending()) return
        val detail = checks.cameraAdjustmentDetail() ?: return
        val attempt = adjustmentSpeech.begin(request.checkRequest.requestId) ?: return
        val failed = { adjustmentSpeech.fail(attempt); Unit }
        val accepted = speech.submit(detail, ::current, { if (current()) adjustmentSpeech.complete(attempt); Unit }, failed, failed, failed)
        if (!accepted) failed()
    }

    fun announceFailure(request: NativePhoneMountingCheckRequest) {
        fun current(): Boolean = scope.foreground() && workflow.nativePhoneMountingCheckRequest == request &&
            workflow.nativePrewalkStartEpoch == request.checkRequest.epoch && scope.reporterId() == request.actorId &&
            scope.walk().epoch == request.checkRequest.epoch && scope.gatewayGeneration() == request.gatewayGeneration &&
            (request.destination == null || workflow.pendingUiDestination == request.destination) && !scope.runtimeActive()
        if (!current()) return
        val attempt = failureSpeech.begin(request.checkRequest.requestId) ?: return
        val reason = checks.failureSpeech()
        var terminal = false
        val failed: () -> Unit = {
            if (!terminal) {
                terminal = true
                if (current()) {
                    if (failureSpeech.fail(attempt)) actions.postFailureRetry(1_000L) { if (current()) announceFailure(request) }
                    else { actions.publishSpeechFailure("음성 안내를 재생하지 못했습니다. 한국어 음성 설정을 확인하고 다시 시도해 주세요."); actions.renderMain() }
                }
            }
        }
        val accepted = speech.submit(reason, ::current, {
            if (!terminal) { terminal = true; if (current()) failureSpeech.complete(attempt) }
        }, failed, failed, failed)
        if (!accepted) failed()
    }

    interface Scope {
        fun homeAvailable(): Boolean
        fun foreground(): Boolean
        fun reporterId(): String?
        fun gatewayGeneration(): Long
        fun runtimeActive(): Boolean
        fun walk(): WalkSessionSnapshot
        fun developmentBypass(): Boolean
    }
    interface Checks {
        fun firstRunComplete(): Boolean
        fun postLoginState(): PostLoginDeviceCheckState
        fun environmentProfilesPresent(): Boolean
        fun officialProfilePresent(): Boolean
        fun cameraEnabled(): Boolean
        fun mountingProfilePresent(): Boolean
        fun preflightFailedOrTimedOut(): Boolean
        fun preflightMeasuring(): Boolean
        fun startupRetryRequired(): Boolean
        fun locationAccessUnavailable(): Boolean = false
        fun environmentReadiness(epoch: WalkRuntimeEpoch): WalkSessionReadinessStatus
        fun mountingReadiness(epoch: WalkRuntimeEpoch): WalkSessionReadinessStatus
        fun startupDecision(): WalkSafeStartupCapabilityDecision?
        fun readinessBlockReason(decision: WalkSafeStartupCapabilityDecision): String?
        fun confirmationPending(): Boolean
        fun startupConfirmed(): Boolean
        fun cameraDiagnosticReason(): String?
        fun cameraAdjustmentDetail(): String?
        fun failureSpeech(): String
    }
    interface Speech {
        fun commandPending(): Boolean
        fun submit(message: String, current: () -> Boolean, completed: () -> Unit,
            failed: () -> Unit, cancelled: () -> Unit, retired: () -> Unit): Boolean
    }
    interface Actions {
        fun showPage(page: NativeUiPage)
        fun ensureVoicePermissionThenListen()
        fun showHomeLockNotice(title: String, detail: String)
        fun preparationDialogShowing(): Boolean
        fun postStartup(action: () -> Unit)
        fun cancelPendingFeatureEntry()
        fun showStatus(title: String, detail: String)
        fun requestDevelopmentGuidanceStart()
        fun startPostLoginCheck()
        fun renderMain()
        fun startFreshWalk(reason: String)
        fun beginDevelopmentGuidance(walk: WalkSessionSnapshot)
        fun clearStartupRetryRequirement()
        fun confirmEnvironment()
        fun beginCalibration(request: NativePhoneMountingCheckRequest) {}
        fun calibrationReady(request: NativePhoneMountingCheckRequest): Boolean = true
        fun cancelCalibration() {}
        fun cancelCalibrationForLocationAccess() { cancelCalibration() }
        fun retryPreparation(request: NativePhoneMountingCheckRequest) {}
        fun requestStartConfirmation(current: () -> Boolean, confirmed: () -> Unit) { if (current()) confirmed() }
        fun completeStartupConfirmation(decision: WalkSafeStartupCapabilityDecision)
        fun completePermissionRecoveryRecheck()
        fun discardAndStopEnvironmentCamera()
        fun clearPrewalkConsent()
        fun dismissPreparationDialog()
        fun cancelPreparationSpeech()
        fun postFailureRetry(delayMs: Long, action: () -> Unit)
        fun publishSpeechFailure(message: String)
    }
    interface Diagnostics {
        fun enabled(): Boolean
        fun capture(stage: String, epoch: WalkRuntimeEpoch, actorId: String?, confirmationPosted: Boolean): String
        fun record(state: String)
        fun directStart(state: WalkSessionState)
    }
}
