package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import com.google.ar.core.Coordinates2d
import com.google.ar.core.TrackingState
import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.PrimaryTrackingPriorityPolicy
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.GrayTrackingFrame
import kr.co.hanium.dreamup.walksafe.navigation.CurrentTrackedFrameEvidence
import kr.co.hanium.dreamup.walksafe.navigation.TactileCurrentFrameDiagnostics
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Actual frame transaction: acquire, qualify, synthesize and publish, all on the original GL call. */
internal class FramePipelineController<L : Any, Q, S, R>(
    private val frameStateLock: Any,
    private val render: ArFrameRenderController<L, Q>,
    private val runtime: FrameRuntimePeer<L, Q>,
    private val scheduling: FrameDetectionSchedulingPeer<L>,
    private val synthesis: FrameSynthesisController,
    private val geometry: ArFrameGeometryFreezer,
    private val route: FrameRouteEvidencePeer,
    private val tactile: FrameTactileResultPeer<L, S, R>,
    private val risk: FrameRiskOutputPeer<R>,
    private val recording: FrameRecordingPeer,
    private val presentation: () -> FramePresentationController<L>,
    private val publication: FramePublicationController<L, S, R>,
    private val noDetectionStatus: (DepthFrameSnapshot) -> String,
    private val clock: ArFrameClock,
) {
    private data class Qualified<L : Any, Q>(val source: ScopedArFrameCapture<L, Q>,
        val epoch: WalkRuntimeEpoch, val gray: GrayTrackingFrame?, val tracking: Boolean,
        val detection: DetectionSnapshot, val selection: FrameOverlaySelection)

    fun draw() = render.draw { source ->
        val qualified = qualify(source) ?: return@draw
        val outputs = synthesize(qualified) ?: return@draw
        publication.publish(outputs) { source.frame.timestamp }
    }

    /** Repeated frames still reach the original metric-loss gate before duplicate admission. */
    private fun qualify(source: ScopedArFrameCapture<L, Q>): Qualified<L, Q>? {
        if (!runtime.runtimePurpose(source.resources.purpose) || !runtime.startupConfirmed()) {
            runtime.revokeMetricOutput(); return null
        }
        val epoch = runtime.runtimeEpochOrNull()
        if (epoch == null) { runtime.revokeMetricOutput(); return null }
        runtime.updateMetricOutput(source, epoch)
        val native = source.frame
        val lease = source.resources.originalLease
        if (!synthesis.admitRuntimeFrame(source.frameGeneration, epoch) { native.timestamp }) return null
        val fps = runtime.expectedCameraFps(lease)
        runtime.observeRuntimeFrame(fps) { native.camera.trackingState == TrackingState.TRACKING }
        val input = source.recognition?.input?.copy(timestampEvidence = source.recognition.timestampEvidence)
        val gray = synthesis.captureVisualTrackingFrame(input, source.snapshot, source.frameGeneration, epoch)
        scheduling.scheduleUnknown(input, native, source.snapshot, source.capturedElapsedMs,
            source.frameGeneration, epoch, lease)
        scheduling.scheduleNamed(input, lease, native, source.timestampMs, source.wallClockMs,
            source.capturedElapsedMs, source.snapshot, source.frameGeneration, epoch, source.resources.generation, gray)
        val tracking = native.camera.trackingState == TrackingState.TRACKING
        if (!runtime.visualOutputAllowsWork(lease, native.timestamp, tracking)) {
            recording.acquisitionFailures(source.resources.provider, source.capturedElapsedMs)
            presentation().showUnavailable(source.capturedElapsedMs, source.frameGeneration, epoch, lease) {
                val observed = synthesis.detectionSnapshot
                val sourceAt = observed.capturedAtElapsedRealtimeMs
                val fresh = sourceAt != null && source.capturedElapsedMs - sourceAt in 0L..MAX_OVERLAY_AGE_MS
                val boxes = geometry.buildDebugOverlayBoxes(native,
                    if (fresh) observed.detections else emptyList(), null, gray?.sourceWidth, gray?.sourceHeight)
                val message = "ARCore ${native.camera.trackingState}\n${noDetectionStatus(source.snapshot)}\n" +
                    observed.debugStatusText(source.wallClockMs, source.timestampMs, MAX_OVERLAY_AGE_MS, MAX_OVERLAY_DELTA_MS) +
                    "\ndepthAcquisitionFailures=${source.resources.provider.runtimeAcquisitionDiagnostics()}"
                val displayFrameId = observed.frameEvidence?.frameId ?: native.timestamp
                FrameUnavailableProjection(boxes, displayFrameId, sourceAt, fresh, message)
            }
            return null
        }
        val detection = synthesis.detectionSnapshot
        val selection = synchronized(frameStateLock) {
            if (!runtime.ownsFrame(source.frameGeneration, epoch)) return null
            synthesis.selectOverlayDetections(detection, source.wallClockMs, source.timestampMs)
        }
        return Qualified(source, epoch, gray, tracking, detection, selection)
    }

    private fun synthesize(q: Qualified<L, Q>): FramePublicationPacket<L, R>? {
        val source = q.source
        val native = source.frame
        val lease = source.resources.originalLease
        val generation = source.frameGeneration
        val arGeneration = source.resources.generation
        val epoch = q.epoch
        val detection = q.detection
        val decision = clock.elapsedRealtimeMs()
        val frozen = if (runtime.metricOutputAllowsWork(arGeneration)) tactile.prepareFrozen(detection,
            source.wallClockMs, source.timestampMs, decision) { evidence, detections ->
            synthesis.processFrozenDepth(detection, evidence, detections, generation, epoch)
        } else null
        val frozenOutputs = frozen?.frame?.guidance?.outputs.orEmpty()
        if (frozen?.frame?.depthProcessingAttempted == true) tactile.recordFrozen(detection, frozenOutputs)
        val priorities = PrimaryTrackingPriorityPolicy.schedulingIndices(detection.detections, frozenOutputs,
            detection.frameEvidence?.frameId ?: 0L, detection.sourceAgeMs(source.wallClockMs))
        val trackingStarted = clock.elapsedRealtimeMs()
        val dispatch = synthesis.trackSource(detection, q.gray, generation, epoch, priorities)
        if (dispatch === FrameSourceTracking.Retired) return null
        val tracking = (dispatch as FrameSourceTracking.Evaluated).result
        if (tracking?.redetectRequested == true) runtime.requestEarlyDetection(clock.elapsedRealtimeMs())
        runtime.recordTrackingLoad(source.snapshot, { native.timestamp }, source.depthProcessingMs,
            trackingStarted, q.gray != null, { synthesis.trackingBudgetNs })
        val evidence = if (q.gray != null && tracking != null) {
            val basis = FloatArray(6)
            native.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED,
                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), Coordinates2d.VIEW, basis)
            val turns = UprightCameraImage.quarterTurns(basis)
            if (turns == null) null else DetectionFrameEvidence(native.timestamp, source.timestampMs,
                source.snapshot, geometry.createFrozenDepthMapper(native, native.timestamp, q.gray.sourceWidth,
                    q.gray.sourceHeight, source.snapshot, allowMissingDepth = true),
                route.projectionContext(native, source.capturedElapsedMs), route.motionContext(source.capturedElapsedMs),
                route.navigationActive(), route.tmapOnRoute(), visualFrameKey = q.gray.key, imageQuarterTurns = turns)
        } else null
        val observation = if (tracking != null && evidence != null)
            CurrentTrackedFrameEvidence.fromTrackingResult(detection, tracking, evidence, clock.elapsedRealtimeMs()) else null
        val overlays = observation?.observation?.trackedObservations.orEmpty().mapNotNull { it.geometry }
        var diagnostics = q.selection.debugState.copy(selection = "current_visual_observation",
            detectionCount = overlays.size, holdApplied = false)
        synthesis.recordTrackingStatus(tracking, overlays.size)
        if (!runtime.visualOutputAllowsWork(lease, native.timestamp, q.tracking)) return null
        val tactileAt = clock.elapsedRealtimeMs()
        val currentFrameTracking = q.tracking
        var issued: FrameTactileSource<S>? = null
        val raw = synchronized(frameStateLock) {
            val sourceLease = tactile.currentSource(epoch, generation, arGeneration, { native.timestamp },
                source.capturedElapsedMs, source.snapshot.cameraImageTimestampNs, { synthesis.geometryVersion })
            issued = sourceLease
            tactile.coordinator().processTrackedObservation(frame = observation,
                observationDiagnostics = TactileCurrentFrameDiagnostics.fromCapture(detection, q.gray?.key, evidence, tracking, observation, tactileAt),
                nowElapsedRealtimeMs = tactileAt, navigationActiveNow = route.navigationActive(), tmapOnRouteNow = route.tmapOnRoute(),
                activeRouteId = sourceLease.routeId, activeRouteProgress = sourceLease.routeProgress) { current, capture ->
                synthesis.processCurrentDepth(current, capture, generation, epoch) {
                    runtime.visualOutputAllowsWork(lease, native.timestamp, q.tracking)
                }
            }
        }
        val sourceLease = issued ?: return null
        var frame: TactileSnapshotFrameResult? = null
        var receipt: R? = null
        if (!tactile.consume(sourceLease.issued, raw, {
                sourceLease.geometryVersion == synthesis.geometryVersion &&
                    sourceLease.owner.feedbackLifecycleGeneration == tactile.feedbackLifecycleGeneration() &&
                    runtime.ownsFrame(generation, epoch) && runtime.visualOutputAllowsWork(lease, native.timestamp, currentFrameTracking)
            }) { accepted, token ->
                if (tactile.publishReference(accepted, sourceLease.owner, token)) { frame = accepted; receipt = token }
            }) return null
        val result = frame ?: return null
        val token = receipt ?: return null
        // This closure retains only the actual receipt and scalar/lease context, never native or source.
        val sourceCurrent = { tactile.resultIsCurrent(token) && runtime.ownsFrame(generation, epoch) && runtime.leaseIsCurrent(lease) }
        tactile.crosswalkReference(sourceLease.owner, lease, sourceCurrent)
        val matched = result.matchedEvidence
        val depthDetections = result.depthDetections
        val depthInputs = if (matched?.depthMapper == null) emptyList() else depthDetections
        val outputs = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(result.guidance.outputs, clock.elapsedRealtimeMs())
        val best = AndroidRiskSelectionPolicy.selectBestOutput(outputs)
        val report = AndroidRiskSelectionPolicy.selectReportOutput(frozenOutputs)
        val automatic = AndroidRiskSelectionPolicy.selectAutomaticReportOutput(frozenOutputs)
        val independent = risk.freshIndependentOutputs(clock.elapsedRealtimeMs())
        val prioritized = AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(outputs + independent.flatMap { it.outputs })
        val gate = prioritized.firstOrNull { it.source.metric && it.confidence.finalScore >= FEEDBACK_MIN_DEPTH_CONFIDENCE }
        val qualitativeGate = outputs.firstOrNull { it.isCurrentQualitativeCaution(clock.elapsedRealtimeMs()) }
        val uiScope = FramePresentationScope(generation, epoch, lease, arGeneration, source.capturedElapsedMs,
            source.originalFrameTimestampNs, currentFrameTracking)
        diagnostics = presentation().showOverlay(FrameOverlayPresentationInput(uiScope, observation != null, q.gray?.key?.frameId,
            detection.capturedAtElapsedRealtimeMs, q.gray?.key?.capturedAtElapsedRealtimeMs, best, diagnostics,
            overlays.size), { native.timestamp }) { overlayOutput ->
            geometry.buildDebugOverlayBoxes(native, overlays, overlayOutput, q.gray?.sourceWidth, q.gray?.sourceHeight,
                q.gray?.key?.capturedAtElapsedRealtimeMs)
        } ?: return null
        // Native input is synchronous only; delayed presentation receives this SDK-free packet.
        val frozenUiScope = uiScope.copy(originalFrameTimestampNs = source.originalFrameTimestampNs)
        return FramePublicationPacket(frozenUiScope, source.resources.provider, source.timestampMs, source.wallClockMs,
            source.snapshot, detection, q.gray, result, token, sourceLease.owner, sourceCurrent, frozen,
            frozenOutputs, outputs, best, report, automatic, independent, gate, qualitativeGate, diagnostics,
            overlays.size, depthInputs)
    }

    private companion object {
        const val MAX_OVERLAY_AGE_MS = 1_200L
        const val MAX_OVERLAY_DELTA_MS = 1_200L
        const val FEEDBACK_MIN_DEPTH_CONFIDENCE = 0.55f
    }
}
