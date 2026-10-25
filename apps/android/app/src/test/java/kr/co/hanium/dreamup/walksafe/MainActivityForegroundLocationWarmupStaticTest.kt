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
        val authenticatedReturn = "return LocationCollectionOwner(actorId, session, process.generation)"
        val authenticatedEnd = owner.indexOf(authenticatedReturn)
        assertTrue(authenticatedEnd >= 0)
        val authenticated = owner.substring(0, authenticatedEnd + authenticatedReturn.length)

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
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val start = method("startLocationUpdatesIfAllowed")
        val retained = start.substringAfter("if (!forceRestart && locationCallback != null")
            .substringBefore("if (locationCallback != null")
        val profile = method("ensurePositioningProfileForCurrentActor")
        assertTrue(retained.contains("locationCollectionOwner == owner"))
        assertTrue(retained.contains("startPositioningObservationSources()"))
        assertTrue(retained.contains("return"))
        assertFalse(retained.contains("stopLocationUpdates("))
        assertFalse(retained.contains("positioningCoordinator.reset()"))
        assertFalse(start.substringBefore("val generation").contains("currentRuntimeEpochOrNull()"))
        assertTrue(profile.contains("locationCollectionOwner?.actorId ?: scope.reporterId()"))
        assertTrue(profile.contains("if (positioningProfileLoaded && actorId == positioningProfileActorId) return"))
        val wire = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(ReportStaticSourceInspector.blockAfter(wire, "scope = { object : PositioningSessionController.Scope").contains("override fun reporterId() = currentReporterUserId()"))
        assertTrue(MainActivityRefactorSource.member(source, "startLocationUpdatesIfAllowed").contains("positioningSessionController.startLocationUpdatesIfAllowed(forceRestart)"))
    }

    @Test
    fun newWalkAndCameraOutputCancellationRetainOnlyFreshForegroundLocation() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/runtime/WalkSessionCleanupController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val delegate = method("cancelWalkSessionOutputs")
        assertTrue(delegate.contains("cancelWalkSessionOutputsInternal(reason, null)"))
        val cancel = method("cancelWalkSessionOutputsInternal")
        val reset = MainActivityRefactorSource.member(source, "resetWalkTransientStateForNewWalk")
        val preserve = reset.substringAfter("if (currentLocationCollectionAllowsWork())").substringBefore("} else {")
        val wire = MainActivityRefactorSource.member(source, "createWalkSessionCleanupController")
        assertTrue(wire.contains("pendingCameraFallbackStart = null"))
        assertTrue(cancel.contains("navigation.stopPositionFieldSession()"))
        assertTrue(cancel.contains("if (!navigation.locationCollectionAllowsWork()) navigation.stopLocationUpdates()"))
        assertTrue(cancel.contains("navigation.retainLocationCompassOrStop()"))
        assertFalse(cancel.contains("earthOrientationTracker.stop()"))
        assertTrue(cancel.indexOf("navigation.startLocationUpdatesIfAllowed()") > cancel.indexOf("navigation.retainLocationCompassOrStop()"))
        assertTrue(preserve.contains("freshTrustedLocationOrNull()"))
        assertTrue(preserve.contains("clearLocationDerivedState()"))
        assertFalse(preserve.contains("clearTrustedLocation()"))
        assertFalse(preserve.contains("positioningCoordinator.reset()"))
        assertTrue(wire.contains("override fun locationCollectionAllowsWork() = currentLocationCollectionAllowsWork()"))
        assertTrue(wire.contains("override fun stopPositionFieldSession() = stopActivePositionFieldSession()"))
    }

    @Test
    fun callbacksAndCachedFixesRevalidateTheActorSessionAndGeneration() {
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val start = method("startLocationUpdatesIfAllowed")
        val current = method("isLocationCallbackCurrent")
        val location = NavigationPolicySource.observation(source)
        val fresh = method("freshTrustedLocationOrNull")
        val sdkSource = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/location/FusedLocationUpdateSource.kt")
        val receipt = ReportStaticSourceInspector.functionBlock(sdkSource, "override fun onLocationResult")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(receipt,
            "val receivedAt = receivedAtMs()", "if (!callbackIsCurrent(this)) return",
            "result.lastLocation?.let", "FusedLocationUpdateEvent.LocationReceived(location, receivedAt)"))
        val admission = ReportStaticSourceInspector.blockAfter(start, "current =")
        assertTrue(admission.contains("isLocationCallbackCurrent(owner, generation, callback)"))
        assertTrue(admission.contains("isPositioningCollectionCurrent(binding)"))
        val received = start.substringAfter("is FusedLocationUpdateEvent.LocationReceived ->")
            .substringBefore("is FusedLocationUpdateEvent.AvailabilityChanged ->")
        assertTrue(received.contains("handleLocationUpdate("))
        assertTrue(received.contains("event.location, owner, generation, binding, event.receivedAtMs"))
        assertTrue(start.contains("val callback = fusedLocationSource.callback"))
        assertTrue(start.contains("locationCallback = callback"))
        assertTrue(current.contains("locationCallback === callback"))
        assertTrue(current.contains("generation == locationCallbackGeneration"))
        assertTrue(current.contains("locationCollectionOwner == owner"))
        assertTrue(current.contains("scope.currentOwner() == owner"))
        val estimateCall = "binding.coordinator.observeGnss("
        assertTrue(location.contains(estimateCall))
        val beforeEstimate = location.substringBefore(estimateCall)
        assertTrue(beforeEstimate.contains("!isLocationObservationCurrent(owner, locationGeneration, binding)"))
        assertTrue(beforeEstimate.contains(") return"))
        assertTrue(location.contains("expectedGeneration = binding.inputGeneration"))
        assertTrue(fresh.substringBefore("val current = latestTrustedLocation").contains("scope.currentOwner() != owner"))
        assertTrue(fresh.contains("LocationTrustPolicy.freshOrNull(current, nowElapsedRealtimeMs)"))
        val wire = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val nativeSources = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt")
        assertTrue(nativeSources.contains("setMaxUpdateAgeMillis(0L)"))
        assertTrue(wire.contains("sources = { createNavigationAndroidSources() }"))
        val sdkWire = MainActivityRefactorSource.member(source, "createNavigationAndroidSources")
        assertTrue(sdkWire.contains("fusedClient = { fusedLocationClient }"))
        assertTrue(ReportStaticSourceInspector.blockAfter(wire, "scope = { object : PositioningSessionController.Scope").contains("override fun currentOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(nativeSources.contains("FusedLocationUpdateSource(fusedClient(), { android.os.SystemClock.elapsedRealtime() }, current, onEvent)"))
    }

    @Test
    fun acceptedWarmupFixReturnsBeforeGuidanceReportsOrTrace() {
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val location = NavigationPolicySource.observation(source)
        val stored = location.substringAfter("latestRawRouteLocation = rawRouteLocation")
        val warmup = stored.substringBefore("val positionGuidancePaused =")
        val fieldOnly = ReportStaticSourceInspector.blockAfter(warmup, "if (scope.fieldAllowed)")
        assertTrue(warmup.contains("if (!scope.navigationAllowed)"))
        assertTrue(warmup.contains("\n            return"))
        assertTrue(fieldOnly.contains("appendGnssTrace("))
        assertTrue(fieldOnly.contains("guidance().applyPositionConfidenceDecision(positioningSnapshot,"))
        assertFalse(warmup.substringBefore("if (scope.fieldAllowed)").contains("applyPositionConfidenceDecision("))
        assertFalse(warmup.contains("updateRouteGuidance("))
        assertFalse(warmup.contains("clearReportLocationStartSchedule("))
        assertFalse(warmup.contains("attemptStepCalibration("))
        val wire = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(ReportStaticSourceInspector.blockAfter(wire, "scope = { object : PositioningSessionController.Scope").contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
        assertTrue(ReportStaticSourceInspector.blockAfter(wire, "scope = { object : PositioningSessionController.Scope").contains("override val fieldAllowed get() = currentPositionFieldCollectionAllowsWork()"))
        assertTrue(ReportStaticSourceInspector.blockAfter(wire, "effects = { object : PositioningActivityEffects").contains("override fun appendGnssTrace"))
    }

    @Test
    fun unavailableRejectedAndExpiredWarmupFixesCannotAnnounceOrVibrate() {
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val start = method("startLocationUpdatesIfAllowed")
        val unavailable = start.substringAfter("if (!availability.isLocationAvailable)").substringBefore("val positionDecisionRequired")
        val rejected = NavigationPolicySource.observation(source).substringAfter("if (hardRejected || filtered == null)").substringBefore("val positionDecisionRequired")
        val expired = method("retireExpiredRouteLocationIfCurrent")
        listOf(unavailable, rejected).forEach { guard ->
            assertTrue(guard.contains("clearTrustedLocation()"))
            assertTrue(guard.contains("!scope.navigationAllowed"))
            assertTrue(guard.contains("!scope.fieldAllowed"))
            assertTrue(guard.contains(") return"))
        }
        assertTrue(expired.indexOf("scope.navigationAllowed") < expired.indexOf("handlePositioningUnavailable(now)"))
        assertTrue(start.contains("if (!navigationWasAllowed) return"))
        assertTrue(method("freshTrustedLocationOrNull").contains("retireExpiredRouteLocationIfCurrent(current, elapsedRealtimeMs(),"))
        assertTrue(expired.contains("latestTrustedLocation !== expectedPosition"))
        assertTrue(ReportStaticSourceInspector.blockAfter(MainActivityRefactorSource.factory(source, "createNavigationSessions"), "scope = { object : PositioningSessionController.Scope").contains("override val navigationAllowed get() = currentNavigationCollectionAllowsWork()"))
    }

    @Test
    fun stoppedCollectionDiscardsCallbacksSensorsAndFilteredCoordinates() {
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val stop = method("stopLocationUpdatesInternal")
        val sources = method("startPositioningObservationSources")
        assertTrue(stop.contains("locationCallbackGeneration += 1"))
        assertTrue(stop.contains("locationCollectionOwner = null"))
        assertTrue(stop.contains("remove = removeUpdates"))
        assertTrue(method("closeGnssQualityObserver").contains("observer.close()"))
        assertTrue(stop.contains("pedestrianMotionTracker?.close()"))
        assertTrue(stop.contains("positioningCoordinator.reset()"))
        assertTrue(stop.contains("clearTrustedLocation()"))
        assertTrue(sources.contains("isLocationCallbackCurrent(owner, generation, callback)"))
        assertTrue(sources.contains("!scope.motionObservationAllowed"))
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions").contains(
            "override val motionObservationAllowed get() = currentStepTrackingCollectionAllowsWork()"))
        assertTrue(sources.contains("pedestrianMotionTracker?.stop()"))
        val wire = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val nativeSources = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt")
        val removal = MainActivityRefactorSource.member(nativeSources, "captureRemoval")
        assertTrue(wire.contains("sources = { createNavigationAndroidSources() }"))
        assertTrue(MainActivityRefactorSource.member(source, "createNavigationAndroidSources").contains("fusedClient = { fusedLocationClient }"))
        assertTrue(removal.contains("val client = fusedClient()"))
        assertTrue(removal.contains("return { client.removeLocationUpdates(it) }"))
        assertTrue(stop.contains("val removeUpdates = sources.captureRemoval()"))
        assertTrue(stop.contains("gps().clearContinuousGpsHistory()"))
    }

    @Test
    fun compassStartsWithAuthenticatedLocationBeforeNavigationAndKeepsItsLeaseAtStart() {
        val activityPorts = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteCompassSession.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val positioning = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        val sources = MainActivityRefactorSource.member(positioning, "startPositioningObservationSources")
        val ensure = method("ensureEarthOrientationForLocation")
        val retain = method("retainLocationCompassOrStop")
        val camera = MainActivityRefactorSource.member(ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/runtime/WalkRuntimeCameraAdmissionController.kt"), "startWalkSessionRuntimeWithoutCamera")
        val wire = MainActivityRefactorSource.composition(source, "routeCompassSession")
        assertTrue(sources.indexOf("compass().ensureEarthOrientationForLocation()") < sources.indexOf("!scope.motionObservationAllowed"))
        assertTrue(ensure.contains("tracker.start()"))
        assertFalse(ensure.contains("isRouteActive"))
        assertTrue(wire.contains("activity.currentCollectionOwner() == owner"))
        assertTrue(wire.contains("orientationActive && positioning.value.callback != null && owner != null"))
        assertTrue(camera.contains("effects.retainLocationCompassOrStop()"))
        assertFalse(camera.contains("earthOrientationTracker.stop()"))
        assertTrue(retain.contains("source.mayRetainCompass(orientationLocationOwnerActive)"))
        assertTrue(ReportStaticSourceInspector.blockAfter(activityPorts, "authority = { object : CompassActivityAuthority").contains("override fun currentCollectionOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(activityPorts.contains("tracker = { createNavigationAndroidSources() }"))
        assertTrue(MainActivityRefactorSource.member(source, "createNavigationAndroidSources").contains("orientation = { earthOrientationTracker }"))
        assertTrue(MainActivityRefactorSource.member(ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt"), "start").contains("orientation().start()"))
        assertTrue(MainActivityRefactorSource.member(source, "retainLocationCompassOrStop").contains("routeCompassSession.retainLocationCompassOrStop()"))
    }

    @Test
    fun rawFreshLocationSetsDeclinationAfterHistoricalRefusalWithoutGrantingRouteTrust() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val location = NavigationPolicySource.observation(source)
        val reference = location.indexOf("RouteCompassLocationReference.accepts(")
        val filtered = location.indexOf("binding.coordinator.observeGnss(")
        val historicalRefusal = location.indexOf("gps().shouldIgnoreSupersededGnssSample(")
        val publication = location.indexOf("gps().recordPositioningGpsObservation(")
        val ownerGuard = location.indexOf("if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return")
        assertTrue(ownerGuard >= 0)
        assertTrue(reference >= 0)
        assertTrue(filtered >= 0)
        assertTrue(reference > ownerGuard)
        assertTrue(historicalRefusal > filtered)
        assertTrue(reference > historicalRefusal)
        assertTrue(publication > reference)
        val geographicUpdate = location.substring(reference, publication)
        assertTrue(geographicUpdate.contains("updateOrientationReference()"))
        assertFalse(geographicUpdate.contains("scope.navigationAllowed"))
        assertFalse(geographicUpdate.contains("scope.developmentBypass"))
        assertTrue(location.indexOf("sampleFingerprint.canSkipAt(") in ownerGuard until filtered)
        assertTrue(location.contains("if (hardRejected || filtered == null)"))
        assertTrue(location.indexOf("gps().shouldSkipAcceptedGnssSample(") in ownerGuard until reference)
        assertTrue(MainActivityRefactorSource.member(source, "handleLocationUpdate").contains("positioningSessionController.handleLocationUpdate("))
    }

    @Test
    fun preRouteCompassRefreshIsSilentAndBoundToTheForegroundLocationGeneration() {
        val activityPorts = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteCompassSession.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val refresh = method("scheduleRouteCompassPresentationUpdate")
        val scheduled = method("scheduleAdmittedRouteCompassUpdate")
        val wire = MainActivityRefactorSource.composition(source, "routeCompassSession")
        val positioning = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        val stop = MainActivityRefactorSource.member(positioning, "stopLocationUpdatesInternal")
        assertTrue(wire.contains("!activity.foreground"))
        assertTrue(ReportStaticSourceInspector.blockAfter(activityPorts, "authority = { object : CompassActivityAuthority").contains("override val foreground get() = isActivityForeground"))
        assertTrue(ReportStaticSourceInspector.blockAfter(activityPorts, "authority = { object : CompassActivityAuthority").contains("override fun currentCollectionOwner() = currentLocationCollectionOwnerOrNull()"))
        assertTrue(wire.contains("positioning.value.callbackGeneration == generation"))
        assertTrue(wire.contains("activity.currentCollectionOwner() != owner"))
        assertTrue(scheduled.contains("pendingRouteCompassRefresh !== refresh"))
        assertTrue(refresh.contains("val nowMs = elapsedRealtimeMs()"))
        assertTrue(refresh.contains("currentRouteFacingObservation(nowMs)"))
        assertTrue(wire.contains("!positioning.value.isPositioningCollectionCurrent(binding)"))
        assertTrue(wire.contains("binding.coordinator.evaluate(nowMs, binding.inputGeneration)"))
        assertTrue(wire.contains("snapshot.userMotion?.let { positioning.value.applyUserMotionState(it) }"))
        assertTrue(wire.contains("guidance.value.applyPositionConfidenceDecision(snapshot, emitAnnouncements = false)"))
        assertFalse(refresh.contains("latestTrustedLocation ="))
        assertFalse(refresh.contains("isRouteActive"))
        assertFalse(refresh.contains("speakInteraction("))
        assertFalse(refresh.contains("speakNavigation("))
        assertFalse(refresh.contains("startVoiceCommandRecognition("))
        assertTrue(stop.contains("stopRouteCompassPresentationUpdates()"))
        val orientationCleared = stop.indexOf("compass().clearLocationOwner()")
        val compassRefreshed = stop.indexOf("effects.refreshCompassPresentation()")
        assertTrue(orientationCleared >= 0)
        assertTrue(compassRefreshed >= 0)
        assertTrue(compassRefreshed > orientationCleared)
        assertTrue(stop.contains("sources.clearGeomagneticReference()"))
        val home = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/ui/HomePanel.kt")
        val compassView = MainActivityRefactorSource.property(home, "nativeHomeCompassText")
        assertTrue(compassView.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE"))
        val presentation = MainActivityRefactorSource.member(source, "refreshRouteCompassPresentation")
        assertFalse(presentation.contains("nativeGuidanceStatusText"))
        val liveGuidance = MainActivityRefactorSource.member(source, "nativeGuidanceStatusMessage")
        assertFalse(liveGuidance.contains("routeCompassStatusMessage"))
        val nativeView = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt")
        assertTrue(nativeView.contains("show(nativeHomeCompassText, (facts.homeVisible || facts.guidanceVisible) && !facts.deviceScreen)"))
        assertTrue(refresh.contains("isCurrent = lease.isCurrent"))
        assertTrue(scheduled.contains("if (!isCurrent()) return@Runnable"))
        assertTrue(wire.contains("elapsedRealtimeMs = elapsedRealtimeMs"))
        assertTrue(activityPorts.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
    }

    private fun functionSection(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
