package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-7: the final confirmation keeps name, address and distance basis of the selected candidate. */
class MainActivityDestinationConfirmationIdentityStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val voice = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/DestinationVoiceDialogController.kt").readText().also {
        MainActivityRefactorSource.composition(source, "voiceInteractionControllers.destinations")
    }
    private fun confirmation(): String {
        assertTrue(MainActivityRefactorSource.member(source, "nativeDestinationConfirmationMessage")
            .contains("NativeGuidanceTextPresentation.destinationConfirmation(pendingUiDestination, { destinationSearchOrigin }, SystemClock::elapsedRealtime)"))
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeGuidanceTextPresentation.kt").readText(),
            "destinationConfirmation",
        )
    }

    @Test fun confirmationUsesTheSelectedCandidateNotALiveListLookup() {
        val confirmation = confirmation()
        assertTrue(confirmation.contains("val selected = destination ?: return"))
        assertTrue(confirmation.contains("selected.displayAddressOrNull()"))
        // S06-31: approximate distance at the search origin's precision, never 1 m.
        assertTrue(
            confirmation.contains(
                "\"검색 시점 기준 \${approximateDestinationDistanceKo(it, origin()?.accuracyM)}\"",
            ),
        )
        assertTrue(confirmation.contains("destinationOriginUncertaintyNoticeKo(origin())"))
        assertFalse(confirmation.contains("\"검색 시점 기준 약 \${it}미터\""))
        assertFalse(confirmation.contains("DestinationSearchController"))
        assertFalse(MainActivityRefactorSource.member(source, "nativeDestinationConfirmationMessage")
            .contains("destinationSearchController.state.results"))
    }

    @Test fun voiceSelectionRepeatsTheAddressInHomeAndWalkingBranches() {
        val selection = MainActivityRefactorSource.member(voice, "selectVoiceDestinationCandidate")
        assertEquals(2, Regex("selectedDestinationSpeechKo\\(selected\\)").findAll(selection).count())
        assertFalse(selection.contains("\"\${selected.name} 목적지를 선택했습니다."))
        val speech = MainActivityRefactorSource.member(voice, "selectedDestinationSpeechKo")
        assertTrue(speech.contains("selected.speechAddressOrNull()"))
        val models = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/WalkingRouteModels.kt").readText()
        assertTrue(models.contains("fun DestinationSearchResult.speechAddressOrNull(): String? = displayAddressOrNull()?.let(::spokenDestinationAddressKo)"))
        assertTrue(speech.contains("에 있는 장소를 선택했습니다."))
    }
    /** S06-7 review P2: the voice selection (both branches) repeats the distance basis of the chosen candidate. */
    @Test fun voiceSelectionSaysTheSearchTimeDistanceBasis() {
        val speech = MainActivityRefactorSource.member(voice, "selectedDestinationSpeechKo")
        assertTrue(speech.contains("navigation.selectedDestinationDistanceSentenceKo(\n                selected,\n                search().state.origin,"))
        assertTrue(speech.contains(").joinToString(\" \")"))
    }
}
