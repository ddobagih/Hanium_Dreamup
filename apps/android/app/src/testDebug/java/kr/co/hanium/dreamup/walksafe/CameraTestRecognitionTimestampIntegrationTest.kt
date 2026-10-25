package kr.co.hanium.dreamup.walksafe

import android.media.CameraTimestampTestImage
import android.media.Image
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraTestRecognitionTimestampIntegrationTest {
    @Test fun initiallyPausedCameraStillCapturesRgbWithoutDepthOrCameraClockQualification() {
        assertUntrackedRgbCapture(TrackingState.PAUSED)
    }

    @Test fun initiallyStoppedCameraStillCapturesRgbWithoutDepthOrCameraClockQualification() {
        assertUntrackedRgbCapture(TrackingState.STOPPED)
    }

    private fun assertUntrackedRgbCapture(state: TrackingState) {
        val frame = FakeFrame().apply {
            cameraValue.state = state
            firstImage.failTimestamp = true // RGB must not depend on reading its unavailable CPU clock.
        }
        val capture = captureCameraTestFrame(provider(), frame)
        assertNotNull("Initial $state must keep the original CameraTest RGB path", capture)
        val acquired = requireNotNull(capture)
        try {
            assertSame(frame.firstImage, acquired.image)
            assertEquals(1, frame.cameraAcquisitions)
            assertEquals(0, frame.rawAcquisitions)
            assertEquals(0, frame.fullAcquisitions)
            assertNull(acquired.snapshot.cameraImageTimestampNs)
            assertFalse(acquired.snapshot.hasMetricRawDepth)
            assertFalse(acquired.snapshot.hasFullDepth)
            assertNull(acquired.depthError)
            assertNotNull(acquired.image.planes[0].buffer)
            assertEquals(0, frame.firstImage.closeCalls)
        } finally { acquired.image.close() }
        assertEquals(1, frame.firstImage.closeCalls)
    }

    @Test fun actualCameraCaptureAcquiresOnlyTheRecognitionImageAndSharesItsClockWithDepth() {
        val frame = FakeFrame()
        val capture = requireNotNull(captureCameraTestFrame(provider(), frame))
        try {
            assertEquals("CameraTest depth must not obtain another CPU image", 1, frame.cameraAcquisitions)
            assertSame(frame.firstImage, capture.image)
            assertEquals(frame.firstImage.timestamp, capture.snapshot.cameraImageTimestampNs)
            assertTrue(capture.snapshot.hasFreshMetricRawDepth)
            assertTrue(capture.snapshot.hasFreshFullDepth)
            assertNull(capture.depthError)
            assertEquals(0, frame.firstImage.closeCalls)
            assertEquals(1, frame.rawImage.closeCalls)
            assertEquals(1, frame.confidenceImage.closeCalls)
            assertEquals(1, frame.fullImage.closeCalls)
        } finally { capture.image.close() }
        assertEquals(1, frame.firstImage.closeCalls)
    }

    @Test fun failedRecognitionDoesNotStartNativeDepthOrReturnAnInferenceImage() {
        val frame = FakeFrame().apply { failCamera = true }
        assertNull(captureCameraTestFrame(provider(), frame))
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(0, frame.rawAcquisitions)
        assertEquals(0, frame.fullAcquisitions)
        assertEquals(0, frame.firstImage.closeCalls)
    }

    @Test fun invalidCpuClockIsClosedOnceWithoutDepthOrInventingAReplacementClock() {
        for (clock in listOf(0L, -1L)) {
            val frame = FakeFrame(clock)
            assertNull(captureCameraTestFrame(provider(), frame))
            assertEquals(1, frame.cameraAcquisitions)
            assertEquals(1, frame.firstImage.closeCalls)
            assertEquals(0, frame.rawAcquisitions)
            assertEquals(0, frame.fullAcquisitions)
        }
    }

    @Test fun failedDepthSnapshotKeepsTheSameOpenRgbImageAndDoesNotInventCameraEvidence() {
        val frame = FakeFrame().apply { cameraValue.failTrackingReadAt = 4 }
        val capture = requireNotNull(captureCameraTestFrame(provider(), frame))
        try {
            assertSame(frame.firstImage, capture.image)
            assertEquals(0, frame.firstImage.closeCalls)
            assertEquals(1, frame.cameraAcquisitions)
            assertNotNull(capture.depthError)
            assertNull(capture.snapshot.cameraImageTimestampNs)
            assertFalse(capture.snapshot.hasMetricRawDepth)
            assertFalse(capture.snapshot.hasFullDepth)
        } finally { capture.image.close() }
        assertEquals(1, frame.firstImage.closeCalls)
    }

    @Test fun unavailableNativeDepthStillReturnsRgbAndTheActualRecognitionReceiptClock() {
        val frame = FakeFrame().apply { failRaw = true; failFull = true }
        val capture = requireNotNull(captureCameraTestFrame(provider(), frame))
        try {
            assertSame(frame.firstImage, capture.image)
            assertEquals(1, frame.cameraAcquisitions)
            assertEquals(1, frame.rawAcquisitions)
            assertEquals(1, frame.fullAcquisitions)
            assertEquals(900_000_000L, capture.snapshot.cameraImageTimestampNs)
            assertFalse(capture.snapshot.hasMetricRawDepth)
            assertFalse(capture.snapshot.hasFullDepth)
            assertEquals(0, frame.firstImage.closeCalls)
        } finally { capture.image.close() }
    }

    @Test fun sameProviderCancellationDuringPoseCannotBeOverwrittenByTheOpenImageClock() {
        val source = provider()
        val frame = FakeFrame().apply { cameraValue.beforePose = source::invalidateRecognitionCameraImageTimestamps }
        val capture = requireNotNull(captureCameraTestFrame(source, frame))
        try {
            assertEquals(1, frame.cameraAcquisitions)
            assertNull(capture.snapshot.cameraImageTimestampNs)
            assertFalse(capture.snapshot.hasFreshMetricRawDepth)
            assertFalse(capture.snapshot.hasFreshFullDepth)
            assertSame(frame.firstImage, capture.image)
            assertEquals(0, frame.firstImage.closeCalls)
        } finally { capture.image.close() }
    }

    @Test fun observedTrackingLossKeepsRgbButDoesNotPromoteItsClockOrAcquireAnotherFrame() {
        val source = provider()
        val frame = FakeFrame().apply { cameraValue.beforePose = { cameraValue.state = TrackingState.PAUSED } }
        val capture = requireNotNull(captureCameraTestFrame(source, frame))
        capture.image.use {
            assertNull(capture.snapshot.cameraImageTimestampNs)
            assertFalse(capture.snapshot.hasFreshMetricRawDepth)
            assertFalse(capture.snapshot.hasFreshFullDepth)
        }
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.firstImage.closeCalls)
    }

    @Test fun mutatedFrameTimestampCannotBorrowTheEarlierRecognitionClock() {
        val frame = FakeFrame().apply { cameraValue.beforePose = { timeNs++ } }
        val capture = requireNotNull(captureCameraTestFrame(provider(), frame))
        capture.image.use {
            assertNull(capture.snapshot.cameraImageTimestampNs)
            assertFalse(capture.snapshot.hasFreshMetricRawDepth)
            assertFalse(capture.snapshot.hasFreshFullDepth)
        }
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.firstImage.closeCalls)
    }

    @Test fun actualGlReleaseBoundaryClosesAnUntransferredCaptureAndClearsBusyOnce() {
        val source = provider()
        val frame = FakeFrame()
        val capture = requireNotNull(captureCameraTestFrame(source, frame))
        val busy = AtomicBoolean(true)
        releaseUntransferredCameraTestFrame(source, capture.image, busy, transferred = false)
        assertEquals(1, frame.firstImage.closeCalls)
        assertFalse(busy.get())
        assertEquals(1, frame.cameraAcquisitions)
    }

    @Test fun actualGlReleaseBoundaryLeavesAcceptedWorkInChargeOfItsImageAndBusyFlag() {
        val source = provider()
        val frame = FakeFrame()
        val capture = requireNotNull(captureCameraTestFrame(source, frame))
        val busy = AtomicBoolean(true)
        releaseUntransferredCameraTestFrame(source, capture.image, busy, transferred = true)
        source.close() // Invalidation does not take over the already accepted caller's Image.
        assertEquals(0, frame.firstImage.closeCalls)
        assertTrue(busy.get())
        capture.image.close()
        busy.set(false)
        assertEquals(1, frame.firstImage.closeCalls)
    }

    @Test fun closedProviderDoesNotAcquireAnyNativeImageAndTheGlBoundaryReleasesBusy() {
        val source = provider().also { it.close() }
        val frame = FakeFrame()
        val busy = AtomicBoolean(true)
        assertNull(captureCameraTestFrame(source, frame))
        releaseUntransferredCameraTestFrame(source, null, busy, transferred = false)
        assertEquals(0, frame.cameraAcquisitions)
        assertEquals(0, frame.rawAcquisitions)
        assertEquals(0, frame.fullAcquisitions)
        assertFalse(busy.get())
    }

    private fun provider() = ArCoreFrameProvider(object : Session() {
        override fun createAnchor(pose: Pose): Anchor = object : Anchor() {
            override fun getPose() = pose
            override fun getTrackingState() = TrackingState.TRACKING
            override fun detach() = Unit
        }
        override fun <T : Trackable> getAllTrackables(type: Class<T>): Collection<T> = emptyList()
    })

    private class FakeCamera : Camera() {
        var state = TrackingState.TRACKING
        var trackingReads = 0
        var failTrackingReadAt = 0
        var beforePose: (() -> Unit)? = null
        override fun getTrackingState(): TrackingState {
            trackingReads++
            check(trackingReads != failTrackingReadAt) { "depth pose tracking unavailable" }
            return state
        }
        override fun getPose(): Pose {
            beforePose?.invoke()
            return Pose.IDENTITY
        }
        override fun getImageIntrinsics(): CameraIntrinsics = object : CameraIntrinsics() {
            override fun getFocalLength() = floatArrayOf(500f, 500f)
            override fun getPrincipalPoint() = floatArrayOf(1f, 1f)
            override fun getImageDimensions() = intArrayOf(2, 2)
        }
    }

    private class FakeFrame(cpuClock: Long = 900_000_000L) : Frame() {
        val cameraValue = FakeCamera()
        var timeNs = 1_000_000_000L
        val firstImage = CameraTimestampTestImage(cpuClock, 1)
        val secondImage = CameraTimestampTestImage(910_000_000L, 1)
        val rawImage = CameraTimestampTestImage(900_000_000L, 2)
        val confidenceImage = CameraTimestampTestImage(900_000_000L, 1)
        val fullImage = CameraTimestampTestImage(900_000_000L, 2)
        var cameraAcquisitions = 0
        var rawAcquisitions = 0
        var fullAcquisitions = 0
        var failCamera = false
        var failRaw = false
        var failFull = false
        override fun getTimestamp() = timeNs
        override fun getCamera(): Camera = cameraValue
        override fun acquireCameraImage(): Image {
            cameraAcquisitions++
            check(!failCamera) { "CPU image unavailable" }
            return if (cameraAcquisitions == 1) firstImage else secondImage
        }
        override fun acquireRawDepthImage16Bits(): Image {
            rawAcquisitions++
            check(!failRaw) { "raw unavailable" }
            return rawImage
        }
        override fun acquireRawDepthConfidenceImage(): Image = confidenceImage
        override fun acquireDepthImage16Bits(): Image {
            fullAcquisitions++
            check(!failFull) { "full unavailable" }
            check(rawImage.closeCalls > 0 && confidenceImage.closeCalls > 0)
            return fullImage
        }
    }
}
