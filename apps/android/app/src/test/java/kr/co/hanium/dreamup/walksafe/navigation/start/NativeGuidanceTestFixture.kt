package kr.co.hanium.dreamup.walksafe.navigation.start

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityTier
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest

internal inline fun <reified T> fakePort(noinline respond: (String, Array<out Any?>) -> Any? = { _, _ -> null }): T {
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        respond(method.name, args ?: emptyArray()) ?: when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as T
}

internal class NativeGuidanceTestFixture(val enableRealCalibration: Boolean = false) {
    val epoch = WalkRuntimeEpoch("walk", 0L)
    val destination = DestinationSearchResult("place", "목적지", RoutePoint(37.5, 127.0), null, null, null, null)
    val events = mutableListOf<String>()
    var now = 10_000L
    var actor: String? = "actor"
    var gatewayGeneration = 3L
    var foreground = true
    var runtimeActive = false
    var homeAvailable = true
    var firstRunComplete = true
    var developmentBypass = false
    var preflightFailed = false
    var retryRequired = false
    var walk = WalkSessionSnapshot(epoch, WalkSessionState.READY, WalkSessionMode.FULL, null, true, null)
    var commandGeneration = 2L
    var reads = 0
    val startupPosts = mutableListOf<() -> Unit>()
    val failurePosts = mutableListOf<Pair<Long, () -> Unit>>()
    val preparationPosts = mutableListOf<Pair<Long, Runnable>>()
    var lastCompleted: (() -> Unit)? = null
    var lastFailed: (() -> Unit)? = null
    var lastCurrent: (() -> Boolean)? = null
    var acceptedSpeech = true
    var speechSubmissions = 0
    var lastPhase: String? = null
    var readinessStatus = WalkSessionReadinessStatus.PENDING
    var afterPermissionRecoveryRecheck: () -> Unit = {}
    var decision: WalkSafeStartupCapabilityDecision? = null
    val calibration: GuidanceStartCalibration get() = NativeGuidanceCalibrationSession::class.java
        .getDeclaredField("nativeGuidanceCalibration").apply { isAccessible = true }
        .get(calibrationSession) as GuidanceStartCalibration
    var locationPermission = true
    var calibrationSpeechBusy = false
    var acceptCalibrationSpeech = true
    val calibrationPrompts = mutableListOf<CalibrationPrompt>()
    val calibrationPosts = mutableListOf<Pair<Long, Runnable>>()
    val preparationRetries = mutableListOf<NativePhoneMountingCheckRequest>()

    data class CalibrationPrompt(
        val lease: GuidanceStartCalibration.PromptLease,
        val message: String,
        val current: () -> Boolean,
        val completed: () -> Unit,
        val failed: () -> Unit,
    )

    val workflow = NativeWorkflowController(
        fakePort(), fakePort { name, _ -> events += "page:$name"; null }, fakePort(), fakePort())

    val preparation = GuidancePreparationController(workflow,
        fakePort<GuidancePreparationController.Scope> { name, _ ->
            reads++
            when (name) {
                "reporterId" -> actor
                "gatewayGeneration" -> gatewayGeneration
                "epoch" -> walk.epoch
                "walkState" -> walk.state
                "foreground" -> foreground
                "hasRoute" -> false
                "preflightFailedOrTimedOut" -> preflightFailed
                "locationWaiting" -> true
                else -> null
            }
        }, fakePort<GuidancePreparationController.Speech> { name, args ->
            when (name) {
                "commandGeneration" -> commandGeneration
                "cancelRegisteredResponse" -> { events += "cancel_response:${args[0]}"; null }
                "cancelCommandInteraction" -> { events += "cancel_interaction"; null }
                "submitWaitingNotice" -> { events += "wait_speech"; true }
                else -> null
            }
        }, object : GuidancePreparationController.Callbacks {
            override fun postDelayed(action: Runnable, delayMs: Long) { preparationPosts += delayMs to action }
            override fun remove(action: Runnable) { preparationPosts.removeAll { it.second === action } }
        }, { now })

    val prewalk: NativePrewalkController = NativePrewalkController(workflow,
        fakePort<NativePrewalkController.Scope> { name, _ ->
            reads++
            when (name) {
                "homeAvailable" -> homeAvailable
                "foreground" -> foreground
                "reporterId" -> actor
                "gatewayGeneration" -> gatewayGeneration
                "runtimeActive" -> runtimeActive
                "walk" -> walk
                "developmentBypass" -> developmentBypass
                else -> null
            }
        }, fakePort<NativePrewalkController.Checks> { name, _ ->
            when (name) {
                "firstRunComplete" -> firstRunComplete
                "postLoginState" -> PostLoginDeviceCheckState.NOT_RUN
                "environmentProfilesPresent", "officialProfilePresent", "mountingProfilePresent" -> true
                "cameraEnabled" -> false
                "preflightFailedOrTimedOut" -> preflightFailed
                "preflightMeasuring" -> !preflightFailed
                "startupRetryRequired" -> retryRequired
                "environmentReadiness", "mountingReadiness" -> readinessStatus
                "startupDecision" -> decision
                "cameraDiagnosticReason" -> "BRIGHTNESS_OUTSIDE_APPROVED_RANGE"
                "cameraAdjustmentDetail" -> "카메라: 밝기를 확인하세요."
                "failureSpeech" -> "다시 시도를 눌러 주세요."
                else -> null
            }
        }, object : NativePrewalkController.Speech {
            override fun commandPending() = false
            override fun submit(message: String, current: () -> Boolean, completed: () -> Unit,
                failed: () -> Unit, cancelled: () -> Unit, retired: () -> Unit): Boolean {
                speechSubmissions++
                lastCompleted = completed
                lastFailed = failed
                lastCurrent = current
                return acceptedSpeech
            }
        }, fakePort<NativePrewalkController.Actions> { name, args ->
            when (name) {
                "postStartup" -> { @Suppress("UNCHECKED_CAST") startupPosts += args[0] as () -> Unit; null }
                "postFailureRetry" -> { @Suppress("UNCHECKED_CAST") failurePosts += args[0] as Long to (args[1] as () -> Unit); null }
                "publishSpeechFailure" -> { preparation.reportVisibleFailure(args[0] as String); null }
                "cancelPreparationSpeech" -> { preparation.cancel(); null }
                "cancelPendingFeatureEntry" -> { cancelPendingFeatureEntry(); null }
                "preparationDialogShowing" -> false
                "beginCalibration" -> { if (enableRealCalibration) advanceCalibration(args[0] as NativePhoneMountingCheckRequest); null }
                "calibrationReady" -> !enableRealCalibration || advanceCalibration(args[0] as NativePhoneMountingCheckRequest)
                "cancelCalibration" -> { if (enableRealCalibration) cancelCalibration(); null }
                "retryPreparation" -> { if (enableRealCalibration) preparationRetries += args[0] as NativePhoneMountingCheckRequest; null }
                "completePermissionRecoveryRecheck" -> { events += name; afterPermissionRecoveryRecheck(); null }
                else -> { events += name; null }
            }
        }, object : NativePrewalkController.Diagnostics {
            override fun enabled() = true
            override fun capture(stage: String, epoch: WalkRuntimeEpoch, actorId: String?, confirmationPosted: Boolean): String { lastPhase = stage; return "$stage:$actorId:$confirmationPosted" }
            override fun record(state: String) {}
            override fun directStart(state: WalkSessionState) { events += "direct_start" }
        }, { now })

    private fun cancelPendingFeatureEntry() {
        workflow.pendingNativeUiPage = null
        if (workflow.nativePrewalkStartEpoch != null) prewalk.cancel(cancelFeatureEntry = false)
        events += "cancelPendingFeatureEntry"
    }

    /** Real execution owner; only Android speech, clock and scheduling leaves are controlled. */
    private val calibrationSession: NativeGuidanceCalibrationSession = NativeGuidanceCalibrationSession(
        object : NativeGuidanceCalibrationSession.Scope {
            override fun requestCurrent(request: NativePhoneMountingCheckRequest) = calibrationRequestCurrent(request)
            override fun ownerCurrent(request: NativePhoneMountingCheckRequest) = calibrationOwnerCurrent(request)
        }, object : NativeGuidanceCalibrationSession.Speech {
            override fun pending() = calibrationSpeechBusy
            override fun commandGeneration() = commandGeneration
            override fun submit(message: String, current: () -> Boolean, completed: () -> Unit, failed: () -> Unit): Boolean {
                val lease = requireNotNull(calibration.snapshot?.prompt)
                calibrationPrompts += CalibrationPrompt(lease, message, current, completed, failed)
                return acceptCalibrationSpeech
            }
            override fun cancel() { events += "cancel_calibration_speech" }
        }, object : NativeGuidanceCalibrationSession.Callbacks {
            override fun postDelayed(action: Runnable, delayMs: Long) { calibrationPosts += delayMs to action }
            override fun remove(action: Runnable) { calibrationPosts.removeAll { it.second === action } }
        }, object : NativeGuidanceCalibrationSession.Effects {
            override fun recordStage(stage: String) { events += stage }
            override fun reportVisibleFailure(message: String) { preparation.reportVisibleFailure(message) }
            override fun visibleFailure() = preparation.visibleFailure
            override fun clearVisibleFailure() { preparation.clearVisibleFailure() }
            override fun render() { events += "render" }
            override fun continuePrewalk() { prewalk.continueIfReady() }
            override fun recoverPreparation(request: NativePhoneMountingCheckRequest) { preparationRetries += request }
        }, { now },
    )

    private fun advanceCalibration(request: NativePhoneMountingCheckRequest) = calibrationSession.advance(request)
    private fun cancelCalibration() = calibrationSession.cancel()

    private fun calibrationOwnerCurrent(request: NativePhoneMountingCheckRequest): Boolean =
        calibrationRequestCurrent(request) && locationPermission

    private fun calibrationRequestCurrent(request: NativePhoneMountingCheckRequest): Boolean =
        workflow.nativePhoneMountingCheckRequest === request && foreground && walk.isForeground &&
            walk.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED) &&
            walk.epoch == request.checkRequest.epoch && actor == request.actorId &&
            gatewayGeneration == request.gatewayGeneration && firstRunComplete &&
            (request.destination == null || request.destination == workflow.pendingUiDestination)

    fun runCalibrationPost() {
        val task = calibrationPosts.single().second
        calibrationPosts.removeAll { it.second === task }
        task.run()
    }

    fun completeReadiness() {
        val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
        val readiness = WalkSessionReadinessCollector(epoch, plan)
        plan.requiredRequirements.forEach { readiness.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY)) }
        walk = walk.copy(readiness = readiness.build(now))
        readinessStatus = WalkSessionReadinessStatus.READY
        decision = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "확인")
    }
}
