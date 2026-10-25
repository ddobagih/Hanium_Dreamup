package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import android.media.Image
import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.ApprovedCameraFrameQualityProfile
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorTiming
import kr.co.hanium.dreamup.walksafe.inference.AndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.OwnedYuvFrame
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class PrimaryDetectorInvocationWorkerTest {
    @Test fun constructionAndPrepareDoNotReadClocksOrCopyAPlatformImage() {
        val f = Fixture()
        val invocation = f.worker.prepare(f.request())
        assertEquals(0, f.clockReads)
        assertEquals(0, f.wallReads)
        assertFalse(invocation.observe().detectorInvoked)
        assertFalse(invocation.observe().result.inferenceCompleted)
        assertFalse(PrimaryInvocationRequest::class.java.declaredFields.any {
            it.type.name == "com.google.ar.core.Frame" || it.type.name == "android.media.Image"
        })
    }

    @Test fun rejectedQualityNeitherCopiesPixelsNorBeginsANativeFlight() {
        val f = Fixture()
        val invocation = f.worker.prepare(f.request().copy(quality = f.quality.copy(occludedFraction = 0.21)))
        f.worker.invoke(invocation, { error("rejected quality copy") }, null,
            { error("rejected quality output") }, { error("rejected quality failure") })
        assertFalse(invocation.observe().admission!!.detectorInvocationAllowed)
        assertFalse(invocation.observe().detectorInvoked)
        assertEquals(0, f.clockReads)
        assertEquals(0, f.wallReads)
        assertTrue(f.events.isEmpty())
    }

    @Test fun unapprovedOrWrongEpochQualityCannotInvokeTheSameOwnedPixelFrame() {
        for (request in listOf(Fixture().request().copy(approvedProfile = null),
                Fixture().request().copy(quality = Fixture().quality.copy(epoch = WalkRuntimeEpoch("other", 2L))))) {
            val f = Fixture()
            val invocation = f.worker.prepare(request)
            f.worker.invoke(invocation, { error("unknown quality copy") }, null,
                { error("unknown quality output") }, { error("unknown quality failure") })
            assertFalse(invocation.observe().admission!!.detectorInvocationAllowed)
            assertEquals(0, f.detector.calls)
        }
    }

    @Test fun nullCopyRunsTheOriginalCompletionFinallyWithoutInvokingTheDetector() {
        val f = Fixture()
        val invocation = f.worker.prepare(f.request(wall = true))
        f.worker.invoke(invocation, { f.events += "copy"; null }, null,
            { error("null input output") }, { error("null input failure") })
        val state = invocation.observe()
        assertTrue(state.admission!!.detectorInvocationAllowed)
        assertFalse(state.detectorInvoked)
        assertFalse(state.result.inferenceCompleted)
        assertEquals(listOf("elapsed", "copy", "wall", "elapsed"), f.events)
        assertEquals(0L, state.inferenceDurationMs)
    }

    @Test fun samePixelsTimestampRotationPartialTimingAndFinalNativeOutputKeepTheirOriginalOrder() {
        val f = Fixture()
        val input = frame()
        val invocation = f.worker.prepare(f.request(wall = true))
        var partialObservedAt = -1L
        f.worker.invoke(invocation, { f.events += "copy"; input },
            onPartial = { partial ->
                f.events += "partial"
                partialObservedAt = partial.observedAtElapsedRealtimeMs
                assertEquals(10L, partial.inferenceLatencyMs)
                assertEquals(f.epoch, partial.flight.walkEpoch)
                assertEquals(4, partial.flight.detectorGeneration)
            },
            onNativeOutput = { output ->
                f.events += "output"
                assertEquals(140L, output.completedAtElapsedRealtimeMs)
                false
            }, onRuntimeFailure = { throw it })
        val state = invocation.observe()
        assertSame(input, f.detector.frame)
        assertEquals(91L, f.detector.timestamp)
        assertEquals(3, f.detector.rotation)
        assertEquals(110L, partialObservedAt)
        assertTrue(state.detectorInvoked)
        assertTrue(state.detectorSucceeded)
        assertFalse(state.detectorOutputExpired)
        assertEquals(40L, state.inferenceDurationMs)
        assertEquals(999L, state.completedAtWallTimeMs)
        assertTrue(f.events.indexOf("copy") < f.events.indexOf("schedule"))
        assertTrue(f.events.indexOf("output") < f.events.indexOf("remove"))
        assertTrue(f.events.indexOf("remove") < f.events.indexOf("wall"))
        invocation.preferReportedInferenceDuration()
        assertEquals(20L, invocation.observe().inferenceDurationMs)
    }

    @Test fun cameraFallbackDoesNotReadTheWallClockAndKeepsNoPartialPublicationCallback() {
        val f = Fixture()
        val invocation = f.worker.prepare(f.request())
        f.worker.invoke(invocation, ::frame, null, { false }, { throw it })
        assertTrue(invocation.observe().detectorSucceeded)
        assertNull(invocation.observe().completedAtWallTimeMs)
        assertEquals(0, f.wallReads)
    }

    @Test fun nativeExceptionIsFailedRatherThanAnEmptyCompletedSceneAndStillCompletesItsFlight() {
        val f = Fixture()
        val failure = IllegalStateException("invoke")
        f.detector.failure = failure
        val invocation = f.worker.prepare(f.request())
        var observed: RuntimeException? = null
        f.worker.invoke(invocation, ::frame, null, { error("failed native output") }, { observed = it })
        assertSame(failure, observed)
        assertTrue(invocation.observe().detectorInvoked)
        assertFalse(invocation.observe().detectorSucceeded)
        assertEquals(kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionStatus.FAILED, invocation.observe().result.status)
        assertTrue(f.events.contains("remove"))
    }

    @Test fun aThrowingFailureEffectCannotLoseActualInvocationAndFinallyTimingFacts() {
        val f = Fixture()
        f.detector.failure = IllegalStateException("invoke")
        val effectFailure = IllegalStateException("failure output")
        val invocation = f.worker.prepare(f.request(wall = true))
        assertSame(effectFailure, runCatching {
            f.worker.invoke(invocation, ::frame, null, { false }, { throw effectFailure })
        }.exceptionOrNull())
        assertTrue(invocation.observe().detectorInvoked)
        assertNotNull(invocation.observe().inferenceDurationMs)
        assertEquals(999L, invocation.observe().completedAtWallTimeMs)
        assertTrue(f.events.contains("remove"))
    }

    @Test fun expiredOutputKeepsMeasuredTimingUntilTheOriginalCallerAdmitsReportedTiming() {
        val f = Fixture()
        val invocation = f.worker.prepare(f.request())
        f.worker.invoke(invocation, ::frame, null, { true }, { throw it })
        assertTrue(invocation.observe().detectorOutputExpired)
        assertTrue(invocation.observe().detectorSucceeded)
        assertEquals(40L, invocation.observe().inferenceDurationMs)
    }

    @Test fun expiredOutputPublishesItsOwnTimingBeforeTheOriginalCallbackWithoutAnotherClockRead() {
        val f = Fixture()
        val request = f.request()
        val invocation = f.worker.prepare(request)
        assertNull(f.lastTiming())
        f.worker.invoke(invocation, ::frame, null, { output ->
            val sample = requireNotNull(f.lastTiming())
            assertSame(request.pacingTicket, sample.pacingTicket)
            assertEquals(request.epoch, sample.epoch)
            assertEquals(request.frameGeneration, sample.frameGeneration)
            assertEquals(request.capturedAtElapsedRealtimeMs, sample.capturedAtElapsedRealtimeMs)
            assertEquals(100L, sample.nativeStartedAtElapsedRealtimeMs)
            assertEquals(output.completedAtElapsedRealtimeMs, sample.nativeReturnedAtElapsedRealtimeMs)
            assertEquals(output.result.status, sample.status)
            assertEquals(output.result.timing, sample.timing)
            f.events += "output"
            true
        }, { throw it })
        assertTrue(invocation.observe().detectorOutputExpired)
        assertTrue(invocation.observe().detectorSucceeded)
        assertEquals(140L, f.lastTiming()!!.nativeReturnedAtElapsedRealtimeMs)
        assertSame(request.pacingTicket, f.lastTiming()!!.pacingTicket)
        assertEquals(7, f.clockReads)
        assertEquals(0, f.wallReads)
        assertTrue(f.events.indexOf("output") < f.events.indexOf("remove"))
    }

    @Test fun aNewOrFailedCallCannotRelabelThePreviousReturnedTimingAsItsOwn() {
        val f = Fixture()
        val first = f.request()
        f.worker.invoke(f.worker.prepare(first), ::frame, null, { false }, { throw it })
        val returned = requireNotNull(f.lastTiming())
        f.owner.finishWork(first.pacingTicket, true, 40L, true,
            { PrimaryCompletionObservation(false, null) }, { _, _ -> })
        f.owner.resetPacing()
        val next = f.request()
        val invocation = f.worker.prepare(next)
        assertSame(returned, f.lastTiming())
        assertNotSame(next.pacingTicket, f.lastTiming()!!.pacingTicket)
        f.detector.failure = IllegalStateException("native")
        f.worker.invoke(invocation, ::frame, null, { error("failed output") }, {})
        assertSame(returned, f.lastTiming())
        assertFalse(invocation.observe().result.inferenceCompleted)
        assertFalse(PrimaryNativeTimingSnapshot::class.java.declaredFields.any {
            it.type == AndroidDetectionResult::class.java || it.type == OwnedYuvFrame::class.java ||
                it.type == Image::class.java
        })
    }

    @Test fun returnedTimingDoesNotBorrowMutableModelLists() {
        val f = Fixture()
        val completed = mutableListOf("unified")
        val skipped = mutableListOf("supplement")
        f.detector.timing = AndroidDetectorTiming(totalMs = 20L,
            completedModels = completed, skippedModels = skipped)
        f.worker.invoke(f.worker.prepare(f.request()), ::frame, null, { false }, { throw it })
        val sample = requireNotNull(f.lastTiming())
        completed.clear(); skipped.clear()
        assertEquals(listOf("unified"), sample.timing.completedModels)
        assertEquals(listOf("supplement"), sample.timing.skippedModels)
        assertTrue(runCatching { (sample.timing.completedModels as MutableList<String>).clear() }
            .exceptionOrNull() is UnsupportedOperationException)
    }

    @Test fun timerRejectionNeverInvokesANativeDetectorAndStillRunsCompletionTiming() {
        val f = Fixture()
        f.schedulerAccept = false
        val invocation = f.worker.prepare(f.request())
        f.worker.invoke(invocation, ::frame, null, { error("no native output") }, { throw it })
        assertFalse(invocation.observe().detectorInvoked)
        assertFalse(invocation.observe().detectorSucceeded)
        assertEquals(0, f.detector.calls)
        assertNotNull(invocation.observe().inferenceDurationMs)
    }

    @Test fun schedulingKeepsEarlyGuardsBeforeSdkTimestampAndBeforeCaptureFlagConsumption() {
        val f = Fixture()
        val owner = PrimaryDetectorFrameSchedulingOwner(f.lock, f.owner)
        assertNull(owner.admit({ error("stale frame timestamp") }, 4, 6L, 100L,
            { false }, { error("stale capture flag") }))
        f.owner.disableUnverified(f.detector) {}
        assertNull(owner.admit({ error("unavailable detector timestamp") }, 4, 6L, 100L,
            { true }, { error("unavailable capture flag") }))
        assertFalse(f.owner.projection().workInFlight)
    }

    @Test fun geometryMismatchStillCommitsTheOriginalWatermarkAndCancelsOnlyTheUnsubmittedTicket() {
        val f = Fixture()
        val owner = PrimaryDetectorFrameSchedulingOwner(f.lock, f.owner)
        val admitted = requireNotNull(owner.admit({ 42L }, 4, 6L, 100L, { true }, { true }))
        assertTrue(admitted.captureRequested)
        assertNull(owner.commitGeometryIfCurrent({ 42L }, 4, 6L, 8L, { true }, { 9L }))
        f.owner.finishWork(admitted.ticket, false, observation = { error("canceled clock") },
            onValidCompletion = { _, _ -> error("canceled sample") })
        val due = f.owner.pacingSnapshot().nextEligibleAtElapsedRealtimeMs
        assertNull(owner.admit({ 42L }, 4, 6L, due, { true }, { error("duplicate flag") }))
        assertNotNull(owner.admit({ 42L }, 4, 7L, due, { true }, { false }))
    }

    @Test fun staleGeometryCommitReadsNeitherTimestampNorGeometryAndDoesNotReplaceNewWatermarks() {
        val f = Fixture()
        val owner = PrimaryDetectorFrameSchedulingOwner(f.lock, f.owner)
        assertNull(owner.commitGeometryIfCurrent({ error("stale timestamp") }, 4, 6L, 8L,
            { false }, { error("stale geometry") }))
        assertEquals(8L, owner.commitGeometryIfCurrent({ 42L }, 4, 6L, 8L,
            { assertTrue(Thread.holdsLock(f.lock)); true }, { 8L }))
    }

    private class Fixture {
        val lock = Any()
        val events = mutableListOf<String>()
        var now = 100L
        var clockReads = 0
        var wallReads = 0
        var schedulerAccept = true
        val epoch = WalkRuntimeEpoch("invocation", 1L)
        val quality = CameraFrameQualityObservation(epoch, 100L, true, 0.4, 0.2, 8.0, 25.0)
        val profile = ApprovedCameraFrameQualityProfile("camera-field-approved-r001", 0.4, 0.2, 8.0, 25.0, 65.0, 2_000L)
        val owner = PrimaryDetectorExecutionOwner(lock, Lane(), Lane(), ::elapsed,
            object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long): Boolean { events += "schedule"; return schedulerAccept }
                override fun remove(task: Runnable) { events += "remove" }
            }, { false }, { false }, {}, { _, _ -> events += "stall" }, { it() })
        val detector = Detector(this)
        val worker = PrimaryDetectorInvocationWorker(owner, ::elapsed, { events += "wall"; wallReads += 1; 999L })
        init {
            val result = PrimaryLoadedDetector(detector, true)
            owner.adoptLoadedOnNativeLane(owner.beginLoad(), result) { false }
            owner.admitLoadedOnMain(owner.beginLoad(), result) { false }
        }
        fun elapsed(): Long { events += "elapsed"; clockReads += 1; return now }
        fun lastTiming() = PrimaryDetectorInvocationWorker::class.java.getDeclaredField("latestNativeTiming")
            .apply { isAccessible = true }.get(worker) as PrimaryNativeTimingSnapshot?
        fun request(wall: Boolean = false) = PrimaryInvocationRequest(epoch, 4,
            requireNotNull(owner.tryStartWork(100L)), 100L, 100L, 99L, 91L, 3, quality, profile,
            startedAtWallTimeMs = 950L.takeIf { wall })
    }

    private class Detector(private val f: Fixture) : AndroidFrameDetector {
        var calls = 0
        var frame: OwnedYuvFrame? = null
        var timestamp = -1L
        var rotation = -1
        var failure: RuntimeException? = null
        var timing = AndroidDetectorTiming(totalMs = 20L)
        override fun detectOriented(cameraFrame: OwnedYuvFrame, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult {
            calls += 1; frame = cameraFrame; timestamp = timestampMs; rotation = quarterTurns
            failure?.let { throw it }
            f.now = 110L
            onPartialResult(AndroidDetectionResult(emptyList(), partial = true))
            f.now = 140L
            return AndroidDetectionResult(emptyList(), timing = timing)
        }
        override fun detectOriented(cameraImage: Image, timestampMs: Long, quarterTurns: Int,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("borrowed Image")
        override fun detect(cameraImage: Image, timestampMs: Long,
            onPartialResult: (AndroidDetectionResult) -> Unit): AndroidDetectionResult = error("borrowed Image")
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
    companion object {
        private fun frame() = OwnedYuvFrame.copyOf(
            width = 2, height = 2,
            yBuffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)),
            uBuffer = ByteBuffer.wrap(byteArrayOf(5)),
            vBuffer = ByteBuffer.wrap(byteArrayOf(6)),
            yRowStride = 2, yPixelStride = 1, uvRowStride = 1,
            uvPixelStride = 1,
        )
    }
}
