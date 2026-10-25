package kr.co.hanium.dreamup.walksafe.navigation.session

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.navigation.InstalledRouteStartSpeechCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteDeviationChoice
import kr.co.hanium.dreamup.walksafe.navigation.RouteDirectionGraceMode
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingObservation
import kr.co.hanium.dreamup.walksafe.navigation.RouteGuidanceDiagnostics
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUpdate
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.dispatchRouteInstructionSpeech
import kr.co.hanium.dreamup.walksafe.navigation.positioning.EnuPositionCovariance
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceAnnouncementKind
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteHeadingEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import kr.co.hanium.dreamup.walksafe.navigation.session.NavigationRunState
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteCallbackScheduler
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle

/** Owns route cue reservation/retry, question and position-recovery delivery. No SDK work in construction. */
internal class RouteGuidanceController(
    private val navigationRunState: NavigationRunState,
    private val scope: ScopeReader,
    private val position: PositionReader,
    private val compassInput: CompassReader,
    private val speech: SpeechPort,
    private val effects: Effects,
    private val diagnostics: Diagnostics,
    private val installedRouteStartSpeechCoordinator: InstalledRouteStartSpeechCoordinator,
    private val callbacks: RouteCallbackScheduler,
    private val elapsedRealtimeMs: () -> Long,
    private val isMainThread: () -> Boolean,
    private val postToUi: (() -> Unit) -> Unit,
    private val capturePositioningSpeechOwner: () -> (() -> Boolean)?,
) {
    interface ScopeReader {
        val lifecycle: WalkSessionLifecycle?
        val foreground: Boolean
        val runtimeActive: Boolean
        val navigationAllowed: Boolean
        val requestPending: Boolean
        val requestGeneration: Int
        val feedbackGeneration: Int
        val developmentBypass: Boolean
        fun feedbackCurrent(generation: Int): Boolean
    }
    interface PositionReader {
        val latestTrusted: TrustedLocation?
        val latestRaw: TrustedLocation?
        val covariance: Pair<TrustedLocation, EnuPositionCovariance>?
        val course: HeadingObservation?
        val speed: WalkingSpeedObservation?
        val stepCount: Int
        val stepLengthM: Float
        fun currentLocation(): TrustedLocation?
        fun confidenceLeaseOrNull(token: Long): ConfidenceLease?
    }
    interface ConfidenceLease {
        fun isCurrent(): Boolean
        fun delivered(nowMs: Long)
    }
    interface CompassReader {
        val revision: Long
        val invalidationPending: Boolean
        fun heading(nowMs: Long): RouteCompassHeadingResult?
        fun facing(nowMs: Long): RouteFacingObservation?
    }
    interface SpeechPort : RouteDecisionSpeechCoordinator.SpeechPort, RouteRecheckNoticeCoordinator.SpeechPort {
        val validatingStarted: Boolean
        val statusText: String?
        override fun busy(): Boolean
        fun canSpeakNavigation(nowMs: Long): Boolean
        fun cancelNavigation()
        fun submitNavigation(message: String, isInstructionStillCurrent: () -> Boolean,
            onCompleted: () -> Unit, onFailed: () -> Unit): Boolean
        fun submitInstalledStart(message: String, current: () -> Boolean, terminal: () -> Unit): Boolean
    }
    interface ArrivalCompletion {
        fun isCurrent(): Boolean
        fun finish()
    }
    interface Effects {
        fun beginArrival(token: RouteNavigatorDecisionToken): ArrivalCompletion? = null
        fun navigationStatus(text: String)
        fun interaction(message: String)
        fun pauseDirection(reason: String, message: String)
        fun decisionActions(decision: RouteNavigatorUserDecision?)
        fun progressBeep(nowMs: Long, offRoute: Boolean, arrived: Boolean)
        fun pausedHaptic()
        fun deviationHaptic()
        fun preparationStage(stage: String)
        fun clearWaitingFailure(current: () -> Boolean)
        fun showLastResult(message: String)
        fun unavailableLocationMessage(): String
    }
    interface Diagnostics {
        val debug: Boolean
        val recording: Boolean
        fun log(tag: String, message: String)
        fun record(event: String, values: Map<String, Any?>)
        fun confidenceLine(snapshot: PositioningSnapshot): String
    }

    private var arrivalCompletion: ArrivalCompletion? = null
    private var arrivalCompletionTimeout: Runnable? = null
    private var arrivalCompletionTerminal: (() -> Unit)? = null
    private var arrivalCompletionResponseGeneration: Long? = null

    /** Background retirement finishes this ending before EnteredBackground advances its epoch. */
    fun retireArrivalForExit() {
        val owner = arrivalCompletion ?: return
        val terminal = arrivalCompletionTerminal ?: return
        val generation = arrivalCompletionResponseGeneration
        if (owner.isCurrent() && generation != null && generation == speech.commandGeneration) {
            speech.failCommand(generation)
        }
        terminal()
    }

    /** The final utterance survives route teardown, but can never finish a replacement walk. */
    internal fun completeArrivalAfterSpeech(
        owner: ArrivalCompletion,
        submitSpeech: (String, () -> Boolean, () -> Unit, () -> Unit) -> Boolean = speech::submitStatus,
    ) {
        if (arrivalCompletion?.isCurrent() == true) return
        arrivalCompletionTimeout?.let(callbacks::remove)
        arrivalCompletion = owner
        arrivalCompletionResponseGeneration = null
        fun current() = arrivalCompletion === owner && owner.isCurrent()
        fun terminal() {
            if (arrivalCompletion !== owner) return
            arrivalCompletion = null
            arrivalCompletionTimeout?.let(callbacks::remove)
            arrivalCompletionTimeout = null
            arrivalCompletionTerminal = null
            arrivalCompletionResponseGeneration = null
            if (owner.isCurrent()) owner.finish()
        }
        arrivalCompletionTerminal = ::terminal
        val timeout = Runnable {
            if (current() && arrivalCompletionResponseGeneration == speech.commandGeneration) {
                speech.failCommand(requireNotNull(arrivalCompletionResponseGeneration))
            }
            terminal()
        }
        arrivalCompletionTimeout = timeout
        callbacks.postDelayed(timeout, ARRIVAL_COMPLETION_TIMEOUT_MS)
        val accepted = try {
            submitSpeech("도착했습니다. 안내를 종료합니다", ::current, ::terminal, ::terminal)
        } catch (_: RuntimeException) {
            terminal()
            false
        }
        if (!accepted) terminal()
        else if (arrivalCompletion === owner) arrivalCompletionResponseGeneration = speech.commandGeneration
    }

    private var navigationSpeechRetryRunnable: Runnable? = null
    private var routeLocationStatusResponseGeneration: Long? = null
    private var positionGuidancePaused = false
    private var lastPositionConfidenceDiagnosticAtMs: Long? = null
    private var lastRouteAlignmentDiagnosticKey: String? = null
    private var lastRouteAlignmentDiagnosticAtMs = 0L
    private var pendingRouteRecoverySpeech: RouteRecoverySpeech? = null
    private var routeGuidanceResumePending = false
    private val decisionSpeech = RouteDecisionSpeechCoordinator(speech, callbacks) {
        routeNavigator.pendingDecisionToken()
    }
    internal val decisionSpeechState: RouteDecisionSpeechCoordinator.State get() = decisionSpeech.state
    private var routeDeviationHapticDecision: RouteNavigatorUserDecision? = null
    private val recheckNotices = RouteRecheckNoticeCoordinator(
        speech, callbacks, elapsedRealtimeMs, ::captureRecheckNoticeOwner,
        ::updateDirectionRecheckNotice,
        { routeNavigator.hasPendingInstruction() || routeNavigator.hasPendingInformation() },
        { routeNavigator.instructionRetryIntervalMs },
        { scheduleNavigationSpeechRetry() },
    )
    internal val recheckNoticeActive: Boolean get() = recheckNotices.active
    internal data class RouteRecoverySpeech(val isCurrent: () -> Boolean, val delivered: () -> Unit)

    val positionPaused: Boolean get() = positionGuidancePaused
    val resumePending: Boolean get() = routeGuidanceResumePending
    fun clearPositionPause() { positionGuidancePaused = false }
    fun clearResume() { routeGuidanceResumePending = false }
    fun clearRecovery() { pendingRouteRecoverySpeech = null }
    fun clearDeviationHaptic() { routeDeviationHapticDecision = null }
    private val routeNavigator get() = navigationRunState.navigator
    private val isRouteActive get() = navigationRunState.active
    private val latestTmapOnRoute get() = navigationRunState.onRoute
    private val directionGuidancePauseReason get() = navigationRunState.directionPauseReason
    private val routeStartStepCount get() = navigationRunState.startStepCount
    private val currentDestination get() = navigationRunState.destination

    /** The route decision, presentation and fresh-fix wait are one user intent. */
    fun requestLocationRecheck(
        expectedToken: RouteNavigatorDecisionToken?,
        startNavigationServices: () -> Unit,
    ): Boolean {
        if (!isRouteActive || routeNavigator.pendingUserDecision() !in setOf(
                RouteNavigatorUserDecision.LOCATION_RECHECK,
                RouteNavigatorUserDecision.REROUTE,
            ) || expectedToken == null
        ) return false
        val decision = routeNavigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION, expectedToken)
        if (decision.reason != "off_route_location_recheck_requested") return false
        effects.decisionActions(decision.pendingUserDecision)
        navigationRunState.pauseDirectionUntilRouteMatch("off_route_recheck")
        startNavigationServices()
        effects.navigationStatus("navigation=off_route_location_recheck_requested auto_resume=false")
        effects.interaction(requireNotNull(decision.instruction))
        return true
    }

    /** Preserve the installed route and resume intent before cancelling its output lifetime. */
    fun restrictGpsNavigation(cancelFeedback: () -> Unit) {
        if (isRouteActive && routeNavigator.hasRoute()) routeGuidanceResumePending = true
        navigationRunState.pauseDirection("environment_gps_quality")
        cancelFeedback()
    }

    /** Only this environment restriction is released; another pause still blocks directions. */
    fun resumeGpsNavigation(startNavigationServices: () -> Unit) {
        if (directionGuidancePauseReason == "environment_gps_quality") navigationRunState.clearDirectionPause()
        startNavigationServices()
    }

    /** An environment recheck shares the idle speech owner; acceptance is not delivery. */
    internal fun offerEnvironmentRecheckNotice(
        message: String,
        isStillCurrent: () -> Boolean,
        onDelivered: () -> Unit = {},
    ) = recheckNotices.offerEnvironment(message, isStillCurrent, onDelivered)

    private fun captureRecheckNoticeOwner(isStillCurrent: () -> Boolean): (() -> Boolean)? {
        val lifecycle = scope.lifecycle ?: return null
        val epoch = lifecycle.currentRuntimeEpochOrNull() ?: return null
        val requestGeneration = scope.requestGeneration
        val feedbackGeneration = scope.feedbackGeneration
        val routeId = routeNavigator.currentRouteId() ?: return null
        val routeVersion = routeNavigator.currentTactileRouteProgress().routeVersion ?: return null
        val positioningOwner = capturePositioningSpeechOwner() ?: return null
        return {
            scope.foreground && scope.runtimeActive && lifecycle.isRuntimeEpochCurrent(epoch) &&
                scope.feedbackCurrent(feedbackGeneration) && isRouteActive && !scope.requestPending &&
                scope.requestGeneration == requestGeneration && routeNavigator.currentRouteId() == routeId &&
                routeNavigator.currentTactileRouteProgress().routeVersion == routeVersion &&
                positioningOwner() && isStillCurrent()
        }
    }

    private fun updateDirectionRecheckNotice() {
        val grace = routeNavigator.currentDirectionGraceSnapshot()
        val episodeId = grace?.episodeId
        val pending = grace != null && episodeId != null &&
            grace.mode == RouteDirectionGraceMode.PROBLEM
        if (!pending) {
            recheckNotices.retireDirection()
            return
        }
        recheckNotices.offerDirection(requireNotNull(episodeId),
            "방향 측정이 계속 불안정해 방향 안내를 잠시 멈춥니다. " +
                "안전한 곳에 멈춰 휴대폰을 고정해 주세요. 방향이 확인되면 안내를 이어갑니다.") {
            val latest = routeNavigator.currentDirectionGraceSnapshot()
            !positionGuidancePaused && scope.navigationAllowed && latest != null && latest.episodeId == episodeId &&
                latest.mode == RouteDirectionGraceMode.PROBLEM
        }
    }

    fun handleRouteLocationUntrusted(): Boolean {
        val update = routeNavigator.onUntrustedLocation() ?: return false
        if (isMainThread()) {
            applyRouteDeviationSafetyUpdate(update)
        } else {
            val expectedToken = routeNavigator.pendingDecisionToken()
            postToUi {
                if (expectedToken == routeNavigator.pendingDecisionToken()) {
                    applyRouteDeviationSafetyUpdate(update)
                }
            }
        }
        return true
    }

    fun applyPositionConfidenceDecision(
        snapshot: PositioningSnapshot,
        emitAnnouncements: Boolean = true,
        routeConfidence: kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceDecision = snapshot.confidence,
    ): Boolean {
        if (scope.developmentBypass) {
            positionGuidancePaused = false
            return false
        }
        val decision = routeConfidence
        if (diagnostics.debug) runCatching {
            val nowMs = elapsedRealtimeMs()
            val diagnosticDue = lastPositionConfidenceDiagnosticAtMs?.let { previous ->
                nowMs < previous || nowMs - previous >= 1_000L
            } ?: true
            if (snapshot.gnssUpdate != null || decision.transition !=
                kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceTransition.STABLE ||
                (decision.guidancePaused && diagnosticDue)
            ) {
                lastPositionConfidenceDiagnosticAtMs = nowMs
                diagnostics.log("WalkSafePositionCheck", diagnostics.confidenceLine(snapshot))
            }
        }
        val pauseReason = "position_quality_${decision.quality.name.lowercase(Locale.US)}"
        val positioningEvidenceInterrupted = decision.guidancePaused && !positionGuidancePaused
        if (positioningEvidenceInterrupted) {
            if (isRouteActive && routeNavigator.hasRoute()) routeGuidanceResumePending = true
            routeNavigator.onPositioningEvidenceInterrupted()
        }
        positionGuidancePaused = decision.guidancePaused
        if (positionGuidancePaused && isRouteActive) {
            speech.cancelNavigation()
            navigationRunState.updateAcceptedRouteMatch(false)
            effects.navigationStatus("navigation=direction_paused reason=$pauseReason")
        }
        updateRouteRecoverySpeech(emitAnnouncements) {
            decision.announcementRequest?.takeIf {
                isRouteActive && routeNavigator.hasRoute() &&
                    it.kind == PositionConfidenceAnnouncementKind.RECOVERED
            }?.let { request ->
                val confidenceLease = position.confidenceLeaseOrNull(request.token) ?: return@let null
                val epoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull() ?: return@let null
                val requestGeneration = scope.requestGeneration
                val routeId = routeNavigator.currentRouteId()
                RouteRecoverySpeech(
                    isCurrent = {
                        scope.foreground && isRouteActive && !positionGuidancePaused &&
                            scope.navigationAllowed && requireNotNull(scope.lifecycle).isRuntimeEpochCurrent(epoch) &&
                            scope.requestGeneration == requestGeneration && routeNavigator.currentRouteId() == routeId &&
                            confidenceLease.isCurrent()
                    },
                    delivered = {
                        confidenceLease.delivered(elapsedRealtimeMs())
                    },
                )
            }
        }
        // Preserve the installed-route start response; paused directions remain blocked immediately.
        // An undelivered confidence notice stays pending and is reconsidered on the next input.
        decision.announcementRequest?.takeIf {
            emitAnnouncements && decision.guidancePaused && isRouteActive && routeNavigator.hasRoute() &&
                scope.navigationAllowed &&
                it.kind != PositionConfidenceAnnouncementKind.RECOVERED &&
                !speech.commandPending && !speech.busy()
        }?.let { request ->
            val pendingDecision = routeNavigator.pendingUserDecision()
            val automaticRecoveryAllowed = routeNavigator.canResumeAfterPositionRecovery()
            val message = when (pendingDecision) {
                RouteNavigatorUserDecision.LOCATION_RECHECK ->
                    if (automaticRecoveryAllowed) (if (request.kind == PositionConfidenceAnnouncementKind.UNAVAILABLE)
                        effects.unavailableLocationMessage() else "위치 오차가 커져 방향 안내를 잠시 보류합니다.") +
                        " 새 위치가 확인되면 자동으로 안내를 이어갑니다."
                    else "현재 위치가 경로에 있는지 확인이 필요합니다. 위치 다시 확인이라고 말씀해 주세요."
                RouteNavigatorUserDecision.REROUTE ->
                    "경로를 벗어나 방향 안내가 멈춰 있습니다. 새 경로, 위치 다시 확인, 길안내 종료 중 선택해 주세요."
                RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION ->
                    "도착했습니다. 안내를 종료합니다"
                null -> when (request.kind) {
                    PositionConfidenceAnnouncementKind.LOW ->
                        "위치 오차가 커서 방향 안내를 잠시 멈춥니다. " +
                            if (automaticRecoveryAllowed) "위치가 확인되면 이어갑니다." else "새 위치를 기다려 주세요."
                    PositionConfidenceAnnouncementKind.UNAVAILABLE ->
                        effects.unavailableLocationMessage() +
                            if (automaticRecoveryAllowed) " 위치가 확인되면 안내를 이어갑니다." else ""
                    PositionConfidenceAnnouncementKind.RECOVERED ->
                        "위치가 다시 확인되어 방향 안내를 이어갑니다."
                }
            }
            if (request.kind != PositionConfidenceAnnouncementKind.RECOVERED) {
                effects.pausedHaptic()
            }
            val confidenceLease = position.confidenceLeaseOrNull(request.token) ?: return@let
            val epoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull() ?: return@let
            val requestGeneration = scope.requestGeneration
            val routeId = routeNavigator.currentRouteId()
            fun current(): Boolean = scope.foreground && isRouteActive && scope.navigationAllowed &&
                requireNotNull(scope.lifecycle).isRuntimeEpochCurrent(epoch) &&
                scope.requestGeneration == requestGeneration && routeNavigator.currentRouteId() == routeId &&
                routeNavigator.pendingUserDecision() == pendingDecision &&
                routeNavigator.canResumeAfterPositionRecovery() == automaticRecoveryAllowed && confidenceLease.isCurrent()
            submitPositionConfidenceAnnouncement(message, ::current) {
                confidenceLease.delivered(elapsedRealtimeMs())
            }
        }
        return decision.guidancePaused
    }

    /** A queued or cancelled status is not a delivered degradation/recovery notice. */
    internal fun submitPositionConfidenceAnnouncement(
        message: String,
        current: () -> Boolean,
        submitSpeech: (String, () -> Boolean, () -> Unit) -> Boolean = { text, guard, completed ->
            speech.submitStatus(text, guard, completed)
        },
        delivered: () -> Unit,
    ): Boolean {
        if (!current()) return false
        val completed = AtomicBoolean(false)
        return submitSpeech(message, current) {
            if (completed.compareAndSet(false, true) && current()) delivered()
        }
    }

    /** The accepted-fix boundary keeps route matching separate from a stopped direction output. */
    internal fun dispatchAcceptedRouteFix(
        location: TrustedLocation,
        rawLocation: TrustedLocation,
        positionPaused: Boolean,
        expectedEpoch: WalkRuntimeEpoch?,
        nowMs: Long,
        navigationOutputsAllowed: () -> Boolean = { scope.navigationAllowed },
        publishDecision: (RouteNavigatorUpdate) -> Boolean = ::applyRouteDeviationSafetyUpdate,
        dispatchGuidance: (TrustedLocation, TrustedLocation) -> Unit = ::updateRouteGuidance,
    ): Boolean {
        val matchedEvidenceCurrent = !positionPaused && directionGuidancePauseReason != "tmap_unavailable"
        if (!positionPaused && !positionGuidancePaused && directionGuidancePauseReason == "tmap_unavailable" &&
            isRouteActive && !scope.requestPending && currentDestination != null && routeNavigator.hasRoute() &&
            expectedEpoch != null && requireNotNull(scope.lifecycle).isRuntimeEpochCurrent(expectedEpoch) &&
            scope.runtimeActive && navigationOutputsAllowed() &&
            position.latestTrusted === location && position.latestRaw === rawLocation &&
            LocationTrustPolicy.freshOrNull(location, nowMs) != null &&
            LocationTrustPolicy.freshOrNull(rawLocation, nowMs) != null &&
            routeNavigator.pendingUserDecision() == null
        ) {
            // Renew only the expired question. Keep the failed-provider direction stop and the
            // original trace/matching gate until the walker explicitly chooses what to do next.
            routeNavigator.rerouteRequestFailed()?.let { decision ->
                navigationRunState.updateAcceptedRouteMatch(false)
                publishDecision(decision.copy(reason = "tmap_unavailable"))
            }
        }
        if (matchedEvidenceCurrent) dispatchGuidance(location, rawLocation)
        return matchedEvidenceCurrent && !location.navigationOnly && !rawLocation.navigationOnly
    }

    fun updateRouteGuidance(
        location: TrustedLocation,
        rawLocation: TrustedLocation = location,
    ) {
        // A location update may start this request earlier in the very same callback. Loading
        // has no installed route yet; it must not be treated as a lost/invalid saved route.
        if (!isRouteActive || scope.requestPending) return
        val expectedWalkEpoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull() ?: return
        if (!scope.developmentBypass &&
            (positionGuidancePaused || directionGuidancePauseReason == "tmap_unavailable")
        ) {
            navigationRunState.updateAcceptedRouteMatch(false)
            val pauseReason = if (positionGuidancePaused) {
                "position_quality"
            } else {
                "tmap_unavailable"
            }
            effects.navigationStatus("navigation=direction_paused reason=$pauseReason")
            return
        }
        val queryAtMs = elapsedRealtimeMs()
        val compass = compassInput.heading(queryAtMs)
        // A sensor callback can publish a newer normal sample while the lookup waits.
        val nowMs = maxOf(queryAtMs, elapsedRealtimeMs())
        val facing = compass?.observation
        val update = routeNavigator.update(
            location = rawLocation,
            nowMs = nowMs,
            requestInFlight = scope.requestPending,
            stepProgressM = routeStepProgressMOrNull(),
            facingObservation = facing,
            facingUnavailableReason = compass?.reason,
            filteredPosition = FilteredRoutePosition(
                point = RoutePoint(location.latitude, location.longitude),
                horizontalAccuracyM = location.accuracyM.toDouble(),
                elapsedRealtimeMs = location.elapsedRealtimeMs,
                uncertaintyKind = location.uncertaintyKind,
                navigationOnly = location.navigationOnly || rawLocation.navigationOnly,
                // S09-45: direction-specific covariance only when it belongs to this exact trusted fix.
                covarianceEnu = position.covariance?.takeIf { !location.navigationOnly && it.first == location }?.second,
                heading = position.course
                    ?.takeIf { position.speed?.speedMps?.let { it > 0.5 } == true }
                    ?.let { heading ->
                    RouteHeadingEstimate(
                        degrees = heading.headingDegreesTrueNorth,
                        standardDeviationDeg = requireNotNull(heading.accuracyDegrees),
                    )
                },
            ),
        )
        recordRouteAlignmentDiagnostic(update, facing, location.accuracyM, nowMs)
        if (update.reason == "arrival_confirmation_required" &&
            update.pendingUserDecision == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION
        ) {
            applyRouteDeviationSafetyUpdate(update)
            return // Arrival owns teardown; no progress, retry, decision question or later cue follows it.
        }
        applyRouteDeviationSafetyUpdate(update)
        val acceptedPosition = routeNavigator.currentAcceptedRouteMatchFor(location.elapsedRealtimeMs) != null &&
            update.reason !in setOf("route_match_untrusted", "location_sample_not_newer")
        if (acceptedPosition && !update.userDecisionRequired && !update.offRoute) {
            navigationRunState.clearDirectionPause()
        }
        navigationRunState.updateAcceptedRouteMatch(acceptedPosition && isRouteActive &&
            !scope.requestPending &&
            update.reason != "route_missing" &&
            update.reason != "polyline_missing" &&
            !update.offRoute &&
            !update.shouldReroute &&
            !update.arrived &&
            !update.userDecisionRequired)
        if (!location.navigationOnly && !rawLocation.navigationOnly) {
            effects.progressBeep(
                nowMs,
                offRoute = !acceptedPosition || update.offRoute || update.userDecisionRequired,
                arrived = update.arrived,
            )
        }
        effects.navigationStatus(
            "navigation=${if (routeNavigator.isWaitingForInitialRouteJoin()) "waiting_for_route_join" else update.reason} offRoute=${update.offRoute} " +
                "arrived=${update.arrived} decisionRequired=${update.userDecisionRequired}",
        )
        if (update.reason in setOf("route_missing", "polyline_missing")) {
            effects.pauseDirection(
                reason = "route_invalid",
                message = "경로 데이터를 확인하지 못했습니다. 안내를 다시 시작해 주세요.",
            )
        }
        if (holdRouteInstructionsForStartAnnouncement(
                initialJoinPending = routeNavigator.isWaitingForInitialRouteJoin(),
                routeUpdate = update,
            )) return
        tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)
    }

    internal fun holdRouteInstructionsForStartAnnouncement(
        locationReady: () -> Boolean = ::installedRouteStartLocationReady,
        submitSpeech: (String, () -> Boolean, () -> Unit) -> Boolean = speech::submitInstalledStart,
        resumeInstructions: () -> Unit = { scheduleNavigationSpeechRetry(0L) },
        initialJoinPending: Boolean = false,
        speechBusy: () -> Boolean = speech::busy,
        deferInstructions: () -> Unit = { scheduleNavigationSpeechRetry() },
        routeUpdate: RouteNavigatorUpdate? = null,
    ): Boolean = installedRouteStartSpeechCoordinator.hold(
        locationReady, submitSpeech, resumeInstructions, initialJoinPending, speechBusy,
        deferInstructions, routeUpdate, onStage = effects::preparationStage,
    )

    internal fun submitInstalledRouteStart(message: String, current: () -> Boolean, terminal: () -> Unit): Boolean =
        speech.submitInstalledStart(message, current, terminal)

    fun installedRouteStartLocationReady(): Boolean {
        if (positionGuidancePaused || routeNavigator.isWaitingForInitialRouteJoin()) return false
        val location = position.currentLocation() ?: return false
        if (location.navigationOnly) return routeNavigator.hasCurrentNavigationOnlyReference(elapsedRealtimeMs())
        return latestTmapOnRoute && routeNavigator.currentAcceptedRouteMatchFor(location.elapsedRealtimeMs) != null
    }

    fun recordRouteAlignmentDiagnostic(
        update: RouteNavigatorUpdate,
        facing: RouteFacingObservation?,
        positionUncertaintyM: Float,
        nowMs: Long,
    ) {
        val alignment = update.routeAlignmentDiagnostic ?: return
        val key = "${scope.requestGeneration}:${alignment.reason}:${alignment.segmentIndex}:${facing?.source}"
        if (lastRouteAlignmentDiagnosticKey == key && nowMs - lastRouteAlignmentDiagnosticAtMs in 0L..1_999L) return
        lastRouteAlignmentDiagnosticKey = key
        lastRouteAlignmentDiagnosticAtMs = nowMs
        val values = RouteGuidanceDiagnostics.alignment(alignment, facing, positionUncertaintyM, nowMs)
        if (diagnostics.debug) runCatching {
            diagnostics.log("WalkSafeNavigation", "route_alignment=$values")
        }
        if (diagnostics.recording) diagnostics.record("route_alignment", values)
    }

    fun cancelRouteLocationStatusSpeech() {
        val generation = routeLocationStatusResponseGeneration ?: return
        routeLocationStatusResponseGeneration = null
        if (generation == speech.commandGeneration) speech.cancelCommand()
    }

    fun cancelNavigationSpeechRetry() {
        val pending = navigationSpeechRetryRunnable ?: return
        navigationSpeechRetryRunnable = null
        callbacks.remove(pending)
    }

    fun isRouteSpeechCurrent(
        epoch: WalkRuntimeEpoch,
        requestGeneration: Int,
        lifecycleGeneration: Int,
    ): Boolean =
        scope.feedbackCurrent(lifecycleGeneration) &&
            (scope.lifecycle != null) &&
            requireNotNull(scope.lifecycle).isRuntimeEpochCurrent(epoch) &&
            scope.runtimeActive && scope.navigationAllowed &&
            capturePositioningSpeechOwner()?.invoke() == true &&
            isRouteActive && !scope.requestPending &&
            scope.requestGeneration == requestGeneration

    fun retryCurrentNavigationSpeechAfterReady(nowMs: Long) {
        if (!(scope.lifecycle != null) || !isRouteActive) return
        val epoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull() ?: return
        val requestGeneration = scope.requestGeneration
        val lifecycleGeneration = scope.feedbackGeneration
        if (!isRouteSpeechCurrent(epoch, requestGeneration, lifecycleGeneration)) return
        cancelNavigationSpeechRetry()
        retryCurrentNavigationSpeech(nowMs, epoch, requestGeneration, lifecycleGeneration)
    }

    fun retryCurrentNavigationSpeechForObservation(
        nowMs: Long,
        firstBadAtMs: Long,
        compass: RouteCompassHeadingResult? = null,
        publishInstruction: Boolean = true,
    ) {
        val epoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull() ?: return
        if (!isRouteSpeechCurrent(epoch, scope.requestGeneration, scope.feedbackGeneration) || positionGuidancePaused) return
        cancelNavigationSpeechRetry()
        retryAdmittedNavigationSpeech(nowMs, epoch, compass ?: compassInput.heading(nowMs),
            firstBadAtMs) { update, currentEpoch, observation ->
            if (publishInstruction) tryDispatchRouteInstruction(update, currentEpoch, observation)
            else if (update.cancelStaleNavigationSpeech) speech.cancelNavigation()
        }
    }

    fun retryCurrentNavigationSpeech(
        nowMs: Long,
        epoch: WalkRuntimeEpoch,
        requestGeneration: Int,
        lifecycleGeneration: Int,
    ) {
        if (!isRouteSpeechCurrent(epoch, requestGeneration, lifecycleGeneration) || positionGuidancePaused) return
        val compass = compassInput.heading(nowMs)
        retryAdmittedNavigationSpeech(nowMs, epoch, compass)
    }

    /** The caller admitted the live route/epoch/position; retry never publishes a new location. */
    internal fun retryAdmittedNavigationSpeech(
        nowMs: Long,
        epoch: WalkRuntimeEpoch,
        compass: RouteCompassHeadingResult?,
        dispatch: (RouteNavigatorUpdate, WalkRuntimeEpoch, RouteCompassHeadingResult?) -> Unit = ::tryDispatchRouteInstruction,
    ) = retryAdmittedNavigationSpeech(nowMs, epoch, compass, null, dispatch)

    internal fun retryAdmittedNavigationSpeech(
        nowMs: Long,
        epoch: WalkRuntimeEpoch,
        compass: RouteCompassHeadingResult?,
        firstBadAtMs: Long?,
        dispatch: (RouteNavigatorUpdate, WalkRuntimeEpoch, RouteCompassHeadingResult?) -> Unit,
    ) {
        if (directionGuidancePauseReason == "tmap_unavailable") return
        val snapshotNowMs = maxOf(nowMs, elapsedRealtimeMs())
        val update = (if (firstBadAtMs == null) routeNavigator.retryGuidance(
            snapshotNowMs, facingObservation = compass?.observation,
            facingUnavailableReason = compass?.reason,
        ) else routeNavigator.retryGuidance(snapshotNowMs, compass?.observation, compass?.reason, firstBadAtMs)) ?: return
        if (holdRouteInstructionsForStartAnnouncement(
                initialJoinPending = routeNavigator.isWaitingForInitialRouteJoin(),
                routeUpdate = update,
            )) return
        dispatch(update, epoch, compass)
        recheckNotices.drain()
    }

    fun scheduleNavigationSpeechRetry(delayMs: Long = 250L) {
        if (!(scope.lifecycle != null) || !isRouteActive) return
        val epoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull() ?: return
        val requestGeneration = scope.requestGeneration
        val lifecycleGeneration = scope.feedbackGeneration
        if (!isRouteSpeechCurrent(epoch, requestGeneration, lifecycleGeneration)) return
        if (navigationSpeechRetryRunnable != null) {
            if (delayMs != 0L) return
            cancelNavigationSpeechRetry()
        }
        lateinit var retry: Runnable
        retry = Runnable {
            if (navigationSpeechRetryRunnable !== retry) return@Runnable
            navigationSpeechRetryRunnable = null
            retryCurrentNavigationSpeech(elapsedRealtimeMs(), epoch, requestGeneration, lifecycleGeneration)
        }
        navigationSpeechRetryRunnable = retry
        callbacks.postDelayed(retry, delayMs)
    }

    /** Formatter used by the real generation record after the current cue is reserved. */
    internal fun routeGuidanceGeneratedValues(
        update: RouteNavigatorUpdate,
        nowMs: Long,
        compass: RouteCompassHeadingResult? = null,
    ): Map<String, Any?> = RouteGuidanceDiagnostics.guidanceGenerated(update, nowMs, compass)

    @JvmOverloads
    fun tryDispatchRouteInstruction(
        update: RouteNavigatorUpdate,
        expectedWalkEpoch: WalkRuntimeEpoch,
        compass: RouteCompassHeadingResult? = null,
    ) {
        if (update.userDecisionRequired) return
        // Manual and retry updates skip applyRouteDeviationSafetyUpdate; a revoked in-flight
        // relative-direction sentence must still stop before any replacement is dispatched.
        if (update.cancelStaleNavigationSpeech) speech.cancelNavigation()
        recheckNotices.drain()
        val instruction = update.instruction
        if (instruction == null) {
            val recoveryUpdate = routeSpeechUpdateForRecovery(update,
                current = {
                    !positionGuidancePaused &&
                        isRouteSpeechCurrent(expectedWalkEpoch, scope.requestGeneration, scope.feedbackGeneration)
                },
                newestGuidance = {
                    val queryAtMs = elapsedRealtimeMs()
                    val latestCompass = compassInput.heading(queryAtMs)
                    val nowMs = maxOf(queryAtMs, elapsedRealtimeMs())
                    routeNavigator.currentGuidance(nowMs, latestCompass?.observation, latestCompass?.reason)
                },
            )
            if (recoveryUpdate !== update && recoveryUpdate.instruction != null) {
                tryDispatchRouteInstruction(recoveryUpdate, expectedWalkEpoch, compassInput.heading(elapsedRealtimeMs()))
                return
            }
            if (update.reason in setOf(
                    "guidance_retry_wait", "guidance_rate_limited", "guidance_already_completed",
                    "guidance_facing_recheck_wait",
                )
            ) {
                scheduleNavigationSpeechRetry()
            }
            return
        }
        val compassRevision = compassInput.revision
        val requestGeneration = scope.requestGeneration
        val lifecycleGeneration = scope.feedbackGeneration
        if (!isRouteSpeechCurrent(expectedWalkEpoch, requestGeneration, lifecycleGeneration)) return
        // Bind the utterance to this collection, not merely whichever new collection is current
        // when a delayed native START or DONE arrives.
        val positioningOwnerCurrent = capturePositioningSpeechOwner() ?: return
        if (!positioningOwnerCurrent()) return
        if (recheckNotices.active || speech.busy() ||
            !speech.canSpeakNavigation(elapsedRealtimeMs())
        ) {
            scheduleNavigationSpeechRetry()
            return
        }
        if (!routeNavigator.reserveInstruction(update)) return
        // S08-34: generation and playback-start checks of one cue share its id in the field log.
        if (diagnostics.recording) diagnostics.record(
            "route_guidance_generated", routeGuidanceGeneratedValues(update, elapsedRealtimeMs(), compass),
        )
        cancelNavigationSpeechRetry()
        cancelRouteLocationStatusSpeech()
        // S08-34 (nav-F5/F6): submission checks record nothing; the engine's actual start check does, once.
        val startRecorded = AtomicBoolean(false)
        val rejectedDirectionStart = AtomicBoolean(false)
        val recovery = pendingRouteRecoverySpeech?.takeIf { it.isCurrent() }
        val accepted = dispatchRouteInstructionSpeech(
            update = update,
            isActionCurrent = {
                val queryAtMs = elapsedRealtimeMs()
                val compassOwned = positioningOwnerCurrent() &&
                    isRouteSpeechCurrent(expectedWalkEpoch, requestGeneration, lifecycleGeneration) &&
                    !compassInput.invalidationPending && compassInput.revision == compassRevision
                val facing = if (compassOwned) compassInput.facing(queryAtMs) else null
                val nowMs = maxOf(queryAtMs, elapsedRealtimeMs())
                var current = compassOwned && routeNavigator.isInstructionCurrent(update, nowMs, facing)
                if (!current && compassOwned &&
                    routeNavigator.prepareRetainedDirectionStart(update, nowMs, facing)
                ) {
                    // Establish the exact packet's soft authorization before returning a native rejection.
                    current = routeNavigator.isInstructionCurrent(update, nowMs, facing)
                    if (!current && speech.validatingStarted) rejectedDirectionStart.set(true)
                }
                if (!current && compassOwned && routeNavigator.prepareFreshDirectionStart(update, nowMs, facing)) {
                    current = routeNavigator.isInstructionCurrent(update, nowMs, facing)
                }
                current.also {
                    if (speech.validatingStarted && startRecorded.compareAndSet(false, true) &&
                        diagnostics.recording
                    ) diagnostics.record(
                        "route_guidance_started", RouteGuidanceDiagnostics.guidanceStartChecked(update, nowMs, current),
                    )
                }
            },
            isInformationCurrent = { information ->
                val nowMs = elapsedRealtimeMs()
                positioningOwnerCurrent() && isRouteSpeechCurrent(expectedWalkEpoch, requestGeneration, lifecycleGeneration) &&
                    speech.canSpeakNavigation(nowMs) &&
                    routeNavigator.isInformationCurrent(information, nowMs)
            },
            reserveInformation = { information ->
                routeNavigator.reserveInformation(information, elapsedRealtimeMs())
            },
            finishInformation = { information ->
                routeNavigator.finishInformation(information)
                recheckNotices.resumeAfterRouteTerminal()
            },
            onActionCompleted = {
                if (positioningOwnerCurrent() && isRouteSpeechCurrent(expectedWalkEpoch, requestGeneration, lifecycleGeneration) &&
                    routeNavigator.isInstructionCompletionCurrent(update, elapsedRealtimeMs())) {
                    routeNavigator.acknowledgeInstruction(update, elapsedRealtimeMs())
                    recheckNotices.resumeAfterRouteTerminal()
                    if (!positionGuidancePaused) routeGuidanceResumePending = false
                    effects.clearWaitingFailure({
                        isRouteSpeechCurrent(expectedWalkEpoch, requestGeneration, lifecycleGeneration) &&
                            !positionGuidancePaused
                    })
                    scheduleNavigationSpeechRetry()
                    effects.showLastResult(instruction)
                    if (diagnostics.debug) diagnostics.log("WalkSafeNavigation", "speech=completed")
                } else {
                    routeNavigator.releaseInstruction(update, elapsedRealtimeMs())
                    recheckNotices.resumeAfterRouteTerminal()
                }
            },
            onActionFailed = {
                if (rejectedDirectionStart.get())
                    routeNavigator.releaseInstructionAfterDirectionStartRejection(update, elapsedRealtimeMs())
                else routeNavigator.releaseInstruction(update, elapsedRealtimeMs())
                recheckNotices.resumeAfterRouteTerminal()
                if (isRouteSpeechCurrent(expectedWalkEpoch, requestGeneration, lifecycleGeneration)) {
                    if (diagnostics.debug) diagnostics.log("WalkSafeNavigation", "speech=retry_pending")
                    scheduleNavigationSpeechRetry()
                }
            },
            speak = { message, validator, completed, failed ->
                dispatchRouteActionWithRecovery(
                    message, recovery.takeIf { message == instruction }, validator, completed, failed,
                ) { text, current, done, failure ->
                    speech.submitNavigation(
                        message = text,
                        isInstructionStillCurrent = current,
                        onCompleted = done,
                        onFailed = failure,
                    )
                }
            },
        )
        if (diagnostics.debug) diagnostics.log(
            "WalkSafeNavigation",
            "speech=${if (accepted) "accepted" else "unavailable"} " +
                "${speech.statusText ?: "tts=absent"}",
        )
    }

    internal fun routeSpeechUpdateForRecovery(
        update: RouteNavigatorUpdate,
        current: () -> Boolean,
        newestGuidance: () -> RouteNavigatorUpdate?,
    ): RouteNavigatorUpdate {
        if (update.instruction != null || update.userDecisionRequired || update.offRoute ||
            update.reason !in setOf("guidance_already_completed", "guidance_rate_limited", "guidance_retry_wait") ||
            (!routeGuidanceResumePending && pendingRouteRecoverySpeech?.isCurrent?.invoke() != true) ||
            !current()) return update
        return newestGuidance() ?: update
    }

    /** Presentation-only compass ticks must not drop a recovery waiting behind another sentence. */
    internal fun updateRouteRecoverySpeech(
        emitAnnouncements: Boolean,
        create: () -> RouteRecoverySpeech?,
    ) {
        pendingRouteRecoverySpeech = if (emitAnnouncements) create()?.takeIf { it.isCurrent() }
            else pendingRouteRecoverySpeech?.takeIf { it.isCurrent() }
    }

    /** Recovery shares the action's output, priority and actual terminal callback; it never flushes it. */
    internal fun dispatchRouteActionWithRecovery(
        message: String,
        recovery: RouteRecoverySpeech?,
        current: () -> Boolean,
        completed: () -> Unit,
        failed: () -> Unit,
        speak: (String, () -> Boolean, () -> Unit, () -> Unit) -> Boolean,
    ): Boolean {
        val ownedRecovery = recovery?.takeIf { it.isCurrent() }
        val terminal = AtomicBoolean(false)
        val done = {
            if (terminal.compareAndSet(false, true)) {
                if (ownedRecovery?.isCurrent?.invoke() == true) ownedRecovery.delivered()
                completed()
            }
            Unit
        }
        val failure = { if (terminal.compareAndSet(false, true)) failed(); Unit }
        val text = if (ownedRecovery == null) message else "안내를 이어갑니다. $message"
        val accepted = speak(text, current, done, failure)
        if (!accepted) failure()
        return accepted
    }

    fun failInterruptedRouteDecisionSpeech(previousResponseGeneration: Long) =
        decisionSpeech.failInterruptedSpeech(previousResponseGeneration)

    fun cancelRouteDecisionSpeech() = decisionSpeech.cancel()

    fun speakRouteDecision(
        message: String,
        token: RouteNavigatorDecisionToken,
        repeatRequested: Boolean = false,
    ): Boolean {
        val epoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull() ?: return false
        val requestGeneration = scope.requestGeneration
        val lifecycleGeneration = scope.feedbackGeneration
        return speakCurrentRouteDecision(message, token, repeatRequested,
            isScopeCurrent = { isRouteSpeechCurrent(epoch, requestGeneration, lifecycleGeneration) })
    }

    /** The coordinator owns delivery; this guard still captures the controller's route scope. */
    internal fun speakCurrentRouteDecision(
        message: String,
        token: RouteNavigatorDecisionToken,
        repeatRequested: Boolean = false,
        isScopeCurrent: () -> Boolean,
        submitSpeech: (String, () -> Boolean, () -> Unit, () -> Unit) -> Boolean = speech::submitStatus,
        enqueueRetry: (Runnable) -> Unit = { callbacks.postDelayed(it, 1_000L) },
        outputBusy: () -> Boolean = speech::outputBusy,
    ): Boolean = decisionSpeech.speak(message, token, repeatRequested,
        isScopeCurrent, submitSpeech, enqueueRetry, outputBusy)

    fun applyRouteDeviationSafetyUpdate(update: RouteNavigatorUpdate): Boolean {
        if (update.reason == "arrival_confirmation_required" &&
            update.pendingUserDecision == RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION
        ) {
            routeNavigator.pendingDecisionToken()?.let { token ->
                effects.beginArrival(token)?.let { completeArrivalAfterSpeech(it) }
            }
            return true
        }
        if (update.cancelStaleNavigationSpeech) {
            speech.cancelNavigation()
        }
        effects.decisionActions(update.pendingUserDecision)
        maybePlayRouteDeviationHaptic(update.pendingUserDecision)
        if (!update.userDecisionRequired) {
            cancelRouteDecisionSpeech()
            return false
        }
        navigationRunState.pauseDirectionUntilRouteMatch(update.reason)
        val token = routeNavigator.pendingDecisionToken()
        if (token != null) {
            update.instruction?.let { speakRouteDecision(it, token) }
        }
        return true
    }

    fun routeDeviationChoicePending(): Boolean =
        routeNavigator.pendingUserDecision() in setOf(
            RouteNavigatorUserDecision.LOCATION_RECHECK,
            RouteNavigatorUserDecision.REROUTE,
            RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,
        )

    fun blockRouteMutationWhileDeviationChoicePending(): Boolean {
        if (scope.developmentBypass) return false
        if (!routeDeviationChoicePending()) return false
        effects.navigationStatus("navigation=route_deviation_choice_required")
        effects.interaction("음성 명령으로 현재 경로 상태를 먼저 확인해 주세요.")
        return true
    }

    fun maybePlayRouteDeviationHaptic(decision: RouteNavigatorUserDecision?) {
        if (decision == routeDeviationHapticDecision) return
        routeDeviationHapticDecision = decision
        when (decision) {
            RouteNavigatorUserDecision.LOCATION_RECHECK ->
                effects.pausedHaptic()
            RouteNavigatorUserDecision.REROUTE ->
                effects.deviationHaptic()
            else -> Unit
        }
    }

    companion object {
        internal const val ARRIVAL_COMPLETION_TIMEOUT_MS = 45_000L
    }

    fun routeStepProgressMOrNull(): Double? {
        val baseline = routeStartStepCount ?: return null
        if (!isRouteActive || !routeNavigator.hasRoute()) return null
        val deltaSteps = position.stepCount - baseline
        if (deltaSteps < 0) return null
        return deltaSteps * position.stepLengthM.toDouble()
    }
}
