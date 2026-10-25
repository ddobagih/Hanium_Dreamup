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
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
    private val runtime = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/HandsFreeRuntimeSession.kt").readText()
    private val controller =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/HandsFreeVoiceController.kt").readText()

    @Test
    fun theControllerIsGivenTheSpokenReask() {
        val attach = MainActivityRefactorSource.member(runtime, "attachHandsFreeVoiceController")
        assertTrue(attach.contains("lowConfidenceReprompt = input::lowConfidenceReprompt,"))
        assertTrue(MainActivityRefactorSource.member(source.replace("\n            override fun ", "\n    override fun "), "lowConfidenceReprompt").contains("speakHandsFreeLowConfidenceReprompt(onFinished)"))
        val platform = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/AndroidVoiceInputPlatform.kt").readText()
        assertTrue(platform.contains("speakLowConfidenceReprompt = callbacks.lowConfidenceReprompt"))
        assertTrue(controller.contains("playLowConfidenceReprompt = speakLowConfidenceReprompt,"))
        assertTrue(controller.contains("internal const val HANDS_FREE_LOW_CONFIDENCE_REPROMPT_KO = \"다시 말씀해 주세요.\""))
    }

    @Test
    fun theReaskIsSpokenAndItsEndIsReportedOnce() {
        val reprompt = MainActivityRefactorSource.member(source, "speakHandsFreeLowConfidenceReprompt")
        assertTrue(reprompt.contains("output.speakLowConfidenceReprompt("))
        assertTrue(reprompt.contains("message = kr.co.hanium.dreamup.walksafe.voice.HANDS_FREE_LOW_CONFIDENCE_REPROMPT_KO,"))
        assertTrue(reprompt.contains("onCompleted = { finish(kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.COMPLETED) }"))
        assertTrue(reprompt.contains("onFailed = { finish(kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.FAILED) }"))
        assertTrue(reprompt.contains("if (!accepted) finish(kr.co.hanium.dreamup.walksafe.voice.WakeAcknowledgementResult.FAILED)"))
        assertTrue(reprompt.contains("scheduler.runOnMain { if (delivered.compareAndSet(false, true)) onFinished(result) }"))
        assertTrue(reprompt.contains("presentation.lastResult("))
    }
}
