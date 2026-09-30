package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-34 (06.txt:241): the screen list also says which numbers the voice dialog reads now ("음성 안내 중:
 * 4번~6번"). Only the header text changes on a page change: no button rebuild, no live region, no focus move.
 */
class MainActivityDestinationVoiceRangeHeaderStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val ui = source.substringAfter("private fun updateDestinationSearchUi()")
        .substringBefore("private fun updateRouteButtonText()")

    @Test
    fun theHeaderIsBuiltWithTheListAndBeforeTheNumberedButtons() {
        val header = ui.indexOf("destinationVoiceRangeText = TextView(this).apply {")
        assertTrue(header >= 0 && header < ui.indexOf("destinationSearchResults.forEachIndexed { index, result ->"))
        val refresh = ui.substringAfter("private fun refreshDestinationVoiceRangeText(")
        assertTrue(refresh.contains("?.voicePageRangeLabelKo()"))
        assertTrue(refresh.contains("it.query == destinationSearchQuery && it.results == destinationSearchResults"))
        assertTrue(refresh.contains("header.text = label.orEmpty()"))
        for (forbidden in listOf("accessibilityLiveRegion", "requestFocus", "ACTION_ACCESSIBILITY_FOCUS", "removeAllViews", "addView(")) {
            assertFalse(forbidden, refresh.contains(forbidden))
        }
        // The buttons still carry the global voice numbers and stay touchable (S06-34 earlier fix).
        assertTrue(ui.contains("android.text.SpannableStringBuilder(\"\${index + 1}번 \${result.name}\")"))
        assertTrue(ui.contains("openNativeDestinationConfirmation(result)"))
    }

    @Test
    fun aPagePromptUpdatesTheHeaderAndAClosedDialogHidesIt() {
        val prompt = source.substringAfter("private fun speakHomeDestinationVoicePrompt(")
            .substringBefore("val attempt = state.beginVoicePrompt()")
        assertTrue(prompt.contains("refreshDestinationVoiceRangeText(state)"))
        assertFalse(prompt.contains("updateDestinationSearchUi()"))
        val clear = source.substringAfter("private fun clearHomeDestinationVoiceDialog(reason:")
            .substringBefore("private fun currentVoiceDestinationDialogState")
        // voice-F9 (test moved): every close hides it, also one that keeps the list (BACKGROUNDED,
        // PAGE_CHANGED); the default would have re-shown the kept list's page.
        assertTrue(clear.contains("refreshDestinationVoiceRangeText(null)"))
    }

    @Test
    fun onlyASpokenPageShowsItsRangeAndListRebuildsKeepTheDialogsPage() {
        val prompt = source.substringAfter("private fun speakHomeDestinationVoicePrompt(")
            .substringBefore("val attempt = state.beginVoicePrompt()")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                prompt,
                "clearHomeDestinationVoiceDialog()",
                "if (state.results.isEmpty()) return speakInteraction(message)",
                "val actorId = reporterUserId ?: return false",
                "refreshDestinationVoiceRangeText(state)",
            ),
        )
        assertTrue(
            source.contains(
                "private fun refreshDestinationVoiceRangeText(state: DestinationSearchVoiceState? = destinationVoiceRangeState) {\n" +
                    "        destinationVoiceRangeState = state\n",
            ),
        )
        assertFalse(source.contains("refreshDestinationVoiceRangeText(state: DestinationSearchVoiceState? = destinationSearchVoiceState)"))
        // The list rebuild uses the tracked page, so a closed dialog's range does not come back.
        assertTrue(ui.contains("destinationSearchResultsContainer.addView(header)\n            refreshDestinationVoiceRangeText()"))
    }

    @Test
    fun failedSpeechAndExpiredLeaseHideTheUnspokenOrOldRange() {
        val prompt = source.substringAfter("private fun speakHomeDestinationVoicePrompt(")
            .substringBefore("private fun previousVoiceDestinationCandidates()")
        val failure = prompt.substringAfter("onFailed = {")
        assertTrue(failure.contains("commandSpeechResponseGeneration == responseGeneration"))
        assertTrue(failure.contains("destinationVoiceRangeState === state"))
        assertTrue(failure.contains("refreshDestinationVoiceRangeText(null)"))
        assertTrue(prompt.contains("reportCleanupCallbackHandler.postDelayed({"))
        assertTrue(prompt.contains("if (homeDestinationVoiceDialogLease === lease) currentVoiceDestinationDialogState()"))
        val leaseLookup = source.substringAfter("private fun currentVoiceDestinationDialogState(): DestinationSearchVoiceState? {")
            .substringBefore("private fun retryHomeDestinationVoiceDialog(")
        assertTrue(leaseLookup.contains("if (state == null && homeDestinationVoiceDialogLease === lease)"))
        assertTrue(leaseLookup.contains("refreshDestinationVoiceRangeText(null)"))
    }
}
