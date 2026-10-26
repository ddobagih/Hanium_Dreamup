package kr.co.hanium.dreamup.walksafe.report

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.STALE_AUTHENTICATION_BODY
import kr.co.hanium.dreamup.walksafe.network.StaleAuthenticationResponseTiming
import kr.co.hanium.dreamup.walksafe.network.StaleAuthenticationTestState
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

class AndroidReportUploaderAuthenticationStaleTest {
    @After
    fun resetConsent() {
        ReportPrivacyConsentSession.resetForNewEnrollment()
    }

    @Test
    fun directDisconnectFailurePreservesAuthenticationFailure() {
        exercise(false, StaleAuthenticationResponseTiming.CURRENT, disconnectFails = true)
    }

    @Test
    fun queuedUploadDisconnectFailurePreservesAuthenticationFailure() {
        exercise(true, StaleAuthenticationResponseTiming.CURRENT, disconnectFails = true)
    }

    @Test
    fun queuedStatusDisconnectFailurePreservesAuthenticationFailure() {
        exercise(true, StaleAuthenticationResponseTiming.CURRENT, statusLookup = true,
            clearExpected = false, disconnectFails = true)
    }

    @Test
    fun directCurrentStaleResponseClearsOnce() {
        exercise(false, StaleAuthenticationResponseTiming.CURRENT)
    }

    @Test
    fun directRetainsCreationGenerationAcrossDelayedExecution() {
        exercise(false, StaleAuthenticationResponseTiming.AFTER_CREATION)
    }

    @Test
    fun directLateResponseCannotClearReplacementSession() {
        exercise(false, StaleAuthenticationResponseTiming.IN_FLIGHT_REPLACEMENT)
    }

    @Test
    fun queuedCurrentStaleResponseClearsOnce() {
        exercise(true, StaleAuthenticationResponseTiming.CURRENT)
    }

    @Test
    fun queuedRetainsCreationGenerationAcrossDelayedExecution() {
        exercise(true, StaleAuthenticationResponseTiming.AFTER_CREATION)
    }

    @Test
    fun queuedLateResponseCannotClearReplacementSession() {
        exercise(true, StaleAuthenticationResponseTiming.IN_FLIGHT_REPLACEMENT)
    }

    @Test
    fun directCancellationAfterBodyReadPreventsClear() {
        exercise(false, StaleAuthenticationResponseTiming.CANCELLED)
    }

    @Test
    fun queuedCancellationAfterBodyReadPreventsClear() {
        exercise(true, StaleAuthenticationResponseTiming.CANCELLED)
    }

    @Test
    fun queuedStatusGetIsExcluded() {
        exercise(true, StaleAuthenticationResponseTiming.CURRENT, statusLookup = true, clearExpected = false)
    }

    @Test
    fun directBusyPreservesBodyAndRetryAfter() {
        val body = """{"detail":{"code":"field_session_storage_busy"}}"""
        val error = exercise(
            false,
            StaleAuthenticationResponseTiming.CURRENT,
            statusCode = 503,
            body = body,
            clearExpected = false,
        ) as ReportUploadHttpException
        assertEquals(503, error.error.statusCode)
        assertEquals(body, error.error.errorBody)
        assertEquals(7_000L, error.error.retryAfterMs)
    }

    @Test
    fun directRootAndPaddedCodesAreNotConsumed() {
        listOf(
            """{"code":"account_authentication_stale"}""",
            """{"detail":{"code":" account_authentication_stale "}}""",
        ).forEach { body ->
            val error = exercise(
                false,
                StaleAuthenticationResponseTiming.CURRENT,
                body = body,
                clearExpected = false,
            ) as ReportUploadHttpException
            assertEquals(body, error.error.errorBody)
        }
    }

    @Test
    fun queuedBusyPreservesBodyAndRetryAfter() {
        val body = """{"detail":{"code":"field_session_storage_busy"}}"""
        val error = exercise(
            true,
            StaleAuthenticationResponseTiming.CURRENT,
            statusCode = 503,
            body = body,
            clearExpected = false,
        ) as ReportUploadHttpException
        assertEquals(503, error.error.statusCode)
        assertEquals(body, error.error.errorBody)
        assertEquals(7_000L, error.error.retryAfterMs)
    }

    private fun exercise(
        queued: Boolean,
        timing: StaleAuthenticationResponseTiming,
        statusCode: Int = 401,
        body: String = STALE_AUTHENTICATION_BODY,
        statusLookup: Boolean = false,
        clearExpected: Boolean = timing == StaleAuthenticationResponseTiming.CURRENT,
        disconnectFails: Boolean = false,
    ): Throwable {
        val origin = if (queued) {
            assumeTrue(
                "Queued transport requires the existing enabled DEBUG queue profile",
                BuildConfig.DEBUG && BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED,
            )
            requireNotNull(approvedReportQueueGatewayOriginOrNull(BuildConfig.WALKSAFE_REPORT_QUEUE_TEST_ORIGIN))
        } else {
            "https://gateway.example.test"
        }
        return StaleAuthenticationTestState().use { state ->
            val session = state.session(origin)
            var expected = state.publish(session)
            lateinit var call: CancellableNetworkCall<*>
            val connection = ErrorConnection(
                URL(origin + "/api/reports/v2"),
                statusCode,
                body,
                onResponse = {
                    if (timing == StaleAuthenticationResponseTiming.IN_FLIGHT_REPLACEMENT) {
                        expected = state.publish(state.session(origin))
                    }
                },
                disconnectFails = disconnectFails,
                onBodyClosed = {
                    if (timing == StaleAuthenticationResponseTiming.CANCELLED) call.cancel()
                },
            )
            val report = queuedReport(session.actorId)
            val binding = IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) { url ->
                assertEquals(
                    if (statusLookup) "/api/reports/v2/" + report.payload.reportId + "/status" else "/api/reports/v2",
                    url.path,
                )
                connection
            }
            val permit = uploadPermit()
            val uploader = AndroidReportUploader()
            call = when {
                statusLookup -> uploader.queuedStatusCall(
                    permit, session, consentConfirmation(), binding, report, origin,
                )
                queued -> uploader.queuedUploadCall(
                    permit, session, consentConfirmation(), binding, report, origin,
                )
                else -> uploader.uploadCall(
                    permit = permit,
                    session = session,
                    consentConfirmation = consentConfirmation(),
                    networkBinding = binding,
                    transferPurpose = ReportTransferPurpose.EXPLICIT,
                    metadataJson = """{"auto_reported":false,"trace_id":"stale-report"}""",
                    imageJpeg = byteArrayOf(1, 2, 3),
                    stableTraceId = "stale-report",
                    expectedGatewayActorId = session.actorId,
                )
            }
            if (timing == StaleAuthenticationResponseTiming.AFTER_CREATION) {
                expected = state.publish(session)
            }
            val failure = if (timing == StaleAuthenticationResponseTiming.CANCELLED) {
                assertThrows(CancellationException::class.java) { call.execute() }
            } else {
                assertThrows(ReportUploadHttpException::class.java) { call.execute() }
            }
            if (clearExpected) state.assertCleared(expected) else state.assertUnchanged(expected)
            assertEquals(1, connection.responseReads)
            assertEquals(if (statusLookup) "GET" else "POST", connection.requestMethod)
            failure
        }
    }

    private fun uploadPermit(): ReportUploadPermit {
        ReportPrivacyConsentSession.resetForNewEnrollment()
        check(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
        return requireNotNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.EXPLICIT))
    }

    private fun queuedReport(actorId: String): QueuedReport = QueuedReport(
        payload = requireNotNull(FrozenReportPayload.freeze(
            "123e4567-e89b-42d3-a456-426614174010",
            "{}".toByteArray(Charsets.UTF_8),
            reportTestJpeg(),
        )),
        priority = ReportQueuePriority.EXPLICIT,
        reporterActorId = actorId,
        walkSessionId = "123e4567-e89b-42d3-a456-426614174011",
        consentReceiptSha256 = "a".repeat(64),
        createdAtEpochMs = 1_000L,
        expiresAtEpochMs = 1_000L + REPORT_QUEUE_TTL_MS,
    )

    private fun consentConfirmation() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
        requestId = "integrated_consent_request_0001",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = IntegratedConsentSelections(),
        confirmedAt = "2026-07-25T12:00:00.000Z",
        gatewayAuditRecordSha256 = "9".repeat(64),
        backendConsentReceiptSha256 = "a".repeat(64),
        controlSecret = "b".repeat(64),
    )

    /** Bound fake connection: no configured origin is contacted. */
    private class ErrorConnection(
        url: URL,
        private val status: Int,
        body: String,
        private val onResponse: () -> Unit,
        private val onBodyClosed: () -> Unit,
        private val disconnectFails: Boolean,
    ) : HttpURLConnection(url) {
        private val bytes = body.toByteArray(Charsets.UTF_8)
        private val closedBody = AtomicBoolean(false)
        private val output = ByteArrayOutputStream()
        var responseReads = 0
            private set

        override fun connect() = Unit
        override fun disconnect() {
            if (disconnectFails) throw IllegalStateException("synthetic disconnect failure")
        }
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): OutputStream = output
        override fun getResponseCode(): Int {
            responseReads += 1
            onResponse()
            return status
        }
        override fun getContentLengthLong(): Long = bytes.size.toLong()
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Retry-After", ignoreCase = true)) "7" else null

        override fun getErrorStream(): InputStream = object : ByteArrayInputStream(bytes) {
            override fun close() {
                super.close()
                if (closedBody.compareAndSet(false, true)) onBodyClosed()
            }
        }
    }
}
