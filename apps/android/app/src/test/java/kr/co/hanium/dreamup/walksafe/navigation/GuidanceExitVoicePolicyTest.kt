package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidanceExitVoicePolicyTest {
    @Test fun explicitChoicesAllowSpacingAndPunctuation() {
        assertEquals(GuidanceExitVoicePolicy.Choice.RETURN,
            GuidanceExitVoicePolicy.parse(listOf("안내로 돌아가기."), 0.8f, false))
        assertEquals(GuidanceExitVoicePolicy.Choice.END,
            GuidanceExitVoicePolicy.parse(listOf("안내 종료!", "안내종료"), 0.8f, false))
    }
    @Test fun negationAndUnrelatedCommandsNeverEndWalk() {
        for (phrase in listOf("종료하지 마", "네", "서울역으로 안내해줘", "설정 열어줘", "")) {
            assertNull(GuidanceExitVoicePolicy.parse(listOf(phrase), 0.99f, false))
        }
        assertEquals(GuidanceExitVoicePolicy.Choice.RETURN,
            GuidanceExitVoicePolicy.parse(listOf("보행 종료 취소"), 0.9f, false))
    }
    @Test fun uncertainOrConflictingRecognitionCannotChoose() {
        for (score in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 0f, 0.54f)) {
            assertNull(GuidanceExitVoicePolicy.parse(listOf("안내 종료"), score, false))
        }
        assertNull(GuidanceExitVoicePolicy.parse(listOf("안내 종료", "돌아가기"), 0.9f, false))
        assertNull(GuidanceExitVoicePolicy.parse(listOf("안내 종료"), 0.9f, true))
        assertNull(GuidanceExitVoicePolicy.parse(emptyList(), 0.9f, false))
    }
}
