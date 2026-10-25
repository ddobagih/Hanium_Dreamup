package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import org.json.JSONArray
import org.json.JSONObject

/** Owns actor-scoped attempt state and its sensitive preference persistence. */
internal class ActorReportAttemptRepository(
    private val preferences: () -> AndroidSensitivePreferenceStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val sensitivePrefs: AndroidSensitivePreferenceStore get() = preferences()
    private val reportAttemptStateLock = Any()
    private var reportAttemptStateActorHash: String? = null
    private var reportAttemptStateStorageBlocked = false
    private val persistedReportAttemptStates =
        mutableMapOf<String, PersistedReportAttemptState>()
    private val reportAttemptStorageBlockedActors = mutableSetOf<String>()

    private data class PersistedReportAttemptState(
        val consecutiveFailures: Int,
        val retryNotBeforeMs: Long,
        val terminalStatus: Int?,
        val terminalUntilMs: Long,
    )

    fun restoreCooldowns(reportAttemptStore: AndroidReportAttemptStore) {
        if (sensitivePrefs.isBlocked()) {
            reportAttemptStateStorageBlocked = true
            return
        }
        val raw = sensitivePrefs.getString(PREF_REPORT_COOLDOWNS_KEY, null) ?: return
        val cooldowns = runCatching {
            val payload = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(payload.length(), AndroidReportCooldownPolicy.MAX_TRACKED_STATES)) {
                    val item = payload.optJSONObject(index) ?: continue
                    add(AndroidReportSuccessfulCooldown.fromJson(item) ?: continue)
                }
            }
        }.getOrElse {
            sensitivePrefs.failClosed()
            reportAttemptStateStorageBlocked = true
            return
        }
        reportAttemptStore.restoreSuccessfulCooldowns(cooldowns, nowEpochMs())
        persistCooldowns(reportAttemptStore)
    }

    fun persistCooldowns(reportAttemptStore: AndroidReportAttemptStore): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        val payload = JSONArray()
        reportAttemptStore.successfulCooldowns(nowEpochMs()).forEach { cooldown ->
            payload.put(cooldown.toJson())
        }
        return sensitivePrefs.edit()
            .putString(PREF_REPORT_COOLDOWNS_KEY, payload.toString())
            .commit()
    }

    fun persistCooldownForSuccess(
        reportAttemptStore: AndroidReportAttemptStore,
        cooldown: AndroidReportSuccessfulCooldown,
    ): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        val cooldowns = listOf(cooldown) +
            reportAttemptStore.successfulCooldowns(nowEpochMs())
                .filterNot { it.scope == cooldown.scope }
                .take(AndroidReportCooldownPolicy.MAX_TRACKED_STATES - 1)
        val payload = JSONArray()
        cooldowns
            .forEach { item ->
                payload.put(item.toJson())
            }
        return sensitivePrefs.edit()
            .putString(PREF_REPORT_COOLDOWNS_KEY, payload.toString())
            .commit()
    }

    fun ensureActor(actorId: String): Boolean =
        synchronized(reportAttemptStateLock) {
            if (sensitivePrefs.isBlocked()) {
                reportAttemptStateStorageBlocked = true
                return@synchronized false
            }
            val actorHash = sha256Hex(actorId.toByteArray(Charsets.UTF_8))
            if (reportAttemptStateActorHash == actorHash) {
                return@synchronized !reportAttemptStateStorageBlocked
            }
            reportAttemptStateStorageBlocked = false
            reportAttemptStorageBlockedActors.remove(actorHash)
            if (
                reportAttemptStateActorHash != null &&
                reportAttemptStateActorHash != actorHash
            ) {
                persistedReportAttemptStates.clear()
                if (
                    !sensitivePrefs.edit()
                        .remove(PREF_REPORT_ATTEMPT_STATE_V2)
                        .commitPersisted()
                ) {
                    reportAttemptStateStorageBlocked = true
                    return@synchronized false
                }
            }
            reportAttemptStateActorHash = actorHash
            persistedReportAttemptStates.clear()
            val raw =
                sensitivePrefs.getString(PREF_REPORT_ATTEMPT_STATE_V2, null)
                    ?: return@synchronized true
            val restored = runCatching {
                val payload = JSONObject(raw)
                check(payload.getString("schema") == REPORT_ATTEMPT_STATE_SCHEMA_V2)
                check(payload.getString("actor_hash") == actorHash)
                val entries = payload.getJSONArray("entries")
                check(entries.length() <= REPORT_ATTEMPT_STATE_MAX_ENTRIES)
                for (index in 0 until entries.length()) {
                    val item = entries.getJSONObject(index)
                    val key = item.getString("key")
                    check(REPORT_ATTEMPT_STATE_KEY.matches(key))
                    val failures = item.getInt("failures")
                    check(failures in 0..REPORT_ATTEMPT_MAX_FAILURES)
                    persistedReportAttemptStates[key] =
                        PersistedReportAttemptState(
                            consecutiveFailures = failures,
                            retryNotBeforeMs = item.getLong("retry_not_before_ms"),
                            terminalStatus =
                                if (item.isNull("terminal_status")) null
                                else item.getInt("terminal_status"),
                            terminalUntilMs = item.getLong("terminal_until_ms"),
                        )
                }
            }.isSuccess
            if (restored) {
                true
            } else {
                persistedReportAttemptStates.clear()
                sensitivePrefs.failClosed()
                reportAttemptStateStorageBlocked = true
                false
            }
        }

    fun stateKey(
        actorId: String,
        className: String,
        latitude: Double,
        longitude: Double,
        transferPurpose: ReportTransferPurpose,
    ): String =
        sha256Hex(
            "$actorId|$className|$latitude|$longitude|${transferPurpose.name}"
                .toByteArray(Charsets.UTF_8),
        )

    fun automaticBlockStatus(
        stateKey: String,
        nowMs: Long,
    ): String? = synchronized(reportAttemptStateLock) {
        if (
            reportAttemptStateStorageBlocked ||
            reportAttemptStateActorHash?.let(
                reportAttemptStorageBlockedActors::contains,
            ) == true
        ) {
            return@synchronized "reportCandidate=blocked:attempt_state_storage"
        }
        val state = persistedReportAttemptStates[stateKey] ?: return@synchronized null
        if (state.terminalUntilMs > nowMs) {
            return@synchronized(
                "reportCandidate=terminal_backoff status=${state.terminalStatus ?: "protocol"} " +
                    "remainingMs=${state.terminalUntilMs - nowMs}"
            )
        }
        if (state.retryNotBeforeMs > nowMs) {
            return@synchronized(
                "reportCandidate=backoff_persisted remainingMs=" +
                    "${state.retryNotBeforeMs - nowMs}"
            )
        }
        null
    }

    fun persistTransientFailure(
        stateKey: String,
        nowMs: Long,
        statusCode: Int? = null,
        retryAfterMs: Long? = null,
    ): Long = synchronized(reportAttemptStateLock) {
        val previous = persistedReportAttemptStates[stateKey]
        val failures =
            ((previous?.consecutiveFailures ?: 0) + 1)
                .coerceAtMost(REPORT_ATTEMPT_MAX_FAILURES)
        val delayMs =
            if (statusCode == null) {
                reportRetryDelayMs(failures)
            } else {
                reportRetryDelayMs(failures, statusCode, retryAfterMs)
            }
        if (
            previous == null &&
            persistedReportAttemptStates.size >= REPORT_ATTEMPT_STATE_MAX_ENTRIES
        ) {
            reportAttemptStateStorageBlocked = true
            return@synchronized delayMs
        }
        persistedReportAttemptStates[stateKey] =
            PersistedReportAttemptState(
                consecutiveFailures = failures,
                retryNotBeforeMs = nowMs + delayMs,
                terminalStatus = null,
                terminalUntilMs = 0L,
            )
        if (!persistReportAttemptStatesLocked()) {
            reportAttemptStateStorageBlocked = true
        }
        delayMs
    }

    fun persistTerminalState(
        stateKey: String,
        statusCode: Int?,
        nowMs: Long,
    ): Boolean = synchronized(reportAttemptStateLock) {
        val previous = persistedReportAttemptStates[stateKey]
        if (
            previous == null &&
            persistedReportAttemptStates.size >= REPORT_ATTEMPT_STATE_MAX_ENTRIES
        ) {
            reportAttemptStateStorageBlocked = true
            return@synchronized false
        }
        persistedReportAttemptStates[stateKey] =
            PersistedReportAttemptState(
                consecutiveFailures = previous?.consecutiveFailures ?: 0,
                retryNotBeforeMs = 0L,
                terminalStatus = statusCode,
                terminalUntilMs = nowMs + REPORT_TERMINAL_STATE_TTL_MS,
            )
        persistReportAttemptStatesLocked().also {
            if (!it) reportAttemptStateStorageBlocked = true
        }
    }

    fun blockStorageForActor(actorId: String) {
        synchronized(reportAttemptStateLock) {
            reportAttemptStorageBlockedActors +=
                sha256Hex(actorId.toByteArray(Charsets.UTF_8))
        }
    }

    fun clearPersistedState(stateKey: String): Boolean =
        synchronized(reportAttemptStateLock) {
            persistedReportAttemptStates.remove(stateKey)
            persistReportAttemptStatesLocked(requireFreshPersistence = true).also {
                if (!it) {
                    reportAttemptStateStorageBlocked = true
                } else {
                    reportAttemptStateActorHash?.let(
                        reportAttemptStorageBlockedActors::remove,
                    )
                }
            }
        }

    private fun persistReportAttemptStatesLocked(requireFreshPersistence: Boolean = false): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        val actorHash = reportAttemptStateActorHash ?: return false
        val entries = JSONArray()
        persistedReportAttemptStates.forEach { (key, state) ->
            entries.put(
                JSONObject()
                    .put("key", key)
                    .put("failures", state.consecutiveFailures)
                    .put("retry_not_before_ms", state.retryNotBeforeMs)
                    .put(
                        "terminal_status",
                        state.terminalStatus ?: JSONObject.NULL,
                    )
                    .put("terminal_until_ms", state.terminalUntilMs),
            )
        }
        val editor = sensitivePrefs.edit()
            .putString(
                PREF_REPORT_ATTEMPT_STATE_V2,
                JSONObject()
                    .put("schema", REPORT_ATTEMPT_STATE_SCHEMA_V2)
                    .put("actor_hash", actorHash)
                    .put("entries", entries)
                    .toString(),
            )
        return if (requireFreshPersistence) editor.commitPersisted() else editor.commit()
    }

    /** Called at the existing account-deletion fence; persisted deletion stays with its owner. */
    fun clearForAccountDeletion() {
        synchronized(reportAttemptStateLock) {
            reportAttemptStateActorHash = null
            reportAttemptStateStorageBlocked = false
            persistedReportAttemptStates.clear()
            reportAttemptStorageBlockedActors.clear()
        }
    }

    companion object {
        const val PREF_REPORT_COOLDOWNS_KEY = "report_successful_cooldowns_v1"
        const val PREF_REPORT_ATTEMPT_STATE_V2 = "report_attempt_state_v2"
        private const val REPORT_ATTEMPT_STATE_SCHEMA_V2 = "walksafe.report-attempt-state.v2"
        private val REPORT_ATTEMPT_STATE_KEY = Regex("^[0-9a-f]{64}$")
        private const val REPORT_ATTEMPT_STATE_MAX_ENTRIES = 64
        private const val REPORT_ATTEMPT_MAX_FAILURES = 32
        private const val REPORT_TERMINAL_STATE_TTL_MS = 24L * 60L * 60L * 1_000L
    }
}
