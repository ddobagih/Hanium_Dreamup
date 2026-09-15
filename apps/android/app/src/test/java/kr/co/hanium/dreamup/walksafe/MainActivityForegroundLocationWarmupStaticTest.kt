package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityForegroundLocationWarmupStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun authenticatedForegroundOwnerDoesNotWaitForEducationOrAnActiveWalk() {
        val owner = functionSection("private fun currentLocationCollectionOwnerOrNull(")
        val authenticated = owner.substringBefore("if (!currentPositionFieldCollectionAllowsWork())")

        assertTrue(authenticated.contains("!isActivityForeground || !hasLocationPermission()"))
        assertTrue(authenticated.contains("!isLocationServiceEnabledForDeviceCheck()"))
        assertTrue(authenticated.contains("firstRunOnboardingSnapshot.verifiedActorBinding?.value"))
        assertTrue(authenticated.contains("process.storageBlocked || process.deletionRecoveryOnly"))
        assertTrue(authenticated.contains("accountDeletionStateMachine.processingBlocked()"))
        assertTrue(authenticated.contains("session.actorId == actorId"))
        assertTrue(authenticated.contains("session.sessionScope == GatewaySessionScope.GENERAL"))
        assertTrue(authenticated.contains("GatewaySessionVerificationState.VERIFIED"))
        assertTrue(authenticated.contains("session.isUsableFor(actorId)"))
        assertTrue(authenticated.contains("LocationCollectionOwner(actorId, session, process.generation)"))
        assertFalse(authenticated.contains("firstRunOnboardingComplete()"))
        assertFalse(authenticated.contains("currentReporterUserId()"))
        assertFalse(authenticated.contains("isWalkSessionRuntimeActive()"))
        assertFalse(authenticated.contains("isStartupCapabilityConfirmed()"))
        assertFalse(authenticated.contains("cameraAnalysisFeaturesEnabled()"))
        assertFalse(authenticated.contains("pendingCameraFallbackStart"))
    }

    @Test
    fun walkActivationKeepsTheSameSubscriptionAndActorProfile() {
        val start = functionSection("private fun startLocationUpdatesIfAllowed(")
        val retained = start.substringAfter("if (!forceRestart && locationCallback != null")
            .substringBefore("if (locationCallback != null)")
        val profile = functionSection("private fun ensurePositioningProfileForCurrentActor(")

        assertTrue(retained.contains("locationCollectionOwner == owner"))
        assertTrue(retained.contains("startPositioningObservationSources()"))
        assertTrue(retained.contains("return"))
        assertFalse(retained.contains("stopLocationUpdates("))
        assertFalse(retained.contains("positioningCoordinator.reset()"))
        assertFalse(start.substringBefore("val generation").contains("currentRuntimeEpochOrNull()"))
        assertTrue(profile.contains("locationCollectionOwner?.actorId ?: currentReporterUserId()"))
        assertTrue(profile.contains("if (positioningProfileLoaded && actorId == positioningProfileActorId) return"))
    }

    @Test
    fun newWalkAndCameraOutputCancellationRetainOnlyFreshForegroundLocation() {
        val cancel = functionSection("private fun cancelWalkSessionOutputs(")
        val reset = functionSection("private fun resetWalkTransientStateForNewWalk(")
        val preserve = reset.substringAfter("if (currentLocationCollectionAllowsWork())")
            .substringBefore("} else {")

        assertTrue(cancel.contains("pendingCameraFallbackStart = null"))
        assertTrue(cancel.contains("stopActivePositionFieldSession()"))
        assertTrue(cancel.contains("if (!currentLocationCollectionAllowsWork()) stopLocationUpdates()"))
        assertTrue(cancel.contains("retainLocationCompassOrStop()"))
        assertFalse(cancel.contains("earthOrientationTracker.stop()"))
        assertTrue(cancel.indexOf("startLocationUpdatesIfAllowed()") >
            cancel.indexOf("retainLocationCompassOrStop()"))
        assertTrue(preserve.contains("freshTrustedLocationOrNull()"))
        assertTrue(preserve.contains("clearLocationDerivedState()"))
        assertFalse(preserve.contains("clearTrustedLocation()"))
        assertFalse(preserve.contains("positioningCoordinator.reset()"))
    }

    @Test
    fun callbacksAndCachedFixesRevalidateTheActorSessionAndGeneration() {
        val start = functionSection("private fun startLocationUpdatesIfAllowed(")
        val current = functionSection("private fun isLocationCallbackCurrent(")
        val location = functionSection("private fun handleLocationUpdate(")
        val fresh = functionSection("private fun freshTrustedLocationOrNull(")

        assertTrue(start.contains("isLocationCallbackCurrent(owner, generation, callback)"))
        assertTrue(current.contains("locationCallback === callback"))
        assertTrue(current.contains("generation == locationCallbackGeneration"))
        assertTrue(current.contains("locationCollectionOwner == owner"))
        assertTrue(current.contains("currentLocationCollectionOwnerOrNull() == owner"))
        val beforeEstimate = location.substringBefore("positioningCoordinator.observeGnss(")
        assertTrue(beforeEstimate.contains("currentLocationCollectionOwnerOrNull() != owner"))
        assertTrue(beforeEstimate.contains("locationGeneration != locationCallbackGeneration"))
        assertTrue(beforeEstimate.contains(") return"))
        assertTrue(fresh.substringBefore("val current = latestTrustedLocation")
            .contains("currentLocationCollectionOwnerOrNull() != locationCollectionOwner"))
        assertTrue(fresh.contains("LocationTrustPolicy.freshOrNull(current, nowElapsedRealtimeMs)"))
        assertTrue(start.contains("setMaxUpdateAgeMillis(0L)"))
    }

    @Test
    fun acceptedWarmupFixReturnsBeforeGuidanceReportsOrTrace() {
        val location = functionSection("private fun handleLocationUpdate(")
        val stored = location.substringAfter("latestRawRouteLocation = rawRouteLocation")
        val warmup = stored.substringBefore("continuePendingExplicitRouteStartIfReady()")
        val fieldOnly = warmup.substringAfter("if (currentPositionFieldCollectionAllowsWork())")
            .substringBefore("\n            return")

        assertTrue(warmup.contains("if (!currentNavigationCollectionAllowsWork())"))
        assertTrue(warmup.contains("\n            return"))
        assertTrue(fieldOnly.contains("appendPositionFieldGnssTrace("))
        assertTrue(fieldOnly.contains("applyPositionConfidenceDecision(positioningSnapshot)"))
        assertFalse(warmup.substringBefore("if (currentPositionFieldCollectionAllowsWork())")
            .contains("applyPositionConfidenceDecision("))
        assertFalse(warmup.contains("updateRouteGuidance("))
        assertFalse(warmup.contains("reportLocationStartScheduled.set("))
        assertFalse(warmup.contains("attemptStepCalibration("))
    }

    @Test
    fun unavailableRejectedAndExpiredWarmupFixesCannotAnnounceOrVibrate() {
        val start = functionSection("private fun startLocationUpdatesIfAllowed(")
        val unavailable = start.substringAfter("if (!availability.isLocationAvailable)")
            .substringBefore("val positionDecisionRequired")
        val rejected = functionSection("private fun handleLocationUpdate(")
            .substringAfter("if (hardRejected || filtered == null)")
            .substringBefore("val positionDecisionRequired")
        val fresh = functionSection("private fun freshTrustedLocationOrNull(")

        listOf(unavailable, rejected).forEach { guard ->
            assertTrue(guard.contains("clearTrustedLocation()"))
            assertTrue(guard.contains("!currentNavigationCollectionAllowsWork()"))
            assertTrue(guard.contains("!currentPositionFieldCollectionAllowsWork()"))
            assertTrue(guard.contains(") return"))
        }
        assertTrue(fresh.indexOf("currentNavigationCollectionAllowsWork()") <
            fresh.indexOf("handlePositioningUnavailable(nowElapsedRealtimeMs)"))
        assertTrue(start.contains("if (!navigationWasAllowed) return"))
    }

    @Test
    fun stoppedCollectionDiscardsCallbacksSensorsAndFilteredCoordinates() {
        val stop = functionSection("private fun stopLocationUpdates(")
        val sources = functionSection("private fun startPositioningObservationSources(")

        assertTrue(stop.contains("locationCallbackGeneration += 1"))
        assertTrue(stop.contains("locationCollectionOwner = null"))
        assertTrue(stop.contains("removeLocationUpdates(registeredCallback)"))
        assertTrue(stop.contains("gnssQualityObserver?.close()"))
        assertTrue(stop.contains("pedestrianMotionTracker?.close()"))
        assertTrue(stop.contains("positioningCoordinator.reset()"))
        assertTrue(stop.contains("clearTrustedLocation()"))
        assertTrue(sources.contains("isLocationCallbackCurrent(owner, generation, callback)"))
        assertTrue(sources.contains("!currentNavigationCollectionAllowsWork()"))
        assertTrue(sources.contains("pedestrianMotionTracker?.stop()"))
    }

    @Test
    fun compassStartsWithAuthenticatedLocationBeforeNavigationAndKeepsItsLeaseAtStart() {
        val sources = functionSection("private fun startPositioningObservationSources(")
        val ensure = functionSection("private fun ensureEarthOrientationForLocation(")
        val retain = functionSection("private fun retainLocationCompassOrStop(")
        val camera = functionSection("private fun startWalkSessionRuntimeWithoutCamera(")
        assertTrue(sources.indexOf("ensureEarthOrientationForLocation()") <
            sources.indexOf("!currentNavigationCollectionAllowsWork()"))
        assertTrue(ensure.contains("earthOrientationTracker.start()"))
        assertFalse(ensure.contains("isRouteActive"))
        assertTrue(retain.contains("currentLocationCollectionOwnerOrNull() == owner"))
        assertTrue(retain.contains("locationCallback != null"))
        assertTrue(camera.contains("retainLocationCompassOrStop()"))
        assertFalse(camera.contains("earthOrientationTracker.stop()"))
    }

    @Test
    fun rawFreshLocationSetsDeclinationBeforePdrRejectionWithoutGrantingRouteTrust() {
        val location = functionSection("private fun handleLocationUpdate(")
        val reference = location.indexOf("RouteCompassLocationReference.accepts(")
        val filtered = location.indexOf("positioningCoordinator.observeGnss(")
        assertTrue(reference > location.indexOf("currentLocationCollectionOwnerOrNull() != owner"))
        assertTrue(reference < filtered)
        assertTrue(location.substring(reference, filtered).contains("updateRouteOrientationReference(location)"))
        assertFalse(location.substring(reference, filtered).contains("currentNavigationCollectionAllowsWork()"))
        assertFalse(location.substring(reference, filtered).contains("developmentGuidanceStartBypassEnabled"))
        assertTrue(location.contains("if (hardRejected || filtered == null)"))
    }

    @Test
    fun preRouteCompassRefreshIsSilentAndBoundToTheForegroundLocationGeneration() {
        val refresh = functionSection("private fun scheduleRouteCompassPresentationUpdate(")
        val stop = functionSection("private fun stopLocationUpdates(")
        assertTrue(refresh.contains("!isActivityForeground"))
        assertTrue(refresh.contains("locationCallbackGeneration != generation"))
        assertTrue(refresh.contains("currentLocationCollectionOwnerOrNull() != owner"))
        assertTrue(refresh.contains("pendingRouteCompassRefresh !== refresh"))
        assertTrue(refresh.contains("currentRouteFacingObservation(SystemClock.elapsedRealtime())"))
        assertFalse(refresh.contains("isRouteActive"))
        assertFalse(refresh.contains("speakInteraction("))
        assertFalse(refresh.contains("speakNavigation("))
        assertFalse(refresh.contains("startVoiceCommandRecognition("))
        assertTrue(stop.contains("stopRouteCompassPresentationUpdates()"))
        assertTrue(stop.indexOf("refreshRouteCompassPresentation()") >
            stop.indexOf("orientationLocationOwnerActive = false"))
        assertTrue(stop.contains("clearGeomagneticReference()"))
        val compassView = source.substringAfter("nativeHomeCompassText = TextView(this).apply")
            .substringBefore("overlay.addView(nativeHomeCompassText")
        assertTrue(compassView.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE"))
        val presentation = functionSection("private fun refreshRouteCompassPresentation(")
        assertFalse(presentation.contains("nativeGuidanceStatusText"))
        val liveGuidance = functionSection("private fun nativeGuidanceStatusMessage(")
        assertFalse(liveGuidance.contains("routeCompassStatusMessage"))
        assertTrue(source.contains("show(nativeHomeCompassText, (homeVisible || guidanceVisible) && !deviceScreen)"))
    }

    private fun functionSection(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
