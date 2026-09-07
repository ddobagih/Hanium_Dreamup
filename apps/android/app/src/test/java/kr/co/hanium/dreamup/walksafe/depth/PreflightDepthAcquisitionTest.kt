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

    private class FakeImage : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() { closeCount += 1 }
    }
}
