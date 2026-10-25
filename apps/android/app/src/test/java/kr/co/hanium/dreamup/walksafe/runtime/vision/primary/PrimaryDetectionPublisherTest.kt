package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.runtime.DetectionFrameEvidence
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameSynthesisAuthority
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.FrameSynthesisController
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.ReportFrameCaptureFacts
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class PrimaryDetectionPublisherTest {
    @Test fun constructorDoesNotReadClockOrEpochAuthority() {
        val f = Fixture()
        assertEquals(0, f.clockReads)
        assertEquals(0, f.authorityReads)
        assertNull(f.frames.detectionSnapshot.completedAtMs)
    }

    @Test fun acceptedPublicationInstallsTheExactReturnedSnapshotInTheActualFrameOwner() {
        val f = Fixture()
        val event = f.event()
        val published = requireNotNull(f.publisher.publish(event))
        assertSame(published, f.frames.detectionSnapshot)
        assertSame(event.frameEvidence, published.frameEvidence)
        assertSame(event.identity, published.identity)
        assertEquals(950L, published.capturedAtMs)
        assertEquals(100L, published.capturedAtElapsedRealtimeMs)
        assertSame(event.reportCaptureFacts, published.reportCaptureFacts)
        assertSame(event.reportCaptureFacts!!.trustedLocation, published.reportCaptureFacts!!.trustedLocation)
    }

    @Test fun absentOrForeignCaptureFactsFailClosedWhileCurrentDetectionStillPublishes() {
        for (variant in 0..3) {
            val f = Fixture()
            val event = when (variant) {
                0 -> f.event().copy(reportCaptureFacts = null)
                1 -> f.event().copy(reportCaptureFacts = f.facts.copy(capturedAtElapsedRealtimeMs = 99L))
                2 -> f.event().copy(reportCaptureFacts = f.facts.copy(walkEpoch = WalkRuntimeEpoch("foreign", 1L)))
                else -> f.event().copy(capturedAtElapsedRealtimeMs = null)
            }
            val published = requireNotNull(f.publisher.publish(event))
            assertSame(published, f.frames.detectionSnapshot)
            assertNull(published.reportCaptureFacts)
        }
    }

    @Test fun missingLocationAndGenerationStayMissingInsteadOfReadingLaterAuthority() {
        val f = Fixture()
        val facts = f.facts.copy(trustedLocation = null, accountGeneration = null)
        val published = requireNotNull(f.publisher.publish(f.event().copy(reportCaptureFacts = facts)))
        assertSame(facts, published.reportCaptureFacts)
        assertNull(published.reportCaptureFacts!!.trustedLocation)
        assertNull(published.reportCaptureFacts!!.accountGeneration)
    }

    @Test fun wrongGenerationAndGeometryRejectBeforeTheOriginalEpochBranch() {
        val f = Fixture()
        assertNull(f.publisher.publish(f.event().copy(generation = 1)))
        assertNull(f.publisher.publish(f.event().copy(frameEvidence = f.evidence.copy(geometryVersion = 1L))))
        assertEquals(0, f.authorityReads)
        assertNull(f.frames.detectionSnapshot.completedAtMs)
    }

    @Test fun nullableLegacyEpochStillAllowsPublicationWithoutReadingAReplacementAuthority() {
        val f = Fixture(); f.allowed = false
        val published = requireNotNull(f.publisher.publish(f.event().copy(expectedEpoch = null)))
        assertNull(published.reportCaptureFacts)
        assertEquals(0, f.authorityReads)
    }

    @Test fun currentEpochBranchCannotInstallAResultWhenItsOriginalCameraEnvironmentIsDenied() {
        val f = Fixture(); val original = f.frames.detectionSnapshot; f.allowed = false
        assertNull(f.publisher.publish(f.event()))
        assertSame(original, f.frames.detectionSnapshot)
        assertEquals(1, f.authorityReads)
    }

    @Test fun visualSourceRequiresTheActualReservedLeaseAndInstallsOnceAtTheSameTransaction() {
        val f = Fixture()
        f.tracker.offerFrame(GrayTrackingFrame.copyOf(f.key, 2, 2, byteArrayOf(1, 2, 3, 4)))
        val lease = requireNotNull(f.frames.reservePendingSourceFrame(f.key))
        val withSource = f.event().copy(frameEvidence = f.evidence.copy(visualFrameKey = f.key))
        assertNull(f.publisher.publish(withSource))
        assertNull(f.publisher.publish(withSource.copy(visualSourceLease = InterFrameDetectionTracker.PendingSourceLease(f.key))))
        val published = requireNotNull(f.publisher.publish(withSource.copy(visualSourceLease = lease)))
        assertSame(published, f.frames.detectionSnapshot)
        assertNull(f.publisher.publish(withSource.copy(visualSourceLease = lease)))
        assertSame(published, f.frames.detectionSnapshot)
    }

    @Test fun lateNativeFlightDoesNotInstallAndRequestsItsOneStopAfterReleasingTheFrameLock() {
        val f = Fixture()
        val flight = requireNotNull(f.detector.beginNativeFlight(f.epoch, 0))
        f.now = flight.ticket.deadlineElapsedRealtimeMs
        val original = f.frames.detectionSnapshot
        assertNull(f.publisher.publish(f.event().copy(nativeFlight = flight)))
        assertSame(original, f.frames.detectionSnapshot)
        assertEquals(1, f.stalls)
        assertFalse(f.stallUnderFrameLock)
        assertNull(f.publisher.publish(f.event().copy(nativeFlight = flight)))
        assertEquals(1, f.stalls)
    }

    private class Fixture {
        val lock = Any()
        var now = 100L
        var clockReads = 0
        var authorityReads = 0
        var allowed = true
        var stalls = 0
        var stallUnderFrameLock = false
        val epoch = WalkRuntimeEpoch("publisher", 1L)
        val facts = ReportFrameCaptureFacts(100L, TrustedLocation(37.0, 127.0, 3f, 95L),
            "capture-actor", 7L, epoch)
        val key = VisualFrameKey(1L, 91_000_000L, 91_000_111L, 100L, 0L)
        val tracker = InterFrameDetectionTracker(VisualTrackingConfig(backend = VisualTrackingBackend.PATCH_DIAGNOSTIC))
        val frames = FrameSynthesisController(lock, object : FrameSynthesisAuthority {
            override fun ownsFrame(generation: Int, epoch: WalkRuntimeEpoch) = true
            override fun userStepLengthM() = 0.8f
            override fun retireUnstartedFeedback(trackPrefix: String) = Unit
        }, { clockReads += 1; now }, tracker)
        val detector = PrimaryDetectorExecutionOwner(lock, Lane(), Lane(), { clockReads += 1; now },
            object : PrimaryNativeFlightScheduler {
                override fun postDelayed(task: Runnable, delayMs: Long) = true
                override fun remove(task: Runnable) = Unit
            }, { false }, { false }, {}, { _, _ -> stalls += 1; stallUnderFrameLock = Thread.holdsLock(lock) }, { it() })
        val publisher = PrimaryDetectionPublisher(lock, detector, frames,
            PrimaryPublicationAuthority { authorityReads += 1; assertTrue(Thread.holdsLock(lock)); allowed })
        val evidence = DetectionFrameEvidence(91_000_000L, 91L,
            DepthFrameSnapshot(91_000_000L, null, null, null, cameraImageTimestampNs = 91_000_111L), null,
            TactileProjectionContext(91_000_000L, null, null, null, null, null, null, null, 100L),
            MotionContext(), false, false, null, 3, 0L)
        fun event() = PrimarySnapshotPublication(AndroidDetectionResult.empty(), 0,
            AndroidDetectionSnapshotFrameIdentity(91_000_000L, 91L), 950L, 1_000L, 1_020L,
            2, 2, null, evidence, epoch, 100L, reportCaptureFacts = facts)
    }
    private class Lane : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow() = emptyList<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }
}
