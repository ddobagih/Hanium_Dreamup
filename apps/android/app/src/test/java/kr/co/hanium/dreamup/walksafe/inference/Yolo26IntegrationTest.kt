package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Yolo26IntegrationTest {
    private fun json() = JSONObject(File("src/main/assets/model-config/two_model_runtime.json").readText())
    private fun root() = TwoModelRuntimeConfig.parse(json().toString())
    private fun model() = requireNotNull(root().unifiedWalksafe)

    @Test fun bothScooterIdsUseGenericSpeechWithoutChangingAnyIndexThreshold() {
        val current = model()
        val previous = requireNotNull(TwoModelRuntimeConfig.parse(requireNotNull(javaClass.getResourceAsStream(
            "/inference/yolo11-runtime-config.json")).bufferedReader().use { it.readText() }).unifiedWalksafe)
        for (id in current.classes.indices) {
            assertEquals("threshold for output $id", previous.thresholdForClassId(id), current.thresholdForClassId(id), 0f)
            if (id !in listOf(9, 10)) assertEquals(previous.classNameForId(id), current.classNameForId(id))
        }
        for (id in listOf(9, 10)) {
            assertEquals("e_scooter", current.classNameForId(id))
            assertEquals("킥보드", ObstacleLabels.labelFor(current.classes[id]))
            assertTrue(TwoModelClassMap.isAndroidDepthCandidate(DetectionCandidate("e_scooter", .9f, RectNorm(.1f,.1f,.2f,.2f))))
        }
        assertNull(ConflictingStateGrouping.conflictingStateFamily("e_scooter"))
    }

    @Test fun parserUsesOutputIdBeforeCombiningScooterNamesAndUsesExplicitPixelUnits() {
        val m = model()
        val parser = YoloEndToEndOutputParser(m.inputSize, m::classNameForId, m::thresholdForClass,
            m::isAllowedClass, m::thresholdForClassId, pixelCoordinates = true)
        val raw = floatArrayOf(.2f,.3f,1f,1f,.2f,9f, .2f,.3f,1f,1f,.2f,10f)
        val detections = parser.parse(raw, rows = 2)
        assertEquals(1, detections.size)
        assertEquals("e_scooter", detections.single().className)
        assertEquals(.2f / 768f, detections.single().bboxNorm.x, 1e-8f)
        assertEquals(.8f / 768f, detections.single().bboxNorm.width, 1e-8f)
        raw[4] = .25f
        assertEquals(2, parser.parse(raw, rows = 2).size)
    }

    @Test fun calibrationStillRejectsCrossingEachScooterIdThreshold() {
        val m = model()
        for ((id, boundary) in listOf(9 to .25f, 10 to .15f)) {
            val before = FloatArray(1800)
            before[0]=100f; before[1]=100f; before[2]=200f; before[3]=200f
            before[4]=boundary-.0002f; before[5]=id.toFloat()
            val after=before.copyOf().also { it[4]=boundary+.0002f }
            assertTrue(RuntimeCalibrationOutputGate.hasNearThresholdCandidate(before,m))
            assertFalse(RuntimeCalibrationOutputGate.compare(before,after,emptyList(),emptyList(),m))
        }
    }

    @Test fun duplicateExceptionsCannotAdmitUnrelatedDuplicatesOrMissingScooterThresholds() {
        for (mutation in 0..6) {
            val j=json();val m=j.getJSONObject("models").getJSONObject("unified_walksafe")
            when (mutation) {
                0 -> m.getJSONArray("classes").put(0,"person")
                1 -> m.getJSONObject("class_id_thresholds").remove("9")
                2 -> m.getJSONObject("class_id_thresholds").put("9",1.01)
                3 -> m.getJSONObject("class_id_thresholds").put("21",.2)
                4 -> m.getJSONObject("class_id_thresholds").put("09",.2)
                5 -> m.put("output_format","yolo_raw_xywh_normalized")
                6 -> m.getJSONObject("class_id_thresholds").put("10",-.1)
            }
            assertThrows("mutation $mutation",IllegalArgumentException::class.java) { TwoModelRuntimeConfig.parse(j.toString()) }
        }
        val j=json();val m=j.getJSONObject("models").getJSONObject("unified_walksafe")
        m.put("classes",JSONArray(listOf("person","person"))).remove("class_id_thresholds")
        assertThrows(IllegalArgumentException::class.java) { TwoModelRuntimeConfig.parse(j.toString()) }
    }

    @Test fun changedOutputLayoutPreservesTactileReportIdentityAndThresholds() {
        val root=root()
        for (name in listOf("damaged_linear_tactile_paving","damaged_dot_tactile_paving")) {
            assertEquals(TwoModelRuntimeConfig.WALKMATE_REPORT_MODEL_KEY,root.modelKeyForReportClass(name))
            assertEquals(.3f,requireNotNull(root.thresholdForReportClass(TwoModelRuntimeConfig.WALKMATE_REPORT_MODEL_KEY,name)),0f)
        }
    }
}
