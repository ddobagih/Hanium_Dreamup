package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.content.SharedPreferences
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.security.SensitivePreferenceSpec
import org.json.JSONObject

/** Stores account-scoped motion calibration without exposing the raw actor ID at rest. */
internal class AndroidPedestrianProfileStore private constructor(
    private val storage: PedestrianProfileStorage,
) {
    constructor(preferences: SharedPreferences) : this(
        SensitivePedestrianProfileStorage(
            AndroidSensitivePreferenceStore(preferences, PROFILE_PREFERENCE_SPEC),
        ),
    )

    internal constructor(
        storage: PedestrianProfileStorage,
        testOnly: Unit = Unit,
    ) : this(storage)

    @Synchronized
    fun load(actorId: String): PersistedPedestrianMotionProfile? {
        val key = profileKey(actorId) ?: return null
        if (storage.isBlocked() || !storage.contains(key)) return null
        val encoded = storage.getString(key) ?: return failClosedLoad()
        val profile = decodeProfile(encoded) ?: return failClosedLoad()
        return profile
    }

    @Synchronized
    fun save(actorId: String, profile: PersistedPedestrianMotionProfile): Boolean {
        val key = profileKey(actorId) ?: return false
        if (storage.isBlocked() || !isValidProfile(profile)) return false
        val encoded = encodeProfile(profile)
        return storage.putString(key, encoded)
    }

    @Synchronized
    fun delete(actorId: String): Boolean {
        val key = profileKey(actorId) ?: return false
        if (storage.isBlocked()) return false
        return storage.remove(key)
    }

    private fun failClosedLoad(): PersistedPedestrianMotionProfile? {
        storage.failClosed()
        return null
    }

    private fun encodeProfile(profile: PersistedPedestrianMotionProfile): String = JSONObject()
        .put("schema_version", PROFILE_SCHEMA_VERSION)
        .put("step_length_m", profile.stepLengthM)
        .put("mean_walking_speed_mps", profile.meanWalkingSpeedMps)
        .put("speed_variance_mps2", profile.speedVarianceMps2)
        .put("accepted_sample_count", profile.acceptedSampleCount)
        .toString()

    private fun decodeProfile(encoded: String): PersistedPedestrianMotionProfile? = runCatching {
        require(encoded.length <= MAX_PROFILE_JSON_CHARS)
        val root = JSONObject(encoded)
        require(root.keysAsSet() == PROFILE_FIELDS)
        require(root.getString("schema_version") == PROFILE_SCHEMA_VERSION)
        val acceptedSampleCountValue = root.requireNumber("accepted_sample_count")
        val acceptedSampleCountAsDouble = acceptedSampleCountValue.toDouble()
        val acceptedSampleCount = acceptedSampleCountValue.toLong()
        require(acceptedSampleCountAsDouble.isFinite())
        require(acceptedSampleCountAsDouble == acceptedSampleCount.toDouble())
        val profile = PersistedPedestrianMotionProfile(
            stepLengthM = root.requireNumber("step_length_m").toDouble(),
            meanWalkingSpeedMps = root.requireNumber("mean_walking_speed_mps").toDouble(),
            speedVarianceMps2 = root.requireNumber("speed_variance_mps2").toDouble(),
            acceptedSampleCount = acceptedSampleCount,
        )
        require(isValidProfile(profile))
        profile
    }.getOrNull()

    private fun isValidProfile(profile: PersistedPedestrianMotionProfile): Boolean =
        PedestrianMotionProfile().restore(profile)

    private fun profileKey(actorId: String): String? {
        if (actorId.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(actorId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return PROFILE_KEY_PREFIX + digest
    }

    private companion object {
        const val PROFILE_SCHEMA_VERSION = "walksafe.pedestrian-motion-profile.v1"
        const val PROFILE_KEY_PREFIX = "pedestrian_motion_profile_v1_"
        const val MAX_PROFILE_JSON_CHARS = 1_024
        val PROFILE_FIELDS = setOf(
            "schema_version",
            "step_length_m",
            "mean_walking_speed_mps",
            "speed_variance_mps2",
            "accepted_sample_count",
        )
        val PROFILE_PREFERENCE_SPEC = SensitivePreferenceSpec(
            storageKey = "pedestrian_motion_profiles_encrypted_v1",
            blockedKey = "pedestrian_motion_profiles_fail_closed_v1",
            resetPendingKey = "pedestrian_motion_profiles_reset_pending_v1",
            domainAad =
                "kr.co.hanium.dreamup.walksafe|USER|navigation|pedestrian-motion-profile|schema=1"
                    .toByteArray(Charsets.UTF_8),
            keyPolicy = AeadKeyPolicy(
                aliasPrefix = "walksafe.user.navigation.pedestrian_motion_profile.aead.v",
                currentVersion = 1,
                readableVersions = setOf(1),
            ),
            exactKeys = emptySet(),
            keyPrefixes = setOf(PROFILE_KEY_PREFIX),
        )
    }
}

internal interface PedestrianProfileStorage {
    fun isBlocked(): Boolean
    fun contains(key: String): Boolean
    fun getString(key: String): String?
    fun putString(key: String, value: String): Boolean
    fun remove(key: String): Boolean
    fun failClosed()
}

private class SensitivePedestrianProfileStorage(
    private val preferences: AndroidSensitivePreferenceStore,
) : PedestrianProfileStorage {
    override fun isBlocked(): Boolean = preferences.isBlocked()

    override fun contains(key: String): Boolean = preferences.contains(key)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String): Boolean =
        preferences.edit().putString(key, value).commit()

    override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()

    override fun failClosed() = preferences.failClosed()
}

private fun JSONObject.keysAsSet(): Set<String> = buildSet { keys().forEach(::add) }

private fun JSONObject.requireNumber(key: String): Number {
    val value = get(key)
    require(value is Number)
    return value
}
