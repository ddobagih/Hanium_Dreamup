package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityIntegratedConsentStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun consentAndNetworkBlocksReleaseTheAcquiredReportLease() {
        assertReleaseBeforeBlockedReturn(
            "!consentConfirmation.selections.automaticReporting",
            "reportCandidate=blocked:automatic_consent_required",
        )
        assertReleaseBeforeBlockedReturn(
            "networkStateProbe.currentIntegratedConsentBinding()",
            "reportCandidate=blocked:network_transport_untrusted",
        )
        assertReleaseBeforeBlockedReturn(
            "!consentConfirmation.selections.mobileNetworkTransfer",
            "reportCandidate=blocked:mobile_network_consent_required",
        )
    }

    @Test
    fun consentMutationRequiresAndForwardsTheCurrentGatewaySession() {
        val persist = source.substringAfter(
            "private fun persistIntegratedConsentDraft(announce: Boolean)",
        ).substringBefore("private fun retryPendingIntegratedConsentMutation")
        val retry = source.substringAfter(
            "private fun retryPendingIntegratedConsentMutation",
        ).substringBefore("private fun startIntegratedConsentRequest")
        for (function in listOf(persist, retry)) {
            val session = function.indexOf("gatewaySessionOrNull(")
            val save = function.indexOf("integratedConsentClient.saveCall(")
            assertTrue(session >= 0)
            assertTrue(save > session)
            assertTrue(
                function.substring(save).contains("session = gatewaySession"),
            )
        }

        val client = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/network/" +
                "AndroidIntegratedConsentClient.kt",
        ).readText()
        val saveCall = client.substringAfter("fun saveCall(")
            .substringBefore("private fun requestCall(")
        assertTrue(saveCall.contains("session: GatewayFieldSession"))
        assertTrue(saveCall.contains("gatewayBaseUrl == session.gatewayBaseUrl"))
        assertTrue(saveCall.contains("session = session"))
        assertTrue(client.contains("session?.requestHeaders()?.forEach(::setRequestProperty)"))
    }

    @Test
    fun pendingDraftNeverDisplaysAsBackendConfirmedConsent() {
        listOf(
            "updateReportPrivacyConsentUi",
            "updateAutomaticReportConsentUi",
            "updateMobileNetworkPreferenceUi",
            "updateTrainingReuseConsentUi",
        ).forEach { name ->
            val function = ReportStaticSourceInspector.functionBlock(
                source,
                "private fun $name",
            )
            assertTrue(function.contains("integratedConsentSession.status("))
            assertTrue(function.contains("PurposeConsentSyncState.CONFIRMED_GRANTED"))
            assertTrue(function.contains("PurposeConsentSyncState.GRANT_PENDING"))
            assertTrue(function.contains("아직 활성 아님"))
            assertTrue(!function.contains("integratedConsentDraft."))
        }

        val integrated = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updateIntegratedConsentUi",
        )
        assertTrue(integrated.contains("허용(서버 확인)"))
        assertTrue(integrated.contains("허용 선택 저장 중(아직 활성 아님)"))
        assertTrue(integrated.contains("button.text = \"\$label: \$syncState\""))
    }

    private fun assertReleaseBeforeBlockedReturn(marker: String, status: String) {
        val markerIndex = source.indexOf(marker)
        val returnIndex = source.indexOf(status, markerIndex)
        assertTrue("missing marker: $marker", markerIndex >= 0)
        assertTrue("missing blocked return: $status", returnIndex > markerIndex)
        assertTrue(
            "report lease must be released before: $status",
            source.substring(markerIndex, returnIndex)
                .contains("reportAttemptStore.release(lease)"),
        )
    }

    @Test
    fun gatewayConnectionFailureIsHandledInsteadOfKillingTheProcess() {
        val request = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startIntegratedConsentRequest",
        )

        // ConnectException 은 IOException 계열이라 RuntimeException 으로 잡히지 않는다.
        // Gateway 가 닿지 않을 때 실행자 스레드에서 빠져나가면 프로세스가 종료된다.
        assertFalse(request.contains("catch (_: RuntimeException)"))
        assertTrue(request.contains("catch (_: Exception)"))
        assertTrue(request.contains("integratedConsent=blocked:server_confirmation_failed"))
    }
}
