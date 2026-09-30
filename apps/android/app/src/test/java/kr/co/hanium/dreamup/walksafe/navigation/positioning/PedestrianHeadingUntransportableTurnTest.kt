package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S09-29 (originals/09.txt:84): when a large chest turn after a GPS course is confirmed but cannot be
 * transported onto the course (no chest baseline at the course time), the pre-turn course is not
 * returned at its original confidence; the selector waits for a course newer than the turn.
 */
class PedestrianHeadingUntransportableTurnTest {
    @Test
    fun confirmedTurnAfterTheCourseWithoutBaselineWaitsForANewCourse() {
        val selector = selector()
        val before = requireNotNull(selector.select(input(now = 1_200L, chest = 50.0)))
        assertEquals(10.0, before.headingDegreesTrueNorth, 1e-9)
        assertEquals(5.0, requireNotNull(before.accuracyDegrees), 0.0)

        assertNull(selector.select(input(now = 1_700L, chest = 140.0)))
        // The same old course stays refused even when the chest turns back.
        assertNull(selector.select(input(now = 1_900L, chest = 55.0)))

        val newCourse = requireNotNull(selector.select(input(now = 2_000L, gpsAt = 1_950L, gps = 100.0, chest = 140.0)))
        assertEquals(100.0, newCourse.headingDegreesTrueNorth, 1e-9)
        assertEquals(1_950L, newCourse.sourceObservationAtElapsedRealtimeMs)
    }

    @Test
    fun smallChestChangeAfterTheCourseKeepsTheRawCourse() {
        val selector = selector()
        selector.select(input(now = 1_200L, chest = 50.0))
        val result = requireNotNull(selector.select(input(now = 1_700L, chest = 70.0)))
        assertEquals(10.0, result.headingDegreesTrueNorth, 1e-9)
        assertEquals(5.0, requireNotNull(result.accuracyDegrees), 0.0)
    }

    @Test
    fun chestHeadingFromBeforeTheCourseCannotConfirmATurnAfterIt() {
        val selector = selector()
        selector.select(input(now = 900L, gpsAt = 800L, chest = 50.0))
        val result = requireNotNull(selector.select(input(now = 1_500L, chest = 140.0)))
        assertEquals(10.0, result.headingDegreesTrueNorth, 1e-9)
        assertEquals(5.0, requireNotNull(result.accuracyDegrees), 0.0)
    }

    @Test
    fun unmountingForgetsEarlierChestHeadingsAndResetClearsTheRefusal() {
        val selector = selector()
        selector.select(input(now = 1_200L, chest = 50.0))
        selector.select(input(now = 1_400L, chest = 50.0, mounted = false))
        val remounted = requireNotNull(selector.select(input(now = 1_700L, chest = 140.0)))
        assertEquals(10.0, remounted.headingDegreesTrueNorth, 1e-9)

        selector.select(input(now = 1_800L, chest = 140.0))
        assertNull(selector.select(input(now = 1_900L, chest = 50.0)))
        selector.reset()
        assertEquals(10.0, requireNotNull(selector.select(input(now = 1_950L, chest = 50.0))).headingDegreesTrueNorth, 1e-9)
    }

    private fun selector() = PedestrianHeadingSelector(PedestrianHeadingSelectorConfig(headingSmoothingAlpha = 1.0))

    private fun input(
        now: Long,
        gpsAt: Long = 1_000L,
        gps: Double = 10.0,
        chest: Double,
        mounted: Boolean = true,
    ) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = now,
        speed = WalkingSpeedObservation(1.1, 0.2, now),
        gpsCourse = HeadingObservation(gps, 5.0, gpsAt),
        magneticTrueHeading = HeadingObservation(chest, 5.0, now),
        phoneForwardMounted = mounted,
        // No usable chest baseline at the course time (missing sample or skew beyond 100 ms).
        magneticTrueHeadingAtGpsCourse = null,
    )
}
