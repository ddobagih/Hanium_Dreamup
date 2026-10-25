package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator

/** Main's actual admitted scheduler/runtime-observation reaction plus Navigator/policy/gate.
 * Android callback admission, UI posting and watchdog execution are not run in this JVM fixture. */
class MainActivityObservationRecheckBehaviorTest {
    @After fun releaseSession() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun aNewDirectionSampleDuringRecheckIsEvaluatedBeforeTheOldPeriodicDeadline() {
        val f = CompassFixture()
        f.nav.update(f.fix, f.now, false, facingObservation = null)
        assertTrue(f.nav.isDirectionRecheckPending())
        f.schedule()
        assertEquals(1_300L, f.queued.single().first)
        f.now = 1_050L
        f.facing = RouteFacingObservation(90.0, 3.0, f.now)
        f.schedule(f.now)
        assertEquals("An abnormal episode must consume a new real sample immediately", listOf(1_050L), f.evaluations)
        assertNotNull(f.nav.currentAcceptedRouteMatchFor(1_000L))
        assertNull(f.nav.currentAcceptedRouteMatchFor(1_050L))
    }

    @Test fun healthyDirectionSamplesKeepOneThreeHundredMillisecondEvaluation() {
        val f = CompassFixture()
        f.nav.update(f.fix, f.now, false, facingObservation = f.facing)
        assertFalse(f.nav.isDirectionRecheckPending())
        f.schedule()
        for (at in listOf(1_020L, 1_040L, 1_070L)) {
            f.now = at
            f.facing = RouteFacingObservation(90.0, 3.0, at)
            f.schedule(at)
        }
        assertTrue(f.evaluations.isEmpty())
        assertEquals(1, f.queued.size)
        f.now = 1_300L
        f.queued.single().second.run()
        assertEquals(listOf(1_300L), f.evaluations)
    }

    @Test fun aQueuedCompassEvaluationStillRejectsAReplacedOwner() {
        val f = CompassFixture()
        f.schedule()
        f.ownerCurrent = false
        f.now = 1_300L
        f.queued.single().second.run()
        assertTrue(f.evaluations.isEmpty())
    }

    @Test fun aFreshGpsPassDuringRetainedPassGraceRearmsTheRuntimeWatchdogAtRecovery() {
        val f = GpsFixture()
        val passed = f.observe(3.0)
        f.now = 1_100L
        val retained = f.observe(30.0)
        assertEquals(passed, retained)
        f.react(passed, retained)
        assertEquals(listOf(1_100L), f.scheduled)
        assertEquals(19_100L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = 1_600L
        val recovered = f.observe(3.0)
        assertEquals(f.now, recovered.observedAtElapsedRealtimeMs)
        assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.react(retained, recovered)
        assertEquals("A genuine new PASS must replace the old grace watchdog, even if displayed PASS was retained",
            listOf(1_100L, 1_600L), f.scheduled)
    }

    @Test fun repeatedBadGpsObservationsDoNotExtendTheFirstEighteenSecondDeadline() {
        val f = GpsFixture()
        val passed = f.observe(3.0)
        val observationTimes = listOf(1_100L, 1_600L, 2_100L, 4_099L, 11_000L, 11_001L, 16_000L, 19_099L)
        for (at in observationTimes) {
            f.now = at
            val retained = f.observe(30.0)
            if (at <= 11_000L) {
                assertEquals(passed, retained)
            } else {
                assertEquals("The 18-second recheck cannot renew the original 10-second GPS TTL",
                    EnvironmentEvidenceStatus.FAIL, retained.status)
            }
            f.react(passed, retained)
            assertEquals(19_100L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
            assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING, f.gate.gpsRecoveryStage(at))
        }
        f.now = 19_100L
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.gate.current(f.now)?.status)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED, f.gate.gpsRecoveryStage(f.now))
        assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(observationTimes, f.scheduled)
    }

    @Test fun locationPreparationDoesNotAutomaticallyTellTheUserToStop() {
        val main = MainActivity()
        val pending = kr.co.hanium.dreamup.walksafe.navigation.start.GuidancePreparationController.PendingGuidancePreparationSpeech(1_000L) { true }
        set(main, "pendingGuidancePreparationSpeech", pending)
        set(main, "positionGuidancePaused", true)
        val messages = mutableListOf<String>()
        main.maybeSpeakGuidancePreparationWait(5_000L, true, false,
            submitSpeech = { text, current, _, _ -> assertTrue(current()); messages += text; true },
            locationStillWaiting = { true }, publishFailure = { fail("No automatic wait speech is needed") })
        assertEquals("Measuring location is silent; it is not evidence that the pedestrian must stop", emptyList<String>(), messages)
        assertFalse(pending.delivered)
    }

    @Test fun persistentRuntimeGpsUnavailabilityDescribesTheAppLimitWithoutAssumingDanger() {
        val f = GpsFixture()
        f.observe(3.0)
        f.now = 1_100L
        val unavailable = OfficialEnvironmentPolicy.assessGpsQuality(f.epoch, f.now,
            GpsQualityObservation(f.epoch, f.now, false, null), f.profile)
        val published = f.gate.observe(unavailable, f.now)
        set(f.main, "officialEnvironmentGpsEvidence", published)
        val assessment = OfficialEnvironmentPolicy.assess(f.epoch, f.now, published, null, null, f.profile)
        assertEquals(EnvironmentEvidenceStatus.FAIL, assessment.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
        val speech = f.main.officialEnvironmentFailureSpeech(assessment, setOf(OfficialEnvironmentFactor.GPS_QUALITY))
        assertFalse("A GPS failure does not establish a physical danger: $speech", speech.contains("멈춰"))
        assertEquals("위치: 현재 위치를 확인하지 못했습니다. 새 위치를 기다려 주세요.", speech)
    }

    @Test fun productionEntriesRetainOwnerGuardsAndCallTheExtractedActualBodies() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val activityPorts = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val compassSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteCompassSession.kt").readText()
        val compass = MainActivityRefactorSource.member(compassSource, "scheduleRouteCompassPresentationUpdate")
        val compassPorts = MainActivityRefactorSource.composition(source, "routeCompassSession")
        assertTrue(compassPorts.contains("RouteCompassSession("))
        assertTrue(MainActivityRefactorSource.member(source, "scheduleRouteCompassPresentationUpdate")
            .contains("routeCompassSession.scheduleRouteCompassPresentationUpdate(observation, firstBadAtMs)"))
        val sourceAdmission = ReportStaticSourceInspector.functionBlock(compassPorts, "override fun captureSchedule")
        assertTrue(sourceAdmission.contains("activity.currentCollectionOwner() != owner"))
        assertTrue(sourceAdmission.contains("positioning.value.callbackGeneration == generation"))
        assertTrue(sourceAdmission.contains("positioning.value.isPositioningCollectionCurrent(binding)"))
        assertTrue(compass.contains("scheduleAdmittedRouteCompassUpdate("))
        assertTrue(compass.contains("actions.retry(nowMs)"))
        assertTrue(compassPorts.contains("guidance.value.retryCurrentNavigationSpeechAfterReady(nowMs)"))
        assertTrue(activityPorts.contains("nativeHomeUiPresenter.compassTarget().postDelayed(task, delayMs)"))
        assertTrue(ReportStaticSourceInspector.blockAfter(activityPorts, "authority = { object : CompassActivityAuthority").contains("override fun currentCollectionOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(compassPorts.contains("callbacks = bearings.callbacks()"))
        val publisher = MainActivityRefactorSource.member(source, "recordOfficialEnvironmentGpsObservationAt", occurrence = 1)
        assertTrue(publisher.contains("officialEnvironmentEvidenceStore.recordGpsObservation("))
        assertTrue(publisher.contains("walkSessionLifecycle.snapshot().epoch, epoch, observedAtElapsedRealtimeMs"))
        assertTrue(publisher.contains("trustedFixAvailable, horizontalAccuracyMeters, elapsedRealtimeMs"))
        MainActivityRefactorSource.composition(source, "officialEnvironmentEvidenceStore")
        val evidence = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/OfficialEnvironmentEvidenceStore.kt").readText()
        val gps = MainActivityRefactorSource.member(evidence, "recordGpsObservation")
        assertTrue(gps.contains("if (currentEpoch != epoch) return"))
        assertTrue(gps.contains("OfficialEnvironmentPolicy.assessGpsQuality("))
        assertTrue(gps.contains("gpsQualityGate.observe(rawEvidence, measuredAtMs)"))
        assertTrue(gps.contains("if (walkRuntimeActive()) onRuntimeObservation(epoch, previous, evidence)"))
        assertTrue(publisher.contains("::isWalkSessionRuntimeActive"))
        assertTrue(publisher.contains("reactToAdmittedRuntimeGpsObservation(currentEpoch, previous, current)"))
        assertTrue(MainActivityRefactorSource.member(source, "reactToAdmittedRuntimeGpsObservation")
            .contains("= ::scheduleOfficialEnvironmentRuntimeWatchdog"))
    }

    @Test fun queuedOldRouteBadIsDiscardedEvenAfterTheReplacementHasFreshGuidanceContext() {
        val f = EventFixture()
        f.post(f.bad(1_100L))
        assertEquals(1, f.ui.size)
        f.source.set("routeRequestGeneration", 2)
        f.installRoute(1_200L)
        f.main.bindCompassObservationOwner()
        f.ui.removeAt(0).run()
        assertTrue(f.events.isEmpty())
        assertFalse(f.nav.isDirectionRecheckPending())
        assertNotNull(f.nav.currentAcceptedRouteMatchFor(1_200L))
    }

    @Test fun aQueuedBadCannotCrossAnEpochSessionForegroundOrSdkAdmissionBoundary() {
        for (change in listOf("epoch", "session", "foreground", "sdk", "registration")) {
            val f = EventFixture()
            f.post(f.bad(1_100L))
            when (change) {
                "epoch" -> f.source.lifecycle.handle(WalkSessionEvent.EnteredBackground)
                "session" -> GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
                "foreground" -> f.source.set("isActivityForeground", false)
                "sdk" -> f.sdkCurrent = false
                "registration" -> f.trackerCurrent = false
            }
            f.ui.removeAt(0).run()
            assertTrue(change, f.events.isEmpty())
        }
    }

    @Test fun softBadThenGoodRetainsTheClaimAndARecoveredNewEpisodeGetsItsOwnDeadline() {
        val f = EventFixture()
        val bad = f.bad(1_100L)
        f.post(bad)
        f.post(f.good(1_150L))
        assertEquals(1, f.ui.size)
        f.source.now = 1_200L
        f.ui.removeAt(0).run()
        assertEquals(listOf("bad:1100", "sample:1150:1100"), f.events)
        val firstFault = requireNotNull(f.faultGraces.single())
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, firstFault.mode)
        assertEquals(1_100L, firstFault.onsetElapsedRealtimeMs)
        assertEquals(10_100L, firstFault.deadlineElapsedRealtimeMs)
        assertEquals(1_000L, firstFault.retainedObservedAtElapsedRealtimeMs)
        assertFalse("Same source with no source-recovery episode can confirm its prior clock on the fresh GOOD",
            f.nav.isDirectionRecheckPending())
        assertEquals(RouteDirectionGraceMode.NORMAL, f.nav.currentDirectionGraceSnapshot()?.mode)
        assertEquals(1_150L, f.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
        f.source.now = 1_450L
        f.post(f.good(1_450L))
        assertTrue("Healthy high-rate samples leave evaluation on the normal 300 ms cadence", f.ui.isEmpty())
        f.source.now = 1_500L
        f.post(f.bad(1_500L)); f.ui.removeAt(0).run()
        assertEquals("sample:1500:1500", f.events.last())
        assertFalse("A second soft fault retains its eligible direction without cancelling speech", "cancel" in f.events)
        assertEquals(1_500L, f.nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
        assertEquals(10_500L, f.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        val early = requireNotNull(f.nav.retryGuidance(10_499L, null))
        assertTrue(early.instruction.orEmpty(), early.instruction.orEmpty().contains("약 12시 방향"))
        assertEquals(RouteFacingExpression.CLOCK, early.facingDiagnostic?.expression)
        val retainedCue = requireNotNull(early.speechCueToken)
        assertTrue(retainedCue.requiresFacingEvidence)
        assertEquals(1_150L, retainedCue.facingObservedAtElapsedRealtimeMs)
        assertEquals(1_650L, retainedCue.facingValidUntilElapsedRealtimeMs)
        assertEquals(10_500L, retainedCue.directionGraceDeadlineElapsedRealtimeMs)
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, f.nav.currentDirectionGraceSnapshot()?.mode)
        assertEquals(10_500L, f.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
        val expired = requireNotNull(f.nav.retryGuidance(10_500L, null))
        assertNotNull(expired.instruction)
        assertEquals(RouteFacingExpression.UNKNOWN, expired.facingDiagnostic?.expression)
        assertFalse(requireNotNull(expired.speechCueToken).requiresFacingEvidence)
        assertFalse(expired.instruction.orEmpty(), Regex("""약\s*\d+시""").containsMatchIn(expired.instruction.orEmpty()))
        assertEquals(RouteDirectionGraceMode.PROBLEM, f.nav.currentDirectionGraceSnapshot()?.mode)
        assertEquals(10_500L, f.nav.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
    }

    @Test fun aNonnullPrecisionLossFromThePublisherRetainsPlayingClockBeforeACoalescedGood() {
        assertNonnullFaultConsumedBeforeGood(90.0, 20.0, hardFault = false)
    }

    @Test fun aNonnullReversalFromThePublisherRevokesPlayingClockBeforeACoalescedGood() {
        assertNonnullFaultConsumedBeforeGood(270.0, 3.0, hardFault = true)
    }

    private fun assertNonnullFaultConsumedBeforeGood(degrees: Double, accuracy: Double, hardFault: Boolean) {
        val f = EventFixture()
        val playing = f.offered
        assertTrue(playing.instruction.orEmpty().contains("12시 방향"))
        assertTrue(f.nav.reserveInstruction(playing))
        val publisher = RouteCompassObservationPublisher().apply { reset(3) }
        val fault = requireNotNull(publisher.sample(3, RouteCompassStream.ROTATION_VECTOR,
            1_100_000_000L, 1_100_000_000L,
            RouteCompassHeadingResult(RouteFacingObservation(degrees, accuracy, 1_100L), RouteCompassHeadingReason.VALID)))
        assertFalse("Tracker source-quality admission is not route-clock claim admission", fault.invalidated)
        assertNotNull(fault.heading.observation)
        assertEquals(hardFault, f.nav.hasHardInvalidatedDirectionClaim(fault.receivedAtMs, fault.heading.observation))
        f.source.now = 1_100L
        f.post(fault)
        assertEquals("A nonnull fault must be evaluated before the coalesced GOOD", 1, f.ui.size)
        f.source.now = 1_150L
        f.post(requireNotNull(publisher.sample(3, RouteCompassStream.ROTATION_VECTOR,
            1_150_000_000L, 1_150_000_000L, f.good(1_150L).heading)))
        assertEquals(1, f.ui.size)
        f.source.now = 1_200L
        f.ui.removeAt(0).run()
        if (hardFault) {
            assertEquals(listOf("cancel", "bad:1100", "sample:1150:1100"), f.events)
            assertFalse("The earlier action claim cannot return after coalescing", f.nav.reserveInstruction(playing))
        } else {
            assertEquals(listOf("bad:1100", "sample:1150:1100"), f.events)
            val retained = requireNotNull(f.faultGraces.single())
            assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, retained.mode)
            assertEquals(1_100L, retained.onsetElapsedRealtimeMs)
            assertEquals(10_100L, retained.deadlineElapsedRealtimeMs)
            assertEquals(1_000L, retained.retainedObservedAtElapsedRealtimeMs)
            assertFalse(f.nav.isDirectionRecheckPending())
            assertEquals(1_150L, f.nav.currentDirectionGraceSnapshot()?.retainedObservedAtElapsedRealtimeMs)
            assertTrue("The same playing action survives the short precision loss",
                f.nav.isInstructionCurrent(playing, 1_200L, f.good(1_150L).heading.observation))
            assertTrue(f.nav.isInstructionCompletionCurrent(playing, 1_200L))
            assertEquals(1_000L, playing.speechCueToken?.facingObservedAtElapsedRealtimeMs)
            assertEquals(1_500L, playing.speechCueToken?.facingValidUntilElapsedRealtimeMs)
            assertFalse("Retention must not reserve a second copy of the playing action", f.nav.reserveInstruction(playing))
        }
    }

    @Test fun oldPassingEvidenceExpiresBeforeTheSingleOutlierGraceIfItsOriginalTtlEndsFirst() {
        val f = GpsFixture()
        val original = f.observe(3.0)
        f.now = 10_900L
        val refused = OfficialEnvironmentPolicy.assessGpsQuality(f.epoch, f.now,
            GpsQualityObservation(f.epoch, f.now, false, null), f.profile)
            .copy(detail = "GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE")
        assertEquals(original, f.gate.observe(refused, f.now))
        assertEquals(28_900L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(original, f.gate.current(11_000L))
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.gate.current(11_001L)?.status)
        assertEquals(10_900L, f.gate.latestRawObservation()?.observedAtElapsedRealtimeMs)
    }

    @Test fun sdkFreshRequestAndCameraDiagnosticAreWiredWithoutRetaggingOrProxyOcclusion() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val activityPorts = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val sdk = MainActivityRefactorSource.member(source, "requestFreshGpsLocation")
        assertTrue(sdk.contains("setMaxUpdateAgeMillis(0L)"))
        assertTrue(sdk.contains("setDurationMillis(3_000L)"))
        assertTrue(sdk.contains("cancellation.token"))
        val freshRequest = delegatedOwner("requestFreshGpsForCurrentSource", "continuousGpsSession", "navigation/session/ContinuousGpsSession.kt", "requestFreshGpsForCurrentSource", "= continuousGpsSession.requestFreshGpsForCurrentSource(elapsedRealtimeMs, requestFresh, consumeFresh)")
        assertTrue(freshRequest.contains("requestFresh: ((android.location.Location?) -> Unit) -> (() -> Unit) = actions::requestFresh"))
        assertTrue(freshRequest.contains("consumeFresh(location, elapsedRealtimeMs())"))
        val gpsPorts = MainActivityRefactorSource.composition(source, "continuousGpsSession")
        assertTrue(gpsPorts.contains("override fun requestFresh(result: (Location?) -> Unit) = gpsBindings.requestFresh(result)"))
        assertTrue(gpsPorts.contains("consumeFreshGpsLocation(location, receivedAtMs)"))
        assertTrue(activityPorts.contains("requestFresh = ::requestFreshGpsLocation"))
        val consume = MainActivityRefactorSource.member(ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/NavigationSessionComposition.kt"), "consumeFreshGpsLocation")
        assertTrue(MainActivityRefactorSource.member(source, "consumeFreshGpsLocation").contains("navigationSessions.consumeFreshGpsLocation(location, receivedAtMs)"))
        assertTrue(consume.contains("val binding = source.binding as PositioningCollectionBinding"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(consume,
            "val source = currentContinuousGpsSourceOrNull() ?: return", "if (gps.value.owner != source) return",
            "val binding = source.binding as PositioningCollectionBinding", "val owner = binding.owner",
            "val generation = binding.locationGeneration",
            "positioning.value.handleLocationUpdate(location, owner, generation, binding, receivedAtMs)"))
        assertFalse(consume.contains("location.elapsedRealtimeNanos ="))
        assertFalse(freshRequest.contains("runtimeGpsQualityGate.reset()"))
        assertFalse(sdk.contains("location.elapsedRealtimeNanos ="))
        assertFalse(sdk.contains("runtimeGpsQualityGate.reset()"))
        val observationFactory = File("src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/AndroidOfficialEnvironmentPreflightSources.kt").readText()
        val observation = MainActivityRefactorSource.member(observationFactory, "build")
        assertTrue(MainActivityRefactorSource.member(source, "cameraFrameQualityObservation")
            .contains("OfficialEnvironmentCameraObservationFactory.build("))
        assertTrue(observation.contains("occludedFraction = null,"))
        assertTrue(observation.contains("nearBlackFraction = luma?.nearBlackFraction"))
        assertFalse(source.contains("밝기·렌즈·정면 각도 기준을 통과"))
        val guidance = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()
        val instruction = MainActivityRefactorSource.member(guidance, "tryDispatchRouteInstruction")
        assertTrue(instruction.contains("!compassInput.invalidationPending && compassInput.revision == compassRevision"))
        assertTrue(MainActivityRefactorSource.member(source, "tryDispatchRouteInstruction")
            .contains("routeGuidanceController.tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)"))
        val compassReader = ReportStaticSourceInspector.blockAfter(
            MainActivityRefactorSource.composition(source, "routeGuidanceController"),
            "compassInput = object : RouteGuidanceController.CompassReader")
        assertTrue(compassReader.contains("override val revision get() = compass.value.revision"))
        assertTrue(compassReader.contains("override val invalidationPending get() = compass.value.invalidationPending"))
    }

    private class EventFixture {
        val source = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val main get() = source.main
        val nav get() = field(main, "routeNavigator") as RouteNavigator
        val ui = mutableListOf<Runnable>()
        val events = mutableListOf<String>()
        val faultGraces = mutableListOf<RouteDirectionGraceSnapshot?>()
        var sdkCurrent = true
        var trackerCurrent = true
        lateinit var offered: RouteNavigatorUpdate
        init {
            MainActivityControllerTestFixture.bindRouteGuidanceClock(main) { source.now }
            source.seedPassingMeasurement()
            source.set("orientationLocationOwnerActive", true)
            installRoute(1_000L)
            main.bindCompassObservationOwner()
        }
        fun installRoute(at: Long) {
            val fix = TrustedLocation(0.0, 0.0, 2f, at)
            val end = RoutePoint(0.0, 0.001)
            main.installRequestedRoute(WalkingRoute("TEST", WalkingRouteSummary(111, 111),
                listOf(RoutePoint(0.0, 0.0), end), emptyList()), end, fix, null, "user_destination")
            offered = nav.update(fix, at, false, facingObservation = RouteFacingObservation(90.0, 3.0, at))
        }
        fun bad(at: Long) = RouteCompassObservationEvent(3, RouteCompassStream.ROTATION_VECTOR,
            at * 1_000_000L, at, RouteCompassHeadingResult(null, RouteCompassHeadingReason.QUALITY_LOW), true)
        fun good(at: Long) = RouteCompassObservationEvent(3, RouteCompassStream.ROTATION_VECTOR,
            at * 1_000_000L, at, RouteCompassHeadingResult(RouteFacingObservation(90.0, 3.0, at), RouteCompassHeadingReason.VALID), false)
        fun post(event: RouteCompassObservationEvent) = main.onRouteCompassObservation(event,
            trackerCurrent = { trackerCurrent }, sdkSourceCurrent = { sdkCurrent }, post = { ui += it },
            cancelSpeech = { events += "cancel" },
            invalidateClaim = { fault ->
                events += "bad:${fault.receivedAtMs}"
                main.retryAdmittedNavigationSpeech(source.now, source.epoch, fault.heading, fault.receivedAtMs) { _, _, _ -> }
                faultGraces += nav.currentDirectionGraceSnapshot()
            },
            schedule = { sample, firstBad ->
                events += "sample:${sample.receivedAtMs}:$firstBad"
                main.retryAdmittedNavigationSpeech(source.now, source.epoch, sample.heading, firstBad) { _, _, _ -> }
            })
    }

    private class CompassFixture {
        val main = MainActivity()
        val nav = field(main, "routeNavigator") as RouteNavigator
        var now = 1_000L
        var ownerCurrent = true
        val fix = TrustedLocation(0.0, 0.0, 2f, now)
        var facing: RouteFacingObservation? = RouteFacingObservation(90.0, 3.0, now)
        val queued = mutableListOf<Pair<Long, Runnable>>()
        val evaluations = mutableListOf<Long>()
        val epoch = WalkRuntimeEpoch("event-compass", 0L)
        init {
            MainActivityControllerTestFixture.bindRouteGuidanceClock(main) { now }
            val end = RoutePoint(0.0, 0.001)
            main.installRequestedRoute(WalkingRoute("TEST", WalkingRouteSummary(111, 111),
                listOf(RoutePoint(0.0, 0.0), end), emptyList()), end, fix, null, "user_destination")
        }
        fun schedule(observedAt: Long? = null) = main.scheduleAdmittedRouteCompassUpdate(
            observedAtMs = observedAt,
            isCurrent = { ownerCurrent },
            evaluate = {
                evaluations += now
                main.retryAdmittedNavigationSpeech(now, epoch,
                    RouteCompassHeadingResult(facing, if (facing == null) RouteCompassHeadingReason.ORIENTATION_MISSING else RouteCompassHeadingReason.VALID)) { _, _, _ -> }
            },
            postDelayed = { callback, delay -> queued += now + delay to callback; true },
        )
    }

    private class GpsFixture {
        val main = MainActivity()
        var now = 1_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "event-gps" }, clock = { now }, elapsedRealtimeClock = { now })
        val epoch: WalkRuntimeEpoch
        val profile = requireNotNull(WalkSafeEnvironmentProfiles.active(BuildConfig.DEBUG)).officialEnvironment
        val gate = field(main, "runtimeGpsQualityGate") as RuntimeMeasuredQualityGate
        val scheduled = mutableListOf<Long>()
        init {
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            epoch = lifecycle.snapshot().epoch
            val collector = WalkSessionReadinessCollector(epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertTrue(lifecycle.isRuntimeEpochCurrent(epoch))
            set(main, "walkSessionLifecycle", lifecycle)
        }
        fun observe(accuracy: Double) = gate.observe(OfficialEnvironmentPolicy.assessGpsQuality(epoch, now,
            GpsQualityObservation(epoch, now, true, accuracy), profile), now)
        fun react(previous: MeasuredEnvironmentEvidence?, evidence: MeasuredEnvironmentEvidence) =
            main.reactToAdmittedRuntimeGpsObservation(epoch, previous, evidence,
                reassess = { scheduled += now }, scheduleWatchdog = { assertEquals(epoch, it); scheduled += now })
    }

    companion object {
        private fun field(main: MainActivity, name: String) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        private fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
    }

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}
