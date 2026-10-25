package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssHardRejectReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssObservationDisposition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssPositionObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssSampleFingerprint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningFilteredFix
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Exercises the admitted Main sample hooks, actual coordinator/GPS publisher and actual location
 * cleanup terminal. SDK delivery, full handleLocationUpdate admission, Looper and TTS are not run.
 * The source assertion binds the small rejected-snapshot dispatch below to the production branch.
 */
class MainActivitySupersededGnssObservationTest {
    @After fun clearSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun aValidOlderDeliveryMustNotEraseTheSameSourcesNewerAcceptedFreshLocation() {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val coordinator = base.field("positioningCoordinator") as PositioningCoordinator
        val positioning = MainActivityControllerTestFixture.positioning(base.main)
        val lookup = MethodHandles.privateLookupIn(positioning.javaClass, MethodHandles.lookup())
        val toTrusted = lookup.findVirtual(positioning.javaClass, "toTrustedLocation",
            MethodType.methodType(TrustedLocation::class.java, PositioningFilteredFix::class.java))
        val clearTrusted = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, "clearTrustedLocation",
            MethodType.methodType(Boolean::class.javaPrimitiveType))

        // A one-shot's real accepted estimate and original raw measurement are already published.
        val newest = GnssPositionObservation(37.5, 127.0, 3.0, 2_500L, 2_500L)
        base.now = newest.receivedAtElapsedRealtimeMs
        assertFalse(base.main.shouldSkipAcceptedGnssSample(fingerprint(newest), base.now, 1.0))
        val accepted = coordinator.observeGnss(newest, coordinator.currentInputGeneration())
        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, accepted.gnssUpdate?.disposition)
        assertTrue(base.main.mayPublishPositioningRouteFix(accepted.quality, accepted.confidence))
        base.main.recordPositioningGpsObservation(accepted, newest.elapsedRealtimeMs, newest.horizontalAccuracyM,
            { base.now }, {})
        base.main.recordAcceptedGnssSample(fingerprint(newest), accepted.gnssUpdate?.disposition, base.now, 1.0)
        val trusted = toTrusted.invokeWithArguments(positioning, requireNotNull(accepted.filtered)) as TrustedLocation
        val raw = requireNotNull(LocationTrustPolicy.trustedOrNull(newest.latitude, newest.longitude,
            newest.horizontalAccuracyM?.toFloat(), newest.elapsedRealtimeMs))
        base.set("latestTrustedLocation", trusted)
        base.set("latestRawRouteLocation", raw)
        val evidence = requireNotNull(base.evidence)
        assertEquals(EnvironmentEvidenceStatus.PASS, evidence.status)
        assertEquals(2_500L, trusted.elapsedRealtimeMs)
        assertEquals(2_500L, evidence.observedAtElapsedRealtimeMs)
        assertTrue("Exact accepted proof belongs to this current source",
            base.main.shouldSkipAcceptedGnssSample(fingerprint(newest), 3_000L, 1.0))
        assertSame(trusted, LocationTrustPolicy.freshOrNull(trusted, 3_000L))
        assertSame(raw, LocationTrustPolicy.freshOrNull(raw, 3_000L))

        // A distinct, valid stream fix arrives late. It must stay rejected as historical input.
        val older = newest.copy(latitude = 37.500001, elapsedRealtimeMs = 2_499L,
            receivedAtElapsedRealtimeMs = 3_000L)
        val probe = PositioningCoordinator()
        probe.observeGnss(newest, probe.currentInputGeneration())
        val oldResult = probe.observeGnss(older, probe.currentInputGeneration())
        assertEquals(GnssObservationDisposition.HARD_REJECTED, oldResult.gnssUpdate?.disposition)
        assertEquals(GnssHardRejectReason.OUT_OF_ORDER, oldResult.gnssUpdate?.hardRejectReason)

        assertRejectedCleanupIsConnected()
        base.now = older.receivedAtElapsedRealtimeMs
        if (!base.main.shouldSkipAcceptedGnssSample(fingerprint(older), base.now, 1.0)) {
            val snapshot = coordinator.observeGnss(older, coordinator.currentInputGeneration())
            assertEquals(GnssHardRejectReason.OUT_OF_ORDER, snapshot.gnssUpdate?.hardRejectReason)
            assertFalse(snapshot.singleOutlierAgainstFreshEstimate)
            if (!base.main.shouldIgnoreSupersededGnssSample(fingerprint(older), snapshot, base.now, 1.0)) {
                base.main.recordPositioningGpsObservation(snapshot, older.elapsedRealtimeMs, older.horizontalAccuracyM,
                    { base.now }, {})
                base.main.recordAcceptedGnssSample(fingerprint(older), snapshot.gnssUpdate?.disposition, base.now, 1.0)
                // The real Main terminal is invoked only for the production hard-rejection condition.
                if (snapshot.gnssUpdate?.disposition == GnssObservationDisposition.HARD_REJECTED || snapshot.filtered == null) {
                    clearTrusted.invokeWithArguments(base.main)
                }
            }
        }
        // Gate already correctly ignores the old FAIL; coordinate cleanup must agree with it.
        assertSame(evidence, base.evidence)
        assertSame(evidence, base.gate.latestRawObservation())
        assertNull(base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertSame(base.binding, base.field("positioningCollectionBinding"))
        assertSame(base.callback, base.field("locationCallback"))
        assertSame("A rejected older sample must not erase the newer source-owned trusted fix",
            trusted, base.field("latestTrustedLocation"))
        assertSame(raw, base.field("latestRawRouteLocation"))
    }

    @Test fun absentOrExpiredRetainedCoordinatesDoNotAuthorizeIgnoringAnOlderResult() {
        for (field in listOf("latestTrustedLocation", "latestRawRouteLocation")) {
            for (missing in listOf(true, false)) {
                val f = hookFixture()
                val original = f.base.field(field) as TrustedLocation
                f.base.set(field, if (missing) null else original.copy(elapsedRealtimeMs = 0L))
                val older = f.newest.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = 12_001L)
                val rejected = f.coordinator.observeGnss(older, f.coordinator.currentInputGeneration())
                assertEquals(GnssHardRejectReason.OUT_OF_ORDER, rejected.gnssUpdate?.hardRejectReason)
                assertFalse(f.base.main.shouldIgnoreSupersededGnssSample(fingerprint(older), rejected,
                    older.receivedAtElapsedRealtimeMs, 1.0))
            }
        }
    }

    @Test fun sameTimeConflictMockFutureExpiredAndInvalidMetadataRemainOrdinaryRejections() {
        val candidates = listOf<(GnssPositionObservation) -> GnssPositionObservation>(
            { it.copy(latitude = 37.500001, receivedAtElapsedRealtimeMs = 3_000L) },
            { it.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = 3_000L, mock = true) },
            { it.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = 2_498L) },
            { it.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = 12_500L) },
            { it.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = 3_000L, latitude = Double.NaN) },
            { it.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = 3_000L, horizontalAccuracyM = -1.0) },
            { it.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = 3_000L, measurementNoiseMultiplier = Double.NaN) },
        )
        for (candidate in candidates) {
            val f = hookFixture()
            val incoming = candidate(f.newest)
            val rejected = f.coordinator.observeGnss(incoming, f.coordinator.currentInputGeneration())
            assertEquals(GnssObservationDisposition.HARD_REJECTED, rejected.gnssUpdate?.disposition)
            assertFalse(f.base.main.shouldIgnoreSupersededGnssSample(fingerprint(incoming), rejected,
                incoming.receivedAtElapsedRealtimeMs, incoming.measurementNoiseMultiplier))
        }
    }

    @Test fun acceptedLedgerAndUnchangedCurrentCollectionAreBothRequired() {
        for (field in listOf("locationCallbackGeneration", "isActivityForeground", "positioningCollectionBinding")) {
            val f = hookFixture()
            val incoming = f.newest.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = 3_000L)
            val rejected = f.coordinator.observeGnss(incoming, f.coordinator.currentInputGeneration())
            val before = f.base.field(field)
            f.base.set(field, when (field) {
                "locationCallbackGeneration" -> 4
                "isActivityForeground" -> false
                else -> null
            })
            assertFalse(f.base.main.shouldIgnoreSupersededGnssSample(fingerprint(incoming), rejected, 3_000L, 1.0))
            f.base.set(field, before)
            assertFalse("An invalidated accepted proof must not revive with old fields",
                f.base.main.shouldIgnoreSupersededGnssSample(fingerprint(incoming), rejected, 3_000L, 1.0))
        }
    }

    @Test fun preservedQualityFailureKeepsItsOriginalDeadlineWhileHistoricalInputIsIgnored() {
        val f = hookFixture()
        val bad = f.newest.copy(elapsedRealtimeMs = 2_800L, receivedAtElapsedRealtimeMs = 2_800L,
            horizontalAccuracyM = 30.0)
        f.base.now = 2_800L
        val acceptedBad = f.coordinator.observeGnss(bad, f.coordinator.currentInputGeneration())
        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, acceptedBad.gnssUpdate?.disposition)
        f.base.main.recordPositioningGpsObservation(acceptedBad, bad.elapsedRealtimeMs, bad.horizontalAccuracyM,
            { f.base.now }, {})
        f.base.main.recordAcceptedGnssSample(fingerprint(bad), acceptedBad.gnssUpdate?.disposition, f.base.now, 1.0)
        assertEquals(18_000L, GuidanceRecoveryTiming.GPS_GRACE_MS)
        val rawFailure = f.base.gate.latestRawObservation()
        assertEquals(EnvironmentEvidenceStatus.FAIL, rawFailure?.status)
        assertEquals(2_800L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        val historical = f.newest.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = 5_000L)
        val rejected = f.coordinator.observeGnss(historical, f.coordinator.currentInputGeneration())
        assertTrue(f.base.main.shouldIgnoreSupersededGnssSample(fingerprint(historical), rejected, 5_000L, 1.0))
        assertSame(rawFailure, f.base.gate.latestRawObservation())
        assertEquals(2_800L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertNotEquals(EnvironmentEvidenceStatus.PASS,
            f.base.main.currentContinuousGpsEvidence(f.base.epoch, 2_800L + GuidanceRecoveryTiming.GPS_GRACE_MS - 1L)?.status)
        assertEquals(EnvironmentEvidenceStatus.FAIL,
            f.base.main.currentContinuousGpsEvidence(f.base.epoch, 2_800L + GuidanceRecoveryTiming.GPS_GRACE_MS)?.status)
    }

    @Test fun historicalInvalidAccuracyDoesNotRenewAnActiveFailureOrDiscardOriginalRouteFixes() {
        val f = hookFixture()
        val gps = MainActivityControllerTestFixture.controller(f.base.main, "continuousGpsSession") as
            kr.co.hanium.dreamup.walksafe.navigation.session.ContinuousGpsSession
        val trusted = f.base.field("latestTrustedLocation")
        val raw = f.base.field("latestRawRouteLocation")
        val bad = f.newest.copy(elapsedRealtimeMs = 2_800L, receivedAtElapsedRealtimeMs = 2_800L,
            horizontalAccuracyM = 30.0)
        f.base.now = 2_800L
        val snapshot = f.coordinator.observeGnss(bad, f.coordinator.currentInputGeneration())
        f.base.main.recordPositioningGpsObservation(snapshot, bad.elapsedRealtimeMs, bad.horizontalAccuracyM,
            { f.base.now }, {})
        f.base.main.recordAcceptedGnssSample(fingerprint(bad), snapshot.gnssUpdate?.disposition, f.base.now, 1.0)
        val originalFailure = f.base.gate.latestRawObservation()
        assertEquals(2_800L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())

        f.base.now = 5_000L
        val historical = f.newest.copy(elapsedRealtimeMs = 2_499L, receivedAtElapsedRealtimeMs = f.base.now,
            horizontalAccuracyM = Double.NaN)
        assertTrue(gps.shouldIgnoreSupersededInvalidAccuracyGnssSample(fingerprint(historical), f.base.now, 1.0))
        assertSame(originalFailure, f.base.gate.latestRawObservation())
        assertSame(trusted, f.base.field("latestTrustedLocation"))
        assertSame(raw, f.base.field("latestRawRouteLocation"))
        assertEquals(2_800L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())

        val current = historical.copy(elapsedRealtimeMs = f.base.now)
        assertFalse(gps.shouldIgnoreSupersededInvalidAccuracyGnssSample(fingerprint(current), f.base.now, 1.0))
        val refused = f.coordinator.observeGnss(current, f.coordinator.currentInputGeneration())
        assertEquals(GnssHardRejectReason.INVALID_ACCURACY, refused.gnssUpdate?.hardRejectReason)
        f.base.main.recordPositioningGpsObservation(refused, current.elapsedRealtimeMs, current.horizontalAccuracyM,
            { f.base.now }, {})
        assertEquals(2_800L + GuidanceRecoveryTiming.GPS_GRACE_MS, f.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.PASS,
            f.base.main.currentContinuousGpsEvidence(f.base.epoch, 11_799L)?.status)
        assertNotEquals(EnvironmentEvidenceStatus.PASS,
            f.base.main.currentContinuousGpsEvidence(f.base.epoch, 2_800L + GuidanceRecoveryTiming.GPS_GRACE_MS - 1L)?.status)
        assertEquals(EnvironmentEvidenceStatus.FAIL,
            f.base.main.currentContinuousGpsEvidence(f.base.epoch, 2_800L + GuidanceRecoveryTiming.GPS_GRACE_MS)?.status)
    }

    private fun assertRejectedCleanupIsConnected() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val handle = NavigationPolicySource.observation(source)
        assertTrue(MainActivityRefactorSource.member(source, "handleLocationUpdate")
            .contains("positioningSessionController.handleLocationUpdate(location, owner, locationGeneration, binding, receivedAtElapsedRealtimeMs)"))
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        val positioningPorts = ReportStaticSourceInspector.blockAfter(
            MainActivityRefactorSource.factory(source, "createNavigationSessions").also { factory ->
                assertEquals(1, Regex(Regex.escape("positions = PositioningAssemblyBindings(")).findAll(factory).count())
            }, "positions = PositioningAssemblyBindings(")
        assertTrue(positioningPorts.contains("override fun currentOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(positioningPorts.contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
        val ignore = handle.indexOf("shouldIgnoreSupersededGnssSample(")
        val ignoreInvalidAccuracy = handle.indexOf("shouldIgnoreSupersededInvalidAccuracyGnssSample(")
        assertTrue(ignoreInvalidAccuracy >= 0 && ignoreInvalidAccuracy < handle.indexOf("binding.coordinator.observeGnss("))
        val publish = handle.indexOf("recordPositioningGpsObservation(")
        val reject = handle.indexOf("if (hardRejected || filtered == null)")
        val cleanup = handle.indexOf("clearTrustedLocation()", reject)
        assertTrue(ignore >= 0 && publish > ignore && reject > publish && cleanup > reject)
        assertTrue(handle.substring(handle.lastIndexOf("if (", ignore), ignore).contains(
            "gps().currentSource() == acceptedSampleSource"))
        assertTrue(handle.substring(reject, cleanup).trimEnd().endsWith("{"))
        assertTrue(handle.substring(cleanup).contains("effects.pauseDirection("))
    }

    private data class HookFixture(
        val base: MainActivityContinuousLocationStartBehaviorTest.Fixture,
        val coordinator: PositioningCoordinator,
        val newest: GnssPositionObservation,
    )

    private fun hookFixture(): HookFixture {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val coordinator = base.field("positioningCoordinator") as PositioningCoordinator
        val newest = GnssPositionObservation(37.5, 127.0, 3.0, 2_500L, 2_500L)
        base.now = 2_500L
        val accepted = coordinator.observeGnss(newest, coordinator.currentInputGeneration())
        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, accepted.gnssUpdate?.disposition)
        base.main.recordPositioningGpsObservation(accepted, newest.elapsedRealtimeMs, newest.horizontalAccuracyM,
            { base.now }, {})
        base.main.recordAcceptedGnssSample(fingerprint(newest), accepted.gnssUpdate?.disposition, base.now, 1.0)
        val positioning = MainActivityControllerTestFixture.positioning(base.main)
        val lookup = MethodHandles.privateLookupIn(positioning.javaClass, MethodHandles.lookup())
        val toTrusted = lookup.findVirtual(positioning.javaClass, "toTrustedLocation",
            MethodType.methodType(TrustedLocation::class.java, PositioningFilteredFix::class.java))
        base.set("latestTrustedLocation", toTrusted.invokeWithArguments(positioning, requireNotNull(accepted.filtered)))
        base.set("latestRawRouteLocation", requireNotNull(LocationTrustPolicy.trustedOrNull(
            newest.latitude, newest.longitude, newest.horizontalAccuracyM?.toFloat(), newest.elapsedRealtimeMs)))
        return HookFixture(base, coordinator, newest)
    }

    private fun fingerprint(observation: GnssPositionObservation) = GnssSampleFingerprint(
        observation.elapsedRealtimeMs * 1_000_000L, 123_456L, "fused",
        observation.latitude, observation.longitude, observation.horizontalAccuracyM, observation.mock,
    )
}
