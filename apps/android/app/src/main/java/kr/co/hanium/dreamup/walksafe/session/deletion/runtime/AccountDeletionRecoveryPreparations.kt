package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal

internal sealed class AccountDeletionRecoveryMarkerPreparation {
    data class Ready(
        val record: AndroidAccountDeletionFallbackMarker.Record,
    ) : AccountDeletionRecoveryMarkerPreparation()

    data class RetryablePresent(
        val record: AndroidAccountDeletionFallbackMarker.Record,
    ) : AccountDeletionRecoveryMarkerPreparation()

    object Absent : AccountDeletionRecoveryMarkerPreparation()

    object Blocked : AccountDeletionRecoveryMarkerPreparation()
}

internal data class AccountDeletionAuthorityPreparation(
    val confirmed: Boolean,
    val retryable: Boolean,
    val failureReason: String?,
)

internal sealed class AccountDeletionPendingJournalPreparation {
    data class Ready(
        val journal: AccountDeletionJournal,
    ) : AccountDeletionPendingJournalPreparation()

    object Retry : AccountDeletionPendingJournalPreparation()

    object Conflict : AccountDeletionPendingJournalPreparation()
}
