package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportQueueDrainPolicyTest {
    @Test
    fun onlyAfterTheWalkAllowsStatusAndPostRequests() {
        // Owner decision 3(b) / FP-032-DETAIL-02: ACTIVE and PAUSED are the walk; R4: SAFE_STOP is a
        // system stop, not an explicit end, so nothing is sent in it either.
        for (state in listOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED, WalkSessionState.SAFE_STOP)) {
            val decision = ReportQueueDrainPolicy().acquire(report(), context(walkState = state))
            assertFalse("$state must not drain reports", decision.allowed)
            assertEquals(0, decision.allowedStatusRequests)
            assertEquals(0, decision.allowedPostRequests)
        }
        for (state in listOf(WalkSessionState.ENDED, WalkSessionState.READY)) {
            val decision = ReportQueueDrainPolicy().acquire(report(), context(walkState = state))
            assertTrue("$state is after the walk", decision.allowed)
            assertEquals(1, decision.allowedStatusRequests)
            assertEquals(1, decision.allowedPostRequests)
        }
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
    fun automaticOptOutStillAllowsAStatusOnlyLeaseUnderCurrentAuthority() {
        val policy = ReportQueueDrainPolicy()
        val current = context(automaticReportingAllowed = false)
        val decision = policy.acquire(
            report(priority = ReportQueuePriority.AUTOMATIC),
            current,
        )
        assertTrue("fresh authority must allow status recovery without automatic POST", decision.allowed)
        assertEquals(1, decision.allowedStatusRequests)
        assertEquals(0, decision.allowedPostRequests)
        assertTrue(policy.isCurrent(requireNotNull(decision.lease), current))
        assertFalse(policy.mayUpload(requireNotNull(decision.lease), current))
    }

    @Test
    fun automaticPostRequiresItsOptInAndEitherSelectionChangeCancelsTheLease() {
        val automatic = report(priority = ReportQueuePriority.AUTOMATIC)
        listOf(false, true).forEach { initial ->
            val policy = ReportQueueDrainPolicy()
            val current = context(automaticReportingAllowed = initial)
            val lease = requireNotNull(policy.acquire(automatic, current).lease)
            assertEquals(ReportQueuePriority.AUTOMATIC, lease.priority)
            assertEquals(initial, policy.mayUpload(lease, current))
            assertFalse(policy.isCurrent(lease, context(automaticReportingAllowed = !initial)))
            assertFalse(policy.mayUpload(lease, current))
        }
    }

    @Test
    fun explicitPostDoesNotDependOnTheAutomaticSelection() {
        val policy = ReportQueueDrainPolicy()
        val lease = requireNotNull(policy.acquire(report(), context(automaticReportingAllowed = false)).lease)
        assertTrue(policy.mayUpload(lease, context(automaticReportingAllowed = false)))
        assertTrue(policy.mayUpload(lease, context(automaticReportingAllowed = true)))
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
        // Owner decision 3(b): a walk that becomes ACTIVE or PAUSED again ends the lease.
        listOf(
            WalkSessionState.ACTIVE,
            WalkSessionState.PAUSED,
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
        walkState: WalkSessionState = WalkSessionState.ENDED,
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

    private fun jpeg() = reportTestJpeg()

    private companion object {
        const val REPORT_1 = "123e4567-e89b-42d3-a456-426614174000"
        const val REPORT_2 = "123e4567-e89b-42d3-a456-426614174001"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174002"
        const val OTHER_WALK = "123e4567-e89b-42d3-a456-426614174003"
        const val ACTOR_ID = "walker-1"
        val CONSENT = "c".repeat(64)
    }
}
