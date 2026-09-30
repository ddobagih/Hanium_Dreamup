package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-7: the final confirmation keeps name, address and distance basis of the selected candidate. */
class MainActivityDestinationConfirmationIdentityStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun block(start: String, end: String) = source.substringAfter(start).substringBefore(end)

    @Test fun confirmationUsesTheSelectedCandidateNotALiveListLookup() {
        val confirmation = block(
            "private fun nativeDestinationConfirmationMessage(): String {",
            "private fun selectedDestinationSpeechKo(",
        )
        assertTrue(confirmation.contains("val destination = pendingUiDestination ?: return"))
        assertTrue(confirmation.contains("destination.displayAddressOrNull()"))
        // S06-31: approximate distance at the search origin's precision, never 1 m.
        assertTrue(
            confirmation.contains(
                "\"검색 시점 기준 \${approximateDestinationDistanceKo(it, destinationSearchOrigin?.accuracyM)}\"",
            ),
        )
        assertTrue(confirmation.contains("destinationOriginUncertaintyNoticeKo(destinationSearchOrigin)"))
        assertFalse(confirmation.contains("\"검색 시점 기준 약 \${it}미터\""))
        assertFalse(confirmation.contains("destinationSearchResults"))
    }

    @Test fun voiceSelectionRepeatsTheAddressInHomeAndWalkingBranches() {
        val selection = block(
            "private fun selectVoiceDestinationCandidate(oneBasedIndex: Int) {",
            "private fun explainRouteCommandUnavailable(",
        )
        assertEquals(2, Regex("selectedDestinationSpeechKo\\(selected\\)").findAll(selection).count())
        assertFalse(selection.contains("\"\${selected.name} 목적지를 선택했습니다."))
        val speech = block(
            "private fun selectedDestinationSpeechKo(selected: DestinationSearchResult): String =",
            "private fun clearNativeDestinationSearchState()",
        )
        assertTrue(speech.contains("selected.displayAddressOrNull()"))
        assertTrue(speech.contains("에 있는 장소를 선택했습니다."))
    }
    /** S06-7 review P2: the voice selection (both branches) repeats the distance basis of the chosen candidate. */
    @Test fun voiceSelectionSaysTheSearchTimeDistanceBasis() {
        val speech = block(
            "private fun selectedDestinationSpeechKo(selected: DestinationSearchResult): String =",
            "private fun clearNativeDestinationSearchState()",
        )
        assertTrue(speech.contains("navigation.selectedDestinationDistanceSentenceKo(\n                selected,\n                destinationSearchOrigin,"))
        assertTrue(speech.contains(").joinToString(\" \")"))
    }
}
