package kr.co.hanium.dreamup.walksafe.report

import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
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
    fun deletionTombstoneScrubsStaleCrossEndpointReportStateAndStatusActions() {
        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
            contentResults += Result.success(content(revision = 3))
            correctionResults += Result.success(contentRevision(expectedRevision = 3))
            requestResults += Result.success(
                requestSummary(
                    requestType = UserReportRequestType.DELETE,
                    publicResponse = "삭제 전 공개 답변",
                ),
            )
            requestStatusResults += Result.success(
                requestSummary(
                    requestType = UserReportRequestType.DELETE,
                    publicResponse = "삭제 전 처리 답변",
                ),
            )
            deletionStatusResults += Result.success(
                deletionStatus(state = UserReportDeletionState.DELETED, requestStatusVersion = 1),
            )
            requestHistoryResults += Result.success(
                historyPage(
                    items = listOf(tombstoneHistoryItem(1, REQUEST_ID)),
                    snapshotRevision = 1,
                    totalCount = 1,
                ),
            )
            requestHistoryResults += Result.success(
                historyPage(
                    items = listOf(tombstoneHistoryItem(2, REQUEST_ID)),
                    snapshotRevision = 2,
                    totalCount = 1,
                ),
            )
        }
        val stableAuthority = authority(backendSession())
        val tracker = FakeDeletionTracker()
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { stableAuthority },
            observer = {},
            deletionTracker = tracker,
            correctionIdFactory = { CORRECTION_ID },
        )
        controller.onAuthorityChanged()
        selectReport(controller, worker)
        assertTrue(controller.loadContent(REPORT_ID))
        worker.runNext()
        assertTrue(
            controller.submitCorrection(
                reportId = REPORT_ID,
                userDescription = UserReportCorrectionPatch.Value("정정 후 설명"),
                categoryHint = UserReportCorrectionPatch.Omitted,
            ),
        )
        worker.runNext()
        assertTrue(
            controller.submitRequest(
                reportId = REPORT_ID,
                requestType = UserReportRequestType.DELETE,
                requestText = REQUEST_TEXT,
            ),
        )
        worker.runNext()
        assertTrue(controller.refreshRequestStatus(REPORT_ID, REQUEST_ID))
        worker.runNext()
        assertTrue(controller.refreshDeletionStatus(REQUEST_ID))
        worker.runNext()

        val before = controller.snapshot()
        assertTrue(before.reports.isNotEmpty())
        assertEquals(REPORT_ID, before.selectedDetail?.reportId)
        assertEquals(REPORT_ID, before.selectedContent?.reportId)
        assertEquals(REPORT_ID, before.latestCorrection?.reportId)
        assertEquals(REQUEST_ID, before.latestCreatedRequest?.requestId)
        assertEquals(REQUEST_ID, before.selectedRequestStatus?.requestId)
        assertEquals(REQUEST_ID, before.selectedDeletionStatus?.requestId)
        assertTrue(controller.loadRequestHistory())
        worker.runNext()

        val scrubbed = controller.snapshot()
        assertEquals(UserReportUiPhase.READY, scrubbed.phase)
        assertTrue(scrubbed.reports.isEmpty())
        assertNull(scrubbed.selectedDetail)
        assertNull(scrubbed.selectedContent)
        assertNull(scrubbed.latestCorrection)
        assertNull(scrubbed.latestCreatedRequest)
        assertNull(scrubbed.selectedRequestStatus)
        assertNull(scrubbed.selectedDeletionStatus)
        assertTrue(scrubbed.trackedRequestReferences.isEmpty())
        assertTrue(scrubbed.trackedDeletionRequestIds.isEmpty())
        assertEquals(
            listOf(UserReportRequestHistorySource.DELETION_TOMBSTONE),
            scrubbed.requestHistoryItems.map { it.source },
        )
        assertFalse(controller.refreshRequestStatus(REPORT_ID, REQUEST_ID))
        assertFalse(controller.refreshDeletionStatus(REQUEST_ID))
        assertEquals(listOf(REPORT_ID to REQUEST_ID), client.requestStatusBindings)
        assertEquals(listOf(REQUEST_ID), client.deletionStatusRequestIds)
        assertEquals(listOf(REQUEST_ID), tracker.trackedRequestIds(stableAuthority.session))

        assertTrue(controller.loadRequestHistory())
        worker.runNext()
        assertTrue(controller.snapshot().trackedRequestReferences.isEmpty())
        assertTrue(controller.snapshot().trackedDeletionRequestIds.isEmpty())
    }

    @Test
    fun deletionTombstoneDropsFailedRequestIntentSoAnotherReportCanProceed() {
        val worker = QueuedExecutor()
        val client = FakeClient().apply {
            listResults += Result.success(pageWithReports(listOf(REPORT_ID, SECOND_REPORT_ID), null))
            detailResults += Result.success(detail(REPORT_ID))
            detailResults += Result.success(detail(SECOND_REPORT_ID))
            requestResults += Result.failure(IOException("offline"))
            requestResults += Result.success(requestSummary(requestId = SECOND_REQUEST_ID))
            requestHistoryResults += Result.success(
                historyPage(
                    items = listOf(tombstoneHistoryItem(1, REQUEST_ID)),
                    snapshotRevision = 1,
                    totalCount = 1,
                ),
            )
        }
        val stableAuthority = authority(session())
        val controller = controller(client, worker) { stableAuthority }
        controller.onAuthorityChanged()
        assertTrue(controller.loadReports())
        worker.runNext()
        assertTrue(controller.openDetail(REPORT_ID))
        worker.runNext()
        assertTrue(
            controller.submitRequest(
                reportId = REPORT_ID,
                requestType = UserReportRequestType.CORRECTION,
                requestText = REQUEST_TEXT,
            ),
        )
        worker.runNext()
        assertEquals(UserReportFailure.TEMPORARY, controller.snapshot().failure)

        assertTrue(controller.loadRequestHistory())
        worker.runNext()
        assertEquals(listOf(SECOND_REPORT_ID), controller.snapshot().reports.map { it.reportId })
        assertTrue(controller.openDetail(SECOND_REPORT_ID))
        worker.runNext()
        assertTrue(
            controller.submitRequest(
                reportId = SECOND_REPORT_ID,
                requestType = UserReportRequestType.CORRECTION,
                requestText = SECOND_REQUEST_TEXT,
            ),
        )
        worker.runNext()

        assertEquals(
            listOf(REPORT_ID, SECOND_REPORT_ID),
            client.requestIntents.map { it.reportId },
        )
        assertEquals(UserReportUiPhase.READY, controller.snapshot().phase)
    }

    @Test
    fun deletionTombstoneDropsFailedCorrectionIntentSoAnotherReportCanProceed() {
        val worker = QueuedExecutor()
        val correctionIds = ArrayDeque(listOf(CORRECTION_ID, SECOND_CORRECTION_ID))
        val client = FakeClient().apply {
            listResults += Result.success(pageWithReports(listOf(REPORT_ID, SECOND_REPORT_ID), null))
            detailResults += Result.success(detail(REPORT_ID))
            detailResults += Result.success(detail(SECOND_REPORT_ID))
            contentResults += Result.success(content(revision = 3, reportId = REPORT_ID))
            contentResults += Result.success(content(revision = 5, reportId = SECOND_REPORT_ID))
            correctionResults += Result.failure(IOException("offline"))
            correctionResults += Result.success(
                contentRevision(
                    expectedRevision = 5,
                    reportId = SECOND_REPORT_ID,
                    idempotencyKey = SECOND_CORRECTION_ID,
                ),
            )
            requestHistoryResults += Result.success(
                historyPage(
                    items = listOf(tombstoneHistoryItem(1, REQUEST_ID)),
                    snapshotRevision = 1,
                    totalCount = 1,
                ),
            )
        }
        val stableAuthority = authority(session())
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { stableAuthority },
            observer = {},
            correctionIdFactory = { correctionIds.removeFirst() },
        )
        controller.onAuthorityChanged()
        assertTrue(controller.loadReports())
        worker.runNext()
        assertTrue(controller.openDetail(REPORT_ID))
        worker.runNext()
        assertTrue(controller.loadContent(REPORT_ID))
        worker.runNext()
        assertTrue(
            controller.submitCorrection(
                reportId = REPORT_ID,
                userDescription = UserReportCorrectionPatch.Value("첫 신고 정정"),
                categoryHint = UserReportCorrectionPatch.Omitted,
            ),
        )
        worker.runNext()
        assertEquals(UserReportFailure.TEMPORARY, controller.snapshot().failure)

        assertTrue(controller.loadRequestHistory())
        worker.runNext()
        assertTrue(controller.openDetail(SECOND_REPORT_ID))
        worker.runNext()
        assertTrue(controller.loadContent(SECOND_REPORT_ID))
        worker.runNext()
        assertTrue(
            controller.submitCorrection(
                reportId = SECOND_REPORT_ID,
                userDescription = UserReportCorrectionPatch.Value("두 번째 신고 정정"),
                categoryHint = UserReportCorrectionPatch.Omitted,
            ),
        )
        worker.runNext()

        assertEquals(
            listOf(REPORT_ID, SECOND_REPORT_ID),
            client.correctionIntents.map { it.reportId },
        )
        assertEquals(SECOND_CORRECTION_ID, controller.snapshot().latestCorrection?.idempotencyKey)
    }

    @Test
    fun requestHistoryPaginatesOnePinnedSnapshotAndDerivesActiveTrackingFromServerItems() {
        val worker = QueuedExecutor()
        val activeDeletion = activeHistoryItem(
            revision = 3,
            requestId = REQUEST_ID,
            requestType = UserReportRequestType.DELETE,
        )
        val tombstone = tombstoneHistoryItem(
            revision = 1,
            requestId = SECOND_REQUEST_ID,
            reportId = SECOND_REPORT_ID,
        )
        val client = FakeClient().apply {
            requestHistoryResults += Result.success(
                historyPage(
                    items = listOf(activeDeletion),
                    snapshotRevision = 3,
                    totalCount = 2,
                    nextCursor = "history_cursor_1",
                ),
            )
            requestHistoryResults += Result.success(
                historyPage(
                    items = listOf(tombstone),
                    snapshotRevision = 3,
                    totalCount = 2,
                ),
            )
        }
        val stableAuthority = authority(backendSession())
        val controller = controller(client, worker) { stableAuthority }
        controller.onAuthorityChanged()

        assertTrue(controller.loadRequestHistory())
        worker.runNext()
        assertEquals("history_cursor_1", controller.snapshot().requestHistoryNextCursor)
        assertTrue(controller.loadNextRequestHistoryPage())
        worker.runNext()

        assertEquals(UserReportUiPhase.READY, controller.snapshot().phase)
        assertEquals(listOf(3L, 1L), controller.snapshot().requestHistoryItems.map { it.revision })
        assertEquals(2L, controller.snapshot().requestHistoryTotalCount)
        assertEquals(
            listOf(UserReportRequestReference(REPORT_ID, REQUEST_ID, UserReportRequestType.DELETE)),
            controller.snapshot().trackedRequestReferences,
        )
        assertEquals(listOf(REQUEST_ID), controller.snapshot().trackedDeletionRequestIds)
        assertEquals(
            listOf(
                Triple(25, null, null),
                Triple(25, "history_cursor_1", null),
            ),
            client.requestHistoryBindings,
        )
    }

    @Test
    fun expiredHistoryCursorRetriesFirstPageWhileKeepingVisibleHistory() {
        val worker = QueuedExecutor()
        val oldItem = activeHistoryItem(revision = 2)
        val replacement = activeHistoryItem(revision = 4, requestId = SECOND_REQUEST_ID)
        val client = FakeClient().apply {
            requestHistoryResults += Result.success(
                historyPage(listOf(oldItem), 2, 2, "expired_cursor"),
            )
            requestHistoryResults += Result.failure(UserReportHttpException(422))
            requestHistoryResults += Result.success(
                historyPage(listOf(replacement), 4, 1),
            )
        }
        val stableAuthority = authority(backendSession())
        val controller = controller(client, worker) { stableAuthority }
        controller.onAuthorityChanged()
        assertTrue(controller.loadRequestHistory())
        worker.runNext()

        assertTrue(controller.loadNextRequestHistoryPage())
        worker.runNext()
        assertEquals(UserReportUiPhase.LOADING_REQUEST_HISTORY, controller.snapshot().phase)
        assertEquals(listOf(oldItem), controller.snapshot().requestHistoryItems)
        assertEquals("expired_cursor", controller.snapshot().requestHistoryNextCursor)
        worker.runNext()

        assertEquals(listOf(replacement), controller.snapshot().requestHistoryItems)
        assertNull(controller.snapshot().requestHistoryNextCursor)
        assertEquals(
            listOf(
                Triple(25, null, null),
                Triple(25, "expired_cursor", null),
                Triple(25, null, null),
            ),
            client.requestHistoryBindings,
        )
    }

    @Test
    fun historyErrorsAndMalformedPagesPreserveExistingItemsAndCursor() {
        listOf<Result<UserReportRequestHistoryPage>>(
            Result.failure(IOException("offline")),
            Result.success(historyPage(emptyList(), 2, 2)),
            Result.success(
                historyPage(
                    items = listOf(activeHistoryItem(1, SECOND_REQUEST_ID)),
                    snapshotRevision = 2,
                    totalCount = 3,
                ),
            ),
        ).forEach { failingPage ->
            val worker = QueuedExecutor()
            val initialItem = activeHistoryItem(revision = 2)
            val client = FakeClient().apply {
                requestHistoryResults += Result.success(
                    historyPage(listOf(initialItem), 2, 2, "history_cursor"),
                )
                requestHistoryResults += failingPage
            }
            val stableAuthority = authority(backendSession())
            val controller = controller(client, worker) { stableAuthority }
            controller.onAuthorityChanged()
            assertTrue(controller.loadRequestHistory())
            worker.runNext()
            assertTrue(controller.loadNextRequestHistoryPage())
            worker.runNext()

            assertEquals(UserReportUiPhase.ERROR, controller.snapshot().phase)
            assertEquals(listOf(initialItem), controller.snapshot().requestHistoryItems)
            assertEquals("history_cursor", controller.snapshot().requestHistoryNextCursor)
            assertTrue(controller.snapshot().retryAvailable)
        }
    }

    @Test
    fun concealedListOrHistory404ClearsAllUserReportMemoryFailClosed() {
        listOf(false, true).forEach { failHistory ->
            val worker = QueuedExecutor()
            val client = FakeClient().apply {
                listResults += Result.success(page(REPORT_ID, null))
                requestHistoryResults += Result.success(
                    historyPage(
                        listOf(activeHistoryItem(1, requestType = UserReportRequestType.DELETE)),
                        1,
                        1,
                    ),
                )
                detailResults += Result.success(detail(REPORT_ID))
                if (failHistory) {
                    requestHistoryResults += Result.failure(UserReportHttpException(404))
                } else {
                    listResults += Result.failure(UserReportHttpException(404))
                }
                listResults += Result.success(page(REPORT_ID, null))
            }
            val stableAuthority = authority(backendSession())
            val controller = controller(client, worker) { stableAuthority }
            controller.onAuthorityChanged()
            assertTrue(controller.loadReports())
            worker.runNext()
            assertTrue(controller.loadRequestHistory())
            worker.runNext()
            assertTrue(controller.openDetail(REPORT_ID))
            worker.runNext()
            assertTrue(controller.snapshot().reports.isNotEmpty())
            assertTrue(controller.snapshot().requestHistoryItems.isNotEmpty())
            assertEquals(REPORT_ID, controller.snapshot().selectedDetail?.reportId)

            assertTrue(
                if (failHistory) {
                    controller.loadRequestHistory()
                } else {
                    controller.loadReports()
                },
            )
            worker.runNext()

            val cleared = controller.snapshot()
            assertEquals(UserReportUiPhase.ERROR, cleared.phase)
            assertEquals(UserReportFailure.NOT_FOUND_OR_SIGNED_OUT, cleared.failure)
            assertFalse(cleared.retryAvailable)
            assertTrue(cleared.reports.isEmpty())
            assertNull(cleared.nextCursor)
            assertTrue(cleared.requestHistoryItems.isEmpty())
            assertNull(cleared.requestHistoryNextCursor)
            assertFalse(cleared.requestHistoryLoaded)
            assertNull(cleared.selectedDetail)
            assertNull(cleared.selectedContent)
            assertNull(cleared.latestCreatedRequest)
            assertTrue(cleared.trackedRequestReferences.isEmpty())
            assertTrue(cleared.trackedDeletionRequestIds.isEmpty())
            assertEquals(1L, cleared.requestHistoryRefreshSequence)

            assertTrue(controller.loadReports())
            worker.runNext()
            assertEquals(2L, controller.snapshot().requestHistoryRefreshSequence)
        }
    }

    @Test
    fun reportFilteredHistory404PreservesExistingStateForTheConcealedReportError() {
        val worker = QueuedExecutor()
        val item = activeHistoryItem(
            revision = 1,
            requestType = UserReportRequestType.DELETE,
        )
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
            requestHistoryResults += Result.success(
                historyPage(
                    items = listOf(item),
                    snapshotRevision = 1,
                    totalCount = 1,
                    reportId = REPORT_ID,
                ),
            )
            requestHistoryResults += Result.failure(UserReportHttpException(404))
        }
        val stableAuthority = authority(backendSession())
        val controller = controller(client, worker) { stableAuthority }
        controller.onAuthorityChanged()
        assertTrue(controller.loadReports())
        worker.runNext()
        assertTrue(controller.loadRequestHistory(REPORT_ID))
        worker.runNext()
        assertTrue(controller.openDetail(REPORT_ID))
        worker.runNext()

        assertTrue(controller.loadRequestHistory(REPORT_ID))
        worker.runNext()

        val preserved = controller.snapshot()
        assertEquals(UserReportUiPhase.ERROR, preserved.phase)
        assertEquals(UserReportFailure.NOT_FOUND_OR_SIGNED_OUT, preserved.failure)
        assertTrue(preserved.retryAvailable)
        assertEquals(listOf(REPORT_ID), preserved.reports.map { it.reportId })
        assertEquals(listOf(item), preserved.requestHistoryItems)
        assertEquals(REPORT_ID, preserved.requestHistoryReportId)
        assertTrue(preserved.requestHistoryLoaded)
        assertEquals(REPORT_ID, preserved.selectedDetail?.reportId)
        assertEquals(listOf(REQUEST_ID), preserved.trackedDeletionRequestIds)
    }

    @Test
    fun firstHistoryPageMustStartAtSnapshotAndEndExactlyWithTotalCount() {
        listOf(
            historyPage(
                items = listOf(activeHistoryItem(1)),
                snapshotRevision = 2,
                totalCount = 1,
            ),
            historyPage(
                items = listOf(activeHistoryItem(2)),
                snapshotRevision = 2,
                totalCount = 2,
                nextCursor = null,
            ),
            historyPage(
                items = listOf(activeHistoryItem(2)),
                snapshotRevision = 2,
                totalCount = 1,
                nextCursor = "unexpected_cursor",
            ),
        ).forEach { malformed ->
            val worker = QueuedExecutor()
            val client = FakeClient().apply {
                requestHistoryResults += Result.success(malformed)
            }
            val stableAuthority = authority(backendSession())
            val controller = controller(client, worker) { stableAuthority }
            controller.onAuthorityChanged()

            assertTrue(controller.loadRequestHistory())
            worker.runNext()

            assertEquals(UserReportUiPhase.ERROR, controller.snapshot().phase)
            assertEquals(UserReportFailure.MALFORMED_RESPONSE, controller.snapshot().failure)
            assertFalse(controller.snapshot().requestHistoryLoaded)
        }
    }

    @Test
    fun everyAuthorityFenceClearsRequestHistoryAndDoesNotRehydratePersistedTracking() {
        val mutations = listOf<(UserReportAuthority) -> UserReportAuthority?>(
            { null },
            { old -> authority(backendSession(), old.sessionGeneration, old.localIdentityEpoch) },
            { old ->
                authority(
                    backendSession(accountGeneration = 8L),
                    old.sessionGeneration,
                    old.localIdentityEpoch,
                )
            },
            { old -> authority(old.session, old.sessionGeneration + 1L, old.localIdentityEpoch) },
            { old -> authority(old.session, old.sessionGeneration, old.localIdentityEpoch + 1L) },
        )
        mutations.forEach { mutate ->
            val worker = QueuedExecutor()
            val tracker = FakeDeletionTracker()
            val initial = authority(backendSession())
            assertTrue(
                tracker.trackRequest(
                    initial.session,
                    UserReportRequestReference(
                        REPORT_ID,
                        REQUEST_ID,
                        UserReportRequestType.DELETE,
                    ),
                ),
            )
            var current: UserReportAuthority? = initial
            val client = FakeClient().apply {
                requestHistoryResults += Result.success(
                    historyPage(listOf(activeHistoryItem(1)), 1, 1),
                )
            }
            val controller = UserReportController(
                client = client,
                workerExecutor = worker,
                callbackExecutor = DIRECT_EXECUTOR,
                authorityProvider = { current },
                observer = {},
                deletionTracker = tracker,
            )
            controller.onAuthorityChanged()
            assertTrue(controller.snapshot().trackedRequestReferences.isEmpty())
            assertTrue(controller.loadRequestHistory())
            worker.runNext()
            assertTrue(controller.snapshot().requestHistoryLoaded)

            current = mutate(initial)
            controller.onAuthorityChanged()

            assertTrue(controller.snapshot().requestHistoryItems.isEmpty())
            assertNull(controller.snapshot().requestHistoryNextCursor)
            assertFalse(controller.snapshot().requestHistoryLoaded)
            assertTrue(controller.snapshot().trackedRequestReferences.isEmpty())
            assertTrue(controller.snapshot().trackedDeletionRequestIds.isEmpty())
        }
    }

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
        assertEquals(1L, controller.snapshot().requestHistoryRefreshSequence)
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
        assertEquals(2L, controller.snapshot().requestHistoryRefreshSequence)
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
        assertEquals(1L, controller.snapshot().requestHistoryRefreshSequence)

        assertTrue(controller.loadReports())
        assertEquals(UserReportUiPhase.LOADING_LIST, controller.snapshot().phase)
        assertEquals(listOf(REPORT_ID), controller.snapshot().reports.map { it.reportId })
        worker.runNext()
        assertEquals(listOf(REPORT_ID), controller.snapshot().reports.map { it.reportId })
        assertEquals(2L, controller.snapshot().requestHistoryRefreshSequence)
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
        assertEquals(
            UserReportExternalCopyDeletionState.REQUEST_SENT,
            controller.snapshot().selectedDeletionStatus?.externalCopies?.single()?.state,
        )
        assertEquals(listOf(REQUEST_ID), client.deletionStatusRequestIds)
    }

    @Test
    fun deletionStatusKeepsConcealed404AndRateLimitRetryableWithoutLosingTracking() {
        listOf(
            404 to UserReportFailure.NOT_FOUND_OR_SIGNED_OUT,
            429 to UserReportFailure.TEMPORARY,
        ).forEach { (statusCode, expectedFailure) ->
            val worker = QueuedExecutor()
            val stableAuthority = authority(backendSession())
            val reference = UserReportRequestReference(
                reportId = REPORT_ID,
                requestId = REQUEST_ID,
                requestType = UserReportRequestType.DELETE,
            )
            val client = FakeClient().apply {
                requestHistoryResults += Result.success(
                    historyPage(
                        listOf(activeHistoryItem(1, requestType = UserReportRequestType.DELETE)),
                        1,
                        1,
                    ),
                )
                deletionStatusResults += Result.failure(UserReportHttpException(statusCode))
            }
            val controller = UserReportController(
                client = client,
                workerExecutor = worker,
                callbackExecutor = DIRECT_EXECUTOR,
                authorityProvider = { stableAuthority },
                observer = {},
            )
            controller.onAuthorityChanged()
            assertTrue(controller.loadRequestHistory())
            worker.runNext()

            assertTrue(controller.refreshDeletionStatus(REQUEST_ID))
            worker.runNext()

            assertEquals(expectedFailure, controller.snapshot().failure)
            assertTrue(controller.snapshot().retryAvailable)
            assertEquals(listOf(REQUEST_ID), controller.snapshot().trackedDeletionRequestIds)
            assertEquals(listOf(reference), controller.snapshot().trackedRequestReferences)
        }
    }

    @Test
    fun deletionStatusRejectsReportIdThatConflictsWithTrackedRequestReference() {
        val worker = QueuedExecutor()
        val stableAuthority = authority(backendSession())
        val client = FakeClient().apply {
            requestHistoryResults += Result.success(
                historyPage(
                    listOf(activeHistoryItem(1, requestType = UserReportRequestType.DELETE)),
                    1,
                    1,
                ),
            )
            deletionStatusResults += Result.success(deletionStatus(reportId = SECOND_REPORT_ID))
        }
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { stableAuthority },
            observer = {},
        )
        controller.onAuthorityChanged()
        assertTrue(controller.loadRequestHistory())
        worker.runNext()

        assertTrue(controller.refreshDeletionStatus(REQUEST_ID))
        worker.runNext()

        assertEquals(UserReportFailure.MALFORMED_RESPONSE, controller.snapshot().failure)
        assertNull(controller.snapshot().selectedDeletionStatus)
        assertTrue(controller.snapshot().retryAvailable)
    }

    @Test
    fun exactRequestStatusUsesServerHistoryBindingAndDisplaysCurrentPublicResult() {
        val worker = QueuedExecutor()
        val stableAuthority = authority(backendSession())
        val reference = UserReportRequestReference(
            reportId = REPORT_ID,
            requestId = REQUEST_ID,
            requestType = UserReportRequestType.DELETE,
        )
        val client = FakeClient().apply {
            requestHistoryResults += Result.success(
                historyPage(
                    listOf(activeHistoryItem(1, requestType = UserReportRequestType.DELETE)),
                    1,
                    1,
                ),
            )
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
        )

        controller.onAuthorityChanged()
        assertTrue(controller.loadRequestHistory())
        worker.runNext()
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
    fun requestStatusDoesNotCreateACallAcrossAuthorityChangeOrRestoreLocalTrackerState() {
        val worker = QueuedExecutor()
        val authorityA = authority(backendSession(BACKEND_ACTOR_ID))
        val authorityB = authority(backendSession(SECOND_BACKEND_ACTOR_ID))
        val referenceA = UserReportRequestReference(
            REPORT_ID,
            REQUEST_ID,
            UserReportRequestType.DELETE,
        )
        val tracker = FakeDeletionTracker().apply {
            assertTrue(trackRequest(authorityA.session, referenceA))
        }
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            detailResults += Result.success(detail(REPORT_ID))
        }
        var current: UserReportAuthority? = authorityA
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { current },
            observer = {},
            deletionTracker = tracker,
        )
        controller.onAuthorityChanged()
        selectReport(controller, worker)

        current = authorityB
        controller.onAuthorityChanged()
        assertFalse(controller.refreshRequestStatus(REPORT_ID, REQUEST_ID))
        assertTrue(client.requestStatusBindings.isEmpty())
        assertEquals(UserReportUiPhase.IDLE, controller.snapshot().phase)
        assertTrue(controller.snapshot().reports.isEmpty())
        assertNull(controller.snapshot().selectedDetail)
        assertTrue(controller.snapshot().trackedRequestReferences.isEmpty())
        assertTrue(controller.snapshot().trackedDeletionRequestIds.isEmpty())
    }

    @Test
    fun historyCompletionFromAStaleAuthorityCannotReplaceTheNewAuthorityState() {
        val worker = QueuedExecutor()
        val authorityA = authority(backendSession(BACKEND_ACTOR_ID))
        val authorityB = authority(backendSession(SECOND_BACKEND_ACTOR_ID))
        val client = FakeClient().apply {
            requestHistoryResults += Result.success(
                historyPage(listOf(activeHistoryItem(1)), 1, 1),
            )
        }
        var current: UserReportAuthority? = authorityA
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = DIRECT_EXECUTOR,
            authorityProvider = { current },
            observer = {},
        )
        controller.onAuthorityChanged()
        assertTrue(controller.loadRequestHistory())
        current = authorityB
        controller.onAuthorityChanged()
        worker.runNext()

        assertEquals(UserReportUiPhase.IDLE, controller.snapshot().phase)
        assertTrue(controller.snapshot().requestHistoryItems.isEmpty())
        assertFalse(controller.snapshot().requestHistoryLoaded)
        assertTrue(controller.snapshot().trackedRequestReferences.isEmpty())
    }

    @Test
    fun completionDetectsAuthorityLossWithoutWaitingForAnAuthorityChangedEvent() {
        val worker = QueuedExecutor()
        val authorityA = authority(backendSession(BACKEND_ACTOR_ID))
        val authorityB = authority(backendSession(SECOND_BACKEND_ACTOR_ID))
        var current: UserReportAuthority? = authorityA
        val client = FakeClient().apply {
            requestHistoryResults += Result.success(
                historyPage(listOf(activeHistoryItem(2)), 2, 1),
            )
            requestHistoryResults += Result.success(
                historyPage(listOf(activeHistoryItem(1)), 1, 1),
            )
        }
        val controller = controller(client, worker) { current }
        controller.onAuthorityChanged()
        assertTrue(controller.loadRequestHistory())

        current = null
        worker.runNext()

        assertEquals(UserReportUiPhase.SIGNED_OUT, controller.snapshot().phase)
        assertEquals(UserReportFailure.NOT_FOUND_OR_SIGNED_OUT, controller.snapshot().failure)
        assertTrue(controller.snapshot().requestHistoryItems.isEmpty())
        assertFalse(controller.snapshot().requestHistoryLoaded)

        current = authorityB
        controller.onAuthorityChanged()
        assertTrue(controller.loadRequestHistory())
        worker.runNext()
        assertEquals(UserReportUiPhase.READY, controller.snapshot().phase)
        assertEquals(listOf(1L), controller.snapshot().requestHistoryItems.map { it.revision })
    }

    @Test
    fun deferredObserverCannotPublishAnOldAuthorityRefreshSequence() {
        val worker = QueuedExecutor()
        val callbacks = QueuedExecutor()
        val authorityA = authority(backendSession(BACKEND_ACTOR_ID))
        val authorityB = authority(backendSession(SECOND_BACKEND_ACTOR_ID))
        var current: UserReportAuthority? = authorityA
        val observed = mutableListOf<UserReportUiState>()
        val client = FakeClient().apply {
            listResults += Result.success(page(REPORT_ID, null))
            listResults += Result.success(page(SECOND_REPORT_ID, null))
        }
        val controller = UserReportController(
            client = client,
            workerExecutor = worker,
            callbackExecutor = callbacks,
            authorityProvider = { current },
            observer = observed::add,
        )
        controller.onAuthorityChanged()
        callbacks.runNext()
        assertTrue(controller.loadReports())
        callbacks.runNext()
        worker.runNext()
        callbacks.runNext()

        current = authorityB
        callbacks.runNext()
        assertTrue(observed.none { it.requestHistoryRefreshSequence > 0L })

        controller.onAuthorityChanged()
        callbacks.runNext()
        assertTrue(controller.loadReports())
        callbacks.runNext()
        worker.runNext()
        callbacks.runNext()
        callbacks.runNext()

        assertEquals(listOf(SECOND_REPORT_ID), observed.last().reports.map { it.reportId })
        assertEquals(1L, observed.last().requestHistoryRefreshSequence)
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
        assertEquals(1, client.listCancelCalls)
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

    private fun historyPage(
        items: List<UserReportRequestHistoryItem>,
        snapshotRevision: Long,
        totalCount: Long,
        nextCursor: String? = null,
        reportId: String? = null,
    ) = UserReportRequestHistoryPage(
        reportId = reportId,
        snapshotRevision = snapshotRevision,
        totalCount = totalCount,
        items = items,
        nextCursor = nextCursor,
    )

    private fun activeHistoryItem(
        revision: Long,
        requestId: String = REQUEST_ID,
        reportId: String = REPORT_ID,
        requestType: UserReportRequestType = UserReportRequestType.CORRECTION,
    ): UserReportRequestHistoryItem {
        val request = requestSummary(
            requestId = requestId,
            requestType = requestType,
        )
        return UserReportRequestHistoryItem(
            revision = revision,
            source = UserReportRequestHistorySource.ACTIVE_REQUEST,
            reportId = reportId,
            requestId = requestId,
            request = request,
            deletionStatus = if (requestType == UserReportRequestType.DELETE) {
                deletionStatus(
                    reportId = reportId,
                    requestId = requestId,
                    state = UserReportDeletionState.PENDING,
                    requestStatusVersion = request.statusVersion,
                )
            } else {
                null
            },
        )
    }

    private fun tombstoneHistoryItem(
        revision: Long,
        requestId: String,
        reportId: String = REPORT_ID,
    ) = UserReportRequestHistoryItem(
        revision = revision,
        source = UserReportRequestHistorySource.DELETION_TOMBSTONE,
        reportId = reportId,
        requestId = requestId,
        request = null,
        deletionStatus = deletionStatus(
            reportId = reportId,
            requestId = requestId,
            state = UserReportDeletionState.DELETED,
        ),
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

    private fun content(
        revision: Long,
        reportId: String = REPORT_ID,
    ) = UserReportContentCurrent(
        reportId = reportId,
        revision = revision,
        contentSha256 = SHA256,
        userDescription = "기존 설명",
        categoryHint = null,
        correctedAt = TIMESTAMP,
    )

    private fun contentRevision(
        expectedRevision: Long,
        reportId: String = REPORT_ID,
        idempotencyKey: String = CORRECTION_ID,
    ) = UserReportContentRevision(
        reportId = reportId,
        revision = expectedRevision + 1,
        expectedRevision = expectedRevision,
        idempotencyKey = idempotencyKey,
        contentSha256 = SHA256,
        userDescription = "보행로 파손 범위",
        categoryHint = null,
        correctedAt = TIMESTAMP,
    )

    private fun deletionStatus(
        reportId: String = REPORT_ID,
        requestId: String = REQUEST_ID,
        state: UserReportDeletionState = UserReportDeletionState.DELETED,
        requestStatusVersion: Long = 3,
    ) = UserReportDeletionStatus(
        requestId = requestId,
        reportId = reportId,
        state = state,
        requestStatusVersion = requestStatusVersion,
        externalCopyCount = if (state == UserReportDeletionState.DELETED) 1 else 0,
        externalCopies = if (state == UserReportDeletionState.DELETED) {
            listOf(
                UserReportDeletionExternalCopyStatus(
                    institution = "서울시청",
                    state = UserReportExternalCopyDeletionState.REQUEST_SENT,
                    statusRecordedAt = TIMESTAMP,
                ),
            )
        } else {
            emptyList()
        },
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
        val requestHistoryResults = ArrayDeque<Result<UserReportRequestHistoryPage>>()
        val detailResults = ArrayDeque<Result<UserReportDetail>>()
        val requestResults = ArrayDeque<Result<UserReportRequestSummary>>()
        val requestStatusResults = ArrayDeque<Result<UserReportRequestSummary>>()
        val contentResults = ArrayDeque<Result<UserReportContentCurrent>>()
        val correctionResults = ArrayDeque<Result<UserReportContentRevision>>()
        val deletionStatusResults = ArrayDeque<Result<UserReportDeletionStatus>>()
        val listBindings = mutableListOf<Pair<String?, UserReportStatus?>>()
        val requestHistoryBindings =
            mutableListOf<Triple<Int, String?, String?>>()
        val requestIntents = mutableListOf<UserReportRequestIntent>()
        val requestStatusBindings = mutableListOf<Pair<String, String>>()
        val correctionIntents = mutableListOf<UserReportCorrectionIntent>()
        val deletionStatusRequestIds = mutableListOf<String>()
        var listCancelFailure: Throwable? = null
        var listCancelCalls: Int = 0

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
                cancelBlock = {
                    listCancelCalls += 1
                    listCancelFailure?.let { throw it }
                },
            )
        }

        override fun reportDetailCall(
            session: GatewayFieldSession,
            reportId: String,
        ): CancellableNetworkCall<UserReportDetail> {
            val result = detailResults.removeFirst()
            return CancellableNetworkCall.blocking { result.getOrThrow() }
        }

        override fun requestHistoryCall(
            session: GatewayFieldSession,
            limit: Int,
            cursor: String?,
            reportId: String?,
        ): CancellableNetworkCall<UserReportRequestHistoryPage> {
            requestHistoryBindings += Triple(limit, cursor, reportId)
            val result = requestHistoryResults.removeFirst()
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

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
        const val REPORT_ID = "44444444-4444-4444-8444-444444444444"
        const val SECOND_REPORT_ID = "44444444-4444-4444-8444-444444444445"
        const val REQUEST_ID = "55555555-5555-4555-8555-555555555555"
        const val SECOND_REQUEST_ID = "55555555-5555-4555-8555-555555555556"
        const val CLIENT_REQUEST_ID = "66666666-6666-4666-8666-666666666666"
        const val SECOND_CLIENT_REQUEST_ID = "77777777-7777-4777-8777-777777777777"
        const val CORRECTION_ID = "88888888-8888-4888-8888-888888888888"
        const val SECOND_CORRECTION_ID = "99999999-9999-4999-8999-999999999999"
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
