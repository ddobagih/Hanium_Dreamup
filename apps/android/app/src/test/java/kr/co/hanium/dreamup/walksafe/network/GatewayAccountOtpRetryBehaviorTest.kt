package kr.co.hanium.dreamup.walksafe.network

import java.net.SocketTimeoutException
import java.util.ArrayDeque
import kr.co.hanium.dreamup.walksafe.account.AccountRemoteAction
import kr.co.hanium.dreamup.walksafe.account.AccountRequestFence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAccountOtpRetryBehaviorTest {
    @Test
    fun timeoutThenPendingWaitsForExplicitRetryWithTheSameWireIdUntil202() {
        val harness = Harness()
        val transport = OtpTransport(
            SocketTimeoutException("synthetic timeout"),
            pending("17"),
            success(),
        )
        val client = GatewayAccountClient(transport)
        val first = requireNotNull(harness.policy.begin(PAYLOAD))
        assertThrows(SocketTimeoutException::class.java) { send(client, first) }
        assertTrue(harness.policy.recordFailure(first, PAYLOAD, 0, null, null))

        val second = requireNotNull(harness.policy.begin(PAYLOAD))
        assertNotEquals(first, second)
        assertEquals(first.requestId, second.requestId)
        val error = assertThrows(GatewayAccountHttpException::class.java) { send(client, second) }
        assertEquals(409, error.statusCode)
        assertEquals("account_enrollment_in_progress", error.serverCode)
        assertEquals(17_000L, error.retryAfterMs)
        assertTrue(harness.policy.recordFailure(
            second, PAYLOAD, error.statusCode, error.serverCode, error.retryAfterMs,
        ))
        assertEquals(17_000L, harness.policy.remainingWaitMs(PAYLOAD))
        assertNull(harness.policy.begin(PAYLOAD))
        harness.nowMs += 16_999L
        assertNull(harness.policy.begin(PAYLOAD))
        assertEquals(2, transport.bodies.size)

        harness.nowMs += 1L
        val third = requireNotNull(harness.policy.begin(PAYLOAD))
        assertEquals(first.requestId, third.requestId)
        val result = send(client, third)
        assertEquals(HANDLE, result.enrollmentHandle)
        assertTrue(harness.policy.recordSuccess(third, PAYLOAD))
        assertEquals(3, transport.bodies.size)
        assertEquals(1, harness.generatedIds)
        assertEquals(1, transport.bodies.distinct().size)
        assertEquals(
            setOf("schema_version", "email", "date_of_birth", "request_id"),
            keys(JSONObject(transport.bodies.singleOrNull() ?: transport.bodies.first())),
        )
    }

    @Test
    fun pendingResponseNeverAutomaticallyPostsTheQueuedSuccessResponse() {
        val transport = OtpTransport(pending("30"), success())
        val client = GatewayAccountClient(transport)
        val error = assertThrows(GatewayAccountHttpException::class.java) {
            client.requestEmailOtp(
                PAYLOAD.gatewayBaseUrl, PAYLOAD.email, PAYLOAD.dateOfBirth, "synthetic_request_1",
            )
        }

        assertEquals(30_000L, error.retryAfterMs)
        assertEquals(1, transport.bodies.size)
        assertFalse(error.message.orEmpty().contains(PAYLOAD.email))
        assertFalse(error.message.orEmpty().contains(PAYLOAD.dateOfBirth))
    }

    @Test
    fun changedWirePayloadOrSignupBindingUsesANewIdAndRejectsOldCallbacks() {
        val variants = listOf(
            PAYLOAD.copy(email = "other@example.com"),
            PAYLOAD.copy(dateOfBirth = "2001-01-02"),
            PAYLOAD.copy(gatewayBaseUrl = "https://other-gateway.example"),
            PAYLOAD.copy(ownerBindingSha256 = "other-owner-binding"),
            PAYLOAD.copy(signupAttempt = "new-signup-epoch"),
        )
        variants.forEach { changed ->
            val harness = Harness()
            val old = requireNotNull(harness.policy.begin(PAYLOAD))
            val current = requireNotNull(harness.policy.begin(changed))
            assertNotEquals(old.requestId, current.requestId)
            assertFalse(harness.policy.recordSuccess(old, PAYLOAD))
            assertFalse(harness.policy.recordFailure(old, changed, 409, PENDING_CODE, 300_000L))
            assertEquals(0L, harness.policy.remainingWaitMs(changed))
            assertTrue(harness.policy.recordSuccess(current, changed))
        }
    }

    @Test
    fun cancellingTheSignupAttemptDropsItsWaitAndFencesLateSuccessAndFailure() {
        val harness = Harness()
        val cancelled = requireNotNull(harness.policy.begin(PAYLOAD))
        assertTrue(harness.policy.recordFailure(cancelled, PAYLOAD, 409, PENDING_CODE, 17_000L))
        harness.policy.cancel()

        val replacement = requireNotNull(harness.policy.begin(PAYLOAD))
        assertNotEquals(cancelled.requestId, replacement.requestId)
        assertFalse(harness.policy.recordSuccess(cancelled, PAYLOAD))
        assertFalse(harness.policy.recordFailure(cancelled, PAYLOAD, 409, PENDING_CODE, 300_000L))
        assertTrue(harness.policy.recordFailure(replacement, PAYLOAD, 409, PENDING_CODE, 17_000L))
        assertEquals(17_000L, harness.policy.remainingWaitMs(PAYLOAD))
    }

    @Test
    fun changedInputsBeforeCompletionRejectTheOldResponseAndDoNotRemainBusy() {
        val harness = Harness()
        val old = requireNotNull(harness.policy.begin(PAYLOAD))
        val changed = PAYLOAD.copy(dateOfBirth = "2002-02-02")

        assertFalse(harness.policy.recordSuccess(old, changed))
        val current = requireNotNull(harness.policy.begin(changed))
        assertNotEquals(old.requestId, current.requestId)
        assertTrue(harness.policy.recordSuccess(current, changed))
    }

    @Test
    fun retryAfterKeepsTheExistingAndroid300SecondBoundAndRejectsUntrustedTiming() {
        for (seconds in listOf("1", "17", "300")) {
            val transport = OtpTransport(pending(seconds, "rEtRy-AfTeR"))
            val error = failure(GatewayAccountClient(transport))
            assertEquals(seconds.toLong() * 1_000L, error.retryAfterMs)
            assertEquals(1, transport.bodies.size)
        }
        for (invalid in listOf("0", "-1", "1.5", "301", "1000000", "9223372036854775808",
            "Sun, 06 Sep 2026 03:00:00 GMT")) {
            val error = failure(GatewayAccountClient(OtpTransport(pending(invalid))))
            assertNull(error.retryAfterMs)
            val harness = Harness()
            val attempt = requireNotNull(harness.policy.begin(PAYLOAD))
            assertTrue(harness.policy.recordFailure(
                attempt, PAYLOAD, error.statusCode, error.serverCode, error.retryAfterMs,
            ))
            assertEquals(1_000L, harness.policy.remainingWaitMs(PAYLOAD))
        }
    }

    @Test
    fun terminalIdempotencyConflictIsNotTreatedAsPendingOrAutomaticallyReplayed() {
        val transport = OtpTransport(GatewayHttpResponse(
            409,
            """{"detail":{"code":"account_enrollment_idempotency_conflict"}}""",
            mapOf("Retry-After" to "17"),
        ))
        val harness = Harness()
        val attempt = requireNotNull(harness.policy.begin(PAYLOAD))
        val error = assertThrows(GatewayAccountHttpException::class.java) {
            send(GatewayAccountClient(transport), attempt)
        }
        assertTrue(harness.policy.recordFailure(
            attempt, PAYLOAD, error.statusCode, error.serverCode, error.retryAfterMs,
        ))

        assertEquals(0L, harness.policy.remainingWaitMs(PAYLOAD))
        assertEquals(1, transport.bodies.size)
        val explicitNext = requireNotNull(harness.policy.begin(PAYLOAD))
        assertNotEquals(attempt.requestId, explicitNext.requestId)
    }

    @Test
    fun clientWireEmailAndBirthDateAreNotRewrittenByTheRetryPolicy() {
        val payload = PAYLOAD.copy(email = "User@EXAMPLE.com")
        val harness = Harness()
        val attempt = requireNotNull(harness.policy.begin(payload))
        val transport = OtpTransport(success())

        assertNotNull(send(GatewayAccountClient(transport), attempt))

        val body = JSONObject(transport.bodies.single())
        assertEquals(payload.email, body.getString("email"))
        assertEquals(payload.dateOfBirth, body.getString("date_of_birth"))
        assertFalse(payload.toString().contains(payload.email))
        assertFalse(attempt.toString().contains(payload.email))
    }

    @Test
    fun backgroundedOtpCallbackCannotBlockTheNextExplicitSameIdRetry() {
        val harness = Harness()
        val fence = AccountRequestFence().apply { enteredForeground() }
        val oldToken = requireNotNull(fence.begin(AccountRemoteAction.REQUEST_EMAIL_OTP, "signup-binding"))
        val oldAttempt = requireNotNull(harness.policy.begin(PAYLOAD))
        val transport = OtpTransport(success(), success())
        val client = GatewayAccountClient(transport)
        val lateResponse = send(client, oldAttempt)

        fence.enteredBackground()
        harness.policy.suspendForLifecycle()
        fence.enteredForeground()

        val currentToken = requireNotNull(fence.begin(
            AccountRemoteAction.REQUEST_EMAIL_OTP, "signup-binding",
        ))
        val currentAttempt = requireNotNull(harness.policy.begin(PAYLOAD))
        assertFalse(fence.completeIfCurrent(oldToken, "signup-binding"))
        assertFalse(harness.policy.recordSuccess(oldAttempt, PAYLOAD))
        assertFalse(harness.policy.recordFailure(oldAttempt, PAYLOAD, 409, PENDING_CODE, 300_000L))
        assertNotEquals(oldAttempt, currentAttempt)
        assertEquals(oldAttempt.requestId, currentAttempt.requestId)

        val currentResponse = send(client, currentAttempt)

        assertEquals(lateResponse.enrollmentHandle, currentResponse.enrollmentHandle)
        assertTrue(fence.completeIfCurrent(currentToken, "signup-binding"))
        assertTrue(harness.policy.recordSuccess(currentAttempt, PAYLOAD))
        assertEquals(2, transport.bodies.size)
        assertEquals(1, transport.bodies.distinct().size)
        assertEquals(1, harness.generatedIds)
    }

    @Test
    fun backgroundingPreservesPayloadWireIdAndTheUnelapsedServerBackoff() {
        val harness = Harness()
        val first = requireNotNull(harness.policy.begin(PAYLOAD))
        assertTrue(harness.policy.recordFailure(first, PAYLOAD, 409, PENDING_CODE, 17_000L))

        harness.policy.suspendForLifecycle()
        harness.nowMs += 16_000L

        assertEquals(1_000L, harness.policy.remainingWaitMs(PAYLOAD))
        assertNull(harness.policy.begin(PAYLOAD))
        harness.nowMs += 1_000L
        val retry = requireNotNull(harness.policy.begin(PAYLOAD))
        assertEquals(PAYLOAD, retry.payload)
        assertEquals(first.requestId, retry.requestId)
        assertNotEquals(first, retry)
        assertEquals(1, harness.generatedIds)
    }

    private class Harness {
        var nowMs = 1_000L
        var generatedIds = 0
        val policy = EmailOtpRequestRetryPolicy(
            elapsedRealtimeMs = { nowMs },
            createRequestId = { "synthetic_request_" + (++generatedIds).toString() },
        )
    }

    private class OtpTransport(vararg replies: Any) : GatewaySessionTransport {
        private val replies = ArrayDeque(replies.toList())
        val bodies = mutableListOf<String>()

        override fun postJson(url: String, body: String): GatewayHttpResponse {
            assertEquals("https://gateway.example/api/account-enrollments/email-otp", url)
            bodies += body
            return when (val reply = replies.removeFirst()) {
                is GatewayHttpResponse -> reply
                is Exception -> throw reply
                else -> error("unsupported synthetic response")
            }
        }

        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("OTP requests must not perform a session GET")

        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("OTP retries must not delete a session")

        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            error("OTP retries must not delete an account")
    }

    private fun send(
        client: GatewayAccountClient,
        attempt: EmailOtpRequestRetryPolicy.Attempt,
    ): EmailOtpEnrollmentResponse = client.requestEmailOtp(
        attempt.payload.gatewayBaseUrl,
        attempt.payload.email,
        attempt.payload.dateOfBirth,
        attempt.requestId,
    )

    private fun failure(client: GatewayAccountClient): GatewayAccountHttpException =
        assertThrows(GatewayAccountHttpException::class.java) {
            client.requestEmailOtp(
                PAYLOAD.gatewayBaseUrl, PAYLOAD.email, PAYLOAD.dateOfBirth, "synthetic_request_1",
            )
        }

    private fun keys(value: JSONObject): Set<String> = buildSet {
        val iterator = value.keys()
        while (iterator.hasNext()) add(iterator.next())
    }

    private companion object {
        const val PENDING_CODE = "account_enrollment_in_progress"
        val HANDLE = "A".repeat(43)
        val PAYLOAD = EmailOtpRequestRetryPolicy.Payload(
            gatewayBaseUrl = "https://gateway.example",
            ownerBindingSha256 = "synthetic-owner-binding",
            signupAttempt = "synthetic-signup-epoch",
            email = "person@example.com",
            dateOfBirth = "2000-02-29",
        )

        fun pending(seconds: String, header: String = "Retry-After") = GatewayHttpResponse(
            409,
            """{"detail":{"code":"account_enrollment_in_progress"}}""",
            mapOf(header to seconds),
        )

        fun success() = GatewayHttpResponse(
            202,
            """{"schema_version":"walksafe.account-enrollment-email-otp-response.v1","enrollment_handle":"$HANDLE","expires_at":"2026-09-06T03:10:00Z","resend_available_at":"2026-09-06T03:01:00Z"}""",
        )
    }
}
