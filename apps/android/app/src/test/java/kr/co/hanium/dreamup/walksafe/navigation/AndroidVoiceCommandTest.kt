package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVoiceCommandTest {
    @Test
    fun parsesReportCommandWithoutChangingTheExistingAction() {
        listOf("신고해줘", "신고해주세요", "여기 신고 접수").forEach { phrase ->
            assertEquals(AndroidVoiceCommand.CreateReport, parseAndroidVoiceCommand(phrase))
        }
        assertEquals(AndroidVoiceAction.CreateReport, AndroidVoiceCommand.CreateReport.toAction())
    }

    @Test
    fun parsesDestinationPrefixAndPreservesNamesEndingInRo() {
        val cases = mapOf(
            "목적지 서울역 설정해" to "서울역",
            "목적지를 서울역으로 변경해" to "서울역",
            "목적지 종로 설정해" to "종로",
            "목적지 구로 지정해" to "구로",
            "목적지 낙성대로 바꿔줘" to "낙성대로",
            "목적지를 종로로 변경해" to "종로",
        )

        cases.forEach { (phrase, expected) ->
            assertEquals(AndroidVoiceCommand.SetDestination(expected), parseAndroidVoiceCommand(phrase))
        }
    }

    @Test
    fun parsesDestinationSuffixCommandsWithoutKeepingTheParticle() {
        val cases = mapOf(
            "서울역으로 목적지 설정해" to "서울역",
            "종로로 목적지 변경해" to "종로",
            "구로로 안내해줘" to "구로",
            "낙성대로로 가자" to "낙성대로",
        )

        cases.forEach { (phrase, expected) ->
            assertEquals(AndroidVoiceCommand.SetDestination(expected), parseAndroidVoiceCommand(phrase))
        }
    }

    @Test
    fun parsesDestinationCancellationNextRouteAndNavigationStop() {
        assertEquals(AndroidVoiceCommand.CancelDestination, parseAndroidVoiceCommand("목적지 취소"))
        assertEquals(AndroidVoiceCommand.CancelDestination, parseAndroidVoiceCommand("목적지를 취소해"))
        assertEquals(AndroidVoiceCommand.NextNavigationInstruction, parseAndroidVoiceCommand("다음 경로 뭐야?"))
        assertEquals(AndroidVoiceCommand.NextNavigationInstruction, parseAndroidVoiceCommand("다음 안내 알려줘"))
        assertEquals(AndroidVoiceCommand.StopNavigation, parseAndroidVoiceCommand("길안내 중지"))
    }

    @Test
    fun parsesDestinationCandidateSelectionForNumericAndKoreanOrdinals() {
        val cases = mapOf(
            "1번 선택" to 1,
            "목적지 2번 선택해" to 2,
            "첫 번째 선택" to 1,
            "두번째 목적지 선택" to 2,
            "목적지 첫번째 선택" to 1,
        )
        cases.forEach { (phrase, index) ->
            assertEquals(
                AndroidVoiceCommand.SelectDestinationCandidate(index),
                parseAndroidVoiceCommand(phrase),
            )
        }
        assertNull(parseAndroidVoiceCommand("0번 선택"))
        assertNull(parseAndroidVoiceCommand("21번 선택"))
    }

    @Test
    fun destinationChangeDispatchesToSearchOnlyUntilTheUserSelectsACandidate() {
        val action = AndroidVoiceCommand.SetDestination("종로").toAction()

        assertTrue(action is AndroidVoiceAction.SearchDestination)
        assertEquals(AndroidVoiceAction.SearchDestination("종로"), action)
    }

    @Test
    fun mapsNonDestinationCommandsToTheirUiActions() {
        assertEquals(AndroidVoiceAction.CancelDestination, AndroidVoiceCommand.CancelDestination.toAction())
        assertEquals(
            AndroidVoiceAction.SpeakNextNavigationInstruction,
            AndroidVoiceCommand.NextNavigationInstruction.toAction(),
        )
        assertEquals(AndroidVoiceAction.StopNavigation, AndroidVoiceCommand.StopNavigation.toAction())
        assertEquals(
            AndroidVoiceAction.SelectDestinationCandidate(2),
            AndroidVoiceCommand.SelectDestinationCandidate(2).toAction(),
        )
    }

    @Test
    fun negatedOrIncompleteCommandsFailClosed() {
        listOf(
            "신고하지 마",
            "목적지 취소하지 마",
            "길안내 중지하지 마",
            "목적지 변경해",
            "다음 경로 아니야",
        ).forEach { phrase ->
            assertNull(parseAndroidVoiceCommand(phrase))
        }
    }

    @Test
    fun topNegatedOrLowConfidenceHypothesisCannotFallThroughToAnAlternativeCommand() {
        assertNull(
            selectAndroidVoiceAction(
                phrases = listOf("길안내 중지하지 마", "길안내 중지"),
                confidenceScores = floatArrayOf(0.91f, 0.80f),
            ),
        )
        assertNull(
            selectAndroidVoiceAction(
                phrases = listOf("목적지 취소", "다음 경로 뭐야"),
                confidenceScores = floatArrayOf(0.30f, 0.95f),
            ),
        )
        assertEquals(
            AndroidVoiceAction.CancelDestination,
            selectAndroidVoiceAction(
                phrases = listOf("목적지 취소", "길안내 중지"),
                confidenceScores = floatArrayOf(0.91f, 0.80f),
            ),
        )
    }

    @Test
    fun destinationSearchPromptReadsNumberedNamesAddressesAndDistances() {
        val results = listOf(
            destinationResult("서울역", roadAddress = "서울 중구 한강대로 405", distanceM = 320),
            destinationResult("서울역 버스환승센터", address = "서울 중구 봉래동", distanceM = 480),
        )

        assertEquals(
            "서울역 목적지 후보가 2곳 있습니다. " +
                "1번 서울역, 서울 중구 한강대로 405, 320m. " +
                "2번 서울역 버스환승센터, 서울 중구 봉래동, 480m. 원하는 번호를 말씀해 주세요.",
            formatDestinationSearchVoicePrompt("서울역", results),
        )
    }

    @Test
    fun destinationSearchPromptIsBoundedToTheTopThreeCandidates() {
        val results = (1..4).map { index -> destinationResult("후보 ${index}", distanceM = index * 100) }

        val prompt = formatDestinationSearchVoicePrompt("도서관", results)

        assertTrue(prompt.contains("1번 후보 1"))
        assertTrue(prompt.contains("3번 후보 3"))
        assertTrue(!prompt.contains("후보 4,"))
        assertTrue(prompt.contains("나머지 1곳은 화면의 더 보기에서 확인할 수 있습니다."))
    }

    @Test
    fun destinationSearchPromptHandlesEmptyAndMissingMetadata() {
        assertEquals(
            "병원 목적지 검색 결과가 없습니다.",
            formatDestinationSearchVoicePrompt("병원", emptyList()),
        )
        assertTrue(
            formatDestinationSearchVoicePrompt("병원", listOf(destinationResult("병원")))
                .contains("1번 병원, 주소 미상, 거리미상"),
        )
    }

    private fun destinationResult(
        name: String,
        roadAddress: String? = null,
        address: String? = null,
        distanceM: Int? = null,
    ): DestinationSearchResult {
        return DestinationSearchResult(
            id = name,
            name = name,
            point = RoutePoint(37.0, 127.0),
            address = address,
            roadAddress = roadAddress,
            category = null,
            distanceM = distanceM,
        )
    }
}
