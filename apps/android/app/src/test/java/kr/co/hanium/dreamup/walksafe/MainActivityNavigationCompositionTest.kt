package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.postlogin.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavigationCompositionTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun offRouteAndArrivalCandidatesNeverTriggerAutomaticNetworkOrShutdown() {
        val guidance = NavigationPolicySource.guidance(source)

        assertFalse(guidance.contains("requestRoute("))
        assertFalse(
            Regex("""if\s*\(update\.arrived\)\s*\{[^}]*isRouteActive\s*=\s*false""")
                .containsMatchIn(guidance),
        )
    }

    @Test
    fun trustedGpsRecoveryDoesNotAutomaticallyRequestANewRoute() {
        val locationUpdate = NavigationPolicySource.observation(source)

        assertFalse(locationUpdate.contains("requestRoute("))
    }

    @Test
    fun destinationWithoutTrustedGpsStaysSelectedButInactiveUntilExplicitRetry() {
        val selection = functionBlock("private fun onDestinationSelected(")
        val request = functionBlock("private fun requestRoute(")
        val routeButton = functionBlock("private fun onRouteButtonClicked(")

        assertTrue(selection.contains("run.selectPendingDestination(result.point)"))
        assertFalse(selection.contains("isRouteActive = true"))
        assertTrue(routeButton.contains("currentDestination ?: parseDestinationInput()"))

        assertTrue(request.contains("val origin = facts.currentLocation"))
        val missingGps = request.substringAfter("val origin = facts.currentLocation")
            .substringBefore("if (navigationRequests.hasActiveRoute()")
        assertTrue(missingGps.contains("currentDestination = destination"))
        assertTrue(missingGps.contains("isRouteActive = false"))
        assertFalse(missingGps.contains("walkingRouteClient.fetchRouteCall("))
        assertFalse(missingGps.contains("backend.fetchRoute("))
        assertTrue(
            request.indexOf("isRouteActive = true") >
                request.indexOf("routeRequestInFlight.compareAndSet(false, true)"),
        )
    }

    @Test
    fun arrivalDoesNotRenderManualConfirmOrRejectActions() {
        assertVoiceNavigationIdentity()
        val controls = destinationPanelSource()
        assertFalse(controls.contains("arrivalConfirmButton"))
        assertFalse(controls.contains("arrivalRejectButton"))
        assertFalse(controls.contains("onConfirmArrival"))
        assertFalse(controls.contains("onRejectArrival"))
    }

    @Test
    fun routeGuidanceUsesStrideOnlyAsAuxiliaryProgressEvidence() {
        val guidance = NavigationPolicySource.guidance(source)

        assertTrue(guidance.contains("stepProgressM = routeStepProgressMOrNull()"))
        assertFalse(guidance.contains("latitude = step"))
        assertFalse(guidance.contains("longitude = step"))
    }

    @Test
    fun voicePagingIsThreeAtATimeAndOnlySelectsFromTheCurrentPage() {
        assertVoiceNavigationIdentity()
        val hearMore = functionBlock("private fun hearMoreVoiceDestinationCandidates(")
        val selection = functionBlock("private fun selectVoiceDestinationCandidate(")

        assertTrue(source.contains("const val DESTINATION_SEARCH_PAGE_SIZE = 3"))
        assertTrue(hearMore.contains("DestinationSearchVoiceCommand.HearMore"))
        // S06-9: local paging only; the first search already fetched the whole candidate cap.
        assertFalse(hearMore.contains("performDestinationSearch("))
        assertTrue(selection.contains("DestinationSearchVoiceCommand.SelectCandidate(oneBasedIndex)"))
        // S06-40: selecting only selects; the separate "길안내 시작" requests the route and says so.
        val start = functionBlock("private fun startPendingWalkingDestinationChange(")
        assertFalse(selection.contains("onDestinationSelected("))
        assertTrue(start.contains("if (!route.onDestinationSelected(selected)) {"))
        assertTrue(start.contains("경로를 확인합니다"))
        assertTrue(
            start.indexOf("if (!route.onDestinationSelected(selected)) {") <
                start.indexOf("경로를 확인합니다"),
        )
    }

    @Test
    fun destinationSearchRejectsOverlongQueriesBeforeCreatingANetworkCall() {
        val searchOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/DestinationSearchController.kt").readText()
        val search = MainActivityRefactorSource.member(searchOwner, "search")
        val actions = MainActivityRefactorSource.member(source, "buildContentView")
        assertTrue(actions.contains("this@MainActivity.destinationSearchController.search(reset = true)"))
        assertTrue(actions.contains("this@MainActivity.destinationSearchController.search(reset = false)"))

        val backend = MainActivityRefactorSource.composition(source, "destinationSearchController")
        assertTrue(backend.contains("DestinationSearchController("))
        assertTrue(backend.contains("walkingRouteClient.searchDestinationsCall(session, query, limit, origin)"))
        assertTrue(backend.contains("gatewaySessionOrNull(\"destination_search\")"))
        assertTrue(search.contains("canonicalDestinationSearchQueryOrNull(rawQuery)"))
        assertTrue(search.contains("navigation=destination_query_invalid max_code_points=80"))
        assertTrue(
            search.indexOf("canonicalDestinationSearchQueryOrNull(rawQuery)") <
                search.indexOf("actions.sessionOrNull()"),
        )
        assertTrue(
            search.indexOf("canonicalDestinationSearchQueryOrNull(rawQuery)") <
                search.indexOf("backend.searchCall("),
        )
    }

    @Test
    fun voiceDecisionCommandsAreBoundToTheRouteRevisionAtRecognitionStart() {
        val recognitionStart = functionBlock("private fun startVoiceCommandRecognition(")
        val commandHandler = functionBlock("private fun handleVoiceCommandPhrases(")

        assertTrue(recognitionStart.contains("expectedNavigationDecisionToken"))
        assertTrue(recognitionStart.contains("environment.pendingNavigationDecisionToken()"))
        assertTrue(MainActivityRefactorSource.composition(source, "voiceInputController")
            .contains("override fun pendingNavigationDecisionToken() = routeNavigator.pendingDecisionToken()"))
        assertVoiceNavigationIdentity()
        assertTrue(commandHandler.contains("routeRun().navigator.pendingDecisionToken()"))
        assertTrue(commandHandler.contains("voice=navigation_decision_stale"))
        assertTrue(commandHandler.contains("AndroidVoiceAction.StopNavigation"))
    }

    @Test
    fun routeDeviationCancelsNavigationBeforeItsInteractionPrompt() {
        MainActivityRefactorSource.composition(source, "routeGuidanceController")
        val guidance = NavigationPolicySource.guidance(source)
        val deviation = functionBlock("private fun applyRouteDeviationSafetyUpdate(")

        val cancellation = deviation.indexOf("speech.cancelNavigation()")
        val prompt = deviation.indexOf("speakRouteDecision(it, token)")
        assertTrue(cancellation >= 0)
        assertTrue(prompt > cancellation)
        assertTrue(deviation.contains("update.cancelStaleNavigationSpeech"))
        val speechBinding = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(speechBinding.contains("GuidanceSpeechPeerAdapter("))
        assertTrue(speechBinding.contains("output = { speechOutputController }"))
        assertTrue(java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/GuidanceSpeechPeerAdapter.kt").readText()
            .contains("override fun cancelNavigation() { output().currentActuatorOrNull()?.cancelNavigationSpeech() }"))
        assertTrue(deviation.contains("update.userDecisionRequired"))
        assertTrue(guidance.contains("applyRouteDeviationSafetyUpdate(update)"))
    }

    @Test
    fun confirmedDeviationRendersExactlyThreeAccessibleActions() {
        val controls = destinationPanelSource().substringAfter("val routeDeviationNewRouteButton = Button(context).apply")
            .substringBefore("val nativeDestinationConfirmationText")

        assertEquals(1, Regex("text = \"새 경로 요청\"").findAll(controls).count())
        assertEquals(1, Regex("text = \"위치 다시 확인\"").findAll(controls).count())
        assertEquals(1, Regex("text = \"길안내 종료\"").findAll(controls).count())
        assertEquals(3, Regex("addView\\(routeDeviation").findAll(controls).count())
        assertTrue(controls.contains("contentDescription = text"))
        assertTrue(source.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
    }

    @Test
    fun teammateTmapUiHandoffIsAdaptedToTheCurrentNavigationLayout() {
        val destination = MainActivityRefactorSource.property(destinationPanelSource(), "destinationQueryInput")
        val controls = MainActivityRefactorSource.property(destinationPanelSource(), "routeDeviationActions")
        val update = functionBlock("private fun updateRouteDeviationActions(")

        assertTrue(destination.contains("contentDescription = \"목적지 입력\""))
        assertTrue(controls.contains("addView(offRouteNoticeText)"))
        assertTrue(update.contains("confirmed -> \"경로를 벗어났습니다\""))
        assertTrue(update.contains("suspected -> \"위치를 다시 확인해 주세요\""))
        assertTrue(update.contains("else -> \"\""))
    }

    @Test
    fun onlyNewRouteChoiceCallsTheTmapRouteRequest() {
        assertVoiceNavigationIdentity()
        val newRoute = functionBlock(
            "private fun requestRerouteFromVoice(expectedToken: RouteNavigatorDecisionToken?)",
        )
        val recheck = functionBlock(
            "private fun recheckLocationFromVoice(expectedToken: RouteNavigatorDecisionToken?)",
        )
        val end = functionBlock(
            "private fun endNavigationAfterDeviation(expectedToken: RouteNavigatorDecisionToken?)",
        )
        val request = functionBlock("private fun requestRoute(")
        val destinationSelection = functionBlock("private fun onDestinationSelected(")

        listOf("requestRerouteFromVoice", "recheckLocationFromVoice", "endNavigationAfterDeviation")
            .forEach { action ->
                val uiEntry = functionBlock("private fun $action()")
                assertTrue(uiEntry.contains("$action(routeRun().navigator.pendingDecisionToken())"))
            }
        listOf(newRoute, end).forEach { action ->
            assertTrue(action.contains("if (expectedToken == null)"))
            assertFalse(action.contains("pendingDecisionToken()"))
            assertTrue(
                action.indexOf("if (expectedToken == null)") in
                    0 until action.indexOf("routeRun().navigator.selectDeviationChoice("),
            )
        }
        assertTrue(newRoute.contains("routeRun().navigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, expectedToken)"))
        val rerouteCallIndex = Regex("""requestRoute\(\s*destination,\s*reason\s*=\s*"off_route",\s*destinationCoordinateEvidence\s*=\s*routeRun\(\)\.navigator\.destinationCoordinateEvidenceFor\(destination\),\s*\)""").find(newRoute)?.range?.first ?: -1
        assertTrue(rerouteCallIndex >= 0)
        assertTrue(
            newRoute.indexOf("if (!decision.shouldReroute)") in
                0 until rerouteCallIndex,
        )
        val recheckPolicy = NavigationPolicySource.locationRecheck(source)
        assertTrue(recheck.contains("if (!route.requestLocationRecheck(expectedToken))"))
        assertTrue(recheckPolicy.indexOf("expectedToken == null") in
            0 until recheckPolicy.indexOf("routeNavigator.selectDeviationChoice("))
        assertTrue(recheckPolicy.contains("routeNavigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, expectedToken)"))
        assertTrue(recheckPolicy.contains("decision.reason != \"off_route_location_recheck_requested\""))
        assertFalse(recheckPolicy.contains("pendingDecisionToken()"))
        assertFalse(recheck.contains("pendingDecisionToken()"))
        assertFalse(recheckPolicy.contains("requestRoute("))
        assertFalse(recheck.contains("requestRoute("))
        assertTrue(end.contains("routeRun().navigator.selectDeviationChoice(RouteDeviationChoice.END_NAVIGATION, expectedToken)"))
        assertTrue(end.contains("if (decision.reason != \"off_route_navigation_ended\") return"))
        assertFalse(end.contains("requestRoute("))
        assertTrue(request.contains("reason != \"off_route\" && effects.blockRouteMutation()"))
        assertTrue(destinationSelection.contains("effects.blockRouteMutation()"))
        assertTrue(destinationSelection.contains("routeRequestGenerationBefore"))
        assertTrue(destinationSelection.contains("routeRequestGeneration != routeRequestGenerationBefore"))
        assertTrue(destinationSelection.contains("routeRequestInFlight.get()"))
    }

    @Test
    fun routeWorkerStoresTheFirstSnapshotBeforeInstallAndWiresTheFailureGuard() {
        val request = functionBlock("private fun requestRoute(")
        val retainedFailure = functionBlock("private fun retainRouteAfterRerouteFailure(")
        val purgeFence = functionBlock("private fun routeSnapshotPurgeFenceAllowsRoute(")
        val snapshotSave = request.indexOf("archive.submitRouteSnapshotResponseStorage(")
        val uiCommit = request.lastIndexOf("postToUi {", snapshotSave)
        val leaseCheck = request.lastIndexOf("routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)", snapshotSave)
        val postLocationLeaseCheck = request.lastIndexOf("if (!isCurrentRouteRequest() || !isRouteActive)", snapshotSave)
        val requestOwner = request.substringAfter("fun isCurrentRouteOwner(): Boolean")
            .substringBefore("fun isCurrentRouteRequest(): Boolean")
        val requestCurrent = request.substringAfter("fun isCurrentRouteRequest(): Boolean")
            .substringBefore("updateNavigationStatus(")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(requestCurrent,
            "if (!isCurrentRouteOwner()) return false", "return routeResponseEnvironmentCurrent("))
        assertTrue(requestOwner.contains("isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)"))
        assertTrue(requestOwner.contains("currentSession.generation == expectedGatewaySessionGeneration"))
        assertTrue(requestOwner.contains("currentSession.session === gatewaySession"))
        val fences = Regex("archive\\.routeSnapshotPurgeFenceAllowsRoute\\(\\)").findAll(request).map { it.range.first }.toList()
        assertEquals(3, fences.size)
        assertTrue(fences[0] < request.indexOf("backend.fetchRoute("))
        assertTrue(uiCommit >= 0 && leaseCheck > uiCommit)
        assertTrue(request.indexOf("if (!isRouteActive || !archive.routeSnapshotPurgeFenceAllowsRoute())", uiCommit) < snapshotSave)
        assertTrue(fences[1] > leaseCheck && fences[1] < snapshotSave)
        assertTrue(postLocationLeaseCheck > fences[1] && postLocationLeaseCheck < snapshotSave)
        val completion = request.indexOf("postToUi completeSnapshot@{")
        val install = request.indexOf("installRequestedRoute(")
        assertTrue(snapshotSave < completion && completion < fences[2] && fences[2] < install)
        assertTrue(request.indexOf("!routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)", completion) in completion until fences[2])
        assertTrue(request.indexOf("!facts.sessionCurrent(gatewaySession) || !isRouteActive", completion) in completion until fences[2])
        assertTrue(request.indexOf("routeResponseLocationStatus(", completion) in completion until install)
        assertTrue(request.indexOf("if (storedRouteSnapshot == null && !facts.developmentBypass)", completion) in completion until install)
        assertTrue(request.indexOf("if (!storageAdmissionCurrent())", completion) in completion until install)
        val submit = archiveMethod("submitRouteSnapshotResponseStorage")
        val save = archiveMethod("routeSnapshotResponseArchive")
        assertTrue(submit.contains("lane.submit<StoredRouteSnapshot?>(lease, null, current)"))
        assertTrue(submit.contains("routeSnapshotResponseArchive(store, scope, captured, destination, provenance)"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(save, "val nowEpochMs = wallTimeMs()",
            "store.saveFirstRoute(", "nowEpochMs = nowEpochMs,",
            "if (!saved) null else store.load(scope, nowEpochMs = nowEpochMs)"))
        assertTrue(save.contains("takeIf { it.walkSessionId == scope.walkSessionId }"))
        val acquisition = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteAcquisitionController.kt")
        val compatibility = MainActivityRefactorSource.member(acquisition, "installRequestedRoute")
        assertTrue(compatibility.contains("installRequestedRoute(route, destination, origin, coordinateEvidence, reason,"))
        assertTrue(compatibility.contains("routeResponseLocationStatus(route, origin, origin, origin.elapsedRealtimeMs) =="))
        assertTrue(compatibility.contains("RouteResponseInstallLocationStatus.APPROACH_REQUIRED"))
        assertTrue(request.contains("completedLocationStatus == RouteResponseInstallLocationStatus.APPROACH_REQUIRED)"))
        val installer = MainActivityRefactorSource.member(acquisition, "installRequestedRoute", 1)
        assertTrue(installer.contains("approachRequired: Boolean"))
        assertTrue(installer.contains("routeNavigator.setRoute("))
        assertTrue(installer.contains("waitForInitialRouteJoin = approachRequired"))

        assertTrue(request.indexOf("catch (_: CancellationException)") < request.indexOf("catch (error: Exception)"))
        assertTrue(request.contains("tmapFailureGuard.recordSuccess()"))
        assertTrue(request.contains("tmapFailureGuard.recordFailure()"))
        assertTrue(request.contains("countableRouteFailure = routeProviderCallInProgress"))
        assertTrue(request.contains("presentation.safetyStop(\"route_consecutive_failures\")"))
        assertTrue(request.contains("navigationFailure.isUserSessionAuthentication"))
        assertTrue(request.contains("navigationFailure.routeUserMessage"))
        assertTrue(request.contains("scheduleEncryptedRouteSnapshotExpiry("))
        assertTrue(retainedFailure.contains("effects::applyDeviationUpdate"))

        assertTrue(purgeFence.contains("if (!routeSnapshotPurgeFailed) return true"))
        assertTrue(purgeFence.contains("presentation.safetyStop(\"route_snapshot_purge_failed\")"))
        assertTrue(purgeFence.contains("presentation.status(\"길안내 저장소 오류 · 안전 중지\", detail)"))
        assertTrue(purgeFence.contains("presentation.speak(detail)"))
        assertTrue(purgeFence.indexOf("presentation.speak(detail)") < purgeFence.lastIndexOf("return false"))
    }

    @Test
    fun terminalRouteSafetyStopsSpeakOneBoundedAccessibleDetail() {
        val request = functionBlock("private fun requestRoute(")
        val tmapStopStart = request.indexOf(
            "presentation.safetyStop(\"route_consecutive_failures\")",
        )
        val tmapStopEnd = request.indexOf("return@postToUi", tmapStopStart)
        val tmapStop = request.substring(tmapStopStart, tmapStopEnd)
        val snapshotStop = ReportStaticSourceInspector.blockAfter(
            request, "if (storedRouteSnapshot == null && !facts.developmentBypass)",
        )
        assertTrue(snapshotStop.contains("return@completeSnapshot"))
        val expiry = functionBlock("private fun scheduleEncryptedRouteSnapshotExpiry(")

        assertTrue(tmapStop.contains("navigationFailure.routeUserMessage"))
        assertTrue(tmapStop.contains("모든 보행 기능을 중지했습니다"))
        assertEquals(1, Regex("presentation\\.speak\\(safetyStopDetail\\)").findAll(tmapStop).count())
        assertFalse(tmapStop.contains("error.message"))
        assertFalse(tmapStop.contains("error.toString"))
        assertFalse(tmapStop.contains("backendCode"))
        assertEquals(1, Regex("presentation\\.speak\\(detail\\)").findAll(snapshotStop).count())
        assertTrue(expiry.contains("onEncryptedRouteSnapshotExpired(scope, lease, operation)"))
        val expired = archiveMethod("onEncryptedRouteSnapshotExpired")
        assertEquals(1, Regex("presentation\\.speak\\(detail\\)").findAll(expired).count())
    }

    @Test
    fun positionQualityLossDoesNotMasqueradeAsRouteDeviationAndPauseKeepsTheEncryptedSnapshot() {
        val location = NavigationPolicySource.observation(source)
        val clearLocation = functionBlock("private fun clearTrustedLocation(")
        val confidence = functionBlock("private fun applyPositionConfidenceDecision(")
        val foregroundPause = functionBlock("private fun enterWalkSessionForegroundRecheckAndCancelOutputs(")
        val cancelEntry = functionBlock("private fun cancelWalkSessionOutputs(")
        val cancelOutputs = functionBlock("private fun cancelWalkSessionOutputsInternal(")
        val reset = functionBlock("private fun resetRouteState(")
        val purge = functionBlock("private fun purgeEncryptedRouteSnapshot(")

        assertTrue(location.contains("binding.coordinator.observeGnss("))
        assertTrue(location.contains("expectedGeneration = binding.inputGeneration"))
        assertTrue(
            location.indexOf("!isLocationObservationCurrent(owner, locationGeneration, binding)") in
                0 until location.indexOf("binding.coordinator.observeGnss("),
        )
        assertFalse(location.contains("handleRouteLocationUntrusted()"))
        assertFalse(clearLocation.contains("handleRouteLocationUntrusted()"))
        assertTrue(confidence.contains("speech.cancelNavigation()"))
        assertTrue(confidence.contains("positionGuidancePaused = decision.guidancePaused"))
        assertFalse(confidence.contains("directionGuidancePauseReason = pauseReason"))
        assertTrue(confidence.contains("confidenceLease.delivered(elapsedRealtimeMs())"))
        assertTrue(MainActivityRefactorSource.composition(source, "routeGuidanceController")
            .contains("override fun delivered(nowMs: Long) { binding.coordinator.commitAnnouncementDelivered(token, nowMs) }"))
        assertFalse(confidence.contains("navigationRunState.pauseDirection("))
        assertTrue(foregroundPause.contains("cancelWalkSessionOutputs(reason)"))
        assertTrue(cancelEntry.contains("cancelWalkSessionOutputsInternal(reason, null)"))
        assertTrue(cancelOutputs.contains("navigation.cancelWalkSessionRouteState(reason, checkpoints, navigationCancellation)"))
        val routeCleanup = MainActivityRefactorSource.member(source, "cancelWalkSessionRouteState")
        assertTrue(routeCleanup.contains("WalkSessionState.PAUSED"))
        assertTrue(routeCleanup.contains("resetRouteState(purgeRouteSnapshot = false)"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkSessionCleanupController")
            .contains("this@MainActivity.cancelWalkSessionRouteState(reason, checkpoints, cancellation)"))
        assertTrue(reset.contains("if (purgeRouteSnapshot) archive.purgeEncryptedRouteSnapshot()"))
        assertTrue(purge.contains("expectedScope: RouteSnapshotScope? = routeSnapshotWalkScope"))
        assertTrue(purge.contains("submitRouteSnapshotScopePurge(lane, lease, expectedScope"))
        val scopePurge = archiveMethod("submitRouteSnapshotScopePurge")
        assertTrue(scopePurge.contains("store.clearWalk(scope)"))
        val observePurge = archiveMethod("observeEncryptedRouteSnapshotPurge")
        assertTrue(observePurge.contains("routeSnapshotPurgeFailed = !purged"))
    }

    @Test
    fun poorGpsUsesCoordinatorSoftGateAndFilteredLocation() {
        val handler = NavigationPolicySource.observation(source)

        assertTrue(handler.contains("binding.coordinator.observeGnss("))
        assertTrue(handler.contains("expectedGeneration = binding.inputGeneration"))
        assertTrue(handler.contains("receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs"))
        assertTrue(handler.contains("GnssObservationDisposition.HARD_REJECTED"))
        assertFalse(handler.contains("LocationTrustPolicy.trustedOrNull("))
        assertTrue(handler.contains("latestTrustedLocation = filteredTrusted"))
        assertTrue(handler.contains("guidance().dispatchAcceptedRouteFix("))
        val acceptedFix = functionBlock("internal fun dispatchAcceptedRouteFix(")
        assertTrue(acceptedFix.contains("dispatchGuidance: (TrustedLocation, TrustedLocation) -> Unit = ::updateRouteGuidance"))
        assertTrue(acceptedFix.contains("dispatchGuidance(location, rawLocation)"))
        assertTrue(MainActivityRefactorSource.composition(source, "positioningSessionController")
            .contains("{ guidance.value }"))
        assertTrue(handler.contains("measurementNoiseMultiplier"))
    }

    @Test
    fun pdrMagneticHeadingRequiresAValidExplicitChestMountContract() {
        val step = functionBlock("private fun handlePositioningStepEvent(")
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions").contains("sources = { createNavigationAndroidSources() }"))
        assertTrue(MainActivityRefactorSource.member(source, "createNavigationAndroidSources").contains("orientation = { earthOrientationTracker }"))
        val chest = functionBlock("private fun chestMountedHeadingForStep(")
        val motion = functionBlock("private fun applyPositioningMotionSnapshot(")
        val heading = NavigationPolicySource.heading(source)
        val route = NavigationPolicySource.guidance(source)

        assertTrue(step.contains("pedestrianMotionTracker?.recordStep(event.timestampMs)"))
        assertTrue(
            step.indexOf("pedestrianMotionTracker?.recordStep(event.timestampMs)") <
                step.indexOf("binding.coordinator.observeStep("),
        )
        assertTrue(step.contains("binding.coordinator.observeStep("))
        assertTrue(step.contains("expectedGeneration = binding.inputGeneration"))
        assertTrue(step.contains("gpsCourse = courseAtStep"))
        assertTrue(step.contains("magneticTrueHeading = chestHeading"))
        assertTrue(step.contains("phoneForwardMounted = chestHeading != null"))
        assertTrue(chest.contains("scope.fieldChestConfirmed &&"))
        assertTrue(chest.contains("scope.fieldLeasePresent"))
        val chestContract = ownerMethod("navigation/session/PositioningSessionController.kt", "chestMountedHeadingForStep", occurrence = 1)
        assertTrue(chestContract.contains("PhoneMountingMethod.CHEST_FORWARD"))
        assertTrue(chestContract.contains("phoneMountingOutputsAllowed"))
        assertTrue(chest.contains("lookupHeading = sources::chestHeading"))
        assertTrue(MainActivityRefactorSource.member(ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt"), "chestHeading")
            .contains("orientation().chestMountedHeadingAt(timestampMs, maximumAgeMs)"))
        assertTrue(step.contains("magneticTrueHeadingAtGpsCourse = chestAtCourse"))
        assertFalse(motion.contains("updateRouteGuidance("))
        assertFalse(heading.contains("location.speed > 0.5f"))
        assertTrue(heading.contains("bearingAccuracy != null"))
        assertTrue(route.contains("filteredPosition = FilteredRoutePosition("))
        assertTrue(route.contains("position.speed?.speedMps"))
        assertTrue(route.contains("standardDeviationDeg = requireNotNull(heading.accuracyDegrees)"))
        assertFalse(route.contains("standardDeviationDeg = 35.0"))
    }

    @Test
    fun fusedAndOptionalSensorsReleaseTheirSessionBoundCallbacksTogether() {
        val start = functionBlock("private fun startLocationUpdatesIfAllowed(")
        val stopWrapper = MainActivityRefactorSource.member(source, "stopLocationUpdates")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(stopWrapper,
            "cancelRouteWarmingReassessment()", "return positioningSessionController.stopLocationUpdates(stopFieldSession)"))
        val stopEntry = ownerMethod("navigation/session/PositioningSessionController.kt", "stopLocationUpdates")
        assertTrue(stopEntry.contains("stopLocationUpdatesInternal(stopFieldSession, retryRetained = true)"))
        val stop = functionBlock("private fun stopLocationUpdatesInternal(")
        val gnssClose = functionBlock("private fun closeGnssQualityObserver(")
        val sources = functionBlock("private fun startPositioningObservationSources(")

        assertTrue(start.contains("sources.requestUpdates(fusedLocationSource)"))
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        val nativeSources = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt")
        val activityPorts = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(activityPorts.contains("sources = { createNavigationAndroidSources() }"))
        assertTrue(nativeSources.contains("Priority.PRIORITY_HIGH_ACCURACY"))
        assertTrue(nativeSources.contains("Granularity.GRANULARITY_FINE"))
        assertTrue(nativeSources.contains("setMaxUpdateAgeMillis(0L)"))
        assertTrue(start.contains("startPositioningObservationSources()"))
        assertTrue(start.contains("catch (_: SecurityException)"))
        assertTrue(sources.contains("if (!scope.fineLocationGranted) return"))
        assertTrue(sources.contains("sources.gnssObserver("))
        assertTrue(sources.contains("sources.pedestrianTracker"))
        assertTrue(ReportStaticSourceInspector.blockAfter(activityPorts, "scope = { object : PositioningSessionController.Scope").contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertTrue(nativeSources.contains("AndroidGnssObservationSource("))
        assertTrue(nativeSources.contains("AndroidPedestrianMotionTracker("))
        assertTrue(sources.contains("binding.coordinator.observeZupt("))
        assertTrue(sources.contains("expectedGeneration = binding.inputGeneration"))
        assertTrue(sources.contains("ensureEarthOrientationForLocation()"))
        assertTrue(Regex(
            """attempt\s*\{\s*check\(closeGnssQualityObserver\(\)\)\s*\{\s*"gnss_cancellation_pending"\s*}\s*}""",
        ).containsMatchIn(stop))
        assertTrue(gnssClose.contains("val observer = gnssQualityObserver ?: return true"))
        val nativeClose = gnssClose.indexOf("observer.close()")
        val pending = gnssClose.indexOf("if (observer.cleanupPending) return false")
        val clear = gnssClose.indexOf("if (gnssQualityObserver === observer) gnssQualityObserver = null")
        assertTrue(nativeClose >= 0 && pending > nativeClose && clear > pending)
        assertTrue(stop.contains("catch (failure: RuntimeException)"))
        assertTrue(stop.contains("if (first == null) lastLocationCleanupFailure = failure"))
        assertTrue(stop.indexOf("closeGnssQualityObserver()") < stop.indexOf("pedestrianMotionTracker?.close()"))
        assertTrue(stop.contains("return lastLocationCleanupFailure == null && fusedRemoved"))
        assertTrue(stop.contains("pedestrianMotionTracker?.close()"))
        assertTrue(stop.contains("pedestrianMotionTracker = null"))
        assertTrue(stop.contains("effects.stopFieldSession()"))
        assertTrue(stop.contains("positioningCoordinator.reset()"))
    }

    @Test
    fun gnssQualityIsRefreshedPerFixAndHeadingChangesOnlyAfterHardAcceptance() {
        val handler = NavigationPolicySource.observation(source)
        val snapshot = "val currentGnssQualitySnapshot = observation.signalQuality"
        val acceptedHeading =
            "updatePositioningHeadingInputs(location, elapsedMs, binding, receivedAtElapsedRealtimeMs)"
        val hardReject = "if (hardRejected || filtered == null)"

        assertTrue(handler.contains(snapshot))
        assertTrue(handler.contains(acceptedHeading))
        assertTrue(handler.contains(hardReject))
        assertTrue(handler.contains("expectedGeneration = binding.inputGeneration"))
        assertTrue(
            handler.indexOf(snapshot) in 0 until handler.indexOf("binding.coordinator.observeGnss("),
        )
        assertTrue(handler.indexOf(acceptedHeading) > handler.indexOf(hardReject))
        assertTrue(handler.indexOf(acceptedHeading) > handler.indexOf("return", handler.indexOf(hardReject)))
    }

    @Test
    fun positionQualityPauseIsIndependentFromProviderPause() {
        val confidence = functionBlock("private fun applyPositionConfidenceDecision(")
        val route = NavigationPolicySource.guidance(source)

        assertTrue(confidence.contains("positionGuidancePaused = decision.guidancePaused"))
        assertFalse(confidence.contains("directionGuidancePauseReason ="))
        assertFalse(confidence.contains("navigationRunState.pauseDirection("))
        assertTrue(route.contains("positionGuidancePaused || directionGuidancePauseReason == \"tmap_unavailable\""))
    }

    @Test
    fun actorSwitchClearsAllCalibrationBaselines() {
        val profile = functionBlock("private fun ensurePositioningProfileForCurrentActor(")

        assertTrue(profile.contains("synchronizeWalkingCalibrationScope()"))
        assertTrue(functionBlock("private fun synchronizeWalkingCalibrationScope(")
            .contains("walkingCalibrationSession.synchronizeScope(actorId, walkEpoch)"))
        val scopeReset = ownerMethod("navigation/session/WalkingCalibrationSession.kt", "synchronizeScope")
        assertTrue(scopeReset.contains("current.actorId == actorId && current.walkEpoch == walkEpoch"))
        assertTrue(scopeReset.contains("segments.resetForScope()"))
        assertTrue(scopeReset.contains("latestStep = null"))
        assertTrue(functionBlock("private fun clearLocationDerivedState(").contains("walkingCalibrationSession.resetHistory()"))
        assertTrue(ownerMethod("navigation/session/WalkingCalibrationSession.kt", "resetHistory").contains("segments.reset()"))
    }

    @Test
    fun debugPositionRecorderDoesNotDependOnNavigationOutputEligibility() {
        val step = functionBlock("private fun handlePositioningStepEvent(")
        val location = NavigationPolicySource.observation(source)
        val motionSources = functionBlock("private fun startPositioningObservationSources(")
        val locationGate = functionBlock("private fun currentLocationCollectionOwnerOrNull()")
        val stepGate = functionBlock("private fun currentStepTrackingCollectionAllowsWork()")
        val fieldGate = functionBlock("private fun currentPositionFieldCollectionAllowsWork()")

        assertTrue(step.contains("!scope.fieldLeasePresent"))
        assertTrue(location.contains("if (!scope.navigationAllowed)"))
        assertTrue(location.contains("appendGnssTrace("))
        assertTrue(location.contains("appendGnssTrace(positioningSnapshot, false)"))
        assertTrue(motionSources.contains("scope.fieldLeasePresent"))
        assertTrue(locationGate.contains("positionFieldController.collectionBindingOrNull(currentPositionFieldAuthority())"))
        assertTrue(stepGate.contains("currentPositionFieldCollectionAllowsWork()"))
        assertTrue(fieldGate.contains("BuildConfig.DEBUG"))
        assertTrue(fieldGate.contains("!isActivityForeground"))
        assertTrue(fieldGate.contains("!isWalkSessionRuntimeActive()"))
        assertTrue(fieldGate.contains("!hasLocationPermission()"))
        assertTrue(fieldGate.contains("positionFieldController.collectionBindingOrNull(currentPositionFieldAuthority())"))
        val fieldAuthority = MainActivityRefactorSource.member(source, "currentPositionFieldAuthority")
        assertTrue(fieldAuthority.contains("walkSessionLifecycle.currentRuntimeEpochOrNull()"))
        assertTrue(fieldAuthority.contains("GatewaySessionProcessCoordinator.snapshot().generation"))
        val fieldBinding = ownerMethod("fieldlog/PositionFieldRecorderController.kt", "collectionBindingOrNull")
        assertTrue(fieldBinding.contains("localScopeId != active.localScopeId"))
        assertTrue(fieldBinding.contains("authority.walkEpoch != active.walkEpoch"))
        assertTrue(fieldBinding.contains("authority.gatewayGeneration != active.gatewayGeneration"))
        assertTrue(MainActivityRefactorSource.property(source, "positionFieldController")
            .contains("PositionFieldRecorderController("))
    }

    @Test
    fun calibrationLearnsOnlyFromOriginalFixWithFreshMotionEvidence() {
        val calibration = NavigationPolicySource.calibration(source)
        assertTrue(functionBlock("internal fun observeWalkingCalibrationFix(")
            .contains("walkingCalibrationSession.observeFix(fix, userMotion, processingAtElapsedRealtimeMs, coordinator)"))
        val calibrationGate = ownerMethod("navigation/session/WalkingCalibrationSession.kt", "observeFix")
        val learning = functionBlock("private fun applyWalkingCalibrationSample(")
        val step = functionBlock("private fun handlePositioningStepEvent(")

        assertTrue(calibration.contains("observeWalkingCalibrationFix("))
        assertTrue(calibrationGate.contains("coordinator.onFix("))
        assertTrue(calibration.contains("::applyWalkingCalibrationSample"))
        assertTrue(learning.contains("positioningCoordinator.calibrateProfile("))
        assertTrue(step.contains("walkingCalibrationSession.onStep("))
        val calibrationStep = ownerMethod("navigation/session/WalkingCalibrationSession.kt", "onStep")
        assertTrue(calibrationStep.contains("segments.onStep("))
        assertTrue(calibrationStep.contains("StepEventSource.STEP_DETECTOR"))
        assertTrue(step.contains("::applyWalkingCalibrationSample"))
        assertTrue(calibration.contains("RawWalkingCalibrationFix("))
        assertTrue(calibration.contains("latitude = location.latitude"))
        assertTrue(calibration.contains("longitude = location.longitude"))
        assertTrue(calibrationGate.contains("trustedRawFix = fix.trustedRawFix && freshMovingEvidence"))
        assertTrue(calibrationGate.contains("userMotion?.movement == UserMovementState.MOVING"))
        assertTrue(calibrationGate.contains("userMotion.stepObservedAtElapsedRealtimeMs != null"))
        assertTrue(calibrationGate.contains("processingAtElapsedRealtimeMs - userMotion.evaluatedAtElapsedRealtimeMs in 0L..2_000L"))
        assertTrue(calibration.contains("!location.mock"))
        assertFalse(calibration.contains("filteredTrusted"))
    }

    @Test
    fun degradedPositionQualityInterruptsMapMatchingEvidenceWithoutProviderPauseMutation() {
        val confidence = functionBlock("private fun applyPositionConfidenceDecision(")

        assertTrue(confidence.contains("val positioningEvidenceInterrupted = decision.guidancePaused && !positionGuidancePaused"))
        assertTrue(confidence.contains("if (positioningEvidenceInterrupted)"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(confidence,
            "if (positioningEvidenceInterrupted)", "routeNavigator.onPositioningEvidenceInterrupted()",
            "positionGuidancePaused = decision.guidancePaused"))
        assertTrue(confidence.contains("routeNavigator.onPositioningEvidenceInterrupted()"))
        assertFalse(confidence.contains("directionGuidancePauseReason ="))
        assertFalse(confidence.contains("navigationRunState.pauseDirection("))
    }

    @Test
    fun legacyArrivalPhrasesOnlyReadStateWithoutChangingTmapTrust() {
        val commands = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt").readText()
        val dispatch = MainActivityRefactorSource.member(commands, "executeVoiceAction")
        assertTrue(dispatch.contains("AndroidVoiceAction.ConfirmArrival,"))
        assertTrue(dispatch.contains("AndroidVoiceAction.RejectArrival -> output.speakCurrentState()"))
        assertFalse(commands.contains("route.confirmArrival("))
        assertFalse(commands.contains("navigator.rejectArrival("))
        assertFalse(commands.contains("navigator.requestArrivalConfirmation("))
        assertFalse(dispatch.contains("latestTmapOnRoute = true"))
        assertFalse(dispatch.contains("updateAcceptedRouteMatch(true)"))
        assertVoiceNavigationIdentity()
    }

    @Test
    fun androidSourceDoesNotContainATmapProviderKey() {
        val androidMain = File("src/main").walkTopDown()
            .filter(File::isFile)
            .filter { it.extension in setOf("kt", "java", "xml") }
            .joinToString("\n") { it.readText() }

        assertFalse(
            Regex("(?i)tmap[_-]?app[_-]?key\\s*=|\\\"appKey\\\"\\s*:")
                .containsMatchIn(androidMain),
        )
    }

    @Test
    fun destinationSelectionGuardsUnavailableNavigationBeforeJointCancellation() {
        val selection = functionBlock("private fun onDestinationSelected(")
        val availabilityGuard = selection.indexOf("!facts.navigationAllowed")
        val unavailableExplanation = selection.indexOf("effects.explainUnavailable()")
        val cancellation = selection.indexOf("cancelNavigationRequestsForDestinationSelection()")

        assertTrue(availabilityGuard >= 0)
        assertTrue(unavailableExplanation > availabilityGuard)
        assertTrue(cancellation > unavailableExplanation)

        val activity = MainActivity()
        val routeCancels = AtomicInteger()
        val searchCancels = AtomicInteger()
        val route = activity.trackRouteRequest(recordingCall(routeCancels))
        val search = activity.trackDestinationSearchRequest(recordingCall(searchCancels))

        activity.cancelNavigationRequestsForDestinationSelection()

        assertTrue(route.isCancelled())
        assertTrue(search.isCancelled())
        assertEquals(1, routeCancels.get())
        assertEquals(1, searchCancels.get())
    }

    @Test
    fun pauseAndDestroyHooksEachCancelTheMainActivityActiveRouteAndSearchCalls() {
        listOf("onPause", "onDestroy").forEach { lifecycleMethod ->
            val activity = MainActivity()
            setBooleanField(activity, "privacyStartupInspectionComplete", true)
            val route = activity.trackRouteRequest(recordingCall(AtomicInteger()))
            val search = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))

            // Android Looper/window entry points are unavailable on plain JVM. Exercise each real
            // cancellation hook and keep its production lifecycle connection separately asserted.
            if (lifecycleMethod == "onPause") {
                assertTrue(functionBlock("override fun onPause()").contains("pauseWalkSafeRuntime()"))
                assertTrue(functionBlock("internal fun pauseWalkSafeRuntime()")
                    .contains("val navigationCancellation = cancelNavigationRequestsForPause()"))
                activity.cancelNavigationRequestsForPause()
            } else {
                assertTrue(functionBlock("override fun onDestroy()").contains("cancelNavigationRequestsForDestroy()"))
                activity.cancelNavigationRequestsForDestroy()
            }

            assertTrue("$lifecycleMethod cancellation hook must cancel its route", route.isCancelled())
            assertTrue("$lifecycleMethod cancellation hook must cancel its search", search.isCancelled())
        }
    }

    @Test
    fun lateOldCompletionCannotUnregisterTheReplacementRouteCall() {
        val activity = MainActivity()
        val oldRoute = activity.trackRouteRequest(recordingCall(AtomicInteger()))
        activity.cancelNavigationRequestsForDestinationSelection()
        val replacement = activity.trackRouteRequest(recordingCall(AtomicInteger()))

        activity.completeRouteRequest(oldRoute)
        val paused = activity.cancelNavigationRequestsForPause()

        assertTrue(paused.routeCancelled)
        assertTrue(replacement.isCancelled())
    }

    @Test
    fun lateOldCompletionCannotUnregisterTheReplacementDestinationSearchCall() {
        val activity = MainActivity()
        val oldSearch = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))
        activity.cancelNavigationRequestsForDestinationSelection()
        val replacement = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))

        activity.completeDestinationSearchRequest(oldSearch)
        val destroyed = activity.cancelNavigationRequestsForDestroy()

        assertTrue(destroyed.destinationSearchCancelled)
        assertTrue(replacement.isCancelled())
    }

    @Test
    fun pauseCancelsRerouteAndRequiresFreshRouteWhileRetainingDestinationProposal() {
        val activity = MainActivity()
        // Keep the production status/recovery path; only Android Log is unavailable on plain JVM.
        val debugLogger: (String, String) -> Unit = { _, _ -> Unit }
        MainActivityControllerTestFixture.field("navigationStateDebugLogger").apply {
            isAccessible = true
            set(activity, debugLogger)
        }
        val destination = RoutePoint(37.001, 127.0, "목적지")
        val navigator = field(activity, "routeNavigator") as RouteNavigator
        navigator.setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(distanceM = 120, durationS = 100),
                polyline = listOf(RoutePoint(37.0, 127.0), destination),
                guidePoints = emptyList(),
            ),
            destination = destination,
        )
        setBooleanField(activity, "isRouteActive", true)
        MainActivityControllerTestFixture.field("currentDestination").apply {
            isAccessible = true
            set(activity, destination)
        }
        (field(activity, "routeRequestInFlight") as AtomicBoolean).set(true)
        val rerouteCall = activity.trackRouteRequest(recordingCall(AtomicInteger()))
        setBooleanField(activity, "privacyStartupInspectionComplete", true)

        val pause = functionBlock("internal fun pauseWalkSafeRuntime()")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(pause,
            "val navigationCancellation = cancelNavigationRequestsForPause()",
            "routeAcquisitionController.suspendNavigationForRecovery(navigationCancellation)"))
        val cancellation = activity.cancelNavigationRequestsForPause()
        val acquisition = MainActivityControllerTestFixture.controller(activity, "routeAcquisitionController")
            as kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController
        acquisition.suspendNavigationForRecovery(cancellation)

        assertTrue(rerouteCall.isCancelled())
        assertFalse(MainActivityControllerTestFixture.field("isRouteActive").run {
            isAccessible = true
            getBoolean(activity)
        })
        assertFalse(navigator.hasRoute())
        assertEquals(destination, field(activity, "currentDestination"))
        assertFalse((field(activity, "routeRequestInFlight") as AtomicBoolean).get())
    }

    private fun recordingCall(cancelCount: AtomicInteger): CancellableNetworkCall<Unit> {
        return CancellableNetworkCall(
            executeBlock = {},
            cancelBlock = { cancelCount.incrementAndGet() },
        )
    }

    private fun field(activity: MainActivity, name: String): Any? {
        return MainActivityControllerTestFixture.field(name).run {
            isAccessible = true
            get(activity)
        }
    }

    private fun setBooleanField(activity: MainActivity, name: String, value: Boolean) {
        val owner = if (name == "privacyStartupInspectionComplete" || name == "privacyStartupInspectionDestroyed") {
            field(activity, "privacyStartupInspectionController")!!
        } else activity
        if (owner is MainActivity) {
            MainActivityControllerTestFixture.field(name).setBoolean(owner, value)
        } else {
            owner.javaClass.getDeclaredField(name).apply {
                isAccessible = true
                setBoolean(owner, value)
            }
        }
    }

    private fun invokeEntry(
        activity: MainActivity,
        methodName: String,
        returnType: Class<*> = Void.TYPE,
        parameterTypes: Array<Class<*>> = emptyArray(),
        arguments: Array<Any> = emptyArray(),
    ): Throwable? {
        if (methodName == "onPause") {
            preparePurePostLoginLifecycleOwner(activity)
            val mounting = MainActivityControllerTestFixture.controller(activity, "phoneMountingSession")
            mounting.javaClass.getDeclaredField("clock").apply {
                isAccessible = true
                set(mounting, { 1_000L })
            }
        }
        return runCatching {
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, methodName, MethodType.methodType(returnType, parameterTypes.toList()))
                .bindTo(activity)
                .invokeWithArguments(arguments.toList())
        }.exceptionOrNull()
    }

    private fun assertVoiceNavigationIdentity() {
        val assembly = MainActivityRefactorSource.composition(source, "voiceInteractionControllers.commands")
        assertTrue(assembly.contains("routeRun = { navigationRunState }"))
        assertTrue(assembly.contains("route = VoiceRoutePeerAdapter("))
        assertTrue(assembly.contains("acquisition = { routeAcquisitionController }, archive = { routeArchiveController }"))
        assertTrue(assembly.contains("guidance = { routeGuidanceController }"))
        val adapter = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertTrue(adapter.contains("override fun onDestinationSelected(result: DestinationSearchResult) = acquisition().onDestinationSelected(result)"))
        assertTrue(adapter.contains("acquisition().requestRoute(destination, reason, destinationCoordinateEvidence = destinationCoordinateEvidence)"))
    }

    private fun <T> unusedNativePort(type: Class<T>): T = type.cast(Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(type),
    ) { _, method, _ -> error("Unexpected native device query in inactive lifecycle fixture: ${type.simpleName}.${method.name}") })

    private fun preparePurePostLoginLifecycleOwner(activity: MainActivity) {
        // The shared fixture reconstructs the real core with this Main's actor reader and
        // original route/speech publication callbacks; hardware and saved-result queries throw.
        MainActivityControllerTestFixture.field("postLoginDeviceCheckSnapshot").get(activity)
        val core = MainActivity::class.java.getDeclaredField("postLoginDeviceCheckController\$delegate").run {
            isAccessible = true
            (get(activity) as Lazy<*>).value as PostLoginDeviceCheckController
        }
        val dispatcher = unusedNativePort(PostLoginProbeDispatcher::class.java)
        val probes = AndroidPostLoginDeviceProbes(
            location = unusedNativePort(PostLoginLocationSource::class.java),
            camera = unusedNativePort(PostLoginCameraSource::class.java),
            wakeFactory = unusedNativePort(PostLoginWakeProbeFactory::class.java),
            hapticFactory = { error("Unexpected native haptic probe") },
            detector = { error("Unexpected native detector query") },
            detectorExecutor = { error("Unexpected native detector execution") },
            dispatcher = dispatcher, cameraReleaseDispatcher = dispatcher,
            elapsedRealtimeMs = android.os.SystemClock::elapsedRealtime,
            environment = { error("Unexpected native device environment query") },
            currentBinding = { core.snapshot.bindingOrNull },
            isCurrent = core::isRunningBindingCurrent,
            uiAlive = { !(field(activity, "privacyStartupInspectionDestroyed") as Boolean) },
            sharedReleaseBlocked = { error("Unexpected native shared release query") },
            markSharedReleaseBlocked = { error("Unexpected native release publication") },
            modelDirectory = { error("Unexpected native model query") },
            modelPreparationFailed = { error("Unexpected native model state query") },
            stopVoiceService = { error("Unexpected native voice service stop") },
            support = unusedNativePort(PostLoginProbeRuntimeSupport::class.java),
            observer = unusedNativePort(PostLoginProbeObserver::class.java),
        )
        PostLoginDeviceCheckController::class.java.getDeclaredField("probes").apply {
            isAccessible = true
            set(core, probes)
        }
        MainActivity::class.java.getDeclaredField("postLoginDeviceProbesHolder").apply {
            isAccessible = true
            set(activity, lazyOf(probes))
        }
    }

    private fun ownerMethod(path: String, name: String, occurrence: Int = 0): String =
        MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/" + path).readText(), name, occurrence)

    private fun archiveMethod(name: String): String {
        MainActivityRefactorSource.composition(source, "routeArchiveController")
        return ownerMethod("navigation/session/RouteArchiveController.kt", name)
    }

    private fun destinationPanelSource(): String {
        val assembly = MainActivityRefactorSource.member(source, "buildContentView")
        assertTrue(assembly.contains("val destinationPanel = DestinationGuidancePanel("))
        assertTrue(assembly.contains("actions = DestinationGuidancePanel.Actions("))
        assertTrue(assembly.contains("onReroute = { requestRerouteFromVoice() }"))
        assertTrue(assembly.contains("onRecheck = { recheckLocationFromVoice() }"))
        assertTrue(assembly.contains("onEndDeviation = { endNavigationAfterDeviation() }"))
        assertFalse(assembly.contains("onConfirmArrival"))
        assertFalse(assembly.contains("onRejectArrival"))
        return File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
    }

    private fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)
}
