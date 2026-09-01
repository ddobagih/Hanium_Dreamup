package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportQueueDrainPolicyTest {
    @Test
    fun onlyPausedWalkAllowsStatusAndPostRequests() {
        WalkSessionState.entries
            .filterNot { it == WalkSessionState.PAUSED }
            .forEach { state ->
                val decision = ReportQueueDrainPolicy().acquire(
                    report(),
                    context(walkState = state),
                )

                assertFalse("$state must not drain reports", decision.allowed)
                assertEquals(0, decision.allowedStatusRequests)
                assertEquals(0, decision.allowedPostRequests)
            }

        assertTrue(ReportQueueDrainPolicy().acquire(report(), context()).allowed)
    }

    @Test
    fun onlyStationaryAllowedNetworkAndCurrentConsentAcquireNextItem() {
        listOf(
            context(appForeground = false),
            context(stationary = false),
            context(networkAllowed = false),
            context(consentAllowed = false),
            context(authorityAllowed = false),
        ).forEach { blocked ->
            val decision = ReportQueueDrainPolicy().acquire(report(), blocked)
            assertEquals(0, decision.allowedStatusRequests)
            assertEquals(0, decision.allowedPostRequests)
        }

        val allowed = ReportQueueDrainPolicy().acquire(report(), context())
        assertTrue(allowed.allowed)
        assertEquals(1, allowed.allowedStatusRequests)
        assertEquals(1, allowed.allowedPostRequests)

        val recovery = ReportQueueDrainPolicy().acquire(
            report(),
            context(walkSessionId = OTHER_WALK),
        )
        assertTrue(recovery.allowed)
        assertEquals(WALK_ID, recovery.lease?.sourceWalkSessionId)
        assertEquals(OTHER_WALK, recovery.lease?.runtimeWalkSessionId)
    }

    @Test
    fun currentReceiptReauthorizesReportCreatedUnderAnOlderOptionalSelectionReceipt() {
        val currentReceipt = "d".repeat(64)
        val decision = ReportQueueDrainPolicy().acquire(
            report(),
            context(consent = currentReceipt),
        )

        assertTrue(decision.allowed)
        assertEquals(CONSENT, report().consentReceiptSha256)
        assertEquals(currentReceipt, decision.lease?.consentReceiptSha256)
    }

    @Test
    fun reportFromAnotherActorCannotAcquireOrRemainCurrent() {
        assertFalse(
            ReportQueueDrainPolicy().acquire(
                report(),
                context(reporterActorId = "walker-2"),
            ).allowed,
        )
        val policy = ReportQueueDrainPolicy()
        val lease = requireNotNull(policy.acquire(report(), context()).lease)

        assertFalse(policy.isCurrent(lease, context(reporterActorId = "walker-2")))
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
    fun currentLeaseRevalidatesRuntimeWalkReceiptForegroundConsentAndAuthority() {
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
        listOf(
            WalkSessionState.READY,
            WalkSessionState.ACTIVE,
            WalkSessionState.SAFE_STOP,
            WalkSessionState.ENDED,
        ).forEach { state ->
            val policy = ReportQueueDrainPolicy()
            val lease = requireNotNull(policy.acquire(report(), context()).lease)
            assertFalse(policy.isCurrent(lease, context(walkState = state)))
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
            reporterActorId = ACTOR_ID,
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
        reporterActorId: String = ACTOR_ID,
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
        reporterActorId = reporterActorId,
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
        const val ACTOR_ID = "walker-1"
        val CONSENT = "c".repeat(64)
    }
}
