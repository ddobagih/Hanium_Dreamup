package kr.co.hanium.dreamup.walksafe.network

import org.junit.Assert.*
import org.junit.Test

class GatewayQuickTunnelOriginUpgradeTest {
    private val compiled = "https://current-field.trycloudflare.com"
    private fun select(saved: String?, build: String = compiled) =
        GatewayEndpointPolicy.configuredDebugOriginOrNull(saved, build)

    @Test fun anOldTemporaryTunnelUsesTheCurrentCompiledTunnel() {
        assertEquals(compiled, select("https://old-field.trycloudflare.com"))
        assertEquals(compiled, select("https://OLD-FIELD.trycloudflare.com:443/"))
        assertEquals(compiled, select(compiled))
    }

    @Test fun fixedServersAndLoopbackSettingsRemainExplicitChoices() {
        for (saved in listOf("https://gateway.example.test", "http://127.0.0.1:8081", "http://localhost:8081")) {
            assertEquals(saved, select(saved))
        }
    }

    @Test fun aNonTunnelBuildDoesNotSilentlyReplaceAnExistingSetting() {
        val saved = "https://old-field.trycloudflare.com"
        for (build in listOf("https://stable.example.test", "http://127.0.0.1:8081", "invalid")) {
            assertEquals(saved, select(saved, build))
        }
    }

    @Test fun similarlyNamedHostsSubdomainsAndCustomPortsAreNotTemporaryTunnelSettings() {
        for (saved in listOf("https://trycloudflare.com", "https://old.trycloudflare.com.attacker.test",
            "https://nested.old.trycloudflare.com", "https://old.trycloudflare.com:8443")) {
            assertEquals(saved, select(saved))
        }
        val old = "https://old-field.trycloudflare.com"
        assertEquals(old, select(old, "https://current.trycloudflare.com.attacker.test"))
        assertEquals(old, select(old, "https://current.trycloudflare.com:8443"))
    }

    @Test fun invalidCredentialsPathsQueriesAndCleartextRemainRejected() {
        for (saved in listOf(null, "", "https://token@old.trycloudflare.com",
            "https://old.trycloudflare.com/api", "https://old.trycloudflare.com?token=x",
            "https://old.trycloudflare.com#fragment", "http://old.trycloudflare.com")) {
            assertNull(select(saved))
        }
    }

    @Test fun releaseOriginBindingStillRejectsThePreviousOrigin() {
        assertNull(GatewayEndpointPolicy.approvedReleaseOriginOrNull("https://old-field.trycloudflare.com", compiled))
        assertEquals(compiled, GatewayEndpointPolicy.approvedReleaseOriginOrNull(compiled, compiled))
    }
}
