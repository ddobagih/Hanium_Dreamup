package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-8 / S06-23: a correctly heard but unusable number is explained with the numbers usable now. */
class DestinationSelectionRejectionMessageTest {
    private fun result(index: Int) = DestinationSearchResult(
        id = "candidate-$index",
        name = "후보 $index",
        point = RoutePoint(37.0, 127.0),
        address = null,
        roadAddress = null,
        category = null,
        distanceM = null,
    )

    private fun secondPage(size: Int = 6): DestinationSearchVoiceState {
        val first = DestinationSearchVoiceState(query = "후보", results = (1..size).map(::result))
        return first.onCommand(DestinationSearchVoiceCommand.HearMore).state.also {
            assertTrue(it.completeVoicePrompt(it.beginVoicePrompt()))
        }
    }

    @Test fun outOfRangeNumberNamesTheCurrentNumbers() {
        val page = secondPage()
        assertFalse(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(2)).accepted)
        assertEquals(
            "2번은 현재 안내 중인 후보가 아닙니다. 지금은 4번, 5번, 6번 중에서 선택해 주세요.",
            page.selectionRejectionMessage(2),
        )
        assertFalse(page.selectionRejectionMessage(2).contains("인식하지 못"))
    }

    @Test fun firstOrdinalOnTheSecondPageIsExplainedNotRemapped() {
        val page = secondPage()
        val ordinal = requireNotNull(parseDestinationSearchVoiceCommand("첫 번째 선택"))
        assertEquals(DestinationSearchVoiceCommand.SelectCandidate(1), ordinal)
        assertFalse(page.onCommand(ordinal).accepted)
        assertTrue(page.selectionRejectionMessage(1).startsWith("1번은 현재 안내 중인 후보가 아닙니다."))
        assertTrue(page.selectionRejectionMessage(1).contains("4번, 5번, 6번"))
    }

    @Test fun singleCandidatePageSaysTheOnlyNumber() {
        val page = secondPage(size = 4)
        assertEquals(
            "9번은 현재 안내 중인 후보가 아닙니다. 지금은 4번만 선택할 수 있습니다.",
            page.selectionRejectionMessage(9),
        )
    }

    @Test fun inRangeNumberBeforeThePromptFinishedIsNotCalledMissing() {
        val unheard = DestinationSearchVoiceState(query = "후보", results = (1..3).map(::result))
        assertFalse(unheard.onCommand(DestinationSearchVoiceCommand.SelectCandidate(2)).accepted)
        val message = unheard.selectionRejectionMessage(2)
        assertTrue(message.startsWith("2번 후보 안내가 아직 끝나지 않았습니다."))
        assertFalse(message.contains("없습니다"))
    }

    @Test fun emptyResultsAskForAnotherSearch() {
        val empty = DestinationSearchVoiceState(query = "후보", results = emptyList())
        assertEquals(
            "선택할 목적지 후보가 없습니다. 다른 장소명으로 다시 말씀해 주세요.",
            empty.selectionRejectionMessage(1),
        )
    }
}
