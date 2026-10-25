package kr.co.hanium.dreamup.walksafe.navigation.session

import android.annotation.SuppressLint
import android.location.Location
import android.os.Build
import com.google.android.gms.location.LocationCallback
import java.util.Locale
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.device.ApprovedPhoneMountingProfile
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessment
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessmentPhase
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingMethod
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingPolicy
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingRuntimeState
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingStatus
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingUserConfirmation
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeStopCleanupCheckpoints
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.NavigationTestLocationPolicy
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassLocationReference
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.navigation.location.FusedLocationCancellationRegistry
import kr.co.hanium.dreamup.walksafe.navigation.location.FusedLocationUpdateEvent
import kr.co.hanium.dreamup.walksafe.navigation.location.FusedLocationUpdateSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.AndroidPedestrianMotionTracker
import kr.co.hanium.dreamup.walksafe.navigation.positioning.AndroidPedestrianProfileStore
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssObservationDisposition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssHardRejectReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssPositionObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssSampleFingerprint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HorizontalUncertaintyKind
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PdrStepQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianHeadingInput
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianStationaryDecision
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidencePolicy
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidencePolicyConfig
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningFilteredFix
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RawWalkingCalibrationFix
import kr.co.hanium.dreamup.walksafe.navigation.positioning.TimestampAlignedWalkingCalibration
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserDeviceBodyRelationState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateOwner
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMovementState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ZuptObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.PositionStationaryState
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualityObserver
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualitySnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnssSampleFingerprint
import kr.co.hanium.dreamup.walksafe.navigation.reliableMovementHeadingDegrees
import kr.co.hanium.dreamup.walksafe.navigation.session.NavigationRunState
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.mounting.MountingPositionFacts

private const val GPS_DEGRADATION_CONFIRMATION_DURATION_MS = GuidanceRecoveryTiming.GPS_GRACE_MS

/** Single owner of collection identity, absolute/PDR estimate, accepted coordinates and calibration history. */
internal class PositioningSessionController(
    private val run: NavigationRunState,
    private val scope: Scope,
    private val sources: Sources,
    private val mounting: Mounting,
    private val effects: Effects,
    private val diagnostics: RouteGuidanceController.Diagnostics,
    private val gps: () -> ContinuousGpsSession,
    private val compass: () -> RouteCompassSession,
    private val guidance: () -> RouteGuidanceController,
    private val requests: () -> RouteAcquisitionController,
    private val elapsedRealtimeMs: () -> Long,
    private val postToUi: (() -> Unit) -> Unit,
) {
    interface Scope {
        fun currentOwner(): LocationCollectionOwner?
        val navigationAllowed: Boolean
        val motionObservationAllowed: Boolean get() = currentOwner() != null
        val fieldAllowed: Boolean
        val fieldLeasePresent: Boolean
        val fieldStorageBlocked: Boolean
        val fieldChestConfirmed: Boolean
        val fineLocationGranted: Boolean
        val developmentBypass: Boolean
        val lifecycle: WalkSessionLifecycle?
        val stepCount: Int
        val bootId: String?
        val processInstanceId: String
        fun reporterId(): String?
    }
    interface Sources {
        val fusedAvailable: Boolean
        fun fusedSource(current: (LocationCallback) -> Boolean, onEvent: (LocationCallback, FusedLocationUpdateEvent) -> Unit): FusedLocationUpdateSource
        fun requestUpdates(source: FusedLocationUpdateSource)
        fun captureRemoval(): (LocationCallback) -> com.google.android.gms.tasks.Task<Void>
        val completionExecutor: java.util.concurrent.Executor
        fun gnssObserver(onSnapshot: (GnssQualitySnapshot) -> Unit): GnssQualityObserver
        fun pedestrianTracker(onMotion: (PedestrianStationaryDecision) -> Unit): AndroidPedestrianMotionTracker
        val profileStoreAvailable: Boolean
        fun profileStore(): AndroidPedestrianProfileStore
        fun mock(location: Location): Boolean
        val orientationAvailable: Boolean
        val orientationCanStop: Boolean
        fun clearGeomagneticReference()
        fun stopOrientation()
        fun geomagneticReference(location: Location)
        fun chestHeading(timestampMs: Long, maximumAgeMs: Long): HeadingObservation?
    }
    interface Mounting {
        val observationLock: Any
        val profile: ApprovedPhoneMountingProfile?
        val confirmation: PhoneMountingUserConfirmation?
        val explicitConfirmation: PhoneMountingUserConfirmation?
        val cameraAssessment: CameraFrameQualityAssessment?
        val runtimeState: PhoneMountingRuntimeState?
        val outputsAllowed: Boolean
        val observationGeneration: Long
        fun retireExplicitForChangedMotionOwner()
        fun retireExplicitForChangedActor()
    }
    interface Effects {
        fun stopFieldSession(): Boolean
        fun resetMotionTelemetry()
        fun refreshCalibration()
        fun updateStepLength(stepLengthM: Float)
        fun pauseDirection(reason: String, message: String)
        fun navigationStatus(text: String)
        fun clearReportLocationStartSchedule()
        fun shouldAppendMotionTelemetry(nowMs: Long, force: Boolean): Boolean
        fun appendMotionTrace(snapshot: PositioningSnapshot?, stepDetected: Boolean, zuptApplied: Boolean, measurementElapsedRealtimeMs: Long)
        fun appendGnssTrace(snapshot: PositioningSnapshot, location: Location, matchedEvidenceCurrent: Boolean)
        fun refreshCompassPresentation()
        fun cleanupWarning(failure: RuntimeException)
        fun scheduleRouteWarmingReassessment() = Unit
    }

    private fun defaultPositioningCoordinator() = PositioningCoordinator(
        confidencePolicy = PositionConfidencePolicy(
            PositionConfidencePolicyConfig(degradationConfirmationDurationMs = GPS_DEGRADATION_CONFIRMATION_DURATION_MS),
        ),
    )

    @Volatile
    private var positioningCoordinator = defaultPositioningCoordinator()
    private val routeWarmingProjection = RouteWarmingProjection(GPS_DEGRADATION_CONFIRMATION_DURATION_MS)
    @Volatile
    private var positioningCollectionBinding: PositioningCollectionBinding? = null
    private var locationCallback: LocationCallback? = null
    private var locationCallbackGeneration = 0
    private var locationCollectionOwner: LocationCollectionOwner? = null
    private var lastLocationCleanupFailure: RuntimeException? = null
    private val fusedLocationCancellations = FusedLocationCancellationRegistry()
    @Volatile
    private var latestTrustedLocation: TrustedLocation? = null
    /** S09-45: ENU covariance of the filtered fix that produced exactly [latestTrustedLocation]. */
    private var latestTrustedLocationCovariance: Pair<TrustedLocation, kr.co.hanium.dreamup.walksafe.navigation.positioning.EnuPositionCovariance>? = null
    private var latestRawRouteLocation: TrustedLocation? = null
    private var latestNavigationTestLocation: TrustedLocation? = null
    private var pedestrianMotionTracker: AndroidPedestrianMotionTracker? = null
    private var gnssQualityObserver: GnssQualityObserver? = null
    private var latestGnssQualitySnapshot: GnssQualitySnapshot? = null
    private var latestGeographicMetadataBinding: PositioningCollectionBinding? = null
    private var latestGeographicMetadataAtNanos: Long? = null
    // S09-56 (pos-F2): also read by the account-deletion cleanup thread.
    @Volatile
    private var pedestrianProfileStore: AndroidPedestrianProfileStore? = null
    /** S09-56: status of the current actor's last profile load, repeated when a field session starts. */
    private var latestPedestrianProfileLoadStatus: String? = null
    private var positioningProfileActorId: String? = null
    private var positioningProfileLoaded = false
    private var latestWalkingSpeedObservation: WalkingSpeedObservation? = null
    private var latestGpsCourseObservation: HeadingObservation? = null
    @Volatile
    private var latestGpsMotionInputs: GpsMotionInputs? = null
    private val gpsMotionInputHistory = java.util.ArrayDeque<GpsMotionInputs>()
    private var lastPositionZuptAtElapsedMs: Long? = null
    private var latestPositionStationaryState = PositionStationaryState.UNKNOWN
    private var latestPositionStationarySinceMs: Long? = null
    @Volatile
    private var latestHeadingDeg: Float? = null
    private val walkingCalibrationSession = WalkingCalibrationSession()
    private val userMotionBootId by lazy { scope.bootId }
    private val USER_MOTION_PROCESS_INSTANCE_ID get() = scope.processInstanceId


    val fusedLocationCleanupFailure get() = fusedLocationCancellations.fusedLocationCleanupFailure
    private val routeNavigator get() = run.navigator
    private val isRouteActive get() = run.active
    val coordinator get() = positioningCoordinator
    val binding get() = positioningCollectionBinding
    val callback get() = locationCallback
    val callbackGeneration get() = locationCallbackGeneration
    val collectionOwner get() = locationCollectionOwner
    val trustedLocation get() = latestTrustedLocation
    val rawRouteLocation get() = latestRawRouteLocation
    val trustedCovariance get() = latestTrustedLocationCovariance
    val testLocation get() = latestNavigationTestLocation
    val cleanupFailure get() = lastLocationCleanupFailure
    val profileStore get() = pedestrianProfileStore
    val profileActorId get() = positioningProfileActorId
    val profileLoaded get() = positioningProfileLoaded
    val profileLoadStatus get() = latestPedestrianProfileLoadStatus
    val gnssQuality get() = latestGnssQualitySnapshot
    val routeWarmingDeadlineElapsedRealtimeMs get() = routeWarmingProjection.deadlineElapsedRealtimeMs()
    val motionTracker get() = pedestrianMotionTracker
    val walkingSpeed get() = latestWalkingSpeedObservation
    val gpsCourse get() = latestGpsCourseObservation
    val gpsMotionInputs get() = latestGpsMotionInputs
    val stationaryState get() = latestPositionStationaryState
    val stationarySince get() = latestPositionStationarySinceMs
    val headingDegrees get() = latestHeadingDeg
    val calibrationSegments get() = walkingCalibrationSession.segments
    val calibrationScope get() = walkingCalibrationSession.scope
    val calibrationStep get() = walkingCalibrationSession.latestStep

    @SuppressLint("MissingPermission")
    fun startLocationUpdatesIfAllowed(forceRestart: Boolean = false) {
        val owner = scope.currentOwner() ?: run {
            stopLocationUpdatesInternal(stopFieldSession = true, retryRetained = false)
            return
        }
        if (!sources.fusedAvailable) return
        if (!forceRestart && locationCallback != null && locationCollectionOwner == owner &&
            positioningCollectionBinding?.let(::isPositioningCollectionCurrent) == true
        ) {
            startPositioningObservationSources()
            return
        }
        if (locationCallback != null &&
            !stopLocationUpdatesInternal(stopFieldSession = false, retryRetained = false)
        ) return
        // Automatic start observes old results; only an explicit cleanup retries a failed target.
        if (!observePendingFusedLocationCancellations()) return
        val generation = ++locationCallbackGeneration
        locationCollectionOwner = owner
        ensurePositioningProfileForCurrentActor()
        val coordinator = positioningCoordinator
        val binding = PositioningCollectionBinding(
            coordinator = coordinator,
            inputGeneration = coordinator.currentInputGeneration(),
            owner = owner,
            locationGeneration = generation,
            startedAtElapsedRealtimeMs = elapsedRealtimeMs(),
        )
        positioningCollectionBinding = binding
        bindPositioningUserMotionEpoch(binding, binding.startedAtElapsedRealtimeMs)
        val fusedLocationSource = sources.fusedSource(
            current = { callback ->
                isLocationCallbackCurrent(owner, generation, callback) && isPositioningCollectionCurrent(binding)
            },
            onEvent = event@{ _, event ->
                when (event) {
                    is FusedLocationUpdateEvent.LocationReceived -> handleLocationUpdate(
                        event.location, owner, generation, binding, event.receivedAtMs,
                    )
                    is FusedLocationUpdateEvent.AvailabilityChanged -> {
                        val availability = event.availability
                        if (!availability.isLocationAvailable) {
                            val nowMs = elapsedRealtimeMs()
                            val currentPosition = binding.coordinator.evaluate(nowMs, binding.inputGeneration)
                            val routeConfidence = routeWarmingSelection(currentPosition, nowMs)?.confidence
                                ?: currentPosition.confidence
                            if (retainFreshPositionOnAvailabilityLoss(latestTrustedLocation, routeConfidence, nowMs)) {
                                // Availability predicts a forthcoming fix; it does not revoke an accepted one.
                                // Keep the original fix/evidence times so the normal stale watchdog still expires them.
                                if (diagnostics.debug) diagnostics.log("WalkSafePositionCheck",
                                    "event=AVAILABILITY_LOSS_RETAINED quality=${currentPosition.quality.name}")
                                return@event
                            }
                            val warmingSource = if (routeWarmingDeadlineElapsedRealtimeMs != null)
                                gps().currentSource() else null
                            if (warmingSource != null && routeWarmingProjection.owns(binding, warmingSource) &&
                                scope.fineLocationGranted
                            ) {
                                // A filter warming timeout is not a newly measured GPS failure.
                                applyRoutePositionConfidence(currentPosition, nowMs)
                                effects.navigationStatus("navigation=position_filter_warming_rechecking")
                                return@event
                            }
                            gps().recordGeneralLocationGpsObservation(
                                observedAtElapsedRealtimeMs = { elapsedRealtimeMs() },
                                trustedFixAvailable = false,
                                horizontalAccuracyMeters = null,
                            )
                            clearTrustedLocation()
                            if (!scope.navigationAllowed &&
                                !scope.fieldAllowed
                            ) return@event
                            val positionDecisionRequired = handlePositioningUnavailable(
                                elapsedRealtimeMs = elapsedRealtimeMs(),
                            )
                            effects.navigationStatus("navigation=gps_unavailable")
                            if (!positionDecisionRequired) {
                                effects.pauseDirection(
                                    reason = "gps_unavailable",
                                    message = "GPS 위치를 확인할 수 없어 방향 안내를 중지했습니다.",
                                )
                            }
                        }
                    }
                    is FusedLocationUpdateEvent.RegistrationFailed -> {
                        val locationWorkWasAllowed = scope.navigationAllowed ||
                            scope.fieldAllowed
                        stopLocationUpdates()
                        if (!locationWorkWasAllowed) return@event
                        val positionDecisionRequired = handlePositioningUnavailable(
                            elapsedRealtimeMs = elapsedRealtimeMs(),
                        )
                        effects.navigationStatus("navigation=gps_unavailable")
                        if (!positionDecisionRequired) {
                            effects.pauseDirection(
                                reason = "gps_unavailable",
                                message = "GPS 위치를 확인할 수 없어 방향 안내를 중지했습니다.",
                            )
                        }
                    }
                }
            },
        )
        val callback = fusedLocationSource.callback

        locationCallback = callback
        startPositioningObservationSources()
        try {
            sources.requestUpdates(fusedLocationSource)
        } catch (_: SecurityException) {
            val navigationWasAllowed = scope.navigationAllowed
            stopLocationUpdates()
            if (!navigationWasAllowed) return
            val positionDecisionRequired = handlePositioningUnavailable(
                elapsedRealtimeMs = elapsedRealtimeMs(),
            )
            effects.navigationStatus("navigation=gps_permission_revoked")
            if (!positionDecisionRequired) {
                effects.pauseDirection(
                    reason = "location_permission_revoked",
                    message = "정확한 위치 권한이 해제되어 방향 안내를 중지했습니다.",
                )
            }
        }
    }

    fun stopLocationUpdates(stopFieldSession: Boolean = true): Boolean =
        stopLocationUpdatesInternal(stopFieldSession, retryRetained = true)

    fun stopLocationUpdatesInternal(stopFieldSession: Boolean, retryRetained: Boolean): Boolean {
        lastLocationCleanupFailure = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: RuntimeException) {
                val first = lastLocationCleanupFailure
                if (first == null) lastLocationCleanupFailure = failure
                else if (first !== failure) first.addSuppressed(failure)
            }
        }
        positioningCollectionBinding = null
        routeWarmingProjection.reset()
        gps().clearContinuousGpsHistory()
        if (stopFieldSession) attempt {
            check(effects.stopFieldSession()) { "position_field_stop_failed" }
        }
        locationCallbackGeneration += 1
        val callback = locationCallback
        locationCallback = null
        locationCollectionOwner = null
        latestNavigationTestLocation = null
        var fusedRemoved = true
        if (sources.fusedAvailable) {
            val removeUpdates = sources.captureRemoval()
            callback?.let { registeredCallback ->
                attempt {
                    fusedRemoved = cancelFusedLocationUpdates(
                        registeredCallback = registeredCallback,
                        remove = removeUpdates,
                        completionExecutor = sources.completionExecutor,
                    )
                }
            }
        }
        // The active callback was cleared above; retained failed/pending targets still matter.
        if (callback == null) {
            fusedRemoved = if (retryRetained) retryPendingFusedLocationCancellations()
            else observePendingFusedLocationCancellations()
        }
        // Keep the native cause for diagnostics; a pending retry is not a failed local tail.
        attempt {
            check(closeGnssQualityObserver()) { "gnss_cancellation_pending" }
        }
        attempt {
            pedestrianMotionTracker?.close()
            pedestrianMotionTracker = null
        }
        latestGnssQualitySnapshot = null
        latestGeographicMetadataBinding = null
        latestGeographicMetadataAtNanos = null
        latestWalkingSpeedObservation = null
        latestGpsCourseObservation = null
        latestRawRouteLocation = null
        guidance().clearPositionPause()
        compass().clearLocationOwner()
        attempt { compass().stopRouteCompassPresentationUpdates() }
        attempt {
            if (sources.orientationAvailable) sources.clearGeomagneticReference()
        }
        attempt {
            if (sources.orientationAvailable && sources.orientationCanStop) {
                sources.stopOrientation()
            }
        }
        attempt { positioningCoordinator.reset() }
        attempt { clearTrustedLocation() }
        attempt { effects.refreshCompassPresentation() }
        (lastLocationCleanupFailure ?: fusedLocationCleanupFailure)?.let { failure ->
            runCatching { effects.cleanupWarning(failure) }
        }
        return lastLocationCleanupFailure == null && fusedRemoved &&
            !fusedLocationCancellations.hasPendingCancellations
    }

    internal fun cancelFusedLocationUpdates(
        registeredCallback: LocationCallback,
        remove: (LocationCallback) -> com.google.android.gms.tasks.Task<Void>,
        completionExecutor: java.util.concurrent.Executor,
    ): Boolean {
        return fusedLocationCancellations.cancelFusedLocationUpdates(registeredCallback, remove, completionExecutor)
    }

    internal fun observePendingFusedLocationCancellations(): Boolean {
        return fusedLocationCancellations.observePendingFusedLocationCancellations()
    }

    internal fun retryPendingFusedLocationCancellations(): Boolean {
        return fusedLocationCancellations.retryPendingFusedLocationCancellations()
    }

    internal fun awaitFusedLocationCancellation(completion: (Boolean) -> Unit) {
        fusedLocationCancellations.awaitFusedLocationCancellation(completion)
    }

    internal fun runLocationStopCleanupCheckpoint(
        checkpoints: WalkRuntimeStopCleanupCheckpoints,
        onResult: (Boolean) -> Unit,
        stopTail: () -> Boolean,
    ): Boolean {
        return fusedLocationCancellations.runLocationStopCleanupCheckpoint(checkpoints, onResult, stopTail)
    }

    fun closeGnssQualityObserver(): Boolean {
        val observer = gnssQualityObserver ?: return true
        observer.close()
        if (observer.cleanupPending) return false
        if (gnssQualityObserver === observer) gnssQualityObserver = null
        return true
    }

    fun capturePositioningMountTarget(): PositioningMountTarget? {
        val binding = positioningCollectionBinding ?: return null
        val owner = binding.coordinator.currentUserMotionOwner() ?: return null
        return PositioningMountTarget(binding, owner)
    }

    /** Caller holds mounting.observationLock; this is a pure evaluation of the original inputs. */
    fun capturePositioningMountObservation(
        target: PositioningMountTarget?,
        epoch: WalkRuntimeEpoch,
        observedAtElapsedRealtimeMs: Long,
        phase: PhoneMountingAssessmentPhase,
        previousState: PhoneMountingRuntimeState?,
        cameraFrameQualityOverride: CameraFrameQualityAssessment? = null,
        runtimeRetryRequested: Boolean = false,
    ): PositioningMountObservation? {
        target ?: return null
        val confirmation = mounting.confirmation
        val assessment = PhoneMountingPolicy.assess(
            phase = phase,
            currentEpoch = epoch,
            nowElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            userConfirmation = confirmation,
            cameraFrameQuality = cameraFrameQualityOverride ?: mounting.cameraAssessment,
            previousState = previousState ?: PhoneMountingPolicy.initialState(epoch, mounting.profile),
            runtimeRetryRequested = runtimeRetryRequested,
            checkRequest = null, // A sensor-only check cannot declare a device-body relation.
        )
        return PositioningMountObservation(target, mounting.observationGeneration, confirmation,
            mounting.explicitConfirmation, assessment, observedAtElapsedRealtimeMs)
    }

    fun positioningMountRelation(observation: PositioningMountObservation): UserDeviceBodyRelationState {
        val confirmation = observation.confirmation ?: return UserDeviceBodyRelationState.UNKNOWN
        val assessment = observation.assessment ?: return UserDeviceBodyRelationState.UNKNOWN
        if (scope.developmentBypass ||
            confirmation !== observation.explicitConfirmation ||
            confirmation.epoch != observation.target.motionOwner.walkEpoch ||
            assessment.nextState.epoch != confirmation.epoch ||
            confirmation.confirmedAtElapsedRealtimeMs < 0L ||
            confirmation.confirmedAtElapsedRealtimeMs > observation.observedAtElapsedRealtimeMs
        ) return UserDeviceBodyRelationState.UNKNOWN
        return when {
            assessment.status == PhoneMountingStatus.SUITABLE &&
                confirmation.method in setOf(PhoneMountingMethod.CHEST_FORWARD, PhoneMountingMethod.NECKLACE_FORWARD) ->
                UserDeviceBodyRelationState.SUPPORTED_MOUNT
            assessment.reason == kr.co.hanium.dreamup.walksafe.device.PhoneMountingReason.PROHIBITED_MOUNTING_METHOD &&
                confirmation.method in setOf(PhoneMountingMethod.HANDHELD, PhoneMountingMethod.POCKET) ->
                UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED
            else -> UserDeviceBodyRelationState.UNKNOWN
        }
    }

    /** Memory-only; used under mounting.observationLock before and after native admission. */
    fun positioningMountObservationIsOwned(observation: PositioningMountObservation): Boolean {
        val target = observation.target
        val binding = target.binding
        return positioningCollectionBinding === binding && positioningCoordinator === binding.coordinator &&
            binding.coordinator.currentInputGeneration() == binding.inputGeneration &&
            binding.coordinator.currentUserMotionOwner() == target.motionOwner &&
            observation.observationGeneration == mounting.observationGeneration &&
            observation.confirmation === mounting.confirmation &&
            observation.explicitConfirmation === mounting.explicitConfirmation
    }

    fun observePositioningMount(
        observation: PositioningMountObservation?,
        receivedAtElapsedRealtimeMs: Long,
    ): Boolean {
        observation ?: return false
        val target = observation.target
        val binding = target.binding
        if (!synchronized(mounting.observationLock) { positioningMountObservationIsOwned(observation) }) return false
        val relation = positioningMountRelation(observation)
        // Positive declarations use the real permission/service/account owner gate. A retirement
        // can clear its exact old owner after foreground/permission admission has already closed.
        if (relation != UserDeviceBodyRelationState.UNKNOWN &&
            (!isPositioningCollectionCurrent(binding) || !(scope.lifecycle != null) ||
                requireNotNull(scope.lifecycle).snapshot().epoch != target.motionOwner.walkEpoch)
        ) return false
        val accepted = synchronized(mounting.observationLock) {
            if (!positioningMountObservationIsOwned(observation)) return@synchronized false
            binding.coordinator.observeUserMount(relation, observation.observedAtElapsedRealtimeMs,
                receivedAtElapsedRealtimeMs, binding.inputGeneration, target.motionOwner)
        }
        if (accepted) applyUserMotionState(binding.coordinator.peekUserMotion(receivedAtElapsedRealtimeMs))
        return accepted
    }

    fun isPositioningCollectionCurrent(binding: PositioningCollectionBinding): Boolean =
        positioningCollectionBinding === binding &&
            positioningCoordinator === binding.coordinator &&
            binding.coordinator.currentInputGeneration() == binding.inputGeneration &&
            locationCallback != null &&
            locationCallbackGeneration == binding.locationGeneration &&
            locationCollectionOwner == binding.owner &&
            scope.currentOwner() == binding.owner

    fun isLocationCallbackCurrent(
        owner: LocationCollectionOwner,
        generation: Int,
        callback: LocationCallback,
    ): Boolean =
        locationCallback === callback &&
            generation == locationCallbackGeneration &&
            locationCollectionOwner == owner &&
            scope.currentOwner() == owner

    fun currentRouteLocationOrNull(): TrustedLocation? {
        if (!scope.developmentBypass) return freshTrustedLocationOrNull()
        if (locationCollectionOwner == null ||
            scope.currentOwner() != locationCollectionOwner
        ) return null
        return NavigationTestLocationPolicy.freshOrNull(
            latestNavigationTestLocation,
            elapsedRealtimeMs(),
        )
    }

    fun freshTrustedLocationOrNull(
        nowElapsedRealtimeMs: Long = elapsedRealtimeMs(),
    ): TrustedLocation? {
        val owner = locationCollectionOwner
        val generation = locationCallbackGeneration
        if (owner == null || scope.currentOwner() != owner) {
            postToUi {
                if (locationCollectionOwner == owner && locationCallbackGeneration == generation &&
                    (owner == null || scope.currentOwner() != owner)
                ) clearTrustedLocation()
            }
            return null
        }
        val current = latestTrustedLocation
        if (current?.navigationOnly == true) {
            val binding = positioningCollectionBinding
            val source = gps().currentSource()
            if (binding == null || source == null || !scope.fineLocationGranted ||
                !isPositioningCollectionCurrent(binding) ||
                !routeWarmingProjection.retains(binding, source, current, nowElapsedRealtimeMs)
            ) {
                postToUi { handlePositioningUnavailable(elapsedRealtimeMs()) }
                return null
            }
        }
        val fresh = LocationTrustPolicy.freshOrNull(current, nowElapsedRealtimeMs)
        if (current != null && fresh == null && nowElapsedRealtimeMs >= current.elapsedRealtimeMs) {
            // A GL reader's event time can precede a fix just published by the main looper.
            // It cannot expire that newer fix or mutate the serial confidence state off-main.
            postToUi {
                retireExpiredRouteLocationIfCurrent(current, elapsedRealtimeMs(),
                    isCollectionCurrent = {
                        locationCollectionOwner == owner && locationCallbackGeneration == generation &&
                            scope.currentOwner() == owner
                    },
                )
            }
        }
        return fresh
    }

    /** Called on the main looper after re-reading time; a queued old reader cannot clear a new fix. */
    internal fun retireExpiredRouteLocationIfCurrent(
        expectedPosition: TrustedLocation,
        nowElapsedRealtimeMs: Long,
        isCollectionCurrent: () -> Boolean,
        onExpired: (Long) -> Unit = { now ->
            if (scope.navigationAllowed && !handlePositioningUnavailable(now)) {
                effects.pauseDirection("gps_stale", "GPS 위치가 오래되어 방향 안내를 중지했습니다.")
            }
        },
    ): Boolean {
        if (!isCollectionCurrent() || latestTrustedLocation !== expectedPosition ||
            nowElapsedRealtimeMs < expectedPosition.elapsedRealtimeMs ||
            LocationTrustPolicy.freshOrNull(expectedPosition, nowElapsedRealtimeMs) != null
        ) return false
        clearTrustedLocation()
        onExpired(nowElapsedRealtimeMs)
        return true
    }

    fun clearTrustedLocation(): Boolean {
        routeWarmingProjection.invalidate()
        latestTrustedLocation = null
        latestRawRouteLocation = null
        clearLocationDerivedState()
        return false
    }

    fun clearLocationDerivedState() {
        latestGpsMotionInputs = null
        gpsMotionInputHistory.clear()
        lastPositionZuptAtElapsedMs = null
        effects.resetMotionTelemetry()
        latestWalkingSpeedObservation = null
        latestGpsCourseObservation = null
        latestHeadingDeg = null
        walkingCalibrationSession.resetHistory()
    }

    /** Motion epochs are separate from the longer-lived location collection and absolute filter. */
    fun bindPositioningUserMotionEpoch(
        binding: PositioningCollectionBinding,
        notBeforeElapsedRealtimeMs: Long,
    ): Boolean {
        val owner = UserMotionStateOwner(
            actorGeneration = binding.owner.gatewayGeneration,
            walkEpoch = requireNotNull(scope.lifecycle).snapshot().epoch,
            bootId = userMotionBootId,
            processInstanceId = USER_MOTION_PROCESS_INSTANCE_ID,
        )
        val previousOwner = binding.coordinator.currentUserMotionOwner()
        val ownerChanged = previousOwner != owner
        if (ownerChanged && !binding.coordinator.beginUserMotionEpoch(owner, notBeforeElapsedRealtimeMs)) return false
        val mountTarget = PositioningMountTarget(binding, owner)
        val mountObservation = synchronized(mounting.observationLock) {
            if (previousOwner != null && previousOwner != owner) {
                mounting.retireExplicitForChangedMotionOwner()
            }
            capturePositioningMountObservation(mountTarget, owner.walkEpoch, elapsedRealtimeMs(),
                PhoneMountingAssessmentPhase.PREFLIGHT, mounting.runtimeState)
        }
        observePositioningMount(mountObservation, elapsedRealtimeMs())
        // The first bind precedes the LocationCallback publication; the later same-owner call
        // synchronizes the relation through its real current collection gate without resetting it.
        if (!ownerChanged) return false
        binding.counterBoundaryEstablished = false
        // start() on an already running tracker does not clear its windows or replace the old closure.
        pedestrianMotionTracker?.close()
        pedestrianMotionTracker = null
        clearLocationDerivedState()
        applyUserMotionState(binding.coordinator.currentUserMotion(notBeforeElapsedRealtimeMs))
        return true
    }

    fun startPositioningObservationSources() {
        if (!scope.fineLocationGranted) return
        val owner = locationCollectionOwner ?: return
        val generation = locationCallbackGeneration
        val callback = locationCallback ?: return
        val binding = positioningCollectionBinding ?: return
        if (!isLocationCallbackCurrent(owner, generation, callback) ||
            !isPositioningCollectionCurrent(binding)
        ) return
        bindPositioningUserMotionEpoch(binding, binding.startedAtElapsedRealtimeMs)
        val motionOwner = binding.coordinator.currentUserMotionOwner() ?: return
        compass().ensureEarthOrientationForLocation()
        if (gnssQualityObserver?.cleanupPending == true) {
            runCatching { closeGnssQualityObserver() }
        }
        if (gnssQualityObserver == null) {
            gnssQualityObserver = sources.gnssObserver(
                onSnapshot = { snapshot ->
                    if (isLocationCallbackCurrent(owner, generation, callback) &&
                        isPositioningCollectionCurrent(binding)
                    ) {
                        latestGnssQualitySnapshot = snapshot
                    }
                },
            )
        }
        if (gnssQualityObserver?.cleanupPending != true) {
            runCatching { gnssQualityObserver?.start() }
        }
        if (!scope.motionObservationAllowed) {
            pedestrianMotionTracker?.stop()
            return
        }
        if (pedestrianMotionTracker == null) {
            pedestrianMotionTracker = sources.pedestrianTracker motion@{ decision ->
                if (!scope.motionObservationAllowed || !isLocationCallbackCurrent(owner, generation, callback) ||
                    !isPositioningCollectionCurrent(binding) ||
                    binding.coordinator.currentUserMotionOwner() != motionOwner
                ) return@motion
                val nowMs = elapsedRealtimeMs()
                val userMotion = binding.coordinator.observeUserMotion(
                    decision,
                    receivedAtElapsedRealtimeMs = nowMs,
                    expectedGeneration = binding.inputGeneration,
                    expectedMotionOwner = motionOwner,
                )
                applyUserMotionState(userMotion)
                effects.refreshCalibration()
                if (!userMotion.zeroVelocityUpdateAllowed) {
                    lastPositionZuptAtElapsedMs = null
                }
                val zuptDue = lastPositionZuptAtElapsedMs?.let { nowMs - it >= 1_000L } ?: true
                val zuptSnapshot = if (
                    decision.shouldApplyZeroVelocityUpdate && userMotion.zeroVelocityUpdateAllowed && zuptDue &&
                    (
                        scope.navigationAllowed ||
                            scope.fieldLeasePresent
                    )
                ) {
                    binding.coordinator.observeZupt(
                        ZuptObservation(decision.sample?.elapsedRealtimeMs ?: nowMs),
                        receivedAtElapsedRealtimeMs = nowMs,
                        expectedGeneration = binding.inputGeneration,
                        expectedMotionOwner = motionOwner,
                    ).also { snapshot ->
                        if (snapshot.motionUpdate?.disposition ==
                            kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionUpdateDisposition.APPLIED
                        ) lastPositionZuptAtElapsedMs = nowMs
                    }
                } else {
                    null
                }
                zuptSnapshot?.let { applyPositioningMotionSnapshot(it, binding) }
                if (diagnostics.debug && !scope.fieldStorageBlocked &&
                    scope.fieldChestConfirmed &&
                    effects.shouldAppendMotionTelemetry(
                        nowMs,
                        force = zuptSnapshot != null || decision.stateChanged,
                    )
                ) {
                    effects.appendMotionTrace(
                        snapshot = zuptSnapshot,
                        stepDetected = false,
                        zuptApplied = zuptSnapshot?.motionUpdate?.disposition ==
                            kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionUpdateDisposition.APPLIED,
                        measurementElapsedRealtimeMs = nowMs,
                    )
                }
            }
        }
        runCatching { pedestrianMotionTracker?.start() }
        effects.refreshCalibration()
    }

    fun ensurePositioningProfileForCurrentActor() {
        val actorId = locationCollectionOwner?.actorId ?: scope.reporterId()
        if (positioningProfileLoaded && actorId == positioningProfileActorId) return
        if (positioningProfileLoaded && actorId != positioningProfileActorId) {
            synchronized(mounting.observationLock) {
                mounting.retireExplicitForChangedActor()
            }
        }
        positioningCoordinator = defaultPositioningCoordinator()
        routeWarmingProjection.reset()
        positioningProfileActorId = actorId
        positioningProfileLoaded = true
        latestPedestrianProfileLoadStatus = null
        synchronizeWalkingCalibrationScope()
        if (pedestrianProfileStore == null && sources.profileStoreAvailable) {
            pedestrianProfileStore = runCatching {
                sources.profileStore()
            }.getOrNull()
        }
        val restored = actorId?.let { id ->
            // S09-55: a save still queued for this actor is newer than the stored value.
            kr.co.hanium.dreamup.walksafe.navigation.positioning.SerialPedestrianProfileWriter.shared
                .pendingProfileOrNull(id)
                // S09-56: the load status separates default step length, migration, reset and a blocked store.
                ?: runCatching { pedestrianProfileStore?.loadResult(id) }.getOrNull()
                    ?.also(::recordPedestrianProfileLoad)?.profile
        }
        restored?.let(positioningCoordinator::restoreProfile)
        val stepLengthM = positioningCoordinator.currentProfile().stepLengthM.toFloat()
        effects.updateStepLength(stepLengthM)
        effects.refreshCalibration()
    }

    fun recordPedestrianProfileLoad(
        result: kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianProfileLoadResult,
    ) {
        latestPedestrianProfileLoadStatus = result.status.name
        if (diagnostics.recording) {
            diagnostics.record("pedestrian_profile_loaded", mapOf("result" to result.status.name))
        }
    }

    fun persistPositioningProfileForCurrentActor() {
        val actorId = scope.reporterId() ?: return
        if (actorId != positioningProfileActorId) return
        val profile = positioningCoordinator.snapshotProfileForPersistence() ?: return
        val store = pedestrianProfileStore ?: return
        // S09-55: the encrypted commit runs on the serial profile I/O thread, pinned to this actor and store.
        kr.co.hanium.dreamup.walksafe.navigation.positioning.SerialPedestrianProfileWriter.shared
            .submit(actorId, profile) { id, value -> store.save(id, value) }
    }

    fun updatePositioningHeadingInputs(
        location: Location,
        elapsedRealtimeMs: Long,
        binding: PositioningCollectionBinding,
        receivedAtElapsedRealtimeMs: Long,
    ) {
        if (!isPositioningCollectionCurrent(binding)) return
        val motionOwner = binding.coordinator.currentUserMotionOwner() ?: return
        if (!binding.coordinator.isUserMotionInputCurrent(elapsedRealtimeMs, motionOwner)) return
        if (latestGpsMotionInputs?.evidence?.observedAtElapsedRealtimeMs
                ?.let { elapsedRealtimeMs <= it } == true
        ) return
        updatePositioningHeadingInputs(location.gnssSampleFingerprint(sources.mock(location)),
            elapsedRealtimeMs, binding, receivedAtElapsedRealtimeMs)
    }

    private fun updatePositioningHeadingInputs(
        location: GnssSampleFingerprint,
        elapsedRealtimeMs: Long,
        binding: PositioningCollectionBinding,
        receivedAtElapsedRealtimeMs: Long,
    ) {
        if (!isPositioningCollectionCurrent(binding)) return
        val motionOwner = binding.coordinator.currentUserMotionOwner() ?: return
        if (!binding.coordinator.isUserMotionInputCurrent(elapsedRealtimeMs, motionOwner)) return
        if (latestGpsMotionInputs?.evidence?.observedAtElapsedRealtimeMs
                ?.let { elapsedRealtimeMs <= it } == true
        ) return
        val speedAccuracy = location.speedAccuracyMps
        latestWalkingSpeedObservation = location.speedMps
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { speed ->
                WalkingSpeedObservation(
                    speedMps = speed.toDouble(),
                    accuracyMps = speedAccuracy,
                    elapsedRealtimeMs = elapsedRealtimeMs,
                )
            }
        val bearingAccuracy = location.bearingAccuracyDegrees
            ?.takeIf { it.isFinite() && it in 0.0..45.0 }
        latestGpsCourseObservation = location.bearingDegrees
            ?.takeIf { it.isFinite() && bearingAccuracy != null }
            ?.let { bearing ->
                HeadingObservation(
                    headingDegreesTrueNorth = bearing.toDouble(),
                    accuracyDegrees = bearingAccuracy,
                    elapsedRealtimeMs = elapsedRealtimeMs,
                )
            }
        // Publish one immutable fix to the GL thread; speed/course may never come from different fixes.
        val inputs = GpsMotionInputs(
            speed = latestWalkingSpeedObservation,
            course = latestGpsCourseObservation,
            evidence = UserMotionLocationEvidence(
                latitude = location.latitude,
                longitude = location.longitude,
                measurementElapsedRealtimeNanos = location.elapsedRealtimeNanos,
                observedAtElapsedRealtimeMs = elapsedRealtimeMs,
                horizontalAccuracyM = location.horizontalAccuracyM,
                isMock = location.mock,
            ),
        )
        latestGpsMotionInputs = inputs
        binding.coordinator.observeUserSpeed(
            inputs.speed,
            inputs.evidence,
            receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
            expectedGeneration = binding.inputGeneration,
            expectedMotionOwner = motionOwner,
        )
        gpsMotionInputHistory.addLast(inputs)
        while (gpsMotionInputHistory.size > 64 || gpsMotionInputHistory.firstOrNull()?.let {
                elapsedRealtimeMs - it.evidence.observedAtElapsedRealtimeMs > 5_000L
            } == true
        ) gpsMotionInputHistory.removeFirst()
    }

    fun handlePositioningStepEvent(
        event: StepEvent,
        binding: PositioningCollectionBinding,
        receivedAtElapsedRealtimeMs: Long,
        motionOwner: UserMotionStateOwner,
    ) {
        if (!scope.motionObservationAllowed || !isPositioningCollectionCurrent(binding) ||
            !binding.coordinator.isUserMotionInputCurrent(event.timestampMs, motionOwner) ||
            event.timestampMs <= binding.startedAtElapsedRealtimeMs ||
            event.timestampMs > receivedAtElapsedRealtimeMs ||
            event.deltaSteps !in 1..64
        ) return
        if (event.source == StepEventSource.STEP_COUNTER && !binding.counterBoundaryEstablished) {
            binding.counterBoundaryEstablished = true
            return
        }
        pedestrianMotionTracker?.recordStep(event.timestampMs)
        if (!scope.navigationAllowed && !scope.fieldLeasePresent) {
            applyUserMotionState(binding.coordinator.observeUserSteps(event, receivedAtElapsedRealtimeMs,
                binding.inputGeneration, motionOwner))
            return
        }
        // S09-40/S09-43: usage condition of this step (enum names only) for the step-length sigma and calibration.
        // pos-F6: set before the calibration segments so a sample completed by this step records this step's
        // condition; only this walk's mounting confirmation counts.
        val currentWalkEpoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull()
        binding.coordinator.setCurrentCalibrationCondition(
            kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianCalibrationCondition(
                stepInput = event.source.name,
                mounting = mounting.confirmation?.takeIf { it.epoch == currentWalkEpoch }?.method?.name,
            ),
        )
        synchronizeWalkingCalibrationScope()
        walkingCalibrationSession.onStep(event, receivedAtElapsedRealtimeMs)
            .forEach(::applyWalkingCalibrationSample)
        val quality = when (event.confidence) {
            StepEventConfidence.HIGH -> PdrStepQuality.HIGH
            StepEventConfidence.MEDIUM -> PdrStepQuality.MEDIUM
            StepEventConfidence.LOW -> PdrStepQuality.LOW
        }
        val chestHeading = chestMountedHeadingForStep(event.timestampMs)
        val gpsAtStep = gpsMotionInputHistory.lastOrNull {
            it.evidence.observedAtElapsedRealtimeMs <= event.timestampMs
        }
        val courseAtStep = gpsAtStep?.course
        val chestAtCourse = if (chestHeading != null && courseAtStep != null) {
            chestMountedHeadingForStep(courseAtStep.elapsedRealtimeMs, maximumAgeMs = 100L)
        } else null
        val snapshot = binding.coordinator.observeStep(
                stepCount = event.deltaSteps,
                headingInput = PedestrianHeadingInput(
                    nowElapsedRealtimeMs = event.timestampMs,
                    speed = gpsAtStep?.speed,
                    gpsCourse = courseAtStep,
                    magneticTrueHeading = chestHeading,
                    magneticTrueHeadingAtGpsCourse = chestAtCourse,
                    phoneForwardMounted = chestHeading != null,
                ),
                quality = quality,
                receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
                expectedGeneration = binding.inputGeneration,
                expectedMotionOwner = motionOwner,
                stepEventSource = event.source,
                stepEventConfidence = event.confidence,
                originalStepEvent = event,
            )
        applyPositioningMotionSnapshot(snapshot, binding)
        effects.refreshCalibration()
        effects.appendMotionTrace(
            snapshot = snapshot,
            stepDetected = true,
            zuptApplied = false,
            measurementElapsedRealtimeMs = event.timestampMs,
        )
    }

    fun updateRouteOrientationReference(location: Location) {
        if (!sources.orientationAvailable) return
        compass().ensureEarthOrientationForLocation()
        sources.geomagneticReference(location)
    }

    fun chestMountedHeadingForStep(
        timestampMs: Long,
        maximumAgeMs: Long = 500L,
    ): HeadingObservation? {
        if (!sources.orientationAvailable) return null
        val walkEpoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull() ?: return null
        val explicitFieldConfirmation = scope.fieldChestConfirmed &&
            scope.fieldLeasePresent
        return chestMountedHeadingForStep(
            timestampMs = timestampMs,
            maximumAgeMs = maximumAgeMs,
            walkEpoch = walkEpoch,
            explicitFieldConfirmation = explicitFieldConfirmation,
            phoneMountingOutputsAllowed = mounting.outputsAllowed,
            phoneMountingRuntimeState = mounting.runtimeState,
            phoneMountingUserConfirmation = mounting.confirmation,
            lookupHeading = sources::chestHeading,
        )
    }

    internal fun chestMountedHeadingForStep(
        timestampMs: Long,
        maximumAgeMs: Long = 500L,
        walkEpoch: WalkRuntimeEpoch,
        explicitFieldConfirmation: Boolean,
        phoneMountingOutputsAllowed: Boolean,
        phoneMountingRuntimeState: PhoneMountingRuntimeState?,
        phoneMountingUserConfirmation: PhoneMountingUserConfirmation?,
        lookupHeading: (Long, Long) -> HeadingObservation?,
    ): HeadingObservation? {
        val existingChestMount = phoneMountingOutputsAllowed &&
            phoneMountingRuntimeState?.epoch == walkEpoch &&
            phoneMountingUserConfirmation?.epoch == walkEpoch &&
            phoneMountingUserConfirmation?.method == PhoneMountingMethod.CHEST_FORWARD &&
            phoneMountingUserConfirmation.confirmedAtElapsedRealtimeMs <= timestampMs
        if (!explicitFieldConfirmation && !existingChestMount) return null
        return lookupHeading(timestampMs, maximumAgeMs)
    }

    fun applyPositioningMotionSnapshot(
        snapshot: PositioningSnapshot,
        binding: PositioningCollectionBinding,
    ) {
        if (!isPositioningCollectionCurrent(binding) ||
            snapshot.inputGeneration != binding.inputGeneration ||
            snapshot.userMotion?.owner != binding.coordinator.currentUserMotionOwner() ||
            snapshot.userMotion?.reason in setOf(
                UserMotionStateReason.OWNER_MISMATCH, UserMotionStateReason.GENERATION_MISMATCH,
            )
        ) return
        // S09-9 (09.txt:34): late step/ZUPT replays and refusals are measured (lag, replayed events) in the field log.
        snapshot.motionUpdate?.lateMotionEventFieldsOrNull()?.let { fields ->
            if (diagnostics.recording) diagnostics.record("pdr_late_motion", fields)
        }
        snapshot.userMotion?.let(::applyUserMotionState)
        if (snapshot.motionUpdate?.disposition ==
            kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionUpdateDisposition.HARD_REJECTED
        ) {
            routeWarmingProjection.invalidate()
            guidance().applyPositionConfidenceDecision(snapshot)
            return
        }
        applyRoutePositionConfidence(snapshot, elapsedRealtimeMs())
        if (routeWarmingSelection(snapshot, elapsedRealtimeMs()) != null) return
        val filtered = snapshot.filtered ?: return
        val rawAccuracyM = snapshot.raw?.reportedHorizontalAccuracyM
            ?: LocationTrustPolicy.freshOrNull(latestRawRouteLocation, elapsedRealtimeMs())?.accuracyM?.toDouble()
        if (!mayPublishPositioningRouteFix(snapshot.quality, snapshot.confidence,
                filtered.horizontalUncertaintyM, rawAccuracyM)) return
        latestTrustedLocation = filtered.toTrustedLocation()
        rememberTrustedLocationCovariance(filtered)
    }

    fun handlePositioningUnavailable(elapsedRealtimeMs: Long): Boolean {
        val binding = positioningCollectionBinding ?: return false
        if (!isPositioningCollectionCurrent(binding)) return false
        val snapshot = binding.coordinator.evaluate(elapsedRealtimeMs, binding.inputGeneration)
        snapshot.userMotion?.let(::applyUserMotionState)
        return applyRoutePositionConfidence(snapshot, elapsedRealtimeMs)
    }

    fun reassessRouteWarming(nowMs: Long) {
        if (routeWarmingDeadlineElapsedRealtimeMs != null) handlePositioningUnavailable(nowMs)
    }

    private fun routeWarmingSelection(snapshot: PositioningSnapshot, nowMs: Long): RouteWarmingProjection.Selection? {
        if (!routeWarmingProjection.hasAdmittedReference()) return null
        val binding = positioningCollectionBinding ?: return null
        val source = gps().currentSource() ?: return null
        if (!scope.fineLocationGranted || !isPositioningCollectionCurrent(binding)) return null
        return routeWarmingProjection.current(binding, source, snapshot, nowMs)
    }

    private fun applyRoutePositionConfidence(snapshot: PositioningSnapshot, nowMs: Long): Boolean {
        val selected = routeWarmingSelection(snapshot, nowMs)
        if (selected != null) return guidance().applyPositionConfidenceDecision(snapshot,
            routeConfidence = selected.confidence)
        if (latestTrustedLocation?.navigationOnly == true) {
            latestTrustedLocation = null
            latestRawRouteLocation = null
            latestTrustedLocationCovariance = null
            gps().reconcileContinuousGpsRecovery()
        }
        if (routeWarmingDeadlineElapsedRealtimeMs == null) {
            return guidance().applyPositionConfidenceDecision(snapshot)
        }
        val binding = positioningCollectionBinding
        val source = gps().currentSource()
        if (binding != null && source != null && scope.fineLocationGranted &&
            isPositioningCollectionCurrent(binding) && routeWarmingProjection.recheckDue(binding, source, nowMs)
        ) {
            if (gps().hasCurrentGpsFailureEpisode()) gps().reconcileContinuousGpsRecovery()
            else {
                gps().cancelGpsRecoveryRequest()
                gps().requestFreshGpsForCurrentSource()
            }
        }
        val warmingOwned = binding != null && source != null && scope.fineLocationGranted &&
            isPositioningCollectionCurrent(binding) && routeWarmingProjection.owns(binding, source)
        if (warmingOwned && (scope.navigationAllowed || scope.fieldAllowed) &&
            routeWarmingProjection.shouldAnnounceTimeout(nowMs)) {
            effects.pauseDirection("position_filter_warming_timeout",
                "위치 추정을 다시 확인하고 있습니다. 새 위치가 확인되면 안내를 이어갑니다.")
        }
        val warmingExpired = warmingOwned && routeWarmingDeadlineElapsedRealtimeMs?.let { nowMs >= it } == true
        return guidance().applyPositionConfidenceDecision(snapshot,
            routeConfidence = if (warmingExpired) snapshot.confidence.copy(quality = PositionQuality.UNAVAILABLE,
                guidancePaused = true, announcementRequest = null) else snapshot.confidence)
    }

    internal fun retainFreshPositionOnAvailabilityLoss(
        position: TrustedLocation?,
        confidence: kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceDecision,
        nowElapsedRealtimeMs: Long,
    ): Boolean = LocationTrustPolicy.freshOrNull(position, nowElapsedRealtimeMs) != null &&
        !confidence.guidancePaused && confidence.failureReason == null &&
        (confidence.quality in setOf(PositionQuality.HIGH, PositionQuality.MEDIUM, PositionQuality.LOW) ||
            confidence.degradationPendingSinceElapsedRealtimeMs?.let { since ->
                nowElapsedRealtimeMs - since in 0L until GPS_DEGRADATION_CONFIRMATION_DURATION_MS
            } == true)

    /** A quality label is route-usable without claiming a precise turn or arrival. */
    internal fun mayPublishPositioningRouteFix(
        quality: PositionQuality,
        confidence: kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceDecision,
    ): Boolean = quality in setOf(PositionQuality.HIGH, PositionQuality.MEDIUM, PositionQuality.LOW) &&
        confidence.failureReason == null

    /** Production publications also require the existing raw and filtered 15 m bounds.
     * LOW stays LOW; missing raw evidence cannot create a new route position.
     */
    internal fun mayPublishPositioningRouteFix(
        quality: PositionQuality,
        confidence: kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceDecision,
        filteredUncertaintyM: Double?,
        reportedAccuracyM: Double?,
    ): Boolean {
        if (!mayPublishPositioningRouteFix(quality, confidence)) return false
        val maximumM = kr.co.hanium.dreamup.walksafe.navigation.LocationTrustConfig().maxAccuracyM.toDouble()
        return filteredUncertaintyM != null && filteredUncertaintyM.isFinite() &&
            filteredUncertaintyM in 0.0..maximumM && reportedAccuracyM != null && reportedAccuracyM.isFinite() &&
            reportedAccuracyM in 0.0..maximumM
    }

    fun PositioningFilteredFix.toTrustedLocation(): TrustedLocation = TrustedLocation(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        accuracyM = horizontalUncertaintyM.toFloat(),
        elapsedRealtimeMs = elapsedRealtimeMs,
        uncertaintyKind = HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA,
    )

    fun rememberTrustedLocationCovariance(filtered: PositioningFilteredFix) {
        latestTrustedLocationCovariance = latestTrustedLocation?.let { location -> filtered.covarianceEnu?.let { location to it } }
    }

    /** Existing Android raw scalar conversion; no location policy is applied here. */
    fun rawAndroidRouteLocation(
        latitude: Double,
        longitude: Double,
        reportedAccuracyM: Float?,
        elapsedRealtimeMs: Long,
    ): TrustedLocation = TrustedLocation(
        latitude = latitude,
        longitude = longitude,
        accuracyM = reportedAccuracyM ?: Float.MAX_VALUE,
        elapsedRealtimeMs = elapsedRealtimeMs,
        uncertaintyKind = if (reportedAccuracyM != null && reportedAccuracyM.isFinite() && reportedAccuracyM >= 0f) {
            HorizontalUncertaintyKind.ANDROID_RADIUS_68
        } else {
            HorizontalUncertaintyKind.LEGACY_UNSPECIFIED
        },
    )

    fun handleLocationUpdate(
        location: Location,
        owner: LocationCollectionOwner,
        locationGeneration: Int,
        binding: PositioningCollectionBinding,
        receivedAtElapsedRealtimeMs: Long,
    ) {
        // Reject a retired callback before reading any SDK values.
        if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return
        val observation = location.toPositioningObservation(receivedAtElapsedRealtimeMs, sources.mock(location)) { atNanos ->
            runCatching { gnssQualityObserver?.snapshot(atNanos) }.getOrNull()
        }
        handleLocationObservation(
            observation, owner, locationGeneration, binding,
            updateOrientationReference = { updateRouteOrientationReference(location) },
            appendGnssTrace = { snapshot, matchedEvidenceCurrent ->
                effects.appendGnssTrace(snapshot, location, matchedEvidenceCurrent)
            },
        )
    }

    private fun isLocationObservationCurrent(
        owner: LocationCollectionOwner,
        locationGeneration: Int,
        binding: PositioningCollectionBinding,
    ): Boolean = locationCollectionOwner == owner && scope.currentOwner() == owner &&
        locationGeneration == locationCallbackGeneration && isPositioningCollectionCurrent(binding) &&
        scope.currentOwner() != null

    /** Shared production/replay policy. SDK effects retain their original admitted Location at the edge. */
    fun handleLocationObservation(
        observation: PositioningLocationObservation,
        owner: LocationCollectionOwner,
        locationGeneration: Int,
        binding: PositioningCollectionBinding,
        updateOrientationReference: () -> Unit,
        appendGnssTrace: (snapshot: PositioningSnapshot, matchedEvidenceCurrent: Boolean) -> Unit,
    ) {
        if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return
        val location = observation.sample
        val receivedAtElapsedRealtimeMs = observation.receivedAtElapsedRealtimeMs
        val accuracy = location.horizontalAccuracyM?.toFloat()
        // A repeated receipt must never become a new GPS measurement. Floor negative values
        // so a malformed sub-millisecond timestamp cannot become a valid zero-time fix.
        val elapsedMs = Math.floorDiv(location.elapsedRealtimeNanos, 1_000_000L)
        val mock = location.mock
        val acceptedSampleSource = gps().currentSource()
        val sampleFingerprint = location
        val currentGnssQualitySnapshot = observation.signalQuality
        val measurementNoiseMultiplier = currentGnssQualitySnapshot
            ?.measurementNoiseMultiplier
            ?.takeIf { it.isFinite() && it >= 1.0 }
            ?: 1.0
        val reportedAccuracyM = accuracy
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.toDouble()
        val effectiveAccuracyM = reportedAccuracyM?.times(kotlin.math.sqrt(measurementNoiseMultiplier))
        // A fresh recheck and the stream may deliver the same original fix. Do not
        // turn its second delivery into loss, or refresh the original fix/evidence time.
        val duplicateValidationNoiseMultiplier = currentGnssQualitySnapshot?.measurementNoiseMultiplier ?: 1.0
        if (gps().shouldSkipAcceptedGnssSample(sampleFingerprint, receivedAtElapsedRealtimeMs,
                duplicateValidationNoiseMultiplier)) return
        // Late refused accuracy or age must not clear a newer source-owned position or poison confidence.
        // Original sample values stay unchanged; current/future failures still use ordinary handling.
        if (acceptedSampleSource != null && gps().currentSource() == acceptedSampleSource &&
            (gps().shouldIgnoreSupersededInvalidAccuracyGnssSample(sampleFingerprint,
                receivedAtElapsedRealtimeMs, duplicateValidationNoiseMultiplier) ||
                gps().shouldIgnoreSupersededStaleGnssSample(sampleFingerprint,
                    receivedAtElapsedRealtimeMs, duplicateValidationNoiseMultiplier))
        ) return
        // A newer coarse fix can own the geographic reference without publishing a precise fix.
        // Drop only its same-collection, real and valid historical input before estimator mutation.
        val newerGeographicAtNanos = latestGeographicMetadataAtNanos
            ?.takeIf { latestGeographicMetadataBinding === binding }
        if (newerGeographicAtNanos != null &&
            sampleFingerprint.elapsedRealtimeNanos < newerGeographicAtNanos &&
            receivedAtElapsedRealtimeMs >= newerGeographicAtNanos / 1_000_000L &&
            receivedAtElapsedRealtimeMs - newerGeographicAtNanos / 1_000_000L <= 10_000L &&
            reportedAccuracyM != null &&
            sampleFingerprint.canSkipAt(receivedAtElapsedRealtimeMs, duplicateValidationNoiseMultiplier, 10_000L)
        ) return
        // The signal weight travels separately so a missing accuracy is weighted on the default sigma.
        var positioningSnapshot = binding.coordinator.observeGnss(
            GnssPositionObservation(
                latitude = location.latitude,
                longitude = location.longitude,
                horizontalAccuracyM = accuracy?.toDouble(),
                elapsedRealtimeMs = elapsedMs,
                receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
                mock = mock,
                measurementNoiseMultiplier = measurementNoiseMultiplier,
            ),
            expectedGeneration = binding.inputGeneration,
            nowElapsedRealtimeMs = elapsedRealtimeMs(),
        )
        currentGnssQualitySnapshot?.let { qualitySnapshot ->
            positioningSnapshot = positioningSnapshot.copy(
                raw = positioningSnapshot.raw?.copy(
                    reportedHorizontalAccuracyM = accuracy?.toDouble(),
                    effectiveHorizontalAccuracyM = effectiveAccuracyM,
                ),
                gnssQuality = qualitySnapshot,
            )
        }
        val filtered = positioningSnapshot.filtered
        val hardRejected = positioningSnapshot.gnssUpdate?.disposition ==
            GnssObservationDisposition.HARD_REJECTED
        if (diagnostics.debug) {
            positioningSnapshot.gnssUpdate?.let { update ->
                diagnostics.log(
                    "WalkSafePositioning",
                    "gnss disposition=${update.disposition} reason=${update.hardRejectReason} " +
                        "ageMs=${elapsedRealtimeMs() - elapsedMs} lagMs=${update.measurementLagMs} " +
                        "replayedMotion=${update.replayedMotionEventCount}",
                )
            }
        }
        if (acceptedSampleSource != null && gps().currentSource() == acceptedSampleSource &&
            gps().shouldIgnoreSupersededGnssSample(sampleFingerprint, positioningSnapshot,
                receivedAtElapsedRealtimeMs, duplicateValidationNoiseMultiplier)
        ) return
        // Keep these side effects after historical GNSS refusal. A coarse geographic fix can
        // advance the reference without publishing a precise route fix, so it owns its ns order.
        val metadataIsNew = latestGeographicMetadataBinding !== binding ||
            latestGeographicMetadataAtNanos?.let { sampleFingerprint.elapsedRealtimeNanos > it } != false
        if (metadataIsNew) {
            latestGnssQualitySnapshot = currentGnssQualitySnapshot
            if (RouteCompassLocationReference.accepts(
                    location.latitude, location.longitude, elapsedMs,
                    elapsedRealtimeMs(), mock,
                )
            ) {
                latestGeographicMetadataBinding = binding
                latestGeographicMetadataAtNanos = sampleFingerprint.elapsedRealtimeNanos
                updateOrientationReference()
            }
        }
        gps().recordPositioningGpsObservation(positioningSnapshot, elapsedMs, accuracy?.toDouble())
        if (acceptedSampleSource != null && gps().currentSource() == acceptedSampleSource) {
            gps().recordAcceptedGnssSample(sampleFingerprint, positioningSnapshot.gnssUpdate?.disposition,
                receivedAtElapsedRealtimeMs, duplicateValidationNoiseMultiplier)
        }
        val navigationTestFix = if (scope.developmentBypass) {
            NavigationTestLocationPolicy.freshOrNull(
                rawAndroidRouteLocation(
                    location.latitude, location.longitude, accuracy, elapsedMs,
                ),
                elapsedRealtimeMs(),
                mock = mock,
            )
        } else null
        latestNavigationTestLocation = navigationTestFix
        val retainsMeasuredWarming = latestTrustedLocation?.navigationOnly == true &&
            ((hardRejected && positioningSnapshot.gnssUpdate?.hardRejectReason in setOf(
                GnssHardRejectReason.INVALID_ACCURACY,
                GnssHardRejectReason.NUMERICAL_FAILURE,
            )) || (!hardRejected && accuracy == null)) &&
            gps().retainsPublishedPositionDuringGpsFailure(positioningSnapshot, elapsedRealtimeMs(),
                allowMissingAccuracyForWarming = true)
        val warmingSelection = acceptedSampleSource?.takeIf {
            isPositioningCollectionCurrent(binding)
        }?.let { source ->
            routeWarmingProjection.observe(binding, source, positioningSnapshot,
                rawAndroidRouteLocation(location.latitude, location.longitude, accuracy, elapsedMs),
                elapsedRealtimeMs(), retainMeasuredFailure = retainsMeasuredWarming,
                admissionAllowed = { source == gps().currentSource() && scope.fineLocationGranted &&
                    isPositioningCollectionCurrent(binding) })
        }
        if (routeWarmingDeadlineElapsedRealtimeMs != null) effects.scheduleRouteWarmingReassessment()
        if (hardRejected && positioningSnapshot.gnssUpdate?.hardRejectReason in setOf(
                GnssHardRejectReason.INVALID_ACCURACY, GnssHardRejectReason.NUMERICAL_FAILURE,
            ) && acceptedSampleSource != null &&
            routeWarmingProjection.owns(binding, acceptedSampleSource) &&
            routeWarmingDeadlineElapsedRealtimeMs?.let { elapsedRealtimeMs() >= it } == true &&
            scope.fineLocationGranted
        ) {
            applyRoutePositionConfidence(positioningSnapshot, elapsedRealtimeMs())
            return
        }
        if (hardRejected && latestTrustedLocation?.navigationOnly == true && warmingSelection == null) clearTrustedLocation()
        if (hardRejected && navigationTestFix == null &&
            gps().retainsPublishedPositionDuringGpsFailure(positioningSnapshot, elapsedRealtimeMs())
        ) {
            // A measured quality dip shares the GPS gate's first-failure deadline. Keep both
            // original route fixes; refused measurements cannot publish progress or renew them.
            if (scope.navigationAllowed || scope.fieldAllowed) {
                guidance().applyPositionConfidenceDecision(positioningSnapshot,
                    routeConfidence = warmingSelection?.confidence ?: positioningSnapshot.confidence)
                effects.navigationStatus("navigation=position_quality_rechecking reason=${positioningSnapshot.gnssUpdate?.hardRejectReason}")
            }
            return
        }
        if (hardRejected || filtered == null) {
            clearTrustedLocation()
            if (navigationTestFix != null && scope.navigationAllowed) {
                guidance().clearPositionPause()
                requests().continuePendingExplicitRouteStartIfReady()
                guidance().updateRouteGuidance(navigationTestFix, navigationTestFix)
                return
            }
            if (!scope.navigationAllowed &&
                !scope.fieldAllowed
            ) return
            val positionDecisionRequired = guidance().applyPositionConfidenceDecision(positioningSnapshot)
            val rejectedReason = if (mock) "gps_mock_rejected" else "gps_invalid_rejected"
            effects.navigationStatus(
                "navigation=$rejectedReason accuracy=${accuracy?.toInt() ?: "null"}m",
            )
            if (!positionDecisionRequired) {
                effects.pauseDirection(
                    reason = rejectedReason,
                    message = if (mock) {
                        "모의 위치를 사용할 수 없어 방향 안내를 중지했습니다."
                    } else {
                        "유효하지 않은 위치 정보로 방향 안내를 중지했습니다."
                    },
                )
            }
            return
        }
        if (warmingSelection == null && !mayPublishPositioningRouteFix(positioningSnapshot.quality, positioningSnapshot.confidence,
                filtered.horizontalUncertaintyM, accuracy?.toDouble())) {
            // Preserve the last usable fix with its original timestamp during a short quality dip.
            // An out-of-bounds measurement is not new route progress or a refreshed location.
            if (scope.navigationAllowed || scope.fieldAllowed) {
                applyRoutePositionConfidence(positioningSnapshot, elapsedRealtimeMs())
                effects.navigationStatus("navigation=position_quality_rechecking")
            }
            return
        }
        updatePositioningHeadingInputs(location, elapsedMs, binding, receivedAtElapsedRealtimeMs)
        positioningSnapshot = positioningSnapshot.copy(
            userMotion = binding.coordinator.currentUserMotion(elapsedRealtimeMs()),
        )
        positioningSnapshot.userMotion?.let(::applyUserMotionState)
        val filteredTrusted = warmingSelection?.location ?: filtered.toTrustedLocation()
        val rawRouteLocation = warmingSelection?.location ?: rawAndroidRouteLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            reportedAccuracyM = accuracy,
            elapsedRealtimeMs = elapsedMs,
        )
        latestTrustedLocation = filteredTrusted
        if (warmingSelection != null) latestTrustedLocationCovariance = null
        else rememberTrustedLocationCovariance(filtered)
        latestRawRouteLocation = rawRouteLocation
        if (!scope.navigationAllowed) {
            if (scope.fieldAllowed) {
                guidance().applyPositionConfidenceDecision(positioningSnapshot,
                    routeConfidence = warmingSelection?.confidence ?: positioningSnapshot.confidence)
                appendGnssTrace(positioningSnapshot, false)
            }
            return
        }
        val positionGuidancePaused = guidance().applyPositionConfidenceDecision(positioningSnapshot,
            routeConfidence = warmingSelection?.confidence ?: positioningSnapshot.confidence)
        requests().continuePendingExplicitRouteStartIfReady()
        requests().continueDisconnectedRerouteRecoveryIfReady()
        effects.clearReportLocationStartSchedule()
        latestHeadingDeg = latestGpsCourseObservation?.headingDegreesTrueNorth?.toFloat()
        if (!filteredTrusted.navigationOnly) attemptStepCalibration(location, positioningSnapshot, receivedAtElapsedRealtimeMs)
        publishPositioningQualityStatus(positioningSnapshot, filteredTrusted)
        if (isRouteActive && !requests().requestPending && !routeNavigator.hasRoute()) {
            effects.navigationStatus("navigation=route_waiting user_route_decision_required trusted_gps_ready")
        }
        val matchedEvidenceCurrent = guidance().dispatchAcceptedRouteFix(
            filteredTrusted, rawRouteLocation, positionGuidancePaused,
            requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull(), elapsedRealtimeMs(),
        )
        appendGnssTrace(positioningSnapshot, matchedEvidenceCurrent)
    }

    private fun publishPositioningQualityStatus(
        positioningSnapshot: PositioningSnapshot,
        filteredTrusted: TrustedLocation,
    ) {
        if (filteredTrusted.navigationOnly) {
            effects.navigationStatus("navigation=position_low source=raw_warming navigation_only=true " +
                "reported_radius=${filteredTrusted.accuracyM}m model_quality=${positioningSnapshot.quality.name} " +
                "model_max_axis_sigma=${positioningSnapshot.filtered?.horizontalUncertaintyM}m")
            return
        }
        effects.navigationStatus(
            "navigation=position_${positioningSnapshot.quality.name.lowercase(Locale.US)} max_axis_sigma=${filteredTrusted.accuracyM.toInt()}m steps=${scope.stepCount} heading=${latestHeadingDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "null"}",
        )
    }

    fun updateHeadingFromLocation(
        previous: TrustedLocation?,
        location: Location,
        trusted: TrustedLocation,
    ): Float? {
        val bearingAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
            location.bearingAccuracyDegrees
        } else {
            null
        }
        return reliableMovementHeadingDegrees(
            reportedBearingDeg = location.bearing.takeIf { location.hasBearing() },
            speedMps = location.speed.takeIf { location.hasSpeed() },
            bearingAccuracyDeg = bearingAccuracy,
            previous = previous,
            current = trusted,
        )
    }

    fun applyUserMotionState(snapshot: UserMotionStateSnapshot) {
        if (snapshot.owner != positioningCoordinator.currentUserMotionOwner() ||
            snapshot.reason in setOf(UserMotionStateReason.OWNER_MISMATCH, UserMotionStateReason.GENERATION_MISMATCH)
        ) return
        latestPositionStationaryState = when (snapshot.movement) {
            UserMovementState.MOVING -> PositionStationaryState.MOVING
            UserMovementState.STATIONARY -> PositionStationaryState.STATIONARY
            UserMovementState.UNKNOWN -> if (snapshot.stationaryCandidateSinceElapsedRealtimeMs != null) {
                PositionStationaryState.CANDIDATE
            } else PositionStationaryState.UNKNOWN
        }
        latestPositionStationarySinceMs = snapshot.stationaryCandidateSinceElapsedRealtimeMs
            ?: snapshot.stateSinceElapsedRealtimeMs ?: snapshot.evaluatedAtElapsedRealtimeMs
    }

    fun attemptStepCalibration(
        location: Location,
        snapshot: PositioningSnapshot,
        receivedAtElapsedRealtimeMs: Long,
    ) {
        if (latestTrustedLocation?.navigationOnly == true) return
        attemptStepCalibration(location.gnssSampleFingerprint(sources.mock(location)), snapshot, receivedAtElapsedRealtimeMs)
    }

    private fun attemptStepCalibration(
        location: GnssSampleFingerprint,
        snapshot: PositioningSnapshot,
        receivedAtElapsedRealtimeMs: Long,
    ) {
        if (latestTrustedLocation?.navigationOnly == true) return
        synchronizeWalkingCalibrationScope()
        val raw = snapshot.raw ?: run {
            walkingCalibrationSession.resetHistory()
            effects.refreshCalibration()
            return
        }
        val processingAtElapsedRealtimeMs = elapsedRealtimeMs()
        val userMotion = snapshot.userMotion
        val disposition = snapshot.gnssUpdate?.disposition
        if (disposition == GnssObservationDisposition.REINITIALIZED) {
            walkingCalibrationSession.resetHistory()
        }
        val acceptedRawFix = disposition == GnssObservationDisposition.INITIALIZED ||
            disposition == GnssObservationDisposition.REINITIALIZED ||
            disposition == GnssObservationDisposition.APPLIED
        observeWalkingCalibrationFix(
            fix = RawWalkingCalibrationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                horizontalAccuracyM = raw.effectiveHorizontalAccuracyM,
                elapsedRealtimeMs = raw.elapsedRealtimeMs,
                receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
                speedMps = location.speedMps,
                speedAccuracyMps = location.speedAccuracyMps,
                courseDegrees = location.bearingDegrees,
                courseAccuracyDegrees = location.bearingAccuracyDegrees,
                trustedRawFix = acceptedRawFix && !location.mock,
            ),
            userMotion = userMotion,
            processingAtElapsedRealtimeMs = processingAtElapsedRealtimeMs,
        ).forEach(::applyWalkingCalibrationSample)
    }

    internal fun observeWalkingCalibrationFix(
        fix: RawWalkingCalibrationFix,
        userMotion: UserMotionStateSnapshot?,
        processingAtElapsedRealtimeMs: Long,
        coordinator: TimestampAlignedWalkingCalibration = calibrationSegments,
    ): List<WalkingCalibrationSample> {
        if (coordinator === calibrationSegments) synchronizeWalkingCalibrationScope()
        return walkingCalibrationSession.observeFix(fix, userMotion, processingAtElapsedRealtimeMs, coordinator)
            .also { if (coordinator === calibrationSegments) effects.refreshCalibration() }
    }

    fun applyWalkingCalibrationSample(sample: WalkingCalibrationSample) {
        val calibration = positioningCoordinator.calibrateProfile(sample)
        applyWalkingCalibrationResult(
            calibration = calibration,
            currentStepLengthM = { positioningCoordinator.currentProfile().stepLengthM.toFloat() },
            applyStepLength = { stepLengthM ->
                effects.updateStepLength(stepLengthM)
            },
            persistProfile = ::persistPositioningProfileForCurrentActor,
            updateStatus = effects::navigationStatus,
        )
        effects.refreshCalibration()
    }

    internal fun applyWalkingCalibrationResult(
        calibration: kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionCalibrationResult,
        currentStepLengthM: () -> Float,
        applyStepLength: (Float) -> Unit,
        persistProfile: () -> Unit,
        updateStatus: (String) -> Unit,
    ) {
        walkingCalibrationSession.applyResult(
            calibration, currentStepLengthM, applyStepLength, persistProfile, updateStatus,
        )
    }

    /** Diagnostic history belongs to an actor/walk, not each individual fix or history restart. */
    fun synchronizeWalkingCalibrationScope(
        actorId: String? = positioningProfileActorId,
        walkEpoch: WalkRuntimeEpoch? = if ((scope.lifecycle != null)) requireNotNull(scope.lifecycle).snapshot().epoch else null,
    ): WalkingCalibrationScope = walkingCalibrationSession.synchronizeScope(actorId, walkEpoch)

    fun walkingCalibrationScopeIsCurrent(scope: WalkingCalibrationScope): Boolean =
        walkingCalibrationSession.isCurrentScope(
            scope,
            positioningProfileActorId,
            GatewaySessionProcessCoordinator.snapshot().session?.actorId,
            this.scope.lifecycle?.snapshot()?.epoch,
        )

    fun capturePositioningMountObservation(
        target: PositioningMountTarget?, epoch: WalkRuntimeEpoch, observedAtElapsedRealtimeMs: Long,
        phase: PhoneMountingAssessmentPhase, previousState: PhoneMountingRuntimeState?,
        cameraFrameQualityOverride: CameraFrameQualityAssessment?, runtimeRetryRequested: Boolean,
        facts: MountingPositionFacts,
    ): PositioningMountObservation? {
        target ?: return null
        val confirmation = facts.confirmation
        val assessment = PhoneMountingPolicy.assess(phase, epoch, observedAtElapsedRealtimeMs,
            confirmation, cameraFrameQualityOverride ?: facts.cameraAssessment,
            previousState ?: PhoneMountingPolicy.initialState(epoch, mounting.profile),
            runtimeRetryRequested, checkRequest = null)
        return PositioningMountObservation(target, facts.observationGeneration, confirmation,
            facts.explicitConfirmation, assessment, observedAtElapsedRealtimeMs)
    }
    fun failLocationStopCleanupCheckpoint(checkpoints: WalkRuntimeStopCleanupCheckpoints): List<(Boolean) -> Unit> = fusedLocationCancellations.failLocationStopCleanupCheckpoint(checkpoints)
    fun resetStationaryState() {
        latestPositionStationaryState = PositionStationaryState.UNKNOWN
        latestPositionStationarySinceMs = null
    }
    fun closePedestrianMotionTracker() { pedestrianMotionTracker?.close(); pedestrianMotionTracker = null }
    fun dropTrustedCoordinate() { latestTrustedLocation = null }

}
