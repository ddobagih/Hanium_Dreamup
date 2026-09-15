package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCompassLocationReferenceTest {
    @Test
    fun validFreshGeographicReferenceDoesNotDependOnWalkingAccuracyOrActiveRoute() {
        assertTrue(RouteCompassLocationReference.accepts(36.14, 128.39, 1_000L, 1_001L, false))
        assertTrue(RouteCompassLocationReference.accepts(-36.14, -128.39, 1_000L, 11_000L, false))
    }

    @Test
    fun expiredFutureAndNegativeTimeCannotSetTheReference() {
        assertFalse(RouteCompassLocationReference.accepts(36.14, 128.39, 1_000L, 11_001L, false))
        assertFalse(RouteCompassLocationReference.accepts(36.14, 128.39, 2_000L, 1_000L, false))
        assertFalse(RouteCompassLocationReference.accepts(36.14, 128.39, -1L, 1_000L, false))
    }

    @Test
    fun mockAndInvalidCoordinatesCannotWarmUpCompassReference() {
        assertFalse(RouteCompassLocationReference.accepts(36.14, 128.39, 1_000L, 1_001L, true))
        for ((lat, lon) in listOf(
            Double.NaN to 128.39, 36.14 to Double.POSITIVE_INFINITY,
            90.01 to 128.39, -90.01 to 128.39, 36.14 to 180.01, 36.14 to -180.01,
        )) assertFalse(RouteCompassLocationReference.accepts(lat, lon, 1_000L, 1_001L, false))
    }
}
