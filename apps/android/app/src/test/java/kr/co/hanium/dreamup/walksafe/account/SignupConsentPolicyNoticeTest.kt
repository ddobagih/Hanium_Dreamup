package kr.co.hanium.dreamup.walksafe.account

import java.io.File
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountClient
import kr.co.hanium.dreamup.walksafe.network.GatewayAccountHttpException
import kr.co.hanium.dreamup.walksafe.network.GatewayHttpResponse
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionTransport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignupConsentPolicyNoticeTest {
    @Test
    fun outdatedRequiredDocumentMapsToTheUpdateGuidanceOnly() {
        assertEquals(
            SignupConsentPolicyNotice.OUTDATED_MESSAGE,
            SignupConsentPolicyNotice.messageOrNull(409, "signup_consent_outdated"),
        )
        assertTrue(SignupConsentPolicyNotice.OUTDATED_MESSAGE.contains("업데이트"))
        assertNull(SignupConsentPolicyNotice.messageOrNull(400, "signup_consent_outdated"))
        assertNull(SignupConsentPolicyNotice.messageOrNull(409, "signup_consent_invalid"))
        assertNull(SignupConsentPolicyNotice.messageOrNull(409, null))
    }

    @Test
    fun accountCreationKeepsTheDedicatedCodeAndStillSendsOnlyTheShownVersions() {
        val transport = CapturingTransport(
            GatewayHttpResponse(409, """{"detail":{"code":"signup_consent_outdated"}}"""),
        )
        val error = assertThrows(GatewayAccountHttpException::class.java) {
            GatewayAccountClient(transport, allowDebugOrigin = true).createAccount(
                "http://127.0.0.1:8081",
                "A".repeat(43),
                "012345",
                "correct horse battery staple",
                SignupConsentSelections(true, true, true, rawOriginal = true),
            )
        }
        assertEquals(409, error.statusCode)
        assertEquals("signup_consent_outdated", error.serverCode)
        val sent = JSONObject(transport.body).getJSONObject("consent")
            .getJSONObject("document_versions")
        assertEquals(SIGNUP_DOCUMENT_VERSIONS, sent.keys().asSequence().associateWith(sent::getString))
    }

    @Test
    fun mainActivityShowsTheUpdateGuidanceForTheDedicatedCode() {
        val source = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
        ).readText(Charsets.UTF_8)
        val failure = source.substringAfter("private fun postAccountFailure(")
            .substringBefore("private fun showAccountMessage(")
        val branch = failure.substringAfter("SignupConsentPolicyNotice.OUTDATED_CODE ->")
        assertTrue(failure.contains("SignupConsentPolicyNotice.OUTDATED_CODE ->"))
        assertTrue(branch.lineSequence().drop(1).first().trim().endsWith(
            "SignupConsentPolicyNotice.OUTDATED_MESSAGE",
        ))
    }

    private class CapturingTransport(private val response: GatewayHttpResponse) :
        GatewaySessionTransport {
        var body = ""
        override fun postJson(url: String, body: String): GatewayHttpResponse {
            this.body = body
            return response
        }
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("no GET")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("no DELETE")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            error("no DELETE")
    }
}
