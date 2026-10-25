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
import java.io.File
import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.inference.tracking.GrayTrackingFrame
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Main's actual acquisition/copy/depth boundary. Native Activity/GL/JNI inference is not run. */
class MainActivityRecognitionTimestampSharingBehaviorTest {
    @Test fun successfulRecognitionCopySharesItsCpuTimestampAndClosesOnce() {
        val frame = FakeFrame()
        val result = MainActivity().acquireRuntimeRecognitionFrame(provider(), frame, false) { it.timestamp }
        assertEquals("Depth and recognition must use one owned CPU image", 1, frame.cameraAcquisitions)
        assertEquals(900_000_000L, result.input)
        assertEquals(result.input, result.depthSnapshot.cameraImageTimestampNs)
        assertTrue(result.depthSnapshot.hasFreshMetricRawDepth)
        assertEquals(1, frame.cameraImage.closeCalls)
        assertEquals(0, frame.additionalImage.closeCalls)
    }

    @Test fun failedCopyCannotQualifyDepthOrReacquireACameraImage() {
        val frame = FakeFrame()
        val result = MainActivity().acquireRuntimeRecognitionFrame<Long>(provider(), frame, false) {
            throw IllegalStateException("input copy failed")
        }
        assertNull(result.input)
        assertNull(result.depthSnapshot.cameraImageTimestampNs)
        assertFalse(result.depthSnapshot.hasFreshMetricRawDepth)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test fun missingRecognitionImagePassesNullWithoutTimestampFallback() {
        val frame = FakeFrame().apply { unavailable = true }
        val result = MainActivity().acquireRuntimeRecognitionFrame(provider(), frame, false) { it.timestamp }
        assertNull(result.input)
        assertNull(result.depthSnapshot.cameraImageTimestampNs)
        assertFalse(result.depthSnapshot.hasFreshMetricRawDepth)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(0, frame.cameraImage.closeCalls)
    }

    @Test fun cancellationDuringCopyInvalidatesReceiptWithoutOwningTheImage() {
        val frame = FakeFrame()
        val provider = provider()
        val result = MainActivity().acquireRuntimeRecognitionFrame(provider, frame, false) {
            provider.invalidateRecognitionCameraImageTimestamps()
            it.timestamp
        }
        assertEquals(900_000_000L, result.input)
        assertNull(result.depthSnapshot.cameraImageTimestampNs)
        assertFalse(result.depthSnapshot.hasFreshMetricRawDepth)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test fun frameTimestampChangeDuringCopyDoesNotInventAnotherClock() {
        val frame = FakeFrame()
        val result = MainActivity().acquireRuntimeRecognitionFrame(provider(), frame, false) {
            frame.timeNs += 1L
            it.timestamp
        }
        assertEquals(900_000_000L, result.input)
        assertNull(result.depthSnapshot.cameraImageTimestampNs)
        assertFalse(result.depthSnapshot.hasFreshMetricRawDepth)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test fun depthFailurePreservesSuccessfullyCopiedRgbInput() {
        val frame = FakeFrame().apply { depthUnavailable = true }
        val result = MainActivity().acquireRuntimeRecognitionFrame(provider(), frame, false) { it.timestamp }
        assertEquals(900_000_000L, result.input)
        assertFalse(result.depthSnapshot.hasMetricRawDepth)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test fun namedAndUnknownConsumersReceiveTheExactClosedImagesOwnedPixelsAndGrayClock() {
        val main = MainActivity()
        val frame = FakeFrame()
        var copies = 0
        val result = main.acquireRuntimeRecognitionFrame(provider(), frame, false) { image ->
            copies++
            val y = image.planes.first()
            val owned = OwnedYuvFrame.copyOf(image.width, image.height, y.buffer,
                ByteBuffer.wrap(byteArrayOf(128.toByte())), ByteBuffer.wrap(byteArrayOf(128.toByte())),
                y.rowStride, y.pixelStride, 1, 1)
            val gray = GrayTrackingFrame.copyFromLuma(VisualFrameKey(1, frame.timeNs, image.timestamp, 100, 0),
                image.width, image.height, y.buffer, y.rowStride, y.pixelStride)
            kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRecognitionInput(owned, image.timestamp, gray,
                CameraFrameQualityObservation(WalkRuntimeEpoch("walk", 0), 100, true, 1.0, 0.0, 0.0, 0.0), 0, 0)
        }
        val input = requireNotNull(result.input)
        var namedCalls = 0
        var unknownCalls = 0
        main.consumeRuntimeRecognitionInput(input) { named ->
            namedCalls++
            assertEquals(1, frame.cameraImage.closeCalls)
            assertSame(input.cameraFrame, named)
            assertEquals(4, named.toArgb().pixels.size)
        }
        main.consumeRuntimeRecognitionInput(input) { unknown ->
            unknownCalls++
            assertEquals(1, frame.cameraImage.closeCalls)
            assertSame(input.cameraFrame, unknown)
            assertArrayEquals(input.cameraFrame.toArgb().pixels, unknown.toArgb().pixels)
        }
        assertEquals(1, copies)
        assertEquals(1, namedCalls)
        assertEquals(1, unknownCalls)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
        assertEquals(input.gray!!.key.cameraTimestampNs, result.depthSnapshot.cameraImageTimestampNs)
        assertTrue(main.runtimeRecognitionMatchesDepth(input.cameraTimestampNs, frame.timeNs, result.depthSnapshot))
    }

    @Test fun untrackedRgbCopiesOnceWithoutCpuClockOrDepthAcquisition() {
        for (state in listOf(TrackingState.PAUSED, TrackingState.STOPPED)) {
            val frame = FakeFrame().apply { cameraValue.state = state; cameraImage.failTimestamp = true }
            var copies = 0
            val result = MainActivity().acquireRuntimeRecognitionFrame(provider(), frame, true) {
                copies++
                it.planes.first().buffer.get(0).toInt() and 255
            }
            assertEquals(255, result.input)
            assertEquals(1, copies)
            assertNull(result.depthSnapshot.cameraImageTimestampNs)
            assertFalse(result.depthSnapshot.hasMetricRawDepth)
            assertEquals(0, frame.depthAcquisitions)
            assertEquals(1, frame.cameraAcquisitions)
            assertEquals(1, frame.cameraImage.closeCalls)
        }
    }

    @Test fun strictTimestampFailureDoesNotRetryThroughTheRgbOnlyBranch() {
        val frame = FakeFrame().apply { cameraImage.failTimestamp = true }
        var copies = 0
        val result = MainActivity().acquireRuntimeRecognitionFrame(provider(), frame, false) { copies++; 1 }
        assertNull(result.input)
        assertNull(result.depthSnapshot.cameraImageTimestampNs)
        assertEquals(0, copies)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test fun anotherExactFrameOrProviderEpochDuringCopyCannotQualifyThisInput() {
        val frame = FakeFrame()
        val other = FakeFrame()
        val provider = provider()
        val result = MainActivity().acquireRuntimeRecognitionFrame(provider, frame, false) { image ->
            requireNotNull(provider.acquireRecognitionCameraImageOrNull(other)).image.use { }
            image.timestamp
        }
        assertEquals(900_000_000L, result.input)
        assertNull(result.depthSnapshot.cameraImageTimestampNs)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, other.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
        assertEquals(1, other.cameraImage.closeCalls)
    }

    @Test fun providerCloseDuringCopyKeepsCallerCloseOwnershipAndBlocksDepth() {
        val frame = FakeFrame()
        val provider = provider()
        val result = MainActivity().acquireRuntimeRecognitionFrame(provider, frame, true) {
            provider.close()
            assertEquals(0, frame.cameraImage.closeCalls)
            it.timestamp
        }
        assertEquals(900_000_000L, result.input)
        assertNull(result.depthSnapshot.cameraImageTimestampNs)
        assertEquals(0, frame.depthAcquisitions)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test fun missingOrForeignCpuClockCannotPassRecognitionOrMetricAdmission() {
        val main = MainActivity()
        val frame = FakeFrame()
        val snapshot = main.acquireRuntimeRecognitionFrame(provider(), frame, false) { it.timestamp }.depthSnapshot
        assertTrue(main.runtimeRecognitionHasMetricDepth(900_000_000L, frame.timeNs, snapshot))
        assertTrue(main.runtimeRecognitionMatchesDepth(900_000_000L, frame.timeNs, snapshot))
        assertFalse(main.runtimeRecognitionMatchesDepth(null, frame.timeNs, snapshot))
        assertFalse(main.runtimeRecognitionMatchesDepth(910_000_000L, frame.timeNs, snapshot))
        assertFalse(main.runtimeRecognitionMatchesDepth(900_000_000L, frame.timeNs + 1, snapshot))
        assertTrue(snapshot.hasMetricRawDepth)
        assertFalse(main.runtimeRecognitionHasMetricDepth(900_000_000L, frame.timeNs, snapshot.copy(cameraImageTimestampNs = null)))
        assertFalse(main.runtimeRecognitionHasMetricDepth(900_000_000L, frame.timeNs, snapshot.copy(cameraImageTimestampNs = 910_000_000L)))
    }

    @Test fun validCopiedCpuClockKeepsTheExistingReprojectedRawPolicyAndSampleThresholds() {
        val main = MainActivity()
        val frame = FakeFrame(rawTimestampNs = 800_000_000L)
        val result = main.acquireRuntimeRecognitionFrame(provider(), frame, false) { it.timestamp }
        val snapshot = result.depthSnapshot
        assertEquals(900_000_000L, result.input)
        assertTrue(snapshot.hasMetricRawDepth)
        assertFalse(snapshot.hasFreshMetricRawDepth)
        assertTrue(main.runtimeRecognitionHasMetricDepth(result.input, frame.timeNs, snapshot))
        assertEquals(4, main.runtimeRecognitionMetricSampleCount(result.input, frame.timeNs, snapshot))
        assertFalse(main.runtimeRecognitionHasMetricDepth(null, frame.timeNs, snapshot))
        assertEquals(0, main.runtimeRecognitionMetricSampleCount(null, frame.timeNs, snapshot))
        assertFalse(main.runtimeRecognitionHasMetricDepth(910_000_000L, frame.timeNs, snapshot))
        assertEquals(0, main.runtimeRecognitionMetricSampleCount(910_000_000L, frame.timeNs, snapshot))
        assertEquals(0, main.runtimeRecognitionMetricSampleCount(result.input, frame.timeNs + 1, snapshot))
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test fun rgbDepthGapRequiresBothTheActualCpuObservationAndTheExistingVisualLease() {
        val main = MainActivity()
        val frame = FakeFrame().apply { depthUnavailable = true }
        val result = main.acquireRuntimeRecognitionFrame(provider(), frame, false) { it.timestamp }
        assertNotNull(result.input)
        assertFalse(main.runtimeRecognitionHasMetricDepth(result.input, frame.timeNs, result.depthSnapshot))
        var leaseCalls = 0
        assertTrue(main.runtimeRecognitionAllowsVisualWork(result.input, frame.timeNs, result.depthSnapshot) {
            leaseCalls++; true
        })
        assertFalse(main.runtimeRecognitionAllowsVisualWork(result.input, frame.timeNs, result.depthSnapshot) {
            leaseCalls++; false
        })
        assertEquals(2, leaseCalls)
        for (invalidTimestamp in listOf(null, 0L, 910_000_000L)) {
            assertFalse(main.runtimeRecognitionAllowsVisualWork(invalidTimestamp, frame.timeNs, result.depthSnapshot) {
                leaseCalls++; true
            })
        }
        assertFalse(main.runtimeRecognitionAllowsVisualWork(result.input, frame.timeNs + 1, result.depthSnapshot) {
            leaseCalls++; true
        })
        assertEquals("A valid visual lease cannot invent the missing CPU observation", 2, leaseCalls)
        assertEquals(1, frame.cameraAcquisitions)
        assertEquals(1, frame.cameraImage.closeCalls)
    }

    @Test fun actualGlAndWorkersUseOneCaptureAndKeepConversionAfterAdmission() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        assertTrue(MainActivityRefactorSource.member(source, "onDrawFrame").contains("framePipelineController.draw()"))
        val renderPorts = MainActivityRefactorSource.property(source, "frameRenderController")
        assertTrue(renderPorts.contains("ArFrameRenderController(frameStateLock,"))
        assertTrue(renderPorts.contains("}, frameRecognition, object : ArFrameClock"))
        val pipelinePorts = MainActivityRefactorSource.property(source, "framePipelineController")
        assertTrue(pipelinePorts.contains("FramePipelineController(frameStateLock, frameRenderController, runtime,"))
        val render = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRenderController.kt").readText()
        val gl = MainActivityRefactorSource.member(render, "draw")
        assertEquals(1, Regex("acquireFrameRecognitionCapture\\(").findAll(gl).count())
        assertTrue(gl.contains("recognition.copyFrameRecognitionInput("))
        assertFalse(gl.contains(".toArgb()"))
        val pipeline = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText()
        val qualify = MainActivityRefactorSource.member(pipeline, "qualify")
        assertTrue(qualify.contains("val input = source.recognition?.input?.copy(timestampEvidence = source.recognition.timestampEvidence)"))
        assertTrue(qualify.contains("scheduling.scheduleUnknown(input, native, source.snapshot,"))
        assertTrue(qualify.contains("scheduling.scheduleNamed(input, lease, native,"))
        assertFalse(qualify.contains(".toArgb()"))
        assertTrue(pipelinePorts.contains("scheduleUnknownDetectionIfDue(input, frame, snapshot,"))
        assertTrue(pipelinePorts.contains("scheduleDetectionIfDue(input, lease, frame,"))
        val named = MainActivityRefactorSource.member(source, "scheduleDetectionIfDue")
        val unknown = MainActivityRefactorSource.member(source, "scheduleUnknownDetectionIfDue")
        assertFalse(named.contains("acquireCameraImage"))
        assertFalse(unknown.contains("acquireCameraImage"))
        val submissionSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/AndroidPrimaryArSubmissionBridge.kt").readText()
        val submit = MainActivityRefactorSource.member(submissionSource, "schedule")
        val submissionBinding = MainActivityRefactorSource.property(source, "primaryArSubmissionBridge")
        assertTrue(named.contains("primaryArSubmissionBridge.schedule("))
        assertTrue(named.contains("recognitionInput, arLease, frame, timestampMs, capturedAtMs, elapsedRealtimeMs,"))
        assertTrue(named.contains("capturedDepthSnapshot, frameGeneration, expectedWalkEpoch, expectedArSessionGeneration, visualFrameKey,"))
        assertTrue(submissionBinding.contains("AndroidPrimaryArSubmissionBridge(frameStateLock, frameCaptureRequested,"))
        assertTrue(submissionBinding.contains("{ primaryDetectorFrameSchedulingOwner }, { frameSynthesisOwner }, { frameGeometry },"))
        assertTrue(submissionBinding.contains("frameRouteEvidence, { primaryArDetectorWorker }"))
        assertTrue(submit.contains("PrimaryArDetectionWork(input, arLease,"))
        assertTrue(submit.contains("timestampMs, capturedAtMs, elapsedRealtimeMs,"))
        assertTrue(submit.contains("val targetWorker = worker()"))
        assertTrue(submit.contains("targetWorker.submit(queuedWork)"))
        assertTrue(submit.indexOf("val queuedWork = PrimaryArDetectionWork(") < submit.indexOf("val targetWorker = worker()"))
        assertTrue(submit.indexOf("submittedToWorker = true") < submit.indexOf("targetWorker.submit(queuedWork)"))
        assertFalse(submit.contains("acquireCameraImage"))
        assertFalse(submit.contains(".toArgb()"))
        val workerPorts = MainActivityRefactorSource.member(source, "createPrimaryArDetectorWorker")
        assertTrue(workerPorts.contains("PrimaryArDetectorWorker<ArSessionLease>("))
        assertTrue(workerPorts.contains("primaryDetectorInvocationWorker,"))
        assertTrue(workerPorts.contains("consumeRuntimeRecognitionInput(input) { it }"))
        assertTrue(workerPorts.contains("override fun visualLeaseAllowsWork(lease: ArSessionLease) = currentRuntimeVisualDepthProcessingAllowsWork("))
        val worker = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryArDetectorWorker.kt").readText()
        val run = MainActivityRefactorSource.member(worker, "run")
        assertTrue(run.contains("quality.observe(work.input.qualityObservation)"))
        assertTrue(run.contains("work.timestampMs, work.imageQuarterTurns, work.input.qualityObservation,"))
        assertTrue(run.contains("FrameRecognitionAdmission.allowsVisualWork(work.input.cameraTimestampNs,"))
        assertTrue(run.contains("authority.visualLeaseAllowsWork(work.lease)"))
        assertTrue(run.contains("invocationWorker.invoke(prepared, { consumeOwnedInput(work.input) },"))
        val invocation = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorInvocationWorker.kt").readText()
        val invoke = MainActivityRefactorSource.member(invocation, "invoke")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(invoke,
            "CameraDetectorAdmissionPolicy.admit(", "observation = request.quality", "val ownedFrame = copyOwnedFrame()",
        ))
        assertFalse(run.contains("acquireCameraImage"))
        assertFalse(invoke.contains("acquireCameraImage"))
        val unknownBridgeSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/capture/UnknownFrameCaptureBridge.kt").readText()
        val unknownBridge = MainActivityRefactorSource.member(unknownBridgeSource, "scheduleUnknownDetectionIfDue")
        val unknownBinding = MainActivityRefactorSource.property(source, "unknownFrameCapture")
        assertTrue(unknown.contains("unknownFrameCapture.scheduleUnknownDetectionIfDue("))
        assertTrue(unknownBinding.contains("UnknownFrameCaptureBridge(runtimeMetricStateLock"))
        assertTrue(unknownBinding.contains("runtimeRecognitionAllowsVisualWork(cpuTimestampNs, frameTimestampNs, snapshot, depthAllowsWork)"))
        assertTrue(unknownBinding.contains("currentRuntimeVisualDepthProcessingAllowsWork(lease, frameTimestampNs, tracking)"))
        assertTrue(unknownBinding.contains("{ unknownCaptureCoordinator }, { frameGeometry }, { frameMotionEvidence }"))
        assertTrue(unknownBridge.contains("authority.recognitionAllowsVisualWork(input.cameraTimestampNs, frame.timestamp, snapshot)"))
        assertTrue(unknownBridge.contains("authority.visualDepthAllowsWork("))
        assertTrue(unknownBridge.contains("unknown().scheduleUnknownDetectionIfDue(input.toUnknownCaptureFrameInput()"))
        val unknownCore = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/unknown/UnknownCaptureCoordinator.kt").readText()
        MainActivityRefactorSource.composition(source, "unknownCaptureCoordinator")
        val queuedUnknown = MainActivityRefactorSource.member(unknownCore, "processQueuedUnknownLumaFrame")
        assertTrue(queuedUnknown.contains("val qualityObservation = input.qualityObservation"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(queuedUnknown,
            "CameraDetectorAdmissionPolicy.admit(", "submitUnknownObjectFrame(context, input, submission.evidence,",
        ))
        val submitUnknown = MainActivityRefactorSource.member(unknownCore, "submitUnknownObjectFrame")
        assertTrue(submitUnknown.contains("val rgb = input.cameraFrame.toArgb()"))
        assertTrue(submitUnknown.contains("service.submitArgb(rgb.width, rgb.height, rgb.pixels, token,"))
        assertFalse(queuedUnknown.contains("acquireCameraImage"))
        assertFalse(submitUnknown.contains("acquireCameraImage"))
        assertTrue(MainActivityRefactorSource.member(source, "copyRuntimeRecognitionInput")
            .contains("frameRecognition.copyFrameRecognitionInput(image, frame,"))
        MainActivityRefactorSource.composition(source, "frameRecognition")
        val recognition = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRecognitionCapture.kt").readText()
        val copy = MainActivityRefactorSource.member(recognition, "copyFrameRecognitionInput")
        assertTrue(copy.contains("val ownedFrame = OwnedYuvFrame.copyOf(image)"))
        assertTrue(copy.contains("GrayTrackingFrame.copyFromLuma("))
        assertTrue(copy.indexOf("OwnedYuvFrame.copyOf(image)") in 0 until copy.indexOf("return FrameRecognitionInput("))
        assertTrue(copy.indexOf("GrayTrackingFrame.copyFromLuma(") in 0 until copy.indexOf("return FrameRecognitionInput("))
        assertTrue(MainActivityRefactorSource.member(source, "resetVisualTrackingState")
            .contains("arSessionOwner.observe().provider?.invalidateRecognitionCameraImageTimestamps()"))
        val preflightSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricPreflightCoordinator.kt").readText()
        val metricEvidenceSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/AndroidRuntimeMetricPreflight.kt").readText()
        val metric = MainActivityRefactorSource.member(metricEvidenceSource, "observe")
        val preflight = MainActivityRefactorSource.member(preflightSource, "updateOutputGate")
        assertTrue(MainActivityRefactorSource.member(source, "updateRuntimeMetricOutputGate", occurrence = 0).contains("runtimeMetricPreflight.updateOutputGate(frame, snapshot, observedAtElapsedRealtimeMs, expectedArSessionGeneration, expectedWalkEpoch, acquisitionFailure, recognitionCameraTimestampNs)"))
        assertTrue(MainActivityRefactorSource.property(source, "runtimeMetricPreflight").contains("AndroidRuntimeMetricFrameEvidence(RUNTIME_METRIC_MIN_RAW_CONFIDENCE)"))
        assertTrue(preflight.contains("metric().updateRuntimeMetricFrameEvidence(expectedGeneration, expectedEpoch, acquisitionFailure,"))
        assertTrue(preflight.contains("observeFrameEvidence = { frames.observe(frame, snapshot, observedAtMs, expectedGeneration, recognitionCameraTimestampNs) }"))
        assertTrue(metric.contains("metricDepthAvailable = FrameRecognitionAdmission.hasMetricDepth(recognitionCameraTimestampNs, frame.timestamp, snapshot)"))
        assertTrue(metric.contains("val validSamples = FrameRecognitionAdmission.metricSampleCount(recognitionCameraTimestampNs, frame.timestamp, snapshot)"))
        assertTrue(pipelinePorts.contains("capture.resources.generation, epoch, capture.acquisitionFailure, capture.recognition?.input?.cameraTimestampNs"))
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

    private class FakeFrame(rawTimestampNs: Long = 900_000_000L) : Frame() {
        val cameraImage = CameraTimestampTestImage(900_000_000L, 1)
        val additionalImage = CameraTimestampTestImage(910_000_000L, 1)
        val rawImage = CameraTimestampTestImage(rawTimestampNs, 2)
        val confidenceImage = CameraTimestampTestImage(rawTimestampNs, 1)
        var timeNs = 1_000_000_000L
        val cameraValue = FakeCamera()
        var cameraAcquisitions = 0
        var depthAcquisitions = 0
        var unavailable = false
        var depthUnavailable = false
        override fun getTimestamp() = timeNs
        override fun getCamera(): Camera = cameraValue
        override fun acquireCameraImage(): Image {
            cameraAcquisitions++
            check(!unavailable) { "CPU image unavailable" }
            return if (cameraAcquisitions == 1) cameraImage else additionalImage
        }
        override fun acquireRawDepthImage16Bits(): Image {
            depthAcquisitions++
            check(!depthUnavailable) { "depth unavailable" }
            return rawImage
        }
        override fun acquireRawDepthConfidenceImage(): Image = confidenceImage
        override fun acquireDepthImage16Bits(): Image { depthAcquisitions++; error("full depth unavailable") }
    }
}
