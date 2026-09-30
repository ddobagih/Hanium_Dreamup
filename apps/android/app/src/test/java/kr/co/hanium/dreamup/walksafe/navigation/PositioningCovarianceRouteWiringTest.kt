package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.EnuPositionCovariance
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssPositionObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PdrStepQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianHeadingInput
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteCorridorMatcher
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.sqrt

/**
 * S09-45 (originals/09.txt:126): the operating path carries the filter's direction-specific ENU
 * covariance to the route matcher; the scalar accuracy remains only the fallback.
 */
class PositioningCovarianceRouteWiringTest {
    @Test
    fun filteredFixCarriesTheCovarianceBehindItsScalarUncertainty() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(GnssPositionObservation(37.0, 127.0, 3.0, 1_000L))
        var snapshot = coordinator.observeStep(1, heading(1_500L), PdrStepQuality.HIGH)
        for (at in 2_000L..4_000L step 500L) snapshot = coordinator.observeStep(1, heading(at), PdrStepQuality.HIGH)
        val filtered = requireNotNull(snapshot.filtered)
        val covariance = requireNotNull(filtered.covarianceEnu)
        assertEquals(filtered.horizontalUncertaintyM, sqrt(largestEigenvalue(covariance)), 1e-9)
        // Directed northward steps add along-track (north) variance; the ellipse is not a circle.
        assertTrue(covariance.northVarianceM2 > covariance.eastVarianceM2)
    }

    @Test
    fun navigatorPassesTheCovarianceToTheMatcherInsteadOfTheScalarRadius() {
        val route = WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 220, durationS = 180),
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.002, 127.0)),
            guidePoints = emptyList(),
        )
        // 4 m east of a north-south route: small cross-track (east) variance, large along-track.
        val location = TrustedLocation(37.001, 127.0 + 4.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(37.001))), 10f, 1_000L)
        val covariance = EnuPositionCovariance(eastVarianceM2 = 1.0, northVarianceM2 = 100.0)
        fun position(withCovariance: Boolean) = FilteredRoutePosition(
            point = RoutePoint(location.latitude, location.longitude),
            horizontalAccuracyM = 10.0,
            elapsedRealtimeMs = location.elapsedRealtimeMs,
            covarianceEnu = covariance.takeIf { withCovariance },
        )
        val results = listOf(true, false).associateWith { withCovariance ->
            val navigator = RouteNavigator(RouteNavigatorConfig(guidanceIntervalMs = 0))
            navigator.setRoute(route)
            navigator.update(location = location, nowMs = 1_000L, requestInFlight = false, filteredPosition = position(withCovariance))
            requireNotNull(navigator.currentRouteMatch())
        }
        val direct = RouteCorridorMatcher().match("r", route.polyline, position(withCovariance = true))
        val withCovariance = results.getValue(true)
        val scalar = results.getValue(false)
        assertNotEquals(scalar.confidence, withCovariance.confidence, 1e-6)
        // With a 1 m cross-track sigma a 4 m offset is a much weaker fit than with the 10 m scalar.
        assertTrue(withCovariance.confidence < scalar.confidence)
        assertEquals(direct.crossTrackDistanceM!!, withCovariance.crossTrackDistanceM!!, 1e-9)
    }

    private fun largestEigenvalue(c: EnuPositionCovariance): Double {
        val half = 0.5 * (c.eastVarianceM2 + c.northVarianceM2)
        val d = sqrt(max(0.0, 0.25 * (c.eastVarianceM2 - c.northVarianceM2) * (c.eastVarianceM2 - c.northVarianceM2) +
            c.eastNorthCovarianceM2 * c.eastNorthCovarianceM2))
        return half + d
    }

    private fun heading(at: Long) = PedestrianHeadingInput(
        at, WalkingSpeedObservation(1.0, 0.1, at), HeadingObservation(0.0, 5.0, at), null, false,
    )
}
