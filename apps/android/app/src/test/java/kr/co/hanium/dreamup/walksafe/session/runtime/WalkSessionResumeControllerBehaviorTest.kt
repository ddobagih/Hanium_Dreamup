package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class WalkSessionResumeControllerBehaviorTest {
    @Test fun constructionAndReadOnlyFlagsDoNotQueryMachineOrEvidence() {
        val f = ResumeFixture()
        assertFalse(f.controller.promptPending); assertFalse(f.controller.retryRequiresUserAction)
        assertEquals(emptyList<String>(), f.events)
    }
    @Test fun actualPromptCompletionAloneStartsResumeRecognition() {
        val f = ResumeFixture(); f.paused()
        f.controller.requestWalkSessionResumeConfirmation()
        assertTrue(f.controller.promptPending); assertEquals(0, f.microphones)
        f.done!!(); assertEquals(1, f.microphones)
    }
    @Test fun oldGenerationCompletionAndFailureDoNotMutateCurrentPrompt() {
        val f = ResumeFixture(); f.paused(); f.controller.requestWalkSessionResumeConfirmation()
        val done = f.done!!; val failed = f.failed!!; val token = f.controller.confirmationToken
        f.generation++; done(); failed()
        assertEquals(0, f.microphones); assertEquals(token, f.controller.confirmationToken)
        assertTrue(f.controller.promptPending)
    }
    @Test fun oldEpochCompletionAndFailureDoNotOpenInputOrWriteRetryState() {
        val f = ResumeFixture(); f.paused(); f.controller.requestWalkSessionResumeConfirmation()
        f.lifecycle.handle(WalkSessionEvent.NewWalkRequested); f.done!!(); f.failed!!()
        assertEquals(0, f.microphones); assertFalse(f.controller.retryRequiresUserAction)
    }
    @Test fun failedMicrophoneLeavesPausedAndEnablesExplicitRetry() {
        val f = ResumeFixture(); f.paused(); f.microphoneStarts = false
        f.controller.requestWalkSessionResumeConfirmation(); f.done!!()
        assertFalse(f.controller.promptPending); assertNull(f.controller.confirmationToken)
        assertTrue(f.controller.retryRequiresUserAction); assertEquals(1, f.enabledButtons)
        assertEquals(WalkSessionState.PAUSED, f.lifecycle.snapshot().state)
    }
    @Test fun failedPromptCannotBecomeAResumeConfirmation() {
        val f = ResumeFixture(); f.paused(); f.speechAccepted = false
        f.controller.requestWalkSessionResumeConfirmation()
        f.controller.handleWalkSessionResumeRecognition(listOf("시작"))
        assertEquals(0, f.activations); assertEquals(1, f.enabledButtons)
        assertTrue(f.controller.retryRequiresUserAction)
    }
    @Test fun canceledVoiceResponseRetainsPauseAndPersistsIt() {
        val f = ResumeFixture(); f.paused(); f.controller.requestWalkSessionResumeConfirmation()
        f.controller.handleWalkSessionResumeRecognition(listOf("취소"))
        assertEquals(WalkSessionState.PAUSED, f.lifecycle.snapshot().state)
        assertEquals(1, f.persisted); assertTrue(f.controller.retryRequiresUserAction)
        assertEquals(0, f.activations)
    }
    @Test fun recognitionRechecksLeaseAndFreshReadinessBeforeResume() {
        val f = ResumeFixture(); f.paused(); f.controller.requestWalkSessionResumeConfirmation()
        f.readinessOpen = false; f.controller.handleWalkSessionResumeRecognition(listOf("시작"), floatArrayOf(0.99f))
        assertEquals(0, f.activations); assertEquals(listOf("resume_readiness_changed"), f.rechecks)
        assertTrue(f.controller.retryRequiresUserAction)
    }
    @Test fun expiredLeaseStopsBeforeReadingReadinessOrActivating() {
        val f = ResumeFixture(); f.paused(); f.leasePresent = false
        f.controller.confirmWalkSessionResumeFromButton()
        assertEquals(listOf("walk_lease_missing_on_resume_confirmation"), f.lost)
        assertEquals(0, f.captures); assertEquals(0, f.activations)
    }
    @Test fun acceptedResumeWaitsForCameraThenCompletesOnceWithSameToken() {
        val f = ResumeFixture(); val token = f.paused(); f.handoffPending = true
        f.controller.confirmWalkSessionResumeFromButton()
        assertEquals(1, f.activations); assertTrue(f.controller.activationPending(token.epoch))
        assertTrue(f.controller.retryRequiresUserAction)
        f.controller.completePendingWalkSessionResumeActivation(token.epoch)
        assertEquals(0, f.completions)
        f.handoffPending = false; f.controller.completePendingWalkSessionResumeActivation(token.epoch)
        f.controller.completePendingWalkSessionResumeActivation(token.epoch)
        assertEquals(1, f.completions); assertEquals(1, f.published)
        assertFalse(f.controller.activationPending(token.epoch)); assertFalse(f.controller.retryRequiresUserAction)
        assertEquals(listOf(true, false), f.captureDiagnosticFlags)
    }
    @Test fun runtimeAdmissionFailureRetiresPendingActivationAndKeepsExplicitRetry() {
        val f = ResumeFixture(); val token = f.paused(); f.activationAccepted = false
        f.controller.confirmWalkSessionResumeFromButton()
        assertFalse(f.controller.activationPending(token.epoch)); assertTrue(f.controller.retryRequiresUserAction)
        assertEquals(0, f.completions)
    }
    @Test fun returningToBackgroundRetiresOnlyMatchingPendingActivation() {
        val f = ResumeFixture(); val token = f.paused(); f.controller.confirmWalkSessionResumeFromButton()
        f.foreground = false; f.controller.completePendingWalkSessionResumeActivation(token.epoch)
        assertFalse(f.controller.activationPending(token.epoch)); assertEquals(0, f.completions)
    }
}

internal class ResumeFixture : WalkSessionRuntimeAccess, WalkResumeReadiness, WalkResumeEnvironment, WalkResumeEffects {
    var now = 1_000L; var generation = 1; var sequence = 0
    val events = mutableListOf<String>(); val rechecks = mutableListOf<String>(); val lost = mutableListOf<String>()
    val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "resume-${++sequence}" }, clock = { now }, elapsedRealtimeClock = { now })
    val controller = WalkSessionResumeController(this, this, this, this)
    var done: (() -> Unit)? = null; var failed: (() -> Unit)? = null
    var microphones = 0; var microphoneStarts = true; var speechAccepted = true; var enabledButtons = 0
    var activations = 0; var activationAccepted = true; var persisted = 0; var captures = 0
    var readinessOpen = true; var leasePresent = true; var handoffPending = false; var foreground = true
    var completions = 0; var published = 0; val captureDiagnosticFlags = mutableListOf<Boolean>()
    var confirmed = true; var permissionAllowed = true; var revalidationPending = false
    var evidenceMaxAgeMs: Long? = null
    var afterCapture: () -> Unit = {}
    var captureTransform: (WalkSessionReadinessSnapshot) -> WalkSessionReadinessSnapshot = { it }
    var lastCapture: WalkSessionReadinessSnapshot? = null
    private val decision = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "ready")
    fun readiness(action: WalkSessionAction): WalkSessionReadinessSnapshot {
        val plan = WalkSessionReadinessPlan(action, WalkSessionMode.FULL, checkEntryPermissions = true)
        val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan)
        plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch, it,
            if (readinessOpen) WalkSessionReadinessStatus.READY else WalkSessionReadinessStatus.UNAVAILABLE,
            reason = if (readinessOpen) "" else "unavailable",
            supportingEvidence = if (it == WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT && evidenceMaxAgeMs != null)
                listOf(WalkSessionReadinessEvidence("camera", now, evidenceMaxAgeMs!!, "current")) else emptyList())) }
        return collector.build(now)
    }
    fun paused(): WalkSessionConfirmationToken {
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness(WalkSessionAction.START_WALK)))
        lifecycle.handle(WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!))
        lifecycle.handle(WalkSessionEvent.RecheckRequested)
        lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(readiness(WalkSessionAction.RESUME_WALK)))
        return lifecycle.snapshot().confirmationToken!!
    }
    override fun available() = true
    override fun snapshot(): WalkSessionSnapshot { events += "snapshot"; return lifecycle.snapshot() }
    override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = lifecycle.isRuntimeEpochCurrent(epoch)
    override fun transition(event: WalkSessionEvent) = lifecycle.handle(event)
    override fun decision(): WalkSafeStartupCapabilityDecision { events += "decision"; return decision }
    override fun blockReason(decision: WalkSafeStartupCapabilityDecision): String? = null
    override fun capture(decision: WalkSafeStartupCapabilityDecision, action: WalkSessionAction, recordResourceDiagnostics: Boolean): WalkSessionReadinessSnapshot {
        captures++; captureDiagnosticFlags += recordResourceDiagnostics
        val value = captureTransform(readiness(action)); lastCapture = value; afterCapture(); return value
    }
    override fun isConfirmed() = confirmed
    override fun isForeground() = foreground
    override fun runtimeActive() = lifecycle.snapshot().state == WalkSessionState.ACTIVE
    override fun navigationOutputsAllowed() = true
    override fun cameraHandoffPending(epoch: WalkRuntimeEpoch) = handoffPending
    override fun voiceConfirmationAvailable() = true
    override fun feedbackGeneration() = generation
    override fun isFeedbackCurrent(generation: Int) = this.generation == generation
    override fun activeLeaseOrNull(epoch: WalkRuntimeEpoch) = if (leasePresent)
        GatewayWalkLease("ACQUIRED", "actor", "device", epoch.walkSessionId, "lease", 1, 1, 99999, 1, 99999) else null
    override fun permissionAllowsAutomaticStart() = permissionAllowed
    override fun gatewayRevalidationPending() = revalidationPending
    override fun runOnUiThread(action: () -> Unit) = action()
    override fun updateStatus(title: String, detail: String) { events += "status:$title" }
    override fun enableResumeConfirmationButton() { enabledButtons++ }
    override fun speakResume(message: String, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean {
        done = onCompleted; failed = onFailed; return speechAccepted
    }
    override fun startResumeRecognition(): Boolean { microphones++; return microphoneStarts }
    override fun acceptsControlConfidence(confidence: Float) = confidence >= 0.5f
    override fun activateRuntime(): Boolean { activations++; return activationAccepted }
    override fun persistInterruptionMarker(): Boolean { persisted++; return true }
    override fun foregroundRecheck(reason: String) { rechecks += reason }
    override fun authorityLost(reason: String) { lost += reason }
    override fun publishResumeCompletion() { published++ }
    override fun requestNavigation(point: RoutePoint, label: String, confirmed: Boolean, evidence: DestinationCoordinateEvidence?) { events += "requestRoute" }
    override fun completeAdmittedResume(epoch: WalkRuntimeEpoch, publishCompletion: () -> Unit,
        requestNavigation: (RoutePoint, String, Boolean, DestinationCoordinateEvidence?) -> Unit) { completions++; publishCompletion() }
}
