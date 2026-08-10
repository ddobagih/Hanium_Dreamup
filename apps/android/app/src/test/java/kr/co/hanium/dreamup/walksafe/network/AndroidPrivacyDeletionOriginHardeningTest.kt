package kr.co.hanium.dreamup.walksafe.network

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPrivacyDeletionOriginHardeningTest {
    @Test
    fun integratedConsentRejectsAnOriginOutsideTheLiveSession() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { attacker ->
            attacker.soTimeout = 250
            val session = GatewayFieldSession.verified(
                gatewayBaseUrl = "http://127.0.0.1:1",
                actorId = "actor-test-0001",
                cookiePair = "walksafe_field_session=" + "a".repeat(64),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
            )

            var rejected = false
            try {
                AndroidIntegratedConsentClient().saveCall(
                    gatewayBaseUrl =
                    "http://127.0.0.1:${attacker.localPort}",
                    session = session,
                    installationId = "install-test-0001",
                    controlSecret = "b".repeat(64),
                    requestId = "integrated_consent_request_0001",
                    clientRevision = 1L,
                    selections = IntegratedConsentSelections(),
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)

            var connected = false
            try {
                attacker.accept().use { connected = true }
            } catch (_: SocketTimeoutException) {
                Unit
            }
            assertTrue("attacker origin must receive zero connections", !connected)
        }
    }

    @Test
    fun mismatchedOriginIsRejectedBeforeAnySocketConnection() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { attacker ->
            attacker.soTimeout = 250
            val session = GatewayFieldSession.verified(
                gatewayBaseUrl = "http://127.0.0.1:1",
                actorId = "actor-test-0001",
                cookiePair = "walksafe_field_session=" + "a".repeat(64),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
            )

            var rejected = false
            try {
                AndroidPrivacyDeletionClient().requestDeletionCall(
                    gatewayBaseUrl = "http://127.0.0.1:${attacker.localPort}",
                    trustedGatewayOrigin = session.gatewayBaseUrl,
                    session = session,
                    installationId = "install-test-0001",
                    accessSecret = ACCESS_SECRET,
                    requestId = "account_delete_" + "1".repeat(64),
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)

            var connected = false
            try {
                attacker.accept().use { connected = true }
            } catch (_: SocketTimeoutException) {
                Unit
            }
            assertTrue("attacker origin must receive zero socket connections", !connected)
        }
    }

    @Test
    fun restoredJournalAttackerOriginIsRejectedBeforeSocketOrHeaders() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { attacker ->
            attacker.soTimeout = 250
            val journal = AccountDeletionJournal.pending(
                gatewayOrigin = "http://127.0.0.1:${attacker.localPort}",
                installationId = "install-test-0001",
                requestId = "account_delete_" + "1".repeat(64),
                requestedAt = "2026-07-25T00:00:00Z",
            )

            var rejected = false
            try {
                AndroidPrivacyDeletionClient().fetchDeletionStatusCall(
                    journal = journal,
                    trustedGatewayOrigin = "http://127.0.0.1:1",
                    accessSecret = ACCESS_SECRET,
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)

            var connected = false
            try {
                attacker.accept().use { connected = true }
            } catch (_: SocketTimeoutException) {
                Unit
            }
            assertTrue(
                "untrusted restored origin must receive zero connections or headers",
                !connected,
            )
        }
    }

    @Test
    fun exactTrustedRestoredJournalOriginAllowsCallConstruction() {
        val trustedOrigin = "http://127.0.0.1:1"
        val journal = AccountDeletionJournal.pending(
            gatewayOrigin = trustedOrigin,
            installationId = "install-test-0001",
            requestId = "account_delete_" + "1".repeat(64),
            requestedAt = "2026-07-25T00:00:00Z",
        )

        AndroidPrivacyDeletionClient().fetchDeletionStatusCall(
            journal = journal,
            trustedGatewayOrigin = trustedOrigin,
            accessSecret = ACCESS_SECRET,
        ).cancel()
    }

    private companion object {
        const val ACCESS_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
