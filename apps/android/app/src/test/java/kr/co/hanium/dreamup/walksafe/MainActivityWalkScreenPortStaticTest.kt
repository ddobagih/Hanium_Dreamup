package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityWalkScreenPortStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val destinationPanelSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
    private val onboardingPanelSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()
    private val onboardingPresenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt").readText()
    private val devicePresenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText()
    private val nativeScreenPresenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt").readText()

    @Test
    fun minimalWalkScreenKeepsNativeFeatureGroupsAndReadinessGates() {
        val runtime = blockAt(destinationPanelSource, "val runtimeControls = LinearLayout(context).apply {")
        val status = blockAt(File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText(), "fun createWalkStatusSection(")
        assertTrue(blockAt("private fun buildContentView()").contains("walkStatusSection = runtimeComposer.createWalkStatusSection(safetySummaryText, statusText, detailText, walkLastResultText)"))
        val regularOverlay = source.substringAfter("val overlay = contentComposer.createOverlayContainer(")
            .substringBefore("walkSafetyOverlay = LinearLayout(this).apply {")
        val firstRunUi = blockAt("private fun updateFirstRunOnboardingUi()")

        assertTrue(runtime.contains("addView(nativeDestinationControls)"))
        assertTrue(runtime.contains("addView(nativeDestinationConfirmationControls)"))
        assertTrue(runtime.contains("addView(nativeVoiceControls)"))
        val nativeFrame = blockAt(nativeScreenPresenterSource, "override fun beginFrame(")
        assertTrue(nativeFrame.contains("show(destination.nativeVoiceControls, facts.featureVisible &&"))
        assertTrue(nativeFrame.contains("show(destination.nativeVoiceControls, facts.featureVisible && facts.pageName == \"VOICE_COMMAND\")"))
        assertTrue(MainActivityRefactorSource.property(source, "nativePagePresentationController").contains("{ nativeScreenPresenter }"))
        assertTrue(status.contains("lastResult"))
        assertFalse(runtime.contains("navigationStatusText"))

        assertTrue(regularOverlay.contains("addView(nativeScreenPresenter.searchBinding().runtimeControls)"))
        assertTrue(regularOverlay.contains("addView(firstRunOnboardingPresenter.readinessBinding().walkReadinessControls)"))
        assertTrue(blockAt("private fun buildContentView()").contains("nativeScreenPresenter.publishSearchTargets(destinationPanel)"))
        assertTrue(blockAt(nativeScreenPresenterSource, "fun searchBinding(").contains("publishedSearch"))
        listOf(
            "nativeDestinationControls", "nativeDestinationConfirmationControls", "nativeVoiceControls",
        ).forEach { field ->
            assertTrue(destinationPanelSource.contains("val $field"))
            assertTrue(blockAt("private fun buildContentView()").contains("destination = destinationPanel"))
        }

        assertTrue(firstRunUi.contains("effects.renderRuntime {"))
        val runtimePresenter = blockAt(onboardingPresenterSource, "fun renderRuntime(")
        assertTrue(runtimePresenter.contains("runtimeViews.runtimeControls?.visibility = if (facts.runtimeVisible) View.VISIBLE else View.GONE"))
        assertTrue(firstRunUi.contains("runtimeVisible ="))
        val visibility = firstRunUi.substringAfter("runtimeVisible =").substringBefore("\n            ),")
        assertTrue(firstRunUi.contains("val mayUseWalk = facts.complete()"))
        assertTrue(visibility.contains("mayUseWalk &&"))
        assertTrue(visibility.contains("WalkSessionState.ACTIVE"))
        assertTrue(visibility.contains("!facts.permissionRecoveryBlocksStart()"))
        assertFalse(visibility.contains("BuildConfig.DEBUG"))
    }

    @Test
    fun pausedWalkKeepsTheExistingResumeActionInTheReadinessGroup() {
        val registration = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registration, "bindRegistration")
        val registrationConstruction = MainActivityRefactorSource.property(source, "nativeRegistrationComposition")
        assertTrue(registrationConstruction.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication) { accountAccessPresenter }"))
        assertTrue(MainActivityRefactorSource.property(source, "firstRunOnboardingPresenter").contains("nativeRegistrationComposition.firstRunPresenter()"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(bindRegistration.contains("if (!::firstRun.isInitialized)"))
        assertTrue(bindRegistration.contains("FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(bindRegistration.contains("firstRun.bindOnboardingPanel(panel)"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(bindRegistration.contains("firstRun.publishRegistrationTargets()"))

        val startupButton = blockAt(onboardingPanelSource, "startupCapabilityConfirmButton = Button(context).apply {")
        val readiness = blockAt(onboardingPanelSource, "walkReadinessControls = LinearLayout(context).apply {")
        val firstRunUi = blockAt("private fun updateFirstRunOnboardingUi()")

        assertTrue(startupButton.contains("actions.readiness.onStartupConfirmed()"))
        val startupAction = blockAt("onStartupConfirmed = {")
        assertTrue(startupAction.contains("handleStartupCapabilityConfirmAction()"))
        assertTrue(blockAt("private fun buildContentView()").contains("nativeRegistrationComposition.publishEducationAndReadiness()"))
        assertTrue(MainActivityRefactorSource.member(registration, "publishEducationAndReadiness").contains("firstRun.publishReadinessTargets()"))
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(MainActivityRefactorSource.property(source, "deviceCheckPresenter").contains("nativeRegistrationComposition.devicePresenter()"))
        val confirmation = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/StartupConfirmationInteractionController.kt").readText()
        val confirmAction = MainActivityRefactorSource.member(confirmation, "handleStartupCapabilityConfirmAction")
        assertTrue(MainActivityRefactorSource.member(source, "handleStartupCapabilityConfirmAction").contains("startupConfirmationInteraction.handleStartupCapabilityConfirmAction()"))
        assertTrue(confirmAction.contains("session.state == WalkSessionState.PAUSED"))
        assertTrue(confirmAction.contains("effects.prepareResumeRetry()"))
        val startupFactory = source.substringAfter("private fun createStartupConfirmationInteraction(): StartupConfirmationInteractionController =")
            .substringBefore("private fun handleStartupCapabilityConfirmAction()")
        assertTrue(startupFactory.contains("override fun prepareResumeRetry() = walkSessionResumeController.prepareConfirmationRetry()"))
        assertTrue(MainActivityRefactorSource.property(source, "startupConfirmationInteraction").contains("createStartupConfirmationInteraction()"))
        assertTrue(blockAt(onboardingPresenterSource, "fun readinessBinding(").contains("publishedReadiness"))
        assertTrue(blockAt(onboardingPresenterSource, "fun publishReadinessTargets(").contains("publishedReadiness = onboarding"))
        assertTrue(readiness.contains("addView(startupCapabilityConfirmButton)"))
        assertTrue(firstRunUi.contains("WalkSessionState.PAUSED"))
        assertTrue(firstRunUi.contains("effects.renderPreparation {"))
        val preparation = blockAt(onboardingPresenterSource, "fun renderPreparation(")
        assertTrue(preparation.contains("walkReadinessControls.visibility"))
        val refreshDelegate = blockAt("private fun refreshStartupCapabilityUi()")
        assertTrue(refreshDelegate.contains("ui.render(decision, confirmed, priorityUserDecision, officialEnvironmentReady, phoneMountingReady)"))
        val refresh = blockAt(devicePresenterSource, "fun startupPresentation(")
        assertTrue(refresh.contains("val resumeRecheckRequired"))
        assertTrue(refresh.contains("보행 재개 상태 다시 확인"))
    }

    @Test
    fun startConfirmationCannotBeEnabledBeforeItsReadinessTokenExists() {
        val refreshDelegate = blockAt("private fun refreshStartupCapabilityUi()")
        assertTrue(refreshDelegate.contains("ui.render(decision, confirmed, priorityUserDecision, officialEnvironmentReady, phoneMountingReady)"))
        val factsRenderer = blockAt(File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/presentation/StartupCapabilityFactsRenderer.kt").readText(), "fun renderStartupCapabilityFacts(")
        assertTrue(factsRenderer.contains("confirmationTokenPresent = walk.confirmationToken != null"))
        assertTrue(MainActivityRefactorSource.property(source, "startupCapabilityFactsRenderer").contains("override fun walkSnapshot() = walkSessionLifecycle.snapshot()"))
        assertTrue(MainActivityRefactorSource.property(source, "startupCapabilityCoordinator").contains("renderStartupCapabilityFacts("))
        val refresh = blockAt(devicePresenterSource, "fun startupPresentation(")
        val advance = blockAt("private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")

        assertTrue(refresh.contains("val confirmationTokenReady"))
        assertTrue(refresh.contains("confirmationTokenReady &&"))
        assertTrue(refresh.contains("!confirmationTokenReady -> \"보행 준비 확인 중\""))
        assertTrue(advance.contains("val createdConfirmationToken"))
        assertTrue(advance.contains("if (createdConfirmationToken)"))
        assertTrue(advance.contains("effects.refreshReadinessUi()"))
    }

    @Test
    fun homeSearchHasItsOwnAvailabilityWhileNavigationKeepsWalkReadiness() {
        val availabilityBridge = blockAt("private fun updateWalkFeatureAvailabilityUi()")
        val searchPresenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText()
        val availability = blockAt(destinationPanelSource, "override fun renderSearchAvailability(")
        assertTrue(availabilityBridge.contains("destinationSearchPresenter.renderAvailability("))
        assertTrue(availabilityBridge.contains("nativeUiPublication.isPublished(NativeUiPublication.Target.DESTINATION_SEARCH_RESULTS_CONTAINER)"))
        assertTrue(blockAt(searchPresenter, "fun renderAvailability(").contains("requireNotNull(target).renderSearchAvailability(searchAvailable, inFlight, routeAvailable, resultsReady)"))
        assertTrue(blockAt("private fun buildContentView()").contains("destinationSearchPresenter.bindPanel(destinationPanel)"))
        val searchAvailability = blockAt("private fun currentDestinationSearchAllowsWork()")
        val homeContext = blockAt("private fun nativeForegroundFeatureContextAvailable()")
        val search = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/DestinationSearchController.kt").readText(), "search")
        val searchWiring = MainActivityRefactorSource.property(source, "destinationSearchController")
        assertTrue(searchWiring.contains("override fun searchAllowed() = currentDestinationSearchAllowsWork()"))
        val content = MainActivityRefactorSource.member(source, "buildContentView")
        assertTrue(content.contains("onSearch = {\n                    this@MainActivity.destinationSearchController.search(reset = true)"))
        assertTrue(content.contains("onLoadMore = {\n                    this@MainActivity.destinationSearchController.search(reset = false)"))
        val voiceSearch = blockAt("private fun startVoiceDestinationSearch(query: String)")
        val moreCandidates = blockAt("private fun hearMoreVoiceDestinationCandidates()")
        val destinationSelection = blockAt("private fun onDestinationSelected(")
        val nativeStart = delegatedOwner("startNativeDestinationGuidance", "nativeGuidanceEntryController", "navigation/start/NativeGuidanceEntryController.kt", "start", "{ if (handleNativeGuidanceResumeAction()) return refreshGuidanceDiagnosticAuthority() guidanceDiagnosticRuntime?.startGuidanceSession() nativeGuidanceEntryController.start() }")
        val preparationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/start/NativePrewalkController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(source, "beginNativeWalkFromHome")
            .contains("nativePrewalkController.beginFromHome()"))
        assertTrue(MainActivityRefactorSource.member(preparationSource, "beginFromHome")
            .contains("begin(permissionRecovery = false)"))
        val nativePreparation = blockAt(preparationSource, "private fun begin(permissionRecovery: Boolean)")
        val nativePrewalk = blockAt("private fun continueNativePrewalkIfReady()")
        val mountingAssessment = blockAt(File("src/main/java/kr/co/hanium/dreamup/walksafe/session/mounting/PhoneMountingSessionController.kt").readText(), "fun currentPhoneMountingAssessment(")
        assertTrue(MainActivityRefactorSource.member(source, "currentPhoneMountingAssessment").contains("phoneMountingSession.currentPhoneMountingAssessment(epoch, nowElapsedRealtimeMs, phase, previousState, cameraFrameQualityOverride, runtimeRetryRequested)"))
        val routeRequest = blockAt("private fun requestRoute(")
        val locationEntry = blockAt("private fun handleLocationUpdate(")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(locationEntry,
            "if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return",
            "location.toPositioningObservation(receivedAtElapsedRealtimeMs, sources.mock(location))",
            "handleLocationObservation(", "observation, owner, locationGeneration, binding,"))
        val locationUpdate = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt",
        ).readText(), "handleLocationObservation")
        assertTrue(locationUpdate.contains("if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return"))
        val voiceSelection = blockAt("private fun selectVoiceDestinationCandidate(oneBasedIndex: Int)")
        val routeAction = blockAt("private fun onRouteButtonClicked()")
        val voiceNavigationActions = listOf(
            blockAt("private fun requestRerouteFromVoice(expectedToken: RouteNavigatorDecisionToken?)"),
            blockAt("private fun recheckLocationFromVoice(expectedToken: RouteNavigatorDecisionToken?)"),
            blockAt("private fun speakNextNavigationInstruction()"),
        )
        listOf("requestRerouteFromVoice", "recheckLocationFromVoice").forEach { action ->
            assertTrue(
                blockAt("private fun $action()").contains("$action(routeRun().navigator.pendingDecisionToken())"),
            )
            val tokenAction = blockAt("private fun $action(expectedToken: RouteNavigatorDecisionToken?)")
            if (action == "requestRerouteFromVoice") {
                assertTrue(tokenAction.contains("if (expectedToken == null)"))
            } else {
                assertTrue(tokenAction.contains("if (!route.requestLocationRecheck(expectedToken))"))
                assertTrue(tokenAction.contains("explainRouteCommandUnavailable()"))
                val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
                assertTrue(MainActivityRefactorSource.member(peers, "requestLocationRecheck")
                    .contains("guidance().requestLocationRecheck(expectedToken, effects::startNavigationServicesIfNeeded)"))
                assertTrue(MainActivityRefactorSource.factory(source, "createVoiceInteractionControllers").contains("route = VoiceRoutePeerAdapter("))
                val recheck = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText(), "requestLocationRecheck")
                assertTrue(recheck.contains("expectedToken == null"))
                assertTrue(ReportStaticSourceInspector.appearsInOrder(recheck,
                    "expectedToken == null", ") return false", "routeNavigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, expectedToken)"))
                assertFalse(recheck.contains("pendingDecisionToken()"))
            }
            assertFalse(tokenAction.contains("pendingDecisionToken()"))
        }

        assertTrue(availabilityBridge.contains("currentNavigationCollectionAllowsWork()"))
        assertTrue(availabilityBridge.contains("currentDestinationSearchAllowsWork()"))
        assertTrue(searchAvailability.contains("currentNavigationCollectionAllowsWork() ||"))
        assertTrue(searchAvailability.contains("nativeForegroundFeatureContextAvailable() &&"))
        assertTrue(searchAvailability.contains("postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)"))
        assertTrue(homeContext.contains("!firstRunOnboardingComplete() || !isActivityForeground"))
        assertTrue(homeContext.contains("permissionRecoveryGate.blocksAutomaticResourceStart"))
        assertTrue(availability.contains("destinationQueryInput.isEnabled"))
        assertTrue(availability.contains("destinationSearchButton.isEnabled"))
        assertTrue(availability.contains("routeButton.isEnabled"))
        assertTrue(availability.contains("destinationSearchResultsContainer"))
        assertTrue(availability.contains("child is Button"))
        assertTrue(availability.contains("child.isEnabled = searchAvailable && !inFlight"))
        assertTrue(availability.contains("필수 기능과 로그인 상태를 확인한 뒤 목적지와 경로 안내를 사용할 수 있습니다"))
        assertTrue(search.contains("actions.explainUnavailable()"))
        assertTrue(search.contains("!facts.searchAllowed()"))
        listOf(voiceSearch, moreCandidates).forEach { action ->
            assertTrue(action.contains("!route.destinationSearchAllowsWork()"))
            assertTrue(action.contains("route.explainNavigationFeatureUnavailable()"))
        }
        assertTrue(
            destinationSelection.indexOf("effects.explainUnavailable()") in
                0 until destinationSelection.indexOf("cancelNavigationRequestsForDestinationSelection()"),
        )
        assertTrue(nativeStart.contains("actions.handOffDestinationStart(destination)"))
        val destinationHandoff = source.substringAfter("internal fun handOffNativeDestinationStart(")
            .substringBefore("private fun beginGuidancePreparationSpeech(")
        assertTrue(destinationHandoff.contains("onDestinationSelected(it, retainExplicitStartUntilTrustedLocation = true)"))
        assertTrue(nativePreparation.contains("NativePhoneMountingCheckRequest("))
        assertTrue(Regex("\\bPhoneMountingCheckRequest\\(").containsMatchIn(nativePreparation))
        assertTrue(File("src/main/java/kr/co/hanium/dreamup/walksafe/device/PhoneMountingPolicy.kt").readText().contains("val requestedAtElapsedRealtimeMs: Long"))
        assertTrue(nativePreparation.contains("PhoneMountingCheckRequest(epoch, ++sensorRequestId, elapsedRealtimeMs())"))
        assertTrue(
            nativePreparation.indexOf("NativePhoneMountingCheckRequest(") in
                0 until nativePreparation.indexOf("actions.confirmEnvironment()"),
        )
        assertFalse(nativePreparation.contains("phoneMountingUserConfirmation ="))
        assertTrue(nativePrewalk.contains("nativePhoneMountingCheckRequest"))
        val prewalkWiring = MainActivityRefactorSource.property(source, "nativePrewalkController")
        assertTrue(prewalkWiring.contains("NativePrewalkController(nativeWorkflowController,"))
        assertTrue(prewalkWiring.contains("override fun environmentReadiness(epoch: WalkRuntimeEpoch) = officialEnvironmentReadiness(epoch).first"))
        assertTrue(prewalkWiring.contains("override fun mountingReadiness(epoch: WalkRuntimeEpoch) = phoneMountingReadiness(epoch).first"))
        val prewalkConfirmation = ReportStaticSourceInspector.maskNonCode(
            ReportStaticSourceInspector.functionBlock(prewalkWiring, "override fun completeStartupConfirmation"),
        ).filterNot(Char::isWhitespace)
        assertTrue(prewalkConfirmation ==
            "{if(walkSessionLifecycle.snapshot().state==WalkSessionState.PAUSED){" +
                "this@MainActivity.startupConfirmationInteraction.confirmStartupCapabilityDecision()" +
                "}else{" +
                "this@MainActivity.startupConfirmationInteraction.completeStartupCapabilityConfirmation(decision)}}")
        assertTrue(prewalkWiring.contains("SystemClock::elapsedRealtime"))
        val routeWiring = MainActivityRefactorSource.member(source, "createVoiceInteractionControllers")
        assertTrue(routeWiring.contains("override fun destinationSearchAllowsWork() = currentDestinationSearchAllowsWork()"))
        assertTrue(routeWiring.contains("override fun navigationCollectionAllowsWork() = currentNavigationCollectionAllowsWork()"))
        assertTrue(routeWiring.contains("override fun explainNavigationFeatureUnavailable() { this@MainActivity.explainNavigationFeatureUnavailable() }"))
        val startupActionIndex = nativePrewalk.indexOf("actions.completeStartupConfirmation(decision)")
        listOf(
            "checks.environmentReadiness(epoch) != WalkSessionReadinessStatus.READY",
            "checks.mountingReadiness(epoch) != WalkSessionReadinessStatus.READY",
            "!decision.mayConfirmAndStart",
            "walk.confirmationToken == null",
            "checks.readinessBlockReason(decision) != null",
        ).forEach { realReadinessGate ->
            assertTrue(nativePrewalk.indexOf(realReadinessGate) in 0 until startupActionIndex)
        }
        assertTrue(mountingAssessment.contains("PhoneMountingPolicy.assess("))
        assertTrue(mountingAssessment.replace(Regex("\\s+"), " ").contains("cameraFrameQualityOverride ?: latestPhoneMountingCameraAssessment"))
        assertTrue(mountingAssessment.contains("cameraFrameQuality = cameraFrameQuality"))
        assertTrue(mountingAssessment.contains("checkRequest = nativeCheck(epoch)"))
        assertTrue(Regex("\\bcheckRequest\\s*=").containsMatchIn(mountingAssessment))
        assertFalse(mountingAssessment.contains("CameraFrameQualityAssessment("))
        assertTrue(routeRequest.contains("retainPendingExplicitRouteStart(destination, requestedDestinationCoordinateEvidence)"))
        assertTrue(routeRequest.contains("start_request_pending"))
        assertTrue(
            // The normal filtered-fix path still checks collection readiness first.
            // Test mode also has an earlier, explicitly gated raw-fix continuation.
            locationUpdate.lastIndexOf("continuePendingExplicitRouteStartIfReady()") >
                locationUpdate.indexOf("if (!scope.navigationAllowed)"),
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
        // S06-40: while walking the number only selects the place; "길안내 시작" requests the route.
        assertTrue(
            voiceSelection.indexOf("walkingDestinationChange.offer(selected, owner,") in
                0 until voiceSelection.lastIndexOf("search().clearVoiceSelection()"),
        )
        assertFalse(voiceSelection.contains("onDestinationSelected("))
        val homeSelection = voiceSelection
            .substringAfterLast("if (ui.homeContextAvailable()) {")
            .substringBefore("// S06-40 (FP-026-DETAIL-01)")
        assertTrue(homeSelection.contains("ui.openDestinationConfirmation(selected)"))
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
            assertTrue(action.contains("!route.navigationCollectionAllowsWork()"))
            assertTrue(action.contains("route.explainNavigationFeatureUnavailable()"))
        }
    }

    @Test
    fun guidanceHasOneStatusWriterAndHomePreparationPreservesDestination() {
        val render = blockAt("private fun renderMainUi()")
        val pages = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePagePresentationController.kt").readText()
        val semantic = blockAt(destinationPanelSource, "override fun renderGuidanceStatus(")
        val failure = blockAt(nativeScreenPresenterSource, "fun applyVoiceFirstPresentation(")
        val assignments = Regex("nativeGuidanceStatusText\\.text\\s*=")
        // Original semantic state and GUIDANCE-only speech failure are separate writers.
        assertTrue("The measured semantic status has one active Panel writer", assignments.findAll(semantic).count() == 1)
        assertTrue("The GUIDANCE failure override has one active presenter writer", assignments.findAll(failure).count() == 1)
        assertTrue(assignments.findAll(destinationPanelSource).count() == 1)
        assertTrue(assignments.findAll(nativeScreenPresenterSource).count() == 1)
        assertTrue(render.contains("pages.renderFrame("))
        val frame = blockAt(pages, "fun renderFrame(")
        assertTrue(frame.contains("if (workflow().nativeUiPage == NativeUiPage.GUIDANCE)"))
        assertTrue(frame.contains("phases.renderGuidanceStatus(presentation, queries.walkingCalibrationStatusMessage())"))
        assertTrue(MainActivityRefactorSource.property(source, "nativePagePresentationController").contains("updateNativeGuidanceStatus(presentation, measurements)"))
        val writer = MainActivityRefactorSource.member(source, "updateNativeGuidanceStatus")
        assertTrue(writer.contains("destinationSearchPresenter.renderGuidanceStatus(nativeGuidanceStatusMessage(presentation, false),"))
        assertTrue(writer.contains("presentation.retryAvailable, nativeGuidanceSpeechFailure"))
        val searchPresenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText()
        val status = blockAt(searchPresenter, "fun renderGuidanceStatus(")
        assertTrue(status.contains("guidanceMessage + retryMessage, speechFailure"))
        assertTrue(status.contains("다시 시도하거나 안내를 종료하세요."))
        assertTrue(status.contains("requireNotNull(target).renderGuidanceStatus("))
        assertTrue(semantic.contains("if (nativeGuidanceStatusText.text?.toString() != message)"))
        assertTrue(semantic.contains("nativeGuidanceStatusText.text = message"))
        assertTrue(failure.contains("val guidanceVisible = pageName == \"GUIDANCE\""))
        assertTrue(failure.contains("speechFailure?.takeIf { guidanceVisible }"))
        assertTrue(failure.contains("if (nativeGuidanceStatusText.text?.toString() != failure)"))
        assertTrue(failure.contains("nativeGuidanceStatusText.text = failure"))
        assertTrue(failure.contains("nativeGuidanceStatusText.contentDescription = failure"))
        val content = blockAt("private fun buildContentView()")
        assertTrue(content.contains("destinationSearchPresenter.bindPanel(destinationPanel)"))
        assertTrue(content.contains("destination = destinationPanel"))
        val voiceFirst = blockAt("private fun applyNativeVoiceFirstPresentation(")
        assertTrue(voiceFirst.contains("nativeScreenPresenter.applyVoiceFirstPresentation("))
        val message = MainActivityRefactorSource.member(source, "nativeGuidanceStatusMessage")
        assertTrue(message.contains("destinationPreparationMessage = ::nativeDestinationPreparationMessage"))
        assertTrue(message.contains("officialEnvironmentFailureSpeech(currentOfficialEnvironmentAssessment(),"))
        assertTrue(message.contains("setOf(OfficialEnvironmentFactor.GPS_QUALITY)"))
        val start = delegatedOwner("startNativeDestinationGuidance", "nativeGuidanceEntryController", "navigation/start/NativeGuidanceEntryController.kt", "start", "{ if (handleNativeGuidanceResumeAction()) return refreshGuidanceDiagnosticAuthority() guidanceDiagnosticRuntime?.startGuidanceSession() nativeGuidanceEntryController.start() }")
        assertTrue(start.indexOf("workflow.nativeUiPage = NativeUiPage.GUIDANCE") in 0 until start.indexOf("prewalk.beginFromHome()"))
        assertTrue(start.contains("val destination = workflow.pendingUiDestination ?: return"))
        assertTrue(start.contains("workflow.pendingNativeUiPage = NativeUiPage.DESTINATION_CONFIRM"))
        assertTrue(start.contains("actions.handOffDestinationStart(destination)"))
        val handoff = source.substringAfter("internal fun handOffNativeDestinationStart(")
            .substringBefore("private fun beginGuidancePreparationSpeech(")
        assertTrue(handoff.contains("onDestinationSelected(it, retainExplicitStartUntilTrustedLocation = true)"))
        assertFalse(start.contains("clearNativeDestinationSearchState("))
        assertFalse(start.contains("showNativeUiPage(NativeUiPage.GUIDANCE)"))
        assertFalse(start.contains("showNativeUiPage(NativeUiPage.HOME)"))
        val styling = blockAt("private fun applyNativePreviewPresentation(")
        assertTrue(styling.contains("NativePreviewPresentationReader.read("))
        val previewPolicy = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePreviewPresentationPolicy.kt").readText()
        assertTrue(previewPolicy.contains("!guidanceVisible"))
        assertTrue(styling.contains("nativeScreenPresenter.renderPreviewAccountAndPreparation(preview,"))
        val preparationStyling = blockAt(nativeScreenPresenterSource, "fun renderPreviewAccountAndPreparation(")
        assertTrue(preparationStyling.contains("show(walkReadinessControls, !facts.guidanceVisible"))
    }

    @Test
    fun pausingOrResettingTheWalkClearsAnyPendingExplicitRouteStart() {
        val cancelEntry = blockAt("private fun cancelWalkSessionOutputs(")
        val cancelOutputs = blockAt("private fun cancelWalkSessionOutputsInternal(")
        val routeState = blockAt("internal fun cancelWalkSessionRouteState(")
        val resetRoute = blockAt("private fun resetRouteState(")

        assertTrue(cancelEntry.contains("cancelWalkSessionOutputsInternal(reason, null)"))
        assertTrue(cancelOutputs.contains("cancelWalkSessionRouteState(reason, checkpoints, navigationCancellation)"))
        assertTrue(routeState.contains("WalkSessionState.PAUSED"))
        assertTrue(routeState.contains("reason == \"voice_pause\" && checkpoints == null"))
        assertTrue(routeState.contains("suspendNavigationForRecovery(navigationCancellation)"))
        assertTrue(routeState.indexOf("clearPendingExplicitRouteStart()") in
            0 until routeState.indexOf("suspendNavigationForRecovery(navigationCancellation)"))
        assertTrue(routeState.contains("resetRouteState(purgeRouteSnapshot = false)"))
        assertTrue(routeState.contains("resetRouteState()"))
        assertTrue(resetRoute.contains("clearPendingExplicitRouteStart()"))
    }

    @Test
    fun permissionRecoveryPanelRemainsInTheSingleProductScroll() {
        val regularOverlay = source.substringAfter("val overlay = contentComposer.createOverlayContainer(")
            .substringBefore("walkSafetyOverlay = LinearLayout(this).apply {")

        assertTrue(regularOverlay.contains("addView(firstRunOnboardingPresenter.permissionBinding().permissionDenialPanel)"))
        assertTrue(regularOverlay.contains("addView(nativeScreenPresenter.searchBinding().runtimeControls)"))
    }

    @Test
    fun lastSpokenInteractionIsRetainedWithoutDuplicateLiveAnnouncement() {
        val speak = MainActivityRefactorSource.function(source, "private fun speakCommandResponse(")
        val result = blockAt(File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoicePresentationAdapters.kt").readText(), "override fun showLastResult(")
        val view = blockAt(File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText(), "fun createLastWalkResult(")
        assertTrue(blockAt("private fun buildContentView()").contains("walkLastResultText = runtimeComposer.createLastWalkResult()"))

        assertTrue(speak.contains("presentation.showLastResult(message)"))
        assertTrue(MainActivityRefactorSource.member(source, "createSpeechOutputController").contains("presentation = voiceSpeechPresentation"))
        assertTrue(
            speak.indexOf("presentation.showLastResult(message)") <
                speak.indexOf("shouldSuppressFeedbackDuringVoiceRecognition"),
        )
        assertTrue(result.contains("val target = lastResultTarget() ?: return"))
        assertTrue(result.contains("target.text = line"))
        assertTrue(result.contains("target.contentDescription = line"))
        assertTrue(MainActivityRefactorSource.property(source, "voiceSpeechPresentation").contains("AndroidSpeechPresentation(voiceUiEffects, { voiceInputController },"))
        val presentation = blockAt(File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoicePresentationAdapters.kt").readText(), "override fun showLastResult(")
        assertTrue(presentation.contains("마지막 안내: \$message"))
        assertTrue(MainActivityRefactorSource.property(source, "voiceSpeechPresentation").contains("walkLastResultText"))
        assertTrue(view.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertFalse(view.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
    }

    private fun blockAt(marker: String): String = blockAt(source, marker)

    private fun blockAt(blockSource: String, marker: String): String {
        if (Regex("^(?:private |internal |public |override )?fun ").containsMatchIn(marker)) {
            if (blockSource === source) return MainActivityRefactorSource.function(blockSource, marker)
            val name = Regex("fun\\s+(\\w+)").find(marker)!!.groupValues[1]
            return try { MainActivityRefactorSource.member(blockSource, name) }
            catch (_: IllegalStateException) { ReportStaticSourceInspector.functionBlock(blockSource, marker.substringBefore("(")) }
        }
        val markerIndex = blockSource.indexOf(marker)
        check(markerIndex >= 0) { "missing block marker: $marker" }
        val open = blockSource.indexOf('{', markerIndex)
        check(open >= 0) { "missing opening brace: $marker" }
        var depth = 0
        for (index in open until blockSource.length) {
            when (blockSource[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return blockSource.substring(markerIndex, index + 1)
                }
            }
        }
        error("unterminated block: $marker")
    }

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = ReportStaticSourceInspector.maskNonCode(caller).filterNot(Char::isWhitespace)
        val body = Regex.escape(ReportStaticSourceInspector.maskNonCode(expectedBody).filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}
