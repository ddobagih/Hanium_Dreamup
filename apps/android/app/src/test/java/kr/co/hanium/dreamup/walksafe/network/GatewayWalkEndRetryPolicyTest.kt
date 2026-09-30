package kr.co.hanium.dreamup.walksafe.network

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S03-21: a lost end response is resent with the same request id only for the owning session. */
class GatewayWalkEndRetryPolicyTest {
    @Test
    fun confirmedOrAlreadyReleasedEndsNeedNoRetry() {
        assertEquals(GatewayWalkEndOutcome.CONFIRMED, GatewayWalkEndRetryPolicy.outcome(null))
        listOf("walk_lease_stale", "walk_lease_conflict", "walk_lease_expired").forEach { code ->
            val error = GatewayWalkHttpException(409, "walk_end_failed", code)
            assertEquals(GatewayWalkEndOutcome.ALREADY_RELEASED, GatewayWalkEndRetryPolicy.outcome(error))
            assertNull(GatewayWalkEndRetryPolicy.retryDelayMsOrNull(error, 0, true, true))
        }
        assertNull(GatewayWalkEndRetryPolicy.retryDelayMsOrNull(null, 0, true, true))
    }

    @Test
    fun lostResponseIsRetriedWithIncreasingDelayAndABoundedBudget() {
        val lost = IOException("response lost")
        assertEquals(1_000L, GatewayWalkEndRetryPolicy.retryDelayMsOrNull(lost, 0, true, true))
        assertEquals(2_000L, GatewayWalkEndRetryPolicy.retryDelayMsOrNull(lost, 1, true, true))
        assertEquals(4_000L, GatewayWalkEndRetryPolicy.retryDelayMsOrNull(lost, 2, true, true))
        assertNull(GatewayWalkEndRetryPolicy.retryDelayMsOrNull(lost, 3, true, true))
        val busy = GatewayWalkHttpException(503, "walk_end_failed", "walk_ledger_busy")
        assertEquals(1_000L, GatewayWalkEndRetryPolicy.retryDelayMsOrNull(busy, 0, true, true))
    }

    @Test
    fun retryStopsForAnotherSessionAnExpiredLeaseOrALoginRejection() {
        val lost = IOException("response lost")
        assertNull(GatewayWalkEndRetryPolicy.retryDelayMsOrNull(lost, 0, leaseMayStillBeHeld = false, sameGatewaySession = true))
        assertNull(GatewayWalkEndRetryPolicy.retryDelayMsOrNull(lost, 0, leaseMayStillBeHeld = true, sameGatewaySession = false))
        val login = GatewayWalkHttpException(401, "walk_end_failed", "gateway_auth_required")
        assertEquals(GatewayWalkEndOutcome.GIVE_UP, GatewayWalkEndRetryPolicy.outcome(login))
        assertNull(GatewayWalkEndRetryPolicy.retryDelayMsOrNull(login, 0, true, true))
        assertEquals(
            GatewayWalkEndOutcome.GIVE_UP,
            GatewayWalkEndRetryPolicy.outcome(IllegalArgumentException("schema differs")),
        )
    }
}
