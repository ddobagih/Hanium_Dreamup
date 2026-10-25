package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.*
import org.junit.Test

class AcceptedGnssSampleRegistryTest {
    private val ageMs = PedestrianPositionEstimatorConfig().maximumObservationAgeMs
    private val owner = Any()
    private val sample = GnssSampleFingerprint(1_000_123_456L, 123_456L, "fused", 37.5, 127.0, 3.0, false)

    @Test fun onlyAnActuallyAcceptedExactSampleCanBeSkipped() {
        val registry = AcceptedGnssSampleRegistry<Any>(ageMs)
        assertFalse(registry.shouldSkip(owner, sample, 1_000L, 1.0))
        registry.recordAccepted(owner, sample, GnssObservationDisposition.HARD_REJECTED, 1_000L, 1.0)
        assertFalse(registry.shouldSkip(owner, sample, 1_001L, 1.0))
        registry.recordAccepted(owner, sample, null, 1_000L, 1.0)
        assertFalse(registry.shouldSkip(owner, sample, 1_001L, 1.0))
        registry.recordAccepted(owner, sample, GnssObservationDisposition.APPLIED, 1_000L, 1.0)
        assertTrue(registry.shouldSkip(owner, sample, 1_001L, 1.0))
    }

    @Test fun everyAcceptedDispositionIsEligibleButDoesNotRenewTheOriginalInclusiveExpiry() {
        for (disposition in GnssObservationDisposition.entries.filter { it != GnssObservationDisposition.HARD_REJECTED }) {
            val registry = AcceptedGnssSampleRegistry<Any>(ageMs)
            registry.recordAccepted(owner, sample, disposition, 1_000L, 1.0)
            registry.recordAccepted(owner, sample, disposition, 5_000L, 1.0)
            assertTrue(registry.shouldSkip(owner, sample, 1_000L + ageMs, 1.0))
            assertFalse(registry.shouldSkip(owner, sample, 1_001L + ageMs, 1.0))
        }
    }

    @Test fun validSignalWeightChangesAreNotIdentityButInvalidWeightsAreNeverHidden() {
        val registry = acceptedRegistry()
        assertTrue(registry.shouldSkip(owner, sample, 1_100L, 4.0))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.99, -1.0)) {
            assertFalse(registry.shouldSkip(owner, sample, 1_100L, invalid))
            val empty = AcceptedGnssSampleRegistry<Any>(ageMs)
            empty.recordAccepted(owner, sample, GnssObservationDisposition.APPLIED, 1_100L, invalid)
            assertFalse(empty.shouldSkip(owner, sample, 1_100L, 1.0))
        }
    }

    @Test fun originalNanosecondsAndEveryRawMetadataFieldDistinguishSamples() {
        val registry = acceptedRegistry()
        val changed = listOf(
            sample.copy(elapsedRealtimeNanos = sample.elapsedRealtimeNanos + 1L),
            sample.copy(timeMillis = sample.timeMillis + 1L), sample.copy(provider = "gps"),
            sample.copy(latitude = 37.500001), sample.copy(longitude = 127.000001),
            sample.copy(horizontalAccuracyM = 4.0), sample.copy(horizontalAccuracyM = null),
            sample.copy(mock = true), sample.copy(altitudeM = 2.0), sample.copy(speedMps = 0.0),
            sample.copy(bearingDegrees = 0.0), sample.copy(verticalAccuracyM = 1.0),
            sample.copy(speedAccuracyMps = 1.0), sample.copy(bearingAccuracyDegrees = 1.0),
            sample.copy(elapsedRealtimeUncertaintyNanos = 1.0), sample.copy(mslAltitudeM = 1.0),
            sample.copy(mslAltitudeAccuracyM = 1.0),
        )
        changed.forEach { assertFalse("Changed raw measurement must use ordinary validation: $it",
            registry.shouldSkip(owner, it, 1_001L, 1.0)) }
        assertTrue(registry.shouldSkip(owner, sample, 1_001L, 1.0))
    }

    @Test fun invalidMetadataCannotBeRegisteredEvenWithAnAcceptedDisposition() {
        val invalidSamples = listOf(
            sample.copy(mock = true), sample.copy(latitude = Double.NaN), sample.copy(latitude = 90.0),
            sample.copy(longitude = 181.0), sample.copy(horizontalAccuracyM = -1.0),
            sample.copy(horizontalAccuracyM = Double.NaN), sample.copy(elapsedRealtimeNanos = -1L),
            sample.copy(timeMillis = -1L), sample.copy(altitudeM = Double.POSITIVE_INFINITY),
            sample.copy(speedMps = -1.0), sample.copy(bearingDegrees = 360.0),
            sample.copy(verticalAccuracyM = Double.NaN), sample.copy(speedAccuracyMps = -1.0),
            sample.copy(bearingAccuracyDegrees = Double.POSITIVE_INFINITY),
            sample.copy(elapsedRealtimeUncertaintyNanos = -1.0),
            sample.copy(mslAltitudeM = Double.NaN), sample.copy(mslAltitudeAccuracyM = -1.0),
        )
        for (invalid in invalidSamples) {
            val registry = AcceptedGnssSampleRegistry<Any>(ageMs)
            registry.recordAccepted(owner, invalid, GnssObservationDisposition.APPLIED, 1_000L, 1.0)
            assertFalse(registry.shouldSkip(owner, invalid, 1_000L, 1.0))
        }
    }

    @Test fun negativeFutureAndOverflowSizedReceiptCannotHideValidation() {
        val registry = acceptedRegistry()
        assertFalse(registry.shouldSkip(owner, sample, -1L, 1.0))
        assertFalse(registry.shouldSkip(owner, sample, 999L, 1.0))
        assertFalse(registry.shouldSkip(owner, sample, Long.MAX_VALUE, 1.0))
        assertFalse(registry.shouldSkip(owner, sample.copy(elapsedRealtimeNanos = Long.MAX_VALUE), 1_000L, 1.0))
    }

    @Test fun sourceChangeResetAndReturnToAnOldSourceNeverRestoreTheOldLedger() {
        data class Owner(val binding: Any, val generation: Long, val epoch: Long, val profile: String)
        val binding = Any()
        val first = Owner(binding, 1L, 1L, "profile-a")
        for (next in listOf(first.copy(binding = Any()), first.copy(generation = 2L),
            first.copy(epoch = 2L), first.copy(profile = "profile-b"))) {
            val registry = AcceptedGnssSampleRegistry<Owner>(ageMs)
            registry.recordAccepted(first, sample, GnssObservationDisposition.INITIALIZED, 1_000L, 1.0)
            assertTrue(registry.shouldSkip(first.copy(), sample, 1_000L, 1.0))
            assertFalse(registry.shouldSkip(next, sample, 1_000L, 1.0))
            assertFalse(registry.shouldSkip(first, sample, 1_000L, 1.0))
            registry.recordAccepted(first, sample, GnssObservationDisposition.APPLIED, 1_000L, 1.0)
            registry.reset()
            assertFalse(registry.shouldSkip(first, sample, 1_000L, 1.0))
        }
    }

    @Test fun onlyLatestAcceptedSampleIsRememberedAndLateResultsCannotReplaceIt() {
        val registry = acceptedRegistry()
        val newer = sample.copy(elapsedRealtimeNanos = 2_000_000_000L)
        registry.recordAccepted(owner, newer, GnssObservationDisposition.APPLIED, 2_000L, 1.0)
        registry.recordAccepted(owner, sample, GnssObservationDisposition.APPLIED, 2_001L, 1.0)
        registry.recordAccepted(owner, newer.copy(latitude = 37.500001), GnssObservationDisposition.APPLIED, 2_001L, 1.0)
        assertTrue(registry.shouldSkip(owner, newer, 2_001L, 1.0))
        assertFalse(registry.shouldSkip(owner, sample, 2_001L, 1.0))
    }

    @Test fun rejectedCandidateDoesNotBecomeAcceptedOrReplaceTheLedger() {
        val registry = acceptedRegistry()
        val rejected = sample.copy(elapsedRealtimeNanos = 2_000_000_000L, latitude = 38.0)
        registry.recordAccepted(owner, rejected, GnssObservationDisposition.HARD_REJECTED, 2_000L, 1.0)
        assertFalse(registry.shouldSkip(owner, rejected, 2_001L, 1.0))
        assertTrue(registry.shouldSkip(owner, sample, 2_001L, 1.0))
    }

    @Test fun onlyAValidatedStrictlyOlderOutOfOrderResultHasSupersedingAcceptedProof() {
        val registry = acceptedRegistry()
        val older = sample.copy(elapsedRealtimeNanos = 999_000_000L, latitude = 37.500001)
        assertTrue(registry.isSuperseded(owner, older, GnssObservationDisposition.HARD_REJECTED,
            GnssHardRejectReason.OUT_OF_ORDER, 1_100L, 1.0))
        assertTrue(registry.shouldSkip(owner, sample, 1_100L, 1.0))
        assertFalse(registry.shouldSkip(owner, older, 1_100L, 1.0))
        for (reason in GnssHardRejectReason.entries.filter { it != GnssHardRejectReason.OUT_OF_ORDER }) {
            assertFalse(registry.isSuperseded(owner, older, GnssObservationDisposition.HARD_REJECTED, reason, 1_100L, 1.0))
        }
        assertFalse(registry.isSuperseded(owner, older, GnssObservationDisposition.APPLIED,
            GnssHardRejectReason.OUT_OF_ORDER, 1_100L, 1.0))
        assertFalse(registry.isSuperseded(owner, sample.copy(latitude = 37.500001),
            GnssObservationDisposition.HARD_REJECTED, GnssHardRejectReason.OUT_OF_ORDER, 1_100L, 1.0))
        assertFalse(registry.isSuperseded(owner, sample.copy(elapsedRealtimeNanos = 1_001_000_000L),
            GnssObservationDisposition.HARD_REJECTED, GnssHardRejectReason.OUT_OF_ORDER, 1_100L, 1.0))
    }

    @Test fun historicalInputAndAcceptedProofMustBothRemainValidForTheCurrentSource() {
        val older = sample.copy(elapsedRealtimeNanos = 999_000_000L)
        val registry = acceptedRegistry()
        fun superseded(candidate: GnssSampleFingerprint, at: Long, multiplier: Double = 1.0) =
            registry.isSuperseded(owner, candidate, GnssObservationDisposition.HARD_REJECTED,
                GnssHardRejectReason.OUT_OF_ORDER, at, multiplier)
        assertTrue(superseded(older, 999L + ageMs))
        assertFalse(superseded(older, 1_000L + ageMs))
        assertFalse(superseded(older, 1_001L + ageMs))
        assertFalse(superseded(older, 998L))
        assertFalse(superseded(older, -1L))
        assertFalse(superseded(older.copy(mock = true), 1_100L))
        assertFalse(superseded(older.copy(latitude = Double.NaN), 1_100L))
        assertFalse(superseded(older.copy(horizontalAccuracyM = -1.0), 1_100L))
        assertFalse(superseded(older.copy(speedAccuracyMps = Double.NaN), 1_100L))
        assertFalse(superseded(older, 1_100L, Double.NaN))
        assertFalse(registry.isSuperseded(Any(), older, GnssObservationDisposition.HARD_REJECTED,
            GnssHardRejectReason.OUT_OF_ORDER, 1_100L, 1.0))
        assertFalse(superseded(older, 1_100L))
    }

    @Test fun neverAcceptedOrRejectedInputCannotProveThatALaterDeliveryIsSuperseded() {
        val registry = AcceptedGnssSampleRegistry<Any>(ageMs)
        val older = sample.copy(elapsedRealtimeNanos = 999_000_000L)
        registry.recordAccepted(owner, sample, GnssObservationDisposition.HARD_REJECTED, 1_000L, 1.0)
        assertFalse(registry.isSuperseded(owner, older, GnssObservationDisposition.HARD_REJECTED,
            GnssHardRejectReason.OUT_OF_ORDER, 1_100L, 1.0))
    }

    @Test fun strictlyHistoricalInvalidAccuracyCannotReplaceOrInvalidateTheNewerAcceptedLedger() {
        for (accuracy in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val registry = acceptedRegistry()
            val older = sample.copy(elapsedRealtimeNanos = 999_000_000L, horizontalAccuracyM = accuracy)
            assertTrue(registry.isSupersededInvalidAccuracy(owner, older, 1_100L, 1.0))
            assertFalse(registry.shouldSkip(owner, older, 1_100L, 1.0))
            registry.recordAccepted(owner, older, GnssObservationDisposition.HARD_REJECTED, 1_100L, 1.0)
            assertTrue(registry.shouldSkip(owner, sample, 1_100L, 1.0))
            assertEquals(1_000_123_456L, sample.elapsedRealtimeNanos)
            assertEquals(3.0, sample.horizontalAccuracyM)
            assertTrue(registry.shouldSkip(owner, sample, 1_000L + ageMs, 1.0))
            assertFalse(registry.shouldSkip(owner, sample, 1_001L + ageMs, 1.0))
        }
    }

    @Test fun historicalAccuracyRefusalStillRequiresStrictTimeMetadataFreshnessAndOwnerProof() {
        val older = sample.copy(elapsedRealtimeNanos = 999_000_000L, horizontalAccuracyM = Double.NaN)
        val invalid = listOf(
            older.copy(mock = true), older.copy(latitude = Double.NaN), older.copy(latitude = 90.0),
            older.copy(longitude = 181.0), older.copy(elapsedRealtimeNanos = -1L),
            older.copy(timeMillis = -1L), older.copy(altitudeM = Double.POSITIVE_INFINITY),
            older.copy(speedMps = -1.0), older.copy(bearingDegrees = 360.0),
            older.copy(verticalAccuracyM = Double.NaN), older.copy(speedAccuracyMps = -1.0),
            older.copy(bearingAccuracyDegrees = Double.POSITIVE_INFINITY),
            older.copy(elapsedRealtimeUncertaintyNanos = -1.0),
            older.copy(mslAltitudeM = Double.NaN), older.copy(mslAltitudeAccuracyM = -1.0),
            older.copy(elapsedRealtimeNanos = sample.elapsedRealtimeNanos),
            older.copy(elapsedRealtimeNanos = sample.elapsedRealtimeNanos + 1L),
            older.copy(horizontalAccuracyM = null), older.copy(horizontalAccuracyM = 3.0),
        )
        for (candidate in invalid) {
            assertFalse(acceptedRegistry().isSupersededInvalidAccuracy(owner, candidate, 1_100L, 1.0))
        }
        assertTrue("The newer accepted proof is still fresh at its inclusive age boundary",
            acceptedRegistry().isSupersededInvalidAccuracy(owner, older, 1_000L + ageMs, 1.0))
        for (at in listOf(-1L, 998L, 1_001L + ageMs, Long.MAX_VALUE)) {
            assertFalse(acceptedRegistry().isSupersededInvalidAccuracy(owner, older, at, 1.0))
        }
        for (noise in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.99, -1.0)) {
            assertFalse(acceptedRegistry().isSupersededInvalidAccuracy(owner, older, 1_100L, noise))
        }
        assertFalse(AcceptedGnssSampleRegistry<Any>(ageMs)
            .isSupersededInvalidAccuracy(owner, older, 1_100L, 1.0))
        val registry = acceptedRegistry()
        assertFalse(registry.isSupersededInvalidAccuracy(Any(), older, 1_100L, 1.0))
        assertFalse(registry.isSupersededInvalidAccuracy(owner, older, 1_100L, 1.0))
    }

    @Test fun historicalInvalidAccuracyAndAgeCannotInvalidateTheNewerAcceptedLedger() {
        val receipt = 1_000L + ageMs
        for (accuracy in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val registry = acceptedRegistry()
            val older = sample.copy(elapsedRealtimeNanos = 999_000_000L, horizontalAccuracyM = accuracy)
            assertTrue(registry.isSupersededInvalidAccuracy(owner, older, receipt, 1.0))
            assertFalse("The refused sample never becomes an accepted duplicate", registry.shouldSkip(owner, older, receipt, 1.0))
            registry.recordAccepted(owner, older, GnssObservationDisposition.HARD_REJECTED, receipt, 1.0)
            assertTrue(registry.shouldSkip(owner, sample, receipt, 1.0))
            assertFalse("No renewal of the accepted sample expiry", registry.isSupersededInvalidAccuracy(owner, older, receipt + 1L, 1.0))
            assertFalse(registry.shouldSkip(owner, sample, receipt + 1L, 1.0))
        }
    }

    @Test fun combinedHistoricalFailureKeepsOwnerMockTimeAndOptionalMetadataAdmission() {
        val older = sample.copy(elapsedRealtimeNanos = 999_000_000L, horizontalAccuracyM = Double.NaN)
        val receipt = 1_000L + ageMs
        val invalid = listOf(
            older.copy(mock = true), older.copy(latitude = Double.NaN), older.copy(longitude = 181.0),
            older.copy(elapsedRealtimeNanos = -1L), older.copy(timeMillis = -1L),
            older.copy(elapsedRealtimeNanos = (receipt + 1L) * 1_000_000L),
            older.copy(altitudeM = Double.POSITIVE_INFINITY), older.copy(speedMps = -1.0),
            older.copy(bearingDegrees = 360.0), older.copy(verticalAccuracyM = Double.NaN),
            older.copy(speedAccuracyMps = -1.0), older.copy(bearingAccuracyDegrees = Double.POSITIVE_INFINITY),
            older.copy(elapsedRealtimeUncertaintyNanos = -1.0), older.copy(mslAltitudeM = Double.NaN),
            older.copy(mslAltitudeAccuracyM = -1.0), older.copy(elapsedRealtimeNanos = sample.elapsedRealtimeNanos),
        )
        for (candidate in invalid) {
            assertFalse("Malformed metadata=$candidate", acceptedRegistry().isSupersededInvalidAccuracy(owner, candidate, receipt, 1.0))
        }
        assertFalse(acceptedRegistry().isSupersededInvalidAccuracy(owner, older, receipt, Double.NaN))
        assertFalse(AcceptedGnssSampleRegistry<Any>(ageMs).isSupersededInvalidAccuracy(owner, older, receipt, 1.0))
        val registry = acceptedRegistry()
        assertFalse(registry.isSupersededInvalidAccuracy(Any(), older, receipt, 1.0))
        assertFalse("A retired owner cannot recover its previous proof", registry.isSupersededInvalidAccuracy(owner, older, receipt, 1.0))
    }

    @Test fun expiredHistoricalInputCannotRetireTheNewerFreshAcceptedLedger() {
        val registry = acceptedRegistry()
        val older = sample.copy(elapsedRealtimeNanos = 999_000_000L)
        val now = 1_000L + ageMs
        assertTrue(registry.isSupersededStale(owner, older, now, 1.0))
        assertFalse(registry.shouldSkip(owner, older, now, 1.0))
        registry.recordAccepted(owner, older, GnssObservationDisposition.HARD_REJECTED, now, 1.0)
        assertTrue(registry.shouldSkip(owner, sample, now, 1.0))
        assertEquals(1_000_123_456L, sample.elapsedRealtimeNanos)
        assertFalse(registry.isSupersededStale(owner, older, now + 1L, 1.0))
    }

    @Test fun historicalStaleExclusionDoesNotHideOtherMalformedMetadataOrNoise() {
        val older = sample.copy(elapsedRealtimeNanos = 999_000_000L)
        val now = 1_000L + ageMs
        val malformed = listOf(
            older.copy(mock = true), older.copy(latitude = Double.NaN), older.copy(latitude = 90.0),
            older.copy(longitude = 181.0), older.copy(elapsedRealtimeNanos = -1L),
            older.copy(timeMillis = -1L), older.copy(horizontalAccuracyM = Double.NaN),
            older.copy(horizontalAccuracyM = -1.0), older.copy(altitudeM = Double.POSITIVE_INFINITY),
            older.copy(speedMps = -1.0), older.copy(bearingDegrees = 360.0),
            older.copy(verticalAccuracyM = Double.NaN), older.copy(speedAccuracyMps = -1.0),
            older.copy(bearingAccuracyDegrees = Double.POSITIVE_INFINITY),
            older.copy(elapsedRealtimeUncertaintyNanos = -1.0),
            older.copy(mslAltitudeM = Double.NaN), older.copy(mslAltitudeAccuracyM = -1.0),
        )
        malformed.forEach { assertFalse(acceptedRegistry().isSupersededStale(owner, it, now, 1.0)) }
        for (noise in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.99, -1.0)) {
            assertFalse(acceptedRegistry().isSupersededStale(owner, older, now, noise))
        }
    }

    @Test fun staleExclusionRequiresStrictPastTimeAndTheSameLiveAcceptedOwner() {
        val older = sample.copy(elapsedRealtimeNanos = 999_000_000L)
        val now = 1_000L + ageMs
        val registry = acceptedRegistry()
        assertFalse(registry.isSupersededStale(owner, older, now - 1L, 1.0))
        assertFalse(registry.isSupersededStale(owner, older, -1L, 1.0))
        assertFalse(registry.isSupersededStale(owner, sample, now, 1.0))
        assertFalse(registry.isSupersededStale(owner,
            older.copy(elapsedRealtimeNanos = (now + 1L) * 1_000_000L), now, 1.0))
        assertFalse(AcceptedGnssSampleRegistry<Any>(ageMs).isSupersededStale(owner, older, now, 1.0))
        assertFalse(registry.isSupersededStale(Any(), older, now, 1.0))
        assertFalse(registry.isSupersededStale(owner, older, now, 1.0))
    }

    private fun acceptedRegistry() = AcceptedGnssSampleRegistry<Any>(ageMs).also {
        it.recordAccepted(owner, sample, GnssObservationDisposition.INITIALIZED, 1_000L, 1.0)
    }
}
