package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.network.*
import org.junit.Assert.*
import org.junit.Test

/** Exercise generated constants, not Gradle source strings. Run in both debug and release. */
class BuildVariantIsolationTest {
    @Test fun debugConfigurationMatchesTheRequestedSupportedFeatures() {
        if (!BuildConfig.DEBUG) return
        assertEquals(java.lang.Boolean.getBoolean("walksafe.test.expectedQueue"), BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED)
        assertEquals(java.lang.Boolean.getBoolean("walksafe.test.expectedQuickStart"), BuildConfig.DEVELOPMENT_QUICK_START)
        assertEquals(java.lang.Boolean.getBoolean("walksafe.test.expectedBypass"), BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
    }

    @Test fun noBuildVariantEmbedsQuickLoginCredentials() {
        val names = BuildConfig::class.java.declaredFields.map { it.name }
        assertFalse(names.contains("DEVELOPMENT_QUICK_START_EMAIL"))
        assertFalse(names.contains("DEVELOPMENT_QUICK_START_PASSWORD"))
    }

    @Test fun releaseDefaultAccountClientsRejectUnapprovedOriginBeforeTransport() {
        if (BuildConfig.DEBUG) return
        val other = if (BuildConfig.WALKSAFE_GATEWAY_ORIGIN == "https://unapproved.invalid")
            "https://another.invalid" else "https://unapproved.invalid"
        val transport = object : GatewaySessionTransport {
            override fun postJson(url: String, body: String): GatewayHttpResponse = error("unexpected request")
            override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse = error("unexpected request")
            override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse = error("unexpected request")
            override fun deleteJson(url: String, body: String): GatewayHttpResponse = error("unexpected request")
        }
        assertEquals("invalid_gateway_url", assertThrows(GatewayAccountHttpException::class.java) {
            GatewayAccountClient(transport).requestEmailOtp(other, "user@example.com", "2000-01-01", "request_1234567890")
        }.reason)
        assertEquals("invalid_gateway_url", assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport).loginWithPassword(other, "user@example.com", "test-password-123", false)
        }.reason)
    }

    @Test fun releaseCannotActivateDevelopmentFeaturesOrEmbedQuickLoginCredentials() {
        if (BuildConfig.DEBUG) return
        assertFalse(BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        assertFalse(BuildConfig.DEVELOPMENT_QUICK_START)
        assertFalse(BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED)
        assertEquals("disabled", BuildConfig.WALKSAFE_REPORT_QUEUE_TEST_ORIGIN)
        assertEquals(0, BuildConfig.WALKSAFE_RAW_QUEUE_MAX_SEGMENTS)
        assertEquals(0L, BuildConfig.WALKSAFE_RAW_QUEUE_MAX_TOTAL_BYTES)
        assertTrue(BuildConfig.WALKSAFE_POST_LOGIN_DEVICE_CHECK_REQUIRED)
        assertEquals("USER", BuildConfig.WALKSAFE_PRODUCT_ROLE)
    }
}
