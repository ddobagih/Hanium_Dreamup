package kr.co.hanium.dreamup.walksafe.report

import java.util.Base64
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** S15-32: receipt problems, pre-delete commit failure and local delete failure stay distinct. */
class ReportQueueLocalDeleteFailureRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun localDeleteFailureKeepsBytesAndTheNextStatusCallRecoversWithoutPost() {
        val storage = DeleteFailingStorage(FileReportQueueStorage(temporary.newFolder()))
        val store = AndroidReportQueueStore(
            capacityProfile = ApprovedReportQueueCapacityProfile(8, 1_024, 4_096L, 32_768L, 6, 24_576L),
            storageFactory = { storage },
            aead = Base64Aead(),
            idFactory = { UUID(0L, 7L) },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )
        val report = requireNotNull(store.enqueue(
            expectedReporterActorId = ACTOR,
            metadataUtf8 = "{\"reporter_user_id\":\"$ACTOR\",\"trigger\":\"voice\",\"auto_reported\":false}"
                .toByteArray(),
            imageJpeg = reportTestJpeg(),
            priority = ReportQueuePriority.EXPLICIT,
            walkSessionId = WALK,
            consentReceiptSha256 = "c".repeat(64),
        ))
        var posts = 0
        var statusReceipt: ReportQueueReceipt? = receipt(report)
        val transport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport) =
                CancellableNetworkCall.blocking<ReportQueueReceipt?> { statusReceipt }
            override fun uploadCall(report: QueuedReport) =
                CancellableNetworkCall.blocking<ReportQueueReceipt> { posts += 1; error("no post expected") }
        }
        val coordinator = ReportQueueDrainCoordinator(store)

        storage.failDelete = true
        assertEquals(ReportQueueDrainOutcome.LOCAL_DELETE_FAILED,
            requireNotNull(coordinator.startNext({ context() }, transport)).execute())
        assertEquals(1, store.queuedReports().size)

        statusReceipt = receipt(report).copy(payloadBytes = report.payload.payloadBytes + 1)
        storage.failDelete = false
        assertEquals(ReportQueueDrainOutcome.RECEIPT_REJECTED,
            requireNotNull(coordinator.startNext({ context() }, transport)).execute())
        assertEquals(ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED,
            requireNotNull(coordinator.startNext({ context() }, transport.withReceipt(receipt(report))) { false }).execute())
        assertEquals(1, store.queuedReports().size)

        statusReceipt = receipt(report)
        assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS,
            requireNotNull(coordinator.startNext({ context() }, transport)).execute())
        assertEquals(0, store.queuedReports().size)
        assertEquals(0, posts)
    }

    private fun ReportQueueTransport.withReceipt(receipt: ReportQueueReceipt) = object : ReportQueueTransport {
        override fun statusCall(report: QueuedReport) =
            CancellableNetworkCall.blocking<ReportQueueReceipt?> { receipt }
        override fun uploadCall(report: QueuedReport) = this@withReceipt.uploadCall(report)
    }

    private fun receipt(report: QueuedReport) = ReportQueueReceipt(
        report.payload.reportId, report.payload.payloadSha256, report.payload.payloadBytes,
        REPORT_RECEIPT_MARKER, "123e4567-e89b-42d3-a456-426614174002",
    )

    private fun context() = ReportQueueDrainContext(
        WalkSessionState.ENDED, true, true, true, true, true, true,
        ACTOR, WALK, "c".repeat(64), 1L,
    )

    private class DeleteFailingStorage(
        private val inner: FileReportQueueStorage,
    ) : ReportQueueStorage by inner {
        @Volatile var failDelete = false

        override fun <T> withLockedAccess(action: (LockedReportQueueStorage) -> T): T? =
            inner.withLockedAccess { access ->
                action(object : LockedReportQueueStorage by access {
                    override fun delete(reportId: String): Boolean =
                        !failDelete && access.delete(reportId)
                })
            }
    }

    private class Base64Aead : LocalAead {
        override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) =
            AeadSealResult.Sealed(Base64.getEncoder().encodeToString(plaintext))
        override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits) =
            AeadOpenResult.Opened(Base64.getDecoder().decode(envelope), 1, false)
        override fun destroyVersion(version: Int) = true
        override fun destroyKnownVersions() = true
        override fun createFreshAfterVerifiedPurge() = true
    }

    private companion object {
        const val ACTOR = "walker-1"
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
    }
}
