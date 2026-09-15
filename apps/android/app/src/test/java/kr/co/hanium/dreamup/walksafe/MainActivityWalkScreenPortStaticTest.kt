package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityWalkScreenPortStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun minimalWalkScreenKeepsNativeFeatureGroupsAndReadinessGates() {
        val runtime = blockAt("runtimeControls = LinearLayout(this).apply {")
        val status = blockAt("walkStatusSection = walkSection(")
        val regularOverlay = source.substringAfter("val overlay = LinearLayout(this).apply {")
            .substringBefore("walkSafetyOverlay = LinearLayout(this).apply {")
        val firstRunUi = blockAt("private fun updateFirstRunOnboardingUi()")

        assertTrue(runtime.contains("addView(nativeDestinationControls)"))
        assertTrue(runtime.contains("addView(nativeDestinationConfirmationControls)"))
        assertTrue(runtime.contains("addView(nativeVoiceControls)"))
        assertTrue(status.contains("walkLastResultText"))
        assertFalse(runtime.contains("navigationStatusText"))

        assertTrue(regularOverlay.contains("addView(runtimeControls)"))
        assertTrue(regularOverlay.contains("addView(walkReadinessControls)"))

        val visibility = firstRunUi.substringAfter("runtimeControls.visibility =")
        assertTrue(firstRunUi.contains("val mayUseWalk = firstRunOnboardingComplete()"))
        assertTrue(visibility.contains("mayUseWalk &&"))
        assertTrue(visibility.contains("WalkSessionState.ACTIVE"))
        assertTrue(visibility.contains("!permissionRecoveryGate.blocksAutomaticResourceStart"))
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
    fun homeSearchHasItsOwnAvailabilityWhileNavigationKeepsWalkReadiness() {
        val availability = blockAt("private fun updateWalkFeatureAvailabilityUi()")
        val searchAvailability = blockAt("private fun currentDestinationSearchAllowsWork()")
        val homeContext = blockAt("private fun nativeHomeFeatureContextAvailable()")
        val search = blockAt("private fun performDestinationSearch(reset: Boolean): Boolean")
        val voiceSearch = blockAt("private fun startVoiceDestinationSearch(query: String)")
        val moreCandidates = blockAt("private fun hearMoreVoiceDestinationCandidates()")
        val destinationSelection = blockAt("private fun onDestinationSelected(")
        val nativeStart = blockAt("private fun startNativeDestinationGuidance()")
        val nativePreparation = blockAt("private fun beginNativeWalkFromHome()")
        val nativePrewalk = blockAt("private fun continueNativePrewalkIfReady()")
        val mountingAssessment = blockAt("private fun currentPhoneMountingAssessment(")
        val routeRequest = blockAt("private fun requestRoute(")
        val locationUpdate = blockAt("private fun handleLocationUpdate(")
        val voiceSelection = blockAt("private fun selectVoiceDestinationCandidate(oneBasedIndex: Int)")
        val routeAction = blockAt("private fun onRouteButtonClicked()")
        val voiceNavigationActions = listOf(
            blockAt("private fun requestRerouteFromVoice()"),
            blockAt("private fun recheckLocationFromVoice()"),
            blockAt("private fun speakNextNavigationInstruction()"),
        )

        assertTrue(availability.contains("currentNavigationCollectionAllowsWork()"))
        assertTrue(availability.contains("currentDestinationSearchAllowsWork()"))
        assertTrue(searchAvailability.contains("currentNavigationCollectionAllowsWork() ||"))
        assertTrue(searchAvailability.contains("nativeHomeFeatureContextAvailable() &&"))
        assertTrue(searchAvailability.contains("postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)"))
        assertTrue(homeContext.contains("!firstRunOnboardingComplete() || !isActivityForeground"))
        assertTrue(homeContext.contains("permissionRecoveryGate.blocksAutomaticResourceStart"))
        assertTrue(availability.contains("destinationQueryInput.isEnabled"))
        assertTrue(availability.contains("destinationSearchButton.isEnabled"))
        assertTrue(availability.contains("routeButton.isEnabled"))
        assertTrue(availability.contains("destinationSearchResultsContainer"))
        assertTrue(availability.contains("child is Button"))
        assertTrue(availability.contains("child.isEnabled = destinationSearchAvailable && !destinationSearchInFlight"))
        assertTrue(availability.contains("위치 기능이 제한되어 목적지와 경로 안내를 사용할 수 없습니다"))
        assertTrue(search.contains("explainNavigationFeatureUnavailable()"))
        assertTrue(search.contains("!currentDestinationSearchAllowsWork()"))
        listOf(voiceSearch, moreCandidates).forEach { action ->
            assertTrue(action.contains("!currentDestinationSearchAllowsWork()"))
            assertTrue(action.contains("explainNavigationFeatureUnavailable()"))
        }
        assertTrue(
            destinationSelection.indexOf("explainNavigationFeatureUnavailable()") in
                0 until destinationSelection.indexOf("cancelNavigationRequestsForDestinationSelection()"),
        )
        assertTrue(nativeStart.contains("retainExplicitStartUntilTrustedLocation = true"))
        assertTrue(nativePreparation.contains("NativePhoneMountingCheckRequest("))
        assertTrue(Regex("\\bPhoneMountingCheckRequest\\(").containsMatchIn(nativePreparation))
        assertTrue(nativePreparation.contains("requestedAtElapsedRealtimeMs"))
        assertTrue(nativePreparation.contains("SystemClock.elapsedRealtime()"))
        assertTrue(
            nativePreparation.indexOf("NativePhoneMountingCheckRequest(") in
                0 until nativePreparation.indexOf("confirmOfficialEnvironmentConditions()"),
        )
        assertFalse(nativePreparation.contains("phoneMountingUserConfirmation ="))
        assertTrue(nativePrewalk.contains("nativePhoneMountingCheckRequest"))
        val startupActionIndex = nativePrewalk.indexOf("handleStartupCapabilityConfirmAction()")
        listOf(
            "officialEnvironmentReadiness(expectedEpoch).first != WalkSessionReadinessStatus.READY",
            "phoneMountingReadiness(expectedEpoch).first != WalkSessionReadinessStatus.READY",
            "!decision.mayConfirmAndStart",
            "walk.confirmationToken == null",
            "walkSessionReadinessBlockReason(decision) != null",
        ).forEach { realReadinessGate ->
            assertTrue(nativePrewalk.indexOf(realReadinessGate) in 0 until startupActionIndex)
        }
        assertTrue(mountingAssessment.contains("PhoneMountingPolicy.assess("))
        assertTrue(mountingAssessment.contains("cameraFrameQualityOverride ?: latestPhoneMountingCameraAssessment"))
        assertTrue(mountingAssessment.contains("cameraFrameQuality = cameraFrameQuality"))
        assertTrue(mountingAssessment.contains("nativePhoneMountingCheckRequest"))
        assertTrue(Regex("\\bcheckRequest\\s*=").containsMatchIn(mountingAssessment))
        assertFalse(mountingAssessment.contains("CameraFrameQualityAssessment("))
        assertTrue(routeRequest.contains("retainPendingExplicitRouteStart(destination)"))
        assertTrue(routeRequest.contains("start_request_pending"))
        assertTrue(
            // The normal filtered-fix path still checks collection readiness first.
            // Test mode also has an earlier, explicitly gated raw-fix continuation.
            locationUpdate.lastIndexOf("continuePendingExplicitRouteStartIfReady()") >
                locationUpdate.indexOf("if (!currentNavigationCollectionAllowsWork())"),
        )
        assertTrue(
            voiceSelection.indexOf("explainNavigationFeatureUnavailable()") in
                0 until voiceSelection.indexOf("state?.onCommand("),
        )
        assertTrue(
            voiceSelection.indexOf("requireReporterUserId(") in
                0 until voiceSelection.indexOf("state?.onCommand("),
        )
        assertTrue(
            voiceSelection.indexOf("currentVoiceDestinationDialogState()") in
                0 until voiceSelection.indexOf("state?.onCommand("),
        )
        assertTrue(
            voiceSelection.indexOf("if (!onDestinationSelected(selected)) {") in
                0 until voiceSelection.lastIndexOf("destinationSearchVoiceState = null"),
        )
        val homeSelection = voiceSelection
            .substringAfterLast("if (nativeHomeFeatureContextAvailable()) {")
            .substringBefore("if (!onDestinationSelected(selected)) {")
        assertTrue(homeSelection.contains("openNativeDestinationConfirmation(selected)"))
        assertTrue(homeSelection.contains("return"))
        assertFalse(homeSelection.contains("requestRoute("))
        assertFalse(homeSelection.contains("onDestinationSelected("))
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
    fun guidanceHasOneStatusWriterAndEntersBeforePreflight() {
        val render = blockAt("private fun renderMainUi()")
        val assignments = Regex("nativeGuidanceStatusText\\.text\\s*=")
        assertTrue("Styling must not overwrite the measured preparation message", assignments.findAll(source).count() == 1)
        assertTrue("The sole guidance writer must remain in the renderer", assignments.containsMatchIn(render))
        assertTrue(render.contains("nativeGuidanceStatusText.text = nativeGuidanceStatusMessage(presentation)"))
        val message = blockAt("private fun nativeGuidanceStatusMessage(")
        assertTrue(message.contains("nativeDestinationPreparationMessage()"))
        assertTrue(message.contains("officialEnvironmentMeasurementDetail(currentOfficialEnvironmentAssessment())"))
        val start = blockAt("private fun startNativeDestinationGuidance()")
        assertTrue(start.indexOf("showNativeUiPage(NativeUiPage.GUIDANCE)") in 0 until start.indexOf("beginNativeWalkFromHome()"))
        assertFalse(start.contains("showNativeUiPage(NativeUiPage.HOME)"))
        val styling = blockAt("private fun applyNativePreviewPresentation(")
        assertTrue(styling.contains("val showPhysicalPreparation = !guidanceVisible"))
        assertTrue(styling.contains("show(walkReadinessControls, !guidanceVisible"))
    }

    @Test
    fun pausingOrResettingTheWalkClearsAnyPendingExplicitRouteStart() {
        val cancelOutputs = blockAt("private fun cancelWalkSessionOutputs(")
        val resetRoute = blockAt("private fun resetRouteState(")

        assertTrue(cancelOutputs.contains("resetRouteState(purgeRouteSnapshot = false)"))
        assertTrue(resetRoute.contains("clearPendingExplicitRouteStart()"))
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
