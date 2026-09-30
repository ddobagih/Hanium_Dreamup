package kr.co.hanium.dreamup.walksafe.device

data class DeviceGateState(
    val cameraPermissionGranted: Boolean = false,
    val arCoreSupported: Boolean = false,
    val depthSupported: Boolean = false,
    val tfliteConfigLoaded: Boolean = false,
    val detectorAvailable: Boolean = false,
    val arSessionRunning: Boolean = false,
    val freshDepthObject: Boolean = false,
    val freshQualitativeObject: Boolean = false,
    val staleReason: String? = null,
) {
    val startupReady: Boolean
        get() = cameraPermissionGranted &&
            arCoreSupported &&
            depthSupported &&
            tfliteConfigLoaded &&
            detectorAvailable &&
            arSessionRunning

    val actuatorsAllowed: Boolean
        get() = startupReady && staleReason == null

    val alertsAllowed: Boolean
        get() = actuatorsAllowed && (freshDepthObject || freshQualitativeObject)

    val reportCandidatesAllowed: Boolean
        get() = actuatorsAllowed && freshDepthObject

    fun allows(purpose: DeviceGatePurpose): Boolean = when (purpose) {
        DeviceGatePurpose.ACTUATOR_READINESS -> actuatorsAllowed
        DeviceGatePurpose.RISK_ALERT -> alertsAllowed
        DeviceGatePurpose.REPORT_CANDIDATE -> reportCandidatesAllowed
    }

    /** Why [purpose] is blocked; null exactly when [allows] returns true for the same purpose. */
    fun blockedReason(purpose: DeviceGatePurpose): String? {
        val startupOrStale = when {
            !cameraPermissionGranted -> "camera_permission_missing"
            !arCoreSupported -> "arcore_unsupported"
            !depthSupported -> "depth_unsupported"
            !tfliteConfigLoaded -> "tflite_config_unavailable"
            !detectorAvailable -> "tflite_detector_unavailable"
            !arSessionRunning -> "ar_session_not_running"
            staleReason != null -> "stale_detection:$staleReason"
            else -> null
        }
        if (startupOrStale != null) return startupOrStale
        return when (purpose) {
            DeviceGatePurpose.ACTUATOR_READINESS -> null
            DeviceGatePurpose.RISK_ALERT ->
                "fresh_depth_object_missing".takeIf { !freshDepthObject && !freshQualitativeObject }
            DeviceGatePurpose.REPORT_CANDIDATE ->
                "fresh_metric_depth_object_missing".takeIf { !freshDepthObject }
        }
    }
}

/**
 * Each consumer names its purpose. Actuator readiness never needs a fresh risk object, so general
 * status or recovery speech must not be blocked by a missing depth object.
 */
enum class DeviceGatePurpose {
    ACTUATOR_READINESS,
    RISK_ALERT,
    REPORT_CANDIDATE,
}
