package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidReportQueueStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun productionNullProfileBlocksAdmissionAndReadWithoutTouchingStorage() {
        var storageCalls = 0
        var idCalls = 0
        val aead = FakeQueueAead()
        val store = AndroidReportQueueStore(
            capacityProfile = null,
            storageFactory = {
                storageCalls += 1
                FakeReportQueueStorage()
            },
            aead = aead,
            idFactory = {
                idCalls += 1
                UUID.fromString(REPORT_1)
            },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )

        assertNull(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT))
        assertTrue(store.queuedReports().isEmpty())
        assertNull(store.nextForDrain())
        assertNull(store.nextForDrain(WALK_ID, CONSENT))
        assertEquals(0, store.countForDrain(WALK_ID, CONSENT))
        assertEquals(0, store.pruneExpired())
        assertFalse(store.deleteAfterReceipt(receipt(REPORT_1, "a".repeat(64), 1L)))
        assertEquals(0, storageCalls)
        assertEquals(0, idCalls)
        assertEquals(0, aead.sealCalls + aead.openCalls)
    }

    @Test
    fun productionNullProfileStillRunsExplicitPrivacyPurgeHooks() {
        var storageCalls = 0
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead()
        val store = AndroidReportQueueStore(
            capacityProfile = null,
            storageFactory = {
                storageCalls += 1
                storage
            },
            aead = aead,
            idFactory = { error("disabled purge must not create report IDs") },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )

        assertTrue(store.onConsentRevoked(CONSENT))
        assertTrue(store.onAccountDeleted())
        assertEquals(1, storageCalls)
        assertEquals(2, aead.destroyCalls)
        assertEquals(1, aead.freshCalls)
        assertTrue("account-deleted" in storage.fences)
    }

    @Test
    fun encryptedEnvelopeSizeNotPlaintextSizeEnforcesStoredByteLimit() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead(envelopeBytes = 64)
        val store = AndroidReportQueueStore(
            capacityProfile = ApprovedReportQueueCapacityProfile(
                maxEntries = 2,
                maxPayloadBytes = 32,
                maxStoredEntryBytes = 63L,
                maxTotalBytes = 126L,
                automaticMaxEntries = 1,
                automaticMaxTotalBytes = 63L,
            ),
            storageFactory = { storage },
            aead = aead,
            idFactory = { UUID.fromString(REPORT_1) },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )

        assertNull(
            store.enqueue(
                metadata(),
                jpeg(),
                ReportQueuePriority.EXPLICIT,
                WALK_ID,
                CONSENT,
            ),
        )
        assertEquals(1, aead.sealCalls)
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun automaticAdmissionPreservesEntryReserveForExplicitReport() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead()
        val ids = ArrayDeque(listOf(UUID.fromString(REPORT_1), UUID.fromString(REPORT_2)))
        val store = AndroidReportQueueStore(
            capacityProfile = ApprovedReportQueueCapacityProfile(
                maxEntries = 2,
                maxPayloadBytes = 1_024,
                maxStoredEntryBytes = 1_024L,
                maxTotalBytes = 10_240L,
                automaticMaxEntries = 1,
                automaticMaxTotalBytes = 8_192L,
            ),
            storageFactory = { storage },
            aead = aead,
            idFactory = { ids.removeFirst() },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )

        assertTrue(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.AUTOMATIC, WALK_ID, CONSENT) != null,
        )
        assertNull(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.AUTOMATIC, WALK_ID, CONSENT),
        )
        assertTrue(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT) != null,
        )
        assertNull(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT),
        )
        assertEquals(2, storage.values.size)
    }

    @Test
    fun automaticAdmissionPreservesStoredByteReserveForExplicitReport() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead(envelopeBytes = 32)
        val ids = ArrayDeque(
            listOf(REPORT_1, REPORT_2, REPORT_3).map(UUID::fromString),
        )
        val store = AndroidReportQueueStore(
            capacityProfile = ApprovedReportQueueCapacityProfile(
                maxEntries = 3,
                maxPayloadBytes = 32,
                maxStoredEntryBytes = 32L,
                maxTotalBytes = 96L,
                automaticMaxEntries = 2,
                automaticMaxTotalBytes = 48L,
            ),
            storageFactory = { storage },
            aead = aead,
            idFactory = { ids.removeFirst() },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )

        assertTrue(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.AUTOMATIC, WALK_ID, CONSENT) != null,
        )
        assertNull(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.AUTOMATIC, WALK_ID, CONSENT),
        )
        assertTrue(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT) != null,
        )
        assertEquals(64L, requireNotNull(storage.measureUsage()).totalBytes)
    }

    @Test
    fun generatesCanonicalIdOnceAndRestoresFrozenBytesAfterRestart() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead()
        var idCalls = 0
        var now = 1_000L
        val originalMetadata = metadata()
        val originalImage = jpeg()
        val store = store(storage, aead, { now }) {
            idCalls += 1
            UUID.fromString(REPORT_1)
        }

        val queued = requireNotNull(
            store.enqueue(
                originalMetadata,
                originalImage,
                ReportQueuePriority.AUTOMATIC,
                WALK_ID,
                CONSENT,
            ),
        )
        originalMetadata.fill(0)
        originalImage.fill(0)

        val restoredStore = store(storage, aead, { now }) { error("restore must not create IDs") }
        val restored = requireNotNull(restoredStore.nextForDrain())
        assertEquals(1, idCalls)
        assertEquals(REPORT_1, queued.payload.reportId)
        assertTrue(queued.payload.contentEquals(restored.payload))
        assertArrayEquals(metadata(), restored.payload.metadataUtf8())
        assertArrayEquals(jpeg(), restored.payload.imageJpeg())
    }

    @Test
    fun explicitReportsPrecedeAutomaticAndFakeClockExpiresAtThirtyDays() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead()
        var now = 5_000L
        val ids = ArrayDeque(listOf(UUID.fromString(REPORT_1), UUID.fromString(REPORT_2)))
        val store = store(storage, aead, { now }) { ids.removeFirst() }

        assertTrue(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.AUTOMATIC, WALK_ID, CONSENT) != null,
        )
        now += 1L
        assertTrue(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT) != null,
        )
        assertEquals(REPORT_2, store.nextForDrain()?.payload?.reportId)

        now = 5_000L + REPORT_QUEUE_TTL_MS
        assertEquals(1, store.pruneExpired())
        assertEquals(listOf(REPORT_2), store.queuedReports().map { it.payload.reportId })
        now += 1L
        assertEquals(1, store.pruneExpired())
        assertTrue(store.queuedReports().isEmpty())
    }

    @Test
    fun exactDrainSelectorPinsWalkAndReceiptWhilePreservingPriorityOrder() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead()
        var now = 5_000L
        val ids = ArrayDeque(
            listOf(REPORT_1, REPORT_2, REPORT_3, REPORT_4).map(UUID::fromString),
        )
        val store = store(storage, aead, { now++ }) { ids.removeFirst() }

        store.enqueue(metadata(), jpeg(), ReportQueuePriority.AUTOMATIC, WALK_ID, CONSENT)
        store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, OTHER_WALK_ID, CONSENT)
        store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, NEW_CONSENT)
        store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT)

        assertEquals(2, store.countForDrain(WALK_ID, CONSENT))
        assertEquals(REPORT_4, store.nextForDrain(WALK_ID, CONSENT)?.payload?.reportId)
        assertEquals(1, store.countForDrain(OTHER_WALK_ID, CONSENT))
        assertEquals(REPORT_2, store.nextForDrain(OTHER_WALK_ID, CONSENT)?.payload?.reportId)
        assertEquals(0, store.countForDrain("not-a-walk", CONSENT))
        assertNull(store.nextForDrain(WALK_ID, "not-a-receipt"))
    }

    @Test
    fun receiptMustMatchIdHashBytesAndPersistenceMarkerExactly() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead()
        val store = store(storage, aead, { 1_000L }) { UUID.fromString(REPORT_1) }
        val report = requireNotNull(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT),
        )

        assertFalse(
            store.deleteAfterReceipt(
                receipt(REPORT_2, report.payload.payloadSha256, report.payload.payloadBytes),
            ),
        )
        assertFalse(
            store.deleteAfterReceipt(
                receipt(REPORT_1, "f".repeat(64), report.payload.payloadBytes),
            ),
        )
        assertFalse(
            store.deleteAfterReceipt(
                receipt(REPORT_1, report.payload.payloadSha256, report.payload.payloadBytes + 1L),
            ),
        )
        assertFalse(
            store.deleteAfterReceipt(
                receipt(
                    REPORT_1,
                    report.payload.payloadSha256,
                    report.payload.payloadBytes,
                    marker = "DATABASE_ONLY",
                ),
            ),
        )
        assertFalse(
            store.deleteAfterReceipt(
                receipt(
                    REPORT_1,
                    report.payload.payloadSha256,
                    report.payload.payloadBytes,
                    persistenceMarker = "not-a-uuid",
                ),
            ),
        )
        assertTrue(
            store.deleteAfterReceipt(
                receipt(REPORT_1, report.payload.payloadSha256, report.payload.payloadBytes),
            ),
        )
        assertTrue(store.queuedReports().isEmpty())
    }

    @Test
    fun consentFenceBlocksOnlyRevokedReceiptAndAllowsNewVerifiedReceipt() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead()
        val store = store(storage, aead, { 1_000L }) { UUID.fromString(REPORT_1) }
        assertTrue(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT) != null)

        assertTrue(store.onConsentRevoked(CONSENT))
        assertTrue(storage.values.isEmpty())
        assertTrue(aead.destroyed)
        assertNull(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT))
        assertTrue(
            store.enqueue(
                metadata(),
                jpeg(),
                ReportQueuePriority.EXPLICIT,
                WALK_ID,
                NEW_CONSENT,
            ) != null,
        )
    }

    @Test
    fun accountDeletionFenceCannotBeClearedByAConsentChange() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead()
        val store = store(storage, aead, { 1_000L }) { UUID.fromString(REPORT_1) }
        assertTrue(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT) != null)

        assertTrue(store.onAccountDeleted())
        assertTrue(storage.values.isEmpty())
        assertNull(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, NEW_CONSENT))
        assertTrue(store.onConsentRevoked(CONSENT))
        assertEquals(0, aead.freshCalls)
        assertNull(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, NEW_CONSENT))
    }

    @Test
    fun durableFencesBlockReadsEvenWhenAnEnvelopeSurvives() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead()
        val store = store(storage, aead, { 1_000L }) { UUID.fromString(REPORT_1) }
        assertTrue(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT) != null)

        storage.fences += "lifecycle-pending"
        assertTrue(store.queuedReports().isEmpty())
        storage.fences -= "lifecycle-pending"
        storage.fences += "consent-revoked-$CONSENT"
        assertTrue(store.queuedReports().isEmpty())
        storage.fences -= "consent-revoked-$CONSENT"
        storage.fences += "account-deleted"
        assertTrue(store.queuedReports().isEmpty())
    }

    @Test
    fun failedKeyDestroyLeavesGlobalBarrierUntilRetryCompletes() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead(destroySucceeds = false)
        val store = store(storage, aead, { 1_000L }) { UUID.fromString(REPORT_1) }
        assertTrue(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT) != null)

        assertFalse(store.onConsentRevoked(CONSENT))
        assertTrue("lifecycle-pending" in storage.fences)
        assertTrue(store.queuedReports().isEmpty())
        assertNull(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, NEW_CONSENT))

        aead.destroySucceeds = true
        assertTrue(store.onConsentRevoked(CONSENT))
        assertFalse("lifecycle-pending" in storage.fences)
        assertTrue(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, NEW_CONSENT) != null,
        )
    }

    @Test
    fun failedFreshKeyCreationLeavesGlobalBarrierUntilRetryCompletes() {
        val storage = FakeReportQueueStorage()
        val aead = FakeQueueAead(freshSucceeds = false)
        val store = store(storage, aead, { 1_000L }) { UUID.fromString(REPORT_1) }

        assertFalse(store.onConsentRevoked(CONSENT))
        assertTrue("lifecycle-pending" in storage.fences)
        assertNull(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, NEW_CONSENT))

        aead.freshSucceeds = true
        assertTrue(store.onConsentRevoked(CONSENT))
        assertFalse("lifecycle-pending" in storage.fences)
        assertEquals(2, aead.freshCalls)
    }

    @Test
    fun fileStorageUsesFinalAtomicNamesAndScopedDeletion() {
        val root = temporaryFolder.newFolder("report-queue")
        val storage = FileReportQueueStorage(root)

        assertTrue(
            storage.writeAtomicallyIfAllowed(REPORT_1, 2, 100L, emptySet()) { "envelope-one" },
        )
        assertFalse(
            storage.writeAtomicallyIfAllowed(REPORT_1, 2, 100L, emptySet()) { "envelope-two" },
        )
        assertEquals("envelope-one", storage.read(REPORT_1))
        assertEquals(
            ReportQueueStorageUsage(1, "envelope-one".toByteArray().size.toLong()),
            storage.measureUsage(),
        )
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
        assertTrue(storage.delete(REPORT_1))
        assertNull(storage.read(REPORT_1))
        assertEquals(ReportQueueStorageUsage(0, 0L), storage.measureUsage())
    }

    @Test
    fun fileStorageSerializesFinalCapacityAdmissionAcrossInstances() {
        val root = temporaryFolder.newFolder("report-queue-capacity-race")
        val first = FileReportQueueStorage(root)
        val second = FileReportQueueStorage(root)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val envelopeFactories = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf(
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    first.writeAtomicallyIfAllowed(REPORT_1, 1, 100L, emptySet()) {
                        envelopeFactories.incrementAndGet()
                        "first"
                    }
                },
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    second.writeAtomicallyIfAllowed(REPORT_2, 1, 100L, emptySet()) {
                        envelopeFactories.incrementAndGet()
                        "second"
                    }
                },
            )
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()

            assertEquals(1, writes.count { it.get(2, TimeUnit.SECONDS) })
            assertEquals(1, envelopeFactories.get())
            val usage = requireNotNull(first.measureUsage())
            assertEquals(1, usage.reportFileCount)
            assertTrue(usage.totalBytes in setOf(5L, 6L))
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun fileStorageSerializesFinalByteAdmissionAcrossInstances() {
        val root = temporaryFolder.newFolder("report-queue-byte-race")
        val first = FileReportQueueStorage(root)
        val second = FileReportQueueStorage(root)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val envelopeFactories = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf(
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    first.writeAtomicallyIfAllowed(REPORT_1, 2, 10L, emptySet()) {
                        envelopeFactories.incrementAndGet()
                        "first!"
                    }
                },
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    second.writeAtomicallyIfAllowed(REPORT_2, 2, 10L, emptySet()) {
                        envelopeFactories.incrementAndGet()
                        "second"
                    }
                },
            )
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()

            assertEquals(1, writes.count { it.get(2, TimeUnit.SECONDS) })
            assertEquals(2, envelopeFactories.get())
            assertEquals(ReportQueueStorageUsage(1, 6L), first.measureUsage())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun fileStorageSerializesCreateOnlyWriteAndFenceCheckAcrossInstances() {
        val root = temporaryFolder.newFolder("report-queue-id-race")
        val first = FileReportQueueStorage(root)
        val second = FileReportQueueStorage(root)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf(
                "first" to executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    first.writeAtomicallyIfAllowed(REPORT_1, 2, 100L, emptySet()) { "first" }
                },
                "second" to executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    second.writeAtomicallyIfAllowed(REPORT_1, 2, 100L, emptySet()) { "second" }
                },
            )
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()

            val outcomes = writes.map { (value, future) ->
                value to future.get(2, TimeUnit.SECONDS)
            }
            assertEquals(1, outcomes.count { it.second })
            assertEquals(outcomes.single { it.second }.first, first.read(REPORT_1))
            assertTrue(first.purgeAllWithFence("account-deleted") { true })
            assertFalse(
                second.writeAtomicallyIfAllowed(
                    REPORT_2,
                    2,
                    100L,
                    setOf("account-deleted"),
                ) { "blocked" },
            )
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun fileStorageKeepsGlobalBarrierAfterKeyFailureUntilRetryCompletes() {
        val root = temporaryFolder.newFolder("report-queue-key-failure")
        val storage = FileReportQueueStorage(root)

        assertFalse(
            storage.purgeAllWithFence("consent-revoked-$CONSENT") { false },
        )
        assertTrue(storage.hasFence("lifecycle-pending"))
        var envelopeCalls = 0
        assertFalse(
            storage.writeAtomicallyIfAllowed(
                REPORT_1,
                2,
                100L,
                setOf("lifecycle-pending"),
            ) {
                envelopeCalls += 1
                "blocked"
            },
        )
        assertEquals(0, envelopeCalls)

        assertTrue(
            storage.purgeAllWithFence("consent-revoked-$CONSENT") { context ->
                context.storageReady && !context.accountDeleted
            },
        )
        assertFalse(storage.hasFence("lifecycle-pending"))
        assertTrue(
            storage.writeAtomicallyIfAllowed(
                REPORT_1,
                2,
                100L,
                setOf("lifecycle-pending", "consent-revoked-$NEW_CONSENT"),
            ) { "allowed" },
        )
    }

    @Test
    fun fileStorageRecoversPendingAccountDeletionBeforeConsentPurge() {
        val root = temporaryFolder.newFolder("report-queue-pending-account")
        val reports = root.resolve("report_queue_v1/reports")
        val fences = root.resolve("report_queue_v1/fences")
        assertTrue(reports.mkdirs())
        assertTrue(fences.mkdir())
        fences.resolve("lifecycle-pending.fence").writeText("account-deleted\n")
        val storage = FileReportQueueStorage(root)
        var observed: ReportQueuePurgeContext? = null

        assertTrue(
            storage.purgeAllWithFence("consent-revoked-$CONSENT") { context ->
                observed = context
                true
            },
        )

        assertTrue(requireNotNull(observed).accountDeleted)
        assertTrue(storage.hasFence("account-deleted"))
        assertTrue(storage.hasFence("consent-revoked-$CONSENT"))
        assertFalse(storage.hasFence("lifecycle-pending"))
    }

    @Test
    fun fileStorageKeepsWriteAndPurgeInOneLifecycleCriticalSection() {
        val root = temporaryFolder.newFolder("report-queue-purge-race")
        val writer = FileReportQueueStorage(root)
        val purger = FileReportQueueStorage(root)
        val envelopeFactoryEntered = CountDownLatch(1)
        val releaseEnvelopeFactory = CountDownLatch(1)
        val purgeAttempted = CountDownLatch(1)
        val keyLifecycleEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val write = executor.submit<Boolean> {
                writer.writeAtomicallyIfAllowed(REPORT_1, 2, 100L, emptySet()) {
                    envelopeFactoryEntered.countDown()
                    check(releaseEnvelopeFactory.await(2, TimeUnit.SECONDS))
                    "old-key-envelope"
                }
            }
            assertTrue(envelopeFactoryEntered.await(2, TimeUnit.SECONDS))
            assertQueueFileLockHeld(root)
            val purge = executor.submit<Boolean> {
                purgeAttempted.countDown()
                purger.purgeAllWithFence("consent-revoked-$CONSENT") {
                    keyLifecycleEntered.countDown()
                    true
                }
            }
            assertTrue(purgeAttempted.await(2, TimeUnit.SECONDS))
            releaseEnvelopeFactory.countDown()

            assertTrue(write.get(2, TimeUnit.SECONDS))
            assertTrue(purge.get(2, TimeUnit.SECONDS))
            assertTrue(keyLifecycleEntered.await(2, TimeUnit.SECONDS))
            assertNull(writer.read(REPORT_1))
            assertTrue(writer.hasFence("consent-revoked-$CONSENT"))
        } finally {
            releaseEnvelopeFactory.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun fileStorageDoesNotSealNewConsentUntilPurgeKeyLifecycleCompletes() {
        val root = temporaryFolder.newFolder("report-queue-key-race")
        val purger = FileReportQueueStorage(root)
        val writer = FileReportQueueStorage(root)
        val keyLifecycleEntered = CountDownLatch(1)
        val releaseKeyLifecycle = CountDownLatch(1)
        val writeAttempted = CountDownLatch(1)
        val envelopeFactoryEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val purge = executor.submit<Boolean> {
                purger.purgeAllWithFence("consent-revoked-$CONSENT") {
                    keyLifecycleEntered.countDown()
                    check(releaseKeyLifecycle.await(2, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(keyLifecycleEntered.await(2, TimeUnit.SECONDS))
            assertQueueFileLockHeld(root)
            val write = executor.submit<Boolean> {
                writeAttempted.countDown()
                writer.writeAtomicallyIfAllowed(
                    REPORT_1,
                    2,
                    100L,
                    setOf("account-deleted", "consent-revoked-$NEW_CONSENT"),
                ) {
                    envelopeFactoryEntered.countDown()
                    "new-key-envelope"
                }
            }
            assertTrue(writeAttempted.await(2, TimeUnit.SECONDS))
            releaseKeyLifecycle.countDown()

            assertTrue(purge.get(2, TimeUnit.SECONDS))
            assertTrue(write.get(2, TimeUnit.SECONDS))
            assertTrue(envelopeFactoryEntered.await(2, TimeUnit.SECONDS))
            assertEquals("new-key-envelope", writer.read(REPORT_1))
        } finally {
            releaseKeyLifecycle.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun fileStorageHoldsOsLockWhileOpeningQueuedPlaintext() {
        val root = temporaryFolder.newFolder("report-queue-read-lock")
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val aead = FakeQueueAead(
            openHook = {
                openEntered.countDown()
                check(releaseOpen.await(2, TimeUnit.SECONDS))
            },
        )
        val store = AndroidReportQueueStore(
            rootDirectory = root,
            capacityProfile = PROFILE,
            aead = aead,
            idFactory = { UUID.fromString(REPORT_1) },
            nowMillis = { 1_000L },
        )
        assertTrue(store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT) != null)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<List<QueuedReport>> { store.queuedReports() }
            assertTrue(openEntered.await(2, TimeUnit.SECONDS))
            assertQueueFileLockHeld(root)
            releaseOpen.countDown()
            assertEquals(1, read.get(2, TimeUnit.SECONDS).size)
        } finally {
            releaseOpen.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun fileStorageCreatesZeroByteLockAndRejectsTampering() {
        val root = temporaryFolder.newFolder("report-queue-lock-integrity")
        val storage = FileReportQueueStorage(root)
        assertTrue(
            storage.writeAtomicallyIfAllowed(REPORT_1, 2, 100L, emptySet()) { "envelope" },
        )
        val lock = root.resolve("report_queue_v1/.queue.lock")
        assertTrue(lock.isFile)
        assertEquals(0L, lock.length())

        lock.writeText("tampered")

        assertNull(storage.measureUsage())
        var envelopeCalls = 0
        assertFalse(
            storage.writeAtomicallyIfAllowed(REPORT_2, 2, 100L, emptySet()) {
                envelopeCalls += 1
                "blocked"
            },
        )
        assertEquals(0, envelopeCalls)
    }

    @Test
    fun fileStorageRejectsDirectoryAndSymlinkLockEntries() {
        val directoryRoot = temporaryFolder.newFolder("report-queue-lock-directory")
        val directoryQueueRoot = directoryRoot.resolve("report_queue_v1")
        assertTrue(directoryQueueRoot.mkdirs())
        assertTrue(directoryQueueRoot.resolve(".queue.lock").mkdir())
        val directoryStorage = FileReportQueueStorage(directoryRoot)
        assertNull(directoryStorage.measureUsage())
        assertFalse(
            directoryStorage.writeAtomicallyIfAllowed(REPORT_1, 2, 100L, emptySet()) { "blocked" },
        )

        val symlinkRoot = temporaryFolder.newFolder("report-queue-lock-symlink")
        val symlinkQueueRoot = symlinkRoot.resolve("report_queue_v1")
        assertTrue(symlinkQueueRoot.mkdirs())
        val symlinkTarget = symlinkRoot.resolve("lock-target")
        symlinkTarget.writeText("")
        Files.createSymbolicLink(
            symlinkQueueRoot.resolve(".queue.lock").toPath(),
            symlinkTarget.toPath(),
        )
        val symlinkStorage = FileReportQueueStorage(symlinkRoot)
        assertNull(symlinkStorage.measureUsage())
        assertFalse(
            symlinkStorage.writeAtomicallyIfAllowed(REPORT_2, 2, 100L, emptySet()) { "blocked" },
        )
    }

    @Test
    fun fileStoragePurgeRejectsQueueRootSymlinkWithoutExternalMutation() {
        val storageBase = temporaryFolder.newFolder("report-queue-root-link")
        val external = temporaryFolder.newFolder("report-queue-root-link-target")
        val externalReports = external.resolve("reports")
        assertTrue(externalReports.mkdirs())
        val victim = externalReports.resolve("victim.partial")
        victim.writeText("must-remain")
        Files.createSymbolicLink(
            storageBase.resolve("report_queue_v1").toPath(),
            external.toPath(),
        )
        val storage = FileReportQueueStorage(storageBase)
        var keyLifecycleCalls = 0

        assertFalse(
            storage.purgeAllWithFence("account-deleted") {
                keyLifecycleCalls += 1
                true
            },
        )

        assertEquals(1, keyLifecycleCalls)
        assertEquals("must-remain", victim.readText())
        assertFalse(external.resolve(".queue.lock").exists())
        assertFalse(external.resolve("fences").exists())
        assertNull(storage.measureUsage())
    }

    @Test
    fun fileStoragePurgeRejectsReportsOrFencesSymlinkBeforeMutation() {
        val reportsBase = temporaryFolder.newFolder("report-queue-reports-link")
        val reportsQueueRoot = reportsBase.resolve("report_queue_v1")
        assertTrue(reportsQueueRoot.mkdirs())
        val externalReports = temporaryFolder.newFolder("report-queue-external-reports")
        val victim = externalReports.resolve("victim.partial")
        victim.writeText("must-remain")
        Files.createSymbolicLink(
            reportsQueueRoot.resolve("reports").toPath(),
            externalReports.toPath(),
        )
        var reportsKeyCalls = 0
        assertFalse(
            FileReportQueueStorage(reportsBase).purgeAllWithFence("account-deleted") {
                reportsKeyCalls += 1
                true
            },
        )
        assertEquals(1, reportsKeyCalls)
        assertEquals("must-remain", victim.readText())
        assertFalse(reportsQueueRoot.resolve("fences").exists())

        val fencesBase = temporaryFolder.newFolder("report-queue-fences-link")
        val fencesQueueRoot = fencesBase.resolve("report_queue_v1")
        assertTrue(fencesQueueRoot.mkdirs())
        val externalFences = temporaryFolder.newFolder("report-queue-external-fences")
        Files.createSymbolicLink(
            fencesQueueRoot.resolve("fences").toPath(),
            externalFences.toPath(),
        )
        var fencesKeyCalls = 0
        assertFalse(
            FileReportQueueStorage(fencesBase).purgeAllWithFence("account-deleted") {
                fencesKeyCalls += 1
                true
            },
        )
        assertEquals(1, fencesKeyCalls)
        assertFalse(externalFences.resolve("account-deleted.fence").exists())
    }

    @Test
    fun unrecognizedRegularFilesConsumeCapacityAndUnsafeEntriesFailClosed() {
        val root = temporaryFolder.newFolder("report-queue-usage")
        val reports = root.resolve("report_queue_v1/reports")
        assertTrue(reports.mkdirs())
        reports.resolve("crash-leftover.partial").writeText("x".repeat(90))
        val profile = ApprovedReportQueueCapacityProfile(
            maxEntries = 3,
            maxPayloadBytes = 32,
            maxStoredEntryBytes = 32L,
            maxTotalBytes = 95L,
            automaticMaxEntries = 2,
            automaticMaxTotalBytes = 60L,
        )
        val store = AndroidReportQueueStore(
            rootDirectory = root,
            capacityProfile = profile,
            aead = FakeQueueAead(),
            idFactory = { UUID.fromString(REPORT_1) },
            nowMillis = { 1_000L },
        )

        assertNull(
            store.enqueue(metadata(), jpeg(), ReportQueuePriority.EXPLICIT, WALK_ID, CONSENT),
        )
        assertEquals(ReportQueueStorageUsage(1, 90L), FileReportQueueStorage(root).measureUsage())

        assertTrue(reports.resolve("unexpected-directory").mkdir())
        assertNull(FileReportQueueStorage(root).measureUsage())
    }

    private fun store(
        storage: FakeReportQueueStorage,
        aead: FakeQueueAead,
        clock: () -> Long,
        idFactory: () -> UUID,
    ): AndroidReportQueueStore = AndroidReportQueueStore(
        capacityProfile = PROFILE,
        storageFactory = { storage },
        aead = aead,
        idFactory = idFactory,
        nowMillis = clock,
        testOnly = Unit,
    )

    private fun receipt(
        id: String,
        hash: String,
        bytes: Long,
        marker: String = REPORT_RECEIPT_MARKER,
        persistenceMarker: String = PERSISTENCE_MARKER,
    ) = ReportQueueReceipt(id, hash, bytes, marker, persistenceMarker)

    private fun metadata() = "{\"kind\":\"hazard\"}".toByteArray()

    private fun jpeg() =
        byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())

    private fun assertQueueFileLockHeld(root: File) {
        val lockFile = root.resolve("report_queue_v1/.queue.lock")
        FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE).use { channel ->
            val acquired = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            try {
                assertNull("queue OS file lock must already be held", acquired)
            } finally {
                acquired?.close()
            }
        }
    }

    private companion object {
        val PROFILE = ApprovedReportQueueCapacityProfile(
            maxEntries = 10,
            maxPayloadBytes = 1_024,
            maxStoredEntryBytes = 1_024L,
            maxTotalBytes = 10_240L,
            automaticMaxEntries = 9,
            automaticMaxTotalBytes = 9_216L,
        )
        const val REPORT_1 = "123e4567-e89b-42d3-a456-426614174000"
        const val REPORT_2 = "123e4567-e89b-42d3-a456-426614174001"
        const val REPORT_3 = "123e4567-e89b-42d3-a456-426614174004"
        const val REPORT_4 = "123e4567-e89b-42d3-a456-426614174005"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174002"
        const val OTHER_WALK_ID = "123e4567-e89b-42d3-a456-426614174006"
        const val PERSISTENCE_MARKER = "123e4567-e89b-42d3-a456-426614174003"
        val CONSENT = "c".repeat(64)
        val NEW_CONSENT = "d".repeat(64)
    }
}

private class FakeReportQueueStorage : ReportQueueStorage {
    val values = mutableMapOf<String, String>()
    val fences = mutableSetOf<String>()
    private val pendingIntents = mutableSetOf<String>()
    private val lockedAccess = object : LockedReportQueueStorage {
        override fun listReportIds(): Set<String> = this@FakeReportQueueStorage.listReportIds()
        override fun read(reportId: String): String? = this@FakeReportQueueStorage.read(reportId)
        override fun delete(reportId: String): Boolean = this@FakeReportQueueStorage.delete(reportId)
        override fun hasFence(name: String): Boolean = this@FakeReportQueueStorage.hasFence(name)
        override fun measureUsage(): ReportQueueStorageUsage =
            this@FakeReportQueueStorage.measureUsage()
    }

    override fun listReportIds(): Set<String> = values.keys.toSet()
    override fun read(reportId: String): String? = values[reportId]
    @Synchronized
    override fun <T> withLockedAccess(action: (LockedReportQueueStorage) -> T): T =
        action(lockedAccess)
    @Synchronized
    override fun writeAtomicallyIfAllowed(
        reportId: String,
        entryLimit: Int,
        storedByteLimit: Long,
        blockingFences: Set<String>,
        storedEntryByteLimit: Long,
        envelopeFactory: () -> String?,
    ): Boolean {
        val usage = measureUsage()
        if (
            blockingFences.any { it in fences } ||
            usage.reportFileCount >= entryLimit ||
            usage.totalBytes >= storedByteLimit ||
            reportId in values
        ) return false
        val envelope = envelopeFactory() ?: return false
        val envelopeBytes = envelope.toByteArray(Charsets.UTF_8).size.toLong()
        if (
            blockingFences.any { it in fences } ||
            usage.reportFileCount >= entryLimit ||
            usage.totalBytes >= storedByteLimit ||
            envelopeBytes > storedEntryByteLimit ||
            envelopeBytes > storedByteLimit - usage.totalBytes ||
            reportId in values
        ) return false
        values[reportId] = envelope
        return true
    }
    override fun delete(reportId: String): Boolean {
        values.remove(reportId)
        return true
    }
    override fun hasFence(name: String): Boolean = name in fences
    @Synchronized
    override fun purgeAllWithFence(
        name: String,
        keyLifecycle: (ReportQueuePurgeContext) -> Boolean,
    ): Boolean {
        pendingIntents += name
        fences += "lifecycle-pending"
        fences += pendingIntents
        values.clear()
        val completed = keyLifecycle(
            ReportQueuePurgeContext(
                storageReady = true,
                accountDeleted = "account-deleted" in pendingIntents ||
                    "account-deleted" in fences,
            ),
        )
        if (completed) {
            pendingIntents.clear()
            fences -= "lifecycle-pending"
        }
        return completed
    }
    override fun measureUsage(): ReportQueueStorageUsage = ReportQueueStorageUsage(
        reportFileCount = values.size,
        totalBytes = values.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() },
    )
}

private class FakeQueueAead(
    private val envelopeBytes: Int? = null,
    var destroySucceeds: Boolean = true,
    var freshSucceeds: Boolean = true,
    private val openHook: (() -> Unit)? = null,
) : LocalAead {
    private val values = mutableMapOf<String, ByteArray>()
    private var next = 0
    var sealCalls = 0
    var openCalls = 0
    var destroyCalls = 0
    var freshCalls = 0
    var destroyed = false

    override fun seal(
        plaintext: ByteArray,
        domainAad: ByteArray,
        limits: AeadLimits,
    ): AeadSealResult {
        sealCalls += 1
        if (plaintext.size > limits.maxPlaintextBytes) {
            return AeadSealResult.Blocked(AeadBlockReason.PLAINTEXT_TOO_LARGE)
        }
        val prefix = "queue-${next++}"
        val envelope = envelopeBytes?.let { prefix.padEnd(it, 'x') } ?: prefix
        values[envelope] = plaintext.copyOf()
        return AeadSealResult.Sealed(envelope)
    }

    override fun open(
        envelope: String,
        domainAad: ByteArray,
        limits: AeadLimits,
    ): AeadOpenResult {
        openCalls += 1
        openHook?.invoke()
        val plaintext = values[envelope]?.copyOf()
            ?: return AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
        return AeadOpenResult.Opened(plaintext, 1, false)
    }

    override fun destroyVersion(version: Int): Boolean = true
    override fun destroyKnownVersions(): Boolean {
        destroyCalls += 1
        if (!destroySucceeds) return false
        destroyed = true
        values.clear()
        return true
    }
    override fun createFreshAfterVerifiedPurge(): Boolean {
        freshCalls += 1
        return freshSucceeds
    }
}
