package kr.co.hanium.dreamup.walksafe.depth

/** Independent depth captures and AR pose times have separate clocks; neither replaces the other. */
internal enum class MotionObservationAdmission { NEW, DUPLICATE, UNAVAILABLE, DISCONTINUOUS }

/** Bounded by elapsed time as well as size; rendering frequency does not shorten the fit window. */
internal class MotionObservationWindow {
    private val metric = mutableListOf<DistanceObservation>()
    private val spatial = mutableListOf<DistanceObservation>()
    private var lastInputAtMs: Long? = null
    private var lastDepthTimestampNs: Long? = null
    private var source: DepthSource? = null
    private var referenceId: Long? = null
    var resetOnAdmission: Boolean = false
        private set

    fun admit(observation: DistanceObservation): MotionObservationAdmission {
        resetOnAdmission = false
        val at = observation.timestampMs
        val depthAt = observation.depthObservationTimestampNs?.takeIf { it > 0L }
        val previousAt = lastInputAtMs
        if (at < 0L || (previousAt != null && at < previousAt)) {
            clear()
            resetOnAdmission = true
            lastInputAtMs = at.takeIf { it >= 0L }
            lastDepthTimestampNs = depthAt
            return MotionObservationAdmission.DISCONTINUOUS
        }
        val gapExceeded = metric.lastOrNull()?.let { at - it.timestampMs > MAX_OBSERVATION_GAP_MS } == true
        prune(metric, at)
        prune(spatial, at)
        // Observe coordinate/source boundaries before duplicate filtering or key sampling.
        val poseReference = observation.cameraPoseEvidence?.referenceId
        if ((source != null && source != observation.source) ||
            (referenceId != null && poseReference != null && referenceId != poseReference) ||
            gapExceeded
        ) {
            clearSamples()
            resetOnAdmission = true
        }
        source = observation.source
        if (poseReference != null) referenceId = poseReference
        if (previousAt == at && depthAt != null && depthAt == lastDepthTimestampNs) {
            return MotionObservationAdmission.DUPLICATE
        }
        lastInputAtMs = at
        if (depthAt == null) return MotionObservationAdmission.UNAVAILABLE
        val previousDepthAt = lastDepthTimestampNs
        if (previousDepthAt != null && depthAt < previousDepthAt) {
            clearSamples()
            resetOnAdmission = true
            lastDepthTimestampNs = depthAt
            return MotionObservationAdmission.DISCONTINUOUS
        }
        if (previousDepthAt == depthAt) return MotionObservationAdmission.DUPLICATE
        if (previousAt == at && previousDepthAt != null) {
            clearSamples()
            resetOnAdmission = true
            lastDepthTimestampNs = depthAt
            return MotionObservationAdmission.DISCONTINUOUS
        }
        lastDepthTimestampNs = depthAt
        return MotionObservationAdmission.NEW
    }

    /** Called only for depth accepted by the distance continuity policy, including confirmed pending samples. */
    fun appendAccepted(observation: DistanceObservation) {
        if (observation.depthObservationTimestampNs?.let { it > 0L } != true ||
            !observation.source.metric || !observation.confidence.isFinite() || observation.confidence < 0.35f ||
            !observation.distanceM.isFinite() || observation.distanceM <= 0f ||
            metric.lastOrNull()?.let { observation.timestampMs <= it.timestampMs } == true
        ) return
        metric += observation
        val pose = observation.cameraPoseEvidence
        val point = observation.objectPositionInAnchor
        if (observation.source.trustedForStepGuidance && observation.confidence >= 0.55f &&
            pose != null && pose.timestampMs == observation.timestampMs && pose.referenceId >= 0L &&
            pose.objectCenterInAnchor(Point2(0.5f, 0.5f), 1f) != null &&
            point != null && point.x.isFinite() && point.y.isFinite() && point.z.isFinite()
        ) spatial += observation
        prune(metric, observation.timestampMs)
        prune(spatial, observation.timestampMs)
    }

    fun metricSamples(atMs: Long): List<DistanceObservation> = sample(metric, atMs)
    fun spatialSamples(atMs: Long): List<DistanceObservation> = sample(spatial, atMs)
    fun spatialHistory(): List<DistanceObservation> = spatial.toList()

    /** A display-only source cannot replace the Raw clock, but can reveal a real frame boundary. */
    fun observeProximityContext(atMs: Long, poseReferenceId: Long?): Boolean {
        val retrograde = atMs < 0L || lastInputAtMs?.let { atMs < it } == true
        val boundary = retrograde ||
            (referenceId != null && poseReferenceId != null && referenceId != poseReferenceId) ||
            metric.lastOrNull()?.let { atMs - it.timestampMs > MAX_OBSERVATION_GAP_MS } == true
        if (retrograde) clear() else if (boundary) clearSamples()
        if (poseReferenceId != null) referenceId = poseReferenceId
        prune(metric, atMs)
        prune(spatial, atMs)
        return boundary
    }

    fun invalidateAt(atMs: Long) {
        if (atMs < 0L || lastInputAtMs?.let { atMs < it } == true) clear()
        prune(metric, atMs)
        prune(spatial, atMs)
    }

    fun clearSamples() {
        metric.clear()
        spatial.clear()
    }

    fun clear() {
        clearSamples()
        lastInputAtMs = null
        lastDepthTimestampNs = null
        source = null
        referenceId = null
    }

    private fun prune(history: MutableList<DistanceObservation>, atMs: Long) {
        history.removeAll { atMs - it.timestampMs > MAX_WINDOW_MS }
        while (history.size > MAX_OBSERVATIONS) history.removeAt(0)
    }

    private fun sample(history: List<DistanceObservation>, atMs: Long): List<DistanceObservation> {
        val latest = history.lastOrNull()?.takeIf { it.timestampMs == atMs } ?: return emptyList()
        val selected = mutableListOf(latest)
        for (observation in history.asReversed().drop(1)) {
            if (latest.timestampMs - observation.timestampMs > MAX_WINDOW_MS) break
            if (selected.last().timestampMs - observation.timestampMs < MIN_SAMPLE_INTERVAL_MS) continue
            selected += observation
            if (selected.size >= 4 && latest.timestampMs - observation.timestampMs >= TARGET_SPAN_MS) break
        }
        return selected.asReversed()
    }

    private companion object {
        const val MAX_WINDOW_MS = 4_000L
        const val MAX_OBSERVATION_GAP_MS = 1_500L
        const val MAX_OBSERVATIONS = 256
        const val MIN_SAMPLE_INTERVAL_MS = 200L
        const val TARGET_SPAN_MS = 1_500L
    }
}
