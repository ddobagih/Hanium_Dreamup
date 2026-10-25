package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Real bounded tracker/source reservations; PATCH diagnostics do not claim native LK accuracy. */
class FrameSynthesisControllerTest {
    @Test fun constructionReadsNoRuntimeSdkOrClock() {
        val f = Fixture()
        assertEquals(0, f.authorityReads)
        assertEquals(0, f.clockReads)
        assertEquals(0L, f.owner.geometryVersion)
        assertEquals(5_000_000L, f.owner.trackingBudgetNs)
    }

    @Test fun retiredRuntimeFrameCannotAdvanceDuplicateSuppression() {
        val f = Fixture()
        f.current = false
        assertFalse(f.owner.admitRuntimeFrame(4, f.epoch) { 10L })
        f.current = true
        assertTrue(f.owner.admitRuntimeFrame(4, f.epoch) { 10L })
        assertFalse(f.owner.admitRuntimeFrame(4, f.epoch) { 10L })
        assertTrue(f.owner.admitRuntimeFrame(4, f.epoch) { 11L })
        f.owner.resetCurrentCaptureHistory { .65f }
        assertTrue(f.owner.admitRuntimeFrame(4, f.epoch) { 10L })
    }

    @Test fun nativeDuplicateClockReadsKeepTheOriginalLockAndShortCircuitOrder() {
        val f = Fixture(); var reads = 0
        f.current = false
        assertFalse(f.owner.admitRuntimeFrame(4, f.epoch) { reads++; 10L })
        assertEquals(0, reads)
        f.current = true
        assertTrue(f.owner.admitRuntimeFrame(4, f.epoch) { reads++; 10L })
        assertEquals(2, reads)
        assertFalse(f.owner.admitRuntimeFrame(4, f.epoch) { reads++; 10L })
        assertEquals(3, reads)
    }

    @Test fun repeatedVisualFrameDoesNotRenewTheActualGrayCapture() {
        val f = Fixture()
        val (input, snapshot) = f.input(1_000_000_000L, 100_000L)
        assertSame(input.gray, f.owner.captureVisualTrackingFrame(input, snapshot, 4, f.epoch))
        val newerClockWithSameFrame = f.input(1_000_000_000L, 100_500L).first
        assertNull(f.owner.captureVisualTrackingFrame(newerClockWithSameFrame, snapshot, 4, f.epoch))
        assertEquals(100_000L, input.gray!!.key.capturedAtElapsedRealtimeMs)
    }

    @Test fun sameValueForeignReservationCannotPublishIntoTheLocalTrackerOwner() {
        val f = Fixture()
        val other = Fixture()
        val (input, snapshot) = f.input(1_000_000_000L, 100_000L)
        f.owner.captureVisualTrackingFrame(input, snapshot, 4, f.epoch)
        other.owner.captureVisualTrackingFrame(input, snapshot, 4, other.epoch)
        val key = requireNotNull(input.gray).key
        val foreign = requireNotNull(other.owner.reservePendingSourceFrame(key))
        val previous = f.owner.detectionSnapshot
        val issued = DetectionSnapshot.empty()
        assertFalse(f.owner.publishSourceSnapshot(issued, key, foreign))
        assertSame(previous, f.owner.detectionSnapshot)
        val actual = requireNotNull(f.owner.reservePendingSourceFrame(key))
        assertTrue(f.owner.publishSourceSnapshot(issued, key, actual))
        assertSame(issued, f.owner.detectionSnapshot)
        other.owner.releasePendingSourceFrame(foreign)
    }

    @Test fun trackingRetirementIsTerminalAndDoesNotReadExecutionClock() {
        val f = Fixture()
        f.current = false
        assertSame(FrameSourceTracking.Retired,
            f.owner.trackSource(f.owner.detectionSnapshot, null, 4, f.epoch, emptyList()))
        assertEquals(0, f.clockReads)
    }

    @Test fun missingOrForeignCpuClockCannotRunTheMetricSamplerOrVisualOwner() {
        val snapshot = DepthFrameSnapshot(1_000L, null, null, null, cameraImageTimestampNs = 900L)
        var samples = 0
        var visual = 0
        assertEquals(0, FrameRecognitionAdmission.metricSampleCount(null, 1_000L, snapshot) { samples++; 99 })
        assertEquals(0, FrameRecognitionAdmission.metricSampleCount(901L, 1_000L, snapshot) { samples++; 99 })
        assertFalse(FrameRecognitionAdmission.allowsVisualWork(901L, 1_000L, snapshot) { visual++; true })
        assertEquals(0, samples)
        assertEquals(0, visual)
        assertEquals(7, FrameRecognitionAdmission.metricSampleCount(900L, 1_000L, snapshot) { samples++; 7 })
        assertTrue(FrameRecognitionAdmission.allowsVisualWork(900L, 1_000L, snapshot) { visual++; true })
        assertEquals(1, samples)
        assertEquals(1, visual)
    }

    private class Fixture {
        val epoch = WalkRuntimeEpoch("walk", 7L)
        var authorityReads = 0
        var clockReads = 0
        var current = true
        val owner = FrameSynthesisController(Any(), object : FrameSynthesisAuthority {
            override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch): Boolean { authorityReads++; return current }
            override fun userStepLengthM(): Float { authorityReads++; return .65f }
            override fun retireUnstartedFeedback(trackPrefix: String) {}
        }, { clockReads++; 100_000L }, InterFrameDetectionTracker(VisualTrackingConfig(backend = VisualTrackingBackend.PATCH_DIAGNOSTIC)))
        fun input(frameId: Long, capturedAt: Long): Pair<FrameRecognitionInput, DepthFrameSnapshot> {
            val width = 32; val height = 24; val cpuTime = 900_000_000L
            val y = ByteBuffer.wrap(ByteArray(width * height) { ((it % 7) * 30).toByte() })
            val uv = ByteBuffer.wrap(ByteArray(width * height / 4) { 128.toByte() })
            val owned = OwnedYuvFrame.copyOf(width, height, y, uv, uv, width, 1, width / 2, 1)
            val gray = GrayTrackingFrame.copyFromLuma(VisualFrameKey(5L, frameId, cpuTime, capturedAt, 0L),
                width, height, y, width, 1)
            val input = FrameRecognitionInput(owned, cpuTime, gray,
                CameraFrameQualityObservation(epoch, capturedAt, true, .5, 0.0, null, null), 0, 0L)
            return input to DepthFrameSnapshot(frameId, null, null, null, cameraImageTimestampNs = cpuTime)
        }
    }
}
