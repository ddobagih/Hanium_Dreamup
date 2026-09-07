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

    @Test
    fun onlyOfflineKoreanTtsIsUserInstallable() {
        val installableRequirements = section(
            capabilitySource,
            "val USER_INSTALLABLE_REQUIREMENTS",
            "data class WalkSafeStartupCapabilityInput(",
        )
        val requirementNames = Regex("WalkSafeStartupRequirement\\.([A-Z_]+)")
            .findAll(installableRequirements)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(listOf("OFFLINE_KOREAN_TTS"), requirementNames)
        assertTrue(source.contains("private lateinit var voiceDataInstallButton: Button"))
        assertTrue(source.contains("addView(voiceDataInstallButton)"))
        assertTrue(
            source.contains(
                "if (installableUnavailable.isEmpty()) View.GONE else View.VISIBLE",
            ),
        )
    }

    @Test
    fun recoveryTriesTtsInstallThenTtsSettingsThenAppSettings() {
        val block = section(
            source,
            "private fun openVoiceDataInstallSettings()",
            "private fun requireFirstRunOnboardingComplete(",
        )
        assertInOrder(
            block,
            "TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA",
            "Intent(ANDROID_TTS_SETTINGS_ACTION)",
            "Settings.ACTION_APPLICATION_DETAILS_SETTINGS",
            "candidates.any",
            "if (opened)",
            "voiceDataInstallRecheckPending = true",
            "return",
            "updateStatus",
        )
        assertTrue(
            source.contains(
                "const val ANDROID_TTS_SETTINGS_ACTION = " +
                    "\"com.android.settings.TTS_SETTINGS\"",
            ),
        )
        assertFalse(block.contains("Settings.ACTION_VOICE_INPUT_SETTINGS"))
    }

    @Test
    fun returningFromSettingsRequestsAFreshOfflineKoreanTtsProbe() {
        val resume = section(
            source,
            "private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection()",
            "private fun scheduleOfficialEnvironmentRuntimeWatchdog(",
        )
        val activityRecheck = section(
            source,
            "private fun recheckVoiceDataAfterSettingsIfNeeded()",
            "private fun completeVoiceDataInstallRecheckIfPossible()",
        )
        val recheck = section(
            probeSource,
            "fun recheckOfflineKoreanTextToSpeech()",
            "fun snapshot(): WalkSafeStartupCapabilityInput",
        )

        assertTrue(resume.contains("recheckVoiceDataAfterSettingsIfNeeded()"))
        assertTrue(
            activityRecheck.contains(
                "startupCapabilityProbe.recheckOfflineKoreanTextToSpeech()",
            ),
        )
        assertTrue(
            activityRecheck.contains(
                "startupCapabilityProbe = AndroidStartupCapabilityProbe(this)",
            ),
        )
        assertTrue(activityRecheck.contains("startupCapabilityProbeStarted = false"))
        assertTrue(
            recheck.contains(
                "probeOfflineKoreanTextToSpeech(currentGeneration, notifyPending = true)",
            ),
        )
        assertTrue(probeSource.contains("offlineKoreanTextToSpeechProbeState.begin()"))
        assertTrue(probeSource.contains("offlineKoreanTextToSpeechProbeState.complete("))
        assertTrue(probeSource.contains("offlineKoreanTextToSpeechProbeState.close()"))
        assertTrue(probeSource.contains("internal class OfflineKoreanTextToSpeechProbeState"))
    }

    @Test
    fun successfulRecheckRemovesOnlyVoiceGuidanceAndPersistsTheRecoveredResult() {
        val removal = Regex(
            "disabledFeatures\\s*-\\s*PostLoginDeviceCheckFeature\\.VOICE_GUIDANCE",
        ).find(source)
        checkNotNull(removal) { "Missing VOICE_GUIDANCE restriction removal" }
        val recovery = privateFunctionContaining(removal.range.first)

        assertTrue(recovery.contains("val current = postLoginDeviceCheckSnapshot"))
        assertTrue(recovery.contains("current.copy("))
        assertTrue(recovery.contains("PostLoginDeviceCheckState.FULL"))
        assertTrue(recovery.contains("PostLoginDeviceCheckState.LIMITED"))
        assertTrue(recovery.contains("disabledFeatures = remaining"))
        assertTrue(recovery.contains("postLoginDeviceCheckResultStore.save("))
        assertTrue(recovery.contains("postLoginDeviceCheckSnapshot ="))
        assertTrue(recovery.contains("offlineKoreanTextToSpeechCapabilityOverride = true"))
        assertTrue(
            "Recovered result must be saved before it becomes the active snapshot",
            recovery.indexOf("postLoginDeviceCheckResultStore.save(") <
                recovery.lastIndexOf("postLoginDeviceCheckSnapshot ="),
        )
    }

    @Test
    fun missingKoreanTtsBlocksDeviceCheckAndWalkStartOrResume() {
        val observation = section(
            source,
            "private fun currentPostLoginDeviceCheckObservation()",
            "private fun completePostLoginDeviceCheckPass(",
        )
        assertTrue(
            observation.contains(
                "koreanTextToSpeechAvailable == false ->\n" +
                    "                    PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE",
            ),
        )

        val restrictions = section(
            source,
            "private fun applyPostLoginDeviceFeatureRestrictions(",
            "/** Active route/risk work",
        )
        assertTrue(
            restrictions.contains(
                "WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS in unavailable",
            ),
        )
        assertTrue(restrictions.contains("WalkSafeStartupCapabilityTier.BLOCKED"))
        assertTrue(restrictions.contains("KOREAN_TTS_WALK_BLOCK_DETAIL"))

        val readiness = section(
            source,
            "private fun walkSessionReadinessBlockReason(",
            "private fun missingRequiredWalkSessionPermissions(",
        )
        assertTrue(readiness.contains("PostLoginDeviceCheckFeature.VOICE_GUIDANCE"))
        assertTrue(readiness.contains("WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS"))
        assertTrue(readiness.contains("return KOREAN_TTS_WALK_BLOCK_DETAIL"))

        val confirmation = section(
            source,
            "private fun confirmStartupCapabilityDecision()",
            "private fun deliverStartupCapabilityNotice(",
        )
        assertFalse(confirmation.contains("사용 가능한 기능으로 계속합니다"))
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
        val settings = section(
            source,
            "postLoginDeviceCheckSettingsButton.apply",
            "text = when {",
        )
        assertFalse(settings.contains("PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE"))
        val recovery = section(
            source,
            "private fun completeVoiceDataInstallRecheckIfPossible()",
            "private fun requireFirstRunOnboardingComplete(",
        )
        assertTrue(recovery.contains("한국어 음성 재점검 완료"))
        assertTrue(recovery.contains("기기 점검 결과를 확인한 뒤 보행을 시작하거나 재개하세요"))
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
