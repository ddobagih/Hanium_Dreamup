package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.device.ApprovedCameraFrameQualityProfile
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.DetectorRuntimeFailureAction
import kr.co.hanium.dreamup.walksafe.inference.DetectorRuntimeSupervisor
import kr.co.hanium.dreamup.walksafe.inference.DetectorWorkClaim
import kr.co.hanium.dreamup.walksafe.inference.DetectorValidOutputExpired
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket
import kr.co.hanium.dreamup.walksafe.inference.NativeDetectorFlight
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.inference.tracking.InterFrameDetectionTracker
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRecognitionAdmission
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRecognitionInput
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ReportFrameCaptureFacts
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal data class PrimaryArDetectionWork<L>(
    val input: FrameRecognitionInput,
    val lease: L,
    val generation: Int,
    val epoch: WalkRuntimeEpoch,
    val ticket: InferencePacingTicket,
    val captureRequested: Boolean,
    val timestampMs: Long,
    val capturedAtWallTimeMs: Long,
    val capturedAtElapsedRealtimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageQuarterTurns: Int,
    val identity: AndroidDetectionSnapshotFrameIdentity,
    val frameEvidence: DetectionFrameEvidence,
    val depthSnapshot: DepthFrameSnapshot,
    val visualSourceLease: InterFrameDetectionTracker.PendingSourceLease?,
) {
    /** Preserve the original facts; a foreign capture never receives replacement metadata. */
    val reportCaptureFacts: ReportFrameCaptureFacts?
        get() = input.reportCaptureFacts?.takeIf {
            it.capturedAtElapsedRealtimeMs == capturedAtElapsedRealtimeMs && it.walkEpoch == epoch
        }
}

/** Frozen report facts grant no authority; publications hold no live SDK Frame/Image. */
internal data class PrimarySnapshotPublication(
    val result: AndroidDetectionResult,
    val generation: Int,
    val identity: AndroidDetectionSnapshotFrameIdentity,
    val capturedAtWallTimeMs: Long,
    val startedAtWallTimeMs: Long,
    val completedAtWallTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val reportImage: ByteArray?,
    val frameEvidence: DetectionFrameEvidence,
    val expectedEpoch: WalkRuntimeEpoch?,
    val capturedAtElapsedRealtimeMs: Long?,
    val nativeFlight: NativeDetectorFlight? = null,
    val visualSourceLease: InterFrameDetectionTracker.PendingSourceLease? = null,
    val reportCaptureFacts: ReportFrameCaptureFacts? = null,
)

internal fun interface PrimarySnapshotPublisher {
    fun publish(event: PrimarySnapshotPublication): DetectionSnapshot?
}

internal interface PrimaryArWorkAuthority<L> {
    fun frameIsCurrent(generation: Int, epoch: WalkRuntimeEpoch): Boolean
    fun visualLeaseAllowsWork(lease: L): Boolean
}

internal interface PrimaryCameraWorkQuality {
    fun observe(observation: CameraFrameQualityObservation)
    fun approvedProfile(): ApprovedCameraFrameQualityProfile?
}

internal interface PrimaryCaptureImages {
    fun rawCollectionAllowed(): Boolean
    fun reportCollectionGranted(): Boolean
    fun sensitiveDebugTransferAllowed(): Boolean
    fun encodeReport(frame: OwnedYuvFrame): ByteArray?
    fun encodeDebug(frame: OwnedYuvFrame): ByteArray?
}

internal interface PrimaryDetectorTimingEffects {
    fun observe(epoch: WalkRuntimeEpoch, capturedAtElapsedRealtimeMs: Long,
        observedAtElapsedRealtimeMs: Long, inferenceLatencyMs: Long): Boolean
    fun failure(error: RuntimeException, generation: Int, epoch: WalkRuntimeEpoch)
}

internal data class PrimaryCompletedInference(
    val epoch: WalkRuntimeEpoch,
    val result: AndroidDetectionResult,
    val inferenceDurationMs: Long,
)

internal data class PrimaryOriginalCapture(
    val image: ByteArray,
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val durationMs: Long,
    val result: AndroidDetectionResult,
    val forcedCustom: Boolean,
)

internal interface PrimaryInferenceRecording {
    fun namedCapture(input: FrameRecognitionInput, published: DetectionSnapshot, result: AndroidDetectionResult)
    fun completed(event: PrimaryCompletedInference)
    /** The existing raw permission callback, uploader and metadata builder run only at this stage. */
    fun originalCapture(event: PrimaryOriginalCapture)
}

internal fun interface PrimaryCapturePresentation {
    fun updateButton()
}

internal fun interface PrimaryWorkCompletion {
    fun finish(ticket: InferencePacingTicket, invoked: Boolean, inferenceDurationMs: Long?, validCompletion: Boolean)
}

internal fun interface PrimaryVisualSourceRelease {
    fun release(lease: InterFrameDetectionTracker.PendingSourceLease)
}

/** Owns the complete off-lane AR task; effects receive typed events, never a MainActivity host. */
internal class PrimaryArDetectorWorker<L>(
    private val frameStateLock: Any,
    private val detectorOwner: PrimaryDetectorExecutionOwner,
    private val invocationWorker: PrimaryDetectorInvocationWorker,
    private val authority: PrimaryArWorkAuthority<L>,
    private val quality: PrimaryCameraWorkQuality,
    private val images: PrimaryCaptureImages,
    private val supervisor: DetectorRuntimeSupervisor,
    private val timing: PrimaryDetectorTimingEffects,
    private val publisher: PrimarySnapshotPublisher,
    private val recording: PrimaryInferenceRecording,
    private val sourceRelease: PrimaryVisualSourceRelease,
    private val completion: PrimaryWorkCompletion,
    private val presentation: PrimaryCapturePresentation,
    private val mainDispatcher: (() -> Unit) -> Unit,
    private val elapsedRealtimeMs: () -> Long,
    private val wallTimeMs: () -> Long,
    private val consumeOwnedInput: (FrameRecognitionInput) -> OwnedYuvFrame,
) {
    fun submit(work: PrimaryArDetectionWork<L>) {
        var submitted = false
        try {
            detectorOwner.nativeLane.execute { run(work) }
            submitted = true
        } catch (_: RejectedExecutionException) {
            Unit
        } finally {
            if (!submitted) cancelUnsubmitted(work.ticket, work.visualSourceLease)
        }
    }

    fun cancelUnsubmitted(ticket: InferencePacingTicket, sourceLease: InterFrameDetectionTracker.PendingSourceLease?) {
        releaseSource(sourceLease)
        completion.finish(ticket, false, null, false)
    }

    private fun releaseSource(lease: InterFrameDetectionTracker.PendingSourceLease?) {
        lease?.let { synchronized(frameStateLock) { sourceRelease.release(it) } }
    }

    private fun run(work: PrimaryArDetectionWork<L>) {
        var invocation: PrimaryDetectorInvocation? = null
        try {
            if (!authority.frameIsCurrent(work.generation, work.epoch)) return
            quality.observe(work.input.qualityObservation)
            if (!FrameRecognitionAdmission.allowsVisualWork(work.input.cameraTimestampNs,
                    work.frameEvidence.frameId, work.depthSnapshot) { authority.visualLeaseAllowsWork(work.lease) }) return
            val rawAllowed = images.rawCollectionAllowed()
            val reportImage = if (images.reportCollectionGranted()) images.encodeReport(work.input.cameraFrame) else null
            val captureImage = if (work.captureRequested && rawAllowed && images.sensitiveDebugTransferAllowed()) {
                images.encodeDebug(work.input.cameraFrame) ?: reportImage
            } else null
            val startedAt = wallTimeMs()
            val initialDetectorAt = elapsedRealtimeMs()
            val admittedAt = elapsedRealtimeMs()
            val prepared = invocationWorker.prepare(PrimaryInvocationRequest(work.epoch, work.generation,
                work.ticket, work.capturedAtElapsedRealtimeMs, admittedAt, initialDetectorAt,
                work.timestampMs, work.imageQuarterTurns, work.input.qualityObservation,
                quality.approvedProfile(), startedAt))
            invocation = prepared
            invocationWorker.invoke(prepared, { consumeOwnedInput(work.input) },
                onPartial = { partial ->
                    if (timing.observe(work.epoch, work.capturedAtElapsedRealtimeMs,
                            partial.observedAtElapsedRealtimeMs, partial.inferenceLatencyMs)) {
                        publisher.publish(publication(work, partial.result, startedAt, wallTimeMs(), reportImage,
                            flight = partial.flight))
                    }
                },
                onNativeOutput = { output ->
                    supervisor.onDetectorOutput(DetectorWorkClaim(work.generation, work.epoch, work.ticket),
                        output.result, work.capturedAtElapsedRealtimeMs, output.completedAtElapsedRealtimeMs)
                        .action == DetectorRuntimeFailureAction.SAFE_STOP
                }, onRuntimeFailure = { timing.failure(it, work.generation, work.epoch) })
            val observed = prepared.observe()
            if (observed.admission?.detectorInvocationAllowed != true) {
                wallTimeMs() // Original rejected-admission completion wall observation, before task cancellation.
                return
            }
            if (observed.detectorOutputExpired) {
                timing.failure(DetectorValidOutputExpired(), work.generation, work.epoch)
                return
            }
            prepared.preferReportedInferenceDuration()
            if (observed.detectorSucceeded) {
                val at = elapsedRealtimeMs()
                if (!timing.observe(work.epoch, work.capturedAtElapsedRealtimeMs, at,
                        observed.result.timing.totalMs ?: (at - observed.detectorStartedAtElapsedRealtimeMs))) return
            }
            if (!observed.result.inferenceCompleted) return
            val completedAt = requireNotNull(observed.completedAtWallTimeMs)
            val published = publisher.publish(publication(work, observed.result, startedAt, completedAt,
                reportImage, sourceLease = work.visualSourceLease))
            if (published != null) {
                recording.namedCapture(work.input, published, observed.result)
                recording.completed(PrimaryCompletedInference(work.epoch, observed.result, observed.result.timing.totalMs ?: (completedAt - startedAt)))
                synchronized(frameStateLock) {
                    if (!authority.frameIsCurrent(work.generation, work.epoch)) return@synchronized
                    if (captureImage != null) recording.originalCapture(PrimaryOriginalCapture(captureImage,
                        work.timestampMs, work.imageWidth, work.imageHeight, completedAt - startedAt,
                        observed.result, work.captureRequested))
                }
                if (work.captureRequested) mainDispatcher {
                    if (authority.frameIsCurrent(work.generation, work.epoch)) presentation.updateButton()
                }
            }
        } finally {
            releaseSource(work.visualSourceLease)
            val observed = invocation?.observe()
            completion.finish(work.ticket, observed?.detectorInvoked == true, observed?.inferenceDurationMs,
                observed?.detectorSucceeded == true && authority.frameIsCurrent(work.generation, work.epoch))
        }
    }

    private fun publication(work: PrimaryArDetectionWork<L>, result: AndroidDetectionResult,
        startedAt: Long, completedAt: Long, reportImage: ByteArray?, flight: NativeDetectorFlight? = null,
        sourceLease: InterFrameDetectionTracker.PendingSourceLease? = null) = PrimarySnapshotPublication(result,
        work.generation, work.identity, work.capturedAtWallTimeMs, startedAt, completedAt,
        work.imageWidth, work.imageHeight, reportImage, work.frameEvidence, work.epoch,
        work.capturedAtElapsedRealtimeMs, flight, sourceLease, work.reportCaptureFacts)
}
