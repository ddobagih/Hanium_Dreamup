package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Real Navigator and controller; SDK admission, UI scheduling and speech terminals are explicit ports. */
class RouteGuidanceControllerTest {
    @Test fun arrivalAcceptanceWaitsForOneTerminalAndRetiresItsTimeout() {
        for (failure in listOf(false, true)) {
            val f = GuidanceFixture(); f.captureStatus = true
            var finishes = 0
            val owner = object : RouteGuidanceController.ArrivalCompletion {
                override fun isCurrent() = true
                override fun finish() { finishes++ }
            }
            f.controller.completeArrivalAfterSpeech(owner)
            assertEquals(0, finishes)
            val spoken = f.statusSent.single()
            assertEquals("도착했습니다. 안내를 종료합니다", spoken.message)
            assertEquals(RouteGuidanceController.ARRIVAL_COMPLETION_TIMEOUT_MS + f.now, f.pending.single().first)
            if (failure) spoken.failed() else spoken.done()
            spoken.done(); spoken.failed()
            assertEquals(1, finishes)
            assertTrue(f.pending.isEmpty())
        }
    }

    @Test fun arrivalRejectionAndTimeoutEachFinishOnceWithoutASecondUtterance() {
        val rejected = GuidanceFixture(); var rejectedFinishes = 0
        rejected.controller.completeArrivalAfterSpeech(object : RouteGuidanceController.ArrivalCompletion {
            override fun isCurrent() = true
            override fun finish() { rejectedFinishes++ }
        }, { _, _, _, _ -> false })
        assertEquals(1, rejectedFinishes)
        assertTrue(rejected.pending.isEmpty())

        val f = GuidanceFixture(); f.captureStatus = true; var timedOut = 0
        f.controller.completeArrivalAfterSpeech(object : RouteGuidanceController.ArrivalCompletion {
            override fun isCurrent() = true
            override fun finish() { timedOut++ }
        })
        val old = f.statusSent.single()
        f.pending.single().second.run()
        old.done(); old.failed()
        assertEquals(1, timedOut)
        assertEquals(listOf(0L), f.failedCommands)
        assertEquals(1, f.statusSent.size)
    }

    @Test fun backgroundRetiresArrivalBeforeEpochChangesWithoutCancellingNewerSpeech() {
        val f = GuidanceFixture(); f.captureStatus = true
        f.lifecycle.handle(WalkSessionEvent.RecheckRequested)
        val paused = f.lifecycle.snapshot().epoch
        var finishes = 0
        f.controller.completeArrivalAfterSpeech(object : RouteGuidanceController.ArrivalCompletion {
            override fun isCurrent() = f.lifecycle.snapshot().let { it.epoch == paused && it.state == WalkSessionState.PAUSED }
            override fun finish() { finishes++; f.lifecycle.handle(WalkSessionEvent.EndRequested) }
        })
        val old = f.statusSent.single()
        f.commandGeneration++ // An onRetired callback may still be queued behind this replacement.
        f.controller.retireArrivalForExit()
        f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        old.failed(); old.done(); f.controller.retireArrivalForExit()
        assertEquals(1, finishes)
        assertEquals(WalkSessionState.ENDED, f.lifecycle.snapshot().state)
        assertTrue("A newer response cannot be cancelled", f.failedCommands.isEmpty())
        assertTrue(f.pending.isEmpty())
    }

    @Test fun staleArrivalTerminalCannotEndOrCancelTheReplacementOwner() {
        val f = GuidanceFixture(); f.captureStatus = true
        var current = true; var oldFinishes = 0; var newFinishes = 0
        f.controller.completeArrivalAfterSpeech(object : RouteGuidanceController.ArrivalCompletion {
            override fun isCurrent() = current
            override fun finish() { oldFinishes++ }
        })
        val old = f.statusSent.single(); val oldTimeout = f.pending.single().second
        current = false; f.commandGeneration++
        f.controller.completeArrivalAfterSpeech(object : RouteGuidanceController.ArrivalCompletion {
            override fun isCurrent() = true
            override fun finish() { newFinishes++ }
        })
        oldTimeout.run(); old.done(); old.failed()
        assertEquals(0, oldFinishes); assertEquals(0, newFinishes)
        assertTrue(f.failedCommands.isEmpty())
        f.statusSent.last().done()
        assertEquals(1, newFinishes)
    }

    @Test fun constructionDoesNotReadScopeOrStartSpeech() {
        val f = GuidanceFixture()
        assertEquals(0, f.scopeReads)
        assertTrue(f.sent.isEmpty())
        assertTrue(f.pending.isEmpty())
    }

    @Test fun actualCueIsReservedAndOnlyActualDoneAcknowledgesItsSpeech() {
        val f = GuidanceFixture()
        f.install()
        f.controller.updateRouteGuidance(f.fix)
        val speech = f.sent.single()
        assertTrue(speech.message, speech.message.contains("약 12시 방향"))
        assertTrue(speech.current())
        assertTrue(f.lastResults.isEmpty())
        assertEquals("guidance_in_flight", f.nav.retryGuidance(f.now, f.heading.observation)?.reason)
        f.now += 100L
        f.heading = f.heading.copy(observation = f.heading.observation!!.copy(observedAtElapsedRealtimeMs = f.now))
        speech.done()
        assertEquals(listOf(speech.message), f.lastResults)
        assertEquals(1, f.pending.size)
        assertEquals("guidance_rate_limited", f.nav.retryGuidance(f.now, f.heading.observation)?.reason)
    }

    @Test fun delayedRetryCannotCrossRequestOrFeedbackOwnership() {
        for (change in listOf<(GuidanceFixture) -> Unit>({ it.requestGeneration++ }, { it.feedbackCurrent = false })) {
            val f = GuidanceFixture(); f.install()
            f.controller.scheduleNavigationSpeechRetry()
            val retry = f.pending.single().second
            change(f); f.now += 250L; retry.run()
            assertTrue(f.sent.isEmpty())
            assertNull(f.nav.currentAcceptedRouteMatchFor(f.now))
        }
    }

    @Test fun nativeStartAndDoneCannotUseAReplacementPositioningCollection() {
        val f=GuidanceFixture();f.install();f.controller.updateRouteGuidance(f.fix)
        val old=f.sent.single();assertTrue(old.current())
        f.positioningGeneration++ // A new collection is valid, but it does not own this packet.
        assertTrue(f.controller.isRouteSpeechCurrent(f.epoch,f.requestGeneration,1))
        assertFalse("Old START must recheck its captured positioning owner",old.current())
        old.done()
        assertTrue("Old DONE must not acknowledge a cue for a replacement owner",f.lastResults.isEmpty())
    }

    @Test fun noCurrentPositioningCollectionCannotDispatchANavigationCue() {
        val f=GuidanceFixture();f.install();f.positioningAvailable=false
        f.controller.updateRouteGuidance(f.fix)
        assertTrue(f.sent.isEmpty())
        assertFalse(f.controller.isRouteSpeechCurrent(f.epoch,f.requestGeneration,1))
    }

    @Test fun confidenceQueueAcceptanceDoesNotMarkTheAnnouncementDelivered() {
        val f = GuidanceFixture(); var delivered = 0; var terminal: (() -> Unit)? = null; var current = true
        assertTrue(f.controller.submitPositionConfidenceAnnouncement("위치 안내", { current },
            { _, validator, done -> assertTrue(validator()); terminal = done; true }) { delivered++ })
        assertEquals(0, delivered)
        terminal!!.invoke(); terminal!!.invoke(); assertEquals(1, delivered)
        f.controller.submitPositionConfidenceAnnouncement("새 소유자", { current },
            { _, _, done -> terminal = done; true }) { delivered++ }
        current = false; terminal!!.invoke(); assertEquals(1, delivered)
    }

    @Test fun providerFailurePauseStillBlocksActualRetryWithoutRefreshingPosition() {
        val f = GuidanceFixture(); f.install(); f.nav.update(f.fix, f.now, false, facingObservation = f.heading.observation)
        f.run.pauseDirection("tmap_unavailable")
        f.controller.retryAdmittedNavigationSpeech(f.now, f.epoch, f.heading) { _, _, _ -> fail("paused route dispatched") }
        assertNotNull(f.nav.currentAcceptedRouteMatchFor(f.fix.elapsedRealtimeMs))
        assertEquals("tmap_unavailable", f.run.directionPauseReason)
    }
}

internal class GuidanceFixture(
    suppliedLifecycle: WalkSessionLifecycle? = null,
    suppliedRun: NavigationRunState? = null,
) {
    var now = 1_000L
    var scopeReads = 0
    var requestGeneration = 1
    var feedbackCurrent = true
    var feedbackGeneration = 1
    var navigationAllowedReader: () -> Boolean = { true }
    var revision = 0L
    var invalidation = false
    var validatingStarted = false
    var commandGeneration = 0L
    val failedCommands = mutableListOf<Long>()
    var heading = RouteCompassHeadingResult(RouteFacingObservation(90.0, 3.0, now), RouteCompassHeadingReason.VALID)
    var compassPeer: RouteGuidanceController.CompassReader? = null
    val lifecycle = suppliedLifecycle ?: WalkSessionLifecycle({ "guidance-private" }, { now }, { now }).apply {
        handle(WalkSessionEvent.EnteredForeground)
        val collector = WalkSessionReadinessCollector(snapshot().epoch,
            WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
        collector.plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(
            snapshot().epoch, it, WalkSessionReadinessStatus.READY)) }
        handle(WalkSessionEvent.InitialCheckCompleted(collector.build(now)))
        handle(WalkSessionEvent.StartRequested(requireNotNull(snapshot().confirmationToken)))
    }
    val epoch get() = lifecycle.snapshot().epoch
    val nav = suppliedRun?.navigator ?: RouteNavigator(nowElapsedRealtimeMs = { now })
    val run = suppliedRun ?: NavigationRunState(nav)
    val fix = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, now) }
    val pending = mutableListOf<Pair<Long, Runnable>>()
    val sent = mutableListOf<Submission>()
    val statusSent = mutableListOf<Submission>()
    var confidenceLease: RouteGuidanceController.ConfidenceLease? = null
    var captureStatus = false
    var beginArrival: (RouteNavigatorDecisionToken) -> RouteGuidanceController.ArrivalCompletion? = { null }
    var positioningGeneration = 0
    var positioningAvailable = true
    var steps = 0
    var unavailableMessage: () -> String = { "위치 정보를 받지 못했습니다." }
    val lastResults = mutableListOf<String>()
    val events = mutableListOf<String>()
    val diagnostics = object : RouteGuidanceController.Diagnostics {
        override val debug = false
        override val recording = false
        override fun log(tag: String, message: String) = Unit
        override fun record(event: String, values: Map<String, Any?>) = Unit
        override fun confidenceLine(snapshot: PositioningSnapshot) = ""
    }
    data class Submission(val message: String, val current: () -> Boolean, val done: () -> Unit, val failed: () -> Unit)
    val controller = RouteGuidanceController(run,
        object : RouteGuidanceController.ScopeReader {
            override val lifecycle get() = this@GuidanceFixture.lifecycle.also { scopeReads++ }
            override val foreground get() = true.also { scopeReads++ }
            override val runtimeActive get() = lifecycle.currentRuntimeEpochOrNull() != null
            override val navigationAllowed get() = navigationAllowedReader().also { scopeReads++ }
            override val requestPending get() = false.also { scopeReads++ }
            override val requestGeneration get() = this@GuidanceFixture.requestGeneration
            override val feedbackGeneration get() = this@GuidanceFixture.feedbackGeneration
            override val developmentBypass get() = false
            override fun feedbackCurrent(generation: Int) = feedbackCurrent && generation == feedbackGeneration
        }, object : RouteGuidanceController.PositionReader {
            override val latestTrusted get() = fix
            override val latestRaw get() = fix
            override val covariance: Pair<TrustedLocation, EnuPositionCovariance>? get() = null
            override val course: HeadingObservation? get() = null
            override val speed: WalkingSpeedObservation? get() = null
            override val stepCount get() = steps
            override val stepLengthM = .7f
            override fun currentLocation() = fix
            override fun confidenceLeaseOrNull(token: Long): RouteGuidanceController.ConfidenceLease? = confidenceLease
        }, object : RouteGuidanceController.CompassReader {
            override val revision get() = compassPeer?.revision ?: this@GuidanceFixture.revision
            override val invalidationPending get() = compassPeer?.invalidationPending ?: invalidation
            override fun heading(nowMs: Long) = compassPeer?.heading(nowMs) ?: heading
            override fun facing(nowMs: Long) = heading(nowMs)?.observation
        }, object : RouteGuidanceController.SpeechPort {
            override val commandGeneration get() = this@GuidanceFixture.commandGeneration
            override val commandPending = false
            override val validatingStarted get() = this@GuidanceFixture.validatingStarted
            override val statusText: String? = null
            override fun busy() = false
            override fun outputBusy() = false
            override fun inputActive() = false
            override fun canSpeakNavigation(nowMs: Long) = true
            override fun cancelNavigation() { events += "cancel" }
            override fun cancelCommand() = Unit
            override fun failCommand(generation: Long) { failedCommands += generation }
            override fun submitNavigation(message: String, isInstructionStillCurrent: () -> Boolean,
                onCompleted: () -> Unit, onFailed: () -> Unit): Boolean {
                sent += Submission(message, isInstructionStillCurrent, onCompleted, onFailed); return true
            }
            override fun submitStatus(message: String, current: () -> Boolean, completed: () -> Unit,
                failed: (() -> Unit)?): Boolean {
                check(captureStatus) { "Unexpected status speech" }
                statusSent += Submission(message, current, completed, { failed?.invoke() })
                return true
            }
            override fun submitInstalledStart(message: String, current: () -> Boolean, terminal: () -> Unit): Boolean = error("No start owner")
        }, object : RouteGuidanceController.Effects {
            override fun beginArrival(token: RouteNavigatorDecisionToken) = this@GuidanceFixture.beginArrival(token)
            override fun navigationStatus(text: String) = Unit
            override fun interaction(message: String) = Unit
            override fun pauseDirection(reason: String, message: String) { run.pauseDirection(reason) }
            override fun decisionActions(decision: RouteNavigatorUserDecision?) = Unit
            override fun progressBeep(nowMs: Long, offRoute: Boolean, arrived: Boolean) = Unit
            override fun pausedHaptic() = Unit
            override fun deviationHaptic() = Unit
            override fun preparationStage(stage: String) = Unit
            override fun clearWaitingFailure(current: () -> Boolean) = Unit
            override fun showLastResult(message: String) { lastResults += message }
            override fun unavailableLocationMessage() = unavailableMessage()
        }, diagnostics, InstalledRouteStartSpeechCoordinator(), object : RouteCallbackScheduler {
            override fun post(task: Runnable) { pending += now to task }
            override fun postDelayed(task: Runnable, delayMs: Long) { pending += now + delayMs to task }
            override fun remove(task: Runnable) { pending.removeAll { it.second === task } }
        }, { now }, { true }, { it() }, {
            val capturedGeneration=positioningGeneration
            val current:()->Boolean={positioningAvailable&&positioningGeneration==capturedGeneration}
            current.takeIf{positioningAvailable}
        })
    fun install() {
        nav.setRoute(WalkingRoute("TEST", WalkingRouteSummary(200, 200), listOf(point(0.0), point(200.0)),
            listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 11))))
        run.markRequestActive(); run.selectDestination(point(200.0))
    }
    private fun point(eastM: Double) = RoutePoint(37.0, 127.0 + eastM / 88_804.0)
}
