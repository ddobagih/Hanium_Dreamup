package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * voice-F4 (S05-10 05.txt:125·131·143·146, S05-12/S06-21 06.txt:219): stop and cancel phrases are one
 * table of target × verb × the endings that verb takes. The table's own example "목적지 선택 취소" and
 * "선택 취소" close the candidate choice (CancelDialog); "목적지 취소" still removes the destination and
 * "길안내 종료" still ends guidance. Negated forms are never commands.
 */
class VoiceStopCancelPhraseTableTest {
    private val hadaEndings = listOf("", "해", "해줘", "해주세요")
    private val stopEndings = listOf("", "줘", "주세요")

    @Test
    fun everyGuidanceStopTargetTakesEveryEndingOfItsVerb() {
        for (target in listOf("안내", "길안내", "안내를", "길안내를")) {
            for (verb in listOf("종료", "중지", "취소")) {
                for (ending in hadaEndings) {
                    assertStop("$target $verb$ending")
                }
            }
            for (ending in stopEndings) assertStop("$target 멈춰$ending")
        }
        // The review's failing examples (probe P5/P6) and the original table's (05.txt:146).
        for (phrase in listOf(
            "안내 종료해줘", "길안내 중지해줘", "길안내 종료해주세요", "안내 멈춰", "안내 멈춰줘", "안내 중지해줘",
            "길안내 종료해줘", "안내 종료", "길안내 그만", "내비 중지", "네비 중지",
        )) {
            assertStop(phrase)
        }
    }

    @Test
    fun everyDialogCancelTargetTakesEveryEnding() {
        for (target in listOf("", "검색 ", "후보 ", "후보 선택 ", "선택 ", "목적지 선택 ", "검색을 ", "선택을 ")) {
            for (ending in hadaEndings) {
                val phrase = "${target}취소$ending"
                assertEquals(phrase, AndroidVoiceCommand.CancelDialog, parseAndroidVoiceCommand(phrase))
                assertEquals(phrase, AndroidVoiceCommand.CancelDialog, parseAndroidVoiceCommand(phrase, allowBareDestinationIndex = true))
            }
        }
        // 05.txt:131 names "목적지 선택 취소" as a failing example; it is the candidate choice, not the destination.
        assertEquals(AndroidVoiceCommand.CancelDialog, parseAndroidVoiceCommand("목적지 선택 취소"))
        assertEquals(AndroidVoiceCommand.CancelDialog, parseAndroidVoiceCommand("선택 취소"))
        assertEquals(AndroidVoiceAction.CancelDialog, selectAndroidVoiceAction(listOf("검색 취소해주세요"), floatArrayOf(0.9f)))
    }

    @Test
    fun theExplicitMeaningsStay() {
        for (phrase in listOf("목적지 취소", "목적지 취소해", "목적지 취소해줘", "목적지 취소해주세요", "목적지를 취소", "목적지 삭제")) {
            assertEquals(phrase, AndroidVoiceCommand.CancelDestination, parseAndroidVoiceCommand(phrase))
        }
        assertStop("길안내 종료")
        assertStop("길안내 취소")
        // The walk session phrases stay with the walk policy, not with guidance.
        for (phrase in listOf("보행 종료", "보행 종료 취소", "보행 일시정지")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
        }
        // "골라해줘"-style forms are not generated: 멈춰 takes no 하다 ending.
        for (phrase in listOf("안내 멈춰해", "안내 멈춰해줘", "길안내 멈춰해주세요", "취소줘", "검색 취소주세요")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
        }
    }

    @Test
    fun negatedStopAndCancelPhrasesAreNeverCommands() {
        for (phrase in listOf(
            "검색 취소하지 마", "후보 선택 취소하지 마세요", "선택 취소 안 해", "목적지 선택 취소하지 말아줘",
            "안내 종료하지 마", "길안내 취소 안 해", "안내 중지 안 해요", "길안내 종료하지 말아요", "안내 멈추지 마",
            "취소 안 해줘", "안내 취소하지 말고",
        )) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertNull(phrase, parseAndroidVoiceCommand(phrase, allowBareDestinationIndex = true))
        }
    }

    @Test
    fun aJoinedSecondCommandIsStillRefusedWhole() {
        for (phrase in listOf("선택 취소하고 편의점 찾아줘", "안내 종료하고 서울역 찾아줘", "검색 취소해주고 편의점 찾아줘")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertTrue(phrase, isCompoundVoiceCommand(phrase))
        }
    }

    private fun assertStop(phrase: String) {
        assertEquals(phrase, AndroidVoiceCommand.StopNavigation, parseAndroidVoiceCommand(phrase))
        assertEquals(phrase, AndroidVoiceAction.StopNavigation, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
    }
}
