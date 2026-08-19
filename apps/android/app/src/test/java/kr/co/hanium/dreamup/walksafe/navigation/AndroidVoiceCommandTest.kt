package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(AndroidVoiceCommand.HearMoreDestinationCandidates, parseAndroidVoiceCommand("더 듣기"))
        assertEquals(AndroidVoiceCommand.NextNavigationInstruction, parseAndroidVoiceCommand("다음 경로 뭐야?"))
        assertEquals(AndroidVoiceCommand.NextNavigationInstruction, parseAndroidVoiceCommand("다음 안내 알려줘"))
        assertEquals(AndroidVoiceCommand.RequestReroute, parseAndroidVoiceCommand("경로 다시 찾아줘"))
        assertEquals(AndroidVoiceCommand.ConfirmArrival, parseAndroidVoiceCommand("도착 확인"))
        assertEquals(AndroidVoiceCommand.RejectArrival, parseAndroidVoiceCommand("아직 도착 아니야"))
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
        assertEquals(
            AndroidVoiceAction.HearMoreDestinationCandidates,
            AndroidVoiceCommand.HearMoreDestinationCandidates.toAction(),
        )
        assertEquals(AndroidVoiceAction.RequestReroute, AndroidVoiceCommand.RequestReroute.toAction())
        assertEquals(AndroidVoiceAction.ConfirmArrival, AndroidVoiceCommand.ConfirmArrival.toAction())
        assertEquals(AndroidVoiceAction.RejectArrival, AndroidVoiceCommand.RejectArrival.toAction())
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
        assertTrue(prompt.contains("더 들으려면 더 듣기라고 말씀해 주세요."))
    }

    @Test
    fun destinationSearchVoiceStatePagesSevenCandidatesThreeThreeOne() {
        val results = (1..7).map { index ->
            destinationResult(
                name = "도서관 ${index}",
                roadAddress = "서울 ${index}길",
                distanceM = index * 100,
            )
        }
        val firstPage = DestinationSearchVoiceState(query = "도서관", results = results)

        assertEquals(listOf("도서관 1", "도서관 2", "도서관 3"), firstPage.currentPageResults.map { it.name })
        assertTrue(firstPage.voicePrompt().contains("1번 도서관 1, 서울 1길, 100m"))
        assertTrue(firstPage.voicePrompt().contains("3번 도서관 3, 서울 3길, 300m"))

        val secondPage = firstPage.onCommand(DestinationSearchVoiceCommand.HearMore)
        assertTrue(secondPage.accepted)
        assertEquals(listOf("도서관 4", "도서관 5", "도서관 6"), secondPage.state.currentPageResults.map { it.name })
        assertTrue(secondPage.state.voicePrompt().contains("4번 도서관 4, 서울 4길, 400m"))
        assertTrue(secondPage.state.voicePrompt().contains("6번 도서관 6, 서울 6길, 600m"))

        val thirdPage = secondPage.state.onCommand(DestinationSearchVoiceCommand.HearMore)
        assertTrue(thirdPage.accepted)
        assertEquals(listOf("도서관 7"), thirdPage.state.currentPageResults.map { it.name })
        assertTrue(thirdPage.state.voicePrompt().contains("7번 도서관 7, 서울 7길, 700m"))
        assertTrue(!thirdPage.state.onCommand(DestinationSearchVoiceCommand.HearMore).accepted)
    }

    @Test
    fun fullRemotePageOffersHearMoreBeforeTheNextPageIsLoaded() {
        val state = DestinationSearchVoiceState(
            query = "도서관",
            results = (1..3).map { destinationResult("도서관 ${it}") },
            moreResultsAvailable = true,
        )

        assertTrue(state.voicePrompt().contains("더 듣기"))
        assertFalse(state.onCommand(DestinationSearchVoiceCommand.HearMore).accepted)
    }

    @Test
    fun onlyExplicitHearMoreCommandAdvancesTheDestinationPage() {
        val state = DestinationSearchVoiceState(
            query = "도서관",
            results = (1..7).map { destinationResult("도서관 ${it}") },
        )

        assertEquals(DestinationSearchVoiceCommand.HearMore, parseDestinationSearchVoiceCommand("더 듣기"))
        listOf("다음", "계속", "더 보여줘", "또 듣기").forEach { phrase ->
            assertNull(parseDestinationSearchVoiceCommand(phrase))
        }
        assertEquals(0, state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).state.pageIndex)
        assertEquals(1, state.onCommand(DestinationSearchVoiceCommand.HearMore).state.pageIndex)
    }

    @Test
    fun destinationSelectionAcceptsOnlyAnExplicitNumberOnTheCurrentPage() {
        val results = (1..7).map { destinationResult("후보 ${it}") }
        val firstPage = DestinationSearchVoiceState(query = "후보", results = results)

        assertNull(parseDestinationSearchVoiceCommand("첫 번째 선택"))
        assertEquals(
            DestinationSearchVoiceCommand.SelectCandidate(4),
            parseDestinationSearchVoiceCommand("4번 선택"),
        )
        assertTrue(!firstPage.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4)).accepted)

        val secondPage = firstPage.onCommand(DestinationSearchVoiceCommand.HearMore).state
        assertTrue(!secondPage.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        val selection = secondPage.onCommand(DestinationSearchVoiceCommand.SelectCandidate(4))
        assertTrue(selection.accepted)
        assertEquals(4, selection.selectedOneBasedIndex)
        assertEquals("후보 4", selection.selectedResult?.name)
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

    @Test
    fun recognizesLocationRecheckAsItsOwnOffRouteChoice() {
        assertEquals(AndroidVoiceCommand.RecheckLocation, parseAndroidVoiceCommand("위치 다시 확인"))
        assertEquals(AndroidVoiceCommand.RecheckLocation, parseAndroidVoiceCommand("위치 확인해줘"))
        assertEquals(
            AndroidVoiceAction.RecheckLocation,
            AndroidVoiceCommand.RecheckLocation.toAction(),
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
