package android.media

import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.CameraImageTimestampEvidence
import kr.co.hanium.dreamup.walksafe.depth.DepthAcquisitionFailureReason
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.FullDepthRequestPurpose
import kr.co.hanium.dreamup.walksafe.depth.FullDepthTiming
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Actual provider/converter path with SDK doubles; no ARCore native/device execution. */
class ArCoreSemanticFrameCaptureTest {
    @Test
    fun defaultDisabledAndUnsupportedNeverCallSemanticFrameApis() {
        val fixture = Fixture()
        assertDepth(fixture.provider.acquireDepthSnapshot(fixture.frame))
        assertEquals(0, fixture.session.configReads)
        assertEquals(0, fixture.frame.labelAcquisitions)
        fixture.session.mode = Config.SemanticMode.DISABLED
        assertFalse(fixture.provider.refreshSemanticAcquisitionConfiguration())
        repeat(2) { assertDepth(fixture.provider.acquireDepthSnapshot(fixture.frame)) }
        assertEquals(1, fixture.session.configReads)
        assertEquals(0, fixture.session.supportReads)
        fixture.session.mode = Config.SemanticMode.ENABLED
        fixture.session.supported = false
        assertFalse(fixture.provider.refreshSemanticAcquisitionConfiguration())
        repeat(2) { assertDepth(fixture.provider.acquireDepthSnapshot(fixture.frame)) }
        assertEquals(2, fixture.session.configReads)
        assertEquals(1, fixture.session.supportReads)
        assertEquals(0, fixture.frame.labelAcquisitions)
        assertEquals(0, fixture.frame.semanticConfidenceAcquisitions)
        assertEquals(0, fixture.session.configureCalls)
    }

    @Test
    fun paddedPixelsOwnBytesAndPreserveIndependentClocksAndReference() {
        val fixture = enabled()
        val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame)
        assertDepth(snapshot)
        val semantic = requireNotNull(snapshot.semanticFrame)
        assertEquals(FRAME_NS, semantic.frameTimestampNs)
        assertEquals(CPU_NS, semantic.cameraImageTimestampNs)
        assertEquals(requireNotNull(snapshot.cameraPoseEvidence).referenceId, semantic.cameraPoseReferenceId)
        assertEquals(LABEL_NS, semantic.labels.timestampNs)
        assertEquals(CONFIDENCE_NS, semantic.confidence.timestampNs)
        assertEquals(3, semantic.labels.width)
        assertEquals(2, semantic.labels.height)
        assertEquals(8, semantic.labels.rowStride)
        assertEquals(2, semantic.labels.pixelStride)
        assertEquals(6, semantic.confidence.rowStride)
        assertEquals(1, semantic.confidence.pixelStride)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), pixels(semantic.labels::valueAt))
        assertEquals(listOf(101, 102, 103, 104, 105, 106), pixels(semantic.confidence::valueAt))
        assertArrayEquals(fixture.frame.labels.data, semantic.labels.copyBytes())
        assertEquals(3, fixture.frame.labels.buffer.position())
        fixture.frame.labels.data.fill(0)
        fixture.frame.semanticConfidence.data.fill(0)
        semantic.labels.copyBytes().fill(0)
        semantic.confidence.copyBytes().fill(0)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), pixels(semantic.labels::valueAt))
        assertEquals(listOf(101, 102, 103, 104, 105, 106), pixels(semantic.confidence::valueAt))
        assertEquals(1, fixture.frame.labels.closeCalls)
        assertEquals(1, fixture.frame.semanticConfidence.closeCalls)
        assertNull(fixture.provider.consumeRuntimeAcquisitionFailure())
    }

    @Test
    fun explicitReceiptReusesCpuImageWithNoFullAndNoPerFrameConfigurationRead() {
        val fixture = enabled()
        val receipt = capture(fixture.provider, fixture.frame)
        val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame, receipt, false)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertFalse(snapshot.hasFullDepth)
        assertEquals(CPU_NS, requireNotNull(snapshot.semanticFrame).cameraImageTimestampNs)
        assertEquals(1, fixture.frame.cameraAcquisitions)
        assertEquals(0, fixture.frame.fullAcquisitions)
        assertEquals(1, fixture.session.configReads)
        assertEquals(1, fixture.session.supportReads)
        assertEquals(1, fixture.frame.labelAcquisitions)
    }

    @Test
    fun missingForeignAndExpiredReceiptsNeverInventCpuOrAcquireAnotherCameraImage() {
        for (kind in listOf("missing", "provider", "frame", "expired")) {
            val fixture = enabled()
            val receipt = when (kind) {
                "missing" -> null
                "provider" -> capture(Fixture().provider, fixture.frame)
                "frame" -> capture(fixture.provider, FakeFrame(fixture.clock))
                else -> capture(fixture.provider, fixture.frame).also {
                    fixture.provider.invalidateRecognitionCameraImageTimestamps()
                }
            }
            val before = fixture.frame.cameraAcquisitions
            val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame, receipt)
            assertNull(snapshot.cameraImageTimestampNs)
            assertFalse(snapshot.hasFreshMetricRawDepth)
            val semantic = requireNotNull(snapshot.semanticFrame)
            assertNull(semantic.cameraImageTimestampNs)
            assertEquals(FRAME_NS, semantic.frameTimestampNs)
            assertEquals(before, fixture.frame.cameraAcquisitions)
        }
    }

    @Test
    fun rawAndConfidenceFallbackAndFailedFullSupplementStillReceiveSemanticObservation() {
        for (kind in listOf("raw", "confidence", "supplement")) {
            val fixture = enabled()
            fixture.frame.rawUnavailable = kind == "raw"
            fixture.frame.rawConfidenceUnavailable = kind == "confidence"
            fixture.frame.fullUnavailable = kind == "supplement"
            val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame)
            assertNotNull(snapshot.semanticFrame)
            assertEquals(kind != "supplement", snapshot.hasFullDepth)
            assertEquals(kind == "supplement", snapshot.hasMetricRawDepth)
            assertEquals(1, fixture.frame.labelAcquisitions)
            assertEquals(1, fixture.frame.labels.closeCalls)
            assertEquals(1, fixture.frame.semanticConfidence.closeCalls)
            assertEquals(DepthAcquisitionFailureReason.NOT_YET_AVAILABLE,
                fixture.provider.consumeRuntimeAcquisitionFailure())
        }
    }

    @Test
    fun eitherSemanticAcquisitionFailurePreservesNormalDepthAndDepthGate() {
        for (second in listOf(false, true)) {
            val fixture = enabled()
            fixture.frame.labelUnavailable = !second
            fixture.frame.semanticConfidenceUnavailable = second
            val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame)
            assertDepth(snapshot)
            assertNull(snapshot.semanticFrame)
            assertEquals(if (second) 1 else 0, fixture.frame.labels.closeCalls)
            assertEquals(0, fixture.frame.semanticConfidence.closeCalls)
            assertEquals(if (second) 1 else 0, fixture.frame.semanticConfidenceAcquisitions)
            assertNull(fixture.provider.consumeRuntimeAcquisitionFailure())
            assertEquals("none", fixture.provider.runtimeAcquisitionDiagnostics())
        }
    }

    @Test
    fun copyTimestampAndIncompletePlaneFailuresCloseBothAndPreserveDepth() {
        for (kind in listOf("labelCopy", "confidenceCopy", "timestamp", "truncated", "stride", "planes")) {
            val fixture = enabled()
            when (kind) {
                "labelCopy" -> fixture.frame.labels.failCopy = true
                "confidenceCopy" -> fixture.frame.semanticConfidence.failCopy = true
                "timestamp" -> fixture.frame.semanticConfidence.failTimestamp = true
                "truncated" -> fixture.frame.labels.buffer.limit(12)
                "stride" -> fixture.frame.semanticConfidence.pixelStrideValue = 0
                else -> fixture.frame.labels.noPlane = true
            }
            val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame)
            assertDepth(snapshot)
            assertNull(snapshot.semanticFrame)
            assertEquals(kind, 1, fixture.frame.labels.closeCalls)
            assertEquals(kind, 1, fixture.frame.semanticConfidence.closeCalls)
            assertNull(fixture.provider.consumeRuntimeAcquisitionFailure())
        }
    }

    @Test
    fun mismatchedShapeCannotBecomeAConsumableSemanticObservation() {
        val fixture = enabled()
        fixture.frame.semanticConfidence.widthValue = 2
        assertNull(fixture.provider.acquireDepthSnapshot(fixture.frame).semanticFrame)
        assertEquals(1, fixture.frame.labels.closeCalls)
        assertEquals(1, fixture.frame.semanticConfidence.closeCalls)
        assertNull(fixture.provider.consumeRuntimeAcquisitionFailure())
    }

    @Test
    fun semanticCloseFailuresStillReleaseBothAndKeepDepth() {
        for (labelFails in listOf(false, true)) {
            val fixture = enabled()
            fixture.frame.labels.failClose = labelFails
            fixture.frame.semanticConfidence.failClose = !labelFails
            val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame)
            assertDepth(snapshot)
            assertNull(snapshot.semanticFrame)
            assertEquals(1, fixture.frame.labels.closeCalls)
            assertEquals(1, fixture.frame.semanticConfidence.closeCalls)
            assertNull(fixture.provider.consumeRuntimeAcquisitionFailure())
        }
    }

    @Test
    fun existingFatalDepthReasonSurvivesOptionalSemanticSuccessOrFailure() {
        for (semanticFails in listOf(false, true)) {
            val fixture = enabled()
            fixture.frame.rawFatal = true
            fixture.frame.semanticConfidenceUnavailable = semanticFails
            val snapshot = fixture.provider.acquireDepthSnapshot(fixture.frame)
            assertTrue(snapshot.hasFreshFullDepth)
            assertEquals(!semanticFails, snapshot.semanticFrame != null)
            assertEquals(DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED,
                fixture.provider.consumeRuntimeAcquisitionFailure())
            fixture.frame.rawFatal = false
            assertDepth(fixture.provider.acquireDepthSnapshot(fixture.frame))
            assertNull(fixture.provider.consumeRuntimeAcquisitionFailure())
        }
    }

    @Test
    fun existingFullConversionFailurePropagatesBeforeSemanticAccess() {
        val fixture = enabled()
        fixture.frame.full.failCopy = true
        try {
            fixture.provider.acquireDepthSnapshot(fixture.frame)
            fail("Depth copy failure must remain a failure")
        } catch (expected: IllegalStateException) {
            assertEquals("synthetic copy failure", expected.message)
        }
        assertEquals(1, fixture.frame.raw.closeCalls)
        assertEquals(1, fixture.frame.rawConfidence.closeCalls)
        assertEquals(1, fixture.frame.full.closeCalls)
        assertEquals(0, fixture.frame.labelAcquisitions)
        assertEquals(0, fixture.frame.semanticConfidenceAcquisitions)
    }

    @Test
    fun refreshFailureAndCameraSupportChangesDisableTheCachedState() {
        val fixture = enabled()
        fixture.session.failConfig = true
        assertFalse(fixture.provider.refreshSemanticAcquisitionConfiguration())
        assertNull(fixture.provider.acquireDepthSnapshot(fixture.frame).semanticFrame)
        fixture.session.failConfig = false
        fixture.session.failSupport = true
        assertFalse(fixture.provider.refreshSemanticAcquisitionConfiguration())
        assertNull(fixture.provider.acquireDepthSnapshot(fixture.frame).semanticFrame)
        fixture.session.failSupport = false
        assertTrue(fixture.provider.refreshSemanticAcquisitionConfiguration())
        assertNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).semanticFrame)
        fixture.session.supported = false
        assertFalse(fixture.provider.refreshSemanticAcquisitionConfiguration())
        assertNull(fixture.provider.acquireDepthSnapshot(fixture.frame).semanticFrame)
        assertEquals(1, fixture.frame.labelAcquisitions)
        assertEquals(0, fixture.session.configureCalls)
    }

    @Test
    fun closeRejectsAllNativeAccessAndTrackingResetDoesNotCarryOldAnchorReference() {
        val fixture = enabled()
        val previous = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).semanticFrame)
        fixture.frame.trackingState = TrackingState.PAUSED
        val untracked = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).semanticFrame)
        assertNull(untracked.cameraPoseReferenceId)
        assertEquals(1, fixture.session.detaches)
        fixture.frame.trackingState = TrackingState.TRACKING
        val resumed = requireNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame).semanticFrame)
        assertNotEquals(previous.cameraPoseReferenceId, resumed.cameraPoseReferenceId)
        fixture.provider.close()
        val beforeConfig = fixture.session.configReads
        val beforeLabels = fixture.frame.labelAcquisitions
        fixture.frame.forbidAccess = true
        assertFalse(fixture.provider.refreshSemanticAcquisitionConfiguration())
        assertNull(fixture.provider.acquireDepthSnapshot(fixture.frame).semanticFrame)
        assertNull(fixture.provider.acquireDepthSnapshot(fixture.frame, null).semanticFrame)
        assertEquals(beforeConfig, fixture.session.configReads)
        assertEquals(beforeLabels, fixture.frame.labelAcquisitions)
        assertEquals(2, fixture.session.detaches)
        val replacement = Fixture()
        assertNull(replacement.provider.acquireDepthSnapshot(replacement.frame).semanticFrame)
        assertEquals(0, replacement.frame.labelAcquisitions)
    }

    @Test
    fun fullPurposeAndTimingExcludeSemanticAcquisitionCopyAndCloseCosts() {
        for (fallback in listOf(false, true)) {
            val fixture = enabled()
            fixture.frame.rawUnavailable = fallback
            val events = mutableListOf<FullDepthTiming>()
            assertNotNull(fixture.provider.acquireDepthSnapshot(fixture.frame,
                fullDepthTimingObserver = events::add).semanticFrame)
            val event = events.single()
            assertEquals(if (fallback) FullDepthRequestPurpose.RAW_UNAVAILABLE_FALLBACK
                else FullDepthRequestPurpose.RAW_AVAILABLE_SUPPLEMENT, event.purpose)
            assertEquals(7L, event.acquisitionDurationNs)
            assertEquals(11L, event.conversionDurationNs)
            assertEquals(13L, event.closeDurationNs)
            assertEquals(24L, event.conversionAndCloseDurationNs)
            assertTrue(fixture.clock.now > 100_000L)
        }
    }

    private class Fixture {
        val clock = FakeClock()
        val session = FakeSession()
        val frame = FakeFrame(clock)
        val provider = ArCoreFrameProvider(session, clock::read)
    }

    private class FakeClock {
        var now = 10_000L
        fun read() = now
        fun advance(cost: Long) { now += cost }
    }

    private class FakeSession : Session() {
        var mode = Config.SemanticMode.ENABLED
        var supported = true
        var failConfig = false
        var failSupport = false
        var configReads = 0
        var supportReads = 0
        var configureCalls = 0
        var detaches = 0
        override fun getConfig(): Config {
            configReads++
            check(!failConfig)
            return object : Config() { override fun getSemanticMode() = mode }
        }
        override fun isSemanticModeSupported(mode: Config.SemanticMode): Boolean {
            supportReads++
            assertEquals(Config.SemanticMode.ENABLED, mode)
            check(!failSupport)
            return supported
        }
        override fun configure(config: Config) { configureCalls++; error("refresh cannot configure") }
        override fun createAnchor(pose: Pose): Anchor = object : Anchor() {
            override fun getPose() = pose
            override fun getTrackingState() = TrackingState.TRACKING
            override fun detach() { detaches++ }
        }
        override fun <T : Trackable> getAllTrackables(type: Class<T>): Collection<T> = emptyList()
    }

    private class FakeImage(
        private val clock: FakeClock,
        val data: ByteArray,
        private val timeNs: Long,
        var widthValue: Int,
        private val heightValue: Int,
        private val rowStrideValue: Int,
        var pixelStrideValue: Int,
        private val copyCost: Long = 0L,
        private val closeCost: Long = 0L,
    ) : Image() {
        val buffer = ByteBuffer.wrap(data)
        var closeCalls = 0
        var failCopy = false
        var failTimestamp = false
        var failClose = false
        var noPlane = false
        override fun getWidth() = widthValue
        override fun getHeight() = heightValue
        override fun getFormat() = 35
        override fun getTimestamp(): Long { check(!failTimestamp); return timeNs }
        override fun close() { closeCalls++; clock.advance(closeCost); check(!failClose) }
        override fun getPlanes(): Array<Plane> {
            clock.advance(copyCost)
            check(!failCopy) { "synthetic copy failure" }
            if (noPlane) return emptyArray()
            return arrayOf(object : Plane() {
                override fun getPixelStride() = pixelStrideValue
                override fun getRowStride() = rowStrideValue
                override fun getBuffer() = this@FakeImage.buffer
            })
        }
    }

    private class FakeFrame(private val clock: FakeClock) : Frame() {
        val cameraImage = FakeImage(clock, ByteArray(4), CPU_NS, 2, 2, 2, 1)
        val raw = FakeImage(clock, byteArrayOf(-24, 3, -24, 3, -24, 3, -24, 3), CPU_NS, 2, 2, 4, 2)
        val rawConfidence = FakeImage(clock, ByteArray(4) { -1 }, CPU_NS, 2, 2, 2, 1)
        val full = FakeImage(clock, byteArrayOf(-24, 3, -24, 3, -24, 3, -24, 3), CPU_NS,
            2, 2, 4, 2, 11L, 13L)
        val labels = FakeImage(clock, byteArrayOf(1, 77, 2, 77, 3, 77, 77, 77, 4, 77, 5, 77, 6),
            LABEL_NS, 3, 2, 8, 2, 10_000L, 20_000L).apply { buffer.position(3) }
        val semanticConfidence = FakeImage(clock, byteArrayOf(101, 102, 103, 88, 88, 88, 104, 105, 106),
            CONFIDENCE_NS, 3, 2, 6, 1, 30_000L, 40_000L)
        var trackingState = TrackingState.TRACKING
        var rawUnavailable = false
        var rawFatal = false
        var rawConfidenceUnavailable = false
        var fullUnavailable = false
        var labelUnavailable = false
        var semanticConfidenceUnavailable = false
        var cameraAcquisitions = 0
        var fullAcquisitions = 0
        var labelAcquisitions = 0
        var semanticConfidenceAcquisitions = 0
        var forbidAccess = false
        override fun getTimestamp(): Long { check(!forbidAccess); return FRAME_NS }
        override fun getCamera(): Camera {
            check(!forbidAccess)
            return object : Camera() {
                override fun getTrackingState() = this@FakeFrame.trackingState
                override fun getPose() = Pose.IDENTITY
                override fun getImageIntrinsics(): CameraIntrinsics = object : CameraIntrinsics() {
                    override fun getFocalLength() = floatArrayOf(500f, 500f)
                    override fun getPrincipalPoint() = floatArrayOf(1f, 1f)
                    override fun getImageDimensions() = intArrayOf(2, 2)
                }
            }
        }
        override fun acquireCameraImage(): Image { check(!forbidAccess); cameraAcquisitions++; return cameraImage }
        override fun acquireRawDepthImage16Bits(): Image {
            check(!forbidAccess)
            if (rawUnavailable) throw NotYetAvailableException()
            if (rawFatal) throw ResourceExhaustedException()
            return raw
        }
        override fun acquireRawDepthConfidenceImage(): Image {
            check(!forbidAccess)
            if (rawConfidenceUnavailable) throw NotYetAvailableException()
            return rawConfidence
        }
        override fun acquireDepthImage16Bits(): Image {
            check(!forbidAccess)
            fullAcquisitions++
            clock.advance(7L)
            if (fullUnavailable) throw NotYetAvailableException()
            return full
        }
        override fun acquireSemanticImage(): Image {
            check(!forbidAccess)
            check(rawUnavailable || rawFatal || raw.closeCalls > 0) { "depth must finish first" }
            check(rawUnavailable || rawFatal || rawConfidenceUnavailable || rawConfidence.closeCalls > 0)
            check(fullAcquisitions == 0 || fullUnavailable || full.closeCalls > 0)
            labelAcquisitions++
            clock.advance(50_000L)
            if (labelUnavailable) throw ResourceExhaustedException()
            return labels
        }
        override fun acquireSemanticConfidenceImage(): Image {
            check(!forbidAccess)
            semanticConfidenceAcquisitions++
            clock.advance(60_000L)
            if (semanticConfidenceUnavailable) throw ResourceExhaustedException()
            return semanticConfidence
        }
    }

    private companion object {
        const val FRAME_NS = 1_000_000_000L
        const val CPU_NS = 900_000_000L
        const val LABEL_NS = 700_000_000L
        const val CONFIDENCE_NS = 710_000_000L
        fun enabled() = Fixture().also { assertTrue(it.provider.refreshSemanticAcquisitionConfiguration()) }
        fun capture(provider: ArCoreFrameProvider, frame: Frame): CameraImageTimestampEvidence =
            requireNotNull(provider.acquireRecognitionCameraImageOrNull(frame)).let {
                it.image.close()
                it.timestampEvidence
            }
        fun assertDepth(snapshot: DepthFrameSnapshot) {
            assertTrue(snapshot.hasFreshMetricRawDepth)
            assertTrue(snapshot.hasFreshFullDepth)
            assertArrayEquals(intArrayOf(1_000, 1_000, 1_000, 1_000), snapshot.rawDepth?.millimeters)
            assertArrayEquals(intArrayOf(1_000, 1_000, 1_000, 1_000), snapshot.fullDepth?.millimeters)
        }
        fun pixels(read: (Int, Int) -> Int) = (0 until 2).flatMap { y -> (0 until 3).map { x -> read(x, y) } }
    }
}
