package kr.co.hanium.dreamup.walksafe.device

import android.content.SharedPreferences

const val POST_LOGIN_DEVICE_CHECK_RESULT_POLICY_VERSION = "2"

data class PersistedPostLoginDeviceCheckResult(
    val state: PostLoginDeviceCheckState,
    val disabledFeatures: Set<PostLoginDeviceCheckFeature>,
    val cameraDependentChecksDeferred: Boolean,
)

class AndroidDeviceCheckResultStore(
    private val preferences: SharedPreferences,
) {
    @Volatile
    private var saveFailedInProcess = false

    fun save(
        snapshot: PostLoginDeviceCheckSnapshot,
        cameraDependentChecksDeferred: Boolean = false,
    ): Boolean {
        val state = snapshot.state
        val disabledFeatures = snapshot.disabledFeatures
        if (!isValid(state, disabledFeatures, cameraDependentChecksDeferred)) {
            saveFailedInProcess = true
            return false
        }

        val disabledFeaturesCsv = disabledFeatures
            .map(PostLoginDeviceCheckFeature::name)
            .sorted()
            .joinToString(",")
        val saved = runCatching {
            preferences.edit()
                .putString(POLICY_VERSION_KEY, POST_LOGIN_DEVICE_CHECK_RESULT_POLICY_VERSION)
                .putString(TIER_KEY, state.name)
                .putString(DISABLED_FEATURES_KEY, disabledFeaturesCsv)
                .putBoolean(
                    CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY,
                    cameraDependentChecksDeferred,
                )
                .commit()
        }.getOrDefault(false)
        saveFailedInProcess = !saved
        return saved
    }

    fun restore(): PersistedPostLoginDeviceCheckResult? {
        if (saveFailedInProcess) return null
        val stored = runCatching { preferences.all }.getOrNull() ?: return null
        val policyVersion = stored[POLICY_VERSION_KEY] as? String ?: return null
        val tier = stored[TIER_KEY] as? String ?: return null
        val disabledFeaturesCsv = stored[DISABLED_FEATURES_KEY] as? String ?: return null
        val cameraDependentChecksDeferred =
            stored[CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY] as? Boolean ?: return null
        if (policyVersion != POST_LOGIN_DEVICE_CHECK_RESULT_POLICY_VERSION) return null

        val state = when (tier) {
            PostLoginDeviceCheckState.FULL.name -> PostLoginDeviceCheckState.FULL
            PostLoginDeviceCheckState.LIMITED.name -> PostLoginDeviceCheckState.LIMITED
            else -> return null
        }
        val disabledFeatures = parseDisabledFeatures(disabledFeaturesCsv) ?: return null
        if (!isValid(state, disabledFeatures, cameraDependentChecksDeferred)) return null
        return PersistedPostLoginDeviceCheckResult(
            state = state,
            disabledFeatures = disabledFeatures,
            cameraDependentChecksDeferred = cameraDependentChecksDeferred,
        )
    }

    private fun parseDisabledFeatures(csv: String): Set<PostLoginDeviceCheckFeature>? {
        if (csv.isEmpty()) return emptySet()
        val names = csv.split(',')
        if (names != names.sorted() || names.toSet().size != names.size) return null
        val features = names.map { name ->
            PostLoginDeviceCheckFeature.entries.singleOrNull { it.name == name } ?: return null
        }
        return features.toSet()
    }

    private fun isValid(
        state: PostLoginDeviceCheckState,
        disabledFeatures: Set<PostLoginDeviceCheckFeature>,
        cameraDependentChecksDeferred: Boolean,
    ): Boolean {
        if (
            cameraDependentChecksDeferred &&
            PostLoginDeviceCheckFeature.OBSTACLE_DETECTION in disabledFeatures
        ) return false
        return when (state) {
            PostLoginDeviceCheckState.FULL -> disabledFeatures.isEmpty()
            PostLoginDeviceCheckState.LIMITED -> disabledFeatures.isNotEmpty()
            else -> false
        }
    }

    private companion object {
        const val POLICY_VERSION_KEY = "device_check_result_policy_v1"
        const val TIER_KEY = "device_check_result_tier_v1"
        const val DISABLED_FEATURES_KEY = "device_check_result_disabled_features_v1"
        const val CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY =
            "device_check_result_camera_dependent_checks_deferred_v2"
    }
}
