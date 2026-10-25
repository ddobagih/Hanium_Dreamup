package kr.co.hanium.dreamup.walksafe.network

import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAccountDateOfBirthTest {
    @Test
    fun compactAndIsoDatesNormalizeToTheSameCanonicalDate() {
        for (date in listOf("2002-01-01", "2000-02-29", "2004-02-29", "1900-02-28", "0001-01-01")) {
            for (input in listOf(date, date.replace("-", ""))) {
                assertEquals(input, date, GatewayAccountInputPolicy.normalizedDateOfBirth(input))
                assertTrue(input, GatewayAccountInputPolicy.validDateOfBirth(input))
            }
        }
    }

    @Test
    fun impossibleCalendarDatesAndYearZeroAreRejectedInBothFormats() {
        for (date in listOf(
            "2001-02-29", "1900-02-29", "2000-02-30", "2002-04-31", "2002-00-01",
            "2002-13-01", "2002-01-00", "2002-01-32", "0000-01-01",
        )) {
            for (input in listOf(date, date.replace("-", ""))) {
                assertNull(input, GatewayAccountInputPolicy.normalizedDateOfBirth(input))
                assertFalse(input, GatewayAccountInputPolicy.validDateOfBirth(input))
            }
        }
    }

    @Test
    fun malformedDatesAreNotTrimmedOrLooselyParsed() {
        for (input in listOf(
            "", "2002011", "200201011", "2002-1-01", "2002-01-1", "02-01-01",
            "2002/01/01", "2002.01.01", "2002 01 01", "2002-0101", "200201-01",
            " 20020101", "20020101 ", " 2002-01-01", "2002-01-01\n",
            "2002-01-01T00:00:00Z", "+2002-01-01", "-001-01-01", "２００２０１０１",
        )) {
            assertNull(input, GatewayAccountInputPolicy.normalizedDateOfBirth(input))
            assertFalse(input, GatewayAccountInputPolicy.validDateOfBirth(input))
        }
    }

    @Test
    fun futureDatesAreRejectedInBothFormats() {
        val tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()
        for (date in listOf(tomorrow, "9999-12-31")) {
            for (input in listOf(date, date.replace("-", ""))) {
                assertNull(input, GatewayAccountInputPolicy.normalizedDateOfBirth(input))
                assertFalse(input, GatewayAccountInputPolicy.validDateOfBirth(input))
            }
        }
    }

    @Test
    fun minimumAgeRemainsAServerDecision() {
        val recentDate = LocalDate.now(ZoneId.of("Asia/Seoul")).minusYears(1).toString()
        assertEquals(
            recentDate,
            GatewayAccountInputPolicy.normalizedDateOfBirth(recentDate.replace("-", "")),
        )
        val transport = OtpTransport(GatewayHttpResponse(
            400, """{"detail":{"code":"account_enrollment_not_allowed"}}""",
        ))
        val error = assertThrows(GatewayAccountHttpException::class.java) {
            client(transport).requestEmailOtp(
                ORIGIN, EMAIL, recentDate.replace("-", ""), REQUEST_ID,
            )
        }
        assertEquals(recentDate, JSONObject(transport.bodies.single()).getString("date_of_birth"))
        assertEquals(400, error.statusCode)
        assertEquals("account_enrollment_not_allowed", error.serverCode)
    }

    @Test
    fun compactAndIsoRequestsSendIdenticalCanonicalWireBodies() {
        for (date in listOf("2002-01-01", "2000-02-29")) {
            val transport = OtpTransport(SUCCESS)
            for (input in listOf(date.replace("-", ""), date)) {
                val result = client(transport).requestEmailOtp(ORIGIN, EMAIL, input, REQUEST_ID)
                assertEquals(HANDLE, result.enrollmentHandle)
            }
            assertEquals(2, transport.bodies.size)
            assertEquals(1, transport.bodies.distinct().size)
            val body = JSONObject(transport.bodies.first())
            assertEquals(
                setOf("schema_version", "email", "date_of_birth", "request_id"),
                buildSet<String> { body.keys().forEach { add(it) } },
            )
            assertEquals("walksafe.account-enrollment-email-otp.v1", body.getString("schema_version"))
            assertEquals(date, body.getString("date_of_birth"))
            assertEquals(EMAIL, body.getString("email"))
            assertEquals(REQUEST_ID, body.getString("request_id"))
        }
    }

    @Test
    fun invalidDatesFailBeforeTransportWithoutLeakingInput() {
        val tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()
        val transport = OtpTransport(SUCCESS)
        for (input in listOf(
            "20010229", "2001-02-29", "20020431", "2002-04-31", "00000101",
            " 20020101", "2002011", tomorrow, tomorrow.replace("-", ""),
        )) {
            val error = assertThrows(GatewayAccountHttpException::class.java) {
                client(transport).requestEmailOtp(ORIGIN, EMAIL, input, REQUEST_ID)
            }
            assertEquals("invalid_account_enrollment_input", error.reason)
            assertEquals(0, error.statusCode)
            assertFalse(error.message.orEmpty().contains(input))
            assertFalse(error.message.orEmpty().contains(EMAIL))
        }
        assertTrue(transport.bodies.isEmpty())
    }

    private fun client(transport: GatewaySessionTransport) = GatewayAccountClient(
        transport,
        approvedReleaseOrigin = ORIGIN,
        allowDebugOrigin = false,
        clockEpochMs = { 1_788_663_600_000L }, // 2026-09-06T03:00:00Z
    )

    private class OtpTransport(private val response: GatewayHttpResponse) : GatewaySessionTransport {
        val bodies = mutableListOf<String>()

        override fun postJson(url: String, body: String): GatewayHttpResponse {
            assertEquals("$ORIGIN/api/account-enrollments/email-otp", url)
            bodies += body
            return response
        }

        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("OTP requests must only POST")

        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("OTP requests must only POST")

        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            error("OTP requests must only POST")
    }

    private companion object {
        const val ORIGIN = "https://gateway.example"
        const val EMAIL = "person@example.com"
        const val REQUEST_ID = "request_1234567890"
        val HANDLE = "A".repeat(43)
        val SUCCESS = GatewayHttpResponse(
            202,
            """{"schema_version":"walksafe.account-enrollment-email-otp-response.v1","enrollment_handle":"$HANDLE","expires_at":"2026-09-06T03:10:00Z","resend_available_at":"2026-09-06T03:01:00Z"}""",
        )
    }
}
