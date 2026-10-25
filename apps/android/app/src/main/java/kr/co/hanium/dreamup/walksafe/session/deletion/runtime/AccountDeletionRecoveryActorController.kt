package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.session.*

/** Owns deletion-only reauthentication requests; the Gateway retains session/network ownership. */
internal class AccountDeletionRecoveryActorController(
    private val store: AccountDeletionRecoveryStore,
    private val sensitivePreferences: () -> AndroidSensitivePreferenceStore,
    private val storageReady: () -> Boolean,
    private val configuredGatewayOriginOrNull: () -> String?,
    private val currentReporterActorId: () -> String?,
    private val enrollment: AccountDeletionEnrollmentActorReader,
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val activityLease: () -> AccountDeletionActivityLease,
    private val workerStages: AccountDeletionWorkerStages,
    private val effects: AccountDeletionRecoveryActorEffects,
) {
    private val sensitivePrefs: AndroidSensitivePreferenceStore get() = sensitivePreferences()
    @Volatile private var reauthenticationRequestId: String? = null
    @Volatile private var legacyGeneralReauthenticationRequestId: String? = null

    fun rev0RecoveryBindingOrNull(
        requireReauthentication: Boolean = true,
    ): AccountDeletionRev0RecoveryBinding? {
        if (!storageReady()) {
            return null
        }
        return runCatching {
            exactAccountDeletionRev0RecoveryBindingOrNull(
                journal = stateMachine().snapshotOrNull(),
                markerState = store.startupFallbackState,
                configuredGatewayOrigin = configuredGatewayOriginOrNull(),
                persistedActorHash = sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_ACTOR_HASH,
                    null,
                ),
                authorityConfirmed =
                    store.authorityAtStartup.aggregateState ==
                    AccountDeletionAggregateAuthorityState.CONFIRMED &&
                        store.authorityAtStartup.fileState ==
                        AccountDeletionIntentAuthorityState.Confirmed,
                sensitiveFenceConfirmed =
                    sensitivePrefs.getBoolean(
                        PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                        false,
                    ) &&
                        !sensitivePrefs.contains(
                            PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                        ),
                remoteResumeBlocked = store.remoteResumeBlocked,
                reauthenticationRequestId =
                    reauthenticationRequestId,
                requireReauthentication = requireReauthentication,
            )
        }.getOrNull()
    }

    fun recoveryLoginRequired(): Boolean =
        stateMachine().durableConfirmationRecoveryRequired() ||
            rev0RecoveryBindingOrNull() != null ||
            legacyRev0ActorCandidateOrNull() != null

    fun passwordRecoveryBindingOrNull(): AccountDeletionPasswordRecoveryBinding? {
        if (!storageReady()) return null
        rev0RecoveryBindingOrNull()?.let { binding ->
            if (!isRecoveryLoginTarget(binding.actorId, binding.journal.gatewayOrigin)) return null
            return AccountDeletionPasswordRecoveryBinding(
                actorId = binding.actorId,
                markerRecord = binding.markerRecord,
                journal = binding.journal,
            )
        }
        if (
            !stateMachine().durableConfirmationRecoveryRequired() ||
            stateMachine().snapshotOrNull() != null ||
            store.remoteResumeBlocked
        ) return null
        val authority = store.authorityAtStartup
        if (
            authority.aggregateState !in setOf(
                AccountDeletionAggregateAuthorityState.ABSENT,
                AccountDeletionAggregateAuthorityState.CONFIRMED,
            ) ||
            authority.fileState !in setOf(
                AccountDeletionIntentAuthorityState.Absent,
                AccountDeletionIntentAuthorityState.Confirmed,
            ) ||
            authority.preferenceState == AccountDeletionIntentFenceState.UNAVAILABLE
        ) return null
        val record = store.preparedRecoveryCandidateOrNull() ?: return null
        val actorId = recoveryActorIdOrNull() ?: return null
        if (!isRecoveryLoginTarget(actorId, record.gatewayOrigin)) return null
        return AccountDeletionPasswordRecoveryBinding(actorId, record, journal = null)
    }

    fun activateRev0Reauthentication(
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val binding =
            rev0RecoveryBindingOrNull(
                requireReauthentication = false,
            ) ?: return false
        if (
            workerAttempt.gatewayOrigin != binding.journal.gatewayOrigin ||
            workerAttempt.installationId != binding.journal.installationId ||
            workerAttempt.requestId != binding.journal.requestId
        ) return false
        reauthenticationRequestId = binding.journal.requestId
        return rev0RecoveryBindingOrNull() == binding
    }

    fun bindLegacyRev0RecoveryActor(
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        if (!storageReady()) {
            return false
        }
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        val binding = runCatching {
            exactLegacyAccountDeletionRev0ActorBindingOrNull(
                journal = stateMachine().snapshotOrNull(),
                markerState = store.startupFallbackState,
                configuredGatewayOrigin = configuredGatewayOriginOrNull(),
                persistedActorHash = sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_ACTOR_HASH,
                    null,
                ),
                authorityConfirmed =
                    store.authorityAtStartup.aggregateState ==
                    AccountDeletionAggregateAuthorityState.CONFIRMED &&
                        store.authorityAtStartup.fileState ==
                        AccountDeletionIntentAuthorityState.Confirmed,
                sensitiveFenceConfirmed =
                    sensitivePrefs.getBoolean(
                        PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                        false,
                    ) &&
                        !sensitivePrefs.contains(
                            PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                        ),
                remoteResumeBlocked = store.remoteResumeBlocked,
                liveActorId = currentReporterActorId(),
                session = processSnapshot.session,
                processSession = processSnapshot.session,
                deletionRecoveryOnly = processSnapshot.deletionRecoveryOnly,
                storageBlocked = processSnapshot.storageBlocked,
            )
        }.getOrNull() ?: return false
        if (
            workerAttempt.gatewayOrigin != binding.journal.gatewayOrigin ||
            workerAttempt.installationId != binding.journal.installationId ||
            workerAttempt.requestId != binding.journal.requestId
        ) return false
        val boundRecord = binding.markerRecord.copy(
            recoveryActorId = binding.actorId,
        )
        val persisted = workerStages.durable(workerAttempt) {
            store.fallbackMarker.update(boundRecord) &&
                store.fallbackMarker.read() ==
                AndroidAccountDeletionFallbackMarker.State.Present(boundRecord)
        } ?: return false
        if (!persisted) return false
        store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(boundRecord))
        legacyGeneralReauthenticationRequestId = null
        reauthenticationRequestId = binding.journal.requestId
        val recovered = rev0RecoveryBindingOrNull() ?: return false
        val exactRecovered = recovered.journal == binding.journal &&
            recovered.markerRecord == boundRecord &&
            recovered.actorId == binding.actorId
        if (exactRecovered && processSnapshot.session != null) {
            effects.clearGatewaySession(processSnapshot.session, activityLease())
        }
        return exactRecovered
    }

    fun handleLegacyRev0NotFound(
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val markerBinding = legacyRev0MarkerBindingOrNull()
            ?: return false
        if (
            workerAttempt.gatewayOrigin != markerBinding.journal.gatewayOrigin ||
            workerAttempt.installationId != markerBinding.journal.installationId ||
            workerAttempt.requestId != markerBinding.journal.requestId
        ) return false
        val actorBinding = legacyRev0ActorCandidateOrNull(
            requireReauthentication = false,
        )
        if (actorBinding != null) {
            legacyGeneralReauthenticationRequestId =
                actorBinding.journal.requestId
            return legacyRev0ActorCandidateOrNull() == actorBinding
        }
        legacyGeneralReauthenticationRequestId = null
        workerStages.terminal(
            workerAttempt,
            "account_deletion_legacy_reauthentication_identity_unavailable",
        ) ?: return true
        effects.invalidateSpeechStorage()
        effects.failClosedIntegratedConsent()
        effects.postUiRefresh()
        return true
    }

    fun legacyRev0MarkerBindingOrNull() =
        if (!storageReady()) {
            null
        } else {
            runCatching {
                exactLegacyAccountDeletionRev0MarkerBindingOrNull(
                    journal = stateMachine().snapshotOrNull(),
                    markerState = store.startupFallbackState,
                    configuredGatewayOrigin = configuredGatewayOriginOrNull(),
                    persistedActorHash = sensitivePrefs.getString(
                        PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        null,
                    ),
                    authorityConfirmed =
                        store.authorityAtStartup.aggregateState ==
                        AccountDeletionAggregateAuthorityState.CONFIRMED &&
                            store.authorityAtStartup.fileState ==
                            AccountDeletionIntentAuthorityState.Confirmed,
                    sensitiveFenceConfirmed =
                        sensitivePrefs.getBoolean(
                            PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                            false,
                        ) &&
                            !sensitivePrefs.contains(
                                PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                            ),
                    remoteResumeBlocked = store.remoteResumeBlocked,
                )
            }.getOrNull()
        }

    fun legacyRev0ActorCandidateOrNull(
        requireReauthentication: Boolean = true,
    ) = legacyRev0MarkerBindingOrNull()?.let { markerBinding ->
        if (
            requireReauthentication &&
            legacyGeneralReauthenticationRequestId !=
            markerBinding.journal.requestId
        ) return@let null
        exactLegacyAccountDeletionRev0ActorCandidateOrNull(
            journal = markerBinding.journal,
            markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                markerBinding.markerRecord,
            ),
            configuredGatewayOrigin = configuredGatewayOriginOrNull(),
            persistedActorHash = markerBinding.markerRecord.identity.actorHash,
            authorityConfirmed = true,
            sensitiveFenceConfirmed = true,
            remoteResumeBlocked = false,
            liveActorId = currentReporterActorId(),
        )
    }

    fun retainRev0ReauthenticationAfterSessionFailure(
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val binding = rev0RecoveryBindingOrNull() ?: return false
        return workerAttempt.gatewayOrigin == binding.journal.gatewayOrigin &&
            workerAttempt.installationId == binding.journal.installationId &&
            workerAttempt.requestId == binding.journal.requestId
    }

    fun clearRev0RecoverySessionIfCurrent() {
        val binding = rev0RecoveryBindingOrNull() ?: return
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        val session = snapshot.session ?: return
        if (
            snapshot.deletionRecoveryOnly &&
            session.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY &&
            session.actorId == binding.actorId &&
            session.gatewayBaseUrl == binding.journal.gatewayOrigin
        ) {
            GatewaySessionProcessCoordinator.clear(snapshot.generation)
        }
    }

    fun completeRev0ReauthenticationAfterAcceptance(
        journal: AccountDeletionJournal,
    ) {
        if (journal.serverRevision <= 0L) return
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        val exactRecoverySession = isExactAcceptedRev0RecoverySessionRetirement(
            journal = journal,
            markerState = store.startupFallbackState,
            persistedActorHash = sensitivePrefs.getString(
                PREF_ACCOUNT_DELETION_ACTOR_HASH,
                null,
            ),
            session = snapshot.session,
            processSession = snapshot.session,
            deletionRecoveryOnly = snapshot.deletionRecoveryOnly,
        )
        if (
            reauthenticationRequestId != journal.requestId &&
            !exactRecoverySession
        ) return
        reauthenticationRequestId = null
        legacyGeneralReauthenticationRequestId = null
        if (exactRecoverySession) {
            GatewaySessionProcessCoordinator.clear(snapshot.generation)
        }
    }

    fun recoveryActorIdOrNull(): String? {
        legacyRev0ActorCandidateOrNull()?.let { return it.actorId }
        rev0RecoveryBindingOrNull()?.let { return it.actorId }
        if (!stateMachine().durableConfirmationRecoveryRequired()) {
            return null
        }
        val preparedRecord =
            (store.startupFallbackState
                as? AndroidAccountDeletionFallbackMarker.State.Present)
                ?.record
                ?.takeIf {
                    it.phase == AndroidAccountDeletionFallbackMarker.Phase.PREPARED &&
                        it.gatewayOrigin == configuredGatewayOriginOrNull()
                }
        preparedRecord?.recoveryActorId?.let { actorId ->
            if (
                sha256Hex(actorId.toByteArray(Charsets.UTF_8)) ==
                preparedRecord.identity.actorHash
            ) return actorId
        }
        if (preparedRecord != null && preparedRecord.recoveryActorId == null) {
            val snapshot = GatewaySessionProcessCoordinator.snapshot()
            val session = snapshot.session
            val accountGeneration = session?.accountGenerationOrNull
            if (
                snapshot.deletionRecoveryOnly &&
                !snapshot.storageBlocked &&
                session != null &&
                session.verificationState == GatewaySessionVerificationState.VERIFIED &&
                session.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY &&
                session.gatewayBaseUrl == preparedRecord.gatewayOrigin &&
                GatewayCredentialPolicy.normalizedActorIdOrNull(session.actorId) == session.actorId &&
                sha256Hex(session.actorId.toByteArray(Charsets.UTF_8)) == preparedRecord.identity.actorHash &&
                (accountGeneration == null || accountGeneration > 0L) &&
                (preparedRecord.accountGeneration == null || preparedRecord.accountGeneration == accountGeneration)
            ) {
                // A previously verified identity keeps the login target; credential use is checked separately.
                return session.actorId
            }
        }
        if (
            enrollment.onboardingInitialized() &&
            enrollment.onboardingComplete() &&
            enrollment.priorityPolicyInitialized()
        ) {
            val liveActorId = enrollment.onboardingActorId()
            if (
                liveActorId != null &&
                enrollment.reporterActorId() == liveActorId &&
                enrollment.priorityActorId() == liveActorId &&
                enrollment.priorityAccountUnblocked() &&
                (
                    preparedRecord == null ||
                        sha256Hex(liveActorId.toByteArray(Charsets.UTF_8)) ==
                        preparedRecord.identity.actorHash
                    )
            ) return liveActorId
        }
        return null
    }

    fun isRecoveryLoginTarget(
        actorId: String,
        gatewayBaseUrl: String,
    ): Boolean {
        if (legacyRev0ActorCandidateOrNull() != null) return false
        return stateMachine().processingBlocked() &&
            recoveryLoginRequired() &&
            recoveryActorIdOrNull() == actorId &&
            configuredGatewayOriginOrNull() == gatewayBaseUrl
    }

    fun recoveryGatewaySessionOrNull(
        session: GatewayFieldSession? = GatewaySessionProcessCoordinator.snapshot().session,
        speak: Boolean = false,
    ): GatewayFieldSession? {
        val rev0Recovery = rev0RecoveryBindingOrNull()
        val durableConfirmationRecovery =
            stateMachine().durableConfirmationRecoveryRequired()
        val actorId = recoveryActorIdOrNull()
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        val trustedOrigin = configuredGatewayOriginOrNull()
        val ready =
            stateMachine().processingBlocked() &&
                (durableConfirmationRecovery || rev0Recovery != null) &&
            actorId != null &&
                !processSnapshot.storageBlocked &&
                session != null &&
                processSnapshot.session === session &&
                (
                    if (rev0Recovery != null) {
                        isExactAccountDeletionRev0RecoverySessionReady(
                            binding = rev0Recovery,
                            session = session,
                            processSession = processSnapshot.session,
                            deletionRecoveryOnly =
                                processSnapshot.deletionRecoveryOnly,
                            storageBlocked = processSnapshot.storageBlocked,
                        )
                    } else {
                        (
                            processSnapshot.deletionRecoveryOnly &&
                                session.sessionScope ==
                                GatewaySessionScope.ACCOUNT_DELETION_RECOVERY
                            ) ||
                            (
                            !processSnapshot.deletionRecoveryOnly &&
                                session.sessionScope == GatewaySessionScope.GENERAL &&
                                currentReporterActorId() == actorId
                            )
                    }
                    ) &&
                session.verificationState == GatewaySessionVerificationState.VERIFIED &&
                session.isUsableFor(actorId) &&
                session.gatewayBaseUrl == trustedOrigin
        if (ready) return session
        if (speak) {
            effects.status(
                "gateway=session_required reason=account_deletion_recovery",
            )
            effects.speak("현장 게이트웨이에 다시 로그인하세요.")
        }
        return null
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
    private companion object {
        const val PREF_ACCOUNT_DELETION_ACTOR_HASH = "account_deletion_actor_hash_v2"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER = "account_deletion_fail_closed_v1"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON = "account_deletion_fail_closed_reason_v1"
    }
}

/** Each query stays at the original program point; no early combined enrollment snapshot. */
internal interface AccountDeletionEnrollmentActorReader {
    fun onboardingInitialized(): Boolean
    fun onboardingComplete(): Boolean
    fun onboardingActorId(): String?
    fun priorityPolicyInitialized(): Boolean
    fun reporterActorId(): String?
    fun priorityActorId(): String?
    fun priorityAccountUnblocked(): Boolean
}
internal interface AccountDeletionRecoveryActorEffects {
    fun clearGatewaySession(expectedSession: GatewayFieldSession, expectedActivityLease: AccountDeletionActivityLease)
    fun invalidateSpeechStorage()
    fun failClosedIntegratedConsent()
    fun postUiRefresh()
    fun status(value: String)
    fun speak(value: String)
}
