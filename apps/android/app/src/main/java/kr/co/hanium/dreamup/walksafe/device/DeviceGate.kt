package kr.co.hanium.dreamup.walksafe.device

data class DeviceGateState(
    val cameraPermissionGranted: Boolean = false,
    val arCoreSupported: Boolean = false,
    val depthSupported: Boolean = false,
    val tfliteConfigLoaded: Boolean = false,
    val detectorAvailable: Boolean = false,
    val arSessionRunning: Boolean = false,
    val freshDepthObject: Boolean = false,
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
        get() = actuatorsAllowed && freshDepthObject

    val reportCandidatesAllowed: Boolean
        get() = actuatorsAllowed && freshDepthObject

    fun blockedReason(): String? {
        return when {
            !cameraPermissionGranted -> "camera_permission_missing"
            !arCoreSupported -> "arcore_unsupported"
            !depthSupported -> "depth_unsupported"
            !tfliteConfigLoaded -> "tflite_config_unavailable"
            !detectorAvailable -> "tflite_detector_unavailable"
            !arSessionRunning -> "ar_session_not_running"
            staleReason != null -> "stale_detection:$staleReason"
            !freshDepthObject -> "fresh_depth_object_missing"
            else -> null
        }
    }
}
