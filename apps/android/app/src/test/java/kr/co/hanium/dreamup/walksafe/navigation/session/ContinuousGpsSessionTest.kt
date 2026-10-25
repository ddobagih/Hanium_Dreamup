package kr.co.hanium.dreamup.walksafe.navigation.session

import android.location.Location
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.device.WalkSafeEnvironmentProfiles
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual estimator/store/shared quality gate; SDK source admission and one-shot delivery are ports. */
class ContinuousGpsSessionTest {
    @Test fun constructionDoesNotReadSourceOrRequestLocation() {
        val f = Fixture()
        assertEquals(0, f.reads)
        assertEquals(0, f.requests)
        assertNull(f.session.owner)
    }
    @Test fun homeBadThenActivationRetainsTheOriginalThreeSecondDeadline() {
        val f = Fixture()
        f.publish(1_000L)
        f.publish(2_000L, 30.0)
        val pass = f.store.gpsEvidence
        assertEquals(1_000L, pass?.observedAtElapsedRealtimeMs)
        assertEquals(5_000L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = 4_000L
        f.store.prepareGpsForPreflight(true, { f.epoch }, { f.now }, {}, f.session::currentContinuousGpsEvidence)
        f.active = true
        f.store.prepareGpsForRuntime({ f.epoch }, f.now, { f.session.owner != null }, f.session::currentContinuousGpsEvidence)
        assertSame(pass, f.store.gpsEvidence)
        assertEquals(5_000L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.PASS, f.session.currentContinuousGpsEvidence(f.epoch, 4_999L)?.status)
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.session.currentContinuousGpsEvidence(f.epoch, 5_000L)?.status)
        assertEquals(1, f.requests)
    }
    @Test fun exactAcceptedDuplicateIsNoOpAndNewRecoveryCancelsOnlyItsRequest() {
        val f = Fixture()
        f.publish(1_000L)
        f.publish(2_000L, 30.0)
        f.publish(2_500L)
        val recovery = f.store.gpsEvidence
        val publications = f.publications
        assertNull(f.publish(2_500L, receipt = 2_501L))
        assertSame(recovery, f.store.gpsEvidence)
        assertEquals(publications, f.publications)
        assertEquals(2_500L, recovery?.observedAtElapsedRealtimeMs)
        assertEquals(1, f.cancels)
        assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertFalse(f.session.shouldSkipAcceptedGnssSample(f.fingerprint(2_500L).copy(mock = true), 2_501L, 1.0))
        assertFalse(f.session.shouldSkipAcceptedGnssSample(f.fingerprint(2_500L), 12_501L, 1.0))
        assertFalse(f.session.shouldSkipAcceptedGnssSample(f.fingerprint(2_500L), 2_501L, Double.NaN))
    }
    @Test fun sourceRetirementCancelsPendingRequestAndRejectsLateDelivery() {
        val f = Fixture()
        f.publish(1_000L)
        f.publish(2_000L, 30.0)
        val oldCallback = requireNotNull(f.callback)
        f.current = f.current.copy(binding = Any())
        assertNull(f.session.currentContinuousGpsEvidence(f.epoch, 2_100L))
        assertEquals(1, f.cancels)
        assertNull(f.store.gpsEvidence)
        assertNull(f.gate.latestRawObservation())
        oldCallback(f.unreadLocation())
        assertEquals(0, f.consumed)
        assertFalse(f.session.shouldSkipAcceptedGnssSample(f.fingerprint(2_000L, 30.0), 2_100L, 1.0))
    }
    @Test fun historicalSampleCanOnlyBeIgnoredWithFreshNewerAcceptedProofAndCoordinates() {
        val f = Fixture()
        f.publish(2_500L)
        val pass = f.store.gpsEvidence
        val old = GnssPositionObservation(37.5, 127.0, 3.0, 2_499L, receivedAtElapsedRealtimeMs = 2_501L)
        val snapshot = f.coordinator.observeGnss(old)
        assertEquals(GnssHardRejectReason.OUT_OF_ORDER, snapshot.gnssUpdate?.hardRejectReason)
        assertTrue(f.session.shouldIgnoreSupersededGnssSample(f.fingerprint(2_499L), snapshot, 2_501L, 1.0))
        assertSame(pass, f.store.gpsEvidence)
        assertFalse(f.session.shouldIgnoreSupersededGnssSample(f.fingerprint(2_500L).copy(latitude = 37.50001), snapshot, 2_501L, 1.0))
        f.trusted = null
        assertFalse(f.session.shouldIgnoreSupersededGnssSample(f.fingerprint(2_499L), snapshot, 2_501L, 1.0))
        assertSame(pass, f.store.gpsEvidence)
    }
    @Test fun rejectedAccuracyKeepsTheOriginalFixAndOnlyOneRecoveryUntilThreeSeconds() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { accuracy ->
            val f = Fixture()
            f.publish(1_000L)
            val original = f.trusted
            val raw = f.raw
            val bad = requireNotNull(f.publish(1_100L, accuracy))
            assertEquals(GnssHardRejectReason.INVALID_ACCURACY, bad.gnssUpdate?.hardRejectReason)
            assertTrue(f.session.retainsPublishedPositionDuringGpsFailure(bad, 1_100L))
            assertSame(original, f.trusted)
            assertSame(raw, f.raw)
            assertEquals(1_000L, f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
            assertEquals("GPS_INVALID_ACCURACY", f.gate.latestRawObservation()?.detail)
            f.publish(2_000L, accuracy)
            assertEquals(4_100L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
            assertEquals(1, f.requests)
            assertTrue(f.session.retainsPublishedPositionDuringGpsFailure(bad, 4_099L))
            assertFalse(f.session.retainsPublishedPositionDuringGpsFailure(bad, 4_100L))
            assertEquals(EnvironmentEvidenceStatus.FAIL, f.session.currentContinuousGpsEvidence(f.epoch, 4_100L)?.status)
        }
    }
    @Test fun aBriefBurstOfDistinctJumpsKeepsTheOriginalFixUntilTheSameRecheckBoundary() {
        val f = Fixture()
        f.publish(1_000L)
        val original = f.trusted
        val raw = f.raw
        val first = requireNotNull(f.publish(1_100L, latitude = 37.6))
        val second = requireNotNull(f.publish(1_400L, latitude = 37.6001))
        assertEquals(GnssHardRejectReason.CORRUPT_JUMP, second.gnssUpdate?.hardRejectReason)
        assertTrue(first.singleOutlierAgainstFreshEstimate)
        assertFalse(second.singleOutlierAgainstFreshEstimate)
        assertTrue(second.transientOutlierAgainstFreshEstimate)
        assertTrue(f.session.retainsPublishedPositionDuringGpsFailure(second, 1_400L))
        assertSame(original, f.trusted)
        assertSame(raw, f.raw)
        assertEquals("GPS_TRANSIENT_CORRUPT_JUMP", f.gate.latestRawObservation()?.detail)
        assertEquals(4_100L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.PASS, f.session.currentContinuousGpsEvidence(f.epoch, 1_400L)?.status)
        val persistent = requireNotNull(f.publish(4_100L, latitude = 37.6002))
        assertFalse(persistent.transientOutlierAgainstFreshEstimate)
        assertFalse(f.session.retainsPublishedPositionDuringGpsFailure(persistent, 4_100L))
        assertSame(original, f.trusted)
        assertSame(raw, f.raw)
        assertEquals("GPS_CORRUPT_JUMP", f.gate.latestRawObservation()?.detail)
        assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.session.currentContinuousGpsEvidence(f.epoch, 4_100L)?.status)
    }
    @Test fun numericalEstimatorFailureUsesTheSameQualityEpisode() {
        val f = Fixture()
        f.publish(1_000L)
        val bad = requireNotNull(f.publish(1_100L, Double.MAX_VALUE))
        assertEquals(GnssHardRejectReason.NUMERICAL_FAILURE, bad.gnssUpdate?.hardRejectReason)
        assertTrue(f.session.retainsPublishedPositionDuringGpsFailure(bad, 1_100L))
        assertEquals("GPS_NUMERICAL_FAILURE", f.gate.latestRawObservation()?.detail)
        assertEquals(4_100L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
    }
    @Test fun hardBoundariesAndColdAcquisitionCannotBorrowPublishedPositions() {
        val cold = Fixture()
        val coldBad = requireNotNull(cold.publish(1_100L, Double.NaN))
        assertFalse(cold.session.retainsPublishedPositionDuringGpsFailure(coldBad, 1_100L))
        assertNull(cold.trusted)
        assertEquals(EnvironmentEvidenceStatus.FAIL, cold.store.gpsEvidence?.status)
        listOf("mock", "coordinate", "future", "stale").forEach { cause ->
            val f = Fixture()
            f.publish(1_000L)
            val bad = requireNotNull(when (cause) {
                "mock" -> f.publish(1_100L, mock = true)
                "coordinate" -> f.publish(1_100L, latitude = 91.0)
                "future" -> f.publish(1_200L, receipt = 1_100L)
                else -> f.publish(1_100L, receipt = 11_101L)
            })
            assertFalse(cause, f.session.retainsPublishedPositionDuringGpsFailure(bad, f.now))
            assertNotEquals(cause, EnvironmentEvidenceStatus.PASS, f.store.gpsEvidence?.status)
            assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
    }
    @Test fun actualGoodAfterInvalidAccuracyCancelsRecoveryAndDuplicateDoesNotRefreshIt() {
        val f = Fixture()
        f.publish(1_000L)
        f.publish(1_100L, Double.NaN)
        f.publish(1_400L)
        assertEquals(1_400L, f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
        assertEquals(1_400L, f.trusted?.elapsedRealtimeMs)
        assertEquals(1, f.cancels)
        assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        val original = f.trusted
        assertNull(f.publish(1_400L, receipt = 1_500L))
        assertSame(original, f.trusted)
        assertEquals(1_400L, f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
        f.publish(2_000L, Double.NaN)
        assertEquals(5_000L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(2, f.requests)
    }
    @Test fun missingAccuracyKeepsOriginalGoodAndColdAcquisitionRemainsUnknown() {
        val f = Fixture()
        f.publish(1_000L)
        val original = f.trusted
        val raw = f.raw
        f.publish(1_100L, null)
        assertSame(original, f.trusted)
        assertSame(raw, f.raw)
        assertEquals(1_000L, f.store.gpsEvidence?.observedAtElapsedRealtimeMs)
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, f.gate.latestRawObservation()?.status)
        assertEquals(4_100L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        f.publish(1_400L, Double.NaN)
        assertEquals(4_100L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(1, f.requests)
        f.publish(2_000L)
        assertEquals(2_000L, f.trusted?.elapsedRealtimeMs)
        assertNull(f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(1, f.cancels)
        val cold = Fixture()
        cold.publish(1_000L, null)
        assertNull(cold.trusted)
        assertNull(cold.raw)
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, cold.store.gpsEvidence?.status)
        assertNull(cold.gate.pendingFailureDeadlineElapsedRealtimeMs())
    }
    @Test fun obsoleteStaleExclusionPreservesPublishedProofAndTheActiveFailureDeadline() {
        val f = Fixture()
        f.publish(6_000L)
        val trusted = f.trusted
        val raw = f.raw
        f.publish(10_400L, Double.NaN)
        f.publish(10_700L, latitude = 37.6)
        val pass = f.store.gpsEvidence
        val publications = f.publications
        assertTrue(f.session.shouldIgnoreSupersededStaleGnssSample(f.fingerprint(999L), 11_000L, 1.0))
        assertSame(trusted, f.trusted); assertSame(raw, f.raw); assertSame(pass, f.store.gpsEvidence)
        assertEquals(publications, f.publications)
        assertEquals(13_400L, f.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.PASS, f.session.currentContinuousGpsEvidence(f.epoch, 13_399L)?.status)
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.session.currentContinuousGpsEvidence(f.epoch, 13_400L)?.status)
    }

    @Test fun obsoleteStaleExclusionCannotCrossPublishedFreshnessMetadataOrPermissionSource() {
        fun fixture() = Fixture().also { it.publish(6_000L) }
        val f = fixture()
        val old = f.fingerprint(999L)
        assertTrue(f.session.shouldIgnoreSupersededStaleGnssSample(old, 11_000L, 1.0))
        assertFalse(f.session.shouldIgnoreSupersededStaleGnssSample(old, 16_001L, 1.0))
        assertFalse(f.session.shouldIgnoreSupersededStaleGnssSample(f.fingerprint(11_001L), 11_000L, 1.0))
        assertFalse(f.session.shouldIgnoreSupersededStaleGnssSample(old.copy(mock = true), 11_000L, 1.0))
        assertFalse(f.session.shouldIgnoreSupersededStaleGnssSample(old.copy(latitude = 91.0), 11_000L, 1.0))
        assertFalse(f.session.shouldIgnoreSupersededStaleGnssSample(old, 11_000L, Double.NaN))
        f.raw = f.raw?.copy(accuracyM = 16f)
        assertFalse(f.session.shouldIgnoreSupersededStaleGnssSample(old, 11_000L, 1.0))
        f.raw = null
        assertFalse(f.session.shouldIgnoreSupersededStaleGnssSample(old, 11_000L, 1.0))
        val retired = fixture(); retired.current = retired.current.copy(binding = Any())
        assertFalse(retired.session.shouldIgnoreSupersededStaleGnssSample(old, 11_000L, 1.0))
        val denied = fixture(); denied.sourceAllowed = false
        assertFalse(denied.session.shouldIgnoreSupersededStaleGnssSample(old, 11_000L, 1.0))
        denied.sourceAllowed = true
        assertFalse("Source denial retires its accepted ledger", denied.session.shouldIgnoreSupersededStaleGnssSample(old, 11_000L, 1.0))
    }

    private class Fixture {
        var now = 1_000L
        var active = false
        val epoch = WalkRuntimeEpoch("continuous", 0L)
        val profile = requireNotNull(WalkSafeEnvironmentProfiles.active(true)).officialEnvironment
        var current = ContinuousGpsSource(Any(), epoch, profile)
        var sourceAllowed = true
        var reads = 0; var requests = 0; var cancels = 0; var consumed = 0; var publications = 0
        var callback: ((Location?) -> Unit)? = null
        var trusted: TrustedLocation? = null
        var raw: TrustedLocation? = null
        val coordinator = PositioningCoordinator()
        val gate = RuntimeMeasuredQualityGate(3_000L)
        val store = OfficialEnvironmentEvidenceStore(Any(), gate, RuntimeMeasuredQualityGate())
        val session: ContinuousGpsSession = ContinuousGpsSession(
            source = object : ContinuousGpsSession.SourceReader {
                override fun current() = current.takeIf { sourceAllowed }.also { reads++ }
                override val latestTrusted get() = trusted
                override val latestRaw get() = raw
                override fun nativeCheckCurrent(epoch: WalkRuntimeEpoch) = true
            }, runtimeGpsQualityGate = gate,
            evidence = object : ContinuousGpsSession.EvidenceWriter {
                override fun clearGpsEvidence() = store.clearGpsEvidence()
                override fun record(epoch: WalkRuntimeEpoch, observedAtMs: Long, trustedFixAvailable: Boolean?, accuracyM: Double?, elapsedRealtimeMs: () -> Long, singleOutlier: Boolean) {
                    publications++
                    store.recordGpsObservation(this@Fixture.epoch, epoch, observedAtMs, trustedFixAvailable, accuracyM,
                        elapsedRealtimeMs, { profile }, { active }, { session.owner?.epoch }, { false }, { active }, singleOutlier, { _, _, _ -> })
                }
            }, actions = object : ContinuousGpsSession.Actions {
                override fun clearDisconnectedRecovery() {}
                override fun prewalkEvidenceChanged() {}
                override fun requestFresh(result: (Location?) -> Unit): () -> Unit { requests++; callback = result; return { cancels++ } }
                override fun consumeFresh(location: Location, receivedAtMs: Long) { consumed++ }
            }, clock = { now },
        )
        fun fingerprint(at: Long, accuracy: Double? = 3.0, latitude: Double = 37.5, mock: Boolean = false) =
            GnssSampleFingerprint(at * 1_000_000L, 123_456L, "fused", latitude, 127.0, accuracy, mock)
        fun publish(at: Long, accuracy: Double? = 3.0, receipt: Long = at, latitude: Double = 37.5, mock: Boolean = false): PositioningSnapshot? {
            now = receipt
            val sample = fingerprint(at, accuracy, latitude, mock)
            if (session.shouldSkipAcceptedGnssSample(sample, now, 1.0)) return null
            val snapshot = coordinator.observeGnss(GnssPositionObservation(latitude, 127.0, accuracy, at, receivedAtElapsedRealtimeMs = receipt, mock = mock))
            session.recordPositioningGpsObservation(snapshot, at, accuracy)
            session.recordAcceptedGnssSample(sample, snapshot.gnssUpdate?.disposition, receipt, 1.0)
            if (snapshot.gnssUpdate?.disposition != GnssObservationDisposition.HARD_REJECTED && accuracy != null && accuracy.isFinite() && accuracy in 0.0..15.0) {
                trusted = TrustedLocation(latitude, 127.0, requireNotNull(snapshot.filtered).horizontalUncertaintyM.toFloat(), at)
                raw = TrustedLocation(latitude, 127.0, accuracy.toFloat(), at)
            }
            return snapshot
        }
        fun unreadLocation(): Location {
            val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return (f.get(null) as sun.misc.Unsafe).allocateInstance(Location::class.java) as Location
        }
    }
}
