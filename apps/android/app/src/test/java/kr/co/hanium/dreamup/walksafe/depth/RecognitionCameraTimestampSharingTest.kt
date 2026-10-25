package kr.co.hanium.dreamup.walksafe.depth

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionCameraTimestampSharingTest {
    @Test
    fun initialUntrackedRgbCaptureRetainsSingleImageAndCallerOwnership() {
        for (state in listOf(TrackingState.PAUSED, TrackingState.STOPPED)) {
            val frame = FakeFrame(0L).apply {
                cameraValue.state = state
                timeNs = 0L
                cameraImage.failTimestamp = true
            }
            val image = provider().acquireUntrackedRecognitionCameraImageOrNull(frame)
            assertNotNull("RGB capture must remain available for initial $state", image)
            assertEquals(1, frame.cameraAcquisitions)
            assertEquals(0, frame.rawDepthAcquisitions)
            assertEquals(0, frame.fullDepthAcquisitions)
            assertEquals(0, frame.cameraImage.closeCalls)
            requireNotNull(image).use { assertSame(frame.cameraImage, it) }
            assertEquals(1, frame.cameraImage.closeCalls)
        }
    }

    @Test
    fun trackingRgbPathCannotRetryFailedStrictCapture() {
        val frame = FakeFrame().apply { cameraImage.failTimestamp = true }
        val provider = provider()
        assertNull(provider.acquireRecognitionCameraImageOrNull(frame))
        assertEquals(1, frame.cameraAcquisitions)
        assertNull(provider.acquireUntrackedRecognitionCameraImageOrNull(frame))
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
        assertEquals(0, frame.rawDepthAcquisitions)
        assertEquals(0, frame.fullDepthAcquisitions)
    }

    @Test
    fun rejectedTrackingRgbPathDoesNotInvalidateASuccessfulStrictReceipt() {
        val frame = FakeFrame()
        val provider = provider()
        val receipt = capture(provider, frame)
        assertNull(provider.acquireUntrackedRecognitionCameraImageOrNull(frame))
        assertEquals(1, frame.cameraAcquisitions)
        assertTrue(provider.acquireDepthSnapshot(frame, receipt).hasFreshMetricRawDepth)
    }

    @Test
    fun closedRgbPathRejectsBeforeAnyFrameOrNativeAccess() {
        val frame = FakeFrame().apply { forbidFrameAccess = true }
        val provider = provider()
        provider.close()
        assertNull(provider.acquireUntrackedRecognitionCameraImageOrNull(frame))
        assertEquals(0, frame.cameraAccesses)
        assertEquals(0, frame.timestampAccesses)
        assertEquals(0, frame.cameraAcquisitions)
        assertEquals(0, frame.rawDepthAcquisitions)
        assertEquals(0, frame.fullDepthAcquisitions)
        assertEquals(0, frame.cameraImage.closeCalls)
    }

    @Test
    fun untrackedRgbCaptureInvalidatesOldStrictReceiptAndMotionReferenceAfterTrackingReturns() {
        for (state in listOf(TrackingState.PAUSED, TrackingState.STOPPED)) {
            val frame = FakeFrame().apply { allowAdditionalCameraImages = true }
            val provider = provider()
            val receipt = capture(provider, frame)
            val previousPose = requireNotNull(provider.acquireDepthSnapshot(frame, receipt).cameraPoseEvidence)
            val rawAttemptsBeforeRgb = frame.rawDepthAcquisitions
            val fullAttemptsBeforeRgb = frame.fullDepthAcquisitions
            frame.cameraValue.state = state
            val image = requireNotNull(provider.acquireUntrackedRecognitionCameraImageOrNull(frame))
            assertEquals(2, frame.cameraAcquisitions)
            assertEquals(rawAttemptsBeforeRgb, frame.rawDepthAcquisitions)
            assertEquals(fullAttemptsBeforeRgb, frame.fullDepthAcquisitions)
            assertEquals(0, frame.additionalImage.closeCalls)
            image.use { assertSame(frame.additionalImage, it) }
            assertEquals(1, frame.additionalImage.closeCalls)
            frame.cameraValue.state = TrackingState.TRACKING
            val resumed = provider.acquireDepthSnapshot(frame, receipt)
            assertUnqualified(resumed)
            assertNotEquals(previousPose.referenceId, requireNotNull(resumed.cameraPoseEvidence).referenceId)
            assertEquals(2, frame.cameraAcquisitions)
        }
    }

    @Test
    fun recognitionTimestampIsSharedWithoutAnotherImageAcquisition() {
        val frame = FakeFrame()
        val provider = provider()
        val recognition = requireNotNull(provider.acquireRecognitionCameraImageOrNull(frame))
        val recognitionTimestamp = recognition.image.use { it.timestamp }
        val snapshot = provider.acquireDepthSnapshot(
            frame, recognition.timestampEvidence, includeFullDepthWhenRawAvailable = false,
        )
        assertEquals("depth must reuse the recognition CPU image", 1, frame.cameraAcquisitions)
        assertEquals(recognitionTimestamp, snapshot.cameraImageTimestampNs)
        assertEquals(frame.timeNs, snapshot.frameTimestampNs)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertEquals(1, frame.cameraImage.closeCalls)
        assertEquals(1, frame.rawImage.closeCalls)
        assertEquals(1, frame.confidenceImage.closeCalls)
        assertEquals(0, frame.fullDepthAcquisitions)
    }

    @Test
    fun explicitMissingRecognitionTimestampDoesNotAcquireOrInventACameraClock() {
        val frame = FakeFrame()
        val snapshot = provider().acquireDepthSnapshot(frame, cameraImageTimestampEvidence = null)
        assertUnqualified(snapshot)
        assertEquals(0, frame.cameraAcquisitions)
    }

    @Test
    fun invalidCpuTimestampsAreClosedAndNeverReplacedWithTheFrameClock() {
        for (timestamp in listOf(0L, -1L)) {
            val frame = FakeFrame(timestamp)
            val provider = provider()
            assertNull(provider.acquireRecognitionCameraImageOrNull(frame))
            val snapshot = provider.acquireDepthSnapshot(frame, cameraImageTimestampEvidence = null)
            assertUnqualified(snapshot)
            assertEquals(1, frame.cameraAcquisitions)
            assertEquals(1, frame.cameraImage.closeCalls)
        }
    }

    @Test
    fun foreignFrameImageCannotBeAttachedEvenWhenBothFrameAndCpuTimestampsMatch() {
        val sourceFrame = FakeFrame()
        val targetFrame = FakeFrame()
        val provider = provider()
        val receipt = capture(provider, sourceFrame)
        val snapshot = provider.acquireDepthSnapshot(targetFrame, receipt)
        assertUnqualified(snapshot)
        assertEquals(1, sourceFrame.cameraAcquisitions)
        assertEquals(0, targetFrame.cameraAcquisitions)
    }

    @Test
    fun foreignProviderReceiptAndMutatedFrameIdentityAreRejectedWithoutAcquisition() {
        val frame = FakeFrame()
        val provider = provider()
        val receipt = capture(provider, frame)
        assertUnqualified(provider().acquireDepthSnapshot(frame, receipt))
        frame.timeNs += 1L
        assertUnqualified(provider.acquireDepthSnapshot(frame, receipt))
        assertEquals(1, frame.cameraAcquisitions)
    }

    @Test
    fun cancellationOrPauseEpochInvalidatesTheReceiptWhileLeavingImageOwnershipWithCaller() {
        val frame = FakeFrame()
        val provider = provider()
        val recognition = requireNotNull(provider.acquireRecognitionCameraImageOrNull(frame))
        provider.invalidateRecognitionCameraImageTimestamps()
        assertUnqualified(provider.acquireDepthSnapshot(frame, recognition.timestampEvidence))
        assertEquals(0, frame.cameraImage.closeCalls)
        recognition.image.close()
        assertEquals(1, frame.cameraImage.closeCalls)
        assertEquals(1, frame.cameraAcquisitions)
    }

    @Test
    fun observedTrackingLossInvalidatesReceiptAfterTrackingReturnsOnTheSameFrameObject() {
        val frame = FakeFrame()
        val provider = provider()
        val receipt = capture(provider, frame)
        frame.cameraValue.state = TrackingState.PAUSED
        assertUnqualified(provider.acquireDepthSnapshot(frame, receipt))
        frame.cameraValue.state = TrackingState.TRACKING
        assertUnqualified(provider.acquireDepthSnapshot(frame, receipt))
        assertEquals(1, frame.cameraAcquisitions)
    }

    @Test
    fun failedRecognitionAttemptCannotReuseAnEarlierSuccessfulReceipt() {
        val frame = FakeFrame()
        val provider = provider()
        val receipt = capture(provider, frame)
        assertNull(provider.acquireRecognitionCameraImageOrNull(frame))
        assertUnqualified(provider.acquireDepthSnapshot(frame, receipt))
        assertEquals(2, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test
    fun anotherConsumersAcquisitionCannotOverwriteOrInvalidateRecognitionClock() {
        val frame = FakeFrame().apply { allowAdditionalCameraImages = true }
        val provider = provider()
        val receipt = capture(provider, frame)
        requireNotNull(provider.acquireCameraImageOrNull(frame, CameraImageConsumer.UNKNOWN_OBJECT)).use {
            assertEquals(910_000_000L, it.timestamp)
        }
        val snapshot = provider.acquireDepthSnapshot(frame, receipt)
        assertEquals(900_000_000L, snapshot.cameraImageTimestampNs)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertEquals(2, frame.cameraAcquisitions)
        assertEquals(1, frame.additionalImage.closeCalls)
    }

    @Test
    fun failedRecognitionPreprocessingPassesNoReceiptAndClosesImageWithoutRetry() {
        val frame = FakeFrame()
        val provider = provider()
        val recognition = requireNotNull(provider.acquireRecognitionCameraImageOrNull(frame))
        val evidence = runCatching {
            recognition.image.use { error("recognition preprocessing failed") }
            recognition.timestampEvidence
        }.getOrNull()
        assertNull(evidence)
        assertUnqualified(provider.acquireDepthSnapshot(frame, evidence))
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test
    fun timestampReadFailureClosesTheImageAndDoesNotTriggerADepthCameraRetry() {
        val frame = FakeFrame()
        frame.cameraImage.failTimestamp = true
        val provider = provider()
        assertNull(provider.acquireRecognitionCameraImageOrNull(frame))
        assertUnqualified(provider.acquireDepthSnapshot(frame, cameraImageTimestampEvidence = null))
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
        assertEquals(1, provider.runtimeAcquisitionFailureCount(
            DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.ILLEGAL_STATE,
        ))
    }

    @Test
    fun closeRejectsReceiptsAndDoesNotAcquireOrCloseTheCallersImage() {
        val frame = FakeFrame()
        val provider = provider()
        val recognition = requireNotNull(provider.acquireRecognitionCameraImageOrNull(frame))
        provider.close()
        val snapshot = provider.acquireDepthSnapshot(frame, recognition.timestampEvidence)
        assertUnqualified(snapshot)
        provider.acquireDepthBundle(frame, recognition.timestampEvidence).use {
            assertNull(it.cameraImageTimestampNs)
            assertFalse(it.hasRawDepth)
            assertFalse(it.hasFullDepth)
        }
        assertEquals(0, frame.rawDepthAcquisitions)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(0, frame.cameraImage.closeCalls)
        assertNull(provider.acquireRecognitionCameraImageOrNull(frame))
        recognition.image.close()
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test
    fun fullDepthStillStartsAfterClosingRawPairWithoutASecondCpuImage() {
        val frame = FakeFrame()
        val provider = provider()
        val receipt = capture(provider, frame)
        val snapshot = provider.acquireDepthSnapshot(frame, receipt, includeFullDepthWhenRawAvailable = true)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertTrue(snapshot.hasFreshFullDepth)
        assertEquals(1, frame.fullDepthAcquisitions)
        assertEquals(1, frame.fullImage.closeCalls)
        assertEquals(1, frame.cameraAcquisitions)
    }

    @Test
    fun sharedBundleUsesReceiptAndLegacyPathStillAcquiresItsOwnTimestamp() {
        val frame = FakeFrame()
        val provider = provider()
        val receipt = capture(provider, frame)
        provider.acquireDepthBundle(frame, receipt, false).use {
            assertEquals(900_000_000L, it.cameraImageTimestampNs)
        }
        assertEquals(1, frame.cameraAcquisitions)
        val legacyFrame = FakeFrame()
        provider.acquireDepthBundle(legacyFrame, false).use {
            assertEquals(900_000_000L, it.cameraImageTimestampNs)
        }
        assertEquals(1, legacyFrame.cameraAcquisitions)
        assertEquals(1, legacyFrame.cameraImage.closeCalls)
    }

    private fun assertUnqualified(snapshot: DepthFrameSnapshot) {
        assertNull(snapshot.cameraImageTimestampNs)
        assertFalse(snapshot.hasFreshMetricRawDepth)
        assertFalse(snapshot.hasFreshFullDepth)
    }

    private fun capture(provider: ArCoreFrameProvider, frame: FakeFrame): CameraImageTimestampEvidence =
        requireNotNull(provider.acquireRecognitionCameraImageOrNull(frame)).let {
            it.image.use { image -> assertNotNull(image.planes.first().buffer) }
            it.timestampEvidence
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
        override fun getTrackingState() = state
        override fun getPose() = Pose.IDENTITY
        override fun getImageIntrinsics(): CameraIntrinsics = object : CameraIntrinsics() {
            override fun getFocalLength() = floatArrayOf(500f, 500f)
            override fun getPrincipalPoint() = floatArrayOf(1f, 1f)
            override fun getImageDimensions() = intArrayOf(2, 2)
        }
    }

    private class FakeFrame(cpuTimestampNs: Long = 900_000_000L) : Frame() {
        val cameraValue = FakeCamera()
        var timeNs = 1_000_000_000L
        val cameraImage = CameraTimestampTestImage(cpuTimestampNs, 1)
        val additionalImage = CameraTimestampTestImage(910_000_000L, 1)
        val rawImage = CameraTimestampTestImage(900_000_000L, 2)
        val confidenceImage = CameraTimestampTestImage(900_000_000L, 1)
        val fullImage = CameraTimestampTestImage(900_000_000L, 2)
        var cameraAcquisitions = 0
        var cameraAccesses = 0
        var timestampAccesses = 0
        var forbidFrameAccess = false
        var allowAdditionalCameraImages = false
        var rawDepthAcquisitions = 0
        var fullDepthAcquisitions = 0
        override fun getTimestamp(): Long {
            check(!forbidFrameAccess) { "frame must not be accessed" }
            timestampAccesses++
            return timeNs
        }
        override fun getCamera(): Camera {
            check(!forbidFrameAccess) { "frame must not be accessed" }
            cameraAccesses++
            return cameraValue
        }
        override fun acquireCameraImage(): Image {
            cameraAcquisitions++
            check(cameraAcquisitions == 1 || allowAdditionalCameraImages) { "second CPU image acquisition unavailable" }
            return if (cameraAcquisitions == 1) cameraImage else additionalImage
        }
        override fun acquireRawDepthImage16Bits(): Image = rawImage.also { rawDepthAcquisitions++ }
        override fun acquireRawDepthConfidenceImage(): Image = confidenceImage
        override fun acquireDepthImage16Bits(): Image {
            check(rawImage.closeCalls > 0 && confidenceImage.closeCalls > 0) { "raw images must be released first" }
            fullDepthAcquisitions++
            return fullImage
        }
    }
}
