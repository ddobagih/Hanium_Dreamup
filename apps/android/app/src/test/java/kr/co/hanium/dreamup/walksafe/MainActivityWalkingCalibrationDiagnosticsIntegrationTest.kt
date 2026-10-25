package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.runtime.vision.capture.FrameMotionEvidenceSource
import kotlin.math.cos
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import org.junit.Assert.*
import org.junit.Test

/**
 * Real Main profile/receipt/diagnostic consumption with controlled motion inputs. Exact private
 * method lookup avoids loading unrelated GL signatures. The step producer and UI publication
 * connections are SOURCE checks; no sensor callback, Handler, TextView or native output runs here.
 */
class MainActivityWalkingCalibrationDiagnosticsIntegrationTest {
    @Test
    fun restoredCalibratedProfileIsNotRelabeledUncalibratedByANewWarmup() {
        val previous = PositioningCoordinator()
        repeat(3) { previous.calibrateProfile(sample()) }
        val persisted = requireNotNull(previous.snapshotProfileForPersistence())
        val restored = PositioningCoordinator()
        assertTrue(restored.restoreProfile(persisted))
        val result = restored.calibrateProfile(sample())
        assertEquals(MotionCalibrationStatus.ACCEPTED_WARMUP, result.status)
        assertTrue(result.snapshot.isCalibrated)
        assertEquals(result.snapshot, restored.currentProfile())
        val statuses = mutableListOf<String>()
        var applied = 0
        var persistedCalls = 0

        // This pre-existing Main body/API is executable unchanged on the original Main13c.
        MainActivity().applyWalkingCalibrationResult(result,
            { restored.currentProfile().stepLengthM.toFloat() }, { applied++ },
            { persistedCalls++ }, { statuses += it })

        assertEquals(1, applied)
        assertEquals(1, persistedCalls)
        assertEquals(1, statuses.size)
        assertFalse("A restored calibrated profile remains calibrated during window warmup",
            statuses.single().contains("uncalibrated"))
        assertTrue(statuses.single().contains("step_length_updated"))
    }

    @Test
    fun actualMainFixConsumptionDistinguishesNoSampleWaitingAndRejectedWithoutResettingCounts() {
        val main = mainWithClock(nowMs = 20_000L)
        synchronizeScope(main, null, null)
        val profile = coordinator(main).currentProfile()
        assertTrue(status(main).contains("보정이 아직 완료되지 않아 기본 보폭"))
        assertTrue(status(main).contains("걸음과 위치 정보를 기다립니다"))

        val pending = main.observeWalkingCalibrationFix(fix(0), moving(1_100L), 1_100L)
        assertTrue(pending.isEmpty())
        assertEquals(WalkingCalibrationReason.WAITING_FOR_STEP_WATERMARK,
            collector(main).diagnosticSnapshot().latestDecision?.reason)
        assertTrue(status(main).contains("보정 대기"))

        val rejected = main.observeWalkingCalibrationFix(fix(1).copy(horizontalAccuracyM = 4.0),
            moving(2_100L), 2_100L)
        assertTrue(rejected.isEmpty())
        val diagnostic = collector(main).diagnosticSnapshot()
        assertEquals(WalkingCalibrationOutcome.REJECTED, diagnostic.latestDecision?.outcome)
        assertEquals(WalkingCalibrationReason.UNSUITABLE_FIX_ACCURACY, diagnostic.latestDecision?.reason)
        assertTrue(status(main).contains("위치 정보의 신뢰가 부족"))
        assertFalse(status(main).contains("보정 대기"))
        collector(main).reset() // Same history-only reset used by invalid/raw-missing callbacks.
        assertEquals(diagnostic, collector(main).diagnosticSnapshot())
        assertEquals(profile, coordinator(main).currentProfile())
        assertTrue(diagnostic.decisionCounts.keys.any { it.outcome == WalkingCalibrationOutcome.WAITING })
    }

    @Test
    fun realStepRejectionUsesTheOriginalSourceAndConfidenceWithoutCallingCounterOnlyHardware() {
        val main = MainActivity()
        val profile = coordinator(main).currentProfile()
        val steps = TimestampAlignedWalkingCalibration()
        val lowDetector = StepEvent(1, 1_000L, StepEventSource.STEP_DETECTOR, StepEventConfidence.LOW)
        assertTrue(steps.onStep(lowDetector.timestampMs, lowDetector.deltaSteps, false, 1_010L).isEmpty())
        val rejected = steps.diagnosticSnapshot()
        assertEquals(WalkingCalibrationReason.STEP_NOT_INDIVIDUAL, rejected.latestDecision?.reason)
        val lowMessage = diagnosticMessage(main, profile, rejected, lowDetector)
        assertTrue(lowMessage.contains("신뢰가 낮아"))
        assertFalse(lowMessage.contains("개별 걸음 시각이 확인되지 않아"))

        val counter = StepEvent(4, 2_000L, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM)
        assertTrue(steps.onStep(counter.timestampMs, counter.deltaSteps, false, 2_010L).isEmpty())
        val counterMessage = diagnosticMessage(main, profile, steps.diagnosticSnapshot(), counter)
        assertTrue(counterMessage.contains("개별 걸음 시각이 확인되지 않아"))
        assertFalse(counterMessage.contains("Counter"))
        assertFalse(counterMessage.contains("전용 기기"))
        assertEquals(2L, steps.diagnosticSnapshot().decisionCounts[rejected.latestDecision])
        assertEquals(profile, coordinator(main).currentProfile())
    }

    @Test
    fun alignedProductionInputsReachMainDiagnosticsWhileFirstValidSampleStillUsesTheDefaultProfile() {
        val main = mainWithClock(nowMs = 20_000L)
        synchronizeScope(main, null, null)
        data class Delivery(val receivedAt: Long, val stepAt: Long? = null, val raw: RawWalkingCalibrationFix? = null)
        val events = (750L..12_750L step 500L).map { Delivery(it + 10L, stepAt = it) } +
            (0..11).map { second -> fix(second).let { Delivery(it.receivedAtElapsedRealtimeMs, raw = it) } }
        val samples = mutableListOf<WalkingCalibrationSample>()
        for (event in events.sortedBy { it.receivedAt }) {
            samples += event.stepAt?.let { collector(main).onStep(it, 1, true, event.receivedAt) }
                ?: main.observeWalkingCalibrationFix(requireNotNull(event.raw), moving(event.receivedAt), event.receivedAt)
            // Observe the actual SAMPLE_READY publication before a subsequent event changes its latest reason.
            if (samples.isNotEmpty()) break
        }
        val sample = samples.single()
        assertEquals(22, sample.stepCount)
        assertEquals(15.4, sample.distanceM, .001)
        assertEquals(11_000L, sample.durationMs)
        assertEquals(WalkingCalibrationOutcome.SAMPLE_READY, collector(main).diagnosticSnapshot().latestDecision?.outcome)
        val result = coordinator(main).calibrateProfile(sample)
        assertEquals(MotionCalibrationStatus.ACCEPTED_WARMUP, result.status)
        assertFalse(result.snapshot.isCalibrated)
        val applied = mutableListOf<Float>()
        val statuses = mutableListOf<String>()
        var persistCalls = 0
        main.applyWalkingCalibrationResult(result, { coordinator(main).currentProfile().stepLengthM.toFloat() },
            { applied += it }, { persistCalls++ }, { statuses += it })
        assertEquals(listOf(.65f), applied)
        assertEquals(1, persistCalls)
        assertTrue(statuses.single().contains("uncalibrated"))
        assertTrue(status(main).contains("유효한 직선 보행 구간"))
        assertTrue(status(main).contains("기본 보폭"))
    }

    @Test
    fun actualGatewayActorReplacementRejectsOldDisplayScopeAndClearsOnlyTheNewScopesDiagnostics() {
        val main = MainActivity()
        var generation: Long? = null
        try {
            generation = publishActor("calibration-actor-one")
            setField(main, "positioningProfileActorId", "calibration-actor-one")
            val old = synchronizeScope(main, "calibration-actor-one", null)
            assertTrue(scopeIsCurrent(main, old))
            main.observeWalkingCalibrationFix(fix(0).copy(horizontalAccuracyM = 4.0), moving(1_100L), 1_100L)
            assertFalse(collector(main).diagnosticSnapshot().decisionCounts.isEmpty())

            generation = publishActor("calibration-actor-two")
            assertFalse(scopeIsCurrent(main, old))
            assertTrue(status(main).contains("현재 사용자를 확인"))
            setField(main, "positioningProfileActorId", "calibration-actor-two")
            val next = synchronizeScope(main, "calibration-actor-two", null)
            assertNotSame(old, next)
            assertTrue(scopeIsCurrent(main, next))
            assertFalse(scopeIsCurrent(main, old))
            assertTrue(collector(main).diagnosticSnapshot().decisionCounts.isEmpty())
            assertNull(collector(main).diagnosticSnapshot().latestDecision)
        } finally {
            generation?.let(GatewaySessionProcessCoordinator::clear)
        }
    }

    @Test
    fun actualNewWalkEpochRetiresOldScopeButSameWalkHistoryResetKeepsTheDiagnosticRecord() {
        val main = MainActivity()
        var walkSequence = 0
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "calibration-walk-${++walkSequence}" }, clock = { 1_000L })
        setField(main, "walkSessionLifecycle", lifecycle)
        var generation: Long? = null
        try {
            generation = publishActor("calibration-walk-actor")
            setField(main, "positioningProfileActorId", "calibration-walk-actor")
            val old = synchronizeScope(main, "calibration-walk-actor", lifecycle.snapshot().epoch)
            main.observeWalkingCalibrationFix(fix(0).copy(horizontalAccuracyM = 4.0), moving(1_100L), 1_100L)
            val rejected = collector(main).diagnosticSnapshot()
            collector(main).reset()
            assertSame(old, synchronizeScope(main, "calibration-walk-actor", lifecycle.snapshot().epoch))
            assertEquals(rejected, collector(main).diagnosticSnapshot())
            assertTrue(scopeIsCurrent(main, old))

            val transition = lifecycle.handle(WalkSessionEvent.NewWalkRequested)
            assertNotEquals(transition.previous.epoch, transition.current.epoch)
            assertFalse(scopeIsCurrent(main, old))
            val next = synchronizeScope(main, "calibration-walk-actor", transition.current.epoch)
            assertTrue(scopeIsCurrent(main, next))
            assertTrue(collector(main).diagnosticSnapshot().decisionCounts.isEmpty())
            assertNull(collector(main).diagnosticSnapshot().latestDecision)
            assertFalse(coordinator(main).currentProfile().isCalibrated)
        } finally {
            generation?.let(GatewaySessionProcessCoordinator::clear)
        }
    }

    @Test
    fun sourceOnlyActualStepFixAndProfileRestoreConsumeDiagnosticsIntoTheExistingGuidanceView() {
        val source = source()
        val step = MainActivityRefactorSource.function(source, "private fun handlePositioningStepEvent(")
        val calibrationOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/WalkingCalibrationSession.kt").readText()
        val originalStep = MainActivityRefactorSource.member(calibrationOwner, "onStep")
        assertTrue(originalStep.indexOf("latestStep = event") in 0 until originalStep.indexOf("segments.onStep("))
        assertTrue(originalStep.contains("event.confidence == StepEventConfidence.HIGH"))
        val stepEvaluation = step.indexOf("walkingCalibrationSession.onStep(event, receivedAtElapsedRealtimeMs)")
        assertTrue(stepEvaluation >= 0)
        assertTrue(step.contains(".forEach(::applyWalkingCalibrationSample)"))
        assertTrue(step.indexOf("effects.refreshCalibration()") > stepEvaluation)
        val fix = MainActivityRefactorSource.function(source, "internal fun observeWalkingCalibrationFix(")
        assertTrue(fix.contains("walkingCalibrationSession.observeFix(fix, userMotion, processingAtElapsedRealtimeMs, coordinator)"))
        assertTrue(MainActivityRefactorSource.member(calibrationOwner, "observeFix").contains("coordinator.onFix("))
        assertTrue(fix.contains("if (coordinator === calibrationSegments) effects.refreshCalibration()"))
        val restore = MainActivityRefactorSource.function(source, "private fun ensurePositioningProfileForCurrentActor(")
        assertTrue(restore.indexOf("restoreProfile") < restore.indexOf("effects.refreshCalibration()"))
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions").contains("override fun refreshCalibration() = refreshWalkingCalibrationDiagnostics()"))
        val composer = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/capture/FrameMotionEvidenceComposer.kt").readText()
        val refresh = MainActivityRefactorSource.member(composer, "refreshWalkingCalibrationDiagnostics")
        val wiring = MainActivityRefactorSource.composition(source, "frameMotionEvidence")
        assertTrue(wiring.contains("FrameMotionEvidenceComposer({ positioningSessionController }, { routeNavigator },"))
        assertTrue(MainActivityRefactorSource.member(source, "refreshWalkingCalibrationDiagnostics").contains("frameMotionEvidence.refreshWalkingCalibrationDiagnostics()"))
        assertTrue(wiring.contains("override fun synchronizeCalibrationScope() = synchronizeWalkingCalibrationScope()"))
        assertTrue(wiring.contains("override fun guidancePageCurrent() = nativeUiPage == NativeUiPage.GUIDANCE"))
        assertTrue(wiring.contains("override fun lifecycleSnapshot() = if (::walkSessionLifecycle.isInitialized) walkSessionLifecycle.snapshot() else null"))
        assertTrue(refresh.contains("diagnosticSnapshot()"))
        assertTrue(refresh.contains("walkingCalibrationScopeIsCurrent(scope)"))
        assertTrue(refresh.contains("currentGateway.generation == gateway.generation && currentGateway.session === gateway.session"))
        assertTrue(refresh.contains("(source.lifecycleSnapshot()) == lifecycle"))
        assertTrue(refresh.contains("positioning().coordinator.currentProfile() == profile"))
        assertTrue(refresh.contains("positioning().calibrationSegments.diagnosticSnapshot() == diagnostic"))
        assertTrue(refresh.contains("display.updateCurrentGuidance(message + \"\\n\" + currentMotionEvidenceStatusMessage())"))
        assertTrue(wiring.contains("override fun updateCurrentGuidance(message: String) = updateNativeGuidanceStatus(currentNativeGuidancePresentation(), message)"))
        assertFalse(refresh.contains("updateNavigationStatus"))
        assertFalse(refresh.contains("recordEvent"))
        val pageSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePagePresentationController.kt").readText()
        val page = MainActivityRefactorSource.member(pageSource, "renderFrame")
        assertTrue(page.contains("val presentation = queries.currentGuidancePresentation()"))
        assertTrue(page.contains("phases.renderGuidanceStatus(presentation, queries.walkingCalibrationStatusMessage())"))
        val pageWiring = MainActivityRefactorSource.property(source, "nativePagePresentationController")
        assertTrue(pageWiring.contains("NativePagePresentationController("))
        assertTrue(pageWiring.contains("override fun walkingCalibrationStatusMessage() = this@MainActivity.walkingCalibrationStatusMessage()"))
        assertTrue(pageWiring.contains("updateNativeGuidanceStatus(presentation, measurements)"))
    }

    @Test
    fun sourceOnlyEpochPublicationResetsScopeWhileAnInvalidFixOnlyResetsMeasurementHistory() {
        val source = source()
        val transition = MainActivityRefactorSource.function(source, "private fun transitionWalkSession(")
        assertTrue(transition.contains("lifecycle().handle(event, effects::beforePublication)"))
        val transitionWiring = MainActivityRefactorSource.member(source, "createWalkSessionTransitionController")
        assertTrue(transitionWiring.contains("lifecycle = { walkSessionLifecycle }"))
        val beforePublication = transitionWiring.substringAfter("override fun beforePublication(").substringBefore("override fun refreshCalibrationDiagnostics(")
        assertTrue(transitionWiring.contains("override fun beforePublication("))
        assertTrue(beforePublication.contains("if (previous.epoch != next.epoch)"))
        assertTrue(beforePublication.contains("synchronizeWalkingCalibrationScope(positioningProfileActorId, next.epoch)"))
        val fix = NavigationPolicySource.calibration(source)
        assertTrue(fix.contains("walkingCalibrationSession.resetHistory()"))
        assertFalse(fix.contains("resetForScope()"))
        val scope = MainActivityRefactorSource.function(source, "private fun synchronizeWalkingCalibrationScope(")
        assertTrue(scope.contains("walkingCalibrationSession.synchronizeScope(actorId, walkEpoch)"))
        val calibrationOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/WalkingCalibrationSession.kt").readText()
        val ownerScope = MainActivityRefactorSource.member(calibrationOwner, "synchronizeScope")
        assertTrue(ownerScope.contains("current.actorId == actorId && current.walkEpoch == walkEpoch"))
        assertTrue(ownerScope.contains("segments.resetForScope()"))
        assertTrue(MainActivityRefactorSource.member(calibrationOwner, "resetHistory").contains("segments.reset()"))
        val caller = block(source, "private fun walkingCalibrationDiagnosticMessage(")
        assertTrue(caller.contains("positioningDiagnosticsPresenter.walkingCalibrationDiagnosticMessage(profile, diagnostic, step)"))
        val profileCaller = block(source, "private fun walkingCalibrationProfileMessage(")
        assertTrue(profileCaller.contains("positioningDiagnosticsPresenter.walkingCalibrationProfileMessage(profile)"))
        val presenter = presenterSource()
        val message = block(presenter, "fun walkingCalibrationDiagnosticMessage(")
        assertTrue(message.contains("walkingCalibrationProfileMessage(profile)"))
        assertTrue(block(presenter, "fun walkingCalibrationProfileMessage(").contains("profile.isCalibrated"))
        assertTrue(message.contains("diagnostic.decisionCounts[it]"))
        assertTrue(message.contains("step.confidence != StepEventConfidence.HIGH"))
        assertFalse(message.contains("decision.reason.name"))
        assertFalse(message.contains("diagnostic.decisionCounts.size"))
    }

    /** Keep Main's real source reads; replace only the unavailable JVM monotonic-clock input. */
    private fun mainWithClock(nowMs: Long): MainActivity = MainActivity().also { main ->
        val composer = MainActivityControllerTestFixture.controller(main, "frameMotionEvidence")
        val sourceField = composer.javaClass.getDeclaredField("source").apply { isAccessible = true }
        val original = sourceField.get(composer) as FrameMotionEvidenceSource
        sourceField.set(composer, object : FrameMotionEvidenceSource by original {
            override fun elapsedRealtimeMs() = nowMs
        })
    }

    private fun source() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun presenterSource() = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/PositioningDiagnosticsPresenter.kt").readText()
    private fun collector(main: MainActivity) = field(main, "walkingCalibrationSegments") as TimestampAlignedWalkingCalibration
    private fun coordinator(main: MainActivity) = field(main, "positioningCoordinator") as PositioningCoordinator
    private fun status(main: MainActivity) = invoke(main, "walkingCalibrationStatusMessage", String::class.java, emptyArray()) as String
    private fun synchronizeScope(main: MainActivity, actor: String?, epoch: WalkRuntimeEpoch?): Any =
        requireNotNull(invoke(main, "synchronizeWalkingCalibrationScope", scopeClass(),
            arrayOf(String::class.java, WalkRuntimeEpoch::class.java), actor, epoch))
    private fun scopeIsCurrent(main: MainActivity, scope: Any) = invoke(main, "walkingCalibrationScopeIsCurrent",
        java.lang.Boolean.TYPE, arrayOf(scopeClass()), scope) as Boolean
    private fun scopeClass() = Class.forName("kr.co.hanium.dreamup.walksafe.navigation.session.WalkingCalibrationScope")
    private fun diagnosticMessage(main: MainActivity, profile: PedestrianMotionProfileSnapshot,
        diagnostic: WalkingCalibrationDiagnosticSnapshot, step: StepEvent) =
        invoke(main, "walkingCalibrationDiagnosticMessage", String::class.java,
            arrayOf(PedestrianMotionProfileSnapshot::class.java, WalkingCalibrationDiagnosticSnapshot::class.java,
                StepEvent::class.java), profile, diagnostic, step) as String
    private fun invoke(main: MainActivity, name: String, result: Class<*>, parameters: Array<Class<*>>, vararg values: Any?): Any? =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(result, parameters))
            .bindTo(main).invokeWithArguments(values.toList())
    private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name)
        .apply { isAccessible = true }.get(main)
    private fun setField(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
        .apply { isAccessible = true }.set(main, value)
    private fun publishActor(actor: String): Long {
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        val session = GatewayFieldSession.verified("https://gateway.example.test", actor,
            "walksafe_field_session=v2.test.session.cookie", Long.MAX_VALUE)
        check(GatewaySessionProcessCoordinator.publishVerified(operation, session, FirstRunOnboardingPolicy.initial()))
        return GatewaySessionProcessCoordinator.snapshot().generation
    }
    private fun sample() = WalkingCalibrationSample(16.0, 20, 16_000L, trustedPositionSegment = true)
    private fun fix(second: Int): RawWalkingCalibrationFix {
        val at = 1_000L + second * 1_000L
        return RawWalkingCalibrationFix(37.5,
            127.0 + second * 1.4 / (LocalGeodesy.METERS_PER_DEGREE * cos(Math.toRadians(37.5))),
            1.0, at, at + 100L, 1.4, .1, 90.0, 5.0, trustedRawFix = true)
    }
    private fun moving(at: Long) = UserMotionStateSnapshot(1L, UserMovementState.MOVING,
        UserLocomotionState.WALKING, UserDeviceRotationState.STABLE, UserMotionStateReason.STEP_OBSERVED,
        750L, at, at + 2_000L, null, null, 750L, null, false)
    private fun block(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing production function $signature" }
        val end = Regex("\n    (?:(?:private|internal|override) )?fun ").find(source, start + signature.length)?.range?.first
        return source.substring(start, end ?: source.length)
    }
}
