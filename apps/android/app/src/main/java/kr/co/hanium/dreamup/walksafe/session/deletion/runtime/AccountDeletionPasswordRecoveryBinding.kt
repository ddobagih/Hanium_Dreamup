package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal

/** Exact deletion target captured before a password request and compared before its publication. */
internal data class AccountDeletionPasswordRecoveryBinding(
    val actorId: String,
    val markerRecord: AndroidAccountDeletionFallbackMarker.Record,
    val journal: AccountDeletionJournal?,
) {
    val gatewayOrigin: String get() = markerRecord.gatewayOrigin

    override fun toString(): String =
        "AccountDeletionPasswordRecoveryBinding(actorId=redacted, markerRecord=redacted, " +
            "journal=${if (journal == null) "none" else "present"})"
}
