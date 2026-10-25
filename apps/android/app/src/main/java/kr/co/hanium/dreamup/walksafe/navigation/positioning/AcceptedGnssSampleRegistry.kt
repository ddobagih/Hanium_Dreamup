package kr.co.hanium.dreamup.walksafe.navigation.positioning

/** Original SDK values only. Null preserves an absent optional measurement. */
internal data class GnssSampleFingerprint(
    val elapsedRealtimeNanos: Long,
    val timeMillis: Long,
    val provider: String?,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Double?,
    val mock: Boolean,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearingDegrees: Double? = null,
    val verticalAccuracyM: Double? = null,
    val speedAccuracyMps: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
    val elapsedRealtimeUncertaintyNanos: Double? = null,
    val mslAltitudeM: Double? = null,
    val mslAltitudeAccuracyM: Double? = null,
) {
    /** Fail open to ordinary processing, never hide malformed or expired input as a duplicate. */
    internal fun canSkipAt(receivedAtMs: Long, noiseMultiplier: Double, maximumAgeMs: Long): Boolean =
        validatesAt(receivedAtMs, noiseMultiplier, maximumAgeMs, ignoreHorizontalAccuracy = false)

    /**
     * The refused accuracy stays refused, including when the same historical fix has also expired.
     * Only its effect on a strictly newer, still-fresh accepted owner proof is discarded.
     */
    internal fun canIgnoreHistoricalInvalidAccuracyAt(
        receivedAtMs: Long, noiseMultiplier: Double, maximumAgeMs: Long,
    ): Boolean = horizontalAccuracyM?.let { !it.isFinite() || it < 0.0 } == true &&
        validatesAt(receivedAtMs, noiseMultiplier, maximumAgeMs,
            ignoreHorizontalAccuracy = true, allowHistoricalAge = true)

    /** Expired input stays refused; its age alone cannot retire a newer valid owner proof. */
    internal fun canIgnoreHistoricalStaleAt(
        receivedAtMs: Long, noiseMultiplier: Double, maximumAgeMs: Long,
    ): Boolean = validatesAt(receivedAtMs, noiseMultiplier, maximumAgeMs,
        ignoreHorizontalAccuracy = false, allowHistoricalAge = true) &&
        receivedAtMs - elapsedRealtimeNanos / 1_000_000L > maximumAgeMs

    private fun validatesAt(
        receivedAtMs: Long, noiseMultiplier: Double, maximumAgeMs: Long, ignoreHorizontalAccuracy: Boolean,
        allowHistoricalAge: Boolean = false,
    ): Boolean {
        if (mock || elapsedRealtimeNanos < 0L || timeMillis < 0L || receivedAtMs < 0L) return false
        if (!latitude.isFinite() || latitude <= -90.0 || latitude >= 90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) return false
        val observedAtMs = elapsedRealtimeNanos / 1_000_000L
        if (observedAtMs > receivedAtMs ||
            (!allowHistoricalAge && receivedAtMs - observedAtMs > maximumAgeMs)
        ) return false
        if (!noiseMultiplier.isFinite() || noiseMultiplier < 1.0) return false
        if ((!ignoreHorizontalAccuracy && !horizontalAccuracyM.nonnegativeOrAbsent()) || !verticalAccuracyM.nonnegativeOrAbsent() ||
            !speedMps.nonnegativeOrAbsent() || !speedAccuracyMps.nonnegativeOrAbsent() ||
            !bearingAccuracyDegrees.nonnegativeOrAbsent() ||
            !elapsedRealtimeUncertaintyNanos.nonnegativeOrAbsent() ||
            !mslAltitudeAccuracyM.nonnegativeOrAbsent()) return false
        if (altitudeM?.isFinite() == false || mslAltitudeM?.isFinite() == false) return false
        return bearingDegrees == null ||
            (bearingDegrees.isFinite() && bearingDegrees >= 0.0 && bearingDegrees < 360.0)
    }

    private fun Double?.nonnegativeOrAbsent(): Boolean = this == null || (isFinite() && this >= 0.0)
}

/**
 * One accepted raw sample for one immutable collection owner. This ledger neither publishes
 * evidence nor changes the estimator, observation time, expiry or quality-failure deadline.
 * Owner equality must include the collection binding's identity and its generation/epoch/profile.
 */
internal class AcceptedGnssSampleRegistry<Owner : Any>(private val maximumObservationAgeMs: Long) {
    init { require(maximumObservationAgeMs >= 0L) }

    private var owner: Owner? = null
    private data class AcceptedSample(val sample: GnssSampleFingerprint, val noiseMultiplier: Double)
    private var accepted: AcceptedSample? = null

    @Synchronized
    fun shouldSkip(
        owner: Owner,
        sample: GnssSampleFingerprint,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ): Boolean {
        selectOwner(owner)
        return sample.canSkipAt(receivedAtElapsedRealtimeMs, measurementNoiseMultiplier, maximumObservationAgeMs) &&
            sample == accepted?.sample
    }

    /** A validated historical delivery is still rejected, but is not a loss of its newer owner proof. */
    @Synchronized
    fun isSuperseded(
        owner: Owner,
        sample: GnssSampleFingerprint,
        disposition: GnssObservationDisposition?,
        hardRejectReason: GnssHardRejectReason?,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ): Boolean {
        selectOwner(owner)
        val newer = accepted ?: return false
        return disposition == GnssObservationDisposition.HARD_REJECTED &&
            hardRejectReason == GnssHardRejectReason.OUT_OF_ORDER &&
            newer.sample.elapsedRealtimeNanos > sample.elapsedRealtimeNanos &&
            sample.canSkipAt(receivedAtElapsedRealtimeMs, measurementNoiseMultiplier, maximumObservationAgeMs) &&
            newer.sample.canSkipAt(receivedAtElapsedRealtimeMs, newer.noiseMultiplier, maximumObservationAgeMs)
    }

    /** A strictly older accuracy refusal cannot remove the current owner's accepted proof. */
    @Synchronized
    fun isSupersededInvalidAccuracy(
        owner: Owner,
        sample: GnssSampleFingerprint,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ): Boolean {
        selectOwner(owner)
        val newer = accepted ?: return false
        return newer.sample.elapsedRealtimeNanos > sample.elapsedRealtimeNanos &&
            sample.canIgnoreHistoricalInvalidAccuracyAt(
                receivedAtElapsedRealtimeMs, measurementNoiseMultiplier, maximumObservationAgeMs) &&
            newer.sample.canSkipAt(receivedAtElapsedRealtimeMs, newer.noiseMultiplier, maximumObservationAgeMs)
    }

    /** A stale callback is not accepted and cannot change the still-fresh accepted ledger. */
    @Synchronized
    fun isSupersededStale(
        owner: Owner,
        sample: GnssSampleFingerprint,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ): Boolean {
        selectOwner(owner)
        val newer = accepted ?: return false
        return newer.sample.elapsedRealtimeNanos > sample.elapsedRealtimeNanos &&
            sample.canIgnoreHistoricalStaleAt(
                receivedAtElapsedRealtimeMs, measurementNoiseMultiplier, maximumObservationAgeMs) &&
            newer.sample.canSkipAt(receivedAtElapsedRealtimeMs, newer.noiseMultiplier, maximumObservationAgeMs)
    }

    @Synchronized
    fun recordAccepted(
        owner: Owner,
        sample: GnssSampleFingerprint,
        disposition: GnssObservationDisposition?,
        receivedAtElapsedRealtimeMs: Long,
        measurementNoiseMultiplier: Double,
    ) {
        selectOwner(owner)
        if (disposition == null || disposition == GnssObservationDisposition.HARD_REJECTED ||
            !sample.canSkipAt(receivedAtElapsedRealtimeMs, measurementNoiseMultiplier, maximumObservationAgeMs)) return
        // A late result cannot replace the latest accepted ledger or renew its original sample.
        if (accepted?.let { sample.elapsedRealtimeNanos <= it.sample.elapsedRealtimeNanos } == true) return
        accepted = AcceptedSample(sample, measurementNoiseMultiplier)
    }

    @Synchronized
    fun reset() {
        owner = null
        accepted = null
    }

    private fun selectOwner(current: Owner) {
        if (owner != current) {
            owner = current
            accepted = null
        }
    }
}
