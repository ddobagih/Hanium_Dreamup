package kr.co.hanium.dreamup.walksafe.network

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S03-23: one transient renewal failure and an explicit revocation take different paths. */
class GatewayWalkRenewalFailurePolicyTest {
    @Test
    fun explicitRejectionAndLoginLossAreNeverRetried() {
        listOf(
            GatewayWalkHttpException(409, "walk_renew_failed", "walk_lease_stale"),
            GatewayWalkHttpException(409, "walk_renew_failed", "walk_lease_conflict"),
            GatewayWalkHttpException(410, "walk_renew_failed", "walk_lease_expired"),
            GatewayWalkHttpException(400, "walk_renew_failed", "walk_command_invalid"),
            IllegalArgumentException("gateway walk response schema differs"),
        ).forEach { error ->
            val failure = GatewayWalkRenewalFailurePolicy.classify(error)
            assertEquals(GatewayWalkRenewalFailure.AUTHORITY_REJECTED, failure)
            assertNull(GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(failure, 0, 80_000L))
        }
        listOf(
            GatewayWalkHttpException(401, "walk_renew_failed", "gateway_auth_required"),
            GatewayWalkHttpException(403, "walk_renew_failed"),
            GatewayWalkHttpException(0, "long_lived_session_required"),
            GatewayWalkHttpException(0, "gateway_session_unavailable"),
        ).forEach { error ->
            val failure = GatewayWalkRenewalFailurePolicy.classify(error)
            assertEquals(GatewayWalkRenewalFailure.LOGIN_REQUIRED, failure)
            assertNull(GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(failure, 0, 80_000L))
        }
    }

    @Test
    fun oneTransientFailureRetriesWithIncreasingDelayWithinTheHeldLease() {
        listOf(
            IOException("reset"),
            SocketTimeoutException("read"),
            GatewayWalkHttpException(503, "walk_renew_failed", "walk_ledger_unavailable"),
            GatewayWalkHttpException(500, "walk_renew_failed"),
            GatewayWalkHttpException(429, "walk_renew_failed"),
        ).forEach { error ->
            assertEquals(
                GatewayWalkRenewalFailure.TRANSIENT,
                GatewayWalkRenewalFailurePolicy.classify(error),
            )
        }
        val transient = GatewayWalkRenewalFailure.TRANSIENT
        assertEquals(1_000L, GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(transient, 0, 55_000L))
        assertEquals(3_000L, GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(transient, 1, 30_000L))
        assertNull(GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(transient, 2, 80_000L))
    }

    @Test
    fun retryNeverOutlivesTheLeaseTheServerGranted() {
        val transient = GatewayWalkRenewalFailure.TRANSIENT
        val budget = GatewayWalkRenewalFailurePolicy.RETRY_REQUEST_BUDGET_MS
        assertNull(GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(transient, 0, null))
        assertNull(GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(transient, 0, 1_000L + budget))
        assertEquals(
            1_000L,
            GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(transient, 0, 1_001L + budget),
        )
        assertNull(GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(transient, -1, 80_000L))
    }

    @Test
    fun recoveryInstructionsDifferByCause() {
        val details = GatewayWalkRenewalFailure.entries.map(GatewayWalkRenewalFailurePolicy::detailKo)
        assertEquals(details.size, details.toSet().size)
        assertNotEquals(
            GatewayWalkRenewalFailure.AUTHORITY_REJECTED.reasonCode,
            GatewayWalkRenewalFailure.TRANSIENT.reasonCode,
        )
    }

    @Test
    fun controllerReportsTheConservativeRemainingLeaseTime() {
        var now = 1_000L
        val controller = GatewayWalkAuthorityController(
            requestIdFactory = { "request-0001" },
            elapsedClock = { now },
        )
        val epoch = kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch("walk-00000001", 0)
        assertNull(controller.safeRemainingMsOrNull(epoch))
        val operation = controller.beginStart(epoch)!!
        controller.completeStart(
            operation,
            GatewayWalkStartResult.Granted(
                GatewayWalkLease(
                    result = "ACQUIRED",
                    actorId = "actor",
                    deviceId = "device",
                    walkId = "walk-00000001",
                    leaseId = "lease-0001",
                    fencingToken = 1L,
                    acquiredAtEpochMs = 0L,
                    leaseExpiresAtEpochMs = 90_000L,
                    serverTimeEpochMs = 0L,
                    localDeadlineElapsedMs = 86_000L,
                ),
            ),
        )
        assertEquals(85_000L, controller.safeRemainingMsOrNull(epoch))
        now = 86_000L
        assertNull(controller.safeRemainingMsOrNull(epoch))
    }
}
