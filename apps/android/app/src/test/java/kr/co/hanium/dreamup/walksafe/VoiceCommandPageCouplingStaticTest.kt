package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S05-33: only commands that need another screen move the page; read-only answers and spoken-only
 * state changes stay put, on a voice page and on the walking guidance page.
 */
class VoiceCommandPageCouplingStaticTest {
    private val activity = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    )

    @Test
    fun readOnlyAnswersOnAVoicePageDoNotCallShowNativeUiPage() {
        val execute = ReportStaticSourceInspector.functionBlock(activity, "private fun executeVoiceAction")
        val pageSelection = execute.substringAfter("val commandPage = when (action.uiDestination()) {")
            .substringBefore("when (action) {")
        // w25 (05.txt:321, S05-33 remaining): the walking guidance page is a current page too, so it is kept.
        assertTrue(
            pageSelection.contains(
                "VoiceCommandUiDestination.CURRENT_VOICE_PAGE ->\n" +
                    "                if (nativeUiPage.isVoiceInteractionPage() || nativeUiPage == NativeUiPage.GUIDANCE) null\n" +
                    "                else NativeUiPage.VOICE_COMMAND",
            ),
        )
        assertTrue(pageSelection.contains("commandPage?.let { page ->"))
        assertTrue(pageSelection.contains("if (!isInWalkInteractionArea() || page != NativeUiPage.VOICE_COMMAND)"))
        assertTrue(pageSelection.contains("showNativeUiPage(page, preserveVoiceInteraction = true)"))
        assertFalse(pageSelection.contains("showNativeUiPage(commandPage, preserveVoiceInteraction = true)"))
    }

    @Test
    fun aSkippedPageChangeAlsoSkipsTheDestinationLocationRequestAndReRender() {
        val show = activity
            .substringAfter("private fun showNativeUiPage(requestedPage: NativeUiPage, preserveVoiceInteraction: Boolean) {")
            .substringBefore("private fun updateNativeHomeGeometry")
        // These side effects live only inside showNativeUiPage, which a read-only answer no longer calls.
        assertTrue(show.contains("if (page == NativeUiPage.DESTINATION_SEARCH) requestDestinationSearchLocation()"))
        assertTrue(show.contains("renderMainUi()"))
    }

    @Test
    fun wakingTheAssistantKeepsTheWalkingGuidancePage() {
        val listening = ReportStaticSourceInspector.functionBlock(activity, "private fun handleHandsFreeVoiceCommandListeningStarted")
        assertTrue(listening.contains("if (!isInWalkInteractionArea()) return"))
        assertTrue(listening.contains("handsFreeVoiceOwnsCommandPage = false"))
        assertFalse(listening.contains("showNativeUiPage("))
    }
}
