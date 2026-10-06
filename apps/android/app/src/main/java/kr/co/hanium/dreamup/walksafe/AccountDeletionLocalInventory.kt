package kr.co.hanium.dreamup.walksafe

/** Device-unsent stores. Journals, receipts and authority fences are retained until final reset. */
internal enum class AccountDeletionLocalStore {
    ROUTE_SNAPSHOT, LEGACY_REPORTS, REPORT_QUEUE, RAW_COLLECTIONS,
    ACCOUNT_PREFERENCES, FIELD_SESSIONS, OUTDOOR_POSITION_TEST, PEDESTRIAN_PROFILES,
    RUNTIME_MEMORY_AND_POSITION_SCOPE, DIAGNOSTIC_LOGS,
}

/** Run every registered purge even if one fails; never claim DELETED with a missing participant. */
internal class AccountDeletionLocalInventory(
    private val purges: Map<AccountDeletionLocalStore, () -> Boolean>,
) {
    fun purge(): Set<AccountDeletionLocalStore> = AccountDeletionLocalStore.entries.filterTo(linkedSetOf()) {
        val action = purges[it]
        action == null || !runCatching(action).getOrDefault(false)
    }
}
