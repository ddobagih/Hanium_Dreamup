package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class DepthGapDecision(
    val availability: DepthAvailability,
    val observedAtMs: Long? = null,
    val prediction: PredictedDepthEstimate? = null,
)

/** Per visual identity. Prediction has its own storage and never enters a metric/motion ledger. */
internal class ShortDepthGapState {
    private data class Sample(
        val atMs: Long,
        val capturedAtMs: Long,
        val depthAtNs: Long,
        val source: DepthSource,
        val distanceM: Float,
        val zDistanceM: Float,
        val errorM: Float,
        val pose: CameraPoseEvidence?,
        val position: Vec3?,
        val motion: ObjectMotionEstimate,
        val geometry: ObjectGeometry,
    )

    private val samples = mutableListOf<Sample>()
    // Preserve source clocks across support/identity boundaries so repeated depth cannot reseed a gap.
    private val depthClocks = mutableMapOf<DepthSource, Long>()

    fun clear() { samples.clear() }

    fun observe(
        input: ObjectDepthInput,
        source: DepthSource,
        stats: DepthStats,
        distanceM: Float?,
        confidence: Float,
        pose: CameraPoseEvidence?,
        position: Vec3?,
        motion: ObjectMotionEstimate,
    ) {
        if (!validContext(input)) {
            clear()
            return
        }
        val depthAt = when (source) {
            DepthSource.ARCORE_RAW_DEPTH -> input.rawDepthTimestampNs
            DepthSource.ARCORE_FULL_DEPTH -> input.fullDepthTimestampNs
            else -> null
        }?.takeIf { it > 0L } ?: return
        val previousClock = depthClocks[source]
        if (previousClock != null && depthAt <= previousClock) {
            if (depthAt < previousClock) clear()
            return
        }
        depthClocks[source] = depthAt
        val synchronized = when (source) {
            DepthSource.ARCORE_RAW_DEPTH -> input.rawDepthMatchesCameraFrame
            DepthSource.ARCORE_FULL_DEPTH -> input.fullDepthMatchesCameraFrame
            else -> false
        }
        val z = stats.medianM
        val observedAtMs = depthAt / 1_000_000L
        if (!synchronized || input.timestampMs - observedAtMs !in 0L..50L || !confidence.isFinite() || confidence < 0.55f ||
            distanceM == null || !distanceM.isFinite() || distanceM <= 0f ||
            z == null || !z.isFinite() || z <= 0f
        ) return
        // CPU/depth synchronization does not imply the AR pose has the same timestamp. Applying
        // a later pose to earlier depth shifts the object in the anchor before prediction starts.
        if (pose != null && pose.timestampMs != observedAtMs) {
            clear()
            return
        }
        val previous = samples.lastOrNull()
        if (previous != null && (observedAtMs <= previous.atMs ||
                observedAtMs - previous.atMs > MAX_SAMPLE_GAP_MS || previous.source != source ||
                previous.geometry.imageQuarterTurns != input.geometry.imageQuarterTurns ||
                abs(previous.distanceM - distanceM) > 1.2f ||
                !continuousPose(previous.pose, pose, input.timestampMs - previous.capturedAtMs))) clear()
        samples += Sample(observedAtMs, input.timestampMs, depthAt, source, distanceM, z,
            max(0.05f, stats.iqrM?.takeIf { it.isFinite() && it >= 0f } ?: 0.35f),
            pose, position, motion.takeIf { motionHasAlignedClocks(input.track, it) } ?: ObjectMotionEstimate(), input.geometry)
        samples.removeAll { input.timestampMs - it.atMs > 4_000L }
        while (samples.size > 8) samples.removeAt(0)
    }

    fun gap(input: ObjectDepthInput): DepthGapDecision {
        val latest = samples.lastOrNull() ?: return DepthGapDecision(DepthAvailability.UNAVAILABLE)
        val ageMs = input.timestampMs - latest.atMs
        val pose = input.motionContext.reliableCameraPoseEvidence()?.takeIf { validPose(it, input.timestampMs) }
        if (!validContext(input) || !input.track.stable || ageMs < 0L ||
            latest.geometry.imageQuarterTurns != input.geometry.imageQuarterTurns ||
            !continuousPose(latest.pose, pose, ageMs)
        ) {
            clear()
            return DepthGapDecision(DepthAvailability.UNAVAILABLE)
        }
        val intervals = samples.zipWithNext().map { (a, b) -> b.atMs - a.atMs }.sorted()
        val cadenceMs = intervals.getOrNull(intervals.size / 2) ?: 100L
        var horizonMs = (cadenceMs * 2L).coerceIn(100L, MAX_PREDICTION_MS)
        if (ageMs > horizonMs) {
            clear()
            return DepthGapDecision(DepthAvailability.UNAVAILABLE, latest.atMs)
        }
        val unavailable = DepthGapDecision(DepthAvailability.TEMPORARILY_UNAVAILABLE, latest.atMs)
        if (ageMs == 0L || samples.size < 3 || latest.atMs - samples.first().atMs < 200L) return unavailable

        val first = samples.first()
        val spanSeconds = (latest.atMs - first.atMs) / 1_000f
        val slope = (latest.distanceM - first.distanceM) / spanSeconds
        if (!slope.isFinite() || abs(slope) > 8f) return unavailable
        val residual = samples.maxOf { sample ->
            abs(sample.distanceM - (first.distanceM + slope * (sample.atMs - first.atMs) / 1_000f))
        }
        val slopeVariation = samples.zipWithNext().maxOf { (a, b) ->
            abs((b.distanceM - a.distanceM) / ((b.atMs - a.atMs) / 1_000f) - slope)
        }
        if (!residual.isFinite() || !slopeVariation.isFinite() || residual > 0.25f || slopeVariation > 1.5f) return unavailable
        val allowedErrorM = min(0.5f, latest.distanceM * 0.20f)
        val baseErrorM = latest.errorM + residual
        val baselineVelocityUncertainty = max(0.5f, slopeVariation)
        val errorBudgetM = max(0f, allowedErrorM - baseErrorM)
        val errorHorizonMs = ((sqrt(baselineVelocityUncertainty * baselineVelocityUncertainty + 6f * errorBudgetM) -
            baselineVelocityUncertainty) / 3f * 1_000f).toLong()
        horizonMs = min(horizonMs, errorHorizonMs)
        if (ageMs > horizonMs) {
            clear()
            return DepthGapDecision(DepthAvailability.UNAVAILABLE, latest.atMs)
        }
        val seconds = ageMs / 1_000f
        var predicted = latest.distanceM + slope * seconds
        var velocityUncertainty = baselineVelocityUncertainty
        var predictedRange: Float? = null
        // A validated object velocity and CURRENT pose replace extrapolated camera motion. The
        // offset preserves the selected risk layer; a substantially different layer cannot use it.
        val velocity = latest.motion.objectVelocityInAnchorMps?.takeIf {
            finite(it) && latest.motion.confidence >= 0.55f && latest.motion.observedAtMs == latest.capturedAtMs &&
                latest.motion.referenceId == latest.pose?.referenceId && it.norm() <= 8f
        }
        if (pose != null && latest.pose != null && latest.position != null && finite(latest.position) && velocity != null &&
            abs(latest.distanceM - latest.zDistanceM) <= 0.25f
        ) {
            val position = latest.position + velocity * seconds
            val currentZ = (position - pose.position()).dot(pose.forward())
            predicted = latest.distanceM + currentZ - latest.zDistanceM
            predictedRange = (position - pose.position()).norm()
            velocityUncertainty = max(0.35f, slopeVariation)
        } else if (pose != null && latest.pose != null) {
            // Axial depth changes when the camera turns, especially for off-axis objects. A
            // scalar fit cannot distinguish that projection change from object motion. Require
            // the same camera axis throughout its fit and gap; the spatial branch above can
            // instead project an observed 3D trajectory into the current camera axis.
            if (samples.any { sample ->
                    sample.pose?.let { (it.forward() - pose.forward()).norm() > 0.0001f } != false
                }) {
                clear()
                return DepthGapDecision(DepthAvailability.UNAVAILABLE, latest.atMs)
            }
            // Scalar prediction assumes unchanged relative motion. If current camera motion
            // differs from the fitted camera motion, widen its error rather than assume an object
            // is stationary. Course/heading is not transformed into an unrelated AR anchor.
            val firstPose = first.pose
            if (firstPose != null) {
                val cameraVelocity = (latest.pose.position() - firstPose.position()) * (1f / spanSeconds)
                val discrepancy = (pose.position() - latest.pose.position() - cameraVelocity * seconds).norm()
                velocityUncertainty += discrepancy / seconds
            }
        }
        // User speed can enlarge uncertainty when pose is absent; it cannot supply an AR direction.
        if (pose == null) {
            input.motionContext.userMotion.speed?.takeIf {
                it.speedMps.isFinite() && it.speedMps in 0.0..3.5 &&
                    it.accuracyMps?.let { accuracy -> accuracy.isFinite() && accuracy in 0.0..0.8 } == true &&
                    input.timestampMs - it.elapsedRealtimeMs in 0L..2_000L
            }?.let { velocityUncertainty = max(velocityUncertainty, (it.speedMps + requireNotNull(it.accuracyMps)).toFloat()) }
        }
        // A model allowance for unobserved acceleration; never described as calibrated accuracy.
        val errorM = latest.errorM + residual + velocityUncertainty * seconds + 1.5f * seconds * seconds
        if (!predicted.isFinite() || predicted <= 0f || !errorM.isFinite() || errorM > allowedErrorM ||
            predicted - errorM <= 0f) return unavailable
        return DepthGapDecision(DepthAvailability.PREDICTED, latest.atMs,
            PredictedDepthEstimate(predicted, latest.atMs, ageMs, errorM, horizonMs,
                predictedRange?.takeIf { it.isFinite() && it > 0f }?.plus(errorM)))
    }

    private fun validContext(input: ObjectDepthInput): Boolean = input.timestampMs >= 0L &&
        input.track.lastSeenAtMs == input.timestampMs && input.track.missedFrames == 0 && !input.track.idSwitchSuspected &&
        input.geometry.imageQuarterTurns in 0..3 && input.visualTrackingQuality.isFinite() && input.visualTrackingQuality >= 0.65f &&
        input.motionContext.trackingQuality >= 0.8f && input.motionContext.motionQuality >= 0.8f &&
        input.motionContext.freshnessQuality >= 0.7f && input.motionContext.shakeScore <= 0.2f

    private fun motionHasAlignedClocks(track: TrackState, motion: ObjectMotionEstimate): Boolean {
        val atMs = motion.observedAtMs ?: return false
        if (motion.elapsedMs <= 0L) return false
        // A newly aligned sample must not reuse a velocity fitted across earlier skewed samples.
        val fitted = track.denseMotionHistory().filter { it.timestampMs in (atMs - motion.elapsedMs)..atMs }
        return fitted.size >= 4 && fitted.all {
            it.depthObservationTimestampNs?.div(1_000_000L) == it.timestampMs &&
                it.cameraPoseEvidence?.timestampMs == it.timestampMs
        }
    }

    private fun continuousPose(previous: CameraPoseEvidence?, current: CameraPoseEvidence?, elapsedMs: Long): Boolean {
        if (previous == null) return current == null // Establish a new fit when pose availability changes.
        if (current == null || !validPose(previous, previous.timestampMs) || !validPose(current, current.timestampMs) ||
            current.referenceId != previous.referenceId || previous.forward().dot(current.forward()) < 0.9848077f) return false
        val allowedMoveM = 0.05f + 3.5f * max(0L, elapsedMs) / 1_000f
        return (current.position() - previous.position()).norm() <= allowedMoveM
    }

    private fun validPose(pose: CameraPoseEvidence, atMs: Long): Boolean = pose.timestampMs == atMs &&
        pose.referenceId >= 0L && finite(pose.position()) && finite(pose.forward()) &&
        abs(pose.forward().norm() - 1f) < 0.001f

    private fun finite(value: Vec3) = value.x.isFinite() && value.y.isFinite() && value.z.isFinite()
    private fun CameraPoseEvidence.position() = Vec3(positionX, positionY, positionZ)
    private fun CameraPoseEvidence.forward() = Vec3(forwardX, forwardY, forwardZ)

    private companion object {
        const val MAX_SAMPLE_GAP_MS = 1_500L
        const val MAX_PREDICTION_MS = 600L
    }
}
