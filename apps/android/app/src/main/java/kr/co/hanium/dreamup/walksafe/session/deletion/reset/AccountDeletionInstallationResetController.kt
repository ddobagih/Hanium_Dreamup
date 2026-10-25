package kr.co.hanium.dreamup.walksafe.session.deletion.reset

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetJournalState
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetResult
import kr.co.hanium.dreamup.walksafe.session.*

/** Owns installation reset, its exact coordinator, and verified terminal cleanup readiness. */
internal class AccountDeletionInstallationResetController(
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val activityLease: () -> AccountDeletionActivityLease,
    private val cleanupExecutor: () -> Executor,
    private val postToUi: (() -> Unit) -> Boolean,
    private val runOnUi: (() -> Unit) -> Unit,
    private val storage: AccountDeletionResetStorage,
    private val startupHandoff: InstallationResetStartupHandoff,
    private val actions: InstallationResetActions,
    private val coordinatorFactory: (File) -> AccountDeletionResetCoordinator = storage::createCoordinator,
    private val purgeEvidenceKeyAfterConfirmedDeletion: (AccountDeletionJournal) -> Boolean = { true },
) {
    private lateinit var coordinator: AccountDeletionResetCoordinator
    private var startupJournalState: AccountDeletionResetJournalState = AccountDeletionResetJournalState.Absent
    val terminalCleanup = AccountDeletionTerminalCleanupCoordinator()
    val resetCoordinator: AccountDeletionResetCoordinator get() = coordinator
    val journalStateAtStartup: AccountDeletionResetJournalState get() = startupJournalState
    val terminalCleanupComplete: Boolean get() = stateMachine().snapshotOrNull()?.let(terminalCleanup::isResetEnabled) == true

    fun initialize(noBackupRoot: File) { coordinator = coordinatorFactory(noBackupRoot) }
    fun observeJournalAtStartup() { startupJournalState = coordinator.journalState() }
    fun hasPendingOrCorruptJournal(): Boolean = coordinator.hasPendingOrCorruptJournal()
    fun legacyIntentState(): LegacyResetIntentState = storage.legacyIntentState()
    fun clearPlainResetArtifacts(): Boolean = storage.clearPlainResetArtifacts()
    fun clearIntentFence(): Boolean = storage.clearIntentFence()
    fun isConfirmedJournal(journal: AccountDeletionJournal): Boolean = isConfirmedTerminalAccountDeletion(journal)

    fun startupResetRequiresPreparation(): Boolean = coordinator.hasPendingOrCorruptJournal() ||
        storage.legacyIntentState() != LegacyResetIntentState.ABSENT

    fun reconcileResetAtStartup(speechOutputPurgeCompleted: Boolean?): AccountDeletionResetResult {
        if (speechOutputPurgeCompleted == false) return AccountDeletionResetResult.BLOCKED
        val journalPresent = coordinator.hasPendingOrCorruptJournal()
        val legacyIntent = storage.legacyIntentState()
        if (!journalPresent && legacyIntent == LegacyResetIntentState.ABSENT) return coordinator.reconcilePending()
        if (speechOutputPurgeCompleted != true) return AccountDeletionResetResult.BLOCKED
        if (!journalPresent) return if (legacyIntent == LegacyResetIntentState.EXACT) {
            coordinator.beginAndReconcile()
        } else AccountDeletionResetResult.BLOCKED
        val resumed = coordinator.reconcilePending()
        if (resumed == AccountDeletionResetResult.COMPLETED || resumed == AccountDeletionResetResult.COMPLETED_ELSEWHERE) return resumed
        val confirmedJournal = storage.confirmedJournalOrNull()?.takeIf(::isConfirmedJournal)
        return if (legacyIntent == LegacyResetIntentState.EXACT || confirmedJournal != null) {
            coordinator.beginAndReconcile()
        } else AccountDeletionResetResult.BLOCKED
    }

    fun restoreStartupHandoffAfterResourcesPrepared(): InstallationResetStartupResult {
        if (!startupHandoff.pending) return InstallationResetStartupResult.CONTINUE
        if (!stateMachine().activityLeaseIsCurrent(activityLease())) return InstallationResetStartupResult.FINISH
        val resetAccepted = stateMachine().resetForNewEnrollment(activityLease())
        if (!resetAccepted && stateMachine().phase() != AccountDeletionPhase.IDLE) return InstallationResetStartupResult.FINISH
        startupHandoff.pending = false
        actions.resetReportPrivacyForNewEnrollment()
        return InstallationResetStartupResult.CONTINUE
    }

    fun inspectStartupReset(acknowledged: Boolean?): InstallationResetStartupResult {
        when (reconcileResetAtStartup(acknowledged)) {
            AccountDeletionResetResult.BLOCKED -> if (!blockForResetFailure()) return InstallationResetStartupResult.FINISH
            AccountDeletionResetResult.COMPLETED_ELSEWHERE -> return InstallationResetStartupResult.FINISH
            AccountDeletionResetResult.COMPLETED -> {
                startupHandoff.pending = true
                val authority = storage.authoritySnapshot()
                actions.observeStartupAuthority(authority)
                if (authority.aggregateState == AccountDeletionAggregateAuthorityState.ABSENT) {
                    val resetAccepted = stateMachine().resetForNewEnrollment(activityLease())
                    if (!resetAccepted && (!stateMachine().activityLeaseIsCurrent(activityLease()) ||
                        stateMachine().phase() != AccountDeletionPhase.IDLE)) return InstallationResetStartupResult.FINISH
                    startupHandoff.pending = false
                    actions.resetReportPrivacyForNewEnrollment()
                    val fresh = actions.prepareFreshSpeechCollection()
                    if (fresh == null || !fresh.publish()) return InstallationResetStartupResult.FINISH
                } else {
                    if (!blockForResetFailure()) return InstallationResetStartupResult.FINISH
                }
            }
            AccountDeletionResetResult.NO_PENDING -> Unit
        }
        return InstallationResetStartupResult.CONTINUE
    }

    fun resetAfterConfirmedDeletion() {
        val journal = stateMachine().snapshotOrNull() ?: return
        if (!terminalCleanupComplete) { actions.updateUi(); return }
        if (!isConfirmedJournal(journal)) {
            val reason = "account_deletion_reset_not_confirmed"
            if (!stateMachine().failClosed(activityLease(), reason)) return
            val terminalJournal = stateMachine().snapshotOrNull()
            actions.updateUi()
            scheduleTerminalPersistence(reason, terminalJournal)
            return
        }
        val attempt = stateMachine().beginWorkerAttempt(journal, activityLease()).also {
            if (it == null) actions.keepPrivacyFenceClosed()
        } ?: return
        actions.invalidateGatewaySession()
        val reservation = stateMachine().reserveWorkerResetIfCurrent(attempt) ?: run {
            actions.keepPrivacyFenceClosed()
            return
        }
        actions.updateUi()
        try {
            cleanupExecutor().execute {
                val resetResult = try {
                    val prepared = actions.captureParticipants()
                    val purged = prepared.awaitOriginalAcknowledgements()
                    if (purged && prepared.isCurrent() && stateMachine().snapshotOrNull() === reservation.journal &&
                        stateMachine().phase() == AccountDeletionPhase.RESET_PENDING &&
                        purgeEvidenceKeyAfterConfirmedDeletion(journal)) {
                        coordinator.beginAndReconcile()
                    } else AccountDeletionResetResult.BLOCKED
                } catch (_: Exception) {
                    stateMachine().rollbackWorkerReset(reservation)
                    val reason = stateMachine().failureReasonOrNull() ?: "account_deletion_reset_failed"
                    persistTerminalStateOrHandle(stateMachine().snapshotOrNull(), reason)
                    postResetFailure()
                    return@execute
                }
                val completed = resetResult in setOf(AccountDeletionResetResult.COMPLETED, AccountDeletionResetResult.COMPLETED_ELSEWHERE)
                if (completed && !stateMachine().commitWorkerReset(reservation)) {
                    val reason = stateMachine().failureReasonOrNull() ?: "account_deletion_reset_commit_conflict"
                    persistTerminalStateOrHandle(stateMachine().snapshotOrNull(), reason)
                    postResetFailure()
                    return@execute
                }
                if (!completed) {
                    stateMachine().rollbackWorkerReset(reservation)
                    val reason = stateMachine().failureReasonOrNull() ?: "account_deletion_reset_failed"
                    persistTerminalStateOrHandle(stateMachine().snapshotOrNull(), reason)
                    postResetFailure()
                    return@execute
                }
                val fresh = if (resetResult == AccountDeletionResetResult.COMPLETED) actions.prepareFreshSpeechCollection() else null
                if (resetResult == AccountDeletionResetResult.COMPLETED && fresh == null) { postResetFailure(); return@execute }
                actions.resetReportPrivacyForNewEnrollment()
                val posted = postToUi {
                    if (actions.isDestroyed() || !stateMachine().activityLeaseIsCurrent(activityLease())) {
                        fresh?.closeAndDrain()
                        return@postToUi
                    }
                    when (resetResult) {
                        AccountDeletionResetResult.COMPLETED -> {
                            actions.resetRuntimeForNewEnrollment()
                            requireNotNull(fresh).publish()
                        }
                        AccountDeletionResetResult.COMPLETED_ELSEWHERE -> actions.finishActivity()
                        AccountDeletionResetResult.BLOCKED, AccountDeletionResetResult.NO_PENDING -> Unit
                    }
                }
                if (!posted) fresh?.closeAndDrain()
            }
        } catch (_: RejectedExecutionException) {
            stateMachine().rollbackWorkerReset(reservation)
            actions.keepPrivacyFenceClosed()
            actions.updateUi()
        }
    }

    fun persistTerminalState(journal: AccountDeletionJournal?, fallbackReason: String?): Boolean = storage.persistTerminalState(journal, fallbackReason)
    fun persistTerminalStateOrHandle(journal: AccountDeletionJournal?, fallbackReason: String): Boolean {
        if (storage.persistTerminalState(journal, fallbackReason)) return true
        handleTerminalPersistenceFailure(fallbackReason)
        return false
    }

    fun handleTerminalPersistenceFailure(fallbackReason: String?) {
        val reason = stateMachine().failureReasonOrNull() ?: fallbackReason ?: "account_deletion_terminal_storage"
        storage.failClosedAuthority(reason)
        terminalCleanup.invalidate()
        actions.applyTerminalPersistenceFailure(reason)
        if (stateMachine().activityLeaseIsCurrent(activityLease())) {
            runOnUi {
                if (!stateMachine().activityLeaseIsCurrent(activityLease())) return@runOnUi
                actions.showTerminalPersistenceFailure(reason)
            }
        }
    }

    fun scheduleTerminalPersistence(reason: String, expectedJournal: AccountDeletionJournal?) {
        try {
            cleanupExecutor().execute {
                if (stateMachine().snapshotOrNull() !== expectedJournal || stateMachine().failureReasonOrNull() != reason) return@execute
                persistTerminalStateOrHandle(expectedJournal, reason)
                postToUi {
                    if (!actions.isDestroyed() && stateMachine().activityLeaseIsCurrent(activityLease())) actions.updateUi()
                }
            }
        } catch (_: RejectedExecutionException) { actions.keepPrivacyFenceClosed() }
    }

    fun postResetFailure() {
        postToUi {
            if (actions.isDestroyed() || !stateMachine().activityLeaseIsCurrent(activityLease())) return@postToUi
            actions.keepPrivacyFenceClosed()
            actions.updateUi()
        }
    }

    fun blockForResetFailure(): Boolean {
        if (!actions.activityCallbackAllowed()) return false
        if (!stateMachine().failClosed(activityLease(), "account_deletion_reset_failed")) return false
        storage.blockSensitiveAndAuthorityForResetFailure()
        actions.blockPrivacyForResetFailure()
        storage.markRawSourceFieldLogBlocked()
        return true
    }
}
