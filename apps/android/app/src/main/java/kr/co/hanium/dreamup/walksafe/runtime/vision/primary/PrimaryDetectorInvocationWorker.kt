package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import kr.co.hanium.dreamup.walksafe.device.ApprovedCameraFrameQualityProfile
import kr.co.hanium.dreamup.walksafe.device.CameraDetectorAdmission
import kr.co.hanium.dreamup.walksafe.device.CameraDetectorAdmissionPolicy
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionStatus
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorTiming
import kr.co.hanium.dreamup.walksafe.inference.InferencePacingTicket
import kr.co.hanium.dreamup.walksafe.inference.NativeDetectorFlight
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Immutable request facts, produced at the original AR/CameraX admission stage. No live Frame/Image. */
internal data class PrimaryInvocationRequest(
    val epoch: WalkRuntimeEpoch,
    val frameGeneration: Int,
    val pacingTicket: InferencePacingTicket,
    val capturedAtElapsedRealtimeMs: Long,
    val admissionAtElapsedRealtimeMs: Long,
    val detectorStartedAtElapsedRealtimeMs: Long,
    val timestampMs: Long,
    val quarterTurns: Int,
    val quality: CameraFrameQualityObservation,
    val approvedProfile: ApprovedCameraFrameQualityProfile?,
    val startedAtWallTimeMs: Long? = null,
)

internal data class PrimaryPartialDetection(
    val result: AndroidDetectionResult,
    val flight: NativeDetectorFlight,
    val observedAtElapsedRealtimeMs: Long,
    val inferenceLatencyMs: Long,
)

internal data class PrimaryNativeOutput(
    val result: AndroidDetectionResult,
    val completedAtElapsedRealtimeMs: Long,
)

/** Last returned call only, for observation. It retains neither input pixels nor detections.
 * A timeout can precede publication, so readers must match the exact pacing ticket. */
internal data class PrimaryNativeTimingSnapshot(
    val pacingTicket: InferencePacingTicket,
    val epoch: WalkRuntimeEpoch,
    val frameGeneration: Int,
    val capturedAtElapsedRealtimeMs: Long,
    val nativeStartedAtElapsedRealtimeMs: Long,
    val nativeReturnedAtElapsedRealtimeMs: Long,
    val status: AndroidDetectionStatus,
    val timing: AndroidDetectorTiming,
)

internal data class PrimaryInvocationObservation(
    val result: AndroidDetectionResult,
    val admission: CameraDetectorAdmission?,
    val detectorInvoked: Boolean,
    val inferenceDurationMs: Long?,
    val detectorSucceeded: Boolean,
    val detectorOutputExpired: Boolean,
    val detectorStartedAtElapsedRealtimeMs: Long,
    val completedAtWallTimeMs: Long?,
)

/** One physical native call owns its progress even if a failure callback itself throws. */
internal class PrimaryDetectorInvocation internal constructor(val request: PrimaryInvocationRequest) {
    internal var result = AndroidDetectionResult.unavailable()
    internal var admission: CameraDetectorAdmission? = null
    internal var detectorInvoked = false
    internal var inferenceDurationMs: Long? = null
    internal var detectorSucceeded = false
    internal var detectorOutputExpired = false
    internal var detectorStartedAtElapsedRealtimeMs = request.detectorStartedAtElapsedRealtimeMs
    internal var completedAtWallTimeMs = request.startedAtWallTimeMs

    /** Caller chooses the original post-admission stage; expired outputs keep the measured duration. */
    fun preferReportedInferenceDuration() { inferenceDurationMs = result.timing.totalMs ?: inferenceDurationMs }

    fun observe() = PrimaryInvocationObservation(result, admission, detectorInvoked, inferenceDurationMs,
        detectorSucceeded, detectorOutputExpired, detectorStartedAtElapsedRealtimeMs, completedAtWallTimeMs)
}

/** Shared AR/CameraX invocation body; clocks, native flight and exception/finally order are unchanged. */
internal class PrimaryDetectorInvocationWorker(
    private val detectorOwner: PrimaryDetectorExecutionOwner,
    private val elapsedRealtimeMs: () -> Long,
    private val wallTimeMs: () -> Long,
) {
    @Volatile private var latestNativeTiming: PrimaryNativeTimingSnapshot? = null

    fun prepare(request: PrimaryInvocationRequest) = PrimaryDetectorInvocation(request)

    fun invoke(
        invocation: PrimaryDetectorInvocation,
        copyOwnedFrame: () -> OwnedYuvFrame?,
        onPartial: ((PrimaryPartialDetection) -> Unit)?,
        onNativeOutput: (PrimaryNativeOutput) -> Boolean,
        onRuntimeFailure: (RuntimeException) -> Unit,
    ) {
        val request = invocation.request
        invocation.admission = CameraDetectorAdmissionPolicy.admit(
            currentEpoch = request.epoch,
            nowElapsedRealtimeMs = request.admissionAtElapsedRealtimeMs,
            observation = request.quality,
            approvedProfile = request.approvedProfile,
        ) {
            invocation.detectorStartedAtElapsedRealtimeMs = elapsedRealtimeMs()
            invocation.result = try {
                val ownedFrame = copyOwnedFrame() ?: return@admit
                val flight = detectorOwner.beginNativeFlight(request.epoch, request.frameGeneration) ?: return@admit
                try {
                    invocation.detectorInvoked = true
                    invocation.detectorStartedAtElapsedRealtimeMs = elapsedRealtimeMs()
                    val result = if (onPartial == null) {
                        detectorOwner.detectOriented(ownedFrame, request.timestampMs, request.quarterTurns)
                    } else {
                        detectorOwner.detectOriented(ownedFrame, request.timestampMs, request.quarterTurns) { partial ->
                            val observedAt = elapsedRealtimeMs()
                            val latency = partial.timing.totalMs ?: (observedAt - invocation.detectorStartedAtElapsedRealtimeMs)
                            onPartial(PrimaryPartialDetection(partial, flight, observedAt, latency))
                        }
                    }
                    result.also {
                        val returnedAt = elapsedRealtimeMs()
                        latestNativeTiming = PrimaryNativeTimingSnapshot(
                            request.pacingTicket, request.epoch, request.frameGeneration,
                            request.capturedAtElapsedRealtimeMs, invocation.detectorStartedAtElapsedRealtimeMs,
                            returnedAt, it.status, it.timing.copy(
                                completedModels = java.util.Collections.unmodifiableList(ArrayList(it.timing.completedModels)),
                                skippedModels = java.util.Collections.unmodifiableList(ArrayList(it.timing.skippedModels)),
                            ),
                        )
                        invocation.detectorOutputExpired = onNativeOutput(PrimaryNativeOutput(it, returnedAt))
                        if (it.inferenceCompleted) invocation.detectorSucceeded = true
                    }
                } finally {
                    detectorOwner.completeNativeFlight(flight)
                }
            } catch (failure: RuntimeException) {
                onRuntimeFailure(failure)
                AndroidDetectionResult.failed()
            } finally {
                if (request.startedAtWallTimeMs != null) invocation.completedAtWallTimeMs = wallTimeMs()
                invocation.inferenceDurationMs = elapsedRealtimeMs() - invocation.detectorStartedAtElapsedRealtimeMs
            }
        }
    }
}

internal data class PrimaryCaptureAdmission(val ticket: InferencePacingTicket, val captureRequested: Boolean)

/** Actual three capture watermarks, shared by scheduling guards rather than copied into Main. */
internal class PrimaryDetectorFrameSchedulingOwner(
    private val stateLock: Any,
    private val detectorOwner: PrimaryDetectorExecutionOwner,
) {
    private var lastCaptureFrameId = 0L
    private var lastCaptureGeneration = -1
    private var lastCaptureArGeneration = -1L

    fun admit(frameId: () -> Long, frameGeneration: Int, arGeneration: Long, nowMs: Long,
        currentFrame: () -> Boolean, consumeCaptureRequest: () -> Boolean): PrimaryCaptureAdmission? = synchronized(stateLock) {
        if (!currentFrame()) return@synchronized null
        if (!detectorOwner.projection().available) return@synchronized null
        if (frameId() <= 0L) return@synchronized null
        if (lastCaptureGeneration == frameGeneration && lastCaptureArGeneration == arGeneration &&
            frameId() <= lastCaptureFrameId) return@synchronized null
        val ticket = detectorOwner.tryStartWork(nowMs) ?: return@synchronized null
        PrimaryCaptureAdmission(ticket, consumeCaptureRequest())
    }

    fun commitGeometryIfCurrent(frameId: () -> Long, frameGeneration: Int, arGeneration: Long, expectedGeometry: Long,
        currentFrame: () -> Boolean, currentGeometry: () -> Long): Long? = synchronized(stateLock) {
        if (!currentFrame()) return@synchronized null
        lastCaptureFrameId = frameId()
        lastCaptureGeneration = frameGeneration
        lastCaptureArGeneration = arGeneration
        // Original order commits the capture watermark before rejecting a rotated input.
        if (currentGeometry() != expectedGeometry) return@synchronized null
        currentGeometry()
    }
}
