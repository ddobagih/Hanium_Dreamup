package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import android.media.Image
import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.device.ApprovedCameraFrameQualityProfile
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.InterFrameDetectionTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameRecognitionInput
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ReportFrameCaptureFacts
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class PrimaryDetectorWorkerBodiesTest {
    @Test fun arConstructorAndTaskSubmissionDoNotReadClockQualityConsentOrPixelsBeforeTheNativeLane() {
        val f = Fixture()
        assertEquals(0, f.clocks)
        f.worker.submit(f.work())
        assertEquals(0, f.clocks)
        assertEquals(0, f.detector.calls)
        assertTrue(f.events.isEmpty())
        assertEquals(1, f.lane.tasks.size)
    }

    @Test fun arCompletedTaskPublishesExactPartialAndFinalThenRecordsBeforeReleaseAndCompletion() {
        val f = Fixture()
        val work = f.work()
        f.worker.submit(work)
        f.lane.runNext()
        assertEquals(2, f.publications.size)
        val partial = f.publications.first()
        val complete = f.publications.last()
        assertTrue(partial.result.partial)
        assertNotNull(partial.nativeFlight)
        assertNull(partial.visualSourceLease)
        assertSame(work.visualSourceLease, complete.visualSourceLease)
        assertSame(work.frameEvidence, complete.frameEvidence)
        assertSame(work.identity, complete.identity)
        assertSame(work.input.reportCaptureFacts, work.reportCaptureFacts)
        assertSame(work.reportCaptureFacts, partial.reportCaptureFacts)
        assertSame(work.reportCaptureFacts, complete.reportCaptureFacts)
        assertSame(work.input.cameraFrame, f.detector.pixels)
        assertEquals(work.timestampMs, f.detector.timestamp)
        assertEquals(work.imageQuarterTurns, f.detector.rotation)
        assertSame(f.reportBytes, complete.reportImage)
        assertEquals(20L, f.metadata.single().inferenceDurationMs)
        assertEquals(listOf("named", "metadata", "original", "release", "finish"), f.events.filter {
            it in setOf("named", "metadata", "original", "release", "finish")
        })
        assertEquals(1, f.mainTasks.size)
        assertFalse(f.owner.projection().workInFlight)
        assertEquals(listOf(true), f.validCompletions)
        f.mainTasks.removeFirst().invoke()
        assertTrue(f.events.contains("button"))
    }

    @Test fun staleQueuedTaskReturnsBeforeQualityPrivacyClocksAndAlwaysReleasesItsOwnSource() {
        val f = Fixture()
        f.worker.submit(f.work())
        f.current = false
        f.lane.runNext()
        assertEquals(listOf("current", "release", "finish"), f.events)
        assertEquals(0, f.clocks)
        assertEquals(0, f.detector.calls)
        assertTrue(f.publications.isEmpty())
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun missingOrForeignReportFactsDoNotBlockDetectionOrAcquireReplacementFacts() {
        for (variant in 0..2) {
            val f = Fixture()
            val facts = when (variant) {
                0 -> null
                1 -> f.facts.copy(capturedAtElapsedRealtimeMs = 99L)
                else -> f.facts.copy(walkEpoch = WalkRuntimeEpoch("foreign", 1L))
            }
            val work = f.work().copy(input = f.input.copy(reportCaptureFacts = facts))
            assertNull(work.reportCaptureFacts)
            f.worker.submit(work); f.lane.runNext()
            assertEquals(2, f.publications.size)
            assertTrue(f.publications.all { it.reportCaptureFacts == null })
            assertEquals(1, f.detector.calls)
            assertEquals(listOf(true), f.validCompletions)
        }
    }

    @Test fun foreignCpuDepthPairSkipsMetricLeaseAndAllPrivacyWork() {
        val f = Fixture()
        val work = f.work().copy(depthSnapshot = f.depth.copy(cameraImageTimestampNs = 91_000_112L))
        f.worker.submit(work); f.lane.runNext()
        assertTrue(f.events.contains("quality"))
        assertFalse(f.events.contains("visual"))
        assertFalse(f.events.contains("raw"))
        assertEquals(0, f.detector.calls)
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun deniedVisualLeaseObservesQualityButDoesNotQueryConsentOrCopyPixels() {
        val f = Fixture()
        f.visualAllowed = false
        f.worker.submit(f.work()); f.lane.runNext()
        assertEquals(listOf("current", "quality", "visual", "release", "finish"), f.events)
        assertEquals(0, f.clocks)
    }

    @Test fun debugCaptureGuardIsLazyAndReportCodecStillRunsForItsOwnGrantedConsent() {
        val f = Fixture()
        f.rawAllowed = false
        f.worker.submit(f.work()); f.lane.runNext()
        assertTrue(f.events.contains("report-codec"))
        assertFalse(f.events.contains("debug-authority"))
        assertFalse(f.events.contains("debug-codec"))
        assertFalse(f.events.contains("original"))
        assertEquals(1, f.metadata.size)
    }

    @Test fun rejectedCurrentCameraQualityNeverCopiesForInferenceAndCancelsWithoutAnInvocation() {
        val f = Fixture()
        val work = f.work().copy(input = f.input.copy(qualityObservation = f.quality.copy(occludedFraction = 0.21)))
        f.worker.submit(work); f.lane.runNext()
        assertEquals(0, f.detector.calls)
        assertFalse(f.events.contains("consume"))
        assertTrue(f.publications.isEmpty())
        assertEquals(listOf(false), f.validCompletions)
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun unavailableResultDoesNotPublishACompletedEmptySceneOrRecordACapture() {
        val f = Fixture()
        f.detector.result = AndroidDetectionResult.unavailable()
        f.detector.partial = false
        f.worker.submit(f.work()); f.lane.runNext()
        assertTrue(f.publications.isEmpty())
        assertTrue(f.metadata.isEmpty())
        assertEquals(listOf(false), f.validCompletions)
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun failedNativeCallDeliversTheOriginalFailureAndCannotPublishEmptySuccess() {
        val f = Fixture()
        val failure = IllegalStateException("native invoke")
        f.detector.failure = failure
        f.worker.submit(f.work()); f.lane.runNext()
        assertEquals(listOf(failure), f.failures)
        assertTrue(f.publications.isEmpty())
        assertTrue(f.events.contains("release"))
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun falseTimingReceiptSkipsBothPublicationsAndCompletedCaptureEffects() {
        val f = Fixture()
        f.timingAllowed = false
        f.worker.submit(f.work()); f.lane.runNext()
        assertTrue(f.publications.isEmpty())
        assertTrue(f.metadata.isEmpty())
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun nullFinalPublicationDoesNotRecordNamedRawOriginalOrQueueUi() {
        val f = Fixture()
        f.acceptFinal = false
        f.worker.submit(f.work()); f.lane.runNext()
        assertEquals(2, f.publications.size)
        assertTrue(f.metadata.isEmpty())
        assertFalse(f.events.contains("named"))
        assertFalse(f.events.contains("original"))
        assertTrue(f.mainTasks.isEmpty())
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun queuedCaptureButtonUsesCurrentAuthorityAtDeliveryInsteadOfItsEarlierPublication() {
        val f = Fixture()
        f.worker.submit(f.work()); f.lane.runNext()
        f.current = false
        f.mainTasks.removeFirst().invoke()
        assertFalse(f.events.contains("button"))
    }

    @Test fun rejectedExecutorReleasesTheExactSourceAndCancelsThePhysicalTicketWithoutClockReads() {
        val f = Fixture()
        f.lane.reject = true
        val work = f.work()
        f.worker.submit(work)
        assertSame(work.visualSourceLease, f.released.single())
        assertEquals(listOf("release", "finish"), f.events)
        assertEquals(0, f.clocks)
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun postPublicationRecordingExceptionStillReleasesSourceAndFinishesInvokedWork() {
        val f = Fixture()
        val failure = IllegalStateException("record")
        f.recordFailure = failure
        f.worker.submit(f.work())
        assertSame(failure, runCatching { f.lane.runNext() }.exceptionOrNull())
        assertTrue(f.events.contains("release"))
        assertTrue(f.events.contains("finish"))
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun originalUploadEffectRunsOnlyWhileHoldingTheOriginalSharedFrameLock() {
        val f = Fixture()
        f.worker.submit(f.work()); f.lane.runNext()
        assertEquals(1, f.uploads.size)
        assertSame(f.reportBytes, f.uploads.single().image)
        assertTrue(f.uploadLockObserved)
    }

    private class Fixture {
        val lock = Any()
        val lane = Lane()
        val events = mutableListOf<String>()
        val mainTasks = ArrayDeque<() -> Unit>()
        val epoch = WalkRuntimeEpoch("ar-body", 1L)
        var now = 100L
        var clocks = 0
        var current = true
        var visualAllowed = true
        var rawAllowed = true
        var timingAllowed = true
        var acceptFinal = true
        var recordFailure: RuntimeException? = null
        var uploadLockObserved = false
        val quality = CameraFrameQualityObservation(epoch, 100L, true, 0.4, 0.2, 8.0, 25.0)
        val profile = ApprovedCameraFrameQualityProfile("camera-field-approved-r001", 0.4, 0.2, 8.0, 25.0, 65.0, 2_000L)
        val reportBytes = byteArrayOf(3, 4)
        val depth = DepthFrameSnapshot(91_000_000L, null, null, null, cameraImageTimestampNs = 91_000_111L)
        val facts = ReportFrameCaptureFacts(100L, TrustedLocation(37.0, 127.0, 3f, 95L),
            "capture-actor", 7L, epoch)
        val input = FrameRecognitionInput(pixels(), 91_000_111L, null, quality, 3, 0L,
            reportCaptureFacts = facts)
        val key = VisualFrameKey(1L, 91_000_000L, 91_000_111L, 100L, 0L)
        val sourceLease = InterFrameDetectionTracker.PendingSourceLease(key)
        val publications = mutableListOf<PrimarySnapshotPublication>()
        val metadata = mutableListOf<PrimaryCompletedInference>()
        val uploads = mutableListOf<PrimaryOriginalCapture>()
        val failures = mutableListOf<RuntimeException>()
        val released = mutableListOf<InterFrameDetectionTracker.PendingSourceLease>()
        val validCompletions = mutableListOf<Boolean>()
        val owner = PrimaryDetectorExecutionOwner(lock, lane, Lane(), ::elapsed,
            object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) = true
                override fun remove(task: Runnable) = Unit
            }, { false }, { false }, {}, { _, _ -> }, { mainTasks.addLast(it) })
        val detector = Detector(this)
        val invocation = PrimaryDetectorInvocationWorker(owner, ::elapsed, ::wall)
        val supervisor = DetectorRuntimeSupervisor(ownsCurrentWork = {
            current && it.detectorGeneration == owner.projection().frameGeneration && owner.ownsWork(it.ticket)
        })
        val worker = PrimaryArDetectorWorker(lock, owner, invocation,
            object : PrimaryArWorkAuthority<Any> {
                override fun frameIsCurrent(generation: Int, epoch: WalkRuntimeEpoch): Boolean { events += "current"; return current }
                override fun visualLeaseAllowsWork(lease: Any): Boolean { events += "visual"; return visualAllowed }
            }, object : PrimaryCameraWorkQuality {
                override fun observe(observation: CameraFrameQualityObservation) { events += "quality" }
                override fun approvedProfile() = profile
            }, object : PrimaryCaptureImages {
                override fun rawCollectionAllowed(): Boolean { events += "raw"; return rawAllowed }
                override fun reportCollectionGranted() = true
                override fun sensitiveDebugTransferAllowed(): Boolean { events += "debug-authority"; return true }
                override fun encodeReport(frame: OwnedYuvFrame): ByteArray { events += "report-codec"; return reportBytes }
                override fun encodeDebug(frame: OwnedYuvFrame): ByteArray? { events += "debug-codec"; return null }
            }, supervisor, object : PrimaryDetectorTimingEffects {
                override fun observe(epoch: WalkRuntimeEpoch, capturedAtElapsedRealtimeMs: Long,
                    observedAtElapsedRealtimeMs: Long, inferenceLatencyMs: Long) = timingAllowed
                override fun failure(error: RuntimeException, generation: Int, epoch: WalkRuntimeEpoch) { failures += error }
            }, PrimarySnapshotPublisher { event ->
                publications += event
                if (!event.result.partial && !acceptFinal) null else DetectionSnapshot.published(event.result,
                    event.identity, event.capturedAtWallTimeMs, event.startedAtWallTimeMs,
                    event.completedAtWallTimeMs, event.imageWidth, event.imageHeight, event.reportImage,
                    event.frameEvidence, event.capturedAtElapsedRealtimeMs, event.reportCaptureFacts)
            }, object : PrimaryInferenceRecording {
                override fun namedCapture(input: FrameRecognitionInput, published: DetectionSnapshot, result: AndroidDetectionResult) {
                    events += "named"; recordFailure?.let { throw it }
                }
                override fun completed(event: PrimaryCompletedInference) { events += "metadata"; metadata += event }
                override fun originalCapture(event: PrimaryOriginalCapture) { uploadLockObserved = Thread.holdsLock(lock); events += "original"; uploads += event }
            }, PrimaryVisualSourceRelease { lease -> assertTrue(Thread.holdsLock(lock)); released += lease; events += "release" },
            PrimaryWorkCompletion { ticket, invoked, duration, valid ->
                events += "finish"; validCompletions += valid
                owner.finishWork(ticket, invoked, duration, valid, { PrimaryCompletionObservation(false, null) }, { _, _ -> })
            }, PrimaryCapturePresentation { events += "button" }, { mainTasks.addLast(it) }, ::elapsed, ::wall,
            { events += "consume"; it.cameraFrame })
        init { val load = PrimaryLoadedDetector(detector, true)
            owner.adoptLoadedOnNativeLane(owner.beginLoad(), load) { false }
            owner.admitLoadedOnMain(owner.beginLoad(), load) { false }
        }
        fun elapsed(): Long { clocks += 1; return now }
        fun wall(): Long { clocks += 1; return 1_000L }
        fun work() = PrimaryArDetectionWork(input, Any(), 0, epoch, requireNotNull(owner.tryStartWork(100L)),
            true, 91L, 950L, 100L, 2, 2, 3, AndroidDetectionSnapshotFrameIdentity(91_000_000L, 91L),
            DetectionFrameEvidence(91_000_000L, 91L, depth, null,
                TactileProjectionContext(91_000_000L, null, null, null, null, null, null, null, 100L),
                MotionContext(), false, false, key, 3, 0L), depth, sourceLease)
    }

    private class Detector(private val f: Fixture) : AndroidFrameDetector {
        var calls = 0
        var pixels: OwnedYuvFrame? = null
        var timestamp = -1L
        var rotation = -1
        var partial = true
        var result = AndroidDetectionResult(emptyList(), timing = AndroidDetectorTiming(totalMs = 20L))
        var failure: RuntimeException? = null
        override fun detectOriented(cameraFrame: OwnedYuvFrame, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult {
            calls += 1; pixels = cameraFrame; timestamp = timestampMs; rotation = quarterTurns
            failure?.let { throw it }
            f.now = 110L
            if (partial) onPartialResult(AndroidDetectionResult(emptyList(), partial = true))
            f.now = 130L
            return result
        }
        override fun detectOriented(cameraImage: Image, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("live Image")
        override fun detect(cameraImage: Image, timestampMs: Long,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("live Image")
        override fun detect(cameraFrame: OwnedYuvFrame, timestampMs: Long,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("unoriented frame")
    }
    private class Lane : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        var reject = false
        override fun execute(command: Runnable) { if (reject) throw RejectedExecutionException(); tasks += command }
        fun runNext() = tasks.removeFirst().run()
        override fun shutdown() = Unit
        override fun shutdownNow() = emptyList<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }
    companion object { private fun pixels() = OwnedYuvFrame.copyOf(2, 2,
        ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), ByteBuffer.wrap(byteArrayOf(5)), ByteBuffer.wrap(byteArrayOf(6)), 2, 1, 1, 1) }
}
