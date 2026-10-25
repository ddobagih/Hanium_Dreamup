package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import kr.co.hanium.dreamup.walksafe.inference.NativeDetectorFlight
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameSynthesisController
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

internal fun interface PrimaryPublicationAuthority {
    /** Exactly the original epoch-current && camera-environment branch; not a stronger work gate. */
    fun epochAllowsPublication(epoch: WalkRuntimeEpoch): Boolean
}

/** Native/gen/geometry gates plus the same actual frame peer's snapshot/source transaction. */
internal class PrimaryDetectionPublisher(
    private val frameStateLock: Any,
    private val detectorOwner: PrimaryDetectorExecutionOwner,
    private val frameOwner: FrameSynthesisController,
    private val authority: PrimaryPublicationAuthority,
) : PrimarySnapshotPublisher {
    override fun publish(event: PrimarySnapshotPublication): DetectionSnapshot? {
        var stalledFlight: NativeDetectorFlight? = null
        val published = synchronized(frameStateLock) {
            if (event.generation != detectorOwner.projection().frameGeneration || detectorOwner.nativeWorkBlocked() ||
                (event.frameEvidence.geometryVersion != null && event.frameEvidence.geometryVersion != frameOwner.geometryVersion) ||
                (event.expectedEpoch != null && !authority.epochAllowsPublication(event.expectedEpoch))) return@synchronized null
            val snapshot = DetectionSnapshot.published(event.result, event.identity, event.capturedAtWallTimeMs,
                event.startedAtWallTimeMs, event.completedAtWallTimeMs, event.imageWidth, event.imageHeight,
                event.reportImage, event.frameEvidence, event.capturedAtElapsedRealtimeMs,
                event.reportCaptureFacts?.takeIf { it.walkEpoch == event.expectedEpoch })
            fun install() = frameOwner.publishSourceSnapshot(snapshot, event.frameEvidence.visualFrameKey,
                event.visualSourceLease)
            val flight = event.nativeFlight
            if (flight != null) {
                var installed = false
                if (!detectorOwner.publishNativeIfCurrent(flight) { installed = install() } || !installed) {
                    if (detectorOwner.nativeWorkBlocked()) stalledFlight = flight
                    return@synchronized null
                }
            } else if (!install()) return@synchronized null
            snapshot
        }
        stalledFlight?.let(detectorOwner::requestNativeStall)
        return published
    }
}
