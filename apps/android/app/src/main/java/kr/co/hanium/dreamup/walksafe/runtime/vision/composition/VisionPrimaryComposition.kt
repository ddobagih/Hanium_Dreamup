package kr.co.hanium.dreamup.walksafe.runtime.vision.composition

import kr.co.hanium.dreamup.walksafe.device.AndroidRuntimeCalibrationSession
import kr.co.hanium.dreamup.walksafe.inference.DetectorRuntimeSupervisor
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Named actual Lazy holders; construction stores live effects without reading locks or SDK facts. */
internal class VisionPrimaryComposition(
    private val frameLock: () -> Any,
    private val elapsedRealtimeMs: () -> Long,
    private val wallTimeMs: () -> Long,
    private val watchdog: PrimaryNativeFlightScheduler,
    private val rejectBlocked: () -> Boolean,
    private val auxiliaryCopyInFlight: () -> Boolean,
    private val dueDrop: () -> Unit,
    private val nativeStall: (WalkRuntimeEpoch, Int) -> Unit,
    private val onMain: (() -> Unit) -> Unit,
    private val removeCalibrationSchedule: (Runnable) -> Unit,
    private val sourceAgeMs: Long,
) {
    val detector: Lazy<PrimaryDetectorExecutionOwner> = lazy {
        PrimaryDetectorExecutionOwner(stateLock = frameLock(), elapsedRealtimeMs = elapsedRealtimeMs,
            nativeScheduler = watchdog, rejectBlockedWork = rejectBlocked,
            auxiliaryCopyInFlight = auxiliaryCopyInFlight, onDueButInFlightDrop = dueDrop,
            onNativeStall = nativeStall, dispatchOnMain = onMain, sourceAgeMs = sourceAgeMs)
    }
    val calibration: Lazy<PrimaryRuntimeCalibrationOwner<AndroidRuntimeCalibrationSession>> = lazy {
        PrimaryRuntimeCalibrationOwner(detector.value, elapsedRealtimeMs,
            { session, reason -> session.cancel(reason) }, removeCalibrationSchedule)
    }
    val scheduling: Lazy<PrimaryDetectorFrameSchedulingOwner> = lazy {
        PrimaryDetectorFrameSchedulingOwner(frameLock(), detector.value)
    }
    val invocation: Lazy<PrimaryDetectorInvocationWorker> = lazy {
        PrimaryDetectorInvocationWorker(detector.value, elapsedRealtimeMs, wallTimeMs)
    }

    /** Main calls once at its original eager supervisor declaration; no native holder is forced here. */
    fun createSupervisor(admission: (Int, WalkRuntimeEpoch) -> Boolean) = DetectorRuntimeSupervisor(
        ownsCurrentWork = { claim ->
            val epoch = claim.walkRun as? WalkRuntimeEpoch
            epoch != null && admission(claim.detectorGeneration, epoch) && detector.value.ownsWork(claim.ticket)
        },
    )
}
