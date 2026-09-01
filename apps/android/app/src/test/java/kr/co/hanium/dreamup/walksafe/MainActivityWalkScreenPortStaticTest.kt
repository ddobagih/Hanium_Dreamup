package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityWalkScreenPortStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun teammateWalkScreenIsGroupedAndReachableInReleaseAndPausedStates() {
        val runtime = blockAt("runtimeControls = LinearLayout(this).apply {")
        val status = blockAt("walkStatusSection = walkSection(")
        val regularOverlay = source.substringAfter("val overlay = LinearLayout(this).apply {")
            .substringBefore("walkSafetyOverlay = LinearLayout(this).apply {")
        val firstRunUi = blockAt("private fun updateFirstRunOnboardingUi()")

        assertTrue(runtime.split("walkSection(").size - 1 >= 6)
        assertTrue(status.contains("walkLastResultText"))
        assertTrue(runtime.split("walkDivider()").size - 1 >= 6)
        assertTrue(
            runtime.contains(
                "walkTwoColumnRow(destinationSearchButton, destinationCancelButton)",
            ),
        )
        assertTrue(runtime.contains("walkTwoColumnRow(routeButton, destinationResetButton)"))
        assertFalse(runtime.contains("navigationStatusText"))

        assertTrue(regularOverlay.contains("addView(runtimeControls)"))
        assertTrue(regularOverlay.contains("addView(walkReadinessControls)"))

        val visibility = firstRunUi.substringAfter("runtimeControls.visibility =")
        assertTrue(visibility.contains("WalkSessionState.ACTIVE"))
        assertFalse(visibility.contains("BuildConfig.DEBUG"))
    }

    @Test
    fun pausedWalkKeepsTheExistingResumeActionInTheReadinessGroup() {
        val startupButton = blockAt("startupCapabilityConfirmButton = Button(this).apply {")
        val readiness = blockAt("walkReadinessControls = LinearLayout(this).apply {")
        val firstRunUi = blockAt("private fun updateFirstRunOnboardingUi()")

        assertTrue(startupButton.contains("handleStartupCapabilityConfirmAction()"))
        assertTrue(readiness.contains("addView(startupCapabilityConfirmButton)"))
        assertTrue(firstRunUi.contains("WalkSessionState.PAUSED"))
        assertTrue(firstRunUi.contains("walkReadinessControls.visibility"))
        val refresh = blockAt("private fun refreshStartupCapabilityUi()")
        assertTrue(refresh.contains("val resumeRecheckRequired"))
        assertTrue(refresh.contains("보행 재개 상태 다시 확인"))
    }

    @Test
    fun startConfirmationCannotBeEnabledBeforeItsReadinessTokenExists() {
        val refresh = blockAt("private fun refreshStartupCapabilityUi()")
        val advance = blockAt("private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")

        assertTrue(refresh.contains("val confirmationTokenReady"))
        assertTrue(refresh.contains("confirmationTokenReady &&"))
        assertTrue(refresh.contains("!confirmationTokenReady -> \"보행 준비 확인 중\""))
        assertTrue(advance.contains("val createdConfirmationToken"))
        assertTrue(advance.contains("if (createdConfirmationToken)"))
        assertTrue(advance.contains("refreshStartupCapabilityUi()"))
    }

    @Test
    fun locationLimitedControlsAreDisabledAndEveryAttemptExplainsWhy() {
        val availability = blockAt("private fun updateWalkFeatureAvailabilityUi()")
        val search = blockAt("private fun performDestinationSearch(reset: Boolean): Boolean")
        val voiceSearch = blockAt("private fun startVoiceDestinationSearch(query: String)")
        val destinationSelection = blockAt("private fun onDestinationSelected(result: DestinationSearchResult): Boolean")
        val voiceSelection = blockAt("private fun selectVoiceDestinationCandidate(oneBasedIndex: Int)")
        val routeAction = blockAt("private fun onRouteButtonClicked()")
        val voiceNavigationActions = listOf(
            blockAt("private fun hearMoreVoiceDestinationCandidates()"),
            blockAt("private fun requestRerouteFromVoice()"),
            blockAt("private fun recheckLocationFromVoice()"),
            blockAt("private fun speakNextNavigationInstruction()"),
        )

        assertTrue(availability.contains("currentNavigationCollectionAllowsWork()"))
        assertTrue(availability.contains("destinationQueryInput.isEnabled"))
        assertTrue(availability.contains("destinationSearchButton.isEnabled"))
        assertTrue(availability.contains("routeButton.isEnabled"))
        assertTrue(availability.contains("destinationSearchResultsContainer"))
        assertTrue(availability.contains("child is Button"))
        assertTrue(availability.contains("child.isEnabled = navigationAvailable && !destinationSearchInFlight"))
        assertTrue(availability.contains("위치 기능이 제한되어 목적지와 경로 안내를 사용할 수 없습니다"))
        assertTrue(search.contains("explainNavigationFeatureUnavailable()"))
        assertTrue(voiceSearch.contains("explainNavigationFeatureUnavailable()"))
        assertTrue(
            destinationSelection.indexOf("explainNavigationFeatureUnavailable()") in
                0 until destinationSelection.indexOf("cancelNavigationRequestsForDestinationSelection()"),
        )
        assertTrue(
            voiceSelection.indexOf("explainNavigationFeatureUnavailable()") in
                0 until voiceSelection.indexOf("destinationSearchVoiceState?.onCommand("),
        )
        assertTrue(
            voiceSelection.indexOf("if (!onDestinationSelected(selected)) return") in
                0 until voiceSelection.indexOf("destinationSearchVoiceState = null"),
        )
        assertTrue(
            routeAction.indexOf("cancelActiveRouteRequest()") in
                0 until routeAction.indexOf("!currentNavigationCollectionAllowsWork()"),
        )
        assertTrue(
            routeAction.indexOf("resetRouteState()") in
                0 until routeAction.indexOf("!currentNavigationCollectionAllowsWork()"),
        )
        assertTrue(routeAction.contains("explainNavigationFeatureUnavailable()"))
        voiceNavigationActions.forEach { action ->
            assertTrue(action.contains("!currentNavigationCollectionAllowsWork()"))
            assertTrue(action.contains("explainNavigationFeatureUnavailable()"))
        }
    }

    @Test
    fun permissionRecoveryPanelRemainsInTheSingleProductScroll() {
        val regularOverlay = source.substringAfter("val overlay = LinearLayout(this).apply {")
            .substringBefore("walkSafetyOverlay = LinearLayout(this).apply {")

        assertTrue(regularOverlay.contains("addView(permissionDenialPanel)"))
        assertTrue(regularOverlay.contains("addView(runtimeControls)"))
    }

    @Test
    fun lastSpokenInteractionStaysVisibleWithoutDuplicateLiveAnnouncement() {
        val speak = blockAt("private fun speakInteraction(message: String): Boolean")
        val result = blockAt("private fun showWalkLastResult(message: String)")
        val view = blockAt("walkLastResultText = TextView(this).apply {")

        assertTrue(speak.contains("showWalkLastResult(message)"))
        assertTrue(
            speak.indexOf("showWalkLastResult(message)") <
                speak.indexOf("shouldSuppressFeedbackDuringVoiceRecognition"),
        )
        assertTrue(result.contains("마지막 안내: \$message"))
        assertTrue(view.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertFalse(view.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
    }

    private fun blockAt(marker: String): String {
        val markerIndex = source.indexOf(marker)
        check(markerIndex >= 0) { "missing block marker: $marker" }
        val open = source.indexOf('{', markerIndex)
        check(open >= 0) { "missing opening brace: $marker" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
        }
        error("unterminated block: $marker")
    }
}
