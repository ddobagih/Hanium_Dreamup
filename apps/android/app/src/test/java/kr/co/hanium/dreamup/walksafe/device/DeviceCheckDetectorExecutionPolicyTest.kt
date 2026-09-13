package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorTiming
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCheckDetectorExecutionPolicyTest {
    @Test
    fun emptyDetectionsPassAfterARealFinalInference() {
        val result = AndroidDetectionResult(
            detections = emptyList(),
            timing = AndroidDetectorTiming(
                yuvDecodeMs = 2L,
                modelInferenceMs = 12L,
                totalMs = 18L,
                completedModels = listOf("walksafe_unified"),
            ),
        )

        assertTrue(DeviceCheckDetectorExecutionPolicy.passes(result))
    }

    @Test
    fun noopPartialAndMissingTimingCannotPass() {
        assertFalse(DeviceCheckDetectorExecutionPolicy.passes(AndroidDetectionResult.empty()))
        assertFalse(
            DeviceCheckDetectorExecutionPolicy.passes(
                AndroidDetectionResult(
                    detections = emptyList(),
                    timing = AndroidDetectorTiming(
                        yuvDecodeMs = 2L,
                        cocoInferenceMs = 8L,
                        totalMs = 12L,
                        completedModels = listOf("coco"),
                    ),
                    partial = true,
                ),
            ),
        )
        assertFalse(
            DeviceCheckDetectorExecutionPolicy.passes(
                AndroidDetectionResult(
                    detections = emptyList(),
                    timing = AndroidDetectorTiming(
                        yuvDecodeMs = 2L,
                        totalMs = 12L,
                        completedModels = listOf("walksafe_unified"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun fusedUnifiedInferencePassesWithItsCombinedPreprocessingTime() {
        assertTrue(DeviceCheckDetectorExecutionPolicy.passes(result(fusedUnifiedTiming())))
    }

    @Test
    fun fusedLegacyPairPassesAfterBothModelsComplete() {
        val result = result(
            AndroidDetectorTiming(
                cocoPreprocessMs = 3L,
                cocoInferenceMs = 8L,
                customPreprocessMs = 4L,
                customInferenceMs = 20L,
                totalMs = 38L,
                completedModels = listOf("coco_general", "custom_tactile"),
                preprocessingStrategy = "FUSED_YUV_TO_TENSOR",
            ),
        )

        assertTrue(DeviceCheckDetectorExecutionPolicy.passes(result))
        assertFalse(DeviceCheckDetectorExecutionPolicy.passes(result.copy(partial = true)))
    }

    @Test
    fun fusedRequiresValidPreprocessingAndInferenceFromTheSameModel() {
        val timing = fusedUnifiedTiming()
        listOf(
            timing.copy(modelPreprocessMs = null),
            timing.copy(modelPreprocessMs = -1L),
            timing.copy(modelInferenceMs = null),
            timing.copy(modelInferenceMs = -1L),
            timing.copy(modelPreprocessMs = null, cocoPreprocessMs = 4L),
            timing.copy(modelInferenceMs = null, cocoInferenceMs = 8L),
        ).forEach { invalid ->
            assertFalse("Invalid fused timing: $invalid", DeviceCheckDetectorExecutionPolicy.passes(result(invalid)))
        }
    }

    @Test
    fun fusedLegacyCompletionDeclarationRequiresBothCorrespondingModelPairs() {
        val timing = AndroidDetectorTiming(
            cocoPreprocessMs = 3L,
            cocoInferenceMs = 8L,
            customPreprocessMs = 4L,
            customInferenceMs = 20L,
            totalMs = 38L,
            completedModels = listOf("coco_general", "custom_tactile"),
            preprocessingStrategy = "FUSED_YUV_TO_TENSOR",
        )
        listOf(
            timing.copy(customPreprocessMs = null, customInferenceMs = null),
            timing.copy(customPreprocessMs = null),
            timing.copy(customPreprocessMs = -1L),
            timing.copy(customInferenceMs = null),
            timing.copy(customInferenceMs = -1L),
            timing.copy(cocoPreprocessMs = null, cocoInferenceMs = null),
            timing.copy(cocoPreprocessMs = -1L),
            timing.copy(cocoInferenceMs = -1L),
        ).forEach { invalid ->
            assertFalse("Declared model lacks its own timing pair: $invalid",
                DeviceCheckDetectorExecutionPolicy.passes(result(invalid)))
        }
    }

    @Test
    fun fusedUnifiedDeclarationCannotBorrowCompleteCocoTiming() {
        listOf("unified_walksafe", "walksafe_unified").forEach { model ->
            val timing = fusedUnifiedTiming().copy(
                modelKey = model,
                modelPreprocessMs = null,
                modelInferenceMs = null,
                cocoPreprocessMs = 4L,
                cocoInferenceMs = 12L,
                completedModels = listOf(model),
            )
            assertFalse(DeviceCheckDetectorExecutionPolicy.passes(result(timing)))
        }
    }

    @Test
    fun fusedRejectsUnknownBlankAndDuplicateCompletionNamesDespiteValidTiming() {
        listOf(
            listOf("unknown"),
            listOf(""),
            listOf("   "),
            listOf("unified_walksafe", ""),
            listOf("unified_walksafe", "unknown"),
            listOf("unified_walksafe", "unified_walksafe"),
            listOf("unified_walksafe", "walksafe_unified"),
            listOf("coco_general", "coco_general"),
            listOf("custom_tactile", "custom_tactile"),
        ).forEach { names ->
            val timing = fusedUnifiedTiming().copy(
                cocoPreprocessMs = 4L,
                cocoInferenceMs = 12L,
                customPreprocessMs = 4L,
                customInferenceMs = 12L,
                completedModels = names,
            )
            assertFalse("Invalid completed model declaration: $names",
                DeviceCheckDetectorExecutionPolicy.passes(result(timing)))
        }
    }

    @Test
    fun fusedKnownUnifiedAliasUsesTheUnifiedTimingPair() {
        listOf("unified_walksafe", "walksafe_unified").forEach { model ->
            assertTrue(DeviceCheckDetectorExecutionPolicy.passes(result(fusedUnifiedTiming().copy(
                modelKey = model,
                completedModels = listOf(model),
            ))))
        }
    }

    @Test
    fun fusedStillRequiresFinalExecutionCompletionAndValidTotalTime() {
        val timing = fusedUnifiedTiming()
        assertFalse(DeviceCheckDetectorExecutionPolicy.passes(result(timing).copy(partial = true)))
        listOf(
            timing.copy(completedModels = emptyList()),
            timing.copy(totalMs = null),
            timing.copy(totalMs = -1L),
        ).forEach { invalid ->
            assertFalse("Invalid final timing: $invalid", DeviceCheckDetectorExecutionPolicy.passes(result(invalid)))
        }
    }

    @Test
    fun fusedZeroMillisecondDurationsAreValidAtClockResolution() {
        val timing = fusedUnifiedTiming().copy(
            modelPreprocessMs = 0L,
            modelInferenceMs = 0L,
            totalMs = 0L,
        )

        assertTrue(DeviceCheckDetectorExecutionPolicy.passes(result(timing)))
    }

    @Test
    fun onlyExplicitFusedStrategyCanReplaceTheDecodeTiming() {
        listOf(null, "LEGACY_TWO_PASS", "UNKNOWN_STRATEGY").forEach { strategy ->
            val timing = fusedUnifiedTiming().copy(preprocessingStrategy = strategy)
            assertFalse(DeviceCheckDetectorExecutionPolicy.passes(result(timing)))
        }
    }

    @Test
    fun declaredAndUnspecifiedLegacyStrategiesKeepTheDecodeRequirement() {
        listOf(null, "LEGACY_TWO_PASS").forEach { strategy ->
            val timing = AndroidDetectorTiming(
                yuvDecodeMs = 2L,
                cocoInferenceMs = 8L,
                customInferenceMs = 20L,
                totalMs = 32L,
                completedModels = listOf("coco_general", "custom_tactile"),
                preprocessingStrategy = strategy,
            )
            assertTrue(DeviceCheckDetectorExecutionPolicy.passes(result(timing)))
            assertFalse(DeviceCheckDetectorExecutionPolicy.passes(result(timing.copy(yuvDecodeMs = null))))
            assertFalse(DeviceCheckDetectorExecutionPolicy.passes(result(timing.copy(yuvDecodeMs = -1L))))
        }
    }

    private fun fusedUnifiedTiming() = AndroidDetectorTiming(
        modelKey = "unified_walksafe",
        modelPreprocessMs = 4L,
        modelInferenceMs = 12L,
        modelParseMs = 1L,
        totalMs = 18L,
        completedModels = listOf("unified_walksafe"),
        preprocessingStrategy = "FUSED_YUV_TO_TENSOR",
    )

    private fun result(timing: AndroidDetectorTiming) = AndroidDetectionResult(
        detections = emptyList(),
        timing = timing,
    )
}
