package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arrival queries must never provide the evidence that ends a walk. */
class MainActivityExplicitArrivalRequestStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt").readText()

    @Test
    fun voiceArrivalRequestsReadStateWithoutOpeningAConfirmation() {
        val dispatch = MainActivityRefactorSource.member(source, "executeVoiceAction")
        assertTrue(dispatch.contains("AndroidVoiceAction.ConfirmArrival,"))
        assertTrue(dispatch.contains("AndroidVoiceAction.RejectArrival -> output.speakCurrentState()"))
        assertFalse(source.contains("requestArrivalConfirmation("))
        assertFalse(source.contains("route.confirmArrival("))
        assertFalse(source.contains("navigator.rejectArrival("))
        for (phrase in listOf("현재 상태", "남은 거리", "남은 거리 알려줘")) {
            assertEquals(phrase, AndroidVoiceAction.SpeakCurrentState,
                selectAndroidVoiceAction(listOf(phrase), floatArrayOf(.9f)))
        }
    }
}
