package kr.co.hanium.dreamup.walksafe.depth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class MetricDistanceContinuityDecision(
    val accepted: Boolean,
    val observationsToAppend: List<DistanceObservation> = emptyList(),
    val resetHistory: Boolean = false,
)

/** A single unusual depth sample waits for confirmation without invalidating object identity. */
class MetricDistanceContinuityPolicy {
    private var pending: DistanceObservation? = null
    private var lastObservation: DistanceObservation? = null
    private var lastAccepted = false

    fun evaluate(
        observation: DistanceObservation,
        acceptedHistory: List<DistanceObservation>,
        maxDepthJumpM: Float,
    ): MetricDistanceContinuityDecision {
        val last = lastObservation
        if (last != null && observation.timestampMs <= last.timestampMs) {
            val sameObservation = observation.timestampMs == last.timestampMs &&
                observation.distanceM == last.distanceM && observation.source == last.source &&
                observation.cameraPoseEvidence == last.cameraPoseEvidence && observation.confidence.isFinite()
            return MetricDistanceContinuityDecision(accepted = sameObservation && lastAccepted)
        }
        if (observation.timestampMs < 0L || !observation.source.metric ||
            !observation.distanceM.isFinite() || observation.distanceM <= 0f ||
            !observation.confidence.isFinite()
        ) return MetricDistanceContinuityDecision(accepted = false)
        val previous = acceptedHistory.lastOrNull()
        if (previous != null && observation.timestampMs <= previous.timestampMs) {
            return MetricDistanceContinuityDecision(accepted = false)
        }
        lastObservation = observation
        val decision = when {
            previous == null || abs(previous.distanceM - observation.distanceM) <= maxDepthJumpM ->
                MetricDistanceContinuityDecision(true, listOf(observation))
            followsEstablishedTrend(observation, acceptedHistory, maxDepthJumpM) ->
                MetricDistanceContinuityDecision(true, listOf(observation))
            else -> confirmPending(requireNotNull(previous), observation, last, maxDepthJumpM)
        }
        lastAccepted = decision.accepted
        val candidate = pending
        pending = when {
            decision.accepted -> null
            candidate != null && abs(candidate.distanceM - observation.distanceM) <= maxDepthJumpM &&
                hasContinuousEvidence(listOf(candidate, observation), minimumIntervalMs = 1L) &&
                (last == null || last == candidate ||
                    hasContinuousEvidence(listOf(last, observation), minimumIntervalMs = 1L)) -> candidate
            else -> observation
        }
        return decision
    }

    private fun confirmPending(
        previous: DistanceObservation,
        current: DistanceObservation,
        last: DistanceObservation?,
        maxDepthJumpM: Float,
    ): MetricDistanceContinuityDecision {
        val candidate = pending ?: return MetricDistanceContinuityDecision(false)
        // Fast frames contribute evidence without restarting the 100 ms confirmation window.
        if (current.timestampMs - candidate.timestampMs < 100L ||
            !hasContinuousEvidence(listOf(previous, candidate, current), minimumIntervalMs = 1L) ||
            (last != null && last != candidate &&
                !hasContinuousEvidence(listOf(last, current), minimumIntervalMs = 1L))
        ) {
            return MetricDistanceContinuityDecision(false)
        }
        if (abs(candidate.distanceM - current.distanceM) <= maxDepthJumpM) {
            // A confirmed new distance baseline must not create a TTC across the rejected jump.
            return MetricDistanceContinuityDecision(true, listOf(candidate, current), resetHistory = true)
        }
        val firstSpeed = closingSpeed(previous, candidate)
        val nextSpeed = closingSpeed(candidate, current)
        return if (firstSpeed * nextSpeed > 0f && abs(firstSpeed - nextSpeed) <= 0.5f) {
            MetricDistanceContinuityDecision(true, listOf(candidate, current))
        } else {
            MetricDistanceContinuityDecision(false)
        }
    }

    private fun followsEstablishedTrend(
        current: DistanceObservation,
        history: List<DistanceObservation>,
        maxDepthJumpM: Float,
    ): Boolean {
        val recent = history.takeLast(3)
        if (recent.size < 3 || !hasContinuousEvidence(recent + current)) return false
        val first = recent.first()
        val times = recent.map { (it.timestampMs - first.timestampMs) / 1_000f }
        val meanTime = times.average().toFloat()
        val meanDistance = recent.map { it.distanceM }.average().toFloat()
        val denominator = times.sumOf { ((it - meanTime) * (it - meanTime)).toDouble() }.toFloat()
        val numerator = recent.indices.sumOf {
            ((times[it] - meanTime) * (recent[it].distanceM - meanDistance)).toDouble()
        }.toFloat()
        val slope = numerator / denominator
        val previous = recent.last()
        val predicted = previous.distanceM + slope * (current.timestampMs - previous.timestampMs) / 1_000f
        return abs(current.distanceM - predicted) <= maxDepthJumpM
    }

    private fun hasContinuousEvidence(
        observations: List<DistanceObservation>,
        minimumIntervalMs: Long = 100L,
    ): Boolean {
        val reference = observations.first().cameraPoseEvidence ?: return false
        if (observations.any { observation ->
                val pose = observation.cameraPoseEvidence
                !observation.source.metric || observation.source != observations.first().source ||
                    !observation.confidence.isFinite() || observation.confidence < 0.55f ||
                    pose == null || !pose.isValidFor(observation.timestampMs) ||
                    pose.referenceId != reference.referenceId || forwardAlignment(reference, pose) < 0.9961947f
            }
        ) return false
        return observations.zipWithNext().all { (a, b) ->
            val intervalMs = b.timestampMs - a.timestampMs
            val previous = requireNotNull(a.cameraPoseEvidence)
            val current = requireNotNull(b.cameraPoseEvidence)
            if (intervalMs !in minimumIntervalMs..1_500L || forwardAlignment(previous, current) < 0.9961947f) {
                false
            } else {
                val seconds = intervalMs / 1_000f
                val dx = current.positionX - previous.positionX
                val dy = current.positionY - previous.positionY
                val dz = current.positionZ - previous.positionZ
                val displacementSquared = dx * dx + dy * dy + dz * dz
                val forwardMeters = dx * reference.forwardX + dy * reference.forwardY + dz * reference.forwardZ
                val lateralMeters = sqrt(max(0f, displacementSquared - forwardMeters * forwardMeters))
                sqrt(displacementSquared) / seconds <= 3.5f &&
                    lateralMeters / seconds <= max(0.35f, abs(forwardMeters / seconds) * 0.5f)
            }
        }
    }

    private fun closingSpeed(a: DistanceObservation, b: DistanceObservation): Float =
        (a.distanceM - b.distanceM) / ((b.timestampMs - a.timestampMs) / 1_000f)

    private fun forwardAlignment(a: CameraPoseEvidence, b: CameraPoseEvidence): Float {
        val lengthA = sqrt(a.forwardX * a.forwardX + a.forwardY * a.forwardY + a.forwardZ * a.forwardZ)
        val lengthB = sqrt(b.forwardX * b.forwardX + b.forwardY * b.forwardY + b.forwardZ * b.forwardZ)
        return (a.forwardX * b.forwardX + a.forwardY * b.forwardY + a.forwardZ * b.forwardZ) / (lengthA * lengthB)
    }

    private fun CameraPoseEvidence.isValidFor(observedAtMs: Long): Boolean =
        referenceId >= 0L && timestampMs == observedAtMs &&
            listOf(positionX, positionY, positionZ, forwardX, forwardY, forwardZ).all { it.isFinite() } &&
            abs(forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ - 1f) <= 0.001f
}
