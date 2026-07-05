package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("models/walksafe_unified_yolo26n_640_float32.tflite", config.unifiedWalksafe?.asset)
        assertEquals(640, config.unifiedWalksafe?.inputSize)
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

        val customTactile = requireNotNull(config.customTactile)
        assertEquals("models/custom_tactile_yolo26s_float32.tflite", customTactile.asset)
        assertEquals(960, customTactile.inputSize)
        assertEquals("damaged_tactile_block", customTactile.classNameForId(1))
        assertEquals(0.45f, customTactile.thresholdForClass("tactile_damage_area"), 0.0001f)
        assertEquals("cpu", customTactile.runtime.delegate)
        assertEquals(4, customTactile.runtime.numThreads)
        assertTrue(customTactile.runtime.fallbackToCpu)

        val cocoGeneral = requireNotNull(config.cocoGeneral)
        assertEquals("models/coco_yolo26n_float32.tflite", cocoGeneral.asset)
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
}
