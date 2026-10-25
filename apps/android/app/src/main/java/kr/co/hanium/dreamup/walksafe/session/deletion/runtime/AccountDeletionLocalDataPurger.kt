package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore

/** Aggregates observed synchronous purge results without converting missing participants to success. */
internal class AccountDeletionLocalDataPurger(
    private val sensitivePreferences: () -> AndroidSensitivePreferenceStore,
    private val participants: AccountDeletionLocalPurgeParticipants,
    private val legacyQueue: LegacyPendingReportQueuePurgeController,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : AccountDeletionLocalDataPurge {
    private val sensitivePrefs get() = sensitivePreferences()
    override fun purgeLocalDataOnWorker(): Boolean {
        val speechOutputPurged = runCatching { participants.awaitSpeechPurgeOnWorker() }.getOrDefault(false)
        val routeSnapshotPurged = retry {
            participants.purgeRouteSnapshot()
        }
        val legacyQueuePurged = runCatching { legacyQueue.purgeOnWorker() }.getOrDefault(false)
        // The unsent report queue and RAW collections are device-unsent data as well. Each
        // purge writes its durable fence before deleting files and keys, and its verified
        // result must gate DELETED evidence instead of an unobserved asynchronous task.
        val reportQueuePurged = retry {
            participants.purgePersistentReportQueue()
        }
        val rawCollectionsPurged =
            runCatching { participants.rawCollectionsInitialized() }.getOrDefault(false) &&
                retry {
                    participants.purgeRawCollections()
                }
        val preferencesPurged = runCatching {
            val profileKeys = sensitivePrefs.keys().filter { key ->
                key == PREF_REPORT_COOLDOWNS_KEY ||
                    key == PREF_REPORT_ATTEMPT_STATE_V2 ||
                    key == PREF_REPORTER_USER_ID_KEY ||
                    key.startsWith(PREF_PRIORITY_USER_PROFILE_PREFIX) ||
                    key.startsWith(PREF_PRIORITY_USER_PROFILE_INVALID_PREFIX)
            }
            val editor = sensitivePrefs.edit()
            profileKeys.forEach(editor::remove)
            editor.commit()
        }.getOrDefault(false)
        val fieldSessionsPurged = speechOutputPurged && runCatching {
            if (participants.persistentFieldLoggerReady()) {
                participants.blockPersistentFieldLogger()
                participants.purgePersistentFieldLogger()
            } else {
                // Release Noop/uninitialized logging still participates in actual root/key purge.
                participants.purgeCollectionFieldProvider() == true
            }
        }.getOrDefault(false)
        // S21-50: the debug outdoor test keeps raw GNSS text outside every other purge.
        val outdoorPositionTestPurged = runCatching { participants.purgeOutdoorPositionArtifacts() }.getOrDefault(false)
        // S09-56: the explicit profile reset path; stored step profiles are account data as well.
        // pos-F2: queued saves are dropped and the reset runs on the serial profile I/O thread after a save
        // being written, so no save can recreate a profile after the reset; the purge waits for it.
        val pedestrianProfilesPurged = runCatching { participants.purgeSerialPedestrianProfiles() }.getOrDefault(false)
        val guidanceDiagnosticsPurged = retry { participants.purgeGuidanceDiagnostics() }
        val runtimeDiagnosticsPurged = retry { participants.purgeRuntimeDiagnostics() }
        return routeSnapshotPurged && legacyQueuePurged && reportQueuePurged && rawCollectionsPurged &&
            preferencesPurged && fieldSessionsPurged && speechOutputPurged &&
            outdoorPositionTestPurged && pedestrianProfilesPurged && guidanceDiagnosticsPurged && runtimeDiagnosticsPurged
    }

    /**
     * Idempotent store purges get the same bounded retry as the legacy queue purge, so one
     * transient KeyStore or IO failure does not fix the deletion evidence at FAILED.
     */
    fun retry(purge: () -> Boolean): Boolean {
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) {
                try {
                    sleep(
                        INITIAL_BACKOFF_MS *
                            (1L shl (attempt - 1)),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            if (runCatching(purge).getOrDefault(false)) return true
        }
        return false
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val INITIAL_BACKOFF_MS = 250L
        const val PREF_REPORT_COOLDOWNS_KEY = "report_successful_cooldowns_v1"
        const val PREF_REPORT_ATTEMPT_STATE_V2 = "report_attempt_state_v2"
        const val PREF_REPORTER_USER_ID_KEY = "reporter_user_id"
        const val PREF_PRIORITY_USER_PROFILE_PREFIX = "priority_user_snapshot_v2_"
        const val PREF_PRIORITY_USER_PROFILE_INVALID_PREFIX = "priority_user_snapshot_invalid_v2_"
    }
}
internal interface AccountDeletionLocalPurgeParticipants {
    fun awaitSpeechPurgeOnWorker(): Boolean
    fun purgeRouteSnapshot(): Boolean
    fun purgePersistentReportQueue(): Boolean
    fun rawCollectionsInitialized(): Boolean
    fun purgeRawCollections(): Boolean
    fun persistentFieldLoggerReady(): Boolean
    fun blockPersistentFieldLogger()
    fun purgePersistentFieldLogger(): Boolean
    fun purgeCollectionFieldProvider(): Boolean?
    fun purgeOutdoorPositionArtifacts(): Boolean
    fun purgeSerialPedestrianProfiles(): Boolean
    fun purgeGuidanceDiagnostics(): Boolean
    fun purgeRuntimeDiagnostics(): Boolean
}
