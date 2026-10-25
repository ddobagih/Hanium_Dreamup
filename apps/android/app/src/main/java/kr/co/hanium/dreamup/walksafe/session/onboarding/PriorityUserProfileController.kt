package kr.co.hanium.dreamup.walksafe.session.onboarding

import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONArray
import org.json.JSONObject

/** Actor-bound durable progress and its fail-closed marker. Constructors never query storage. */
internal class PriorityUserProfileController(
    private val storage: Storage,
    private val actors: Actors,
    private val digest: (ByteArray) -> String,
    private val config: Config,
) {
    data class Config(val profilePrefix: String, val invalidPrefix: String, val storageVersion: Int,
        val probePolicyVersion: String)
    interface Storage {
        fun initialized(): Boolean
        fun blocked(): Boolean
        fun contains(key: String): Boolean
        fun boolean(key: String, defaultValue: Boolean): Boolean
        fun string(key: String, defaultValue: String?): String?
        fun remove(key: String): Boolean
        fun markInvalid(key: String): Boolean
        fun writeAndClearMarker(key: () -> String, json: () -> String, invalidKey: String): Boolean
    }
    interface Actors {
        fun reporterActorId(): String?
        fun deviceGatePassed(): Boolean
        fun installationId(): String?
        fun gatewayOrigin(): String?
    }

    private lateinit var currentPolicy: PriorityUserOnboardingPolicy
    val policyInitialized: Boolean get() = ::currentPolicy.isInitialized
    var policy: PriorityUserOnboardingPolicy
        get() = currentPolicy
        set(value) { currentPolicy = value }
    var actorId: String? = null
    private val blockedActorHashes = mutableSetOf<String>()
    var nativeCompletedRecord: JSONObject? = null
    fun blockedForActorHash(hash: String): Boolean = hash in blockedActorHashes

    fun priorityUserActorSha256(actorId: String): String =
        digest(actorId.toByteArray())

    fun priorityUserProfileKey(actorId: String): String =
        "${config.profilePrefix}${priorityUserActorSha256(actorId)}"

    fun priorityUserProfileInvalidKey(actorId: String): String =
        "${config.invalidPrefix}${priorityUserActorSha256(actorId)}"

    fun restorePriorityUserOnboardingFromPrefs() {
        nativeCompletedRecord = null
        val actorId = actors.reporterActorId()
        this.actorId = actorId
        if (actorId == null) {
            policy = PriorityUserOnboardingPolicy()
            return
        }
        val actorSha256 = priorityUserActorSha256(actorId)
        if (storage.blocked()) {
            blockedActorHashes.add(actorSha256)
            policy = PriorityUserOnboardingPolicy()
            return
        }
        val profileKey = priorityUserProfileKey(actorId)
        val profileInvalid = actorSha256 in blockedActorHashes ||
            runCatching {
                storage.boolean(
                    priorityUserProfileInvalidKey(actorId),
                    false,
                )
            }.getOrDefault(true)
        if (profileInvalid) {
            policy = PriorityUserOnboardingPolicy()
            return
        }
        val profilePresent = storage.contains(profileKey)
        val rawProfile = runCatching {
            storage.string(profileKey, null)
        }.getOrNull()
        val profile = rawProfile?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (profile != null && !profileMatchesInstallation(profile)) {
            // A different phone requires education and practice again; keep the previous phone's bytes intact.
            policy = PriorityUserOnboardingPolicy()
            return
        }
        val snapshot = if (profile != null) {
            runCatching { decodeProfile(profile, actorSha256) }.getOrNull()
        } else null
        if (profilePresent && snapshot == null) {
            storage.remove(profileKey)
            blockedActorHashes.add(actorSha256)
        }
        if (snapshot != null && profile != null) {
            nativeCompletedRecord = profile.optJSONObject("native_completed_first_run")
        }
        policy = PriorityUserOnboardingPolicy(
            snapshot ?: PriorityUserOnboardingSnapshot(),
        )
    }

    private fun profileMatchesInstallation(profile: JSONObject): Boolean {
        val native = profile.optJSONObject("native_completed_first_run")
        val storedIds = buildList {
            if (profile.has("installation_id")) add(profile.optString("installation_id", ""))
            if (native?.has("installation_id") == true) add(native.optString("installation_id", ""))
        }
        // Older partial profiles had no device identity. Preserve their existing local progress.
        if (storedIds.isEmpty()) return true
        val currentId = actors.installationId()?.takeIf(String::isNotBlank) ?: return false
        return storedIds.all { it == currentId }
    }

    private fun decodeProfile(profile: JSONObject, actorSha256: String): PriorityUserOnboardingSnapshot {
        check(profile.getInt("storage_version") == config.storageVersion)
        check(profile.getString("actor_id_sha256") == actorSha256)
        check(
            profile.getString("policy_version") ==
                PRIORITY_USER_TRAINING_POLICY_VERSION,
        )
        val practices = profile.getJSONArray("completed_practices")
        val completedPractices = buildList {
            for (index in 0 until practices.length()) {
                add(PriorityUserPractice.valueOf(practices.getString(index)))
            }
        }
        check(
            completedPractices ==
                PriorityUserPractice.entries.take(completedPractices.size),
        )
        return PriorityUserOnboardingSnapshot(
            ageBand = PriorityUserAgeBand.valueOf(
                profile.getString("age_band"),
            ),
            guardianVerified = profile.getBoolean("guardian_verified"),
            educationReviewed = profile.getBoolean("education_reviewed"),
            safePracticePlaceConfirmed =
                profile.getBoolean("safe_practice_place_confirmed"),
            completedPractices = completedPractices.toSet(),
            hazardResponseConfirmed = profile.optBoolean("hazard_response_confirmed", false),
            phonePostureAcknowledged = profile.optBoolean("phone_posture_acknowledged", false),
            practiceNecessityReviewed = profile.optBoolean("practice_necessity_reviewed", false),
            educationAccepted = profile.optBoolean("education_accepted", false),
            usageConditionsAcknowledged = profile.optBoolean("usage_conditions_acknowledged", false),
            appUsageReviewed = profile.optBoolean("app_usage_reviewed", false),
            appUsageAccepted = profile.optBoolean("app_usage_accepted", false),
        )
    }

    fun persistPriorityUserOnboarding(): Boolean {
        val actorId = this.actorId ?: return false
        if (actorId != actors.reporterActorId()) return false
        if (storage.blocked()) return false
        val installationId = actors.installationId()?.takeIf(String::isNotBlank) ?: return false
        val invalidKey = priorityUserProfileInvalidKey(actorId)
        if (!runCatching { storage.boolean(invalidKey, false) }.getOrDefault(false)) {
            return false
        }
        val snapshot = policy.snapshot()
        val profile = JSONObject()
            .put("storage_version", config.storageVersion)
            .put("actor_id_sha256", priorityUserActorSha256(actorId))
            .put("installation_id", installationId)
            .put("policy_version", snapshot.policyVersion)
            .put("age_band", snapshot.ageBand.name)
            .put("guardian_verified", snapshot.guardianVerified)
            .put("education_reviewed", snapshot.educationReviewed)
            .put("phone_posture_acknowledged", snapshot.phonePostureAcknowledged)
            .put("practice_necessity_reviewed", snapshot.practiceNecessityReviewed)
            .put("education_accepted", snapshot.educationAccepted)
            .put("usage_conditions_acknowledged", snapshot.usageConditionsAcknowledged)
            .put("app_usage_reviewed", snapshot.appUsageReviewed)
            .put("app_usage_accepted", snapshot.appUsageAccepted)
            .put("hazard_response_confirmed", snapshot.hazardResponseConfirmed)
            .put("native_completed_first_run", nativeCompletedRecord ?: JSONObject.NULL)
            .put(
                "safe_practice_place_confirmed",
                snapshot.safePracticePlaceConfirmed,
            )
            .put(
                "completed_practices",
                JSONArray(
                    PriorityUserPractice.entries
                        .filter(snapshot.completedPractices::contains)
                        .map(PriorityUserPractice::name),
                ),
            )
        val committed = storage.writeAndClearMarker(
            { priorityUserProfileKey(actorId) }, { profile.toString() }, invalidKey)
        if (committed) {
            blockedActorHashes.remove(
                priorityUserActorSha256(actorId),
            )
        }
        return committed
    }

    fun beginPriorityUserProfileMutationOrFailClosed(): Boolean {
        val actorId = this.actorId ?: return false
        if (actorId != actors.reporterActorId()) return false
        val actorSha256 = priorityUserActorSha256(actorId)
        val invalidKey = priorityUserProfileInvalidKey(actorId)
        val invalidated = !storage.blocked() &&
            storage.markInvalid(invalidKey)
        if (invalidated) {
            blockedActorHashes.remove(actorSha256)
        } else {
            blockedActorHashes.add(actorSha256)
            policy = PriorityUserOnboardingPolicy()
        }
        return invalidated
    }

    fun persistPriorityUserOnboardingOrFailClosed(): Boolean {
        if (persistPriorityUserOnboarding()) return true
        actorId?.let { actorId ->
            blockedActorHashes.add(
                priorityUserActorSha256(actorId),
            )
        }
        policy = PriorityUserOnboardingPolicy()
        return false
    }

    fun persistNativeCompletedOnboarding(snapshot: FirstRunOnboardingSnapshot): Boolean {
        val actorId = snapshot.verifiedActorBinding?.value ?: return false
        if (!snapshot.isComplete || actorId != actors.reporterActorId() || actorId != this.actorId ||
            !policy.snapshot().nativeEducationComplete ||
            !actors.deviceGatePassed()) return false
        val installationId = actors.installationId() ?: return false
        val origin = actors.gatewayOrigin() ?: return false
        val receipts = JSONObject()
        snapshot.completedReceiptHashes.forEach { (stage, hash) -> receipts.put(stage.name, hash.value) }
        val record = JSONObject()
            .put("version", 1)
            .put("actor_id_sha256", priorityUserActorSha256(actorId))
            .put("installation_id", installationId)
            .put("gateway_origin", origin)
            .put("policy_version", snapshot.policyVersion)
            .put("probe_policy_version", config.probePolicyVersion)
            .put("epoch", snapshot.epoch)
            .put("revision", snapshot.revision)
            .put("age_band", snapshot.ageBand?.name)
            .put("receipts", receipts)
        if (!beginPriorityUserProfileMutationOrFailClosed()) return false
        nativeCompletedRecord = record
        return persistPriorityUserOnboardingOrFailClosed()
    }

    fun restoreCompletedNativeOnboarding(actorId: String): FirstRunOnboardingSnapshot? {
        if (!storage.initialized() || storage.blocked()) return null
        val actorHash = priorityUserActorSha256(actorId)
        if (actorHash in blockedActorHashes) return null
        return runCatching {
            check(!storage.boolean(priorityUserProfileInvalidKey(actorId), false))
            val raw = storage.string(priorityUserProfileKey(actorId), null) ?: return null
            val profile = JSONObject(raw)
            check(profileMatchesInstallation(profile))
            check(decodeProfile(profile, actorHash).nativeEducationComplete)
            val record = profile.getJSONObject("native_completed_first_run")
            check(record.getInt("version") == 1)
            check(record.getString("actor_id_sha256") == actorHash)
            check(record.getString("installation_id") == actors.installationId())
            check(record.getString("gateway_origin") == actors.gatewayOrigin())
            check(record.getString("policy_version") ==
                kr.co.hanium.dreamup.walksafe.session.EMAIL_ACCOUNT_ONBOARDING_POLICY_VERSION)
            val rawReceipts = record.getJSONObject("receipts")
            val receipts = mutableMapOf<FirstRunOnboardingStage, FirstRunReceiptHash>()
            rawReceipts.keys().forEach { key ->
                receipts[FirstRunOnboardingStage.valueOf(key)] =
                    FirstRunReceiptHash.fromSha256Hex(rawReceipts.getString(key))
            }
            val completed = FirstRunOnboardingSnapshot(
                policyVersion = record.getString("policy_version"),
                flow = FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4,
                epoch = record.getLong("epoch"),
                revision = record.getLong("revision"),
                stage = FirstRunOnboardingStage.COMPLETE,
                ageBand = FirstRunAgeBand.valueOf(record.getString("age_band")),
                completedReceiptHashes = receipts.toMap(),
                localCredentialPhoneSubmissionHandle = null,
                verifiedActorBinding = FirstRunOpaqueActorBinding.fromProvider(actorId),
                pendingAttempt = null,
            )
            val sameProbePolicy = record.getString("probe_policy_version") == config.probePolicyVersion
            if (sameProbePolicy) completed else {
                // A probe update on the same phone needs fresh hardware checks while retaining its education.
                check(completed.revision < Long.MAX_VALUE)
                completed.copy(
                    revision = completed.revision + 1L,
                    stage = FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION,
                )
            }
        }.getOrNull()
    }
}
