package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-34: only the spoken page owns the existing range header; refreshing it cannot rebuild/focus candidates. */
class MainActivityDestinationVoiceRangeHeaderStaticTest {
    private fun source(relative: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$relative").readText()
    private val main = source("MainActivity.kt")
    private val panel = source("ui/DestinationGuidancePanel.kt")
    private val presenter = source("ui/DestinationSearchPresenter.kt")
    private val dialog = source("voice/interaction/DestinationVoiceDialogController.kt")
    private fun member(source: String, name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(source, name, occurrence)

    @Test fun theHeaderIsBuiltWithTheListAndBeforeTheNumberedButtons() {
        val render = member(panel, "renderSearchResults")
        val header = render.indexOf("voiceRangeHeader = TextView(context).apply {")
        assertTrue(header >= 0 && header < render.indexOf("candidates.forEach { candidate ->"))
        val refresh = member(presenter, "refreshVoiceRange") + member(presenter, "voiceRangeLabel") + member(panel, "renderVoiceRange") + member(panel, "applyVoiceRange")
        assertTrue(refresh.contains("?.voicePageRangeLabelKo()"))
        assertTrue(refresh.contains("it.query == search.query && it.results == search.results"))
        assertTrue(refresh.contains("header.text = label.orEmpty()"))
        for (forbidden in listOf("accessibilityLiveRegion", "requestFocus", "ACTION_ACCESSIBILITY_FOCUS", "removeAllViews", "addView(")) {
            assertFalse(forbidden, refresh.contains(forbidden))
        }
        assertTrue(render.contains("android.text.SpannableStringBuilder(\"\${candidate.number}번 \${candidate.result.name}\")"))
        assertTrue(member(presenter, "renderSearch").contains("Candidate(result, index + 1, details)"))
        assertTrue(render.contains("setOnClickListener { onSelected(candidate.result) }"))
        assertTrue(member(main, "updateDestinationSearchUi").contains("onSelected = ::openNativeDestinationConfirmation"))
    }

    @Test fun aPagePromptUpdatesTheHeaderAndAClosedDialogHidesIt() {
        val prompt = member(dialog, "speakHomeDestinationVoicePrompt").substringBefore("val attempt = state.beginVoicePrompt()")
        assertTrue(prompt.contains("ui.refreshDestinationRange(state)"))
        assertFalse(prompt.contains("ui.renderSearch()"))
        val clear = member(dialog, "clearHomeDestinationVoiceDialog", occurrence = 1)
        assertTrue(clear.contains("ui.refreshDestinationRange(null)"))
    }

    @Test fun onlyASpokenPageShowsItsRangeAndListRebuildsKeepTheDialogsPage() {
        val prompt = member(dialog, "speakHomeDestinationVoicePrompt").substringBefore("val attempt = state.beginVoicePrompt()")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(prompt,
            "clearHomeDestinationVoiceDialog()", "if (state.results.isEmpty()) return output.speakInteraction(message)",
            "val actorId = identity.actorId() ?: return false", "ui.refreshDestinationRange(state)"))
        val mainRefresh = member(main, "refreshDestinationVoiceRangeText")
        assertTrue(mainRefresh.contains("state: DestinationSearchVoiceState? = destinationSearchPresenter.voiceRangeState"))
        assertTrue(member(presenter, "refreshVoiceRange").contains("voiceRangeState = state"))
        assertFalse(mainRefresh.contains("state: DestinationSearchVoiceState? = destinationSearchVoiceState"))
        assertTrue(member(presenter, "renderSearch").contains("voiceRangeLabel(state), onSelected"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(member(panel, "renderSearchResults"),
            "destinationSearchResultsContainer.addView(header)", "renderVoiceRange(voiceRange)"))
    }

    @Test fun failedSpeechAndExpiredLeaseHideTheUnspokenOrOldRange() {
        val prompt = member(dialog, "speakHomeDestinationVoicePrompt")
        val failure = prompt.substringAfter("onFailed = {")
        assertTrue(failure.contains("output.responseGeneration() == responseGeneration"))
        assertTrue(failure.contains("ui.destinationRangeState() === state"))
        assertTrue(failure.contains("ui.refreshDestinationRange(null)"))
        assertTrue(prompt.contains("scheduler.postDialogExpiry({"))
        assertTrue(prompt.contains("if (homeDestinationVoiceDialogLease === lease) currentVoiceDestinationDialogState()"))
        val leaseLookup = member(dialog, "currentVoiceDestinationDialogState")
        assertTrue(leaseLookup.contains("if (state == null && homeDestinationVoiceDialogLease === lease)"))
        assertTrue(leaseLookup.contains("ui.refreshDestinationRange(null)"))
    }
}
