package kr.co.hanium.dreamup.walksafe.report

import java.io.IOException
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S15-36: when the atomic move published the entry but the following file/directory sync
 * failed, the write is UNCERTAIN, not FAILED. enqueue re-reads the same report ID and payload
 * hash and returns that entry, so no retry creates a second ID for the same report.
 */
class ReportQueueUncertainWriteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun syncFailureAfterRenameIsUncertainAndTheFileStays() {
        val root = temporaryFolder.newFolder("uncertain-storage")
        val storage = FileReportQueueStorage(root) { throw IOException("injected directory fsync") }
        assertEquals(
            ReportQueueWriteOutcome.UNCERTAIN,
            storage.writeAtomicallyWithOutcome(REPORT_1, 2, 100L, emptySet()) { "envelope-one" },
        )
        assertEquals("envelope-one", FileReportQueueStorage(root).read(REPORT_1))
        // The Boolean contract stays conservative for other callers.
        assertFalse(storage.writeAtomicallyIfAllowed(REPORT_2, 3, 100L, emptySet()) { "two" })
        assertEquals(setOf(REPORT_1, REPORT_2), FileReportQueueStorage(root).listReportIds())
        assertEquals(
            ReportQueueWriteOutcome.FAILED,
            storage.writeAtomicallyWithOutcome(REPORT_1, 5, 100L, emptySet()) { "again" },
        )
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        val healthy = FileReportQueueStorage(root)
        assertEquals(
            ReportQueueWriteOutcome.STORED,
            healthy.writeAtomicallyWithOutcome(REPORT_3, 5, 100L, emptySet()) { "three" },
        )
    }

    @Test
    fun enqueueConfirmsTheUncertainEntryUnderTheSameIdAndHash() {
        val root = temporaryFolder.newFolder("uncertain-enqueue")
        var idCalls = 0
        val store = AndroidReportQueueStore(
            capacityProfile = PROFILE,
            storageFactory = { FileReportQueueStorage(root) { throw IOException("injected fsync") } },
            aead = MemoryAead(),
            idFactory = {
                idCalls += 1
                UUID.fromString(REPORT_1)
            },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )
        val queued = store.enqueue(ACTOR_ID, metadata(), reportTestJpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT)
        assertNotNull("an uncertain but confirmed write is the stored report", queued)
        assertEquals(REPORT_1, queued!!.payload.reportId)
        assertEquals(1, idCalls)
        val listed = store.queuedReports()
        assertEquals(listOf(REPORT_1), listed.map { it.payload.reportId })
        assertEquals(queued.payload.payloadSha256, listed.single().payload.payloadSha256)
        assertEquals(setOf(REPORT_1), FileReportQueueStorage(root).listReportIds())
    }

    @Test
    fun uncertainWithoutAReadableMatchingEntryIsNotReportedAsStored() {
        val storage = UncertainStorage(storeValue = false)
        val store = AndroidReportQueueStore(
            capacityProfile = PROFILE,
            storageFactory = { storage },
            aead = MemoryAead(),
            idFactory = { UUID.fromString(REPORT_1) },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )
        assertNull(store.enqueue(ACTOR_ID, metadata(), reportTestJpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT))
        val stored = UncertainStorage(storeValue = true)
        val confirmed = AndroidReportQueueStore(
            capacityProfile = PROFILE,
            storageFactory = { stored },
            aead = MemoryAead(),
            idFactory = { UUID.fromString(REPORT_2) },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )
        assertEquals(
            REPORT_2,
            confirmed.enqueue(ACTOR_ID, metadata(), reportTestJpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT)
                ?.payload?.reportId,
        )
        assertTrue(stored.values.keys == setOf(REPORT_2))
    }

    private fun metadata(): ByteArray =
        "{\"reporter_user_id\":\"$ACTOR_ID\",\"trigger\":\"voice\",\"auto_reported\":false}".toByteArray()

    /** Returns UNCERTAIN; optionally keeps the envelope as a published file would. */
    private class UncertainStorage(private val storeValue: Boolean) : ReportQueueStorage {
        val values = mutableMapOf<String, String>()
        private val locked = object : LockedReportQueueStorage {
            override fun listReportIds(): Set<String> = values.keys.toSet()
            override fun read(reportId: String): String? = values[reportId]
            override fun delete(reportId: String): Boolean = values.remove(reportId).let { true }
            override fun hasFence(name: String): Boolean = false
            override fun measureUsage() = ReportQueueStorageUsage(values.size, values.values.sumOf { it.length.toLong() })
        }
        override fun listReportIds(): Set<String> = values.keys.toSet()
        override fun read(reportId: String): String? = values[reportId]
        override fun <T> withLockedAccess(action: (LockedReportQueueStorage) -> T): T = action(locked)
        override fun writeAtomicallyIfAllowed(
            reportId: String, entryLimit: Int, storedByteLimit: Long, blockingFences: Set<String>,
            storedEntryByteLimit: Long, envelopeFactory: () -> String?,
        ): Boolean = error("enqueue must use the outcome-aware write")
        override fun writeAtomicallyWithOutcome(
            reportId: String, entryLimit: Int, storedByteLimit: Long, blockingFences: Set<String>,
            storedEntryByteLimit: Long, envelopeFactory: () -> String?,
        ): ReportQueueWriteOutcome {
            val envelope = envelopeFactory() ?: return ReportQueueWriteOutcome.FAILED
            if (storeValue) values[reportId] = envelope
            return ReportQueueWriteOutcome.UNCERTAIN
        }
        override fun delete(reportId: String): Boolean = values.remove(reportId).let { true }
        override fun hasFence(name: String): Boolean = false
        override fun purgeAllWithFence(
            name: String, keyLifecycle: (ReportQueuePurgeContext) -> Boolean,
        ): Boolean = error("unused")
        override fun deleteMatchingWithFences(
            pendingFence: String, initialPermanentFences: Set<String>,
            permanentFenceForMatch: (reportId: String, envelope: String) -> String?,
        ): Boolean = error("unused")
        override fun measureUsage() = ReportQueueStorageUsage(values.size, values.values.sumOf { it.length.toLong() })
    }

    private class MemoryAead : LocalAead {
        private val values = mutableMapOf<String, ByteArray>()
        private var next = 0
        override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult {
            val envelope = "uncertain-${next++}"
            values[envelope] = plaintext.copyOf()
            return AeadSealResult.Sealed(envelope)
        }
        override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
            values[envelope]?.copyOf()?.let { AeadOpenResult.Opened(it, 1, false) }
                ?: AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
        override fun destroyVersion(version: Int): Boolean = true
        override fun destroyKnownVersions(): Boolean = true
        override fun createFreshAfterVerifiedPurge(): Boolean = true
    }

    private companion object {
        val PROFILE = ApprovedReportQueueCapacityProfile(
            maxEntries = 10,
            maxPayloadBytes = 1_024 * 1_024,
            maxStoredEntryBytes = 4L * 1_024L * 1_024L,
            maxTotalBytes = 16L * 1_024L * 1_024L,
            automaticMaxEntries = 9,
            automaticMaxTotalBytes = 8L * 1_024L * 1_024L,
        )
        const val REPORT_1 = "123e4567-e89b-42d3-a456-426614174000"
        const val REPORT_2 = "123e4567-e89b-42d3-a456-426614174001"
        const val REPORT_3 = "123e4567-e89b-42d3-a456-426614174004"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174002"
        const val ACTOR_ID = "a"
        val CONSENT = "c".repeat(64)
    }
}
