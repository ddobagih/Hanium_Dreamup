package kr.co.hanium.dreamup.walksafe.navigation.session

import android.location.Location
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.device.WalkSessionDeviceResourceSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.network.*

/** The existing navigation owners and lazy edges, never another state/snapshot authority. */
internal class NavigationSessionComposition(
    allowDegradedRouteGuidance: Boolean,
    private val positions: PositioningAssemblyBindings,
    private val gpsBindings: GpsAssemblyBindings,
    private val guides: GuidanceAssemblyBindings,
    private val bearings: CompassAssemblyBindings,
    private val archiveFacts: () -> ArchiveActivityFacts,
    private val routes: AcquisitionAssemblyBindings,
    private val resources: NavigationAssemblyResources,
    private val gpsActor: NavigationGpsActorAuthority,
    private val elapsedRealtimeMs: () -> Long,
    private val wallTimeMs: () -> Long,
    private val isMainThread: () -> Boolean,
    private val postToUi: (() -> Unit) -> Unit,
) {
    val run = NavigationRunState(RouteNavigator(
        config = RouteNavigatorConfig(directionRecheckGraceMs = GuidanceRecoveryTiming.DIRECTION_GRACE_MS),
        allowDegradedRouteGuidance = allowDegradedRouteGuidance,
        nowElapsedRealtimeMs = elapsedRealtimeMs,
    ))

    val positioning: Lazy<PositioningSessionController> = lazy {
        val scope = positions.scope()
        val sources = positions.sources()
        val mounting = positions.mounting()
        val activityEffects = positions.effects()
        PositioningSessionController(run, scope, sources, mounting,
            object : PositioningSessionController.Effects {
                override fun stopFieldSession(): Boolean = activityEffects.stopFieldSession()
                override fun resetMotionTelemetry() = activityEffects.resetMotionTelemetry()
                override fun refreshCalibration() = activityEffects.refreshCalibration()
                override fun updateStepLength(stepLengthM: Float) = activityEffects.updateStepLength(stepLengthM)
                override fun pauseDirection(reason: String, message: String) = acquisition.value.pauseDirectionGuidance(reason, message)
                override fun navigationStatus(text: String) = activityEffects.navigationStatus(text)
                override fun clearReportLocationStartSchedule() = activityEffects.clearReportLocationStartSchedule()
                override fun scheduleRouteWarmingReassessment() = activityEffects.scheduleRouteWarmingReassessment()
                override fun shouldAppendMotionTelemetry(nowMs: Long, force: Boolean): Boolean = activityEffects.shouldAppendMotionTelemetry(nowMs, force)
                override fun appendMotionTrace(snapshot: PositioningSnapshot?, stepDetected: Boolean, zuptApplied: Boolean, measurementElapsedRealtimeMs: Long) = activityEffects.appendMotionTrace(snapshot, stepDetected, zuptApplied, measurementElapsedRealtimeMs)
                override fun appendGnssTrace(snapshot: PositioningSnapshot, location: Location, matchedEvidenceCurrent: Boolean) = activityEffects.appendGnssTrace(snapshot, location, matchedEvidenceCurrent)
                override fun refreshCompassPresentation() = activityEffects.refreshCompassPresentation()
                override fun cleanupWarning(failure: RuntimeException) = activityEffects.cleanupWarning(failure)
            }, diagnostics.value, { gps.value }, { compass.value }, { guidance.value }, { acquisition.value },
            elapsedRealtimeMs, postToUi)
    }

    val gps: Lazy<ContinuousGpsSession> = lazy {
        ContinuousGpsSession(
            source = object : ContinuousGpsSession.SourceReader {
                override fun current() = currentContinuousGpsSourceOrNull()
                override val latestTrusted get() = positioning.value.trustedLocation
                override val latestRaw get() = positioning.value.rawRouteLocation
                override fun nativeCheckCurrent(epoch: WalkRuntimeEpoch) = gpsBindings.nativeCheckCurrent(epoch)
            },
            runtimeGpsQualityGate = gpsBindings.qualityGate(),
            evidence = gpsBindings.evidence(),
            actions = object : ContinuousGpsSession.Actions {
                override fun clearDisconnectedRecovery() = acquisition.value.clearDisconnectedRecovery()
                override fun prewalkEvidenceChanged() = gpsBindings.prewalkEvidenceChanged()
                override fun requestFresh(result: (Location?) -> Unit) = gpsBindings.requestFresh(result)
                override fun consumeFresh(location: Location, receivedAtMs: Long) = consumeFreshGpsLocation(location, receivedAtMs)
            }, clock = elapsedRealtimeMs)
    }

    val diagnostics: Lazy<RouteGuidanceController.Diagnostics> = lazy { resources.diagnostics() }

    val guidance: Lazy<RouteGuidanceController> = lazy {
        val activityScope = guides.scope()
        val speech = guides.speech()
        val activityEffects = guides.effects()
        RouteGuidanceController(run,
            scope = object : RouteGuidanceController.ScopeReader {
                override val lifecycle: WalkSessionLifecycle? get() = activityScope.lifecycle
                override val foreground: Boolean get() = activityScope.foreground
                override val runtimeActive: Boolean get() = activityScope.runtimeActive
                override val navigationAllowed: Boolean get() = activityScope.navigationAllowed
                override val requestPending get() = acquisition.value.requestPending
                override val requestGeneration get() = acquisition.value.requestGeneration
                override val feedbackGeneration: Int get() = activityScope.feedbackGeneration
                override val developmentBypass: Boolean get() = activityScope.developmentBypass
                override fun feedbackCurrent(generation: Int): Boolean = activityScope.feedbackCurrent(generation)
            }, position = object : RouteGuidanceController.PositionReader {
                override val latestTrusted get() = positioning.value.trustedLocation
                override val latestRaw get() = positioning.value.rawRouteLocation
                override val covariance get() = positioning.value.trustedCovariance
                override val course get() = positioning.value.gpsCourse
                override val speed get() = positioning.value.walkingSpeed
                override val stepCount get() = guides.stepCount()
                override val stepLengthM get() = guides.stepLengthM()
                override fun currentLocation() = positioning.value.currentRouteLocationOrNull()
                override fun confidenceLeaseOrNull(token: Long): RouteGuidanceController.ConfidenceLease? {
                    val binding = positioning.value.binding ?: return null
                    return object : RouteGuidanceController.ConfidenceLease {
                        override fun isCurrent() = positioning.value.isPositioningCollectionCurrent(binding) && binding.coordinator.isConfidenceAnnouncementCurrent(token)
                        override fun delivered(nowMs: Long) { binding.coordinator.commitAnnouncementDelivered(token, nowMs) }
                    }
                }
            }, compassInput = object : RouteGuidanceController.CompassReader {
                override val revision get() = compass.value.revision
                override val invalidationPending get() = compass.value.invalidationPending
                override fun heading(nowMs: Long) = compass.value.heading(nowMs)
                override fun facing(nowMs: Long) = compass.value.facing(nowMs)
            }, speech = speech, effects = object : RouteGuidanceController.Effects {
                override fun beginArrival(token: kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken) = activityEffects.beginArrival(token)
                override fun navigationStatus(text: String) = activityEffects.navigationStatus(text)
                override fun interaction(message: String) = activityEffects.interaction(message)
                override fun pauseDirection(reason: String, message: String) = acquisition.value.pauseDirectionGuidance(reason, message)
                override fun decisionActions(decision: RouteNavigatorUserDecision?) = activityEffects.decisionActions(decision)
                override fun progressBeep(nowMs: Long, offRoute: Boolean, arrived: Boolean) = activityEffects.progressBeep(nowMs, offRoute, arrived)
                override fun pausedHaptic() = activityEffects.pausedHaptic()
                override fun deviationHaptic() = activityEffects.deviationHaptic()
                override fun preparationStage(stage: String) = activityEffects.preparationStage(stage)
                override fun clearWaitingFailure(current: () -> Boolean) = activityEffects.clearWaitingFailure(current)
                override fun showLastResult(message: String) = activityEffects.showLastResult(message)
                override fun unavailableLocationMessage(): String = activityEffects.unavailableLocationMessage()
            }, diagnostics = diagnostics.value,
            installedRouteStartSpeechCoordinator = resources.installedStartSpeech(),
            callbacks = resources.callbacks(), elapsedRealtimeMs = elapsedRealtimeMs,
            isMainThread = isMainThread, postToUi = postToUi,
            capturePositioningSpeechOwner = {
                positioning.value.binding?.let { binding ->
                    { positioning.value.isPositioningCollectionCurrent(binding) }
                }
            })
    }

    val compass: Lazy<RouteCompassSession> = lazy {
        val activity = bearings.authority()
        val tracker = bearings.tracker()
        RouteCompassSession(run,
            source = object : RouteCompassSession.Source {
                override fun captureOwner(): RouteCompassSession.CompassObservationOwner? {
                    val binding = positioning.value.binding ?: return null
                    if (!activity.lifecycleReady) return null
                    return RouteCompassSession.CompassObservationOwner(binding, activity.walkEpoch(),
                        acquisition.value.requestGeneration, activity.feedbackGeneration, run.navigator.currentTactileRouteProgress().routeVersion)
                }
                override fun ownerCurrent(owner: RouteCompassSession.CompassObservationOwner, exactOwner: Boolean, orientationActive: Boolean): Boolean {
                    val gateway = activity.gatewaySnapshot()
                    val binding = owner.binding as PositioningCollectionBinding
                    return exactOwner && activity.foreground && orientationActive &&
                        positioning.value.callback != null && positioning.value.binding === binding &&
                        positioning.value.coordinator === binding.coordinator &&
                        binding.coordinator.currentInputGeneration() == binding.inputGeneration &&
                        positioning.value.callbackGeneration == binding.locationGeneration && positioning.value.collectionOwner == binding.owner &&
                        gateway.generation == binding.owner.gatewayGeneration && gateway.session === binding.owner.gatewaySession &&
                        !gateway.storageBlocked && !gateway.deletionRecoveryOnly && activity.lifecycleReady &&
                        activity.walkEpoch() == owner.epoch && acquisition.value.requestGeneration == owner.requestGeneration &&
                        activity.feedbackGeneration == owner.lifecycleGeneration && run.navigator.currentTactileRouteProgress().routeVersion == owner.routeVersion
                }
                override fun collectionCurrent() = positioning.value.binding?.let { positioning.value.isPositioningCollectionCurrent(it) } == true
                override fun mayRetainCompass(orientationActive: Boolean): Boolean {
                    val owner = positioning.value.collectionOwner
                    return orientationActive && positioning.value.callback != null && owner != null && activity.currentCollectionOwner() == owner
                }
                override fun captureSchedule(orientationActive: Boolean): RouteCompassSession.ScheduleLease? {
                    val owner = positioning.value.collectionOwner ?: return null
                    val generation = positioning.value.callbackGeneration
                    val binding = positioning.value.binding ?: return null
                    if (!activity.foreground || !orientationActive || positioning.value.callback == null ||
                        activity.currentCollectionOwner() != owner || !positioning.value.isPositioningCollectionCurrent(binding)) return null
                    return RouteCompassSession.ScheduleLease(
                        isCurrent = { activity.foreground && compass.value.orientationActive && positioning.value.callback != null &&
                            positioning.value.collectionOwner == owner && positioning.value.callbackGeneration == generation &&
                            activity.currentCollectionOwner() == owner && positioning.value.isPositioningCollectionCurrent(binding) },
                        evaluatePosition = { nowMs ->
                            val snapshot = binding.coordinator.evaluate(nowMs, binding.inputGeneration)
                            snapshot.userMotion?.let { positioning.value.applyUserMotionState(it) }
                            if (activity.navigationAllowed || activity.fieldAllowed) {
                                guidance.value.applyPositionConfidenceDecision(snapshot, emitAnnouncements = false)
                            }
                        },
                    )
                }
                override fun headingAllowed(trackerAvailable: () -> Boolean, orientationActive: Boolean) =
                    activity.foreground && trackerAvailable() && orientationActive && positioning.value.callback != null &&
                        positioning.value.collectionOwner != null && activity.currentCollectionOwner() == positioning.value.collectionOwner &&
                        positioning.value.binding?.let { positioning.value.isPositioningCollectionCurrent(it) } == true
            }, tracker = tracker, actions = object : RouteCompassSession.Actions {
                override fun cancelSpeech() = bearings.cancelSpeech()
                override fun retry(nowMs: Long) = guidance.value.retryCurrentNavigationSpeechAfterReady(nowMs)
                override fun retryObservation(nowMs: Long, firstBadAtMs: Long, compass: RouteCompassHeadingResult?, publishInstruction: Boolean) = guidance.value.retryCurrentNavigationSpeechForObservation(nowMs, firstBadAtMs, compass, publishInstruction)
                override fun refreshPresentation() = bearings.refreshPresentation()
            }, callbacks = bearings.callbacks(), diagnostics = diagnostics.value, elapsedRealtimeMs = elapsedRealtimeMs)
    }

    val archive: Lazy<RouteArchiveController> = lazy {
        val lane = resources.lane()
        val activity = archiveFacts()
        RouteArchiveController(lane,
            object : RouteArchiveController.Facts {
                override fun gatewaySnapshot(): GatewaySessionProcessSnapshot = activity.gatewaySnapshot()
                override fun lifecycleOrNull(): WalkSessionLifecycle? = activity.lifecycleOrNull()
                override fun activityCurrent(): Boolean = activity.activityCurrent()
                override fun deletionBlocked(): Boolean = activity.deletionBlocked()
                override fun deletionCoordinator(): AccountDeletionResetCoordinator = activity.deletionCoordinator()
                override fun deletionActivityLease(): AccountDeletionActivityLease = activity.deletionActivityLease()
                override fun deletionIntentToken(): Any = activity.deletionIntentToken()
                override fun developmentBypass(): Boolean = activity.developmentBypass()
                override fun routeActive() = run.active
            }, resources.presentation(), resources.callbacks(),
            { acquisition.value.continuePendingExplicitRouteStartIfReady() }, wallTimeMs)
    }

    val acquisition: Lazy<RouteAcquisitionController> = lazy {
        val archive = this.archive.value
        val activityFacts = routes.facts()
        val activityEffects = routes.effects()
        RouteAcquisitionController(run, archive,
            facts = object : RouteAcquisitionController.Facts {
                override val foreground: Boolean get() = activityFacts.foreground
                override val navigationAllowed: Boolean get() = activityFacts.navigationAllowed
                override val environmentOutputsAllowed: Boolean get() = activityFacts.environmentOutputsAllowed
                override val runtimeActive: Boolean get() = activityFacts.runtimeActive
                override val lifecycle: WalkSessionLifecycle? get() = activityFacts.lifecycle
                override val gateway: GatewaySessionProcessSnapshot get() = activityFacts.gateway
                override val rawActorId: String? get() = activityFacts.rawActorId
                override val actorId: String? get() = activityFacts.actorId
                override fun sessionCurrent(session: GatewayFieldSession): Boolean = activityFacts.sessionCurrent(session)
                override val resources: WalkSessionDeviceResourceSnapshot get() = activityFacts.resources
                override val developmentBypass: Boolean get() = activityFacts.developmentBypass
                override val locationPermissionReady: Boolean get() = activityFacts.locationPermissionReady
                override val selectedDestination: DestinationSearchResult? get() = activityFacts.selectedDestination
                override val guidancePage: Boolean get() = activityFacts.guidancePage
                override val currentLocation get() = positioning.value.currentRouteLocationOrNull()
                override val latestTrustedLocation get() = positioning.value.trustedLocation
                override val latestRawLocation get() = positioning.value.rawRouteLocation
                override val positionPaused get() = guidance.value.positionPaused
                override val startWaitViewReady: Boolean get() = activityFacts.startWaitViewReady
                override val stepCount: Int get() = activityFacts.stepCount
                override val gpsSource get() = currentContinuousGpsSourceOrNull()?.let {
                    RouteAcquisitionController.RecoverySource(it, it.epoch,
                        (it.binding as PositioningCollectionBinding).owner.gatewayGeneration, (it.binding as PositioningCollectionBinding).owner.gatewaySession)
                }
                override val publishedGpsSourceKey get() = gps.value.owner
                override val permissionOwner: PermissionSessionPolicy get() = activityFacts.permissionOwner
                override val deletionOwner: AccountDeletionStateMachine get() = activityFacts.deletionOwner
                override val deletionActivityLease: AccountDeletionActivityLease get() = activityFacts.deletionActivityLease
                override val activityDestroyed: Boolean get() = activityFacts.activityDestroyed
            }, effects = object : RouteAcquisitionController.Effects {
                override fun blockRouteMutation() = guidance.value.blockRouteMutationWhileDeviationChoicePending()
                override fun requireActor(reason: String): Boolean = activityEffects.requireActor(reason)
                override fun networkAllowed(reason: String): Boolean = activityEffects.networkAllowed(reason)
                override fun sessionOrNull(reason: String): GatewayFieldSession? = activityEffects.sessionOrNull(reason)
                override fun explainUnavailable() = activityEffects.explainUnavailable()
                override fun requestLocationServices() = activityEffects.requestLocationServices()
                override fun setRoutePermissionRequested(requested: Boolean) = activityEffects.setRoutePermissionRequested(requested)
                override fun invalidateStopConfirmation() = activityEffects.invalidateStopConfirmation()
                override fun cancelNavigationRetry() = guidance.value.cancelNavigationSpeechRetry()
                override fun cancelLocationStatusSpeech() = guidance.value.cancelRouteLocationStatusSpeech()
                override fun cancelNavigationSpeech() = activityEffects.cancelNavigationSpeech()
                override fun cancelDecisionSpeech() = guidance.value.cancelRouteDecisionSpeech()
                override fun clearDeviationHaptic() = guidance.value.clearDeviationHaptic()
                override fun updateDeviationActions(decision: RouteNavigatorUserDecision?) = activityEffects.updateDeviationActions(decision)
                override fun applyDeviationUpdate(update: RouteNavigatorUpdate) = guidance.value.applyRouteDeviationSafetyUpdate(update)
                override fun markTactileInactive() = activityEffects.markTactileInactive()
                override fun updateDestinationSearch() = activityEffects.updateDestinationSearch()
                override fun clearNativeSearchAndShowHome() = activityEffects.clearNativeSearchAndShowHome()
                override fun renderMain() = activityEffects.renderMain()
                override fun resetStartSpeechState() = activityEffects.resetStartSpeechState {
                    guidance.value.clearRecovery()
                    guidance.value.clearResume()
                }
                override fun beginInstalledRoute() = activityEffects.beginInstalledRoute { guidance.value.clearResume() }
                override fun recordInstalledRoute(route: WalkingRoute) = activityEffects.recordInstalledRoute(route) { run.navigator.currentRouteId() }
                override fun showDestinationCoordinates(destination: RoutePoint) = activityEffects.showDestinationCoordinates(destination)
                override fun recordStage(stage: String) = activityEffects.recordStage(stage)
                override fun bindCompassOwner() = compass.value.bindCompassObservationOwner()
                override fun updateGuidance(location: TrustedLocation, rawLocation: TrustedLocation) = guidance.value.updateRouteGuidance(location, rawLocation)
                override fun requestFreshGps() {
                    gps.value.requestFreshGpsForCurrentSource(elapsedRealtimeMs)
                }
                override fun sessionNotReady(session: GatewayFieldSession) = activityEffects.sessionNotReady(session)
                override fun failureAdmission(session: GatewayFieldSession, authenticationFailure: Boolean): (() -> Boolean)? = activityEffects.failureAdmission(session, authenticationFailure)
            }, presentation = resources.presentation(), backend = routes.backend(),
            navigationRequests = routes.navigationRequests(), destinationSearch = routes.destinationSearch(),
            installedStartSpeech = resources.installedStartSpeech(), worker = routes.worker(), postToUi = postToUi,
            startWaitCallbacks = routes.startWaitCallbacks(), recoveryCallbacks = resources.callbacks(),
            elapsedRealtimeMs = elapsedRealtimeMs, wallTimeMs = wallTimeMs, lane = resources.lane())
    }

    fun currentContinuousGpsSourceOrNull(): ContinuousGpsSource? {
        val binding = positioning.value.binding ?: return null
        val owner = positioning.value.collectionOwner ?: return null
        val session = owner.gatewaySession ?: return null
        val actor = owner.actorId ?: return null
        val walk = gpsActor.walkSnapshot()
        val gateway = gpsActor.gatewaySnapshot()
        val profile = gpsActor.profileOrNull() ?: return null
        if (!gpsActor.foreground || !walk.isForeground ||
            walk.state !in setOf(WalkSessionState.READY, WalkSessionState.PAUSED, WalkSessionState.ACTIVE) ||
            positioning.value.callback == null || binding.owner != owner ||
            binding.locationGeneration != positioning.value.callbackGeneration ||
            binding.coordinator !== positioning.value.coordinator ||
            binding.inputGeneration != positioning.value.coordinator.currentInputGeneration() ||
            owner.fieldWalkEpoch != null || owner.fieldScopeId != null ||
            gateway.storageBlocked || gateway.deletionRecoveryOnly ||
            gateway.generation != owner.gatewayGeneration || gateway.session !== session ||
            actor != gpsActor.rawActorId || !gpsActor.onboardingInitialized ||
            gpsActor.verifiedActorId() != actor || session.actorId != actor ||
            session.sessionScope != GatewaySessionScope.GENERAL ||
            session.verificationState != GatewaySessionVerificationState.VERIFIED ||
            !session.isUsableFor(actor) || gpsActor.deletionBlocked()
        ) return null
        return ContinuousGpsSource(binding, walk.epoch, profile)
    }

    fun consumeFreshGpsLocation(location: Location, receivedAtMs: Long) {
        val source = currentContinuousGpsSourceOrNull() ?: return
        if (gps.value.owner != source) return
        val binding = source.binding as PositioningCollectionBinding
        val owner = binding.owner
        val generation = binding.locationGeneration
        positioning.value.handleLocationUpdate(location, owner, generation, binding, receivedAtMs)
    }
}
