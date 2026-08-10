package kr.co.hanium.dreamup.walksafe.session

import java.net.URI
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.Executors

const val ACCOUNT_DELETION_REQUEST_SCHEMA_VERSION =
    "walksafe.account-deletion-request.v2"
const val ACCOUNT_DELETION_STATUS_SCHEMA_VERSION =
    "walksafe.account-deletion-status.v2"
const val DEVICE_DELETION_EVIDENCE_SCHEMA_VERSION =
    "walksafe.device-deletion-evidence.v2"
const val ACCOUNT_DELETION_ACCESS_SECRET_HEADER =
    "x-walksafe-deletion-access-secret"
private const val ACCOUNT_DELETION_IDENTITY_MISMATCH_REASON =
    "account_deletion_identity_mismatch"
private const val ACCOUNT_DELETION_REVISION_CONFLICT_REASON =
    "account_deletion_revision_conflict"
private const val ACCOUNT_DELETION_STATUS_CONFLICT_REASON =
    "account_deletion_status_conflict"
private const val ACCOUNT_DELETION_DEVICE_EVIDENCE_MISSING_REASON =
    "account_deletion_device_evidence_missing"
private const val ACCOUNT_DELETION_RESET_FAILURE_REASON =
    "account_deletion_reset_failed"

enum class AccountDeletionPhase {
    IDLE,
    CONFIRM_REQUIRED,
    REQUEST_PENDING,
    IN_PROGRESS,
    RETRY_WAIT,
    RESTRICTED,
    PARTIAL_FAILURE,
    COMPLETED,
    FAIL_CLOSED,
    RESET_PENDING,
    REENROLLMENT_REQUIRED,
}

/** Exact canonical status-array order. */
enum class DeletionInventoryItem(
    val wireValue: String,
    val labelKo: String,
    val maximumSlaMs: Long,
) {
    DEVICE_UNSENT(
        "device_untransmitted_data",
        "휴대전화 미전송 자료",
        24L * 60L * 60L * 1_000L,
    ),
    SERVER_ORIGINAL(
        "server_originals",
        "서버 원본",
        7L * 24L * 60L * 60L * 1_000L,
    ),
    SERVER_QUARANTINE(
        "server_quarantine",
        "서버 검역본",
        7L * 24L * 60L * 60L * 1_000L,
    ),
    SERVER_COPY(
        "server_copies",
        "서버 복사본",
        7L * 24L * 60L * 60L * 1_000L,
    ),
    REPORT_DATA(
        "report_records",
        "신고자료",
        7L * 24L * 60L * 60L * 1_000L,
    ),
    TRAINING_DATASET(
        "training_datasets",
        "학습 데이터셋",
        30L * 24L * 60L * 60L * 1_000L,
    ),
    TRAINING_LABEL(
        "training_labels",
        "학습 라벨",
        30L * 24L * 60L * 60L * 1_000L,
    ),
    PROCESSED_DERIVATIVE(
        "derived_artifacts",
        "가공본",
        30L * 24L * 60L * 60L * 1_000L,
    ),
    BACKUP(
        "backups",
        "백업",
        35L * 24L * 60L * 60L * 1_000L,
    );

    companion object {
        fun fromWireValue(value: String): DeletionInventoryItem? =
            entries.singleOrNull { it.wireValue == value }
    }
}

enum class DeletionItemState(val wireValue: String) {
    PENDING("PENDING"),
    IN_PROGRESS("IN_PROGRESS"),
    EXTERNAL_PENDING("EXTERNAL_PENDING"),
    RETRY_WAIT("RETRY_WAIT"),
    LEGAL_HOLD("LEGAL_HOLD"),
    FAILED("FAILED"),
    COMPLETED("COMPLETED"),
    NOT_APPLICABLE("NOT_APPLICABLE");

    val isComplete: Boolean
        get() = this == COMPLETED || this == NOT_APPLICABLE

    companion object {
        // Source compatibility for pre-v2 UI/tests; neither is a distinct wire state.
        val DELETED: DeletionItemState = COMPLETED
        val NOT_FOUND: DeletionItemState = NOT_APPLICABLE

        fun fromWireValue(value: String): DeletionItemState? =
            entries.singleOrNull { it.wireValue == value }
    }
}

enum class AccountDeletionOverallStatus(val wireValue: String) {
    PROCESSING("PROCESSING"),
    PARTIAL("PARTIAL"),
    RETRY_WAIT("RETRY_WAIT"),
    RESTRICTED("RESTRICTED"),
    FAILED("FAILED"),
    COMPLETED("COMPLETED");

    companion object {
        fun fromWireValue(value: String): AccountDeletionOverallStatus? =
            entries.singleOrNull { it.wireValue == value }
    }
}

data class DeletionItemStatus(
    val item: DeletionInventoryItem,
    val state: DeletionItemState,
    val dueAt: String,
    val itemRevision: Long = 1L,
    val updatedAt: String = dueAt,
    val evidenceSha256: String? = null,
    val dispositionBasis: String? = null,
    val nextRetryAt: String? = null,
    val reasonCode: String? = null,
    val legalHoldReviewAt: String? = null,
    val contactUrl: String? = null,
    val terminalAt: String? = null,
) {
    init {
        require(itemRevision > 0L)
        require(validInstant(dueAt) && validInstant(updatedAt))
        require(evidenceSha256 == null || SHA256.matches(evidenceSha256))
        if (state == DeletionItemState.NOT_APPLICABLE) {
            require(!dispositionBasis.isNullOrBlank())
        } else {
            require(dispositionBasis == null)
        }
        if (state == DeletionItemState.RETRY_WAIT) {
            require(nextRetryAt?.let(::validInstant) == true)
        } else {
            require(nextRetryAt == null)
        }
        if (state == DeletionItemState.LEGAL_HOLD) {
            require(!reasonCode.isNullOrBlank())
            require(legalHoldReviewAt?.let(::validInstant) == true)
            require(!contactUrl.isNullOrBlank())
        } else {
            require(reasonCode == null)
            require(legalHoldReviewAt == null)
            require(contactUrl == null)
        }
        if (state.isComplete) {
            require(evidenceSha256 != null)
            require(terminalAt?.let(::validInstant) == true)
        } else {
            require(terminalAt == null)
        }
    }
}

data class AccountDeletionStatus(
    val schemaVersion: String,
    /** Local binding only; this value is not present in the wire status object. */
    val installationId: String,
    val requestId: String,
    val revision: Long,
    /** Canonical backend accepted_at. Kept under the old source name for UI compatibility. */
    val requestedAt: String,
    val updatedAt: String,
    val items: Map<DeletionInventoryItem, DeletionItemStatus>,
    /** Canonical completion_receipt_sha256. */
    val receiptSha256: String?,
    val clientRevision: Long = 1L,
    val accountGeneration: Long = 1L,
    val tombstoneId: String = "local-tombstone-placeholder",
    val requestReceiptSha256: String = "0".repeat(64),
    val overallStatus: AccountDeletionOverallStatus = derivedOverallStatus(items),
) {
    init {
        require(schemaVersion == ACCOUNT_DELETION_STATUS_SCHEMA_VERSION)
        require(OPAQUE_ID.matches(installationId))
        require(REQUEST_ID.matches(requestId))
        require(clientRevision > 0L && revision > 0L && accountGeneration > 0L)
        require(validInstant(requestedAt) && validInstant(updatedAt))
        require(OPAQUE_ID.matches(tombstoneId))
        require(SHA256.matches(requestReceiptSha256))
        require(items.keys == DeletionInventoryItem.entries.toSet())
        items.forEach { (item, status) ->
            require(status.item == item)
            require(
                !Instant.parse(status.dueAt)
                    .isAfter(Instant.parse(requestedAt).plusMillis(item.maximumSlaMs)),
            )
        }
        require(overallStatus == derivedOverallStatus(items))
        require(receiptSha256 == null || SHA256.matches(receiptSha256))
        require((overallStatus == AccountDeletionOverallStatus.COMPLETED) ==
            (receiptSha256 != null))
    }

    fun derivedPhase(): AccountDeletionPhase = overallStatus.toPhase()
}

data class AccountDeletionJournal(
    val gatewayOrigin: String,
    val installationId: String,
    val requestId: String,
    /** Client-created marker timestamp, not sent to the server. */
    val requestedAt: String,
    val phase: AccountDeletionPhase,
    val serverRevision: Long,
    val items: Map<DeletionInventoryItem, DeletionItemStatus>,
    val receiptSha256: String? = null,
    val lastErrorCode: String? = null,
    val clientRevision: Long = 1L,
    val acceptedAt: String? = null,
    val accountGeneration: Long? = null,
    val tombstoneId: String? = null,
    val requestReceiptSha256: String? = null,
    val deviceEvidenceId: String? = null,
    val deviceEvidenceSha256: String? = null,
    val deviceEvidenceExpectedStatusRevision: Long? = null,
    val deviceEvidenceResult: String? = null,
    val deviceEvidenceCompletedAt: String? = null,
    val deviceEvidenceAcknowledged: Boolean = false,
) {
    init {
        require(phase != AccountDeletionPhase.IDLE)
        require(clientRevision > 0L && serverRevision >= 0L)
        require(items.keys == DeletionInventoryItem.entries.toSet())
        require(isTrustedRootOrigin(gatewayOrigin))
        require(OPAQUE_ID.matches(installationId) && REQUEST_ID.matches(requestId))
        require(validInstant(requestedAt))
        items.forEach { (item, status) -> require(status.item == item) }
        if (serverRevision == 0L) {
            require(acceptedAt == null && accountGeneration == null)
            require(tombstoneId == null && requestReceiptSha256 == null)
        } else {
            require(acceptedAt?.let(::validInstant) == true)
            require(accountGeneration != null && accountGeneration > 0L)
            require(tombstoneId?.let(OPAQUE_ID::matches) == true)
            require(requestReceiptSha256?.let(SHA256::matches) == true)
        }
        require(deviceEvidenceId == null || OPAQUE_ID.matches(deviceEvidenceId))
        require(deviceEvidenceSha256 == null || SHA256.matches(deviceEvidenceSha256))
        require((deviceEvidenceId == null) == (deviceEvidenceSha256 == null))
        require((deviceEvidenceId == null) == (deviceEvidenceResult == null))
        require((deviceEvidenceId == null) == (deviceEvidenceCompletedAt == null))
        require((deviceEvidenceId == null) ==
            (deviceEvidenceExpectedStatusRevision == null))
        if (deviceEvidenceId != null) {
            require(deviceEvidenceExpectedStatusRevision!! > 0L)
            require(deviceEvidenceResult in setOf("DELETED", "NOT_FOUND", "FAILED"))
            require(deviceEvidenceCompletedAt?.let(::validDeviceEvidenceInstant) == true)
        }
        if (deviceEvidenceAcknowledged) require(deviceEvidenceSha256 != null)
        if (phase == AccountDeletionPhase.COMPLETED) {
            require(serverRevision > 0L)
            require(items.values.all { it.state.isComplete })
            require(receiptSha256?.let(SHA256::matches) == true)
            require(deviceEvidenceAcknowledged)
        }
    }

    companion object {
        fun pending(
            gatewayOrigin: String,
            installationId: String,
            requestId: String,
            requestedAt: String,
            clientRevision: Long = 1L,
        ): AccountDeletionJournal {
            val requested = Instant.parse(requestedAt)
            val statuses = DeletionInventoryItem.entries.associateWith { item ->
                DeletionItemStatus(
                    item = item,
                    state = DeletionItemState.PENDING,
                    dueAt = requested.plusMillis(item.maximumSlaMs).toString(),
                    updatedAt = requestedAt,
                )
            }
            return AccountDeletionJournal(
                gatewayOrigin = gatewayOrigin,
                installationId = installationId,
                requestId = requestId,
                requestedAt = requestedAt,
                phase = AccountDeletionPhase.REQUEST_PENDING,
                serverRevision = 0L,
                items = Collections.unmodifiableMap(statuses),
                clientRevision = clientRevision,
            )
        }
    }
}

enum class AccountDeletionApplyResult {
    APPLIED,
    DUPLICATE,
    STALE_IGNORED,
    CONFLICT_FAIL_CLOSED,
    IDENTITY_MISMATCH_FAIL_CLOSED,
}

internal class AccountDeletionWorkerAttempt internal constructor(
    internal val generation: Long,
    internal val activityLease: AccountDeletionActivityLease?,
    internal val gatewayOrigin: String?,
    internal val installationId: String?,
    internal val requestId: String?,
)

internal enum class AccountDeletionWorkerStageResult {
    STALE,
    SUCCEEDED,
    FAILED,
}

internal enum class AccountDeletionStartupRestoreResult {
    RESTORED,
    RETAINED_MONOTONIC_PROCESS_SUCCESSOR,
    STALE_ACTIVITY,
    CONFLICT,
}

internal class AccountDeletionActivityLease internal constructor(
    internal val generation: Long,
)

internal class AccountDeletionNetworkCallLease internal constructor(
    internal val generation: Long,
    internal val workerAttempt: AccountDeletionWorkerAttempt,
)

internal class AccountDeletionResetReservation internal constructor(
    internal val generation: Long,
    internal val journal: AccountDeletionJournal,
)

internal class AccountDeletionLegacyFailClosedUpgradeAttempt internal constructor(
    internal val generation: Long,
    internal val activityLease: AccountDeletionActivityLease,
    internal val journal: AccountDeletionJournal,
)

/** Process-lifetime owner of the deletion state; it never retains an Activity or callback. */
internal class AccountDeletionProcessCoordinator internal constructor(
    val stateMachine: AccountDeletionStateMachine = AccountDeletionStateMachine(),
) {
    private val processExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-account-deletion").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    val executor: Executor = Executor(processExecutor::execute)

    fun attach(): AccountDeletionActivityLease = stateMachine.attachActivity()

    fun detach(lease: AccountDeletionActivityLease): Boolean =
        stateMachine.detachActivity(lease)

    companion object {
        val shared = AccountDeletionProcessCoordinator()
    }
}

class AccountDeletionStateMachine {
    private val lock = Any()
    private val workerStageSerialLock = Any()
    @Volatile
    private var confirmationRequired = false
    @Volatile
    private var durableConfirmationRecovery = false
    @Volatile
    private var preparationRecoveryPending = false
    @Volatile
    private var current: AccountDeletionJournal? = null
    @Volatile
    private var failClosed = false
    @Volatile
    private var failClosedReason: String? = null
    private var workerGeneration = 0L
    private var activityLeaseGeneration = 0L
    @Volatile
    private var activeActivityLease: AccountDeletionActivityLease? = null
    private var lifecycleManaged = false
    private var networkCallGeneration = 0L
    private var activeNetworkCall: ActiveNetworkCall? = null
    @Volatile
    private var activeWorkerPreparationReservation: WorkerPreparationReservation? = null
    @Volatile
    private var activeWorkerTerminalPreparationReservation:
        WorkerTerminalPreparationReservation? = null
    private var resetReservationGeneration = 0L
    @Volatile
    private var activeResetReservation: AccountDeletionResetReservation? = null

    private class ActiveNetworkCall(
        val lease: AccountDeletionNetworkCallLease,
        val cancel: () -> Unit,
        var begun: Boolean = false,
    )

    private class WorkerPreparationReservation(
        val attempt: AccountDeletionWorkerAttempt,
        val workerGeneration: Long,
        val journal: AccountDeletionJournal?,
    )

    private class WorkerTerminalPreparationReservation(
        val attempt: AccountDeletionWorkerAttempt,
        val workerGeneration: Long,
        val sourceJournal: AccountDeletionJournal?,
        val terminalJournal: AccountDeletionJournal?,
        val ownedTerminalAtReservation: Boolean,
    )

    private class PreparedStageCompletion(
        val result: AccountDeletionWorkerStageResult,
        val networkCancellation: (() -> Unit)? = null,
        val discardPreparedState: Boolean,
    )

    internal fun attachActivity(): AccountDeletionActivityLease {
        val transition = synchronized(lock) {
            lifecycleManaged = true
            activityLeaseGeneration = nextGeneration(activityLeaseGeneration)
            workerGeneration = nextGeneration(workerGeneration)
            val lease = AccountDeletionActivityLease(activityLeaseGeneration)
            activeActivityLease = lease
            lease to invalidateNetworkCallLocked()
        }
        cancelNetworkCall(transition.second)
        return transition.first
    }

    internal fun detachActivity(lease: AccountDeletionActivityLease): Boolean {
        val transition = synchronized(lock) {
            if (activeActivityLease !== lease) return false
            activeActivityLease = null
            workerGeneration = nextGeneration(workerGeneration)
            true to invalidateNetworkCallLocked()
        }
        cancelNetworkCall(transition.second)
        return transition.first
    }

    internal fun activityLeaseIsCurrent(lease: AccountDeletionActivityLease): Boolean =
        activeActivityLease === lease

    /**
     * Recovers an independently durable confirmation that has no matching journal yet.
     * The confirmation UI is reused, but privacy remains fenced until a pending journal exists.
     */
    internal fun enterDurableConfirmationRecovery(
        lease: AccountDeletionActivityLease,
    ): Boolean {
        val transition = synchronized(lock) {
            if (
                !activityLeaseAllowedLocked(lease) ||
                activeResetReservation != null ||
                failClosed ||
                current != null
            ) return false
            val changed =
                !durableConfirmationRecovery ||
                    !preparationRecoveryPending ||
                    !confirmationRequired
            durableConfirmationRecovery = true
            preparationRecoveryPending = true
            confirmationRequired = true
            if (changed) workerGeneration = nextGeneration(workerGeneration)
            true to invalidateNetworkCallLocked()
        }
        cancelNetworkCall(transition.second)
        return transition.first
    }

    internal fun durableConfirmationRecoveryRequired(): Boolean =
        durableConfirmationRecovery

    internal fun preparationRecoveryRequired(): Boolean =
        preparationRecoveryPending

    fun requestConfirmation(): Boolean = requestConfirmationForLease(null)

    internal fun requestConfirmation(lease: AccountDeletionActivityLease): Boolean =
        requestConfirmationForLease(lease)

    private fun requestConfirmationForLease(
        lease: AccountDeletionActivityLease?,
    ): Boolean = synchronized(lock) {
        if (
            !activityLeaseAllowedLocked(lease) ||
            activeWorkerPreparationReservation != null ||
            activeWorkerTerminalPreparationReservation != null ||
            durableConfirmationRecovery ||
            preparationRecoveryPending ||
            current != null ||
            failClosed
        ) {
            return@synchronized false
        }
        confirmationRequired = true
        workerGeneration = nextGeneration(workerGeneration)
        true
    }

    fun cancelConfirmation(): Boolean = cancelConfirmationForLease(null)

    internal fun cancelConfirmation(lease: AccountDeletionActivityLease): Boolean =
        cancelConfirmationForLease(lease)

    private fun cancelConfirmationForLease(
        lease: AccountDeletionActivityLease?,
    ): Boolean = synchronized(lock) {
        if (
            !activityLeaseAllowedLocked(lease) ||
            activeWorkerPreparationReservation != null ||
            activeWorkerTerminalPreparationReservation != null ||
            durableConfirmationRecovery ||
            preparationRecoveryPending
        ) return@synchronized false
        val changed = confirmationRequired
        confirmationRequired = false
        if (changed) workerGeneration = nextGeneration(workerGeneration)
        changed
    }

    fun begin(journal: AccountDeletionJournal): Boolean = synchronized(lock) {
        beginLocked(journal, lease = null, attempt = null)
    }

    internal fun begin(
        journal: AccountDeletionJournal,
        attempt: AccountDeletionWorkerAttempt,
    ): Boolean = synchronized(lock) {
        beginLocked(journal, lease = attempt.activityLease, attempt = attempt)
    }

    private fun beginLocked(
        journal: AccountDeletionJournal,
        lease: AccountDeletionActivityLease?,
        attempt: AccountDeletionWorkerAttempt?,
    ): Boolean {
        if (
            !activityLeaseAllowedLocked(lease) ||
            failClosed ||
            !confirmationRequired ||
            current != null ||
            activeWorkerPreparationReservation != null ||
            activeWorkerTerminalPreparationReservation != null ||
            journal.phase != AccountDeletionPhase.REQUEST_PENDING ||
            (attempt != null &&
                (attempt.requestId != null || !workerAttemptAllowedLocked(attempt)))
        ) return false
        current = journal
        confirmationRequired = false
        durableConfirmationRecovery = false
        preparationRecoveryPending = false
        failClosedReason = null
        workerGeneration = nextGeneration(workerGeneration)
        return true
    }

    fun restore(journal: AccountDeletionJournal): Boolean = restoreForLease(journal, null)

    internal fun restore(
        journal: AccountDeletionJournal,
        lease: AccountDeletionActivityLease,
    ): Boolean = restoreForLease(journal, lease)

    /**
     * Startup may race a response that advanced the process journal before its preference write.
     * Retain that process-owned monotonic successor, but never confuse it with a stale Activity or
     * an unrelated/conflicting journal.
     */
    internal fun restoreAtStartup(
        journal: AccountDeletionJournal,
        lease: AccountDeletionActivityLease,
    ): AccountDeletionStartupRestoreResult = synchronized(lock) {
        if (!activityLeaseAllowedLocked(lease)) {
            AccountDeletionStartupRestoreResult.STALE_ACTIVITY
        } else if (current == null && !failClosed) {
            confirmationRequired = false
            durableConfirmationRecovery = false
            preparationRecoveryPending = false
            current = journal
            failClosed = journal.phase == AccountDeletionPhase.FAIL_CLOSED
            failClosedReason = journal.lastErrorCode.takeIf { failClosed }
            workerGeneration = nextGeneration(workerGeneration)
            AccountDeletionStartupRestoreResult.RESTORED
        } else if (
            current == journal ||
            current?.isMonotonicProcessSuccessorOf(
                persisted = journal,
                processFailClosed = failClosed,
            ) == true
        ) {
            AccountDeletionStartupRestoreResult.RETAINED_MONOTONIC_PROCESS_SUCCESSOR
        } else {
            AccountDeletionStartupRestoreResult.CONFLICT
        }
    }

    private fun restoreForLease(
        journal: AccountDeletionJournal,
        lease: AccountDeletionActivityLease?,
    ): Boolean = synchronized(lock) {
        if (!activityLeaseAllowedLocked(lease)) return@synchronized false
        if (current != null || failClosed) return@synchronized current == journal
        confirmationRequired = false
        durableConfirmationRecovery = false
        preparationRecoveryPending = false
        current = journal
        failClosed = journal.phase == AccountDeletionPhase.FAIL_CLOSED
        failClosedReason = journal.lastErrorCode.takeIf { failClosed }
        workerGeneration = nextGeneration(workerGeneration)
        true
    }

    internal fun beginPreparationWorkerAttempt(): AccountDeletionWorkerAttempt? =
        beginPreparationWorkerAttemptForLease(null)

    internal fun beginPreparationWorkerAttempt(
        lease: AccountDeletionActivityLease,
    ): AccountDeletionWorkerAttempt? = beginPreparationWorkerAttemptForLease(lease)

    private fun beginPreparationWorkerAttemptForLease(
        lease: AccountDeletionActivityLease?,
    ): AccountDeletionWorkerAttempt? = synchronized(lock) {
        if (
            !activityLeaseAllowedLocked(lease) ||
            failClosed ||
            !confirmationRequired ||
            current != null ||
            activeWorkerPreparationReservation != null ||
            activeWorkerTerminalPreparationReservation != null
        ) return@synchronized null
        preparationRecoveryPending = true
        AccountDeletionWorkerAttempt(
            generation = workerGeneration,
            activityLease = lease,
            gatewayOrigin = null,
            installationId = null,
            requestId = null,
        )
    }

    /** Retires a failed pre-journal attempt while preserving the durable recovery fence. */
    internal fun retainDurableConfirmationRecovery(
        attempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val transition = synchronized(lock) {
            if (
                attempt.requestId != null ||
                activeWorkerPreparationReservation != null ||
                activeWorkerTerminalPreparationReservation != null ||
                !workerAttemptAllowedLocked(attempt)
            ) return false
            durableConfirmationRecovery = true
            preparationRecoveryPending = true
            confirmationRequired = true
            workerGeneration = nextGeneration(workerGeneration)
            true to invalidateNetworkCallLocked()
        }
        cancelNetworkCall(transition.second)
        return transition.first
    }

    /**
     * Clears a pre-journal fence only after the caller has independently verified that no durable
     * confirmation authority exists. A durable recovery flag can never be cleared through here.
     */
    internal fun abortPreparationRecovery(
        lease: AccountDeletionActivityLease,
    ): Boolean {
        val transition = synchronized(lock) {
            if (
                !activityLeaseAllowedLocked(lease) ||
                !preparationRecoveryPending ||
                durableConfirmationRecovery ||
                activeWorkerPreparationReservation != null ||
                activeWorkerTerminalPreparationReservation != null ||
                activeResetReservation != null ||
                current != null ||
                failClosed
            ) return false
            preparationRecoveryPending = false
            confirmationRequired = false
            workerGeneration = nextGeneration(workerGeneration)
            true to invalidateNetworkCallLocked()
        }
        cancelNetworkCall(transition.second)
        return transition.first
    }

    internal fun beginWorkerAttempt(
        journal: AccountDeletionJournal,
    ): AccountDeletionWorkerAttempt? = beginWorkerAttemptForLease(journal, null)

    internal fun beginWorkerAttempt(
        journal: AccountDeletionJournal,
        lease: AccountDeletionActivityLease,
    ): AccountDeletionWorkerAttempt? = beginWorkerAttemptForLease(journal, lease)

    private fun beginWorkerAttemptForLease(
        journal: AccountDeletionJournal,
        lease: AccountDeletionActivityLease?,
    ): AccountDeletionWorkerAttempt? = synchronized(lock) {
        if (
            !activityLeaseAllowedLocked(lease) ||
            activeResetReservation != null
        ) return@synchronized null
        val currentJournal = current ?: return@synchronized null
        if (
            !accountDeletionNetworkEntryAllowed(currentJournal) ||
            !journal.sameWorkerIdentity(currentJournal)
        ) return@synchronized null
        AccountDeletionWorkerAttempt(
            generation = workerGeneration,
            activityLease = lease,
            gatewayOrigin = currentJournal.gatewayOrigin,
            installationId = currentJournal.installationId,
            requestId = currentJournal.requestId,
        )
    }

    internal fun workerAttemptAllowed(
        attempt: AccountDeletionWorkerAttempt,
    ): Boolean = synchronized(lock) { workerAttemptAllowedLocked(attempt) }

    /**
     * Prepares temporary bytes and fsync without [lock]. [publish] runs only after the exact
     * attempt/generation/journal check and must be a bounded atomic rename or pointer switch: it
     * must not perform fsync or call this state machine. Storage requiring a blocking post-publish
     * fsync must provide its own generation-bound CAS contract.
     *
     * [retainDurableConfirmationOnStale] is only for a null-identity preparation whose successful
     * prepare step already made the confirmation authority durable. Rotation then hands that
     * authority to the replacement Activity as durable recovery instead of deleting it as temp.
     */
    internal fun runWorkerPreparedCommitIfCurrent(
        attempt: AccountDeletionWorkerAttempt,
        prepare: (AccountDeletionJournal?) -> Boolean,
        publish: () -> Boolean,
        discardPreparedState: () -> Unit,
        retainDurableConfirmationOnStale: Boolean = false,
    ): AccountDeletionWorkerStageResult = synchronized(workerStageSerialLock) stage@{
        val reservation = synchronized(lock) reservation@{
            if (
                activeWorkerPreparationReservation != null ||
                activeWorkerTerminalPreparationReservation != null ||
                !workerAttemptAllowedLocked(attempt)
            ) return@reservation null
            WorkerPreparationReservation(
                attempt = attempt,
                workerGeneration = workerGeneration,
                journal = current,
            ).also { activeWorkerPreparationReservation = it }
        } ?: return@stage AccountDeletionWorkerStageResult.STALE
        val prepared = runWorkerAction { prepare(reservation.journal) }
        val completion = synchronized(lock) {
            val ownsReservation = activeWorkerPreparationReservation === reservation
            if (ownsReservation) activeWorkerPreparationReservation = null
            val exact =
                ownsReservation &&
                    reservation.attempt === attempt &&
                    reservation.workerGeneration == workerGeneration &&
                    current === reservation.journal &&
                    workerAttemptAllowedLocked(attempt)
            if (!exact) {
                val retainDurableAuthority =
                    retainDurableConfirmationOnStale &&
                        prepared == AccountDeletionWorkerStageResult.SUCCEEDED &&
                        reservation.journal == null &&
                        attempt.requestId == null
                var cancellation: (() -> Unit)? = null
                if (
                    retainDurableAuthority &&
                    current == null &&
                    !failClosed &&
                    activeResetReservation == null
                ) {
                    val changed =
                        !durableConfirmationRecovery ||
                            !preparationRecoveryPending ||
                            !confirmationRequired
                    durableConfirmationRecovery = true
                    preparationRecoveryPending = true
                    confirmationRequired = true
                    if (changed) workerGeneration = nextGeneration(workerGeneration)
                    cancellation = invalidateNetworkCallLocked()
                }
                PreparedStageCompletion(
                    result = AccountDeletionWorkerStageResult.STALE,
                    networkCancellation = cancellation,
                    discardPreparedState = !retainDurableAuthority,
                )
            } else if (prepared != AccountDeletionWorkerStageResult.SUCCEEDED) {
                PreparedStageCompletion(
                    result = prepared,
                    discardPreparedState = true,
                )
            } else {
                val published = runWorkerAction(publish)
                PreparedStageCompletion(
                    result = published,
                    discardPreparedState =
                        published != AccountDeletionWorkerStageResult.SUCCEEDED,
                )
            }
        }
        cancelNetworkCall(completion.networkCancellation)
        if (completion.discardPreparedState) {
            discardPreparedStateSafely(discardPreparedState)
        }
        completion.result
    }

    internal fun runWorkerPreparationIfCurrent(
        attempt: AccountDeletionWorkerAttempt,
        action: (AccountDeletionJournal?) -> Boolean,
    ): AccountDeletionWorkerStageResult = runWorkerPreparedCommitIfCurrent(
        attempt = attempt,
        prepare = action,
        publish = { true },
        discardPreparedState = {},
    )

    /**
     * Runs deletion-monotonic durable I/O outside the state lock after reserving the exact worker
     * attempt. This API is intentionally narrower than a normal durable write:
     *
     * - a null-identity action may only make the already-confirmed deletion authority durable;
     *   if Activity replacement wins while that write succeeds, recovery remains fail-closed;
     * - a journal action may only advance or strengthen the deletion fence, never grant access or
     *   clear durable state, and its store must reject an older whole-snapshot owner;
     * - callers must treat [AccountDeletionWorkerStageResult.STALE] as a completed stale ordering,
     *   not as permission to undo the durable action.
     */
    internal fun runWorkerMonotonicDurableIoIfCurrent(
        attempt: AccountDeletionWorkerAttempt,
        action: (AccountDeletionJournal?) -> Boolean,
    ): AccountDeletionWorkerStageResult = runWorkerPreparedCommitIfCurrent(
        attempt = attempt,
        prepare = action,
        publish = { true },
        discardPreparedState = {},
        retainDurableConfirmationOnStale = attempt.requestId == null,
    )

    /**
     * Removes only terminal recovery metadata after an exact confirmed deletion receipt. The
     * action must be idempotent and may clear the fallback marker or actor binding, but it must not
     * clear the deletion journal, receipt, authority, or privacy fence. If rotation wins after the
     * action, the replacement Activity safely verifies/retries the already-progressed cleanup.
     */
    internal fun runWorkerTerminalCleanupIoIfCurrent(
        attempt: AccountDeletionWorkerAttempt,
        action: (AccountDeletionJournal) -> Boolean,
    ): AccountDeletionWorkerStageResult = runWorkerPreparedCommitIfCurrent(
        attempt = attempt,
        prepare = { journal ->
            journal?.takeIf(::isConfirmedTerminalAccountDeletion)
                ?.let(action)
                ?: false
        },
        publish = { true },
        discardPreparedState = {},
    )

    @Deprecated(
        message = "Blocking durable I/O must use prepare-only runWorkerPreparationIfCurrent",
        replaceWith = ReplaceWith("runWorkerPreparationIfCurrent(attempt, action)"),
    )
    internal fun runWorkerStageIfCurrent(
        attempt: AccountDeletionWorkerAttempt,
        action: (AccountDeletionJournal?) -> Boolean,
    ): AccountDeletionWorkerStageResult =
        runWorkerPreparationIfCurrent(attempt, action)

    /**
     * Prepares terminal bytes and fsync without [lock]. [publish] runs only after an exact
     * attempt/generation/journal check and must be a bounded atomic rename or pointer switch: it
     * must not perform fsync or call this state machine. Storage requiring a blocking fsync after
     * publish must provide its own generation-bound CAS instead of using this critical section.
     */
    internal fun runWorkerTerminalPreparedCommitIfCurrent(
        attempt: AccountDeletionWorkerAttempt,
        errorCode: String? = null,
        prepare: (AccountDeletionJournal?) -> Boolean,
        publish: () -> Boolean,
        discardPreparedState: () -> Unit,
    ): AccountDeletionWorkerStageResult = synchronized(workerStageSerialLock) stage@{
        val reservation = synchronized(lock) reservation@{
            if (
                activeWorkerPreparationReservation != null ||
                activeWorkerTerminalPreparationReservation != null
            ) {
                return@reservation null
            }
            val ownsCurrent = workerAttemptAllowedLocked(attempt)
            val ownsTerminal = workerAttemptOwnsCurrentTerminalLocked(attempt)
            if (!ownsCurrent && !ownsTerminal) return@reservation null
            WorkerTerminalPreparationReservation(
                attempt = attempt,
                workerGeneration = workerGeneration,
                sourceJournal = current,
                terminalJournal = terminalJournalCandidateLocked(errorCode),
                ownedTerminalAtReservation = ownsTerminal,
            ).also { activeWorkerTerminalPreparationReservation = it }
        } ?: return@stage AccountDeletionWorkerStageResult.STALE
        val prepared = runWorkerAction { prepare(reservation.terminalJournal) }
        val completion = synchronized(lock) {
            val ownsReservation =
                activeWorkerTerminalPreparationReservation === reservation
            if (ownsReservation) activeWorkerTerminalPreparationReservation = null
            val stillOwned = if (reservation.ownedTerminalAtReservation) {
                workerAttemptOwnsCurrentTerminalLocked(reservation.attempt)
            } else {
                workerAttemptAllowedLocked(reservation.attempt)
            }
            val exact =
                ownsReservation &&
                    reservation.attempt === attempt &&
                    reservation.workerGeneration == workerGeneration &&
                    current === reservation.sourceJournal &&
                    stillOwned
            if (!exact) {
                PreparedStageCompletion(
                    result = AccountDeletionWorkerStageResult.STALE,
                    discardPreparedState = true,
                )
            } else {
                val published = if (prepared == AccountDeletionWorkerStageResult.SUCCEEDED) {
                    runWorkerAction(publish)
                } else {
                    prepared
                }
                enterFailClosedLocked(errorCode)
                check(current == reservation.terminalJournal)
                PreparedStageCompletion(
                    result = published,
                    networkCancellation = invalidateNetworkCallLocked(),
                    discardPreparedState =
                        published != AccountDeletionWorkerStageResult.SUCCEEDED,
                )
            }
        }
        cancelNetworkCall(completion.networkCancellation)
        if (completion.discardPreparedState) {
            discardPreparedStateSafely(discardPreparedState)
        }
        completion.result
    }

    /**
     * Runs only monotonic fail-closed terminal persistence. The action may write the supplied
     * terminal journal (or a journal-free terminal fence) but must never clear or weaken durable
     * deletion state. Once the action starts, success and failure both hand the same first terminal
     * reason to the latest process journal. Activity replacement may make the UI result stale, but
     * it cannot leave durable FAIL_CLOSED state paired with a non-terminal process journal.
     */
    internal fun runWorkerMonotonicTerminalDurableIoIfCurrent(
        attempt: AccountDeletionWorkerAttempt,
        errorCode: String? = null,
        action: (AccountDeletionJournal?) -> Boolean,
    ): AccountDeletionWorkerStageResult = synchronized(workerStageSerialLock) stage@{
        val reservation = synchronized(lock) reservation@{
            if (
                activeWorkerPreparationReservation != null ||
                activeWorkerTerminalPreparationReservation != null
            ) {
                return@reservation null
            }
            val ownsCurrent = workerAttemptAllowedLocked(attempt)
            val ownsTerminal = workerAttemptOwnsCurrentTerminalLocked(attempt)
            if (!ownsCurrent && !ownsTerminal) return@reservation null
            WorkerTerminalPreparationReservation(
                attempt = attempt,
                workerGeneration = workerGeneration,
                sourceJournal = current,
                terminalJournal = terminalJournalCandidateLocked(errorCode),
                ownedTerminalAtReservation = ownsTerminal,
            ).also { activeWorkerTerminalPreparationReservation = it }
        } ?: return@stage AccountDeletionWorkerStageResult.STALE
        val durableResult = runWorkerAction { action(reservation.terminalJournal) }
        val completion = synchronized(lock) {
            val ownsReservation =
                activeWorkerTerminalPreparationReservation === reservation
            if (ownsReservation) activeWorkerTerminalPreparationReservation = null
            val stillOwned = if (reservation.ownedTerminalAtReservation) {
                workerAttemptOwnsCurrentTerminalLocked(reservation.attempt)
            } else {
                workerAttemptAllowedLocked(reservation.attempt)
            }
            val exact =
                ownsReservation &&
                    reservation.attempt === attempt &&
                    reservation.workerGeneration == workerGeneration &&
                    current === reservation.sourceJournal &&
                    stillOwned
            val firstReason = reservation.terminalJournal?.lastErrorCode ?: errorCode
            enterFailClosedLocked(firstReason)
            val result = if (exact) {
                durableResult
            } else {
                AccountDeletionWorkerStageResult.STALE
            }
            result to invalidateNetworkCallLocked()
        }
        cancelNetworkCall(completion.second)
        completion.first
    }

    @Deprecated(
        message = "Durable terminal I/O must use runWorkerTerminalPreparedCommitIfCurrent",
    )
    internal fun runWorkerTerminalStageIfCurrent(
        attempt: AccountDeletionWorkerAttempt,
        errorCode: String? = null,
        action: (AccountDeletionJournal?) -> Boolean,
    ): AccountDeletionWorkerStageResult = runWorkerTerminalPreparedCommitIfCurrent(
        attempt = attempt,
        errorCode = errorCode,
        prepare = action,
        publish = { true },
        discardPreparedState = {},
    )

    /** Reserves a destructive reset; the durable destructive action must run without [lock]. */
    internal fun reserveWorkerResetIfCurrent(
        attempt: AccountDeletionWorkerAttempt,
    ): AccountDeletionResetReservation? = synchronized(workerStageSerialLock) stage@{
        val transition = synchronized(lock) reservation@{
            val journal = current
            if (
                activeResetReservation != null ||
                activeWorkerPreparationReservation != null ||
                activeWorkerTerminalPreparationReservation != null ||
                !workerAttemptAllowedLocked(attempt) ||
                journal == null ||
                !isConfirmedTerminalAccountDeletion(journal)
            ) {
                return@reservation null
            }
            reserveResetLocked(journal)
        } ?: return@stage null
        cancelNetworkCall(transition.second)
        transition.first
    }

    internal fun commitWorkerReset(
        reservation: AccountDeletionResetReservation,
    ): Boolean {
        val transition = synchronized(lock) {
            if (activeResetReservation !== reservation) return false
            val matches = resetReservationMatchesCurrentLocked(reservation)
            activeResetReservation = null
            if (!matches) return false
            confirmationRequired = false
            durableConfirmationRecovery = false
            preparationRecoveryPending = false
            current = null
            failClosed = false
            failClosedReason = null
            workerGeneration = nextGeneration(workerGeneration)
            true to invalidateNetworkCallLocked()
        }
        cancelNetworkCall(transition.second)
        return transition.first
    }

    /** Retires a failed reset into fail-closed without relying on the invalidated worker attempt. */
    internal fun rollbackWorkerReset(
        reservation: AccountDeletionResetReservation,
    ): Boolean {
        val transition = synchronized(lock) {
            if (activeResetReservation !== reservation) return false
            val matches = resetReservationMatchesCurrentLocked(reservation)
            activeResetReservation = null
            if (!matches) return false
            enterFailClosedLocked(ACCOUNT_DELETION_RESET_FAILURE_REASON)
            true to invalidateNetworkCallLocked()
        }
        cancelNetworkCall(transition.second)
        return transition.first
    }

    internal fun beginLegacyFailClosedUpgradeAttempt(
        journal: AccountDeletionJournal,
        lease: AccountDeletionActivityLease,
    ): AccountDeletionLegacyFailClosedUpgradeAttempt? = synchronized(lock) {
        val currentJournal = current ?: return@synchronized null
        if (
            activeResetReservation != null ||
            !activityLeaseAllowedLocked(lease) ||
            !failClosed ||
            currentJournal.phase != AccountDeletionPhase.FAIL_CLOSED ||
            currentJournal != journal
        ) return@synchronized null
        AccountDeletionLegacyFailClosedUpgradeAttempt(
            generation = workerGeneration,
            activityLease = lease,
            journal = currentJournal,
        )
    }

    /** Persists a legacy terminal upgrade without reopening network processing. */
    internal fun runLegacyFailClosedUpgradeStageIfCurrent(
        attempt: AccountDeletionLegacyFailClosedUpgradeAttempt,
        action: (AccountDeletionJournal) -> Boolean,
    ): AccountDeletionWorkerStageResult = synchronized(workerStageSerialLock) stage@{
        val reservedJournal = synchronized(lock) reservation@{
            val currentJournal = current
            if (
                activeResetReservation != null ||
                !activityLeaseAllowedLocked(attempt.activityLease) ||
                attempt.generation != workerGeneration ||
                !failClosed ||
                currentJournal == null ||
                currentJournal.phase != AccountDeletionPhase.FAIL_CLOSED ||
                currentJournal != attempt.journal
            ) {
                return@reservation null
            }
            currentJournal
        } ?: return@stage AccountDeletionWorkerStageResult.STALE
        val prepared = runWorkerAction { action(reservedJournal) }
        synchronized(lock) completion@{
            if (
                activeResetReservation != null ||
                !activityLeaseAllowedLocked(attempt.activityLease) ||
                attempt.generation != workerGeneration ||
                !failClosed ||
                current !== reservedJournal ||
                current?.phase != AccountDeletionPhase.FAIL_CLOSED
            ) return@completion AccountDeletionWorkerStageResult.STALE
            prepared
        }
    }

    internal fun registerNetworkCall(
        attempt: AccountDeletionWorkerAttempt,
        cancel: () -> Unit,
    ): AccountDeletionNetworkCallLease? = synchronized(lock) {
        if (!workerAttemptAllowedLocked(attempt) || activeNetworkCall != null) {
            return@synchronized null
        }
        networkCallGeneration = nextGeneration(networkCallGeneration)
        AccountDeletionNetworkCallLease(
            generation = networkCallGeneration,
            workerAttempt = attempt,
        ).also { lease ->
            activeNetworkCall = ActiveNetworkCall(lease = lease, cancel = cancel)
        }
    }

    /** Linearization point for starting I/O; the network execute itself runs without [lock]. */
    internal fun beginNetworkCall(lease: AccountDeletionNetworkCallLease): Boolean =
        synchronized(lock) {
            val call = activeNetworkCall
                ?.takeIf { it.lease === lease }
                ?: return@synchronized false
            if (call.begun || !workerAttemptAllowedLocked(lease.workerAttempt)) {
                return@synchronized false
            }
            call.begun = true
            true
        }

    internal fun completeNetworkCall(
        lease: AccountDeletionNetworkCallLease,
        status: AccountDeletionStatus,
        acknowledgesDeviceEvidence: Boolean = false,
    ): AccountDeletionApplyResult? = synchronized(lock) {
        val call = activeNetworkCall
            ?.takeIf { it.lease === lease && it.begun }
            ?: return@synchronized null
        activeNetworkCall = null
        if (!workerAttemptAllowedLocked(lease.workerAttempt)) {
            return@synchronized null
        }
        applyLocked(status, acknowledgesDeviceEvidence)
    }

    internal fun releaseNetworkCall(lease: AccountDeletionNetworkCallLease): Boolean =
        synchronized(lock) {
            if (activeNetworkCall?.lease !== lease) return@synchronized false
            activeNetworkCall = null
            true
        }

    private fun runWorkerAction(action: () -> Boolean): AccountDeletionWorkerStageResult =
        try {
            if (action()) {
                AccountDeletionWorkerStageResult.SUCCEEDED
            } else {
                AccountDeletionWorkerStageResult.FAILED
            }
        } catch (_: Exception) {
            AccountDeletionWorkerStageResult.FAILED
        }

    private fun discardPreparedStateSafely(discardPreparedState: () -> Unit) {
        try {
            discardPreparedState()
        } catch (_: Exception) {
            Unit
        }
    }

    private fun activityLeaseAllowedLocked(
        lease: AccountDeletionActivityLease?,
    ): Boolean = if (lease == null) {
        !lifecycleManaged
    } else {
        activeActivityLease === lease
    }

    private fun workerAttemptAllowedLocked(
        attempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        if (
            activeResetReservation != null ||
            !activityLeaseAllowedLocked(attempt.activityLease) ||
            attempt.generation != workerGeneration ||
            failClosed
        ) {
            return false
        }
        if (attempt.requestId == null) {
            return confirmationRequired && current == null
        }
        val currentJournal = current ?: return false
        return accountDeletionNetworkEntryAllowed(currentJournal) &&
            attempt.gatewayOrigin == currentJournal.gatewayOrigin &&
            attempt.installationId == currentJournal.installationId &&
            attempt.requestId == currentJournal.requestId
    }

    private fun workerAttemptOwnsCurrentTerminalLocked(
        attempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val currentJournal = current ?: return false
        return activeResetReservation == null &&
            failClosed &&
            activityLeaseAllowedLocked(attempt.activityLease) &&
            currentJournal.phase == AccountDeletionPhase.FAIL_CLOSED &&
            workerGeneration == attempt.generation + 1L &&
            attempt.requestId != null &&
            attempt.gatewayOrigin == currentJournal.gatewayOrigin &&
            attempt.installationId == currentJournal.installationId &&
            attempt.requestId == currentJournal.requestId
    }

    fun apply(
        status: AccountDeletionStatus,
        acknowledgesDeviceEvidence: Boolean = false,
    ): AccountDeletionApplyResult = synchronized(lock) {
        if (!activityLeaseAllowedLocked(null)) {
            return@synchronized AccountDeletionApplyResult.STALE_IGNORED
        }
        applyLocked(status, acknowledgesDeviceEvidence)
    }

    internal fun apply(
        attempt: AccountDeletionWorkerAttempt,
        status: AccountDeletionStatus,
        acknowledgesDeviceEvidence: Boolean = false,
    ): AccountDeletionApplyResult = synchronized(lock) {
        if (!workerAttemptAllowedLocked(attempt)) {
            return@synchronized AccountDeletionApplyResult.STALE_IGNORED
        }
        applyLocked(status, acknowledgesDeviceEvidence)
    }

    private fun applyLocked(
        status: AccountDeletionStatus,
        acknowledgesDeviceEvidence: Boolean,
    ): AccountDeletionApplyResult {
        val journal = current
            ?: return AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED
        if (failClosed || journal.phase == AccountDeletionPhase.FAIL_CLOSED) {
            return AccountDeletionApplyResult.STALE_IGNORED
        }
        if (
            status.installationId != journal.installationId ||
            status.requestId != journal.requestId ||
            status.clientRevision != journal.clientRevision ||
            (journal.acceptedAt != null && journal.acceptedAt != status.requestedAt) ||
            (journal.accountGeneration != null &&
                journal.accountGeneration != status.accountGeneration) ||
            (journal.tombstoneId != null && journal.tombstoneId != status.tombstoneId) ||
            (journal.requestReceiptSha256 != null &&
                journal.requestReceiptSha256 != status.requestReceiptSha256)
        ) {
            enterFailClosedLocked(ACCOUNT_DELETION_IDENTITY_MISMATCH_REASON)
            return AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED
        }
        if (status.revision < journal.serverRevision) {
            return AccountDeletionApplyResult.STALE_IGNORED
        }
        val deviceEvidenceAcknowledged =
            journal.deviceEvidenceAcknowledged ||
                (acknowledgesDeviceEvidence && journal.deviceEvidenceSha256 != null) ||
                (
                    journal.deviceEvidenceSha256 != null &&
                        status.items.getValue(DeletionInventoryItem.DEVICE_UNSENT)
                            .evidenceSha256 == journal.deviceEvidenceSha256
                )
        if (status.revision == journal.serverRevision) {
            if (statusMatchesJournal(status, journal)) {
                if (deviceEvidenceAcknowledged != journal.deviceEvidenceAcknowledged) {
                    current = journal.copy(
                        deviceEvidenceAcknowledged = deviceEvidenceAcknowledged,
                    )
                }
                return AccountDeletionApplyResult.DUPLICATE
            }
            enterFailClosedLocked(ACCOUNT_DELETION_REVISION_CONFLICT_REASON)
            return AccountDeletionApplyResult.CONFLICT_FAIL_CLOSED
        }
        if (
            journal.serverRevision > 0L &&
            DeletionInventoryItem.entries.any { item ->
                !validBackendItemAdvance(
                    journal.items.getValue(item),
                    status.items.getValue(item),
                )
            }
        ) {
            enterFailClosedLocked(ACCOUNT_DELETION_STATUS_CONFLICT_REASON)
            return AccountDeletionApplyResult.CONFLICT_FAIL_CLOSED
        }
        val proposedPhase = status.overallStatus.toPhase()
        if (
            proposedPhase == AccountDeletionPhase.COMPLETED &&
            !deviceEvidenceAcknowledged
        ) {
            enterFailClosedLocked(ACCOUNT_DELETION_DEVICE_EVIDENCE_MISSING_REASON)
            return AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED
        }
        current = journal.copy(
            phase = preserveFailClosedPhaseLocked(journal, proposedPhase),
            serverRevision = status.revision,
            items = Collections.unmodifiableMap(status.items.toMap()),
            receiptSha256 = status.receiptSha256,
            lastErrorCode = null,
            acceptedAt = status.requestedAt,
            accountGeneration = status.accountGeneration,
            tombstoneId = status.tombstoneId,
            requestReceiptSha256 = status.requestReceiptSha256,
            deviceEvidenceAcknowledged = deviceEvidenceAcknowledged,
        )
        return AccountDeletionApplyResult.APPLIED
    }

    /** Device state becomes authoritative only after the backend echoes linked evidence. */
    fun updateLocal(@Suppress("UNUSED_PARAMETER") status: DeletionItemStatus): Boolean = false

    fun recordDeviceEvidence(
        evidenceId: String,
        evidenceSha256: String,
        expectedStatusRevision: Long,
        result: String,
        completedAt: String,
    ): Boolean = synchronized(lock) {
        if (!activityLeaseAllowedLocked(null)) return@synchronized false
        recordDeviceEvidenceLocked(
            evidenceId,
            evidenceSha256,
            expectedStatusRevision,
            result,
            completedAt,
        )
    }

    internal fun recordDeviceEvidence(
        attempt: AccountDeletionWorkerAttempt,
        evidenceId: String,
        evidenceSha256: String,
        expectedStatusRevision: Long,
        result: String,
        completedAt: String,
    ): Boolean = synchronized(lock) {
        if (!workerAttemptAllowedLocked(attempt)) return@synchronized false
        recordDeviceEvidenceLocked(
            evidenceId,
            evidenceSha256,
            expectedStatusRevision,
            result,
            completedAt,
        )
    }

    private fun recordDeviceEvidenceLocked(
        evidenceId: String,
        evidenceSha256: String,
        expectedStatusRevision: Long,
        result: String,
        completedAt: String,
    ): Boolean {
        if (
            !OPAQUE_ID.matches(evidenceId) ||
            !SHA256.matches(evidenceSha256) ||
            expectedStatusRevision <= 0L ||
            result !in setOf("DELETED", "NOT_FOUND", "FAILED") ||
            !validDeviceEvidenceInstant(completedAt)
        ) return false
        val journal = current ?: return false
        if (failClosed || journal.phase == AccountDeletionPhase.FAIL_CLOSED) return false
        if (journal.serverRevision <= 0L) return false
        if (
            journal.deviceEvidenceId != null &&
            (journal.deviceEvidenceId != evidenceId ||
                journal.deviceEvidenceSha256 != evidenceSha256 ||
                journal.deviceEvidenceExpectedStatusRevision != expectedStatusRevision ||
                journal.deviceEvidenceResult != result ||
                journal.deviceEvidenceCompletedAt != completedAt)
        ) return false
        current = journal.copy(
            deviceEvidenceId = evidenceId,
            deviceEvidenceSha256 = evidenceSha256,
            deviceEvidenceExpectedStatusRevision = expectedStatusRevision,
            deviceEvidenceResult = result,
            deviceEvidenceCompletedAt = completedAt,
        )
        return true
    }

    fun rebaseDeviceEvidence(
        evidenceId: String,
        evidenceSha256: String,
        expectedStatusRevision: Long,
        result: String,
        completedAt: String,
    ): Boolean = synchronized(lock) {
        if (!activityLeaseAllowedLocked(null)) return@synchronized false
        rebaseDeviceEvidenceLocked(
            evidenceId,
            evidenceSha256,
            expectedStatusRevision,
            result,
            completedAt,
        )
    }

    internal fun rebaseDeviceEvidence(
        attempt: AccountDeletionWorkerAttempt,
        evidenceId: String,
        evidenceSha256: String,
        expectedStatusRevision: Long,
        result: String,
        completedAt: String,
    ): Boolean = synchronized(lock) {
        if (!workerAttemptAllowedLocked(attempt)) return@synchronized false
        rebaseDeviceEvidenceLocked(
            evidenceId,
            evidenceSha256,
            expectedStatusRevision,
            result,
            completedAt,
        )
    }

    private fun rebaseDeviceEvidenceLocked(
        evidenceId: String,
        evidenceSha256: String,
        expectedStatusRevision: Long,
        result: String,
        completedAt: String,
    ): Boolean {
        if (
            !OPAQUE_ID.matches(evidenceId) ||
            !SHA256.matches(evidenceSha256) ||
            expectedStatusRevision <= 0L ||
            result !in setOf("DELETED", "NOT_FOUND", "FAILED") ||
            !validDeviceEvidenceInstant(completedAt)
        ) return false
        val journal = current ?: return false
        if (failClosed || journal.phase == AccountDeletionPhase.FAIL_CLOSED) return false
        val priorExpected = journal.deviceEvidenceExpectedStatusRevision ?: return false
        if (
            journal.deviceEvidenceAcknowledged ||
            evidenceId == journal.deviceEvidenceId ||
            expectedStatusRevision <= priorExpected ||
            expectedStatusRevision != journal.serverRevision ||
            result != journal.deviceEvidenceResult ||
            completedAt != journal.deviceEvidenceCompletedAt
        ) return false
        current = journal.copy(
            deviceEvidenceId = evidenceId,
            deviceEvidenceSha256 = evidenceSha256,
            deviceEvidenceExpectedStatusRevision = expectedStatusRevision,
        )
        return true
    }

    fun markRetry(errorCode: String): Boolean = synchronized(lock) {
        if (!activityLeaseAllowedLocked(null)) return@synchronized false
        markRetryLocked(errorCode)
    }

    internal fun markRetry(
        attempt: AccountDeletionWorkerAttempt,
        errorCode: String,
    ): Boolean = synchronized(lock) {
        if (!workerAttemptAllowedLocked(attempt)) return@synchronized false
        markRetryLocked(errorCode)
    }

    private fun markRetryLocked(errorCode: String): Boolean {
        val journal = current ?: return false
        if (failClosed || journal.phase == AccountDeletionPhase.FAIL_CLOSED) return false
        current = journal.copy(
            phase = preserveFailClosedPhaseLocked(journal, AccountDeletionPhase.RETRY_WAIT),
            lastErrorCode = errorCode.take(96),
        )
        return true
    }

    fun failClosed(errorCode: String? = null): Boolean = failClosedForLease(null, errorCode)

    internal fun failClosed(
        lease: AccountDeletionActivityLease,
        errorCode: String? = null,
    ): Boolean = failClosedForLease(lease, errorCode)

    /** Applies the independently persisted no-backup reason over any older journal reason. */
    internal fun enforceFailClosedAuthority(
        lease: AccountDeletionActivityLease,
        reason: String,
    ): Boolean {
        val exactReason = reason.take(96)
        val transition = synchronized(lock) {
            if (
                !activityLeaseAllowedLocked(lease) ||
                activeResetReservation != null
            ) return false
            val changed =
                !failClosed ||
                    confirmationRequired ||
                    preparationRecoveryPending ||
                    failClosedReason != exactReason ||
                    current?.phase != AccountDeletionPhase.FAIL_CLOSED ||
                    current?.lastErrorCode != exactReason
            failClosed = true
            confirmationRequired = false
            durableConfirmationRecovery = false
            preparationRecoveryPending = false
            failClosedReason = exactReason
            current = current?.copy(
                phase = AccountDeletionPhase.FAIL_CLOSED,
                lastErrorCode = exactReason,
            )
            if (changed) workerGeneration = nextGeneration(workerGeneration)
            true to invalidateNetworkCallLocked()
        }
        cancelNetworkCall(transition.second)
        return transition.first
    }

    private fun failClosedForLease(
        lease: AccountDeletionActivityLease?,
        errorCode: String?,
    ): Boolean = synchronized(workerStageSerialLock) stage@{
        val transition = synchronized(lock) transition@{
            if (!activityLeaseAllowedLocked(lease)) return@transition false to null
            enterFailClosedLocked(errorCode)
            true to invalidateNetworkCallLocked()
        }
        if (!transition.first) return@stage false
        cancelNetworkCall(transition.second)
        transition.first
    }

    fun phase(): AccountDeletionPhase {
        val resetPending = activeResetReservation != null
        val blocked = failClosed
        val journal = current
        val recovering = durableConfirmationRecovery
        val preparationRecovery = preparationRecoveryPending
        val confirming = confirmationRequired
        return when {
            resetPending -> AccountDeletionPhase.RESET_PENDING
            blocked -> AccountDeletionPhase.FAIL_CLOSED
            journal != null -> journal.phase
            recovering || preparationRecovery || confirming ->
                AccountDeletionPhase.CONFIRM_REQUIRED
            else -> AccountDeletionPhase.IDLE
        }
    }

    fun snapshotOrNull(): AccountDeletionJournal? = current

    fun failureReasonOrNull(): String? {
        if (!failClosed) return null
        return current?.lastErrorCode ?: failClosedReason
    }

    fun processingBlocked(): Boolean {
        return failClosed ||
            current != null ||
            durableConfirmationRecovery ||
            preparationRecoveryPending ||
            activeResetReservation != null ||
            activeWorkerPreparationReservation != null ||
            activeWorkerTerminalPreparationReservation != null
    }

    fun resetForNewEnrollment(): Boolean = resetForNewEnrollmentForLease(null)

    internal fun resetForNewEnrollment(
        lease: AccountDeletionActivityLease,
    ): Boolean = resetForNewEnrollmentForLease(lease)

    private fun resetForNewEnrollmentForLease(
        lease: AccountDeletionActivityLease?,
    ): Boolean = synchronized(workerStageSerialLock) stage@{
        val transition = synchronized(lock) transition@{
            if (
                !activityLeaseAllowedLocked(lease) ||
                activeResetReservation != null ||
                durableConfirmationRecovery
            ) return@transition null
            val changed =
                confirmationRequired ||
                    durableConfirmationRecovery ||
                    preparationRecoveryPending ||
                    current != null ||
                    failClosed
            confirmationRequired = false
            durableConfirmationRecovery = false
            preparationRecoveryPending = false
            current = null
            failClosed = false
            failClosedReason = null
            workerGeneration = nextGeneration(workerGeneration)
            changed to invalidateNetworkCallLocked()
        } ?: return@stage false
        cancelNetworkCall(transition.second)
        transition.first
    }

    private fun resetReservationMatchesCurrentLocked(
        reservation: AccountDeletionResetReservation,
    ): Boolean =
        activeResetReservation === reservation &&
            activeResetReservation?.generation == reservation.generation &&
            !failClosed &&
            current == reservation.journal &&
            isConfirmedTerminalAccountDeletion(reservation.journal)

    private fun reserveResetLocked(
        journal: AccountDeletionJournal,
    ): Pair<AccountDeletionResetReservation, (() -> Unit)?> {
        resetReservationGeneration = nextGeneration(resetReservationGeneration)
        workerGeneration = nextGeneration(workerGeneration)
        val reservation = AccountDeletionResetReservation(
            generation = resetReservationGeneration,
            journal = journal,
        )
        activeResetReservation = reservation
        return reservation to invalidateNetworkCallLocked()
    }

    private fun enterFailClosedLocked(errorCode: String? = null) {
        if (failClosed || current?.phase == AccountDeletionPhase.FAIL_CLOSED) {
            failClosed = true
            confirmationRequired = false
            durableConfirmationRecovery = false
            preparationRecoveryPending = false
            return
        }
        val firstReason = errorCode?.take(96) ?: current?.lastErrorCode
        failClosed = true
        confirmationRequired = false
        durableConfirmationRecovery = false
        preparationRecoveryPending = false
        failClosedReason = firstReason
        workerGeneration = nextGeneration(workerGeneration)
        current = current?.copy(
            phase = AccountDeletionPhase.FAIL_CLOSED,
            lastErrorCode = firstReason,
        )
    }

    private fun terminalJournalCandidateLocked(
        errorCode: String?,
    ): AccountDeletionJournal? {
        val journal = current ?: return null
        if (failClosed || journal.phase == AccountDeletionPhase.FAIL_CLOSED) return journal
        val firstReason = errorCode?.take(96) ?: journal.lastErrorCode
        return journal.copy(
            phase = AccountDeletionPhase.FAIL_CLOSED,
            lastErrorCode = firstReason,
        )
    }

    private fun invalidateNetworkCallLocked(): (() -> Unit)? =
        activeNetworkCall?.cancel.also { activeNetworkCall = null }

    private fun cancelNetworkCall(cancel: (() -> Unit)?) {
        if (cancel == null) return
        try {
            cancel()
        } catch (_: Exception) {
            Unit
        }
    }

    private fun nextGeneration(current: Long): Long {
        check(current < Long.MAX_VALUE) { "account deletion generation exhausted" }
        return current + 1L
    }

    private fun preserveFailClosedPhaseLocked(
        journal: AccountDeletionJournal,
        proposed: AccountDeletionPhase,
    ): AccountDeletionPhase {
        if (failClosed || journal.phase == AccountDeletionPhase.FAIL_CLOSED) {
            failClosed = true
            return AccountDeletionPhase.FAIL_CLOSED
        }
        return proposed
    }
}

private fun AccountDeletionJournal.sameWorkerIdentity(
    other: AccountDeletionJournal,
): Boolean =
    gatewayOrigin == other.gatewayOrigin &&
        installationId == other.installationId &&
        requestId == other.requestId

private fun AccountDeletionJournal.isMonotonicProcessSuccessorOf(
    persisted: AccountDeletionJournal,
    processFailClosed: Boolean,
): Boolean {
    if (
        !sameWorkerIdentity(persisted) ||
        clientRevision != persisted.clientRevision ||
        requestedAt != persisted.requestedAt ||
        serverRevision < persisted.serverRevision ||
        persisted.phase == AccountDeletionPhase.FAIL_CLOSED
    ) return false

    if (persisted.serverRevision > 0L) {
        if (
            acceptedAt != persisted.acceptedAt ||
            accountGeneration != persisted.accountGeneration ||
            tombstoneId != persisted.tombstoneId ||
            requestReceiptSha256 != persisted.requestReceiptSha256
        ) return false
    }
    if (serverRevision == persisted.serverRevision) {
        if (
            acceptedAt != persisted.acceptedAt ||
            accountGeneration != persisted.accountGeneration ||
            tombstoneId != persisted.tombstoneId ||
            requestReceiptSha256 != persisted.requestReceiptSha256 ||
            items != persisted.items ||
            receiptSha256 != persisted.receiptSha256
        ) return false
    } else {
        if (
            serverRevision <= 0L ||
            acceptedAt == null ||
            accountGeneration == null ||
            tombstoneId == null ||
            requestReceiptSha256 == null ||
            (persisted.serverRevision > 0L &&
                DeletionInventoryItem.entries.any { item ->
                    !validBackendItemAdvance(
                        persisted.items.getValue(item),
                        items.getValue(item),
                    )
                }) ||
            (persisted.receiptSha256 != null &&
                receiptSha256 != persisted.receiptSha256)
        ) return false
    }

    if (persisted.deviceEvidenceAcknowledged && !deviceEvidenceAcknowledged) {
        return false
    }
    if (persisted.deviceEvidenceId != null) {
        val exactEvidence =
            deviceEvidenceId == persisted.deviceEvidenceId &&
                deviceEvidenceSha256 == persisted.deviceEvidenceSha256 &&
                deviceEvidenceExpectedStatusRevision ==
                persisted.deviceEvidenceExpectedStatusRevision &&
                deviceEvidenceResult == persisted.deviceEvidenceResult &&
                deviceEvidenceCompletedAt == persisted.deviceEvidenceCompletedAt
        val rebasedEvidence =
            !persisted.deviceEvidenceAcknowledged &&
                deviceEvidenceExpectedStatusRevision != null &&
                deviceEvidenceExpectedStatusRevision!! >
                requireNotNull(persisted.deviceEvidenceExpectedStatusRevision) &&
                deviceEvidenceExpectedStatusRevision == serverRevision &&
                deviceEvidenceResult == persisted.deviceEvidenceResult &&
                deviceEvidenceCompletedAt == persisted.deviceEvidenceCompletedAt
        if (!exactEvidence && !rebasedEvidence) return false
    }

    if (processFailClosed) {
        return phase == AccountDeletionPhase.FAIL_CLOSED
    }
    val backendPhase = derivedOverallStatus(items).toPhase()
    return when {
        phase == backendPhase -> lastErrorCode == null
        phase == AccountDeletionPhase.RETRY_WAIT &&
            backendPhase != AccountDeletionPhase.COMPLETED ->
            !lastErrorCode.isNullOrBlank()
        else -> false
    }
}

private fun statusMatchesJournal(
    status: AccountDeletionStatus,
    journal: AccountDeletionJournal,
): Boolean =
    status.requestedAt == journal.acceptedAt &&
        status.accountGeneration == journal.accountGeneration &&
        status.tombstoneId == journal.tombstoneId &&
        status.requestReceiptSha256 == journal.requestReceiptSha256 &&
        status.items == journal.items &&
        status.receiptSha256 == journal.receiptSha256

private fun validBackendItemAdvance(
    current: DeletionItemStatus,
    next: DeletionItemStatus,
): Boolean {
    if (next.itemRevision < current.itemRevision) return false
    if (next.itemRevision == current.itemRevision && next != current) return false
    if (current.state.isComplete && next != current) return false
    return true
}

private fun derivedOverallStatus(
    items: Map<DeletionInventoryItem, DeletionItemStatus>,
): AccountDeletionOverallStatus = when {
    items.values.all { it.state.isComplete } -> AccountDeletionOverallStatus.COMPLETED
    items.values.any { it.state == DeletionItemState.LEGAL_HOLD } ->
        AccountDeletionOverallStatus.RESTRICTED
    items.values.any { it.state == DeletionItemState.FAILED } ->
        AccountDeletionOverallStatus.FAILED
    items.values.any { it.state == DeletionItemState.RETRY_WAIT } ->
        AccountDeletionOverallStatus.RETRY_WAIT
    items.values.any {
        it.state == DeletionItemState.EXTERNAL_PENDING || it.state.isComplete
    } -> AccountDeletionOverallStatus.PARTIAL
    else -> AccountDeletionOverallStatus.PROCESSING
}

private fun AccountDeletionOverallStatus.toPhase(): AccountDeletionPhase = when (this) {
    AccountDeletionOverallStatus.RETRY_WAIT -> AccountDeletionPhase.RETRY_WAIT
    AccountDeletionOverallStatus.RESTRICTED -> AccountDeletionPhase.RESTRICTED
    AccountDeletionOverallStatus.FAILED -> AccountDeletionPhase.PARTIAL_FAILURE
    AccountDeletionOverallStatus.COMPLETED -> AccountDeletionPhase.COMPLETED
    AccountDeletionOverallStatus.PROCESSING,
    AccountDeletionOverallStatus.PARTIAL,
    -> AccountDeletionPhase.IN_PROGRESS
}

private fun validInstant(value: String): Boolean =
    runCatching { Instant.parse(value) }.isSuccess

internal fun validCanonicalWholeSecondUtcInstant(value: String): Boolean {
    if (!DEVICE_EVIDENCE_INSTANT.matches(value)) return false
    val parsed = runCatching { Instant.parse(value) }.getOrNull() ?: return false
    return parsed.toString() == value
}

internal fun isConfirmedTerminalAccountDeletion(
    journal: AccountDeletionJournal,
): Boolean =
    journal.phase == AccountDeletionPhase.COMPLETED &&
        journal.serverRevision > 0L &&
        journal.acceptedAt != null &&
        journal.accountGeneration != null &&
        journal.tombstoneId != null &&
        journal.requestReceiptSha256?.let(SHA256::matches) == true &&
        journal.receiptSha256?.let(SHA256::matches) == true &&
        journal.deviceEvidenceAcknowledged &&
        journal.deviceEvidenceSha256?.let(SHA256::matches) == true &&
        journal.items.values.all { item -> item.state.isComplete }

internal fun accountDeletionNetworkEntryAllowed(
    journal: AccountDeletionJournal?,
): Boolean = journal != null && journal.phase != AccountDeletionPhase.FAIL_CLOSED

internal fun dispatchAccountDeletionNetworkEntry(
    journal: AccountDeletionJournal?,
    keepPrivacyFenceClosed: () -> Unit,
    dispatch: () -> Unit,
): Boolean {
    if (!accountDeletionNetworkEntryAllowed(journal)) {
        keepPrivacyFenceClosed()
        return false
    }
    dispatch()
    return true
}

internal fun dispatchAccountDeletionWorkerStage(
    stateMachine: AccountDeletionStateMachine,
    attempt: AccountDeletionWorkerAttempt,
    keepPrivacyFenceClosed: () -> Unit,
    dispatch: () -> Unit,
): Boolean {
    if (!stateMachine.workerAttemptAllowed(attempt)) {
        keepPrivacyFenceClosed()
        return false
    }
    dispatch()
    return true
}

internal enum class AccountDeletionTerminalMarkerCleanupResult {
    ALREADY_ABSENT,
    REMOVED,
    FAILED,
}

internal class AccountDeletionTerminalCleanupAttempt internal constructor(
    internal val gatewayOrigin: String,
    internal val installationId: String,
    internal val requestId: String,
    internal val receiptSha256: String,
    internal val generation: Long,
)

/** UI-thread reset gate completed only by a matching durable cleanup callback. */
internal class AccountDeletionTerminalCleanupCoordinator {
    private val lock = Any()
    private var generation = 0L
    private var activeAttempt: AccountDeletionTerminalCleanupAttempt? = null
    private var resetEnabledAttempt: AccountDeletionTerminalCleanupAttempt? = null

    fun begin(
        journal: AccountDeletionJournal,
    ): AccountDeletionTerminalCleanupAttempt? = synchronized(lock) {
        generation += 1L
        resetEnabledAttempt = null
        if (!isConfirmedTerminalAccountDeletion(journal)) {
            activeAttempt = null
            return@synchronized null
        }
        AccountDeletionTerminalCleanupAttempt(
            gatewayOrigin = journal.gatewayOrigin,
            installationId = journal.installationId,
            requestId = journal.requestId,
            receiptSha256 = journal.receiptSha256!!,
            generation = generation,
        ).also { activeAttempt = it }
    }

    /** Null means a superseded callback, false means matching cleanup failed. */
    fun complete(
        attempt: AccountDeletionTerminalCleanupAttempt,
        journal: AccountDeletionJournal,
        markerCleanup: AccountDeletionTerminalMarkerCleanupResult,
        actorBindingAbsent: Boolean,
    ): Boolean? = synchronized(lock) {
        if (attempt != activeAttempt) return@synchronized null
        activeAttempt = null
        val completed = attempt.matches(journal) &&
            isConfirmedTerminalAccountDeletion(journal) &&
            markerCleanup != AccountDeletionTerminalMarkerCleanupResult.FAILED &&
            actorBindingAbsent
        resetEnabledAttempt = attempt.takeIf { completed }
        completed
    }

    fun isResetEnabled(journal: AccountDeletionJournal): Boolean = synchronized(lock) {
        isConfirmedTerminalAccountDeletion(journal) &&
            resetEnabledAttempt?.matches(journal) == true
    }

    fun invalidate() = synchronized(lock) {
        generation += 1L
        activeAttempt = null
        resetEnabledAttempt = null
    }

    private fun AccountDeletionTerminalCleanupAttempt.matches(
        journal: AccountDeletionJournal,
    ): Boolean =
        gatewayOrigin == journal.gatewayOrigin &&
            installationId == journal.installationId &&
            requestId == journal.requestId &&
            receiptSha256 == journal.receiptSha256
}

private fun validDeviceEvidenceInstant(value: String): Boolean =
    validCanonicalWholeSecondUtcInstant(value)

private fun isTrustedRootOrigin(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
    if (!uri.rawPath.isNullOrEmpty()) return false
    return true
}

private val OPAQUE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{16,128}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
private val DEVICE_EVIDENCE_INSTANT =
    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
