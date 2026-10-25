package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.Assert.*
import org.junit.Test

class NativePreviewPresentationPolicyTest {
    @Test fun guidanceAndSettingsSuppressPreparationAndDeviceScreens() {
        val preparing = input().copy(prewalkPending = true, stage = FirstRunOnboardingStage.DEVICE_CHECK)
        for (facts in listOf(preparing.copy(page = NativeUiPage.GUIDANCE), preparing.copy(settingsVisible = true))) {
            val actual = NativePreviewPresentationPolicy.project(facts)
            assertFalse(actual.deviceScreen)
            assertFalse(actual.showPhysicalPreparation)
            assertFalse(actual.showMountingPreparation)
        }
    }
    @Test fun accountAndPurposeTakePrecedenceOverDeviceReadiness() {
        val account = NativePreviewPresentationPolicy.project(input().copy(homeAvailable = false, accountPanelVisible = true, stage = FirstRunOnboardingStage.DEVICE_CHECK))
        assertTrue(account.accountVisible)
        assertFalse(account.deviceScreen)
        val purpose = NativePreviewPresentationPolicy.project(input().copy(stage = FirstRunOnboardingStage.PURPOSE_AND_SAFETY))
        assertTrue(purpose.purpose)
        assertFalse(purpose.deviceScreen)
    }
    @Test fun unstartedDeviceCheckShowsNoDetailsAndFailureShowsDetails() {
        val device = input().copy(stage = FirstRunOnboardingStage.DEVICE_CHECK)
        val waiting = NativePreviewPresentationPolicy.project(device.copy(deviceState = PostLoginDeviceCheckState.NOT_RUN))
        assertTrue(waiting.deviceScreen)
        assertFalse(waiting.deviceDetails)
        val failed = NativePreviewPresentationPolicy.project(device.copy(deviceState = PostLoginDeviceCheckState.FAIL))
        assertTrue(failed.deviceDetails)
        assertFalse(failed.checking)
        assertTrue(NativePreviewPresentationPolicy.project(device.copy(deviceState = PostLoginDeviceCheckState.REQUESTING_PERMISSIONS)).checking)
    }
    @Test fun completedOnboardingRetainsDeviceRecoveryWithoutPendingFeatureEntry() {
        for (page in listOf(NativeUiPage.HOME, NativeUiPage.DESTINATION_SEARCH, NativeUiPage.VOICE_COMMAND)) {
            for (state in listOf(PostLoginDeviceCheckState.NOT_RUN, PostLoginDeviceCheckState.FAIL)) {
                val actual = NativePreviewPresentationPolicy.project(input().copy(
                    stage = FirstRunOnboardingStage.COMPLETE, page = page,
                    homeVisible = page == NativeUiPage.HOME, pendingPage = null,
                    deviceState = state, devicePassesFeatureGate = false,
                ))
                assertTrue("$page/$state must expose device recovery after education", actual.deviceScreen)
                assertEquals(state == PostLoginDeviceCheckState.FAIL, actual.deviceDetails)
                assertFalse(actual.checking)
            }
        }
    }
    @Test fun completedPassedCheckKeepsHomeSearchAndVoicePages() {
        for (page in listOf(NativeUiPage.HOME, NativeUiPage.DESTINATION_SEARCH, NativeUiPage.VOICE_COMMAND)) {
            val actual = NativePreviewPresentationPolicy.project(input().copy(
                stage = FirstRunOnboardingStage.COMPLETE, page = page,
                homeVisible = page == NativeUiPage.HOME,
                deviceState = PostLoginDeviceCheckState.LIMITED, devicePassesFeatureGate = true,
            ))
            assertFalse(actual.deviceScreen)
        }
    }
    @Test fun pendingDestinationUsesPhysicalPreparationUnlessBypassedAndMountingNeedsCamera() {
        val preparing = input().copy(pendingPage = NativeUiPage.DESTINATION_CONFIRM, devicePassesFeatureGate = true)
        assertTrue(NativePreviewPresentationPolicy.project(preparing).showPhysicalPreparation)
        assertTrue(NativePreviewPresentationPolicy.project(preparing).showMountingPreparation)
        assertFalse(NativePreviewPresentationPolicy.project(preparing.copy(cameraEnabled = false)).showMountingPreparation)
        assertFalse(NativePreviewPresentationPolicy.project(preparing.copy(developmentBypass = true)).showPhysicalPreparation)
    }
    @Test fun limitedHomeRequiresAnActiveRuntimeAndDeletionConfirmationRequiresSettings() {
        val missingVoice = input().copy(voiceAvailable = false)
        assertFalse(NativePreviewPresentationPolicy.project(missingVoice).limitedHome)
        assertTrue(NativePreviewPresentationPolicy.project(missingVoice.copy(runtimeActive = true)).limitedHome)
        val deletion = input().copy(deletionPhase = AccountDeletionPhase.CONFIRM_REQUIRED)
        assertFalse(NativePreviewPresentationPolicy.project(deletion).deletionConfirm)
        assertTrue(NativePreviewPresentationPolicy.project(deletion.copy(settingsVisible = true)).deletionConfirm)
    }
    @Test fun guidanceWithoutPendingPreparationDoesNotQueryOrExpireRuntimeAuthority() {
        val queries = RecordingQueries(mutableListOf(false))
        val facts = NativePreviewPresentationReader.read(input().copy(
            page = NativeUiPage.GUIDANCE, homeVisible = false,
        ), queries)
        assertTrue(facts.guidanceVisible)
        assertEquals(listOf("deletion"), queries.calls)
        assertEquals(0, queries.runtimeReads)
    }
    @Test fun liveQueriesKeepEachOriginalShortCircuitAndDoNotCacheRuntimeAcrossPhases() {
        val queries = RecordingQueries(mutableListOf(false, false, true))
        val facts = NativePreviewPresentationReader.read(input().copy(
            pendingPage = NativeUiPage.DESTINATION_CONFIRM,
        ), queries)
        assertEquals(listOf("runtime", "deviceGate", "runtime", "camera", "runtime", "VOICE_COMMAND", "deletion"), queries.calls)
        assertTrue(facts.showMountingPreparation)
        assertTrue(facts.limitedHome)
        assertEquals(3, queries.runtimeReads)
    }
    @Test fun visibleDeviceCheckShortCircuitsGateCameraAndLimitedHomeQueries() {
        val queries = RecordingQueries(mutableListOf(false))
        val facts = NativePreviewPresentationReader.read(input().copy(stage = FirstRunOnboardingStage.DEVICE_CHECK), queries)
        assertTrue(facts.deviceScreen)
        assertEquals(listOf("runtime", "deletion"), queries.calls)
    }
    private class RecordingQueries(private val runtimes: MutableList<Boolean>) : NativePreviewPresentationReader.Queries {
        val calls = mutableListOf<String>()
        var runtimeReads = 0
        override fun runtimeActive(): Boolean { calls += "runtime"; runtimeReads++; return runtimes.removeAt(0) }
        override fun devicePassesFeatureGate(): Boolean { calls += "deviceGate"; return true }
        override fun cameraEnabled(): Boolean { calls += "camera"; return true }
        override fun featureAvailable(page: NativeUiPage): Boolean { calls += page.name; return false }
        override fun deletionPhase(): AccountDeletionPhase { calls += "deletion"; return AccountDeletionPhase.IDLE }
    }
    private fun input() = NativePreviewPresentationPolicy.Input(
        homeAvailable = true, homeVisible = true, settingsVisible = false, forceSettings = false,
        stage = FirstRunOnboardingStage.VERIFIED_LOGIN, accountPanelVisible = false, training = false,
        deviceState = PostLoginDeviceCheckState.NOT_RUN, page = NativeUiPage.HOME, pendingPage = null,
        runtimeActive = false, devicePassesFeatureGate = true, prewalkPending = false,
        developmentBypass = false, cameraEnabled = true, voiceAvailable = true, destinationAvailable = true,
        deletionPhase = AccountDeletionPhase.IDLE,
    )
}
