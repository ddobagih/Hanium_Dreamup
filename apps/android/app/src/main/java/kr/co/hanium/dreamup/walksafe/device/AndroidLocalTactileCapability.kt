package kr.co.hanium.dreamup.walksafe.device

enum class AndroidLocalTactileTier(
    val metric: Boolean,
    val mayCreateReportCandidates: Boolean,
) {
    ARCORE_METRIC(metric = true, mayCreateReportCandidates = true),
    CAMERA_IMU_NON_METRIC(metric = false, mayCreateReportCandidates = false),
    TMAP_ONLY(metric = false, mayCreateReportCandidates = false),
}

data class AndroidLocalTactileCapabilityInput(
    val cameraPermissionGranted: Boolean,
    val detectorAvailable: Boolean,
    val arCoreSupported: Boolean,
    val depthSupported: Boolean,
    val arSessionRunning: Boolean,
    val cameraFallbackRunning: Boolean,
    val imuFresh: Boolean,
    val tmapRouteActive: Boolean,
)

object AndroidLocalTactileCapability {
    fun resolve(input: AndroidLocalTactileCapabilityInput): AndroidLocalTactileTier {
        if (
            input.cameraPermissionGranted &&
            input.detectorAvailable &&
            input.arCoreSupported &&
            input.depthSupported &&
            input.arSessionRunning
        ) {
            return AndroidLocalTactileTier.ARCORE_METRIC
        }
        if (
            input.cameraPermissionGranted &&
            input.detectorAvailable &&
            input.cameraFallbackRunning &&
            input.imuFresh
        ) {
            return AndroidLocalTactileTier.CAMERA_IMU_NON_METRIC
        }
        return AndroidLocalTactileTier.TMAP_ONLY
    }
}
