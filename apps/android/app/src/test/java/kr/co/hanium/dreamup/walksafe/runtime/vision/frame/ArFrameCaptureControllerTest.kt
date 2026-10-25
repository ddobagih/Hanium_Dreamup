package kr.co.hanium.dreamup.walksafe.runtime.vision.frame

import android.media.CameraTimestampTestImage
import android.media.FramePipelineTestImage
import android.media.Image
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeAttachment
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Actual provider and controller with SDK objects doubled; no device, GL or native AR proof. */
class ArFrameCaptureControllerTest {
    @Test fun constructionDoesNotReadSdkSourceClockOrAdmission() {
        val f = Fixture()
        assertTrue(f.trace.isEmpty())
        assertEquals(0, f.sourceReads)
        assertEquals(0, f.frame.cameraAcquisitions)
        assertEquals(0, f.clockReads)
    }

    @Test fun oneActualCpuCopySharesReceiptAndClosesBeforeRuntimeConsumer() {
        val f = Fixture()
        f.draw { scope ->
            assertSame(f.marker, scope.resources.originalLease)
            assertSame(f.frame, scope.frame)
            assertEquals(1, f.frame.rgb.closes)
            val input = requireNotNull(scope.recognition?.input)
            assertEquals(f.frame.rgb.originalTimestampNs, input.cameraTimestampNs)
            assertEquals(input.cameraTimestampNs, scope.snapshot.cameraImageTimestampNs)
            assertEquals(input.cameraTimestampNs, input.gray?.key?.cameraTimestampNs)
            assertEquals(scope.capturedElapsedMs, input.gray?.key?.capturedAtElapsedRealtimeMs)
            assertEquals(4, input.cameraFrame.toArgb().pixels.size)
            assertEquals(0, input.imageQuarterTurns)
        }
        assertEquals(1, f.frame.cameraAcquisitions)
        assertEquals(1, f.frame.rgb.closes)
        assertTrue(f.trace.indexOf("draw") < f.trace.indexOf("quality"))
        assertTrue(f.trace.indexOf("diagnostic") < f.trace.indexOf("runtime"))
    }

    @Test fun failedCopyDoesNotReacquireOrQualifyDepthWithAClockFallback() {
        val f = Fixture()
        val capture = f.recognition.acquireFrameRecognitionCapture<Long>(f.provider, f.frame, false) {
            throw IllegalStateException("copy")
        }
        assertNull(capture.input)
        assertNull(capture.depthSnapshot.cameraImageTimestampNs)
        assertFalse(capture.depthSnapshot.hasFreshMetricRawDepth)
        assertEquals(1, f.frame.cameraAcquisitions)
        assertEquals(1, f.frame.rgb.closes)
    }

    @Test fun providerReceiptRetirementDuringCopyCannotBorrowAnotherCpuImage() {
        val f = Fixture()
        val capture = f.recognition.acquireFrameRecognitionCapture(f.provider, f.frame, false) {
            f.provider.invalidateRecognitionCameraImageTimestamps()
            it.timestamp
        }
        assertEquals(f.frame.rgb.originalTimestampNs, capture.input)
        assertNull(capture.depthSnapshot.cameraImageTimestampNs)
        assertEquals(1, f.frame.cameraAcquisitions)
        assertEquals(1, f.frame.rgb.closes)
    }

    @Test fun untrackedOwnedRgbDoesNotReadTheUnavailableCpuClockOrAcquireDepth() {
        val f = Fixture()
        f.frame.cameraValue.state = TrackingState.PAUSED
        f.frame.rgb.rejectTimestamp = true
        f.draw { scope ->
            val input = requireNotNull(scope.recognition?.input)
            assertNull(input.cameraTimestampNs)
            assertNull(input.gray)
            assertNull(scope.snapshot.cameraImageTimestampNs)
            assertEquals(4, input.cameraFrame.toArgb().pixels.size)
        }
        assertEquals(0, f.frame.rgb.timestampReads)
        assertEquals(0, f.frame.depthAcquisitions)
        assertEquals(1, f.frame.cameraAcquisitions)
        assertEquals(1, f.frame.rgb.closes)
    }

    @Test fun sourceRetiredDuringUpdateCannotDrawOrAcquirePixels() {
        val f = Fixture()
        f.afterUpdate = { f.sourceCurrent = false }
        f.draw { fail("Retired session must not deliver a capture") }
        assertFalse(f.trace.contains("draw"))
        assertEquals(0, f.frame.cameraAcquisitions)
        assertEquals(0, f.clockReads)
    }

    @Test fun sourceRetiredDuringCopyClosesItsImageWithoutPublishingTheFrame() {
        val f = Fixture()
        f.onQuality = { f.sourceCurrent = false }
        f.draw { fail("Retired source must not publish after acquisition") }
        assertEquals(1, f.frame.cameraAcquisitions)
        assertEquals(1, f.frame.rgb.closes)
        assertFalse(f.trace.contains("runtime"))
        assertFalse(f.trace.contains("diagnostic"))
    }

    @Test fun preflightUsesTheOriginalBundleWithoutCopyingRuntimeRecognitionPixels() {
        val f = Fixture()
        f.preflight = true
        f.draw { fail("Preflight must retain its own handler") }
        assertEquals(1, f.preflightCalls)
        assertEquals(1, f.frame.cameraAcquisitions)
        assertEquals(1, f.frame.rgb.closes)
        assertFalse(f.trace.contains("quality"))
        assertFalse(f.trace.contains("diagnostic"))
        assertEquals(1, f.frame.raw.closeCalls)
        assertEquals(1, f.frame.confidence.closeCalls)
    }

    @Test fun originalUpdateExceptionKindAndLeaseReachTheExistingFailureHandler() {
        for (cameraUnavailable in listOf(true, false)) {
            val f = Fixture()
            val error = if (cameraUnavailable) CameraNotAvailableException() else IllegalStateException("update")
            f.updateError = error
            f.draw { fail("An update failure has no source frame") }
            assertSame(error, f.failure)
            assertEquals(if (cameraUnavailable) "CAMERA_NOT_AVAILABLE" else "RUNTIME_EXCEPTION", f.failureKind)
            assertEquals(0, f.frame.cameraAcquisitions)
            assertEquals(0, f.clockReads)
        }
    }

    @Test fun delayedUiUsesOriginalTimestampEvenIfSdkFrameChangesAndRetiredCallbackDoesNothing() {
        val f = Fixture()
        val queued = mutableListOf<() -> Unit>()
        val rendered = mutableListOf<Long>()
        var currentUi = true
        f.frame.timeNs = 1_000_000_123L
        f.draw { scope ->
            val original = scope.originalFrameTimestampNs
            queued += { if (currentUi) rendered += original }
        }
        f.frame.timeNs = 9_999_999_999L
        queued.removeAt(0).invoke()
        assertEquals(listOf(1_000_000_123L), rendered)
        f.frame.rgb = FramePipelineTestImage(9_900_000_000L)
        f.draw { scope ->
            val original = scope.originalFrameTimestampNs
            queued += { if (currentUi) rendered += original }
        }
        currentUi = false
        queued.removeAt(0).invoke()
        assertEquals(listOf(1_000_000_123L), rendered)
    }

    private class Fixture {
        val trace = mutableListOf<String>()
        var sourceReads = 0
        var clockReads = 0
        var sourceCurrent = true
        var preflight = false
        var preflightCalls = 0
        var failure: Throwable? = null
        var failureKind: String? = null
        var updateError: Throwable? = null
        var afterUpdate: () -> Unit = {}
        var onQuality: () -> Unit = {}
        val marker = Any()
        val epoch = WalkRuntimeEpoch("walk", 7L)
        val frame = FakeFrame()
        val session = object : Session() {
            override fun update(): Frame {
                updateError?.let { throw it }
                afterUpdate(); return frame
            }
            override fun createAnchor(pose: Pose): Anchor = object : Anchor() {
                override fun getPose() = pose
                override fun getTrackingState() = TrackingState.TRACKING
                override fun detach() {}
            }
            override fun <T : Trackable> getAllTrackables(type: Class<T>): Collection<T> = emptyList()
        }
        val provider = ArCoreFrameProvider(session)
        val lock = Any()
        val recognition = ArFrameRecognitionCapture(lock, object : FrameRecognitionAuthority {
            override fun obstacleFeatureEnabled() = true
            override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch) = true
            override fun detectorAvailable() = true
            override fun geometryVersion() = 3L
            override fun unknownService(): FastSamRuntimeService<UnknownRuntimeAttachment>? = null
            override fun cameraQualityObservation(epoch: WalkRuntimeEpoch, observedAtElapsedRealtimeMs: Long,
                frameAvailable: Boolean, width: Int, height: Int, yPlaneBuffer: ByteBuffer?,
                yPlaneRowStride: Int?, yPlanePixelStride: Int?): CameraFrameQualityObservation {
                assertEquals(0, frame.rgb.closes)
                trace += "quality"; onQuality()
                return CameraFrameQualityObservation(epoch, observedAtElapsedRealtimeMs, frameAvailable, .5, 0.0, null, null)
            }
        }, false, { clockReads++; 100_000L }, { error("Optional diagnostic is disabled") })
        val controller = ArFrameRenderController(lock, object : ArFrameRenderSource<Any, Boolean> {
            override fun foreground(): Boolean { sourceReads++; return true }
            override fun frameGeneration(): Int { assertTrue(Thread.holdsLock(lock)); return 4 }
            override fun acquireLease() = FrameArResources(marker, session, provider, preflight, 5L)
            override fun bindTextureIfReady() {}
            override fun textureBound() = true
            override fun leaseIsCurrent(lease: Any): Boolean { assertSame(marker, lease); return sourceCurrent }
            override fun currentEpoch() = epoch
            override fun isPreflight(purpose: Boolean) = purpose
            override fun isRuntime(purpose: Boolean) = !purpose
        }, object : ArFrameRenderDemand {
            override fun normalDetectionDue(nowElapsedRealtimeMs: Long): Boolean {
                assertTrue(Thread.holdsLock(lock)); return false
            }
            override fun unknownCaptureDue(nowElapsedRealtimeMs: Long) = false
            override fun freshVisualSource(nowElapsedRealtimeMs: Long) = false
        }, object : ArFrameGraphics {
            override fun clear() { trace += "clear" }
            override fun drawBackground(frame: Frame) { assertSame(this@Fixture.frame, frame); trace += "draw" }
        }, recognition, object : ArFrameClock {
            override fun elapsedRealtimeMs(): Long { clockReads++; return 100_000L }
            override fun wallClockMs(): Long { clockReads++; return 1_000L }
        }, object : ArFrameRenderObserver<Any, Boolean> {
            override fun onRenderCallback() { trace += "heartbeat" }
            override fun geometryChanged(lease: Any, frameGeneration: Int, epoch: WalkRuntimeEpoch) { error("Unchanged geometry") }
            override fun updateFailed(status: String, detail: String, purpose: Boolean, arGeneration: Long,
                epoch: WalkRuntimeEpoch, diagnosticKind: String, error: Throwable) {
                assertEquals(5L, arGeneration); assertEquals(this@Fixture.epoch, epoch)
                failure = error; failureKind = diagnosticKind
            }
            override fun diagnoseRuntimeCapture(capture: ScopedArFrameCapture<Any, Boolean>) { trace += "diagnostic" }
            override fun processPreflight(capture: ScopedArFrameCapture<Any, Boolean>) {
                assertNull(capture.recognition)
                preflightCalls++
            }
        })
        fun draw(consumer: (ScopedArFrameCapture<Any, Boolean>) -> Unit) = controller.draw { trace += "runtime"; consumer(it) }
    }

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
    private class FakeFrame : Frame() {
        var rgb = FramePipelineTestImage(900_000_000L)
        val raw = CameraTimestampTestImage(900_000_000L, 2)
        val confidence = CameraTimestampTestImage(900_000_000L, 1)
        val cameraValue = FakeCamera()
        var timeNs = 1_000_000_000L
        var cameraAcquisitions = 0
        var depthAcquisitions = 0
        override fun getTimestamp() = timeNs
        override fun getCamera(): Camera = cameraValue
        override fun hasDisplayGeometryChanged() = false
        override fun acquireCameraImage(): Image { cameraAcquisitions++; return rgb }
        override fun acquireRawDepthImage16Bits(): Image { depthAcquisitions++; return raw }
        override fun acquireRawDepthConfidenceImage(): Image = confidence
        override fun acquireDepthImage16Bits(): Image { error("Full depth unavailable") }
        override fun transformCoordinates2d(input: Coordinates2d, values: FloatArray,
            output: Coordinates2d, destination: FloatArray) {
            for (i in values.indices) destination[i] = when {
                input == Coordinates2d.IMAGE_NORMALIZED && output == Coordinates2d.VIEW -> values[i] * 2f
                output == Coordinates2d.TEXTURE_NORMALIZED -> values[i] / 2f
                else -> values[i]
            }
        }
    }
}
