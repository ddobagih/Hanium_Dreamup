package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S15-1: callbacks of a released lease A must not mutate the next lease B of the same place. */
class AndroidReportAttemptStaleLeaseTest {
    @Test
    fun lateSuccessFailureAndReleaseOfAnOldLeaseLeaveTheNextAttemptUntouched() {
        val store = AndroidReportAttemptStore(monotonicNowMs = { 5_000L })
        val a = allowed(store.acquire(scope(), 1_000L, bypassAutomaticCooldown = false))
        store.release(a)
        val b = allowed(store.acquire(scope(), 2_000L, bypassAutomaticCooldown = false))
        assertEquals(a.storageKey, b.storageKey)

        store.markSucceeded(a, 2_100L)
        assertNull(store.markFailed(a, 2_200L) { 60_000L })
        store.release(a)

        // B is still in flight, so a third attempt stays serialized.
        val c = store.acquire(scope(), 2_300L, bypassAutomaticCooldown = false)
        assertEquals(
            AndroidReportAttemptBlockReason.IN_FLIGHT,
            (c as AndroidReportAttemptResult.Blocked).reason,
        )
        // A's late success/failure created neither a cooldown nor a retry backoff for B's place.
        store.release(b)
        val d = allowed(store.acquire(scope(), 2_400L, bypassAutomaticCooldown = false))

        // The current lease's own callbacks still work.
        assertNotNull(store.markFailed(d, 2_500L) { 60_000L })
        store.release(d)
        val backoff = store.acquire(scope(), 2_600L, bypassAutomaticCooldown = false)
        assertEquals(
            AndroidReportAttemptBlockReason.RETRY_BACKOFF,
            (backoff as AndroidReportAttemptResult.Blocked).reason,
        )
    }

    @Test
    fun lateSuccessOfAnOldLeaseCannotStartACooldownAfterItsOwnRelease() {
        val store = AndroidReportAttemptStore(monotonicNowMs = { 5_000L })
        val a = allowed(store.acquire(scope(), 1_000L, bypassAutomaticCooldown = false))
        store.release(a)
        store.markSucceeded(a, 1_100L)
        assertTrue(store.successfulCooldowns(1_200L).isEmpty())
        assertTrue(store.acquire(scope(), 1_300L, bypassAutomaticCooldown = false) is AndroidReportAttemptResult.Allowed)
    }

    private fun allowed(result: AndroidReportAttemptResult): AndroidReportAttemptLease =
        (result as AndroidReportAttemptResult.Allowed).lease

    private fun scope(): AndroidReportSpatialScope = requireNotNull(
        AndroidReportCooldownPolicy.spatialScopeOrNull(
            actorId = "walker-1",
            className = AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK,
            location = TrustedLocation(37.0, 127.0, 5f, 0L),
        ),
    )
}
