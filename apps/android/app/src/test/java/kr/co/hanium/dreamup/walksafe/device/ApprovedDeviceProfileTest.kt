package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedDeviceProfileTest {
    @Test
    fun productionInventoryIsEmptyUntilARealDeviceProfileIsApproved() {
        assertTrue(WalkSafeApprovedDeviceProfiles.production.isEmpty())
        assertEquals(64, WalkSafeApprovedDeviceProfiles.registryContentSha256.length)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            WalkSafeApprovedDeviceProfiles.registryContentSha256,
        )

        val match = ApprovedDeviceProfileMatcher.match(
            identity = identity(),
            profiles = WalkSafeApprovedDeviceProfiles.production,
        )

        assertFalse(match.approved)
        assertEquals(ApprovedDeviceProfileMatchReason.NO_EXACT_MATCH, match.reason)
    }

    @Test
    fun exactIdentityAndInclusiveSdkRangeYieldApprovedVersionedMatch() {
        val profile = profile()

        listOf(profile.minSdk, profile.maxSdk).forEach { sdk ->
            val match = ApprovedDeviceProfileMatcher.match(
                identity = identity(sdk = sdk),
                profiles = listOf(profile),
            )

            assertTrue(match.approved)
            assertEquals(profile.profileId, match.profileId)
            assertEquals(profile.version, match.profileVersion)
            assertEquals(ApprovedDeviceProfileMatchReason.EXACT_MATCH, match.reason)
        }
    }

    @Test
    fun everyDeviceIdentityFieldMustMatchExactly() {
        val profile = profile()
        val mismatches = listOf(
            identity().copy(manufacturer = "walksafe"),
            identity().copy(model = "Model-X "),
            identity().copy(device = "DEVICE-X"),
            identity(sdk = profile.minSdk - 1),
            identity(sdk = profile.maxSdk + 1),
        )

        mismatches.forEach { candidate ->
            val match = ApprovedDeviceProfileMatcher.match(candidate, listOf(profile))

            assertFalse(match.approved)
            assertEquals(ApprovedDeviceProfileMatchReason.NO_EXACT_MATCH, match.reason)
            assertNull(match.profileVersion)
        }
    }

    @Test
    fun blankVersionOrInvalidProfileFailsClosed() {
        val invalidProfiles = listOf(
            profile().copy(version = "  "),
            profile().copy(profileId = ""),
            profile().copy(minSdk = 36, maxSdk = 35),
            profile().copy(manufacturer = ""),
        )

        invalidProfiles.forEach { invalidProfile ->
            val match = ApprovedDeviceProfileMatcher.match(identity(), listOf(invalidProfile))

            assertFalse(match.approved)
            assertEquals(ApprovedDeviceProfileMatchReason.INVALID_PROFILE, match.reason)
            assertNull(match.profileVersion)
        }
    }

    @Test
    fun blankOrInvalidRuntimeIdentityFailsClosed() {
        val invalidIdentities = listOf(
            identity().copy(manufacturer = ""),
            identity().copy(model = " "),
            identity().copy(device = ""),
            identity(sdk = 0),
        )

        invalidIdentities.forEach { invalidIdentity ->
            val match = ApprovedDeviceProfileMatcher.match(invalidIdentity, listOf(profile()))

            assertFalse(match.approved)
            assertEquals(ApprovedDeviceProfileMatchReason.INVALID_DEVICE_IDENTITY, match.reason)
        }
    }

    @Test
    fun ambiguousDuplicateMatchesFailClosed() {
        val match = ApprovedDeviceProfileMatcher.match(
            identity = identity(),
            profiles = listOf(profile(), profile().copy(profileId = "profile-002")),
        )

        assertFalse(match.approved)
        assertEquals(ApprovedDeviceProfileMatchReason.AMBIGUOUS_MATCH, match.reason)
        assertNull(match.profileId)
        assertNull(match.profileVersion)
    }

    private fun identity(sdk: Int = 35) = WalkSafeDeviceIdentity(
        manufacturer = "WalkSafe",
        model = "Model-X",
        device = "device-x",
        sdk = sdk,
    )

    private fun profile() = ApprovedDeviceProfile(
        profileId = "profile-001",
        version = "2026.07.22-1",
        manufacturer = "WalkSafe",
        model = "Model-X",
        device = "device-x",
        minSdk = 34,
        maxSdk = 36,
    )
}
