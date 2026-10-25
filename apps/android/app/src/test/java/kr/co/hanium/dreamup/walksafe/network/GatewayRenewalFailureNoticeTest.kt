package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import java.io.File
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S01-21: a lost refresh response is not recovered with the old refresh value (reuse is refused);
 * the user is told to log in again instead of being logged out silently.
 */
class GatewayRenewalFailureNoticeTest {
    @Before
    fun resetProofFence() = resetGatewayRefreshProofFenceForTests()

    @Test
    fun transportTimeoutDuringRefreshAsksForReloginWithoutReplay() {
        val session = session("r")
        val transport = ScriptedTransport { throw SocketTimeoutException("synthetic response timeout") }
        val client = client(transport)
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(session, ACTOR, NOW)
        }
        val notice = GatewayRenewalFailureNotice.of(error)
        assertEquals(GatewayRenewalFailureNotice.OUTCOME_UNKNOWN, notice)
        assertEquals("gateway=refresh_outcome_unknown reauthentication_required", notice!!.statusToken)
        assertEquals(
            "연결이 끊겨 로그인 확인 결과를 알 수 없습니다. 안전을 위해 다시 로그인해 주세요.",
            notice.speech,
        )
        // No result recovery: the same one-use refresh value is never sent a second time.
        assertThrows(GatewaySessionHttpException::class.java) { client.renew(session, ACTOR, NOW) }
        assertEquals(1, transport.posts)
    }

    @Test
    fun serverStorageOutcomeUnknownAlsoAsksForRelogin() {
        val transport = ScriptedTransport {
            GatewayHttpResponse(500, """{"code":"field_session_storage_outcome_unknown"}""")
        }
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            client(transport).renew(session("s"), ACTOR, NOW)
        }
        assertEquals("field_session_storage_outcome_unknown", error.serverCode)
        assertEquals(GatewayRenewalFailureNotice.OUTCOME_UNKNOWN, GatewayRenewalFailureNotice.of(error))
    }

    @Test
    fun reuseDetectionTellsEveryDeviceWhyAllDevicesWereLoggedOut() {
        // Owner decision 1: the reusing device and every other device get the same reason.
        for ((code, tokenChar) in listOf(
            "refresh_token_reuse_detected" to "t",
            "field_session_revoked_reuse_detected" to "w",
        )) {
            val transport = ScriptedTransport {
                GatewayHttpResponse(401, """{"code":"$code","reauthentication_required":true}""")
            }
            val error = assertThrows(GatewaySessionHttpException::class.java) {
                client(transport).renew(session(tokenChar), ACTOR, NOW)
            }
            assertEquals(code, error.serverCode)
            val notice = GatewayRenewalFailureNotice.of(error)
            assertEquals(code, GatewayRenewalFailureNotice.REUSE_DETECTED, notice)
            assertEquals(
                "gateway=refresh_reuse_detected all_devices_revoked reauthentication_required",
                notice!!.statusToken,
            )
            assertEquals(
                "이전 로그인 정보가 다시 사용되어 보안을 위해 모든 기기에서 로그아웃했습니다. 다시 로그인해 주세요.",
                notice.speech,
            )
            // The session is closed; the same refresh value is never sent again.
            assertEquals(1, transport.posts)
        }
        // A plain revocation (logout elsewhere, account deletion) keeps the generic path.
        assertNull(
            GatewayRenewalFailureNotice.of(
                GatewaySessionHttpException(401, "gateway_refresh_failed", serverCode = "field_session_revoked"),
            ),
        )
    }

    @Test
    fun confirmedUnappliedBusyRetryAndDuplicateAttemptStaySilent() {
        val busy = ScriptedTransport {
            GatewayHttpResponse(
                503,
                """{"code":"field_session_storage_busy","retryable":true}""",
                mapOf("Retry-After" to "1"),
            )
        }
        val busyError = assertThrows(GatewaySessionHttpException::class.java) {
            client(busy).renew(session("u"), ACTOR, NOW)
        }
        assertNull(GatewayRenewalFailureNotice.of(busyError))
        assertNull(
            GatewayRenewalFailureNotice.of(
                GatewaySessionHttpException(0, "gateway_refresh_proof_already_attempted"),
            ),
        )
        assertNull(GatewayRenewalFailureNotice.of(IllegalStateException("unrelated")))
    }

    @Test
    fun mainRenewalFailureSpeaksTheNoticeForEveryReloginOutcome() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val body = MainActivityRefactorSource.function(source, "fun renewRestoredGatewaySession(")
        assertTrue(body.contains("GatewayRenewalFailureNotice.of(error)?.let { notice ->"))
        assertTrue(body.contains("ui.status(notice.statusToken)"))
        assertTrue(body.contains("speakInteraction(notice.speech)"))
        // Owner decision 1: the reason stays visible on the login screen (TalkBack-readable text).
        assertTrue(body.contains("ui.setAccountAccessNotice(notice.speech)"))
        assertTrue(body.contains("ui.refreshFirstRun()"))
        assertTrue(
            "the notice must follow abandonRenewal, not the silent busy-retry branch",
            body.indexOf("GatewayRenewalFailureNotice.of(error)") >
                body.indexOf("store.abandonRenewal("),
        )
    }

    private fun client(transport: GatewaySessionTransport) = GatewayFieldSessionClient(
        transport = transport,
        approvedAccountOrigin = ORIGIN,
        allowDebugAccountOrigin = false,
    )

    private fun session(tokenChar: String) = GatewayFieldSession.longLivedSession(
        gatewayBaseUrl = ORIGIN,
        actorId = ACTOR,
        deviceId = "device-installation-notice-01",
        familyId = tokenChar.repeat(32),
        rotation = 0L,
        cookiePair = "walksafe_field_session=access-token-notice-${tokenChar.repeat(24)}",
        refreshToken = tokenChar.repeat(64),
        accessExpiresAtEpochMs = NOW + 60_000L,
        idleExpiresAtEpochMs = NOW + 120_000L,
        absoluteExpiresAtEpochMs = NOW + 240_000L,
    )

    private class ScriptedTransport(private val onRefresh: () -> GatewayHttpResponse) : GatewaySessionTransport {
        var posts = 0
        override fun postJson(url: String, body: String): GatewayHttpResponse {
            posts += 1
            return onRefresh()
        }
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("refresh must not fall back to GET")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("client does not revoke by itself")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            error("client does not revoke by itself")
    }

    private companion object {
        const val ORIGIN = "https://field.example"
        const val ACTOR = "tester-01"
        const val NOW = 1_000L
    }
}
