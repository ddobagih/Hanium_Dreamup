package kr.co.hanium.dreamup.walksafe.inference

/** Detector-label semantics without rewriting model-local class names or ids. */
object WalkMateClassPolicy {
    fun isTraversableTactileClass(className: String): Boolean =
        className.lowercase() in traversableTactileClasses

    fun isDamagedTactileClass(className: String): Boolean = className.lowercase() in damagedTactileClasses

    fun isTactileRouteBlockingClass(className: String): Boolean =
        isDamagedTactileClass(className) || className.equals("dot_tactile_paving", ignoreCase = true)

    fun isTactileClass(className: String): Boolean =
        isTraversableTactileClass(className) || isTactileRouteBlockingClass(className) ||
            className.equals("tactile_damage_area", ignoreCase = true)

    fun isTrafficLightClass(className: String): Boolean =
        className.equals("traffic light", ignoreCase = true) || className.equals("traffic_light", ignoreCase = true)

    private val traversableTactileClasses = setOf("normal_tactile_block", "linear_tactile_paving")
    private val damagedTactileClasses = setOf(
        "damaged_tactile_block",
        "damaged_linear_tactile_paving",
        "damaged_dot_tactile_paving",
    )
}
