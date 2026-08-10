package kr.co.hanium.dreamup.walksafe.network

import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase

internal data class AccountDeletionRev0RecoveryBinding(
    val journal: AccountDeletionJournal,
    val markerRecord: AndroidAccountDeletionFallbackMarker.Record,
    val actorId: String,
)

internal data class AccountDeletionRev0LegacyActorBinding(
    val journal: AccountDeletionJournal,
    val markerRecord: AndroidAccountDeletionFallbackMarker.Record,
    val actorId: String,
)

internal data class AccountDeletionRev0LegacyMarkerBinding(
    val journal: AccountDeletionJournal,
    val markerRecord: AndroidAccountDeletionFallbackMarker.Record,
)

private data class ExactAccountDeletionRev0Candidate(
    val journal: AccountDeletionJournal,
    val markerRecord: AndroidAccountDeletionFallbackMarker.Record,
)

internal fun exactAccountDeletionRev0RecoveryBindingOrNull(
    journal: AccountDeletionJournal?,
    markerState: AndroidAccountDeletionFallbackMarker.State,
    configuredGatewayOrigin: String?,
    persistedActorHash: String?,
    authorityConfirmed: Boolean,
    sensitiveFenceConfirmed: Boolean,
    remoteResumeBlocked: Boolean,
    reauthenticationRequestId: String?,
    requireReauthentication: Boolean,
): AccountDeletionRev0RecoveryBinding? {
    val candidate = exactAccountDeletionRev0CandidateOrNull(
        journal = journal,
        markerState = markerState,
        configuredGatewayOrigin = configuredGatewayOrigin,
        persistedActorHash = persistedActorHash,
        authorityConfirmed = authorityConfirmed,
        sensitiveFenceConfirmed = sensitiveFenceConfirmed,
        remoteResumeBlocked = remoteResumeBlocked,
    ) ?: return null
    if (
        requireReauthentication &&
        reauthenticationRequestId != candidate.journal.requestId
    ) return null
    val record = candidate.markerRecord
    val actorId = record.recoveryActorId ?: return null
    if (
        GatewayCredentialPolicy.normalizedActorIdOrNull(actorId) != actorId ||
        sha256Hex(actorId) != record.identity.actorHash
    ) return null
    return AccountDeletionRev0RecoveryBinding(
        journal = candidate.journal,
        markerRecord = record,
        actorId = actorId,
    )
}

internal fun exactLegacyAccountDeletionRev0ActorBindingOrNull(
    journal: AccountDeletionJournal?,
    markerState: AndroidAccountDeletionFallbackMarker.State,
    configuredGatewayOrigin: String?,
    persistedActorHash: String?,
    authorityConfirmed: Boolean,
    sensitiveFenceConfirmed: Boolean,
    remoteResumeBlocked: Boolean,
    liveActorId: String?,
    session: GatewayFieldSession?,
    processSession: GatewayFieldSession?,
    deletionRecoveryOnly: Boolean,
    storageBlocked: Boolean,
    nowEpochMs: Long = System.currentTimeMillis(),
): AccountDeletionRev0LegacyActorBinding? {
    val actorBinding = exactLegacyAccountDeletionRev0ActorCandidateOrNull(
        journal = journal,
        markerState = markerState,
        configuredGatewayOrigin = configuredGatewayOrigin,
        persistedActorHash = persistedActorHash,
        authorityConfirmed = authorityConfirmed,
        sensitiveFenceConfirmed = sensitiveFenceConfirmed,
        remoteResumeBlocked = remoteResumeBlocked,
        liveActorId = liveActorId,
    ) ?: return null
    if (
        storageBlocked ||
        deletionRecoveryOnly ||
        session == null ||
        processSession !== session ||
        session.sessionScope != GatewaySessionScope.GENERAL ||
        session.actorId != actorBinding.actorId ||
        session.gatewayBaseUrl != actorBinding.journal.gatewayOrigin ||
        session.verificationState != GatewaySessionVerificationState.VERIFIED ||
        !session.isUsableFor(actorBinding.actorId, nowEpochMs)
    ) return null
    return actorBinding
}

internal fun exactLegacyAccountDeletionRev0ActorCandidateOrNull(
    journal: AccountDeletionJournal?,
    markerState: AndroidAccountDeletionFallbackMarker.State,
    configuredGatewayOrigin: String?,
    persistedActorHash: String?,
    authorityConfirmed: Boolean,
    sensitiveFenceConfirmed: Boolean,
    remoteResumeBlocked: Boolean,
    liveActorId: String?,
): AccountDeletionRev0LegacyActorBinding? {
    val markerBinding = exactLegacyAccountDeletionRev0MarkerBindingOrNull(
        journal = journal,
        markerState = markerState,
        configuredGatewayOrigin = configuredGatewayOrigin,
        persistedActorHash = persistedActorHash,
        authorityConfirmed = authorityConfirmed,
        sensitiveFenceConfirmed = sensitiveFenceConfirmed,
        remoteResumeBlocked = remoteResumeBlocked,
    ) ?: return null
    val actorId = GatewayCredentialPolicy.normalizedActorIdOrNull(liveActorId)
        ?: return null
    if (sha256Hex(actorId) != markerBinding.markerRecord.identity.actorHash) return null
    return AccountDeletionRev0LegacyActorBinding(
        journal = markerBinding.journal,
        markerRecord = markerBinding.markerRecord,
        actorId = actorId,
    )
}

internal fun exactLegacyAccountDeletionRev0MarkerBindingOrNull(
    journal: AccountDeletionJournal?,
    markerState: AndroidAccountDeletionFallbackMarker.State,
    configuredGatewayOrigin: String?,
    persistedActorHash: String?,
    authorityConfirmed: Boolean,
    sensitiveFenceConfirmed: Boolean,
    remoteResumeBlocked: Boolean,
): AccountDeletionRev0LegacyMarkerBinding? {
    val candidate = exactAccountDeletionRev0CandidateOrNull(
        journal = journal,
        markerState = markerState,
        configuredGatewayOrigin = configuredGatewayOrigin,
        persistedActorHash = persistedActorHash,
        authorityConfirmed = authorityConfirmed,
        sensitiveFenceConfirmed = sensitiveFenceConfirmed,
        remoteResumeBlocked = remoteResumeBlocked,
    ) ?: return null
    if (candidate.markerRecord.recoveryActorId != null) return null
    return AccountDeletionRev0LegacyMarkerBinding(
        journal = candidate.journal,
        markerRecord = candidate.markerRecord,
    )
}

internal fun isExactAccountDeletionRev0RecoverySessionReady(
    binding: AccountDeletionRev0RecoveryBinding,
    session: GatewayFieldSession?,
    processSession: GatewayFieldSession?,
    deletionRecoveryOnly: Boolean,
    storageBlocked: Boolean,
    nowEpochMs: Long = System.currentTimeMillis(),
): Boolean =
    !storageBlocked &&
        deletionRecoveryOnly &&
        session != null &&
        processSession === session &&
        session.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY &&
        session.actorId == binding.actorId &&
        session.gatewayBaseUrl == binding.journal.gatewayOrigin &&
        session.verificationState == GatewaySessionVerificationState.VERIFIED &&
        session.isUsableFor(binding.actorId, nowEpochMs)

internal fun isExactAcceptedRev0RecoverySessionRetirement(
    journal: AccountDeletionJournal,
    markerState: AndroidAccountDeletionFallbackMarker.State,
    persistedActorHash: String?,
    session: GatewayFieldSession?,
    processSession: GatewayFieldSession?,
    deletionRecoveryOnly: Boolean,
): Boolean {
    if (journal.serverRevision <= 0L) return false
    val record =
        (markerState as? AndroidAccountDeletionFallbackMarker.State.Present)
            ?.record
            ?: return false
    val actorId = record.recoveryActorId ?: return false
    return record.phase == AndroidAccountDeletionFallbackMarker.Phase.PREPARED &&
        record.identity.requestId == journal.requestId &&
        record.gatewayOrigin == journal.gatewayOrigin &&
        record.installationId == journal.installationId &&
        record.clientRevision == journal.clientRevision &&
        GatewayCredentialPolicy.normalizedActorIdOrNull(actorId) == actorId &&
        sha256Hex(actorId) == record.identity.actorHash &&
        persistedActorHash == record.identity.actorHash &&
        deletionRecoveryOnly &&
        session != null &&
        processSession === session &&
        session.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY &&
        session.actorId == actorId &&
        session.gatewayBaseUrl == journal.gatewayOrigin
}

private fun exactAccountDeletionRev0CandidateOrNull(
    journal: AccountDeletionJournal?,
    markerState: AndroidAccountDeletionFallbackMarker.State,
    configuredGatewayOrigin: String?,
    persistedActorHash: String?,
    authorityConfirmed: Boolean,
    sensitiveFenceConfirmed: Boolean,
    remoteResumeBlocked: Boolean,
): ExactAccountDeletionRev0Candidate? {
    if (
        journal == null ||
        journal.serverRevision != 0L ||
        journal.phase !in setOf(
            AccountDeletionPhase.REQUEST_PENDING,
            AccountDeletionPhase.RETRY_WAIT,
        ) ||
        configuredGatewayOrigin == null ||
        !authorityConfirmed ||
        !sensitiveFenceConfirmed ||
        remoteResumeBlocked
    ) return null
    val record =
        (markerState as? AndroidAccountDeletionFallbackMarker.State.Present)
            ?.record
            ?: return null
    if (
        record.phase != AndroidAccountDeletionFallbackMarker.Phase.PREPARED ||
        record.identity.requestId != journal.requestId ||
        record.gatewayOrigin != configuredGatewayOrigin ||
        record.gatewayOrigin != journal.gatewayOrigin ||
        record.installationId != journal.installationId ||
        record.clientRevision != journal.clientRevision ||
        persistedActorHash != record.identity.actorHash ||
        !ACTOR_SHA256.matches(record.identity.actorHash)
    ) return null
    return ExactAccountDeletionRev0Candidate(journal, record)
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val ACTOR_SHA256 = Regex("^[0-9a-f]{64}$")
