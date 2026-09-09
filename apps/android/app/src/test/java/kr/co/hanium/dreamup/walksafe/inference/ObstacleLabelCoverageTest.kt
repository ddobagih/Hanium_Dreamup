package kr.co.hanium.dreamup.walksafe.inference

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector's class list and the words spoken for it have to move together.
 *
 * Today's model carries 13 classes and a 21-class one is planned. An unmapped class falls back to
 * "물체", which is the right answer for something nobody named — and the wrong one for eight new
 * hazards arriving unnoticed. This fails the moment the config names a class the table does not,
 * so the model swap has to bring the wording with it.
 */
class ObstacleLabelCoverageTest {
    private val config = JSONObject(
        File("src/main/assets/model-config/two_model_runtime.json").readText(),
    )

    @Test
    fun everyClassTheDetectorCanEmitHasAWord() {
        val unmapped = detectorClasses().filterNot(ObstacleLabels::isMapped)

        assertTrue(
            "이름표 없는 클래스: $unmapped — ObstacleLabels 에 추가하세요",
            unmapped.isEmpty(),
        )
    }

    @Test
    fun vehiclesShareOneWord() {
        listOf("car", "bus", "truck", "motorcycle").forEach {
            assertEquals(it, "차량", ObstacleLabels.labelFor(it))
        }
    }

    @Test
    fun staticObstaclesKeepTheirOwn() {
        // The name changes what the walker does, so it is not collapsed.
        assertEquals("턱", ObstacleLabels.labelFor("curb_step"))
        assertEquals("킥보드", ObstacleLabels.labelFor("e_scooter_obstruction"))
        assertEquals("고르지 않은 보도", ObstacleLabels.labelFor("uneven_sidewalk"))
    }

    @Test
    fun anUnknownClassIsNotGuessedAt() {
        assertEquals(ObstacleLabels.FALLBACK_KO, ObstacleLabels.labelFor("bench"))
        assertEquals(ObstacleLabels.FALLBACK_KO, ObstacleLabels.labelFor("무엇인지 모를 것"))
    }

    /** Classes the unified model and the tactile fallback can actually emit. */
    private fun detectorClasses(): List<String> {
        val models = config.getJSONObject("models")
        return listOf("unified_walksafe", "custom_tactile").flatMap { key ->
            val names = models.getJSONObject(key).optJSONArray("class_names")
                ?: return@flatMap emptyList()
            (0 until names.length()).map { names.getString(it) }
        }.distinct()
    }
}
