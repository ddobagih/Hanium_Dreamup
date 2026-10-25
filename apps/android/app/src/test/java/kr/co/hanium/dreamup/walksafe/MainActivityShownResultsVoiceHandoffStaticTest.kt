package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-6: keyboard search results -> 다시 듣기/더 듣기 -> current candidates are read, without a re-search. */
class MainActivityShownResultsVoiceHandoffStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/DestinationVoiceDialogController.kt").readText()
    private fun block(start: String, end: String) = source.substringAfter(start).substringBefore(end)

    private val repeat = block("fun repeatVoiceDestinationCandidates() {", "fun hearMoreVoiceDestinationCandidates() {")
    private val hearMore = block("fun hearMoreVoiceDestinationCandidates() {", "fun cancelDestinationFromVoice() {")
    private val handoff = block(
        "private fun voiceStateForShownDestinationResultsOrNull(): DestinationSearchVoiceState? {",
        "private fun noVoiceDestinationCandidatesMessage(): String",
    )

    @Test fun repeatFallsBackToTheShownResultsWhenNoDialogOwnsThem() {
        assertTrue(repeat.contains("} ?: voiceStateForShownDestinationResultsOrNull()"))
        assertTrue(repeat.contains("speakHomeDestinationVoicePrompt(transition.state)"))
        assertFalse(repeat.contains("performDestinationSearch("))
        assertFalse(repeat.contains("search().search("))
        assertFalse(repeat.contains("search().requestLocation("))
    }

    @Test fun hearMoreWithoutADialogReadsTheFirstShownPage() {
        val noDialog = hearMore.substringAfter("if (state == null) {").substringBefore("val transition =")
        assertTrue(noDialog.contains("voiceStateForShownDestinationResultsOrNull()"))
        assertTrue(noDialog.contains("speakHomeDestinationVoicePrompt(shown)"))
        assertFalse(noDialog.contains("performDestinationSearch("))
        assertFalse(noDialog.contains("search().search("))
        assertFalse(noDialog.contains("search().requestLocation("))
    }

    @Test fun handoffUsesTheOneResultListAndNeverGrantsSelectionBeforeThePrompt() {
        assertTrue(handoff.contains("search().state.inFlight || search().state.query.isBlank() || search().state.results.isEmpty()"))
        assertTrue(handoff.contains("val results = search().state.results.toList()"))
        assertTrue(handoff.contains("also { search().showVoicePage(it) }"))
        // Selection authority still comes only from a completed page prompt.
        assertFalse(handoff.contains("completeVoicePrompt"))
        assertFalse(handoff.contains("performDestinationSearch("))
        assertFalse(handoff.contains("search().search("))
        assertFalse(handoff.contains("search().requestLocation("))
        val prompt = block("fun speakHomeDestinationVoicePrompt(", "fun startVoiceDestinationSearch(")
        assertTrue(prompt.contains("state.beginVoicePrompt()"))
        assertTrue(prompt.contains("state.completeVoicePrompt(attempt)"))
        assertTrue(prompt.contains("search().state.voicePage === state"))
    }
}
