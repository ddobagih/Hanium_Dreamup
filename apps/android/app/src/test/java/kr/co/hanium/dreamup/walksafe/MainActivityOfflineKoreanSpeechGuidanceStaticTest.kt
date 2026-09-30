package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13-15: both settings entry points show cause-specific guidance and recheck after returning. */
class MainActivityOfflineKoreanSpeechGuidanceStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun unsupportedNoticeAndButtonsUseTheProbeCause() {
        val guidance = functionBlock("private fun offlineKoreanSpeechGuidance(")
        assertTrue(guidance.contains("startupCapabilityProbe.offlineKoreanTextToSpeechUnavailableCause()"))
        assertTrue(functionBlock("private fun postLoginDeviceCheckFailureMessage(").contains(
            "PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE ->\n            offlineKoreanSpeechGuidance().detail"))
        assertTrue(functionBlock("private fun refreshStartupCapabilityUi(").contains("offlineKoreanSpeechGuidance().settingsButtonText"))
        val preview = functionBlock("private fun applyNativePreviewPresentation(")
        assertTrue(preview.contains("voiceDataInstallButton.text = guidance.actionLabel"))
        assertTrue(preview.contains("voiceDataInstallButton.contentDescription = guidance.actionContentDescription"))
    }

    @Test
    fun engineChangeOpensTheTtsEngineSettingsFirstAndEveryEntryRechecksOnReturn() {
        val open = functionBlock("private fun openVoiceDataInstallSettings(")
        assertTrue(open.contains("OfflineKoreanSpeechSettingsAction.CHOOSE_PREFERRED_ENGINE) chooseEngine else installVoiceData"))
        assertTrue(open.contains("voiceDataInstallRecheckPending = true"))
        assertTrue(open.contains("updateStatus(guidance.actionLabel, guidance.openedDetail)"))
        val deviceCheck = functionBlock("private fun openPostLoginDeviceCheckSettings(")
        val route = deviceCheck.indexOf("if (failure == PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE) {")
        assertTrue(route >= 0)
        assertTrue(deviceCheck.indexOf("openVoiceDataInstallSettings()") > route)
        assertTrue(deviceCheck.indexOf("openVoiceDataInstallSettings()") < deviceCheck.indexOf("val intent = when (failure)"))
        assertFalse("the device-check button no longer bypasses the return recheck",
            deviceCheck.contains("ACTION_INSTALL_TTS_DATA"))
        val recheck = functionBlock("private fun completeVoiceDataInstallRecheckIfPossible(")
        assertTrue(recheck.contains("\${offlineKoreanSpeechGuidance().detail}"))
        assertFalse("the app never selects another engine by itself", source.contains("setEngineByPackageName"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = Regex("""\n    (private |internal |override )?fun """).find(source, start + signature.length)
        return if (next == null) source.substring(start) else source.substring(start, next.range.first)
    }
}
