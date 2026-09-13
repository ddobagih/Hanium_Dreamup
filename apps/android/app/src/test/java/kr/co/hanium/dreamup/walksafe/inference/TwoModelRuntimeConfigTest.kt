package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TwoModelRuntimeConfigTest {
    @Test
    fun runtimeThreadsAcceptEntirePositiveIntDomainWithoutADeviceLimit() {
        val json = File("src/main/assets/model-config/two_model_runtime.json").readText()
        for (threads in listOf(1, 3, 8, 9, 12, Int.MAX_VALUE)) {
            assertEquals(threads, ModelRuntimeOptions(numThreads = threads).numThreads)
            val changed = json.replace(Regex("\"num_threads\"\\s*:\\s*[0-9]+"), "\"num_threads\": $threads")
            assertEquals(threads, TwoModelRuntimeConfig.parse(changed).unifiedWalksafe?.runtime?.numThreads)
        }
    }

    @Test
    fun runtimeThreadsRejectFractionStringsNullAndOverflowInsteadOfCoercing() {
        val json = File("src/main/assets/model-config/two_model_runtime.json").readText()
        for (invalid in listOf("0", "-1", "1.5", "4.0", "\"4\"", "null", "true", "2147483648", "4294967300", "1e100")) {
            val changed = json.replace(Regex("\"num_threads\"\\s*:\\s*[0-9]+"), "\"num_threads\": $invalid")
            assertThrows("must reject $invalid", IllegalArgumentException::class.java) { TwoModelRuntimeConfig.parse(changed) }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelRuntimeOptions(numThreads = 0) }
        assertThrows(IllegalArgumentException::class.java) { ModelRuntimeOptions(numThreads = -1) }
    }

    @Test
    fun parsesAndroidAssetConfigAsRuntimeSourceOfTruth() {
        val json = File("src/main/assets/model-config/two_model_runtime.json").readText()
        val config = TwoModelRuntimeConfig.parse(json)

        assertEquals("walksafe-android-runtime-walkmate21-yolo11n-768-fp32-normalized-bilinear-20260913", config.bundleVersion)
        assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, config.primaryModelKey)
        assertNull(config.fallbackModelKey)
        assertEquals("models/walkmate_yolo11n_21cls_768_float32.tflite", config.unifiedWalksafe?.asset)
        assertEquals(768, config.unifiedWalksafe?.inputSize)
        assertEquals("d10aa174a2ceb8ae116a09b7b570e27ff71c296fbd98f240c11e69dbdb434b57", config.unifiedWalksafe?.artifactSha256)
        assertEquals("6f8ae9d2bb82f91e4391e42957a1b374bd4b14efa4702a86f7283454bc38cee0", config.unifiedWalksafe?.sourceModelSha256)
        assertEquals(
            "model/artifacts/candidates/walkmate_21cls_yolo11n_img768_20260912/best.pt",
            config.unifiedWalksafe?.sourceModel,
        )
        val unifiedRuntime = requireNotNull(config.unifiedWalksafe).runtime
        assertEquals("gpu", unifiedRuntime.delegate)
        assertEquals(4, unifiedRuntime.numThreads)
        assertTrue(unifiedRuntime.fallbackToCpu)
        assertEquals(false, unifiedRuntime.gpuPrecisionLossAllowed)
        assertTrue(unifiedRuntime.gpuSerializationCacheEnabled)
        assertEquals(true, config.unifiedWalksafe?.enabled)
        assertEquals(YoloOutputFormat.RAW_XYWH_NORMALIZED, config.unifiedWalksafe?.outputFormat)
        org.junit.Assert.assertArrayEquals(intArrayOf(1, 25, 12096), requireNotNull(config.unifiedWalksafe).outputTensorShape())
        assertEquals(21, config.unifiedWalksafe?.classes?.size)
        assertEquals("linear_tactile_paving", config.unifiedWalksafe?.classNameForId(0))
        assertEquals("damaged_linear_tactile_paving", config.unifiedWalksafe?.classNameForId(1))
        assertEquals("dot_tactile_paving", config.unifiedWalksafe?.classNameForId(2))
        assertEquals("damaged_dot_tactile_paving", config.unifiedWalksafe?.classNameForId(3))
        assertEquals("passenger_car", config.unifiedWalksafe?.classNameForId(4))
        assertEquals("bus", config.unifiedWalksafe?.classNameForId(5))
        assertEquals("truck", config.unifiedWalksafe?.classNameForId(6))
        assertEquals("motorcycle", config.unifiedWalksafe?.classNameForId(7))
        assertEquals("bicycle", config.unifiedWalksafe?.classNameForId(8))
        assertEquals("abandoned_e_scooter", config.unifiedWalksafe?.classNameForId(9))
        assertEquals("moving_e_scooter", config.unifiedWalksafe?.classNameForId(10))
        assertEquals("traffic_light", config.unifiedWalksafe?.classNameForId(11))
        assertEquals("crosswalk", config.unifiedWalksafe?.classNameForId(12))
        assertEquals("construction_fence", config.unifiedWalksafe?.classNameForId(13))
        assertEquals("barricade", config.unifiedWalksafe?.classNameForId(14))
        assertEquals("traffic_cone", config.unifiedWalksafe?.classNameForId(15))
        assertEquals("bollard", config.unifiedWalksafe?.classNameForId(16))
        assertEquals("utility_or_streetlight_pole", config.unifiedWalksafe?.classNameForId(17))
        assertEquals("trash_bin", config.unifiedWalksafe?.classNameForId(18))
        assertEquals("portable_sign", config.unifiedWalksafe?.classNameForId(19))
        assertEquals("person", config.unifiedWalksafe?.classNameForId(20))
        assertTrue(config.unifiedWalksafe?.isAllowedClass("passenger_car") == true)
        assertTrue(config.unifiedWalksafe?.isAllowedClass("linear_tactile_paving") == true)
        assertTrue(config.unifiedWalksafe?.isAllowedClass("abandoned_e_scooter") == true)
        val activeThresholds = mapOf(
            "linear_tactile_paving" to 0.25f,
            "damaged_linear_tactile_paving" to 0.3f,
            "dot_tactile_paving" to 0.25f,
            "damaged_dot_tactile_paving" to 0.3f,
            "passenger_car" to 0.15f,
            "bus" to 0.15f,
            "truck" to 0.15f,
            "motorcycle" to 0.15f,
            "bicycle" to 0.15f,
            "abandoned_e_scooter" to 0.25f,
            "moving_e_scooter" to 0.15f,
            "traffic_light" to 0.3f,
            "crosswalk" to 0.25f,
            "construction_fence" to 0.15f,
            "barricade" to 0.15f,
            "traffic_cone" to 0.15f,
            "bollard" to 0.15f,
            "utility_or_streetlight_pole" to 0.15f,
            "trash_bin" to 0.15f,
            "portable_sign" to 0.15f,
            "person" to 0.15f,
        )
        activeThresholds.forEach { (className, expected) ->
            assertEquals(className, expected, requireNotNull(config.unifiedWalksafe).thresholdForClass(className), 0.0001f)
        }
        assertEquals(0.15f, requireNotNull(config.unifiedWalksafe).thresholdForClass("unconfigured_class"), 0.0001f)
        assertEquals(
            0.30f,
            requireNotNull(config.thresholdForReportClass(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, "damaged_linear_tactile_paving")),
            0.0001f,
        )

        val customTactile = requireNotNull(config.customTactile)
        assertEquals("models/custom_tactile_yolo26s_float32.tflite", customTactile.asset)
        assertEquals("3336a4411eda461a3159cca80c046d52a5dadfd6a91f3831490bb5574a160780", customTactile.artifactSha256)
        assertEquals(960, customTactile.inputSize)
        assertEquals("damaged_tactile_block", customTactile.classNameForId(1))
        assertEquals(0.45f, customTactile.thresholdForClass("tactile_damage_area"), 0.0001f)
        assertEquals("cpu", customTactile.runtime.delegate)
        assertEquals(4, customTactile.runtime.numThreads)
        assertTrue(customTactile.runtime.fallbackToCpu)

        val cocoGeneral = requireNotNull(config.cocoGeneral)
        assertEquals("models/coco_yolo26n_float32.tflite", cocoGeneral.asset)
        assertEquals("776cafdaf1e0bc585a076d4ee3dd71d62f653e0bc2f8504f287e7b5a76c689e1", cocoGeneral.artifactSha256)
        assertEquals(640, cocoGeneral.inputSize)
        assertEquals(80, cocoGeneral.classes.size)
        assertEquals("traffic light", cocoGeneral.classNameForId(9))
        assertTrue(cocoGeneral.isAllowedClass("person"))
        assertEquals(0.40f, cocoGeneral.thresholdForClass("bench"), 0.0001f)
    }

    @Test
    fun parsesUnifiedOnlyConfigWithoutLegacyModelConfigs() {
        val json = """
            {
              "version": "unified-only-v1",
              "primary_model": "unified_walksafe",
              "fallback_model": null,
              "models": {
                "unified_walksafe": {
                  "enabled": true,
                  "asset": "models/walksafe_unified_yolo26n_640_float32.tflite",
                  "source_model": "model/artifacts/candidates/example.pt",
                  "artifact_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                  "source_model_sha256": "1111111111111111111111111111111111111111111111111111111111111111",
                  "input_size": 640,
                  "classes": ["person", "damaged_tactile_block"],
                  "allowlist": ["person", "damaged_tactile_block"],
                  "thresholds": {
                    "person": 0.35,
                    "damaged_tactile_block": 0.35
                  }
                }
              }
            }
        """.trimIndent()

        val config = TwoModelRuntimeConfig.parse(json)

        assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, config.primaryModelKey)
        assertNull(config.fallbackModelKey)
        assertEquals("models/walksafe_unified_yolo26n_640_float32.tflite", config.unifiedWalksafe?.asset)
        assertNull(config.customTactile)
        assertNull(config.cocoGeneral)
    }

    @Test
    fun enabledUnifiedConfigRejectsMissingArtifactProvenance() {
        val json = """
            {
              "version": "missing-provenance-v1",
              "primary_model": "unified_walksafe",
              "fallback_model": null,
              "models": {
                "unified_walksafe": {
                  "enabled": true,
                  "asset": "models/model.tflite",
                  "input_size": 768,
                  "classes": ["person"],
                  "allowlist": ["person"],
                  "thresholds": {"person": 0.35}
                }
              }
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            TwoModelRuntimeConfig.parse(json)
        }
    }

    @Test
    fun configuredLegacyFallbackRejectsMissingArtifactHash() {
        val json = File("src/main/assets/model-config/two_model_runtime.json").readText()
            .replace("\"fallback_model\": null", "\"fallback_model\": \"legacy_two_model\"")
            .replace(
                "      \"artifact_sha256\": \"3336a4411eda461a3159cca80c046d52a5dadfd6a91f3831490bb5574a160780\",\n",
                "",
            )

        assertThrows(IllegalArgumentException::class.java) {
            TwoModelRuntimeConfig.parse(json)
        }
    }

    @Test
    fun bundleVersionIsRequiredAndMustBeNonBlankWithoutSurroundingWhitespace() {
        val json = File("src/main/assets/model-config/two_model_runtime.json").readText()
        listOf(
            json.replace(Regex("\\s*\\\"version\\\"\\s*:\\s*\\\"[^\\\"]+\\\",?"), ""),
            json.replace(
                "\"version\": \"walksafe-android-runtime-walkmate21-yolo11n-768-fp32-normalized-bilinear-20260913\"",
                "\"version\": 1",
            ),
            json.replace(
                "walksafe-android-runtime-walkmate21-yolo11n-768-fp32-normalized-bilinear-20260913",
                " ",
            ),
            json.replace(
                "walksafe-android-runtime-walkmate21-yolo11n-768-fp32-normalized-bilinear-20260913",
                " version-with-space ",
            ),
        ).forEach { invalid ->
            assertThrows(RuntimeException::class.java) {
                TwoModelRuntimeConfig.parse(invalid)
            }
        }
    }
}
