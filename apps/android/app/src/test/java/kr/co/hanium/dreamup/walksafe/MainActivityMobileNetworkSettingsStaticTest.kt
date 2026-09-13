package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityMobileNetworkSettingsStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun mobileDataControlIsAttachedToTheUserSettingsScreen() {
        val settings = ReportStaticSourceInspector.blockAfter(
            source,
            "privacySettingsControls = LinearLayout(this@MainActivity).apply",
        )

        assertTrue(settings.contains("addView(mobileNetworkPreferenceButton)"))
        assertTrue(settings.contains("계정 로그인·인증·동의 변경·계정 삭제·외부 개인정보 권리 요청"))
    }

    @Test
    fun mobileDataChangeUsesTheExistingServerConfirmedConsentFlow() {
        val click = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun onMobileNetworkPreferenceButtonClicked",
        )
        val display = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun updateMobileNetworkPreferenceUi",
        )

        assertTrue(click.contains("IntegratedConsentItem.MOBILE_NETWORK_TRANSFER"))
        assertTrue(click.contains("persistIntegratedConsentDraft(announce = true)"))
        assertFalse(click.contains("setMobileNetworkPreference("))
        assertTrue(display.contains("integratedConsentSession.status("))
        assertTrue(display.contains("PurposeConsentSyncState.GRANT_PENDING"))
        assertTrue(display.contains("아직 활성 아님"))
        assertTrue(display.contains("mobileNetworkPreferenceButton.contentDescription"))
    }

    @Test
    fun mobileDataWithdrawalBlocksTransfersBeforeServerConfirmation() {
        val withdrawal = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun applyImmediateConsentWithdrawals",
        ).substringAfter("IntegratedConsentItem.MOBILE_NETWORK_TRANSFER ->")
            .substringBefore("IntegratedConsentItem.TRAINING_REUSE ->")

        assertTrue(withdrawal.contains("MobileNetworkPreference.WIFI_ONLY"))
        assertTrue(withdrawal.contains("ActiveNetworkTransport.CELLULAR"))
        assertTrue(withdrawal.contains("cancelGatewayNetworkCalls()"))
        assertTrue(withdrawal.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(withdrawal.contains("refreshGatewayFeatureNetworkPolicy()"))
    }

    @Test
    fun speechRequestsAndLateCallbacksUseTheSameNetworkTicket() {
        for (name in listOf("submitGatewayVoiceRecording", "speakGatewayInteractionOrLocalFallback")) {
            val request = ReportStaticSourceInspector.functionBlock(source, "private fun $name")
            assertTrue(request.contains("gatewaySpeechNetworkGate.bind("))
            assertTrue(request.contains("active.networkTicket"))
            assertTrue(request.contains("isGatewaySpeechInteractionCurrent(active)"))
        }
        val current = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun isGatewaySpeechInteractionCurrent",
        )
        assertTrue(current.contains("gatewaySpeechNetworkGate.isCurrent(active.networkTicket)"))
        val session = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun currentGatewaySpeechSessionOrNull",
        )
        assertTrue(session.contains("!isGatewayFeatureNetworkAllowed()"))
        val networkState = source.substringAfter("private fun gatewayFeatureNetworkState()")
            .substringBefore("private fun isGatewayFeatureNetworkAllowed")
        assertTrue(networkState.contains("permissionSessionPolicy.snapshot().mobileNetworkPreference"))
        assertFalse(networkState.contains("integratedConsentDraft"))
    }

    @Test
    fun networkChangesCancelServerWorkAndPreserveLocalSpeechEntry() {
        val changed = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun refreshGatewayFeatureNetworkPolicy",
        )
        assertTrue(changed.contains("gatewaySpeechNetworkGate.onNetworkPolicyChanged()"))
        assertTrue(changed.contains("userReportController.onNetworkPolicyChanged()"))
        assertTrue(changed.contains("cancelGatewaySpeechInteraction("))
        val observer = ReportStaticSourceInspector.blockAfter(
            source,
            "object : ConnectivityManager.NetworkCallback()",
        )
        assertTrue(observer.split("refreshGatewayFeatureNetworkPolicy()").size == 4)
        assertTrue(source.contains("networkAllowedProvider = ::isGatewayFeatureNetworkAllowed"))
        val localSpeech = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun startVoiceCommandRecognition",
        )
        assertFalse(localSpeech.contains("isGatewayFeatureNetworkAllowed("))
        assertFalse(localSpeech.contains("isGatewayNetworkAllowed("))
        val fallback = ReportStaticSourceInspector.functionBlock(
            source,
            "private fun speakGatewayInteractionOrLocalFallback",
        )
        assertTrue(fallback.contains("speakInteraction(message)"))
    }

    @Test
    fun accountAndConsentControlCallsCanEnableCellularWithoutExistingCellularConsent() {
        val accountNetwork = source.substringAfter("private fun accountNetworkAvailable()")
            .substringBefore("private fun positionFieldAccountTransitionAllowed")
        assertTrue(accountNetwork.contains("ActiveNetworkTransport.OFFLINE"))
        assertFalse(accountNetwork.contains("mobileNetworkPreference"))

        listOf(
            "loginEmailAccount",
            "persistIntegratedConsentDraft",
            "refreshIntegratedConsentFromServer",
            "startIntegratedConsentBootstrap",
            "startIntegratedConsentRequest",
            "retryPendingIntegratedConsentMutation",
        ).forEach { name ->
            val function = ReportStaticSourceInspector.functionBlock(
                source,
                "private fun $name",
            )
            assertFalse(function.contains("isGatewayNetworkAllowed("))
        }
    }
}
