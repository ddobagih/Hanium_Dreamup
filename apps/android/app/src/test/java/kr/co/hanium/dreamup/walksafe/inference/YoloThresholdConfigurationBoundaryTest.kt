package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloThresholdConfigurationBoundaryTest {
    @Test
    fun missingSelectedThresholdCannotReachEitherParserAtScoreOne() {
        for (format in formats) {
            val model = model(format, emptyMap())
            assertThrows(IllegalArgumentException::class.java) {
                selected(model)
            }
            for (score in listOf(0.99f, 1f)) {
                assertThrows(IllegalArgumentException::class.java) {
                    parse(model, score)
                }
            }
        }
    }

    @Test
    fun explicitClassThresholdOneRejectsBelowAndAcceptsEqualityAcrossFormats() {
        for (format in formats) {
            val model = selected(model(format, mapOf("person" to 1f)))
            assertTrue(parse(model, 0.99f).isEmpty())
            val detection = parse(model, 1f).single()
            assertEquals("person", detection.className)
            assertEquals(1f, detection.detectionConfidence, 0f)
        }
    }

    @Test
    fun explicitDefaultThresholdOneRejectsBelowAndAcceptsEqualityAcrossFormats() {
        for (format in formats) {
            val model = selected(model(format, mapOf("default" to 1f)))
            assertTrue(parse(model, 0.99f).isEmpty())
            assertEquals(1f, parse(model, 1f).single().detectionConfidence, 0f)
        }
    }

    @Test
    fun explicitClassThresholdStillOverridesDefaultAcrossFormats() {
        for (format in formats) {
            val model = selected(model(format, mapOf("person" to 1f, "default" to 0.5f)))
            assertTrue(parse(model, 0.99f).isEmpty())
            assertEquals(1f, parse(model, 1f).single().detectionConfidence, 0f)
        }
    }

    @Test
    fun excludedClassDoesNotRequireOrReadAThresholdAcrossFormats() {
        for (format in formats) {
            val model = selected(model(format, mapOf("person" to 0.5f)))
            assertTrue(parse(model, 1f, classId = 1).isEmpty())
        }
    }

    @Test
    fun endToEndRawInspectionRemainsThresholdIndependent() {
        val model = model(YoloOutputFormat.END_TO_END_XYXY, emptyMap())
        val parser = YoloEndToEndOutputParser(
            inputSize = model.inputSize,
            classNameForId = model::classNameForId,
            thresholdForClass = model::thresholdForClass,
            allowClass = model::isAllowedClass,
        )

        val raw = parser.parseRaw(endToEndValues(1f, 1), rows = 1)
        assertEquals(1, raw.size)
        assertEquals(1, raw.single().classId)
        assertEquals(1f, raw.single().score, 0f)
    }

    private fun model(format: YoloOutputFormat, thresholds: Map<String, Float>) = ModelRuntimeConfig(
        key = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
        asset = "models/threshold-boundary.tflite",
        inputSize = 640,
        classes = listOf("person", "excluded"),
        allowlist = setOf("person"),
        thresholds = thresholds,
        outputFormat = format,
    )

    private fun selected(model: ModelRuntimeConfig): ModelRuntimeConfig =
        requireNotNull(TwoModelRuntimeConfig(
            bundleVersion = "threshold-boundary",
            runtimeSourceModel = null,
            primaryModelKey = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY,
            fallbackModelKey = null,
            unifiedWalksafe = model,
            customTactile = null,
            cocoGeneral = null,
        ).unifiedWalksafe)

    private fun parse(model: ModelRuntimeConfig, score: Float, classId: Int = 0): List<DetectionCandidate> =
        if (model.outputFormat.isRaw) {
            YoloRawOutputParser(
                inputSize = model.inputSize,
                classCount = model.classes.size,
                classNameForId = model::classNameForId,
                thresholdForClass = model::thresholdForClass,
                allowClass = model::isAllowedClass,
                normalizedCoordinates = model.outputFormat == YoloOutputFormat.RAW_XYWH_NORMALIZED,
            ).parse(rawValues(model, score, classId))
        } else {
            YoloEndToEndOutputParser(
                inputSize = model.inputSize,
                classNameForId = model::classNameForId,
                thresholdForClass = model::thresholdForClass,
                allowClass = model::isAllowedClass,
            ).parse(endToEndValues(score, classId), rows = 1)
        }

    private fun rawValues(model: ModelRuntimeConfig, score: Float, classId: Int): FloatArray {
        val anchors = model.outputTensorShape()[2]
        val values = FloatArray((4 + model.classes.size) * anchors)
        val coordinate = if (model.outputFormat == YoloOutputFormat.RAW_XYWH_NORMALIZED) 0.5f else 320f
        values[0] = coordinate
        values[anchors] = coordinate
        values[2 * anchors] = coordinate
        values[3 * anchors] = coordinate
        values[(4 + classId) * anchors] = score
        return values
    }

    private fun endToEndValues(score: Float, classId: Int) =
        floatArrayOf(160f, 160f, 480f, 480f, score, classId.toFloat())

    private val formats = listOf(
        YoloOutputFormat.RAW_XYWH_PIXELS,
        YoloOutputFormat.RAW_XYWH_NORMALIZED,
        YoloOutputFormat.END_TO_END_XYXY,
    )
}
