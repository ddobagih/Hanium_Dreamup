package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightDepthAcquisitionTest {
    @Test
    fun fullDepthSkipsRawAcquisitionAndTransfersOwnershipToTheBundle() {
        val full = FakeImage()
        val bundle = acquirePreflightDepth(
            acquireFullDepth = { full },
            acquireRawDepth = { error("Raw must not be acquired while full is held") },
            acquireRawConfidence = { error("Confidence must not be acquired while full is held") },
            createBundle = ::Images,
        )

        assertSame(full, bundle.full)
        assertNull(bundle.raw)
        assertNull(bundle.confidence)
        assertEquals(0, full.closeCount)
        bundle.close()
        assertEquals(1, full.closeCount)
    }

    @Test
    fun missingFullDepthFallsBackToRawPairInOrder() {
        val calls = mutableListOf<String>()
        val raw = FakeImage()
        val confidence = FakeImage()
        val bundle = acquirePreflightDepth(
            acquireFullDepth = { calls += "full"; null },
            acquireRawDepth = { calls += "raw"; raw },
            acquireRawConfidence = { calls += "confidence"; confidence },
            createBundle = ::Images,
        )

        assertEquals(listOf("full", "raw", "confidence"), calls)
        assertNull(bundle.full)
        assertSame(raw, bundle.raw)
        assertSame(confidence, bundle.confidence)
        assertEquals(0, raw.closeCount)
        assertEquals(0, confidence.closeCount)
        bundle.close()
        assertEquals(1, raw.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun missingConfidenceReleasesRawWithoutPublishingIncompleteEvidence() {
        val raw = FakeImage()
        val bundle = acquirePreflightDepth({ null }, { raw }, { null }, ::Images)

        assertEquals(Images(null, null, null), bundle)
        assertEquals(1, raw.closeCount)
        bundle.close()
        assertEquals(1, raw.closeCount)
    }

    @Test
    fun missingRawSkipsConfidenceAcquisition() {
        val bundle = acquirePreflightDepth(
            { null }, { null }, { error("No raw image to match") }, ::Images,
        )

        assertEquals(Images(null, null, null), bundle)
    }

    @Test
    fun confidenceAcquisitionExceptionReleasesRaw() {
        val raw = FakeImage()
        val failure = runCatching {
            acquirePreflightDepth({ null }, { raw }, { error("acquisition failed") }, ::Images)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, raw.closeCount)
    }

    @Test
    fun bundleConstructionFailureReleasesFullOrRawPair() {
        val full = FakeImage()
        assertTrue(runCatching {
            acquirePreflightDepth({ full }, { null }, { null }) { _, _, _ ->
                error("bundle failed")
            }
        }.isFailure)
        assertEquals(1, full.closeCount)

        val raw = FakeImage()
        val confidence = FakeImage()
        assertTrue(runCatching {
            acquirePreflightDepth({ null }, { raw }, { confidence }) { _, _, _ ->
                error("bundle failed")
            }
        }.isFailure)
        assertEquals(1, raw.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun samePositiveObservationTransfersBothImagesWithoutClosingThemEarly() {
        val raw = FakeImage(timestampNs = 90L)
        val confidence = FakeImage(timestampNs = 90L)
        val pair = acquireMatchingRawDepthPair({ raw }, { confidence }, ::matchingObservation)

        assertSame(raw, pair?.first)
        assertSame(confidence, pair?.second)
        assertEquals(0, raw.closeCount)
        assertEquals(0, confidence.closeCount)
        pair?.first?.close()
        pair?.second?.close()
    }

    @Test
    fun mismatchedOrNonPositiveObservationsCloseBothImagesBeforePreflightPublishesMissingRaw() {
        listOf(90L to 91L, 0L to 0L, -1L to -1L).forEach { (rawNs, confidenceNs) ->
            val raw = FakeImage(timestampNs = rawNs)
            val confidence = FakeImage(timestampNs = confidenceNs)
            val bundle = acquirePreflightDepth(
                acquireFullDepth = { null },
                acquireRawDepth = { raw },
                acquireRawConfidence = { confidence },
                isMatchingRawPair = ::matchingObservation,
            ) { full, acceptedRaw, acceptedConfidence ->
                assertEquals(1, raw.closeCount)
                assertEquals(1, confidence.closeCount)
                Images(full, acceptedRaw, acceptedConfidence)
            }

            assertEquals(Images(null, null, null), bundle)
            bundle.close()
            assertEquals(1, raw.closeCount)
            assertEquals(1, confidence.closeCount)
        }
    }

    @Test
    fun sameTimestampWithDifferentDimensionsIsNotPublishedAsRawPair() {
        val raw = FakeImage(width = 2, height = 2)
        val confidence = FakeImage(width = 1, height = 4)
        val pair = acquireMatchingRawDepthPair({ raw }, { confidence }, ::matchingObservation)

        assertNull(pair)
        assertEquals(1, raw.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun fullDepthPreservesItsPriorityWithoutAcquiringOrValidatingRaw() {
        val full = FakeImage()
        val bundle = acquirePreflightDepth(
            acquireFullDepth = { full },
            acquireRawDepth = { error("Full depth is already held") },
            acquireRawConfidence = { error("No raw observation was acquired") },
            isMatchingRawPair = { _, _ -> error("No raw pair to validate") },
            createBundle = ::Images,
        )

        assertSame(full, bundle.full)
        bundle.close()
        assertEquals(1, full.closeCount)
    }

    @Test
    fun pairValidationExceptionReleasesBothImagesAndPreservesTheFailure() {
        val raw = FakeImage()
        val confidence = FakeImage()
        val failure = runCatching {
            acquireMatchingRawDepthPair({ raw }, { confidence }) { _, _ -> error("timestamp read failed") }
        }.exceptionOrNull()

        assertEquals("timestamp read failed", failure?.message)
        assertEquals(1, raw.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    @Test
    fun rejectedPairStillClosesConfidenceWhenRawCloseThrows() {
        val raw = FakeImage(failClose = true)
        val confidence = FakeImage()
        assertTrue(runCatching {
            acquireMatchingRawDepthPair({ raw }, { confidence }) { _, _ -> false }
        }.isFailure)

        assertEquals(1, raw.closeCount)
        assertEquals(1, confidence.closeCount)
    }

    private fun matchingObservation(raw: FakeImage, confidence: FakeImage): Boolean =
        rawConfidenceObservationMatches(
            raw.timestampNs, confidence.timestampNs, raw.width, raw.height, confidence.width, confidence.height,
        )

    private data class Images(
        val full: FakeImage?,
        val raw: FakeImage?,
        val confidence: FakeImage?,
    ) : AutoCloseable {
        override fun close() {
            full?.close()
            raw?.close()
            confidence?.close()
        }
    }

    private class FakeImage(
        val timestampNs: Long = 100L,
        val width: Int = 2,
        val height: Int = 2,
        private val failClose: Boolean = false,
    ) : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
            if (failClose) error("close failed")
        }
    }
}
