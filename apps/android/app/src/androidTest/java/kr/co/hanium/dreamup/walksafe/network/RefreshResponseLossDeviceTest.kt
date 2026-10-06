package kr.co.hanium.dreamup.walksafe.network

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual device HTTP and local Gateway; drop only this test session's accepted refresh response. */
class RefreshResponseLossDeviceTest {
    @Test fun lostRotationResponseCannotReuseProofAndFreshLoginRemainsPossible() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowFixtureRefreshLoss") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = File(instrumentation.targetContext.cacheDir, "device-audit-account.json")
        val account = JSONObject(fixture.readText())
        require(account.getString("email").startsWith("codex-device-") && account.getString("email").endsWith("@example.com"))
        val real = HttpUrlConnectionGatewaySessionTransport()
        var refreshCount = 0
        var acceptedResponse: GatewayHttpResponse? = null
        val faulty = object : GatewaySessionTransport by real {
            override fun postJson(url: String, body: String): GatewayHttpResponse {
                val response = real.postJson(url, body)
                if (JSONObject(body).optString("grant_type") == "refresh_token") {
                    refreshCount++
                    assertEquals(200, response.statusCode)
                    acceptedResponse = response
                    throw IOException("synthetic response loss after server commit")
                }
                return response
            }
        }
        val client = GatewayFieldSessionClient(faulty)
        val deviceId = GatewayCredentialPolicy.newDeviceId()
        fun login() = client.loginWithPassword("http://127.0.0.1:8081", account.getString("email"),
            account.getString("password"), true, deviceId, enableLongLivedSession = true)
        var first: GatewayFieldSession? = null
        var fresh: GatewayFieldSession? = null
        try {
            first = login()
            assertTrue(first.isLongLived)
            val failure = assertThrows(GatewaySessionHttpException::class.java) { client.renew(first, first.actorId) }
            assertEquals(GatewaySessionNextAction.OUTCOME_UNKNOWN, failure.nextAction)
            assertFalse(first.isUsableFor(first.actorId))
            assertThrows(GatewaySessionHttpException::class.java) { client.renew(first, first.actorId) }
            assertEquals("The already-used proof must never be sent again", 1, refreshCount)
            fresh = login()
            assertTrue(fresh.isUsableFor(fresh.actorId))
            assertNotEquals(first.familyId, fresh.familyId)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "REFRESH_LOSS server_rotated=true response_dropped=true old_proof_resend=0 fresh_password_login=true main_ui_recovery=NOT_TESTED\n") })
        } finally {
            // Revoke the rotated family with its current proof retained only in test memory.
            acceptedResponse?.let { response ->
                val rotated = JSONObject(response.responseBody)
                val body = JSONObject().put("grant_type", "refresh_token").put("actor_id", first?.actorId)
                    .put("device_id", deviceId).put("family_id", rotated.getString("family_id"))
                    .put("rotation", rotated.getLong("rotation")).put("refresh_token", rotated.getString("refresh_token"))
                assertEquals(204, real.deleteJson("http://127.0.0.1:8081/api/field-session", body.toString()).statusCode)
            }
            fresh?.let(client::logout)
            fixture.delete()
        }
    }
}
