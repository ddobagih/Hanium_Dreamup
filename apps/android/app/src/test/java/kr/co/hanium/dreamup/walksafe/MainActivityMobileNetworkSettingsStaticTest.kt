package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityMobileNetworkSettingsStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val panelSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/SettingsPrivacyPanel.kt").readText()

    private val presenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/SettingsPrivacyPresenter.kt").readText()

    @Test
    fun mobileDataControlIsAttachedToTheUserSettingsScreen() {
        val settings = ReportStaticSourceInspector.blockAfter(
            panelSource,
            "val privacySettingsControls: LinearLayout = LinearLayout(context).apply",
        )

        assertTrue(settings.contains("addView(mobileNetworkPreferenceButton)"))
        assertFalse(settings.contains("addView(TextView("))
        val button = ReportStaticSourceInspector.blockAfter(
            panelSource,
            "val mobileNetworkPreferenceButton: Button = Button(context).apply",
        )
        assertTrue(button.contains("setOnClickListener { actions.onMobileNetworkPreference() }"))
        assertTrue(source.contains("val settingsPanel = SettingsPrivacyPanel("))
        assertTrue(source.contains("onMobileNetworkPreference = { onMobileNetworkPreferenceButtonClicked() }"))
        assertTrue(source.contains("settingsPrivacyPresenter = SettingsPrivacyPresenter(settingsPanel, onboardingPanel)"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                source,
                "settingsPrivacyPresenter = SettingsPrivacyPresenter(settingsPanel, onboardingPanel)",
                "updateMobileNetworkPreferenceUi()",
            ),
        )
        val spoken = MainActivityRefactorSource.function(source, "private fun updateMobileNetworkPreferenceUi")
        assertTrue(spoken.contains("settingsPrivacyPresenter.renderConsentButton("))
        assertTrue(spoken.contains("IntegratedConsentItem.MOBILE_NETWORK_TRANSFER"))
        val render = ReportStaticSourceInspector.functionBlock(presenterSource, "fun renderConsentButton")
        val presentation = ReportStaticSourceInspector.functionBlock(presenterSource, "fun consentButtonPresentation")
        assertTrue(render.contains("button.contentDescription = it"))
        assertTrue(presentation.contains("계정 로그인·인증·동의 변경·계정 삭제·외부 개인정보 권리 요청"))
    }

    @Test
    fun mobileDataChangeUsesTheExistingServerConfirmedConsentFlow() {
        val click = MainActivityRefactorSource.function(
            source,
            "private fun onMobileNetworkPreferenceButtonClicked",
        )
        val display = MainActivityRefactorSource.function(
            source,
            "private fun updateMobileNetworkPreferenceUi",
        )

        assertTrue(click.contains("IntegratedConsentItem.MOBILE_NETWORK_TRANSFER"))
        assertTrue(click.contains("persistIntegratedConsentDraft(announce = true)"))
        assertFalse(click.contains("setMobileNetworkPreference("))
        assertTrue(display.contains("integratedConsentSession.status("))
        assertTrue(display.contains("settingsPrivacyPresenter.renderConsentButton("))
        assertTrue(display.contains("SettingsPrivacyPresenter.ConsentFacts("))
        val presentation = ReportStaticSourceInspector.functionBlock(presenterSource, "fun consentButtonPresentation")
        val render = ReportStaticSourceInspector.functionBlock(presenterSource, "fun renderConsentButton")
        assertTrue(presentation.contains("PurposeConsentSyncState.GRANT_PENDING"))
        assertTrue(presentation.contains("아직 활성 아님"))
        assertTrue(render.contains("button.contentDescription = it"))
    }

    @Test
    fun mobileDataWithdrawalBlocksTransfersBeforeServerConfirmation() {
        val withdrawal = MainActivityRefactorSource.function(
            source,
            "private fun applyImmediateConsentWithdrawals",
        ).substringAfter("IntegratedConsentItem.MOBILE_NETWORK_TRANSFER ->")
            .substringBefore("IntegratedConsentItem.TRAINING_REUSE ->")

        assertTrue(withdrawal.contains("MobileNetworkPreference.WIFI_ONLY"))
        assertTrue(withdrawal.contains("network.isCellularIfReady()"))
        assertTrue(withdrawal.contains("network.cancelGatewayCalls()"))
        assertTrue(withdrawal.contains("reports.cancelAllCallsForMobileWithdrawal()"))
        assertTrue(withdrawal.contains("network.refreshFeaturePolicy()"))
        val composition = MainActivityRefactorSource.composition(source, "integratedConsentController")
        assertTrue(composition.contains("reports = ports.reports"))
        assertTrue(composition.contains("network = ports.network"))
        val participants = MainActivityRefactorSource.factory(source, "createPrivacyConsent")
        assertTrue(participants.contains("privacyAuthority.createIntegratedConsentController"))
        assertTrue(participants.contains("reports = object : IntegratedConsentController.Reports"))
        assertTrue(participants.contains("network = object : IntegratedConsentController.Network"))
        assertTrue(participants.contains("override fun isCellularIfReady() = ::networkStateProbe.isInitialized && networkStateProbe.currentTransport() == ActiveNetworkTransport.CELLULAR"))
        assertTrue(participants.contains("override fun cancelGatewayCalls() = cancelGatewayNetworkCalls()"))
        val cancellation = ReportStaticSourceInspector.functionBlock(participants, "override fun cancelAllCallsForMobileWithdrawal")
        assertTrue(cancellation.contains("reportConsent().cancelActiveCalls()"))
        assertTrue(participants.contains("override fun refreshFeaturePolicy() = refreshGatewayFeatureNetworkPolicy()"))
    }

    @Test
    fun speechRequestsAndLateCallbacksUseTheSameNetworkTicket() {
        for (name in listOf("submitGatewayVoiceRecording", "speakGatewayInteractionOrLocalFallback")) {
            val request = MainActivityRefactorSource.function(source, "private fun $name")
            assertTrue(request.contains("gatewaySpeechNetworkGate.bind("))
            assertTrue(request.contains("active.networkTicket"))
            assertTrue(request.contains("isGatewaySpeechInteractionCurrent(active)"))
        }
        val current = MainActivityRefactorSource.function(
            source,
            "private fun isGatewaySpeechInteractionCurrent",
        )
        assertTrue(current.contains("gatewaySpeechNetworkGate.isCurrent(active.networkTicket)"))
        val session = MainActivityRefactorSource.function(
            source,
            "private fun currentGatewaySpeechSessionOrNull",
        )
        assertTrue(session.contains("!gatewayEnvironment.sessionPrerequisitesAllowed()"))
        val voiceComposition = MainActivityRefactorSource.composition(source, "voiceInputController")
        assertTrue(voiceComposition.contains("override fun sessionPrerequisitesAllowed() ="))
        assertTrue(voiceComposition.contains("isGatewayFeatureNetworkAllowed() && isActivityForeground"))
        val networkState = MainActivityRefactorSource.function(source, "fun gatewayFeatureNetworkState(")
        assertTrue(networkState.contains("permissionSessionPolicy().snapshot().mobileNetworkPreference"))
        assertFalse(networkState.contains("integratedConsentDraft"))
    }

    @Test
    fun networkChangesCancelServerWorkAndPreserveLocalSpeechEntry() {
        val changed = delegatedOwner("refreshGatewayFeatureNetworkPolicy", "networkPreferenceController", "session/consent/NetworkPreferenceController.kt", "refreshGatewayFeatureNetworkPolicy", "{ networkPreferenceController.refreshGatewayFeatureNetworkPolicy() refreshGuidanceDiagnosticAuthority() }")
        assertTrue(changed.contains("ports.markVoiceNetworkPolicyChanged()"))
        assertTrue(changed.contains("ports.userReportNetworkPolicyChanged()"))
        assertTrue(changed.contains("ports.cancelVoiceForNetworkPolicyIfNeeded()"))
        val wiring = MainActivityRefactorSource.composition(source, "networkPreferenceController")
        assertTrue(wiring.contains("voiceInputController.gateway.markNetworkPolicyChanged()"))
        assertTrue(wiring.contains("userReportController.onNetworkPolicyChanged()"))
        assertTrue(wiring.contains("voiceInputController.gateway.cancelForNetworkPolicyIfNeeded()"))
        val gatewayVoice = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/GatewayVoiceCaptureController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(gatewayVoice, "markNetworkPolicyChanged").contains("gatewaySpeechNetworkGate.onNetworkPolicyChanged()"))
        assertTrue(MainActivityRefactorSource.member(gatewayVoice, "cancelForNetworkPolicyIfNeeded").contains("cancelGatewaySpeechInteraction("))
        val observer = MainActivityRefactorSource.function(source, "fun registerGatewayCapacityNetworkObserver(")
        assertTrue(observer.split("networkEffects.refreshFeatureNetworkPolicy()").size == 4)
        assertTrue(MainActivityRefactorSource.composition(source, "gatewaySessionController").contains("override fun refreshFeatureNetworkPolicy() = refreshGatewayFeatureNetworkPolicy()"))
        assertTrue(source.contains("networkAllowedProvider = ::isGatewayFeatureNetworkAllowed"))
        val localSpeech = MainActivityRefactorSource.function(
            source,
            "private fun startVoiceCommandRecognition",
        )
        assertFalse(localSpeech.contains("isGatewayFeatureNetworkAllowed("))
        assertFalse(localSpeech.contains("isGatewayNetworkAllowed("))
        val fallback = MainActivityRefactorSource.function(
            source,
            "private fun speakGatewayInteractionOrLocalFallback",
        )
        assertTrue(fallback.contains("output.speakInteraction(message)"))
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
            val function = if (name == "loginEmailAccount")
                MainActivityRefactorSource.function(source, "private fun $name")
            else MainActivityRefactorSource.function(source, "private fun $name")
            assertFalse(function.contains("isGatewayNetworkAllowed("))
            if (name == "loginEmailAccount") {
                assertTrue(function.contains("readers.networkAvailable()"))
                assertTrue(function.contains("readers.gatewayOrigin()"))
                val composition = MainActivityRefactorSource.member(source, "createEmailAccountController")
                assertTrue(composition.contains("networkAvailable = ::accountNetworkAvailable"))
                assertTrue(composition.contains("gatewayOrigin = ::configuredGatewayOriginOrNull"))
            }
        }
    }

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}
