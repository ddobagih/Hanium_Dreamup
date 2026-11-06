package kr.co.hanium.dreamup.walksafe.navigation.session

import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.RouteResponseInstallLocationStatus
import kr.co.hanium.dreamup.walksafe.device.WalkSessionDeviceResourceSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.ConsecutiveTmapFailureGuard
import kr.co.hanium.dreamup.walksafe.navigation.DestinationCoordinateEvidence
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchController
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.InstalledRouteStartSpeechCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.NavigationBackendErrorKind
import kr.co.hanium.dreamup.walksafe.navigation.PendingExplicitRouteStart
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUpdate
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.RouteSnapshotIoLane
import kr.co.hanium.dreamup.walksafe.navigation.RouteSnapshotScope
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteRequest
import kr.co.hanium.dreamup.walksafe.navigation.classifyNavigationBackendFailure
import kr.co.hanium.dreamup.walksafe.navigation.formatDestinationDistance
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationCancellation
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationRequestCoordinator
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationRequestEvent
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewayInRunRenewalPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidation
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidationStatus
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVerificationState
import kr.co.hanium.dreamup.walksafe.routeResponseInstallLocationStatus
import kr.co.hanium.dreamup.walksafe.routeResponseHasRecoverableCurrentConnection
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionActivityLease
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

/** Owns one explicit route request and its recovery intent, not Android lifecycle or speech engines. */
internal class RouteAcquisitionController(
    private val run: NavigationRunState,
    private val archive: RouteArchiveController,
    private val facts: Facts,
    private val effects: Effects,
    private val presentation: RoutePresentation,
    private val backend: Backend,
    private val navigationRequests: AndroidNavigationRequestCoordinator,
    private val destinationSearch: DestinationSearchController,
    private val installedStartSpeech: InstalledRouteStartSpeechCoordinator,
    private val worker: Executor,
    private val postToUi: (() -> Unit) -> Unit,
    private val startWaitCallbacks: RouteCallbackScheduler,
    private val recoveryCallbacks: RouteCallbackScheduler,
    private val elapsedRealtimeMs: () -> Long,
    private val wallTimeMs: () -> Long,
    private val lane: RouteSnapshotIoLane,
) {
    enum class RequestTerminalCause {
        OTHER, ENVIRONMENT_BEFORE_PROVIDER, PROVIDER_FAILURE_WITH_ENVIRONMENT,
        ARCHIVE_PREPARATION_WITH_VALID_RESPONSE,
    }

    interface Facts {
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
        val currentLocation: TrustedLocation?
        val latestTrustedLocation: TrustedLocation?
        val latestRawLocation: TrustedLocation?
        val positionPaused: Boolean
        val startWaitViewReady: Boolean
        val stepCount: Int
        val gpsSource: RecoverySource?
        val publishedGpsSourceKey: Any?
        val permissionOwner: PermissionSessionPolicy
        val deletionOwner: AccountDeletionStateMachine
        val deletionActivityLease: AccountDeletionActivityLease
        val activityDestroyed: Boolean
    }
    interface Backend {
        fun fetchRoute(session: GatewayFieldSession, request: WalkingRouteRequest): CancellableNetworkCall<WalkingRoute>
        fun revalidate(session: GatewayFieldSession, actorId: String?, capacitySessionGeneration: Long): GatewaySessionRevalidation
    }
    interface Effects {
        fun blockRouteMutation(): Boolean
        fun requireActor(reason: String): Boolean
        fun networkAllowed(reason: String): Boolean
        fun sessionOrNull(reason: String): GatewayFieldSession?
        fun explainUnavailable()
        fun requestLocationServices()
        fun setRoutePermissionRequested(requested: Boolean)
        fun invalidateStopConfirmation()
        fun cancelNavigationRetry()
        fun cancelLocationStatusSpeech()
        fun cancelNavigationSpeech()
        fun cancelDecisionSpeech()
        fun clearDeviationHaptic()
        fun updateDeviationActions(decision: RouteNavigatorUserDecision?)
        fun applyDeviationUpdate(update: RouteNavigatorUpdate): Boolean
        fun markTactileInactive()
        fun updateDestinationSearch()
        fun clearNativeSearchAndShowHome()
        fun renderMain()
        fun resetStartSpeechState()
        fun beginInstalledRoute(): () -> Unit
        fun recordInstalledRoute(route: WalkingRoute)
        fun showDestinationCoordinates(destination: RoutePoint)
        fun recordStage(stage: String)
        fun bindCompassOwner()
        fun updateGuidance(location: TrustedLocation, rawLocation: TrustedLocation)
        fun requestFreshGps()
        fun sessionNotReady(session: GatewayFieldSession)
        fun failureAdmission(session: GatewayFieldSession, authenticationFailure: Boolean): (() -> Boolean)?
    }
    /** Key retains the exact existing source equality, without exposing any Main/SDK binding. */
    data class RecoverySource(val key: Any, val epoch: WalkRuntimeEpoch,
        val gatewayGeneration: Long, val session: GatewayFieldSession?)

    // Presentation fact only: confirming arrival never ends the walk or admits new work.
    private data class ConfirmedArrival(
        val lifecycle: WalkSessionLifecycle,
        val epoch: WalkRuntimeEpoch,
        val session: GatewayFieldSession,
        val gatewayGeneration: Long,
        val destination: RoutePoint,
        val requestGeneration: Int,
    )
    private var confirmedArrival: ConfirmedArrival? = null
    private var installedArrivalOwner: ConfirmedArrival? = null
    private var arrivalIntentGeneration = 0L

    private fun retireConfirmedArrival() {
        confirmedArrival = null
        arrivalIntentGeneration += 1L
    }

    val hasCurrentConfirmedArrival: Boolean
        get() {
            val completed = confirmedArrival ?: return false
            return confirmedArrivalOwnerCurrent(completed) && !archive.purgeFailed &&
                !isRouteActive && !routeNavigator.hasRoute() && !routeRequestInFlight.get() &&
                pendingExplicitRouteStart.currentOrNull() == null && currentDestination == null
        }

    private fun confirmedArrivalOwnerCurrent(completed: ConfirmedArrival, committedEnding: Boolean = false): Boolean {
        val gateway = facts.gateway
        val session = gateway.session
        val sessionCurrent = if (committedEnding) {
            // Ending issues no new authority: use this snapshot once, including a renewal in progress.
            session != null && gateway.generation >= completed.gatewayGeneration &&
                session.verificationState == GatewaySessionVerificationState.VERIFIED &&
                session.sessionScope == GatewaySessionScope.GENERAL && session.isUsableFor(completed.session.actorId) &&
                (session === completed.session || GatewayInRunRenewalPolicy.isRenewedContinuation(completed.session, session))
        } else {
            gateway.generation == completed.gatewayGeneration && session === completed.session &&
                facts.sessionCurrent(completed.session)
        }
        return facts.foreground && !facts.activityDestroyed && facts.runtimeActive &&
            facts.lifecycle === completed.lifecycle && completed.lifecycle.isRuntimeEpochCurrent(completed.epoch) &&
            sessionCurrent &&
            !gateway.storageBlocked && !gateway.deletionRecoveryOnly &&
            facts.rawActorId == completed.session.actorId && facts.actorId == completed.session.actorId &&
            routeRequestGeneration == completed.requestGeneration
    }

    private fun detectedArrivalOwner(expectedToken: RouteNavigatorDecisionToken): ConfirmedArrival? {
        val installed = installedArrivalOwner ?: return null
        val gateway = facts.gateway
        val renewed = gateway.session?.takeIf {
            gateway.generation > installed.gatewayGeneration &&
                it.verificationState == GatewaySessionVerificationState.VERIFIED &&
                it.sessionScope == GatewaySessionScope.GENERAL &&
                it.isUsableFor(installed.session.actorId) &&
                GatewayInRunRenewalPolicy.isRenewedContinuation(installed.session, it)
        }
        val owner = installed.copy(requestGeneration = routeRequestGeneration,
            session = renewed ?: installed.session,
            gatewayGeneration = if (renewed != null) gateway.generation else installed.gatewayGeneration)
        return owner.takeIf { currentDestination == installed.destination &&
            isRouteActive && !routeRequestInFlight.get() && pendingExplicitRouteStart.currentOrNull() == null &&
            !archive.purgeFailed && confirmedArrivalOwnerCurrent(it) &&
            routeNavigator.canCompleteDetectedArrival(expectedToken) }
    }

    /** No route mutation: safety ownership must be retired before the destructive claim. */
    fun canCompleteDetectedArrival(expectedToken: RouteNavigatorDecisionToken): Boolean =
        detectedArrivalOwner(expectedToken) != null

    /** Only a current automatic arrival may retire the installed route and start walk termination. */
    fun completeDetectedArrival(
        expectedToken: RouteNavigatorDecisionToken,
        beforeCommit: () -> Boolean = { true },
    ): RouteNavigatorUpdate? {
        val owner = detectedArrivalOwner(expectedToken) ?: return null
        val intent = arrivalIntentGeneration
        val decision = routeNavigator.completeDetectedArrival(expectedToken) {
            confirmedArrivalOwnerCurrent(owner) && beforeCommit() &&
                currentDestination == owner.destination && isRouteActive &&
                !routeRequestInFlight.get() && pendingExplicitRouteStart.currentOrNull() == null &&
                confirmedArrivalOwnerCurrent(owner)
        }
        if (!decision.arrived) return null
        resetRouteState()
        // Reset ports may synchronously retire this walk or start a different destination.
        return decision.takeIf {
            arrivalIntentGeneration == intent + 1L && routeRequestGeneration == owner.requestGeneration + 1 &&
                !isRouteActive && !routeRequestInFlight.get() && currentDestination == null &&
                pendingExplicitRouteStart.currentOrNull() == null && !routeNavigator.hasRoute() &&
                confirmedArrivalOwnerCurrent(owner.copy(requestGeneration = routeRequestGeneration), committedEnding = true)
        }
    }

    /** Both spoken and visible confirmation use the navigator's existing exact-token authority. */
    fun confirmArrival(expectedToken: RouteNavigatorDecisionToken): RouteNavigatorUpdate {
        val gateway = facts.gateway
        val walk = facts.lifecycle
        val epoch = walk?.currentRuntimeEpochOrNull()
        val destination = currentDestination
        val owner = if (walk != null && epoch != null && gateway.session != null && destination != null) {
            ConfirmedArrival(walk, epoch, gateway.session, gateway.generation, destination, routeRequestGeneration)
        } else null
        val intent = arrivalIntentGeneration
        val decision = routeNavigator.confirmArrival(expectedToken)
        if (!decision.arrived) return decision
        resetRouteState()
        // Reset effects may synchronously start/cancel another intent, even without an HTTP request.
        if (owner != null && arrivalIntentGeneration == intent + 1L &&
            routeRequestGeneration == owner.requestGeneration + 1) {
            val completed = owner.copy(requestGeneration = routeRequestGeneration)
            if (confirmedArrivalOwnerCurrent(completed)) confirmedArrival = completed
        }
        return decision
    }

    private val routeRequestInFlight = AtomicBoolean(false)
    @Volatile private var routeRequestGeneration = 0
        set(value) {
            if (field != value) archive.invalidateRouteSnapshotResponseLease()
            field = value
        }
    private var activeRouteRequestReason: String? = null
    private var activeRouteRequestOwner: PendingRouteRequestOwner? = null
    private val pendingExplicitRouteStart = PendingExplicitRouteStart<ExplicitRouteStartContext>(ROUTE_START_LOCATION_TIMEOUT_MS)
    private var pendingExplicitRouteStartTimeout: Runnable? = null
    private var pendingExplicitRouteStartAfterFixMs: Long? = null
    private var pendingExplicitRouteStartRequestId: Int? = null
    private var continuingExplicitRouteStart: PendingExplicitRouteStart.Request<ExplicitRouteStartContext>? = null
    private var initialConnectionRecovery: InitialConnectionRecovery? = null
    private var initialConnectionRecoveryNotice: Runnable? = null
    private val initialRouteRetry = InitialRouteRetryEpisode(recoveryCallbacks, elapsedRealtimeMs, ::retryInitialRouteEpisode)
    private var suspendedNavigationResume: SuspendedNavigationResume? = null
    private var disconnectedRerouteRecovery: DisconnectedRerouteRecovery? = null
    private val tmapFailureGuard = ConsecutiveTmapFailureGuard(safetyStopThreshold = 2)

    val requestPending: Boolean get() = routeRequestInFlight.get()
    val requestGeneration: Int get() = routeRequestGeneration
    val requestReason: String? get() = activeRouteRequestReason
    val explicitStart: PendingExplicitRouteStart.Request<ExplicitRouteStartContext>? get() = pendingExplicitRouteStart.currentOrNull()
    fun clearDisconnectedRecovery() { disconnectedRerouteRecovery = null }
    fun clearResumeIntent() { suspendedNavigationResume = null }
    fun resetProviderFailureCount() = tmapFailureGuard.reset()

    private val routeNavigator get() = run.navigator
    private var currentDestination: RoutePoint?
        get() = run.destination
        set(value) { run.selectDestination(value) }
    private var isRouteActive: Boolean
        get() = run.active
        set(value) { if (value) run.markRequestActive() else run.markInactive() }
    private var latestTmapOnRoute: Boolean
        get() = run.onRoute
        set(value) { run.updateAcceptedRouteMatch(value) }
    private var directionGuidancePauseReason: String?
        get() = run.directionPauseReason
        set(value) { if (value == null) run.clearDirectionPause() else run.pauseDirection(value) }
    private var routeStartStepCount: Int?
        get() = run.startStepCount
        set(value) { run.recordRouteStartStep(value) }
    private val lifecycle get() = requireNotNull(facts.lifecycle)

    /** The caller consumes the actual resume token before invoking its original completion effect. */
    fun completeAdmittedResume(epoch: WalkRuntimeEpoch, publishCompletion: () -> Unit,
        requestNavigation: (RoutePoint, String, Boolean, DestinationCoordinateEvidence?) -> Unit = ::requestRoute) {
        retireConfirmedArrival()
        val resume = suspendedNavigationResume
        publishCompletion()
        if (resume != null && suspendedNavigationResume === resume && facts.foreground &&
            lifecycle.isRuntimeEpochCurrent(epoch) && resume.matches(epoch, routeRequestGeneration, currentDestination) &&
            navigationResumeSessionOrNull(resume.session)?.isUsableFor(resume.session.actorId) == true &&
            !isRouteActive && !routeRequestInFlight.get() && !routeNavigator.hasRoute()) {
            suspendedNavigationResume = resume.copy(approvedEpoch = epoch)
            requestNavigation(resume.destination, "walk_resume", true, resume.destinationCoordinateEvidence)
        }
    }

    internal data class ExplicitRouteStartContext(
        val walkEpoch: WalkRuntimeEpoch,
        // Gateway generation is the existing actor/session fence.
        val gatewayGeneration: Long,
        val destination: RoutePoint,
        val destinationCoordinateEvidence: DestinationCoordinateEvidence? = null,
        val waitForInitialLocationConnection: Boolean = false,
        val gatewaySession: GatewayFieldSession? = null,
        val selectedDestination: DestinationSearchResult? = null,
    )

    internal data class PendingRouteRequestOwner(
        val epoch: WalkRuntimeEpoch,
        val requestId: Int,
        val gatewayGeneration: Long,
        val session: GatewayFieldSession,
        val preserveExistingRoute: Boolean,
        val reason: String,
        val call: CancellableNetworkCall<*>,
        val initialExplicitStart: InitialExplicitRouteStart? = null,
    )

    internal data class InitialExplicitRouteStart(
        val destination: RoutePoint,
        val coordinateEvidence: DestinationCoordinateEvidence?,
        val originObservedAtMs: Long,
        val selectedDestination: DestinationSearchResult,
        val requestedAtElapsedRealtimeMs: Long,
    )

    /** Only a route actually installed in this walk may create this recovery intent. */
    private data class SuspendedNavigationResume(
        val epoch: WalkRuntimeEpoch,
        val routeGeneration: Int,
        val session: GatewayFieldSession,
        val destination: RoutePoint,
        val destinationCoordinateEvidence: DestinationCoordinateEvidence?,
        val approvedEpoch: WalkRuntimeEpoch? = null,
    ) {
        fun matches(currentEpoch: WalkRuntimeEpoch, generation: Int, currentDestination: RoutePoint?): Boolean =
            epoch.walkSessionId == currentEpoch.walkSessionId &&
                epoch.recoveryGeneration <= currentEpoch.recoveryGeneration &&
                routeGeneration == generation && destination == currentDestination
    }

    private data class DisconnectedRerouteRecovery(
        val source: RecoverySource,
        val requestId: Int,
        val routeVersion: Long?,
        val destination: RoutePoint,
        val coordinateEvidence: DestinationCoordinateEvidence?,
        val decisionToken: RouteNavigatorDecisionToken,
        val afterFixMs: Long,
        val requestedAtMs: Long,
    )

    private data class InitialConnectionRecovery(
        val context: ExplicitRouteStartContext,
        val requestedAtMs: Long,
        val startedAtMs: Long,
        var requestId: Int,
    )

    /** Coordinates GPS, pedestrian motion, and confidence before app consumers use a fix. */
    fun explicitRouteStartContextOrNull(
        destination: RoutePoint,
        destinationCoordinateEvidence: DestinationCoordinateEvidence? = null,
    ): ExplicitRouteStartContext? {
        if (!facts.foreground || !facts.navigationAllowed) return null
        return ownedExplicitRouteStartContextOrNull(destination, destinationCoordinateEvidence)
    }

    fun ownedExplicitRouteStartContextOrNull(
        destination: RoutePoint,
        destinationCoordinateEvidence: DestinationCoordinateEvidence?,
    ): ExplicitRouteStartContext? {
        if (!facts.foreground) return null
        val walkEpoch = lifecycle.currentRuntimeEpochOrNull() ?: return null
        val gateway = facts.gateway
        if (gateway.session == null || gateway.deletionRecoveryOnly || gateway.storageBlocked) {
            return null
        }
        if (currentDestination != destination) return null
        return ExplicitRouteStartContext(
            walkEpoch = walkEpoch,
            gatewayGeneration = gateway.generation,
            destination = destination,
            destinationCoordinateEvidence = destinationCoordinateEvidence,
            waitForInitialLocationConnection = facts.guidancePage &&
                facts.selectedDestination?.point == destination && !routeNavigator.hasRoute(),
            gatewaySession = gateway.session,
            selectedDestination = facts.selectedDestination,
        )
    }

    private fun explicitStartOwnerCurrent(context: ExplicitRouteStartContext): Boolean {
        val gateway = facts.gateway
        val session = context.gatewaySession ?: return false
        return facts.foreground && !facts.activityDestroyed && facts.locationPermissionReady &&
            lifecycle.isRuntimeEpochCurrent(context.walkEpoch) &&
            gateway.generation == context.gatewayGeneration && gateway.session === session &&
            !gateway.storageBlocked && !gateway.deletionRecoveryOnly &&
            facts.rawActorId == session.actorId && facts.actorId == session.actorId &&
            session.sessionScope == GatewaySessionScope.GENERAL &&
            session.verificationState == GatewaySessionVerificationState.VERIFIED &&
            session.isUsableFor(session.actorId, wallTimeMs()) && facts.sessionCurrent(session) &&
            currentDestination == context.destination &&
            (!context.waitForInitialLocationConnection || facts.guidancePage &&
                facts.selectedDestination === context.selectedDestination && !routeNavigator.hasRoute()) &&
            !facts.deletionOwner.processingBlocked() &&
            facts.deletionOwner.activityLeaseIsCurrent(facts.deletionActivityLease)
    }

    fun retainPendingExplicitRouteStart(
        destination: RoutePoint,
        destinationCoordinateEvidence: DestinationCoordinateEvidence?,
        waitForNewFixAfterMs: Long? = null,
    ): Boolean = retainPendingExplicitRouteStart(destination, destinationCoordinateEvidence,
        waitForNewFixAfterMs, this.elapsedRealtimeMs())

    private fun retainPendingExplicitRouteStart(
        destination: RoutePoint,
        destinationCoordinateEvidence: DestinationCoordinateEvidence?,
        waitForNewFixAfterMs: Long?,
        requestedAtElapsedRealtimeMs: Long,
    ): Boolean {
        val context = explicitRouteStartContextOrNull(destination, destinationCoordinateEvidence) ?: return false
        clearPendingExplicitRouteStart(clearConnectionRecovery = false)
        val request = pendingExplicitRouteStart.replace(
            context,
            requestedAtElapsedRealtimeMs,
            keepUntilCancelled = context.waitForInitialLocationConnection,
        )
        pendingExplicitRouteStartAfterFixMs = waitForNewFixAfterMs
        // A foreground request waiting for a trusted origin remains the user's intent.
        // It is fenced by the full walk epoch, actor/session, destination and page, not a new TTL.
        if (request.keepUntilCancelled) return true
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (pendingExplicitRouteStartTimeout !== timeout) return@Runnable
            pendingExplicitRouteStartTimeout = null
            val current = explicitRouteStartContextOrNull(
                context.destination,
                context.destinationCoordinateEvidence,
            )
            if (current == null) {
                pendingExplicitRouteStart.cancel()
                return@Runnable
            }
            if (!pendingExplicitRouteStart.expireIfCurrent(
                    request,
                    current,
                    this.elapsedRealtimeMs(),
                )
            ) return@Runnable
            val detail =
                "현재 위치와 길안내 준비를 완료하지 못했습니다. 선택한 목적지는 유지했습니다. " +
                    "위치 서비스를 확인한 뒤 안내 시작을 다시 선택하세요."
            presentation.navigationStatus(
                "navigation=route_waiting trusted_gps_missing timeout explicit_start_required",
            )
            presentation.status("현재 위치 확인 필요", detail)
            presentation.speakStatus(detail)
        }
        pendingExplicitRouteStartTimeout = timeout
        startWaitCallbacks.postDelayed(
            timeout,
            ROUTE_START_LOCATION_TIMEOUT_MS,
        )
        return true
    }

    fun continuePendingExplicitRouteStartIfReady() {
        val request = pendingExplicitRouteStart.currentOrNull() ?: return
        if (!explicitStartOwnerCurrent(request.context)) {
            clearPendingExplicitRouteStart()
            return
        }
        if (!facts.navigationAllowed) {
            // Archive preparation may finish during a transient GPS restriction. Retain only
            // the same initial human intent; this neither admits output nor extends other waits.
            val context = request.context
            val current = if (request.keepUntilCancelled && context.waitForInitialLocationConnection) {
                ownedExplicitRouteStartContextOrNull(context.destination, context.destinationCoordinateEvidence)
            } else null
            val gateway = facts.gateway
            val session = gateway.session
            val sameInitialOwner = current == context &&
                gateway.generation == context.gatewayGeneration &&
                !gateway.storageBlocked && !gateway.deletionRecoveryOnly &&
                pendingExplicitRouteStartRequestId?.let { it != routeRequestGeneration } != true &&
                !request.isExpired(elapsedRealtimeMs(), ROUTE_START_LOCATION_TIMEOUT_MS) &&
                session != null && session.sessionScope == GatewaySessionScope.GENERAL &&
                session.verificationState == GatewaySessionVerificationState.VERIFIED &&
                facts.rawActorId == session.actorId && facts.actorId == session.actorId &&
                session.isUsableFor(session.actorId, wallTimeMs()) && facts.sessionCurrent(session) &&
                !facts.activityDestroyed && !facts.deletionOwner.processingBlocked() &&
                facts.deletionOwner.activityLeaseIsCurrent(facts.deletionActivityLease)
            if (!sameInitialOwner) clearPendingExplicitRouteStart()
            return
        }
        continueAdmittedExplicitRouteStartIfReady()
    }

    /** The caller has admitted SDK collection; ownership, pending TTL and route fences remain here. */
    internal fun continueAdmittedExplicitRouteStartIfReady(
        elapsedRealtimeMs: () -> Long = this.elapsedRealtimeMs,
        trustedLocation: () -> TrustedLocation? = { facts.currentLocation },
        requestNavigation: (RoutePoint, String, Boolean, DestinationCoordinateEvidence?) -> Unit = ::requestRoute,
    ) {
        val request = pendingExplicitRouteStart.currentOrNull() ?: return
        if (!explicitStartOwnerCurrent(request.context) ||
            pendingExplicitRouteStartRequestId?.let { it != routeRequestGeneration } == true) {
            clearPendingExplicitRouteStart()
            return
        }
        val current = ownedExplicitRouteStartContextOrNull(
            request.context.destination,
            request.context.destinationCoordinateEvidence,
        )
        if (current == null) {
            clearPendingExplicitRouteStart()
            return
        }
        if (expireInitialRouteRecovery(routeRequestGeneration) ||
            !initialRouteRetry.eligibleAt(elapsedRealtimeMs())) return
        if (!archive.routeSnapshotPurgeFenceAllowsRoute()) return
        val location = trustedLocation() ?: return
        if (routeRequestInFlight.get() || facts.positionPaused ||
            LocationTrustPolicy.freshOrNull(location, elapsedRealtimeMs()) == null ||
            LocationTrustPolicy.trustedOrNull(location.latitude, location.longitude,
                location.accuracyM, location.elapsedRealtimeMs) == null ||
            pendingExplicitRouteStartAfterFixMs?.let {
                location.elapsedRealtimeMs <= it
            } == true
        ) return
        val retained = pendingExplicitRouteStart.consumeIfCurrent(
            request,
            current,
            elapsedRealtimeMs(),
        ) ?: return
        pendingExplicitRouteStartTimeout?.let(startWaitCallbacks::remove)
        pendingExplicitRouteStartTimeout = null
        pendingExplicitRouteStartAfterFixMs = null
        pendingExplicitRouteStartRequestId = null
        initialRouteRetry.cancelTimer()
        val generationBefore = routeRequestGeneration
        continuingExplicitRouteStart = request
        try {
            requestNavigation(
                retained.destination,
                resumedNavigationRequestReason(retained.walkEpoch, retained.gatewayGeneration,
                    retained.destination, retained.destinationCoordinateEvidence),
                retained.waitForInitialLocationConnection,
                retained.destinationCoordinateEvidence,
            )
        } finally {
            continuingExplicitRouteStart = null
        }
        if (routeRequestGeneration != generationBefore && routeRequestInFlight.get()) {
            if (facts.guidancePage) {
                effects.renderMain()
            } else {
                effects.clearNativeSearchAndShowHome()
            }
        }
    }

    fun clearPendingExplicitRouteStart() = clearPendingExplicitRouteStart(clearConnectionRecovery = true)

    private fun clearPendingExplicitRouteStart(clearConnectionRecovery: Boolean) {
        if (clearConnectionRecovery) {
            clearInitialConnectionRecovery()
            clearTransientRouteRecovery()
        }
        pendingExplicitRouteStart.cancel()
        if (facts.startWaitViewReady) {
            pendingExplicitRouteStartTimeout?.let(
                startWaitCallbacks::remove,
            )
        }
        pendingExplicitRouteStartTimeout = null
        pendingExplicitRouteStartAfterFixMs = null
        pendingExplicitRouteStartRequestId = null
    }

    private fun clearInitialConnectionRecovery() {
        initialConnectionRecovery = null
        initialConnectionRecoveryNotice?.let(recoveryCallbacks::remove)
        initialConnectionRecoveryNotice = null
    }

    private fun clearTransientRouteRecovery() = initialRouteRetry.clear()

    private fun retainTransientRouteRecovery(
        owner: PendingRouteRequestOwner,
        rejectedAfterFixMs: Long?,
        metadata: String,
        explanation: String,
        cancelledForPendingRevalidation: Boolean = false,
    ): Boolean {
        val explicit = owner.initialExplicitStart ?: return false
        // Only the identified PENDING pre-provider branch may retire its transport and retain the
        // captured human start. Transport cancellation alone never creates an intent.
        if (cancelledForPendingRevalidation &&
            initialRouteRetry.current?.let { it.requestId != owner.requestId } == true) return false
        if (owner.preserveExistingRoute || owner.reason != "user_destination" ||
            owner.call.isCancelled() && !cancelledForPendingRevalidation ||
            routeRequestInFlight.get() || routeRequestGeneration != owner.requestId ||
            facts.gateway.generation != owner.gatewayGeneration || facts.gateway.session !== owner.session ||
            !lifecycle.isRuntimeEpochCurrent(owner.epoch) || archive.purgeFailed ||
            archive.resetParticipant != null || archive.leaseOrNull?.let(archive::routeSnapshotActivityIsCurrent) != true
        ) return false
        val context = ownedExplicitRouteStartContextOrNull(explicit.destination, explicit.coordinateEvidence)
            ?.takeIf(::explicitStartOwnerCurrent) ?: return false
        if (!context.waitForInitialLocationConnection || context.selectedDestination !== explicit.selectedDestination) return false
        val previous = initialRouteRetry.current
        val now = elapsedRealtimeMs()
        val recovery = initialRouteRetry.retainFailure(context, explicit.requestedAtElapsedRealtimeMs,
            owner.requestId, now) ?: return false
        if (recovery.exhaustedAt(now)) {
            stopInitialRouteRecovery(recovery)
            return true
        }
        clearPendingExplicitRouteStart(clearConnectionRecovery = false)
        pendingExplicitRouteStart.replace(context, explicit.requestedAtElapsedRealtimeMs, keepUntilCancelled = true)
        pendingExplicitRouteStartRequestId = owner.requestId
        pendingExplicitRouteStartAfterFixMs = rejectedAfterFixMs?.let { maxOf(explicit.originObservedAtMs, it) }
        presentation.updateRouteButton()
        presentation.navigationStatus("navigation=route_waiting $metadata automatic_retry_pending")
        if (previous == null) {
            val detail = "$explanation 목적지는 유지했습니다. 연결과 위치를 확인하며 자동으로 다시 요청합니다."
            presentation.status("안내 연결 복구 중", detail)
            presentation.speakStatus(detail)
        }
        effects.requestFreshGps()
        initialRouteRetry.schedule(recovery, maxOf(0L, recovery.retryAtMs - now))
        return true
    }

    private fun retryInitialRouteEpisode(recovery: InitialRouteRetryEpisode.Episode): Boolean {
        val now = elapsedRealtimeMs()
        if (!explicitStartOwnerCurrent(recovery.context) || routeRequestGeneration != recovery.requestId ||
            now < recovery.firstFailureAtMs || archive.purgeFailed) {
            clearPendingExplicitRouteStart()
            return false
        }
        if (recovery.exhaustedAt(now)) {
            stopInitialRouteRecovery(recovery)
            return false
        }
        effects.requestFreshGps()
        continuePendingExplicitRouteStartIfReady()
        return pendingExplicitRouteStart.currentOrNull() != null && !routeRequestInFlight.get()
    }

    /** Retires only this failed initial intent, including an HTTP call still awaiting a response. */
    private fun stopInitialRouteRecovery(recovery: InitialRouteRetryEpisode.Episode): Boolean {
        if (initialRouteRetry.current !== recovery || routeRequestGeneration != recovery.requestId ||
            !explicitStartOwnerCurrent(recovery.context) || routeNavigator.hasRoute()) return false
        val owner = activeRouteRequestOwner?.takeIf { it.requestId == recovery.requestId }
        // Fence late worker/UI completion before cancelling the exact old transport.
        invalidateActiveRouteRequestLease()
        activeRouteRequestOwner = null
        isRouteActive = false
        latestTmapOnRoute = false
        currentDestination = null
        val retiredGeneration = routeRequestGeneration
        owner?.let { navigationRequests.completeRoute(it.call); it.call.cancel() }
        // A cancellation callback may already have admitted a new intent or ended this walk.
        if (routeRequestGeneration != retiredGeneration || !facts.foreground || facts.activityDestroyed ||
            !lifecycle.isRuntimeEpochCurrent(recovery.context.walkEpoch) ||
            recovery.context.gatewaySession?.let(facts::sessionCurrent) != true) return true
        val detail = "경로 연결을 여러 번 시도했지만 완료하지 못했습니다. 선택한 목적지는 유지했습니다. " +
            "네트워크와 위치 서비스를 확인한 뒤 안내 시작을 다시 선택해 주세요."
        presentation.updateRouteButton()
        presentation.navigationStatus("navigation=route_failed automatic_retry_limit explicit_start_required")
        presentation.status("길안내 연결 확인 필요", detail)
        presentation.speakStatus(detail)
        return true
    }

    private fun expireInitialRouteRecovery(requestId: Int): Boolean {
        val recovery = initialRouteRetry.current ?: return false
        return recovery.requestId == requestId && recovery.exhaustedAt(elapsedRealtimeMs()) &&
            stopInitialRouteRecovery(recovery)
    }

    private fun retainInitialConnectionRecovery(
        owner: PendingRouteRequestOwner,
        afterFixMs: Long,
    ): Boolean {
        val explicit = owner.initialExplicitStart ?: return false
        if (owner.preserveExistingRoute || owner.reason != "user_destination" ||
            routeRequestInFlight.get() || routeRequestGeneration != owner.requestId ||
            owner.call.isCancelled() || facts.selectedDestination !== explicit.selectedDestination ||
            facts.gateway.session !== owner.session || facts.gateway.generation != owner.gatewayGeneration ||
            !lifecycle.isRuntimeEpochCurrent(owner.epoch)
        ) return false
        if (!retainPendingExplicitRouteStart(explicit.destination, explicit.coordinateEvidence,
                waitForNewFixAfterMs = afterFixMs,
                requestedAtElapsedRealtimeMs = explicit.requestedAtElapsedRealtimeMs)) return false
        val request = requireNotNull(pendingExplicitRouteStart.currentOrNull())
        if (!explicitStartOwnerCurrent(request.context)) {
            clearPendingExplicitRouteStart()
            return false
        }
        pendingExplicitRouteStartRequestId = owner.requestId
        val previousRecovery = initialConnectionRecovery
        if (previousRecovery != null && (previousRecovery.context != request.context ||
                previousRecovery.requestedAtMs != request.requestedAtElapsedRealtimeMs)) {
            clearPendingExplicitRouteStart()
            return false
        }
        if (initialConnectionRecovery == null) {
            val recovery = InitialConnectionRecovery(request.context, request.requestedAtElapsedRealtimeMs,
                elapsedRealtimeMs(), owner.requestId)
            initialConnectionRecovery = recovery
            lateinit var notice: Runnable
            notice = Runnable {
                if (initialConnectionRecoveryNotice !== notice || initialConnectionRecovery !== recovery) return@Runnable
                initialConnectionRecoveryNotice = null
                if (!explicitStartOwnerCurrent(recovery.context) ||
                    routeRequestGeneration != recovery.requestId || routeNavigator.hasRoute()) {
                    clearPendingExplicitRouteStart()
                    return@Runnable
                }
                val detail = "현재 위치와 경로 연결을 계속 확인하고 있습니다. 목적지는 유지했습니다. " +
                    "위치 서비스와 GPS 수신 상태를 확인해 주세요. 새 위치가 확인되면 안내를 이어갑니다."
                presentation.navigationStatus("navigation=route_waiting current_connection_missing start_request_pending")
                presentation.status("경로 연결 확인 중", detail)
                presentation.speakStatus(detail)
                effects.requestFreshGps()
            }
            initialConnectionRecoveryNotice = notice
            recoveryCallbacks.postDelayed(notice,
                maxOf(0L, 3_000L - (elapsedRealtimeMs() - recovery.startedAtMs)))
            presentation.status("경로 연결 확인 중", "목적지는 유지했습니다. 새 위치로 경로 연결을 다시 확인합니다.")
        }
        presentation.updateRouteButton()
        presentation.navigationStatus("navigation=route_waiting current_connection_missing start_request_pending")
        effects.requestFreshGps()
        return true
    }

    internal fun cancelNavigationRequestsForDestinationSelection(): AndroidNavigationCancellation {
        retireConfirmedArrival()
        clearPendingExplicitRouteStart()
        disconnectedRerouteRecovery = null
        suspendedNavigationResume = null
        effects.cancelNavigationRetry()
        effects.cancelLocationStatusSpeech()
        effects.cancelDecisionSpeech()
        return navigationRequests.handle(AndroidNavigationRequestEvent.DESTINATION_SELECTED)
    }

    internal fun cancelNavigationRequestsForPause(): AndroidNavigationCancellation {
        retireConfirmedArrival()
        clearPendingExplicitRouteStart()
        disconnectedRerouteRecovery = null
        effects.cancelNavigationRetry()
        effects.cancelLocationStatusSpeech()
        effects.cancelDecisionSpeech()
        return navigationRequests.handle(AndroidNavigationRequestEvent.ACTIVITY_PAUSED)
    }

    internal fun cancelNavigationRequestsForDestroy(): AndroidNavigationCancellation {
        retireConfirmedArrival()
        clearPendingExplicitRouteStart()
        disconnectedRerouteRecovery = null
        effects.cancelNavigationRetry()
        effects.cancelLocationStatusSpeech()
        effects.cancelDecisionSpeech()
        return navigationRequests.handle(AndroidNavigationRequestEvent.ACTIVITY_DESTROYED)
    }

    fun cancelGatewayNetworkCalls() {
        retireConfirmedArrival()
        clearPendingExplicitRouteStart()
        navigationRequests.handle(AndroidNavigationRequestEvent.GATEWAY_SESSION_CLEARED)
    }

    fun clearGatewayNavigationState() {
        retireConfirmedArrival()
        installedArrivalOwner = null
        clearPendingExplicitRouteStart()
        suspendedNavigationResume = null
        // Account clear applies in PAUSED/SAFE_STOP/ENDED as well as ACTIVE.
        archive.reconcileRouteSnapshotAccount(facts.gateway)
        effects.invalidateStopConfirmation()
        effects.cancelNavigationRetry()
        effects.cancelDecisionSpeech()
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
        destinationSearch.retirePending()
        isRouteActive = false
        latestTmapOnRoute = false
        effects.markTactileInactive()
        currentDestination = null
        effects.setRoutePermissionRequested(false)
        routeNavigator.clear()
        effects.cancelLocationStatusSpeech()
        effects.cancelNavigationSpeech()
        routeStartStepCount = null
        destinationSearch.clearResults()
        effects.updateDestinationSearch()
        presentation.updateRouteButton()
    }

    fun resetRouteState(purgeRouteSnapshot: Boolean = true) {
        retireConfirmedArrival()
        installedArrivalOwner = null
        suspendedNavigationResume = null
        effects.invalidateStopConfirmation()
        effects.cancelNavigationRetry()
        clearPendingExplicitRouteStart()
        installedStartSpeech.clear()
        effects.resetStartSpeechState()
        navigationRequests.cancelRoute()
        isRouteActive = false
        latestTmapOnRoute = false
        effects.markTactileInactive()
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
        currentDestination = null
        effects.setRoutePermissionRequested(false)
        routeNavigator.clear()
        effects.cancelLocationStatusSpeech()
        effects.cancelNavigationSpeech()
        if (purgeRouteSnapshot) archive.purgeEncryptedRouteSnapshot()
        effects.cancelDecisionSpeech()
        effects.clearDeviationHaptic()
        effects.updateDeviationActions(null)
        routeStartStepCount = null
        destinationSearch.clearResults()
        destinationSearch.clearVoicePage()
        effects.updateDestinationSearch()
        presentation.updateRouteButton()
        presentation.navigationStatus(
            if (archive.purgeFailed) {
                "navigation=route_snapshot_purge_failed"
            } else {
                "navigation=destination_none hazard_only"
            },
        )
    }

    fun cancelActiveRouteRequest(preserveExistingRoute: Boolean = true) {
        retireConfirmedArrival()
        clearPendingExplicitRouteStart()
        effects.invalidateStopConfirmation()
        clearActiveRouteRequestState(
            navigationRequests.cancelRoute(),
            preserveExistingRoute = preserveExistingRoute,
        )
    }

    fun clearActiveRouteRequestState(
        transportWasActive: Boolean,
        preserveExistingRoute: Boolean = false,
    ) {
        if (!routeRequestInFlight.get() && !transportWasActive) return
        invalidateActiveRouteRequestLease()
        if (preserveExistingRoute && isRouteActive && routeNavigator.hasRoute()) {
            routeNavigator.rerouteRequestFailed()?.let(effects::applyDeviationUpdate)
            effects.setRoutePermissionRequested(false)
            presentation.updateRouteButton()
            presentation.navigationStatus("navigation=route_request_cancelled existing_route_retained")
            return
        }
        isRouteActive = false
        latestTmapOnRoute = false
        effects.markTactileInactive()
        currentDestination = null
        effects.setRoutePermissionRequested(false)
        routeNavigator.clear()
        effects.cancelNavigationRetry()
        effects.cancelLocationStatusSpeech()
        effects.cancelNavigationSpeech()
        archive.purgeEncryptedRouteSnapshot()
        effects.cancelDecisionSpeech()
        effects.clearDeviationHaptic()
        effects.updateDeviationActions(null)
        routeStartStepCount = null
        presentation.updateRouteButton()
        presentation.navigationStatus(
            if (archive.purgeFailed) {
                "navigation=route_snapshot_purge_failed"
            } else {
                "navigation=route_request_cancelled hazard_only"
            },
        )
    }

    /** Invalidates a completed response even when its UI installation has not run yet. */
    internal fun invalidateActiveRouteRequestLease() {
        clearPendingExplicitRouteStart()
        suspendedNavigationResume = null
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
    }

    fun navigationResumeSessionOrNull(previous: GatewayFieldSession? = null): GatewayFieldSession? {
        val snapshot = facts.gateway
        val session = snapshot.session ?: return null
        if (snapshot.storageBlocked || snapshot.deletionRecoveryOnly || facts.rawActorId != session.actorId ||
            session.verificationState != GatewaySessionVerificationState.VERIFIED ||
            session.sessionScope != GatewaySessionScope.GENERAL ||
            (previous != null && session !== previous &&
                !GatewayInRunRenewalPolicy.isRenewedContinuation(previous, session))
        ) return null
        return session
    }

    /** Preserve provenance through the existing, consumed GPS-wait context, never by reason alone. */
    internal fun resumedNavigationRequestReason(
        epoch: WalkRuntimeEpoch,
        gatewayGeneration: Long,
        destination: RoutePoint,
        evidence: DestinationCoordinateEvidence?,
    ): String {
        val resume = suspendedNavigationResume ?: return "user_destination"
        return if (resume.approvedEpoch == epoch && lifecycle.isRuntimeEpochCurrent(epoch) &&
            resume.matches(epoch, routeRequestGeneration, destination) && currentDestination == destination &&
            resume.destinationCoordinateEvidence == evidence &&
            facts.gateway.generation == gatewayGeneration &&
            navigationResumeSessionOrNull(resume.session)?.isUsableFor(resume.session.actorId) == true
        ) "walk_resume" else "user_destination"
    }

    /** Called only after requestRoute wins its loading CAS and allocates this exact request id. */
    internal fun bindResumedNavigationRequest(
        epoch: WalkRuntimeEpoch,
        requestId: Int,
        session: GatewayFieldSession,
        destination: RoutePoint,
        reason: String,
    ) {
        val resume = suspendedNavigationResume ?: return
        if (reason != "walk_resume" || resume.approvedEpoch != epoch ||
            !lifecycle.isRuntimeEpochCurrent(epoch) || !routeRequestInFlight.get() ||
            routeRequestGeneration != requestId || !resume.matches(epoch, requestId - 1, destination) ||
            currentDestination != destination || navigationResumeSessionOrNull(resume.session) !== session
        ) return
        suspendedNavigationResume = resume.copy(routeGeneration = requestId, session = session)
    }

    fun suspendNavigationForRecovery(
        cancellation: AndroidNavigationCancellation,
    ) {
        retireConfirmedArrival()
        val retainedDestination = currentDestination
        val pausedEpoch = if ((facts.lifecycle != null)) {
            lifecycle.snapshot().takeIf { it.state == WalkSessionState.PAUSED }?.epoch
        } else null
        val session = navigationResumeSessionOrNull()
        val previous = suspendedNavigationResume
        suspendedNavigationResume = if (pausedEpoch == null || session == null || retainedDestination == null) {
            null
        } else if (isRouteActive && routeNavigator.hasRoute()) {
            SuspendedNavigationResume(pausedEpoch, routeRequestGeneration + 1, session, retainedDestination,
                routeNavigator.destinationCoordinateEvidenceFor(retainedDestination)?.let {
                    it.copy(candidates = it.candidates.toList())
                })
        } else if (previous != null && previous.matches(pausedEpoch, routeRequestGeneration, retainedDestination) &&
            navigationResumeSessionOrNull(previous.session) === session
        ) {
            // Repeated pause, GPS wait, or this intent's exact pending HTTP request.
            previous.copy(epoch = pausedEpoch, routeGeneration = routeRequestGeneration + 1,
                session = session, approvedEpoch = null)
        } else null
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
        destinationSearch.retirePending()
        isRouteActive = false
        latestTmapOnRoute = false
        effects.markTactileInactive()
        effects.setRoutePermissionRequested(false)
        routeNavigator.clear()
        effects.cancelNavigationRetry()
        effects.cancelLocationStatusSpeech()
        effects.cancelNavigationSpeech()
        effects.cancelDecisionSpeech()
        effects.clearDeviationHaptic()
        effects.updateDeviationActions(null)
        routeStartStepCount = null
        currentDestination = retainedDestination
        destinationSearch.clearResults()
        effects.updateDestinationSearch()
        presentation.updateRouteButton()
        presentation.navigationStatus(
            "navigation=route_revalidation_required " +
                "route_cancelled=${cancellation.routeCancelled} " +
                "search_cancelled=${cancellation.destinationSearchCancelled}",
        )
    }

    /** UI admission and coordinate parsing are complete; the request owner prepares the selection. */
    fun requestUserDestination(destination: RoutePoint) {
        retireConfirmedArrival()
        if (destinationSearch.state.inFlight || navigationRequests.hasActiveDestinationSearch()) destinationSearch.cancel()
        run.selectPendingDestination(destination)
        presentation.updateRouteButton()
        requestRoute(destination, reason = "user_destination")
    }

    fun onDestinationSelected(
        result: DestinationSearchResult,
        retainExplicitStartUntilTrustedLocation: Boolean = false,
    ): Boolean {
        retireConfirmedArrival()
        if (!facts.navigationAllowed) {
            effects.explainUnavailable()
            return false
        }
        if (effects.blockRouteMutation()) return false
        val cancellation = cancelNavigationRequestsForDestinationSelection()
        destinationSearch.clearRequest(cancellation.destinationSearchCancelled)
        if (!effects.requireActor("login_required_route_select")) return false
        val preserveExistingRoute = isRouteActive && routeNavigator.hasRoute()
        if (routeRequestInFlight.get() || cancellation.routeCancelled) {
            clearActiveRouteRequestState(
                cancellation.routeCancelled,
                preserveExistingRoute = preserveExistingRoute,
            )
        }
        if (!preserveExistingRoute) {
            effects.showDestinationCoordinates(result.point)
            run.selectPendingDestination(result.point)
        }
        presentation.updateRouteButton()
        presentation.navigationStatus("navigation=destination_selected ${result.name} ${formatDestinationDistance(result.distanceM)}")
        val routeRequestGenerationBefore = routeRequestGeneration
        requestRoute(
            result.point,
            reason = "user_destination",
            retainExplicitStartUntilTrustedLocation =
                retainExplicitStartUntilTrustedLocation,
            destinationCoordinateEvidence = result.coordinateEvidence,
        )
        return routeRequestGeneration != routeRequestGenerationBefore &&
            routeRequestInFlight.get()
    }

    /** Checked when the completed HTTP response's UI work actually runs, not when it is posted. */
    internal fun routeResponseInstallLeaseCurrent(
        expectedRequestId: Int,
        isCurrentRequest: () -> Boolean,
    ): Boolean = routeRequestInFlight.get() &&
        routeRequestGeneration == expectedRequestId && isCurrentRequest()

    /** The caller has checked request identity; this consumes its actual output-gate observation. */
    internal fun routeResponseEnvironmentCurrent(
        successfulResponseReceived: Boolean,
        rejectedAfterFixMs: AtomicReference<Long?>,
        originObservedAtMs: Long,
        outputsAllowed: Boolean,
    ): Boolean {
        // An admission refusal is terminal for this response, even if GPS recovers before UI work.
        if (rejectedAfterFixMs.get() != null) return false
        if (!outputsAllowed) {
            if (routeRequestInFlight.get() &&
                (successfulResponseReceived || activeRouteRequestOwner?.initialExplicitStart != null)) {
                rejectedAfterFixMs.compareAndSet(null, maxOf(originObservedAtMs,
                    facts.latestTrustedLocation?.elapsedRealtimeMs ?: originObservedAtMs,
                    facts.latestRawLocation?.elapsedRealtimeMs ?: originObservedAtMs))
            }
            return false
        }
        return true
    }

    /** Only the just-retired, already-approved midwalk request can acquire this one-use ticket. */
    internal fun retainDisconnectedRerouteForFreshLocation(
        epoch: WalkRuntimeEpoch,
        requestId: Int,
        gatewayGeneration: Long,
        session: GatewayFieldSession,
        destination: RoutePoint,
        coordinateEvidence: DestinationCoordinateEvidence?,
        reason: String,
        preserveExistingRoute: Boolean,
        connectionRecoveryAttempted: Boolean,
        status: RouteResponseInstallLocationStatus,
        originObservedAtMs: Long,
        rejectedFixAtMs: Long?,
        elapsedRealtimeMs: () -> Long = this.elapsedRealtimeMs,
        requestFresh: () -> Unit = { effects.requestFreshGps() },
        scheduleTimeout: (Runnable, Long) -> Unit = { task, delay -> recoveryCallbacks.postDelayed(task, delay); Unit },
        publishFailure: (String) -> Unit = ::retainRouteAfterRerouteFailure,
    ): Boolean {
        if (status != RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED || reason != "off_route" ||
            !preserveExistingRoute || connectionRecoveryAttempted || routeRequestInFlight.get() ||
            routeRequestGeneration != requestId || !isRouteActive || !routeNavigator.hasRoute() ||
            currentDestination != destination || !lifecycle.isRuntimeEpochCurrent(epoch)) return false
        val source = facts.gpsSource ?: return false
        if (source.key != facts.publishedGpsSourceKey || source.epoch != epoch ||
            source.gatewayGeneration != gatewayGeneration || source.session !== session) return false
        if (routeNavigator.pendingUserDecision() != RouteNavigatorUserDecision.REROUTE) return false
        val token = routeNavigator.pendingDecisionToken() ?: return false
        val nowMs = elapsedRealtimeMs()
        if (nowMs < 0L || originObservedAtMs < 0L || originObservedAtMs > nowMs ||
            rejectedFixAtMs?.let { it < 0L || it > nowMs } == true) return false
        val ticket = DisconnectedRerouteRecovery(source, requestId,
            routeNavigator.currentTactileRouteProgress().routeVersion, destination,
            coordinateEvidence?.copy(candidates = coordinateEvidence.candidates.toList()), token,
            maxOf(originObservedAtMs, rejectedFixAtMs ?: originObservedAtMs), nowMs)
        disconnectedRerouteRecovery = ticket
        requestFresh()
        if (disconnectedRerouteRecovery === ticket) scheduleTimeout(Runnable {
            if (disconnectedRerouteRecovery !== ticket) return@Runnable
            val current = disconnectedRerouteRecoveryIsCurrent(ticket)
            disconnectedRerouteRecovery = null
            if (current) publishFailure("새 경로를 현재 위치에 연결하지 못했습니다. 기존 목적지와 재탐색 선택을 유지합니다.")
        }, 3_000L)
        return true
    }

    private fun disconnectedRerouteRecoveryIsCurrent(ticket: DisconnectedRerouteRecovery): Boolean =
        disconnectedRerouteRecovery === ticket && facts.gpsSource == ticket.source &&
            facts.publishedGpsSourceKey == ticket.source.key && lifecycle.isRuntimeEpochCurrent(ticket.source.epoch) &&
            routeRequestGeneration == ticket.requestId && !routeRequestInFlight.get() && isRouteActive &&
            currentDestination == ticket.destination && routeNavigator.hasRoute() &&
            routeNavigator.currentTactileRouteProgress().routeVersion == ticket.routeVersion &&
            routeNavigator.pendingUserDecision() == RouteNavigatorUserDecision.REROUTE &&
            routeNavigator.pendingDecisionToken() == ticket.decisionToken

    fun continueDisconnectedRerouteRecoveryIfReady() {
        if (disconnectedRerouteRecovery == null || !facts.navigationAllowed) return
        continueAdmittedDisconnectedRerouteRecovery()
    }

    /** Full source/route/choice ownership is still checked after SDK collection admission. */
    internal fun continueAdmittedDisconnectedRerouteRecovery(
        elapsedRealtimeMs: () -> Long = this.elapsedRealtimeMs,
        currentLocation: () -> TrustedLocation? = { facts.currentLocation },
        requestRouteAgain: (RoutePoint, DestinationCoordinateEvidence?) -> Unit = { destination, evidence ->
            requestRoute(destination, "off_route", false, evidence, connectionRecoveryAttempted = true)
            if (!routeRequestInFlight.get() && routeNavigator.hasRoute()) {
                retainRouteAfterRerouteFailure("새 경로 요청을 완료하지 못했습니다. 기존 목적지와 재탐색 선택을 유지합니다.")
            }
        },
    ) {
        val ticket = disconnectedRerouteRecovery ?: return
        val nowMs = elapsedRealtimeMs()
        if (!disconnectedRerouteRecoveryIsCurrent(ticket) || nowMs < ticket.requestedAtMs) {
            disconnectedRerouteRecovery = null
            return
        }
        // The posted timeout owns the final failure action; a late fix must not consume it.
        if (nowMs - ticket.requestedAtMs > 3_000L) return
        val location = currentLocation() ?: return
        if (facts.positionPaused || location.elapsedRealtimeMs <= ticket.afterFixMs ||
            LocationTrustPolicy.freshOrNull(location, nowMs) == null) return
        // Consume before any external work. The exact restored token belongs to the user's
        // already-approved request; a different/replaced choice never receives an approval.
        disconnectedRerouteRecovery = null
        if (!routeNavigator.approveReroute().shouldReroute) return
        requestRouteAgain(ticket.destination, ticket.coordinateEvidence)
    }

    internal fun routeResponseLocationStatus(
        route: WalkingRoute,
        origin: TrustedLocation,
        location: TrustedLocation?,
        nowMs: Long,
    ): RouteResponseInstallLocationStatus = routeResponseInstallLocationStatus(
        route, origin, location, nowMs, allowInitialStartConnector = false,
    )

    internal fun canRetainInitialRouteResponseForLocation(
        destination: RoutePoint,
        reason: String,
        preserveExistingRoute: Boolean,
        status: RouteResponseInstallLocationStatus,
    ): Boolean = canRetainInitialRouteResponseForLocation(destination, reason,
        preserveExistingRoute, status, recoverableCurrentConnection = false)

    private fun canRetainInitialRouteResponseForLocation(
        destination: RoutePoint,
        reason: String,
        preserveExistingRoute: Boolean,
        status: RouteResponseInstallLocationStatus,
        recoverableCurrentConnection: Boolean,
    ): Boolean = (status == RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE ||
        status == RouteResponseInstallLocationStatus.ROUTE_DISCONNECTED && recoverableCurrentConnection) &&
        !preserveExistingRoute && reason == "user_destination" &&
        facts.guidancePage && facts.selectedDestination?.point == destination

    /** The caller has completed current request, location and snapshot admission. */
    internal fun installRequestedRoute(
        route: WalkingRoute,
        destination: RoutePoint,
        origin: TrustedLocation,
        coordinateEvidence: DestinationCoordinateEvidence?,
        reason: String,
    ) {
        installRequestedRoute(route, destination, origin, coordinateEvidence, reason,
            routeResponseLocationStatus(route, origin, origin, origin.elapsedRealtimeMs) ==
                RouteResponseInstallLocationStatus.APPROACH_REQUIRED)
    }

    /** The queued response passes its CURRENT validated fix status without replacing request provenance. */
    internal fun installRequestedRoute(
        route: WalkingRoute,
        destination: RoutePoint,
        origin: TrustedLocation,
        coordinateEvidence: DestinationCoordinateEvidence?,
        reason: String,
        approachRequired: Boolean,
    ) {
        // This is the validated installation commit. Retire the old navigation channel before
        // setRoute clears its cue; otherwise an already-started utterance survives the new owner.
        if (routeNavigator.hasRoute()) effects.cancelNavigationSpeech()
        routeNavigator.setRoute(
            route,
            destination = destination,
            origin = origin,
            destinationCoordinateEvidence = coordinateEvidence,
            waitForInitialRouteJoin = approachRequired,
            announceStartSummary = reason == "off_route",
        )
        val gateway = facts.gateway
        val walk = facts.lifecycle
        val epoch = walk?.currentRuntimeEpochOrNull()
        installedArrivalOwner = if (walk != null && epoch != null && gateway.session != null) {
            ConfirmedArrival(walk, epoch, gateway.session, gateway.generation, destination, routeRequestGeneration)
        } else null
        // Start the observation owner at the actual route handoff, not the next periodic tick.
        effects.bindCompassOwner()
    }

    fun isRouteRequestLeaseCurrent(
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedRequestId: Int,
    ): Boolean =
        lifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) &&
            expectedRequestId == routeRequestGeneration

    /** Latest same-login transitions retire the old request; they never authorize its response. */
    internal fun retireRouteRequestForGatewayTransition(snapshot: GatewaySessionProcessSnapshot): Boolean {
        val owner = activeRouteRequestOwner ?: return false
        val current = facts.gateway
        val session = snapshot.session ?: return false
        if (snapshot.generation != current.generation || snapshot.session !== current.session ||
            snapshot.restoredFirstRunSnapshot != current.restoredFirstRunSnapshot ||
            snapshot.storageBlocked != current.storageBlocked ||
            snapshot.deletionRecoveryOnly != current.deletionRecoveryOnly ||
            snapshot.inFlightOperationId != current.inFlightOperationId ||
            snapshot.storageBlocked || snapshot.deletionRecoveryOnly ||
            snapshot.generation <= owner.gatewayGeneration ||
            session.verificationState != GatewaySessionVerificationState.VERIFIED ||
            session.sessionScope != GatewaySessionScope.GENERAL ||
            (session !== owner.session &&
                !(GatewayInRunRenewalPolicy.isRenewedContinuation(owner.session, session) &&
                    session.isUsableFor(owner.session.actorId)))
        ) return false
        if (!finishOwnedRouteRequest(owner.epoch, owner.requestId, owner.session.actorId,
                owner.preserveExistingRoute, owner.reason)) return false
        // The coordinator may already track a different call. Cancel only this captured transport.
        owner.call.cancel()
        navigationRequests.completeRoute(owner.call)
        return true
    }

    /** UI-thread terminal cleanup owns identity, not transient GPS output or credential readiness. */
    internal fun finishRouteRequestIfCurrent(
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedRequestId: Int,
        expectedGatewayGeneration: Long,
        expectedSession: GatewayFieldSession,
        preserveExistingRoute: Boolean,
        reason: String,
        completedResponse: Boolean = false,
        environmentRejectedAfterFixMs: Long? = null,
        terminalCause: RequestTerminalCause = RequestTerminalCause.OTHER,
    ): Boolean {
        val gateway = facts.gateway
        if (gateway.generation != expectedGatewayGeneration || gateway.session !== expectedSession) return false
        val owner = activeRouteRequestOwner
        val explicit = owner?.initialExplicitStart
        // Preserve the human intent only for an identified interruption of this exact request. A cancelled
        // pre-provider call cannot install anything; it may retry from a newer fix under the same owner.
        val environmentCancelledBeforeProvider = !completedResponse &&
            terminalCause == RequestTerminalCause.ENVIRONMENT_BEFORE_PROVIDER
        val providerFailedDuringEnvironmentInterruption = !completedResponse &&
            terminalCause == RequestTerminalCause.PROVIDER_FAILURE_WITH_ENVIRONMENT
        // Storage ownership can be rebound while this same owner's validated reply is in flight.
        // Keep its human intent, but the retired reply cannot install through the new I/O lease.
        val archivePreparationWithValidResponse = completedResponse &&
            terminalCause == RequestTerminalCause.ARCHIVE_PREPARATION_WITH_VALID_RESPONSE &&
            archive.preparationPending && archive.resetParticipant == null &&
            archive.leaseOrNull?.let(archive::routeSnapshotActivityIsCurrent) == true
        val retainedContext = if ((completedResponse || environmentCancelledBeforeProvider ||
                providerFailedDuringEnvironmentInterruption) &&
            (environmentRejectedAfterFixMs != null || archivePreparationWithValidResponse) &&
            owner != null && explicit != null &&
            owner.epoch == expectedWalkEpoch && owner.requestId == expectedRequestId &&
            owner.gatewayGeneration == expectedGatewayGeneration && owner.session === expectedSession &&
            !owner.preserveExistingRoute && owner.reason == "user_destination" &&
            !preserveExistingRoute && reason == "user_destination" &&
            (!owner.call.isCancelled() || environmentCancelledBeforeProvider) &&
            (!environmentCancelledBeforeProvider || facts.sessionCurrent(expectedSession)) &&
            !gateway.storageBlocked && !gateway.deletionRecoveryOnly &&
            (!archive.preparationPending || archivePreparationWithValidResponse) && !archive.purgeFailed &&
            isRouteActive && !routeNavigator.hasRoute() &&
            facts.guidancePage && facts.selectedDestination === explicit.selectedDestination &&
            currentDestination == explicit.destination && facts.rawActorId == expectedSession.actorId &&
            expectedSession.isUsableFor(expectedSession.actorId) && facts.runtimeActive
        ) ownedExplicitRouteStartContextOrNull(explicit.destination, explicit.coordinateEvidence)
            ?.takeIf(::explicitStartOwnerCurrent) else null
        if (!finishOwnedRouteRequest(expectedWalkEpoch, expectedRequestId, expectedSession.actorId,
                preserveExistingRoute, reason)) return false
        if (retainedContext != null && explicit != null) {
            clearPendingExplicitRouteStart(clearConnectionRecovery = false)
            pendingExplicitRouteStart.replace(retainedContext, explicit.requestedAtElapsedRealtimeMs,
                keepUntilCancelled = true)
            pendingExplicitRouteStartRequestId = expectedRequestId
            // Archive-only waits may reuse a still-fresh fix. An actual GPS/output refusal keeps
            // its original observation fence instead of starting a new sensor wait.
            pendingExplicitRouteStartAfterFixMs = environmentRejectedAfterFixMs?.let {
                maxOf(explicit.originObservedAtMs, it)
            }
        }
        return true
    }

    fun finishOwnedRouteRequest(
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedRequestId: Int,
        expectedActorId: String,
        preserveExistingRoute: Boolean,
        reason: String,
    ): Boolean {
        if (!isRouteRequestLeaseCurrent(expectedWalkEpoch, expectedRequestId) ||
            facts.rawActorId != expectedActorId || !routeRequestInFlight.compareAndSet(true, false)
        ) return false
        activeRouteRequestOwner = null
        if (preserveExistingRoute && isRouteActive && routeNavigator.hasRoute()) {
            if (reason == "off_route") {
                latestTmapOnRoute = false
                routeNavigator.rerouteRequestFailed()?.let { directionGuidancePauseReason = it.reason }
            }
        } else {
            isRouteActive = false
            latestTmapOnRoute = false
            routeNavigator.clear()
            routeStartStepCount = null
        }
        return true
    }

    fun retainRouteAfterRerouteFailure(message: String) {
        if (facts.developmentBypass) {
            directionGuidancePauseReason = null
            presentation.speak("$message 기존 TMAP 경로를 유지합니다.")
            return
        }
        routeNavigator.rerouteRequestFailed()?.let(effects::applyDeviationUpdate)
        pauseDirectionGuidance(reason = "tmap_unavailable", message = message)
    }

    fun pauseDirectionGuidance(reason: String, message: String) {
        if (!isRouteActive) return
        latestTmapOnRoute = false
        presentation.navigationStatus("navigation=direction_paused reason=$reason")
        if (isRouteActive && directionGuidancePauseReason != reason) {
            directionGuidancePauseReason = reason
            presentation.speak(message)
        }
    }

    /** Serializes route requests so user start and off-route reroute cannot race each other. */
    fun requestRoute(
        destination: RoutePoint,
        reason: String,
        retainExplicitStartUntilTrustedLocation: Boolean = false,
        destinationCoordinateEvidence: DestinationCoordinateEvidence? = null,
    ) = requestRoute(destination, reason, retainExplicitStartUntilTrustedLocation,
        destinationCoordinateEvidence, connectionRecoveryAttempted = false)

    fun requestRoute(
        destination: RoutePoint,
        reason: String,
        retainExplicitStartUntilTrustedLocation: Boolean,
        destinationCoordinateEvidence: DestinationCoordinateEvidence?,
        connectionRecoveryAttempted: Boolean,
    ) {
        retireConfirmedArrival()
        val continuingStart = continuingExplicitRouteStart
        if (continuingStart == null) clearPendingExplicitRouteStart()
        if (!connectionRecoveryAttempted) disconnectedRerouteRecovery = null
        if (!facts.navigationAllowed) return
        if (reason != "off_route" && effects.blockRouteMutation()) return
        val preserveExistingRoute = isRouteActive && routeNavigator.hasRoute()
        if (!archive.routeSnapshotPurgeFenceAllowsRoute()) {
            if (archive.preparationPending && retainExplicitStartUntilTrustedLocation &&
                !preserveExistingRoute && facts.locationPermissionReady
            ) {
                currentDestination = destination
                val evidence = destinationCoordinateEvidence?.let {
                    it.copy(candidates = it.candidates.toList())
                }
                if (retainPendingExplicitRouteStart(destination, evidence, waitForNewFixAfterMs = null,
                        requestedAtElapsedRealtimeMs = continuingStart?.requestedAtElapsedRealtimeMs
                            ?: this.elapsedRealtimeMs())) {
                    presentation.navigationStatus("navigation=route_waiting route_snapshot_preparing start_request_pending")
                    presentation.status("안내 준비 중", "현재 위치와 경로를 준비하고 있습니다. 준비되면 방금 요청한 안내 시작을 이어갑니다.")
                }
            }
            return
        }
        val expectedWalkEpoch = lifecycle.currentRuntimeEpochOrNull() ?: return
        val deviceResources = facts.resources
        if (!facts.developmentBypass &&
            deviceResources.readinessStatus != WalkSessionReadinessStatus.READY
        ) {
            presentation.navigationStatus(
                "navigation=route_blocked device_resources=" +
                    deviceResources.readinessStatus.name.lowercase(Locale.US),
            )
            return
        }
        fun retainExistingRouteAfterFailure(message: String) {
            if (reason == "off_route") {
                retainRouteAfterRerouteFailure(message)
            } else {
                presentation.speak("$message 기존 목적지와 경로를 유지합니다.")
            }
        }
        if (!effects.networkAllowed(reason = "walking_route")) {
            if (!preserveExistingRoute) isRouteActive = false
            presentation.updateRouteButton()
            return
        }
        val gatewaySession = effects.sessionOrNull("walking_route") ?: run {
            if (!preserveExistingRoute) isRouteActive = false
            if (!preserveExistingRoute || reason == "off_route") latestTmapOnRoute = false
            if (!preserveExistingRoute) currentDestination = null
            presentation.updateRouteButton()
            return
        }
        val gatewayProcessSnapshot = facts.gateway
        if (
            gatewayProcessSnapshot.session !== gatewaySession ||
            gatewayProcessSnapshot.deletionRecoveryOnly ||
            gatewayProcessSnapshot.storageBlocked
        ) return
        val expectedGatewaySessionGeneration = gatewayProcessSnapshot.generation
        GatewayCapacityProcessState.fenceSessionGeneration(
            expectedGatewaySessionGeneration,
        )
        // Keep provider coordinate provenance with this request, including its GPS wait.
        val requestedDestinationCoordinateEvidence = destinationCoordinateEvidence
            ?.let { it.copy(candidates = it.candidates.toList()) }
        val requestedDestinationName = facts.selectedDestination?.takeIf { it.point == destination }?.name
            ?: destination.name
        val origin = facts.currentLocation
        if (origin == null) {
            latestTmapOnRoute = false
            if (!preserveExistingRoute) {
                isRouteActive = false
                currentDestination = destination
                if (facts.locationPermissionReady) {
                    effects.setRoutePermissionRequested(true)
                    effects.requestLocationServices()
                }
            }
            presentation.updateRouteButton()
            val explicitStartRetained =
                retainExplicitStartUntilTrustedLocation &&
                    !preserveExistingRoute &&
                    facts.locationPermissionReady &&
                    retainPendingExplicitRouteStart(destination, requestedDestinationCoordinateEvidence)
            presentation.navigationStatus(
                if (preserveExistingRoute) {
                    "navigation=reroute_blocked trusted_gps_missing existing_route_retained"
                } else if (explicitStartRetained) {
                    "navigation=route_waiting trusted_gps_missing start_request_pending"
                } else if (facts.locationPermissionReady) {
                    "navigation=route_waiting trusted_gps_missing explicit_start_required"
                } else {
                    "navigation=route_blocked gps_permission_missing"
                },
            )
            if (explicitStartRetained) {
                presentation.status("안내 준비 중", "선택한 목적지로 안내를 준비하고 있습니다.")
            }
            return
        }
        if (navigationRequests.hasActiveRoute() || !routeRequestInFlight.compareAndSet(false, true)) {
            presentation.navigationStatus("navigation=route_blocked request_in_flight")
            return
        }
        // S06-39: "목적지 취소" cancels a confirmed destination replacement but keeps a reroute.
        activeRouteRequestReason = reason
        isRouteActive = true
        // The installed route keeps its destination until a validated replacement is ready.
        if (!preserveExistingRoute) currentDestination = destination
        val requestId = ++routeRequestGeneration
        initialConnectionRecovery?.requestId = requestId
        initialRouteRetry.bindRequest(requestId)
        bindResumedNavigationRequest(expectedWalkEpoch, requestId, gatewaySession, destination, reason)
        val successfulResponseReceived = AtomicBoolean(false)
        val environmentRejectedAfterFixMs = AtomicReference<Long?>(null)
        fun isCurrentRouteOwner(): Boolean {
            val currentSession = facts.gateway
            return isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId) &&
                currentSession.generation == expectedGatewaySessionGeneration &&
                currentSession.session === gatewaySession &&
                facts.actorId == gatewaySession.actorId
        }
        fun isCurrentRouteRequest(): Boolean {
            if (!isCurrentRouteOwner()) return false
            return routeResponseEnvironmentCurrent(successfulResponseReceived.get(),
                environmentRejectedAfterFixMs, origin.elapsedRealtimeMs,
                outputsAllowed = !facts.runtimeActive || facts.environmentOutputsAllowed)
        }
        fun showCurrentUnstartedRequestFailure(message: String, failureMetadata: String? = null) {
            if (!isCurrentRouteOwner() || !facts.foreground || facts.activityDestroyed) return
            failureMetadata?.let { presentation.navigationStatus("navigation=route_failed $it") }
            presentation.status("길 안내 요청 확인 필요", message)
            presentation.speakStatus(message)
        }
        fun finishCurrentRouteRequest(completedResponse: Boolean = false,
            terminalCause: RequestTerminalCause = RequestTerminalCause.OTHER): Boolean {
            if (expireInitialRouteRecovery(requestId)) return false
            val cause = if (terminalCause == RequestTerminalCause.OTHER && completedResponse &&
                successfulResponseReceived.get() && archive.preparationPending)
                RequestTerminalCause.ARCHIVE_PREPARATION_WITH_VALID_RESPONSE else terminalCause
            if (!finishRouteRequestIfCurrent(expectedWalkEpoch, requestId,
                    expectedGatewaySessionGeneration, gatewaySession, preserveExistingRoute, reason,
                    completedResponse, environmentRejectedAfterFixMs.get(), cause)) return false
            // A current valid provider reply is a success even when sensors defer its installation.
            if (completedResponse && successfulResponseReceived.get() && facts.sessionCurrent(gatewaySession)) {
                tmapFailureGuard.recordSuccess()
            }
            effects.updateDeviationActions(routeNavigator.pendingUserDecision())
            presentation.updateRouteButton()
            presentation.navigationStatus(if (preserveExistingRoute) {
                "navigation=route_request_finished existing_route_retained"
            } else if (pendingExplicitRouteStart.currentOrNull() != null) {
                "navigation=route_waiting trusted_gps_missing start_request_pending"
            } else {
                "navigation=route_waiting explicit_start_required"
            })
            // A fresh fix may have recovered while this terminal waited in the UI queue.
            // Do not call the clearing wrapper while admission is still closed.
            if (terminalCause != RequestTerminalCause.PROVIDER_FAILURE_WITH_ENVIRONMENT &&
                pendingExplicitRouteStartRequestId == requestId &&
                pendingExplicitRouteStart.currentOrNull() != null && facts.navigationAllowed
            ) {
                continuePendingExplicitRouteStartIfReady()
            }
            return true
        }
        presentation.navigationStatus("navigation=route_requesting reason=$reason priority=STAIR_AVOID")
        presentation.updateRouteButton()
        val routeCall = navigationRequests.trackRoute(
            backend.fetchRoute(
                session = gatewaySession,
                request = WalkingRouteRequest(
                    origin = RoutePoint(origin.latitude, origin.longitude, "현재 위치"),
                    destination = destination,
                ),
            ),
        )
        activeRouteRequestOwner = PendingRouteRequestOwner(expectedWalkEpoch, requestId,
            expectedGatewaySessionGeneration, gatewaySession, preserveExistingRoute, reason, routeCall,
            initialExplicitStart = if (retainExplicitStartUntilTrustedLocation &&
                !preserveExistingRoute && reason == "user_destination"
            ) facts.selectedDestination?.takeIf {
                facts.guidancePage && it.point == destination
            }?.let { selected -> InitialExplicitRouteStart(destination, requestedDestinationCoordinateEvidence,
                origin.elapsedRealtimeMs, selected,
                continuingStart?.requestedAtElapsedRealtimeMs ?: this.elapsedRealtimeMs()) }
                else null)
        fun retireUnadmittedPreProviderRequest() {
            val cause = if (environmentRejectedAfterFixMs.get() != null &&
                !routeCall.isCancelled() && facts.sessionCurrent(gatewaySession)) RequestTerminalCause.ENVIRONMENT_BEFORE_PROVIDER
                else RequestTerminalCause.OTHER
            navigationRequests.completeRoute(routeCall)
            routeCall.cancel()
            postToUi { finishCurrentRouteRequest(terminalCause = cause) }
        }
        try {
            worker.execute {
                var routeProviderCallInProgress = false
                try {
                    if (
                        !isCurrentRouteRequest() ||
                        !facts.sessionCurrent(gatewaySession)
                    ) {
                        retireUnadmittedPreProviderRequest()
                        return@execute
                    }
                    effects.recordStage("ROUTE_SESSION_CHECK_BEGIN")
                    val revalidation = backend.revalidate(
                        gatewaySession,
                        facts.actorId,
                        capacitySessionGeneration =
                            expectedGatewaySessionGeneration,
                    )
                    effects.recordStage("ROUTE_SESSION_CHECK_COMPLETE")
                    if (revalidation.status != GatewaySessionRevalidationStatus.READY) {
                        val pendingSameSession = revalidation.status == GatewaySessionRevalidationStatus.PENDING &&
                            !routeCall.isCancelled() && facts.sessionCurrent(gatewaySession)
                        navigationRequests.completeRoute(routeCall)
                        routeCall.cancel()
                        postToUi {
                            val pendingOwner = activeRouteRequestOwner
                            if (pendingSameSession) isCurrentRouteRequest()
                            if (!finishCurrentRouteRequest()) return@postToUi
                            if (pendingSameSession && pendingOwner?.requestId == requestId &&
                                pendingOwner.initialExplicitStart != null &&
                                retainTransientRouteRecovery(pendingOwner, environmentRejectedAfterFixMs.get(),
                                    "gateway=pending", "안내 서버의 연결 확인을 기다리고 있습니다.",
                                    cancelledForPendingRevalidation = true)) return@postToUi
                            if (!preserveExistingRoute) clearPendingExplicitRouteStart()
                            if (
                                revalidation.status ==
                                GatewaySessionRevalidationStatus.NOT_READY
                            ) {
                                effects.sessionNotReady(gatewaySession)
                            } else if (preserveExistingRoute) {
                                if (reason == "off_route") latestTmapOnRoute = false
                                if (isCurrentRouteRequest()) {
                                    retainExistingRouteAfterFailure("새 TMAP 경로를 아직 확인하지 못했습니다.")
                                }
                                presentation.updateRouteButton()
                            } else {
                                isRouteActive = false
                                latestTmapOnRoute = false
                                routeNavigator.clear()
                                routeStartStepCount = null
                                currentDestination = destination
                                presentation.updateRouteButton()
                                showCurrentUnstartedRequestFailure(
                                    "안내 서버의 연결을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                                    "gateway=pending")
                            }
                            if (preserveExistingRoute || revalidation.status ==
                                GatewaySessionRevalidationStatus.NOT_READY
                            ) presentation.navigationStatus(
                                "navigation=route_wait " +
                                    "gateway=${revalidation.status.name.lowercase(Locale.US)}",
                            )
                        }
                        return@execute
                    }
                    if (
                        !isCurrentRouteRequest() ||
                        !facts.sessionCurrent(gatewaySession)
                    ) {
                        retireUnadmittedPreProviderRequest()
                        return@execute
                    }
                    routeProviderCallInProgress = true
                    effects.recordStage("ROUTE_HTTP_BEGIN")
                    val route = archive.captureRouteSnapshotResponseRoute(routeCall.execute())
                    effects.recordStage("ROUTE_HTTP_COMPLETE")
                    successfulResponseReceived.set(true)
                    routeProviderCallInProgress = false
                    if (!facts.sessionCurrent(gatewaySession)) {
                        navigationRequests.completeRoute(routeCall)
                        postToUi {
                            if (finishCurrentRouteRequest()) {
                                presentation.navigationStatus("navigation=route_cancelled session_changed")
                            }
                        }
                        return@execute
                    }
                    if (!isCurrentRouteRequest()) {
                        navigationRequests.completeRoute(routeCall)
                        postToUi { finishCurrentRouteRequest(completedResponse = true) }
                        return@execute
                    }
                    navigationRequests.completeRoute(routeCall)
                    postToUi {
                        if (expireInitialRouteRecovery(requestId)) return@postToUi
                        if (!routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)) {
                            finishCurrentRouteRequest(completedResponse = true)
                            return@postToUi
                        }
                        if (!facts.sessionCurrent(gatewaySession)) {
                            if (finishCurrentRouteRequest(completedResponse = true)) {
                                presentation.navigationStatus("navigation=route_cancelled session_changed")
                            }
                            return@postToUi
                        }
                        if (!isRouteActive || !archive.routeSnapshotPurgeFenceAllowsRoute()) {
                            finishCurrentRouteRequest(completedResponse = true)
                            return@postToUi
                        }
                        fun rejectDisconnectedResponse(installLocationStatus: RouteResponseInstallLocationStatus) {
                            // The provider succeeded, but its old request origin no longer proves
                            // that the returned route begins at the walker's current position.
                            val requestOwner = activeRouteRequestOwner
                            val recoveryAfterFixMs = maxOf(origin.elapsedRealtimeMs,
                                facts.currentLocation?.elapsedRealtimeMs ?: origin.elapsedRealtimeMs,
                                facts.latestTrustedLocation?.elapsedRealtimeMs ?: origin.elapsedRealtimeMs,
                                facts.latestRawLocation?.elapsedRealtimeMs ?: origin.elapsedRealtimeMs)
                            val recoverableCurrentConnection =
                                routeResponseHasRecoverableCurrentConnection(route, origin,
                                    facts.currentLocation, this.elapsedRealtimeMs())
                            if (!finishCurrentRouteRequest()) return
                            tmapFailureGuard.recordSuccess()
                            if (retainDisconnectedRerouteForFreshLocation(
                                    expectedWalkEpoch, requestId, expectedGatewaySessionGeneration, gatewaySession,
                                    destination, requestedDestinationCoordinateEvidence, reason, preserveExistingRoute,
                                    connectionRecoveryAttempted, installLocationStatus,
                                    origin.elapsedRealtimeMs,
                                    facts.currentLocation?.elapsedRealtimeMs,
                                )) return
                            if (requestOwner != null &&
                                canRetainInitialRouteResponseForLocation(destination, reason,
                                    preserveExistingRoute, installLocationStatus, recoverableCurrentConnection) &&
                                recoverableCurrentConnection &&
                                retainInitialConnectionRecovery(requestOwner, recoveryAfterFixMs)) return
                            if (!preserveExistingRoute) {
                                isRouteActive = false
                                latestTmapOnRoute = false
                                routeNavigator.clear()
                                routeStartStepCount = null
                            }
                            if (canRetainInitialRouteResponseForLocation(destination, reason,
                                    preserveExistingRoute, installLocationStatus) &&
                                retainPendingExplicitRouteStart(
                                    destination, requestedDestinationCoordinateEvidence,
                                    waitForNewFixAfterMs = recoveryAfterFixMs,
                                    requestedAtElapsedRealtimeMs = requestOwner?.initialExplicitStart
                                        ?.requestedAtElapsedRealtimeMs ?: this.elapsedRealtimeMs(),
                                )
                            ) {
                                pendingExplicitRouteStartRequestId = requestId
                                presentation.updateRouteButton()
                                presentation.navigationStatus("navigation=route_waiting trusted_gps_missing")
                                presentation.status("현재 위치 확인 필요", "목적지는 유지했습니다. 현재 위치가 확인되면 경로 연결을 다시 확인합니다.")
                                return
                            }
                            clearInitialConnectionRecovery()
                            presentation.updateRouteButton()
                            presentation.navigationStatus(
                                "navigation=route_failed " +
                                    "reason=${installLocationStatus.name.lowercase(Locale.US)}",
                            )
                            val detail = if (preserveExistingRoute && reason == "off_route") {
                                "새 경로를 현재 위치에 연결하지 못했습니다. 기존 목적지와 재탐색 선택을 유지합니다."
                            } else if (installLocationStatus == RouteResponseInstallLocationStatus.LOCATION_UNAVAILABLE) {
                                "현재 위치를 확인하지 못했습니다. 새 위치가 확인된 뒤 길안내를 다시 요청해 주세요."
                            } else {
                                "현재 위치에서 이어지는 경로를 확인하지 못했습니다. 길안내를 다시 요청해 주세요."
                            }
                            if (preserveExistingRoute) retainExistingRouteAfterFailure(detail)
                            else presentation.speak(detail)
                        }
                        val installLocation = facts.currentLocation
                        if (!isCurrentRouteRequest() || !isRouteActive) {
                            finishCurrentRouteRequest(completedResponse = true)
                            return@postToUi
                        }
                        val installLocationStatus = routeResponseLocationStatus(
                            route, origin, installLocation, this.elapsedRealtimeMs(),
                        )
                        if (installLocationStatus != RouteResponseInstallLocationStatus.CONNECTED &&
                            installLocationStatus != RouteResponseInstallLocationStatus.APPROACH_REQUIRED) {
                            rejectDisconnectedResponse(installLocationStatus)
                            return@postToUi
                        }
                        val owner = archive.routeSnapshotOwnerOrNull(gatewaySession) ?: run {
                            finishCurrentRouteRequest(completedResponse = true)
                            return@postToUi
                        }
                        val scope = RouteSnapshotScope(owner, expectedWalkEpoch.walkSessionId)
                        if (archive.walkScope != scope || archive.leaseOrNull == null) {
                            finishCurrentRouteRequest(completedResponse = true)
                            return@postToUi
                        }
                        val lease = requireNotNull(archive.leaseOrNull)
                        val operation = lease.operation.get()
                        val response = lease.response.get()
                        val permissionOwner = facts.permissionOwner
                        val lifecycleOwner = lifecycle
                        val deletionOwner = facts.deletionOwner
                        val activityLease = facts.deletionActivityLease
                        val onboarding = gatewayProcessSnapshot.restoredFirstRunSnapshot
                        fun storageAdmissionCurrent(): Boolean {
                            val gateway = facts.gateway
                            return lane.isCurrent(lease, operation) &&
                                lease.response.get() === response && !facts.activityDestroyed &&
                                facts.foreground && routeRequestInFlight.get() && routeRequestGeneration == requestId &&
                                lifecycleOwner.isRuntimeEpochCurrent(expectedWalkEpoch) &&
                                gateway.generation == expectedGatewaySessionGeneration && gateway.session === gatewaySession &&
                                !gateway.storageBlocked && !gateway.deletionRecoveryOnly &&
                                gateway.restoredFirstRunSnapshot == onboarding &&
                                !deletionOwner.processingBlocked() && deletionOwner.activityLeaseIsCurrent(activityLease) &&
                                archive.routeSnapshotNavigationPermissionCurrent(permissionOwner, gatewaySession.actorId) &&
                                isCurrentRouteRequest()
                        }
                        effects.recordStage("ROUTE_ARCHIVE_BEGIN")
                        archive.submitRouteSnapshotResponseStorage(lane, lease,
                            ::storageAdmissionCurrent, scope, route, destination,
                            requestedDestinationCoordinateEvidence).thenAccept { storedRouteSnapshot ->
                            postToUi completeSnapshot@{
                                if (expireInitialRouteRecovery(requestId)) return@completeSnapshot
                                effects.recordStage("ROUTE_ARCHIVE_COMPLETE")
                                if (!storageAdmissionCurrent() ||
                                    !routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest) ||
                                    !facts.sessionCurrent(gatewaySession) || !isRouteActive ||
                                    archive.walkScope != scope || !facts.navigationAllowed ||
                                    !archive.routeSnapshotPurgeFenceAllowsRoute()
                                ) {
                                    finishCurrentRouteRequest(completedResponse = true)
                                    return@completeSnapshot
                                }
                                val completedLocation = facts.currentLocation
                                val completedLocationStatus = routeResponseLocationStatus(
                                    route, origin, completedLocation, this.elapsedRealtimeMs(),
                                )
                                if (completedLocationStatus != RouteResponseInstallLocationStatus.CONNECTED &&
                                    completedLocationStatus != RouteResponseInstallLocationStatus.APPROACH_REQUIRED) {
                                    rejectDisconnectedResponse(completedLocationStatus)
                                    return@completeSnapshot
                                }
                                val connectedInstallLocation = requireNotNull(completedLocation)
                                if (storedRouteSnapshot != null && storedRouteSnapshot.expiresAtEpochMs <= wallTimeMs()) {
                                    archive.onEncryptedRouteSnapshotExpired(scope, lease, operation, ::storageAdmissionCurrent)
                                    finishCurrentRouteRequest()
                                    return@completeSnapshot
                                }
                                if (storedRouteSnapshot == null && !facts.developmentBypass) {
                                    val requestOwner = activeRouteRequestOwner
                                    if (requestOwner != null && finishCurrentRouteRequest() &&
                                        retainTransientRouteRecovery(requestOwner, environmentRejectedAfterFixMs.get(),
                                            "reason=route_snapshot_store_failed", "경로 저장을 다시 준비하고 있습니다.")) {
                                        return@completeSnapshot
                                    }
                                    routeRequestInFlight.set(false)
                                    presentation.safetyStop("route_snapshot_store_failed")
                                    val detail =
                                        "최초 TMAP 경로를 암호화해 저장하지 못해 보행 기능을 중지했습니다."
                                    presentation.status(
                                        "길안내 안전 중지",
                                        detail,
                                    )
                                    presentation.navigationStatus("navigation=safety_stopped reason=route_snapshot_store_failed")
                                    presentation.speak(detail)
                                    return@completeSnapshot
                                }
                                if (storedRouteSnapshot != null) {
                                    val committed = lane.commit(lease, operation,
                                        ::storageAdmissionCurrent) { archive.markArchiveStored() }
                                    if (!committed) {
                                        finishCurrentRouteRequest(completedResponse = true)
                                        return@completeSnapshot
                                    }
                                    archive.scheduleEncryptedRouteSnapshotExpiry(
                                        scope = storedRouteSnapshot.scope,
                                        expiresAtEpochMs = storedRouteSnapshot.expiresAtEpochMs,
                                        expectedOperation = operation,
                                    )
                                }
                                if (!storageAdmissionCurrent()) {
                                    finishCurrentRouteRequest(completedResponse = true)
                                    return@completeSnapshot
                                }
                                tmapFailureGuard.recordSuccess()
                                clearInitialConnectionRecovery()
                                clearTransientRouteRecovery()
                                val finishPreparation = effects.beginInstalledRoute()
                                installRequestedRoute(route, destination, origin,
                                    requestedDestinationCoordinateEvidence, reason,
                                    completedLocationStatus == RouteResponseInstallLocationStatus.APPROACH_REQUIRED)
                                effects.recordInstalledRoute(route)
                                currentDestination = destination
                                effects.showDestinationCoordinates(destination)
                                routeStartStepCount = facts.stepCount
                                directionGuidancePauseReason = null
                                latestTmapOnRoute = false
                                routeRequestInFlight.set(false)
                                activeRouteRequestOwner = null
                                effects.recordStage("ROUTE_INSTALLED")
                                presentation.updateRouteButton()
                                presentation.navigationStatus(
                                    "navigation=route_ready distance=${route.summary.distanceM}m priority=${route.priority}",
                                )
                                // A sensor pass is not a route start. Announce the captured destination only
                                // after the validated route was installed; retain its accessibility limits.
                                val scopeRouteGeneration = routeRequestGeneration
                                val installedRouteId = routeNavigator.currentRouteId()
                                fun startAnnouncementCurrent(): Boolean = facts.foreground &&
                                    isCurrentRouteRequest() && facts.runtimeActive && isRouteActive &&
                                    routeRequestGeneration == scopeRouteGeneration && currentDestination == destination &&
                                    routeNavigator.currentRouteId() == installedRouteId
                                if (reason == "off_route") installedStartSpeech.clearPending() else {
                                    installedStartSpeech.install(
                                        message = kr.co.hanium.dreamup.walksafe.navigation.routeStartAnnouncementKo(
                                            requestedDestinationName, route.summary.distanceM, true, route.priority),
                                        current = ::startAnnouncementCurrent,
                                    )
                                }
                                finishPreparation()
                                facts.currentLocation?.let { current ->
                                    effects.updateGuidance(current, facts.latestRawLocation ?: current)
                                }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    navigationRequests.completeRoute(routeCall)
                    postToUi {
                        if (!finishCurrentRouteRequest() || !isCurrentRouteRequest()) return@postToUi
                        clearPendingExplicitRouteStart()
                        if (!preserveExistingRoute) isRouteActive = false
                        if (!preserveExistingRoute || reason == "off_route") latestTmapOnRoute = false
                        if (!preserveExistingRoute) currentDestination = null
                        if (preserveExistingRoute) {
                            retainExistingRouteAfterFailure("TMAP 새 경로 요청이 취소되었습니다.")
                        }
                        presentation.updateRouteButton()
                        presentation.navigationStatus("navigation=route_cancelled")
                    }
                } catch (error: Exception) {
                    navigationRequests.completeRoute(routeCall)
                    val navigationFailure = classifyNavigationBackendFailure(error)
                    val countableRouteFailure = routeProviderCallInProgress &&
                        !navigationFailure.isUserSessionAuthentication
                    postToUi {
                        val failedOwner = activeRouteRequestOwner
                        val transientInitialFailure = countableRouteFailure &&
                            failedOwner?.initialExplicitStart != null && !preserveExistingRoute &&
                            navigationFailure.kind in setOf(NavigationBackendErrorKind.TIMEOUT,
                                NavigationBackendErrorKind.NETWORK, NavigationBackendErrorKind.PROVIDER_UNAVAILABLE,
                                NavigationBackendErrorKind.BACKEND_UNAVAILABLE)
                        // Observe the output refusal while the original request still owns its intent.
                        val currentAtFailure = isCurrentRouteRequest()
                        val interruptedProviderFailure = countableRouteFailure && !currentAtFailure &&
                            environmentRejectedAfterFixMs.get() != null
                        val cause = if (interruptedProviderFailure) {
                            RequestTerminalCause.PROVIDER_FAILURE_WITH_ENVIRONMENT
                        } else RequestTerminalCause.OTHER
                        // Restore the exact request before an authentication probe can replace its session.
                        if (!finishCurrentRouteRequest(terminalCause = cause)) return@postToUi
                        if (!interruptedProviderFailure && !transientInitialFailure) clearPendingExplicitRouteStart()
                        val failureGuard = effects.failureAdmission(gatewaySession,
                            navigationFailure.isUserSessionAuthentication)
                        if (failureGuard == null || !failureGuard()) {
                            if (transientInitialFailure) clearPendingExplicitRouteStart()
                            return@postToUi
                        }
                        if (navigationFailure.isUserSessionAuthentication) {
                            presentation.navigationStatus("navigation=route_failed ${navigationFailure.statusMetadata}")
                            presentation.speak(navigationFailure.routeUserMessage)
                            return@postToUi
                        }
                        if (!isCurrentRouteOwner()) return@postToUi
                        // Record each admitted provider failure even when its human intent can recover.
                        val providerFailureStopReached = countableRouteFailure && tmapFailureGuard.recordFailure()
                        if (transientInitialFailure && failedOwner != null &&
                            retainTransientRouteRecovery(failedOwner, environmentRejectedAfterFixMs.get(),
                                navigationFailure.statusMetadata,
                                if (navigationFailure.kind == NavigationBackendErrorKind.NETWORK)
                                    "네트워크 연결을 확인하고 있습니다."
                                else "경로 서비스 연결을 확인하고 있습니다.")) return@postToUi
                        if (
                            providerFailureStopReached &&
                            !facts.developmentBypass
                        ) {
                            clearPendingExplicitRouteStart()
                            presentation.safetyStop("route_consecutive_failures")
                            val safetyStopDetail =
                                "${navigationFailure.routeUserMessage} " +
                                    "경로 요청이 두 번 연속 실패해 모든 보행 기능을 중지했습니다. " +
                                    "새 보행을 직접 시작해 주세요."
                            presentation.status(
                                "길안내 안전 중지",
                                safetyStopDetail,
                            )
                            presentation.navigationStatus(
                                "navigation=safety_stopped reason=route_consecutive_failures " +
                                    navigationFailure.statusMetadata,
                            )
                            presentation.speak(safetyStopDetail)
                            return@postToUi
                        }
                        if (interruptedProviderFailure && pendingExplicitRouteStart.currentOrNull() != null) {
                            presentation.navigationStatus("navigation=route_waiting ${navigationFailure.statusMetadata} start_request_pending")
                            presentation.status("안내 준비 중", "경로 요청을 완료하지 못했습니다. 위치가 회복되면 다시 요청합니다.")
                            // The first failure is already counted before any fresh-fix retry can start.
                            continuePendingExplicitRouteStartIfReady()
                            return@postToUi
                        }
                        if (!isCurrentRouteRequest()) {
                            if (!countableRouteFailure && !preserveExistingRoute) {
                                showCurrentUnstartedRequestFailure(navigationFailure.routeUserMessage,
                                    navigationFailure.statusMetadata)
                            }
                            return@postToUi
                        }
                        if (!preserveExistingRoute) isRouteActive = false
                        if (!preserveExistingRoute || reason == "off_route") latestTmapOnRoute = false
                        if (!preserveExistingRoute) currentDestination = null
                        presentation.updateRouteButton()
                        presentation.navigationStatus(
                            if (preserveExistingRoute) {
                                "navigation=reroute_failed ${navigationFailure.statusMetadata} existing_route_retained"
                            } else {
                                "navigation=route_failed ${navigationFailure.statusMetadata}"
                            },
                        )
                        if (preserveExistingRoute) {
                            retainExistingRouteAfterFailure(navigationFailure.routeUserMessage)
                        } else {
                            presentation.speak(navigationFailure.routeUserMessage)
                        }
                    }
                } finally {
                    navigationRequests.completeRoute(routeCall)
                }
            }
        } catch (_: RejectedExecutionException) {
            navigationRequests.completeRoute(routeCall)
            routeCall.cancel()
            if (!finishCurrentRouteRequest()) return
            if (!isCurrentRouteRequest()) {
                if (!preserveExistingRoute) {
                    showCurrentUnstartedRequestFailure(
                        "경로 요청을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                        "route_executor_rejected")
                }
                return
            }
            clearPendingExplicitRouteStart()
            if (!preserveExistingRoute) isRouteActive = false
            if (!preserveExistingRoute || reason == "off_route") latestTmapOnRoute = false
            if (!preserveExistingRoute) currentDestination = null
            presentation.updateRouteButton()
            presentation.navigationStatus(
                if (preserveExistingRoute) {
                    "navigation=reroute_failed executor_rejected existing_route_retained"
                } else {
                    "navigation=route_failed executor_rejected"
                },
            )
            if (preserveExistingRoute) {
                retainExistingRouteAfterFailure("TMAP 새 경로를 확인할 수 없습니다.")
            } else {
                presentation.speak("TMAP 경로를 확인할 수 없어 길안내를 시작하지 않았습니다.")
            }
        }
    }
    companion object { const val ROUTE_START_LOCATION_TIMEOUT_MS = 15_000L }
}
