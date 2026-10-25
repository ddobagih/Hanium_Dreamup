package kr.co.hanium.dreamup.walksafe.navigation.capture

import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.TactileSemanticMaskSelector
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot

/** Single consumed-result owner; source measurement and policy remain in the actual collaborators. */
internal class TactileFrameCaptureController<S : TactileCaptureSourceFacts, R : TactileCaptureReceiptFacts<S>>(
    private val frameStateLock: Any,
    private val coordinatorProvider: () -> AndroidTactileFrameCoordinator,
    private val navigator: () -> RouteNavigator,
    private val routeFacts: TactileCaptureRouteFacts,
    private val newReceipt: (S, Boolean) -> R,
    feedbackDelivery: (TactileFrameFeedbackDispatch) -> Unit,
) {
    private var latestConsumedResult: R? = null
    var feedbackDelivery: (TactileFrameFeedbackDispatch) -> Unit = feedbackDelivery

    /** Invoked at the original geometry-retirement point under the same frame publication lock. */
    fun retireConsumedResult() = synchronized(frameStateLock) { latestConsumedResult = null }

    fun processTactileSnapshotFrame(
        detectionSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
        maxDetectionSourceAgeMs: Long,
        maxDetectionFrameDeltaMs: Long,
        detectionAgeMs: Long?,
        decisionElapsedRealtimeMs: Long? = null,
        coordinator: AndroidTactileFrameCoordinator = coordinatorProvider(),
        processDepth: (DetectionFrameEvidence, List<DetectionCandidate>) -> List<TrackedObjectDepth>,
    ): TactileSnapshotFrameResult = synchronized(frameStateLock) { coordinator.processSnapshot(
        detectionSnapshot = detectionSnapshot,
        nowMs = nowMs,
        currentFrameTimestampMs = currentFrameTimestampMs,
        maxDetectionSourceAgeMs = maxDetectionSourceAgeMs,
        maxDetectionFrameDeltaMs = maxDetectionFrameDeltaMs,
        detectionAgeMs = detectionAgeMs,
        decisionElapsedRealtimeMs = decisionElapsedRealtimeMs,
        navigationActiveNow = routeFacts.navigationActiveNow(),
        tmapOnRouteNow = routeFacts.tmapOnRouteNow(),
        activeRouteId = navigator().currentRouteId(),
        activeRouteProgress = navigator().currentTactileRouteProgress(),
        processDepth = processDepth,
    ) }

    fun prepareTactileFrameDispatch(
        detectionSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
        maxDetectionSourceAgeMs: Long,
        maxDetectionFrameDeltaMs: Long,
        detectionAgeMs: Long?,
        decisionElapsedRealtimeMs: Long? = null,
        coordinator: AndroidTactileFrameCoordinator = coordinatorProvider(),
        processDepth: (DetectionFrameEvidence, List<DetectionCandidate>) -> List<TrackedObjectDepth>,
    ): PreparedTactileFrameDispatch = ProductionPreparedTactileFrameDispatch(
        frame = this.processTactileSnapshotFrame(
            detectionSnapshot = detectionSnapshot,
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxDetectionSourceAgeMs = maxDetectionSourceAgeMs,
            maxDetectionFrameDeltaMs = maxDetectionFrameDeltaMs,
            detectionAgeMs = detectionAgeMs,
            decisionElapsedRealtimeMs = decisionElapsedRealtimeMs,
            coordinator = coordinator,
            processDepth = processDepth,
        ),
    )

    fun evaluateTactileFrame(
        input: AndroidTactileFrameInput,
    ): TactileRouteGuidanceResult = synchronized(frameStateLock) { coordinatorProvider().evaluate(input) }

    /** Orders actual results, including empty/rejected ones; camera arrival never advances it. */
    fun consumeTactileFrameResult(
        source: S,
        frame: TactileSnapshotFrameResult,
        maskUpgrade: Boolean = false,
        admissionIsCurrent: (() -> Boolean)? = null,
        consume: (TactileSnapshotFrameResult, R) -> Unit,
    ): Boolean {
        val admitted = synchronized(frameStateLock) {
            if (admissionIsCurrent?.invoke() == false) return false
            if (!tactileResultRouteIsCurrent(source)) return false
            val previous = latestConsumedResult
            if (previous != null && previous.source.owner == source.owner &&
                previous.source.geometryVersion == source.geometryVersion
            ) {
                val old = previous.source
                if (source.frameId < old.frameId || source.capturedAtElapsedRealtimeMs < old.capturedAtElapsedRealtimeMs)
                    return false
                if (source.frameId == old.frameId &&
                    (source != old || !maskUpgrade || previous.maskUpgrade)) return false
            }
            val receipt = newReceipt(source, maskUpgrade)
            latestConsumedResult = receipt
            val guidance = frame.guidance
            val conflicting = AndroidRiskSelectionPolicy.hasConflictingTactileState(guidance.outputs)
            val safeFrame = frame.copy(guidance = guidance.copy(
                observation = if (conflicting) null else guidance.observation,
                dotReferenceNotice = if (conflicting) null else guidance.dotReferenceNotice,
                crosswalkReferenceNotice = if (conflicting) null else guidance.crosswalkReferenceNotice,
                decision = if (conflicting) guidance.decision.copy(
                    mode = kr.co.hanium.dreamup.walksafe.navigation.LocalRouteMode.TMAP,
                    steering = null, reason = "conflicting_tactile_state", instruction = null,
                ) else guidance.decision,
            ))
            safeFrame to receipt
        }
        consume(admitted.first, admitted.second)
        return true
    }

    fun tactileResultIsCurrent(receipt: R): Boolean =
    synchronized(frameStateLock) { latestConsumedResult === receipt && tactileResultRouteIsCurrent(receipt.source) }

    fun <T> consumeCurrentTactileResult(receipt: R, consume: () -> T): T? =
    synchronized(frameStateLock) { if (tactileResultIsCurrent(receipt)) consume() else null }

    private fun tactileResultRouteIsCurrent(source: S): Boolean =
    source.routeId == navigator().currentRouteId() &&
        source.routeProgress == navigator().currentTactileRouteProgress()

    /** Same worker composition/forwarding entry; no depth or tracker processing is replayed. */
    fun deliverTactileMaskCaptureBundle(
        bundle: TactileMaskCaptureBundle,
        decisionNs: Long,
        surfaces: List<TactileMaskSurfaceResult>,
        source: S,
        admissionIsCurrent: (() -> Boolean)? = null,
        consume: (TactileSnapshotFrameResult, R) -> Unit,
    ): Boolean {
        val token = bundle.semanticCapture.token
        if (source.frameId != token.frameId || source.capturedAtElapsedRealtimeMs != token.capturedElapsedNs / 1_000_000L ||
            source.cpuImageTimestampNs != token.cpuImageTimestampNs ||
            decisionNs < token.capturedElapsedNs || decisionNs > bundle.semanticCapture.originalDeadlineElapsedNs
        ) return false
        val guidance = evaluateTactileMaskCaptureBundle(bundle, decisionNs, surfaces)
        return consumeTactileFrameResult(source, TactileSnapshotFrameResult(bundle.snapshot.frameEvidence,
            bundle.snapshot.detections, depthProcessingAttempted = false, guidance), maskUpgrade = true,
            admissionIsCurrent = admissionIsCurrent, consume = consume)
    }

    /** Original completed capture composition; never invokes processSnapshot/processDepth. */
    fun evaluateTactileMaskCaptureBundle(
        bundle: TactileMaskCaptureBundle,
        decisionElapsedRealtimeNs: Long,
        surfaces: List<TactileMaskSurfaceResult> = projectTactileMaskCaptureBundle(bundle, decisionElapsedRealtimeNs),
    ): TactileRouteGuidanceResult = synchronized(frameStateLock) {
        val evidence = bundle.snapshot.frameEvidence
        evaluateTactileFrame(AndroidTactileFrameComposition.input(
            outputs = bundle.outputs,
            detectionIdentity = bundle.snapshot.identity,
            evidenceIdentity = evidence?.tactileFrameIdentity(),
            context = evidence?.tactileContext,
            detectionAgeMs = decisionElapsedRealtimeNs / 1_000_000L -
                bundle.semanticCapture.token.capturedElapsedNs / 1_000_000L,
            navigationActiveAtCapture = evidence?.navigationActive == true,
            tmapOnRouteAtCapture = evidence?.tmapOnRoute == true,
            navigationActiveNow = routeFacts.navigationActiveNow(),
            tmapOnRouteNow = routeFacts.tmapOnRouteNow(),
            activeRouteId = navigator().currentRouteId(),
            activeRouteProgress = navigator().currentTactileRouteProgress(),
            decisionElapsedRealtimeMs = decisionElapsedRealtimeNs / 1_000_000L,
            tactileSurfaces = surfaces,
            decisionElapsedRealtimeNs = decisionElapsedRealtimeNs,
        ))
    }

    fun projectTactileMaskCaptureBundle(
        bundle: TactileMaskCaptureBundle,
        decisionElapsedRealtimeNs: Long,
    ): List<TactileMaskSurfaceResult> {
        val selected = TactileSemanticMaskSelector(bundle.runtimeConfig).select(
            bundle.named, bundle.masks, decisionElapsedRealtimeNs)
        val projector = TactileMaskSurfaceProjector()
        return selected.candidates.map {
            projector.project(it, bundle.frozenCapture, decisionElapsedRealtimeNs,
                bundle.snapshot.frameEvidence?.tactileContext)
        }
    }

    fun dispatchTactileFrameFeedback(
        frame: TactileSnapshotFrameResult,
        stale: Boolean,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
        feedbackActuatorOverride: TactileFrameFeedbackActuator? = null,
        independentFreshOutputs: List<UnknownObjectFeedbackBatch> = emptyList(),
        warningContext: kr.co.hanium.dreamup.walksafe.navigation.WarningRegionContext? = null,
    ): TactileFrameFeedbackDispatch = coordinatorProvider().dispatchFeedback(
        frame = frame,
        stale = stale,
        deviceGateAllowsAlerts = deviceGateAllowsAlerts,
        nowMs = nowMs,
        feedbackActuatorOverride = feedbackActuatorOverride,
        independentFreshOutputs = independentFreshOutputs,
        warningContext = warningContext,
    )

    fun dispatchConsumedTactileFrameFeedback(
        receipt: R,
        frame: TactileSnapshotFrameResult,
        stale: Boolean,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
        feedbackActuatorOverride: TactileFrameFeedbackActuator? = null,
        independentFreshOutputs: List<UnknownObjectFeedbackBatch> = emptyList(),
        warningContext: kr.co.hanium.dreamup.walksafe.navigation.WarningRegionContext? = null,
    ): TactileFrameFeedbackDispatch? {
        val dispatch = synchronized(frameStateLock) {
            if (!tactileResultIsCurrent(receipt)) return null
            dispatchTactileFrameFeedback(frame, stale, deviceGateAllowsAlerts, nowMs,
                TactileFrameFeedbackActuator { }, independentFreshOutputs, warningContext)
        }
        (feedbackActuatorOverride ?: TactileFrameFeedbackActuator { feedbackDelivery(it) }).emit(dispatch)
        return dispatch
    }

    private inner class ProductionPreparedTactileFrameDispatch(
        override val frame: TactileSnapshotFrameResult,
    ) : PreparedTactileFrameDispatch {
        override fun dispatchFeedback(
            stale: Boolean,
            deviceGateAllowsAlerts: Boolean,
            nowMs: Long,
        ): TactileFrameFeedbackDispatch = coordinatorProvider().dispatchFeedback(
            frame = frame,
            stale = stale,
            deviceGateAllowsAlerts = deviceGateAllowsAlerts,
            nowMs = nowMs,
        )
    }
}
