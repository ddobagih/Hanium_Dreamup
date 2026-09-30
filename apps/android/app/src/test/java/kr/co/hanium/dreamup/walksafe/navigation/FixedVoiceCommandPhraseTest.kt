package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** voice-F7: which recognized command texts are fixed phrases (every word decisive for the recognizer). */
class FixedVoiceCommandPhraseTest {
    @Test
    fun parsedNonDestinationCommandsAndWalkPhrasesAreFixed() {
        for (phrase in listOf(
            "다른 경로 찾아줘", "새 경로 요청", "경로 재탐색", "위치 다시 확인", "목적지 취소", "검색 취소", "취소",
            "길안내 종료", "안내 중지", "다음 안내 알려줘", "다시 듣기", "더 듣기", "이전 후보", "2번 선택", "두 번째",
            "보행 종료", "보행 일시정지", "보행 재개", "보행 종료 확인", "보행 종료 취소", "도움말", "신고해줘",
        )) {
            assertTrue(phrase, isFixedVoiceCommandPhrase(phrase))
        }
    }

    @Test
    fun destinationSearchesAndUnparsedTextAreNotFixed() {
        for (phrase in listOf(
            "서울역 찾아줘", "서울역 근처 카페 찾아줘", "강남역으로 안내해줘", "안내 좀 시작해줘", "멈춰", "그만",
            "취소하지 마", "",
        )) {
            assertFalse(phrase, isFixedVoiceCommandPhrase(phrase))
        }
    }
}
