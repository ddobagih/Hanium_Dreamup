package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 (voice-F1, S04-19; FP-026 "낮은 신뢰도는 no-op 후 재질문"): the hands-free controller's re-ask after a
 * low-confidence command is spoken by the app's own speech path, and its end (completed or failed)
 * is reported back on the UI thread exactly once so the command window can reopen.
 */
class MainActivityHandsFreeLowConfidenceRepromptStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val controller =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/HandsFreeVoiceController.kt").readText()

    @Test
    fun theControllerIsGivenTheSpokenReask() {
        val attach = source.substringAfter("private fun attachHandsFreeVoiceController(")
            .substringBefore("handsFreeVoiceController = controller")
        assertTrue(attach.contains("speakLowConfidenceReprompt = ::speakHandsFreeLowConfidenceReprompt,"))
        assertTrue(controller.contains("playLowConfidenceReprompt = speakLowConfidenceReprompt,"))
        assertTrue(controller.contains("internal const val HANDS_FREE_LOW_CONFIDENCE_REPROMPT_KO = \"다시 말씀해 주세요.\""))
    }

    @Test
    fun theReaskIsSpokenAndItsEndIsReportedOnce() {
        val reprompt = source.substringAfter("private fun speakHandsFreeLowConfidenceReprompt(")
            .substringBefore("\n    }\n")
        assertTrue(reprompt.contains("dispatchOwnedInteractionSpeech("))
        assertTrue(reprompt.contains("message = kr.co.hanium.dreamup.walksafe.voice.HANDS_FREE_LOW_CONFIDENCE_REPROMPT_KO,"))
        assertTrue(reprompt.contains("onCompleted = { finish(kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.COMPLETED) }"))
        assertTrue(reprompt.contains("onFailed = { finish(kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.FAILED) }"))
        assertTrue(reprompt.contains("if (!accepted) finish(kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.FAILED)"))
        assertTrue(reprompt.contains("runOnUiThread { if (delivered.compareAndSet(false, true)) onFinished(result) }"))
        assertTrue(reprompt.contains("showWalkLastResult("))
    }
}
