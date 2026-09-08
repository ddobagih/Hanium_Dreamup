package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One passing frame out of five ended the camera check, so a phone that recognised a fifth of
 * what it saw was reported ready — and during a walk that phone misses four hazards in five.
 * The sample shape is the one `RuntimeMetricPreflight` already uses: ten frames, eighty percent.
 *
 * SM-A716S returned 30 of 30 (2026-09-08), so the bar does not exclude a working phone.
 */
class DeviceCheckCameraSamplingPolicyTest {
    @Test
    fun theSampleShapeMatchesTheMetricPreflight() {
        assertEquals(
            RuntimeMetricPreflightPolicy.MIN_DISTINCT_FRAMES,
            DeviceCheckCameraSamplingPolicy.REQUIRED_FRAMES,
        )
        assertEquals(
            RuntimeMetricPreflightPolicy.MIN_PASSING_PERCENT,
            DeviceCheckCameraSamplingPolicy.MINIMUM_PASSING_PERCENT,
        )
    }

    @Test
    fun oneGoodFrameNoLongerEndsTheCheck() {
        assertEquals(
            DeviceCheckCameraSampleVerdict.CONTINUE,
            DeviceCheckCameraSamplingPolicy.evaluate(attempted = 1, passed = 1),
        )
    }

    @Test
    fun eightOfTenPasses() {
        assertEquals(
            DeviceCheckCameraSampleVerdict.PASSED,
            DeviceCheckCameraSamplingPolicy.evaluate(attempted = 10, passed = 8),
        )
    }

    @Test
    fun sevenOfTenFails() {
        assertEquals(
            DeviceCheckCameraSampleVerdict.FAILED,
            DeviceCheckCameraSamplingPolicy.evaluate(attempted = 10, passed = 7),
        )
    }

    @Test
    fun theVerdictLandsEarlyOnceItCannotChange() {
        // A third failure puts eighty percent out of reach; holding the camera longer is noise.
        assertEquals(
            DeviceCheckCameraSampleVerdict.FAILED,
            DeviceCheckCameraSamplingPolicy.evaluate(attempted = 3, passed = 0),
        )
        // Eight passes cannot be undone by the two frames still owed.
        assertEquals(
            DeviceCheckCameraSampleVerdict.PASSED,
            DeviceCheckCameraSamplingPolicy.evaluate(attempted = 8, passed = 8),
        )
    }

    @Test
    fun aPartialSampleIsNeitherPassNorFail() {
        assertEquals(
            DeviceCheckCameraSampleVerdict.CONTINUE,
            DeviceCheckCameraSamplingPolicy.evaluate(attempted = 9, passed = 7),
        )
    }

    @Test
    fun anEmptySampleDoesNotPass() {
        assertEquals(
            DeviceCheckCameraSampleVerdict.CONTINUE,
            DeviceCheckCameraSamplingPolicy.evaluate(attempted = 0, passed = 0),
        )
    }
}
