package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13-15: both settings entry points show cause-specific guidance and recheck after returning. */
class MainActivityOfflineKoreanSpeechGuidanceStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private val recovery = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/VoiceDataSettingsRecoveryController.kt").readText()
    private val nativeSettings = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/StartupSettingsNavigation.kt").readText()

    @Test
    fun unsupportedNoticeAndButtonsUseTheProbeCause() {
        val guidance = functionBlock("private fun offlineKoreanSpeechGuidance(")
        assertTrue(guidance.contains("probe.unavailableCause()"))
        assertTrue(MainActivityRefactorSource.factory(source, "createVoiceDataSettingsRecovery").contains("startupCapabilityProbe.offlineKoreanTextToSpeechUnavailableCause()"))
        assertTrue(functionBlock("private fun postLoginDeviceCheckFailureMessage(").contains(
            "if (failure == PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE) offlineKoreanSpeechGuidance().detail"))
        assertTrue(source.contains("override fun koreanSpeechSettingsButtonText() = offlineKoreanSpeechGuidance().settingsButtonText"))
        val preview = functionBlock("private fun applyNativePreviewPresentation(")
        assertTrue(preview.contains("voiceActionLabel = speechGuidance.actionLabel"))
        val presenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt").readText()
        assertTrue(presenter.contains("voiceDataInstallButton.text = readiness.voiceActionLabel"))
        assertTrue(preview.contains("voiceActionContentDescription = speechGuidance.actionContentDescription"))
        assertTrue(presenter.contains("voiceDataInstallButton.contentDescription = readiness.voiceActionContentDescription"))
    }

    @Test
    fun engineChangeOpensTheTtsEngineSettingsFirstAndEveryEntryRechecksOnReturn() {
        val open = functionBlock("private fun openVoiceDataInstallSettings(")
        assertTrue(open.contains("guidance.settingsAction == OfflineKoreanSpeechSettingsAction.CHOOSE_PREFERRED_ENGINE"))
        assertTrue(nativeSettings.contains("if (preferEngine) chooseEngine else installVoiceData"))
        assertTrue(open.contains("recheckPending = true"))
        assertTrue(open.contains("effects.status(guidance.actionLabel, guidance.openedDetail)"))
        val deviceCheck = functionBlock("private fun openPostLoginDeviceCheckSettings(")
        val route = deviceCheck.indexOf("if (failure == PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE) {")
        assertTrue(route >= 0)
        assertTrue(deviceCheck.indexOf("openVoiceDataInstallSettings()") > route)
        assertTrue(deviceCheck.indexOf("openVoiceDataInstallSettings()") < deviceCheck.indexOf("val target = when (failure)"))
        assertFalse("the device-check button no longer bypasses the return recheck",
            deviceCheck.contains("ACTION_INSTALL_TTS_DATA"))
        val recheck = functionBlock("private fun completeVoiceDataInstallRecheckIfPossible(")
        assertTrue(recheck.contains("\${offlineKoreanSpeechGuidance().detail}"))
        assertFalse("the app never selects another engine by itself", source.contains("setEngineByPackageName"))
    }

    private fun functionBlock(signature: String): String {
        val name = signature.substringAfter("fun ").substringBefore("(")
        return MainActivityRefactorSource.member(if (name in setOf("offlineKoreanSpeechGuidance", "openVoiceDataInstallSettings",
            "openPostLoginDeviceCheckSettings", "completeVoiceDataInstallRecheckIfPossible")) recovery else source, name)
    }
}
