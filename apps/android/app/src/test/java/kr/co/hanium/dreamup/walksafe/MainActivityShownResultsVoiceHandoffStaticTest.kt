package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-6: keyboard search results -> 다시 듣기/더 듣기 -> current candidates are read, without a re-search. */
class MainActivityShownResultsVoiceHandoffStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun block(start: String, end: String) = source.substringAfter(start).substringBefore(end)

    private val repeat = block("private fun repeatVoiceDestinationCandidates() {", "private fun hearMoreVoiceDestinationCandidates() {")
    private val hearMore = block("private fun hearMoreVoiceDestinationCandidates() {", "private fun cancelDestinationFromVoice() {")
    private val handoff = block(
        "private fun voiceStateForShownDestinationResultsOrNull(): DestinationSearchVoiceState? {",
        "private fun noVoiceDestinationCandidatesMessage(): String",
    )

    @Test fun repeatFallsBackToTheShownResultsWhenNoDialogOwnsThem() {
        assertTrue(repeat.contains("} ?: voiceStateForShownDestinationResultsOrNull()"))
        assertTrue(repeat.contains("speakHomeDestinationVoicePrompt(transition.state)"))
        assertFalse(repeat.contains("performDestinationSearch("))
    }

    @Test fun hearMoreWithoutADialogReadsTheFirstShownPage() {
        val noDialog = hearMore.substringAfter("if (state == null) {").substringBefore("val transition =")
        assertTrue(noDialog.contains("voiceStateForShownDestinationResultsOrNull()"))
        assertTrue(noDialog.contains("speakHomeDestinationVoicePrompt(shown)"))
        assertFalse(noDialog.contains("performDestinationSearch("))
    }

    @Test fun handoffUsesTheOneResultListAndNeverGrantsSelectionBeforeThePrompt() {
        assertTrue(handoff.contains("destinationSearchInFlight || destinationSearchQuery.isBlank() || destinationSearchResults.isEmpty()"))
        assertTrue(handoff.contains("val results = destinationSearchResults.toList()"))
        assertTrue(handoff.contains("also { destinationSearchVoiceState = it }"))
        // Selection authority still comes only from a completed page prompt.
        assertFalse(handoff.contains("completeVoicePrompt"))
        assertFalse(handoff.contains("performDestinationSearch("))
        val prompt = block("private fun speakHomeDestinationVoicePrompt(", "private fun startVoiceDestinationSearch(")
        assertTrue(prompt.contains("state.beginVoicePrompt()"))
        assertTrue(prompt.contains("state.completeVoicePrompt(attempt)"))
        assertTrue(prompt.contains("destinationSearchVoiceState === state"))
    }
}
