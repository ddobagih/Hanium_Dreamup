package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianHeadingTimingTest {
    @Test
    fun repeatedHeadingObservationDoesNotRepeatSmoothing() {
        val selector = PedestrianHeadingSelector()
        selector.select(input(1_000L, 1_000L, 0.0))
        val first = requireNotNull(selector.select(input(1_100L, 1_100L, 10.0)))

        val repeated = requireNotNull(selector.select(input(1_200L, 1_100L, 10.0)))

        assertEquals(first.headingDegreesTrueNorth, repeated.headingDegreesTrueNorth, 0.0)
    }

    @Test
    fun distinctNinetyDegreeTurnRespondsWithoutFixedAlphaLag() {
        val selector = PedestrianHeadingSelector()
        selector.select(input(1_000L, 1_000L, 0.0))

        val turn = requireNotNull(selector.select(input(1_500L, 1_500L, 90.0)))

        assertTrue(
            "Expected turn response >=70deg, got ${turn.headingDegreesTrueNorth}",
            turn.headingDegreesTrueNorth >= 70.0,
        )
    }

    @Test
    fun smoothingResidualIsIncludedOnceInTheHeadingErrorBudget() {
        val selector = PedestrianHeadingSelector()
        selector.select(input(1_000L, 1_000L, 0.0))

        // S09-28: 0.35 is the gain per nominal magnetic interval (500 ms), not per call.
        val smoothed = requireNotNull(selector.select(input(1_500L, 1_500L, 10.0)))
        val repeated = requireNotNull(selector.select(input(1_600L, 1_500L, 10.0)))

        assertEquals(3.5, smoothed.headingDegreesTrueNorth, 1e-12)
        assertEquals(11.5, requireNotNull(smoothed.accuracyDegrees), 1e-12)
        assertEquals(smoothed.accuracyDegrees, repeated.accuracyDegrees)
        assertEquals(smoothed.sourceObservationAtElapsedRealtimeMs, repeated.sourceObservationAtElapsedRealtimeMs)
    }

    private fun input(now: Long, time: Long, heading: Double) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = now,
        speed = WalkingSpeedObservation(0.2, 0.2, now),
        gpsCourse = null,
        magneticTrueHeading = HeadingObservation(heading, 5.0, time),
        phoneForwardMounted = true,
    )
}
