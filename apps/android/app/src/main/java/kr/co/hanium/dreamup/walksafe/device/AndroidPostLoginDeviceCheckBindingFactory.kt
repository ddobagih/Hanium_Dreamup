package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.os.Build
import kr.co.hanium.dreamup.walksafe.BuildConfig

/** Current shared owners provide copied facts; a null capture preserves the startup guard. */
internal data class PostLoginDeviceCheckBindingFacts(
    val installationId: String,
    val startup: WalkSafeStartupCapabilityInput,
    val environmentProfileId: String?,
    val environmentProfileRevision: String?,
    val approvedDeviceProfileId: String?,
    val approvedDeviceProfileVersion: String?,
    val missingRequiredPermissions: Set<String>,
    val locationServiceEnabled: Boolean,
    val voiceDisclosureAccepted: Boolean,
)

/** Builds the existing durable identity from platform/app metadata without owning a session. */
internal class AndroidPostLoginDeviceCheckBindingFactory(
    context: Context,
    private val capture: () -> PostLoginDeviceCheckBindingFacts?,
) {
    private val context = context.applicationContext

    fun create(actorId: String): PostLoginDeviceCheckResultBinding? {
        val facts = capture() ?: return null
        return PostLoginDeviceCheckResultBinding(
            actorId = actorId,
            installationId = facts.installationId,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            deviceName = Build.DEVICE,
            osSdkInt = Build.VERSION.SDK_INT,
            osBuildFingerprint = Build.FINGERPRINT,
            appId = context.packageName,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            appVersionName = BuildConfig.VERSION_NAME,
            appSourceRevision = BuildConfig.WALKSAFE_SOURCE_COMMIT,
            probePolicyVersion = POST_LOGIN_DEVICE_CHECK_PROBE_POLICY_VERSION,
            environmentProfileId = facts.environmentProfileId,
            environmentProfileRevision = facts.environmentProfileRevision,
            approvedDeviceProfileRegistryRevision = WalkSafeApprovedDeviceProfiles.REGISTRY_REVISION,
            approvedDeviceProfileRegistrySha256 = WalkSafeApprovedDeviceProfiles.registryContentSha256,
            approvedDeviceProfileId = facts.approvedDeviceProfileId,
            approvedDeviceProfileVersion = facts.approvedDeviceProfileVersion,
            missingRequiredPermissions = facts.missingRequiredPermissions,
            locationServiceEnabled = facts.locationServiceEnabled,
            voiceDisclosureAccepted = facts.voiceDisclosureAccepted,
            offlineKoreanTextToSpeechAvailable = facts.startup.offlineKoreanTextToSpeechAvailable,
            onDeviceSpeechRecognitionAvailable = facts.startup.onDeviceSpeechRecognitionAvailable,
        )
    }
}
