package kr.co.hanium.dreamup.walksafe.device

import android.content.SharedPreferences
import java.security.MessageDigest

const val POST_LOGIN_DEVICE_CHECK_RESULT_POLICY_VERSION = "4"
const val POST_LOGIN_DEVICE_CHECK_PROBE_POLICY_VERSION = "20260912-device-function-v1"
const val POST_LOGIN_METRIC_DEPTH_PROBE_POLICY_VERSION = "20260905-support-only-v1"

data class PostLoginDeviceCheckResultBinding(
    val actorId: String,
    val installationId: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceName: String,
    val osSdkInt: Int,
    val osBuildFingerprint: String,
    val appId: String,
    val appVersionCode: Long,
    val appVersionName: String,
    val appSourceRevision: String,
    val probePolicyVersion: String,
    val environmentProfileId: String?,
    val environmentProfileRevision: String?,
    val approvedDeviceProfileRegistryRevision: String,
    val approvedDeviceProfileRegistrySha256: String,
    val approvedDeviceProfileId: String?,
    val approvedDeviceProfileVersion: String?,
    val missingRequiredPermissions: Set<String>,
    val locationServiceEnabled: Boolean,
    val voiceDisclosureAccepted: Boolean,
    val offlineKoreanTextToSpeechAvailable: Boolean?,
    val onDeviceSpeechRecognitionAvailable: Boolean?,
)

data class PersistedPostLoginDeviceCheckResult(
    val state: PostLoginDeviceCheckState,
    val disabledFeatures: Set<PostLoginDeviceCheckFeature>,
    val cameraDependentChecksDeferred: Boolean,
    val metricDepthProbePolicyCurrent: Boolean,
    val metricDepthState: PostLoginMetricDepthState = PostLoginMetricDepthState.PENDING,
)

class AndroidDeviceCheckResultStore(
    private val preferences: SharedPreferences,
) {
    @Volatile
    private var saveFailedInProcess = false

    fun hasCurrentPolicyResultCandidate(): Boolean {
        val stored = runCatching { preferences.all }.getOrNull() ?: return false
        return stored[POLICY_VERSION_KEY] == POST_LOGIN_DEVICE_CHECK_RESULT_POLICY_VERSION &&
            stored[BINDING_SHA256_KEY] is String &&
            stored[TIER_KEY] is String &&
            stored[DISABLED_FEATURES_KEY] is String &&
            stored[CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY] is Boolean
    }

    fun save(
        snapshot: PostLoginDeviceCheckSnapshot,
        binding: PostLoginDeviceCheckResultBinding,
        cameraDependentChecksDeferred: Boolean = false,
        metricDepthState: PostLoginMetricDepthState = PostLoginMetricDepthState.PENDING,
    ): Boolean {
        val state = snapshot.state
        val disabledFeatures = snapshot.disabledFeatures
        val bindingSha256 = binding.sha256OrNull()
        if (
            bindingSha256 == null ||
            !isValid(state, disabledFeatures, cameraDependentChecksDeferred)
        ) {
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
                .putString(BINDING_SHA256_KEY, bindingSha256)
                .putString(TIER_KEY, state.name)
                .putString(DISABLED_FEATURES_KEY, disabledFeaturesCsv)
                .putBoolean(
                    CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY,
                    cameraDependentChecksDeferred,
                )
                .putString(
                    METRIC_DEPTH_PROBE_POLICY_VERSION_KEY,
                    POST_LOGIN_METRIC_DEPTH_PROBE_POLICY_VERSION,
                )
                .putString(METRIC_DEPTH_SUPPORT_STATE_KEY, metricDepthState.name)
                .commit()
        }.getOrDefault(false)
        saveFailedInProcess = !saved
        return saved
    }

    fun restore(
        expectedBinding: PostLoginDeviceCheckResultBinding,
    ): PersistedPostLoginDeviceCheckResult? {
        if (saveFailedInProcess) return null
        val expectedBindingSha256 = expectedBinding.sha256OrNull() ?: return null
        val stored = runCatching { preferences.all }.getOrNull() ?: return null
        val policyVersion = stored[POLICY_VERSION_KEY] as? String ?: return null
        val bindingSha256 = stored[BINDING_SHA256_KEY] as? String ?: return null
        val tier = stored[TIER_KEY] as? String ?: return null
        val disabledFeaturesCsv = stored[DISABLED_FEATURES_KEY] as? String ?: return null
        val cameraDependentChecksDeferred =
            stored[CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY] as? Boolean ?: return null
        val metricDepthState = when (stored[METRIC_DEPTH_SUPPORT_STATE_KEY]) {
            PostLoginMetricDepthState.SUPPORTED.name -> PostLoginMetricDepthState.SUPPORTED
            PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED.name ->
                PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED
            PostLoginMetricDepthState.UNKNOWN.name -> PostLoginMetricDepthState.UNKNOWN
            else -> PostLoginMetricDepthState.PENDING
        }
        val metricDepthProbePolicyCurrent =
            stored[METRIC_DEPTH_PROBE_POLICY_VERSION_KEY] ==
                POST_LOGIN_METRIC_DEPTH_PROBE_POLICY_VERSION &&
                metricDepthState in setOf(
                    PostLoginMetricDepthState.SUPPORTED,
                    PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED,
                )
        if (
            policyVersion != POST_LOGIN_DEVICE_CHECK_RESULT_POLICY_VERSION ||
            bindingSha256 != expectedBindingSha256
        ) return null

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
            metricDepthProbePolicyCurrent = metricDepthProbePolicyCurrent,
            metricDepthState = metricDepthState,
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

    private fun PostLoginDeviceCheckResultBinding.sha256OrNull(): String? {
        val requiredStrings = listOf(
            actorId,
            installationId,
            deviceManufacturer,
            deviceModel,
            deviceName,
            osBuildFingerprint,
            appId,
            appVersionName,
            appSourceRevision,
            probePolicyVersion,
            approvedDeviceProfileRegistryRevision,
            approvedDeviceProfileRegistrySha256,
        )
        if (
            requiredStrings.any(String::isBlank) ||
            osSdkInt <= 0 ||
            appVersionCode <= 0L ||
            !isValidOptionalPair(environmentProfileId, environmentProfileRevision) ||
            !isValidOptionalPair(approvedDeviceProfileId, approvedDeviceProfileVersion)
        ) return null
        val values = listOf(
            actorId,
            installationId,
            deviceManufacturer,
            deviceModel,
            deviceName,
            osSdkInt.toString(),
            osBuildFingerprint,
            appId,
            appVersionCode.toString(),
            appVersionName,
            appSourceRevision,
            probePolicyVersion,
            environmentProfileId.orEmpty(),
            environmentProfileRevision.orEmpty(),
            approvedDeviceProfileRegistryRevision,
            approvedDeviceProfileRegistrySha256,
            approvedDeviceProfileId.orEmpty(),
            approvedDeviceProfileVersion.orEmpty(),
        )
        val canonical = buildString {
            values.forEach { value ->
                append(value.length).append(':').append(value)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun isValidOptionalPair(first: String?, second: String?): Boolean =
        (first == null && second == null) ||
            (!first.isNullOrBlank() && !second.isNullOrBlank())

    private companion object {
        const val POLICY_VERSION_KEY = "device_check_result_policy_v1"
        const val BINDING_SHA256_KEY = "device_check_result_binding_sha256_v4"
        const val TIER_KEY = "device_check_result_tier_v1"
        const val DISABLED_FEATURES_KEY = "device_check_result_disabled_features_v1"
        const val CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY =
            "device_check_result_camera_dependent_checks_deferred_v2"
        const val METRIC_DEPTH_PROBE_POLICY_VERSION_KEY =
            "device_check_result_metric_depth_probe_policy_v1"
        const val METRIC_DEPTH_SUPPORT_STATE_KEY =
            "device_check_result_metric_depth_support_state_v1"
    }
}
