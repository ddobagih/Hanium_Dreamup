package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationTestLocationPolicyTest {
    private val fix = TrustedLocation(37.5665, 126.9780, 120f, 10_000L)

    @Test fun impreciseRealFixIsRetainedWithoutChangingItsAccuracyOrTimestamp() {
        assertSame(fix, NavigationTestLocationPolicy.freshOrNull(fix, 10_500L))
        assertEquals(120f, fix.accuracyM)
        assertNull(LocationTrustPolicy.trustedOrNull(fix.latitude, fix.longitude, fix.accuracyM, fix.elapsedRealtimeMs))
    }

    @Test fun staleFutureMockAndMissingFixAreNotMadeIntoCurrentLocation() {
        assertNull(NavigationTestLocationPolicy.freshOrNull(fix, 20_001L))
        assertNull(NavigationTestLocationPolicy.freshOrNull(fix, 9_999L))
        assertNull(NavigationTestLocationPolicy.freshOrNull(fix, 10_000L, mock = true))
        assertNull(NavigationTestLocationPolicy.freshOrNull(null, 10_000L))
        assertSame(fix, NavigationTestLocationPolicy.freshOrNull(fix, 20_000L))
    }

    @Test fun malformedCoordinatesAndAccuracyNeverReachRouteProjection() {
        listOf(
            fix.copy(latitude = Double.NaN), fix.copy(latitude = 91.0),
            fix.copy(longitude = Double.POSITIVE_INFINITY), fix.copy(longitude = -181.0),
            fix.copy(accuracyM = Float.NaN), fix.copy(accuracyM = -1f),
            fix.copy(elapsedRealtimeMs = -1L),
        ).forEach { assertNull(NavigationTestLocationPolicy.freshOrNull(it, 10_000L)) }
    }
}
