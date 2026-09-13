package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthFrameSnapshotTest {
    private val depth = DepthImage16(2, 2, intArrayOf(1_000, 1_000, 1_000, 1_000))
    private val confidence = ConfidenceImage8(2, 2, ByteArray(4) { 255.toByte() })

    @Test
    fun matchingRawTimestampIsFreshQualificationEvidence() {
        val snapshot = snapshot(rawTimestampNs = 100L)

        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertEquals(1f, snapshot.rawDepthFreshnessQuality, 0f)
    }

    @Test
    fun actualCameraImageTimestampQualifiesDepthAcrossDifferentFrameClockDomains() {
        val cameraTimestamp = 1_000_000_000L
        val snapshot = snapshot(cameraTimestamp).copy(
            frameTimestampNs = cameraTimestamp + 500_000L,
            cameraImageTimestampNs = cameraTimestamp,
            fullDepth = depth,
            fullDepthTimestampNs = cameraTimestamp,
        )

        assertTrue(snapshot.rawDepthMatchesCameraImage)
        assertTrue(snapshot.hasFreshMetricRawDepth)
        assertTrue(snapshot.hasFreshFullDepth)
        assertEquals(4, snapshot.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true))
        assertEquals(cameraTimestamp + 500_000L, snapshot.frameTimestampNs)
    }

    @Test
    fun matchingFrameClockCannotSubstituteForMatchingTheActualCameraImage() {
        val snapshot = snapshot(100L).copy(
            cameraImageTimestampNs = 99L,
            fullDepth = depth,
            fullDepthTimestampNs = 100L,
        )

        assertFalse(snapshot.rawDepthMatchesCameraImage)
        assertFalse(snapshot.hasFreshMetricRawDepth)
        assertFalse(snapshot.hasFreshFullDepth)
        assertEquals(0, snapshot.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true))
    }

    @Test
    fun missingOrNonPositiveCameraImageTimestampNeverFallsBackToTheFrameClock() {
        listOf(null, 0L, -1L).forEach { cameraTimestamp ->
            val snapshot = snapshot(100L).copy(
                cameraImageTimestampNs = cameraTimestamp,
                fullDepth = depth,
                fullDepthTimestampNs = 100L,
            )

            assertFalse(snapshot.rawDepthMatchesCameraImage)
            assertFalse(snapshot.hasFreshMetricRawDepth)
            assertFalse(snapshot.hasFreshFullDepth)
            assertEquals(0.70f, snapshot.rawDepthFreshnessQuality, 0f)
            assertEquals(0, snapshot.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true))
        }
    }

    @Test
    fun missingOrNonPositiveRawTimestampIsNotFreshQualificationEvidence() {
        listOf(null, 0L, -1L).forEach { rawTimestamp ->
            val snapshot = snapshot(100L).copy(rawDepthTimestampNs = rawTimestamp)

            assertFalse(snapshot.hasFreshMetricRawDepth)
            assertEquals(0.70f, snapshot.rawDepthFreshnessQuality, 0f)
            assertEquals(0, snapshot.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true))
        }
    }

    @Test
    fun nonPositiveFrameTimestampCannotQualifyEvenWithMatchingImageClocks() {
        listOf(0L, -1L).forEach { frameTimestamp ->
            val snapshot = snapshot(100L).copy(
                frameTimestampNs = frameTimestamp,
                fullDepth = depth,
                fullDepthTimestampNs = 100L,
            )

            assertFalse(snapshot.hasFreshMetricRawDepth)
            assertFalse(snapshot.hasFreshFullDepth)
        }
    }

    @Test
    fun bundlePreservesTheActualCameraImageTimestampWithoutReplacingFrameIdentity() {
        val snapshot = ArCoreDepthBundle(
            frameTimestampNs = 100L,
            rawDepthTimestampNs = null,
            rawDepth = null,
            rawConfidence = null,
            fullDepth = null,
            cameraImageTimestampNs = 95L,
        ).toSnapshotAndClose()

        assertEquals(100L, snapshot.frameTimestampNs)
        assertEquals(95L, snapshot.cameraImageTimestampNs)
    }

    @Test
    fun reprojectedRawTimestampIsDownweightedAndNotFreshQualificationEvidence() {
        val snapshot = snapshot(rawTimestampNs = 90L)

        assertFalse(snapshot.hasFreshMetricRawDepth)
        assertEquals(0.70f, snapshot.rawDepthFreshnessQuality, 0f)
    }

    @Test
    fun reprojectedRawDepthKeepsRuntimeDistanceAvailableBetweenTenHertzRawObservations() {
        val reprojected = snapshot(rawTimestampNs = 90L)

        assertEquals(4, reprojected.validMetricSampleCount(0.35, 0.2, 8.0))
        assertEquals(0, reprojected.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true))
    }

    @Test
    fun samplesStillNeedMatchingConfidenceDimensionsAndValidMetricRange() {
        val invalidPair = snapshot(100L).copy(rawConfidence = ConfidenceImage8(1, 1, byteArrayOf(-1)))
        assertEquals(0, invalidPair.validMetricSampleCount(0.35, 0.2, 8.0))

        val mixed = snapshot(100L).copy(
            rawDepth = DepthImage16(2, 2, intArrayOf(199, 200, 8_000, 8_001)),
            rawConfidence = ConfidenceImage8(2, 2, byteArrayOf(-1, -1, 0, -1)),
        )
        assertEquals(1, mixed.validMetricSampleCount(0.35, 0.2, 8.0))
    }

    @Test
    fun fullDepthCanQualifyWhenTheRawImageIsReprojected() {
        val full = snapshot(90L).copy(fullDepth = depth, fullDepthTimestampNs = 100L)

        assertEquals(4, full.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true))
    }

    @Test
    fun oldFutureAndUnknownFullDepthCannotRefreshMetricEvidenceEvenWithinTheSameMillisecond() {
        val cameraTimestamp = 1_000_000_000L
        listOf(null, 0L, cameraTimestamp - 1L, cameraTimestamp + 1L).forEach { fullTimestamp ->
            val full = snapshot(90L).copy(
                frameTimestampNs = cameraTimestamp + 500_000L,
                cameraImageTimestampNs = cameraTimestamp,
                rawDepth = null,
                rawConfidence = null,
                fullDepth = depth,
                fullDepthTimestampNs = fullTimestamp,
            )

            assertTrue(full.hasFullDepth)
            assertFalse(full.hasFreshFullDepth)
            assertEquals(0, full.validMetricSampleCount(0.35, 0.2, 8.0))
        }
    }

    @Test
    fun zeroCameraAndDepthTimestampsDoNotRepresentACapturedFrame() {
        val uninitialized = snapshot(0L).copy(
            frameTimestampNs = 0L,
            cameraImageTimestampNs = 0L,
            rawDepth = null,
            rawConfidence = null,
            fullDepth = depth,
            fullDepthTimestampNs = 0L,
        )

        assertFalse(uninitialized.hasFreshFullDepth)
        assertEquals(0, uninitialized.validMetricSampleCount(0.35, 0.2, 8.0))
    }

    @Test
    fun staleFullDepthDoesNotRemoveUsableRawDepth() {
        val mixed = snapshot(100L).copy(fullDepth = depth, fullDepthTimestampNs = 90L)

        assertEquals(4, mixed.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true))
    }

    @Test
    fun matchingConfidenceClockVerifiesTheRawObservationIndependentlyOfCpuFreshness() {
        val reprojected = snapshot(90L).copy(rawConfidenceTimestampNs = 90L)

        assertTrue(reprojected.rawConfidenceMatchesRawDepth)
        assertTrue(reprojected.hasMetricRawDepth)
        assertFalse(reprojected.rawDepthMatchesCameraImage)
        assertFalse(reprojected.hasFreshMetricRawDepth)
        assertEquals(0.70f, reprojected.rawDepthFreshnessQuality, 0f)
        assertEquals(4, reprojected.validMetricSampleCount(0.35, 0.2, 8.0))
    }

    @Test
    fun differentConfidenceObservationBlocksRawMetricEvidenceWithoutChangingRawFreshness() {
        val mismatched = snapshot(100L).copy(rawConfidenceTimestampNs = 99L)

        assertFalse(mismatched.rawConfidenceMatchesRawDepth)
        assertFalse(mismatched.hasMetricRawDepth)
        assertFalse(mismatched.hasFreshMetricRawDepth)
        assertTrue(mismatched.rawDepthMatchesCameraImage)
        assertEquals(1f, mismatched.rawDepthFreshnessQuality, 0f)
        assertEquals(0, mismatched.validMetricSampleCount(0.35, 0.2, 8.0))
        assertEquals(0, mismatched.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true))
    }

    @Test
    fun unknownConfidenceClockPreservesLegacyShapeButCannotClaimStrictPairVerification() {
        val legacy = snapshot(100L)

        assertTrue(legacy.hasMetricRawDepth)
        assertFalse(legacy.rawConfidenceMatchesRawDepth)
        assertEquals(4, legacy.validMetricSampleCount(0.35, 0.2, 8.0))
    }

    @Test
    fun zeroNegativeAndMismatchedImageClocksNeverVerifyTheRawPair() {
        listOf(0L to 0L, -1L to -1L, 100L to 0L, 100L to -1L, 100L to 101L).forEach { (rawNs, confidenceNs) ->
            val invalid = snapshot(rawNs).copy(rawConfidenceTimestampNs = confidenceNs)

            assertFalse(invalid.rawConfidenceMatchesRawDepth)
            assertFalse(invalid.hasMetricRawDepth)
            assertEquals(0, invalid.validMetricSampleCount(0.35, 0.2, 8.0))
        }
    }

    @Test
    fun matchingImageClocksStillRequireTheSameDepthAndConfidenceDimensions() {
        val invalid = snapshot(100L).copy(
            rawConfidenceTimestampNs = 100L,
            rawConfidence = ConfidenceImage8(1, 1, byteArrayOf(-1)),
        )

        assertFalse(invalid.rawConfidenceMatchesRawDepth)
        assertFalse(invalid.hasMetricRawDepth)
        assertEquals(0, invalid.validMetricSampleCount(0.35, 0.2, 8.0))
    }

    @Test
    fun rawPairMismatchDoesNotChangeTheIndependentFullDepthClock() {
        val full = snapshot(90L).copy(
            rawConfidenceTimestampNs = 91L,
            fullDepth = depth,
            fullDepthTimestampNs = 100L,
        )

        assertFalse(full.hasMetricRawDepth)
        assertTrue(full.hasFreshFullDepth)
        assertEquals(4, full.validMetricSampleCount(0.35, 0.2, 8.0, requireFreshRaw = true))
        assertFalse(full.copy(fullDepthTimestampNs = 91L).hasFreshFullDepth)
    }

    @Test
    fun bundleCopiesConfidenceClockMetadataWithoutReplacingAnyOtherClock() {
        val copied = ArCoreDepthBundle(
            frameTimestampNs = 100L,
            rawDepthTimestampNs = 90L,
            rawDepth = null,
            rawConfidence = null,
            fullDepth = null,
            fullDepthTimestampNs = 95L,
            cameraImageTimestampNs = 95L,
            rawConfidenceTimestampNs = 91L,
        ).toSnapshotAndClose()

        assertEquals(100L, copied.frameTimestampNs)
        assertEquals(90L, copied.rawDepthTimestampNs)
        assertEquals(91L, copied.rawConfidenceTimestampNs)
        assertEquals(95L, copied.fullDepthTimestampNs)
        assertEquals(95L, copied.cameraImageTimestampNs)
        assertFalse(copied.rawConfidenceMatchesRawDepth)
    }

    private fun snapshot(rawTimestampNs: Long): DepthFrameSnapshot = DepthFrameSnapshot(
        frameTimestampNs = 100L,
        rawDepth = depth,
        rawConfidence = confidence,
        fullDepth = null,
        rawDepthTimestampNs = rawTimestampNs,
        cameraImageTimestampNs = 100L,
    )
}
