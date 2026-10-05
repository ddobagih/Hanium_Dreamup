package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceHelpPolicyTest {
    @Test fun numberedAlternativesAgreeAfterNormalization() {
        assertEquals(1, VoiceHelpPolicy.parse(listOf("1번.", "일 번", "첫 번째")))
        assertEquals(2, VoiceHelpPolicy.parse(listOf("2번", "두 번째")))
        assertEquals(3, VoiceHelpPolicy.parse(listOf("3번", "문제 해결")))
    }
    @Test fun differentAlternativesNeverSelectAnItem() {
        assertNull(VoiceHelpPolicy.parse(listOf("1번", "2번")))
        assertNull(VoiceHelpPolicy.parse(listOf("모르겠어요", "1번")))
        assertNull(VoiceHelpPolicy.parse(emptyList()))
    }
    @Test fun menuRepeatAndExplanationRepeatAreDifferent() {
        assertEquals(4, VoiceHelpPolicy.parse(listOf("4번", "목록 다시 듣기")))
        assertEquals(5, VoiceHelpPolicy.parse(listOf("다시 듣기", "다시 말해줘")))
    }
    @Test fun closeIsExplicitAndNegationDoesNotClose() {
        assertEquals(0, VoiceHelpPolicy.parse(listOf("도움말 닫기")))
        assertNull(VoiceHelpPolicy.parse(listOf("도움말 닫지 마")))
    }
    @Test fun unrelatedCommandsCannotBecomeHelpSelections() {
        assertNull(VoiceHelpPolicy.parse(listOf("서울역으로 안내해줘")))
        assertNull(VoiceHelpPolicy.parse(listOf("5번")))
    }
    @Test fun closeAcceptsNaturalPhrasingAndIrrelevantLowerCandidates() {
        assertEquals(0, VoiceHelpPolicy.parse(listOf("도움말 닫기", "도움말 달기", "도움말 닫아줘")))
        assertEquals(0, VoiceHelpPolicy.parse(listOf("도움말 닫아 주세요.")))
        assertNull(VoiceHelpPolicy.parse(listOf("도움말 닫지 마", "도움말 닫기")))
        assertNull(VoiceHelpPolicy.parse(listOf("도움말 닫기", "4번")))
    }
}
