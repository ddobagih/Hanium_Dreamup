package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class UnifiedOverlapCropMetadataTest {
    @Test
    fun retainedCropRemovesPaddingAndClipsCrossingResultInSensorCoordinates() {
        val crop = YuvCropRect(160, 120, 480, 360)
        val preparation = preparation(crop)
        val timing = AndroidDetectorTiming(modelKey = "unified_walksafe")
        val output = AndroidDetectionResult(listOf(
            DetectionCandidate("outside", .95f, RectNorm(.05f, .3f, .1f, .1f)),
            DetectionCandidate("edge", .9f, RectNorm(.15f, .3f, .1f, .1f)),
            DetectionCandidate("crossing", .8f, RectNorm(.2f, .2f, .2f, .2f)),
            DetectionCandidate("inside", .7f, RectNorm(.4f, .4f, .1f, .1f)),
        ), timing)

        assertEquals(crop, preparation.validImageCrop())
        val result = preparation.cropResult(output)
        assertSame(timing, result.timing)
        assertEquals(listOf("crossing", "inside"), result.detections.map { it.className })
        assertBox(RectNorm(.25f, .25f, .15f, .15f), result.detections[0].bboxNorm)
        assertBox(RectNorm(.4f, .4f, .1f, .1f), result.detections[1].bboxNorm)
    }

    @Test
    fun fullCropLeavesPreparedResultAndRawCandidateContractUnchanged() {
        val preparation = preparation(YuvCropRect.full(640, 480))
        val output = AndroidDetectionResult(listOf(
            DetectionCandidate("person", .9f, RectNorm(.1f, .2f, .3f, .4f)),
        ))

        assertNull(preparation.validImageCrop())
        assertSame(output, preparation.cropResult(output))
    }

    private fun preparation(crop: YuvCropRect) = OwnedTensorPreparation(
        transform = LetterboxTransform(640, 480, 640, 1f, 0f, 80f),
        preprocessingMs = 1L,
        tensorCopyMs = 1L,
        preparationMs = 2L,
        preparedAtElapsedRealtimeMs = 3L,
        strategy = YuvPreprocessingStrategy.LEGACY_TWO_PASS,
        cropRect = crop,
    )

    private fun assertBox(expected: RectNorm, actual: RectNorm) {
        assertEquals(expected.x, actual.x, .000001f)
        assertEquals(expected.y, actual.y, .000001f)
        assertEquals(expected.width, actual.width, .000001f)
        assertEquals(expected.height, actual.height, .000001f)
    }
}
