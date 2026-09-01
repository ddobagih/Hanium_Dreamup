package kr.co.hanium.dreamup.walksafe.report

import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.network.BackendAccountDeviceCookieBinding
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.UserReportHttpException
import kr.co.hanium.dreamup.walksafe.network.UserReportNetworkClient
import kr.co.hanium.dreamup.walksafe.network.UserReportProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserReportControllerTest {
    @Test
    fun pagesRemainBoundToStableFilterCursorAndUniqueReportIds() {
        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(reportId = REPORT_ID, nextCursor = "cursor_1"))
            listResults += Result.success(page(reportId = REPORT_ID, nextCursor = null))
        }
        var authority: UserReportAuthority? = authority(session())
        val controller = controller(client, worker) { authority }
        controller.onAuthorityChanged()

        assertTrue(controller.loadReports(UserReportStatus.RECEIVED))
        worker.runNext()
        assertEquals("cursor_1", controller.snapshot().nextCursor)
        assertTrue(controller.loadNextPage())
        worker.runNext()

        assertEquals(UserReportUiPhase.ERROR, controller.snapshot().phase)
        assertEquals(UserReportFailure.MALFORMED_RESPONSE, controller.snapshot().failure)
        assertEquals(
            listOf(null to UserReportStatus.RECEIVED, "cursor_1" to UserReportStatus.RECEIVED),
            client.listBindings,
        )
        assertEquals(listOf(REPORT_ID), controller.snapshot().reports.map { it.reportId })
        assertTrue(controller.snapshot().retryAvailable)
    }

    @Test
    fun firstTapUuidSurvivesTemporaryNetworkRetryAndDoubleTapCreatesOneCall() {
        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
            requestResults += Result.failure(IOException("offline"))
            requestResults += Result.success(requestSummary())
        }
        val stableAuthority = authority(session())
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { stableAuthority },
            observer = {},
            requestIdFactory = { CLIENT_REQUEST_ID },
        )
        controller.onAuthorityChanged()
        assertTrue(controller.loadReports())
        worker.runNext()
        assertTrue(controller.openDetail(REPORT_ID))
        worker.runNext()

        assertTrue(
            controller.submitRequest(
                REPORT_ID,
                UserReportRequestType.CORRECTION,
                REQUEST_TEXT,
            ),
        )
        assertFalse(
            controller.submitRequest(
                REPORT_ID,
                UserReportRequestType.CORRECTION,
                REQUEST_TEXT,
            ),
        )
        assertEquals(1, client.requestIntents.size)
        worker.runNext()
        assertEquals(UserReportUiPhase.ERROR, controller.snapshot().phase)
        assertEquals(UserReportFailure.TEMPORARY, controller.snapshot().failure)
        assertTrue(controller.snapshot().retryAvailable)
        assertFalse(
            controller.submitRequest(
                REPORT_ID,
                UserReportRequestType.CORRECTION,
                "다른 본문은 같은 UUID로 보내면 안 됩니다.",
            ),
        )

        assertTrue(controller.retry())
        worker.runNext()

        assertEquals(2, client.requestIntents.size)
        assertEquals(CLIENT_REQUEST_ID, client.requestIntents[0].clientRequestId)
        assertEquals(client.requestIntents[0], client.requestIntents[1])
        assertEquals(UserReportUiPhase.READY, controller.snapshot().phase)
        assertEquals(UserReportRequestStatus.RECEIVED, controller.snapshot().latestCreatedRequest?.status)
    }

    @Test
    fun protocolAndServer5xxPreserveTheExactUuidAndIntentForRetry() {
        listOf(
            UserReportProtocolException() to UserReportFailure.MALFORMED_RESPONSE,
            UserReportHttpException(503) to UserReportFailure.TEMPORARY,
        ).forEach { (failure, expectedFailure) ->
            val worker = QueuedExecutor()
            val client = FakeClient().apply {
                listResults += Result.success(page(REPORT_ID, null))
                detailResults += Result.success(detail(REPORT_ID))
                requestResults += Result.failure(failure)
                requestResults += Result.success(requestSummary())
            }
            val stableAuthority = authority(session())
            val controller = UserReportController(
                client = client,
                workerExecutor = worker,
                callbackExecutor = DIRECT_EXECUTOR,
                authorityProvider = { stableAuthority },
                observer = {},
                requestIdFactory = { CLIENT_REQUEST_ID },
            )
            controller.onAuthorityChanged()
            selectReport(controller, worker)

            assertTrue(
                controller.submitRequest(
                    REPORT_ID,
                    UserReportRequestType.CORRECTION,
                    REQUEST_TEXT,
                ),
            )
            worker.runNext()

            assertEquals(expectedFailure, controller.snapshot().failure)
            assertTrue(controller.snapshot().retryAvailable)
            assertFalse(
                controller.submitRequest(
                    REPORT_ID,
                    UserReportRequestType.CORRECTION,
                    SECOND_REQUEST_TEXT,
                ),
            )
            assertTrue(controller.retry())
            worker.runNext()
            assertEquals(2, client.requestIntents.size)
            assertEquals(client.requestIntents[0], client.requestIntents[1])
            assertEquals(CLIENT_REQUEST_ID, client.requestIntents[1].clientRequestId)
        }
    }

    @Test
    fun terminalRequest4xxReleasesIntentAndRetryForANewReportAndBody() {
        listOf(404, 409, 413, 415, 422).forEach { statusCode ->
            val worker = QueuedExecutor()
            val client = FakeClient().apply {
                listResults += Result.success(
                    pageWithReports(listOf(REPORT_ID, SECOND_REPORT_ID), null),
                )
                detailResults += Result.success(detail(REPORT_ID))
                detailResults += Result.success(detail(SECOND_REPORT_ID))
                requestResults += Result.failure(UserReportHttpException(statusCode))
                requestResults += Result.success(requestSummary())
            }
            val requestIds = ArrayDeque(listOf(CLIENT_REQUEST_ID, SECOND_CLIENT_REQUEST_ID))
            val stableAuthority = authority(session())
            val controller = UserReportController(
                client = client,
                workerExecutor = worker,
                callbackExecutor = DIRECT_EXECUTOR,
                authorityProvider = { stableAuthority },
                observer = {},
                requestIdFactory = { requestIds.removeFirst() },
            )
            controller.onAuthorityChanged()
            selectReport(controller, worker)

            assertTrue(
                controller.submitRequest(
                    REPORT_ID,
                    UserReportRequestType.CORRECTION,
                    REQUEST_TEXT,
                ),
            )
            worker.runNext()

            assertFalse(controller.snapshot().retryAvailable)
            assertFalse(controller.retry())
            assertTrue(controller.openDetail(SECOND_REPORT_ID))
            worker.runNext()
            assertTrue(
                controller.submitRequest(
                    SECOND_REPORT_ID,
                    UserReportRequestType.CORRECTION,
                    SECOND_REQUEST_TEXT,
                ),
            )
            worker.runNext()
            assertEquals(2, client.requestIntents.size)
            assertEquals(CLIENT_REQUEST_ID, client.requestIntents[0].clientRequestId)
            assertEquals(SECOND_CLIENT_REQUEST_ID, client.requestIntents[1].clientRequestId)
            assertEquals(SECOND_REPORT_ID, client.requestIntents[1].reportId)
            assertEquals(SECOND_REQUEST_TEXT, client.requestIntents[1].requestText)
        }
    }

    @Test
    fun refreshingAnUnchangedListDoesNotClearReportsDuringLoading() {
        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            listResults += Result.success(page(REPORT_ID, null))
        }
        val stableAuthority = authority(session())
        val controller = controller(client, worker) { stableAuthority }
        controller.onAuthorityChanged()
        assertTrue(controller.loadReports())
        worker.runNext()

        assertTrue(controller.loadReports())
        assertEquals(UserReportUiPhase.LOADING_LIST, controller.snapshot().phase)
        assertEquals(listOf(REPORT_ID), controller.snapshot().reports.map { it.reportId })
        worker.runNext()
        assertEquals(listOf(REPORT_ID), controller.snapshot().reports.map { it.reportId })
    }

    @Test
    fun logoutSessionGenerationLocalIdentityEpochAndDestroyDropStaleCompletions() {
        listOf<(UserReportAuthority) -> UserReportAuthority?>(
            { null },
            { old ->
                authority(
                    session(actorId = old.session.actorId),
                    old.sessionGeneration,
                    old.localIdentityEpoch,
                )
            },
            { old -> authority(old.session, old.sessionGeneration + 1L, old.localIdentityEpoch) },
            { old -> authority(old.session, old.sessionGeneration, old.localIdentityEpoch + 1L) },
        ).forEach { mutateAuthority ->
            val worker = QueuedExecutor()
            val client = FakeClient().apply {
                listResults += Result.success(page(REPORT_ID, null))
            }
            var current: UserReportAuthority? = authority(session())
            val controller = controller(client, worker) { current }
            controller.onAuthorityChanged()
            assertTrue(controller.loadReports())

            current = mutateAuthority(requireNotNull(current))
            controller.onAuthorityChanged()
            worker.runNext()

            assertTrue(controller.snapshot().reports.isEmpty())
            assertEquals(
                if (current == null) UserReportUiPhase.SIGNED_OUT else UserReportUiPhase.IDLE,
                controller.snapshot().phase,
            )
        }

        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
        }
        val stableAuthority = authority(session())
        val controller = controller(client, worker) { stableAuthority }
        controller.onAuthorityChanged()
        assertTrue(controller.loadReports())
        controller.onDestroy()
        worker.runNext()
        assertTrue(controller.snapshot().reports.isEmpty())
        assertNull(controller.snapshot().latestCreatedRequest)
    }

    @Test
    fun structuredCorrectionRetryKeepsExactUuidCasAndPatch() {
        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
            contentResults += Result.success(content(revision = 3))
            correctionResults += Result.failure(IOException("offline"))
            correctionResults += Result.success(contentRevision(expectedRevision = 3))
        }
        val stableAuthority = authority(session())
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { stableAuthority },
            observer = {},
            correctionIdFactory = { CORRECTION_ID },
        )
        controller.onAuthorityChanged()
        selectReport(controller, worker)
        assertTrue(controller.loadContent(REPORT_ID))
        worker.runNext()
        val description = UserReportCorrectionPatch.Value("보행로 파손 범위")

        assertTrue(
            controller.submitCorrection(
                reportId = REPORT_ID,
                userDescription = description,
                categoryHint = UserReportCorrectionPatch.Omitted,
            ),
        )
        assertFalse(
            controller.submitCorrection(
                reportId = REPORT_ID,
                userDescription = description,
                categoryHint = UserReportCorrectionPatch.Omitted,
            ),
        )
        worker.runNext()

        assertEquals(UserReportFailure.TEMPORARY, controller.snapshot().failure)
        assertTrue(controller.snapshot().retryAvailable)
        assertTrue(controller.retry())
        worker.runNext()

        assertEquals(2, client.correctionIntents.size)
        assertEquals(client.correctionIntents[0], client.correctionIntents[1])
        assertEquals(3L, client.correctionIntents[1].expectedRevision)
        assertEquals(CORRECTION_ID, client.correctionIntents[1].idempotencyKey)
        assertEquals(4L, controller.snapshot().selectedContent?.revision)
        assertEquals(CORRECTION_ID, controller.snapshot().latestCorrection?.idempotencyKey)
    }

    @Test
    fun deleteTracksRequestByBackendGenerationAndStatusRemainsReachableAfterReport404() {
        val worker = QueuedExecutor()
        val tracker = FakeDeletionTracker()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
            requestResults += Result.success(
                requestSummary(requestType = UserReportRequestType.DELETE),
            )
            detailResults += Result.failure(UserReportHttpException(404))
            deletionStatusResults += Result.success(deletionStatus())
        }
        val stableAuthority = authority(backendSession())
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { stableAuthority },
            observer = {},
            deletionTracker = tracker,
            requestIdFactory = { CLIENT_REQUEST_ID },
        )
        controller.onAuthorityChanged()
        selectReport(controller, worker)

        assertTrue(
            controller.submitRequest(
                reportId = REPORT_ID,
                requestType = UserReportRequestType.DELETE,
                requestText = REQUEST_TEXT,
            ),
        )
        worker.runNext()
        assertEquals(listOf(REQUEST_ID), controller.snapshot().trackedDeletionRequestIds)
        assertEquals(
            listOf(UserReportRequestReference(REPORT_ID, REQUEST_ID, UserReportRequestType.DELETE)),
            controller.snapshot().trackedRequestReferences,
        )
        assertEquals(listOf(REQUEST_ID), tracker.trackedRequestIds(stableAuthority.session))

        assertTrue(controller.openDetail(REPORT_ID))
        worker.runNext()
        assertEquals(UserReportFailure.NOT_FOUND_OR_SIGNED_OUT, controller.snapshot().failure)
        assertTrue(controller.refreshDeletionStatus(REQUEST_ID))
        worker.runNext()

        assertEquals(UserReportDeletionState.DELETED, controller.snapshot().selectedDeletionStatus?.state)
        assertEquals(listOf(REQUEST_ID), client.deletionStatusRequestIds)
    }

    @Test
    fun exactRequestStatusUsesRestoredReportBindingAndDisplaysCurrentPublicResult() {
        val worker = QueuedExecutor()
        val tracker = FakeDeletionTracker()
        val stableAuthority = authority(backendSession())
        val reference = UserReportRequestReference(
            reportId = REPORT_ID,
            requestId = REQUEST_ID,
            requestType = UserReportRequestType.DELETE,
        )
        assertTrue(tracker.trackRequest(stableAuthority.session, reference))
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
            requestStatusResults += Result.success(
                requestSummary(
                    requestType = UserReportRequestType.DELETE,
                    status = UserReportRequestStatus.ACKNOWLEDGED,
                    publicResponse = "삭제 요청을 확인했습니다.",
                ),
            )
        }
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { stableAuthority },
            observer = {},
            deletionTracker = tracker,
        )

        controller.onAuthorityChanged()
        assertEquals(listOf(reference), controller.snapshot().trackedRequestReferences)
        selectReport(controller, worker)
        assertTrue(controller.refreshRequestStatus(REPORT_ID, REQUEST_ID))
        worker.runNext()

        assertEquals(UserReportUiPhase.READY, controller.snapshot().phase)
        assertEquals(
            UserReportRequestStatus.ACKNOWLEDGED,
            controller.snapshot().selectedRequestStatus?.status,
        )
        assertEquals(
            "삭제 요청을 확인했습니다.",
            controller.snapshot().selectedRequestStatus?.publicResponse,
        )
        assertEquals(listOf(REPORT_ID to REQUEST_ID), client.requestStatusBindings)
    }

    @Test
    fun staleAuthorityTrackerReadCannotOverwriteTheNewerAuthoritySnapshot() {
        val authorityA = authority(backendSession(BACKEND_ACTOR_ID))
        val authorityB = authority(backendSession(SECOND_BACKEND_ACTOR_ID))
        val referenceA = UserReportRequestReference(
            REPORT_ID,
            REQUEST_ID,
            UserReportRequestType.DELETE,
        )
        val referenceB = UserReportRequestReference(
            SECOND_REPORT_ID,
            SECOND_REQUEST_ID,
            UserReportRequestType.DELETE,
        )
        val delegate = FakeDeletionTracker().apply {
            assertTrue(trackRequest(authorityA.session, referenceA))
            assertTrue(trackRequest(authorityB.session, referenceB))
        }
        val tracker = BlockingReadDeletionTracker(BACKEND_ACTOR_ID, delegate)
        val current = AtomicReference<UserReportAuthority?>(authorityA)
        val controller = UserReportController(
            client = FakeClient(),
            workerExecutor = QueuedExecutor(),
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { current.get() },
            observer = {},
            deletionTracker = tracker,
        )
        val staleFailure = AtomicReference<Throwable?>()
        val staleRead = thread(isDaemon = true) {
            runCatching { controller.onAuthorityChanged() }
                .exceptionOrNull()
                ?.let(staleFailure::set)
        }

        try {
            assertTrue(tracker.readStarted.await(5, TimeUnit.SECONDS))
            current.set(authorityB)
            controller.onAuthorityChanged()
        } finally {
            tracker.releaseRead.countDown()
            staleRead.join(5_000)
        }

        assertFalse(staleRead.isAlive)
        assertNull(staleFailure.get())
        assertEquals(UserReportUiPhase.IDLE, controller.snapshot().phase)
        assertEquals(listOf(referenceB), controller.snapshot().trackedRequestReferences)
        assertEquals(listOf(SECOND_REQUEST_ID), controller.snapshot().trackedDeletionRequestIds)
    }

    @Test
    fun requestStatusDoesNotCreateACallAcrossAuthorityChangeAndRestoresNewTrackerState() {
        val worker = QueuedExecutor()
        val authorityA = authority(backendSession(BACKEND_ACTOR_ID))
        val authorityB = authority(backendSession(SECOND_BACKEND_ACTOR_ID))
        val referenceA = UserReportRequestReference(
            REPORT_ID,
            REQUEST_ID,
            UserReportRequestType.DELETE,
        )
        val referenceB = UserReportRequestReference(
            SECOND_REPORT_ID,
            SECOND_REQUEST_ID,
            UserReportRequestType.CORRECTION,
        )
        val tracker = FakeDeletionTracker().apply {
            assertTrue(trackRequest(authorityA.session, referenceA))
            assertTrue(trackRequest(authorityB.session, referenceB))
        }
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
        }
        val current = AtomicReference<UserReportAuthority?>(authorityA)
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { current.get() },
            observer = {},
            deletionTracker = tracker,
        )
        controller.onAuthorityChanged()
        selectReport(controller, worker)

        current.set(authorityB)
        assertFalse(controller.refreshRequestStatus(REPORT_ID, REQUEST_ID))
        assertTrue(client.requestStatusBindings.isEmpty())
        assertEquals(UserReportUiPhase.IDLE, controller.snapshot().phase)
        assertTrue(controller.snapshot().reports.isEmpty())
        assertNull(controller.snapshot().selectedDetail)
        assertTrue(controller.snapshot().trackedRequestReferences.isEmpty())

        controller.onAuthorityChanged()
        assertEquals(listOf(referenceB), controller.snapshot().trackedRequestReferences)
        assertTrue(controller.snapshot().trackedDeletionRequestIds.isEmpty())
    }

    @Test
    fun requestStatusPreconditionRejectsAStaleSnapshotAfterAuthorityStateReplacement() {
        val worker = QueuedExecutor()
        val authorityA = authority(backendSession(BACKEND_ACTOR_ID))
        val authorityB = authority(backendSession(SECOND_BACKEND_ACTOR_ID))
        val referenceA = UserReportRequestReference(
            REPORT_ID,
            REQUEST_ID,
            UserReportRequestType.DELETE,
        )
        val referenceB = UserReportRequestReference(
            SECOND_REPORT_ID,
            SECOND_REQUEST_ID,
            UserReportRequestType.CORRECTION,
        )
        val delegate = FakeDeletionTracker().apply {
            assertTrue(trackRequest(authorityA.session, referenceA))
            assertTrue(trackRequest(authorityB.session, referenceB))
        }
        val tracker = BlockingIterationDeletionTracker(
            blockedActorId = BACKEND_ACTOR_ID,
            blockedReference = referenceA,
            delegate = delegate,
        )
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
            requestStatusResults += Result.success(
                requestSummary(requestType = UserReportRequestType.DELETE),
            )
        }
        val current = AtomicReference<UserReportAuthority?>(authorityA)
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { current.get() },
            observer = {},
            deletionTracker = tracker,
        )
        controller.onAuthorityChanged()
        selectReport(controller, worker)
        val refreshResult = AtomicReference<Boolean?>()
        val refreshFailure = AtomicReference<Throwable?>()
        val refresh = thread(isDaemon = true) {
            runCatching { controller.refreshRequestStatus(REPORT_ID, REQUEST_ID) }
                .onSuccess(refreshResult::set)
                .exceptionOrNull()
                ?.let(refreshFailure::set)
        }

        try {
            assertTrue(tracker.iterationStarted.await(5, TimeUnit.SECONDS))
            current.set(authorityB)
            controller.onAuthorityChanged()
        } finally {
            tracker.releaseIteration.countDown()
            refresh.join(5_000)
        }

        assertFalse(refresh.isAlive)
        assertNull(refreshFailure.get())
        assertEquals(false, refreshResult.get())
        assertTrue(client.requestStatusBindings.isEmpty())
        assertEquals(listOf(referenceB), controller.snapshot().trackedRequestReferences)
    }

    @Test
    fun unboundStartDoesNotCreateAClientCallAndCanRecoverAfterAuthorityBinding() {
        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
        }
        val stableAuthority = authority(session())
        val controller = controller(client, worker) { stableAuthority }

        assertFalse(controller.loadReports())
        assertTrue(client.listBindings.isEmpty())
        assertEquals(UserReportUiPhase.IDLE, controller.snapshot().phase)
        assertTrue(controller.snapshot().reports.isEmpty())

        controller.onAuthorityChanged()
        assertTrue(controller.loadReports())
        worker.runNext()
        assertEquals(UserReportUiPhase.READY, controller.snapshot().phase)
        assertEquals(1, client.listBindings.size)
    }

    @Test
    fun successfulPostWithLocalTrackingFailureIsNotRetriedAndKeepsCreatedResult() {
        val worker = QueuedExecutor()
        val tracker = FakeDeletionTracker(acceptTracks = false)
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
            requestResults += Result.success(
                requestSummary(
                    requestId = REQUEST_ID,
                    requestType = UserReportRequestType.DELETE,
                ),
            )
            requestResults += Result.success(
                requestSummary(
                    requestId = SECOND_REQUEST_ID,
                    requestType = UserReportRequestType.DELETE,
                ),
            )
        }
        val requestIds = ArrayDeque(listOf(CLIENT_REQUEST_ID, SECOND_CLIENT_REQUEST_ID))
        val stableAuthority = authority(backendSession())
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { stableAuthority },
            observer = {},
            deletionTracker = tracker,
            requestIdFactory = { requestIds.removeFirst() },
        )
        controller.onAuthorityChanged()
        selectReport(controller, worker)

        assertTrue(
            controller.submitRequest(
                REPORT_ID,
                UserReportRequestType.DELETE,
                REQUEST_TEXT,
            ),
        )
        worker.runNext()

        assertEquals(UserReportUiPhase.ERROR, controller.snapshot().phase)
        assertEquals(UserReportFailure.LOCAL_TRACKING, controller.snapshot().failure)
        assertFalse(controller.snapshot().retryAvailable)
        assertFalse(controller.retry())
        assertEquals(REQUEST_ID, controller.snapshot().latestCreatedRequest?.requestId)
        assertEquals(REQUEST_ID, controller.snapshot().selectedDetail?.latestRequest?.requestId)
        assertTrue(controller.snapshot().trackedRequestReferences.isEmpty())
        assertTrue(controller.snapshot().trackedDeletionRequestIds.isEmpty())

        assertTrue(
            controller.submitRequest(
                REPORT_ID,
                UserReportRequestType.DELETE,
                SECOND_REQUEST_TEXT,
            ),
        )
        worker.runNext()
        assertEquals(2, client.requestIntents.size)
        assertEquals(
            listOf(CLIENT_REQUEST_ID, SECOND_CLIENT_REQUEST_ID),
            client.requestIntents.map(UserReportRequestIntent::clientRequestId),
        )
        assertEquals(SECOND_REQUEST_ID, controller.snapshot().latestCreatedRequest?.requestId)
    }

    @Test
    fun rejectedCompletionDispatchReleasesActiveOperationForRetry() {
        val worker = QueuedExecutor()
        val callbacks = SwitchableCallbackExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            listResults += Result.success(page(REPORT_ID, null))
            listCancelFailure = IllegalStateException("cancel failed")
        }
        val stableAuthority = authority(session())
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = callbacks,
            authorityProvider = { stableAuthority },
            observer = {},
        )
        controller.onAuthorityChanged()
        assertTrue(controller.loadReports())

        callbacks.reject = true
        worker.runNext()
        assertEquals(UserReportUiPhase.ERROR, controller.snapshot().phase)
        assertEquals(UserReportFailure.TEMPORARY, controller.snapshot().failure)
        assertTrue(controller.snapshot().retryAvailable)

        callbacks.reject = false
        assertTrue(controller.retry())
        worker.runNext()
        assertEquals(UserReportUiPhase.READY, controller.snapshot().phase)
        assertEquals(2, client.listBindings.size)
    }

    @Test
    fun deleteIsRejectedWithoutV7BackendSession() {
        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
        }
        val legacyAuthority = authority(session())
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { legacyAuthority },
            observer = {},
            deletionTracker = FakeDeletionTracker(),
        )
        controller.onAuthorityChanged()
        selectReport(controller, worker)

        assertFalse(
            controller.submitRequest(
                reportId = REPORT_ID,
                requestType = UserReportRequestType.DELETE,
                requestText = REQUEST_TEXT,
            ),
        )
        assertTrue(client.requestIntents.isEmpty())
    }

    @Test
    fun deleteIsRejectedWithoutTrackerEvenWithV7BackendSession() {
        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
        }
        val stableAuthority = authority(backendSession())
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { stableAuthority },
            observer = {},
        )
        controller.onAuthorityChanged()
        selectReport(controller, worker)

        assertFalse(
            controller.submitRequest(
                reportId = REPORT_ID,
                requestType = UserReportRequestType.DELETE,
                requestText = REQUEST_TEXT,
            ),
        )
        assertTrue(client.requestIntents.isEmpty())
    }

    private fun controller(
        client: FakeClient,
        worker: QueuedExecutor,
        authority: () -> UserReportAuthority?,
    ) = UserReportController(
        client = client,
        workerExecutor = worker,
        callbackExecutor = DIRECT_EXECUTOR,
        authorityProvider = authority,
        observer = {},
    )

    private fun authority(
        session: GatewayFieldSession,
        sessionGeneration: Long = 7L,
        localIdentityEpoch: Long = 11L,
    ) = UserReportAuthority(session, sessionGeneration, localIdentityEpoch)

    private fun selectReport(
        controller: UserReportController,
        worker: QueuedExecutor,
    ) {
        assertTrue(controller.loadReports())
        worker.runNext()
        assertTrue(controller.openDetail(REPORT_ID))
        worker.runNext()
    }

    private fun session(actorId: String = "report-user"): GatewayFieldSession =
        GatewayFieldSession.verified(
            gatewayBaseUrl = "https://gateway.example.test",
            actorId = actorId,
            cookiePair = "walksafe_field_session=v2.report.user.session",
            expiresAtEpochMs = Long.MAX_VALUE,
        )

    private fun backendSession(
        actorId: String = BACKEND_ACTOR_ID,
        accountGeneration: Long = 7L,
    ): GatewayFieldSession = GatewayFieldSession.backendAccountDeviceSession(
        gatewayBaseUrl = "https://gateway.example.test",
        binding = BackendAccountDeviceCookieBinding(
            actorId = actorId,
            accountGeneration = accountGeneration,
            authEpoch = AUTH_EPOCH,
            deviceId = DEVICE_ID,
            sessionId = SESSION_ID,
            expiresAtEpochMs = EXPIRES_AT_EPOCH_MS,
        ),
        cookiePair = v7CookiePair(actorId, accountGeneration),
        expiresAtEpochMs = EXPIRES_AT_EPOCH_MS,
    )

    private fun v7CookiePair(actorId: String, accountGeneration: Long): String =
        "walksafe_field_session=" + listOf(
            "v7",
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(actorId.toByteArray(Charsets.UTF_8)),
            accountGeneration.toString(),
            AUTH_EPOCH.toString(),
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(DEVICE_ID.toByteArray(Charsets.UTF_8)),
            "general",
            EXPIRES_AT_EPOCH_SECONDS.toString(),
            SESSION_ID,
            "s".repeat(43),
        ).joinToString(".")

    private fun page(reportId: String, nextCursor: String?) =
        pageWithReports(listOf(reportId), nextCursor)

    private fun pageWithReports(
        reportIds: List<String>,
        nextCursor: String?,
    ) = UserReportListPage(
        items = reportIds.map { reportId ->
            UserReportSummary(
                reportId = reportId,
                createdAt = TIMESTAMP,
                userStatus = UserReportStatus.RECEIVED,
                publicRejectionReason = null,
                latestRequest = null,
            )
        },
        nextCursor = nextCursor,
    )

    private fun requestSummary(
        requestId: String = REQUEST_ID,
        requestType: UserReportRequestType = UserReportRequestType.CORRECTION,
        status: UserReportRequestStatus = UserReportRequestStatus.RECEIVED,
        publicResponse: String? = null,
    ) = UserReportRequestSummary(
        requestId = requestId,
        requestType = requestType,
        status = status,
        statusVersion = 1L,
        publicResponse = publicResponse,
        createdAt = TIMESTAMP,
        updatedAt = TIMESTAMP,
    )

    private fun detail(reportId: String) = UserReportDetail(
        reportId = reportId,
        createdAt = TIMESTAMP,
        userStatus = UserReportStatus.RECEIVED,
        publicRejectionReason = null,
        latestRequest = null,
    )

    private fun content(revision: Long) = UserReportContentCurrent(
        reportId = REPORT_ID,
        revision = revision,
        contentSha256 = SHA256,
        userDescription = "기존 설명",
        categoryHint = null,
        correctedAt = TIMESTAMP,
    )

    private fun contentRevision(expectedRevision: Long) = UserReportContentRevision(
        reportId = REPORT_ID,
        revision = expectedRevision + 1,
        expectedRevision = expectedRevision,
        idempotencyKey = CORRECTION_ID,
        contentSha256 = SHA256,
        userDescription = "보행로 파손 범위",
        categoryHint = null,
        correctedAt = TIMESTAMP,
    )

    private fun deletionStatus() = UserReportDeletionStatus(
        requestId = REQUEST_ID,
        reportId = REPORT_ID,
        state = UserReportDeletionState.DELETED,
        requestStatusVersion = 3,
        externalCopyCount = 0,
        updatedAt = TIMESTAMP,
    )

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    private class SwitchableCallbackExecutor : Executor {
        var reject = false

        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException("callback rejected")
            command.run()
        }
    }

    private class FakeClient : UserReportNetworkClient {
        val listResults = ArrayDeque<Result<UserReportListPage>>()
        val detailResults = ArrayDeque<Result<UserReportDetail>>()
        val requestResults = ArrayDeque<Result<UserReportRequestSummary>>()
        val requestStatusResults = ArrayDeque<Result<UserReportRequestSummary>>()
        val contentResults = ArrayDeque<Result<UserReportContentCurrent>>()
        val correctionResults = ArrayDeque<Result<UserReportContentRevision>>()
        val deletionStatusResults = ArrayDeque<Result<UserReportDeletionStatus>>()
        val listBindings = mutableListOf<Pair<String?, UserReportStatus?>>()
        val requestIntents = mutableListOf<UserReportRequestIntent>()
        val requestStatusBindings = mutableListOf<Pair<String, String>>()
        val correctionIntents = mutableListOf<UserReportCorrectionIntent>()
        val deletionStatusRequestIds = mutableListOf<String>()
        var listCancelFailure: Throwable? = null

        override fun listReportsCall(
            session: GatewayFieldSession,
            limit: Int,
            cursor: String?,
            userStatus: UserReportStatus?,
        ): CancellableNetworkCall<UserReportListPage> {
            listBindings += cursor to userStatus
            val result = listResults.removeFirst()
            return CancellableNetworkCall(
                executeBlock = { result.getOrThrow() },
                cancelBlock = { listCancelFailure?.let { throw it } },
            )
        }

        override fun reportDetailCall(
            session: GatewayFieldSession,
            reportId: String,
        ): CancellableNetworkCall<UserReportDetail> {
            val result = detailResults.removeFirst()
            return CancellableNetworkCall.blocking { result.getOrThrow() }
        }

        override fun createRequestCall(
            session: GatewayFieldSession,
            intent: UserReportRequestIntent,
        ): CancellableNetworkCall<UserReportRequestSummary> {
            requestIntents += intent
            val result = requestResults.removeFirst()
            return CancellableNetworkCall.blocking { result.getOrThrow() }
        }

        override fun reportRequestStatusCall(
            session: GatewayFieldSession,
            reportId: String,
            requestId: String,
        ): CancellableNetworkCall<UserReportRequestSummary> {
            requestStatusBindings += reportId to requestId
            val result = requestStatusResults.removeFirst()
            return CancellableNetworkCall.blocking { result.getOrThrow() }
        }

        override fun reportContentCall(
            session: GatewayFieldSession,
            reportId: String,
        ): CancellableNetworkCall<UserReportContentCurrent> {
            val result = contentResults.removeFirst()
            return CancellableNetworkCall.blocking { result.getOrThrow() }
        }

        override fun correctReportContentCall(
            session: GatewayFieldSession,
            intent: UserReportCorrectionIntent,
        ): CancellableNetworkCall<UserReportContentRevision> {
            correctionIntents += intent
            val result = correctionResults.removeFirst()
            return CancellableNetworkCall.blocking { result.getOrThrow() }
        }

        override fun reportDeletionStatusCall(
            session: GatewayFieldSession,
            requestId: String,
        ): CancellableNetworkCall<UserReportDeletionStatus> {
            deletionStatusRequestIds += requestId
            val result = deletionStatusResults.removeFirst()
            return CancellableNetworkCall.blocking { result.getOrThrow() }
        }
    }

    private class FakeDeletionTracker(
        private val acceptTracks: Boolean = true,
    ) : UserReportDeletionTracker {
        private val requestIdsByBinding = mutableMapOf<Pair<String, Long>, MutableList<String>>()
        private val referencesByBinding =
            mutableMapOf<Pair<String, Long>, MutableList<UserReportRequestReference>>()

        override fun track(session: GatewayFieldSession, requestId: String): Boolean {
            val generation = session.backendAccountGeneration ?: return false
            requestIdsByBinding.getOrPut(session.actorId to generation) { mutableListOf() }
                .apply { if (requestId !in this) add(requestId) }
            return true
        }

        override fun trackedRequestIds(session: GatewayFieldSession): List<String> {
            val generation = session.backendAccountGeneration ?: return emptyList()
            return requestIdsByBinding[session.actorId to generation].orEmpty()
        }

        override fun trackRequest(
            session: GatewayFieldSession,
            reference: UserReportRequestReference,
        ): Boolean {
            if (!acceptTracks) return false
            val generation = session.backendAccountGeneration ?: return false
            val binding = session.actorId to generation
            referencesByBinding.getOrPut(binding) { mutableListOf() }
                .apply { if (none { it.requestId == reference.requestId }) add(reference) }
            if (reference.requestType == UserReportRequestType.DELETE) {
                requestIdsByBinding.getOrPut(binding) { mutableListOf() }
                    .apply { if (reference.requestId !in this) add(reference.requestId) }
            }
            return true
        }

        override fun trackedRequestReferences(
            session: GatewayFieldSession,
        ): List<UserReportRequestReference> {
            val generation = session.backendAccountGeneration ?: return emptyList()
            return referencesByBinding[session.actorId to generation].orEmpty()
        }
    }

    private class BlockingReadDeletionTracker(
        private val blockedActorId: String,
        private val delegate: UserReportDeletionTracker,
    ) : UserReportDeletionTracker by delegate {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)

        override fun trackedRequestReferences(
            session: GatewayFieldSession,
        ): List<UserReportRequestReference> {
            if (session.actorId == blockedActorId) {
                readStarted.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
            }
            return delegate.trackedRequestReferences(session)
        }
    }

    private class BlockingIterationDeletionTracker(
        private val blockedActorId: String,
        blockedReference: UserReportRequestReference,
        private val delegate: UserReportDeletionTracker,
    ) : UserReportDeletionTracker by delegate {
        val iterationStarted = CountDownLatch(1)
        val releaseIteration = CountDownLatch(1)
        private val blockedReferences = object : AbstractList<UserReportRequestReference>() {
            override val size: Int = 1

            override fun get(index: Int): UserReportRequestReference {
                require(index == 0)
                iterationStarted.countDown()
                check(releaseIteration.await(5, TimeUnit.SECONDS))
                return blockedReference
            }
        }

        override fun trackedRequestReferences(
            session: GatewayFieldSession,
        ): List<UserReportRequestReference> = if (session.actorId == blockedActorId) {
            blockedReferences
        } else {
            delegate.trackedRequestReferences(session)
        }
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
        const val REPORT_ID = "44444444-4444-4444-8444-444444444444"
        const val SECOND_REPORT_ID = "44444444-4444-4444-8444-444444444445"
        const val REQUEST_ID = "55555555-5555-4555-8555-555555555555"
        const val SECOND_REQUEST_ID = "55555555-5555-4555-8555-555555555556"
        const val CLIENT_REQUEST_ID = "66666666-6666-4666-8666-666666666666"
        const val SECOND_CLIENT_REQUEST_ID = "77777777-7777-4777-8777-777777777777"
        const val CORRECTION_ID = "88888888-8888-4888-8888-888888888888"
        const val TIMESTAMP = "2026-08-29T01:02:03.000000Z"
        const val SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val REQUEST_TEXT = "표면 손상 범위를 정정해 주세요."
        const val SECOND_REQUEST_TEXT = "새 요청 사유입니다."
        const val DEVICE_ID = "android-report-device"
        const val AUTH_EPOCH = 3L
        const val BACKEND_ACTOR_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val SECOND_BACKEND_ACTOR_ID = "123e4567-e89b-42d3-a456-426614174001"
        val EXPIRES_AT_EPOCH_SECONDS = System.currentTimeMillis() / 1_000L + 3_600L
        val EXPIRES_AT_EPOCH_MS = EXPIRES_AT_EPOCH_SECONDS * 1_000L
        val SESSION_ID = "i".repeat(43)
    }
}
