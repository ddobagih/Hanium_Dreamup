package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오프라인 한국어 음성이 없으면 보행을 시작하지 않는다(`RQ-FP-027-001`). 그것은 그대로 두되,
 * `RQ-FP-029-001` 이 요구하는 대로 **회복 가능한 문제에는 설정 이동을 준다.** 음성 데이터는
 * 내려받으면 풀리는 문제라 「이 휴대폰에서는 시작할 수 없습니다」로 끝내면 안 된다.
 */
class MainActivityVoiceDataRecoveryStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val capability =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt")
            .readText()

    @Test
    fun onlyTheRequirementsAUserCanInstallOfferSettings() {
        // 카메라·ARCore 처럼 기기가 못 하는 것과 구분한다.
        assertTrue(capability.contains("val USER_INSTALLABLE_REQUIREMENTS"))
        assertTrue(capability.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(capability.contains("WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS"))
        assertFalse(
            capability.substringAfter("val USER_INSTALLABLE_REQUIREMENTS")
                .substringBefore("enum class")
                .contains("CAMERA"),
        )

        assertTrue(
            source.contains(
                "decision.unavailableRequirements\n            .filter { it in USER_INSTALLABLE_REQUIREMENTS }",
            ),
        )
    }

    @Test
    fun theBlockedNoticeSaysWhatTheUserCanDo() {
        assertTrue(source.contains("append(\"\\n다음 행동: \")"))
        assertTrue(source.contains("휴대폰 설정에서 음성 데이터를 내려받으면 사용할 수 있습니다."))

        // 버튼은 그 항목이 막고 있을 때만 나온다.
        assertTrue(
            source.contains("if (installableBlocking.isEmpty()) View.GONE else View.VISIBLE"),
        )
    }

    @Test
    fun thePressNeverDoesNothing() {
        val open = source.substringAfter("private fun openVoiceDataInstallSettings()")
            .substringBefore("\n    private fun ")

        // 엔진마다 화면이 다르므로 표준 설치 intent 부터 차례로 내려간다.
        assertTrue(open.contains("TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA"))
        assertTrue(open.contains("Settings.ACTION_VOICE_INPUT_SETTINGS"))

        // 어디로도 갈 수 없으면 그 사실을 말한다. 눌렀는데 아무 일도 안 일어나면 안 된다.
        assertTrue(open.contains("if (!opened)"))
        assertTrue(open.contains("speakInteraction("))
    }
}
