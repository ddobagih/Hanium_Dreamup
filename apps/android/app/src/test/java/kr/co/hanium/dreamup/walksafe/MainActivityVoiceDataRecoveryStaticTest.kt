package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityVoiceDataRecoveryStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val capabilitySource =
        File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/device/" +
                "WalkSafeStartupCapability.kt",
        ).readText()
    private val probeSource =
        File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/device/" +
                "AndroidStartupCapabilityProbe.kt",
        ).readText()

    private fun owner(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()
    private val recoverySource = owner("startup/interaction/VoiceDataSettingsRecoveryController.kt")
    private fun member(text: String, name: String) = MainActivityRefactorSource.member(text, name)

    @Test
    fun onlyOfflineKoreanTtsIsUserInstallable() {
        val installable = section(capabilitySource, "val USER_INSTALLABLE_REQUIREMENTS", "data class WalkSafeStartupCapabilityInput(")
        val names = Regex("WalkSafeStartupRequirement\\.([A-Z_]+)").findAll(installable).map { it.groupValues[1] }.toList()
        assertEquals(listOf("OFFLINE_KOREAN_TTS"), names)
        val panel = owner("ui/OnboardingReadinessPanel.kt")
        val presenter = owner("ui/DeviceCheckPresenter.kt")
        assertTrue(panel.contains("val voiceDataInstallButton: Button"))
        assertTrue(panel.contains("addView(voiceDataInstallButton)"))
        assertTrue(presenter.contains("voiceDataInstallVisible = capability.installableUnavailableLabels.isNotEmpty()"))
        assertTrue(presenter.contains("panel.voiceDataInstallButton.visibility = if (presentation.voiceDataInstallVisible) View.VISIBLE else View.GONE"))
    }

    @Test
    fun recoveryTriesTtsInstallThenTtsSettingsThenAppSettings() {
        val native = owner("startup/interaction/AndroidStartupSettingsNative.kt")
        val candidates = member(owner("startup/interaction/StartupSettingsNavigation.kt"), "openVoiceCandidates")
        val open = member(recoverySource, "openVoiceDataInstallSettings")
        assertTrue(native.contains("Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)"))
        assertTrue(native.contains("Intent(\"com.android.settings.TTS_SETTINGS\")"))
        assertTrue(native.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertInOrder(candidates, "native.installVoiceData()", "native.chooseVoiceEngine()",
            "if (preferEngine) chooseEngine else installVoiceData", "if (preferEngine) installVoiceData else chooseEngine",
            "native.applicationSettings()", "candidates.any")
        assertInOrder(open, "settings.openVoiceCandidates(", "if (opened)", "recheckPending = true", "return", "effects.status")
        assertTrue(source.contains("const val ANDROID_TTS_SETTINGS_ACTION = \"com.android.settings.TTS_SETTINGS\""))
        assertFalse(native.contains("Settings.ACTION_VOICE_INPUT_SETTINGS"))
    }

    @Test
    fun returningFromSettingsRequestsAFreshOfflineKoreanTtsProbe() {
        val resume = member(source, "resumeWalkSafeRuntimeAfterPrivacyStartupInspection")
        val recheck = member(recoverySource, "recheckVoiceDataAfterSettingsIfNeeded")
        val replace = member(recoverySource, "startReplacedProbe")
        val factory = MainActivityRefactorSource.factory(source, "createVoiceDataSettingsRecovery")
        assertTrue(resume.contains("recheckVoiceDataAfterSettingsIfNeeded()"))
        assertTrue(recheck.contains("probe.recheck()"))
        assertTrue(recheck.contains("startReplacedProbe()"))
        assertTrue(factory.contains("startupCapabilityProbe.recheckOfflineKoreanTextToSpeech()"))
        assertTrue(factory.contains("override fun close() = privacyStartupResources.releaseOnUiThread(startupCapabilityProbe)"))
        assertTrue(factory.contains("startupCapabilityProbe = privacyStartupResources.acquireOnUiThread({ AndroidStartupCapabilityProbe(this@MainActivity) { onChanged() } }, { it.close() })"))
        assertTrue(recheck.contains("probe.started = false"))
        assertInOrder(replace, "probe.close()", "probe.replace", "probe.started = true", "probe.start()")
        assertTrue(member(probeSource, "recheckOfflineKoreanTextToSpeech").contains("probeOfflineKoreanTextToSpeech(currentGeneration, notifyPending = true)"))
        assertTrue(probeSource.contains("offlineKoreanTextToSpeechProbeState.begin()"))
        assertTrue(probeSource.contains("offlineKoreanTextToSpeechProbeState.complete("))
        assertTrue(probeSource.contains("offlineKoreanTextToSpeechProbeState.close()"))
        assertTrue(probeSource.contains("internal class OfflineKoreanTextToSpeechProbeState"))
    }

    @Test
    fun successfulRecheckRemovesOnlyVoiceGuidanceAndPersistsTheRecoveredResult() {
        val device = owner("device/PostLoginDeviceCheckController.kt")
        val recovered = member(device, "recoverKoreanSpeechPassedGate")
        assertTrue(recovered.contains("current.disabledFeatures - PostLoginDeviceCheckFeature.VOICE_GUIDANCE"))
        assertTrue(recovered.contains("current.copy("))
        assertTrue(recovered.contains("PostLoginDeviceCheckState.FULL"))
        assertTrue(recovered.contains("PostLoginDeviceCheckState.LIMITED"))
        assertTrue(recovered.contains("disabledFeatures = remaining"))
        assertInOrder(recovered, "savedResults.save(next, binding", "publish(next)")
        assertTrue(member(device, "publish").contains("current = next"))
        val completion = member(recoverySource, "completeVoiceDataInstallRecheckIfPossible")
        assertInOrder(completion, "if (!effects.recoverPassedGate())", "return", "effects.offlineTtsOverride = true")
        val factory = MainActivityRefactorSource.factory(source, "createVoiceDataSettingsRecovery")
        assertTrue(factory.contains("postLoginDeviceCheckController.recoverKoreanSpeechPassedGate()"))
        assertTrue(factory.contains("offlineKoreanTextToSpeechCapabilityOverride = value"))
    }

    @Test
    fun missingKoreanTtsBlocksDeviceCheckAndWalkStartOrResume() {
        val device = owner("device/PostLoginDeviceCheckController.kt")
        assertTrue(device.contains("koreanTextToSpeechAvailable == false -> PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE"))
        val restrictions = MainActivityRefactorSource.function(source, "private fun applyPostLoginDeviceFeatureRestrictions(")
        assertTrue(restrictions.contains("WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in unavailable"))
        assertTrue(restrictions.contains("WalkSafeStartupCapabilityTier.BLOCKED"))
        assertTrue(restrictions.contains("koreanTtsBlockDetail"))
        val readiness = owner("session/readiness/WalkReadinessBlockController.kt")
        assertTrue(readiness.contains("!sources.voiceFeatureEnabled()"))
        assertTrue(readiness.contains("!sources.offlineTtsAllowed(decision)"))
        assertTrue(readiness.contains("return koreanTtsBlockDetail"))
        val factory = MainActivityRefactorSource.factory(source, "createWalkReadinessBlockController")
        assertTrue(factory.contains("PostLoginDeviceCheckFeature.VOICE_GUIDANCE"))
        assertTrue(factory.contains("WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS"))
        assertTrue(factory.contains("koreanTtsBlockDetail = KOREAN_TTS_WALK_BLOCK_DETAIL"))
        assertFalse(MainActivityRefactorSource.function(source, "private fun confirmStartupCapabilityDecision()").contains("사용 가능한 기능으로 계속합니다"))
    }

    @Test
    fun runtimeKoreanTtsFailureSafetyStopsActiveWalkAndRequiresRecheck() {
        val failure = section(
            source,
            "private fun handleRuntimeSpeechCapabilityFailure(",
            "private fun feedbackActuatorStatusText()",
        )
        assertTrue(failure.contains("walkSessionLifecycle.snapshot().state == WalkSessionState.ACTIVE"))
        assertTrue(
            failure.contains(
                "enterWalkSessionSafetyStopAndCancelOutputs(\"offline_korean_tts_runtime_failure\")",
            ),
        )
        assertTrue(failure.contains("한국어 음성 필요 · 보행 시작/재개 불가"))
        assertTrue(failure.contains("기기 점검을 다시 실행"))
        assertFalse(failure.contains("다른 기능은 계속"))
    }

    @Test
    fun ttsFailureShowsOneInstallActionAndSuccessfulProbeExplainsRecheck() {
        val render = member(owner("ui/DeviceCheckPresenter.kt"), "renderStartupCapability")
        val settings = render.substringAfter("panel.postLoginDeviceCheckSettingsButton.apply {")
            .substringBefore("panel.voiceDataInstallButton.visibility")
        assertFalse(settings.contains("PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE"))
        val complete = member(recoverySource, "completeVoiceDataInstallRecheckIfPossible")
        assertTrue(complete.contains("한국어 음성 재점검 완료"))
        assertTrue(complete.contains("기기 점검 결과를 확인한 뒤 보행을 시작하거나 재개하세요"))
    }

    private fun section(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        check(start >= 0) { "Missing start marker: $startMarker" }
        val end = text.indexOf(endMarker, start + startMarker.length)
        check(end > start) { "Missing end marker: $endMarker" }
        return text.substring(start, end)
    }

    private fun privateFunctionContaining(index: Int): String {
        val functionMarker = "    private fun "
        val start = source.lastIndexOf(functionMarker, index)
        check(start >= 0) { "Missing private function before source index $index" }
        val end = source.indexOf("\n$functionMarker", index).let { next ->
            if (next >= 0) next else source.length
        }
        return source.substring(start, end)
    }

    private fun assertInOrder(text: String, vararg values: String) {
        var cursor = -1
        values.forEach { value ->
            val next = text.indexOf(value, cursor + 1)
            assertTrue("Missing or out-of-order marker: $value", next > cursor)
            cursor = next
        }
    }
}
