package kr.co.hanium.dreamup.walksafe.session.permission

import android.Manifest
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import java.security.MessageDigest

internal class PermissionFlowTestFixture : PermissionFlowAuthority<Long>, PermissionWalkReadiness,
    PermissionRuntimeActions, PermissionResultHandlers, PermissionPlatform, PermissionFlowUi, PermissionFlowPreferences {
    val events = mutableListOf<String>()
    val launches = mutableListOf<Pair<Int, List<String>>>()
    val denials = mutableListOf<String>()
    var foreground = true
    var privacyComplete = true
    var privacyDestroyed = false
    var firstRunReady = true
    var firstToken = 1L
    var firstSnapshot = FirstRunOnboardingPolicy.initialEmailAccount(1L)
    var postSnapshot = PostLoginDeviceCheckPolicy.initial()
    var postBindingLive = true
    private var walkSerial = 0
    var lifecycle = WalkSessionLifecycle(sessionIdFactory = { "permission-flow-walk-${++walkSerial}" }, elapsedRealtimeClock = { 100L }).also {
        it.handle(WalkSessionEvent.EnteredForeground)
    }
    var lifecyclePresent = true
    var actor: String? = "actor_b1c2d3e4f5a60718293b4c5d6e7f809a"
    var gatewayUsable = true
    var capability: WalkSafeStartupCapabilityDecision? = WalkSafeStartupCapabilityDecision(
        WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "ready")
    var block: String? = null
    var onBlockReason: () -> Unit = {}
    var runtimeIsActive = false
    var locationEnabled = true
    var probeReady = false
    var deviceRestrictions = false
    var analysisEnabled = true
    var routeIsActive = false
    var camera = true
    var location = true
    var microphone = true
    var activity = true
    var notifications = true
    var coarse = true
    var bypass = false
    var isFinishing = false
    var isDestroyed = false
    var launchFailure: RuntimeException? = null
    var osRead: ((String) -> Unit)? = null
    var started = false
    var completed = false
    var preferencesReady = true
    var saveStarted = true
    var saveCompleted = true
    var storedGate = PermissionRecoveryGate()
    var panelPublished = true
    var dialogShowing = false
    var pendingFocus: (() -> Boolean)? = null
    var afterRefresh: () -> Unit = {}
    var afterRecoveryPreparation: () -> Unit = {}
    private var nextRecoveryId = 1
    val flow = PermissionFlowController(this, this, this, this, this, this, this,
        requiredWalkPermissions = { if (it.preciseLocationGranted) emptyList() else listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) },
        actorDigest = { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b.toInt() and 255) } },
        requestCodeMin = 7000, requestCodeMax = 7010,
        newRecheckRequestId = { "permission-recheck-${nextRecoveryId++}" })

    override fun foreground() = foreground
    override fun privacyInspectionComplete() = privacyComplete
    override fun privacyInspectionDestroyed() = privacyDestroyed
    override fun firstRunSnapshot() = firstSnapshot
    override fun postLoginSnapshot() = postSnapshot
    override fun postLoginBindingLive(binding: PostLoginDeviceCheckBinding) = postBindingLive && postSnapshot.bindingOrNull == binding
    override fun firstRunLease() = firstToken
    override fun firstRunLeaseCurrent(lease: Long) = lease == firstToken
    override fun firstRunComplete() = firstRunReady
    override fun metricPreflightAllowed() = firstRunReady
    override fun lifecycleOrNull() = lifecycle.takeIf { lifecyclePresent }
    override fun currentActorId() = actor
    override fun gatewaySessionReady(session: GatewayFieldSession) = gatewayUsable && session.actorId == actor
    override fun decision() = capability
    override fun gatewayReady() = gatewayUsable
    override fun blockReason(decision: WalkSafeStartupCapabilityDecision): String? { onBlockReason(); return block }
    override fun missingRequired(action: WalkSessionAction) = emptyList<String>()
    override fun missingForDecision(decision: WalkSafeStartupCapabilityDecision) = emptyList<String>()
    override fun runtimeActive() = runtimeIsActive
    override fun locationServiceEnabled() = locationEnabled
    override fun startupProbeReady() = probeReady
    override fun hasDeviceRestrictions() = deviceRestrictions
    override fun disabledFeatureText() = "microphone"
    override fun cameraAnalysisEnabled() = analysisEnabled
    override fun invalidateSpeechStorage() { events += "invalidateSpeech" }
    override fun blockFieldSessionRestore() { events += "blockFieldRestore" }
    override fun status(status: String, detail: String) { events += "status:$status" }
    override fun navigationStatus(message: String) { events += "navigation:$message" }
    override fun refreshStartup() { events += "refreshStartup"; afterRefresh() }
    override fun renderMain() { events += "renderMain" }
    override fun cancelPrewalk() { events += "cancelPrewalk" }
    override fun safetyStop(reason: String) { events += "safetyStop:$reason"; lifecycle.handle(WalkSessionEvent.SafetyStopRequested) }
    override fun startAfterPermissionRecovery() { events += "startAfterRecovery" }
    override fun recheckPrewalkReadiness() { events += "recheckPrewalkReadiness"; afterRecoveryPreparation() }
    override fun cancelCameraHandoff() { events += "cancelCameraHandoff" }
    override fun stopDepth() { events += "stopDepth" }
    override fun stopCameraFallback() { events += "stopCameraFallback" }
    override fun cancelDestinationLocation() { events += "cancelDestinationLocation" }
    override fun clearReportLocationRequest() { events += "clearReportLocationRequest" }
    override fun stopLocation() { events += "stopLocation" }
    override fun routeActive() = routeIsActive
    override fun cancelRouteRequest() { events += "cancelRouteRequest" }
    override fun resetRoute() { events += "resetRoute" }
    override fun stopHandsFree() { events += "stopHandsFree" }
    override fun cancelVoiceRecognition() { events += "cancelVoiceRecognition" }
    override fun cancelReportDrain() { events += "cancelReportDrain" }
    override fun stopSteps() { events += "stopSteps" }
    override fun activateMounting() { events += "activateMounting" }
    override fun applyEnvironment() { events += "applyEnvironment" }
    override fun startWithoutCamera() { events += "startWithoutCamera" }
    override fun startNavigationServices() { events += "startNavigationServices" }
    override fun startHandsFreeIfAllowed() { events += "startHandsFreeIfAllowed" }
    override fun startLocationIfAllowed() { events += "startLocationIfAllowed" }
    override fun postLogin(binding: PostLoginDeviceCheckBinding) { events += "postLogin:${binding.actorId}" }
    override fun metricCamera() { events += "metricCameraResult" }
    override fun runtimeCamera() { events += "runtimeCameraResult" }
    override fun navigation() { events += "navigationResult" }
    override fun voice() { events += "voiceResult" }
    override val sdkInt = 35
    override fun finishing() = isFinishing
    override fun destroyed() = isDestroyed
    override fun debugExitBypass() = bypass
    override fun cameraGranted(): Boolean { osRead?.invoke("camera"); return camera }
    override fun preciseLocationGranted(): Boolean { osRead?.invoke("location"); return location }
    override fun microphoneGranted(): Boolean { osRead?.invoke("microphone"); return microphone }
    override fun activityRecognitionGranted(): Boolean { osRead?.invoke("activity"); return activity }
    override fun notificationGranted() = notifications
    override fun coarseLocationGranted() = coarse
    override fun rationaleRecommended(permission: String): Boolean { events += "rationale:$permission"; return false }
    override fun launchRequest(permissions: Array<String>, requestCode: Int) {
        events += "launch:$requestCode"; launches += requestCode to permissions.toList(); launchFailure?.let { throw it }
    }
    override fun panelReady() = panelPublished
    override fun hidePanel() { events += "hidePanel" }
    override fun renderProgress(awaitingResume: Boolean) { events += "progress:$awaitingResume" }
    override fun renderDenial(message: String) { denials += message; events += "denial" }
    override fun renderResumeControl() { events += "resumeControl" }
    override fun postResumeFocus(stillAwaiting: () -> Boolean) { pendingFocus = stillAwaiting; events += "postResumeFocus" }
    override fun requiredExitShowing() = dialogShowing
    override fun showRequiredExit(missing: Set<String>) { dialogShowing = true; events += "showExit:${missing.joinToString()}" }
    override fun dismissRequiredExit() { dialogShowing = false; events += "dismissExit" }
    override fun ready() = preferencesReady
    override fun restoreRecovery() = storedGate
    override fun persistRecovery(gate: PermissionRecoveryGate) { storedGate = gate; events += "persist:${gate.state}" }
    override fun initialStarted() = started
    override fun initialCompleted() = completed
    override fun markInitialStarted(): Boolean { events += "saveInitialStarted"; if (saveStarted) started = true; return saveStarted }
    override fun clearInitialStarted() { started = false; events += "clearInitialStarted" }
    override fun markInitialCompleted(): Boolean { events += "saveInitialCompleted"; if (saveCompleted) { completed = true; started = false }; return saveCompleted }

    fun publishGateway() {
        val process = GatewaySessionProcessCoordinator.snapshot()
        GatewaySessionProcessCoordinator.clear(process.generation)
        val operation = checkNotNull(GatewaySessionProcessCoordinator.beginOperation())
        check(GatewaySessionProcessCoordinator.publishVerified(operation,
            GatewayFieldSession.verified("https://permission.example.test", checkNotNull(actor), "fixture=value", Long.MAX_VALUE), firstSnapshot))
    }
    fun prepareRecovery() {
        publishGateway()
        flow.enterPermissionRecoveryBarrier(setOf(ObservedPermission.CAMERA), "test")
        flow.completePermissionRecoveryRecheckIfPossible()
        check(flow.permissionRecoveryGate.state == PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME)
        events.clear()
    }
    fun activateWalk() {
        val epoch = lifecycle.snapshot().epoch
        val collector = WalkSessionReadinessCollector(epoch, WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
        collector.plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY)) }
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(assessedAtElapsedRealtimeMs = 100L)))
        lifecycle.handle(WalkSessionEvent.StartRequested(checkNotNull(lifecycle.snapshot().confirmationToken)))
        runtimeIsActive = true
    }
    fun preparePostLoginRequest() {
        val loggedIn = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(firstSnapshot,
            FirstRunOpaqueActorBinding.fromProvider(checkNotNull(actor)), FirstRunReceiptHash.fromSha256Hex("1".padStart(64, '0'))).current
        firstSnapshot = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(loggedIn,
            FirstRunOnboardingAttemptRequest.forSnapshot(loggedIn, "req_" + "a".repeat(64), "att_" + "b".repeat(64)),
            FirstRunReceiptHash.fromSha256Hex("2".padStart(64, '0'))).current
        postSnapshot = PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, actor, 11L, 1L)
    }
}
