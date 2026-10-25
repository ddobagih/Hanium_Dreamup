package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import com.google.ar.core.Frame
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket
import kr.co.hanium.dreamup.walksafe.inference.tracking.InterFrameDetectionTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ArFrameGeometryFreezer
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRecognitionAdmission
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRecognitionInput
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRouteEvidencePeer
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameSynthesisController
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal interface PrimaryArSubmissionAuthority {
    fun obstacleFeatureEnabled(): Boolean
    fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch): Boolean
    fun finishUnsubmitted(ticket: InferencePacingTicket)
}

/** Synchronously freezes the borrowed GL frame and transfers the existing ticket/source to its worker. */
internal class AndroidPrimaryArSubmissionBridge<L>(
    private val frameStateLock: Any,
    private val frameCaptureRequested: AtomicBoolean,
    private val scheduling: () -> PrimaryDetectorFrameSchedulingOwner,
    private val synthesis: () -> FrameSynthesisController,
    private val geometry: () -> ArFrameGeometryFreezer,
    private val route: FrameRouteEvidencePeer,
    private val worker: () -> PrimaryArDetectorWorker<L>,
    private val authority: PrimaryArSubmissionAuthority,
) {
    /**
     * Runs inference off the GL thread. Published snapshots retain both capture and completion
     * timestamps so a fast partial result cannot be mistaken for a fresh current-frame result.
     */
    fun schedule(
        recognitionInput: FrameRecognitionInput?,
        arLease: L,
        frame: Frame,
        timestampMs: Long,
        capturedAtMs: Long,
        elapsedRealtimeMs: Long,
        capturedDepthSnapshot: DepthFrameSnapshot,
        frameGeneration: Int,
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedArSessionGeneration: Long,
        visualFrameKey: VisualFrameKey? = null,
    ) {
        if (!authority.obstacleFeatureEnabled()) return
        val input = recognitionInput ?: return
        if (!FrameRecognitionAdmission.matchesDepth(input.cameraTimestampNs, frame.timestamp, capturedDepthSnapshot)) return
        val captureAdmission = scheduling().admit(
            frameId = { frame.timestamp }, frameGeneration = frameGeneration,
            arGeneration = expectedArSessionGeneration, nowMs = elapsedRealtimeMs,
            currentFrame = { authority.ownsFrame(frameGeneration, expectedWalkEpoch) },
            consumeCaptureRequest = { frameCaptureRequested.getAndSet(false) },
        ) ?: return
        val pacingTicket = captureAdmission.ticket
        val shouldCaptureFrame = captureAdmission.captureRequested
        var submittedToWorker = false
        var reservedVisualSource: InterFrameDetectionTracker.PendingSourceLease? = null
        try {
            val requestGeometryVersion = scheduling().commitGeometryIfCurrent(
                frameId = { frame.timestamp }, frameGeneration = frameGeneration,
                arGeneration = expectedArSessionGeneration, expectedGeometry = input.geometryVersion,
                currentFrame = { authority.ownsFrame(frameGeneration, expectedWalkEpoch) },
                currentGeometry = { synthesis().geometryVersion },
            ) ?: return
            val generation = frameGeneration
            val imageWidth = input.cameraFrame.width
            val imageHeight = input.cameraFrame.height
            val imageQuarterTurns = input.imageQuarterTurns
            val detectionIdentity = AndroidDetectionSnapshotFrameIdentity(
                captureFrameId = frame.timestamp,
                frameTimestampMs = timestampMs,
            )
            val frameEvidence = DetectionFrameEvidence(
                frameId = frame.timestamp,
                frameTimestampMs = timestampMs,
                depthSnapshot = capturedDepthSnapshot,
                depthMapper = geometry().createFrozenDepthMapper(
                    frame = frame,
                    frameId = frame.timestamp,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    snapshot = capturedDepthSnapshot,
                ),
                tactileContext = route.projectionContext(frame, elapsedRealtimeMs),
                motionContext = route.motionContext(elapsedRealtimeMs),
                imageQuarterTurns = imageQuarterTurns,
                navigationActive = route.navigationActive(),
                tmapOnRoute = route.tmapOnRoute(),
                visualFrameKey = visualFrameKey?.takeIf {
                    it.frameId == frame.timestamp && it.cameraTimestampNs == input.cameraTimestampNs &&
                        it.cameraTimestampNs == capturedDepthSnapshot.cameraImageTimestampNs
                },
                geometryVersion = requestGeometryVersion,
            )
            val visualSourceLease = synchronized(frameStateLock) {
                if (!authority.ownsFrame(generation, expectedWalkEpoch) ||
                    requestGeometryVersion != synthesis().geometryVersion
                ) return
                frameEvidence.visualFrameKey?.let { key ->
                    synthesis().reservePendingSourceFrame(key) ?: return
                }
            }
            reservedVisualSource = visualSourceLease
            val queuedWork = PrimaryArDetectionWork(input, arLease, generation, expectedWalkEpoch,
                pacingTicket, shouldCaptureFrame, timestampMs, capturedAtMs, elapsedRealtimeMs,
                imageWidth, imageHeight, imageQuarterTurns, detectionIdentity, frameEvidence,
                capturedDepthSnapshot, visualSourceLease)
            val targetWorker = worker()
            // The worker owns enqueue failure and task-finally after this exact handoff.
            submittedToWorker = true
            targetWorker.submit(queuedWork)
        } catch (_: RejectedExecutionException) {
            Unit
        } finally {
            if (!submittedToWorker) {
                reservedVisualSource?.let { lease ->
                    synchronized(frameStateLock) {
                        synthesis().releasePendingSourceFrame(lease)
                    }
                }
                authority.finishUnsubmitted(pacingTicket)
            }
        }
    }
}
