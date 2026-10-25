package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Uses only existing entry points so the missing command can be asserted on the original parser. */
class AndroidVoiceCurrentStateCommandRegressionTest {
    @Test
    fun approvedCurrentStateTraversesTheExistingParserActionSelectionAndUiPolicy() {
        for (phrase in listOf("현재 상태", "현재상태", "  현재\t상태  ", "현재\u00a0상태?!", "현재 상태。")) {
            val parsed = parseAndroidVoiceCommand(phrase)
            assertNotNull("The approved current-state request must produce a typed command: $phrase", parsed)
            val command = requireNotNull(parsed)
            // Names avoid referring to absent new subtypes when running this same body on the baseline.
            assertEquals(phrase, "CurrentState", command.javaClass.simpleName)
            val action = command.toAction()
            assertEquals(phrase, "SpeakCurrentState", action.javaClass.simpleName)
            assertEquals(phrase, action, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
            assertEquals(phrase, VoiceCommandUiDestination.CURRENT_VOICE_PAGE, action.uiDestination())
        }
    }

    @Test
    fun currentRouteAndRepeatRequestsKeepTheirExistingNavigationAction() {
        for (phrase in listOf("현재 경로 안내해줘", "현재경로안내해줘", "다시 안내해줘", "다시안내해줘", "다시 말해줘")) {
            assertEquals(phrase, AndroidVoiceCommand.RepeatGuidance, parseAndroidVoiceCommand(phrase))
            assertEquals(phrase, AndroidVoiceAction.SpeakCurrentGuidance,
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
            assertEquals(phrase, VoiceCommandUiDestination.CURRENT_VOICE_PAGE,
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f))?.uiDestination())
        }
    }

    @Test
    fun currentStateStillRequiresTheOriginalTopHypothesisAndConfidenceContract() {
        assertNull(selectAndroidVoiceAction(listOf("지원하지 않는 발화", "현재 상태"), floatArrayOf(0.9f, 0.99f)))
        assertNull(selectAndroidVoiceAction(listOf("현재 상태", "신고해줘"), floatArrayOf(0.3f, 0.99f)))
        assertNull(selectAndroidVoiceAction(listOf("현재 상태"), null))
        assertNull(selectAndroidVoiceAction(listOf("현재 상태", "도움말"), floatArrayOf(0.9f)))
        assertNull(selectAndroidVoiceAction(listOf("현재 상태"), floatArrayOf(Float.NaN)))
        assertNull(selectAndroidVoiceAction(listOf("현재 상태"), floatArrayOf(Float.POSITIVE_INFINITY)))
    }

    @Test
    fun unregisteredNegatedCombinedAndInvisibleControlPhrasesDoNotBecomeCurrentState() {
        for (phrase in listOf("상태", "현재", "현재 상황", "현재 상태 확인", "현재 상태 알려줘",
            "현재 상태 하지 마", "현재 상태 그리고 안내 시작", "현재\u200b상태", "현재\u0000상태")) {
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
            assertNull(phrase, selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
    }

    @Test
    fun destinationNamesContainingCurrentStateRemainDestinationRequests() {
        for (place in listOf("현재상태센터", "현재 상태역")) {
            val phrase = "$place 찾아줘"
            assertEquals(phrase, AndroidVoiceCommand.SetDestination(place), parseAndroidVoiceCommand(phrase))
            assertEquals(phrase, AndroidVoiceAction.SearchDestination(place),
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(0.9f)))
        }
    }

    @Test
    fun destinationSelectionContextDoesNotChangeTheCurrentStateAction() {
        val ordinary = selectAndroidVoiceAction(listOf("현재 상태"), floatArrayOf(0.9f))
        assertNotNull(ordinary)
        assertEquals(ordinary, selectAndroidVoiceAction(listOf("현재 상태"), floatArrayOf(0.9f),
            allowBareDestinationIndex = true))
        assertNull(parseDestinationSearchVoiceCommand("현재 상태", allowBareDestinationIndex = true))
    }
}
