package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import android.media.Image
import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.feedback.delivery.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackFrameCapture
import kr.co.hanium.dreamup.walksafe.runtime.camera.CameraFallbackImageAccess
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class PrimaryCameraFallbackWorkerBodyTest {
    @Test fun featureGuardPrecedesTheClockLeaseAndImageInspectionButStillClosesTheSource() {
        val f = Fixture(); f.feature = false
        f.run()
        assertEquals(listOf("feature", "close"), f.events)
        assertEquals(0, f.clockReads)
        assertEquals(1, f.image.closes)
        assertEquals(0, f.detector.calls)
    }

    @Test fun staleCaptureDoesNotAdmitPhysicalWorkOrInspectLuma() {
        val f = Fixture(); f.captureCurrent = false
        f.run()
        assertEquals(listOf("feature", "elapsed", "capture", "close"), f.events)
        assertFalse(f.owner.projection().workInFlight)
        assertFalse(f.events.contains("luma"))
    }

    @Test fun noImageRetainsOriginalLoadAdmissionAndClosesBeforeTicketCancellation() {
        val f = Fixture(); f.image.imagePresent = false
        f.run()
        assertTrue(f.events.indexOf("load") < f.events.indexOf("has-image"))
        assertTrue(f.events.indexOf("close") < f.events.indexOf("finish"))
        assertEquals(0, f.detector.calls)
        assertFalse(f.owner.projection().workInFlight)
        assertEquals(listOf(false), f.completions)
    }

    @Test fun nullableOfficialObservationPreservesItsEarlyReturnAndCannotCopyOrInvoke() {
        val f = Fixture(); f.lumaObserved = null
        f.run()
        assertTrue(f.events.contains("luma"))
        assertFalse(f.events.contains("outputs"))
        assertFalse(f.events.contains("copy"))
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun deniedCameraEnvironmentAfterLumaDoesNotReadApprovedProfileOrCopyPixels() {
        val f = Fixture(); f.outputs = false
        f.run()
        assertFalse(f.events.contains("profile"))
        assertFalse(f.events.contains("copy"))
        assertEquals(0, f.detector.calls)
    }

    @Test fun sourceImageClosesBeforeSameOwnedPixelsTimestampAndRotationReachTheActualDetector() {
        val f = Fixture(); f.run()
        assertEquals(1, f.detector.calls)
        assertTrue(f.detector.closedBeforeInvoke)
        assertSame(f.image.pixels, f.detector.pixels)
        assertEquals(91L, f.detector.timestamp)
        assertEquals(3, f.detector.rotation)
        assertEquals(1, f.image.closes)
        assertEquals(listOf("analyzed", "metadata", "sample"), f.events.filter {
            it in setOf("analyzed", "metadata", "sample")
        })
        assertEquals(2, f.completionGuards)
        assertFalse(f.owner.projection().workInFlight)
        assertEquals(listOf(true), f.completions)
    }

    @Test fun unavailableResultIsNotAnEmptyCompletedSceneAndProducesNoMetadataOrAdvisory() {
        val f = Fixture(); f.detector.result = AndroidDetectionResult.unavailable()
        f.run()
        assertTrue(f.metadata.isEmpty())
        assertFalse(f.events.contains("sample"))
        assertTrue(f.mainTasks.isEmpty())
        assertEquals(1, f.image.closes)
        assertEquals(listOf(false), f.completions)
    }

    @Test fun failedNativeCallRetainsFailureIdentityClosesTheSourceAndFinishesInvokedWork() {
        val f = Fixture(); val failure = IllegalStateException("native")
        f.detector.failure = failure; f.run()
        assertEquals(listOf(failure), f.failures)
        assertEquals(1, f.image.closes)
        assertFalse(f.owner.projection().workInFlight)
        assertTrue(f.metadata.isEmpty())
    }

    @Test fun staleBeforeCompletionEffectsCannotRecordAnAnalyzedFrameOrEvaluateTheAdvisory() {
        val f = Fixture(); f.firstCompletionCurrent = false
        f.run()
        assertTrue(f.metadata.isEmpty())
        assertFalse(f.events.contains("gate"))
        assertEquals(1, f.completionGuards)
    }

    @Test fun staleAfterAdvisoryEvaluationResetsTheSamePolicyAndDoesNotQueueItsAction() {
        val f = Fixture(); f.secondCompletionCurrent = false
        f.run()
        assertTrue(f.mainTasks.isEmpty())
        assertEquals(2, f.completionGuards)
        // The policy was reset: even an earlier frame id can now be accepted afresh.
        assertNotNull(f.policy.evaluate(f.detector.result.detections, f.gate, 1L, 100L))
    }

    @Test fun validNonMetricAdvisoryQueuesTheOriginalEpochGenerationAndDeliveryOwner() {
        val f = Fixture(); f.run()
        assertEquals(1, f.mainTasks.size)
        assertTrue(f.presented.isEmpty())
        f.mainTasks.removeFirst().invoke()
        assertEquals(listOf(Triple(f.epoch, 4, 9)), f.presented)
    }

    @Test fun originalTimingFallbackReadsAnExtraClockOnlyWhenReportedInferenceTimingIsAbsent() {
        val withTiming = Fixture(); withTiming.run()
        val absent = Fixture(); absent.detector.result = absent.detector.result.copy(timing = AndroidDetectorTiming())
        absent.run()
        assertEquals(withTiming.clockReads + 1, absent.clockReads)
        assertEquals(20L, withTiming.metadata.single().inferenceDurationMs)
        assertEquals(30L, absent.metadata.single().inferenceDurationMs)
    }

    @Test fun finalSourceCloseFailureStillRunsTheOriginalPhysicalCompletionFinally() {
        val f = Fixture(); f.image.imagePresent = false
        val failure = IllegalStateException("image close"); f.image.closeFailure = failure
        assertSame(failure, runCatching { f.run() }.exceptionOrNull())
        assertTrue(f.events.contains("finish"))
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun freshImuUpdatedDuringNativeWorkUsesTheLiveGateClockAndRetainsCaptureEvidenceTime() {
        val f = Fixture(); f.runWithNativeImuUpdate(1_590L)
        assertEquals(1, f.detector.calls)
        assertEquals(listOf(1_596L), f.gateTimes)
        assertEquals(listOf(1_000L), f.sampleObservedAtTimes)
        assertEquals(1_000L, f.detector.timestamp)
        assertEquals(596L, f.metadata.single().inferenceDurationMs)
        assertEquals(1, f.mainTasks.size)
        f.mainTasks.removeFirst().invoke()
        assertEquals(listOf(Triple(f.epoch, 4, 9)), f.presented)
        assertEquals(1_000L, f.actions.single().observedAtMs)
        assertEquals(2_500L, f.actions.single().validUntilMs)
    }

    @Test fun theLiveGateRejectsActuallyStaleAndFutureImuAfterNativeWork() {
        for (imuAtMs in listOf(95L, 1_597L)) {
            val f = Fixture(); f.runWithNativeImuUpdate(imuAtMs)
            assertEquals(1, f.detector.calls)
            assertEquals(listOf(1_596L), f.gateTimes)
            assertEquals(listOf(1_000L), f.sampleObservedAtTimes)
            assertEquals(listOf(true), f.completions)
            assertTrue("An IMU timestamp of $imuAtMs must not reserve an advisory", f.mainTasks.isEmpty())
            assertTrue(f.actions.isEmpty())
        }
    }

    @Test fun aFreshReturnTimeGateCannotExtendTheCaptureBoundActionStartDeadline() {
        val f = Fixture(); f.runWithNativeImuUpdate(1_590L)
        f.mainTasks.removeFirst().invoke()
        val action = f.actions.single()
        assertEquals(1_000L, action.observedAtMs)
        assertEquals(2_500L, action.validUntilMs)
        assertTrue(f.policy.isDeliveryCurrent(action))
        f.now = 2_501L
        var speechRequests = 0
        val delivery = CameraNonMetricAdvisoryDeliveryController(f.policy,
            object : CameraAdvisoryDeliveryAuthority {
                override fun newOutputAllowed(nowMs: Long) = true
                override fun ownerCurrent(epoch: WalkRuntimeEpoch, fallbackGeneration: Int,
                    lifecycleGeneration: Int, nowMs: Long) = true
                override fun mountingAllowed() = true
            }, CameraAdvisorySpeech { _, _, _, _, _ -> speechRequests += 1; true },
            { f }, { f.now }, { it() }, { _, _ -> fail("An expired action cannot be delivered") })
        delivery.dispatch(action, f.epoch, 4, 9)
        assertEquals("Return-time freshness cannot renew the capture-bound start TTL", 0, speechRequests)
        assertFalse(f.policy.isDeliveryCurrent(action))
    }

    private class Fixture {
        val epoch = WalkRuntimeEpoch("fallback-body", 1L)
        val events = mutableListOf<String>()
        var now = 100L
        var clockReads = 0
        var feature = true
        var captureCurrent = true
        var outputs = true
        var firstCompletionCurrent = true
        var secondCompletionCurrent = true
        var completionGuards = 0
        val profile = ApprovedCameraFrameQualityProfile("camera-field-approved-r001", 0.4, 0.2, 8.0, 25.0, 65.0, 2_000L)
        val quality = CameraFrameQualityObservation(epoch, 100L, true, 0.4, 0.2, 8.0, 25.0)
        var lumaObserved: CameraFrameQualityObservation? = quality
        val image = SourceImage(this)
        val detector = Detector(this)
        val gate = NonMetricAdvisoryGate(true, true, true, true, false)
        var imuObservedAtMs: Long? = null
        val gateTimes = mutableListOf<Long>()
        val sampleObservedAtTimes = mutableListOf<Long>()
        val actions = mutableListOf<NonMetricObstacleAdvisoryAction>()
        val policy = AndroidNonMetricObstacleAdvisoryPolicy(NonMetricObstacleAdvisoryConfig(minStableFrames = 1, minStableMs = 0L))
        val metadata = mutableListOf<PrimaryCompletedInference>()
        val failures = mutableListOf<RuntimeException>()
        val completions = mutableListOf<Boolean>()
        val mainTasks = ArrayDeque<() -> Unit>()
        val presented = mutableListOf<Triple<WalkRuntimeEpoch, Int, Int>>()
        val owner = PrimaryDetectorExecutionOwner(Any(), Lane(), Lane(), ::elapsed,
            object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) = true
                override fun remove(task: Runnable) = Unit
            }, { false }, { false }, {}, { _, _ -> }, { mainTasks.addLast(it) })
        val supervisor = DetectorRuntimeSupervisor(ownsCurrentWork = { owner.ownsWork(it.ticket) })
        val invocation = PrimaryDetectorInvocationWorker(owner, ::elapsed, { error("fallback wall clock") })
        val worker = PrimaryCameraFallbackDetectorWorker(owner, invocation,
            object : PrimaryFallbackWorkAuthority<Int> {
                override fun obstacleFeatureEnabled(): Boolean { events += "feature"; return feature }
                override fun captureIsCurrent(epoch: WalkRuntimeEpoch, fallbackGeneration: Int): Boolean { events += "capture"; return captureCurrent }
                override fun captureDeliveryOwner(): Int { events += "delivery"; return 9 }
                override fun cameraOutputsAllowed(): Boolean { events += "outputs"; return outputs }
                override fun completionIsCurrent(epoch: WalkRuntimeEpoch, fallbackGeneration: Int, deliveryOwner: Int, detectorGeneration: Int): Boolean {
                    completionGuards += 1; return if (completionGuards == 1) firstCompletionCurrent else secondCompletionCurrent
                }
                override fun advisoryGate(nowElapsedRealtimeMs: Long): NonMetricAdvisoryGate {
                    events += "gate"; gateTimes += nowElapsedRealtimeMs
                    // Model Main's existing 1,500 ms IMU window at the authority input boundary.
                    val imuFresh = imuObservedAtMs?.let { nowElapsedRealtimeMs - it in 0L..1_500L } ?: gate.imuFresh
                    return gate.copy(imuFresh = imuFresh)
                }
                override fun tactileTier(gate: NonMetricAdvisoryGate) = AndroidLocalTactileTier.CAMERA_IMU_NON_METRIC
            }, object : PrimaryFallbackFrameObservation {
                override fun load(timestampNs: Long, observedAtElapsedRealtimeMs: Long) { events += "load" }
                override fun luma(epoch: WalkRuntimeEpoch, observedAtElapsedRealtimeMs: Long, width: Int, height: Int,
                    bytes: ByteBuffer?, rowStride: Int?, pixelStride: Int?): CameraFrameQualityObservation? { events += "luma"; return lumaObserved }
                override fun observe(observation: CameraFrameQualityObservation) = Unit
                override fun approvedProfile(): ApprovedCameraFrameQualityProfile { events += "profile"; return profile }
            }, supervisor, object : PrimaryDetectorTimingEffects {
                override fun observe(epoch: WalkRuntimeEpoch, capturedAtElapsedRealtimeMs: Long,
                    observedAtElapsedRealtimeMs: Long, inferenceLatencyMs: Long) = true
                override fun failure(error: RuntimeException, generation: Int, epoch: WalkRuntimeEpoch) { failures += error }
            }, object : PrimaryFallbackRecording<Int> {
                override fun analyzed(epoch: WalkRuntimeEpoch, fallbackGeneration: Int, deliveryOwner: Int) { events += "analyzed" }
                override fun completed(event: PrimaryCompletedInference) { events += "metadata"; metadata += event }
                override fun sample(result: AndroidDetectionResult, tier: AndroidLocalTactileTier, gate: NonMetricAdvisoryGate,
                    observedAtElapsedRealtimeMs: Long) { events += "sample"; sampleObservedAtTimes += observedAtElapsedRealtimeMs }
            }, policy, PrimaryWorkCompletion { ticket, invoked, duration, valid ->
                events += "finish"; completions += valid
                owner.finishWork(ticket, invoked, duration, valid, { PrimaryCompletionObservation(false, null) }, { _, _ -> })
            }, PrimaryFallbackPresentation { action, epoch, generation, delivery ->
                actions += action; presented += Triple(epoch, generation, delivery)
            },
            { mainTasks.addLast(it) }, ::elapsed)
        init { val load = PrimaryLoadedDetector(detector, true)
            owner.adoptLoadedOnNativeLane(owner.beginLoad(), load) { false }
            owner.admitLoadedOnMain(owner.beginLoad(), load) { false }
        }
        fun elapsed(): Long { events += "elapsed"; clockReads += 1; return now }
        fun run() = worker.analyze(CameraFallbackFrameCapture(image), epoch, 4)
        fun runWithNativeImuUpdate(imuAtMs: Long) {
            now = 1_000L
            image.timestampNs = 1_000_000_111L
            lumaObserved = quality.copy(observedAtElapsedRealtimeMs = now)
            detector.returnedAtMs = 1_596L
            detector.beforeReturn = { imuObservedAtMs = imuAtMs }
            detector.result = detector.result.copy(timing = detector.result.timing.copy(totalMs = 596L))
            run()
        }
    }

    private class SourceImage(private val f: Fixture) : CameraFallbackImageAccess {
        val pixels = pixels()
        var imagePresent = true
        var closes = 0
        var closeFailure: RuntimeException? = null
        override var timestampNs = 91_000_111L
        override val quarterTurns = 3
        override val width = 2
        override val height = 2
        override fun hasImage(): Boolean { f.events += "has-image"; return imagePresent }
        override fun <T> inspectLuma(block: (ByteBuffer?, Int?, Int?) -> T) = block(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 2, 1)
        override fun copyOwnedFrame(): OwnedYuvFrame { f.events += "copy"; return pixels }
        override fun close() { f.events += "close"; closes += 1; closeFailure?.let { throw it } }
    }
    private class Detector(private val f: Fixture) : AndroidFrameDetector {
        var calls = 0
        var closedBeforeInvoke = false
        var pixels: OwnedYuvFrame? = null
        var timestamp = -1L
        var rotation = -1
        var failure: RuntimeException? = null
        var returnedAtMs = 130L
        var beforeReturn: () -> Unit = {}
        var result = AndroidDetectionResult(listOf(kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate(
            "e_scooter_obstruction", 0.86f, kr.co.hanium.dreamup.walksafe.depth.RectNorm(0.2f, 0.42f, 0.24f, 0.48f))),
            timing = AndroidDetectorTiming(totalMs = 20L))
        override fun detectOriented(cameraFrame: OwnedYuvFrame, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult {
            calls += 1; closedBeforeInvoke = f.image.closes == 1; pixels = cameraFrame; timestamp = timestampMs; rotation = quarterTurns
            failure?.let { throw it }; f.now = returnedAtMs; beforeReturn(); return result
        }
        override fun detectOriented(cameraImage: Image, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("live Image")
        override fun detect(cameraImage: Image, timestampMs: Long,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("live Image")
        override fun detect(cameraFrame: OwnedYuvFrame, timestampMs: Long,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("unoriented frame")
    }
    private class Lane : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow() = emptyList<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }
    companion object { private fun pixels() = OwnedYuvFrame.copyOf(2, 2,
        ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), ByteBuffer.wrap(byteArrayOf(5)), ByteBuffer.wrap(byteArrayOf(6)), 2, 1, 1, 1) }
}
