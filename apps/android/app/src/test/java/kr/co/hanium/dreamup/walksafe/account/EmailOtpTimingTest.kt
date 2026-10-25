package kr.co.hanium.dreamup.walksafe.account

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource

import java.io.File
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountClient
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountHttpException
import kr.co.hanium.dreamup.walksafe.network.GatewayHttpResponse
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailOtpTimingTest {
    @Test
    fun responseBoundsUseTheServerTtlCapAndClockSkew() {
        val now = NOW
        val max = EmailOtpResponseBounds.MAX_TTL_MS
        val skew = EmailOtpResponseBounds.CLOCK_SKEW_MS
        assertTrue(EmailOtpResponseBounds.accepts(now, now + 600_000L, now + 60_000L))
        assertTrue(EmailOtpResponseBounds.accepts(now, now + max + skew, now + max + skew))
        assertTrue(EmailOtpResponseBounds.accepts(now, now - skew + 1L, now - skew))
        assertFalse("already expired", EmailOtpResponseBounds.accepts(now, now - skew, now - skew))
        assertFalse("far future", EmailOtpResponseBounds.accepts(now, now + max + skew + 1L, now))
        assertFalse(
            "resend after expiry",
            EmailOtpResponseBounds.accepts(now, now + 600_000L, now + 600_001L),
        )
        assertFalse("clock unset", EmailOtpResponseBounds.accepts(0L, 600_000L, 1L))
    }

    @Test
    fun clientRejectsExpiredFarFutureAndResendAfterExpiryResponses() {
        fun response(expires: String, resend: String) = GatewayHttpResponse(
            202,
            """{"schema_version":"walksafe.account-enrollment-email-otp-response.v1","enrollment_handle":"${"A".repeat(43)}","expires_at":"$expires","resend_available_at":"$resend"}""",
        )
        // NOW = 2026-09-06T03:00:00Z
        val accepted = request(response("2026-09-06T03:10:00Z", "2026-09-06T03:01:00Z"))
        assertEquals(NOW + 600_000L, accepted.expiresAtEpochMs)
        for (bad in listOf(
            response("2026-09-06T02:58:00Z", "2026-09-06T02:57:00Z"),
            response("2026-09-06T03:32:01Z", "2026-09-06T03:01:00Z"),
            response("2026-09-06T03:10:00Z", "2026-09-06T03:10:01Z"),
            response("2027-09-06T03:10:00Z", "2026-09-06T03:01:00Z"),
        )) {
            val error = assertThrows(GatewayAccountHttpException::class.java) { request(bad) }
            assertEquals("invalid_email_otp_response", error.reason)
        }
    }

    @Test
    fun partialRequiresResendNoLaterThanExpiryAndCodecDropsViolations() {
        val partial = partial(expires = 2_000L, resend = 2_000L)
        assertEquals(partial, EmailEnrollmentPartialCodec.decode(EmailEnrollmentPartialCodec.encode(partial)))
        assertThrows(IllegalArgumentException::class.java) { partial(expires = 2_000L, resend = 2_001L) }
        val tampered = EmailEnrollmentPartialCodec.encode(partial)
            .replace("\"resend_available_at_epoch_ms\":2000", "\"resend_available_at_epoch_ms\":2001")
        assertTrue(tampered.contains("2001"))
        assertEquals(null, EmailEnrollmentPartialCodec.decode(tampered))
    }

    @Test
    fun deadlineIgnoresDeviceWallClockChanges() {
        val deadline = EmailOtpDeadline.received(
            expiresAtEpochMs = NOW + 600_000L,
            receivedAtEpochMs = NOW,
            receivedAtElapsedMs = 50_000L,
        )
        // The wall clock is not an input: only elapsed realtime since receipt counts.
        assertEquals(600_000L, deadline.remainingMs(50_000L))
        assertEquals(1_000L, deadline.remainingMs(649_000L))
        assertTrue(deadline.isOpenAt(649_999L))
        assertFalse(deadline.isOpenAt(650_000L))
        assertEquals(0L, deadline.remainingMs(49_999L))
        val capped = EmailOtpDeadline.received(NOW + 86_400_000L, NOW, 0L)
        assertEquals(
            EmailOtpResponseBounds.MAX_TTL_MS + EmailOtpResponseBounds.CLOCK_SKEW_MS,
            capped.remainingMs(0L),
        )
    }

    @Test
    fun mainActivityUsesTheElapsedDeadlineBeforeCreatingTheAccount() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")
            .readText(Charsets.UTF_8)
        val create = MainActivityRefactorSource.function(source, "private fun createEmailAccount()")
        assertTrue(create.contains("!emailOtpPartialStillValid(partial, ownerBinding)"))
        assertFalse(create.contains("!partial.isValidAt(System.currentTimeMillis(), ownerBinding)"))
        val helper = MainActivityRefactorSource.function(source, "private fun emailOtpPartialStillValid(")
        assertTrue(helper.contains("deadline.isOpenAt(elapsedRealtimeMs())"))
        assertTrue(MainActivityRefactorSource.member(source, "createEmailAccountController").contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        val request = MainActivityRefactorSource.function(source, "private fun requestEmailAccountOtp()")
        assertTrue(request.contains("emailOtpDeadline = kr.co.hanium.dreamup.walksafe.account.EmailOtpDeadline.received("))
    }

    private fun request(response: GatewayHttpResponse) = GatewayAccountClient(
        FixedTransport(response),
        allowDebugOrigin = true,
        clockEpochMs = { NOW },
    ).requestEmailOtp("http://127.0.0.1:8081", "person@example.com", "2000-01-01", "request_1234567890")

    private fun partial(expires: Long, resend: Long) = EmailEnrollmentPartial(
        enrollmentHandle = "A".repeat(43),
        expiresAtEpochMs = expires,
        resendAvailableAtEpochMs = resend,
        ownerBindingSha256 = "a".repeat(64),
        selections = SignupConsentSelections(true, true, true),
    )

    private class FixedTransport(private val response: GatewayHttpResponse) : GatewaySessionTransport {
        override fun postJson(url: String, body: String): GatewayHttpResponse = response
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse = error("no GET")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse = error("no DELETE")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse = error("no DELETE")
    }

    private companion object {
        const val NOW = 1_788_663_600_000L
    }
}
