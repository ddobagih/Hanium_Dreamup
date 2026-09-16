package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidanceStartVoicePolicyTest {
    @Test fun explicitStartAndCancel() {
        assertEquals(GuidanceStartVoicePolicy.Choice.START,
            GuidanceStartVoicePolicy.parse(listOf("시작"), 0.9f, false, false))
        assertEquals(GuidanceStartVoicePolicy.Choice.CANCEL,
            GuidanceStartVoicePolicy.parse(listOf("시작하지 마"), 0.9f, false, false))
    }
    @Test fun zeroPlatformConfidenceNeedsUnambiguousExplicitChoice() {
        assertEquals(GuidanceStartVoicePolicy.Choice.START,
            GuidanceStartVoicePolicy.parse(listOf("길안내 시작", "길 안내 시작"), 0f, true, false))
        assertNull(GuidanceStartVoicePolicy.parse(listOf("시작", "취소"), 0f, true, false))
        assertNull(GuidanceStartVoicePolicy.parse(listOf("시작"), 0f, true, true))
        assertNull(GuidanceStartVoicePolicy.parse(listOf("시작"), 0f, false, false))
    }
    @Test fun unrelatedOrUncertainSpeechCannotStart() {
        for (phrase in listOf("네", "서울역", "시작하지 말고 기다려", "다음")) {
            assertNull(GuidanceStartVoicePolicy.parse(listOf(phrase), 1f, true, false))
        }
        assertNull(GuidanceStartVoicePolicy.parse(listOf("시작"), 0.2f, true, false))
        assertNull(GuidanceStartVoicePolicy.parse(emptyList(), null, true, false))
    }
}
