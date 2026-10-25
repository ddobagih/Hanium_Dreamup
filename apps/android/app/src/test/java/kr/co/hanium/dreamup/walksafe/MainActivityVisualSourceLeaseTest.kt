package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import java.io.File
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.GrayTrackingFrame
import kr.co.hanium.dreamup.walksafe.inference.tracking.InterFrameDetectionTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import org.junit.Assert.*
import org.junit.Test

/** Calls the actual Main publication method; camera/GL scheduling is checked separately below. */
class MainActivityVisualSourceLeaseTest {
    @Test
    fun completedVisualPublicationRequiresAndTransfersTheMatchingPendingLease() {
        val activity = MainActivity()
        val tracker = tracker(activity)
        val key = offer(tracker, 1L)
        val before = latest(activity)
        assertNull(publish(activity, key, null))
        assertSame(before, latest(activity))
        val lease = requireNotNull(tracker.reservePendingSourceFrame(key))
        val accepted = requireNotNull(publish(activity, key, lease))
        assertSame(accepted, latest(activity))
        assertFalse("Worker finally must not release the completed publication",
            tracker.releasePendingSourceFrame(lease))
        assertNotNull(tracker.reservePendingSourceFrame(offer(tracker, 2L)))
    }

    @Test
    fun partialPublicationKeepsThePendingLeaseForTheCompletedResult() {
        val activity = MainActivity()
        val tracker = tracker(activity)
        val key = offer(tracker, 1L)
        val lease = requireNotNull(tracker.reservePendingSourceFrame(key))
        assertTrue(requireNotNull(publish(activity, key, lease, partial = true)).partial)
        assertNull("A partial must not free the in-flight slot", tracker.reservePendingSourceFrame(key))
        assertFalse(requireNotNull(publish(activity, key, lease)).partial)
        assertFalse(tracker.releasePendingSourceFrame(lease))
    }

    @Test
    fun aLeaseFromBeforeClearCannotPublishOrReleaseTheSameKeysNewReservation() {
        val activity = MainActivity()
        val tracker = tracker(activity)
        val key = offer(tracker, 1L)
        val old = requireNotNull(tracker.reservePendingSourceFrame(key))
        tracker.clear()
        offer(tracker, 1L)
        val current = requireNotNull(tracker.reservePendingSourceFrame(key))
        val before = latest(activity)
        assertNull(publish(activity, key, old))
        assertSame(before, latest(activity))
        assertFalse(tracker.releasePendingSourceFrame(old))
        assertNotNull(publish(activity, key, current))
    }

    @Test
    fun aWrongSourceKeyCannotConsumeTheWorkersReservationOrReplaceTheSnapshot() {
        val activity = MainActivity()
        val tracker = tracker(activity)
        val first = offer(tracker, 1L)
        val second = offer(tracker, 2L)
        val lease = requireNotNull(tracker.reservePendingSourceFrame(first))
        val before = latest(activity)
        assertNull(publish(activity, second, lease))
        assertSame(before, latest(activity))
        assertNotNull(publish(activity, first, lease))
    }

    @Test
    fun actualWorkerReservesBeforeSubmitAndReleasesItsOwnLeaseOnBothFailurePaths() {
        val main = mainSource()
        val bridge = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/AndroidPrimaryArSubmissionBridge.kt").readText()
        val worker = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryArDetectorWorker.kt").readText()
        assertTrue(main.contains("primaryArSubmissionBridge.schedule("))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(bridge,
            "synthesis().reservePendingSourceFrame(key)", "reservedVisualSource = visualSourceLease",
            "capturedDepthSnapshot, visualSourceLease)", "targetWorker.submit(queuedWork)"))
        assertTrue(bridge.contains("if (!submittedToWorker)"))
        assertTrue(bridge.contains("synthesis().releasePendingSourceFrame(lease)"))
        assertTrue(worker.contains("detectorOwner.nativeLane.execute { run(work) }"))
        assertTrue(worker.contains("if (!submitted) cancelUnsubmitted(work.ticket, work.visualSourceLease)"))
        assertTrue(worker.contains("reportImage, sourceLease = work.visualSourceLease)"))
        assertTrue(worker.contains("synchronized(frameStateLock) { sourceRelease.release(it) }"))
        assertTrue(MainActivityRefactorSource.member(worker, "cancelUnsubmitted").contains("releaseSource(sourceLease)"))
        assertTrue(MainActivityRefactorSource.member(worker, "run").substringAfterLast("finally").contains("releaseSource(work.visualSourceLease)"))
    }

    @Test
    fun actualGlConsumptionRechecksTheCurrentSnapshotUnderTheFrameLock() {
        val synthesis = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FrameSynthesisController.kt").readText()
        val tracking = MainActivityRefactorSource.member(synthesis, "trackSource")
        val pipeline = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText()
        assertTrue(mainSource().contains("override fun onDrawFrame(gl: GL10?) = framePipelineController.draw()"))
        assertTrue(pipeline.contains("synthesis.trackSource(detection, q.gray, generation, epoch, priorities)"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(tracking,
            "synchronized(frameStateLock)", "latestDetectionSnapshot !== snapshot", "visualFrameTracker.trackFrom("))
    }

    private fun offer(tracker: InterFrameDetectionTracker, id: Long): VisualFrameKey {
        val key = VisualFrameKey(0L, id, 1_000_000_000L + id * 17_000_000L, 1_000L + id * 17L, 0L)
        assertTrue(tracker.offerFrame(GrayTrackingFrame.copyOf(key, 16, 16, ByteArray(256))).accepted)
        return key
    }

    private fun publish(activity: MainActivity, key: VisualFrameKey,
        lease: InterFrameDetectionTracker.PendingSourceLease?, partial: Boolean = false) =
        activity.publishDetectionSnapshot(
            result = AndroidDetectionResult.empty().copy(partial = partial), generation = 0,
            detectionIdentity = AndroidDetectionSnapshotFrameIdentity(
                captureFrameId = key.frameId, frameTimestampMs = key.cameraTimestampNs / 1_000_000L),
            capturedAtMs = 1_000L, startedAtMs = 1_000L, completedAtMs = 1_010L,
            imageWidth = 16, imageHeight = 16, reportImageJpeg = null,
            frameEvidence = DetectionFrameEvidence(
                frameId = key.frameId, frameTimestampMs = key.cameraTimestampNs / 1_000_000L,
                depthSnapshot = DepthFrameSnapshot(frameTimestampNs = key.frameId,
                    rawDepth = null, rawConfidence = null, fullDepth = null), depthMapper = null,
                tactileContext = TactileProjectionContext(captureFrameId = key.frameId,
                    expectedRouteId = null, routeProjection = null, trustedLocation = null,
                    orientation = null, magneticDeclinationDeg = null, cameraFrame = null,
                    detectionAgeMs = 10L, nowElapsedRealtimeMs = key.capturedAtElapsedRealtimeMs),
                motionContext = MotionContext(), navigationActive = false, tmapOnRoute = false,
                visualFrameKey = key, geometryVersion = 0L,
            ),
            visualSourceLease = lease,
        )

    private fun tracker(activity: MainActivity) = MainActivityControllerTestFixture.field("visualFrameTracker")
        .apply { isAccessible = true }.get(activity) as InterFrameDetectionTracker
    private fun latest(activity: MainActivity) = MainActivityControllerTestFixture.field("latestDetectionSnapshot")
        .apply { isAccessible = true }.get(activity)
    private fun mainSource() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
}
