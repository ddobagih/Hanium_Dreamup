package android.media

import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.CameraImageTimestampEvidence
import kr.co.hanium.dreamup.walksafe.depth.DepthAcquisitionFailureReason
import kr.co.hanium.dreamup.walksafe.depth.DepthAcquisitionStage
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.FullDepthRequestPurpose
import kr.co.hanium.dreamup.walksafe.depth.FullDepthTiming
import kr.co.hanium.dreamup.walksafe.depth.toSnapshotAndClose
import kr.co.hanium.dreamup.walksafe.captureCameraTestFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** SDK Image/Plane constructors need this package. These are fake-clock unit costs, not native timings. */
class ArCoreFullDepthTimingTest {
    @Test
    fun rawPairFullSupplementMeasuresOnlyFullWorkAndClosesAllImages() {
        val fixture = Fixture()
        val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame,
            fullDepthTimingObserver = fixture.events::add)

        assertSnapshotIdentity(snapshot)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertTrue(snapshot.hasFreshFullDepth)
        assertEquals(1, fixture.frame.fullAcquisitions)
        assertEquals(1, fixture.frame.raw.closeCalls)
        assertEquals(1, fixture.frame.confidence.closeCalls)
        assertEquals(1, fixture.frame.full.closeCalls)
        assertEquals(1, fixture.frame.full.conversionCalls)
        assertTiming(fixture.events.single(), FullDepthRequestPurpose.RAW_AVAILABLE_SUPPLEMENT)
    }

    @Test
    fun missingRawOrConfidenceUsesFullFallbackAndMeasuresRealSnapshotConversion() {
        for (missingRaw in listOf(true, false)) {
            val fixture = Fixture()
            fixture.frame.rawUnavailable = missingRaw
            fixture.frame.confidenceUnavailable = !missingRaw
            val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame,
                fullDepthTimingObserver = fixture.events::add)

            assertSnapshotIdentity(snapshot)
            assertNull(snapshot.rawDepth)
            assertNull(snapshot.rawConfidence)
            assertTrue(snapshot.hasFreshFullDepth)
            assertEquals(if (missingRaw) 0 else 1, fixture.frame.raw.closeCalls)
            assertEquals(0, fixture.frame.confidence.closeCalls)
            assertEquals(1, fixture.frame.full.closeCalls)
            assertEquals(1, fixture.frame.full.conversionCalls)
            assertTiming(fixture.events.single(), FullDepthRequestPurpose.RAW_UNAVAILABLE_FALLBACK)
        }
    }

    @Test
    fun rawPairWithFullDisabledSkipsFullAcquisitionAndTiming() {
        val fixture = Fixture()
        val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame,
            includeFullDepthWhenRawAvailable = false, fullDepthTimingObserver = fixture.events::add)

        assertSnapshotIdentity(snapshot)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertNull(snapshot.fullDepth)
        assertEquals(0, fixture.frame.fullAcquisitions)
        assertEquals(0, fixture.frame.full.closeCalls)
        assertEquals(1, fixture.frame.raw.closeCalls)
        assertEquals(1, fixture.frame.confidence.closeCalls)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun preflightFullPrimaryMeasuresExistingSnapshotExtensionAndSkipsRaw() {
        val fixture = Fixture()
        val bundle = fixture.provider.acquirePreflightDepthBundle(fixture.frame,
            fullDepthTimingObserver = fixture.events::add)
        assertTrue("Successful Full timing finishes at actual close", fixture.events.isEmpty())
        val snapshot = bundle.toSnapshotAndClose()

        assertSnapshotIdentity(snapshot)
        assertTrue(snapshot.hasFreshFullDepth)
        assertNull(snapshot.rawDepth)
        assertEquals(0, fixture.frame.rawAcquisitions)
        assertEquals(0, fixture.frame.confidenceAcquisitions)
        assertEquals(1, fixture.frame.full.conversionCalls)
        assertEquals(1, fixture.frame.full.closeCalls)
        assertTiming(fixture.events.single(), FullDepthRequestPurpose.PREFLIGHT_PRIMARY)
    }

    @Test
    fun preflightBundleClosedWithoutConversionLeavesConversionUnobserved() {
        val fixture = Fixture()
        fixture.provider.acquirePreflightDepthBundle(fixture.frame,
            fullDepthTimingObserver = fixture.events::add).close()
        val timing = fixture.events.single()

        assertEquals(FullDepthRequestPurpose.PREFLIGHT_PRIMARY, timing.purpose)
        assertEquals(7L, timing.acquisitionDurationNs)
        assertTrue(timing.acquisitionSucceeded)
        assertNull(timing.conversionDurationNs)
        assertNull(timing.conversionSucceeded)
        assertEquals(13L, timing.closeDurationNs)
        assertEquals(true, timing.closeSucceeded)
        assertNull(timing.conversionAndCloseDurationNs)
        assertEquals(0, fixture.frame.full.conversionCalls)
        assertEquals(1, fixture.frame.full.closeCalls)
    }

    @Test
    fun fullAcquisitionFailureRetainsDepthFailureReasonAndNoConversionOrClose() {
        val fixture = Fixture()
        fixture.frame.fullUnavailable = true
        val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame,
            fullDepthTimingObserver = fixture.events::add)
        val timing = fixture.events.single()

        assertSnapshotIdentity(snapshot)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertNull(snapshot.fullDepth)
        assertEquals(FullDepthRequestPurpose.RAW_AVAILABLE_SUPPLEMENT, timing.purpose)
        assertEquals(7L, timing.acquisitionDurationNs)
        assertFalse(timing.acquisitionSucceeded)
        assertNull(timing.conversionDurationNs)
        assertNull(timing.conversionSucceeded)
        assertNull(timing.closeDurationNs)
        assertNull(timing.closeSucceeded)
        assertNull(timing.conversionAndCloseDurationNs)
        assertEquals(1, fixture.provider.runtimeAcquisitionFailureCount(
            DepthAcquisitionStage.FULL_DEPTH, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE))
        assertEquals(0, fixture.frame.full.closeCalls)
        assertEquals(1, fixture.frame.raw.closeCalls)
        assertEquals(1, fixture.frame.confidence.closeCalls)
    }

    @Test
    fun invalidReceiptsKeepUnqualifiedDepthWithoutReacquiringCpuImage() {
        for (kind in listOf("missing", "foreign", "expired")) {
            val fixture = Fixture()
            val evidence: CameraImageTimestampEvidence? = when (kind) {
                "missing" -> null
                "foreign" -> capture(ArCoreFrameProvider(fakeSession()), fixture.frame)
                else -> capture(fixture.provider, fixture.frame).also {
                    fixture.provider.invalidateRecognitionCameraImageTimestamps()
                }
            }
            val cpuAttemptsBefore = fixture.frame.cameraAcquisitions
            val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame, evidence,
                fullDepthTimingObserver = fixture.events::add)

            assertEquals(cpuAttemptsBefore, fixture.frame.cameraAcquisitions)
            assertEquals(FRAME_NS, snapshot.frameTimestampNs)
            assertNull(snapshot.cameraImageTimestampNs)
            assertTrue(snapshot.hasMetricRawDepth)
            assertFalse(snapshot.hasFreshMetricRawDepth)
            assertFalse(snapshot.hasFreshFullDepth)
            assertEquals(1, fixture.frame.rawAcquisitions)
            assertEquals(1, fixture.frame.fullAcquisitions)
            assertEquals(1, fixture.frame.raw.closeCalls)
            assertEquals(1, fixture.frame.confidence.closeCalls)
            assertEquals(1, fixture.frame.full.closeCalls)
            assertTiming(fixture.events.single(), FullDepthRequestPurpose.RAW_AVAILABLE_SUPPLEMENT)
        }
    }

    @Test
    fun closedSnapshotNeverReadsFrameOrAcquiresNativeImagesAndEmitsNoTiming() {
        val fixture = Fixture()
        fixture.provider.close()
        fixture.frame.forbidFrameAccess = true
        val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame,
            fullDepthTimingObserver = fixture.events::add)
        fixture.provider.acquireDepthBundle(fixture.frame, cameraImageTimestampEvidence = null,
            fullDepthTimingObserver = fixture.events::add).use { assertEquals(0L, it.frameTimestampNs) }

        assertEquals(0L, snapshot.frameTimestampNs)
        assertNull(snapshot.rawDepth)
        assertNull(snapshot.fullDepth)
        assertEquals(0, fixture.frame.cameraAcquisitions)
        assertEquals(0, fixture.frame.rawAcquisitions)
        assertEquals(0, fixture.frame.confidenceAcquisitions)
        assertEquals(0, fixture.frame.fullAcquisitions)
        assertEquals(0, fixture.clock.readCalls)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun fullConversionThrowClosesEveryAcquiredImageOnceAndReportsFailedConversion() {
        val fixture = Fixture()
        fixture.frame.full.failConversion = true
        try {
            fixture.provider.acquireDepthSnapshot(fixture.frame, fullDepthTimingObserver = fixture.events::add)
            fail("Actual Full conversion must propagate its original exception")
        } catch (error: IllegalStateException) {
            assertEquals("synthetic Full plane unavailable", error.message)
        }
        val timing = fixture.events.single()
        assertEquals(FullDepthRequestPurpose.RAW_AVAILABLE_SUPPLEMENT, timing.purpose)
        assertEquals(7L, timing.acquisitionDurationNs)
        assertTrue(timing.acquisitionSucceeded)
        assertEquals(11L, timing.conversionDurationNs)
        assertEquals(false, timing.conversionSucceeded)
        assertEquals(13L, timing.closeDurationNs)
        assertEquals(true, timing.closeSucceeded)
        assertEquals(24L, timing.conversionAndCloseDurationNs)
        assertEquals(1, fixture.frame.full.conversionCalls)
        assertEquals(1, fixture.frame.raw.closeCalls)
        assertEquals(1, fixture.frame.confidence.closeCalls)
        assertEquals(1, fixture.frame.full.closeCalls)
        assertEquals(1, fixture.frame.cameraImage.closeCalls)
    }

    @Test
    fun observerThrowDoesNotChangeSnapshotFailureSemanticsOrImageOwnership() {
        val fixture = Fixture()
        var observerCalls = 0
        val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame, fullDepthTimingObserver = {
            observerCalls++
            throw IllegalStateException("diagnostic observer failed")
        })

        assertSnapshotIdentity(snapshot)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertTrue(snapshot.hasFreshFullDepth)
        assertEquals(1, observerCalls)
        assertNull(fixture.provider.consumeRuntimeAcquisitionFailure())
        assertEquals(1, fixture.frame.raw.closeCalls)
        assertEquals(1, fixture.frame.confidence.closeCalls)
        assertEquals(1, fixture.frame.full.closeCalls)
        assertEquals(1, fixture.frame.cameraImage.closeCalls)
    }

    @Test
    fun defaultObserverOffNeverReadsInjectedTimingClock() {
        val fixture = Fixture()
        val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame)

        assertSnapshotIdentity(snapshot)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertTrue(snapshot.hasFreshFullDepth)
        assertEquals(0, fixture.clock.readCalls)
        assertEquals(1, fixture.frame.full.closeCalls)
        assertEquals(1, fixture.frame.raw.closeCalls)
        assertEquals(1, fixture.frame.confidence.closeCalls)
    }

    @Test
    fun conversionAndCloseFailuresKeepOriginalExceptionWithCloseSuppressed() {
        val fixture = Fixture()
        fixture.frame.full.failConversion = true
        fixture.frame.full.failClose = true
        val failure = runCatching {
            fixture.provider.acquireDepthSnapshot(fixture.frame, fullDepthTimingObserver = fixture.events::add)
        }.exceptionOrNull()

        assertEquals("synthetic Full plane unavailable", failure?.message)
        assertEquals(listOf("synthetic Full close failed"), failure?.suppressed?.map { it.message })
        val timing = fixture.events.single()
        assertEquals(false, timing.conversionSucceeded)
        assertEquals(false, timing.closeSucceeded)
        assertEquals(11L, timing.conversionDurationNs)
        assertEquals(13L, timing.closeDurationNs)
        assertEquals(24L, timing.conversionAndCloseDurationNs)
        assertEquals(1, fixture.frame.raw.closeCalls)
        assertEquals(1, fixture.frame.confidence.closeCalls)
        assertEquals(1, fixture.frame.full.closeCalls)
    }

    @Test
    fun throwingOrBackwardClockOnlyInvalidatesDurationsAndKeepsSemanticSuccess() {
        for (throwing in listOf(true, false)) {
            val fixture = Fixture()
            fixture.clock.throwReads = throwing
            fixture.clock.backward = !throwing
            val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame,
                fullDepthTimingObserver = fixture.events::add)
            val timing = fixture.events.single()

            assertSnapshotIdentity(snapshot)
            assertTrue(snapshot.hasFreshMetricRawDepth)
            assertTrue(snapshot.hasFreshFullDepth)
            assertTrue(timing.acquisitionSucceeded)
            assertEquals(true, timing.conversionSucceeded)
            assertEquals(true, timing.closeSucceeded)
            assertNull(timing.acquisitionDurationNs)
            assertNull(timing.conversionDurationNs)
            assertNull(timing.closeDurationNs)
            assertNull(timing.conversionAndCloseDurationNs)
            assertEquals(1, fixture.frame.full.closeCalls)
            assertNull(fixture.provider.consumeRuntimeAcquisitionFailure())
        }
    }

    @Test
    fun cameraCaptureKeepsEachCallTimingIncludingDepthErrorAndDoesNotReuseItForUntrackedFrame() {
        val fixture = Fixture()
        val success = requireNotNull(captureCameraTestFrame(fixture.provider, fixture.frame, profileFullDepth = true))
        assertSnapshotIdentity(success.snapshot)
        assertNull(success.depthError)
        assertTiming(requireNotNull(success.fullDepthTiming), FullDepthRequestPurpose.RAW_AVAILABLE_SUPPLEMENT)
        assertEquals(0, fixture.frame.cameraImage.closeCalls)
        success.image.close()
        assertEquals(1, fixture.frame.cameraImage.closeCalls)

        val profileOffFrame = FakeFrame(fixture.clock).apply { frameNs = 1_500_000_000L }
        val clockReadsBeforeOffCapture = fixture.clock.readCalls
        val profileOff = requireNotNull(captureCameraTestFrame(fixture.provider, profileOffFrame))
        assertEquals(profileOffFrame.frameNs, profileOff.snapshot.frameTimestampNs)
        assertTrue(profileOff.snapshot.hasFreshFullDepth)
        assertNull(profileOff.fullDepthTiming)
        assertEquals(clockReadsBeforeOffCapture, fixture.clock.readCalls)
        assertEquals(1, profileOffFrame.full.closeCalls)
        assertEquals(0, profileOffFrame.cameraImage.closeCalls)
        profileOff.image.close()
        assertEquals(1, profileOffFrame.cameraImage.closeCalls)

        val failedFrame = FakeFrame(fixture.clock).apply {
            frameNs = 2_000_000_000L
            full.failConversion = true
        }
        val failed = requireNotNull(captureCameraTestFrame(fixture.provider, failedFrame, profileFullDepth = true))
        assertEquals(failedFrame.frameNs, failed.snapshot.frameTimestampNs)
        assertNull(failed.snapshot.rawDepth)
        assertNull(failed.snapshot.fullDepth)
        assertEquals("synthetic Full plane unavailable", failed.depthError?.message)
        val failureTiming = requireNotNull(failed.fullDepthTiming)
        assertEquals(false, failureTiming.conversionSucceeded)
        assertEquals(true, failureTiming.closeSucceeded)
        assertEquals(1, failedFrame.full.closeCalls)
        assertEquals(0, failedFrame.cameraImage.closeCalls)
        failed.image.close()
        assertEquals(1, failedFrame.cameraImage.closeCalls)

        val untrackedFrame = FakeFrame(fixture.clock).apply {
            frameNs = 3_000_000_000L
            trackingState = TrackingState.PAUSED
        }
        val untracked = requireNotNull(captureCameraTestFrame(fixture.provider, untrackedFrame, profileFullDepth = true))
        assertEquals(untrackedFrame.frameNs, untracked.snapshot.frameTimestampNs)
        assertNull(untracked.snapshot.fullDepth)
        assertNull(untracked.fullDepthTiming)
        assertNull(untracked.depthError)
        assertEquals(0, untrackedFrame.rawAcquisitions)
        assertEquals(0, untrackedFrame.fullAcquisitions)
        assertEquals(0, untrackedFrame.cameraImage.closeCalls)
        untracked.image.close()
        assertEquals(1, untrackedFrame.cameraImage.closeCalls)
    }

    private fun assertSnapshotIdentity(snapshot: DepthFrameSnapshot) {
        assertEquals(FRAME_NS, snapshot.frameTimestampNs)
        assertEquals(CPU_NS, snapshot.cameraImageTimestampNs)
        snapshot.rawDepth?.let { assertEquals(CPU_NS, snapshot.rawDepthTimestampNs) }
        snapshot.fullDepth?.let {
            assertEquals(CPU_NS, snapshot.fullDepthTimestampNs)
            assertEquals(listOf(1_000, 1_000, 1_000, 1_000), it.millimeters.toList())
        }
    }

    private fun assertTiming(timing: FullDepthTiming, purpose: FullDepthRequestPurpose) {
        assertEquals(purpose, timing.purpose)
        assertEquals(7L, timing.acquisitionDurationNs)
        assertTrue(timing.acquisitionSucceeded)
        assertEquals(11L, timing.conversionDurationNs)
        assertEquals(true, timing.conversionSucceeded)
        assertEquals(13L, timing.closeDurationNs)
        assertEquals(true, timing.closeSucceeded)
        assertEquals(24L, timing.conversionAndCloseDurationNs)
    }

    private class Fixture {
        val clock = FakeClock()
        val frame = FakeFrame(clock)
        val events = mutableListOf<FullDepthTiming>()
        val provider = ArCoreFrameProvider(fakeSession(), timingClockNs = clock::read)
    }

    private class FakeClock {
        var now = 10_000L
        var readCalls = 0
        var throwReads = false
        var backward = false
        fun read(): Long {
            readCalls++
            check(!throwReads) { "diagnostic clock failed" }
            return if (backward) -now else now
        }
        fun advance(nanoseconds: Long) { now += nanoseconds }
    }

    private class FakeImage(private val clock: FakeClock, private val pixelStride: Int,
                            private val copyNs: Long, private val closeNs: Long) : Image() {
        var conversionCalls = 0
        var closeCalls = 0
        var failConversion = false
        var failClose = false
        override fun getWidth() = 2
        override fun getHeight() = 2
        override fun getFormat() = if (pixelStride == 2) 1144402265 else 35
        override fun getTimestamp() = CPU_NS
        override fun close() {
            closeCalls++
            clock.advance(closeNs)
            check(!failClose) { "synthetic Full close failed" }
        }
        override fun getPlanes(): Array<Plane> {
            conversionCalls++
            clock.advance(copyNs)
            check(!failConversion) { "synthetic Full plane unavailable" }
            return arrayOf(object : Plane() {
                override fun getPixelStride() = this@FakeImage.pixelStride
                override fun getRowStride() = 2 * pixelStride
                override fun getBuffer(): ByteBuffer = ByteBuffer.allocate(4 * pixelStride)
                    .order(ByteOrder.LITTLE_ENDIAN).apply {
                        repeat(4) { if (pixelStride == 2) putShort(1_000.toShort()) else put(255.toByte()) }
                        rewind()
                    }
            })
        }
    }

    private class FakeFrame(private val clock: FakeClock) : Frame() {
        val cameraImage = FakeImage(clock, 1, 0L, 0L)
        // Large distinct Raw/confidence costs prove they are excluded from Full-specific durations.
        val raw = FakeImage(clock, 2, 101L, 103L)
        val confidence = FakeImage(clock, 1, 107L, 109L)
        val full = FakeImage(clock, 2, 11L, 13L)
        var rawUnavailable = false
        var confidenceUnavailable = false
        var fullUnavailable = false
        var forbidFrameAccess = false
        var frameNs = FRAME_NS
        var trackingState = TrackingState.TRACKING
        var cameraAcquisitions = 0
        var rawAcquisitions = 0
        var confidenceAcquisitions = 0
        var fullAcquisitions = 0
        override fun getTimestamp(): Long { check(!forbidFrameAccess); return frameNs }
        override fun getCamera(): Camera { check(!forbidFrameAccess); return fakeCamera(trackingState) }
        override fun acquireCameraImage(): Image { cameraAcquisitions++; return cameraImage }
        override fun acquireRawDepthImage16Bits(): Image {
            rawAcquisitions++
            clock.advance(2L)
            if (rawUnavailable) throw NotYetAvailableException()
            return raw
        }
        override fun acquireRawDepthConfidenceImage(): Image {
            confidenceAcquisitions++
            clock.advance(3L)
            if (confidenceUnavailable) throw NotYetAvailableException()
            return confidence
        }
        override fun acquireDepthImage16Bits(): Image {
            check(rawAcquisitions == 0 || rawUnavailable || raw.closeCalls == 1) { "Raw must close before Full" }
            check(confidenceAcquisitions == 0 || confidenceUnavailable || confidence.closeCalls == 1)
            fullAcquisitions++
            clock.advance(7L)
            if (fullUnavailable) throw NotYetAvailableException()
            return full
        }
    }

    private companion object {
        const val FRAME_NS = 1_000_000_000L
        const val CPU_NS = 900_000_000L
        fun capture(provider: ArCoreFrameProvider, frame: Frame): CameraImageTimestampEvidence =
            requireNotNull(provider.acquireRecognitionCameraImageOrNull(frame)).let {
                it.image.close()
                it.timestampEvidence
            }
        fun fakeCamera(state: TrackingState): Camera = object : Camera() {
            override fun getTrackingState() = state
            override fun getPose() = Pose.IDENTITY
            override fun getImageIntrinsics(): CameraIntrinsics = object : CameraIntrinsics() {
                override fun getFocalLength() = floatArrayOf(500f, 500f)
                override fun getPrincipalPoint() = floatArrayOf(1f, 1f)
                override fun getImageDimensions() = intArrayOf(2, 2)
            }
        }
        fun fakeSession(): Session = object : Session() {
            override fun createAnchor(pose: Pose): Anchor = object : Anchor() {
                override fun getPose() = pose
                override fun getTrackingState() = TrackingState.TRACKING
                override fun detach() = Unit
            }
            override fun <T : Trackable> getAllTrackables(type: Class<T>): Collection<T> = emptyList()
        }
    }
}
