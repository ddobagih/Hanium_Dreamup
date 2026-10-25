package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

internal enum class GnssRegistrationFailure {
    SECURITY_EXCEPTION,
    REGISTRATION_REJECTED,
    CLEANUP_PENDING,
}

internal data class GnssQualityObserverStartResult(
    val statusRegistered: Boolean,
    val measurementsRegistered: Boolean,
    val statusFailure: GnssRegistrationFailure?,
    val measurementsFailure: GnssRegistrationFailure?,
    val alreadyStarted: Boolean = false,
    val observerClosed: Boolean = false,
)

/**
 * Owns optional status and raw-measurement feeds for one foreground navigation session.
 * All retained state is an in-memory aggregate and is cleared by stop().
 */
internal class GnssQualityObserver(
    private val source: GnssObservationSource,
    private val estimator: GnssQualityEstimator = GnssQualityEstimator(),
    private val statusStaleAfterNanos: Long = 5_000_000_000L,
    /**
     * Status C/N0 window, used only while the raw-measurement feed is not registered. Android
     * reports both GnssStatus and GnssMeasurement C/N0 at the antenna, so the same thresholds apply.
     */
    private val statusCn0Estimator: GnssQualityEstimator = GnssQualityEstimator(),
    private val onSnapshot: (GnssQualitySnapshot) -> Unit,
) : AutoCloseable {
    private var generation = 0L
    private var active = false
    private var closed = false
    private var statusSubscription: GnssObservationSubscription? = null
    private var measurementSubscription: GnssObservationSubscription? = null
    private var latestStatusSummary: GnssFrequencySummary? = null
    private var latestStatusAtNanos: Long? = null
    // Querying an expired (or older) quality snapshot must not change source admission order.
    private var latestRawAtNanos: Long? = null
    private var lastStartResult: GnssQualityObserverStartResult? = null
    private val pendingCancellations = mutableListOf<GnssObservationSubscription>()

    /**
     * Cancellation handles whose cancel call did not complete. Read after stop/close to retain
     * their retry owner; false on an active observer does not mean its subscriptions are closed.
     * This query never retries cancellation or infers that native callbacks have physically ended.
     */
    @get:Synchronized
    val cleanupPending: Boolean
        get() = pendingCancellations.isNotEmpty()

    init {
        require(statusStaleAfterNanos > 0L)
    }

    @Synchronized
    fun start(): GnssQualityObserverStartResult {
        if (closed) {
            return GnssQualityObserverStartResult(
                statusRegistered = false,
                measurementsRegistered = false,
                statusFailure = null,
                measurementsFailure = null,
                observerClosed = true,
            )
        }
        if (active) {
            return requireNotNull(lastStartResult).copy(alreadyStarted = true)
        }
        retryPendingCancellations()
        if (pendingCancellations.isNotEmpty()) {
            return GnssQualityObserverStartResult(
                statusRegistered = false,
                measurementsRegistered = false,
                statusFailure = GnssRegistrationFailure.CLEANUP_PENDING,
                measurementsFailure = GnssRegistrationFailure.CLEANUP_PENDING,
            )
        }

        active = true
        val currentGeneration = ++generation
        val statusAttempt = subscribeStatus(currentGeneration)
        if (isCurrent(currentGeneration)) statusSubscription = statusAttempt.subscription

        val measurementAttempt = if (isCurrent(currentGeneration)) {
            subscribeMeasurements(currentGeneration)
        } else {
            RegistrationAttempt(null, null)
        }
        if (isCurrent(currentGeneration)) measurementSubscription = measurementAttempt.subscription

        if (!isCurrent(currentGeneration)) {
            cancelOrRetain(statusAttempt.subscription)
            cancelOrRetain(measurementAttempt.subscription)
        }
        val result = GnssQualityObserverStartResult(
            statusRegistered = statusSubscription != null,
            measurementsRegistered = measurementSubscription != null,
            statusFailure = statusAttempt.failure,
            measurementsFailure = measurementAttempt.failure,
            observerClosed = closed,
        )
        if (!result.statusRegistered && !result.measurementsRegistered && isCurrent(currentGeneration)) {
            active = false
            generation += 1L
            clearSessionState()
        }
        lastStartResult = result
        return result
    }

    @Synchronized
    fun snapshot(elapsedRealtimeNanos: Long): GnssQualitySnapshot {
        val rawSnapshot = estimator.snapshot(elapsedRealtimeNanos)
        return mergeStatus(rawSnapshot, elapsedRealtimeNanos)
    }

    @Synchronized
    fun stop() {
        if (active || statusSubscription != null || measurementSubscription != null) {
            stopInternal()
        } else {
            retryPendingCancellations()
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            stopInternal()
            closed = true
        } else {
            retryPendingCancellations()
        }
    }

    private fun subscribeStatus(currentGeneration: Long): RegistrationAttempt = try {
        val subscription = source.subscribeStatus(
            GnssObservationListener { epoch -> handleStatus(currentGeneration, epoch) },
        )
        RegistrationAttempt(
            subscription = subscription,
            failure = if (subscription == null) GnssRegistrationFailure.REGISTRATION_REJECTED else null,
        )
    } catch (_: SecurityException) {
        RegistrationAttempt(null, GnssRegistrationFailure.SECURITY_EXCEPTION)
    }

    private fun subscribeMeasurements(currentGeneration: Long): RegistrationAttempt = try {
        val subscription = source.subscribeMeasurements(
            GnssObservationListener { epoch -> handleMeasurements(currentGeneration, epoch) },
        )
        RegistrationAttempt(
            subscription = subscription,
            failure = if (subscription == null) GnssRegistrationFailure.REGISTRATION_REJECTED else null,
        )
    } catch (_: SecurityException) {
        RegistrationAttempt(null, GnssRegistrationFailure.SECURITY_EXCEPTION)
    }

    @Synchronized
    private fun handleStatus(callbackGeneration: Long, epoch: GnssSignalEpoch) {
        if (!isCurrent(callbackGeneration) || epoch.elapsedRealtimeNanos < 0L ||
            epoch.receivedAtElapsedRealtimeNanos < epoch.elapsedRealtimeNanos ||
            latestStatusAtNanos?.let { epoch.elapsedRealtimeNanos <= it } == true
        ) return
        latestStatusSummary = summarizeGnssFrequencies(epoch.signals)
        latestStatusAtNanos = epoch.elapsedRealtimeNanos
        statusCn0Estimator.addEpoch(
            epoch.elapsedRealtimeNanos,
            epoch.signals,
            epoch.timeSource,
            epoch.timestampUncertaintyNanos,
            epoch.receivedAtElapsedRealtimeNanos,
        )
        val rawSnapshot = estimator.snapshot(epoch.elapsedRealtimeNanos)
        onSnapshot(mergeStatus(rawSnapshot, epoch.elapsedRealtimeNanos))
    }

    @Synchronized
    private fun handleMeasurements(callbackGeneration: Long, epoch: GnssSignalEpoch) {
        if (!isCurrent(callbackGeneration) || epoch.elapsedRealtimeNanos < 0L ||
            epoch.receivedAtElapsedRealtimeNanos < epoch.elapsedRealtimeNanos ||
            latestRawAtNanos?.let { epoch.elapsedRealtimeNanos <= it } == true
        ) return
        val rawSnapshot = estimator.addEpoch(
            epoch.elapsedRealtimeNanos,
            epoch.signals,
            epoch.timeSource,
            epoch.timestampUncertaintyNanos,
            epoch.receivedAtElapsedRealtimeNanos,
        )
        latestRawAtNanos = epoch.elapsedRealtimeNanos
        onSnapshot(mergeStatus(rawSnapshot, epoch.elapsedRealtimeNanos))
    }

    private fun mergeStatus(
        rawSnapshot: GnssQualitySnapshot,
        evaluatedAtNanos: Long,
    ): GnssQualitySnapshot {
        // S09-5: without a raw-measurement subscription the status C/N0 window is the only signal
        // quality evidence. With raw registered, status C/N0 is ignored (no double counting).
        val signalSnapshot = if (measurementSubscription == null && statusSubscription != null) {
            val statusSnapshot = statusCn0Estimator.snapshot(evaluatedAtNanos)
            statusSnapshot.copy(cn0FromSatelliteStatus = statusSnapshot.latestEpochAtNanos != null)
        } else {
            rawSnapshot
        }
        val statusAtNanos = latestStatusAtNanos
        val statusSummary = latestStatusSummary?.takeIf {
            statusAtNanos != null &&
                evaluatedAtNanos >= statusAtNanos &&
                evaluatedAtNanos - statusAtNanos <= statusStaleAfterNanos
        }
        val mergedFrequencySummary = when {
            statusSummary == null -> rawSnapshot.frequencySummary
            statusSummary.state != DualFrequencyObservationState.UNKNOWN -> statusSummary
            else -> rawSnapshot.frequencySummary.copy(
                trackedSatelliteCount = statusSummary.trackedSatelliteCount,
                usedInFixSatelliteCount = statusSummary.usedInFixSatelliteCount,
                usedInFixKnown = statusSummary.usedInFixKnown,
            )
        }
        return signalSnapshot.copy(
            evaluatedAtNanos = evaluatedAtNanos.coerceAtLeast(signalSnapshot.evaluatedAtNanos),
            frequencySummary = mergedFrequencySummary,
        )
    }

    private fun stopInternal() {
        active = false
        generation += 1L
        val statusToCancel = statusSubscription
        val measurementsToCancel = measurementSubscription
        statusSubscription = null
        measurementSubscription = null
        clearSessionState()
        cancelOrRetain(statusToCancel)
        cancelOrRetain(measurementsToCancel)
    }

    private fun clearSessionState() {
        latestStatusSummary = null
        latestStatusAtNanos = null
        latestRawAtNanos = null
        lastStartResult = null
        estimator.clear()
        statusCn0Estimator.clear()
    }

    private fun cancelOrRetain(subscription: GnssObservationSubscription?) {
        if (subscription == null) return
        try {
            subscription.cancel()
        } catch (_: RuntimeException) {
            if (pendingCancellations.none { it === subscription }) {
                pendingCancellations += subscription
            }
        }
    }

    private fun retryPendingCancellations() {
        if (pendingCancellations.isEmpty()) return
        val pending = pendingCancellations.toList()
        pendingCancellations.clear()
        pending.forEach { subscription ->
            cancelOrRetain(subscription)
        }
    }

    private fun isCurrent(callbackGeneration: Long): Boolean =
        active && !closed && callbackGeneration == generation

    private fun unknownSnapshot(evaluatedAtNanos: Long): GnssQualitySnapshot = GnssQualitySnapshot(
        evaluatedAtNanos = evaluatedAtNanos,
        latestEpochAtNanos = null,
        epochCount = 0,
        finiteSignalCount = 0,
        medianCn0DbHz = null,
        p25Cn0DbHz = null,
        dropoutRatio = null,
        volatilityDbHz = null,
        frequencySummary = unknownGnssFrequencySummary(),
        signalEnvironmentRisk = SignalEnvironmentRisk.UNKNOWN,
        signalEnvironmentRiskScore = null,
        measurementNoiseMultiplier = 1.0,
        isStale = false,
    )

    private data class RegistrationAttempt(
        val subscription: GnssObservationSubscription?,
        val failure: GnssRegistrationFailure?,
    )
}
