package kr.co.hanium.dreamup.walksafe.account

import android.content.SharedPreferences
import java.time.Instant
import java.util.Base64
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.security.SensitivePreferenceSpec
import org.json.JSONObject

internal const val EMAIL_ENROLLMENT_SCHEMA =
    "walksafe.account-enrollment-email-otp.v1"
internal const val ACCOUNT_CREATE_SCHEMA = "walksafe.account-create.v1"
internal const val SIGNUP_CONSENT_SCHEMA = "walksafe.signup-consent.v1"

internal val SIGNUP_DOCUMENT_VERSIONS: Map<String, String> = linkedMapOf(
    "terms_of_service" to "walksafe.terms-of-service.v1",
    "privacy_notice" to "walksafe.privacy-notice.v1",
    "location_terms" to "walksafe.location-terms.v1",
    "raw_original" to "FP-013-RAW-1.1.0",
    "automatic_reporting" to "FP-013-AUTO-1.1.0",
    "training_reuse" to "FP-013-TRAINING-1.1.0",
)

internal data class SignupConsentSelections(
    val termsOfService: Boolean = false,
    val privacyNotice: Boolean = false,
    val locationTerms: Boolean = false,
    val rawOriginal: Boolean = false,
    val automaticReporting: Boolean = false,
    val trainingReuse: Boolean = false,
) {
    val requiredGranted: Boolean
        get() = termsOfService && privacyNotice && locationTerms

    fun toWireJson(): JSONObject = JSONObject()
        .put("terms_of_service", termsOfService)
        .put("privacy_notice", privacyNotice)
        .put("location_terms", locationTerms)
        .put("raw_original", rawOriginal)
        .put("automatic_reporting", automaticReporting)
        .put("training_reuse", trainingReuse)

    companion object {
        fun fromExactJson(value: JSONObject): SignupConsentSelections? {
            if (value.jsonKeys() != SIGNUP_DOCUMENT_VERSIONS.keys) return null
            if (SIGNUP_DOCUMENT_VERSIONS.keys.any { value.opt(it) !is Boolean }) return null
            return SignupConsentSelections(
                termsOfService = value.getBoolean("terms_of_service"),
                privacyNotice = value.getBoolean("privacy_notice"),
                locationTerms = value.getBoolean("location_terms"),
                rawOriginal = value.getBoolean("raw_original"),
                automaticReporting = value.getBoolean("automatic_reporting"),
                trainingReuse = value.getBoolean("training_reuse"),
            ).takeIf { it.requiredGranted }
        }
    }
}

internal data class EmailEnrollmentPartial(
    val enrollmentHandle: String,
    val expiresAtEpochMs: Long,
    val resendAvailableAtEpochMs: Long,
    val ownerBindingSha256: String,
    val documentVersions: Map<String, String> = SIGNUP_DOCUMENT_VERSIONS,
    val selections: SignupConsentSelections,
) {
    init {
        require(validEnrollmentHandle(enrollmentHandle))
        require(expiresAtEpochMs > 0L)
        require(resendAvailableAtEpochMs > 0L)
        require(OWNER_BINDING.matches(ownerBindingSha256))
        require(documentVersions == SIGNUP_DOCUMENT_VERSIONS)
        require(selections.requiredGranted)
    }

    fun isValidAt(nowEpochMs: Long, expectedOwnerBindingSha256: String): Boolean =
        nowEpochMs in 0L until expiresAtEpochMs &&
            expectedOwnerBindingSha256 == ownerBindingSha256
}

internal object EmailEnrollmentPartialCodec {
    private const val SCHEMA = "walksafe.android-email-enrollment-partial.v4"
    private val FIELDS = setOf(
        "schema_version",
        "enrollment_handle",
        "expires_at_epoch_ms",
        "resend_available_at_epoch_ms",
        "owner_binding_sha256",
        "document_versions",
        "selections",
    )

    fun encode(partial: EmailEnrollmentPartial): String = JSONObject()
        .put("schema_version", SCHEMA)
        .put("enrollment_handle", partial.enrollmentHandle)
        .put("expires_at_epoch_ms", partial.expiresAtEpochMs)
        .put("resend_available_at_epoch_ms", partial.resendAvailableAtEpochMs)
        .put("owner_binding_sha256", partial.ownerBindingSha256)
        .put("document_versions", JSONObject(partial.documentVersions))
        .put("selections", partial.selections.toWireJson())
        .toString()

    fun decode(encoded: String): EmailEnrollmentPartial? = runCatching {
        if (encoded.length !in 2..4_096) return@runCatching null
        val value = JSONObject(encoded)
        if (
            value.jsonKeys() != FIELDS ||
            value.opt("schema_version") !is String ||
            value.getString("schema_version") != SCHEMA ||
            value.opt("enrollment_handle") !is String ||
            value.opt("owner_binding_sha256") !is String
        ) {
            return@runCatching null
        }
        val versions = value.getJSONObject("document_versions")
        if (
            versions.jsonKeys() != SIGNUP_DOCUMENT_VERSIONS.keys ||
            SIGNUP_DOCUMENT_VERSIONS.any { (key, expected) ->
                versions.opt(key) !is String || versions.getString(key) != expected
            }
        ) return@runCatching null
        val selections = SignupConsentSelections.fromExactJson(
            value.getJSONObject("selections"),
        ) ?: return@runCatching null
        val expiresAtEpochMs = value.strictLongOrNull("expires_at_epoch_ms")
            ?: return@runCatching null
        val resendAvailableAtEpochMs = value.strictLongOrNull(
            "resend_available_at_epoch_ms",
        ) ?: return@runCatching null
        EmailEnrollmentPartial(
            enrollmentHandle = value.getString("enrollment_handle"),
            expiresAtEpochMs = expiresAtEpochMs,
            resendAvailableAtEpochMs = resendAvailableAtEpochMs,
            ownerBindingSha256 = value.getString("owner_binding_sha256"),
            documentVersions = SIGNUP_DOCUMENT_VERSIONS,
            selections = selections,
        )
    }.getOrNull()
}

internal sealed interface EmailEnrollmentRestoreResult {
    data object Absent : EmailEnrollmentRestoreResult
    data class Restored(val partial: EmailEnrollmentPartial) : EmailEnrollmentRestoreResult
    data object ClearedInvalid : EmailEnrollmentRestoreResult
    data object Blocked : EmailEnrollmentRestoreResult
}

internal class AndroidEmailEnrollmentStore(
    preferences: SharedPreferences,
    aead: LocalAead = AndroidKeyStoreAead(KEY_POLICY),
) {
    private val sensitive = AndroidSensitivePreferenceStore(
        preferences = preferences,
        spec = SPEC,
        aead = aead,
    )

    fun save(partial: EmailEnrollmentPartial): Boolean =
        !sensitive.isBlocked() && sensitive.edit()
            .putString(PARTIAL_KEY, EmailEnrollmentPartialCodec.encode(partial))
            .remove(LEGACY_PARTIAL_KEY)
            .commit()

    fun restore(
        nowEpochMs: Long,
        expectedOwnerBindingSha256: String,
    ): EmailEnrollmentRestoreResult {
        if (sensitive.isBlocked()) return EmailEnrollmentRestoreResult.Blocked
        if (sensitive.contains(LEGACY_PARTIAL_KEY)) {
            return if (sensitive.edit().remove(LEGACY_PARTIAL_KEY).remove(PARTIAL_KEY).commit()) {
                EmailEnrollmentRestoreResult.ClearedInvalid
            } else {
                EmailEnrollmentRestoreResult.Blocked
            }
        }
        val encoded = sensitive.getString(PARTIAL_KEY, null)
            ?: return EmailEnrollmentRestoreResult.Absent
        val partial = EmailEnrollmentPartialCodec.decode(encoded)
        if (
            partial == null ||
            !OWNER_BINDING.matches(expectedOwnerBindingSha256) ||
            !partial.isValidAt(nowEpochMs, expectedOwnerBindingSha256)
        ) {
            return if (clear()) {
                EmailEnrollmentRestoreResult.ClearedInvalid
            } else {
                EmailEnrollmentRestoreResult.Blocked
            }
        }
        return EmailEnrollmentRestoreResult.Restored(partial)
    }

    fun clear(): Boolean =
        !sensitive.isBlocked() && sensitive.edit()
            .remove(PARTIAL_KEY)
            .remove(LEGACY_PARTIAL_KEY)
            .commit()

    fun resetAfterConfirmedAccountDeletion(): Boolean = sensitive.destroyAndClear()

    companion object {
        private const val PARTIAL_KEY = "email_enrollment_partial_v4"
        private const val LEGACY_PARTIAL_KEY = "email_enrollment_partial_v3"
        private val KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe.user.email_enrollment.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        )
        private val SPEC = SensitivePreferenceSpec(
            storageKey = "email_enrollment_encrypted_v4",
            blockedKey = "email_enrollment_fail_closed_v4",
            resetPendingKey = "email_enrollment_reset_pending_v4",
            domainAad =
                "kr.co.hanium.dreamup.walksafe|USER|email-enrollment|schema=4"
                    .toByteArray(Charsets.UTF_8),
            keyPolicy = KEY_POLICY,
            exactKeys = setOf(PARTIAL_KEY, LEGACY_PARTIAL_KEY),
        )
    }
}

internal enum class AccountRemoteAction {
    REQUEST_EMAIL_OTP,
    CREATE_ACCOUNT,
    PASSWORD_LOGIN,
}

internal data class AccountRequestToken internal constructor(
    val generation: Long,
    val action: AccountRemoteAction,
    val stateBinding: String,
)

/** Rejects duplicate taps, stale callbacks, and callbacks delivered after backgrounding. */
internal class AccountRequestFence {
    private var foreground = false
    private var generation = 0L
    private var active: AccountRequestToken? = null

    @Synchronized
    fun enteredForeground() {
        foreground = true
    }

    @Synchronized
    fun enteredBackground() {
        foreground = false
        generation += 1L
        active = null
    }

    @Synchronized
    fun begin(action: AccountRemoteAction, stateBinding: String): AccountRequestToken? {
        if (!foreground || active != null || stateBinding.isBlank()) return null
        generation += 1L
        return AccountRequestToken(generation, action, stateBinding).also { active = it }
    }

    @Synchronized
    fun completeIfCurrent(token: AccountRequestToken, stateBinding: String): Boolean {
        if (!foreground || active !== token || token.stateBinding != stateBinding) return false
        active = null
        return true
    }

    @Synchronized
    fun cancel(token: AccountRequestToken) {
        if (active === token) active = null
    }

    @Synchronized
    fun isInFlight(): Boolean = active != null
}

internal fun validEnrollmentHandle(value: String): Boolean = runCatching {
    if (value.length != 43 || !value.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
        return@runCatching false
    }
    val decoded = Base64.getUrlDecoder().decode("$value=")
    decoded.size == 32 &&
        Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
}.getOrDefault(false)

internal fun strictInstantEpochMs(value: String): Long? = runCatching {
    if (!value.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$"))) {
        return@runCatching null
    }
    Instant.parse(value).toEpochMilli()
}.getOrNull()

private val OWNER_BINDING = Regex("^[0-9a-f]{64}$")

private fun JSONObject.jsonKeys(): Set<String> {
    val result = mutableSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) result += iterator.next()
    return result
}

private fun JSONObject.strictLongOrNull(name: String): Long? = when (val value = opt(name)) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    else -> null
}
