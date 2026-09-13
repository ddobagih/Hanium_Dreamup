package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVoiceCommandTest {
    @Test
    fun explicitSettingsCommandsOpenSettings() {
        listOf("설정", "설정 열어줘", "설정으로 이동").forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.OpenSettings, parseAndroidVoiceCommand(phrase))
            assertEquals(
                phrase, AndroidVoiceAction.OpenSettings,
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)),
            )
        }
    }

    @Test
    fun settingsPlaceNamesAndDestinationConfigurationStillSearchForThePlace() {
        val cases = mapOf(
            "설정역으로 안내해줘" to "설정역",
            "설정역 찾아줘" to "설정역",
            "목적지 서울역 설정해" to "서울역",
        )
        cases.forEach { (phrase, query) ->
            assertEquals(
                phrase, AndroidVoiceAction.SearchDestination(query),
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)),
            )
        }
    }

    @Test
    fun settingsCommandsKeepWholeCommandTopHypothesisAndConfidenceRequirements() {
        listOf("설정 열지 마", "설정으로 이동하지 마", "설정 열어줘 그리고 신고해줘").forEach { phrase ->
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertNull(
                phrase,
                selectAndroidVoiceAction(listOf(phrase, "설정"), floatArrayOf(0.9f, 0.99f)),
            )
        }
        listOf(0.3f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { confidence ->
            assertNull(selectAndroidVoiceAction(listOf("설정", "신고해줘"), floatArrayOf(confidence, 0.99f)))
        }
    }

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
    fun destinationVoiceCommandUsesUnicodeCodePointsForTheEightyCharacterLimit() {
        val accepted = "😀".repeat(80)
        val rejected = "😀".repeat(81)

        assertEquals(
            AndroidVoiceCommand.SetDestination(accepted),
            parseAndroidVoiceCommand("목적지 $accepted 설정해"),
        )
        assertNull(parseAndroidVoiceCommand("목적지 $rejected 설정해"))
    }

    @Test
    fun parsesDestinationCancellationNextRouteAndNavigationStop() {
        assertEquals(AndroidVoiceCommand.CancelDestination, parseAndroidVoiceCommand("목적지 취소"))
        assertEquals(AndroidVoiceCommand.CancelDestination, parseAndroidVoiceCommand("목적지를 취소해"))
        assertEquals(AndroidVoiceCommand.HearMoreDestinationCandidates, parseAndroidVoiceCommand("더 듣기"))
        assertEquals(AndroidVoiceCommand.NextNavigationInstruction, parseAndroidVoiceCommand("다음 경로 뭐야?"))
        assertEquals(AndroidVoiceCommand.NextNavigationInstruction, parseAndroidVoiceCommand("다음 안내 알려줘"))
        assertEquals(AndroidVoiceCommand.RequestReroute, parseAndroidVoiceCommand("경로 다시 찾아줘"))
        assertEquals(AndroidVoiceCommand.RecheckLocation, parseAndroidVoiceCommand("위치 확인해줘"))
        assertEquals(AndroidVoiceCommand.RecheckLocation, parseAndroidVoiceCommand("현재 위치 다시 확인해줘"))
        assertEquals(AndroidVoiceCommand.ConfirmArrival, parseAndroidVoiceCommand("도착 확인"))
        assertEquals(AndroidVoiceCommand.RejectArrival, parseAndroidVoiceCommand("아직 도착 아니야"))
        assertEquals(AndroidVoiceCommand.StopNavigation, parseAndroidVoiceCommand("길안내 종료"))
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
        assertEquals(AndroidVoiceAction.RecheckLocation, AndroidVoiceCommand.RecheckLocation.toAction())
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
                "2번 서울역 버스환승센터, 서울 중구 봉래동, 480m. 원하는 번호나 다시 듣기라고 말씀해 주세요.",
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
    fun explicitReplayKeepsTheCurrentPageWhileHearMoreAdvances() {
        val state = DestinationSearchVoiceState(
            query = "후보",
            results = (1..7).map { destinationResult("후보 $it") },
            pageIndex = 1,
        )
        listOf("다시 듣기", "다시듣기").forEach { phrase ->
            assertEquals(DestinationSearchVoiceCommand.RepeatPage, parseDestinationSearchVoiceCommand(phrase))
            assertEquals(AndroidVoiceCommand.RepeatDestinationCandidates, parseAndroidVoiceCommand(phrase))
        }
        assertEquals(
            AndroidVoiceAction.RepeatDestinationCandidates,
            AndroidVoiceCommand.RepeatDestinationCandidates.toAction(),
        )
        val replay = state.onCommand(DestinationSearchVoiceCommand.RepeatPage)
        assertTrue(replay.accepted)
        assertTrue(replay.state === state)
        assertEquals(1, replay.state.pageIndex)
        assertNull(replay.selectedResult)
        assertEquals(2, state.onCommand(DestinationSearchVoiceCommand.HearMore).state.pageIndex)
        assertFalse(DestinationSearchVoiceState("후보", emptyList())
            .onCommand(DestinationSearchVoiceCommand.RepeatPage).accepted)
        assertNull(parseAndroidVoiceCommand("다시 듣지 마"))
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
    fun parsesStartRepeatAndHelpAsDistinctActions() {
        val cases = mapOf(
            "안내 시작" to (AndroidVoiceCommand.StartNavigation to AndroidVoiceAction.StartNavigation),
            "길안내 시작" to (AndroidVoiceCommand.StartNavigation to AndroidVoiceAction.StartNavigation),
            "다시 말해줘" to (AndroidVoiceCommand.RepeatGuidance to AndroidVoiceAction.SpeakCurrentGuidance),
            "도움말" to (AndroidVoiceCommand.Help to AndroidVoiceAction.SpeakVoiceHelp),
        )
        cases.forEach { (phrase, expected) ->
            assertEquals(expected.first, parseAndroidVoiceCommand(phrase))
            assertEquals(expected.second, expected.first.toAction())
            assertEquals(
                expected.second,
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)),
            )
        }
        listOf("안내 시작하지 마", "다시 말하지 마", "도움말 하지 마", "보행 시작").forEach {
            assertNull(parseAndroidVoiceCommand(it))
        }
    }

    @Test
    fun bareNumericAndSpokenNumbersRequireDestinationSelectionContext() {
        val cases = mapOf(
            "1" to 1, "1번" to 1, "일 번" to 1, "하나" to 1, "한 번" to 1,
            "첫 번째" to 1, "두 번째" to 2, "이 번" to 2, "둘" to 2,
            "세 번" to 3, "사 번" to 4, "네 번" to 4, "넷" to 4,
            "여섯 번째" to 6, "십 번" to 10, "십일 번" to 11, "이십 번" to 20,
        )
        cases.forEach { (phrase, index) ->
            assertNull(parseAndroidVoiceCommand(phrase))
            assertNull(parseDestinationSearchVoiceCommand(phrase))
            assertEquals(
                AndroidVoiceCommand.SelectDestinationCandidate(index),
                parseAndroidVoiceCommand(phrase, allowBareDestinationIndex = true),
            )
            assertEquals(
                DestinationSearchVoiceCommand.SelectCandidate(index),
                parseDestinationSearchVoiceCommand(phrase, allowBareDestinationIndex = true),
            )
        }
    }

    @Test
    fun destinationContextStillRejectsAmbiguousNegatedAndOutOfRangeNumbers() {
        listOf(
            "0", "0번", "21", "21번", "영번", "이십일번", "네", "예", "응",
            "1번 아니야", "일번 선택하지 마", "1번 그리고 2번", "01번", "-1번",
        ).forEach { phrase ->
            assertNull(parseAndroidVoiceCommand(phrase, allowBareDestinationIndex = true))
            assertNull(parseDestinationSearchVoiceCommand(phrase, allowBareDestinationIndex = true))
        }
    }

    @Test
    fun destinationContextDoesNotOverrideTheTopHypothesisOrConfidence() {
        assertNull(selectAndroidVoiceAction(listOf("1번"), floatArrayOf(0.9f)))
        assertEquals(
            AndroidVoiceAction.SelectDestinationCandidate(1),
            selectAndroidVoiceAction(
                listOf("1번"), floatArrayOf(0.9f), allowBareDestinationIndex = true,
            ),
        )
        listOf(0.3f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { confidence ->
            assertNull(
                selectAndroidVoiceAction(
                    listOf("1번", "2번"), floatArrayOf(confidence, 0.99f),
                    allowBareDestinationIndex = true,
                ),
            )
        }
        assertNull(
            selectAndroidVoiceAction(
                listOf("1번 선택하지 마", "2번"), floatArrayOf(0.9f, 0.99f),
                allowBareDestinationIndex = true,
            ),
        )
    }

    @Test
    fun spokenCandidateNumberStillHasToBelongToTheCurrentPage() {
        val firstPage = DestinationSearchVoiceState(
            query = "후보",
            results = (1..7).map { destinationResult("후보 $it") },
        )
        val command = parseDestinationSearchVoiceCommand("사 번", allowBareDestinationIndex = true)
        assertEquals(DestinationSearchVoiceCommand.SelectCandidate(4), command)
        assertFalse(firstPage.onCommand(requireNotNull(command)).accepted)
        val secondPage = firstPage.onCommand(DestinationSearchVoiceCommand.HearMore).state
        val selection = secondPage.onCommand(command)
        assertTrue(selection.accepted)
        assertEquals("후보 4", selection.selectedResult?.name)
    }

    @Test
    fun naturalDestinationRequestsKeepThePlaceQueryWithoutHardcodedAliases() {
        val cases = mapOf(
            "금오공대 목적지로 해줘" to "금오공대",
            "금오공대 목적지로 해 줘" to "금오공대",
            "금오공대목적지로해주세요" to "금오공대",
            "금오공대 가고 싶어" to "금오공대",
            "금오공대 가고싶어요" to "금오공대",
            "시립 도서관 가고 싶습니다" to "시립 도서관",
            "서울역으로 가고 싶어" to "서울역",
            "편의점 찾아줘" to "편의점",
            "편의점찾아 줘" to "편의점",
            "CU 편의점 찾아 주세요" to "CU 편의점",
            "금오공대 안내해줘" to "금오공대",
            "편의점 안내해줘" to "편의점",
            "시립 도서관 안내 해 주세요" to "시립 도서관",
        )
        cases.forEach { (phrase, query) ->
            assertEquals(phrase, AndroidVoiceCommand.SetDestination(query), parseAndroidVoiceCommand(phrase))
            assertEquals(
                phrase, AndroidVoiceAction.SearchDestination(query),
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)),
            )
        }
    }

    @Test
    fun newDestinationFormsPreserveRoNamesAndLegacyDirectionalFormsStillWork() {
        val cases = mapOf(
            "종로 찾아줘" to "종로",
            "구로 가고 싶어" to "구로",
            "낙성대로 목적지로 해줘" to "낙성대로",
            "학교로 안내해줘" to "학교",
            "금오공대로 안내해줘" to "금오공대",
            "종로로 안내해줘" to "종로",
        )
        cases.forEach { (phrase, query) ->
            assertEquals(phrase, AndroidVoiceCommand.SetDestination(query), parseAndroidVoiceCommand(phrase))
        }
    }

    @Test
    fun incompleteNegatedAndCombinedDestinationCommandsDoNotStartAnyAction() {
        listOf(
            "목적지로 해줘", "찾아줘", "가고 싶어", "안내해줘", "목적지 찾아줘",
            "신고 찾아줘", "정지 찾아줘", "편의점 찾아주지 마",
            "금오공대 목적지로 하지 마", "서울역 안내해줘 찾아줘",
            "신고해줘 그리고 편의점 찾아줘", "길안내 종료하고 편의점 찾아줘",
            "편의점 찾아줘 그리고 신고해줘",
        ).forEach { phrase ->
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertNull(phrase, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
    }

    @Test
    fun destinationNamesContainingControlWordsNeverBecomeRiskyActions() {
        listOf("신고센터", "취소상담센터", "정지선 안내센터").forEach { place ->
            assertEquals(
                AndroidVoiceAction.SearchDestination(place),
                selectAndroidVoiceAction(listOf("$place 찾아줘"), floatArrayOf(0.9f)),
            )
        }
        listOf("편의점에서 신고해줘", "도서관에서 목적지 취소", "병원 앞에서 길안내 중지").forEach {
            assertNull(parseAndroidVoiceCommand(it))
        }
    }

    @Test
    fun naturalDestinationRequestsStillRequireConfidenceAndExplicitCandidateSelection() {
        assertNull(selectAndroidVoiceAction(listOf("금오공대 목적지로 해줘"), floatArrayOf(0.3f)))
        assertNull(selectAndroidVoiceAction(listOf("신고하지 마", "금오공대 찾아줘"), floatArrayOf(0.9f, 0.99f)))
        val action = selectAndroidVoiceAction(listOf("금오공대 목적지로 해줘"), floatArrayOf(0.9f))
        assertEquals(AndroidVoiceAction.SearchDestination("금오공대"), action)
        val state = DestinationSearchVoiceState(
            query = "금오공대",
            results = listOf(destinationResult("첫 후보"), destinationResult("다른 후보")),
        )
        assertNull(parseDestinationSearchVoiceCommand("첫 후보"))
        assertEquals(2, state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(2)).selectedOneBasedIndex)
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
