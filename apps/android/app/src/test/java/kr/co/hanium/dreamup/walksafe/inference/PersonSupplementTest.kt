package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersonSupplementTest {
    private val box = RectNorm(0.2f, 0.1f, 0.3f, 0.7f)
    private fun detection(name: String, score: Float = 0.8f, rect: RectNorm = box) =
        DetectionCandidate(name, score, rect)

    @Test fun missingPersonIsAddedWithoutRelabelingOverlappingScooter() {
        val scooter = detection("moving_e_scooter", 0.3f)
        val person = detection("person", 0.96f)
        assertEquals(listOf(scooter, person), PersonSupplementMerger().merge(listOf(scooter), listOf(person)))
    }

    @Test fun actualRiderAndVehicleRemainSeparateRegardlessOfPersonScore() {
        for (vehicle in listOf("moving_e_scooter", "abandoned_e_scooter", "bicycle", "motorcycle")) {
            val primary = listOf(detection(vehicle, 0.9f), detection("crosswalk", 0.7f))
            val merged = PersonSupplementMerger().merge(primary, listOf(detection("person", 0.99f)))
            assertEquals(primary, merged.take(primary.size))
            assertEquals("person", merged.last().className)
        }
    }

    @Test fun strongerSupplementDoesNotReplacePrimaryPersonGeometryOrConfidence() {
        val primary = detection("person", 0.4f)
        assertEquals(listOf(primary), PersonSupplementMerger().merge(listOf(primary), listOf(detection("person", 0.99f))))
    }

    @Test fun supplementaryDuplicatesAreRemovedButDistinctPeopleRemain() {
        val person = detection("person", 0.9f)
        val other = detection("person", 0.8f, RectNorm(0.7f, 0.1f, 0.2f, 0.5f))
        assertEquals(listOf(person, other), PersonSupplementMerger().merge(emptyList(),
            listOf(detection("person", 0.5f), other, person)))
    }

    @Test fun auxiliaryNonPersonOrInvalidPredictionCannotAddClasses() {
        val invalid = listOf(detection("bicycle"), detection("person", Float.NaN), detection("person", 1.01f),
            detection("person", 0f), detection("person", rect = RectNorm(Float.NaN, 0f, 1f, 1f)),
            detection("person", rect = RectNorm(0f, 0f, 0f, 1f)))
        assertTrue(PersonSupplementMerger().merge(emptyList(), invalid).isEmpty())
    }

    @Test fun candidateIsOffAndRetainsAllPrimaryClassesAndThresholds() {
        val config = TwoModelRuntimeConfig.parse(candidateConfig().toString())
        assertEquals("unified_walksafe", config.primaryModelKey)
        assertNull(config.fallbackModelKey)
        assertEquals(TwoModelClassMap.walkmate26Classes, config.unifiedWalksafe!!.classes)
        assertEquals(21, config.unifiedWalksafe.classes.size)
        assertEquals(0.15f, config.unifiedWalksafe.thresholdForClass("person"), 0f)
        assertEquals(0.15f, config.unifiedWalksafe.thresholdForClassId(10), 0f)
        val person = requireNotNull(config.personSupplement)
        assertFalse(person.enabled)
        assertEquals(setOf("person"), person.allowlist)
        assertEquals("person", person.classNameForId(0))
        assertEquals(0.35f, person.thresholdForClass("person"), 0f)
        assertArrayEquals(intArrayOf(1, 300, 6), person.outputTensorShape())
    }

    @Test fun enabledCandidateRequiresSameInputSizeAndKeepsPrimarySelection() {
        val json = candidateConfig()
        json.getJSONObject("models").getJSONObject("person_supplement").put("enabled", true)
        assertTrue(TwoModelRuntimeConfig.parse(json.toString()).personSupplement!!.enabled)
        json.getJSONObject("models").getJSONObject("person_supplement").put("input_size", 640)
        assertThrows(IllegalArgumentException::class.java) { TwoModelRuntimeConfig.parse(json.toString()) }
    }

    @Test fun candidateRejectsWrongClassOrderMissingIdentityAndBroadenedAllowlist() {
        for (fault in listOf("classes", "artifact_sha256", "source_model_sha256", "allowlist", "thresholds")) {
            val json = candidateConfig()
            val person = json.getJSONObject("models").getJSONObject("person_supplement")
            when (fault) {
                "classes" -> person.put(fault, JSONArray(listOf("bicycle", "person")))
                "allowlist" -> person.put(fault, JSONArray(listOf("person", "bicycle")))
                "thresholds" -> person.put(fault, JSONObject())
                else -> person.remove(fault)
            }
            assertThrows(IllegalArgumentException::class.java) { TwoModelRuntimeConfig.parse(json.toString()) }
        }
    }

    @Test fun oldConfigurationWithoutCandidateStillParses() {
        val json = JSONObject(configFile().readText())
        assertFalse(json.getJSONObject("models").has("person_supplement"))
        assertNull(TwoModelRuntimeConfig.parse(json.toString()).personSupplement)
    }

    private fun candidateConfig(): JSONObject = JSONObject(configFile().readText()).also { root ->
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("inference/person-supplement.json"))
            .bufferedReader().use { it.readText() }
        root.getJSONObject("models").put("person_supplement", JSONObject(text))
    }

    private fun configFile(): File = listOf(
        File("src/main/assets/model-config/two_model_runtime.json"),
        File("apps/android/app/src/main/assets/model-config/two_model_runtime.json"),
    ).first { it.isFile }
}
