package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.device.WalkSafeEnvironmentProfiles
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentAssessment
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentPolicy
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentRuntimeAction
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentUserConfirmation
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.*
import org.junit.Test

/** Real admission, quality gates, runtime guard and registered watchdogs; speech DONE is injected.
 * The fixture starts after permission/education/start admission and does not exercise Android TTS.
 */
class OfficialEnvironmentGpsWarningFlapRegressionTest {
    @Test
    fun aShortGraceFlapStaysSilentAndALaterRestrictionExplainsTheActualProblem() {
        val f = Fixture().apply { start() }
        f.gps(2_000L, accuracyMeters = 50.0)
        f.advanceTo(5_000L)
        assertTrue("a short quality recheck must not interrupt ongoing guidance", f.warnings.isEmpty())

        f.gps(6_000L, accuracyMeters = 7.0)
        f.gps(9_500L, accuracyMeters = 50.0)
        f.advanceTo(16_001L)

        f.assertGpsRestricted()
        assertEquals(1, f.gpsRestrictions)
        assertEquals("the actual restriction needs one corrective explanation", 1, f.warnings.size)
        assertTrue(f.warnings.single().requestedAtMs >= 16_001L)
    }

    @Test
    fun deliveredExpiryWarningDoesNotRepeatOnAnotherExpiryWithinThirtySeconds() {
        val f = Fixture().apply { start() }
        f.advanceTo(11_001L)
        f.assertGpsRestricted()
        f.complete(f.warnings.single(), 11_500L)

        f.gps(12_000L, accuracyMeters = 7.0)
        assertTrue(requireNotNull(f.controller.guard).decision().navigationOutputsAllowed)
        assertEquals(1, f.gpsResumptions)
        assertEquals("위치가 다시 확인되었습니다.", f.notices.last().message)
        f.advanceTo(22_001L)

        f.assertGpsRestricted()
        assertEquals("Both stale fixes still restrict navigation immediately", 2, f.gpsRestrictions)
        assertEquals("Native DONE must suppress the same expiry warning after a brief recovery",
            1, f.warnings.size)
    }

    @Test
    fun warningIntervalStartsAtDoneRatherThanDispatchAcceptance() {
        val f = Fixture().apply { start() }
        f.advanceTo(11_001L)
        f.complete(f.warnings.single(), 16_000L)
        f.gps(35_000L, accuracyMeters = 7.0)
        f.advanceTo(45_001L)

        f.assertGpsRestricted()
        assertEquals("Dispatch was over 30 seconds ago, but audible completion was not",
            1, f.warnings.size)
    }

    @Test
    fun aNewRestrictionAfterThirtySecondsMayWarnAgain() {
        val f = Fixture().apply { start() }
        f.advanceTo(11_001L)
        f.complete(f.warnings.single(), 11_500L)
        f.gps(32_000L, accuracyMeters = 7.0)
        f.advanceTo(42_001L)

        f.assertGpsRestricted()
        assertEquals(2, f.gpsRestrictions)
        assertEquals(2, f.warnings.size)
        assertTrue(f.warnings.last().requestedAtMs - 11_500L >= 30_000L)
    }

    @Test
    fun anUndeliveredWarningCannotSuppressTheNextRestriction() {
        val f = Fixture().apply { start() }
        f.advanceTo(11_001L)
        assertNull(f.warnings.single().completedAtMs)
        f.gps(12_000L, accuracyMeters = 7.0)
        f.advanceTo(22_001L)

        f.assertGpsRestricted()
        assertEquals("Accepted or interrupted speech is not proof the user heard the warning",
            2, f.warnings.size)
    }

    @Test
    fun unchangedRestrictionDoesNotCancelFeedbackOrWarnOnEveryWatchdog() {
        val f = Fixture().apply { start() }
        f.advanceTo(11_001L)
        f.complete(f.warnings.single(), 11_500L)
        f.advanceTo(50_000L)

        f.assertGpsRestricted()
        assertEquals(1, f.gpsRestrictions)
        assertEquals(1, f.warnings.size)
    }

    @Test
    fun deliveredGpsOnlySuppressionWarningDoesNotRepeatWithinThirtySeconds() {
        val f = Fixture(cameraFeatureEnabled = false).apply { start() }
        f.advanceTo(11_001L)
        f.assertAllOutputsSuppressed()
        f.complete(f.warnings.single(), 11_500L)

        f.gps(12_000L, accuracyMeters = 7.0)
        assertTrue(requireNotNull(f.controller.guard).decision().navigationOutputsAllowed)
        f.advanceTo(22_001L)

        f.assertAllOutputsSuppressed()
        assertEquals("Both expiries must still cancel unavailable outputs", 2, f.outputCancellations)
        assertEquals("GPS-only mode must retain the same DONE-based warning interval", 1, f.warnings.size)
    }

    @Test
    fun gpsOnlySuppressionIntervalAlsoStartsAtDoneRatherThanTheRequest() {
        val f = Fixture(cameraFeatureEnabled = false).apply { start() }
        f.advanceTo(11_001L)
        f.complete(f.warnings.single(), 16_000L)
        f.gps(35_000L, accuracyMeters = 7.0)
        f.advanceTo(45_001L)

        f.assertAllOutputsSuppressed()
        assertEquals("The request is older than 30 seconds, but completion is not", 1, f.warnings.size)
    }

    @Test
    fun aGpsOnlySuppressionAfterThirtySecondsMayWarnAgain() {
        val f = Fixture(cameraFeatureEnabled = false).apply { start() }
        f.advanceTo(11_001L)
        f.complete(f.warnings.single(), 11_500L)
        f.gps(32_000L, accuracyMeters = 7.0)
        f.advanceTo(42_001L)

        f.assertAllOutputsSuppressed()
        assertEquals(2, f.warnings.size)
        assertTrue(f.warnings.last().requestedAtMs - 11_500L >= 30_000L)
    }

    @Test
    fun anUndeliveredGpsOnlySuppressionWarningCannotSuppressTheNextEpisode() {
        val f = Fixture(cameraFeatureEnabled = false).apply { start() }
        f.advanceTo(11_001L)
        assertNull(f.warnings.single().completedAtMs)
        f.gps(12_000L, accuracyMeters = 7.0)
        f.advanceTo(22_001L)

        f.assertAllOutputsSuppressed()
        assertEquals("Suppression cannot count enqueue or interruption as audible delivery", 2, f.warnings.size)
    }

    @Test
    fun aQueuedGpsOnlySuppressionWarningCannotRemainCurrentAfterRecoveryOrEpochLoss() {
        for (recover in listOf(false, true)) {
            val f = Fixture(cameraFeatureEnabled = false).apply { start() }
            f.advanceTo(11_001L)
            val warning = f.warnings.single()
            val current = requireNotNull(warning.current) {
                "A queued suppression explanation needs the same captured restriction owner"
            }
            assertTrue(current())
            if (recover) f.gps(12_000L, accuracyMeters = 7.0) else f.currentEpoch = false
            assertFalse("Obsolete GPS warnings must not pass a later native START", current())
            f.complete(warning, 12_500L)
        }
    }

    @Test
    fun readySessionCannotEnterRuntimeAndRetiredActiveEpochCannotCompleteItsNotice() {
        val ready = Fixture(WalkSessionState.READY)
        ready.gps(1_000L, accuracyMeters = 7.0)
        assertFalse(ready.controller.activate())
        assertFalse(ready.controller.outputsAllowed)
        assertNull(ready.controller.runtimeReadiness(ready.epoch))
        assertTrue(ready.notices.isEmpty())

        val active = Fixture().apply { start() }
        active.gps(2_000L, accuracyMeters = 50.0)
        active.advanceTo(11_001L)
        val warning = active.warnings.single()
        assertTrue(requireNotNull(warning.current).invoke())
        active.currentEpoch = false
        assertFalse(requireNotNull(warning.current).invoke())
        active.complete(warning, 11_500L)
        active.advanceTo(22_001L)
        assertNull(active.controller.runtimeReadiness(active.epoch))
        assertEquals(1, active.gpsRestrictions)
        assertEquals(1, active.warnings.size)
    }

    private data class Notice(
        val message: String,
        val requestedAtMs: Long,
        val current: (() -> Boolean)? = null,
        val delivered: (() -> Unit)? = null,
        var completedAtMs: Long? = null,
    )

    private class Fixture(
        private val state: WalkSessionState = WalkSessionState.ACTIVE,
        private val cameraFeatureEnabled: Boolean = true,
    ) : OfficialEnvironmentRuntimeFactsReader, OfficialEnvironmentRuntimeEffects,
        OfficialEnvironmentAssessmentFactsReader {
        val epoch = WalkRuntimeEpoch("gps-warning-flap", 1L)
        private val profiles = WalkSafeEnvironmentProfiles.testCandidate
        private val profile = profiles.officialEnvironment
        // This ACTIVE fixture assumes the same epoch's supported conditions were explicitly
        // confirmed. Education acknowledgement alone permits LIMITED, still-unknown conditions.
        private val confirmation = OfficialEnvironmentUserConfirmation(epoch,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS)
        private val evidence = OfficialEnvironmentEvidenceStore(Any(),
            RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS,
                GuidanceRecoveryTiming.GPS_RECHECK_PERIODS),
            RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.CAMERA_GRACE_MS))
        private data class Scheduled(val atMs: Long, val action: () -> Unit)
        private val scheduled = mutableListOf<Scheduled>()
        var now = 1_000L
        var currentEpoch = true
        var gpsRestrictions = 0
        var gpsResumptions = 0
        var outputCancellations = 0
        val notices = mutableListOf<Notice>()
        val warnings get() = notices.filterNot { it.message == "위치가 다시 확인되었습니다." }
        val controller = OfficialEnvironmentRuntimeController(evidence, this, this, { now },
            { delay, action -> scheduled += Scheduled(now + delay, action) }, { false })

        fun start() {
            gps(now, accuracyMeters = 7.0)
            assertTrue(controller.activate())
            assertTrue(requireNotNull(controller.guard).decision().navigationOutputsAllowed)
            assertEquals(WalkSessionReadinessStatus.READY to "", controller.runtimeReadiness(epoch))
            assertTrue(notices.isEmpty())
        }

        fun gps(atMs: Long, accuracyMeters: Double) {
            advanceTo(atMs)
            evidence.recordGpsObservation(epoch, epoch, now, true, accuracyMeters,
                { now }, { profile }, ::isRuntimeEpochCurrent, { epoch }, { false },
                { isRuntimeEpochCurrent(epoch) }, false,
                { admittedEpoch, previous, current ->
                    controller.reactToAdmittedGpsObservation(admittedEpoch, previous, current)
                })
        }

        fun advanceTo(atMs: Long) {
            require(atMs >= now)
            while (true) {
                val next = scheduled.filter { it.atMs <= atMs }.minByOrNull { it.atMs } ?: break
                scheduled.remove(next)
                now = next.atMs
                freshCameraFrame()
                next.action()
            }
            now = atMs
            freshCameraFrame()
        }

        fun complete(notice: Notice, atMs: Long) {
            advanceTo(atMs)
            notice.completedAtMs = now
            if (notice.current?.invoke() != false) notice.delivered?.invoke()
        }

        fun assertGpsRestricted() {
            val decision = requireNotNull(controller.guard).decision()
            assertFalse(decision.navigationOutputsAllowed)
            assertTrue("An independently fresh camera remains usable", decision.cameraOutputsAllowed)
            assertFalse(decision.isTerminal)
            assertTrue(controller.outputsAllowed)
            assertEquals("A feature restriction does not exit the admitted ACTIVE walk",
                WalkSessionReadinessStatus.READY to "", controller.runtimeReadiness(epoch))
        }

        fun assertAllOutputsSuppressed() {
            val decision = requireNotNull(controller.guard).decision()
            assertEquals(setOf(OfficialEnvironmentFactor.GPS_QUALITY), decision.enabledMeasuredFactors)
            assertEquals(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY, decision.action)
            assertFalse(decision.navigationOutputsAllowed)
            assertFalse(decision.cameraOutputsAllowed)
            assertFalse("A recoverable GPS outage must not terminate the admitted walk", decision.isTerminal)
            assertFalse(controller.outputsAllowed)
            assertEquals(WalkSessionReadinessStatus.READY to "", controller.runtimeReadiness(epoch))
        }

        private fun freshCameraFrame() {
            if (!cameraFeatureEnabled) return
            val frame = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.CAMERA_QUALITY,
                epoch, now, EnvironmentEvidenceStatus.PASS, profile.cameraQualityProfileId,
                profiles.cameraFrameQuality.maximumEvidenceAgeMs, "PASSED")
            evidence.cameraQualityGate.observe(frame, now)
            evidence.commitCameraEvidence(frame)
        }

        override fun lifecycle() = OfficialEnvironmentLifecycleFacts(epoch, state)
        override fun currentRuntimeEpoch() = epoch.takeIf { isRuntimeEpochCurrent(it) }
        override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) =
            currentEpoch && state == WalkSessionState.ACTIVE && epoch == this.epoch
        override fun profile() = profile
        override fun continuousSourcePresent() = true
        override fun continuousGpsEvidence(epoch: WalkRuntimeEpoch, nowMs: Long) = evidence.gpsQualityGate.current(nowMs)
        override fun latestMountingCameraAssessment(): CameraFrameQualityAssessment? = null
        override fun currentAssessment(epoch: WalkRuntimeEpoch, nowMs: Long): OfficialEnvironmentAssessment {
            val measured = evidence.currentAssessment(epoch, nowMs, this, ::continuousGpsEvidence, {})
            // The store exposes no confirmation writer. Supply the scenario's immutable
            // confirmation to the same policy after its actual source/gate refresh.
            return OfficialEnvironmentPolicy.assess(epoch, nowMs, evidence.gpsEvidence,
                evidence.cameraEvidence, confirmation, profile, usageLimitsAcknowledged = true)
                .copy(enabledMeasuredFactors = measured.enabledMeasuredFactors,
                    runtimeUnavailableMeasuredFactors = measured.runtimeUnavailableMeasuredFactors,
                    recoverableRuntimeUnavailableMeasuredFactors = measured.recoverableRuntimeUnavailableMeasuredFactors)
        }
        // Main supplies true only after the current actor's native education is complete.
        override fun policyFacts() = OfficialEnvironmentPolicyFacts(profile, usageLimitsAcknowledged = true)
        override fun separateGpsActive() = false
        override fun locationEnabled() = true
        override fun cameraEnabled() = cameraFeatureEnabled
        override fun cameraHandoffEpoch() = epoch
        override fun mountingCameraRestricted(epoch: WalkRuntimeEpoch) = false
        override fun reassessMountingWithoutExplicitRetry() = Unit
        override fun invalidateReportOutput(reason: String) = Unit
        override fun restrictGpsNavigationAndCancelFeedback() { gpsRestrictions++ }
        override fun resumeGpsNavigation() { gpsResumptions++ }
        override fun cancelPendingFeedbackDeliveries() = Unit
        override fun cancelVoiceAndFeedbackActuator() { outputCancellations++ }
        override fun showStatus(title: String, detail: String) = Unit
        override fun speakStatusExplanation(message: String) { notices += Notice(message, now) }
        override fun speakRecheckExplanation(message: String, current: () -> Boolean, delivered: () -> Unit) {
            if (current()) notices += Notice(message, now, current, delivered)
        }
        override fun failureSpeech(assessment: OfficialEnvironmentAssessment,
            factors: Set<OfficialEnvironmentFactor>?) = "위치 품질이 확인되지 않아 방향 안내를 잠시 보류합니다."
        override fun locationRecoveryActionMessage() = "새 위치를 기다려 주세요."
        override fun enterSafetyStop(reason: String) { fail("GPS quality alone must remain recoverable: $reason") }
    }
}
