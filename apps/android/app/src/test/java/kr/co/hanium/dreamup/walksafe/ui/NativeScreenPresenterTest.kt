package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeScreenPresenterTest {
    @Test
    fun cameraAndSettingsTitlesTakePrecedenceOverRegistrationHeadings() {
        val facts = pane(homeAvailable = false, settingsVisible = true).copy(
            firstRunHeading = "기기 점검", educationHeading = "안전 교육", cameraVisible = true,
        )
        assertEquals("카메라 화면", NativeScreenPresenter.paneTitle(facts))
        assertEquals("설정", NativeScreenPresenter.paneTitle(facts.copy(cameraVisible = false)))
    }

    @Test
    fun registrationUsesTheVisibleHeadingWithoutDiscardingItsFormatting() {
        val heading = StringBuilder("앱의 한계와 안전 안내")
        val facts = pane(homeAvailable = false).copy(firstRunHeading = heading, educationHeading = "안전 교육")
        assertSame(heading, NativeScreenPresenter.paneTitle(facts))
        assertEquals("안전 교육", NativeScreenPresenter.paneTitle(facts.copy(firstRunHeading = "  ")))
        assertEquals("첫 실행 등록", NativeScreenPresenter.paneTitle(facts.copy(firstRunHeading = null, educationHeading = "\n")))
    }

    @Test
    fun destinationSelectionAndGuidanceKeepDistinctPaneTitles() {
        assertEquals("홈", NativeScreenPresenter.paneTitle(pane()))
        assertEquals("목적지 찾기", NativeScreenPresenter.paneTitle(pane().copy(pageName = "DESTINATION_SEARCH")))
        assertEquals("목적지 확인", NativeScreenPresenter.paneTitle(pane().copy(pageName = "DESTINATION_CONFIRM")))
        assertEquals("경로 안내", NativeScreenPresenter.paneTitle(pane().copy(pageName = "GUIDANCE")))
        assertEquals("음성 명령", NativeScreenPresenter.paneTitle(pane().copy(pageName = "VOICE_COMMAND")))
    }

    @Test
    fun ordinaryHomeUsesCompactSpacingButLimitedHomeKeepsReadableNoticeSpacing() {
        assertEquals(NativeScreenPresenter.OverlayLayout(16f, 16f, false), NativeScreenPresenter.overlayLayout(preview()))
        assertEquals(NativeScreenPresenter.OverlayLayout(20f, 24f, false), NativeScreenPresenter.overlayLayout(preview().copy(limitedHome = true)))
    }

    @Test
    fun accountAndUnstartedDeviceScreenAreCenteredWhileDeviceDetailsStayAtTheTop() {
        val device = preview().copy(homeVisible = false, deviceScreen = true)
        assertEquals(NativeScreenPresenter.OverlayLayout(20f, 24f, true), NativeScreenPresenter.overlayLayout(device))
        assertEquals(NativeScreenPresenter.OverlayLayout(20f, 24f, false), NativeScreenPresenter.overlayLayout(device.copy(deviceDetails = true)))
        assertEquals(NativeScreenPresenter.OverlayLayout(20f, 24f, true), NativeScreenPresenter.overlayLayout(device.copy(deviceScreen = false, accountVisible = true)))
    }

    @Test
    fun deletionConfirmationLayoutRequiresTheSettingsScreenToBeVisible() {
        val facts = preview().copy(homeVisible = false, deletionPhase = AccountDeletionPhase.CONFIRM_REQUIRED)
        assertEquals(NativeScreenPresenter.OverlayLayout(20f, 24f, false), NativeScreenPresenter.overlayLayout(facts))
        assertEquals(NativeScreenPresenter.OverlayLayout(20f, 24f, true), NativeScreenPresenter.overlayLayout(facts.copy(settingsVisible = true)))
    }

    @Test
    fun fullHeightHomeSubtractsHeaderSpaceAndRetainsThreeLargeTouchRows() {
        assertEquals(NativeScreenPresenter.HomeGeometry(808, 0, 1f),
            NativeScreenPresenter.homeGeometry(1000, 24, 24, 80, 64, 1f, false))
        assertEquals(NativeScreenPresenter.HomeGeometry(696, 0, 1f),
            NativeScreenPresenter.homeGeometry(200, 24, 24, 80, 64, 1.5f, false))
    }

    @Test
    fun limitedHomeRowsWrapTheirTextAndDoNotShareRemainingHeight() {
        assertEquals(NativeScreenPresenter.HomeGeometry(-2, -2, 0f),
            NativeScreenPresenter.homeGeometry(1000, 24, 24, 80, 64, 2f, true))
    }

    @Test
    fun retryActionRetainsTheFailedSpeechExplanationAndCurrentStatus() {
        assertEquals("다시 시도. 음성 안내를 재생하지 못했습니다. 현재 위치를 확인하고 있습니다.",
            NativeScreenPresenter.retryContentDescription("음성 안내를 재생하지 못했습니다", "현재 위치를 확인하고 있습니다."))
        assertEquals("다시 시도", NativeScreenPresenter.retryContentDescription(null, "  "))
    }

    @Test fun resumeActionDescriptionUsesItsActualLabelInsteadOfRetry() {
        assertEquals("보행 안내 재개 확인. 보행 안내 일시중지", NativeScreenPresenter.retryContentDescription(
            null, "보행 안내 일시중지", "보행 안내 재개 확인"))
        assertEquals("재개 확인 중", NativeScreenPresenter.retryContentDescription(null, null, "재개 확인 중"))
    }

    private fun pane(homeAvailable: Boolean = true, settingsVisible: Boolean = false) =
        NativeScreenPresenter.PaneFacts("HOME", homeAvailable, settingsVisible, false, null, null)

    private fun preview() = NativeScreenPresenter.PreviewFacts(
        homeAvailable = true, homeVisible = true, settingsVisible = false, forceSettings = false,
        accountVisible = false, purpose = false, training = false, checking = false,
        guidanceVisible = false, deviceScreen = false, deviceDetails = false, preparing = false,
        showPhysicalPreparation = false, showMountingPreparation = false, limitedHome = false,
        deletionPhase = AccountDeletionPhase.IDLE,
    )
}
