package kr.co.hanium.dreamup.walksafe.ui

import android.view.View
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.Assert.*
import org.junit.Test

/** Actual screen policy and presenter; View setters are observed leaves, not an Android window. */
class SettingsKoreanSpeechRecoveryPresentationTest {
    private fun render(
        views: SettingsKoreanSpeechRecoveryViews = SettingsKoreanSpeechRecoveryViews(),
        page: NativeUiPage = NativeUiPage.SETTINGS,
        completed: Boolean = true,
        needsVoice: Boolean = true,
        forceSettings: Boolean = false,
        deletion: AccountDeletionPhase = AccountDeletionPhase.IDLE,
        label: String = "한국어 음성 데이터 설치",
    ): SettingsKoreanSpeechRecoveryViews {
        val preview=NativePreviewPresentationPolicy.project(NativePreviewPresentationPolicy.Input(
            homeAvailable=completed, homeVisible=page==NativeUiPage.HOME && completed,
            settingsVisible=page==NativeUiPage.SETTINGS, forceSettings=forceSettings,
            stage=if(completed) FirstRunOnboardingStage.COMPLETE else FirstRunOnboardingStage.DEVICE_CHECK,
            accountPanelVisible=false, training=false,
            deviceState=if(completed) PostLoginDeviceCheckState.FULL else PostLoginDeviceCheckState.NOT_RUN,
            page=page, pendingPage=null, runtimeActive=false, devicePassesFeatureGate=completed,
            prewalkPending=page==NativeUiPage.GUIDANCE, developmentBypass=false, deletionPhase=deletion,
        ))
        views.presenter.finishFrame(NativeScreenPresenter.FrameFacts(page.name,completed,
            page==NativeUiPage.HOME && completed,page==NativeUiPage.SETTINGS,
            page==NativeUiPage.GUIDANCE,forceSettings,deletion!=AccountDeletionPhase.IDLE))
        views.presenter.renderPreviewAccountAndPreparation(preview, NativeScreenPresenter.AccountPreviewFacts(false,null))
        views.presenter.renderPreviewReadiness(preview, NativeScreenPresenter.ReadinessPreviewFacts(
            "posture",false,null,emptyMap(),false,true,true,completed,needsVoice,label,"$label 열기",
        ))
        return views
    }
    private fun assertSettingsRecoveryShown(v: SettingsKoreanSpeechRecoveryViews) {
        assertEquals(View.VISIBLE,v.settings.privacyControls.visibility)
        assertEquals(View.VISIBLE,v.settings.privacySettingsControls.visibility)
        assertEquals(View.VISIBLE,v.settings.nativeSettingsVoiceDataButton.visibility)
    }
    @Test fun savedFullWithFailedSpeechHasAnExplicitActionInSettingsWithoutReopeningOnboarding() {
        val v=render()
        assertSettingsRecoveryShown(v)
        assertEquals(View.GONE,v.onboarding.walkReadinessControls.visibility)
        assertEquals(View.GONE,v.onboarding.voiceDataInstallButton.visibility)
        assertEquals(View.GONE,v.onboarding.startupMetricPreflightButton.visibility)
    }
    @Test fun missingVoiceAndWrongEngineKeepTheirExistingCauseSpecificActionDescriptions() {
        for(label in listOf("한국어 음성 데이터 설치","기본 음성 엔진 설정")) {
            val v=render(label=label); assertSettingsRecoveryShown(v)
            // Final TextView.setText is an SDK adapter gap here; the Android UI case checks text.
            assertEquals("$label 열기",v.settings.nativeSettingsVoiceDataButton.contentDescription)
        }
    }
    @Test fun knownGoodOrStillPendingSpeechDoesNotOfferUnrequestedRecovery() {
        val v=render(needsVoice=false)
        assertEquals(View.GONE,v.settings.nativeSettingsVoiceDataButton.visibility)
    }
    @Test fun incompleteOnboardingKeepsItsOwnDeviceCheckActionAndHidesCompletedAccountAction() {
        val settings=render(completed=false)
        assertEquals(View.GONE,settings.settings.nativeSettingsVoiceDataButton.visibility)
        val device=render(completed=false,page=NativeUiPage.HOME)
        assertEquals(View.VISIBLE,device.onboarding.walkReadinessControls.visibility)
        assertEquals(View.VISIBLE,device.onboarding.voiceDataInstallButton.visibility)
        assertEquals(View.GONE,device.settings.nativeSettingsVoiceDataButton.visibility)
    }
    @Test fun deletionAndForcedSettingsNeverExposeSpeechSettingsRecovery() {
        for(phase in AccountDeletionPhase.entries.filter { it!=AccountDeletionPhase.IDLE }) {
            assertEquals(phase.name,View.GONE,render(deletion=phase).settings.nativeSettingsVoiceDataButton.visibility)
        }
        assertEquals(View.GONE,render(forceSettings=true).settings.nativeSettingsVoiceDataButton.visibility)
    }
    @Test fun leavingSettingsOrRestoringSpeechHidesPreviouslyVisibleAction() {
        val v=render(); assertSettingsRecoveryShown(v)
        for(page in NativeUiPage.entries.filter { it!=NativeUiPage.SETTINGS }) {
            render(views=v,page=page)
            assertEquals(page.name,View.GONE,v.settings.nativeSettingsVoiceDataButton.visibility)
        }
        render(views=v); assertSettingsRecoveryShown(v)
        render(views=v,needsVoice=false)
        assertEquals(View.GONE,v.settings.nativeSettingsVoiceDataButton.visibility)
    }
}
