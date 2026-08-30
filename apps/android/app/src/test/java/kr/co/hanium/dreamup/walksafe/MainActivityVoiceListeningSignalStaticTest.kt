package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FP-025 는 듣기 시작·종료 신호를 요구한다. 지금까지 그 신호는 버튼 글자와 화면 상태 문자열뿐이라
 * 화면을 보는 사람에게만 도착했다. 이 앱의 주 사용자에게는 아무 신호도 없었다.
 */
class MainActivityVoiceListeningSignalStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val actuator =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt")
            .readText()

    @Test
    fun listeningStartAndEndReachTheUserWithoutTheScreen() {
        val ready = source.substringAfter("override fun onReadyForSpeech(")
            .substringBefore("override fun onBeginningOfSpeech()")
        assertTrue(ready.contains("signalVoiceListening(started = true)"))

        val end = source.substringAfter("override fun onEndOfSpeech()")
            .substringBefore("override fun onEvent(")
        assertTrue(end.contains("signalVoiceListening(started = false)"))

        // 실패로 끝나도 끝 신호가 나가야 한다. 없으면 계속 듣고 있는 줄 안다.
        val error = source.substringAfter("override fun onError(error: Int)")
            .substringBefore("override fun onResults(")
        assertTrue(error.contains("signalVoiceListening(started = false)"))
    }

    @Test
    fun theSignalIsHapticAndDoesNotCollideWithRisk() {
        assertTrue(actuator.contains("fun playVoiceListeningStartVibration(): Boolean = vibrate(longArrayOf(0L, 35L))"))
        assertTrue(
            actuator.contains(
                "fun playVoiceListeningEndVibration(): Boolean = vibrate(longArrayOf(0L, 35L, 45L, 35L))",
            ),
        )

        // 위험 신호는 단발 220ms 와 점점 길어지는 3연타다. 듣기 신호가 그것들과 같으면 안 된다.
        assertFalse(actuator.contains("playVoiceListeningStartVibration(): Boolean = vibrate(longArrayOf(0L, 220L))"))
        assertFalse(actuator.contains("playVoiceListeningStartVibration(): Boolean = vibrate(longArrayOf(0L, 120L))"))
    }

    @Test
    fun theSignalIsNotSilencedByTheRuleThatSilencesOtherFeedback() {
        val signal = source.substringAfter("private fun signalVoiceListening(started: Boolean)")
            .substringBefore("\n    private fun ")

        // 그 규칙은 인식 중 다른 안내가 끼어드는 것을 막는 것이고, 이 신호는 인식 그 자체를 알린다.
        assertFalse(signal.contains("shouldSuppressFeedbackDuringVoiceRecognition"))
    }
}
