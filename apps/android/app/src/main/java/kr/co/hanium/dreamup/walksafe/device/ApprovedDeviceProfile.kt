package kr.co.hanium.dreamup.walksafe.device

import java.security.MessageDigest

data class WalkSafeDeviceIdentity(
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdk: Int,
)

data class ApprovedDeviceProfile(
    val profileId: String,
    val version: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val minSdk: Int,
    val maxSdk: Int,
)

enum class ApprovedDeviceProfileMatchReason {
    EXACT_MATCH,
    NO_EXACT_MATCH,
    INVALID_DEVICE_IDENTITY,
    INVALID_PROFILE,
    AMBIGUOUS_MATCH,
}

data class ApprovedDeviceProfileMatch(
    val approved: Boolean,
    val profileId: String?,
    val profileVersion: String?,
    val reason: ApprovedDeviceProfileMatchReason,
)

object WalkSafeApprovedDeviceProfiles {
    const val REGISTRY_ID = "WS-ANDROID-APPROVED-DEVICE-PROFILES"
    const val REGISTRY_REVISION = "20260722-r001-empty"

    // A real profile may be added only after designated-device approval evidence exists.
    val production: List<ApprovedDeviceProfile> = emptyList()

    val registryContentSha256: String by lazy {
        val canonical = production
            .sortedBy { it.profileId }
            .joinToString("\n") { profile ->
                listOf(
                    profile.profileId,
                    profile.version,
                    profile.manufacturer,
                    profile.model,
                    profile.device,
                    profile.minSdk.toString(),
                    profile.maxSdk.toString(),
                ).joinToString("\u0000")
            }
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

object ApprovedDeviceProfileMatcher {
    fun match(
        identity: WalkSafeDeviceIdentity,
        profiles: List<ApprovedDeviceProfile> = WalkSafeApprovedDeviceProfiles.production,
    ): ApprovedDeviceProfileMatch {
        if (!identity.isValid()) {
            return rejected(ApprovedDeviceProfileMatchReason.INVALID_DEVICE_IDENTITY)
        }
        if (profiles.any { !it.isValid() }) {
            return rejected(ApprovedDeviceProfileMatchReason.INVALID_PROFILE)
        }

        val matches = profiles.filter { profile ->
            identity.manufacturer == profile.manufacturer &&
                identity.model == profile.model &&
                identity.device == profile.device &&
                identity.sdk in profile.minSdk..profile.maxSdk
        }
        if (matches.size > 1) {
            return rejected(ApprovedDeviceProfileMatchReason.AMBIGUOUS_MATCH)
        }
        val match = matches.singleOrNull()
            ?: return rejected(ApprovedDeviceProfileMatchReason.NO_EXACT_MATCH)
        return ApprovedDeviceProfileMatch(
            approved = true,
            profileId = match.profileId,
            profileVersion = match.version,
            reason = ApprovedDeviceProfileMatchReason.EXACT_MATCH,
        )
    }

    private fun WalkSafeDeviceIdentity.isValid(): Boolean =
        manufacturer.isNotBlank() && model.isNotBlank() && device.isNotBlank() && sdk > 0

    private fun ApprovedDeviceProfile.isValid(): Boolean =
        profileId.isNotBlank() &&
            version.isNotBlank() &&
            manufacturer.isNotBlank() &&
            model.isNotBlank() &&
            device.isNotBlank() &&
            minSdk > 0 &&
            maxSdk >= minSdk

    private fun rejected(reason: ApprovedDeviceProfileMatchReason) = ApprovedDeviceProfileMatch(
        approved = false,
        profileId = null,
        profileVersion = null,
        reason = reason,
    )
}
