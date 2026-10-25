package kr.co.hanium.dreamup.walksafe.startup.interaction

import android.content.SharedPreferences
import android.os.Build
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.device.*

internal class AndroidStartupDecisionStorage(private val preferences: () -> SharedPreferences) : StartupDecisionStorage {
    override fun write(decision: WalkSafeStartupCapabilityDecision, profile: ApprovedDeviceProfileMatch) {
        preferences().edit()
            .putString("startup_capability_tier", decision.tier.name)
            .putString(
                "startup_capability_unavailable",
                decision.unavailableRequirements.joinToString(",") { it.name },
            )
            .putString(
                "startup_capability_pending",
                decision.pendingRequirements.joinToString(",") { it.name },
            )
            .putString("startup_capability_device_model", Build.MODEL)
            .putString("startup_capability_android_version", Build.VERSION.RELEASE)
            .putString("startup_capability_app_version", BuildConfig.VERSION_NAME)
            .putString("startup_capability_source_commit", BuildConfig.WALKSAFE_SOURCE_COMMIT)
            .putString(
                "startup_capability_device_profile_status",
                if (profile.approved) {
                    "APPROVED_DESIGNATED_DEVICE_PROFILE"
                } else {
                    WALKSAFE_DEVICE_PROFILE_POLICY_STATUS
                },
            )
            .putString("startup_capability_device_profile_id", profile.profileId)
            .putString(
                "startup_capability_device_profile_version",
                profile.profileVersion,
            )
            .putString("startup_capability_device_profile_reason", profile.reason.name)
            .putLong("startup_capability_evaluated_at_epoch_ms", System.currentTimeMillis())
            .apply()
    }
}
