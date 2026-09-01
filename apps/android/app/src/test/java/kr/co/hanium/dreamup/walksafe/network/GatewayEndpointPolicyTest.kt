package kr.co.hanium.dreamup.walksafe.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayEndpointPolicyTest {
    @Test
    fun releaseAcceptsOnlyTheExactBuildApprovedRootHttpsGateway() {
        assertEquals(
            "https://field.example",
            GatewayEndpointPolicy.approvedReleaseOriginOrNull(
                raw = "https://FIELD.example/",
                approvedOrigin = "https://field.example",
            ),
        )
        assertNull(GatewayEndpointPolicy.approvedReleaseOriginOrNull("https://evil.example", "https://field.example"))
        assertNull(GatewayEndpointPolicy.approvedReleaseOriginOrNull("http://field.example", "https://field.example"))
        assertNull(GatewayEndpointPolicy.approvedReleaseOriginOrNull("https://field.example/api", "https://field.example"))
        assertNull(GatewayEndpointPolicy.approvedReleaseOriginOrNull("https://token@field.example", "https://field.example"))
        assertNull(GatewayEndpointPolicy.approvedReleaseOriginOrNull("https://field.example?token=secret", "https://field.example"))
    }

    @Test
    fun debugCleartextIsRestrictedToLoopbackGateway() {
        assertEquals(
            "http://127.0.0.1:8081",
            GatewayEndpointPolicy.debugOriginOrNull("http://127.0.0.1:8081"),
        )
        assertEquals(
            "http://localhost:8081",
            GatewayEndpointPolicy.debugOriginOrNull("http://localhost:8081"),
        )
        assertNull(GatewayEndpointPolicy.debugOriginOrNull("http://192.168.0.10:8081"))
    }

    @Test
    fun queueStyleDebugTrafficAcceptsOnlyTheExactBuildApprovedTestOrigin() {
        assertEquals(
            "http://127.0.0.1:8081",
            GatewayEndpointPolicy.approvedDebugOriginOrNull(
                raw = "http://127.0.0.1:8081/",
                approvedOrigin = "http://127.0.0.1:8081",
            ),
        )
        assertNull(
            GatewayEndpointPolicy.approvedDebugOriginOrNull(
                raw = "https://arbitrary.example",
                approvedOrigin = "http://127.0.0.1:8081",
            ),
        )
        assertNull(
            GatewayEndpointPolicy.approvedDebugOriginOrNull(
                raw = "http://localhost:8081",
                approvedOrigin = "http://127.0.0.1:8081",
            ),
        )
        assertEquals(
            "https://queue-test.example",
            GatewayEndpointPolicy.approvedDebugOriginOrNull(
                raw = "https://QUEUE-test.example/",
                approvedOrigin = "https://queue-test.example",
            ),
        )
    }
}
