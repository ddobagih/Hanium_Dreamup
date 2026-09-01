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
}
