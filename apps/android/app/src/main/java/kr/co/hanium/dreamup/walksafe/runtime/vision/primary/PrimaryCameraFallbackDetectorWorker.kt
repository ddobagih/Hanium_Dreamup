package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.device.AndroidLocalTactileTier
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.feedback.AndroidNonMetricObstacleAdvisoryPolicy
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricAdvisoryGate
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricObstacleAdvisoryAction
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.DetectorRuntimeFailureAction
import kr.co.hanium.dreamup.walksafe.inference.DetectorRuntimeSupervisor
import kr.co.hanium.dreamup.walksafe.inference.DetectorWorkClaim
import kr.co.hanium.dreamup.walksafe.inference.DetectorValidOutputExpired
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket
import kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackFrameCapture
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal interface PrimaryFallbackWorkAuthority<R> {
    fun obstacleFeatureEnabled(): Boolean
    fun captureIsCurrent(epoch: WalkRuntimeEpoch, fallbackGeneration: Int): Boolean
    fun captureDeliveryOwner(): R
    fun cameraOutputsAllowed(): Boolean
    fun completionIsCurrent(epoch: WalkRuntimeEpoch, fallbackGeneration: Int,
        deliveryOwner: R, detectorGeneration: Int): Boolean
    fun advisoryGate(nowElapsedRealtimeMs: Long): NonMetricAdvisoryGate
    fun tactileTier(gate: NonMetricAdvisoryGate): AndroidLocalTactileTier
}

internal interface PrimaryFallbackFrameObservation : PrimaryCameraWorkQuality {
    fun load(timestampNs: Long, observedAtElapsedRealtimeMs: Long)
    fun luma(epoch: WalkRuntimeEpoch, observedAtElapsedRealtimeMs: Long,
        width: Int, height: Int, bytes: ByteBuffer?, rowStride: Int?, pixelStride: Int?): CameraFrameQualityObservation?
}

internal interface PrimaryFallbackRecording<R> {
    fun analyzed(epoch: WalkRuntimeEpoch, fallbackGeneration: Int, deliveryOwner: R)
    fun completed(event: PrimaryCompletedInference)
    fun sample(result: AndroidDetectionResult, tier: AndroidLocalTactileTier,
        gate: NonMetricAdvisoryGate, observedAtElapsedRealtimeMs: Long)
}

internal fun interface PrimaryFallbackPresentation<R> {
    fun advisory(action: NonMetricObstacleAdvisoryAction, epoch: WalkRuntimeEpoch,
        fallbackGeneration: Int, deliveryOwner: R)
}

/** Owns the actual CameraX analysis body, including all early returns and source close/finally. */
internal class PrimaryCameraFallbackDetectorWorker<R>(
    private val detectorOwner: PrimaryDetectorExecutionOwner,
    private val invocationWorker: PrimaryDetectorInvocationWorker,
    private val authority: PrimaryFallbackWorkAuthority<R>,
    private val observation: PrimaryFallbackFrameObservation,
    private val supervisor: DetectorRuntimeSupervisor,
    private val timing: PrimaryDetectorTimingEffects,
    private val recording: PrimaryFallbackRecording<R>,
    private val advisoryPolicy: AndroidNonMetricObstacleAdvisoryPolicy,
    private val completion: PrimaryWorkCompletion,
    private val presentation: PrimaryFallbackPresentation<R>,
    private val mainDispatcher: (() -> Unit) -> Unit,
    private val elapsedRealtimeMs: () -> Long,
) {
    fun analyze(frameCapture: CameraFallbackFrameCapture, expectedEpoch: WalkRuntimeEpoch,
        fallbackGeneration: Int) {
        var pacingTicket: InferencePacingTicket? = null
        var invocation: PrimaryDetectorInvocation? = null
        val timestampNs = frameCapture.imageTimestampNs
        val quarterTurns = frameCapture.imageQuarterTurns
        try {
            if (!authority.obstacleFeatureEnabled()) return
            val now = elapsedRealtimeMs()
            if (!authority.captureIsCurrent(expectedEpoch, fallbackGeneration)) return
            observation.load(frameCapture.imageTimestampNs, now)
            val ticket = detectorOwner.tryStartWork(now) ?: return
            pacingTicket = ticket
            val deliveryOwner = authority.captureDeliveryOwner()
            val detectorGeneration = detectorOwner.projection().frameGeneration
            if (!frameCapture.hasImage()) return
            val quality = frameCapture.inspectLuma { bytes, rowStride, pixelStride ->
                observation.luma(expectedEpoch, now, frameCapture.width, frameCapture.height,
                    bytes, rowStride, pixelStride)
            } ?: return
            if (!authority.cameraOutputsAllowed()) return
            val prepared = invocationWorker.prepare(PrimaryInvocationRequest(expectedEpoch, detectorGeneration,
                ticket, now, now, now, timestampNs / NANOS_PER_MILLISECOND, quarterTurns, quality,
                observation.approvedProfile()))
            invocation = prepared
            invocationWorker.invoke(prepared, { frameCapture.copyOwnedInputAndClose()?.cameraFrame }, null,
                onNativeOutput = { output ->
                    supervisor.onDetectorOutput(DetectorWorkClaim(detectorGeneration, expectedEpoch, ticket),
                        output.result, now, output.completedAtElapsedRealtimeMs)
                        .action == DetectorRuntimeFailureAction.SAFE_STOP
                }, onRuntimeFailure = { timing.failure(it, detectorGeneration, expectedEpoch) })
            val observed = prepared.observe()
            if (observed.admission?.detectorInvocationAllowed != true) return
            if (observed.detectorOutputExpired) {
                timing.failure(DetectorValidOutputExpired(), detectorGeneration, expectedEpoch)
                return
            }
            if (!observed.result.inferenceCompleted) return
            prepared.preferReportedInferenceDuration()
            if (observed.detectorSucceeded) {
                val at = elapsedRealtimeMs()
                if (!timing.observe(expectedEpoch, now, at,
                        observed.result.timing.totalMs ?: (at - observed.detectorStartedAtElapsedRealtimeMs))) return
            }
            if (!authority.completionIsCurrent(expectedEpoch, fallbackGeneration, deliveryOwner, detectorGeneration)) return
            if (observed.detectorSucceeded) {
                recording.analyzed(expectedEpoch, fallbackGeneration, deliveryOwner)
                recording.completed(PrimaryCompletedInference(expectedEpoch, observed.result,
                    observed.result.timing.totalMs ?: (elapsedRealtimeMs() - observed.detectorStartedAtElapsedRealtimeMs).coerceAtLeast(0L)))
            }
            val gate = authority.advisoryGate(elapsedRealtimeMs())
            val tier = authority.tactileTier(gate)
            if (observed.detectorSucceeded) recording.sample(observed.result, tier, gate, now)
            val action = advisoryPolicy.evaluate(observed.result.detections, gate, timestampNs, now)
            if (!authority.completionIsCurrent(expectedEpoch, fallbackGeneration, deliveryOwner, detectorGeneration)) {
                advisoryPolicy.reset()
                return
            }
            if (tier == AndroidLocalTactileTier.CAMERA_IMU_NON_METRIC && action != null) mainDispatcher {
                presentation.advisory(action, expectedEpoch, fallbackGeneration, deliveryOwner)
            }
        } finally {
            try { frameCapture.close() } finally {
                pacingTicket?.let { ticket ->
                    val observed = invocation?.observe()
                    completion.finish(ticket, observed?.detectorInvoked == true,
                        observed?.inferenceDurationMs, observed?.detectorSucceeded == true)
                }
            }
        }
    }

    private companion object { const val NANOS_PER_MILLISECOND = 1_000_000L }
}
