package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingReason
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassObservationEvent
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassStream
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingObservation
import kr.co.hanium.dreamup.walksafe.navigation.RouteGuidanceDiagnostics
import kr.co.hanium.dreamup.walksafe.navigation.session.NavigationRunState
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** The route-facing event owner and coalescing/cadence ledger; one shared Navigator, no sensor registration in construction. */
internal class RouteCompassSession(
    private val run: NavigationRunState,
    private val source: Source,
    private val tracker: Tracker,
    private val actions: Actions,
    private val callbacks: Callbacks,
    private val diagnostics: RouteGuidanceController.Diagnostics,
    private val elapsedRealtimeMs: () -> Long,
) : RouteGuidanceController.CompassReader {
    /** Source identity is the existing collection binding, never a re-created proxy key. */
    data class CompassObservationOwner(val binding: Any, val epoch: WalkRuntimeEpoch,
        val requestGeneration: Int, val lifecycleGeneration: Int, val routeVersion: Long?)
    class ScheduleLease(val isCurrent: () -> Boolean, val evaluatePosition: (Long) -> Unit)
    interface Source {
        fun captureOwner(): CompassObservationOwner?
        fun ownerCurrent(owner: CompassObservationOwner, exactOwner: Boolean, orientationActive: Boolean): Boolean
        fun collectionCurrent(): Boolean
        fun mayRetainCompass(orientationActive: Boolean): Boolean
        fun captureSchedule(orientationActive: Boolean): ScheduleLease?
        fun headingAllowed(trackerAvailable: () -> Boolean, orientationActive: Boolean): Boolean
    }
    interface Tracker {
        val available: Boolean
        fun listener(listener: (RouteCompassObservationEvent) -> Unit)
        fun start()
        fun stop()
        fun current(event: RouteCompassObservationEvent): Boolean
        fun heading(nowMs: Long): RouteCompassHeadingResult
    }
    interface Actions {
        fun cancelSpeech()
        fun retry(nowMs: Long)
        fun retryObservation(nowMs: Long, firstBadAtMs: Long, compass: RouteCompassHeadingResult? = null,
            publishInstruction: Boolean = true)
        fun refreshPresentation()
    }
    interface Callbacks {
        val viewReady: Boolean
        fun post(task: Runnable)
        fun postDelayed(task: Runnable, delayMs: Long): Boolean
        fun remove(task: Runnable)
    }

    private var orientationLocationOwnerActive = false
    private var lastRouteCompassStatusKey: String? = null
    /** S08-8: the compass cause of the latest route facing lookup, passed with that observation. */
    private var latestRouteFacingUnavailableReason: RouteCompassHeadingReason? = null
    private var pendingRouteCompassRefresh: Runnable? = null
    private val routeCompassObservedSamples = mutableMapOf<RouteCompassStream, Long>()
    @Volatile private var compassObservationOwner: CompassObservationOwner? = null
    private val compassObservationLock = Any()
    private var queuedCompassObservation: RouteCompassObservationEvent? = null
    private var queuedCompassInvalidation: RouteCompassObservationEvent? = null
    private var queuedCompassHardInvalidation: RouteCompassObservationEvent? = null
    private var queuedCompassConsumer: Runnable? = null
    @Volatile private var compassSpeechEvidenceRevision = 0L
    @Volatile private var compassInvalidationPending = false

    private val routeNavigator get() = run.navigator
    override val revision: Long get() = compassSpeechEvidenceRevision
    override val invalidationPending: Boolean get() = compassInvalidationPending
    val orientationActive: Boolean get() = orientationLocationOwnerActive
    val lastUnavailableReason: RouteCompassHeadingReason? get() = latestRouteFacingUnavailableReason
    override fun heading(nowMs: Long) = currentRouteCompassHeadingResult(nowMs)
    override fun facing(nowMs: Long) = currentRouteFacingObservation(nowMs)
    fun clearLocationOwner() { orientationLocationOwnerActive = false; lastRouteCompassStatusKey = null }

    fun ensureEarthOrientationForLocation() {
        if (!tracker.available) return
        orientationLocationOwnerActive = true
        tracker.listener { onRouteCompassObservation(it) }
        tracker.start()
        scheduleRouteCompassPresentationUpdate()
    }
    fun retainLocationCompassOrStop() {
        if (!tracker.available) return
        if (source.mayRetainCompass(orientationLocationOwnerActive)) ensureEarthOrientationForLocation()
        else tracker.stop()
    }
    fun bindCompassObservationOwner() {
        val current = source.captureOwner() ?: return
        if (compassObservationOwner != current) {
            synchronized(compassObservationLock) {
                compassObservationOwner?.let { previous ->
                    val sameSensorSource = previous.binding === current.binding && previous.epoch == current.epoch &&
                        previous.requestGeneration == current.requestGeneration && previous.routeVersion == current.routeVersion &&
                        source.collectionCurrent()
                    val hardFaultPending = compassInvalidationPending || queuedCompassHardInvalidation != null
                    if (sameSensorSource && previous.lifecycleGeneration != current.lifecycleGeneration && !hardFaultPending) {
                        routeNavigator.invalidateDirectionSpeechClaims()
                    } else routeNavigator.invalidateDirectionClaim()
                }
                compassObservationOwner = current
                queuedCompassConsumer = null
                queuedCompassObservation = null
                queuedCompassInvalidation = null
                queuedCompassHardInvalidation = null
                compassInvalidationPending = false
            }
            routeCompassObservedSamples.clear()
        }
    }
    private fun compassObservationOwnerIsCurrent(owner: CompassObservationOwner): Boolean =
        source.ownerCurrent(owner, compassObservationOwner === owner, orientationLocationOwnerActive)

    fun scheduleRouteCompassPresentationUpdate(observation: RouteCompassObservationEvent? = null,
        firstBadAtMs: Long? = null) {
        if (!callbacks.viewReady) return
        val lease = source.captureSchedule(orientationLocationOwnerActive) ?: return
        bindCompassObservationOwner()
        scheduleAdmittedRouteCompassUpdate(
            observedAtMs = observation?.sensorTimestampNanos,
            stream = observation?.stream ?: RouteCompassStream.ROTATION_VECTOR,
            invalidated = observation?.invalidated == true,
            isCurrent = lease.isCurrent,
            evaluate = {
                val nowMs = elapsedRealtimeMs()
                lease.evaluatePosition(nowMs)
                currentRouteFacingObservation(nowMs)
                if (firstBadAtMs == null) actions.retry(nowMs)
                else actions.retryObservation(nowMs, firstBadAtMs)
                actions.refreshPresentation()
                scheduleRouteCompassPresentationUpdate()
            },
            postDelayed = callbacks::postDelayed,
            remove = callbacks::remove,
        )
    }

    /** Called on the sensor thread outside the tracker lock. Keep a BAD before a coalesced GOOD. */
    internal fun onRouteCompassObservation(
        event: RouteCompassObservationEvent,
        trackerCurrent: (RouteCompassObservationEvent) -> Boolean = { tracker.current(it) },
        sdkSourceCurrent: () -> Boolean = {
            source.collectionCurrent()
        },
        post: (Runnable) -> Unit = { callbacks.post(it) },
        cancelSpeech: () -> Unit = { actions.cancelSpeech(); Unit },
        invalidateClaim: (RouteCompassObservationEvent) -> Unit = {
            actions.retryObservation(elapsedRealtimeMs(), it.receivedAtMs,
                it.heading, publishInstruction = false)
        },
        schedule: (RouteCompassObservationEvent, Long?) -> Unit = ::scheduleRouteCompassPresentationUpdate,
    ) {
        val consumer = synchronized(compassObservationLock) {
            val owner = compassObservationOwner ?: return
            if (!compassObservationOwnerIsCurrent(owner) || !trackerCurrent(event)) return
            // Serialize classification with output rebinding so a measured hard event cannot
            // fall between the pending-fault check and the owner's queue replacement.
            // A receipt may wait behind a newer GPS update. Read the query clock after
            // entering the Navigator monitor, then classify both claims against one state.
            // Original sample/receipt times still own freshness and the first BAD deadline.
            val (invalidated, hardInvalidated) = synchronized(routeNavigator) {
                val nowMs = elapsedRealtimeMs()
                (event.invalidated || routeNavigator.hasInvalidatedDirectionClaim(
                    nowMs, event.heading.observation)) to routeNavigator.hasHardInvalidatedDirectionClaim(
                    nowMs, event.heading.observation)
            }
            if (invalidated && !hardInvalidated) routeNavigator.recordFirstDirectionFaultReceipt(
                event.receivedAtMs, owner.routeVersion,
            ) { compassObservationOwnerIsCurrent(owner) && trackerCurrent(event) }
            if (!invalidated && !hardInvalidated && queuedCompassInvalidation == null && !compassInvalidationPending &&
                !routeNavigator.isDirectionRecheckPending()) return
            val admittedEvent = if (invalidated == event.invalidated) event else event.copy(invalidated = true)
            if (compassObservationOwner !== owner) return
            if (hardInvalidated) {
                compassSpeechEvidenceRevision += 1L
                compassInvalidationPending = true
                if (queuedCompassHardInvalidation == null) queuedCompassHardInvalidation = admittedEvent
            }
            if (invalidated || hardInvalidated) {
                if (queuedCompassInvalidation == null) queuedCompassInvalidation = admittedEvent
            }
            queuedCompassObservation = admittedEvent
            if (queuedCompassConsumer != null) return
            lateinit var pending: Runnable
            pending = Runnable {
                val observations = synchronized(compassObservationLock) {
                    if (queuedCompassConsumer !== pending) return@Runnable
                    queuedCompassConsumer = null
                    val pair = Triple(queuedCompassInvalidation, queuedCompassObservation, queuedCompassHardInvalidation)
                    queuedCompassInvalidation = null
                    queuedCompassObservation = null
                    queuedCompassHardInvalidation = null
                    pair
                }
                if (!compassObservationOwnerIsCurrent(owner) || !sdkSourceCurrent()) return@Runnable
                val latest = observations.second ?: return@Runnable
                if (!trackerCurrent(latest)) return@Runnable
                val fault = observations.first?.takeIf(trackerCurrent)
                val hardFault = observations.third?.takeIf(trackerCurrent)
                if (hardFault != null) {
                    routeNavigator.invalidateDirectionClaim()
                    cancelSpeech()
                }
                if (fault != null) {
                    // Observe the first soft fault before GOOD; Nav alone owns retention/deadline.
                    invalidateClaim(fault)
                }
                synchronized(compassObservationLock) {
                    compassInvalidationPending = queuedCompassHardInvalidation != null
                }
                schedule(latest, fault?.receivedAtMs)
            }
            queuedCompassConsumer = pending
            pending
        }
        post(consumer)
    }

    /** Schedules the actual admitted compass evaluation; source ownership remains in the caller. */
    internal fun scheduleAdmittedRouteCompassUpdate(
        observedAtMs: Long? = null,
        stream: RouteCompassStream = RouteCompassStream.ROTATION_VECTOR,
        invalidated: Boolean = false,
        isCurrent: () -> Boolean,
        evaluate: () -> Unit,
        postDelayed: (Runnable, Long) -> Boolean,
        remove: (Runnable) -> Unit = {},
    ) {
        if (!isCurrent()) return
        val newSample = observedAtMs != null && observedAtMs >= 0L &&
            routeCompassObservedSamples[stream]?.let { observedAtMs > it } != false
        if (newSample) routeCompassObservedSamples[stream] = requireNotNull(observedAtMs)
        if (invalidated || newSample && routeNavigator.isDirectionRecheckPending()) {
            pendingRouteCompassRefresh?.let(remove)
            pendingRouteCompassRefresh = null
            evaluate()
            return
        }
        if (pendingRouteCompassRefresh != null) return
        lateinit var refresh: Runnable
        refresh = Runnable {
            if (pendingRouteCompassRefresh !== refresh) return@Runnable
            pendingRouteCompassRefresh = null
            if (!isCurrent()) return@Runnable
            evaluate()
        }
        pendingRouteCompassRefresh = refresh
        if (!postDelayed(refresh, 300L)) pendingRouteCompassRefresh = null
    }

    fun stopRouteCompassPresentationUpdates() {
        routeNavigator.invalidateDirectionClaim()
        synchronized(compassObservationLock) {
            compassObservationOwner = null
            queuedCompassConsumer = null
            queuedCompassObservation = null
            queuedCompassInvalidation = null
            queuedCompassHardInvalidation = null
            compassInvalidationPending = false
        }
        routeCompassObservedSamples.clear()
        pendingRouteCompassRefresh?.let { callback ->
            if (callbacks.viewReady) callbacks.remove(callback)
        }
        pendingRouteCompassRefresh = null
    }

    /** Route speech follows the rear camera axis; step/PDR mounting confirmation stays separate. */
    fun currentRouteFacingObservation(nowMs: Long): RouteFacingObservation? =
        currentRouteCompassHeadingResult(nowMs)?.observation

    fun currentRouteCompassHeadingResult(nowMs: Long): RouteCompassHeadingResult? {
        latestRouteFacingUnavailableReason = null
        if (!source.headingAllowed({ tracker.available }, orientationLocationOwnerActive)
        ) return null
        val facing = tracker.heading(nowMs)
        val observation = facing.observation
        latestRouteFacingUnavailableReason = facing.reason
        val statusKey = "${facing.reason}:${observation?.source}"
        if (lastRouteCompassStatusKey != statusKey) {
            lastRouteCompassStatusKey = statusKey
            if (diagnostics.debug) runCatching {
                val evidence = facing.checkEvidence
                diagnostics.log("WalkSafeNavigation", "compass=$statusKey heading=${observation?.degreesTrueNorth}" +
                    " rejected_check=${evidence?.rejectedCheck}" +
                    " magnetic_ut=${evidence?.magneticFieldMicrotesla}" +
                    " expected_magnetic_ut=${evidence?.expectedMagneticFieldMicrotesla}" +
                    " magnetic_allowance_ut=${evidence?.magneticFieldAllowanceMicrotesla}" +
                    " inclination_residual_deg=${evidence?.magneticInclinationResidualDegrees}")
            }
            if (diagnostics.recording) {
                // S08-34: allowlisted keys, so the persisted chain keeps more than the reason.
                diagnostics.record("route_compass_state_changed", RouteGuidanceDiagnostics.compassState(facing, nowMs))
            }
        }
        return facing
    }
}
