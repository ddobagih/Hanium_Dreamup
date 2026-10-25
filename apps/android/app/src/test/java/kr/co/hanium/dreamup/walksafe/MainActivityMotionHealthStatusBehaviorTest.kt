package kr.co.hanium.dreamup.walksafe

import android.hardware.Sensor
import android.hardware.SensorEventListener
import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.AndroidStepTracker
import kr.co.hanium.dreamup.walksafe.navigation.StepSensorRegistration
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionEstimate
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.ui.PositioningDiagnosticsPresenter
import org.junit.Assert.*
import org.junit.Test

/** Production formatter body and Main wiring. Native getter/permissions/Views have a separate SDK test. */
class MainActivityMotionHealthStatusBehaviorTest {
    @Test fun actualProducerConflictAndUnsampledRegistrationAreExplainedByTheSameProductionFormatter() {
        val c = conflictingInputs()
        val step = AndroidStepTracker(Registration(), onStepCountChanged = {})
        try {
            assertTrue(step.start())
            val health = step.operationalHealth
            assertTrue(health.sources.all { it.lastAcceptedSample == null })
            val state = c.peekUserMotion(1_250L)
            assertEquals(UserMotionStateReason.STEP_WITH_STATIONARY_GNSS, state.support.conflicts.single().reason)
            val text = format(state, stepHealth = health)
            assertTrue("Production representation must consume the original opposing source facts", text.contains(CONFLICT))
            assertTrue(text.contains("등록됐지만 실제 측정은 아직 확인되지 않았습니다."))
            assertFalse(text.contains("실제 측정 이력이 있습니다."))
            assertNoEnums(text)
            assertSame(health, step.operationalHealth)
            assertEquals(state, c.peekUserMotion(1_250L))
        } finally { step.stop() }
    }

    @Test fun realTrackerNullReceiptIsNotReplacedAndRetiredSamplesAreNotReportedAsCurrent() {
        val c = ownedCoordinator()
        val step = AndroidStepTracker(Registration(), onStepCountChanged = {})
        try {
            assertTrue(step.start())
            assertTrue(step.onStepCounterTotal(100f, 1_000_123_456L))
            val observed = step.operationalHealth
            val sample = requireNotNull(observed.source(TrackedSensorSource.STEP_COUNTER)?.lastAcceptedSample)
            assertNull(sample.receivedAtElapsedRealtimeNanos)
            val text = format(c.peekUserMotion(1_250L), stepHealth = observed)
            assertTrue(text.contains("실제 측정 이력이 있습니다."))
            assertTrue(text.contains("원 수신 시각은 확인되지 않았습니다."))
            assertSame(observed, step.operationalHealth)
            assertEquals(1_000_123_456L, sample.measurementElapsedRealtimeNanos)
            step.stop()
            val retired = step.operationalHealth
            assertTrue(retired.sources.all { it.lastAcceptedSample == null && !it.acceptingSamples })
            // The legacy scalar input may accept data after stop; the retired health registration must not publish it.
            step.onStepCounterTotal(101f, 1_001_123_456L, 1_001_223_456L)
            assertSame(retired, step.operationalHealth)
            val retiredText = format(c.peekUserMotion(1_250L), stepHealth = retired)
            assertTrue(retiredText.contains("수집을 중단했습니다."))
            assertFalse(retiredText.contains("실제 측정 이력이 있습니다."))
        } finally { step.stop() }
    }

    @Test fun realProducerExpiryAndNewOwnerCannotKeepTheOldConflictAlive() {
        val c = conflictingInputs()
        val original = c.peekUserMotion(1_250L)
        assertTrue(format(original).contains(CONFLICT))
        val expired = c.peekUserMotion(3_101L)
        assertTrue(expired.support.conflicts.isEmpty())
        assertTrue(format(expired).contains("이전 움직임 근거가 만료"))
        assertFalse(format(expired).contains(CONFLICT))
        val next = OWNER.copy(actorGeneration = OWNER.actorGeneration + 1L,
            walkEpoch = OWNER.walkEpoch.copy(recoveryGeneration = OWNER.walkEpoch.recoveryGeneration + 1L))
        assertTrue(c.beginUserMotionEpoch(next, 3_102L))
        val rejected = format(original, expectedOwner = c.currentUserMotionOwner())
        assertTrue(rejected.contains("현재 보행 범위의 움직임 근거를 다시 확인"))
        assertFalse(rejected.contains(CONFLICT))
        assertEquals(UserMovementState.UNKNOWN, c.peekUserMotion(3_102L).movement)
    }

    @Test fun cleanupFailureAndCurrentPermissionAreSeparateFromTheOriginalRegistrationOutcome() {
        val registration = Registration()
        val step = AndroidStepTracker(registration, onStepCountChanged = {})
        val c = ownedCoordinator()
        try {
            assertTrue(step.start())
            registration.unregisterFails = true
            assertTrue(runCatching { step.stop() }.isFailure)
            val failed = step.operationalHealth
            assertTrue(failed.cleanupPending)
            val denied = format(c.peekUserMotion(1_250L), stepHealth = failed, permitted = false)
            assertTrue(denied.contains("현재 활동 인식 권한이 없습니다."))
            assertTrue(denied.contains("이전 등록 자원의 정리가 끝나지 않았습니다."))
            assertFalse(denied.contains("실제 측정 이력이 있습니다."))
            assertSame(failed, step.operationalHealth)
            val unowned = format(c.peekUserMotion(1_250L), stepHealth = failed, expectedOwner = null)
            assertTrue(unowned.contains("정리가 끝나지 않았습니다."))
            assertFalse(unowned.contains(CONFLICT))
        } finally { registration.unregisterFails = false; step.stop() }
    }

    @Test fun sourceGetterReadsExistingHealthPermissionAndCurrentOwnersWithoutRenewingInputFacts() {
        val source = source()
        val composer = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/capture/FrameMotionEvidenceComposer.kt").readText()
        val getter = MainActivityRefactorSource.member(composer, "currentMotionEvidenceStatusMessage")
        val binding = MainActivityRefactorSource.composition(source, "frameMotionEvidence")
        assertTrue(binding.contains("FrameMotionEvidenceComposer({ positioningSessionController }, { routeNavigator },"))
        assertTrue(MainActivityRefactorSource.member(source, "currentMotionEvidenceStatusMessage").contains("frameMotionEvidence.currentMotionEvidenceStatusMessage()"))
        assertTrue(binding.contains("override fun stepTrackerOrNull() = if (::stepTracker.isInitialized) stepTracker else null"))
        assertTrue(binding.contains("override fun activityRecognitionPermissionGranted() = hasActivityRecognitionPermission()"))
        assertTrue(binding.contains("override val stepTrackingEpoch get() = this@MainActivity.stepTrackingEpoch"))
        listOf("step?.operationalHealth", "pedestrian?.operationalHealth", "source.activityRecognitionPermissionGranted()",
            "positioning().isPositioningCollectionCurrent(binding)", "state.generation == binding.inputGeneration",
            "it.walkEpoch == epoch", "it.actorGeneration == gateway.generation", "source.stepTrackingEpoch == epoch",
            "currentGateway.session === gateway.session", "owner.takeIf { stillOwned }").forEach { assertTrue(it, getter.contains(it)) }
        assertFalse(getter.contains("observeUser"))
        assertFalse(getter.contains("receivedAtElapsedRealtimeNanos ="))
        assertFalse(getter.contains("maximumAge"))
        val caller = getter
        assertTrue(caller.contains("return presenter.motionEvidenceStatusMessage("))
        assertTrue(caller.contains("""state, motion, stepHealth, motionHealth, activityPermissionGranted,
            owner.takeIf { stillOwned })"""))
        val formatter = body(presenterSource(), "    fun motionEvidenceStatusMessage(", "    fun walkingCalibrationProfileMessage(")
        assertTrue(formatter.contains("state.support.conflicts.map { it.reason } + state.reasonCodes"))
        assertTrue(formatter.contains("it.producerRegistrationGeneration == health.registrationGeneration"))
        assertTrue(formatter.contains("sample.receivedAtElapsedRealtimeNanos == null"))
        assertFalse(formatter.contains("SystemClock"))
        assertFalse(formatter.contains(".name"))
    }

    @Test fun sourceUsesTheExistingGuidanceUiAndProductionCallbacksWithoutFieldTelemetryOrPolicyChanges() {
        val source = source()
        val pageSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePagePresentationController.kt").readText()
        val page = MainActivityRefactorSource.member(pageSource, "renderFrame")
        assertTrue(page.contains("val presentation = queries.currentGuidancePresentation()"))
        assertTrue(page.contains("phases.renderGuidanceStatus(presentation, queries.walkingCalibrationStatusMessage())"))
        val pageWiring = MainActivityRefactorSource.property(source, "nativePagePresentationController")
        assertTrue(pageWiring.contains("NativePagePresentationController("))
        assertTrue(pageWiring.contains("override fun walkingCalibrationStatusMessage() = this@MainActivity.walkingCalibrationStatusMessage()"))
        assertTrue(pageWiring.contains("updateNativeGuidanceStatus(presentation, measurements)"))
        val composer = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/capture/FrameMotionEvidenceComposer.kt").readText()
        val refresh = MainActivityRefactorSource.member(composer, "refreshWalkingCalibrationDiagnostics")
        val motionBinding = MainActivityRefactorSource.composition(source, "frameMotionEvidence")
        assertTrue(refresh.contains("display.updateCurrentGuidance(message + \"\\n\" + currentMotionEvidenceStatusMessage())"))
        assertTrue(motionBinding.contains("override fun updateCurrentGuidance(message: String) = updateNativeGuidanceStatus(currentNativeGuidancePresentation(), message)"))
        assertTrue(source.contains("latestStepCount = steps\n                            refreshWalkingCalibrationDiagnostics()"))
        val sensor = MainActivityRefactorSource.function(source, "private fun startPositioningObservationSources(")
        assertTrue(sensor.contains("applyUserMotionState(userMotion)\n                effects.refreshCalibration()"))
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions").contains("override fun refreshCalibration() = refreshWalkingCalibrationDiagnostics()"))
        val ui = refresh
        assertFalse(ui.contains("updateNavigationStatus("))
        assertFalse(ui.contains("recordEvent("))
        val compassCaller = MainActivityRefactorSource.member(source, "routeCompassStatusMessage")
        assertTrue(compassCaller.contains("positioningDiagnosticsPresenter.routeCompassStatusMessage(result)"))
        val compassFormatter = presenterSource().substringAfter("    fun routeCompassStatusMessage(")
        assertTrue(compassFormatter.contains("RouteCompassHeadingReason.SENSOR_STALLED ->"))
        // Both production display paths include the existing read-only motion facts.
        val status = MainActivityRefactorSource.member(composer, "walkingCalibrationStatusMessage")
        assertTrue(status.contains("calibrationSummary + \"\\n\" + currentMotionEvidenceStatusMessage()"))
    }

    @Test fun sourceRefreshesTheSamePositioningStepCallbackAfterMotionSupportCommit() {
        val source = source()
        val step = MainActivityRefactorSource.function(source, "private fun handlePositioningStepEvent(")
        val current = step.indexOf("!isPositioningCollectionCurrent(binding)")
        val observe = step.indexOf("val snapshot = binding.coordinator.observeStep(")
        val apply = step.indexOf("applyPositioningMotionSnapshot(snapshot, binding)", observe)
        val refresh = step.indexOf("effects.refreshCalibration()", apply)
        val trace = step.indexOf("effects.appendMotionTrace(", apply)
        assertTrue(current >= 0 && current < observe)
        assertTrue(observe < apply && apply < refresh && refresh < trace)
        assertEquals(1, step.split("effects.refreshCalibration()").size - 1)
        assertTrue(step.contains("expectedGeneration = binding.inputGeneration"))
        assertTrue(step.contains("expectedMotionOwner = motionOwner"))
        assertTrue(step.contains("originalStepEvent = event"))
        val wiring = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(wiring.contains("override fun refreshCalibration() = refreshWalkingCalibrationDiagnostics()"))
        assertTrue(wiring.contains("appendPositionFieldMotionTrace(snapshot, stepDetected, zuptApplied, measurementElapsedRealtimeMs)"))
    }

    private fun format(state: UserMotionStateSnapshot, stepHealth: SensorTrackerHealthSnapshot? = null,
        motionHealth: SensorTrackerHealthSnapshot? = null, permitted: Boolean = true,
        expectedOwner: UserMotionStateOwner? = state.owner): String {
        return PositioningDiagnosticsPresenter().motionEvidenceStatusMessage(
            state, UserMotionEstimate(), stepHealth, motionHealth, permitted, expectedOwner,
        )
    }
    private fun ownedCoordinator() = PositioningCoordinator().also { assertTrue(it.beginUserMotionEpoch(OWNER, 1_000L)) }
    private fun conflictingInputs() = ownedCoordinator().also { c ->
        c.observeUserSpeed(WalkingSpeedObservation(0.0, 0.1, 1_000L),
            UserMotionLocationEvidence(1_000L, 2.0, false, 11L), receivedAtElapsedRealtimeMs = 1_100L,
            expectedMotionOwner = OWNER)
        c.observeStep(1, PedestrianHeadingInput(1_100L, null, null, null, false), PdrStepQuality.HIGH,
            receivedAtElapsedRealtimeMs = 1_200L, expectedMotionOwner = OWNER)
    }
    private class Registration : StepSensorRegistration {
        var unregisterFails = false
        override fun hasSensor(type: Int) = type in setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR)
        override fun register(listener: SensorEventListener, type: Int) = true
        override fun unregister(listener: SensorEventListener?) { if (unregisterFails) throw IllegalStateException("controlled platform release") }
    }
    private fun assertNoEnums(text: String) { UserMotionStateReason.entries.forEach { assertFalse(it.name, text.contains(it.name)) } }
    private fun source() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun presenterSource() = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/PositioningDiagnosticsPresenter.kt").readText()
    private fun body(source: String, start: String, end: String): String {
        val at = source.indexOf(start); assertTrue(start, at >= 0)
        val until = source.indexOf(end, at + start.length); assertTrue(end, until > at)
        return source.substring(at, until)
    }
    private companion object {
        const val CONFLICT = "GPS 정지 근거와 실제 걸음 감지가 함께 들어와 근거가 서로 맞지 않습니다."
        val OWNER = UserMotionStateOwner(7L, WalkRuntimeEpoch("health-controlled-walk", 0L), "controlled-boot", "controlled-process")
    }
}
