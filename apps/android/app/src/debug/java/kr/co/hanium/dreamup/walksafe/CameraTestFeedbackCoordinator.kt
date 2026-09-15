package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingConfig
import kotlin.math.abs

/** Main-thread owner of the camera viewer's single production feedback queue. No walk is opened. */
internal class CameraTestFeedbackCoordinator {
    enum class Source { PRIMARY, UNKNOWN }

    data class Sample(
        val source: Source,
        val epoch: Int,
        val frameId: Long,
        val capturedAtMs: Long,
        val completedAtMs: Long,
        val geometryId: String,
        val outputs: List<TrackedObjectDepth>,
    ) {
        val validUntilMs: Long get() = capturedAtMs + UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS
        fun fresh(nowMs: Long) = nowMs >= completedAtMs && nowMs <= validUntilMs
    }

    data class Delivery(val epoch: Int, val geometryId: String, val action: FeedbackAction, val evaluatedAtMs: Long) {
        internal var wasClaimed = false
    }

    private data class Candidate(val sample: Sample, val output: TrackedObjectDepth)
    private data class WarningObservation(val sample: Sample, val observedAtMs: Long)
    private data class WarningSelection(
        val candidates: List<Candidate>,
        val historyTransfers: List<Pair<String, String>>,
    )
    private data class ClaimedUnknownHandoff(
        val delivery: Delivery,
        val anchor: TrackedObjectDepth,
        val previous: WarningObservation,
    )
    private val policy = WalkSafeFeedbackPolicy()
    private val warningObservations = ArrayDeque<WarningObservation>()
    private val warningTrackingLimits = VisualTrackingConfig()
    private val samples = mutableMapOf<Source, Sample>()
    private var retainedUnknownSamples: List<Sample> = emptyList()
    private val sourceTrackIds = mutableMapOf<Source, MutableSet<String>>()
    private var activeEpoch: Int? = null
    private var geometryId: String? = null
    private var geometryFrameId = 0L
    private var claimedDelivery: Delivery? = null
    private var claimedUnknownHandoff: ClaimedUnknownHandoff? = null
    private var claimedUnknownHandoffInvalidated = false
    private var primaryContinuityResetFrameId = 0L

    fun start(epoch: Int) {
        stop()
        activeEpoch = epoch
    }

    fun stop() {
        activeEpoch = null
        samples.clear()
        retainedUnknownSamples = emptyList()
        warningObservations.clear()
        sourceTrackIds.clear()
        geometryId = null
        geometryFrameId = 0L
        claimedDelivery = null
        claimedUnknownHandoff = null
        claimedUnknownHandoffInvalidated = false
        primaryContinuityResetFrameId = 0L
        policy.resetForNewWalk() // Resets local cooldowns only; this policy never opens a walk.
    }

    fun clear(source: Source) {
        samples.remove(source)
        claimedUnknownHandoff = null
        claimedUnknownHandoffInvalidated = true
        if (source == Source.PRIMARY) {
            warningObservations.clear()
            primaryContinuityResetFrameId = geometryFrameId
        }
        if (source == Source.UNKNOWN) retainedUnknownSamples = emptyList()
        // Include earlier samples: an async speech or queued candidate can outlive its latest frame.
        policy.cancelFeedbackForTracks(sourceTrackIds.remove(source) ?: emptySet())
    }

    /** Keep only currently supported regions from the original independent measurement. */
    fun retainUnknownRegions(epoch: Int, geometryId: String, retainedTrackIds: Set<String>, nowMs: Long): Boolean {
        // A late callback from an old session/orientation cannot clear the current source.
        if (epoch != activeEpoch || geometryId != this.geometryId) return false
        val sample = samples[Source.UNKNOWN] ?: return false
        if (sample.epoch != epoch || sample.geometryId != geometryId) return false
        val retained = if (sample.fresh(nowMs)) sample.outputs.filter { it.trackId in retainedTrackIds } else emptyList()
        // As with an ordinary empty/new sample, missing regions prevent new starts but do not
        // cancel accepted speech or haptics. Explicit clear/stop still owns source cancellation.
        samples[Source.UNKNOWN] = sample.copy(outputs = retained)
        retainedUnknownSamples = retainedUnknownSamples.mapNotNull { old ->
            old.takeIf { it.fresh(nowMs) }?.outputs?.filter { it.trackId in retainedTrackIds }
                ?.takeIf { it.isNotEmpty() }?.let { old.copy(outputs = it) }
        }
        return retained.isNotEmpty() || retainedUnknownSamples.isNotEmpty()
    }

    fun offer(sample: Sample, retainedUnknownTrackIds: Set<String> = emptySet(), nowMs: Long = sample.completedAtMs): Boolean {
        if (sample.epoch != activeEpoch || sample.frameId <= 0L || sample.capturedAtMs < 0L ||
            sample.completedAtMs < sample.capturedAtMs || sample.completedAtMs > nowMs || sample.geometryId.isBlank() ||
            sample.outputs.any { it.frameId != sample.frameId || it.timestampMs != sample.frameId / 1_000_000L }
        ) return false
        if (geometryId != sample.geometryId) {
            if (sample.frameId < geometryFrameId) return false
            samples.clear()
            retainedUnknownSamples = emptyList()
            warningObservations.clear()
            claimedDelivery = null
            claimedUnknownHandoff = null
            claimedUnknownHandoffInvalidated = false
            primaryContinuityResetFrameId = 0L
            policy.cancelPendingFeedbackDeliveries()
            geometryId = sample.geometryId
        }
        geometryFrameId = maxOf(geometryFrameId, sample.frameId)
        val old = samples[sample.source]
        if (old != null && (sample.frameId <= old.frameId || sample.capturedAtMs < old.capturedAtMs)) return false
        if (sample.source == Source.UNKNOWN) {
            val remaining = retainedUnknownTrackIds.toMutableSet().apply {
                sample.outputs.forEach { remove(it.trackId) }
            }
            retainedUnknownSamples = (listOfNotNull(old) + retainedUnknownSamples)
                .sortedByDescending { it.capturedAtMs }.mapNotNull { previous ->
                    if (!previous.fresh(nowMs)) return@mapNotNull null
                    previous.outputs.filter { it.trackId in remaining }.takeIf { it.isNotEmpty() }
                        ?.let { outputs ->
                            outputs.forEach { remaining.remove(it.trackId) }
                            previous.copy(outputs = outputs)
                        }
                }
        }
        val accepted = sample.copy(outputs = sample.outputs.toList())
        samples[sample.source] = accepted
        if (sample.source == Source.PRIMARY) {
            updateClaimedUnknownHandoff(accepted, nowMs)
            recordWarningObservation(accepted, nowMs)
        }
        sourceTrackIds.getOrPut(sample.source) { mutableSetOf() }.addAll(sample.outputs.map { it.trackId })
        return true
    }

    fun next(nowMs: Long): Delivery? {
        val epoch = activeEpoch ?: return null
        val candidates = reconcileWarningSelection(nowMs)
        val action = policy.evaluateCandidates(candidates.mapNotNull { it.output.toFeedbackCandidate() }, true, nowMs)
            ?: return null
        val evidence = candidates.firstOrNull { it.output.trackId == action.trackId } ?: run {
            policy.rejectUndeliveredFeedback(action.trackId, nowMs)
            return null
        }
        return Delivery(epoch, evidence.sample.geometryId,
            action.copy(validUntilMs = minOf(action.validUntilMs, evidence.sample.validUntilMs)), nowMs)
    }

    fun isDeliverable(delivery: Delivery, nowMs: Long): Boolean =
        delivery.epoch == activeEpoch && delivery.geometryId == geometryId && nowMs <= delivery.action.validUntilMs &&
            (!delivery.wasClaimed || (claimedDelivery == delivery &&
                policy.hasClaimedFeedbackDelivery(delivery.action.trackId, delivery.action.level))) &&
            delivery.action.deliveryKey in policy.activeFeedbackDeliveryKeys(
                selectWarningRegions(nowMs).candidates.mapNotNull { it.output.toFeedbackCandidate() }, true)

    fun claim(delivery: Delivery, nowMs: Long): Boolean {
        if (!isDeliverable(delivery, nowMs) ||
            !policy.claimFeedbackDelivery(delivery.action.trackId, delivery.evaluatedAtMs)) return false
        delivery.wasClaimed = true
        claimedDelivery = delivery
        claimedUnknownHandoff = null
        claimedUnknownHandoffInvalidated = false
        rememberClaimedUnknownHandoff(selectWarningRegions(nowMs), nowMs)
        return true
    }

    fun complete(delivery: Delivery, nowMs: Long): Boolean {
        if (delivery.epoch != activeEpoch || !policy.confirmFeedbackDelivery(
                delivery.action.trackId, delivery.evaluatedAtMs, nowMs)) return false
        claimedUnknownHandoff?.takeIf {
            it.delivery == delivery && it.previous.sample.fresh(nowMs) &&
                nowMs - it.previous.observedAtMs in 0L..warningTrackingLimits.maxFrameGapMs
        }?.let {
            policy.carryCompletedFeedbackHistory(delivery.action.trackId, it.anchor.trackId)
        }
        claimedDelivery = null
        claimedUnknownHandoff = null
        // Share the completed history while the original source is still fresh; the next
        // camera tick may arrive after its deadline. No reservation or source lease is moved.
        reconcileWarningSelection(nowMs)
        return true
    }

    fun reject(delivery: Delivery) {
        if (delivery.epoch == activeEpoch) policy.rejectUndeliveredFeedback(
            delivery.action.trackId, delivery.evaluatedAtMs)
        if (claimedDelivery == delivery) {
            claimedDelivery = null
            claimedUnknownHandoff = null
        }
    }

    fun diagnostic(nowMs: Long): String {
        if (activeEpoch == null) return "카메라 중지 · 출력 중지"
        val fresh = (samples.values + retainedUnknownSamples).filter { it.fresh(nowMs) }
        if (fresh.isEmpty()) return if (samples.isEmpty()) "실시간 위험 근거 대기" else "원본 프레임 만료 · 새 근거 대기"
        val outputs = fresh.flatMap { it.outputs }
        if (outputs.isEmpty()) return "경고 후보 없음 · 객체/깊이 근거 대기"
        val measured = outputs.filter { hasMetricEvidence(it) }
        if (measured.isEmpty()) return "거리 미확인 · 음성 경고 대기"
        val stable = measured.filter { it.trackAgeFrames >= 3 && it.trackStableMs >= 700L }
        if (stable.isEmpty()) return "추적 누적 중 · 3회/700ms 이상 필요"
        val eligible = selectWarningRegions(nowMs).candidates.count { it.output.confidence.finalScore >= 0.55f &&
            it.output.trackAgeFrames >= 3 && it.output.trackStableMs >= 700L }
        return if (eligible == 0) "현재 위험 경고 조건 미충족" else "위험 후보 ${eligible}개 · 공용 출력 대기열/반복 간격 적용"
    }

    private fun selectWarningRegions(nowMs: Long): WarningSelection {
        val ordered = (samples.values + retainedUnknownSamples).filter { it.fresh(nowMs) }.flatMap { sample ->
            AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(sample.outputs).filter { output ->
                hasMetricEvidence(output) && output.walkingObstacleCandidate &&
                    output.userFacing.messageLevel in ALERT_LEVELS &&
                    output.trackAgeFrames >= 3 && output.trackStableMs >= 700L &&
                    output.confidence.finalScore >= 0.55f
            }.map { Candidate(sample, it) }
        }.sortedWith(compareByDescending<Candidate> { it.output.userFacing.messageLevel.ordinal }
            .thenBy { it.sample.source.ordinal }.thenByDescending { it.output.confidence.finalScore })
        val primary = ordered.filter { it.sample.source == Source.PRIMARY }
        val independent = ordered.filter { it.sample.source == Source.UNKNOWN }
        val pairs = primary.flatMap { first -> independent.filter { second ->
            sameCapturedWarningRegion(first, second) || sameContinuouslyObservedWarningRegion(first, second)
        }.map { first to it } }
        val primaryMatches = pairs.groupingBy { it.first }.eachCount()
        val independentMatches = pairs.groupingBy { it.second }.eachCount()
        val suppressed = hashSetOf<Candidate>()
        val transfers = mutableListOf<Pair<String, String>>()
        pairs.forEach { (first, second) ->
            // Only a unique match in both sources may share a spoken-warning history.
            if (primaryMatches[first] != 1 || independentMatches[second] != 1) return@forEach
            val primaryLevel = first.output.userFacing.messageLevel
            val independentLevel = second.output.userFacing.messageLevel
            val keepIndependent = independentLevel.ordinal > primaryLevel.ordinal ||
                (independentLevel == primaryLevel &&
                    policy.hasClaimedFeedbackDelivery(second.output.trackId, independentLevel))
            val discarded = if (keepIndependent) first else second
            val retained = if (keepIndependent) second else first
            suppressed += discarded
            transfers += discarded.output.trackId to retained.output.trackId
        }
        return WarningSelection(ordered.filterNot { it in suppressed }, transfers)
    }

    private fun reconcileWarningSelection(nowMs: Long): List<Candidate> {
        val selection = selectWarningRegions(nowMs)
        rememberClaimedUnknownHandoff(selection, nowMs)
        policy.cancelFeedbackForTracks(selection.historyTransfers.mapTo(hashSetOf()) { it.first })
        selection.historyTransfers.forEach { (source, target) -> policy.carryCompletedFeedbackHistory(source, target) }
        return selection.candidates
    }

    private fun rememberClaimedUnknownHandoff(selection: WarningSelection, nowMs: Long) {
        val delivery = claimedDelivery ?: return
        if (claimedUnknownHandoffInvalidated) return
        if (!policy.hasClaimedFeedbackDelivery(delivery.action.trackId, delivery.action.level)) {
            claimedDelivery = null
            claimedUnknownHandoff = null
            claimedUnknownHandoffInvalidated = true
            return
        }
        val independent = selection.candidates.singleOrNull {
            it.sample.source == Source.UNKNOWN && it.output.trackId == delivery.action.trackId &&
                it.output.userFacing.messageLevel == delivery.action.level
        } ?: return
        val targetId = selection.historyTransfers.singleOrNull { it.second == delivery.action.trackId }?.first ?: return
        val primary = samples[Source.PRIMARY]?.takeIf { it.fresh(nowMs) } ?: return
        val anchorSample = warningObservations.firstOrNull { it.sample.frameId == independent.sample.frameId }?.sample
            ?: return
        val anchor = anchorSample.outputs.singleOrNull { it.trackId == targetId } ?: return
        if (claimedUnknownHandoff == null) {
            claimedUnknownHandoff = ClaimedUnknownHandoff(delivery, anchor,
                warningObservations.lastOrNull()?.takeIf { it.sample == primary } ?: return)
        }
    }

    /** Completion-only continuity. This never restores a source candidate or extends its start TTL. */
    private fun updateClaimedUnknownHandoff(sample: Sample, nowMs: Long) {
        val handoff = claimedUnknownHandoff ?: return
        val current = sample.outputs.singleOrNull { it.trackId == handoff.anchor.trackId }
        val previous = handoff.previous
        val previousOutput = previous.sample.outputs.single { it.trackId == handoff.anchor.trackId }
        if (!sample.fresh(nowMs) || current == null || !eligibleWarning(current) ||
            sample.epoch != previous.sample.epoch || sample.geometryId != previous.sample.geometryId ||
            sample.frameId - previous.sample.frameId !in 1L..warningTrackingLimits.maxFrameGapMs * 1_000_000L ||
            sample.capturedAtMs - previous.sample.capturedAtMs !in 0L..warningTrackingLimits.maxFrameGapMs ||
            nowMs - previous.observedAtMs !in 0L..warningTrackingLimits.maxFrameGapMs ||
            current.className != handoff.anchor.className ||
            current.motionEstimate.direction != handoff.anchor.motionEstimate.direction ||
            !compatibleMetricRegions(previousOutput, current) || !compatibleMetricRegions(handoff.anchor, current) ||
            sample.outputs.count { hasMetricEvidence(it) && compatibleMetricRegions(it, current) } != 1
        ) {
            claimedUnknownHandoff = null
            claimedUnknownHandoffInvalidated = true
            return
        }
        claimedUnknownHandoff = handoff.copy(previous = WarningObservation(sample, nowMs))
    }

    private fun recordWarningObservation(sample: Sample, nowMs: Long) {
        if (!sample.fresh(nowMs) || sample.outputs.isEmpty() ||
            warningObservations.lastOrNull()?.observedAtMs?.let { nowMs < it } == true
        ) warningObservations.clear()
        if (!sample.fresh(nowMs) || sample.outputs.isEmpty()) return
        warningObservations.addLast(WarningObservation(sample, nowMs))
        while (warningObservations.size > warningTrackingLimits.maxHistoryFrames ||
            nowMs - warningObservations.first().observedAtMs > UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS
        ) warningObservations.removeFirst()
    }

    private fun sameContinuouslyObservedWarningRegion(primary: Candidate, independent: Candidate): Boolean {
        if (primary.sample.epoch != independent.sample.epoch ||
            primary.sample.geometryId != independent.sample.geometryId ||
            primary.sample.capturedAtMs - independent.sample.capturedAtMs !in 1L..UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS ||
            primary.sample.frameId <= independent.sample.frameId
        ) return false
        val history = warningObservations.dropWhile { it.sample.frameId < independent.sample.frameId }
        val captured = history.firstOrNull()?.takeIf { it.sample.frameId == independent.sample.frameId } ?: return false
        if (history.size < 2 || history.last().sample != primary.sample) return false
        val anchor = captured.sample.outputs.filter { output ->
            eligibleWarning(output) && sameCapturedWarningRegion(Candidate(captured.sample, output), independent)
        }.singleOrNull()?.takeIf { it.trackId == primary.output.trackId } ?: return false
        var previous = captured
        var previousOutput = anchor
        for (observation in history) {
            val output = observation.sample.outputs.singleOrNull { it.trackId == primary.output.trackId } ?: return false
            if (!eligibleWarning(output) || output.className != anchor.className ||
                output.motionEstimate.direction != anchor.motionEstimate.direction ||
                observation.sample.frameId - previous.sample.frameId > warningTrackingLimits.maxFrameGapMs * 1_000_000L ||
                observation.sample.capturedAtMs - previous.sample.capturedAtMs > warningTrackingLimits.maxFrameGapMs ||
                observation.observedAtMs - previous.observedAtMs > warningTrackingLimits.maxFrameGapMs ||
                !compatibleMetricRegions(previousOutput, output) || !compatibleMetricRegions(anchor, output) ||
                observation.sample.outputs.count { hasMetricEvidence(it) && compatibleMetricRegions(it, output) } != 1
            ) return false
            previous = observation
            previousOutput = output
        }
        return true
    }

    private fun sameCapturedWarningRegion(a: Candidate, b: Candidate): Boolean {
        if (a.sample.source == b.sample.source || a.sample.geometryId != b.sample.geometryId ||
            a.sample.epoch != b.sample.epoch || a.sample.frameId != b.sample.frameId ||
            a.sample.frameId <= primaryContinuityResetFrameId ||
            a.sample.capturedAtMs != b.sample.capturedAtMs
        ) return false
        return compatibleMetricRegions(a.output, b.output)
    }

    private fun eligibleWarning(output: TrackedObjectDepth): Boolean = hasMetricEvidence(output) &&
        output.walkingObstacleCandidate && output.userFacing.messageLevel in ALERT_LEVELS &&
        !output.userFacing.message.isNullOrBlank() && output.trackAgeFrames >= 3 && output.trackStableMs >= 700L &&
        output.confidence.finalScore >= 0.55f

    private fun compatibleMetricRegions(a: TrackedObjectDepth, b: TrackedObjectDepth): Boolean =
        hasMetricEvidence(a) && hasMetricEvidence(b) &&
            abs(requireNotNull(a.riskDistanceM) - requireNotNull(b.riskDistanceM)) <= 0.30f &&
            intersectionOverUnion(a.bboxNorm, b.bboxNorm) >= 0.70f

    private fun hasMetricEvidence(output: TrackedObjectDepth): Boolean = output.source.metric &&
        output.riskDistanceM?.let { it.isFinite() && it > 0f } == true &&
        output.confidence.hardGate > 0f && output.confidence.freshnessQuality > 0f

    private fun intersectionOverUnion(a: RectNorm, b: RectNorm): Float {
        val width = (minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)).coerceAtLeast(0f)
        val height = (minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)).coerceAtLeast(0f)
        val intersection = width * height
        val union = a.area + b.area - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private companion object {
        val ALERT_LEVELS = setOf(MessageLevel.CAUTION, MessageLevel.WARNING, MessageLevel.STOP)
    }
}
