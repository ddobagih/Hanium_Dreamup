package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.CurrentTrackedFrameObservation
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackBatch
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackCandidate
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingConfig
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kotlin.math.abs

internal data class TactileFrameFeedbackDispatch(
    val activeRiskTrackIds: Set<String>,
    val activeFeedbackDeliveryKeys: Set<String>,
    val deviceGateAllowsAlerts: Boolean,
    val action: FeedbackAction?,
    val policyEvaluatedAtMs: Long,
)

internal fun interface TactileFrameFeedbackActuator {
    fun emit(dispatch: TactileFrameFeedbackDispatch)
}

/** Main supplies this only after admitting both producers against the current runtime context. */
internal data class WarningRegionContext(
    val epoch: WalkRuntimeEpoch,
    val geometryId: String,
    val detectorGeneration: Int,
)

/** Runtime seam from one detector snapshot through tactile guidance and feedback delivery. */
internal class AndroidTactileFrameCoordinator(
    private val tactileRouteGuidance: AndroidTactileRouteGuidance,
    private val feedbackPolicy: WalkSafeFeedbackPolicy,
    private val feedbackActuator: TactileFrameFeedbackActuator,
) {
    private data class SourcedFeedbackCandidate(
        val output: TrackedObjectDepth,
        val feedback: FeedbackCandidate,
        val independentBatch: UnknownObjectFeedbackBatch? = null,
    )

    private data class WarningObservation(
        val frameId: Long,
        val observedAtMs: Long,
        val candidates: List<SourcedFeedbackCandidate>,
    )

    private data class WarningRegionSelection(
        val candidates: List<SourcedFeedbackCandidate>,
        val historyTransfers: List<Pair<String, String>>,
    )

    private val warningObservations = ArrayDeque<WarningObservation>()
    private var warningContext: WarningRegionContext? = null
    private val warningTrackingLimits = VisualTrackingConfig()

    fun processSnapshot(
        detectionSnapshot: MainActivity.DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
        maxDetectionSourceAgeMs: Long,
        maxDetectionFrameDeltaMs: Long,
        detectionAgeMs: Long?,
        navigationActiveNow: Boolean,
        tmapOnRouteNow: Boolean,
        activeRouteId: String?,
        processDepth: (MainActivity.DetectionFrameEvidence, List<DetectionCandidate>) -> List<TrackedObjectDepth>,
    ): MainActivity.TactileSnapshotFrameResult {
        val candidateEvidence = detectionSnapshot.frameEvidence
        val matchedEvidence = matchingFrameEvidenceOrNull(
            evidence = candidateEvidence,
            identity = AndroidTactileFrameComposition.identity(
                detectionIdentity = detectionSnapshot.identity,
                evidenceIdentity = candidateEvidence?.tactileFrameIdentity(),
            ),
            context = candidateEvidence?.tactileContext,
            requireDepthMapper = false,
        )
        val depthDetections = detectionSnapshot.freshDetections(
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxSourceAgeMs = maxDetectionSourceAgeMs,
            maxFrameDeltaMs = maxDetectionFrameDeltaMs,
        )
        val depthProcessingAttempted = matchedEvidence?.depthMapper != null &&
            !detectionSnapshot.partial &&
            depthDetections.isNotEmpty()
        val outputs = if (depthProcessingAttempted) {
            processDepth(requireNotNull(matchedEvidence), depthDetections)
        } else {
            emptyList()
        }
        val guidance = evaluate(
            AndroidTactileFrameComposition.input(
                outputs = outputs,
                detectionIdentity = detectionSnapshot.identity,
                evidenceIdentity = matchedEvidence?.tactileFrameIdentity(),
                context = matchedEvidence?.tactileContext,
                detectionAgeMs = detectionAgeMs,
                navigationActiveAtCapture = matchedEvidence?.navigationActive == true,
                tmapOnRouteAtCapture = matchedEvidence?.tmapOnRoute == true,
                navigationActiveNow = navigationActiveNow,
                tmapOnRouteNow = tmapOnRouteNow,
                activeRouteId = activeRouteId,
            ),
        )
        return MainActivity.TactileSnapshotFrameResult(
            matchedEvidence = matchedEvidence,
            depthDetections = depthDetections,
            depthProcessingAttempted = depthProcessingAttempted,
            guidance = guidance,
        )
    }

    fun evaluate(input: AndroidTactileFrameInput): TactileRouteGuidanceResult = tactileRouteGuidance.apply(
        outputs = input.outputs,
        context = matchingFrameEvidenceOrNull(
            evidence = input.context,
            identity = input.identity,
            context = input.context,
            requireDepthMapper = true,
        )?.copy(detectionAgeMs = input.detectionAgeMs),
        navigationActive = input.navigationActiveAtCapture && input.navigationActiveNow,
        tmapOnRoute = input.tmapOnRouteAtCapture && input.tmapOnRouteNow,
        activeRouteId = input.activeRouteId,
    )

    /** Uses a verified current observation without publishing it as another detector snapshot. */
    fun processTrackedObservation(
        frame: CurrentTrackedFrameEvidence?,
        nowElapsedRealtimeMs: Long,
        navigationActiveNow: Boolean,
        tmapOnRouteNow: Boolean,
        activeRouteId: String?,
        processDepth: (CurrentTrackedFrameObservation, MainActivity.DetectionFrameEvidence) -> List<TrackedObjectDepth>,
    ): MainActivity.TactileSnapshotFrameResult {
        val current = frame?.takeIf { it.observation.isFreshAt(nowElapsedRealtimeMs) }
        val evidence = current?.currentEvidence
        val detections = current?.observation?.trackedObservations.orEmpty().mapNotNull { it.geometry }
        // An all-LOST observation still reaches the visual pipeline to invalidate its track state.
        val outputs = if (current != null) processDepth(current.observation, current.currentEvidence) else emptyList()
        val guidance = tactileRouteGuidance.apply(
            outputs = outputs,
            context = current?.let { it.currentEvidence.tactileContext.copy(detectionAgeMs = it.sourceAgeMs(nowElapsedRealtimeMs)) },
            navigationActive = evidence?.navigationActive == true && navigationActiveNow,
            tmapOnRoute = evidence?.tmapOnRoute == true && tmapOnRouteNow,
            activeRouteId = activeRouteId,
        )
        return MainActivity.TactileSnapshotFrameResult(
            matchedEvidence = evidence,
            depthDetections = detections,
            depthProcessingAttempted = current != null && detections.isNotEmpty(),
            guidance = guidance,
        )
    }

    fun dispatchFeedback(
        frame: MainActivity.TactileSnapshotFrameResult,
        stale: Boolean,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
        feedbackActuatorOverride: TactileFrameFeedbackActuator? = null,
        independentFreshOutputs: List<UnknownObjectFeedbackBatch> = emptyList(),
        warningContext: WarningRegionContext? = null,
    ): TactileFrameFeedbackDispatch {
        val currentCandidates = AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(frame.guidance.outputs).mapNotNull { output ->
            output.toFeedbackCandidate(stale = stale)?.let { SourcedFeedbackCandidate(output, it) }
        }
        val independentCandidates = independentFreshOutputs.filter { it.isFreshAt(nowMs) }.flatMap { batch ->
            AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(batch.outputs).mapNotNull { output ->
                output.toFeedbackCandidate()?.let { SourcedFeedbackCandidate(output, it, batch) }
            }
        }
        recordWarningObservation(currentCandidates, warningContext, stale || !deviceGateAllowsAlerts, nowMs)
        // A callback must not claim a reservation between representative selection and its
        // cancellation. Policy entry points synchronize on this same reentrant monitor.
        val dispatch = synchronized(feedbackPolicy) {
            val regions = coalesceCurrentWarningRegions(currentCandidates, independentCandidates)
            val selected = regions.candidates
            val selectedTrackIds = selected.mapTo(hashSetOf()) { it.feedback.trackId }
            val suppressedTrackIds = (currentCandidates + independentCandidates).mapTo(hashSetOf()) { it.feedback.trackId }
                .apply { removeAll(selectedTrackIds) }
            // A claimed reservation survives normal missing-frame reconciliation. Explicitly remove
            // a suppressed duplicate so neither its delayed start nor late completion owns a delivery.
            feedbackPolicy.cancelFeedbackForTracks(suppressedTrackIds)
            regions.historyTransfers.forEach { (source, target) ->
                feedbackPolicy.carryCompletedFeedbackHistory(source, target)
            }
            val candidates = selected.map { it.feedback }
                .sortedWith(compareByDescending<FeedbackCandidate> { it.level.ordinal }.thenByDescending { it.confidence })
            val sourceDeadlines = selected.mapNotNull { candidate ->
                candidate.independentBatch?.let { candidate.feedback.deliveryKey to it.validUntilElapsedRealtimeMs }
            }.groupBy({ it.first }, { it.second })
                .mapValues { (_, deadlines) -> deadlines.min() }
            val action = feedbackPolicy.evaluateCandidates(candidates, deviceGateAllowsAlerts, nowMs)?.let { selected ->
                sourceDeadlines[selected.deliveryKey]?.let { sourceDeadline ->
                    selected.copy(validUntilMs = minOf(selected.validUntilMs, sourceDeadline))
                } ?: selected
            }
            TactileFrameFeedbackDispatch(
                activeRiskTrackIds = feedbackPolicy.activeRiskTrackIds(
                    candidates = candidates,
                    deviceGateAllowsAlerts = deviceGateAllowsAlerts,
                ),
                activeFeedbackDeliveryKeys = feedbackPolicy.activeFeedbackDeliveryKeys(
                    candidates = candidates,
                    deviceGateAllowsAlerts = deviceGateAllowsAlerts,
                ),
                deviceGateAllowsAlerts = deviceGateAllowsAlerts,
                action = action,
                policyEvaluatedAtMs = nowMs,
            )
        }
        (feedbackActuatorOverride ?: feedbackActuator).emit(dispatch)
        return dispatch
    }

    private fun coalesceCurrentWarningRegions(
        primary: List<SourcedFeedbackCandidate>,
        independent: List<SourcedFeedbackCandidate>,
    ): WarningRegionSelection {
        val all = primary + independent
        val eligibleKeys = feedbackPolicy.activeFeedbackDeliveryKeys(all.map { it.feedback }, true)
        val pairs = primary.flatMap { first ->
            independent.filter { second ->
                first.feedback.deliveryKey in eligibleKeys && second.feedback.deliveryKey in eligibleKeys &&
                    (sameCapturedWarningRegion(first, second) || sameContinuouslyObservedWarningRegion(first, second))
            }.map { second -> first to second }
        }
        val primaryMatchCounts = pairs.groupingBy { it.first }.eachCount()
        val independentMatchCounts = pairs.groupingBy { it.second }.eachCount()
        val suppressed = hashSetOf<SourcedFeedbackCandidate>()
        val transfers = mutableListOf<Pair<String, String>>()
        pairs.forEach { (first, second) ->
            // Preserve ambiguous nearby objects: only a unique match in both sources is consumed.
            if (primaryMatchCounts[first] != 1 || independentMatchCounts[second] != 1) return@forEach
            // An already accepted equal-severity warning keeps its original source ownership.
            // Cancelling it merely because the primary catches up would restart the same alert.
            val keepIndependent = second.feedback.level.ordinal > first.feedback.level.ordinal ||
                (second.feedback.level == first.feedback.level &&
                    feedbackPolicy.hasClaimedFeedbackDelivery(second.feedback.trackId, second.feedback.level))
            val discarded = if (keepIndependent) first else second
            val retained = if (discarded === first) second else first
            suppressed += discarded
            transfers += discarded.feedback.trackId to retained.feedback.trackId
        }
        return WarningRegionSelection(all.filterNot { it in suppressed }, transfers)
    }

    private fun recordWarningObservation(
        primary: List<SourcedFeedbackCandidate>,
        context: WarningRegionContext?,
        invalid: Boolean,
        nowMs: Long,
    ) {
        if (context != warningContext || invalid || context == null ||
            warningObservations.lastOrNull()?.observedAtMs?.let { nowMs < it } == true
        ) warningObservations.clear()
        warningContext = context.takeUnless { invalid }
        if (warningContext == null) return
        val frameIds = primary.map { it.output.frameId }.distinct()
        // A missing/invalid current observation breaks continuity, even if the ID later returns.
        if (frameIds.size != 1 || frameIds.single() <= 0L) {
            warningObservations.clear()
            return
        }
        val frameId = frameIds.single()
        val previous = warningObservations.lastOrNull()
        if (previous != null && frameId <= previous.frameId) {
            // Replaying a capture cannot extend its observation history.
            if (frameId == previous.frameId && primary == previous.candidates) return
            warningObservations.clear()
        }
        warningObservations.addLast(WarningObservation(frameId, nowMs, primary))
        while (warningObservations.size > warningTrackingLimits.maxHistoryFrames ||
            nowMs - warningObservations.first().observedAtMs > UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS
        ) warningObservations.removeFirst()
    }

    private fun sameContinuouslyObservedWarningRegion(
        primary: SourcedFeedbackCandidate,
        independent: SourcedFeedbackCandidate,
    ): Boolean {
        val context = warningContext ?: return false
        val batch = independent.independentBatch ?: return false
        if (batch.sourceEpoch != context.epoch || batch.sourceFrameId >= primary.output.frameId ||
            primary.output.timestampMs - batch.sourceTimestampMs !in 1L..UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS
        ) return false
        val history = warningObservations.dropWhile { it.frameId < batch.sourceFrameId }
        val captured = history.firstOrNull()?.takeIf { it.frameId == batch.sourceFrameId } ?: return false
        if (history.last().frameId != primary.output.frameId || history.size < 2) return false
        // Match the auxiliary mask at its actual capture, including every primary peer there.
        // The link is warning-region evidence, not a new object identity or current geometry.
        val captureMatches = captured.candidates.filter { sameCapturedWarningRegion(it, independent) }
        val anchor = captureMatches.singleOrNull()?.takeIf { it.feedback.trackId == primary.feedback.trackId }
            ?: return false
        var previous = captured
        var previousCandidate = anchor
        for (observation in history) {
            val candidate = observation.candidates.singleOrNull { it.feedback.trackId == primary.feedback.trackId }
                ?: return false
            if (candidate.feedback.deliveryKey !in feedbackPolicy.activeFeedbackDeliveryKeys(listOf(candidate.feedback), true) ||
                candidate.feedback.stale ||
                !candidate.feedback.alertableLevel || !hasCurrentMetricRegion(candidate.output) ||
                candidate.output.timestampMs != candidate.output.frameId / 1_000_000L ||
                candidate.output.className != anchor.output.className ||
                candidate.output.motionEstimate.direction != anchor.output.motionEstimate.direction ||
                observation.frameId - previous.frameId > warningTrackingLimits.maxFrameGapMs * 1_000_000L ||
                observation.observedAtMs - previous.observedAtMs > warningTrackingLimits.maxFrameGapMs ||
                !compatibleMetricRegions(previousCandidate.output, candidate.output) ||
                !compatibleMetricRegions(anchor.output, candidate.output) ||
                observation.candidates.count { peer ->
                    hasCurrentMetricRegion(peer.output) && compatibleMetricRegions(peer.output, candidate.output)
                } != 1
            ) return false
            previous = observation
            previousCandidate = candidate
        }
        return true
    }

    private fun sameCapturedWarningRegion(
        primary: SourcedFeedbackCandidate,
        independent: SourcedFeedbackCandidate,
    ): Boolean {
        val a = primary.output
        val b = independent.output
        val batch = independent.independentBatch ?: return false
        // This is also the capture-aligned anchor for the bounded temporal continuity check.
        if (a.frameId <= 0L || a.frameId != batch.sourceFrameId || b.frameId != batch.sourceFrameId ||
            a.timestampMs != a.frameId / 1_000_000L || a.timestampMs != batch.sourceTimestampMs ||
            b.timestampMs != batch.sourceTimestampMs ||
            !primary.feedback.alertableLevel || !independent.feedback.alertableLevel ||
            !hasCurrentMetricRegion(a) || !hasCurrentMetricRegion(b)
        ) return false
        return compatibleMetricRegions(a, b)
    }

    private fun compatibleMetricRegions(a: TrackedObjectDepth, b: TrackedObjectDepth): Boolean {
        if (abs(requireNotNull(a.riskDistanceM) - requireNotNull(b.riskDistanceM)) > 0.30f) return false
        val left = a.bboxNorm
        val right = b.bboxNorm
        val width = (minOf(left.x + left.width, right.x + right.width) - maxOf(left.x, right.x)).coerceAtLeast(0f)
        val height = (minOf(left.y + left.height, right.y + right.height) - maxOf(left.y, right.y)).coerceAtLeast(0f)
        val intersection = width * height
        val union = left.area + right.area - intersection
        return union > 0f && intersection / union >= 0.70f
    }

    private fun hasCurrentMetricRegion(output: TrackedObjectDepth): Boolean {
        val box = output.bboxNorm
        return output.source.metric && output.walkingObstacleCandidate &&
            output.riskDistanceM?.let { it.isFinite() && it > 0f } == true &&
            output.confidence.hardGate.isFinite() && output.confidence.hardGate > 0f &&
            output.confidence.freshnessQuality.isFinite() && output.confidence.freshnessQuality > 0f &&
            box.x.isFinite() && box.y.isFinite() && box.width.isFinite() && box.height.isFinite() &&
            box.x >= 0f && box.y >= 0f && box.width > 0f && box.height > 0f &&
            box.x + box.width <= 1f && box.y + box.height <= 1f
    }

    fun <T> matchingFrameEvidenceOrNull(
        evidence: T?,
        identity: AndroidTactileFrameIdentity,
        context: TactileProjectionContext?,
        requireDepthMapper: Boolean,
    ): T? = evidence?.takeIf { identity.matchesCapture(context, requireDepthMapper) }
}

internal fun createProductionAndroidTactileFrameCoordinator(
    feedbackPolicy: WalkSafeFeedbackPolicy,
    feedbackActuator: TactileFrameFeedbackActuator,
): AndroidTactileFrameCoordinator = AndroidTactileFrameCoordinator(
    tactileRouteGuidance = AndroidTactileRouteGuidance(),
    feedbackPolicy = feedbackPolicy,
    feedbackActuator = feedbackActuator,
)
