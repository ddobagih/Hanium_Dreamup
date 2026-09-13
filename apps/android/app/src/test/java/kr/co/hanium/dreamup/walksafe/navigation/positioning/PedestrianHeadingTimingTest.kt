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

    private fun input(now: Long, time: Long, heading: Double) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = now,
        speed = WalkingSpeedObservation(0.2, 0.2, now),
        gpsCourse = null,
        magneticTrueHeading = HeadingObservation(heading, 5.0, time),
        phoneForwardMounted = true,
    )
}
