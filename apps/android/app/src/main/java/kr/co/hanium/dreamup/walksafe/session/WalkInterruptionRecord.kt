package kr.co.hanium.dreamup.walksafe.session

import java.security.MessageDigest
import org.json.JSONObject

enum class WalkInterruptionLastState {
    ACTIVE,
    PAUSED,
    SAFE_STOP,
    UNKNOWN,
}

/**
 * Versioned previous-walk interruption record (S03-16; FP-018 design_rules[0], FP-018-DETAIL-03).
 *
 * It keeps only the opaque walk id, the last lifecycle state, a machine reason code, a hashed
 * account binding and the wall-clock record time. It never holds location, destination, image,
 * voice or readiness evidence, and it never restores or activates a walk: it only selects the
 * notice shown after the app is opened again. The legacy Boolean marker stays the authority for
 * whether an interruption is pending ([PreviousWalkInterruptionMarker]).
 */
data class WalkInterruptionRecord(
    val walkSessionId: String?,
    val lastState: WalkInterruptionLastState,
    val reasonCode: String,
    val actorBinding: String?,
    val recordedAtEpochMs: Long?,
) {
    fun encode(): String = JSONObject()
        .put("version", VERSION)
        .put("walkSessionId", walkSessionId ?: JSONObject.NULL)
        .put("lastState", lastState.name)
        .put("reasonCode", reasonCode)
        .put("actorBinding", actorBinding ?: JSONObject.NULL)
        .put("recordedAtEpochMs", recordedAtEpochMs ?: JSONObject.NULL)
        .toString()

    /**
     * A normal pause, a safety stop and an interrupted active walk get different explanations.
     * When the record belongs to another account, only the account-neutral notice is shown.
     */
    fun noticeMessageKo(currentActorId: String?): String {
        val currentBinding = actorBindingOf(currentActorId)
        val otherAccount = actorBinding != null && currentBinding != null &&
            actorBinding != currentBinding
        val cause = when (if (otherAccount) WalkInterruptionLastState.UNKNOWN else lastState) {
            WalkInterruptionLastState.ACTIVE ->
                "이전 보행은 안내 중에 앱이 갑자기 종료되어 중단되었습니다."
            WalkInterruptionLastState.PAUSED ->
                "이전 보행은 일시중지된 상태에서 앱이 종료되어 끝났습니다."
            WalkInterruptionLastState.SAFE_STOP ->
                "이전 보행은 안전 중지된 뒤 앱이 종료되었습니다. 안전 중지 원인이 해결됐는지 먼저 확인해 주세요."
            WalkInterruptionLastState.UNKNOWN ->
                "이전 보행은 앱 종료로 중단되었습니다."
        }
        return "$cause 자동으로 재개하지 않습니다. " +
            "새 보행을 시작하려면 기기 점검과 보행 시작을 다시 진행해 주세요."
    }

    companion object {
        const val VERSION = 2
        const val LEGACY_REASON = "legacy_boolean_marker"
        /** Pending Boolean marker without a readable version-2 record. */
        val LEGACY = WalkInterruptionRecord(
            walkSessionId = null,
            lastState = WalkInterruptionLastState.UNKNOWN,
            reasonCode = LEGACY_REASON,
            actorBinding = null,
            recordedAtEpochMs = null,
        )
        private const val MAX_REASON_LENGTH = 160
        private val UNSAFE_REASON_CHARS = Regex("[^a-z0-9_:.,-]")

        /** Record for the walk that would be interrupted if the process ended now, or null. */
        fun forCurrentWalk(
            state: WalkSessionState,
            walkSessionId: String,
            recoveryStage: WalkSessionRecoveryStage?,
            safetyStopCauses: List<String>,
            actorId: String?,
            recordedAtEpochMs: Long,
        ): WalkInterruptionRecord? {
            val (lastState, reason) = when (state) {
                WalkSessionState.ACTIVE ->
                    WalkInterruptionLastState.ACTIVE to "process_ended_while_active"
                WalkSessionState.PAUSED -> WalkInterruptionLastState.PAUSED to
                    "process_ended_while_paused:${recoveryStage?.name ?: "unknown"}"
                WalkSessionState.SAFE_STOP -> WalkInterruptionLastState.SAFE_STOP to
                    (
                        listOf("process_ended_after_safety_stop") +
                            safetyStopCauses.filter(String::isNotBlank).take(1)
                        ).joinToString(":")
                WalkSessionState.READY,
                WalkSessionState.ENDED,
                -> return null
            }
            return WalkInterruptionRecord(
                walkSessionId = walkSessionId.takeIf(String::isNotBlank),
                lastState = lastState,
                reasonCode = sanitizeReason(reason),
                actorBinding = actorBindingOf(actorId),
                recordedAtEpochMs = recordedAtEpochMs,
            )
        }

        /**
         * The Boolean marker decides whether an interruption is pending. A pending marker without a
         * readable version-2 record (older app, damaged JSON) becomes an UNKNOWN record.
         */
        fun restore(rawRecord: String?, legacyInterrupted: Boolean): WalkInterruptionRecord? {
            if (!legacyInterrupted) return null
            return rawRecord?.let(::decodeOrNull) ?: LEGACY
        }

        fun actorBindingOf(actorId: String?): String? {
            val actor = actorId?.takeIf(String::isNotBlank) ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("walksafe.walk_interruption.v2:$actor".toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }

        private fun decodeOrNull(raw: String): WalkInterruptionRecord? = runCatching {
            val json = JSONObject(raw)
            if (json.optInt("version", -1) != VERSION) return@runCatching null
            val lastState = WalkInterruptionLastState.valueOf(json.getString("lastState"))
            WalkInterruptionRecord(
                walkSessionId = json.optStringOrNull("walkSessionId"),
                lastState = lastState,
                reasonCode = sanitizeReason(json.getString("reasonCode")),
                actorBinding = json.optStringOrNull("actorBinding")
                    ?.takeIf { it.matches(Regex("^[0-9a-f]{32}$")) },
                recordedAtEpochMs = if (json.isNull("recordedAtEpochMs")) {
                    null
                } else {
                    json.getLong("recordedAtEpochMs").takeIf { it >= 0L }
                },
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)

        private fun sanitizeReason(reason: String): String =
            reason.lowercase().replace(UNSAFE_REASON_CHARS, "_").take(MAX_REASON_LENGTH)
                .ifBlank { "unspecified" }
    }
}
