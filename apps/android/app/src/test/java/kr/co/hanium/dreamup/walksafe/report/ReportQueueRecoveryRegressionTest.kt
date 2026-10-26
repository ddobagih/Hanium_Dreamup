package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import java.net.SocketTimeoutException
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentAuthorityGuard
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportQueueRecoveryRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun invalidStatusDocumentKeepsExactStoredReportUntilCleanStatusRecovery() = invalidReceiptRecovery(status = true)

    @Test
    fun invalidUploadDocumentKeepsExactStoredReportUntilCleanStatusRecovery() = invalidReceiptRecovery(status = false)

    private fun invalidReceiptRecovery(status: Boolean) {
        val f = fixture()
        val original = f.enqueue()
        var commits = 0
        var posts = 0
        var held: QueuedReport? = null
        fun body(report: QueuedReport): String = org.json.JSONObject()
            .put("persistence_state", "PERSISTED").put("user_status", "RECEIVED")
            .put("transport_receipt", org.json.JSONObject()
                .put("marker", REPORT_RECEIPT_MARKER).put("report_id", report.payload.reportId)
                .put("persistence_marker", "123e4567-e89b-42d3-a456-426614174002")
                .put("payload_sha256", report.payload.payloadSha256).put("payload_bytes", report.payload.payloadBytes))
            .toString() + "{}"
        val transport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> {
                held = report
                if (status) parseQueuedStatusReceiptOrNull(body(report), report) ?: throw ReportUploadProtocolException()
                else null
            }
            override fun uploadCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt> {
                posts++
                parseQueuedUploadReceiptOrNull(body(report), report) ?: throw ReportUploadProtocolException()
            }
        }
        try {
            assertEquals(ReportQueueDrainOutcome.RECEIPT_REJECTED,
                requireNotNull(f.coordinator.startNext({ context() }, transport) { commits++; true }).execute())
            assertEquals(0, commits)
            assertEquals(if (status) 0 else 1, posts)
            assertThrows(IllegalStateException::class.java) { requireNotNull(held).payload.imageJpeg() }
            val restored = f.reopen()
            val stored = restored.queuedReports()
            try {
                assertEquals(1, stored.size)
                assertTrue(original.payload.contentEquals(stored.single().payload))
                assertFalse(stored.single().quarantined)
            } finally { stored.forEach { it.close() } }
            assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS,
                requireNotNull(ReportQueueDrainCoordinator(restored).startNext({ context() }, successfulTransport()) {
                    commits++; true
                }).execute())
            assertEquals(1, commits)
            assertTrue(restored.queuedReports().isEmpty())
        } finally { original.close() }
    }

    @Test
    fun typedCooldownPreservesExactPayloadAndTenMinuteDelayAfterReopen() {
        val f = fixture()
        val delayed = f.enqueue(priority = ReportQueuePriority.AUTOMATIC)
        val next = f.enqueue(priority = ReportQueuePriority.AUTOMATIC)
        val body = """{"detail":{"code":"automatic_report_cooldown_active","creation_outcome":"SUPPRESSED_BY_COOLDOWN"}}"""
        val transport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> { null }
            override fun uploadCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt> {
                throw ReportUploadHttpException(ReportUploadError(429, body, 600_000L))
            }
        }
        var successCommits = 0
        val result = requireNotNull(f.coordinator.startNextWithResult(
            { context() }, transport, beforeDeleteAfterReceipt = { successCommits++; true },
        )).execute()
        assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED, result.outcome)
        assertEquals(ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN, result.creationOutcome)
        assertEquals(0, successCommits)
        val reopened = f.reopen()
        assertEquals(2, reopened.queuedReports().size)
        assertEquals(next.payload.reportId, reopened.nextForRecoveryDrain(ACTOR)?.payload?.reportId)
        assertTrue(reopened.deleteAfterReceipt(ACTOR, receipt(next)))
        f.now += 599_999L
        assertNull(reopened.nextForRecoveryDrain(ACTOR))
        f.now += 1L
        val retried = requireNotNull(reopened.nextForRecoveryDrain(ACTOR))
        assertTrue(delayed.payload.contentEquals(retried.payload))
    }

    @Test
    fun ordinaryRateLimitDoesNotClaimAutomaticSuppression() {
        val f = fixture()
        f.enqueue(priority = ReportQueuePriority.AUTOMATIC)
        val result = requireNotNull(f.coordinator.startNextWithResult(
            { context() }, failingTransport(429, 600_000L),
        )).execute()
        assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED, result.outcome)
        assertNull(result.creationOutcome)
        assertEquals(1, f.store.queuedReports().size)
    }

    @Test
    fun cancelBeforeExecuteReleasesSlotAndLateCancelDoesNotReleaseReplacement() {
        val f = fixture()
        f.enqueue()
        val first = requireNotNull(f.coordinator.startNext({ context() }, successfulTransport()))
        first.cancel()
        assertThrows(CancellationException::class.java) { first.execute() }
        val second = requireNotNull(f.coordinator.startNext({ context() }, successfulTransport()))
        first.cancel()
        assertNull(f.coordinator.startNext({ context() }, successfulTransport()))
        assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS, second.execute())
    }

    @Test
    fun rejectedExecutorSubmissionCanCancelUnstartedDrain() {
        val f = fixture()
        f.enqueue()
        val executor = Executors.newSingleThreadExecutor().apply { shutdown() }
        val call = requireNotNull(f.coordinator.startNext({ context() }, successfulTransport()))
        try {
            executor.submit { call.execute() }
            fail("executor must reject")
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            call.cancel()
        }
        requireNotNull(f.coordinator.startNext({ context() }, successfulTransport())).cancel()
    }

    @Test
    fun executingCancellationKeepsSlotUntilNetworkOwnerExits() {
        val f = fixture()
        f.enqueue()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = requireNotNull(f.coordinator.startNext({ context() }, successfulTransport {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<ReportQueueDrainOutcome> { call.execute() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            call.cancel()
            assertNull(f.coordinator.startNext({ context() }, successfulTransport()))
            release.countDown()
            assertEquals(ReportQueueDrainOutcome.CANCELLED, result.get(3, TimeUnit.SECONDS))
            assertEquals(1, f.store.queuedReports().size)
            requireNotNull(f.coordinator.startNext({ context() }, successfulTransport())).cancel()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun permanentHeadIsRetainedAndSkippedAfterStoreRecreation() {
        for (status in listOf(400, 413, 415, 422)) {
            val f = fixture()
            val rejected = f.enqueue()
            val good = f.enqueue()
            assertEquals(ReportQueueDrainOutcome.QUARANTINED,
                requireNotNull(f.coordinator.startNext({ context() }, failingTransport(status))).execute())
            val restored = f.reopen()
            assertEquals(good.payload.reportId, restored.nextForRecoveryDrain(ACTOR)?.payload?.reportId)
            assertTrue(restored.queuedReports().single { it.payload.reportId == rejected.payload.reportId }.quarantined)
            assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS,
                requireNotNull(ReportQueueDrainCoordinator(restored).startNext({ context() }, successfulTransport())).execute())
            assertEquals(1, restored.queuedReports().size)
            assertNull(restored.nextForRecoveryDrain(ACTOR))
        }
    }

    @Test
    fun serverTenMinuteRetryAfterSurvivesRestartWithoutBlockingAnotherReport() {
        val f = fixture()
        val delayed = f.enqueue()
        val next = f.enqueue()
        assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED,
            requireNotNull(f.coordinator.startNext({ context() }, failingTransport(429, 600_000L))).execute())
        val restored = f.reopen()
        assertEquals(next.payload.reportId, restored.nextForRecoveryDrain(ACTOR)?.payload?.reportId)
        assertTrue(restored.deleteAfterReceipt(ACTOR, receipt(next)))
        f.now += 599_999L
        assertNull(restored.nextForRecoveryDrain(ACTOR))
        f.now += 1L
        assertEquals(delayed.payload.reportId, restored.nextForRecoveryDrain(ACTOR)?.payload?.reportId)
    }

    @Test
    fun authenticationAndPermissionFailuresAreDistinctAndNeverQuarantinePayload() {
        for ((status, expected) in listOf(
            401 to ReportQueueDrainOutcome.AUTHENTICATION_REQUIRED,
            403 to ReportQueueDrainOutcome.PERMISSION_BLOCKED,
        )) {
            val f = fixture()
            val original = f.enqueue()
            assertEquals(expected,
                requireNotNull(f.coordinator.startNext({ context() }, failingTransport(status))).execute())
            val stored = f.reopen().queuedReports().single()
            assertFalse(stored.quarantined)
            assertEquals(0L, stored.retryNotBeforeEpochMs)
            assertTrue(original.payload.contentEquals(stored.payload))
        }
    }

    @Test
    fun sameActorDifferentAccountGenerationAndLegacyUnboundPayloadCannotDrain() {
        val f = fixture()
        f.enqueue(accountGeneration = 1L)
        f.enqueue()
        val current = f.enqueue(accountGeneration = 2L)
        val restored = f.reopen()
        assertEquals(current.payload.reportId,
            restored.nextForRecoveryDrain(ACTOR, accountGeneration = 2L)?.payload?.reportId)
        assertNull(restored.nextForRecoveryDrain(ACTOR, accountGeneration = 3L))
        assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS,
            requireNotNull(ReportQueueDrainCoordinator(restored).startNext(
                { context().copy(accountGeneration = 2L) }, successfulTransport(),
            )).execute())
        assertEquals(2, restored.queuedReports().size)
    }

    @Test
    fun sessionGenerationChangeBeforeExecuteBlocksEvenForSameAccount() {
        val f = fixture()
        f.enqueue(accountGeneration = 1L)
        var current = context().copy(accountGeneration = 1L, sessionGeneration = 7L)
        var callbacks = 0
        val call = requireNotNull(f.coordinator.startNext({ current }, successfulTransport { callbacks++ }))
        current = current.copy(sessionGeneration = 8L)
        assertEquals(ReportQueueDrainOutcome.CANCELLED, call.execute())
        assertEquals(0, callbacks)
    }

    @Test
    fun invalidReceiptCannotCommitCooldownForAnotherPayload() {
        val f = fixture()
        val first = f.enqueue()
        val other = f.enqueue()
        var commits = 0
        val transport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport) =
                CancellableNetworkCall.blocking<ReportQueueReceipt?> { receipt(other) }
            override fun uploadCall(report: QueuedReport) = error("no post")
        }
        assertEquals(ReportQueueDrainOutcome.RECEIPT_REJECTED,
            requireNotNull(f.coordinator.startNext({ context() }, transport) { commits++; true }).execute())
        assertEquals(0, commits)
        assertEquals(first.payload.reportId, f.store.nextForDrain()?.payload?.reportId)
    }

    @Test
    fun cancellationFromReceiptCallbackDoesNotDeleteQueuedBytes() {
        val f = fixture()
        f.enqueue()
        lateinit var call: CancellableNetworkCall<ReportQueueDrainOutcome>
        call = requireNotNull(f.coordinator.startNext({ context() }, successfulTransport()) {
            call.cancel()
            true
        })
        assertEquals(ReportQueueDrainOutcome.CANCELLED, call.execute())
        assertEquals(1, f.store.queuedReports().size)
    }

    @Test
    fun automaticReceiptNeedsExplicitDurableCooldownCallback() {
        val f = fixture()
        f.enqueue(priority = ReportQueuePriority.AUTOMATIC)
        assertEquals(ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED,
            requireNotNull(f.coordinator.startNext({ context() }, successfulTransport())).execute())
        assertEquals(1, f.store.queuedReports().size)
    }

    @Test
    fun temporaryErrorsWithoutRetryAfterPersistDelayAndPreserveExactPayload() {
        for ((status, delay) in listOf(500 to 2_000L, 503 to 2_000L, 429 to 30_000L)) {
            val f = fixture()
            val failed = f.enqueue()
            val next = f.enqueue()
            assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED,
                requireNotNull(f.coordinator.startNext({ context() }, failingTransport(status))).execute())
            val restored = f.reopen()
            assertEquals(next.payload.reportId, restored.nextForRecoveryDrain(ACTOR)?.payload?.reportId)
            assertTrue(restored.deleteAfterReceipt(ACTOR, receipt(next)))
            f.now += delay - 1L
            assertNull(restored.nextForRecoveryDrain(ACTOR))
            f.now++
            assertTrue(failed.payload.contentEquals(requireNotNull(restored.nextForRecoveryDrain(ACTOR)).payload))
        }
    }

    @Test
    fun timeoutAfterUploadIsDeferredAndRetryChecksReceiptBeforePostingAgain() {
        val f = fixture()
        val failed = f.enqueue()
        var posts = 0
        var statusCalls = 0
        val transport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> {
                statusCalls++
                if (posts > 0) receipt(report) else null
            }
            override fun uploadCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt> {
                posts++
                throw SocketTimeoutException("response lost after acceptance")
            }
        }
        assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED,
            requireNotNull(f.coordinator.startNext({ context() }, transport)).execute())
        val reopened = f.reopen()
        assertNull(reopened.nextForRecoveryDrain(ACTOR))
        assertTrue(failed.payload.contentEquals(reopened.queuedReports().single().payload))
        f.now += 2_000L
        assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS,
            requireNotNull(ReportQueueDrainCoordinator(reopened).startNext({ context() }, transport)).execute())
        assertEquals(1, posts)
        assertEquals(2, statusCalls)
        assertTrue(reopened.queuedReports().isEmpty())
    }

    @Test
    fun freshGuardForIdenticalConfirmationKeepsAnExecutingDrain() {
        exerciseConsentRedelivery(unchanged = true, oldAuthorityLive = true)
    }

    @Test
    fun differentConfirmationCancelsAnExecutingDrainAndKeepsPayload() {
        exerciseConsentRedelivery(unchanged = false, oldAuthorityLive = true)
    }

    @Test
    fun replacedAccountCancelsEvenIfConfirmationWasReportedAsDuplicate() {
        exerciseConsentRedelivery(unchanged = true, oldAuthorityLive = false)
    }

    private fun exerciseConsentRedelivery(unchanged: Boolean, oldAuthorityLive: Boolean) {
        val consent = ReportPrivacyConsentSession
        consent.resetForNewEnrollment()
        val activity = MainActivity()
        var live = true
        fun freshGuard() = object : IntegratedConsentAuthorityGuard {
            override fun <T : Any> withCurrent(action: () -> T): T? = if (live) action() else null
        }
        try {
            activity.applyReportConsentAuthority(false, freshGuard())
            val permit = requireNotNull(consent.issueUploadPermit(ReportTransferPurpose.EXPLICIT))
            val f = fixture()
            f.enqueue()
            lateinit var upload: CancellableNetworkCall<ReportQueueReceipt>
            val transport = object : ReportQueueTransport {
                override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> { null }
                override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> {
                    upload = CancellableNetworkCall.blocking {
                        // MainActivity creates a fresh guard object for every server response.
                        live = oldAuthorityLive
                        activity.applyReportConsentAuthority(unchanged, freshGuard())
                        if (upload.isCancelled()) throw CancellationException()
                        consent.withLiveUploadPermit(permit, ReportTransferPurpose.EXPLICIT) { receipt(report) }
                    }
                    return requireNotNull(consent.trackIfLive(permit, upload, ReportTransferPurpose.EXPLICIT))
                }
            }
            val result = requireNotNull(f.coordinator.startNext({ context() }, transport)).execute()
            val keepGoing = unchanged && oldAuthorityLive
            assertEquals(if (keepGoing) ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD else ReportQueueDrainOutcome.CANCELLED, result)
            assertEquals(!keepGoing, upload.isCancelled())
            assertEquals(if (keepGoing) 0 else 1, f.reopen().queuedReports().size)
        } finally {
            consent.resetForNewEnrollment()
        }
    }

    private fun fixture() = Fixture(temporary.newFolder())

    private class Fixture(private val root: File) {
        var now = 1_000L
        private var nextId = 1L
        val store = reopen()
        val coordinator = ReportQueueDrainCoordinator(store)
        fun reopen() = AndroidReportQueueStore(
            rootDirectory = root,
            capacityProfile = ApprovedReportQueueCapacityProfile(8, 1_024, 4_096L, 32_768L, 6, 24_576L),
            aead = RecoveryAead(),
            idFactory = { UUID(0L, nextId++) },
            nowMillis = { now },
        )
        fun enqueue(accountGeneration: Long? = null, priority: ReportQueuePriority = ReportQueuePriority.EXPLICIT) =
            requireNotNull(store.enqueue(
                expectedReporterActorId = ACTOR,
                metadataUtf8 = (
                    "{\"reporter_user_id\":\"$ACTOR\"," +
                        if (priority == ReportQueuePriority.AUTOMATIC) {
                            "\"trigger\":\"auto\",\"auto_reported\":true}"
                        } else {
                            "\"trigger\":\"voice\",\"auto_reported\":false}"
                        }
                    ).toByteArray(),
                imageJpeg = reportTestJpeg(),
                priority = priority,
                walkSessionId = WALK,
                consentReceiptSha256 = "c".repeat(64),
                accountGeneration = accountGeneration,
            ))
    }

    private fun context() = ReportQueueDrainContext(
        WalkSessionState.ENDED, true, true, true, true, true, true,
        ACTOR, WALK, "c".repeat(64), 1L,
    )

    private fun successfulTransport(beforeReturn: () -> Unit = {}) = object : ReportQueueTransport {
        override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> {
            beforeReturn()
            receipt(report)
        }
        override fun uploadCall(report: QueuedReport) = error("status must avoid post")
    }

    private fun failingTransport(status: Int, retryAfterMs: Long? = null) = object : ReportQueueTransport {
        override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> { null }
        override fun uploadCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt> {
            throw ReportUploadHttpException(ReportUploadError(status, "", retryAfterMs))
        }
    }

    private fun receipt(report: QueuedReport) = ReportQueueReceipt(
        report.payload.reportId, report.payload.payloadSha256, report.payload.payloadBytes,
        REPORT_RECEIPT_MARKER, "123e4567-e89b-42d3-a456-426614174002",
    )

    private companion object {
        const val ACTOR = "walker-1"
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
    }
}

private class RecoveryAead : LocalAead {
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) =
        AeadSealResult.Sealed(Base64.getEncoder().encodeToString(plaintext))
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits) =
        AeadOpenResult.Opened(Base64.getDecoder().decode(envelope), 1, false)
    override fun destroyVersion(version: Int) = true
    override fun destroyKnownVersions() = true
    override fun createFreshAfterVerifiedPurge() = true
}
