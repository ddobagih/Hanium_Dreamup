package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportQueueDrainPolicyTest {
    @Test
    fun activeWalkAllowsZeroStatusAndPostRequests() {
        val decision = ReportQueueDrainPolicy().acquire(
            report(),
            context(walkState = WalkSessionState.ACTIVE),
        )

        assertFalse(decision.allowed)
        assertEquals(0, decision.allowedStatusRequests)
        assertEquals(0, decision.allowedPostRequests)
    }

    @Test
    fun onlyStationaryAllowedNetworkAndCurrentConsentSessionAcquireNextItem() {
        listOf(
            context(appForeground = false),
            context(stationary = false),
            context(networkAllowed = false),
            context(consentAllowed = false),
            context(authorityAllowed = false),
            context(walkSessionId = OTHER_WALK),
            context(consent = "d".repeat(64)),
        ).forEach { blocked ->
            val decision = ReportQueueDrainPolicy().acquire(report(), blocked)
            assertEquals(0, decision.allowedStatusRequests)
            assertEquals(0, decision.allowedPostRequests)
        }

        val allowed = ReportQueueDrainPolicy().acquire(report(), context())
        assertTrue(allowed.allowed)
        assertEquals(1, allowed.allowedStatusRequests)
        assertEquals(1, allowed.allowedPostRequests)
    }

    @Test
    fun automaticReportRequiresAutomaticReportingConsentAndPinsPriority() {
        val automatic = report(priority = ReportQueuePriority.AUTOMATIC)
        val blocked = ReportQueueDrainPolicy().acquire(
            automatic,
            context(automaticReportingAllowed = false),
        )
        assertFalse(blocked.allowed)

        val policy = ReportQueueDrainPolicy()
        val lease = requireNotNull(policy.acquire(automatic, context()).lease)
        assertEquals(ReportQueuePriority.AUTOMATIC, lease.priority)
        assertFalse(
            policy.isCurrent(
                lease,
                context(automaticReportingAllowed = false),
            ),
        )
    }

    @Test
    fun singleLeaseBlocksASecondDrainUntilReleased() {
        val policy = ReportQueueDrainPolicy()
        val first = requireNotNull(policy.acquire(report(), context()).lease)

        assertFalse(policy.acquire(report(REPORT_2), context()).allowed)
        assertTrue(policy.release(first))
        assertTrue(policy.acquire(report(REPORT_2), context()).allowed)
    }

    @Test
    fun movementGenerationChangeStopsAndReleasesTheLease() {
        val policy = ReportQueueDrainPolicy()
        val lease = requireNotNull(policy.acquire(report(), context(movementGeneration = 7L)).lease)

        assertTrue(policy.isCurrent(lease, context(movementGeneration = 7L)))
        assertFalse(policy.isCurrent(lease, context(movementGeneration = 8L)))
        assertTrue(policy.acquire(report(REPORT_2), context(movementGeneration = 8L)).allowed)
    }

    @Test
    fun currentLeaseRevalidatesExactWalkReceiptForegroundConsentAndAuthority() {
        listOf(
            context(walkSessionId = OTHER_WALK),
            context(consent = "d".repeat(64)),
            context(appForeground = false),
            context(consentAllowed = false),
            context(authorityAllowed = false),
        ).forEach { changed ->
            val policy = ReportQueueDrainPolicy()
            val lease = requireNotNull(policy.acquire(report(), context()).lease)
            assertFalse(policy.isCurrent(lease, changed))
        }
    }

    @Test
    fun nullQueueItemFromDefaultOffStoreAllowsNoNetworkWork() {
        val decision = ReportQueueDrainPolicy().acquire(null, context())

        assertEquals(0, decision.allowedStatusRequests)
        assertEquals(0, decision.allowedPostRequests)
    }

    private fun report(
        id: String = REPORT_1,
        priority: ReportQueuePriority = ReportQueuePriority.EXPLICIT,
    ): QueuedReport {
        val payload = requireNotNull(FrozenReportPayload.freeze(id, "{}".toByteArray(), jpeg()))
        return QueuedReport(
            payload = payload,
            priority = priority,
            walkSessionId = WALK_ID,
            consentReceiptSha256 = CONSENT,
            createdAtEpochMs = 1_000L,
            expiresAtEpochMs = 1_000L + REPORT_QUEUE_TTL_MS,
        )
    }

    private fun context(
        walkState: WalkSessionState = WalkSessionState.PAUSED,
        appForeground: Boolean = true,
        stationary: Boolean = true,
        networkAllowed: Boolean = true,
        consentAllowed: Boolean = true,
        automaticReportingAllowed: Boolean = true,
        authorityAllowed: Boolean = true,
        walkSessionId: String = WALK_ID,
        consent: String = CONSENT,
        movementGeneration: Long = 7L,
    ) = ReportQueueDrainContext(
        walkState = walkState,
        appForeground = appForeground,
        stationary = stationary,
        networkAllowed = networkAllowed,
        consentAllowed = consentAllowed,
        automaticReportingAllowed = automaticReportingAllowed,
        authorityAllowed = authorityAllowed,
        walkSessionId = walkSessionId,
        consentReceiptSha256 = consent,
        movementGeneration = movementGeneration,
    )

    private fun jpeg() =
        byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())

    private companion object {
        const val REPORT_1 = "123e4567-e89b-42d3-a456-426614174000"
        const val REPORT_2 = "123e4567-e89b-42d3-a456-426614174001"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174002"
        const val OTHER_WALK = "123e4567-e89b-42d3-a456-426614174003"
        val CONSENT = "c".repeat(64)
    }
}
