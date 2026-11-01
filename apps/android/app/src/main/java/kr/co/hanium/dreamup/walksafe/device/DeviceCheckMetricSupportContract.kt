package kr.co.hanium.dreamup.walksafe.device

/** Version/config identity for a support-only Session, not a model SHA or runtime readiness claim. */
object DeviceCheckMetricSupportContract {
    private const val SDK = "arcore-java-1.54.0"
    private const val OPERATION = "Session(applicationContext);isDepthModeSupported(AUTOMATIC);finally-close;no-resume;v1"
    val spec: DeviceCheckNativeSpec = DeviceCheckNativeSpec("METRIC_SUPPORT", SDK,
        DeviceCheckAuthorityValidation.digest("$SDK;$OPERATION"), "support", 0,
        DeviceCheckAuthorityValidation.digest(OPERATION))
}
