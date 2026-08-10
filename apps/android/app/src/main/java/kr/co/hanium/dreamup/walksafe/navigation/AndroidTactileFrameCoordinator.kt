package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate

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

/** Runtime seam from one detector snapshot through tactile guidance and feedback delivery. */
internal class AndroidTactileFrameCoordinator(
    private val tactileRouteGuidance: AndroidTactileRouteGuidance,
    private val feedbackPolicy: WalkSafeFeedbackPolicy,
    private val feedbackActuator: TactileFrameFeedbackActuator,
) {
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

    fun dispatchFeedback(
        frame: MainActivity.TactileSnapshotFrameResult,
        stale: Boolean,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
        feedbackActuatorOverride: TactileFrameFeedbackActuator? = null,
    ): TactileFrameFeedbackDispatch {
        val candidates = AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(frame.guidance.outputs).mapNotNull { output ->
            output.toFeedbackCandidate(stale = stale)
        }
        val dispatch = TactileFrameFeedbackDispatch(
            activeRiskTrackIds = feedbackPolicy.activeRiskTrackIds(
                candidates = candidates,
                deviceGateAllowsAlerts = deviceGateAllowsAlerts,
            ),
            activeFeedbackDeliveryKeys = feedbackPolicy.activeFeedbackDeliveryKeys(
                candidates = candidates,
                deviceGateAllowsAlerts = deviceGateAllowsAlerts,
            ),
            deviceGateAllowsAlerts = deviceGateAllowsAlerts,
            action = feedbackPolicy.evaluateCandidates(
                candidates = candidates,
                deviceGateAllowsAlerts = deviceGateAllowsAlerts,
                nowMs = nowMs,
            ),
            policyEvaluatedAtMs = nowMs,
        )
        (feedbackActuatorOverride ?: feedbackActuator).emit(dispatch)
        return dispatch
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
