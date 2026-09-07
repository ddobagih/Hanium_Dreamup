package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationBackendFailureTest {
    @Test
    fun stairsAreReportedAsAnUnavailableRouteRatherThanAnInvalidResponse() {
        val failure = classify(422, "route_stairs_present")

        assertEquals(NavigationBackendErrorKind.STAIRS_PRESENT, failure.kind)
        assertEquals("route_stairs_present", failure.kind.statusToken)
        assertEquals("route_stairs_present", failure.backendCode)
        assertEquals(422, failure.statusCode)
        assertTrue(failure.kind.userMessage.contains("계단"))
        assertTrue(failure.kind.userMessage.contains("새 경로를 사용할 수 없습니다"))
    }

    @Test
    fun providerRateLimitWrappedAs503KeepsItsRetryGuidance() {
        listOf(429, 503).forEach { status ->
            val failure = classify(status, "tmap_rate_limited")

            assertEquals(NavigationBackendErrorKind.RATE_LIMITED, failure.kind)
            assertEquals(status, failure.statusCode)
            assertTrue(failure.kind.userMessage.contains("사용 한도"))
            assertTrue(failure.kind.userMessage.contains("목적지 검색"))
            assertTrue(failure.kind.userMessage.contains("다시 시도"))
        }
        assertEquals(NavigationBackendErrorKind.RATE_LIMITED, classify(429, null).kind)
    }

    @Test
    fun missingProviderKeysAndPermissionsDoNotAskTheUserToLogInAgain() {
        val cases = listOf(
            503 to "tmap_app_key_missing",
            502 to "tmap_invalid_api_key",
            401 to "tmap_invalid_api_key",
            403 to "tmap_invalid_api_key",
            503 to "walking_route_provider_invalid",
            503 to "tmap_poi_provider_invalid",
        )
        cases.forEach { (status, code) ->
            val failure = classify(status, code)

            assertEquals(NavigationBackendErrorKind.PROVIDER_CONFIGURATION, failure.kind)
            assertTrue(failure.kind.userMessage.contains("API 키"))
            assertTrue(failure.kind.userMessage.contains("이용 권한"))
            assertTrue(failure.kind.userMessage.contains("관리자"))
            assertFalse(failure.kind.userMessage.contains("로그인"))
        }
    }

    @Test
    fun gatewayAuthenticationFailuresStillRequireTheUserSessionToBeChecked() {
        listOf(401, 403).forEach { status ->
            assertEquals(NavigationBackendErrorKind.AUTHENTICATION, classify(status, null).kind)
        }
        assertEquals(
            NavigationBackendErrorKind.AUTHENTICATION,
            classify(502, "gateway_upstream_auth_failed").kind,
        )
    }

    @Test
    fun unrelatedProviderAndRouteErrorsKeepTheirExistingKinds() {
        assertEquals(
            NavigationBackendErrorKind.INVALID_RESPONSE,
            classify(422, "route_geometry_summary_mismatch").kind,
        )
        assertEquals(
            NavigationBackendErrorKind.PROVIDER_UNAVAILABLE,
            classify(502, "route_unavailable").kind,
        )
        assertEquals(
            NavigationBackendErrorKind.BACKEND_UNAVAILABLE,
            classify(503, "gateway_unavailable").kind,
        )
        assertEquals(NavigationBackendErrorKind.UNKNOWN, classify(422, null).kind)
    }

    private fun classify(status: Int, code: String?): NavigationBackendFailure =
        classifyNavigationBackendFailure(
            GatewayProxyHttpException(status, "walking_route_failed", code),
        )
}
