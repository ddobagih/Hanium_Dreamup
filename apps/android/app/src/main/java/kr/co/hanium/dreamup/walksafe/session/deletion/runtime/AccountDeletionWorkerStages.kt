package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.AccountDeletionInstallationResetController

/** Reuses the process StateMachine's existing serial durable I/O and generation fences. */
internal class AccountDeletionWorkerStages(
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val activityLease: () -> AccountDeletionActivityLease,
    private val resetController: () -> AccountDeletionInstallationResetController,
    private val keepPrivacyFenceClosed: () -> Unit,
) {
    fun allowed(
        attempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        return dispatchAccountDeletionWorkerStage(
            stateMachine = stateMachine(),
            attempt = attempt,
            keepPrivacyFenceClosed = keepPrivacyFenceClosed,
            dispatch = {},
        )
    }

    fun begin(
        journal: AccountDeletionJournal,
    ): AccountDeletionWorkerAttempt? =
        stateMachine().beginWorkerAttempt(
            journal,
            activityLease(),
        ).also { attempt ->
            if (attempt == null) keepPrivacyFenceClosed()
        }

    fun durable(
        attempt: AccountDeletionWorkerAttempt,
        action: (AccountDeletionJournal?) -> Boolean,
    ): Boolean? {
        return when (
            stateMachine().runWorkerMonotonicDurableIoIfCurrent(
                attempt = attempt,
                action = action,
            )
        ) {
            AccountDeletionWorkerStageResult.STALE -> {
                keepPrivacyFenceClosed()
                null
            }
            AccountDeletionWorkerStageResult.SUCCEEDED -> true
            AccountDeletionWorkerStageResult.FAILED -> false
        }
    }

    fun terminal(
        attempt: AccountDeletionWorkerAttempt,
        errorCode: String? = null,
    ): Boolean? {
        return when (
            stateMachine().runWorkerMonotonicTerminalDurableIoIfCurrent(
                attempt = attempt,
                errorCode = errorCode,
                action = { terminalJournal ->
                    resetController().persistTerminalState(
                        journal = terminalJournal,
                        fallbackReason = errorCode,
                    )
                },
            )
        ) {
            AccountDeletionWorkerStageResult.STALE -> {
                keepPrivacyFenceClosed()
                null
            }
            AccountDeletionWorkerStageResult.SUCCEEDED -> true
            AccountDeletionWorkerStageResult.FAILED -> {
                resetController().handleTerminalPersistenceFailure(errorCode)
                null
            }
        }
    }

    fun terminalCleanup(
        attempt: AccountDeletionWorkerAttempt,
        action: () -> Boolean,
    ): Boolean? {
        return when (
            stateMachine().runWorkerTerminalCleanupIoIfCurrent(
                attempt = attempt,
                action = { action() },
            )
        ) {
            AccountDeletionWorkerStageResult.STALE -> {
                keepPrivacyFenceClosed()
                null
            }
            AccountDeletionWorkerStageResult.SUCCEEDED -> true
            AccountDeletionWorkerStageResult.FAILED -> false
        }
    }
}
