package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural Main/panel wiring complements the actual presenter and recovery-owner behavior tests. */
class MainSettingsKoreanSpeechRecoveryStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()
    private fun code(text: String) = ReportStaticSourceInspector.maskNonCode(text).replace(Regex("\\s+"), " ").trim()
    private fun compact(text: String) = ReportStaticSourceInspector.maskNonCode(text).filterNot(Char::isWhitespace)

    @Test fun settingsButtonIsARealChildAndItsOnlyClickActionUsesTheExistingMainRecoveryEntry() {
        val panel = source("ui/SettingsPrivacyPanel.kt")
        val property = MainActivityRefactorSource.property(panel, "nativeSettingsVoiceDataButton")
        assertEquals("{ actions.onVoiceDataSettings() }",
            code(ReportStaticSourceInspector.blockAfter(property, "setOnClickListener")))
        val children = code(MainActivityRefactorSource.property(panel, "privacySettingsControls"))
        assertEquals(1, Regex(Regex.escape("addView(nativeSettingsVoiceDataButton)")).findAll(children).count())
        val outer = code(MainActivityRefactorSource.property(panel, "privacyControls"))
        assertEquals(1, Regex(Regex.escape("addView(privacySettingsControls)")).findAll(outer).count())
        val main = source("MainActivity.kt")
        val build = code(MainActivityRefactorSource.member(main, "buildContentView"))
        val settings = build.substringAfter("val settingsPanel = SettingsPrivacyPanel(")
            .substringBefore("guidanceDiagnosticButton = settingsPanel.guidanceDiagnosticButton")
        assertTrue(settings.contains("actions = SettingsPrivacyPanel.Actions("))
        assertEquals(1, Regex("onVoiceDataSettings\\s*=\\s*::openVoiceDataInstallSettings\\s*,").findAll(settings).count())
        assertEquals("privatefunopenVoiceDataInstallSettings()=voiceDataSettingsRecovery.openVoiceDataInstallSettings()",
            compact(MainActivityRefactorSource.member(main, "openVoiceDataInstallSettings")))

        val presenter = source("ui/NativeScreenPresenter.kt")
        assertCauseTextBinding(presenter)
        val assignment = "nativeSettingsVoiceDataButton.text = readiness.voiceActionLabel"
        for (replacement in listOf("", "// $assignment")) {
            val changed = presenter.replace(assignment, replacement)
            assertNotEquals(presenter, changed)
            assertThrows(AssertionError::class.java) { assertCauseTextBinding(changed) }
        }
    }

    /** Exact executable body, not a string/comment hit; this does not claim Android text rendering. */
    private fun assertCauseTextBinding(presenter: String) {
        val render = MainActivityRefactorSource.member(presenter, "renderPreviewReadiness")
        val settings = ReportStaticSourceInspector.blockAfter(render, "with(settings)")
        assertEquals(compact("""
            {
                show(nativeSettingsVoiceDataButton, facts.settingsVisible && facts.homeAvailable &&
                    !facts.forceSettings && facts.deletionIdle && readiness.needsKoreanVoice)
                nativeSettingsVoiceDataButton.text = readiness.voiceActionLabel
                nativeSettingsVoiceDataButton.contentDescription = readiness.voiceActionContentDescription
            }
        """), compact(settings))
    }

    @Test fun mainKeepsMeasuredUnavailableFactsAndExistingPauseReturnRecoveryWithoutAutomaticRetry() {
        val main = source("MainActivity.kt")
        val preview = code(MainActivityRefactorSource.member(main, "applyNativePreviewPresentation"))
        assertTrue(preview.contains("val needsKoreanVoice = startupCapabilityDecision?.unavailableRequirements ?.contains(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS) == true"))
        assertTrue(preview.contains("needsKoreanVoice = needsKoreanVoice"))
        val pause = code(MainActivityRefactorSource.member(main, "onPause"))
        assertTrue(pause.contains("pauseWalkSafeRuntime()"))
        assertTrue(code(MainActivityRefactorSource.member(main, "pauseWalkSafeRuntime")).contains("speechOutputController.closeActuator()"))
        val resume = code(MainActivityRefactorSource.member(main, "resumeWalkSafeRuntimeAfterPrivacyStartupInspection"))
        assertTrue(resume.contains("recheckVoiceDataAfterSettingsIfNeeded()"))
        assertEquals("privatefunrecheckVoiceDataAfterSettingsIfNeeded()=voiceDataSettingsRecovery.recheckVoiceDataAfterSettingsIfNeeded()",
            compact(MainActivityRefactorSource.member(main, "recheckVoiceDataAfterSettingsIfNeeded")))
        val recovery = source("startup/interaction/VoiceDataSettingsRecoveryController.kt")
        val returnCheck = compact(MainActivityRefactorSource.member(recovery, "recheckVoiceDataAfterSettingsIfNeeded"))
        assertTrue(returnCheck.contains("if(!recheckPending||recheckInFlight||!probe.initialized)return"))
        assertTrue(returnCheck.contains("probe.recheck()"))
        val completion = code(MainActivityRefactorSource.member(recovery, "completeVoiceDataInstallRecheckIfPossible"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(completion,
            "val available = probe.snapshot()", "if (!available)", "if (!effects.recoverPassedGate())", "effects.offlineTtsOverride = true"))
    }
}
