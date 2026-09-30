package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCompoundCommandRegressionTest {
    @Test
    fun aJoinedClauseThatIsItselfACommandNeverBecomesAPlaceSearch() {
        listOf(
            "도움말 그리고 서울역 찾아줘",
            "다음 안내 알려줘 그리고 편의점 찾아줘",
            "다시 말해줘 그리고 편의점 찾아줘",
            "위치 다시 확인 그리고 서울역으로 안내해줘",
            "1번 선택 그리고 서울역 찾아줘",
            "더 듣기 그리고 편의점 찾아줘",
            "도착했어 그 다음에 편의점 찾아줘",
            "목적지 도움말 그리고 서울역 설정해",
            "신고해줘 그리고 편의점 찾아줘",
            "설정 열어줘 그리고 편의점 찾아줘",
            "안내 시작하고 서울역 찾아줘",
            // A bare candidate number is a selection in the candidate dialog, so it is a command clause.
            "2번 그리고 편의점 찾아줘",
            "두 번째 그리고 서울역 찾아줘",
        ).forEach { phrase ->
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertNull(phrase, parseAndroidVoiceCommand(phrase, allowBareDestinationIndex = true))
            assertNull(phrase, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
    }

    // S05-6 (05.txt:69-78): two actions joined by a connective ending or a second imperative, no conjunction.
    @Test
    fun aConnectiveEndingOrASecondImperativeJoiningTwoCommandsIsRefused() {
        listOf(
            "다시 말해주고 편의점 찾아줘",
            "도움말 알려주고 서울역 찾아줘",
            "다음 안내 알려주고 편의점 찾아줘",
            "설정 열고 편의점 찾아줘",
            "설정 열어주고 편의점 찾아줘",
            "위치 다시 확인하고 서울역 찾아줘",
            "위치 확인해주고 서울역 찾아줘",
            "1번 선택하고 서울역 찾아줘",
            "더 듣고 편의점 찾아줘",
            "도착했고 편의점 찾아줘",
            "경로 재탐색하고 서울역 찾아줘",
            "다른 경로 찾고 편의점 찾아줘",
            "서울역 찾고 편의점 찾아줘",
            "서울역으로 가고 편의점 찾아줘",
            "안내 멈추고 편의점 찾아줘",
            "도움말하고 서울역 찾아줘",
            "다시 안내해주고 서울역으로 안내해줘",
            "다시 말해줘 편의점 찾아줘",
            "신고해줘 편의점 찾아줘",
        ).forEach { phrase ->
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertNull(phrase, parseAndroidVoiceCommand(phrase, allowBareDestinationIndex = true))
            assertNull(phrase, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
            assertTrue(phrase, isCompoundVoiceCommand(phrase))
        }
    }

    @Test
    fun placeWordsEndingInGoOrHagoAreStillOneSearch() {
        mapOf(
            "고려대 찾아줘" to "고려대",
            "마포고 앞 편의점 찾아줘" to "마포고 앞 편의점",
            "원주고 찾아줘" to "원주고",
            "진주고 정문 찾아줘" to "진주고 정문",
            "커피하고 빵 찾아줘" to "커피하고 빵",
            "서울역하고 시청 찾아줘" to "서울역하고 시청",
        ).forEach { (phrase, place) ->
            assertEquals(phrase, AndroidVoiceCommand.SetDestination(place), parseAndroidVoiceCommand(phrase))
            assertFalse(phrase, isCompoundVoiceCommand(phrase))
        }
        assertEquals(AndroidVoiceCommand.SetDestination("서울역에"), parseAndroidVoiceCommand("서울역에 가고 싶어"))
        for (phrase in listOf("서울역 찾아줘", "도움말", "편의점 말고 서울역 찾아줘", "목적지 취소", "알 수 없는 말")) {
            assertFalse(phrase, isCompoundVoiceCommand(phrase))
        }
        // The original conjunction cases are compound too.
        assertTrue(isCompoundVoiceCommand("설정 열어줘 그리고 편의점 찾아줘"))
        assertTrue(isCompoundVoiceCommand("안내 시작하고 서울역 찾아줘"))
    }

    @Test(timeout = 5_000L)
    fun manyJoinedWordsStayBounded() {
        val phrase = List(19) { "가하고" }.joinToString(" ") + " 찾아줘"
        parseAndroidVoiceCommand(phrase)
        isCompoundVoiceCommand(phrase)
        val longSearch = List(30) { "서울역 찾고" }.joinToString(" ") + " 찾아줘"
        assertNull(parseAndroidVoiceCommand(longSearch))
    }

    @Test
    fun aConjunctionBetweenPlaceWordsIsStillOneSearch() {
        mapOf(
            "커피 그리고 빵 찾아줘" to "커피 그리고 빵",
            "서울역 그 다음에 시청 찾아줘" to "서울역 그 다음에 시청",
            "그리고 카페 찾아줘" to "그리고 카페",
        ).forEach { (phrase, place) ->
            assertEquals(phrase, AndroidVoiceCommand.SetDestination(place), parseAndroidVoiceCommand(phrase))
        }
        assertEquals(AndroidVoiceCommand.Help, parseAndroidVoiceCommand("도움말"))
        assertEquals(AndroidVoiceCommand.SetDestination("서울역"), parseAndroidVoiceCommand("서울역으로 안내해줘"))
    }
}
