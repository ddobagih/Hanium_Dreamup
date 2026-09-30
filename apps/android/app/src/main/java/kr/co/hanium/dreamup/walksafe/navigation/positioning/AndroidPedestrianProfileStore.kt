package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.content.SharedPreferences
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.security.SensitivePreferenceSpec
import org.json.JSONObject

/** Why a load returned (or did not return) a stored profile; defaults and storage failure differ. */
internal enum class PedestrianProfileLoadStatus {
    /** No stored profile for the actor: the general default step length is in use. */
    NO_PROFILE,
    LOADED,
    /** A supported older schema was read and rewritten in the current schema. */
    MIGRATED,
    /**
     * The record decrypted but was invalid or came from another profile model. Only that record
     * was removed; defaults are in use until a new calibration.
     */
    RESET_INVALID_RECORD,
    /** Encrypted storage is blocked (authentication or key failure); nothing is read or written. */
    STORE_BLOCKED,
}

internal data class PedestrianProfileLoadResult(
    val status: PedestrianProfileLoadStatus,
    val profile: PersistedPedestrianMotionProfile? = null,
    /** Wall-clock time of the calibration that produced the record; null when unknown (v1). */
    val lastCalibratedAtEpochMs: Long? = null,
)

/** Stores account-scoped motion calibration without exposing the raw actor ID at rest. */
internal class AndroidPedestrianProfileStore private constructor(
    private val storage: PedestrianProfileStorage,
    private val nowEpochMs: () -> Long,
) {
    constructor(preferences: SharedPreferences) : this(
        SensitivePedestrianProfileStorage(
            AndroidSensitivePreferenceStore(preferences, PROFILE_PREFERENCE_SPEC),
        ),
        System::currentTimeMillis,
    )

    internal constructor(
        storage: PedestrianProfileStorage,
        testOnly: Unit = Unit,
        nowEpochMs: () -> Long = System::currentTimeMillis,
    ) : this(storage, nowEpochMs)

    @Synchronized
    fun load(actorId: String): PersistedPedestrianMotionProfile? = loadResult(actorId).profile

    @Synchronized
    fun loadResult(actorId: String): PedestrianProfileLoadResult {
        val key = profileKey(actorId) ?: return PedestrianProfileLoadResult(PedestrianProfileLoadStatus.NO_PROFILE)
        if (storage.isBlocked()) return PedestrianProfileLoadResult(PedestrianProfileLoadStatus.STORE_BLOCKED)
        if (!storage.contains(key)) return PedestrianProfileLoadResult(PedestrianProfileLoadStatus.NO_PROFILE)
        // A listed key without a readable value is a storage inconsistency, not a bad record.
        val encoded = storage.getString(key) ?: return failClosedLoad()
        // Authentication already succeeded in the encrypted store, so a record that fails schema or
        // value validation is removed alone instead of blocking every actor's personalization.
        val decoded = decodeProfile(encoded) ?: return removeInvalidRecord(key)
        if (decoded.modelVersion != PROFILE_MODEL_VERSION) return removeInvalidRecord(key)
        if (decoded.schemaVersion == PROFILE_SCHEMA_VERSION) {
            return PedestrianProfileLoadResult(
                PedestrianProfileLoadStatus.LOADED,
                decoded.profile,
                decoded.lastCalibratedAtEpochMs,
            )
        }
        // v1 carried no calibration time and v1/v2 no usage condition (S09-43); keep them unknown
        // instead of inventing values.
        val profile = decoded.profile
        val migrated = storage.putString(key, encodeProfile(profile, lastCalibratedAtEpochMs = decoded.lastCalibratedAtEpochMs))
        return PedestrianProfileLoadResult(
            if (migrated) PedestrianProfileLoadStatus.MIGRATED else PedestrianProfileLoadStatus.LOADED,
            profile,
            decoded.lastCalibratedAtEpochMs,
        )
    }

    /**
     * Saves a profile produced by an accepted calibration; the save time is recorded as the last
     * calibration time only when the profile's calibration condition is known. A restored v1 value
     * saved again without recalibration (for example after a warm-up sample) keeps an unknown time
     * instead of gaining the save time (S09-40/S09-43).
     */
    @Synchronized
    fun save(actorId: String, profile: PersistedPedestrianMotionProfile): Boolean {
        val key = profileKey(actorId) ?: return false
        if (storage.isBlocked() || !isValidProfile(profile)) return false
        val now = nowEpochMs()
        if (now < 0L) return false
        val encoded = encodeProfile(profile, lastCalibratedAtEpochMs = now.takeIf { profile.calibrationConditionKnown })
        return storage.putString(key, encoded)
    }

    @Synchronized
    fun delete(actorId: String): Boolean {
        val key = profileKey(actorId) ?: return false
        if (storage.isBlocked()) return false
        return storage.remove(key)
    }

    /**
     * Explicit reset of every stored profile, including a blocked store. Never called implicitly:
     * a blocked store stays blocked until an explicit reset (account deletion, user reset).
     */
    @Synchronized
    fun resetAllProfiles(): Boolean = storage.resetAll()

    private fun failClosedLoad(): PedestrianProfileLoadResult {
        storage.failClosed()
        return PedestrianProfileLoadResult(PedestrianProfileLoadStatus.STORE_BLOCKED)
    }

    private fun removeInvalidRecord(key: String): PedestrianProfileLoadResult =
        if (storage.remove(key)) {
            PedestrianProfileLoadResult(PedestrianProfileLoadStatus.RESET_INVALID_RECORD)
        } else {
            failClosedLoad()
        }

    private fun encodeProfile(profile: PersistedPedestrianMotionProfile, lastCalibratedAtEpochMs: Long?): String =
        JSONObject()
            .put("schema_version", PROFILE_SCHEMA_VERSION)
            .put("model_version", PROFILE_MODEL_VERSION)
            .put("step_length_m", profile.stepLengthM)
            .put("mean_walking_speed_mps", profile.meanWalkingSpeedMps)
            .put("speed_variance_mps2", profile.speedVarianceMps2)
            .put("accepted_sample_count", profile.acceptedSampleCount)
            .put("last_calibrated_at_epoch_ms", lastCalibratedAtEpochMs ?: JSONObject.NULL)
            // S09-43: enum names only (FP-046 minimum collection); null when not recorded.
            .put("step_input", profile.calibrationCondition?.stepInput ?: JSONObject.NULL)
            .put("mounting", profile.calibrationCondition?.mounting ?: JSONObject.NULL)
            .toString()

    private data class DecodedProfile(
        val schemaVersion: String,
        val modelVersion: String,
        val profile: PersistedPedestrianMotionProfile,
        val lastCalibratedAtEpochMs: Long?,
    )

    private fun decodeProfile(encoded: String): DecodedProfile? = runCatching {
        require(encoded.length <= MAX_PROFILE_JSON_CHARS)
        val root = JSONObject(encoded)
        val schemaVersion = root.getString("schema_version")
        val v3 = schemaVersion == PROFILE_SCHEMA_VERSION
        val v2 = v3 || when (schemaVersion) {
            PROFILE_SCHEMA_VERSION_V2 -> true
            PROFILE_SCHEMA_VERSION_V1 -> false
            else -> throw IllegalArgumentException("unsupported profile schema")
        }
        require(root.keysAsSet() == when {
            v3 -> PROFILE_FIELDS
            v2 -> PROFILE_FIELDS_V2
            else -> PROFILE_FIELDS_V1
        })
        val acceptedSampleCountValue = root.requireNumber("accepted_sample_count")
        val acceptedSampleCountAsDouble = acceptedSampleCountValue.toDouble()
        val acceptedSampleCount = acceptedSampleCountValue.toLong()
        require(acceptedSampleCountAsDouble.isFinite())
        require(acceptedSampleCountAsDouble == acceptedSampleCount.toDouble())
        val lastCalibratedAtEpochMs = if (v2 && !root.isNull("last_calibrated_at_epoch_ms")) {
            val value = root.requireNumber("last_calibrated_at_epoch_ms")
            require(value.toDouble() == value.toLong().toDouble() && value.toLong() >= 0L)
            value.toLong()
        } else {
            null
        }
        val condition = if (v3) {
            PedestrianCalibrationCondition(
                stepInput = root.optionalName("step_input"),
                mounting = root.optionalName("mounting"),
            ).takeIf { it.stepInput != null || it.mounting != null }
        } else {
            null
        }
        val profile = PersistedPedestrianMotionProfile(
            stepLengthM = root.requireNumber("step_length_m").toDouble(),
            meanWalkingSpeedMps = root.requireNumber("mean_walking_speed_mps").toDouble(),
            speedVarianceMps2 = root.requireNumber("speed_variance_mps2").toDouble(),
            acceptedSampleCount = acceptedSampleCount,
            // v1, or a v2 record migrated from v1, has no known calibration time.
            calibrationConditionKnown = lastCalibratedAtEpochMs != null,
            calibrationCondition = condition,
        )
        require(isValidProfile(profile))
        DecodedProfile(
            schemaVersion = schemaVersion,
            // v1 predates model versioning; it was produced by the first profile model.
            modelVersion = if (v2) root.getString("model_version") else PROFILE_MODEL_VERSION_1,
            profile = profile,
            lastCalibratedAtEpochMs = lastCalibratedAtEpochMs,
        )
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
        const val PROFILE_SCHEMA_VERSION_V1 = "walksafe.pedestrian-motion-profile.v1"
        const val PROFILE_SCHEMA_VERSION_V2 = "walksafe.pedestrian-motion-profile.v2"

        /** S09-43: v3 adds the calibration usage condition (step input and mounting enum names). */
        const val PROFILE_SCHEMA_VERSION = "walksafe.pedestrian-motion-profile.v3"
        const val PROFILE_MODEL_VERSION_1 = "walksafe.pedestrian-motion-profile-model.1"

        /** Bump when PedestrianMotionProfile learning changes; older records then need recalibration. */
        const val PROFILE_MODEL_VERSION = PROFILE_MODEL_VERSION_1

        // Key namespace, not the record schema; kept so existing records stay addressable.
        const val PROFILE_KEY_PREFIX = "pedestrian_motion_profile_v1_"
        const val MAX_PROFILE_JSON_CHARS = 1_024
        val PROFILE_FIELDS_V1 = setOf(
            "schema_version",
            "step_length_m",
            "mean_walking_speed_mps",
            "speed_variance_mps2",
            "accepted_sample_count",
        )
        val PROFILE_FIELDS_V2 = PROFILE_FIELDS_V1 + setOf("model_version", "last_calibrated_at_epoch_ms")
        val PROFILE_FIELDS = PROFILE_FIELDS_V2 + setOf("step_input", "mounting")
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

    /** Explicit destruction of every profile and the blocked marker. */
    fun resetAll(): Boolean
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

    override fun resetAll(): Boolean = preferences.destroyAndClear()
}

private fun JSONObject.keysAsSet(): Set<String> = buildSet { keys().forEach(::add) }

private fun JSONObject.optionalName(key: String): String? {
    if (isNull(key)) return null
    val value = get(key)
    require(value is String)
    return value
}

private fun JSONObject.requireNumber(key: String): Number {
    val value = get(key)
    require(value is Number)
    return value
}
