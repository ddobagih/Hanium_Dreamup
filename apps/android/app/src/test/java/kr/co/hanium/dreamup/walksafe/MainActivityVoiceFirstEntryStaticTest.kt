package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring checks; real microphone and audible speech remain device checks. */
class MainActivityVoiceFirstEntryStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val homePanelSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/HomePanel.kt").readText()
    private val destinationPanelSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
    private val nativeScreenPresenterSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt").readText()
    private val homePresenterSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeHomeUiPresenter.kt").readText()
    private val voicePlatformSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/AndroidVoiceInputPlatform.kt").readText()
    private val voicePresentationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoicePresentationAdapters.kt").readText()
    private val voiceInputComposition get() = MainActivityRefactorSource.composition(source, "voiceInputController")
    private fun method(name: String) = MainActivityRefactorSource.function(source, "private fun $name(")

    @Test fun homePanelKeepsAccessibleCardsAndReceivesAvailabilityFromMain() {
        for (label in listOf("카메라 테스트", "음성 명령", "길라잡이", "설정")) {
            assertTrue(homePanelSource.contains("homeCard(\"$label\""))
        }
        val card = blockAt(homePanelSource, "private fun homeCard(")
        assertTrue(card.contains("Button(context)"))
        assertTrue(card.contains("style.applyWsSecondaryButtonStyle(this)"))
        assertTrue(card.contains("View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(card.contains("setOnClickListener { onOpen() }"))
        val grid = viewDeclaration(homePanelSource, "homeCardGrid")
        assertTrue(grid.contains("LinearLayout.VERTICAL"))
        assertTrue(grid.contains("cameraCard.takeIf { props.showCameraTest }"))
        assertTrue(grid.contains("(16f * context.resources.displayMetrics.density).roundToInt()"))
        assertTrue(grid.contains("ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f"))
        val availability = blockAt(homePanelSource, "private fun renderCardAvailability(")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(availability,
            "val cardEnabled = !runtimeActive || available", "card.isEnabled = cardEnabled",
            "card.contentDescription = if (cardEnabled)"))
        assertTrue(availability.contains("\"\$title. \$subtitle\""))
        assertTrue(availability.contains("\"\$title. \$subtitle. 잠김. \$lockTitle. \$lockDetail\""))
        val availabilityInputs = blockAt(homePanelSource, "fun renderAvailability(")
        for ((field, available) in listOf(
            "cameraCard" to "true", "voiceCard" to "voiceAvailable",
            "destinationCard" to "searchAvailable", "settingsCard" to "true",
        )) {
            assertTrue(
                "Card availability: $field",
                Regex("renderCardAvailability\\(\\s*$field,\\s*runtimeActive,\\s*available\\s*=\\s*$available,")
                    .containsMatchIn(availabilityInputs),
            )
        }
        assertTrue(homePanelSource.contains("기기 장착 상태와 기기 상태를 확인해주세요."))
        val restriction = viewDeclaration(homePanelSource, "nativeHomeRestrictionText")
        assertTrue(restriction.contains("앱 내의 기능 사용 제한"))
        assertTrue(restriction.contains("View.GONE"))
        assertTrue(restriction.contains("style.applyNativeNoticeStyle(this, \"warning\")"))
        for (field in listOf("nativeHomeCompassText", "nativeHomeRouteStatusText")) {
            val view = viewDeclaration(homePanelSource, field)
            assertTrue(view.contains("View.GONE"))
            assertTrue(view.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        }
        assertTrue(viewDeclaration(homePanelSource, "nativeHomeCompassText").contains("나침반 방향을 준비하고 있습니다."))
        assertTrue(viewDeclaration(homePanelSource, "nativeHomeRouteStatusText").contains("text = props.initialRouteStatus"))

        val creation = between(source, "val homePanel = HomePanel(", "applyWsButtonStyle(actionButton,")
        assertTrue(creation.contains("showCameraTest = BuildConfig.DEBUG"))
        assertTrue(creation.contains("initialRouteStatus = homeRouteStatusMessage()"))
        assertTrue(creation.contains("onCamera = ::openLiveCameraScreen"))
        assertTrue(blockAt(creation, "onVoice = {").contains("requestNativeFeature(NativeUiPage.VOICE_COMMAND)"))
        assertTrue(blockAt(creation, "onDestinationSearch = {").contains("requestNativeFeature(NativeUiPage.DESTINATION_SEARCH)"))
        assertTrue(blockAt(creation, "onSettings = {").contains("showNativeUiPage(NativeUiPage.SETTINGS)"))
        val refresh = blockAt(creation, "homeCardRefreshers += {")
        assertTrue(refresh.contains("homePanel.renderAvailability("))
        assertTrue(refresh.contains("runtimeActive = isWalkSessionRuntimeActive()"))
        assertTrue(refresh.contains("voiceAvailable = nativeFeatureAvailable(NativeUiPage.VOICE_COMMAND)"))
        assertTrue(refresh.contains("searchAvailable = nativeFeatureAvailable(NativeUiPage.DESTINATION_SEARCH)"))
        assertTrue(creation.contains("homeCardRefreshers.forEach { it() }"))
        assertTrue(method("refreshHomeCards").contains("homeCardRefreshers.forEach { it() }"))
        assertTrue(MainActivityRefactorSource.property(source, "nativeHomeUiPresenter").contains("NativeHomeUiPresenter()"))
        for ((field, slot, panel) in listOf(
            Triple("homeCardGrid", "Grid", "gridPanel"),
            Triple("nativeHomeCompassText", "Compass", "compassPanel"),
            Triple("nativeHomeRouteStatusText", "Route", "routePanel"),
        )) {
            assertTrue(creation.contains("nativeHomeUiPresenter.publish${slot}Target(homePanel)"))
            assertTrue(MainActivityRefactorSource.member(homePresenterSource, "publish${slot}Target").contains("$panel = panel"))
            assertTrue(MainActivityRefactorSource.member(homePresenterSource, "${slot.lowercase()}Target").contains("$panel.$field"))
        }
        assertTrue(creation.contains("overlay.addView(homePanel.nativeHomeRestrictionText, overlay.indexOfChild(walkStatusSection))"))
        var previousIndex = -1
        for (target in listOf("homePanel.nativeHomeRestrictionText", "nativeHomeUiPresenter.compassTarget()",
            "nativeHomeUiPresenter.routeTarget()", "nativeHomeUiPresenter.gridTarget()")) {
            val index = creation.indexOf("overlay.addView($target, overlay.indexOfChild(walkStatusSection))")
            assertTrue("Home slot order: $target", index > previousIndex)
            previousIndex = index
        }
    }

    @Test fun destinationPanelKeepsSearchGuidanceAndVoiceViewsWithMainOwnedActions() {
        val query = viewDeclaration(destinationPanelSource, "destinationQueryInput")
        assertTrue(query.contains("hint = \"검색어\""))
        assertTrue(query.contains("contentDescription = \"목적지 입력\""))
        assertTrue(query.contains("InputType.TYPE_CLASS_TEXT"))
        assertTrue(query.contains("WS_TOUCH_WALK_ACTION_DP"))
        val more = viewDeclaration(destinationPanelSource, "destinationMoreButton")
        assertTrue(more.contains("text = \"결과 더보기\""))
        assertTrue(more.contains("isEnabled = false"))
        assertTrue(more.contains("visibility = View.GONE"))
        assertTrue(more.contains("actions.onLoadMore()"))
        assertTrue(viewDeclaration(destinationPanelSource, "destinationCancelButton").contains("isEnabled = true"))
        for (field in listOf("nativeDestinationConfirmationText", "nativeGuidanceDestinationText")) {
            assertTrue(viewDeclaration(destinationPanelSource, field).contains("ViewCompat.setAccessibilityHeading(this, true)"))
        }
        val status = viewDeclaration(destinationPanelSource, "nativeGuidanceStatusText")
        assertTrue(status.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        val measurements = viewDeclaration(destinationPanelSource, "nativeGuidanceMeasurementText")
        assertTrue(measurements.contains("View.GONE"))
        assertTrue(measurements.contains("View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"))
        assertTrue(measurements.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        val voice = viewDeclaration(destinationPanelSource, "voiceReportButton")
        assertTrue(voice.contains("음성 명령 듣기 시작"))
        assertTrue(voice.contains("actions.onVoiceStart()"))
        assertTrue(viewDeclaration(destinationPanelSource, "gatewayVoiceStatusText").contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        for ((field, label, action) in listOf(
            Triple("nativeDestinationStartButton", "안내 시작", "onStart"),
            Triple("nativeDestinationCancelButton", "안내 취소", "onSearchCancel"),
            Triple("nativeGuidanceRetryButton", "다시 시도", "onStart"),
            Triple("nativeGuidanceCancelButton", "안내 종료", "onGuidanceCancel"),
            Triple("nativeVoiceCancelButton", "음성 명령 취소", "onVoiceCancel"),
        )) {
            val view = viewDeclaration(destinationPanelSource, field)
            assertTrue(view.contains("style.accessiblePriorityUserButton("))
            assertTrue(view.contains("label = \"$label\""))
            assertTrue(view.contains("onClick = actions.$action"))
        }

        val creation = destinationCreation()
        assertTrue(blockAt(creation, "onSearch = {").contains("destinationSearchController.search(reset = true)"))
        assertTrue(blockAt(creation, "onLoadMore = {").contains("destinationSearchController.search(reset = false)"))
        MainActivityRefactorSource.composition(source, "destinationSearchController")
        assertTrue(creation.contains("onSearchCancel = ::cancelNativeDestinationSearchAndReturnHome"))
        assertTrue(creation.contains("onStart = ::startNativeDestinationGuidance"))
        assertTrue(creation.contains("onGuidanceCancel = ::showWalkExitConfirmationDialog"))
        assertTrue(blockAt(creation, "onVoiceStart = {").contains("ensureVoicePermissionThenListen()"))
        assertTrue(creation.contains("onVoiceCancel = ::cancelNativeVoiceCommandAndReturnHome"))
        assertDestinationPanelBindings()
        for (field in listOf(
            "destinationQueryInput", "destinationSearchButton", "destinationCancelButton",
            "destinationSearchResultsContainer", "destinationMoreButton", "voiceReportButton",
            "gatewayVoiceStatusText", "nativeDestinationControls", "nativeDestinationConfirmationControls",
            "nativeDestinationConfirmationText", "nativeDestinationStartButton", "nativeDestinationCancelButton",
            "nativeGuidanceControls", "nativeGuidanceDestinationText", "nativeGuidanceStatusText",
            "nativeGuidanceMeasurementText", "nativeGuidanceRetryButton", "nativeGuidanceCancelButton",
            "nativeVoiceControls", "nativeVoiceCancelButton", "runtimeControls",
        )) assertTrue("Destination binding: $field", viewDeclaration(destinationPanelSource, field).contains("val $field ="))
    }

    @Test fun deviationButtonsReadTheLatestMainDecisionWithoutArrivalChoices() {
        val creation = destinationCreation()
        assertDestinationPanelBindings()
        for ((action, callback) in listOf(
            "onRoute" to "onRouteButtonClicked()",
            "onReroute" to "requestRerouteFromVoice()",
            "onRecheck" to "recheckLocationFromVoice()",
            "onEndDeviation" to "endNavigationAfterDeviation()",
        )) assertTrue(blockAt(creation, "$action = {").contains(callback))
        for ((field, action) in listOf(
            "routeButton" to "onRoute", "routeDeviationNewRouteButton" to "onReroute",
            "routeDeviationRecheckButton" to "onRecheck", "routeDeviationEndButton" to "onEndDeviation",
        )) {
            assertTrue(viewDeclaration(destinationPanelSource, field).contains("setOnClickListener { actions.$action() }"))
            val target = field.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
            assertTrue(source.contains("nativeUiPublication.publish(NativeUiPublication.Target.$target)"))
        }
        assertFalse(destinationPanelSource.contains("renderedArrivalDecisionToken"))
        val actions = viewDeclaration(destinationPanelSource, "routeDeviationActions")
        assertTrue(actions.contains("visibility = View.GONE"))
        assertTrue(actions.contains("View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE"))
        for (field in listOf("offRouteNoticeText", "routeDeviationNewRouteButton", "routeDeviationRecheckButton",
            "routeDeviationEndButton")) {
            assertTrue(actions.contains("addView($field)"))
        }
        assertTrue(viewDeclaration(destinationPanelSource, "routeDeviationSection").contains("visibility = View.GONE"))
        val renderDecision = method("updateRouteDeviationActions")
        assertTrue(renderDecision.contains("nativeScreenPresenter.searchBinding().routeDeviationActions"))
        assertTrue(renderDecision.contains("nativeScreenPresenter.searchBinding().routeDeviationSection"))
        assertTrue(renderDecision.contains("nativeScreenPresenter.searchBinding().offRouteNoticeText"))
        assertFalse(source.contains("addView(routeDeviationSection)"))
        assertFalse(destinationPanelSource.contains("arrivalConfirmButton"))
        assertFalse(destinationPanelSource.contains("arrivalRejectButton"))
        assertFalse(renderDecision.contains("renderedArrivalDecisionToken"))
        assertFalse(nativeScreenPresenterSource.contains("revealArrivalChoice"))
    }

    @Test fun allOrdinaryForegroundPagesCanWakeWithoutAuthorizingBackgroundOrActiveProbe() {
        val context = method("foregroundHomeWakeContextAvailable")
        assertFalse(context.contains("nativeUiPage == NativeUiPage.HOME"))
        assertTrue(context.contains("environment.page().noPendingPageOrGuidance"))
        val pageFacts = voiceInputComposition.substringAfter("override fun page()")
            .substringBefore("override fun walkOrNull()")
        assertTrue(pageFacts.contains("pendingNativeUiPage == null || nativeUiPage == NativeUiPage.GUIDANCE"))
        assertFalse(pageFacts.contains("nativeUiPage == NativeUiPage.HOME"))
        for (guard in listOf("environment.page().noPendingPageOrGuidance", "session.homeCommandAvailable()",
            "environment.disclosureAccepted()", "!session.state().recognitionActive", "!foregroundWakeHandoffInProgress")) {
            assertTrue(guard, context.contains(guard))
        }
        assertTrue(voiceInputComposition.contains("override fun disclosureAccepted() = isHandsFreeVoiceDisclosureAccepted()"))
        val voiceInputSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
        assertTrue(MainActivityRefactorSource.property(voiceInputSource, "wake").contains("override fun homeCommandAvailable() = homeVoiceCommandAvailable()"))
        val availability = method("nativeForegroundFeatureContextAvailable")
        assertTrue(availability.contains("!isActivityForeground"))
        assertTrue(availability.contains("!firstRunOnboardingComplete()"))
        assertTrue(availability.contains("WalkSessionState.READY, WalkSessionState.SAFE_STOP, WalkSessionState.ENDED"))
        assertFalse(availability.contains("WalkSessionState.ACTIVE"))
        val probe = method("refreshForegroundHomeWakeListening")
        assertTrue(probe.contains("environment.page().owner == page && environment.walkAreaCommandAvailable() == walkArea"))
        assertTrue(probe.contains("requireNotNull(environment.walkOrNull()).epoch == epoch"))
        assertTrue(pageFacts.contains("VoiceInputPage(nativeUiPage,"))
        assertTrue(voiceInputComposition.contains("walkSessionLifecycle.snapshot()"))
        assertTrue(probe.contains("suppressed = {\n                !isCurrent()"))
        assertTrue(MainActivityRefactorSource.member(voicePlatformSource, "createProbe").contains("isInputSuppressed = callbacks.suppressed"))
    }

    @Test fun wakeHandoffReservesMicrophoneAcrossPageRendering() {
        val ack = method("acknowledgeForegroundHomeWake")
        assertTrue(ack.indexOf("foregroundWakeHandoffInProgress = true") in
            0 until ack.indexOf("presentation.showCommandPage()"))
        assertTrue(MainActivityRefactorSource.property(source, "voiceInputPresentation").contains("VoiceInputPresentationAdapter(voiceUiEffects, { voiceInputController }, { nativeWorkflowController },"))
        assertTrue(MainActivityRefactorSource.member(voicePresentationSource, "showCommandPage").contains("workflow().showNativeUiPage(NativeUiPage.VOICE_COMMAND, preserveVoiceInteraction = true)"))
        assertTrue(ack.contains("foregroundHomeWakeAcknowledgementGeneration == generation"))
        assertTrue(ack.contains("foregroundWakeHandoffInProgress = false"))
        assertTrue(method("cancelForegroundHomeWakeAcknowledgement").contains("foregroundWakeHandoffInProgress = false"))
        val service = method("maybeStartHandsFreeVoiceService")
        assertFalse(service.contains("nativeUiPage == NativeUiPage.VOICE_COMMAND && !handsFreeVoiceOwnsCommandPage"))
        assertTrue(File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt")
            .readText().contains("override fun otherInputBusy() = voiceRecognitionActive || voiceCommandPromptPending ||"))
        for (guard in listOf("input.otherInputBusy()", "!environment.runtimeActive()",
            "environment.microphoneGranted()", "environment.notificationGranted()", "environment.disclosureAccepted()")) {
            assertTrue(guard, service.contains(guard))
        }
        for (binding in listOf("override fun runtimeActive() = isWalkSessionRuntimeActive()",
            "override fun microphoneGranted() = hasRecordAudioPermission()",
            "override fun notificationGranted() = hasHandsFreeNotificationPermission()",
            "override fun disclosureAccepted() = isHandsFreeVoiceDisclosureAccepted()")) {
            assertTrue(binding, voiceInputComposition.contains(binding))
        }
    }

    @Test fun ordinaryScreensHideExplanationsButKeepAccessibleActions() {
        val render = method("renderMainUi")
        assertTrue(render.contains("pages.renderFrame(NativeScreenPresenter.FrameFacts(nativeUiPage.name, homeAvailable, homeVisible,"))
        assertTrue(render.contains("settingsVisible, featureVisible, forceSettings, deletionRecovery)"))
        val workflow = MainActivityRefactorSource.composition(source, "nativeWorkflowController")
        assertTrue(workflow.contains("this@MainActivity.nativePagePresentationController.renderFrame(frame)"))
        val pagePresentation = MainActivityRefactorSource.composition(source, "nativePagePresentationController")
        assertTrue(pagePresentation.contains("screen = { nativeScreenPresenter }"))
        val pageSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePagePresentationController.kt").readText()
        val renderFrame = MainActivityRefactorSource.member(pageSource, "renderFrame")
        assertTrue(renderFrame.contains("screen().beginFrame(frame)"))
        val frame = blockAt(nativeScreenPresenterSource, "fun beginFrame(")
        val voiceVisibility = between(frame, "show(destination.nativeVoiceControls,", "show(destination.gatewayVoiceStatusText,")
            .replace(Regex("\\s+"), " ")
        assertTrue(voiceVisibility.contains("show(destination.nativeVoiceControls, facts.featureVisible && facts.pageName == \"VOICE_COMMAND\")"))
        assertFalse(voiceVisibility.contains("facts.featureVisible ||"))
        for (page in listOf("HOME", "SETTINGS", "GUIDANCE", "DESTINATION_CONFIRM", "DESTINATION_SEARCH")) {
            assertFalse("Voice controls hidden on $page", voiceVisibility.contains("facts.pageName == \"$page\""))
        }
        assertTrue(renderFrame.contains("phases.renderVoiceFirst(frame.homeAvailable)"))
        assertTrue(pagePresentation.contains("override fun renderVoiceFirst(homeAvailable: Boolean) { applyNativeVoiceFirstPresentation(homeAvailable) }"))
        val delegate = method("applyNativeVoiceFirstPresentation")
        assertTrue(delegate.contains("if (!homeAvailable) return"))
        assertTrue(delegate.contains("nativeScreenPresenter.applyVoiceFirstPresentation(homeAvailable, nativeUiPage.name, nativeGuidanceSpeechFailure)"))
        val presentation = blockAt(nativeScreenPresenterSource, "fun applyVoiceFirstPresentation(")
        for (view in listOf("nativeHomeCompassText", "nativeHomeRouteStatusText", "nativeGuidanceStatusText",
            "nativeGuidanceMeasurementText", "nativeSettingsTitle")) assertTrue(presentation.contains(view))
        assertTrue(presentation.contains("val guidanceVisible = pageName == \"GUIDANCE\""))
        assertTrue(presentation.contains("val visibility = if (shown) View.VISIBLE else View.GONE"))
        assertTrue(presentation.contains("if (it.visibility != visibility) it.visibility = visibility"))
        assertTrue(presentation.contains("nativeGuidanceRetryButton.contentDescription"))
        assertFalse(source.contains("label = \"고정 자료 성능 확인\""))
        val navigation = MainActivityRefactorSource.function(source,
            "private fun showNativeUiPage(page: NativeUiPage, preserveVoiceInteraction: Boolean)")
        assertTrue(navigation.contains("pages.displayPage(page)"))
        assertTrue(workflow.contains("this@MainActivity.nativePagePresentationController.displayPage(page)"))
        assertTrue(MainActivityRefactorSource.member(pageSource, "displayPage").contains("phases.selectFocusTarget(page)"))
        assertTrue(pagePresentation.contains("nativeScreenPresenter.pageFocusTarget(page, { nativeHomeUiPresenter }, { settingsPrivacyPresenter })"))
        val focus = MainActivityRefactorSource.member(nativeScreenPresenterSource, "pageFocusTarget")
        assertTrue(focus.contains("NativeUiPage.DESTINATION_CONFIRM -> guidanceBinding().nativeDestinationStartButton"))
        val guidanceFocus = ReportStaticSourceInspector.maskNonCode(
            ReportStaticSourceInspector.blockAfter(focus, "NativeUiPage.GUIDANCE -> when"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(guidanceFocus,
            "searchBinding().routeDeviationNewRouteButton.isShown -> searchBinding().routeDeviationNewRouteButton",
            "searchBinding().routeDeviationRecheckButton.isShown -> searchBinding().routeDeviationRecheckButton",
            "guidanceBinding().nativeGuidanceRetryButton.isShown -> guidanceBinding().nativeGuidanceRetryButton",
            "else -> guidanceBinding().nativeGuidanceCancelButton"))
    }

    @Test fun failureSpeechIsGuardedBoundedAndOnlyDoneCompletesDelivery() {
        val failure = method("announceNativePrewalkFailureIfNeeded")
        for (guard in listOf("workflow.nativePhoneMountingCheckRequest == request", "scope.reporterId() == request.actorId",
            "workflow.pendingUiDestination == request.destination", "!scope.runtimeActive()")) assertTrue(failure.contains(guard))
        assertTrue(failure.contains("failureSpeech.begin(request.checkRequest.requestId) ?: return"))
        assertTrue(failure.contains("failureSpeech.fail(attempt)"))
        assertTrue(failure.contains("speech.submit(reason, ::current, {"))
        assertTrue(failure.contains("if (current()) failureSpeech.complete(attempt)"))
        assertTrue(failure.contains("}, failed, failed, failed)"))
        val prewalk = MainActivityRefactorSource.composition(source, "nativePrewalkController")
        assertTrue(prewalk.contains("NativePrewalkController(nativeWorkflowController,"))
        assertTrue(prewalk.contains("override fun reporterId() = reporterUserId"))
        assertTrue(prewalk.contains("override fun runtimeActive() = isWalkSessionRuntimeActive()"))
        assertTrue(prewalk.contains("additionalCurrent = current,"))
        assertTrue(prewalk.contains("onCompleted = completed, onFailed = failed, onCancelled = cancelled, onRetired = retired"))
        assertTrue(method("cancelNativePrewalkPreparation").contains("failureSpeech.cancel()"))
        val startup = method("deliverStartupCapabilityNotice")
        assertTrue(startup.contains("effects.speakNotice("))
        val startupComposition = MainActivityRefactorSource.composition(source, "startupConfirmationInteraction")
        val startupSpeech = blockAt(startupComposition, "effects = object : StartupConfirmationEffects")
        assertTrue(startupSpeech.contains("statusExplanation = true"))
        assertTrue(startup.contains("onCompleted = deliveredOnMain"))
        assertTrue(startup.contains("onRetired = failedOnMain"))
        assertTrue(startup.contains("confirmationToken == confirmationToken"))
        assertTrue(startup.contains("lifecycle().snapshot().epoch == epoch"))
        assertTrue(startupComposition.contains("lifecycle = { walkSessionLifecycle }"))
    }

    @Test fun criticalSpeechRetirementIsDetachedBeforeReplacingOrCancellingTheOwner() {
        val response = method("speakCommandResponse")
        assertTrue(response.contains("stateSource, additionalCurrent, onRetired, nativeParts)"))
        assertTrue(response.indexOf("takeRetirementCallbacks()") < response.indexOf("registerLatest("))
        assertTrue(response.indexOf("registerLatest(") < response.indexOf("retiredCallbacks.forEach"))
        assertTrue(response.contains("onRetired = { commandSpeechTracker.terminal(trackedResponse, false); onRetired?.invoke() }"))
        val cancel = method("cancelCommandSpeechResponse")
        assertTrue(cancel.indexOf("takeRetirementCallbacks()") < cancel.indexOf("commandSpeechResponseCallbacks.clear()"))
        assertTrue(cancel.contains("retiredCallbacks.forEach"))
    }

    private fun viewDeclaration(panelSource: String, name: String): String {
        val marker = "    val $name = "
        val start = panelSource.indexOf(marker)
        check(start >= 0) { "missing panel view: $name" }
        val next = panelSource.indexOf("\n    val ", start + marker.length)
        return panelSource.substring(start, if (next < 0) panelSource.length else next)
    }

    private fun destinationCreation() = between(source,
        "val destinationPanel = DestinationGuidancePanel(", "destinationSearchPresenter.bindPanel(destinationPanel)")

    private fun assertDestinationPanelBindings() {
        assertTrue(MainActivityRefactorSource.property(source, "nativeScreenPresenter").contains("NativeScreenPresenter(viewStyle)"))
        for ((group, stored) in listOf("Voice" to "publishedVoice", "Search" to "publishedSearch", "Guidance" to "publishedGuidance")) {
            assertTrue(source.contains("nativeScreenPresenter.publish${group}Targets(destinationPanel)"))
            assertTrue(MainActivityRefactorSource.member(nativeScreenPresenterSource, "publish${group}Targets").contains("$stored = panel"))
            assertTrue(MainActivityRefactorSource.member(nativeScreenPresenterSource, "${group.lowercase()}Binding").contains("= $stored"))
        }
        val framePanels = between(source, "nativeScreenPresenter.bindPanels(NativeScreenPresenter.Panels(",
            "nativeScreenPresenter.bindRuntimeViews(")
        assertTrue(framePanels.contains("destination = destinationPanel"))
    }

    private fun between(blockSource: String, startMarker: String, endMarker: String): String {
        val start = blockSource.indexOf(startMarker)
        check(start >= 0) { "missing start marker: $startMarker" }
        val end = blockSource.indexOf(endMarker, start + startMarker.length)
        check(end > start) { "missing end marker: $endMarker" }
        return blockSource.substring(start, end)
    }

    private fun blockAt(blockSource: String, marker: String): String {
        val start = blockSource.indexOf(marker)
        check(start >= 0) { "missing block marker: $marker" }
        val open = blockSource.indexOf('{', start)
        check(open >= 0) { "missing opening brace: $marker" }
        var depth = 0
        for (index in open until blockSource.length) {
            when (blockSource[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return blockSource.substring(start, index + 1)
                }
            }
        }
        error("unterminated block: $marker")
    }
}
