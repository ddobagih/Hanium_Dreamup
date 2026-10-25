package kr.co.hanium.dreamup.walksafe.navigation.session

import android.location.Location
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.device.WalkSessionDeviceResourceSnapshot
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*

internal interface PositioningActivityEffects {
    fun stopFieldSession(): Boolean
    fun resetMotionTelemetry()
    fun refreshCalibration()
    fun updateStepLength(stepLengthM: Float)
    fun navigationStatus(text: String)
    fun clearReportLocationStartSchedule()
    fun scheduleRouteWarmingReassessment() {}
    fun shouldAppendMotionTelemetry(nowMs: Long, force: Boolean): Boolean
    fun appendMotionTrace(snapshot: PositioningSnapshot?, stepDetected: Boolean, zuptApplied: Boolean, measurementElapsedRealtimeMs: Long)
    fun appendGnssTrace(snapshot: PositioningSnapshot, location: Location, matchedEvidenceCurrent: Boolean)
    fun refreshCompassPresentation()
    fun cleanupWarning(failure: RuntimeException)
}

internal interface GuidanceActivityScope {
    val lifecycle: WalkSessionLifecycle?
    val foreground: Boolean
    val runtimeActive: Boolean
    val navigationAllowed: Boolean
    val feedbackGeneration: Int
    val developmentBypass: Boolean
    fun feedbackCurrent(generation: Int): Boolean
}

internal interface GuidanceActivityEffects {
    fun beginArrival(token: RouteNavigatorDecisionToken): RouteGuidanceController.ArrivalCompletion?
    fun navigationStatus(text: String)
    fun interaction(message: String)
    fun decisionActions(decision: RouteNavigatorUserDecision?)
    fun progressBeep(nowMs: Long, offRoute: Boolean, arrived: Boolean)
    fun pausedHaptic()
    fun deviationHaptic()
    fun preparationStage(stage: String)
    fun clearWaitingFailure(current: () -> Boolean)
    fun showLastResult(message: String)
    fun unavailableLocationMessage(): String
}

internal interface ArchiveActivityFacts {
    fun gatewaySnapshot(): GatewaySessionProcessSnapshot
    fun lifecycleOrNull(): WalkSessionLifecycle?
    fun activityCurrent(): Boolean
    fun deletionBlocked(): Boolean
    fun deletionCoordinator(): AccountDeletionResetCoordinator
    fun deletionActivityLease(): AccountDeletionActivityLease
    fun deletionIntentToken(): Any
    fun developmentBypass(): Boolean
}

internal interface AcquisitionActivityFacts {
    val foreground: Boolean
    val navigationAllowed: Boolean
    val environmentOutputsAllowed: Boolean
    val runtimeActive: Boolean
    val lifecycle: WalkSessionLifecycle?
    val gateway: GatewaySessionProcessSnapshot
    val rawActorId: String?
    val actorId: String?
    fun sessionCurrent(session: GatewayFieldSession): Boolean
    val resources: WalkSessionDeviceResourceSnapshot
    val developmentBypass: Boolean
    val locationPermissionReady: Boolean
    val selectedDestination: DestinationSearchResult?
    val guidancePage: Boolean
    val startWaitViewReady: Boolean
    val stepCount: Int
    val permissionOwner: PermissionSessionPolicy
    val deletionOwner: AccountDeletionStateMachine
    val deletionActivityLease: AccountDeletionActivityLease
    val activityDestroyed: Boolean
}

internal interface AcquisitionActivityEffects {
    fun requireActor(reason: String): Boolean
    fun networkAllowed(reason: String): Boolean
    fun sessionOrNull(reason: String): GatewayFieldSession?
    fun explainUnavailable()
    fun requestLocationServices()
    fun setRoutePermissionRequested(requested: Boolean)
    fun invalidateStopConfirmation()
    fun cancelNavigationSpeech()
    fun updateDeviationActions(decision: RouteNavigatorUserDecision?)
    fun markTactileInactive()
    fun updateDestinationSearch()
    fun clearNativeSearchAndShowHome()
    fun renderMain()
    fun resetStartSpeechState(clearGuidanceState: () -> Unit)
    fun beginInstalledRoute(clearResume: () -> Unit): () -> Unit
    fun recordInstalledRoute(route: WalkingRoute, currentRouteId: () -> String?)
    fun showDestinationCoordinates(destination: RoutePoint)
    fun recordStage(stage: String)
    fun sessionNotReady(session: GatewayFieldSession)
    fun failureAdmission(session: GatewayFieldSession, authenticationFailure: Boolean): (() -> Boolean)?
}

/** Read on each source query, never captured as an actor/profile/epoch snapshot. */
internal interface NavigationGpsActorAuthority {
    val foreground: Boolean
    fun walkSnapshot(): WalkSessionSnapshot
    fun gatewaySnapshot(): GatewaySessionProcessSnapshot
    fun profileOrNull(): ApprovedOfficialEnvironmentProfile?
    val rawActorId: String?
    val onboardingInitialized: Boolean
    fun verifiedActorId(): String?
    fun deletionBlocked(): Boolean
}

internal interface CompassActivityAuthority {
    val foreground: Boolean
    val lifecycleReady: Boolean
    fun walkEpoch(): WalkRuntimeEpoch
    fun gatewaySnapshot(): GatewaySessionProcessSnapshot
    val feedbackGeneration: Int
    fun currentCollectionOwner(): LocationCollectionOwner?
    val navigationAllowed: Boolean
    val fieldAllowed: Boolean
}

internal class PositioningAssemblyBindings(
    val scope: () -> PositioningSessionController.Scope,
    val sources: () -> PositioningSessionController.Sources,
    val mounting: () -> PositioningSessionController.Mounting,
    val effects: () -> PositioningActivityEffects,
)
internal class GpsAssemblyBindings(
    val qualityGate: () -> RuntimeMeasuredQualityGate,
    val evidence: () -> ContinuousGpsSession.EvidenceWriter,
    val nativeCheckCurrent: (WalkRuntimeEpoch) -> Boolean,
    val prewalkEvidenceChanged: () -> Unit,
    val requestFresh: ((Location?) -> Unit) -> (() -> Unit),
)
internal class GuidanceAssemblyBindings(
    val scope: () -> GuidanceActivityScope,
    val stepCount: () -> Int,
    val stepLengthM: () -> Float,
    val speech: () -> RouteGuidanceController.SpeechPort,
    val effects: () -> GuidanceActivityEffects,
)
internal class CompassAssemblyBindings(
    val authority: () -> CompassActivityAuthority,
    val tracker: () -> RouteCompassSession.Tracker,
    val cancelSpeech: () -> Unit,
    val refreshPresentation: () -> Unit,
    val callbacks: () -> RouteCompassSession.Callbacks,
)
internal class AcquisitionAssemblyBindings(
    val facts: () -> AcquisitionActivityFacts,
    val effects: () -> AcquisitionActivityEffects,
    val backend: () -> RouteAcquisitionController.Backend,
    val navigationRequests: () -> AndroidNavigationRequestCoordinator,
    val destinationSearch: () -> DestinationSearchController,
    val worker: () -> Executor,
    val startWaitCallbacks: () -> RouteCallbackScheduler,
)
internal class NavigationAssemblyResources(
    val presentation: () -> RoutePresentation,
    val callbacks: () -> RouteCallbackScheduler,
    val installedStartSpeech: () -> InstalledRouteStartSpeechCoordinator,
    val lane: () -> RouteSnapshotIoLane,
    val diagnostics: () -> RouteGuidanceController.Diagnostics,
)
