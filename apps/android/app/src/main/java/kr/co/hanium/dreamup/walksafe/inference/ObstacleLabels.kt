package kr.co.hanium.dreamup.walksafe.inference

/** Shared spoken labels for metric warnings and camera-only observations. */
object ObstacleLabels {
    const val FALLBACK_KO = "물체"

    private val labels: Map<String, String> = mapOf(
        "person" to "사람",
        "bicycle" to "자전거",
        // Vehicle classes share a warning label; class alone does not establish movement.
        "car" to "차량",
        "passenger_car" to "차량",
        "bus" to "차량",
        "truck" to "차량",
        "motorcycle" to "차량",
        "crosswalk" to "횡단보도",
        "curb_step" to "턱",
        "uneven_sidewalk" to "고르지 않은 보도",
        "e_scooter_obstruction" to "킥보드",
        "abandoned_e_scooter" to "킥보드",
        "moving_e_scooter" to "킥보드",
        "bench" to "벤치",
        "construction_fence" to "공사 울타리",
        "barricade" to "차단 시설",
        "traffic_cone" to "안전 고깔",
        "bollard" to "볼라드",
        "utility_or_streetlight_pole" to "기둥",
        "trash_bin" to "쓰레기통",
        "portable_sign" to "이동식 표지판",
        // Tactile route/report semantics and traffic-light handling precede obstacle speech.
        "normal_tactile_block" to "점자블록",
        "damaged_tactile_block" to "점자블록",
        "tactile_damage_area" to "점자블록",
        "linear_tactile_paving" to "선형 점자블록",
        "damaged_linear_tactile_paving" to "손상된 선형 점자블록",
        "dot_tactile_paving" to "점형 점자블록",
        "damaged_dot_tactile_paving" to "손상된 점형 점자블록",
        "traffic light" to "신호등",
        "traffic_light" to "신호등",
    )

    fun labelFor(className: String): String = labels[className.lowercase()] ?: FALLBACK_KO

    fun isMapped(className: String): Boolean = labels.containsKey(className.lowercase())
}
