package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PedestrianHeadingSelectorTest {
    @Test
    fun prefersMagneticAtOrBelowLowSpeedBoundary() {
        val selector = selector()

        val result = selector.select(input(now = 1_000L, speedMps = 0.5, gps = 90.0, magnetic = 370.0))

        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, requireNotNull(result).source)
        assertEquals(10.0, result.headingDegreesTrueNorth, 1e-9)
    }

    @Test
    fun prefersGpsAtOrAboveHighSpeedBoundary() {
        val selector = selector()

        val result = selector.select(input(now = 1_000L, speedMps = 0.8, gps = 95.0, magnetic = 15.0))

        assertEquals(PedestrianHeadingSource.GPS_COURSE, requireNotNull(result).source)
        assertEquals(95.0, result.headingDegreesTrueNorth, 1e-9)
    }

    @Test
    fun hysteresisBandRetainsPreviouslySelectedSource() {
        val selector = selector(requiredConsecutive = 1)
        selector.select(input(now = 0L, speedMps = 0.3, gps = 90.0, magnetic = 10.0))

        val magnetic = selector.select(input(now = 100L, speedMps = 0.65, gps = 90.0, magnetic = 20.0))
        selector.select(input(now = 200L, speedMps = 0.9, gps = 100.0, magnetic = 20.0))
        val gps = selector.select(input(now = 300L, speedMps = 0.65, gps = 110.0, magnetic = 20.0))

        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, requireNotNull(magnetic).source)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, requireNotNull(gps).source)
    }

    @Test
    fun switchRequiresMinimumHoldAndConsecutiveNewObservations() {
        val selector = selector(minimumHoldMs = 1_000L, requiredConsecutive = 2)
        selector.select(input(now = 0L, speedMps = 0.3, gps = 90.0, magnetic = 10.0))

        val first = selector.select(input(now = 100L, speedMps = 1.0, gps = 90.0, magnetic = 10.0))
        val duplicate = selector.select(input(now = 200L, speedMps = 1.0, gps = 90.0, magnetic = 10.0, gpsAt = 100L))
        val held = selector.select(input(now = 500L, speedMps = 1.0, gps = 95.0, magnetic = 10.0))
        val switched = selector.select(input(now = 1_000L, speedMps = 1.0, gps = 100.0, magnetic = 10.0))

        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, requireNotNull(first).source)
        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, requireNotNull(duplicate).source)
        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, requireNotNull(held).source)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, requireNotNull(switched).source)
    }

    @Test
    fun invalidPreferredSourceResetsConsecutiveSwitchEvidence() {
        val selector = selector(requiredConsecutive = 2)
        selector.select(input(now = 0L, speedMps = 0.2, gps = 80.0, magnetic = 10.0))
        selector.select(input(now = 100L, speedMps = 1.0, gps = 80.0, magnetic = 10.0))
        selector.select(
            input(now = 200L, speedMps = 1.0, gps = 85.0, magnetic = 10.0, gpsAccuracy = null),
        )

        val restarted = selector.select(input(now = 300L, speedMps = 1.0, gps = 90.0, magnetic = 10.0))

        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, requireNotNull(restarted).source)
    }

    @Test
    fun rejectsStaleInaccurateUnmountedAndMissingInputs() {
        val selector = selector()

        assertNull(selector.select(input(now = 3_001L, speedMps = 0.2, gps = 90.0, magnetic = 10.0, speedAt = 0L)))
        assertNull(selector.select(input(now = 1_000L, speedMps = 0.2, gps = 90.0, magnetic = 10.0, magneticAt = 499L)))
        assertNull(selector.select(input(now = 1_000L, speedMps = 0.2, gps = 90.0, magnetic = 10.0, magneticAccuracy = 31.0)))
        assertNull(selector.select(input(now = 1_000L, speedMps = 0.2, gps = 90.0, magnetic = 10.0, mounted = false)))
        assertNull(selector.select(input(now = 1_000L, speedMps = 0.8, gps = 90.0, magnetic = 10.0, gpsAccuracy = null)))
        assertNull(
            selector.select(
                input(now = 1_000L, speedMps = 0.8, gps = 90.0, magnetic = 10.0).copy(speed = null),
            ),
        )
    }

    @Test
    fun doesNotReuseLastHeadingWhenActiveObservationDisappears() {
        val selector = selector(requiredConsecutive = 2)
        selector.select(input(now = 0L, speedMps = 0.2, gps = 90.0, magnetic = 10.0))

        val unavailable = selector.select(
            input(now = 100L, speedMps = 1.0, gps = 90.0, magnetic = 10.0).copy(
                magneticTrueHeading = null,
            ),
        )

        assertNull(unavailable)
    }

    @Test
    fun blendsAcrossNorthUsingShortestCircularArc() {
        val selector = selector(smoothingAlpha = 0.5)
        val first = selector.select(input(now = 0L, speedMps = 0.2, gps = 90.0, magnetic = -10.0))
        val second = selector.select(input(now = 100L, speedMps = 0.2, gps = 90.0, magnetic = 10.0))

        assertEquals(350.0, requireNotNull(first).headingDegreesTrueNorth, 1e-9)
        assertEquals(0.0, requireNotNull(second).headingDegreesTrueNorth, 1e-9)
    }

    @Test
    fun startsFailClosedInsideHysteresisBand() {
        val selector = selector()

        val result = selector.select(input(now = 1_000L, speedMps = 0.65, gps = 90.0, magnetic = 10.0))

        assertNull(result)
    }

    private fun selector(
        minimumHoldMs: Long = 0L,
        requiredConsecutive: Int = 2,
        smoothingAlpha: Double = 1.0,
    ) = PedestrianHeadingSelector(
        PedestrianHeadingSelectorConfig(
            minimumSourceHoldMs = minimumHoldMs,
            consecutivePreferredObservationsRequired = requiredConsecutive,
            headingSmoothingAlpha = smoothingAlpha,
        ),
    )

    private fun input(
        now: Long,
        speedMps: Double,
        gps: Double?,
        magnetic: Double?,
        speedAt: Long = now,
        gpsAt: Long = now,
        magneticAt: Long = now,
        speedAccuracy: Double? = 0.2,
        gpsAccuracy: Double? = 5.0,
        magneticAccuracy: Double? = 5.0,
        mounted: Boolean = true,
    ) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = now,
        speed = WalkingSpeedObservation(speedMps, speedAccuracy, speedAt),
        gpsCourse = gps?.let { HeadingObservation(it, gpsAccuracy, gpsAt) },
        magneticTrueHeading = magnetic?.let { HeadingObservation(it, magneticAccuracy, magneticAt) },
        phoneForwardMounted = mounted,
    )
}
