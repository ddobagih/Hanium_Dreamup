package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationPolicyTest {
    @Test
    fun rejectsNullOrPoorAccuracyForRouteDecisions() {
        assertNull(LocationTrustPolicy.trustedOrNull(37.0, 127.0, null, 1_000L))
        assertNull(LocationTrustPolicy.trustedOrNull(37.0, 127.0, 15.1f, 1_000L))
    }

    @Test
    fun rejectsLargeGpsJump() {
        val previous = LocationTrustPolicy.trustedOrNull(37.0, 127.0, 5f, 1_000L)

        val jumped = LocationTrustPolicy.trustedOrNull(37.01, 127.01, 5f, 2_000L, previous = previous)

        assertNull(jumped)
    }

    @Test
    fun rejectsLargeGpsJumpEvenWithinHalfASecond() {
        val previous = LocationTrustPolicy.trustedOrNull(37.0, 127.0, 5f, 1_000L)

        val jumped = LocationTrustPolicy.trustedOrNull(37.01, 127.01, 5f, 1_200L, previous = previous)

        assertNull(jumped)
    }

    @Test
    fun retainedLastAcceptedFixContinuesToRejectRepeatedOutlier() {
        val previous = LocationTrustPolicy.trustedOrNull(37.0, 127.0, 5f, 1_000L)

        assertNull(LocationTrustPolicy.trustedOrNull(37.01, 127.01, 5f, 1_200L, previous = previous))
        assertNull(LocationTrustPolicy.trustedOrNull(37.01, 127.01, 5f, 1_400L, previous = previous))
    }

    @Test
    fun acceptsTrustedFix() {
        val trusted = LocationTrustPolicy.trustedOrNull(37.0, 127.0, 15f, 1_000L)

        assertNotNull(trusted)
    }

    @Test
    fun rejectsMockFixWithoutReplacingPreviousTrustedFix() {
        val previous = LocationTrustPolicy.trustedOrNull(37.0, 127.0, 5f, 1_000L)

        assertNull(
            LocationTrustPolicy.trustedOrNull(
                latitude = 37.00001,
                longitude = 127.00001,
                accuracyM = 5f,
                elapsedRealtimeMs = 2_000L,
                previous = previous,
                mock = true,
            ),
        )
        assertNotNull(previous)
    }

    @Test
    fun clearsFixesThatAreOldOrFromTheFuture() {
        val trusted = TrustedLocation(37.0, 127.0, 5f, 1_000L)

        assertNotNull(LocationTrustPolicy.freshOrNull(trusted, nowElapsedRealtimeMs = 11_000L))
        assertNull(LocationTrustPolicy.freshOrNull(trusted, nowElapsedRealtimeMs = 11_001L))
        assertNull(LocationTrustPolicy.freshOrNull(trusted, nowElapsedRealtimeMs = 999L))
    }

    @Test
    fun rejectsInvalidCoordinatesAndNonIncreasingFixTime() {
        assertNull(LocationTrustPolicy.trustedOrNull(91.0, 127.0, 5f, 1_000L))
        val previous = TrustedLocation(37.0, 127.0, 5f, 1_000L)
        assertNull(LocationTrustPolicy.trustedOrNull(37.0, 127.0, 5f, 1_000L, previous))
    }

    @Test
    fun movementHeadingRejectsStationaryOrInaccurateBearing() {
        val current = TrustedLocation(37.0, 127.0, 5f, 2_000L)

        assertNull(reliableMovementHeadingDegrees(90f, 0.1f, 5f, null, current))
        assertNull(reliableMovementHeadingDegrees(90f, 1f, 50f, null, current))
        assertEquals(90f, reliableMovementHeadingDegrees(90f, 1f, 5f, null, current)!!, 0.001f)
    }

    @Test
    fun movementHeadingRequiresDisplacementBeyondBothFixUncertainties() {
        val previous = TrustedLocation(37.0, 127.0, 5f, 1_000L)
        val noiseOnly = TrustedLocation(37.0, 127.00008, 5f, 2_000L)
        val clearMovement = TrustedLocation(37.0, 127.00015, 5f, 3_000L)

        assertNull(reliableMovementHeadingDegrees(null, null, null, previous, noiseOnly))
        assertEquals(90f, reliableMovementHeadingDegrees(null, null, null, previous, clearMovement)!!, 0.1f)
    }

    @Test
    fun reportedStopBlocksDisplacementFallbackButSlowMovementCanUseIt() {
        val previous = TrustedLocation(37.0, 127.0, 5f, 1_000L)
        val apparentJump = TrustedLocation(37.0, 127.00015, 5f, 3_000L)
        val slowMovement = apparentJump.copy(elapsedRealtimeMs = 131_000L)

        assertNull(reliableMovementHeadingDegrees(90f, 0f, 5f, previous, apparentJump))
        assertEquals(90f, reliableMovementHeadingDegrees(null, 0.1f, null, previous, slowMovement)!!, 0.1f)
    }
}
