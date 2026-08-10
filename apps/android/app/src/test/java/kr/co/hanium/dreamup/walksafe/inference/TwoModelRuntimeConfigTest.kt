package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TwoModelRuntimeConfigTest {
    @Test
    fun parsesAndroidAssetConfigAsRuntimeSourceOfTruth() {
        val json = File("src/main/assets/model-config/two_model_runtime.json").readText()
        val config = TwoModelRuntimeConfig.parse(json)

        assertEquals(TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, config.primaryModelKey)
        assertEquals(TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY, config.fallbackModelKey)
        assertEquals("models/walksafe_unified_yolo26n_768_float32.tflite", config.unifiedWalksafe?.asset)
        assertEquals(768, config.unifiedWalksafe?.inputSize)
        assertEquals("92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19", config.unifiedWalksafe?.artifactSha256)
        assertEquals("a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669", config.unifiedWalksafe?.sourceModelSha256)
        assertEquals(
            "model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt",
            config.unifiedWalksafe?.sourceModel,
        )
        assertEquals(true, config.unifiedWalksafe?.enabled)
        assertEquals(13, config.unifiedWalksafe?.classes?.size)
        assertEquals("person", config.unifiedWalksafe?.classNameForId(0))
        assertEquals("car", config.unifiedWalksafe?.classNameForId(2))
        assertEquals("normal_tactile_block", config.unifiedWalksafe?.classNameForId(7))
        assertEquals("damaged_tactile_block", config.unifiedWalksafe?.classNameForId(8))
        assertEquals("crosswalk", config.unifiedWalksafe?.classNameForId(9))
        assertEquals("curb_step", config.unifiedWalksafe?.classNameForId(10))
        assertEquals("uneven_sidewalk", config.unifiedWalksafe?.classNameForId(11))
        assertEquals("e_scooter_obstruction", config.unifiedWalksafe?.classNameForId(12))
        assertTrue(config.unifiedWalksafe?.isAllowedClass("car") == true)
        assertTrue(config.unifiedWalksafe?.isAllowedClass("normal_tactile_block") == true)
        assertTrue(config.unifiedWalksafe?.isAllowedClass("e_scooter_obstruction") == true)
        val activeThresholds = mapOf(
            "person" to 0.2f,
            "bicycle" to 0.2f,
            "car" to 0.2f,
            "motorcycle" to 0.2f,
            "bus" to 0.2f,
            "truck" to 0.2f,
            "traffic light" to 0.3f,
            "normal_tactile_block" to 0.3f,
            "damaged_tactile_block" to 0.3f,
            "crosswalk" to 0.3f,
            "curb_step" to 0.2f,
            "uneven_sidewalk" to 0.15f,
            "e_scooter_obstruction" to 0.35f,
        )
        activeThresholds.forEach { (className, expected) ->
            assertEquals(className, expected, requireNotNull(config.unifiedWalksafe).thresholdForClass(className), 0.0001f)
        }

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
            .replace(
                "      \"artifact_sha256\": \"3336a4411eda461a3159cca80c046d52a5dadfd6a91f3831490bb5574a160780\",\n",
                "",
            )

        assertThrows(IllegalArgumentException::class.java) {
            TwoModelRuntimeConfig.parse(json)
        }
    }
}
