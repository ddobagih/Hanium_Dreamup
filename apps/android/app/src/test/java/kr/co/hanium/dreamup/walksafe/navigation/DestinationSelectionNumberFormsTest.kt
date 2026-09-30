package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** S06-22: digits, Korean numerals and ordinals are one structured number before the selection ending. */
class DestinationSelectionNumberFormsTest {
    @Test fun everyFormOfThreeSelectsCandidateThree() {
        listOf("3번 선택", "3 번 선택", "삼 번 선택", "삼번 선택", "세 번째 선택", "셋째 선택", "목적지 삼 번 선택해줘").forEach {
            assertEquals(it, AndroidVoiceCommand.SelectDestinationCandidate(3), parseAndroidVoiceCommand(it))
            assertEquals(it, DestinationSearchVoiceCommand.SelectCandidate(3), parseDestinationSearchVoiceCommand(it))
        }
    }

    @Test fun bareFormsInsideTheCandidateDialogAgree() {
        listOf("3번", "삼 번", "세 번째").forEach {
            assertEquals(
                it,
                AndroidVoiceCommand.SelectDestinationCandidate(3),
                parseAndroidVoiceCommand(it, allowBareDestinationIndex = true),
            )
            assertEquals(
                it,
                DestinationSearchVoiceCommand.SelectCandidate(3),
                parseDestinationSearchVoiceCommand(it, allowBareDestinationIndex = true),
            )
        }
    }
}
