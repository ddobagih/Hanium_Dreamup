package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
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
        val guidance = functionBlock("private fun updateRouteGuidance(")

        assertFalse(guidance.contains("requestRoute("))
        assertFalse(
            Regex("""if\s*\(update\.arrived\)\s*\{[^}]*isRouteActive\s*=\s*false""")
                .containsMatchIn(guidance),
        )
    }

    @Test
    fun trustedGpsRecoveryDoesNotAutomaticallyRequestANewRoute() {
        val locationUpdate = functionBlock("private fun handleLocationUpdate(")

        assertFalse(locationUpdate.contains("requestRoute("))
    }

    @Test
    fun destinationWithoutTrustedGpsStaysSelectedButInactiveUntilExplicitRetry() {
        val selection = functionBlock("private fun onDestinationSelected(")
        val request = functionBlock("private fun requestRoute(")
        val routeButton = functionBlock("private fun onRouteButtonClicked(")

        assertTrue(selection.contains("currentDestination = result.point"))
        assertFalse(selection.contains("isRouteActive = true"))
        assertTrue(routeButton.contains("currentDestination ?: parseDestinationInput()"))

        val missingGps = request.substringAfter("val origin = freshTrustedLocationOrNull()")
            .substringBefore("if (navigationRequests.hasActiveRoute()")
        assertTrue(missingGps.contains("currentDestination = destination"))
        assertTrue(missingGps.contains("isRouteActive = false"))
        assertFalse(missingGps.contains("walkingRouteClient.fetchRouteCall("))
        assertTrue(
            request.indexOf("isRouteActive = true") >
                request.indexOf("routeRequestInFlight.compareAndSet(false, true)"),
        )
    }

    @Test
    fun arrivalDecisionRendersTokenBoundAccessibleConfirmAndRejectActions() {
        val controls = source.substringAfter("routeDeviationNewRouteButton = Button(this).apply")
            .substringBefore("destinationResetButton = Button(this).apply")
        val update = functionBlock("private fun updateRouteDeviationActions(")
        val confirm = functionBlock("private fun confirmArrivalFromButton(")
        val reject = functionBlock("private fun rejectArrivalFromButton(")

        assertTrue(controls.contains("text = \"도착 확인\""))
        assertTrue(controls.contains("text = \"도착 아님\""))
        assertTrue(controls.contains("setOnClickListener"))
        assertTrue(update.contains("RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION"))
        assertTrue(update.contains("routeNavigator.pendingDecisionToken()"))
        assertTrue(confirm.contains("routeNavigator.confirmArrival(expectedToken)"))
        assertTrue(reject.contains("routeNavigator.rejectArrival(expectedToken)"))
    }

    @Test
    fun routeGuidanceUsesStrideOnlyAsAuxiliaryProgressEvidence() {
        val guidance = functionBlock("private fun updateRouteGuidance(")

        assertTrue(guidance.contains("stepProgressM = routeStepProgressMOrNull()"))
        assertFalse(guidance.contains("latitude = step"))
        assertFalse(guidance.contains("longitude = step"))
    }

    @Test
    fun voicePagingIsThreeAtATimeAndOnlySelectsFromTheCurrentPage() {
        val hearMore = functionBlock("private fun hearMoreVoiceDestinationCandidates(")
        val selection = functionBlock("private fun selectVoiceDestinationCandidate(")

        assertTrue(source.contains("const val DESTINATION_SEARCH_PAGE_SIZE = 3"))
        assertTrue(hearMore.contains("DestinationSearchVoiceCommand.HearMore"))
        assertTrue(hearMore.contains("performDestinationSearch(reset = false)"))
        assertTrue(selection.contains("DestinationSearchVoiceCommand.SelectCandidate(oneBasedIndex)"))
        assertTrue(selection.contains("if (!onDestinationSelected(selected)) {"))
        assertTrue(
            selection.indexOf("if (!onDestinationSelected(selected)) {") <
                selection.indexOf("TMAP 경로를 확인합니다"),
        )
    }

    @Test
    fun destinationSearchRejectsOverlongQueriesBeforeCreatingANetworkCall() {
        val search = functionBlock("private fun performDestinationSearch(")

        assertTrue(search.contains("canonicalDestinationSearchQueryOrNull(rawQuery)"))
        assertTrue(search.contains("navigation=destination_query_invalid max_code_points=80"))
        assertTrue(
            search.indexOf("canonicalDestinationSearchQueryOrNull(rawQuery)") <
                search.indexOf("gatewaySessionOrNull(\"destination_search\")"),
        )
        assertTrue(
            search.indexOf("canonicalDestinationSearchQueryOrNull(rawQuery)") <
                search.indexOf("walkingRouteClient.searchDestinationsCall("),
        )
    }

    @Test
    fun voiceDecisionCommandsAreBoundToTheRouteRevisionAtRecognitionStart() {
        val recognitionStart = functionBlock("private fun startVoiceCommandRecognition(")
        val commandHandler = functionBlock("private fun handleVoiceCommandPhrases(")

        assertTrue(recognitionStart.contains("expectedNavigationDecisionToken"))
        assertTrue(commandHandler.contains("routeNavigator.pendingDecisionToken()"))
        assertTrue(commandHandler.contains("voice=navigation_decision_stale"))
        assertTrue(commandHandler.contains("AndroidVoiceAction.StopNavigation"))
    }

    @Test
    fun routeDeviationCancelsNavigationBeforeItsInteractionPrompt() {
        val guidance = functionBlock("private fun updateRouteGuidance(")
        val deviation = functionBlock("private fun applyRouteDeviationSafetyUpdate(")

        val cancellation = deviation.indexOf("feedbackActuator?.cancelNavigationSpeech()")
        val prompt = deviation.indexOf("update.instruction?.let(::speakInteraction)")
        assertTrue(cancellation >= 0)
        assertTrue(prompt > cancellation)
        assertTrue(deviation.contains("update.cancelStaleNavigationSpeech"))
        assertTrue(deviation.contains("update.userDecisionRequired"))
        assertTrue(guidance.contains("applyRouteDeviationSafetyUpdate(update)"))
    }

    @Test
    fun confirmedDeviationRendersExactlyThreeAccessibleActions() {
        val controls = source.substringAfter("routeDeviationNewRouteButton = Button(this).apply")
            .substringBefore("destinationResetButton = Button(this).apply")

        assertEquals(1, Regex("text = \"새 경로 요청\"").findAll(controls).count())
        assertEquals(1, Regex("text = \"위치 다시 확인\"").findAll(controls).count())
        assertEquals(1, Regex("text = \"길안내 종료\"").findAll(controls).count())
        assertEquals(3, Regex("addView\\(routeDeviation").findAll(controls).count())
        assertTrue(controls.contains("contentDescription = text"))
        assertTrue(source.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
    }

    @Test
    fun teammateTmapUiHandoffIsAdaptedToTheCurrentNavigationLayout() {
        val destination = source.substringAfter("destinationQueryInput = EditText(this).apply")
            .substringBefore("destinationSearchButton = Button(this).apply")
        val controls = source.substringAfter("offRouteNoticeText = TextView(this).apply")
            .substringBefore("destinationResetButton = Button(this).apply")
        val update = functionBlock("private fun updateRouteDeviationActions(")

        assertTrue(destination.contains("contentDescription = \"목적지 입력\""))
        assertTrue(controls.contains("addView(offRouteNoticeText)"))
        assertTrue(update.contains("confirmed -> \"경로를 벗어났습니다\""))
        assertTrue(update.contains("suspected -> \"경로를 벗어난 것으로 보입니다\""))
        assertTrue(update.contains("else -> \"\""))
    }

    @Test
    fun onlyNewRouteChoiceCallsTheTmapRouteRequest() {
        val newRoute = functionBlock("private fun requestRerouteFromVoice(")
        val recheck = functionBlock("private fun recheckLocationFromVoice(")
        val end = functionBlock("private fun endNavigationAfterDeviation(")
        val request = functionBlock("private fun requestRoute(")
        val destinationSelection = functionBlock("private fun onDestinationSelected(")

        assertTrue(newRoute.contains("RouteDeviationChoice.NEW_ROUTE"))
        assertTrue(newRoute.contains("requestRoute(destination, reason = \"off_route\")"))
        assertTrue(recheck.contains("RouteDeviationChoice.RECHECK_LOCATION"))
        assertFalse(recheck.contains("requestRoute("))
        assertTrue(end.contains("RouteDeviationChoice.END_NAVIGATION"))
        assertFalse(end.contains("requestRoute("))
        assertTrue(request.contains("reason != \"off_route\" && blockRouteMutationWhileDeviationChoicePending()"))
        assertTrue(destinationSelection.contains("blockRouteMutationWhileDeviationChoicePending()"))
        assertTrue(destinationSelection.contains("routeRequestGenerationBefore"))
        assertTrue(destinationSelection.contains("routeRequestGeneration != routeRequestGenerationBefore"))
        assertTrue(destinationSelection.contains("routeRequestInFlight.get()"))
    }

    @Test
    fun routeWorkerStoresTheFirstSnapshotBeforeInstallAndWiresTheFailureGuard() {
        val request = functionBlock("private fun requestRoute(")
        val retainedFailure = functionBlock("private fun retainRouteAfterRerouteFailure(")
        val purgeFence = functionBlock("private fun routeSnapshotPurgeFenceAllowsRoute(")
        val snapshotSave = request.indexOf("routeSnapshotStore.saveFirstRoute(")
        val uiCommit = request.lastIndexOf("runOnUiThread {", snapshotSave)
        val leaseCheck = request.lastIndexOf("isRouteRequestLeaseCurrent(", snapshotSave)
        val firstPurgeFence = request.indexOf("routeSnapshotPurgeFenceAllowsRoute()")
        val secondPurgeFence = request.indexOf(
            "routeSnapshotPurgeFenceAllowsRoute()",
            firstPurgeFence + 1,
        )

        assertTrue(snapshotSave < request.indexOf("routeNavigator.setRoute("))
        assertTrue(uiCommit >= 0)
        assertTrue(leaseCheck > uiCommit)
        assertTrue(request.indexOf("if (!isRouteActive) return@runOnUiThread", uiCommit) < snapshotSave)
        assertEquals(2, Regex("routeSnapshotPurgeFenceAllowsRoute\\(\\)").findAll(request).count())
        assertTrue(firstPurgeFence < request.indexOf("walkingRouteClient.fetchRouteCall("))
        assertTrue(secondPurgeFence > leaseCheck)
        assertTrue(secondPurgeFence < snapshotSave)
        assertTrue(request.indexOf("catch (_: CancellationException)") < request.indexOf("catch (error: Exception)"))
        assertTrue(request.contains("tmapFailureGuard.recordSuccess()"))
        assertTrue(request.contains("tmapFailureGuard.recordFailure()"))
        assertTrue(request.contains("countableTmapFailure = routeProviderCallInProgress"))
        assertTrue(request.contains("enterWalkSessionSafetyStopAndCancelOutputs(\"tmap_consecutive_failures\")"))
        assertTrue(request.contains("NavigationBackendErrorKind.AUTHENTICATION"))
        assertTrue(request.contains("navigationFailure.kind.userMessage"))
        assertTrue(request.contains("scheduleEncryptedRouteSnapshotExpiry("))
        assertTrue(retainedFailure.contains("applyRouteDeviationSafetyUpdate"))

        assertTrue(purgeFence.contains("if (!routeSnapshotPurgeFailed) return true"))
        assertTrue(purgeFence.contains("enterWalkSessionSafetyStopAndCancelOutputs(\"route_snapshot_purge_failed\")"))
        assertTrue(purgeFence.contains("updateStatus(\"길안내 저장소 오류 · 안전 중지\", detail)"))
        assertTrue(purgeFence.contains("speakInteraction(detail)"))
        assertTrue(purgeFence.indexOf("speakInteraction(detail)") < purgeFence.indexOf("return false"))
    }

    @Test
    fun terminalRouteSafetyStopsSpeakOneBoundedAccessibleDetail() {
        val request = functionBlock("private fun requestRoute(")
        val tmapStopStart = request.indexOf(
            "enterWalkSessionSafetyStopAndCancelOutputs(\"tmap_consecutive_failures\")",
        )
        val tmapStopEnd = request.indexOf("return@runOnUiThread", tmapStopStart)
        val tmapStop = request.substring(tmapStopStart, tmapStopEnd)
        val snapshotStop = request.substringAfter("if (storedRouteSnapshot == null)")
            .substringBefore("return@runOnUiThread")
        val expiry = functionBlock("private fun scheduleEncryptedRouteSnapshotExpiry(")

        assertTrue(tmapStop.contains("navigationFailure.kind.userMessage"))
        assertTrue(tmapStop.contains("모든 보행 기능을 중지했습니다"))
        assertEquals(1, Regex("speakInteraction\\(safetyStopDetail\\)").findAll(tmapStop).count())
        assertFalse(tmapStop.contains("error.message"))
        assertFalse(tmapStop.contains("error.toString"))
        assertFalse(tmapStop.contains("backendCode"))
        assertEquals(1, Regex("speakInteraction\\(detail\\)").findAll(snapshotStop).count())
        assertEquals(1, Regex("speakInteraction\\(detail\\)").findAll(expiry).count())
    }

    @Test
    fun positionQualityLossDoesNotMasqueradeAsRouteDeviationAndPauseKeepsTheEncryptedSnapshot() {
        val location = functionBlock("private fun handleLocationUpdate(")
        val clearLocation = functionBlock("private fun clearTrustedLocation(")
        val confidence = functionBlock("private fun applyPositionConfidenceDecision(")
        val foregroundPause = functionBlock("private fun enterWalkSessionForegroundRecheckAndCancelOutputs(")
        val cancelOutputs = functionBlock("private fun cancelWalkSessionOutputs(")
        val reset = functionBlock("private fun resetRouteState(")
        val purge = functionBlock("private fun purgeEncryptedRouteSnapshot(")

        assertTrue(location.contains("positioningCoordinator.observeGnss("))
        assertFalse(location.contains("handleRouteLocationUntrusted()"))
        assertFalse(clearLocation.contains("handleRouteLocationUntrusted()"))
        assertTrue(confidence.contains("feedbackActuator?.cancelNavigationSpeech()"))
        assertTrue(confidence.contains("positionGuidancePaused = decision.guidancePaused"))
        assertFalse(confidence.contains("directionGuidancePauseReason = pauseReason"))
        assertTrue(confidence.contains("commitAnnouncementDelivered("))
        assertTrue(foregroundPause.contains("cancelWalkSessionOutputs(reason)"))
        assertTrue(cancelOutputs.contains("WalkSessionState.PAUSED"))
        assertTrue(cancelOutputs.contains("resetRouteState(purgeRouteSnapshot = false)"))
        assertTrue(reset.contains("purgeEncryptedRouteSnapshot()"))
        assertTrue(purge.contains("routeSnapshotStore.clear()"))
    }

    @Test
    fun poorGpsUsesCoordinatorSoftGateAndFilteredLocation() {
        val handler = functionBlock("private fun handleLocationUpdate(")

        assertTrue(handler.contains("positioningCoordinator.observeGnss("))
        assertTrue(handler.contains("GnssObservationDisposition.HARD_REJECTED"))
        assertFalse(handler.contains("LocationTrustPolicy.trustedOrNull("))
        assertTrue(handler.contains("latestTrustedLocation = filteredTrusted"))
        assertTrue(handler.contains("updateRouteGuidance(filteredTrusted, rawRouteLocation)"))
        assertTrue(handler.contains("measurementNoiseMultiplier"))
    }

    @Test
    fun pdrMagneticHeadingRequiresAValidExplicitChestMountContract() {
        val step = functionBlock("private fun handlePositioningStepEvent(")
        val chest = functionBlock("private fun chestMountedHeadingForStep(")
        val motion = functionBlock("private fun applyPositioningMotionSnapshot(")
        val heading = functionBlock("private fun updatePositioningHeadingInputs(")
        val route = functionBlock("private fun updateRouteGuidance(")

        assertTrue(
            step.indexOf("pedestrianMotionTracker?.recordStep(event.timestampMs)") <
                step.indexOf("positioningCoordinator.observeStep("),
        )
        assertTrue(step.contains("positioningCoordinator.observeStep("))
        assertTrue(step.contains("gpsCourse = courseAtStep"))
        assertTrue(step.contains("magneticTrueHeading = chestHeading"))
        assertTrue(step.contains("phoneForwardMounted = chestHeading != null"))
        assertTrue(chest.contains("positionFieldExplicitChestConfirmed"))
        assertTrue(chest.contains("PhoneMountingMethod.CHEST_FORWARD"))
        assertTrue(chest.contains("phoneMountingOutputsAllowed"))
        assertTrue(chest.contains("chestMountedHeadingAt(timestampMs, maximumAgeMs)"))
        assertTrue(step.contains("magneticTrueHeadingAtGpsCourse = chestAtCourse"))
        assertFalse(motion.contains("updateRouteGuidance("))
        assertFalse(heading.contains("location.speed > 0.5f"))
        assertTrue(heading.contains("bearingAccuracy != null"))
        assertTrue(route.contains("filteredPosition = FilteredRoutePosition("))
        assertTrue(route.contains("latestWalkingSpeedObservation?.speedMps"))
        assertTrue(route.contains("standardDeviationDeg = requireNotNull(heading.accuracyDegrees)"))
        assertFalse(route.contains("standardDeviationDeg = 35.0"))
    }

    @Test
    fun fusedAndOptionalSensorsReleaseTheirSessionBoundCallbacksTogether() {
        val start = functionBlock("private fun startLocationUpdatesIfAllowed(")
        val stop = functionBlock("private fun stopLocationUpdates(")
        val sources = functionBlock("private fun startPositioningObservationSources(")

        assertTrue(start.contains("Priority.PRIORITY_HIGH_ACCURACY"))
        assertTrue(start.contains("Granularity.GRANULARITY_FINE"))
        assertTrue(start.contains("setMaxUpdateAgeMillis(0L)"))
        assertTrue(start.contains("startPositioningObservationSources()"))
        assertTrue(start.contains("catch (_: SecurityException)"))
        assertTrue(sources.contains("Manifest.permission.ACCESS_FINE_LOCATION"))
        assertTrue(sources.contains("AndroidGnssObservationSource("))
        assertTrue(sources.contains("AndroidPedestrianMotionTracker("))
        assertTrue(sources.contains("positioningCoordinator.observeZupt("))
        assertTrue(sources.contains("ensureEarthOrientationForLocation()"))
        assertTrue(stop.contains("gnssQualityObserver?.close()"))
        assertTrue(stop.contains("pedestrianMotionTracker?.close()"))
        assertTrue(stop.contains("gnssQualityObserver = null"))
        assertTrue(stop.contains("pedestrianMotionTracker = null"))
        assertTrue(stop.contains("stopActivePositionFieldSession()"))
        assertTrue(stop.contains("positioningCoordinator.reset()"))
    }

    @Test
    fun gnssQualityIsRefreshedPerFixAndHeadingChangesOnlyAfterHardAcceptance() {
        val handler = functionBlock("private fun handleLocationUpdate(")
        val snapshot = "gnssQualityObserver?.snapshot(SystemClock.elapsedRealtimeNanos())"
        val acceptedHeading = "updatePositioningHeadingInputs(location, elapsedMs)"
        val hardReject = "if (hardRejected || filtered == null)"

        assertTrue(handler.contains(snapshot))
        assertTrue(handler.indexOf(acceptedHeading) > handler.indexOf(hardReject))
        assertTrue(handler.indexOf(acceptedHeading) > handler.indexOf("return", handler.indexOf(hardReject)))
    }

    @Test
    fun positionQualityPauseIsIndependentFromProviderPause() {
        val confidence = functionBlock("private fun applyPositionConfidenceDecision(")
        val route = functionBlock("private fun updateRouteGuidance(")

        assertTrue(confidence.contains("positionGuidancePaused = decision.guidancePaused"))
        assertFalse(confidence.contains("directionGuidancePauseReason ="))
        assertTrue(route.contains("positionGuidancePaused || directionGuidancePauseReason == \"tmap_unavailable\""))
    }

    @Test
    fun actorSwitchClearsAllCalibrationBaselines() {
        val profile = functionBlock("private fun ensurePositioningProfileForCurrentActor(")

        assertTrue(profile.contains("walkingCalibrationSegments.reset()"))
        assertTrue(functionBlock("private fun clearLocationDerivedState(").contains("walkingCalibrationSegments.reset()"))
    }

    @Test
    fun debugPositionRecorderDoesNotDependOnNavigationOutputEligibility() {
        val step = functionBlock("private fun handlePositioningStepEvent(")
        val location = functionBlock("private fun handleLocationUpdate(")
        val motionSources = functionBlock("private fun startPositioningObservationSources(")
        val locationGate = functionBlock("private fun currentLocationCollectionOwnerOrNull()")
        val stepGate = functionBlock("private fun currentStepTrackingCollectionAllowsWork()")
        val fieldGate = functionBlock("private fun currentPositionFieldCollectionAllowsWork()")

        assertTrue(step.contains("currentPositionFieldLeaseOrNull() == null"))
        assertTrue(location.contains("if (!currentNavigationCollectionAllowsWork())"))
        assertTrue(location.contains("appendPositionFieldGnssTrace("))
        assertTrue(location.contains("matchedEvidenceCurrent = false"))
        assertTrue(motionSources.contains("currentPositionFieldLeaseOrNull() != null"))
        assertTrue(locationGate.contains("currentPositionFieldCollectionAllowsWork()"))
        assertTrue(stepGate.contains("currentPositionFieldCollectionAllowsWork()"))
        assertTrue(fieldGate.contains("BuildConfig.DEBUG"))
        assertTrue(fieldGate.contains("!isActivityForeground"))
        assertTrue(fieldGate.contains("!isWalkSessionRuntimeActive()"))
        assertTrue(fieldGate.contains("!hasLocationPermission()"))
        assertTrue(fieldGate.contains("currentRuntimeEpochOrNull()"))
        assertTrue(fieldGate.contains("GatewaySessionProcessCoordinator.snapshot().generation"))
        assertTrue(fieldGate.contains("positionFieldLocalScopeId == positionFieldLeaseScopeId"))
    }

    @Test
    fun calibrationLearnsOnlyFromOriginalFixWithFreshMotionEvidence() {
        val calibration = functionBlock("private fun attemptStepCalibration(")
        val learning = functionBlock("private fun applyWalkingCalibrationSample(")
        val step = functionBlock("private fun handlePositioningStepEvent(")

        assertTrue(calibration.contains("walkingCalibrationSegments.onFix("))
        assertTrue(calibration.contains("::applyWalkingCalibrationSample"))
        assertTrue(learning.contains("positioningCoordinator.calibrateProfile("))
        assertTrue(step.contains("walkingCalibrationSegments.onStep("))
        assertTrue(step.contains("StepEventSource.STEP_DETECTOR"))
        assertTrue(step.contains("::applyWalkingCalibrationSample"))
        assertTrue(calibration.contains("RawWalkingCalibrationFix("))
        assertTrue(calibration.contains("latitude = location.latitude"))
        assertTrue(calibration.contains("longitude = location.longitude"))
        assertTrue(calibration.contains("freshMovingEvidence"))
        assertTrue(calibration.contains("!isMockLocationCompat(location)"))
        assertFalse(calibration.contains("filteredTrusted"))
    }

    @Test
    fun degradedPositionQualityInterruptsMapMatchingEvidenceWithoutProviderPauseMutation() {
        val confidence = functionBlock("private fun applyPositionConfidenceDecision(")

        assertTrue(confidence.contains("PositionQuality.LOW, PositionQuality.UNAVAILABLE"))
        assertTrue(confidence.contains("decision.previousQuality != decision.quality"))
        assertTrue(confidence.contains("routeNavigator.onPositioningEvidenceInterrupted()"))
        assertFalse(confidence.contains("directionGuidancePauseReason ="))
    }

    @Test
    fun rejectingArrivalDoesNotRestoreTmapTrustWithoutFreshGpsEvidence() {
        val rejection = functionBlock("private fun rejectArrivalFromVoice(")

        assertFalse(rejection.contains("latestTmapOnRoute = true"))
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
        val availabilityGuard = selection.indexOf("!currentNavigationCollectionAllowsWork()")
        val unavailableExplanation = selection.indexOf("explainNavigationFeatureUnavailable()")
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
    fun actualPauseAndDestroyEntriesEachCancelTheMainActivityActiveRouteAndSearchCalls() {
        listOf("onPause", "onDestroy").forEach { lifecycleMethod ->
            val activity = MainActivity()
            setBooleanField(activity, "privacyStartupInspectionComplete", true)
            val route = activity.trackRouteRequest(recordingCall(AtomicInteger()))
            val search = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))

            val entryFailure = invokeEntry(activity, lifecycleMethod)

            assertTrue("$lifecycleMethod failed before cancellation: $entryFailure", route.isCancelled())
            assertTrue("$lifecycleMethod failed before cancellation: $entryFailure", search.isCancelled())
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
        MainActivity::class.java.getDeclaredField("currentDestination").apply {
            isAccessible = true
            set(activity, destination)
        }
        (field(activity, "routeRequestInFlight") as AtomicBoolean).set(true)
        val rerouteCall = activity.trackRouteRequest(recordingCall(AtomicInteger()))
        setBooleanField(activity, "privacyStartupInspectionComplete", true)

        invokeEntry(activity, "onPause")

        assertTrue(rerouteCall.isCancelled())
        assertFalse(MainActivity::class.java.getDeclaredField("isRouteActive").run {
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
        return MainActivity::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(activity)
        }
    }

    private fun setBooleanField(activity: MainActivity, name: String, value: Boolean) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            setBoolean(activity, value)
        }
    }

    private fun invokeEntry(
        activity: MainActivity,
        methodName: String,
        returnType: Class<*> = Void.TYPE,
        parameterTypes: Array<Class<*>> = emptyArray(),
        arguments: Array<Any> = emptyArray(),
    ): Throwable? {
        return runCatching {
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, methodName, MethodType.methodType(returnType, parameterTypes.toList()))
                .bindTo(activity)
                .invokeWithArguments(arguments.toList())
        }.exceptionOrNull()
    }

    private fun functionBlock(marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "missing function marker: $marker" }
        val openingBrace = source.indexOf('{', markerIndex)
        require(openingBrace >= 0) { "missing opening brace: $marker" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
        }
        error("missing closing brace: $marker")
    }
}
