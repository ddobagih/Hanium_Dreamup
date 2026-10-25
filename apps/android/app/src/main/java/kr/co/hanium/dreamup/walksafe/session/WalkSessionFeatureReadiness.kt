package kr.co.hanium.dreamup.walksafe.session

/**
 * Walk readiness of the camera, precise location and detector model (S03-6).
 *
 * FP-017-DETAIL-01 and FP-017 permissions[0] make the camera, precise location and the model start
 * requirements of both approved modes (full and distance-limited, SP-05-POLICY-001). A feature that
 * is disabled or whose permission is missing is therefore UNAVAILABLE with its cause. It is never
 * reported as READY, and a disabled feature does not turn into NOT_REQUIRED either: NOT_REQUIRED
 * is decided only by [WalkSessionReadinessPlan].
 */
object WalkSessionFeatureReadiness {
    private val READY: Pair<WalkSessionReadinessStatus, String> =
        WalkSessionReadinessStatus.READY to ""

    fun camera(
        guidanceEnabled: Boolean,
        permissionGranted: Boolean,
        capability: Pair<WalkSessionReadinessStatus, String>,
    ): Pair<WalkSessionReadinessStatus, String> = combine(
        if (guidanceEnabled || !permissionGranted) {
            READY
        } else {
            WalkSessionReadinessStatus.UNAVAILABLE to "camera_guidance_disabled"
        },
        capability,
        if (permissionGranted) {
            READY
        } else {
            WalkSessionReadinessStatus.UNAVAILABLE to "camera_permission_missing"
        },
    )

    fun location(
        guidanceEnabled: Boolean,
        permissionGranted: Boolean,
        capability: Pair<WalkSessionReadinessStatus, String>,
    ): Pair<WalkSessionReadinessStatus, String> = combine(
        if (guidanceEnabled || !permissionGranted) {
            READY
        } else {
            WalkSessionReadinessStatus.UNAVAILABLE to "location_guidance_disabled"
        },
        capability,
        if (permissionGranted) {
            READY
        } else {
            WalkSessionReadinessStatus.UNAVAILABLE to "precise_location_permission_missing"
        },
    )

    fun model(
        cameraGuidanceEnabled: Boolean,
        detectorAvailable: Boolean,
        detectorLoadReason: String?,
        preparing: Boolean = false,
    ): Pair<WalkSessionReadinessStatus, String> = when {
        !cameraGuidanceEnabled ->
            WalkSessionReadinessStatus.UNAVAILABLE to "model_unavailable:camera_guidance_disabled"
        detectorAvailable -> READY
        preparing && detectorLoadReason == null ->
            WalkSessionReadinessStatus.PENDING to "model_preparing"
        else ->
            WalkSessionReadinessStatus.UNAVAILABLE to
                "model_unavailable:${detectorLoadReason ?: "not_loaded"}"
    }

    /** UNAVAILABLE outranks PENDING, which outranks READY; reasons of the winning status are kept. */
    fun combine(
        vararg checks: Pair<WalkSessionReadinessStatus, String>,
    ): Pair<WalkSessionReadinessStatus, String> {
        for (status in listOf(
            WalkSessionReadinessStatus.UNAVAILABLE,
            WalkSessionReadinessStatus.PENDING,
        )) {
            val matching = checks.filter { it.first == status }
            if (matching.isNotEmpty()) {
                val reason = matching.map { it.second }.filter(String::isNotBlank).joinToString(",")
                return status to reason.ifBlank { status.name.lowercase() }
            }
        }
        return READY
    }
}
