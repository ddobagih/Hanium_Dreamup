package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S05-10 (05.txt:139): "현재 위치 알려줘" (a description) is answered as not offered, apart from a plain
 * miss, while "내 위치 확인해줘" (re-measure) is the RecheckLocation command handled by the route flow.
 */
class MainActivityVoiceLocationDescriptionStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun aLocationDescriptionRequestIsNamedAsNotOfferedWithTheStateHint() {
        val helper = source.substringAfter("private fun unrecognizedCommandMessageKo(recognized: String): String =")
            .substringBefore("\n\n")
        assertTrue(helper.contains("navigation.isLocationDescriptionRequest(recognized)"))
        assertTrue(helper.contains("navigation.LOCATION_DESCRIPTION_UNSUPPORTED_KO} \${unrecognizedCommandHintKo()}"))
        val scoredMiss = source.substringAfter("private fun handleVoiceCommandPhrases(")
            .substringBefore("executeVoiceAction(action, expectedNavigationDecisionToken)")
            .substringAfter("if (action == null) {")
        val retry = scoredMiss.substringAfter("val retryMessage =").substringBefore("if (!retryHomeDestinationVoiceDialog(retryMessage))")
        assertTrue(retry.contains("navigation.isLocationDescriptionRequest(recognized)"))
        assertTrue(retry.contains("kr.co.hanium.dreamup.walksafe.navigation.LOCATION_DESCRIPTION_UNSUPPORTED_KO"))
    }

    // voice-F10 (05.txt:139 "재측정 요청과 위치 설명 요청을 구분"): the answer names the re-measure command
    // exactly when it works (route re-check or reroute choice), and only then.
    @Test
    fun theReMeasureCommandIsPairedOnlyWhileItWorks() {
        val helper = source.substringAfter("private fun unrecognizedCommandMessageKo(recognized: String): String =")
            .substringBefore("\n\n")
        assertTrue(helper.contains("navigation.LOCATION_DESCRIPTION_UNSUPPORTED_KO} \${unrecognizedCommandHintKo()}\" +\n                locationRecheckOfferKo()"))
        val offer = source.substringAfter("private fun locationRecheckOfferKo(): String = when (routeNavigator.pendingUserDecision()) {")
            .substringBefore("\n    }\n")
        assertTrue(offer.contains("RouteNavigatorUserDecision.LOCATION_RECHECK, RouteNavigatorUserDecision.REROUTE ->"))
        assertTrue(offer.contains("\" 위치 다시 확인이라고 하면 현재 위치를 다시 잽니다.\""))
        assertTrue(offer.contains("else -> \"\""))
        assertTrue(
            kr.co.hanium.dreamup.walksafe.navigation.parseAndroidVoiceCommand("위치 다시 확인") ==
                kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceCommand.RecheckLocation,
        )
        // The same two decisions are the ones recheckLocationFromVoice accepts.
        val recheck = source.substringAfter("private fun recheckLocationFromVoice(expectedToken: RouteNavigatorDecisionToken?) {")
            .substringBefore("\n    }\n")
        assertTrue(recheck.contains("RouteNavigatorUserDecision.LOCATION_RECHECK,\n                RouteNavigatorUserDecision.REROUTE,"))
    }

    @Test
    fun reMeasuringStaysTheRouteDecisionCommand() {
        val execute = source.substringAfter("private fun executeVoiceAction(")
            .substringBefore("private fun unrecognizedCommandHintKo()")
        assertTrue(execute.contains("AndroidVoiceAction.RecheckLocation -> recheckLocationFromVoice(expectedNavigationDecisionToken)"))
    }
}
