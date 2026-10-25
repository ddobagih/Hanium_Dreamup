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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ReportQueueDrainCoordinatorTest {
    @Test fun responseLossKeepsExactPayloadAndReturnsTypedUnconfirmedWithPersistentBackoff() {
        val f = fixture()
        var observed: QueuedReport? = null
        val transport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?> {
                observed = report
                return CancellableNetworkCall.blocking { throw java.io.IOException("response lost") }
            }
            override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> = error("must query status first")
        }
        val result = requireNotNull(f.coordinator.startNextWithResult({ context() }, transport)).execute()
        assertEquals(ReportQueueDrainOutcome.RECEIPT_UNCONFIRMED, result.outcome)
        val retained = f.store.queuedReports().single()
        retained.use {
            assertEquals(f.report.payload.payloadSha256, it.payload.payloadSha256)
            assertTrue(it.retryNotBeforeEpochMs > it.createdAtEpochMs)
            assertEquals(1, it.retryAttempts)
        }
        assertNull(f.store.nextForRecoveryDrain(ACTOR_ID))
        assertThrows(IllegalStateException::class.java) { requireNotNull(observed).payload.imageJpeg() }
    }

    @Test fun previousCoordinatorMustFinishPlaintextFinallyBeforeDeletionEvidence() {
        val f = fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        var observed: QueuedReport? = null
        val transport = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?> {
                observed = report
                return CancellableNetworkCall.blocking { entered.countDown(); finish.await(); null }
            }
            override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> = error("cancelled")
        }
        val call = requireNotNull(f.coordinator.startNextWithResult({ context() }, transport))
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val task = worker.submit<ReportQueueDrainResult> { call.execute() }
        val replacement = ReportQueueDrainCoordinator(f.store)
        try {
            assertTrue(entered.await(2L, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(replacement.cancelAndAwaitDrainTermination(1L))
            assertEquals(1, f.store.queuedReports().size)
            assertNotNull(requireNotNull(observed).payload.imageJpeg())
            finish.countDown()
            assertEquals(ReportQueueDrainOutcome.CANCELLED, task.get(2L, java.util.concurrent.TimeUnit.SECONDS).outcome)
            assertThrows(IllegalStateException::class.java) { requireNotNull(observed).payload.imageJpeg() }
            assertTrue(replacement.onAccountDeleted())
            assertTrue(f.store.queuedReports().isEmpty())
        } finally { finish.countDown(); worker.shutdownNow(); replacement.onAccountDeleted() }
    }

    @Test
    fun createdAndReplayedReturnDistinctMetadataOnlyAfterReceiptCommitAndDelete() {
        listOf(ReportCreationOutcome.CREATED, ReportCreationOutcome.REPLAYED, null).forEach { creation ->
            val fixture = fixture(ReportQueuePriority.AUTOMATIC)
            var commits = 0
            val result = requireNotNull(fixture.coordinator.startNextWithResult(
                { context() }, outcomeTransport(creation),
                beforeDeleteAfterReceipt = { commits++; true },
            )).execute()
            assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD, result.outcome)
            assertEquals(creation, result.creationOutcome)
            assertEquals(ReportQueuePriority.AUTOMATIC, result.reportPriority)
            assertEquals(1, commits)
            assertTrue(fixture.store.queuedReports().isEmpty())
        }
    }

    @Test
    fun statusRecoveryResultCarriesTheSelectedReportsActualPriority() {
        ReportQueuePriority.entries.forEach { priority ->
            val fixture = fixture(priority)
            val transport = object : ReportQueueTransport {
                override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> { receipt(report) }
                override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> = error("status receipt must avoid post")
            }
            val result = requireNotNull(fixture.coordinator.startNextWithResult(
                { context() }, transport, beforeDeleteAfterReceipt = { true },
            )).execute()
            assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS, result.outcome)
            assertEquals(priority, result.reportPriority)
            assertNull(result.creationOutcome)
        }
    }

    @Test
    fun failedCommitAndSuppressedSuccessCannotExposeCreatedOrDelete() {
        listOf(ReportCreationOutcome.CREATED, ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN).forEach { creation ->
            val fixture = fixture(ReportQueuePriority.AUTOMATIC)
            val result = requireNotNull(fixture.coordinator.startNextWithResult(
                { context() }, outcomeTransport(creation), beforeDeleteAfterReceipt = { false },
            )).execute()
            assertEquals(
                if (creation == ReportCreationOutcome.CREATED) ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED
                else ReportQueueDrainOutcome.RECEIPT_REJECTED,
                result.outcome,
            )
            assertNull(result.creationOutcome)
            assertEquals(1, fixture.store.queuedReports().size)
        }
    }

    @Test
    fun authorityChangeAfterCreatedReceiptHidesOutcomeAndKeepsPayload() {
        val fixture = fixture()
        var current = context()
        val result = requireNotNull(fixture.coordinator.startNextWithResult(
            { current }, outcomeTransport(ReportCreationOutcome.CREATED) {
                current = context(consentAllowed = false)
            },
        )).execute()
        assertEquals(ReportQueueDrainOutcome.CANCELLED, result.outcome)
        assertNull(result.creationOutcome)
        assertEquals(1, fixture.store.queuedReports().size)
    }

    @Test
    fun aWalkInProgressRefusalKeepsTheReportAndAsksForOneDelayedRetry() {
        // transfer-F7: 409 report_walk_in_progress (the Gateway still holds this device's walk).
        listOf(
            """{"detail":{"code":"report_walk_in_progress","message":"later"}}""" to true,
            """{"detail":{"code":"walk_owned_by_other_device","message":"other"}}""" to false,
        ).forEach { (body, walkInProgress) ->
            val fixture = fixture(ReportQueuePriority.AUTOMATIC)
            val refusing = object : ReportQueueTransport {
                override fun statusCall(report: QueuedReport) =
                    CancellableNetworkCall.blocking<ReportQueueReceipt?> { null }
                override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> =
                    CancellableNetworkCall.blocking {
                        throw ReportUploadHttpException(ReportUploadError(409, body))
                    }
            }
            val result = requireNotNull(fixture.coordinator.startNextWithResult({ context() }, refusing)).execute()
            assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED, result.outcome)
            assertEquals(walkInProgress, result.walkInProgress)
            assertEquals(1, fixture.store.queuedReports().size)
        }
    }

    private fun outcomeTransport(
        creationOutcome: ReportCreationOutcome?,
        beforeReturn: () -> Unit = {},
    ) = object : ReportQueueTransport {
        override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> { null }
        override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> =
            error("coordinator must consume the typed upload result")
        override fun uploadResultCall(report: QueuedReport) = CancellableNetworkCall.blocking {
            beforeReturn()
            ReportQueueUploadResult(receipt(report), creationOutcome)
        }
    }

    @Test
    fun activeOrPausedWalkCreatesZeroTransportCallbacks() {
        // Owner decision 3(b): PAUSED is still the walk; queued reports wait for its end.
        val fixture = fixture()
        var callbacks = 0
        for (state in listOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) {
            assertNull(
                fixture.coordinator.startNext(
                    contextProvider = { context(state) },
                    transport = transport(fixture.report) { callbacks += 1 },
                ),
            )
        }
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
            ReportQueuePriority.entries.forEach { priority ->
                val fixture = fixture(priority)
                var callbacks = 0
                assertNull(
                    fixture.coordinator.startNext(
                        contextProvider = { blocked.copy(automaticReportingAllowed = false) },
                        transport = transport(fixture.report) { callbacks += 1 },
                    ),
                )
                assertEquals(0, callbacks)
            }
        }
        // Automatic OFF is tested separately: it admits only the status query under these gates.
    }

    @Test
    fun automaticOptOutAllowsExactStatusReceiptDeletionWithoutPost() {
        val fixture = fixture(ReportQueuePriority.AUTOMATIC)
        var statusCalls = 0
        var postFactories = 0
        val recovering = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> {
                statusCalls++
                receipt(report)
            }
            override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> {
                postFactories++
                return CancellableNetworkCall.blocking { receipt(report) }
            }
        }
        val call = fixture.coordinator.startNext(
            { context(automaticReportingAllowed = false) }, recovering, beforeDeleteAfterReceipt = { true },
        )
        assertTrue("fresh authority must admit status-only recovery", call != null)
        assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS, requireNotNull(call).execute())
        assertEquals(1, statusCalls)
        assertEquals(0, postFactories)
        assertTrue(fixture.store.queuedReports().isEmpty())
    }

    @Test
    fun automaticOptOutMissingStatusKeepsExactQueuedPayloadWithoutConstructingPost() {
        val fixture = fixture(ReportQueuePriority.AUTOMATIC)
        var statusCalls = 0
        var postFactories = 0
        val absent = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> {
                statusCalls++
                null
            }
            override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> {
                postFactories++
                return CancellableNetworkCall.blocking { receipt(report) }
            }
        }
        val call = fixture.coordinator.startNext(
            { context(automaticReportingAllowed = false) }, absent,
        )
        assertTrue("fresh authority must admit status-only recovery", call != null)
        assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED, requireNotNull(call).execute())
        assertEquals(1, statusCalls)
        assertEquals(0, postFactories)
        val preserved = fixture.store.queuedReports().single()
        assertEquals(fixture.report.payload.reportId, preserved.payload.reportId)
        assertTrue(fixture.report.payload.contentEquals(preserved.payload))
    }

    @Test
    fun automaticSelectionChangeDuringStatusCancelsInsteadOfCreatingAPostOrDeleting() {
        listOf(false, true).forEach { initial ->
            listOf(false, true).forEach { persisted ->
                val fixture = fixture(ReportQueuePriority.AUTOMATIC)
                var current = context(automaticReportingAllowed = initial)
                var posts = 0
                val changing = object : ReportQueueTransport {
                    override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> {
                        current = context(automaticReportingAllowed = !initial)
                        if (persisted) receipt(report) else null
                    }
                    override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> {
                        posts++
                        return CancellableNetworkCall.blocking { receipt(report) }
                    }
                }
                val result = requireNotNull(fixture.coordinator.startNext(
                    { current }, changing, beforeDeleteAfterReceipt = { true },
                )).execute()
                assertEquals(ReportQueueDrainOutcome.CANCELLED, result)
                assertEquals(0, posts)
                assertTrue(fixture.report.payload.contentEquals(fixture.store.queuedReports().single().payload))
            }
        }
    }

    @Test
    fun automaticWithdrawalWhileConstructingPostCancelsBeforeExecution() {
        val fixture = fixture(ReportQueuePriority.AUTOMATIC)
        var current = context()
        var executions = 0
        var cancellations = 0
        val withdrawing = object : ReportQueueTransport {
            override fun statusCall(report: QueuedReport) = CancellableNetworkCall.blocking<ReportQueueReceipt?> { null }
            override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> {
                current = context(automaticReportingAllowed = false)
                return CancellableNetworkCall(
                    executeBlock = { executions++; receipt(report) },
                    cancelBlock = { cancellations++ },
                )
            }
        }
        val result = requireNotNull(fixture.coordinator.startNext({ current }, withdrawing)).execute()
        assertEquals(ReportQueueDrainOutcome.CANCELLED, result)
        assertEquals(0, executions)
        assertEquals(1, cancellations)
        assertTrue(fixture.report.payload.contentEquals(fixture.store.queuedReports().single().payload))
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

        assertEquals(ReportQueueDrainOutcome.RECEIPT_UNCONFIRMED, outcome)
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

        assertEquals(ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED, outcome)
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
                metadata(priority = ReportQueuePriority.AUTOMATIC),
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
                metadata(priority = priority),
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
        state: WalkSessionState = WalkSessionState.ENDED,
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

    private fun jpeg() = reportTestJpeg()

    private fun metadata(
        actorId: String = ACTOR_ID,
        priority: ReportQueuePriority = ReportQueuePriority.EXPLICIT,
    ): ByteArray {
        val automatic = priority == ReportQueuePriority.AUTOMATIC
        val trigger = if (automatic) "auto" else "voice"
        return ("{\"reporter_user_id\":\"$actorId\",\"trigger\":\"$trigger\"," +
            "\"auto_reported\":$automatic}").toByteArray()
    }

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
        override fun replace(reportId: String, envelope: String, storedEntryByteLimit: Long, storedByteLimit: Long): Boolean {
            val previous = values[reportId] ?: return false
            val size = envelope.toByteArray().size.toLong()
            if (size > storedEntryByteLimit || measureUsage().totalBytes - previous.toByteArray().size + size > storedByteLimit) return false
            values[reportId] = envelope
            return true
        }
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
