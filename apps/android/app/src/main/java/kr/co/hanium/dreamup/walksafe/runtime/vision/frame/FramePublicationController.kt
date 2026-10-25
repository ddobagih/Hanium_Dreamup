package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.delivery.FeedbackRejectionProducerScope
import kr.co.hanium.dreamup.walksafe.navigation.TactileFrameFeedbackActuator
import kr.co.hanium.dreamup.walksafe.navigation.WarningRegionContext

/** Output transaction, independent of native Frame/Image lifetime. Original policy peers remain authoritative. */
internal class FramePublicationController<L : Any, S, R>(
    private val frameStateLock: Any,
    private val runtimeOwnsFrame: (Int, kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch) -> Boolean,
    private val metricOutputAllowed: (Long) -> Boolean,
    private val visualOutputAllowed: (L, Long, Boolean) -> Boolean,
    private val tactile: FrameTactileResultPeer<L, S, R>,
    private val risk: FrameRiskOutputPeer<R>,
    private val recording: FrameRecordingPeer,
    private val reports: FrameReportPublicationPeer,
    private val presentation: () -> FramePresentationController<L>,
    private val clock: ArFrameClock,
) {
    // Synchronous supplier only: preserves the original qualitative branch's native read point.
    // It is invoked on this stack and is never captured by a delayed output callback.
    fun publish(input: FramePublicationPacket<L, R>, frameTimestampNs: () -> Long) {
        val scope = input.scope
        if (!runtimeOwnsFrame(scope.frameGeneration, scope.walkEpoch)) return
        if (!metricOutputAllowed(scope.arGeneration)) {
            publishQualitative(input, frameTimestampNs)
            return
        }
        val matched = input.frame.matchedEvidence
        val mapper = matched?.depthMapper
        val entry = recording.buildCaptureEntry(FrameCaptureAudit(input.timestampMs, input.wallClockMs,
            input.detectionSnapshot, input.overlayDiagnostics, input.depthInputDetections,
            matched?.depthSnapshot ?: input.depthSnapshot, input.bestOutput,
            if (mapper == null) "unavailable" else "current_visual_image_to_texture_normalized",
            when {
                matched == null -> "matched_detection_frame_evidence_unavailable"
                mapper == null -> "frozen_depth_mapper_unavailable"
                input.frame.depthDetections.isNotEmpty() && input.depthInputDetections.isEmpty() -> "depth_input_suppressed"
                else -> null
            }))
        synchronized(frameStateLock) {
            if (!runtimeOwnsFrame(scope.frameGeneration, scope.walkEpoch) || !metricOutputAllowed(scope.arGeneration)) return
            recording.appendMetadata(entry)
            recording.enqueueMetadataIfOriginalUploadAllowed(entry)
        }
        val device = risk.deviceGate(input.feedbackGateOutput,
            if (input.independentBatches.isNotEmpty()) null else entry.staleReason,
            scope.arGeneration, input.qualitativeGateOutput)
        val reportGate = risk.deviceGate(input.automaticReportOutput, entry.staleReason, scope.arGeneration)
        val explicitGate = risk.deviceGate(input.reportOutput, entry.staleReason, scope.arGeneration)
        val location = recording.freshLocation()
        val locationAge = location?.let { (clock.elapsedRealtimeMs() - it.elapsedRealtimeMs).coerceAtLeast(0L) }
        synchronized(frameStateLock) {
            if (!runtimeOwnsFrame(scope.frameGeneration, scope.walkEpoch) || !metricOutputAllowed(scope.arGeneration)) return
            recording.frameProduced()
            recording.appendTelemetry(entry, device.alertsAllowed, reportGate.reportCandidatesAllowed, location, locationAge)
        }
        if (!reports.publish(FrameReportPublication(scope.frameGeneration, scope.arGeneration,
                input.automaticReportOutput, reportGate, input.reportOutput, explicitGate,
                AndroidRiskSelectionPolicy.hasReportableDamageWithoutReportDepth(input.frozenOutputs),
                AndroidRiskSelectionPolicy.hasConflictingReportDamage(input.frozenOutputs),
                input.detectionSnapshot, input.wallClockMs, scope.walkEpoch,
                input.frozenPrepared?.let { input.detectionSnapshot to it.frame }))) return
        if (!metricOutputAllowed(scope.arGeneration)) return
        val producer = FeedbackRejectionProducerScope(scope.walkEpoch, scope.frameGeneration, scope.arGeneration)
        tactile.consumeCurrent(input.receipt) {
            risk.rememberFrame(input.frame, entry.staleReason != null, false, input.sourceIsCurrent,
                { _ -> risk.currentAlertsAllowed() }, input.receipt, warningContext(input),
                delivery = { dispatch, current -> risk.emitFeedback(dispatch, producer, current, input.sourceIsCurrent) })
        }
        tactile.dispatchFeedback(input.receipt, input.frame, entry.staleReason != null, device.alertsAllowed,
            clock.elapsedRealtimeMs(), TactileFrameFeedbackActuator { dispatch ->
                risk.emitFeedback(dispatch, producer, input.sourceIsCurrent, input.sourceIsCurrent)
            }, risk.freshIndependentOutputs(clock.elapsedRealtimeMs()), warningContext(input))
        tactile.dotReference(input.dotOwner, scope.lease, input.sourceIsCurrent)
        presentation().showStatus(FrameStatusPresentationInput(scope, input.bestOutput, input.depthSnapshot,
            input.detectionSnapshot, input.timestampMs, input.wallClockMs,
            input.grayFrame?.key?.capturedAtElapsedRealtimeMs, { risk.gateText(device) })) {
            val status = "depthAcquisitionFailures=${input.provider.runtimeAcquisitionDiagnostics()}"
            recording.acquisitionFailures(input.provider, scope.elapsedMs)
            status
        }
    }

    private fun publishQualitative(input: FramePublicationPacket<L, R>, frameTimestampNs: () -> Long) {
        val scope = input.scope
        val now = clock.elapsedRealtimeMs()
        val outputs = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(input.frame.guidance.outputs, now)
            .filter { it.isCurrentQualitativeCaution(now) }
        val visualReady = visualOutputAllowed(scope.lease, frameTimestampNs(), scope.tracking)
        val gate = risk.deviceGate(null, null, scope.arGeneration, outputs.firstOrNull(), visualReady)
        val producer = FeedbackRejectionProducerScope(scope.walkEpoch, scope.frameGeneration, scope.arGeneration)
        tactile.consumeCurrent(input.receipt) {
            risk.rememberFrame(input.frame.copy(guidance = input.frame.guidance.copy(outputs = outputs)), !visualReady, true, input.sourceIsCurrent,
                risk::qualitativeAlertsAllowed, input.receipt, null,
                delivery = { dispatch, current -> risk.emitFeedback(dispatch, producer, current, input.sourceIsCurrent) })
        }
        tactile.dispatchFeedback(input.receipt, input.frame.copy(guidance = input.frame.guidance.copy(outputs = outputs)), !visualReady, gate.alertsAllowed, now,
            TactileFrameFeedbackActuator { dispatch ->
                risk.emitFeedback(dispatch, producer, input.sourceIsCurrent, input.sourceIsCurrent)
            }, emptyList(), null)
        presentation().showGap(FrameGapPresentationInput(scope,
            input.grayFrame?.key?.capturedAtElapsedRealtimeMs, input.overlayCount, input.guidanceOutputs))
    }

    private fun warningContext(input: FramePublicationPacket<L, R>) = input.grayFrame?.let { current ->
        WarningRegionContext(input.scope.walkEpoch,
            "${input.scope.arGeneration}:${current.key.geometryVersion}:${current.sourceWidth}x${current.sourceHeight}",
            input.scope.frameGeneration)
    }
}
