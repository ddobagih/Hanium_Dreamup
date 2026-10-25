package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

internal enum class AccountDeletionCallPurpose {
    INITIAL,
    CAPABILITY_REPLAY,
    RECOVERY_ACCEPT_OR_REPLAY,
    STATUS,
    EVIDENCE,
}
