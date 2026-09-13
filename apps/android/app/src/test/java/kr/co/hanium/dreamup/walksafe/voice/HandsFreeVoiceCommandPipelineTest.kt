package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.selectAndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeVoiceCommandPipelineTest {
    @Test
    fun combinedWakeAndCommandWaitsForOneAcknowledgementBeforeDispatch() {
        val extraction = WakePhraseCommandExtractor.extractFinalTranscript("길라잡이, 서울역으로 안내해줘")
            as WakePhraseCommandExtraction.Command
        val machine = HandsFreeVoiceStateMachine()
        val wake = machine.startIfEligible(WalkSessionState.ACTIVE, voiceConsentGranted = true, modelsReady = true)
        val acknowledging = machine.onWakeWordDetected(wake.state.generation)
        assertFalse(machine.onCommandRecognized(acknowledging.state.generation, extraction.text).accepted)

        val commandWindow = machine.onWakeAcknowledgementFinished(acknowledging.state.generation)
        val processing = machine.onCommandRecognized(commandWindow.state.generation, extraction.text)
        assertEquals(
            AndroidVoiceAction.SearchDestination("서울역"),
            selectAndroidVoiceAction(
                listOf((processing.state as HandsFreeVoiceState.ProcessingCommand).command),
                floatArrayOf(0.9f),
            ),
        )
        assertFalse(machine.onCommandRecognized(commandWindow.state.generation, extraction.text).accepted)
        assertFalse(machine.onWakeAcknowledgementFinished(acknowledging.state.generation).accepted)
    }

    @Test
    fun oneUtteranceWakePhraseAndDestinationUsesExistingCommandParser() {
        val extracted = WakePhraseCommandExtractor.extractFinalTranscript(
            "길라잡이, 서울역으로 안내해줘",
        )

        assertTrue(extracted is WakePhraseCommandExtraction.Command)
        val command = (extracted as WakePhraseCommandExtraction.Command).text
        assertEquals("서울역으로 안내해줘", command)
        assertEquals(
            AndroidVoiceAction.SearchDestination("서울역"),
            selectAndroidVoiceAction(listOf(command), floatArrayOf(0.90f)),
        )
    }

    @Test
    fun wakePhraseOnlyThenFollowUpCommandUsesExistingCommandParser() {
        assertEquals(
            WakePhraseCommandExtraction.AwaitingCommand,
            WakePhraseCommandExtractor.extractFinalTranscript("길라잡이"),
        )
        val extracted = WakePhraseCommandExtractor.extractFinalTranscript(
            transcript = "다음 안내 알려줘",
            mode = WakePhraseCommandMode.COMMAND_AWAITED,
        )

        assertEquals(
            AndroidVoiceAction.SpeakNextNavigationInstruction,
            selectAndroidVoiceAction(
                listOf((extracted as WakePhraseCommandExtraction.Command).text),
                floatArrayOf(0.90f),
            ),
        )
    }
}
