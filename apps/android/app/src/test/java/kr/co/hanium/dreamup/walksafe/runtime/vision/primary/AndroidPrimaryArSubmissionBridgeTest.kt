package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import android.media.Image
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class AndroidPrimaryArSubmissionBridgeTest {
    @Test fun constructionReadsNoSdkAuthorityLazyPeerOrClockAndStoresNoBorrowedFrame() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.clocks)
        assertTrue(f.capture.get())
        assertFalse(AndroidPrimaryArSubmissionBridge::class.java.declaredFields.any { Frame::class.java.isAssignableFrom(it.type) })
        assertFalse(PrimaryArDetectionWork::class.java.declaredFields.any { Frame::class.java.isAssignableFrom(it.type) })
    }

    @Test fun disabledFeatureAndNullInputCannotTouchTimestampSchedulingOrCaptureFlag() {
        val f = Fixture(); f.feature = false; f.submit()
        assertEquals(listOf("feature"), f.events)
        f.events.clear(); f.feature = true; f.submit(input = null)
        assertEquals(listOf("feature"), f.events)
        assertTrue(f.capture.get())
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun foreignCpuOrFrameDepthPairStopsBeforeAnyAdmissionOrFlagConsumption() {
        for (depth in listOf(Fixture().depth.copy(cameraImageTimestampNs = 3L), Fixture().depth.copy(frameTimestampNs = 4L))) {
            val f = Fixture(); f.submit(depth = depth)
            assertEquals(listOf("feature", "sdk_timestamp"), f.events)
            assertTrue(f.capture.get()); assertFalse(f.owner.projection().workInFlight)
        }
    }

    @Test fun admissionRetirementKeepsCaptureRequestAndDoesNotFreezeEvidence() {
        val f = Fixture(); f.current = false; f.submit()
        assertEquals(listOf("feature", "sdk_timestamp", "scheduling", "owns"), f.events)
        assertTrue(f.capture.get()); assertEquals(0, f.bridgeFinishes)
    }

    @Test fun geometryMismatchCommitsWatermarkBeforeRollbackAndCannotRetryTheSameCapture() {
        val f = Fixture(); f.frames.advanceGeometry(); f.submit()
        assertFalse(f.capture.get()); assertEquals(1, f.bridgeFinishes)
        assertFalse(f.events.contains("mapper")); assertFalse(f.events.contains("worker"))
        assertFalse(f.owner.projection().workInFlight)
        f.events.clear(); f.submit(input = f.input.copy(geometryVersion = 1L))
        assertEquals(1, f.bridgeFinishes)
        assertFalse(f.owner.projection().workInFlight)
        assertFalse(f.events.contains("geometry"))
    }

    @Test fun retirementBetweenAdmissionAndCommitFinishesOnlyTheIssuedTicket() {
        val f = Fixture(); f.retireAtOwns = 2; f.submit()
        assertFalse(f.capture.get()); assertEquals(1, f.bridgeFinishes)
        assertFalse(f.events.contains("geometry")); assertTrue(f.lane.tasks.isEmpty())
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun evidenceFailurePropagatesAndCancelsUnsubmittedTicketWithoutWorkerOrLease() {
        val f = Fixture(); val failure = IllegalStateException("evidence"); f.evidenceFailure = failure
        assertSame(failure, runCatching { f.submit() }.exceptionOrNull())
        assertEquals(1, f.bridgeFinishes); assertTrue(f.released.isEmpty())
        assertFalse(f.events.contains("worker")); assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun unavailableSourceReservationRunsRollbackAfterEvidenceButNeverLooksUpWorker() {
        val f = Fixture(); f.tracker.clear(); f.submit()
        assertTrue(f.events.indexOf("tactile") < f.events.indexOf("motion"))
        assertTrue(f.events.indexOf("motion") < f.events.indexOf("navigation"))
        assertTrue(f.events.indexOf("navigation") < f.events.indexOf("tmap"))
        assertEquals(1, f.bridgeFinishes); assertFalse(f.events.contains("worker"))
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun geometryChangedDuringEvidenceCannotReserveOrTransferTheSource() {
        val f = Fixture(); f.advanceDuringEvidence = true; f.submit()
        assertTrue(f.events.contains("tmap")); assertEquals(1, f.bridgeFinishes)
        assertFalse(f.events.contains("worker")); assertFalse(f.owner.projection().workInFlight)
        assertNotNull(f.frames.reservePendingSourceFrame(f.key))
    }

    @Test fun workerLookupFailureReleasesExactReservationBeforeFinishingAndPropagates() {
        val f = Fixture(); val failure = IllegalStateException("worker getter"); f.workerFailure = failure
        assertSame(failure, runCatching { f.submit() }.exceptionOrNull())
        assertEquals(1, f.bridgeFinishes); assertEquals(0, f.workerFinishes)
        assertFalse(f.owner.projection().workInFlight)
        // A stranded actual tracker reservation would make this second reservation fail.
        assertNotNull(f.frames.reservePendingSourceFrame(f.key))
    }

    @Test fun workerLookupRejectionIsSwallowedButKeepsBridgeRollbackOwnership() {
        val f = Fixture(); f.workerFailure = RejectedExecutionException("lookup"); f.submit()
        assertEquals(1, f.bridgeFinishes); assertEquals(0, f.workerFinishes)
        assertNotNull(f.frames.reservePendingSourceFrame(f.key))
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun executorRejectionBelongsToWorkerAndCannotDoubleFinishOrStrandSource() {
        val f = Fixture(); f.lane.reject = true; f.submit()
        assertEquals(0, f.bridgeFinishes); assertEquals(1, f.workerFinishes)
        assertEquals(1, f.released.size); assertEquals(listOf("release", "worker_finish"), f.events.takeLast(2))
        assertSame(f.key, f.released.single().sourceKey)
        assertNotNull(f.frames.reservePendingSourceFrame(f.key))
        assertFalse(f.owner.projection().workInFlight); assertEquals(0, f.clocks)
    }

    @Test fun retiredQueuedWorkDoesNoNativeQueryAndReleasesExactlyOnce() {
        val f = Fixture(); f.submit(); val timestampReads = f.frame.reads
        f.current = false; f.events.clear(); f.lane.runNext()
        assertEquals(listOf("worker_current", "release", "worker_finish"), f.events)
        assertEquals(timestampReads, f.frame.reads); assertEquals(0, f.clocks)
        assertEquals(0, f.detector.calls); assertEquals(1, f.workerFinishes)
        assertNotNull(f.frames.reservePendingSourceFrame(f.key))
    }

    @Test fun successfulSubmissionFreezesAtOriginalPointAndWorkerKeepsExactCaptureAndEpoch() {
        val f = Fixture(); f.submit()
        assertEquals(0, f.clocks); assertEquals(0, f.detector.calls)
        assertEquals(0, f.bridgeFinishes); assertTrue(f.owner.projection().workInFlight)
        assertTrue(f.events.indexOf("mapper") < f.events.indexOf("tactile"))
        assertTrue(f.events.indexOf("tmap") < f.events.indexOf("worker"))
        val frameReads = f.frame.reads
        f.frame.timeNs = 999L; f.lane.runNext()
        assertEquals(frameReads, f.frame.reads)
        assertEquals(2, f.publications.size)
        val partial = f.publications.first(); val complete = f.publications.last()
        assertTrue(partial.result.partial); assertNull(partial.visualSourceLease)
        assertFalse(complete.result.partial); assertSame(f.released.single(), complete.visualSourceLease)
        assertSame(f.depth, complete.depthEvidence())
        assertSame(f.key, complete.frameEvidence.visualFrameKey)
        assertEquals(91_000_000L, complete.identity.captureFrameId)
        assertEquals(91L, complete.identity.frameTimestampMs)
        assertEquals(950L, complete.capturedAtWallTimeMs)
        assertEquals(100L, complete.capturedAtElapsedRealtimeMs)
        assertSame(f.input.reportCaptureFacts, partial.reportCaptureFacts)
        assertSame(f.input.reportCaptureFacts, complete.reportCaptureFacts)
        assertEquals(91_000_000L, complete.frameEvidence.depthMapper!!.frameId)
        assertSame(f.input.cameraFrame, f.detector.pixels)
        assertEquals(91L, f.detector.timestamp); assertEquals(3, f.detector.rotation)
        assertEquals(listOf(f.epoch), f.completedEpochs)
        assertEquals(1, f.workerFinishes); assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun foreignVisualKeyNeverBorrowsAnotherSourcesReservation() {
        val f = Fixture(); f.submit(key = f.key.copy(cameraTimestampNs = 7L)); f.lane.runNext()
        assertNull(f.publications.last().frameEvidence.visualFrameKey)
        assertNull(f.publications.last().visualSourceLease)
        assertTrue(f.released.isEmpty()); assertEquals(1, f.workerFinishes)
    }

    private fun PrimarySnapshotPublication.depthEvidence() = frameEvidence.depthSnapshot

    private class Fixture {
        val lock = Any(); val lane = Lane(); val events = mutableListOf<String>()
        val epoch = WalkRuntimeEpoch("ar-submit", 1L)
        val frame = TimestampFrame(events)
        val capture = AtomicBoolean(true)
        var now = 100L; var clocks = 0; var current = true; var feature = true
        var ownsCalls = 0; var retireAtOwns: Int? = null
        var evidenceFailure: RuntimeException? = null; var workerFailure: RuntimeException? = null
        var advanceDuringEvidence = false
        var bridgeFinishes = 0; var workerFinishes = 0
        val completedEpochs = mutableListOf<WalkRuntimeEpoch>()
        val released = mutableListOf<InterFrameDetectionTracker.PendingSourceLease>()
        val publications = mutableListOf<PrimarySnapshotPublication>()
        val quality = CameraFrameQualityObservation(epoch, 100L, true, 0.4, 0.2, 8.0, 25.0)
        val profile = ApprovedCameraFrameQualityProfile("camera-field-approved-r001", 0.4, 0.2, 8.0, 25.0, 65.0, 2_000L)
        val depth = DepthFrameSnapshot(91_000_000L, DepthImage16(2, 2, intArrayOf(500, 500, 500, 500)), null, null,
            cameraImageTimestampNs = 91_000_111L)
        val key = VisualFrameKey(1L, 91_000_000L, 91_000_111L, 100L, 0L)
        val facts = ReportFrameCaptureFacts(100L, TrustedLocation(37.0, 127.0, 3f, 95L),
            "capture-actor", 7L, epoch)
        val input = FrameRecognitionInput(pixels(), 91_000_111L, null, quality, 3, 0L,
            reportCaptureFacts = facts)
        val tracker = InterFrameDetectionTracker(VisualTrackingConfig(backend = VisualTrackingBackend.PATCH_DIAGNOSTIC))
        val frames = FrameSynthesisController(lock, object : FrameSynthesisAuthority {
            override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch) = current
            override fun userStepLengthM() = .65f
            override fun retireUnstartedFeedback(trackPrefix: String) = Unit
        }, { error("new synthesis measurement") }, tracker)
        val geometry = ArFrameGeometryFreezer({ error("new mapper time") }, { error("new viewport query") }, { error("unused percent") })
        val owner = PrimaryDetectorExecutionOwner(lock, lane, Lane(), ::elapsed,
            object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) = true
                override fun remove(task: Runnable) = Unit
            }, { false }, { false }, {}, { _, _ -> }, { it() })
        val scheduling = PrimaryDetectorFrameSchedulingOwner(lock, owner)
        val detector = Detector(this)
        val invocation = PrimaryDetectorInvocationWorker(owner, ::elapsed, ::wall)
        val route = object : FrameRouteEvidencePeer {
            override fun projectionContext(frame: Frame, capturedElapsedMs: Long): TactileProjectionContext {
                events += "tactile"; evidenceFailure?.let { throw it }
                if (advanceDuringEvidence) frames.advanceGeometry()
                return TactileProjectionContext(91_000_000L, null, null, null, null, null, null, null, capturedElapsedMs)
            }
            override fun motionContext(capturedElapsedMs: Long): MotionContext { events += "motion"; return MotionContext() }
            override fun navigationActive(): Boolean { events += "navigation"; return true }
            override fun tmapOnRoute(): Boolean { events += "tmap"; return true }
        }
        val worker = PrimaryArDetectorWorker(lock, owner, invocation,
            object : PrimaryArWorkAuthority<Any> {
                override fun frameIsCurrent(generation: Int, epoch: WalkRuntimeEpoch): Boolean { events += "worker_current"; return current }
                override fun visualLeaseAllowsWork(lease: Any) = true
            }, object : PrimaryCameraWorkQuality {
                override fun observe(observation: CameraFrameQualityObservation) = Unit
                override fun approvedProfile() = profile
            }, object : PrimaryCaptureImages {
                override fun rawCollectionAllowed() = false
                override fun reportCollectionGranted() = false
                override fun sensitiveDebugTransferAllowed(): Boolean = error("short-circuited debug permission")
                override fun encodeReport(frame: OwnedYuvFrame): ByteArray? = error("report permission denied")
                override fun encodeDebug(frame: OwnedYuvFrame): ByteArray? = error("debug permission denied")
            }, DetectorRuntimeSupervisor(ownsCurrentWork = { current && owner.ownsWork(it.ticket) }),
            object : PrimaryDetectorTimingEffects {
                override fun observe(epoch: WalkRuntimeEpoch, capturedAtElapsedRealtimeMs: Long,
                    observedAtElapsedRealtimeMs: Long, inferenceLatencyMs: Long) = true
                override fun failure(error: RuntimeException, generation: Int, epoch: WalkRuntimeEpoch): Unit = throw error
            }, PrimarySnapshotPublisher { event -> publications += event
                DetectionSnapshot.published(event.result, event.identity, event.capturedAtWallTimeMs,
                    event.startedAtWallTimeMs, event.completedAtWallTimeMs, event.imageWidth, event.imageHeight,
                    event.reportImage, event.frameEvidence, event.capturedAtElapsedRealtimeMs,
                    event.reportCaptureFacts)
            }, object : PrimaryInferenceRecording {
                override fun namedCapture(input: FrameRecognitionInput, published: DetectionSnapshot, result: AndroidDetectionResult) = Unit
                override fun completed(event: PrimaryCompletedInference) { completedEpochs += event.epoch }
                override fun originalCapture(event: PrimaryOriginalCapture): Unit = error("raw permission denied")
            }, PrimaryVisualSourceRelease { lease ->
                assertTrue(Thread.holdsLock(lock)); assertTrue(frames.releasePendingSourceFrame(lease))
                released += lease; events += "release"
            }, PrimaryWorkCompletion { ticket, invoked, duration, valid ->
                workerFinishes++; events += "worker_finish"
                owner.finishWork(ticket, invoked, duration, valid, { PrimaryCompletionObservation(false, null) }, { _, _ -> })
            }, PrimaryCapturePresentation {}, { it() }, ::elapsed, ::wall, { it.cameraFrame })
        val bridge = AndroidPrimaryArSubmissionBridge(lock, capture,
            { events += "scheduling"; scheduling }, { events += "synthesis"; frames },
            { events += "geometry"; geometry }, route,
            { events += "worker"; workerFailure?.let { throw it }; worker },
            object : PrimaryArSubmissionAuthority {
                override fun obstacleFeatureEnabled(): Boolean { events += "feature"; return feature }
                override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch): Boolean {
                    assertTrue(Thread.holdsLock(lock)); events += "owns"; ownsCalls++
                    return current && ownsCalls != retireAtOwns
                }
                override fun finishUnsubmitted(ticket: InferencePacingTicket) {
                    bridgeFinishes++; events += "bridge_finish"
                    owner.finishWork(ticket, false, observation = { error("uninvoked clock") }, onValidCompletion = { _, _ -> error("uninvoked completion") })
                }
            })
        init {
            val load = PrimaryLoadedDetector(detector, true)
            owner.adoptLoadedOnNativeLane(owner.beginLoad(), load) { false }
            owner.admitLoadedOnMain(owner.beginLoad(), load) { false }
            tracker.offerFrame(GrayTrackingFrame.copyOf(key, 2, 2, byteArrayOf(1, 2, 3, 4)))
        }
        fun elapsed(): Long { clocks++; return now }
        fun wall(): Long { clocks++; return 1_000L }
        fun submit(input: FrameRecognitionInput? = this.input, depth: DepthFrameSnapshot = this.depth,
            key: VisualFrameKey? = this.key) = bridge.schedule(input, Any(), frame, 91L, 950L, 100L, depth, 0, epoch, 6L, key)
    }

    private class TimestampFrame(private val events: MutableList<String>) : Frame() {
        var timeNs = 91_000_000L; var reads = 0
        override fun getTimestamp(): Long { reads++; events += "sdk_timestamp"; return timeNs }
        override fun transformCoordinates2d(input: Coordinates2d, values: FloatArray,
            output: Coordinates2d, destination: FloatArray) {
            events += "mapper"
            for (i in values.indices) destination[i] = values[i] / 2f
        }
    }
    private class Detector(private val f: Fixture) : AndroidFrameDetector {
        var calls = 0; var pixels: OwnedYuvFrame? = null; var timestamp = -1L; var rotation = -1
        override fun detectOriented(cameraFrame: OwnedYuvFrame, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult {
            calls++; pixels = cameraFrame; timestamp = timestampMs; rotation = quarterTurns
            f.now = 110L; onPartialResult(AndroidDetectionResult(emptyList(), partial = true))
            f.now = 130L; return AndroidDetectionResult(emptyList(), timing = AndroidDetectorTiming(totalMs = 20L))
        }
        override fun detectOriented(cameraImage: Image, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("live Image")
        override fun detect(cameraImage: Image, timestampMs: Long,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("live Image")
        override fun detect(cameraFrame: OwnedYuvFrame, timestampMs: Long,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("unoriented frame")
    }
    private class Lane : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>(); var reject = false
        override fun execute(command: Runnable) { if (reject) throw RejectedExecutionException(); tasks += command }
        fun runNext() = tasks.removeFirst().run()
        override fun shutdown() = Unit
        override fun shutdownNow() = emptyList<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }
    companion object {
        private fun pixels() = OwnedYuvFrame.copyOf(2, 2, ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)),
            ByteBuffer.wrap(byteArrayOf(5)), ByteBuffer.wrap(byteArrayOf(6)), 2, 1, 1, 1)
    }
}
