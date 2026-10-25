package kr.co.hanium.dreamup.walksafe.depth

import android.media.DepthOwnershipTestImage
import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual provider/bundle with SDK subclasses, never native ARCore image buffers. */
class ArCoreDepthBundleOwnershipTest {
    @Test
    fun adoptedBundleAttemptsEveryCloseAndRetainsFirstFailure() {
        for (mask in 1..7) {
            val f = Fixture()
            val failures = listOf(f.frame.raw, f.frame.confidence, f.frame.full).mapIndexedNotNull { i, image ->
                if (mask and (1 shl i) == 0) null else IllegalStateException(image.name).also {
                    image.closeFailure = it
                }
            }
            val bundle = f.runtime()
            val failure = runCatching { bundle.close() }.exceptionOrNull()

            assertSame(failures.first(), failure)
            assertEquals(failures.drop(1), failure!!.suppressed.toList())
            f.assertClosed("raw", "confidence", "full")
            assertEquals(1, f.timings.size)
            assertEquals(mask and 4 == 0, f.timings.single().closeSucceeded)
            assertEquals(7L, f.timings.single().acquisitionDurationNs)
            assertEquals(13L, f.timings.single().closeDurationNs)
            assertEquals(FullDepthRequestPurpose.RAW_AVAILABLE_SUPPLEMENT, f.timings.single().purpose)
        }
    }

    @Test
    fun usePreservesConsumerFailureWhileBundleClosesEveryImage() {
        val f = Fixture()
        val original = IllegalArgumentException("consumer failed")
        val rawClose = IllegalStateException("raw close")
        val confidenceClose = IllegalStateException("confidence close")
        f.frame.raw.closeFailure = rawClose
        f.frame.confidence.closeFailure = confidenceClose
        val failure = runCatching { f.runtime().use { throw original } }.exceptionOrNull()

        assertSame(original, failure)
        assertEquals(listOf(rawClose), original.suppressed.toList())
        assertEquals(listOf(confidenceClose), rawClose.suppressed.toList())
        f.assertClosed("raw", "confidence", "full")
        assertEquals(true, f.timings.single().closeSucceeded)
    }

    @Test
    fun runtimeFrameTimestampFailureRollsBackAllAcquiredImages() {
        val f = Fixture()
        val original = IllegalArgumentException("frame timestamp")
        val closeFailures = listOf(f.frame.raw, f.frame.confidence, f.frame.full).map {
            IllegalStateException(it.name).also { error -> it.closeFailure = error }
        }
        f.frame.frameTimestampFailure = original
        val failure = runCatching { f.runtime() }.exceptionOrNull()

        assertSame(original, failure)
        assertEquals(closeFailures, original.suppressed.toList())
        f.assertClosed("raw", "confidence", "full")
        assertEquals(false, f.timings.single().closeSucceeded)
        assertNull(f.timings.single().conversionSucceeded)
    }

    @Test
    fun runtimeImageTimestampFailuresAfterPairValidationRollBackAllImages() {
        for (name in listOf("raw", "confidence", "full")) {
            val f = Fixture()
            val image = f.frame.image(name)
            val original = IllegalStateException("$name assembly timestamp")
            image.timestampFailure = original
            image.failTimestampAtRead = if (name == "full") 1 else 2
            assertSame(original, runCatching { f.runtime() }.exceptionOrNull())
            f.assertClosed("raw", "confidence", "full")
            assertEquals(true, f.timings.single().closeSucceeded)
        }
    }

    @Test
    fun unexpectedFullAcquisitionFailureRollsBackTheAlreadyAcceptedRawPair() {
        val f = Fixture()
        val original = IllegalArgumentException("full acquisition")
        val closeFailure = IllegalStateException("raw close")
        f.frame.fullFailure = original
        f.frame.raw.closeFailure = closeFailure
        assertSame(original, runCatching { f.runtime() }.exceptionOrNull())
        assertEquals(listOf(closeFailure), original.suppressed.toList())
        f.assertClosed("raw", "confidence")
        assertFalse(f.timings.single().acquisitionSucceeded)
        assertNull(f.timings.single().closeSucceeded)
    }

    @Test
    fun fatalAcquisitionFailureAlsoReleasesAcceptedRawPair() {
        val f = Fixture()
        val original = AssertionError("full acquisition error")
        f.frame.fullFailure = original
        assertSame(original, runCatching { f.runtime() }.exceptionOrNull())
        f.assertClosed("raw", "confidence")
    }

    @Test
    fun classifiedFullFailureKeepsRawAvailableAndRecordsFailureWithoutClosingEarly() {
        val f = Fixture()
        f.frame.fullFailure = NotYetAvailableException()
        val bundle = f.runtime()
        assertTrue(bundle.hasRawDepth)
        assertFalse(bundle.hasFullDepth)
        assertEquals(0, f.frame.raw.closeCalls)
        assertEquals(1, f.provider.runtimeAcquisitionFailureCount(
            DepthAcquisitionStage.FULL_DEPTH, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE,
        ))
        bundle.close()
        f.assertClosed("raw", "confidence")
        assertFalse(f.timings.single().acquisitionSucceeded)
        assertNull(f.timings.single().closeSucceeded)
    }

    @Test
    fun rawOnlyRuntimeTransfersOwnershipAndSkipsFull() {
        val f = Fixture()
        val bundle = f.runtime(includeFull = false)
        assertEquals(listOf("acquire:raw", "acquire:confidence"), f.frame.calls)
        assertTrue(bundle.hasRawDepth)
        assertFalse(bundle.hasFullDepth)
        assertEquals(0, f.frame.raw.closeCalls)
        assertTrue(f.timings.isEmpty())
        bundle.close()
        f.assertClosed("raw", "confidence")
    }

    @Test
    fun runtimeMissingRawFallsBackToFullWithItsOwnTiming() {
        val f = Fixture()
        f.frame.rawFailure = NotYetAvailableException()
        val bundle = f.runtime(includeFull = false)
        assertEquals(listOf("acquire:raw", "acquire:full"), f.frame.calls)
        assertTrue(bundle.hasFullDepth)
        assertFalse(bundle.hasRawDepth)
        bundle.close()
        f.assertClosed("full")
        assertEquals(FullDepthRequestPurpose.RAW_UNAVAILABLE_FALLBACK, f.timings.single().purpose)
        assertEquals(7L, f.timings.single().acquisitionDurationNs)
        assertEquals(13L, f.timings.single().closeDurationNs)
    }

    @Test
    fun confidenceAcquisitionFailureRollsBackRawAndPreservesCause() {
        val f = Fixture()
        val original = IllegalArgumentException("confidence acquisition")
        val closeFailure = IllegalStateException("raw close")
        f.frame.confidenceFailure = original
        f.frame.raw.closeFailure = closeFailure
        assertSame(original, runCatching { f.runtime() }.exceptionOrNull())
        assertEquals(listOf(closeFailure), original.suppressed.toList())
        f.assertClosed("raw")
        assertTrue(f.timings.isEmpty())
    }

    @Test
    fun rejectedPairClosesBothAndPreservesFirstCloseFailure() {
        val f = Fixture()
        f.frame.confidence.timeNs++
        val rawClose = IllegalStateException("raw close")
        val confidenceClose = IllegalStateException("confidence close")
        f.frame.raw.closeFailure = rawClose
        f.frame.confidence.closeFailure = confidenceClose
        assertSame(rawClose, runCatching { f.runtime() }.exceptionOrNull())
        assertEquals(listOf(confidenceClose), rawClose.suppressed.toList())
        f.assertClosed("raw", "confidence")
    }

    @Test
    fun rawPairTimestampFailureClosesBothWithoutSelfSuppression() {
        val f = Fixture()
        val original = IllegalStateException("raw metadata and close failure")
        f.frame.raw.timestampFailure = original
        f.frame.raw.failTimestampAtRead = 1
        f.frame.raw.closeFailure = original
        assertSame(original, runCatching { f.runtime() }.exceptionOrNull())
        assertTrue(original.suppressed.isEmpty())
        f.assertClosed("raw", "confidence")
    }

    @Test
    fun preflightFullAssemblyFailureRetainsTimestampFailureAndReportsClose() {
        val f = Fixture()
        val original = IllegalArgumentException("full timestamp")
        val closeFailure = IllegalStateException("full close")
        f.frame.full.timestampFailure = original
        f.frame.full.failTimestampAtRead = 1
        f.frame.full.closeFailure = closeFailure
        assertSame(original, runCatching { f.preflight() }.exceptionOrNull())
        assertEquals(listOf(closeFailure), original.suppressed.toList())
        f.assertClosed("full")
        assertEquals(listOf("acquire:camera", "acquire:full", "close:full"), f.frame.calls)
        assertEquals(FullDepthRequestPurpose.PREFLIGHT_PRIMARY, f.timings.single().purpose)
        assertEquals(false, f.timings.single().closeSucceeded)
    }

    @Test
    fun preflightRawAssemblyAndCounterMetadataFailuresRollBackRawPair() {
        for (name in listOf("raw", "confidence")) {
            for (timestampRead in listOf(2, 3)) {
                val f = Fixture()
                f.frame.fullFailure = NotYetAvailableException()
                val original = IllegalArgumentException("$name timestamp $timestampRead")
                val closeFailure = IllegalStateException("confidence close")
                f.frame.image(name).apply {
                    timestampFailure = original
                    failTimestampAtRead = timestampRead
                }
                f.frame.confidence.closeFailure = closeFailure
                assertSame(original, runCatching { f.preflight() }.exceptionOrNull())
                assertEquals(listOf(closeFailure), original.suppressed.toList())
                f.assertClosed("raw", "confidence")
                assertTrue(f.provider.preflightDiagnostics().contains("rawPairFrames=0"))
            }
        }
    }

    @Test
    fun successfulPreflightKeepsFullPriorityAndTransfersOwnership() {
        val f = Fixture()
        val bundle = f.preflight()
        assertEquals(listOf("acquire:camera", "acquire:full"), f.frame.calls)
        assertTrue(bundle.hasFullDepth)
        assertFalse(bundle.hasRawDepth)
        assertEquals(0, f.frame.full.closeCalls)
        assertTrue(f.provider.preflightDiagnostics().contains("fullFrames=1 rawPairFrames=0"))
        bundle.close()
        f.assertClosed("full")
        assertEquals(true, f.timings.single().closeSucceeded)
    }

    @Test
    fun successfulPreflightFallbackAcquiresRawOnlyAfterFullUnavailable() {
        val f = Fixture()
        f.frame.fullFailure = NotYetAvailableException()
        val bundle = f.preflight()
        assertEquals(listOf("acquire:camera", "acquire:full", "acquire:raw", "acquire:confidence"), f.frame.calls)
        assertTrue(bundle.hasRawDepth)
        assertFalse(bundle.hasFullDepth)
        assertEquals(0, f.frame.raw.closeCalls)
        assertTrue(f.provider.preflightDiagnostics().contains("fullFrames=0 rawPairFrames=1"))
        bundle.close()
        f.assertClosed("raw", "confidence")
    }

    private class Fixture {
        val frame = TestFrame()
        val timings = mutableListOf<FullDepthTiming>()
        val provider = ArCoreFrameProvider(object : Session() {}, timingClockNs = { frame.clockNs })
        fun runtime(includeFull: Boolean = true) = provider.acquireDepthBundle(
            frame, cameraImageTimestampEvidence = null, includeFullDepthWhenRawAvailable = includeFull,
            fullDepthTimingObserver = timings::add,
        )
        fun preflight() = provider.acquirePreflightDepthBundle(frame, timings::add)
        fun assertClosed(vararg names: String) {
            assertEquals(names.map { "close:$it" }, frame.calls.filter { it.startsWith("close:") })
            for (name in listOf("raw", "confidence", "full")) {
                assertEquals(name, if (name in names) 1 else 0, frame.image(name).closeCalls)
            }
        }
    }

    private class TestFrame : Frame() {
        val calls = mutableListOf<String>()
        val raw = DepthOwnershipTestImage("raw", calls)
        val confidence = DepthOwnershipTestImage("confidence", calls)
        val full = DepthOwnershipTestImage("full", calls)
        var clockNs = 1_000L
        init {
            raw.onClose = Runnable { clockNs += 103L }
            confidence.onClose = Runnable { clockNs += 109L }
            full.onClose = Runnable { clockNs += 13L }
        }
        var rawFailure: Exception? = null
        var frameTimestampFailure: RuntimeException? = null
        var fullFailure: Throwable? = null
        var confidenceFailure: RuntimeException? = null
        override fun getTimestamp(): Long {
            frameTimestampFailure?.let { throw it }
            return 200L
        }
        override fun getCamera() = object : Camera() {
            override fun getTrackingState() = TrackingState.TRACKING
        }
        override fun acquireCameraImage(): Image {
            calls += "acquire:camera"
            throw NotYetAvailableException()
        }
        override fun acquireRawDepthImage16Bits(): Image {
            calls += "acquire:raw"
            clockNs += 2L
            rawFailure?.let { throw it }
            return raw
        }
        override fun acquireRawDepthConfidenceImage(): Image {
            calls += "acquire:confidence"
            clockNs += 3L
            confidenceFailure?.let { throw it }
            return confidence
        }
        override fun acquireDepthImage16Bits(): Image {
            calls += "acquire:full"
            clockNs += 7L
            fullFailure?.let { throw it }
            return full
        }
        fun image(name: String) = when (name) {
            "raw" -> raw
            "confidence" -> confidence
            else -> full
        }
    }
}
