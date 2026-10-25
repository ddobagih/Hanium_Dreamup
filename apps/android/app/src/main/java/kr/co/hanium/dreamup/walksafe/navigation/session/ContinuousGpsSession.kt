package kr.co.hanium.dreamup.walksafe.navigation.session

import android.location.Location
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.AcceptedGnssSampleRegistry
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssObservationDisposition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssHardRejectReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssSampleFingerprint
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianPositionEstimatorConfig
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** One collection-source ledger and fresh-fix request; the injected quality gate remains shared with the evidence store. */
internal class ContinuousGpsSession(
    private val source: SourceReader,
    private val runtimeGpsQualityGate: RuntimeMeasuredQualityGate,
    private val evidence: EvidenceWriter,
    private val actions: Actions,
    private val clock: () -> Long,
) {
    interface SourceReader {
        fun current(): ContinuousGpsSource?
        val latestTrusted: TrustedLocation?
        val latestRaw: TrustedLocation?
        fun nativeCheckCurrent(epoch: WalkRuntimeEpoch): Boolean
    }
    interface EvidenceWriter {
        fun clearGpsEvidence()
        fun record(epoch: WalkRuntimeEpoch, observedAtMs: Long, trustedFixAvailable: Boolean?,
            accuracyM: Double?, elapsedRealtimeMs: () -> Long, singleOutlier: Boolean)
    }
    interface Actions {
        fun clearDisconnectedRecovery()
        fun prewalkEvidenceChanged()
        fun requestFresh(result: (android.location.Location?) -> Unit): () -> Unit
        fun consumeFresh(location: android.location.Location, receivedAtMs: Long)
    }
    val owner: ContinuousGpsSource? get() = continuousGpsSource
    fun currentSource(): ContinuousGpsSource? = source.current()
    fun hasCurrentGpsFailureEpisode(): Boolean = continuousGpsSource?.let {
        it == source.current() && runtimeGpsQualityGate.gpsFailureStartedAtElapsedRealtimeMs() != null
    } == true
    fun retireOwner() { continuousGpsSource = null }

    @Volatile
    private var continuousGpsSource: ContinuousGpsSource? = null
    private var gpsRecoveryRequest: GpsRecoveryRequest? = null
    private val acceptedGnssSamples = AcceptedGnssSampleRegistry<ContinuousGpsSource>(
        PedestrianPositionEstimatorConfig().maximumObservationAgeMs,
    )
    private class GpsRecoveryRequest(
        val source: ContinuousGpsSource,
        val afterObservationAtMs: Long?,
        val recheckStartedAtMs: Long?,
        val evaluationPeriod: Long?,
    ) {
        var cancel: (() -> Unit)? = null
        var completed = false
    }
    /** Re-evaluates original evidence without retagging it or renewing its failure clock. */
    internal fun currentContinuousGpsEvidence(epoch: WalkRuntimeEpoch, nowMs: Long): MeasuredEnvironmentEvidence? {
        val source = continuousGpsSource ?: return null
        if (source != this.source.current()) {
            clearContinuousGpsHistory()
            return null
        }
        if (source.epoch != epoch) return null
        // Runtime watchdogs also remeasure when a one-shot ended without another stream callback.
        // Reconciliation uses the original episode clock and cannot count a new GNSS sample.
        reconcileContinuousGpsRecovery(
            elapsedRealtimeMs = { nowMs },
            // The assessment time chooses a period; a later SDK callback has its own receipt time.
            consumeFresh = { location, _ -> actions.consumeFresh(location, clock()) },
        )
        if (continuousGpsSource != source || this.source.current() != source) {
            clearContinuousGpsHistory()
            return null
        }
        return runtimeGpsQualityGate.current(nowMs)
    }

    fun clearContinuousGpsHistory() {
        acceptedGnssSamples.reset()
        cancelGpsRecoveryRequest()
        actions.clearDisconnectedRecovery()
        if (continuousGpsSource == null) return
        continuousGpsSource = null
        runtimeGpsQualityGate.reset()
        evidence.clearGpsEvidence()
    }

    internal fun shouldSkipAcceptedGnssSample(
        sample: GnssSampleFingerprint,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ): Boolean {
        val source = source.current() ?: run {
            acceptedGnssSamples.reset()
            return false
        }
        return acceptedGnssSamples.shouldSkip(source, sample, receivedAtElapsedRealtimeMs, measurementNoiseMultiplier)
    }

    internal fun shouldIgnoreSupersededGnssSample(
        sample: GnssSampleFingerprint,
        snapshot: PositioningSnapshot,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ): Boolean {
        val update = snapshot.gnssUpdate ?: return false
        if (update.disposition != GnssObservationDisposition.HARD_REJECTED ||
            update.hardRejectReason != kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssHardRejectReason.OUT_OF_ORDER
        ) return false
        val source = source.current() ?: run {
            acceptedGnssSamples.reset()
            return false
        }
        if (source != continuousGpsSource) return false
        if (!hasFreshSupersedingPosition(sample, receivedAtElapsedRealtimeMs)) return false
        return acceptedGnssSamples.isSuperseded(
            source, sample, update.disposition, update.hardRejectReason,
            receivedAtElapsedRealtimeMs, measurementNoiseMultiplier,
        )
    }

    /** Ignore only a historical accuracy refusal before it can alter estimator confidence. */
    internal fun shouldIgnoreSupersededInvalidAccuracyGnssSample(
        sample: GnssSampleFingerprint,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ): Boolean {
        val source = source.current() ?: run {
            acceptedGnssSamples.reset()
            return false
        }
        if (source != continuousGpsSource ||
            !hasFreshSupersedingPosition(sample, receivedAtElapsedRealtimeMs)
        ) return false
        return acceptedGnssSamples.isSupersededInvalidAccuracy(
            source, sample, receivedAtElapsedRealtimeMs, measurementNoiseMultiplier,
        )
    }

    /** The expired callback cannot remove a newer real position or restart its failure episode. */
    internal fun shouldIgnoreSupersededStaleGnssSample(
        sample: GnssSampleFingerprint,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ): Boolean {
        val source = source.current() ?: run {
            acceptedGnssSamples.reset()
            return false
        }
        val trusted = this.source.latestTrusted ?: return false
        val raw = this.source.latestRaw ?: return false
        if (source != continuousGpsSource ||
            !hasFreshSupersedingPosition(sample, receivedAtElapsedRealtimeMs) ||
            trusted.elapsedRealtimeMs <= sample.elapsedRealtimeNanos / 1_000_000L ||
            raw.accuracyM !in 0f..15f
        ) return false
        return acceptedGnssSamples.isSupersededStale(
            source, sample, receivedAtElapsedRealtimeMs, measurementNoiseMultiplier,
        )
    }

    private fun hasFreshSupersedingPosition(
        sample: GnssSampleFingerprint,
        receivedAtElapsedRealtimeMs: Long,
    ): Boolean {
        val trusted = this.source.latestTrusted ?: return false
        val raw = this.source.latestRaw ?: return false
        if (receivedAtElapsedRealtimeMs < 0L ||
            raw.elapsedRealtimeMs <= sample.elapsedRealtimeNanos / 1_000_000L ||
            listOf(trusted, raw).any { location ->
                location.elapsedRealtimeMs < 0L ||
                    !location.latitude.isFinite() || location.latitude <= -90.0 || location.latitude >= 90.0 ||
                    !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
                    !location.accuracyM.isFinite() || location.accuracyM < 0f ||
                    LocationTrustPolicy.freshOrNull(location, receivedAtElapsedRealtimeMs) == null
            }
        ) return false
        return true
    }

    internal fun recordAcceptedGnssSample(
        sample: GnssSampleFingerprint,
        disposition: GnssObservationDisposition?,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ) {
        val source = source.current() ?: run {
            acceptedGnssSamples.reset()
            return
        }
        acceptedGnssSamples.recordAccepted(source, sample, disposition, receivedAtElapsedRealtimeMs, measurementNoiseMultiplier)
    }

    /** Uses the estimator's actual rejection outcome, after the raw callback's owner admission. */
    internal fun recordPositioningGpsObservation(
        snapshot: PositioningSnapshot,
        observedAtElapsedRealtimeMs: Long,
        horizontalAccuracyMeters: Double?,
        elapsedRealtimeMs: () -> Long = clock,
        onPrewalkEvidenceChanged: () -> Unit = actions::prewalkEvidenceChanged,
    ) {
        val hardRejected = snapshot.gnssUpdate?.disposition == GnssObservationDisposition.HARD_REJECTED
        val failure = when {
            hardRejected && !snapshot.singleOutlierAgainstFreshEstimate &&
                snapshot.transientOutlierAgainstFreshEstimate -> RuntimeMeasuredQualityGate.GpsMeasurementFailure.TRANSIENT_CORRUPT_JUMP
            hardRejected -> snapshot.gnssUpdate?.hardRejectReason.measuredQualityFailure()
            snapshot.gnssUpdate != null && horizontalAccuracyMeters == null ->
                RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY
            else -> null
        }
        val source = source.current()
        val recordObservation = {
            recordGeneralLocationGpsObservation(
                observedAtElapsedRealtimeMs = { observedAtElapsedRealtimeMs },
                trustedFixAvailable = !hardRejected && snapshot.filtered != null,
                horizontalAccuracyMeters = horizontalAccuracyMeters,
                elapsedRealtimeMs = elapsedRealtimeMs,
                onPrewalkEvidenceChanged = onPrewalkEvidenceChanged,
                singleOutlierAgainstFreshEstimate = snapshot.singleOutlierAgainstFreshEstimate,
            )
        }
        if (failure != null && source != null) {
            runtimeGpsQualityGate.withGpsMeasurementFailure(
                source.epoch, observedAtElapsedRealtimeMs, source.profile.profileId, failure, recordObservation,
            )
        } else {
            recordObservation()
        }
    }

    /** Only measured quality failures can keep an already published, source-owned route fix. */
    internal fun retainsPublishedPositionDuringGpsFailure(snapshot: PositioningSnapshot, nowMs: Long,
        allowMissingAccuracyForWarming: Boolean = false): Boolean {
        val rejectedMeasuredFailure = snapshot.gnssUpdate?.disposition == GnssObservationDisposition.HARD_REJECTED &&
            snapshot.gnssUpdate.hardRejectReason.measuredQualityFailure() != null
        val missingAccuracy = allowMissingAccuracyForWarming && snapshot.raw?.reportedHorizontalAccuracyM == null &&
            snapshot.gnssUpdate?.disposition in setOf(GnssObservationDisposition.INITIALIZED,
                GnssObservationDisposition.REINITIALIZED, GnssObservationDisposition.APPLIED) &&
            snapshot.confidence.failureReason == null
        if (!rejectedMeasuredFailure && !missingAccuracy) return false
        val source = continuousGpsSource ?: return false
        if (source != this.source.current()) return false
        if (runtimeGpsQualityGate.current(nowMs)?.status != EnvironmentEvidenceStatus.PASS ||
            runtimeGpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs() == null
        ) return false
        return listOf(this.source.latestTrusted, this.source.latestRaw).all { location ->
            location != null && location.latitude.isFinite() && location.latitude > -90.0 && location.latitude < 90.0 &&
                location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
                location.accuracyM.isFinite() && location.accuracyM in 0f..15f &&
                location.elapsedRealtimeMs >= 0L && LocationTrustPolicy.freshOrNull(location, nowMs) != null
        }
    }

    private fun GnssHardRejectReason?.measuredQualityFailure(): RuntimeMeasuredQualityGate.GpsMeasurementFailure? = when (this) {
        GnssHardRejectReason.INVALID_ACCURACY -> RuntimeMeasuredQualityGate.GpsMeasurementFailure.INVALID_ACCURACY
        GnssHardRejectReason.NUMERICAL_FAILURE -> RuntimeMeasuredQualityGate.GpsMeasurementFailure.NUMERICAL_FAILURE
        GnssHardRejectReason.CORRUPT_JUMP -> RuntimeMeasuredQualityGate.GpsMeasurementFailure.CORRUPT_JUMP
        else -> null
    }

    /** Called only after the general callback's SDK/source admission checks. */
    internal fun recordGeneralLocationGpsObservation(
        observedAtElapsedRealtimeMs: () -> Long,
        trustedFixAvailable: Boolean?,
        horizontalAccuracyMeters: Double?,
        elapsedRealtimeMs: () -> Long = clock,
        onPrewalkEvidenceChanged: () -> Unit = actions::prewalkEvidenceChanged,
        singleOutlierAgainstFreshEstimate: Boolean = false,
        requestFreshGps: ((android.location.Location?) -> Unit) -> (() -> Unit) = actions::requestFresh,
        consumeFreshGps: (android.location.Location, Long) -> Unit = actions::consumeFresh,
    ) {
        val source = source.current() ?: run {
            clearContinuousGpsHistory()
            return
        }
        if (continuousGpsSource != source) {
            clearContinuousGpsHistory()
            runtimeGpsQualityGate.reset()
            continuousGpsSource = source
        }
        evidence.record(
            source.epoch, observedAtElapsedRealtimeMs(), trustedFixAvailable,
            horizontalAccuracyMeters, elapsedRealtimeMs, singleOutlierAgainstFreshEstimate,
        )
        reconcileContinuousGpsRecovery(elapsedRealtimeMs, requestFreshGps, consumeFreshGps)
        if (this.source.nativeCheckCurrent(source.epoch)) onPrewalkEvidenceChanged()
    }

    fun cancelGpsRecoveryRequest() {
        val request = gpsRecoveryRequest
        gpsRecoveryRequest = null
        request?.cancel?.invoke()
        request?.cancel = null
    }

    /** Rechecks once per original failure period; watchdogs and duplicate callbacks cannot renew it. */
    internal fun reconcileContinuousGpsRecovery(
        elapsedRealtimeMs: () -> Long = clock,
        requestFresh: ((android.location.Location?) -> Unit) -> (() -> Unit) = actions::requestFresh,
        consumeFresh: (android.location.Location, Long) -> Unit = actions::consumeFresh,
    ) {
        val source = source.current()
        if (source == null || source != continuousGpsSource) {
            cancelGpsRecoveryRequest()
            return
        }
        val raw = runtimeGpsQualityGate.latestRawObservation() ?: return
        val nowMs = elapsedRealtimeMs()
        runtimeGpsQualityGate.current(nowMs)
        val normalized = OfficialEnvironmentPolicy.assess(source.epoch, nowMs, raw, null, null, source.profile)
            .factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY]
        if (normalized == EnvironmentEvidenceStatus.PASS) {
            val requestedAfter = gpsRecoveryRequest?.afterObservationAtMs
            if (requestedAfter == null || raw.observedAtElapsedRealtimeMs?.let { it > requestedAfter } == true) {
                cancelGpsRecoveryRequest()
            }
            return
        }
        requestFreshGpsForCurrentSource(elapsedRealtimeMs, requestFresh, consumeFresh)
    }

    internal fun requestFreshGpsForCurrentSource(
        elapsedRealtimeMs: () -> Long = clock,
        requestFresh: ((android.location.Location?) -> Unit) -> (() -> Unit) = actions::requestFresh,
        consumeFresh: (android.location.Location, Long) -> Unit = actions::consumeFresh,
    ) {
        val source = source.current() ?: return
        if (source != continuousGpsSource) return
        val nowMs = elapsedRealtimeMs()
        val failureStartedAtMs = runtimeGpsQualityGate.gpsFailureStartedAtElapsedRealtimeMs()
        // A silent stream has no measured-quality failure episode. Its recheck clock starts
        // at the original PASS expiry, without changing that observation or the quality gate.
        val startedAtMs = failureStartedAtMs ?: expiredGpsEvidenceAtElapsedRealtimeMs(source, nowMs)
        val evaluationPeriod = if (failureStartedAtMs != null) {
            runtimeGpsQualityGate.gpsFailureEvaluationPeriod(nowMs)
        } else startedAtMs?.let { (nowMs - it) / GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS }
        val previous = gpsRecoveryRequest
        if (previous?.source == source && (evaluationPeriod == null ||
            previous.recheckStartedAtMs == startedAtMs && previous.evaluationPeriod == evaluationPeriod)
        ) return
        cancelGpsRecoveryRequest()
        val request = GpsRecoveryRequest(
            source, runtimeGpsQualityGate.latestRawObservation()?.observedAtElapsedRealtimeMs,
            startedAtMs, evaluationPeriod,
        )
        // Publish before entering the SDK: synchronous completion/cancellation cannot orphan it.
        gpsRecoveryRequest = request
        val cancel = requestFresh { location ->
            if (gpsRecoveryRequest !== request || request.completed) return@requestFresh
            request.completed = true
            request.cancel = null
            if (location != null && continuousGpsSource == source &&
                this.source.current() == source) {
                consumeFresh(location, elapsedRealtimeMs())
            }
        }
        if (gpsRecoveryRequest !== request || request.completed) cancel() else request.cancel = cancel
    }

    private fun expiredGpsEvidenceAtElapsedRealtimeMs(source: ContinuousGpsSource, nowMs: Long): Long? {
        val raw = runtimeGpsQualityGate.latestRawObservation() ?: return null
        if (raw.status != EnvironmentEvidenceStatus.PASS || raw.factor != OfficialEnvironmentFactor.GPS_QUALITY ||
            raw.epoch != source.epoch || raw.measurementProfileId != source.profile.profileId
        ) return null
        val observedAtMs = raw.observedAtElapsedRealtimeMs?.takeIf { it >= 0L } ?: return null
        val maximumAgeMs = raw.maximumEvidenceAgeMs?.takeIf { it >= 0L }
            ?.let { minOf(it, source.profile.maximumMeasuredEvidenceAgeMs) } ?: return null
        if (nowMs < observedAtMs || nowMs - observedAtMs <= maximumAgeMs) return null
        return observedAtMs + maximumAgeMs
    }
}

internal data class ContinuousGpsSource(val binding: Any, val epoch: WalkRuntimeEpoch, val profile: ApprovedOfficialEnvironmentProfile)
