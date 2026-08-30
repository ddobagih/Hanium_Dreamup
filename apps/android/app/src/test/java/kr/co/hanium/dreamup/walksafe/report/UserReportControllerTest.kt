package kr.co.hanium.dreamup.walksafe.report

import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.Executor
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
        requestType: UserReportRequestType = UserReportRequestType.CORRECTION,
    ) = UserReportRequestSummary(
        requestId = REQUEST_ID,
        requestType = requestType,
        status = UserReportRequestStatus.RECEIVED,
        statusVersion = 1L,
        publicResponse = null,
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

    private class FakeClient : UserReportNetworkClient {
        val listResults = ArrayDeque<Result<UserReportListPage>>()
        val detailResults = ArrayDeque<Result<UserReportDetail>>()
        val requestResults = ArrayDeque<Result<UserReportRequestSummary>>()
        val contentResults = ArrayDeque<Result<UserReportContentCurrent>>()
        val correctionResults = ArrayDeque<Result<UserReportContentRevision>>()
        val deletionStatusResults = ArrayDeque<Result<UserReportDeletionStatus>>()
        val listBindings = mutableListOf<Pair<String?, UserReportStatus?>>()
        val requestIntents = mutableListOf<UserReportRequestIntent>()
        val correctionIntents = mutableListOf<UserReportCorrectionIntent>()
        val deletionStatusRequestIds = mutableListOf<String>()

        override fun listReportsCall(
            session: GatewayFieldSession,
            limit: Int,
            cursor: String?,
            userStatus: UserReportStatus?,
        ): CancellableNetworkCall<UserReportListPage> {
            listBindings += cursor to userStatus
            val result = listResults.removeFirst()
            return CancellableNetworkCall.blocking { result.getOrThrow() }
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

    private class FakeDeletionTracker : UserReportDeletionTracker {
        private val requestIdsByBinding = mutableMapOf<Pair<String, Long>, MutableList<String>>()

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
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
        const val REPORT_ID = "44444444-4444-4444-8444-444444444444"
        const val SECOND_REPORT_ID = "44444444-4444-4444-8444-444444444445"
        const val REQUEST_ID = "55555555-5555-4555-8555-555555555555"
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
        val EXPIRES_AT_EPOCH_SECONDS = System.currentTimeMillis() / 1_000L + 3_600L
        val EXPIRES_AT_EPOCH_MS = EXPIRES_AT_EPOCH_SECONDS * 1_000L
        val SESSION_ID = "i".repeat(43)
    }
}
