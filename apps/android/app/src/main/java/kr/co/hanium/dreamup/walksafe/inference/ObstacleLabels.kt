package kr.co.hanium.dreamup.walksafe.inference

/**
 * What each detector class is called out loud.
 *
 * One table, because two of them drifted: the same scooter was "방치 킥보드" on a phone that
 * measures distance and "전동 킥보드" on one that does not, and a bus was "차량" on one and "버스"
 * on the other. The walker cannot tell which kind of phone they hold.
 *
 * Two rules decide whether classes share a name:
 *
 * - Vehicles are one word. Calling a van a truck costs trust and buys nothing, because the walker
 *   does the same thing either way — stop.
 * - Static obstacles keep their own. Here the name changes what you do: a bollard is stepped
 *   around, a construction fence may mean the footway is shut and you need another route.
 *
 * An unmapped class is "물체" rather than a guess. `ObstacleLabelCoverageTest` fails when the
 * runtime config carries a class this table has never heard of, so a new model cannot quietly
 * turn eight hazards into "물체".
 */
object ObstacleLabels {
    const val FALLBACK_KO = "물체"

    private val labels: Map<String, String> = mapOf(
        "person" to "사람",
        "bicycle" to "자전거",

        // One word for all of them; the walker stops either way.
        "car" to "차량",
        "bus" to "차량",
        "truck" to "차량",
        "motorcycle" to "차량",

        "crosswalk" to "횡단보도",
        "curb_step" to "턱",
        "uneven_sidewalk" to "고르지 않은 보도",
        "e_scooter_obstruction" to "킥보드",

        // Named but never announced: tactile blocks are route guidance or report-only, and traffic
        // light colour is deferred. MessagePolicy routes them before the label is read.
        "normal_tactile_block" to "점자블록",
        "damaged_tactile_block" to "점자블록",
        "tactile_damage_area" to "점자블록",
        "traffic light" to "신호등",
    )

    fun labelFor(className: String): String =
        labels[className.lowercase()] ?: FALLBACK_KO

    fun isMapped(className: String): Boolean = labels.containsKey(className.lowercase())
}
