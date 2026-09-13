package kr.co.hanium.dreamup.walksafe.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic numeric fixtures, without camera, model loading, permission or account access. */
@RunWith(AndroidJUnit4::class)
class HeterogeneousOutputComparisonTest {
    private val config = ModelRuntimeConfig("test", "unused", 768, listOf("person", "car"),
        emptySet(), mapOf("default" to 0.5f))

    @Test fun unchangedRawRowsAreComparableEvenWithoutFilteredDetections() {
        val tensor = FloatArray(1_800)
        tensor[4] = 0.1f
        val result = HeterogeneousOutputComparison.compareRaw(tensor, tensor.copyOf(), config)
        assertTrue(result.getBoolean("available"))
        assertTrue(result.getBoolean("withinTolerance"))
        assertFalse(result.getBoolean("groundTruthAccuracyValidated"))
        assertEquals(0.0, result.getDouble("scoreMaxAbsError"), 0.0)
    }

    @Test fun thresholdCrossingFailsEvenWhenNumericDifferenceFitsTolerance() {
        val reference = FloatArray(1_800).apply { this[4] = 0.4999f }
        val candidate = reference.copyOf().apply { this[4] = 0.5001f }
        val result = HeterogeneousOutputComparison.compareRaw(reference, candidate, config)
        assertEquals(1, result.getInt("thresholdCrossingRows"))
        assertFalse(result.getBoolean("withinTolerance"))
    }

    @Test fun nonFiniteAndWrongShapeNeverPass() {
        val reference = FloatArray(1_800)
        assertFalse(HeterogeneousOutputComparison.compareRaw(reference, FloatArray(6), config).getBoolean("available"))
        val candidate = reference.copyOf().apply { this[0] = Float.NaN }
        val result = HeterogeneousOutputComparison.compareRaw(reference, candidate, config)
        assertEquals(1, result.getInt("nonFiniteValues"))
        assertFalse(result.getBoolean("withinTolerance"))
    }

    @Test fun equalCountsCannotHideChangedClassesOrBoxes() {
        val reference = listOf(detection("person", 0.1f))
        assertFalse(HeterogeneousOutputComparison.compareDetections(reference,
            listOf(detection("car", 0.1f))).getBoolean("allDetectionsMatched"))
        assertFalse(HeterogeneousOutputComparison.compareDetections(reference,
            listOf(detection("person", 0.3f))).getBoolean("allDetectionsMatched"))
    }

    @Test fun matchingHandlesReorderingAndAmbiguousNearbyPredictions() {
        val reference = listOf(detection("person", 0.10f), detection("person", 0.115f))
        val candidate = listOf(detection("person", 0.108f), detection("person", 0.091f))
        val result = HeterogeneousOutputComparison.compareDetections(reference, candidate)
        assertTrue(result.getBoolean("allDetectionsMatched"))
        assertEquals(2, result.getInt("matchedCount"))
    }

    @Test fun nonFiniteFilteredCoordinatesCannotMatch() {
        val reference = listOf(detection("person", 0.1f))
        val candidate = listOf(detection("person", Float.NaN))
        val result = HeterogeneousOutputComparison.compareDetections(reference, candidate)
        assertFalse(result.getBoolean("allDetectionsMatched"))
        assertEquals(0, result.getInt("matchedCount"))
    }

    @Test fun rawClassChangeIsNotReducedToEqualDetectionCounts() {
        val reference = FloatArray(1_800)
        val candidate = reference.copyOf().apply { this[5] = 1f }
        val result = HeterogeneousOutputComparison.compareRaw(reference, candidate, config)
        assertEquals(1, result.getInt("classChangedRows"))
        assertFalse(result.getBoolean("withinTolerance"))
    }

    @Test fun crossResolutionCoordinatesAreComparedInNormalizedUnits() {
        val reference = FloatArray(1_800).apply { this[0] = 384f; this[2] = 768f }
        val candidate = FloatArray(1_800).apply { this[0] = 320f; this[2] = 640f }
        val result = HeterogeneousOutputComparison.compareRaw(reference, candidate,
            config.copy(inputSize = 640), referenceInputSize = 768)
        assertTrue(result.getBoolean("withinTolerance"))
        assertEquals("NORMALIZED_MODEL_COORDINATES", result.getString("coordinateUnits"))
    }

    @Test fun reorderedLowConfidenceRowsPassSetComparisonWhileStrictRowsStillFail() {
        val reference = rawTensor(floatArrayOf(10f, 20f, 30f, 40f, 0.1f, 0f),
            floatArrayOf(50f, 60f, 70f, 80f, 0.2f, 1f))
        val candidate = rawTensor(reference.copyOfRange(6, 12), reference.copyOfRange(0, 6))
        val strict = HeterogeneousOutputComparison.compareRaw(reference, candidate, config)
        val unordered = HeterogeneousOutputComparison.compareRawSet(reference, candidate, config)
        assertFalse(strict.getBoolean("withinTolerance"))
        assertTrue(unordered.getBoolean("withinTolerance"))
        assertEquals(300, unordered.getInt("matchedRows"))
        assertEquals(2, unordered.getInt("matchedNonIdentityPairs"))
        val confidence = unordered.getJSONObject("sameRowFailureConfidence")
        assertEquals(2, confidence.getInt("rowCount"))
        assertEquals(2, confidence.getInt("bothBelowClassThresholdRows"))
        assertEquals(2, confidence.getJSONObject("reference").getInt("finiteConfidenceRows"))
        assertEquals(0.2, confidence.getJSONObject("candidate").getDouble("confidenceMax"), 1e-7)
        assertFalse(unordered.getBoolean("replacesStrictSameRowComparison"))
    }

    @Test fun rawSetAugmentationCanMoveAnInitiallyPreferredIdentityPair() {
        fun row(x: Float) = floatArrayOf(x, 10f, 20f, 30f, 0.2f, 0f)
        val reference = rawTensor(row(2.0f), row(2.06f))
        val candidate = rawTensor(row(2.02f), row(1.96f))
        val result = HeterogeneousOutputComparison.compareRawSet(reference, candidate, config)
        assertTrue(result.getBoolean("withinTolerance"))
        assertEquals(300, result.getInt("matchedRows"))
        assertEquals(2, result.getInt("matchedNonIdentityPairs"))
    }

    @Test fun rawSetComparisonPreservesDuplicateMultiplicity() {
        val a = floatArrayOf(10f, 20f, 30f, 40f, 0.2f, 0f)
        val b = floatArrayOf(50f, 60f, 70f, 80f, 0.2f, 0f)
        val result = HeterogeneousOutputComparison.compareRawSet(rawTensor(a, a, b), rawTensor(a, b, b), config)
        assertFalse(result.getBoolean("withinTolerance"))
        assertEquals(299, result.getInt("matchedRows"))
        assertEquals(1, result.getInt("unmatchedReferenceRows"))
        assertEquals(1, result.getInt("unmatchedCandidateRows"))
    }

    @Test fun rawSetCannotHideThresholdClassOrNumericChanges() {
        val reference = rawTensor(floatArrayOf(10f, 20f, 30f, 40f, 0.4999f, 0f))
        val crossing = reference.copyOf().apply { this[4] = 0.5001f }
        val changedClass = reference.copyOf().apply { this[5] = 1f }
        val changedCoordinate = reference.copyOf().apply { this[0] += 0.0501f }
        val changedScore = reference.copyOf().apply { this[4] -= 0.0011f }
        for (candidate in listOf(crossing, changedClass, changedCoordinate, changedScore)) {
            assertFalse(HeterogeneousOutputComparison.compareRawSet(reference, candidate, config).getBoolean("withinTolerance"))
        }
        val close = reference.copyOf().apply { this[0] += 0.0499f; this[4] -= 0.0009f }
        assertTrue(HeterogeneousOutputComparison.compareRawSet(reference, close, config).getBoolean("withinTolerance"))
    }

    @Test fun rawSetRejectsNonFiniteOrMissingTensorsAndPreservesCoordinateUnits() {
        val reference = rawTensor(floatArrayOf(384f, 0f, 768f, 768f, 0.2f, 0f))
        val scaled = rawTensor(floatArrayOf(320f, 0f, 640f, 640f, 0.2f, 0f))
        val normalized = HeterogeneousOutputComparison.compareRawSet(reference, scaled,
            config.copy(inputSize = 640), referenceInputSize = 768)
        assertTrue(normalized.getBoolean("withinTolerance"))
        assertEquals("NORMALIZED_MODEL_COORDINATES", normalized.getString("coordinateUnits"))
        assertEquals(0.001, normalized.getDouble("coordinateAbsoluteTolerance"), 0.0)
        assertFalse(HeterogeneousOutputComparison.compareRawSet(reference, scaled, config).getBoolean("withinTolerance"))
        assertFalse(HeterogeneousOutputComparison.compareRawSet(null, scaled, config).getBoolean("available"))
        assertFalse(HeterogeneousOutputComparison.compareRawSet(reference, FloatArray(6), config).getBoolean("available"))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val candidate = reference.copyOf().apply { this[0] = invalid }
            assertFalse(HeterogeneousOutputComparison.compareRawSet(reference, candidate, config).getBoolean("withinTolerance"))
        }
    }

    @Test fun newerCompletionPreventsLateOlderResultFromChangingTracks() {
        val gate = HeterogeneousResultGate()
        assertEquals("NONE", gate.admit(200L, 200L, 200L, 200L, 1_000L, 1_200L, true))
        assertEquals("OLDER_THAN_COMPLETED", gate.admit(100L, 100L, 200L, 200L, 1_000L, 1_300L, true))
        assertEquals("OLDER_THAN_COMPLETED", gate.admit(200L, 200L, 200L, 200L, 1_000L, 1_300L, true))
    }

    @Test fun freshnessBoundaryAndClockRegressionFailClosed() {
        val gate = HeterogeneousResultGate()
        assertEquals("NONE", gate.admit(100L, 100L, 100L, 100L, 1_000L, 1_800L, true))
        assertEquals("STALE", gate.admit(200L, 200L, 200L, 200L, 1_000L, 1_801L, true))
        assertEquals("STALE", gate.admit(300L, 300L, 299L, 300L, 1_000L, 1_300L, true))
        assertEquals("SOURCE_MISMATCH", gate.admit(300L, 300L, 300L, 300L, 1_000L, 1_300L, false))
    }

    @Test fun candidatesKeepLabelsAndThresholdsWhileDisablingLegacyFallback() {
        val production = TwoModelRuntimeConfig("test", "source.pt", TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
            TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY, config, config, config)
        assertSame(production, HeterogeneousModelCandidates.select(production, "production"))
        for (name in HeterogeneousModelCandidates.names - "production") {
            val selected = HeterogeneousModelCandidates.select(production, name)
            val candidate = requireNotNull(selected.unifiedWalksafe)
            assertEquals(config.classes, candidate.classes)
            assertEquals(config.thresholds, candidate.thresholds)
            assertEquals(config.allowlist, candidate.allowlist)
            assertEquals(production.runtimeSourceModel, selected.runtimeSourceModel)
            assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, selected.primaryModelKey)
            assertNull(selected.fallbackModelKey)
            assertNull(selected.customTactile)
            assertNull(selected.cocoGeneral)
            assertTrue(candidate.asset.startsWith("runtime-candidates/"))
            assertEquals(if (name == "fp32_640") 640 else 768, candidate.inputSize)
        }
    }

    @Test fun gpuCompatibleCandidatePinsApprovedAssetAndHash() {
        val production = TwoModelRuntimeConfig("test", null, TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
            null, config, null, null)
        val selected = requireNotNull(HeterogeneousModelCandidates.select(production, "gpu_compatible_768").unifiedWalksafe)
        assertEquals("runtime-candidates/walksafe_unified_yolo26n_768_float32_gpu_compatible.tflite", selected.asset)
        assertEquals("25ef119d4f07e1bffbd4ac6827fa46a44a0fa3deaaedfb0c867e7c655f813559", selected.artifactSha256)
        assertEquals(768, selected.inputSize)
    }

    @Test(expected = IllegalArgumentException::class)
    fun arbitraryCandidateNamesCannotSelectUnapprovedAssets() {
        HeterogeneousModelCandidates.select(TwoModelRuntimeConfig("test", null,
            TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, null, config, null, null), "../../other")
    }

    private fun rawTensor(vararg rows: FloatArray): FloatArray = FloatArray(1_800).also { result ->
        rows.forEachIndexed { index, row -> require(row.size == 6); row.copyInto(result, index * 6) }
    }

    private fun detection(className: String, x: Float) = DetectionCandidate(className, 0.8f, RectNorm(x, 0.2f, 0.2f, 0.3f))
}
