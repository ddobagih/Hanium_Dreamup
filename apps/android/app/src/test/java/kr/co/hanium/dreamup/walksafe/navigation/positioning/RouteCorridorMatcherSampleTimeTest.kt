package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S09-50 (originals/09.txt:131): with the caller's evaluation time the matcher checks sample age and
 * future time itself, and reports an order error (STALE_SAMPLE) separately from real expiry
 * (EXPIRED_SAMPLE) and future time (FUTURE_SAMPLE). Rejected samples do not consume the order watermark.
 */
class RouteCorridorMatcherSampleTimeTest {
    @Test
    fun orderErrorExpiryAndFutureTimeAreDistinctReasons() {
        val matcher = RouteCorridorMatcher()
        assertEquals(RouteMatchReason.MATCHED, match(matcher, at = 1_000L, now = 1_000L).reason)

        val duplicate = match(matcher, at = 1_000L, now = 1_500L)
        assertEquals(RouteMatchReason.STALE_SAMPLE, duplicate.reason)
        assertEquals(RouteMatchQuality.UNMATCHED, duplicate.quality)

        val expired = match(matcher, at = 1_500L, now = 11_501L)
        assertEquals(RouteMatchReason.EXPIRED_SAMPLE, expired.reason)
        assertEquals(RouteMatchQuality.UNMATCHED, expired.quality)

        val future = match(matcher, at = 12_000L, now = 11_600L)
        assertEquals(RouteMatchReason.FUTURE_SAMPLE, future.reason)

        // Neither the expired nor the future sample moved the order watermark (still 1 000 ms).
        assertEquals(RouteMatchReason.MATCHED, match(matcher, at = 2_000L, now = 11_700L).reason)
        // Older than the last accepted sample but still fresh: an order error, not expiry.
        assertEquals(RouteMatchReason.STALE_SAMPLE, match(matcher, at = 1_900L, now = 11_800L).reason)
        // Exactly the maximum age is still usable.
        assertEquals(RouteMatchReason.MATCHED, match(matcher, at = 2_100L, now = 12_100L).reason)
    }

    @Test
    fun withoutEvaluationTimeOnlyTheOrderIsChecked() {
        val matcher = RouteCorridorMatcher()
        assertEquals(RouteMatchReason.MATCHED, match(matcher, at = 50_000L, now = null).reason)
        assertEquals(RouteMatchReason.STALE_SAMPLE, match(matcher, at = 50_000L, now = null).reason)
    }

    @Test
    fun navigatorPassesItsEvaluationTimeAndTheExistingTenSecondSampleAge() {
        val navigator = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt").readText()
        val call = navigator.substringAfter("val routeMatch = routeCorridorMatcher.match(").substringBefore(")\n")
        assertTrue(call, "evaluatedAtElapsedRealtimeMs = nowMs" in call)
        assertTrue(call, "maximumSampleAgeMs = LocationTrustConfig().maxAgeMs" in call)
        assertEquals(10_000L, kr.co.hanium.dreamup.walksafe.navigation.LocationTrustConfig().maxAgeMs)
    }

    private fun match(matcher: RouteCorridorMatcher, at: Long, now: Long?): RouteCorridorMatchResult = matcher.match(
        routeId = "r",
        polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.002)),
        position = FilteredRoutePosition(RoutePoint(37.0, 127.001), 3.0, at),
        evaluatedAtElapsedRealtimeMs = now,
        maximumSampleAgeMs = now?.let { 10_000L },
    )
}
