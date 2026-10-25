package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssObservationDisposition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssHardRejectReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HorizontalUncertaintyKind
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceDecision
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot

/** A bounded route capability. The estimator and its original confidence are never changed. */
internal class RouteWarmingProjection(private val durationMs: Long = 9_000L) {
    init { require(durationMs > 0L) }
    data class Selection(val location: TrustedLocation, val confidence: PositionConfidenceDecision)
    private var binding: PositioningCollectionBinding? = null
    private var source: ContinuousGpsSource? = null
    private var filteredEstablished = false
    private var blocked = false
    private var firstAtMs: Long? = null
    private var raw: TrustedLocation? = null
    private var lastRequestPeriod: Long? = null
    private var timeoutAnnounced = false

    @Synchronized
    fun observe(binding: PositioningCollectionBinding, source: ContinuousGpsSource,
        snapshot: PositioningSnapshot, candidate: TrustedLocation, nowMs: Long,
        retainMeasuredFailure: Boolean = false, admissionAllowed: () -> Boolean = { true }): Selection? {
        if (this.binding !== binding || this.source != source) {
            reset()
            this.binding = binding
            this.source = source
        }
        val rejectedMeasuredFailure = snapshot.gnssUpdate?.disposition == GnssObservationDisposition.HARD_REJECTED &&
            snapshot.gnssUpdate.hardRejectReason in setOf(GnssHardRejectReason.INVALID_ACCURACY,
                GnssHardRejectReason.NUMERICAL_FAILURE)
        val missingAccuracy = snapshot.raw?.reportedHorizontalAccuracyM == null &&
            snapshot.gnssUpdate?.disposition in setOf(GnssObservationDisposition.INITIALIZED,
                GnssObservationDisposition.REINITIALIZED, GnssObservationDisposition.APPLIED)
        if ((rejectedMeasuredFailure || missingAccuracy) && !blocked &&
            firstAtMs?.let { nowMs >= it + durationMs } == true) {
            raw = null
            return null
        }
        if (retainMeasuredFailure && (rejectedMeasuredFailure || missingAccuracy)) {
            // Refused inputs never become a new sample. Only the previously admitted reference
            // can survive the intersection of the GPS grace, fixed warming window and raw TTL.
            if (!admissionAllowed()) return null
            return current(binding, source, snapshot, nowMs, retainMeasuredFailure = true)
        }
        if (snapshot.gnssUpdate?.disposition == GnssObservationDisposition.HARD_REJECTED ||
            snapshot.confidence.failureReason != null || !structurallyValid(candidate, nowMs)
        ) {
            invalidate()
            return null
        }
        if (candidate.accuracyM > 15f) return null
        val accepted = snapshot.gnssUpdate?.disposition in setOf(
            GnssObservationDisposition.INITIALIZED, GnssObservationDisposition.REINITIALIZED,
            GnssObservationDisposition.APPLIED,
        )
        if (!accepted) return null
        val original = snapshot.raw ?: return null
        if (original.elapsedRealtimeMs != candidate.elapsedRealtimeMs ||
            original.coordinate.latitude != candidate.latitude || original.coordinate.longitude != candidate.longitude ||
            original.reportedHorizontalAccuracyM != candidate.accuracyM.toDouble() ||
            snapshot.gnssUpdate?.estimateAtGnssMeasurement?.lastInformativeGnssAtElapsedRealtimeMs != candidate.elapsedRealtimeMs
        ) return null
        val filtered = snapshot.filtered ?: return null
        if (snapshot.quality != PositionQuality.UNAVAILABLE &&
            filtered.horizontalUncertaintyM.isFinite() && filtered.horizontalUncertaintyM <= 15.0 &&
            (!snapshot.confidence.guidancePaused || firstAtMs == null)
        ) {
            filteredEstablished = true
            raw = null
            firstAtMs = null
            return null
        }
        if (filteredEstablished || blocked || !warming(snapshot)) return null
        val previous = raw
        if (previous != null && candidate.elapsedRealtimeMs <= previous.elapsedRealtimeMs) return null
        if (!admissionAllowed()) return null
        if (firstAtMs == null) {
            // A real pending model episode owns the deadline; otherwise this first admitted
            // warming input owns a separate cause. Later fixes never renew either onset.
            val first = snapshot.confidence.degradationPendingSinceElapsedRealtimeMs ?: nowMs
            if (first < 0L || first > nowMs || first > Long.MAX_VALUE - durationMs) return null
            firstAtMs = first
        }
        raw = candidate.copy(uncertaintyKind = HorizontalUncertaintyKind.ANDROID_RADIUS_68,
            navigationOnly = true)
        return current(binding, source, snapshot, nowMs)
    }

    @Synchronized
    fun current(binding: PositioningCollectionBinding, source: ContinuousGpsSource,
        snapshot: PositioningSnapshot, nowMs: Long, retainMeasuredFailure: Boolean = false): Selection? {
        val location = raw ?: return null
        if (this.binding !== binding || this.source != source || blocked || !warming(snapshot) ||
            snapshot.confidence.failureReason != null ||
            (snapshot.gnssUpdate?.disposition == GnssObservationDisposition.HARD_REJECTED && !retainMeasuredFailure) ||
            !withinWindow(nowMs) || !valid(location, nowMs)
        ) return null
        return Selection(location, snapshot.confidence.copy(quality = PositionQuality.LOW,
            guidancePaused = false, announcementRequest = null))
    }

    @Synchronized
    fun retains(binding: PositioningCollectionBinding, source: ContinuousGpsSource,
        location: TrustedLocation, nowMs: Long): Boolean =
        this.binding === binding && this.source == source && !blocked && raw === location &&
            withinWindow(nowMs) && valid(location, nowMs)

    @Synchronized
    fun hasAdmittedReference(): Boolean = !blocked && raw != null

    @Synchronized
    fun owns(binding: PositioningCollectionBinding, source: ContinuousGpsSource): Boolean =
        this.binding === binding && this.source == source && !blocked && firstAtMs != null

    @Synchronized
    fun deadlineElapsedRealtimeMs(): Long? = if (blocked) null else firstAtMs?.plus(durationMs)

    @Synchronized
    fun recheckDue(binding: PositioningCollectionBinding, source: ContinuousGpsSource, nowMs: Long): Boolean {
        val first = firstAtMs ?: return false
        if (this.binding !== binding || this.source != source || blocked || nowMs < first ||
            nowMs - first < durationMs
        ) return false
        val period = (nowMs - first) / 3_000L
        if (lastRequestPeriod == period) return false
        lastRequestPeriod = period
        return true
    }

    @Synchronized
    fun shouldAnnounceTimeout(nowMs: Long): Boolean {
        val first = firstAtMs ?: return false
        if (blocked || timeoutAnnounced || nowMs < first || nowMs - first < durationMs) return false
        timeoutAnnounced = true
        return true
    }

    @Synchronized
    fun invalidate() { blocked = true; raw = null }

    @Synchronized
    fun reset() {
        binding = null; source = null; filteredEstablished = false; blocked = false
        firstAtMs = null; raw = null; lastRequestPeriod = null; timeoutAnnounced = false
    }

    private fun withinWindow(nowMs: Long): Boolean = firstAtMs?.let {
        nowMs >= it && nowMs - it < durationMs
    } == true

    private fun warming(snapshot: PositioningSnapshot): Boolean {
        val filtered = snapshot.filtered ?: return false
        val modelWarming = snapshot.quality == PositionQuality.UNAVAILABLE && filtered.horizontalUncertaintyM > 15.0
        val confirmingRecovery = firstAtMs != null &&
            snapshot.quality in setOf(PositionQuality.HIGH, PositionQuality.MEDIUM, PositionQuality.LOW) &&
            filtered.horizontalUncertaintyM <= 15.0
        return (modelWarming || confirmingRecovery) &&
            filtered.coordinate.latitude.isFinite() && filtered.coordinate.latitude in -90.0..90.0 &&
            filtered.coordinate.longitude.isFinite() && filtered.coordinate.longitude in -180.0..180.0 &&
            filtered.horizontalUncertaintyM.isFinite()
    }

    private fun valid(location: TrustedLocation, nowMs: Long): Boolean =
        structurallyValid(location, nowMs) && location.accuracyM <= 15f

    private fun structurallyValid(location: TrustedLocation, nowMs: Long): Boolean =
        location.latitude.isFinite() && location.latitude > -90.0 && location.latitude < 90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.accuracyM.isFinite() && location.accuracyM >= 0f &&
            location.elapsedRealtimeMs >= 0L && LocationTrustPolicy.freshOrNull(location, nowMs) != null
}
