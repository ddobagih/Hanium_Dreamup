package kr.co.hanium.dreamup.walksafe.report

import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ReportQueueDrainCoordinatorTest {
    @Test
    fun activeWalkCreatesZeroTransportCallbacks() {
        val fixture = fixture()
        var callbacks = 0

        assertNull(
            fixture.coordinator.startNext(
                contextProvider = { context(WalkSessionState.ACTIVE) },
                transport = transport(fixture.report) { callbacks += 1 },
            ),
        )
        assertEquals(0, callbacks)
    }

    @Test
    fun everyAdmissionGateBlocksBeforeTransportConstruction() {
        val blockedContexts = listOf(
            context(appForeground = false),
            context(stationary = false),
            context(networkAllowed = false),
            context(consentAllowed = false),
            context(authorityAllowed = false),
        )
        blockedContexts.forEach { blocked ->
            val fixture = fixture()
            var callbacks = 0
            assertNull(
                fixture.coordinator.startNext(
                    contextProvider = { blocked },
                    transport = transport(fixture.report) { callbacks += 1 },
                ),
            )
            assertEquals(0, callbacks)
        }
        val automaticFixture = fixture(ReportQueuePriority.AUTOMATIC)
        var automaticCallbacks = 0
        assertNull(
            automaticFixture.coordinator.startNext(
                contextProvider = { context(automaticReportingAllowed = false) },
                transport = transport(automaticFixture.report) { automaticCallbacks += 1 },
            ),
        )
        assertEquals(0, automaticCallbacks)
    }

    @Test
    fun exactStatusReceiptDeletesWithoutPost() {
        val fixture = fixture()
        var statusCalls = 0
        var postCalls = 0
        val transport = object : ReportQueueTransport {
            override fun statusCall(
                report: QueuedReport,
            ): CancellableNetworkCall<ReportQueueReceipt?> = CancellableNetworkCall.blocking {
                statusCalls += 1
                receipt(report)
            }

            override fun uploadCall(report: QueuedReport) = CancellableNetworkCall.blocking {
                postCalls += 1
                receipt(report)
            }
        }

        val outcome = requireNotNull(
            fixture.coordinator.startNext({ context() }, transport),
        ).execute()

        assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS, outcome)
        assertEquals(1, statusCalls)
        assertEquals(0, postCalls)
        assertTrue(fixture.store.queuedReports().isEmpty())
    }

    @Test
    fun missingStatusReceiptPostsOnceAndDeletesOnlyAfterUploadReceipt() {
        val fixture = fixture()
        var statusCalls = 0
        var postCalls = 0
        val transport = object : ReportQueueTransport {
            override fun statusCall(
                report: QueuedReport,
            ): CancellableNetworkCall<ReportQueueReceipt?> = CancellableNetworkCall.blocking {
                statusCalls += 1
                null
            }

            override fun uploadCall(report: QueuedReport) = CancellableNetworkCall.blocking {
                postCalls += 1
                receipt(report)
            }
        }

        val outcome = requireNotNull(
            fixture.coordinator.startNext({ context() }, transport),
        ).execute()

        assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD, outcome)
        assertEquals(1, statusCalls)
        assertEquals(1, postCalls)
        assertTrue(fixture.store.queuedReports().isEmpty())
    }

    @Test
    fun mismatchedStatusReceiptIsRejectedWithoutUploadOrDeletion() {
        val fixture = fixture()
        var postCalls = 0
        val transport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport) =
                CancellableNetworkCall.blocking<ReportQueueReceipt?> {
                    receipt(report).copy(payloadSha256 = "f".repeat(64))
                }

            override fun uploadCall(report: QueuedReport) = CancellableNetworkCall.blocking {
                postCalls += 1
                receipt(report)
            }
        }

        val outcome = requireNotNull(
            fixture.coordinator.startNext({ context() }, transport),
        ).execute()

        assertEquals(ReportQueueDrainOutcome.RECEIPT_REJECTED, outcome)
        assertEquals(0, postCalls)
        assertEquals(1, fixture.store.queuedReports().size)
    }

    @Test
    fun requiredPreDeleteCommitFailureKeepsExactReceiptQueueEntry() {
        val fixture = fixture(ReportQueuePriority.AUTOMATIC)
        var commitCalls = 0
        val outcome = requireNotNull(
            fixture.coordinator.startNext(
                contextProvider = { context() },
                transport = object : ReportQueueTransport {
                    override fun statusCall(report: QueuedReport) =
                        CancellableNetworkCall.blocking<ReportQueueReceipt?> { receipt(report) }

                    override fun uploadCall(report: QueuedReport) =
                        error("status receipt must avoid upload")
                },
                beforeDeleteAfterReceipt = {
                    commitCalls += 1
                    false
                },
            ),
        ).execute()

        assertEquals(ReportQueueDrainOutcome.RECEIPT_REJECTED, outcome)
        assertEquals(1, commitCalls)
        assertEquals(1, fixture.store.queuedReports().size)
    }

    @Test
    fun recoverySelectorDrainsPreviousWalkUsingCurrentConsentReceipt() {
        val storage = CoordinatorStorage()
        val aead = CoordinatorAead()
        val ids = ArrayDeque(listOf(UUID.fromString(OTHER_REPORT_ID), UUID.fromString(REPORT_ID)))
        val store = AndroidReportQueueStore(
            capacityProfile = ApprovedReportQueueCapacityProfile(
                maxEntries = 3,
                maxPayloadBytes = 1_024,
                maxStoredEntryBytes = 1_024L,
                maxTotalBytes = 3_072L,
                automaticMaxEntries = 2,
                automaticMaxTotalBytes = 2_048L,
            ),
            storageFactory = { storage },
            aead = aead,
            idFactory = { ids.removeFirst() },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )
        requireNotNull(
            store.enqueue(
                ACTOR_ID,
                metadata(),
                jpeg(),
                ReportQueuePriority.EXPLICIT,
                OTHER_WALK_ID,
                OLD_CONSENT,
            ),
        )
        requireNotNull(
            store.enqueue(
                ACTOR_ID,
                metadata(),
                jpeg(),
                ReportQueuePriority.AUTOMATIC,
                WALK_ID,
                CONSENT,
            ),
        )
        var selectedReportId: String? = null

        val outcome = requireNotNull(
            ReportQueueDrainCoordinator(store).startNext(
                { context() },
                object : ReportQueueTransport {
                    override fun statusCall(report: QueuedReport) =
                        CancellableNetworkCall.blocking<ReportQueueReceipt?> {
                            selectedReportId = report.payload.reportId
                            receipt(report)
                        }

                    override fun uploadCall(report: QueuedReport) =
                        error("status receipt must avoid upload")
                },
            ),
        ).execute()

        assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS, outcome)
        assertEquals(OTHER_REPORT_ID, selectedReportId)
        assertEquals(REPORT_ID, store.nextForDrain()?.payload?.reportId)
    }

    @Test
    fun loginAsAnotherActorCannotUploadOrDeleteThePreviousActorsReport() {
        val storage = CoordinatorStorage()
        val aead = CoordinatorAead()
        val ids = ArrayDeque(listOf(UUID.fromString(OTHER_REPORT_ID), UUID.fromString(REPORT_ID)))
        val store = AndroidReportQueueStore(
            capacityProfile = ApprovedReportQueueCapacityProfile(
                maxEntries = 3,
                maxPayloadBytes = 1_024,
                maxStoredEntryBytes = 1_024L,
                maxTotalBytes = 3_072L,
                automaticMaxEntries = 2,
                automaticMaxTotalBytes = 2_048L,
            ),
            storageFactory = { storage },
            aead = aead,
            idFactory = { ids.removeFirst() },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )
        requireNotNull(
            store.enqueue(
                expectedReporterActorId = ACTOR_ID,
                metadataUtf8 = metadata(ACTOR_ID),
                imageJpeg = jpeg(),
                priority = ReportQueuePriority.EXPLICIT,
                walkSessionId = OTHER_WALK_ID,
                consentReceiptSha256 = OLD_CONSENT,
            ),
        )
        requireNotNull(
            store.enqueue(
                expectedReporterActorId = OTHER_ACTOR_ID,
                metadataUtf8 = metadata(OTHER_ACTOR_ID),
                imageJpeg = jpeg(),
                priority = ReportQueuePriority.EXPLICIT,
                walkSessionId = WALK_ID,
                consentReceiptSha256 = CONSENT,
            ),
        )
        var selectedActor: String? = null

        val outcome = requireNotNull(
            ReportQueueDrainCoordinator(store).startNext(
                { context(reporterActorId = OTHER_ACTOR_ID) },
                object : ReportQueueTransport {
                    override fun statusCall(report: QueuedReport) =
                        CancellableNetworkCall.blocking<ReportQueueReceipt?> {
                            selectedActor = report.reporterActorId
                            receipt(report)
                        }

                    override fun uploadCall(report: QueuedReport) =
                        error("status receipt must avoid upload")
                },
            ),
        ).execute()

        assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS, outcome)
        assertEquals(OTHER_ACTOR_ID, selectedActor)
        assertEquals(OTHER_REPORT_ID, store.nextForRecoveryDrain(ACTOR_ID)?.payload?.reportId)
        assertNull(store.nextForRecoveryDrain(OTHER_ACTOR_ID))
    }

    @Test
    fun cancelWhileStatusCallIsBeingInstalledPreventsStatusExecution() {
        val fixture = fixture()
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val executed = AtomicInteger(0)
        val nestedCancelled = AtomicBoolean(false)
        val call = requireNotNull(
            fixture.coordinator.startNext(
                { context() },
                object : ReportQueueTransport {
                    override fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?> {
                        factoryEntered.countDown()
                        check(releaseFactory.await(2, TimeUnit.SECONDS))
                        return CancellableNetworkCall(
                            executeBlock = {
                                executed.incrementAndGet()
                                null
                            },
                            cancelBlock = { nestedCancelled.set(true) },
                        )
                    }

                    override fun uploadCall(report: QueuedReport) =
                        error("cancelled status must avoid upload")
                },
            ),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val outcome = executor.submit<ReportQueueDrainOutcome> { call.execute() }
            assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))
            fixture.coordinator.cancelActive()
            releaseFactory.countDown()

            assertEquals(ReportQueueDrainOutcome.CANCELLED, outcome.get(2, TimeUnit.SECONDS))
            assertEquals(0, executed.get())
            assertTrue(nestedCancelled.get())
        } finally {
            releaseFactory.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelWhileUploadCallIsBeingInstalledPreventsUploadExecution() {
        val fixture = fixture()
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val executed = AtomicInteger(0)
        val nestedCancelled = AtomicBoolean(false)
        val call = requireNotNull(
            fixture.coordinator.startNext(
                { context() },
                object : ReportQueueTransport {
                    override fun statusCall(report: QueuedReport) =
                        CancellableNetworkCall.blocking<ReportQueueReceipt?> { null }

                    override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> {
                        factoryEntered.countDown()
                        check(releaseFactory.await(2, TimeUnit.SECONDS))
                        return CancellableNetworkCall(
                            executeBlock = {
                                executed.incrementAndGet()
                                receipt(report)
                            },
                            cancelBlock = { nestedCancelled.set(true) },
                        )
                    }
                },
            ),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val outcome = executor.submit<ReportQueueDrainOutcome> { call.execute() }
            assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))
            fixture.coordinator.cancelActive()
            releaseFactory.countDown()

            assertEquals(ReportQueueDrainOutcome.CANCELLED, outcome.get(2, TimeUnit.SECONDS))
            assertEquals(0, executed.get())
            assertTrue(nestedCancelled.get())
        } finally {
            releaseFactory.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun missingStatusPostsOnceAndMovementChangeCancelsBeforeAnyCallback() {
        val fixture = fixture()
        var generation = 1L
        var callbacks = 0
        val call = requireNotNull(
            fixture.coordinator.startNext(
                { context(movementGeneration = generation) },
                transport(fixture.report) { callbacks += 1 },
            ),
        )

        generation += 1L
        assertFalse(fixture.coordinator.revalidate(context(movementGeneration = generation)))
        assertThrows(CancellationException::class.java) { call.execute() }
        assertEquals(0, callbacks)
        assertEquals(1, fixture.store.queuedReports().size)
    }

    private fun fixture(
        priority: ReportQueuePriority = ReportQueuePriority.EXPLICIT,
    ): Fixture {
        val storage = CoordinatorStorage()
        val aead = CoordinatorAead()
        val store = AndroidReportQueueStore(
            capacityProfile = ApprovedReportQueueCapacityProfile(
                maxEntries = 3,
                maxPayloadBytes = 1_024,
                maxStoredEntryBytes = 1_024L,
                maxTotalBytes = 3_072L,
                automaticMaxEntries = 2,
                automaticMaxTotalBytes = 2_048L,
            ),
            storageFactory = { storage },
            aead = aead,
            idFactory = { UUID.fromString(REPORT_ID) },
            nowMillis = { 1_000L },
            testOnly = Unit,
        )
        val report = requireNotNull(
            store.enqueue(
                ACTOR_ID,
                metadata(),
                jpeg(),
                priority,
                WALK_ID,
                CONSENT,
            ),
        )
        return Fixture(store, ReportQueueDrainCoordinator(store), report)
    }

    private fun transport(
        expected: QueuedReport,
        callback: () -> Unit,
    ) = object : ReportQueueTransport {
        override fun statusCall(
            report: QueuedReport,
        ): CancellableNetworkCall<ReportQueueReceipt?> = CancellableNetworkCall.blocking {
            callback()
            null
        }

        override fun uploadCall(report: QueuedReport) = CancellableNetworkCall.blocking {
            callback()
            receipt(expected)
        }
    }

    private fun AndroidReportQueueStore.enqueue(
        metadataUtf8: ByteArray,
        imageJpeg: ByteArray,
        priority: ReportQueuePriority,
        walkSessionId: String,
        consentReceiptSha256: String,
    ): QueuedReport? = enqueue(
        expectedReporterActorId = ACTOR_ID,
        metadataUtf8 = metadataUtf8,
        imageJpeg = imageJpeg,
        priority = priority,
        walkSessionId = walkSessionId,
        consentReceiptSha256 = consentReceiptSha256,
    )

    private fun context(
        state: WalkSessionState = WalkSessionState.PAUSED,
        appForeground: Boolean = true,
        stationary: Boolean = true,
        networkAllowed: Boolean = true,
        consentAllowed: Boolean = true,
        automaticReportingAllowed: Boolean = true,
        authorityAllowed: Boolean = true,
        reporterActorId: String = ACTOR_ID,
        movementGeneration: Long = 1L,
    ) = ReportQueueDrainContext(
        walkState = state,
        appForeground = appForeground,
        stationary = stationary,
        networkAllowed = networkAllowed,
        consentAllowed = consentAllowed,
        automaticReportingAllowed = automaticReportingAllowed,
        authorityAllowed = authorityAllowed,
        reporterActorId = reporterActorId,
        walkSessionId = WALK_ID,
        consentReceiptSha256 = CONSENT,
        movementGeneration = movementGeneration,
    )

    private fun receipt(report: QueuedReport) = ReportQueueReceipt(
        reportId = report.payload.reportId,
        payloadSha256 = report.payload.payloadSha256,
        payloadBytes = report.payload.payloadBytes,
        marker = REPORT_RECEIPT_MARKER,
        persistenceMarker = PERSISTENCE_MARKER,
    )

    private fun jpeg() =
        byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())

    private fun metadata(actorId: String = ACTOR_ID) =
        "{\"reporter_user_id\":\"$actorId\"}".toByteArray()

    private data class Fixture(
        val store: AndroidReportQueueStore,
        val coordinator: ReportQueueDrainCoordinator,
        val report: QueuedReport,
    )

    private companion object {
        const val REPORT_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val OTHER_REPORT_ID = "123e4567-e89b-42d3-a456-426614174003"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val OTHER_WALK_ID = "123e4567-e89b-42d3-a456-426614174004"
        const val PERSISTENCE_MARKER = "123e4567-e89b-42d3-a456-426614174002"
        const val ACTOR_ID = "walker-1"
        const val OTHER_ACTOR_ID = "walker-2"
        val CONSENT = "c".repeat(64)
        val OLD_CONSENT = "d".repeat(64)
    }
}

private class CoordinatorStorage : ReportQueueStorage {
    val values = mutableMapOf<String, String>()
    val fences = mutableSetOf<String>()
    private val pendingIntents = mutableSetOf<String>()
    private val lockedAccess = object : LockedReportQueueStorage {
        override fun listReportIds(): Set<String> = this@CoordinatorStorage.listReportIds()
        override fun read(reportId: String): String? = this@CoordinatorStorage.read(reportId)
        override fun delete(reportId: String): Boolean = this@CoordinatorStorage.delete(reportId)
        override fun hasFence(name: String): Boolean = this@CoordinatorStorage.hasFence(name)
        override fun measureUsage(): ReportQueueStorageUsage = this@CoordinatorStorage.measureUsage()
    }
    override fun listReportIds(): Set<String> = values.keys
    override fun read(reportId: String): String? = values[reportId]
    @Synchronized
    override fun <T> withLockedAccess(action: (LockedReportQueueStorage) -> T): T =
        action(lockedAccess)
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
    override fun delete(reportId: String): Boolean = values.remove(reportId).let { true }
    override fun hasFence(name: String): Boolean = name in fences
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
    @Synchronized
    override fun deleteMatchingWithFences(
        pendingFence: String,
        initialPermanentFences: Set<String>,
        permanentFenceForMatch: (reportId: String, envelope: String) -> String?,
    ): Boolean {
        fences += pendingFence
        fences += initialPermanentFences
        values.entries
            .filter { (reportId, envelope) ->
                permanentFenceForMatch(reportId, envelope)?.also(fences::add) != null
            }
            .map { it.key }
            .forEach(values::remove)
        fences -= pendingFence
        return true
    }
    override fun measureUsage(): ReportQueueStorageUsage = ReportQueueStorageUsage(
        reportFileCount = values.size,
        totalBytes = values.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() },
    )
}

private class CoordinatorAead : LocalAead {
    private val values = mutableMapOf<String, ByteArray>()
    private var next = 0
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult {
        if (plaintext.size > limits.maxPlaintextBytes) {
            return AeadSealResult.Blocked(AeadBlockReason.PLAINTEXT_TOO_LARGE)
        }
        val envelope = "coordinator-${next++}"
        values[envelope] = plaintext.copyOf()
        return AeadSealResult.Sealed(envelope)
    }
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
        values[envelope]?.copyOf()?.let { AeadOpenResult.Opened(it, 1, false) }
            ?: AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
    override fun destroyVersion(version: Int): Boolean = true
    override fun destroyKnownVersions(): Boolean = values.clear().let { true }
    override fun createFreshAfterVerifiedPurge(): Boolean = true
}
