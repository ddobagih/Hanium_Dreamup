package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import java.util.Base64
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** S15-6: queue priority, metadata.trigger and metadata.auto_reported must agree. */
class ReportQueuePurposeInvariantTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun contradictoryOrMissingPurposeMetadataIsRejectedBeforeIdOrStorage() {
        val rejected = listOf(
            metadata("auto", true) to ReportQueuePriority.EXPLICIT,
            metadata("voice", false) to ReportQueuePriority.AUTOMATIC,
            metadata("on_screen", false) to ReportQueuePriority.AUTOMATIC,
            metadata("auto", null) to ReportQueuePriority.AUTOMATIC,
            metadata("voice", null) to ReportQueuePriority.EXPLICIT,
            metadata("auto", false) to ReportQueuePriority.AUTOMATIC,
            metadata("auto", false) to ReportQueuePriority.EXPLICIT,
            metadata("voice", true) to ReportQueuePriority.EXPLICIT,
            metadata("voice", true) to ReportQueuePriority.AUTOMATIC,
            metadata(null, true) to ReportQueuePriority.AUTOMATIC,
            metadata("AUTO", true) to ReportQueuePriority.AUTOMATIC,
            metadata("auto", "true") to ReportQueuePriority.AUTOMATIC,
            metadata("voice", "false") to ReportQueuePriority.EXPLICIT,
        )
        for ((bytes, priority) in rejected) {
            var idCalls = 0
            val root = temporary.newFolder()
            val store = store(root) { idCalls += 1; UUID(0L, 1L) }
            assertNull(String(bytes), store.enqueue(bytes, priority))
            assertEquals(0, idCalls)
            assertTrue(store.queuedReports().isEmpty())
            assertTrue(reportFiles(root).isEmpty())
        }
        assertNull(reportQueuePriorityFromMetadataOrNull("not-json".toByteArray()))
    }

    @Test
    fun matchingPurposeMetadataIsStoredWithItsPriority() {
        val accepted = listOf(
            metadata("auto", true) to ReportQueuePriority.AUTOMATIC,
            metadata("voice", false) to ReportQueuePriority.EXPLICIT,
            metadata("on_screen", false) to ReportQueuePriority.EXPLICIT,
        )
        for ((bytes, priority) in accepted) {
            assertEquals(priority, reportQueuePriorityFromMetadataOrNull(bytes))
            val store = store(temporary.newFolder()) { UUID(0L, 2L) }
            assertEquals(priority, assertNotNullAndGet(store.enqueue(bytes, priority)).priority)
            assertEquals(priority, store.queuedReports().single().priority)
        }
    }

    @Test
    fun storedPriorityFlippedAfterEnqueueFailsClosedOnRead() {
        for (original in ReportQueuePriority.entries) {
            val root = temporary.newFolder()
            val bytes = if (original == ReportQueuePriority.AUTOMATIC) {
                metadata("auto", true)
            } else {
                metadata("voice", false)
            }
            val queued = assertNotNullAndGet(store(root) { UUID(0L, 3L) }.enqueue(bytes, original))
            val flipped = ReportQueuePriority.entries.single { it != original }
            val file = reportFiles(root).single()
            val plaintext = JSONObject(String(Base64.getDecoder().decode(file.readText())))
            file.writeText(
                Base64.getEncoder().encodeToString(
                    plaintext.put("priority", flipped.name).toString().toByteArray(),
                ),
            )

            val reopened = store(root) { error("read must not create IDs") }
            assertTrue(reopened.queuedReports().isEmpty())
            assertNull(reopened.nextForRecoveryDrain(ACTOR))
            assertTrue(
                !reopened.deleteAfterReceipt(
                    ACTOR,
                    ReportQueueReceipt(
                        queued.payload.reportId,
                        queued.payload.payloadSha256,
                        queued.payload.payloadBytes,
                        REPORT_RECEIPT_MARKER,
                        "123e4567-e89b-42d3-a456-426614174009",
                    ),
                ),
            )
        }
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun AndroidReportQueueStore.enqueue(
        metadataUtf8: ByteArray,
        priority: ReportQueuePriority,
    ): QueuedReport? = enqueue(
        expectedReporterActorId = ACTOR,
        metadataUtf8 = metadataUtf8,
        imageJpeg = reportTestJpeg(),
        priority = priority,
        walkSessionId = WALK,
        consentReceiptSha256 = "c".repeat(64),
    )

    private fun store(root: File, idFactory: () -> UUID) = AndroidReportQueueStore(
        rootDirectory = root,
        capacityProfile = ApprovedReportQueueCapacityProfile(8, 1_024, 4_096L, 32_768L, 6, 24_576L),
        aead = IdentityAead(),
        idFactory = idFactory,
        nowMillis = { 1_000L },
    )

    private fun reportFiles(root: File): List<File> =
        File(root, "report_queue_v1/reports").listFiles()?.filter { it.name.endsWith(".aead") }.orEmpty()

    private fun metadata(trigger: String?, autoReported: Any?): ByteArray = JSONObject()
        .put("reporter_user_id", ACTOR)
        .apply {
            if (trigger != null) put("trigger", trigger)
            if (autoReported != null) put("auto_reported", autoReported)
        }
        .toString()
        .toByteArray()

    private companion object {
        const val ACTOR = "walker-1"
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
    }
}

private class IdentityAead : LocalAead {
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits) =
        AeadSealResult.Sealed(Base64.getEncoder().encodeToString(plaintext))
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits) =
        AeadOpenResult.Opened(Base64.getDecoder().decode(envelope), 1, false)
    override fun destroyVersion(version: Int) = true
    override fun destroyKnownVersions() = true
    override fun createFreshAfterVerifiedPurge() = true
}
