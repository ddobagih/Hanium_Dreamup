package kr.co.hanium.dreamup.walksafe.navigation.session

import android.location.Location
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.device.WalkSafeEnvironmentProfiles
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentAssessmentFactsReader
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentLifecycleFacts
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentPolicyFacts
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeController
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeEffects
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeFactsReader
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Source-owned estimator/store/session composition with real source timestamps and SDK ports. */
class ContinuousGpsRecoveryPeriodsTest {
    @Test fun sixFailedPeriodsRemeasureWhileTheOriginalGoodFixExpiresWithoutTimestampRenewal() {
        val f = Fixture()
        f.publish(1_000L)
        val good = f.store.gpsEvidence
        f.publish(2_000L, 30.0)
        assertEquals(1, f.requests)
        assertSame(good, f.session.currentContinuousGpsEvidence(f.epoch, 4_999L))
        val deadline = 2_000L + GuidanceRecoveryTiming.GPS_GRACE_MS
        assertEquals(20_000L, deadline)
        listOf(5_000L, 8_000L, 11_000L, 14_000L, 17_000L).forEachIndexed { index, now ->
            f.publish(now, 30.0)
            f.reconcile(now)
            assertEquals(index + 2, f.requests)
            assertEquals(deadline, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
            if (now <= 11_000L) assertSame(good, f.session.currentContinuousGpsEvidence(f.epoch, now))
            else assertEquals(EnvironmentEvidenceStatus.FAIL, f.session.currentContinuousGpsEvidence(f.epoch, now)?.status)
        }
        assertEquals(1_000L, good?.observedAtElapsedRealtimeMs)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING, f.gate.gpsRecoveryStage(deadline - 1L))
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED, f.gate.gpsRecoveryStage(deadline))
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.session.currentContinuousGpsEvidence(f.epoch, deadline)?.status)
    }

    @Test fun oneExpiringRequestCannotConsumeSixPeriodsOrRequireAUserRetry() {
        val f = Fixture()
        f.publish(1_000L)
        f.publish(2_000L, 30.0)
        val firstCallback = requireNotNull(f.callback)
        firstCallback(null)
        f.reconcile(2_100L)
        f.reconcile(4_999L)
        assertEquals(1, f.requests)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.session.currentContinuousGpsEvidence(f.epoch, 4_999L)?.status)
        f.reconcile(5_000L)
        assertEquals(2, f.requests)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.session.currentContinuousGpsEvidence(f.epoch, 5_000L)?.status)
        val secondCallback = requireNotNull(f.callback)
        firstCallback(f.unreadLocation())
        assertEquals(0, f.consumed)
        secondCallback(f.unreadLocation())
        assertEquals(1, f.consumed)
    }

    @Test fun initialCoarseThenGoodAcquiresAutomaticallyWithItsActualPrecisionAndTimestamp() {
        val f = Fixture()
        val coarse = f.publish(1_000L, 30.0)
        assertTrue(requireNotNull(coarse).confidence.guidancePaused)
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.store.gpsEvidence?.status)
        assertNull(f.trusted)
        val good = f.publish(2_000L)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.store.gpsEvidence?.status)
        assertEquals(2_000L, f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
        assertEquals(3f, f.raw?.accuracyM)
        assertFalse(good.confidence.guidancePaused)
        assertEquals(1, f.cancels)
        f.reconcile(5_000L)
        assertEquals(1, f.requests)
    }

    @Test fun watchdogRemeasuresWithoutAStreamCallbackAndPreservesTheLaterSdkReceiptTime() {
        val f = Fixture()
        f.publish(1_000L)
        f.publish(2_000L, 30.0)
        requireNotNull(f.callback)(null)
        f.now = 5_000L
        assertEquals(EnvironmentEvidenceStatus.PASS, f.session.currentContinuousGpsEvidence(f.epoch, f.now)?.status)
        assertEquals(2, f.requests)
        requireNotNull(f.callback)(null)
        f.now = 8_000L
        assertEquals(EnvironmentEvidenceStatus.PASS, f.session.currentContinuousGpsEvidence(f.epoch, f.now)?.status)
        assertEquals(3, f.requests)
        f.now = 8_500L
        requireNotNull(f.callback)(f.unreadLocation())
        assertEquals(8_500L, f.consumedAtMs)
    }

    @Test fun registeredWatchdogRemeasuresPureStaleEvidenceAfterAnEmptySdkResult() {
        val runtime = RuntimeFixture().apply { start() }
        val f = runtime.f
        runtime.advanceTo(10_999L)
        repeat(3) {
            assertEquals(EnvironmentEvidenceStatus.PASS,
                f.session.currentContinuousGpsEvidence(f.epoch, f.now)?.status)
        }
        runtime.advanceTo(11_000L)
        repeat(3) {
            assertEquals(EnvironmentEvidenceStatus.PASS,
                f.session.currentContinuousGpsEvidence(f.epoch, f.now)?.status)
        }
        assertEquals(0, f.requests)
        runtime.advanceTo(11_001L)
        runtime.assertGpsRestricted()
        assertEquals(listOf(11_001L), f.requestTimes)
        assertNull("There was no bad-accuracy sample before this plain expiry",
            f.gate.gpsFailureStartedAtElapsedRealtimeMs())
        requireNotNull(f.callback)(null)

        runtime.advanceTo(14_000L)
        assertEquals("An empty SDK result must not trigger a tight retry loop", 1, f.requests)
        runtime.advanceTo(14_001L)
        assertEquals("The registered runtime watchdog must retry without a new stream callback",
            listOf(11_001L, 14_001L), f.requestTimes)
        requireNotNull(f.callback)(null)
        runtime.advanceTo(17_001L)
        assertEquals(listOf(11_001L, 14_001L, 17_001L), f.requestTimes)
        runtime.assertGpsRestricted()
        assertEquals("Remeasurement attempts must never refresh the original GPS measurement",
            1_000L, f.gate.latestRawObservation()?.observedAtElapsedRealtimeMs)
        assertEquals(1_000L, f.trusted?.elapsedRealtimeMs)
    }

    @Test fun lateStaleDetectionDoesNotMoveTheOriginalMeasurementExpiryPeriods() {
        val f = Fixture()
        f.publish(1_000L)
        // The original PASS expires at 11,000 ms. This assessment arrives one second late;
        // it must not move the existing 3-second remeasurement periods to a new receipt clock.
        f.now = 12_000L
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN,
            f.session.currentContinuousGpsEvidence(f.epoch, f.now)?.status)
        assertEquals(1, f.requests)
        requireNotNull(f.callback)(null)
        f.now = 13_999L
        f.session.currentContinuousGpsEvidence(f.epoch, f.now)
        assertEquals(1, f.requests)
        f.now = 14_000L
        f.session.currentContinuousGpsEvidence(f.epoch, f.now)

        assertEquals("Original expiry + 3 seconds must begin the next period", 2, f.requests)
        assertEquals(listOf(12_000L, 14_000L), f.requestTimes)
        assertNull("Retry timing cannot fabricate a measured bad-accuracy episode",
            f.gate.gpsFailureStartedAtElapsedRealtimeMs())
        assertEquals(1_000L, f.gate.latestRawObservation()?.observedAtElapsedRealtimeMs)
    }

    @Test fun aNewFixCancelsPureStaleReacquisitionAndOldCallbacksCannotConsumeIt() {
        val runtime = RuntimeFixture().apply { start() }
        val f = runtime.f
        runtime.advanceTo(11_001L)
        val oldCallback = requireNotNull(f.callback)
        assertEquals(1, f.requests)

        runtime.publish(12_000L)
        assertTrue(requireNotNull(runtime.controller.guard).decision().navigationOutputsAllowed)
        assertEquals(12_000L, f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
        assertEquals(1, f.cancels)
        oldCallback(f.unreadLocation())
        assertEquals("A retired one-shot cannot publish into the recovered owner", 0, f.consumed)
        runtime.advanceTo(22_000L)
        assertEquals("A fresh fix cancels the outage's pending retries", 1, f.requests)
        runtime.advanceTo(22_001L)
        assertEquals(listOf(11_001L, 22_001L), f.requestTimes)
        runtime.assertGpsRestricted()
    }

    @Test fun sourceRetirementCancelsPureStaleReacquisitionAndItsRegisteredWatchdog() {
        for (retiredBoundary in listOf("epoch", "source", "access")) {
            val runtime = RuntimeFixture().apply { start() }
            val f = runtime.f
            runtime.advanceTo(11_001L)
            val oldCallback = requireNotNull(f.callback)
            when (retiredBoundary) {
                "epoch" -> { runtime.epochCurrent = false; f.allowed = false }
                "source" -> f.current = f.current.copy(binding = Any())
                else -> f.allowed = false
            }
            assertNull(f.session.currentContinuousGpsEvidence(f.epoch, f.now))
            assertEquals(1, f.cancels)
            oldCallback(f.unreadLocation())
            runtime.advanceTo(20_001L)

            assertEquals(retiredBoundary, 0, f.consumed)
            assertEquals(retiredBoundary, 1, f.requests)
            assertNull(f.session.owner)
            assertNull(f.gate.latestRawObservation())
        }
    }

    @Test fun persistentFailureKeepsReacquiringAndFreshRecoveryCancelsTheLatestRequest() {
        val f = Fixture()
        f.publish(1_000L)
        f.publish(2_000L, 30.0)
        listOf(5_000L, 8_000L, 11_000L, 14_000L, 17_000L, 20_000L).forEach { now ->
            f.publish(now, 30.0)
            f.reconcile(now)
        }
        assertEquals(7, f.requests)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED, f.gate.gpsRecoveryStage(20_000L))
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.session.currentContinuousGpsEvidence(f.epoch, 20_000L)?.status)
        f.publish(20_100L)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.store.gpsEvidence?.status)
        assertEquals(20_100L, f.trusted?.elapsedRealtimeMs)
        f.reconcile(23_000L)
        assertEquals(7, f.requests)
    }

    @Test fun duplicateAndOldGoodCannotCancelAnActiveFailureOrRenewTheGoodTimestamp() {
        val f = Fixture()
        f.publish(1_000L)
        f.publish(2_000L, 30.0)
        val oldPass = requireNotNull(f.store.gpsEvidence)
        f.gate.observe(oldPass, 2_100L)
        f.reconcile(2_100L)
        assertEquals(0, f.cancels)
        assertEquals(1_000L, f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
        f.reconcile(5_000L)
        assertEquals(2, f.requests)
        assertEquals(1_000L, f.session.currentContinuousGpsEvidence(f.epoch, 5_000L)?.observedAtElapsedRealtimeMs)
    }

    @Test fun originalGoodExpiresAtTenSecondsBeforeTheSixPeriodDeadline() {
        val f = Fixture()
        f.publish(1_000L)
        f.publish(7_000L, Double.NaN)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.session.currentContinuousGpsEvidence(f.epoch, 11_000L)?.status)
        assertNotEquals(EnvironmentEvidenceStatus.PASS, f.session.currentContinuousGpsEvidence(f.epoch, 11_001L)?.status)
        val bad = requireNotNull(f.publish(11_002L, Double.NaN))
        assertFalse(f.session.retainsPublishedPositionDuringGpsFailure(bad, 11_002L))
        assertTrue(bad.confidence.guidancePaused)
    }

    @Test fun unavailableConfidenceSharesEighteenSecondConfirmationButNeverPromotesTheRawGrade() {
        val coordinator = applicationCoordinator()
        val policyField = PositioningCoordinator::class.java.getDeclaredField("confidencePolicy").apply { isAccessible = true }
        val policy = policyField.get(coordinator) as PositionConfidencePolicy
        assertFalse(policy.observe(PositionQuality.MEDIUM, 1_000L).guidancePaused)
        val first = policy.observe(PositionQuality.UNAVAILABLE, 2_000L, informativeGnss = false)
        assertEquals(PositionQuality.UNAVAILABLE, first.quality)
        assertFalse(first.guidancePaused)
        listOf(5_000L, 8_000L, 11_000L, 14_000L, 17_000L).forEach { now ->
            // New, genuinely informative but poor GNSS samples retain UNAVAILABLE grade.
            val actual = policy.observe(PositionQuality.UNAVAILABLE, now, informativeGnss = true)
            assertEquals(PositionQuality.UNAVAILABLE, actual.quality)
            assertEquals(2_000L, actual.degradationPendingSinceElapsedRealtimeMs)
            assertFalse(actual.guidancePaused)
        }
        val deadline = 2_000L + GuidanceRecoveryTiming.GPS_GRACE_MS
        assertFalse(policy.evaluate(deadline - 1L).guidancePaused)
        assertTrue(policy.evaluate(deadline).guidancePaused)
    }

    @Test fun coldCoarseForTwoPeriodsThenFirstGoodAcquiresWithoutAUserDecision() {
        val f = Fixture()
        f.publish(1_000L, 70.0)
        f.publish(4_000L, 70.0)
        f.publish(7_000L, 70.0)
        val acquired = f.publish(7_100L)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.store.gpsEvidence?.status)
        assertFalse(acquired.confidence.guidancePaused)
        assertNull(acquired.confidence.degradedEpisodeId)
        assertEquals(7_100L, f.trusted?.elapsedRealtimeMs)
        assertEquals(3f, f.raw?.accuracyM)
    }

    @Test fun malformedMockFuturePermissionAndRetiredOwnerCannotBorrowOrConsumeGoodEvidence() {
        listOf("coordinate", "mock", "future").forEach { cause ->
            val f = Fixture()
            f.publish(1_000L)
            val bad = requireNotNull(when (cause) {
                "coordinate" -> f.publish(2_000L, latitude = 91.0)
                "mock" -> f.publish(2_000L, mock = true)
                else -> f.publish(2_001L, received = 2_000L)
            })
            assertNotEquals(cause, EnvironmentEvidenceStatus.PASS, f.store.gpsEvidence?.status)
            assertFalse(cause, f.session.retainsPublishedPositionDuringGpsFailure(bad, 2_000L))
        }
        listOf(false, true).forEach { retire ->
            val f = Fixture()
            f.publish(1_000L)
            f.publish(2_000L, 30.0)
            val oldCallback = requireNotNull(f.callback)
            if (retire) f.current = f.current.copy(binding = Any()) else f.allowed = false
            assertNull(f.session.currentContinuousGpsEvidence(f.epoch, 2_100L))
            oldCallback(f.unreadLocation())
            assertEquals(0, f.consumed)
            assertEquals(1, f.cancels)
        }
    }

    @Test fun availabilityLossIntersectsEighteenSecondPendingConfidenceWithOriginalFixTtl() {
        val controller = unsafe().allocateInstance(PositioningSessionController::class.java) as PositioningSessionController
        val policy = applicationConfidencePolicy()
        val position = TrustedLocation(37.5, 127.0, 3f, 1_000L)
        policy.observe(PositionQuality.MEDIUM, 1_000L)
        policy.observe(PositionQuality.UNAVAILABLE, 2_000L, informativeGnss = false)
        listOf(5_001L, 8_000L, 10_999L, 11_000L).forEach { now ->
            val decision = policy.evaluate(now)
            assertEquals(PositionQuality.UNAVAILABLE, decision.quality)
            assertEquals(PositionConfidenceTransition.DEGRADATION_PENDING, decision.transition)
            assertEquals(2_000L, decision.degradationPendingSinceElapsedRealtimeMs)
            assertFalse(decision.guidancePaused)
            assertTrue(controller.retainFreshPositionOnAvailabilityLoss(position, decision, now))
            assertEquals(1_000L, position.elapsedRealtimeMs)
        }
        val expiredPosition = policy.evaluate(11_001L)
        assertTrue(expiredPosition.guidancePaused)
        assertEquals(PositionConfidenceFailureReason.STALE, expiredPosition.failureReason)
        assertFalse(controller.retainFreshPositionOnAvailabilityLoss(position, expiredPosition, 11_001L))
        listOf(11_100L, 14_000L, 17_000L).forEach { now ->
            val actual = policy.observe(PositionQuality.UNAVAILABLE, now, informativeGnss = false)
            assertTrue(actual.guidancePaused)
            assertEquals(PositionConfidenceFailureReason.STALE, actual.failureReason)
            assertFalse(controller.retainFreshPositionOnAvailabilityLoss(position, actual, now))
            assertEquals(1_000L, position.elapsedRealtimeMs)
        }
        val deadline = 2_000L + GuidanceRecoveryTiming.GPS_GRACE_MS
        val beforeDeadline = policy.evaluate(deadline - 1L)
        assertTrue(beforeDeadline.guidancePaused)
        assertEquals(PositionConfidenceFailureReason.STALE, beforeDeadline.failureReason)
        val confirmed = policy.evaluate(deadline)
        assertTrue(confirmed.guidancePaused)
        assertFalse(controller.retainFreshPositionOnAvailabilityLoss(position, confirmed, deadline))
    }

    @Test fun availabilityLossCannotExtendTheOriginalTenSecondLocationFreshness() {
        val controller = unsafe().allocateInstance(PositioningSessionController::class.java) as PositioningSessionController
        val policy = applicationConfidencePolicy()
        val position = TrustedLocation(37.5, 127.0, 3f, 1_000L)
        policy.observe(PositionQuality.MEDIUM, 1_000L)
        policy.observe(PositionQuality.UNAVAILABLE, 7_000L, informativeGnss = false)
        val atBoundary = policy.evaluate(11_000L)
        assertFalse(atBoundary.guidancePaused)
        assertTrue(controller.retainFreshPositionOnAvailabilityLoss(position, atBoundary, 11_000L))
        val expired = policy.evaluate(11_001L)
        assertEquals(PositionConfidenceFailureReason.STALE, expired.failureReason)
        assertFalse(controller.retainFreshPositionOnAvailabilityLoss(position, expired, 11_001L))
    }

    @Test fun availabilityLossStillRefusesMissingFutureAndInvalidTimeEvidence() {
        val controller = unsafe().allocateInstance(PositioningSessionController::class.java) as PositioningSessionController
        val policy = applicationConfidencePolicy()
        val position = TrustedLocation(37.5, 127.0, 3f, 1_000L)
        val good = policy.observe(PositionQuality.MEDIUM, 1_000L)
        assertFalse(controller.retainFreshPositionOnAvailabilityLoss(null, good, 1_000L))
        assertFalse(controller.retainFreshPositionOnAvailabilityLoss(position, good, 999L))
        val invalid = policy.observe(PositionQuality.HIGH, 2_000L, 1_999L)
        assertEquals(PositionConfidenceFailureReason.INVALID_TIME, invalid.failureReason)
        assertFalse(controller.retainFreshPositionOnAvailabilityLoss(position, invalid, 1_999L))
    }

    private class Fixture {
        var now = 1_000L
        val epoch = WalkRuntimeEpoch("gps-periods", 0L)
        val profile = requireNotNull(WalkSafeEnvironmentProfiles.active(true)).officialEnvironment
        var current = ContinuousGpsSource(Any(), epoch, profile)
        var allowed = true
        var trusted: TrustedLocation? = null
        var raw: TrustedLocation? = null
        var requests = 0
        val requestTimes = mutableListOf<Long>()
        var cancels = 0
        var consumed = 0
        var consumedAtMs: Long? = null
        var callback: ((Location?) -> Unit)? = null
        var onRuntimeObservation: (WalkRuntimeEpoch, MeasuredEnvironmentEvidence?, MeasuredEnvironmentEvidence) -> Unit = { _, _, _ -> }
        val gate = RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS, gpsFailureConfirmationPeriods = GuidanceRecoveryTiming.GPS_RECHECK_PERIODS)
        val store = OfficialEnvironmentEvidenceStore(Any(), gate, RuntimeMeasuredQualityGate())
        val coordinator = applicationCoordinator()
        val session: ContinuousGpsSession = ContinuousGpsSession(
            source = object : ContinuousGpsSession.SourceReader {
                override fun current() = current.takeIf { allowed }
                override val latestTrusted get() = trusted
                override val latestRaw get() = raw
                override fun nativeCheckCurrent(epoch: WalkRuntimeEpoch) = true
            }, runtimeGpsQualityGate = gate,
            evidence = object : ContinuousGpsSession.EvidenceWriter {
                override fun clearGpsEvidence() = store.clearGpsEvidence()
                override fun record(epoch: WalkRuntimeEpoch, observedAtMs: Long, trustedFixAvailable: Boolean?, accuracyM: Double?, elapsedRealtimeMs: () -> Long, singleOutlier: Boolean) {
                    store.recordGpsObservation(epoch, epoch, observedAtMs, trustedFixAvailable, accuracyM, elapsedRealtimeMs,
                        { profile }, { true }, { session.owner?.epoch }, { false }, { true }, singleOutlier, onRuntimeObservation)
                }
            }, actions = object : ContinuousGpsSession.Actions {
                override fun clearDisconnectedRecovery() {}
                override fun prewalkEvidenceChanged() {}
                override fun requestFresh(result: (Location?) -> Unit): () -> Unit {
                    requests++
                    requestTimes += now
                    callback = result
                    return { cancels++ }
                }
                override fun consumeFresh(location: Location, receivedAtMs: Long) {
                    consumed++
                    consumedAtMs = receivedAtMs
                }
            }, clock = { now },
        )

        fun reconcile(at: Long) {
            now = at
            session.reconcileContinuousGpsRecovery()
        }

        fun publish(at: Long, accuracy: Double? = 3.0, received: Long = at, latitude: Double = 37.5, mock: Boolean = false): PositioningSnapshot {
            now = received
            val snapshot = coordinator.observeGnss(GnssPositionObservation(latitude, 127.0, accuracy, at,
                receivedAtElapsedRealtimeMs = received, mock = mock))
            session.recordPositioningGpsObservation(snapshot, at, accuracy)
            if (snapshot.gnssUpdate?.disposition != GnssObservationDisposition.HARD_REJECTED &&
                accuracy != null && accuracy.isFinite() && accuracy in 0.0..15.0
            ) {
                trusted = TrustedLocation(latitude, 127.0, requireNotNull(snapshot.filtered).horizontalUncertaintyM.toFloat(), at)
                raw = TrustedLocation(latitude, 127.0, accuracy.toFloat(), at)
            }
            return snapshot
        }

        fun unreadLocation(): Location {
            return unsafe().allocateInstance(Location::class.java) as Location
        }
    }

    /** Starts at the admitted ACTIVE boundary with supported common conditions. Actual GPS
     * source/store/gate and runtime callbacks are retained; camera measurements and SDK are ports.
     */
    private class RuntimeFixture : OfficialEnvironmentRuntimeFactsReader, OfficialEnvironmentRuntimeEffects,
        OfficialEnvironmentAssessmentFactsReader {
        val f = Fixture()
        var epochCurrent = true
        private val confirmation = OfficialEnvironmentUserConfirmation(f.epoch,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS)
        private data class Scheduled(val atMs: Long, val action: () -> Unit)
        private val scheduled = mutableListOf<Scheduled>()
        val controller = OfficialEnvironmentRuntimeController(f.store, this, this, { f.now },
            { delay, action -> scheduled += Scheduled(f.now + delay, action) }, { false })

        init {
            f.onRuntimeObservation = { epoch, previous, current ->
                controller.reactToAdmittedGpsObservation(epoch, previous, current)
            }
        }

        fun start() {
            f.publish(f.now)
            freshCameraFrame()
            assertTrue(controller.activate())
            assertTrue(requireNotNull(controller.guard).decision().navigationOutputsAllowed)
            assertTrue(scheduled.isNotEmpty())
        }

        fun publish(atMs: Long) {
            advanceTo(atMs)
            f.publish(atMs)
        }

        fun advanceTo(atMs: Long) {
            require(atMs >= f.now)
            var runs = 0
            while (true) {
                val next = scheduled.filter { it.atMs <= atMs }.minByOrNull { it.atMs } ?: break
                check(++runs <= 200) { "Unexpected runtime watchdog retry loop" }
                scheduled.remove(next)
                f.now = next.atMs
                freshCameraFrame()
                next.action()
            }
            f.now = atMs
            freshCameraFrame()
        }

        fun assertGpsRestricted() {
            val decision = requireNotNull(controller.guard).decision()
            assertFalse(decision.navigationOutputsAllowed)
            assertTrue(decision.cameraOutputsAllowed)
            assertFalse(decision.isTerminal)
            assertTrue(controller.outputsAllowed)
            assertNotEquals(EnvironmentEvidenceStatus.PASS, f.store.gpsEvidence?.status)
        }

        private fun freshCameraFrame() {
            val cameraProfile = WalkSafeEnvironmentProfiles.testCandidate.cameraFrameQuality
            val frame = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.CAMERA_QUALITY,
                f.epoch, f.now, EnvironmentEvidenceStatus.PASS, cameraProfile.profileId,
                cameraProfile.maximumEvidenceAgeMs, "PASSED")
            f.store.cameraQualityGate.observe(frame, f.now)
            f.store.commitCameraEvidence(frame)
        }

        override fun lifecycle() = OfficialEnvironmentLifecycleFacts(f.epoch, WalkSessionState.ACTIVE)
        override fun currentRuntimeEpoch() = f.epoch.takeIf(::isRuntimeEpochCurrent)
        override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = epochCurrent && epoch == f.epoch
        override fun profile() = f.profile
        override fun continuousSourcePresent() = f.session.owner != null
        override fun continuousGpsEvidence(epoch: WalkRuntimeEpoch, nowMs: Long) =
            f.session.currentContinuousGpsEvidence(epoch, nowMs)
        override fun latestMountingCameraAssessment(): CameraFrameQualityAssessment? = null
        override fun currentAssessment(epoch: WalkRuntimeEpoch, nowMs: Long): OfficialEnvironmentAssessment {
            val measured = f.store.currentAssessment(epoch, nowMs, this, ::continuousGpsEvidence, {})
            return OfficialEnvironmentPolicy.assess(epoch, nowMs, f.store.gpsEvidence,
                f.store.cameraEvidence, confirmation, f.profile, usageLimitsAcknowledged = true)
                .copy(enabledMeasuredFactors = measured.enabledMeasuredFactors,
                    runtimeUnavailableMeasuredFactors = measured.runtimeUnavailableMeasuredFactors,
                    recoverableRuntimeUnavailableMeasuredFactors = measured.recoverableRuntimeUnavailableMeasuredFactors)
        }
        override fun policyFacts() = OfficialEnvironmentPolicyFacts(f.profile, usageLimitsAcknowledged = true)
        override fun separateGpsActive() = false
        override fun locationEnabled() = true
        override fun cameraEnabled() = true
        override fun cameraHandoffEpoch() = f.epoch
        override fun mountingCameraRestricted(epoch: WalkRuntimeEpoch) = false
        override fun reassessMountingWithoutExplicitRetry() = Unit
        override fun invalidateReportOutput(reason: String) = Unit
        override fun restrictGpsNavigationAndCancelFeedback() = Unit
        override fun resumeGpsNavigation() = Unit
        override fun cancelPendingFeedbackDeliveries() = Unit
        override fun cancelVoiceAndFeedbackActuator() = Unit
        override fun showStatus(title: String, detail: String) = Unit
        override fun speakStatusExplanation(message: String) = Unit
        override fun failureSpeech(assessment: OfficialEnvironmentAssessment,
            factors: Set<OfficialEnvironmentFactor>?) = "위치 품질을 다시 확인합니다."
        override fun locationRecoveryActionMessage() = "새 위치를 기다려 주세요."
        override fun enterSafetyStop(reason: String) { fail("A stale GPS must remain recoverable: $reason") }
    }

    private companion object {
        fun unsafe(): sun.misc.Unsafe {
            val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return field.get(null) as sun.misc.Unsafe
        }

        fun applicationCoordinator(): PositioningCoordinator {
            val factory = PositioningSessionController::class.java.declaredMethods.singleOrNull {
                it.name == "defaultPositioningCoordinator"
            } ?: return PositioningCoordinator()
            factory.isAccessible = true
            val controller = unsafe().allocateInstance(PositioningSessionController::class.java)
            return factory.invoke(controller) as PositioningCoordinator
        }

        fun applicationConfidencePolicy(): PositionConfidencePolicy {
            val field = PositioningCoordinator::class.java.getDeclaredField("confidencePolicy").apply { isAccessible = true }
            return field.get(applicationCoordinator()) as PositionConfidencePolicy
        }
    }
}
