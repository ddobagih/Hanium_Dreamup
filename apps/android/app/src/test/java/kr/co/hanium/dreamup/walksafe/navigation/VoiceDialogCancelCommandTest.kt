package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S05-12 (05.txt:155-157) / S06-21 (06.txt:219): a bare "취소" is limited to the dialog open now; the
 * destination removal ("목적지 취소") and the end of guidance ("길안내 종료") stay separate explicit phrases.
 */
class VoiceDialogCancelCommandTest {
    @Test
    fun bareCancelIsTheDialogCancel() {
        listOf("취소", "취소해", "취소해줘", "취소해주세요", "취소.", "검색 취소", "검색 취소해줘", "후보 선택 취소", "후보 취소")
            .forEach { phrase ->
                assertEquals(phrase, AndroidVoiceCommand.CancelDialog, parseAndroidVoiceCommand(phrase))
                assertEquals(
                    phrase,
                    AndroidVoiceCommand.CancelDialog,
                    parseAndroidVoiceCommand(phrase, allowBareDestinationIndex = true),
                )
                assertEquals(phrase, AndroidVoiceAction.CancelDialog, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
            }
        assertEquals(AndroidVoiceAction.CancelDialog, AndroidVoiceCommand.CancelDialog.toAction())
        assertEquals(VoiceCommandUiDestination.CURRENT_VOICE_PAGE, AndroidVoiceAction.CancelDialog.uiDestination())
    }

    @Test
    fun explicitDestinationAndGuidancePhrasesKeepTheirOwnMeaning() {
        listOf("목적지 취소", "목적지 취소해줘", "목적지를 취소해", "목적지 삭제").forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.CancelDestination, parseAndroidVoiceCommand(phrase))
        }
        listOf("길안내 종료", "길안내 취소", "안내 취소", "안내 중지").forEach { phrase ->
            assertEquals(phrase, AndroidVoiceCommand.StopNavigation, parseAndroidVoiceCommand(phrase))
        }
    }

    @Test
    fun negatedOrLowConfidenceCancelDoesNothing() {
        listOf("취소하지 마", "취소하지 마세요", "취소 안 해", "취소 말고").forEach { phrase ->
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
        }
        assertNull(selectAndroidVoiceAction(listOf("취소"), floatArrayOf(0.30f)))
        assertNull(selectAndroidVoiceAction(listOf("취소"), null))
    }
}
