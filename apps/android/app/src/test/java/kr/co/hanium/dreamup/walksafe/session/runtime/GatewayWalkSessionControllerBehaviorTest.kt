package kr.co.hanium.dreamup.walksafe.session.runtime

import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class GatewayWalkSessionControllerBehaviorTest {
    @Test fun constructionDoesNotReadSessionLeaseClockOrSchedule() {
        val f = GatewayFixture()
        assertEquals(emptyList<String>(), f.events)
        assertTrue(f.background.isEmpty())
        assertTrue(f.scheduled.isEmpty())
    }

    @Test fun startRequiresFreshReadinessBeforeRequestAndAgainBeforeActivation() {
        val f = GatewayFixture(); val token = f.prepare()
        f.controller.requestGatewayWalkStart(token)
        assertEquals(1, f.readinessReads)
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
        f.runBackground()
        assertEquals(2, f.readinessReads)
        assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        assertEquals(1, f.activations)
        assertNull(f.controller.startConfirmationToken)
    }

    @Test fun readinessLostWhileServerAnsweredEndsAcquiredLeaseWithoutRuntime() {
        val f = GatewayFixture(); val token = f.prepare()
        f.controller.requestGatewayWalkStart(token); f.readinessOpen = false
        f.runBackground(); f.runBackground()
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
        assertEquals(0, f.activations)
        assertEquals(token.epoch.walkSessionId, f.ends.single().second.walkId)
        assertSame(f.account, f.ends.single().first)
        assertNull(f.authority.activeLeaseOrNull(token.epoch))
        assertTrue(f.retries.single().contains("실제 상태가 바뀌어"))
    }

    @Test fun initialReadinessFailureDoesNotAcquireServerOrPendingToken() {
        val f = GatewayFixture(); val token = f.prepare(); f.readinessOpen = false
        f.controller.requestGatewayWalkStart(token)
        assertTrue(f.background.isEmpty()); assertNull(f.controller.startConfirmationToken)
        assertEquals(1, f.retries.size)
    }

    @Test fun takeoverDispatchAcceptanceDoesNotOpenMicrophoneUntilActualCompletion() {
        val f = GatewayFixture(); val token = f.prepare(); f.startConflict = true
        f.controller.requestGatewayWalkStart(token); f.runBackground()
        assertTrue(f.controller.takeoverPromptPending)
        assertEquals(0, f.microphones)
        f.promptDone!!()
        assertEquals(1, f.microphones)
        f.controller.handleGatewayWalkTakeoverRecognition(listOf("예")); f.runBackground()
        assertEquals(1, f.takeovers); assertEquals(1, f.activations)
        assertEquals(8L, f.authority.activeLeaseOrNull(token.epoch)?.fencingToken)
    }

    @Test fun canceledTakeoverIgnoresLateCompletedPromptAndKeepsPreviousWalk() {
        val f = GatewayFixture(); val token = f.prepare(); f.startConflict = true
        f.controller.requestGatewayWalkStart(token); f.runBackground()
        val done = f.promptDone!!
        f.controller.handleGatewayWalkTakeoverRecognition(listOf("아니요")); done()
        assertEquals(0, f.microphones); assertEquals(0, f.takeovers)
        assertNull(f.controller.startConfirmationToken)
        assertTrue(f.spoken.single().contains("이전 기기의 보행을 유지"))
    }

    @Test fun failedTakeoverMicrophoneClearsConflictAndRequiresNewUserAttempt() {
        val f = GatewayFixture(); val token = f.prepare(); f.startConflict = true; f.microphoneStarts = false
        f.controller.requestGatewayWalkStart(token); f.runBackground(); f.promptDone!!()
        assertNull(f.authority.currentConflictOrNull(token.epoch))
        assertFalse(f.controller.takeoverPromptPending); assertNull(f.controller.startConfirmationToken)
        assertTrue(f.events.last().contains("takeover_voice_not_started"))
    }

    @Test fun staleGrantedStartEndsExactlyTheAbandonedLeaseUsingOriginalAccount() {
        val f = GatewayFixture(); val token = f.prepare()
        f.controller.requestGatewayWalkStart(token)
        f.lifecycle.handle(WalkSessionEvent.NewWalkRequested)
        f.runBackground(); f.runBackground()
        assertEquals(0, f.activations)
        assertEquals(token.epoch.walkSessionId, f.ends.single().second.walkId)
        assertSame(f.account, f.ends.single().first)
    }

    @Test fun queuedAbandonedEndDoesNotEndLeaseTrackedByNewAttemptForSameWalk() {
        val f = GatewayFixture(); val token = f.prepare()
        f.controller.requestGatewayWalkStart(token)
        f.actorCurrent = false; f.runBackground()
        assertEquals(1, f.background.size)
        f.authority.beginStart(token.epoch)
        f.runBackground()
        assertTrue(f.ends.isEmpty())
    }

    @Test fun transientRenewalRetriesTwiceWithinExistingLeaseThenStops() {
        val f = GatewayFixture(); f.active(); f.renewError = IOException("offline")
        repeat(3) {
            f.controller.requestGatewayWalkRenewal(); f.runBackground()
        }
        assertEquals(listOf(1_000L, 3_000L), f.postedDelays)
        assertEquals(listOf("walk_lease_renew_unreachable"), f.stops)
    }

    @Test fun rejectedRenewalNeverUsesTransientRetry() {
        val f = GatewayFixture(); f.active()
        f.renewError = GatewayWalkHttpException(409, "rejected", "walk_lease_lost")
        f.controller.requestGatewayWalkRenewal(); f.runBackground()
        assertTrue(f.postedDelays.isEmpty())
        assertEquals(listOf("walk_lease_rejected"), f.stops)
    }

    @Test fun replacedActorCannotExtendLeaseEvenWithMatchingServerLease() {
        val f = GatewayFixture(); val token = f.active(); f.controller.requestGatewayWalkRenewal()
        f.actorCurrent = false; f.runBackground()
        assertEquals(listOf("walk_lease_gateway_session_changed"), f.stops)
        assertNull(f.authority.activeLeaseOrNull(token.epoch))
    }

    @Test fun sameLoginRenewalRequeuesInsteadOfApplyingOldCredentialResponse() {
        val f = GatewayFixture(); val token = f.active(); val before = f.authority.activeLeaseOrNull(token.epoch)
        f.controller.requestGatewayWalkRenewal(); f.actorCurrent = false; f.renewedContinuation = true
        f.runBackground()
        assertEquals(before, f.authority.activeLeaseOrNull(token.epoch))
        assertTrue(f.stops.isEmpty()); assertEquals(0L, f.postedDelays.single())
    }

    @Test fun staleEpochRenewalResponseHasNoCurrentWalkEffects() {
        val f = GatewayFixture(); f.active(); f.controller.requestGatewayWalkRenewal()
        f.lifecycle.handle(WalkSessionEvent.NewWalkRequested); f.runBackground()
        assertTrue(f.stops.isEmpty()); assertTrue(f.postedDelays.isEmpty())
    }

    @Test fun scheduledTickObservesResourcesBeforeRenewalAndSuccessfulRenewalClearsRetryBudget() {
        val f = GatewayFixture(); f.active(); f.controller.scheduleGatewayWalkRenewal()
        f.events.clear(); f.scheduled.removeAt(0).first.run()
        assertEquals("resources", f.events.first())
        assertTrue(f.background.isNotEmpty())
        f.runBackground(); assertTrue(f.stops.isEmpty())
        assertTrue(f.postedDelays.all { it == 30_000L })
    }

    @Test fun endRetryRetainsRequestIdAndOwningAccount() {
        val f = GatewayFixture(); val token = f.active()
        val end = f.authority.beginEnd(token.epoch)!!; f.endError = IOException("lost")
        f.controller.endGatewayWalkBestEffort(f.account, end); f.runBackground()
        f.endError = null; f.scheduled.removeAt(0).first.run(); f.runBackground()
        assertEquals(listOf(end.requestId, end.requestId), f.ends.map { it.third })
        assertTrue(f.ends.all { it.first === f.account })
    }

    @Test fun rejectedExecutorClearsPendingStartInsteadOfStartingRuntime() {
        val f = GatewayFixture(); val token = f.prepare(); f.rejectExecutor = true
        f.controller.requestGatewayWalkStart(token)
        assertNull(f.controller.startConfirmationToken); assertEquals(0, f.activations)
        assertNotNull(f.authority.beginStart(token.epoch))
    }
}

internal class GatewayFixture : GatewayWalkRuntime, GatewayWalkIdentity, GatewayWalkOutput, GatewayWalkScheduler, GatewayWalkRequests {
    var now = 1_000L
    private var requestSequence = 0
    private var walkSequence = 0
    val events = mutableListOf<String>()
    val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-${++walkSequence}" }, clock = { now }, elapsedRealtimeClock = { now })
    val authority = GatewayWalkAuthorityController(requestIdFactory = { "request-${++requestSequence}" }, elapsedClock = { now })
    val account = GatewayFieldSession.longLivedSession("https://gateway.example.test", "actor-test", "device-0001", "f".repeat(32), 1L,
        "${GatewayFieldSession.COOKIE_NAME}=${"a".repeat(32)}", "r".repeat(64), Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
    val controller = GatewayWalkSessionController(authority, this, this, this, this, this, 30_000L, "restart", false)
    val background = mutableListOf<() -> Unit>()
    val scheduled = mutableListOf<Pair<Runnable, Long>>()
    val postedDelays = mutableListOf<Long>()
    val stops = mutableListOf<String>()
    val retries = mutableListOf<String>()
    val spoken = mutableListOf<String>()
    val ends = mutableListOf<Triple<GatewayFieldSession, GatewayWalkLease, String>>()
    var readinessOpen = true; var readinessReads = 0; var activations = 0
    var actorCurrent = true; var renewedContinuation = false; var startConflict = false
    var microphones = 0; var microphoneStarts = true; var takeovers = 0; var rejectExecutor = false
    var promptDone: (() -> Unit)? = null; var promptFailed: (() -> Unit)? = null
    var renewError: Throwable? = null; var endError: Throwable? = null
    fun prepare(): WalkSessionConfirmationToken {
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness()))
        return lifecycle.snapshot().confirmationToken!!
    }
    fun readiness(): WalkSessionReadinessSnapshot {
        val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL, checkEntryPermissions = true)
        val collector = WalkSessionReadinessCollector(lifecycle.snapshot().epoch, plan)
        plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(lifecycle.snapshot().epoch, it, WalkSessionReadinessStatus.READY)) }
        return collector.build(now)
    }
    fun active(): WalkSessionConfirmationToken {
        val token = prepare(); controller.requestGatewayWalkStart(token); runBackground(); return token
    }
    fun runBackground() { check(background.isNotEmpty()); background.removeAt(0)() }
    fun lease(walkId: String, deadline: Long = now + 85_000L, fence: Long = 7L) = GatewayWalkLease("ACQUIRED", account.actorId,
        account.deviceId, walkId, "lease-1", fence, 1_000L, 91_000L, 1_000L, deadline)
    override fun snapshot(): WalkSessionSnapshot { events += "snapshot"; return lifecycle.snapshot() }
    override fun currentStartReadinessOrNull(token: WalkSessionConfirmationToken): WalkSessionReadinessSnapshot? {
        readinessReads++; return if (readinessOpen) readiness() else null
    }
    override fun blocksAutomaticStart() = false
    override fun startRequested(token: WalkSessionConfirmationToken, readiness: WalkSessionReadinessSnapshot) { lifecycle.handle(WalkSessionEvent.StartRequested(token, readiness)) }
    override fun isActive() = lifecycle.snapshot().state == WalkSessionState.ACTIVE
    override fun activate(): Boolean { activations++; return true }
    override fun isForeground() = true
    override fun safetyStop(reason: String) { stops += reason; lifecycle.handle(WalkSessionEvent.SafetyStopRequested) }
    override fun refreshReadinessUi() { events += "refresh" }
    override fun observeResourceSafety() { events += "resources" }
    override fun sessionOrNull(reason: String, speak: Boolean) = account
    override fun currentSession() = account
    override fun isCurrent(session: GatewayFieldSession) = actorCurrent && session === account
    override fun isRenewedContinuation(session: GatewayFieldSession) = renewedContinuation
    override fun awaitRenewal(onRenewed: () -> Unit) = false
    override fun probeAfterUnauthorized(session: GatewayFieldSession, onRenewed: () -> Unit) = false
    override fun updateStatus(title: String, detail: String) { events += "status:$title" }
    override fun updateNavigationStatus(detail: String) { events += detail }
    override fun makeStartRetryAvailable(detail: String, spoken: String?) { retries += detail; spoken?.let(this.spoken::add) }
    override fun startTakeoverRecognition(operationId: String): Boolean { microphones++; return microphoneStarts }
    override fun speakTakeover(message: String, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean {
        promptDone = onCompleted; promptFailed = onFailed; return true
    }
    override fun debug(tag: String, message: String) { events += message }
    override fun execute(action: () -> Unit) { if (rejectExecutor) throw RejectedExecutionException(); background += action }
    override fun runOnUiThread(action: () -> Unit) = action()
    override fun runOnUiThread(action: Runnable) = action.run()
    override fun nowMs(): Long { events += "clock"; return now }
    override fun removeCallbacks(runnable: Runnable) { scheduled.removeAll { it.first === runnable } }
    override fun post(runnable: Runnable) = postDelayed(runnable, 0L)
    override fun postDelayed(runnable: Runnable, delayMs: Long) { scheduled += runnable to delayMs; postedDelays += delayMs }
    override fun postDelayed(action: () -> Unit, delayMs: Long) = postDelayed(Runnable(action), delayMs)
    override fun startWithOwnedWalkRecovery(session: GatewayFieldSession, walkId: String, requestId: String,
        nowElapsedMs: () -> Long, isCurrent: () -> Boolean): GatewayWalkStartResult =
        if (startConflict) GatewayWalkStartResult.Conflict(GatewayWalkConflict("previous-walk", "other-device", 7L, 91_000L, 1_000L, now + 85_000L))
        else GatewayWalkStartResult.Granted(lease(walkId))
    override fun takeover(session: GatewayFieldSession, walkId: String, requestId: String,
        conflict: GatewayWalkConflict, localNowElapsedMs: Long): GatewayWalkLease { takeovers++; return lease(walkId, fence = 8L) }
    override fun renew(session: GatewayFieldSession, lease: GatewayWalkLease, requestId: String,
        localNowElapsedMs: Long): GatewayWalkLease { renewError?.let { throw it }; return lease.copy(localDeadlineElapsedMs = lease.localDeadlineElapsedMs + 30_000L) }
    override fun end(session: GatewayFieldSession, lease: GatewayWalkLease, requestId: String) { ends += Triple(session, lease, requestId); endError?.let { throw it } }
}
