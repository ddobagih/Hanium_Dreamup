package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandAuditRegressionTest {
    @Test fun routeControlPhrasesNeverBecomePlaceSearches() {
        mapOf(
            "다음 안내해줘" to AndroidVoiceCommand.NextNavigationInstruction,
            "다시 안내해줘" to AndroidVoiceCommand.RepeatGuidance,
            "현재 경로 안내해줘" to AndroidVoiceCommand.RepeatGuidance,
            "다른 경로 찾아줘" to AndroidVoiceCommand.RequestReroute,
            "안내 중지해줘" to AndroidVoiceCommand.StopNavigation,
            "안내 멈춰줘" to AndroidVoiceCommand.StopNavigation,
        ).forEach { (phrase, expected) -> assertEquals(phrase, expected, parseAndroidVoiceCommand(phrase)) }
        assertNull(parseAndroidVoiceCommand("길 안내해줘"))
    }

    @Test fun ambiguousNameSuffixesAndInternalPunctuationArePreserved() {
        mapOf(
            "종로 안내해줘" to "종로", "구로 안내해줘" to "구로",
            "을지로 안내해줘" to "을지로", "세종대로 안내해줘" to "세종대로",
            "낙성대로 안내해줘" to "낙성대로", "학교로 안내해줘" to "학교로",
            "뽀로로 찾아줘" to "뽀로로", "3.1 기념관 찾아줘" to "3.1 기념관",
            "4.19 기념관 찾아줘" to "4.19 기념관",
            "목적지 4.19 기념관 설정해" to "4.19 기념관",
            "4.19 기념관으로 안내해줘." to "4.19 기념관",
        ).forEach { (phrase, expected) ->
            assertEquals(phrase, AndroidVoiceCommand.SetDestination(expected), parseAndroidVoiceCommand(phrase))
            assertEquals(phrase, AndroidVoiceAction.SearchDestination(expected),
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
        assertNull(parseAndroidVoiceCommand("학교로"))
    }

    @Test fun unambiguousDirectionalFormsKeepTheirExistingPlaceContract() {
        mapOf("서울역으로 안내해줘" to "서울역", "종로로 안내해줘" to "종로",
            "학교 로 안내해줘" to "학교", "목적지 종로 설정해" to "종로",
            "목적지를 종로로 변경해" to "종로").forEach { (phrase, expected) ->
            assertEquals(phrase, AndroidVoiceCommand.SetDestination(expected), parseAndroidVoiceCommand(phrase))
        }
    }

    @Test fun placeSubstringsAreNotNegativeCommandsButActualNegativeOrCombinedCommandsAreRejected() {
        for (place in listOf("태안 해변", "가지마을", "하지마을")) {
            assertEquals(AndroidVoiceCommand.SetDestination(place), parseAndroidVoiceCommand("$place 찾아줘"))
        }
        listOf("서울역 안 가고 싶어", "서울역 말고 편의점 찾아줘", "설정 열어줘 그리고 편의점 찾아줘",
            "안내 시작하고 서울역 찾아줘", "안내 시작하지 마", "1번 선택하지 마",
            "서울역으로 가지마", "서울역으로 가지 마세요", "학교로 안내하지 말아줘").forEach {
            assertNull(it, parseAndroidVoiceCommand(it, true))
        }
    }

    @Test fun namesStartingWithNegativeVerbSyllablesRemainSearchable() {
        for (place in listOf("안하마을", "안해약국", "안가네 식당", "안가요식당")) {
            assertEquals(place, AndroidVoiceCommand.SetDestination(place), parseAndroidVoiceCommand("$place 찾아줘"))
            assertEquals(place, AndroidVoiceAction.SearchDestination(place),
                selectAndroidVoiceAction(listOf("$place 찾아줘"), floatArrayOf(0.9f)))
        }
    }

    @Test fun separatedNegativeAdverbBeforeAMatchedDestinationCommandDoesNotSearch() {
        for (phrase in listOf("서울역 안 안내해줘", "서울역 안 찾아줘", "목적지 서울역 안 설정해",
            "서울역 안가고싶어", "서울역 안 가고 싶어요", "취소하지 마",
            "안 가요 서울역 찾아줘", "안 해 주세요 서울역 찾아줘")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertNull(phrase, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
        assertEquals(AndroidVoiceCommand.SetDestination("서울역 안"), parseAndroidVoiceCommand("서울역 안으로 안내해줘"))
    }

    @Test fun joinedNegativeVerbInflectionsDoNotBecomeDestinationNames() {
        for (phrase in listOf("서울역은 안가요 편의점 찾아줘", "안해줘 서울역 찾아줘",
            "안하고 서울역 찾아줘", "안해주세요 서울역 찾아줘", "안해주고 서울역 찾아줘",
            "안가고싶어요 서울역 찾아줘")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertNull(phrase, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
    }

    @Test fun unsupportedExclusionWithAnAttachedAlternativeNeverBecomesASearch() {
        for (phrase in listOf("서울역 말고편의점 찾아줘", "목적지 서울역 말고편의점 설정해",
            "서울역 말고편의점 목적지로 해줘", "서울역 말고편의점으로 안내해줘",
            "서울역\u00a0말고편의점 찾아줘", "서울역 말고 편의점 찾아줘", "서울역말고 편의점 찾아줘")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertNull(phrase, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
    }

    @Test fun aPlaceStartingWithMalgoIsPreservedAcrossExplicitDestinationForms() {
        for ((phrase, place) in mapOf("말고개 찾아줘" to "말고개", "말고개 공원 찾아줘" to "말고개 공원",
            "목적지 말고개 설정해" to "말고개", "말고개 목적지로 해줘" to "말고개",
            "말고개역으로 안내해줘" to "말고개역")) {
            assertEquals(phrase, AndroidVoiceCommand.SetDestination(place), parseAndroidVoiceCommand(phrase))
            assertEquals(phrase, AndroidVoiceAction.SearchDestination(place),
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
    }

    @Test fun naturalMovementParticlesDoNotTrimLookupNames() {
        mapOf(
            "서울역으로 가고 싶어" to "서울역",
            "서울역으로가고싶어" to "서울역",
            "종로로 가고 싶어" to "종로",
            "종로 가고 싶어" to "종로",
            "뽀로로 찾아줘" to "뽀로로",
            "서울역으로 찾아줘" to "서울역으로",
        ).forEach { (phrase, query) ->
            assertEquals(phrase, AndroidVoiceCommand.SetDestination(query), parseAndroidVoiceCommand(phrase))
        }
    }

    @Test fun sharedCandidateGrammarDoesNotWeakenCurrentPageSelection() {
        mapOf("삼 번 선택해줘" to 3, "여섯 번째 선택" to 6, "첫 번째 선택" to 1,
            "3번 골라줘" to 3, "첫 번째 목적지 선택해줘" to 1, "３번 선택" to 3).forEach { (phrase, index) ->
            assertEquals(phrase, AndroidVoiceCommand.SelectDestinationCandidate(index), parseAndroidVoiceCommand(phrase))
            assertEquals(phrase, DestinationSearchVoiceCommand.SelectCandidate(index), parseDestinationSearchVoiceCommand(phrase))
        }
        val candidates = (1..6).map { DestinationSearchResult("$it", "후보$it", RoutePoint(37.0, 127.0), null, null, null, null) }
        val secondPage = DestinationSearchVoiceState("후보", candidates, pageIndex = 1)
        assertTrue(secondPage.completeVoicePrompt(secondPage.beginVoicePrompt()))
        assertFalse(secondPage.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertTrue(secondPage.onCommand(DestinationSearchVoiceCommand.SelectCandidate(6)).accepted)
        assertNull(parseAndroidVoiceCommand("3번"))
        assertNull(parseDestinationSearchVoiceCommand("3번"))
    }

    @Test fun numberSeparatorsCannotBeErasedIntoAnotherCandidate() {
        for (phrase in listOf("1.0번 선택", "1, 2번 선택", "１．０번 선택", "1 2번 선택", "21번 선택",
            "1.0번", "1, 2번", "1 2번", "1.0", "1, 2")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase, true))
            assertNull(phrase, parseDestinationSearchVoiceCommand(phrase, true))
        }
    }

    @Test fun leadingDecimalPointCannotBecomeAnIntegerCandidate() {
        for (phrase in listOf(".1번 선택", ".1번", ".1")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase, true))
            assertNull(phrase, parseDestinationSearchVoiceCommand(phrase, true))
        }
    }

    @Test fun punctuationBetweenSpokenNumbersCannotJoinThemIntoOneCandidate() {
        for (phrase in listOf("십, 이 번 선택", "십, 이 번", "이, 십 번 선택")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase, true))
            assertNull(phrase, parseDestinationSearchVoiceCommand(phrase, true))
        }
        assertEquals(AndroidVoiceCommand.SelectDestinationCandidate(12), parseAndroidVoiceCommand("십이 번 선택."))
        assertEquals(DestinationSearchVoiceCommand.SelectCandidate(12), parseDestinationSearchVoiceCommand("십이 번 선택."))
    }

    @Test fun sentenceEndingPunctuationDoesNotChangeAValidCandidateNumber() {
        for ((phrase, index) in mapOf("1번." to 1, "12번 선택." to 12, "10번 선택해주세요!" to 10)) {
            assertEquals(phrase, AndroidVoiceCommand.SelectDestinationCandidate(index),
                parseAndroidVoiceCommand(phrase, true))
            assertEquals(phrase, DestinationSearchVoiceCommand.SelectCandidate(index),
                parseDestinationSearchVoiceCommand(phrase, true))
        }
    }

    @Test fun nonNumericCandidateCommandsKeepTheirPunctuationNormalization() {
        assertEquals(AndroidVoiceCommand.RepeatDestinationCandidates, parseAndroidVoiceCommand("다시, 듣기"))
        assertEquals(DestinationSearchVoiceCommand.RepeatPage, parseDestinationSearchVoiceCommand("다시, 듣기"))
        assertEquals(AndroidVoiceCommand.HearMoreDestinationCandidates, parseAndroidVoiceCommand("더, 듣기"))
        assertEquals(DestinationSearchVoiceCommand.HearMore, parseDestinationSearchVoiceCommand("더, 듣기"))
    }

    @Test fun unicodeSpacesWorkButHiddenControlsAndInvalidConfidenceDoNotExecute() {
        assertEquals(AndroidVoiceCommand.StartNavigation, parseAndroidVoiceCommand("안내\u00a0시작"))
        assertNull(parseAndroidVoiceCommand("서울\u0000역 찾아줘"))
        assertNull(canonicalDestinationSearchQueryOrNull("서울\u200b역"))
        assertNull(selectAndroidVoiceAction(listOf("신고해줘")))
        assertNull(selectAndroidVoiceAction(listOf("신고해줘"), floatArrayOf()))
        assertNull(selectAndroidVoiceAction(listOf("신고해줘"), floatArrayOf(1.5f)))
        assertNull(selectAndroidVoiceAction(listOf("신고해줘"), floatArrayOf(0.9f), Float.NaN))
        assertEquals(AndroidVoiceAction.CreateReport, selectAndroidVoiceAction(listOf("신고해줘"), floatArrayOf(0.9f)))
    }
}
