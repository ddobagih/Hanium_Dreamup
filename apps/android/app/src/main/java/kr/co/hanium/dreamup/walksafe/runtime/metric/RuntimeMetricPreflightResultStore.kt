package kr.co.hanium.dreamup.walksafe.runtime.metric

import android.content.SharedPreferences
import android.os.Build
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.device.ApprovedDeviceProfileMatch
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightResult
import kr.co.hanium.dreamup.walksafe.device.WalkSafeApprovedDeviceProfiles

/** The existing shared preference store and original write/query ordering, with no copied cache. */
internal class RuntimeMetricPreflightResultStore(
    private val preferences: () -> SharedPreferences,
    private val policyVersion: String,
    private val wallTimeMs: () -> Long = System::currentTimeMillis,
    private val appVersion: () -> String = { BuildConfig.VERSION_NAME },
    private val sourceCommit: () -> String = { BuildConfig.WALKSAFE_SOURCE_COMMIT },
    private val manufacturer: () -> String = { Build.MANUFACTURER },
    private val model: () -> String = { Build.MODEL },
    private val deviceCode: () -> String = { Build.DEVICE },
    private val androidSdk: () -> Int = { Build.VERSION.SDK_INT },
) {
    fun persist(
        result: RuntimeMetricPreflightResult,
        profileMatch: ApprovedDeviceProfileMatch,
    ) {
        preferences().edit()
            .putString("runtime_metric_preflight_policy", policyVersion)
            .putString("runtime_metric_preflight_status", result.status.name)
            .putString("runtime_metric_preflight_reason", result.reason.name)
            .putLong("runtime_metric_preflight_generation", result.generation)
            .putInt("runtime_metric_preflight_distinct_frames", result.distinctFrameCount)
            .putInt("runtime_metric_preflight_passing_frames", result.passingFrameCount)
            .putLong("runtime_metric_preflight_observation_span_ms", result.observationSpanMs)
            .putLong(
                "runtime_metric_preflight_completed_at_elapsed_ms",
                result.completedAtElapsedRealtimeMs ?: -1L,
            )
            .putLong("runtime_metric_preflight_recorded_at_epoch_ms", wallTimeMs())
            .putString("runtime_metric_preflight_app_version", appVersion())
            .putString("runtime_metric_preflight_source_commit", sourceCommit())
            .putString("runtime_metric_preflight_device_manufacturer", manufacturer())
            .putString("runtime_metric_preflight_device_model", model())
            .putString("runtime_metric_preflight_device_code", deviceCode())
            .putInt("runtime_metric_preflight_android_sdk", androidSdk())
            .putBoolean("runtime_metric_preflight_profile_approved", profileMatch.approved)
            .putString("runtime_metric_preflight_profile_id", profileMatch.profileId)
            .putString("runtime_metric_preflight_profile_version", profileMatch.profileVersion)
            .putString("runtime_metric_preflight_profile_reason", profileMatch.reason.name)
            .putString(
                "runtime_metric_preflight_profile_registry_id",
                WalkSafeApprovedDeviceProfiles.REGISTRY_ID,
            )
            .putString(
                "runtime_metric_preflight_profile_registry_revision",
                WalkSafeApprovedDeviceProfiles.REGISTRY_REVISION,
            )
            .putString(
                "runtime_metric_preflight_profile_registry_sha256",
                WalkSafeApprovedDeviceProfiles.registryContentSha256,
            )
            .apply()
    }
}
