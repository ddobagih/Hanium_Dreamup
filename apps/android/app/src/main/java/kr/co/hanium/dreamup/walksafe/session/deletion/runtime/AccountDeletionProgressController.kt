package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.report.dispatchAndroidAccountDeletionResume
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.AccountDeletionInstallationResetController

/** Owns local-purge admission and PREPARED through TERMINAL_RECEIPT marker progress. */
internal class AccountDeletionProgressController(
    private val operation: AccountDeletionOperationAuthority,
    private val store: AccountDeletionRecoveryStore,
    private val actor: AccountDeletionRecoveryActorController,
    private val stages: AccountDeletionWorkerStages,
    private val stateMachine: () -> AccountDeletionStateMachine,
    private val activityLease: () -> AccountDeletionActivityLease,
    private val resetController: () -> AccountDeletionInstallationResetController,
    private val sensitivePreferences: () -> AndroidSensitivePreferenceStore,
    private val cleanupExecutor: () -> Executor,
    private val callbacks: AccountDeletionProgressCallbacks,
    private val effects: AccountDeletionProgressEffects,
    private val network: AccountDeletionProgressNetwork,
    private val evidenceController: AccountDeletionProgressEvidence,
    private val purge: AccountDeletionLocalDataPurge,
) : AccountDeletionEvidenceProgress {
    private var localPurgeInFlightRequestId: String? = null
    val localPurgeRequestId: String? get() = localPurgeInFlightRequestId
    private val sensitivePrefs get() = sensitivePreferences()
    fun scheduleActorBindingCleanup(
        journal: AccountDeletionJournal,
    ) {
        val workerAttempt = stages.begin(journal) ?: return
        val cleanupAttempt =
            resetController().terminalCleanup.begin(journal)
        if (cleanupAttempt == null) {
            postFailure(
                "terminal_marker_cleanup",
                workerAttempt,
            )
            return
        }
        effects.postUiRefresh()
        try {
            cleanupExecutor().execute {
                if (!stages.allowed(workerAttempt)) return@execute
                var actorHashReadSucceeded = false
                var actorHash: String? = null
                var actorBindingPresent = true
                stages.durable(workerAttempt) {
                    val read = runCatching {
                        sensitivePrefs.getString(
                            PREF_ACCOUNT_DELETION_ACTOR_HASH,
                            null,
                        )
                    }
                    actorHashReadSucceeded = read.isSuccess
                    actorHash = read.getOrNull()
                    actorBindingPresent = runCatching {
                        sensitivePrefs.contains(
                            PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        )
                    }.getOrDefault(true)
                    true
                } ?: return@execute
                val markerCleanup = if (!actorHashReadSucceeded) {
                    AccountDeletionTerminalMarkerCleanupResult.FAILED
                } else {
                    var markerState: AndroidAccountDeletionFallbackMarker.State =
                        AndroidAccountDeletionFallbackMarker.State.Unavailable
                    stages.durable(workerAttempt) {
                        markerState = store.fallbackMarker.read()
                        true
                    } ?: return@execute
                    when (val state = markerState) {
                        is AndroidAccountDeletionFallbackMarker.State.Absent ->
                            AccountDeletionTerminalMarkerCleanupResult.ALREADY_ABSENT
                        is AndroidAccountDeletionFallbackMarker.State.Present -> {
                            val identity = actorHash?.let {
                                AndroidAccountDeletionFallbackMarker.Identity(
                                    requestId = journal.requestId,
                                    actorHash = it,
                                )
                            }
                            val removed = if (
                                identity != null && state.identity == identity
                            ) {
                                stages.terminalCleanup(workerAttempt) {
                                    store.fallbackMarker.clear(identity) &&
                                        store.fallbackMarker.read() is
                                        AndroidAccountDeletionFallbackMarker.State.Absent
                                } ?: return@execute
                            } else {
                                false
                            }
                            if (removed) {
                                AccountDeletionTerminalMarkerCleanupResult.REMOVED
                            } else {
                                AccountDeletionTerminalMarkerCleanupResult.FAILED
                            }
                        }
                        is AndroidAccountDeletionFallbackMarker.State.Corrupt,
                        is AndroidAccountDeletionFallbackMarker.State.Unavailable,
                        -> AccountDeletionTerminalMarkerCleanupResult.FAILED
                    }
                }
                val actorBindingAbsent =
                    markerCleanup != AccountDeletionTerminalMarkerCleanupResult.FAILED &&
                        if (actorHash == null) {
                            !actorBindingPresent
                        } else {
                            stages.terminalCleanup(workerAttempt) {
                                sensitivePrefs.edit()
                                    .remove(PREF_ACCOUNT_DELETION_ACTOR_HASH)
                                    .commit() &&
                                    runCatching {
                                        !sensitivePrefs.contains(
                                            PREF_ACCOUNT_DELETION_ACTOR_HASH,
                                        )
                                    }.getOrDefault(false)
                            } ?: return@execute
                        }
                if (!stages.allowed(workerAttempt)) return@execute
                val activityToken = callbacks.activityToken
                callbacks.post {
                    if (
                        callbacks.destroyed ||
                        activityToken != callbacks.activityToken ||
                        !stages.allowed(workerAttempt)
                    ) return@post
                    val current = stateMachine().snapshotOrNull()
                        ?: return@post
                    when (
                        resetController().terminalCleanup.complete(
                            attempt = cleanupAttempt,
                            journal = current,
                            markerCleanup = markerCleanup,
                            actorBindingAbsent = actorBindingAbsent,
                        )
                    ) {
                        null -> return@post
                        false -> {
                            postFailure(
                                "terminal_marker_cleanup",
                                workerAttempt,
                            )
                            return@post
                        }
                        true -> effects.updateUi()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            postFailure(
                "cleanup_executor_rejected",
                workerAttempt,
            )
        }
    }

    fun applyFenceAndPurgeLocal() {
        if (!effects.applyRuntimeFence()) {
            effects.status("accountDeletion=blocked:position_field_purge")
            effects.updateUi()
            return
        }
        resumeFromMarker()
    }

    override fun scheduleAfterStatus(
        journal: AccountDeletionJournal,
    ) {
        val workerAttempt = stages.begin(journal) ?: return
        try {
            cleanupExecutor().execute {
                if (!stages.allowed(workerAttempt)) return@execute
                var state: AndroidAccountDeletionFallbackMarker.State =
                    AndroidAccountDeletionFallbackMarker.State.Unavailable
                stages.durable(workerAttempt) {
                    state = store.fallbackMarker.read()
                    true
                } ?: return@execute
                val present = state as? AndroidAccountDeletionFallbackMarker.State.Present
                val current = present?.record
                if (
                    current == null ||
                    current.identity.requestId != journal.requestId ||
                    current.installationId != journal.installationId ||
                    journal.acceptedAt == null ||
                    journal.accountGeneration == null ||
                    journal.tombstoneId == null ||
                    journal.requestReceiptSha256 == null
                ) {
                    postFailure(
                        "fallback_status_linkage",
                        workerAttempt,
                    )
                    return@execute
                }
                val nextPhase = when {
                    journal.phase == AccountDeletionPhase.COMPLETED &&
                        journal.deviceEvidenceAcknowledged ->
                        AndroidAccountDeletionFallbackMarker.Phase.TERMINAL_RECEIPT
                    journal.deviceEvidenceAcknowledged ->
                        AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_ACKED
                    current.phase < AndroidAccountDeletionFallbackMarker.Phase.ACCEPTED ->
                        AndroidAccountDeletionFallbackMarker.Phase.ACCEPTED
                    else -> current.phase
                }
                val next = current.copy(
                    recoveryActorId = null,
                    phase = nextPhase,
                    acceptedAt = journal.acceptedAt,
                    accountGeneration = journal.accountGeneration,
                    tombstoneId = journal.tombstoneId,
                    requestReceiptSha256 = journal.requestReceiptSha256,
                    statusRevision = journal.serverRevision,
                    completionReceiptSha256 = journal.receiptSha256,
                )
                val markerUpdated =
                    stages.durable(workerAttempt) {
                        store.fallbackMarker.update(next)
                    } ?: return@execute
                if (!markerUpdated) {
                    postFailure(
                        "fallback_status_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(next))
                if (!stages.allowed(workerAttempt)) return@execute
                val activityToken = callbacks.activityToken
                callbacks.post {
                    if (
                        callbacks.destroyed ||
                        activityToken != callbacks.activityToken ||
                        !stages.allowed(workerAttempt)
                    ) return@post
                    // This response already contains the acknowledged server status.
                    // Startup resume and explicit refresh may fetch again; this callback must not.
                    if (next.phase == AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_ACKED) {
                        effects.updateUi()
                    } else {
                        continueFromMarker(next, workerAttempt)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            postFailure(
                "cleanup_executor_rejected",
                workerAttempt,
            )
        }
    }

    fun resumeFromMarker() {
        if (operation.requestInFlight || localPurgeInFlightRequestId != null) {
            return
        }
        val restoredJournal = stateMachine().snapshotOrNull()
        if (restoredJournal?.phase == AccountDeletionPhase.FAIL_CLOSED) {
            effects.applyRuntimeFence()
            effects.updateUi()
            return
        }
        val workerAttempt = restoredJournal
            ?.let(stages::begin)
            ?: return
        try {
            cleanupExecutor().execute {
                if (!stages.allowed(workerAttempt)) return@execute
                var markerState: AndroidAccountDeletionFallbackMarker.State =
                    AndroidAccountDeletionFallbackMarker.State.Unavailable
                stages.durable(workerAttempt) {
                    markerState = store.fallbackMarker.read()
                    true
                } ?: return@execute
                when (val state = markerState) {
                    is AndroidAccountDeletionFallbackMarker.State.Present ->
                        continueFromMarker(state.record, workerAttempt)
                    is AndroidAccountDeletionFallbackMarker.State.Absent -> {
                        if (resetController().isConfirmedJournal(restoredJournal)) {
                            scheduleActorBindingCleanup(restoredJournal)
                        } else {
                            postFailure(
                                "fallback_marker_missing",
                                workerAttempt,
                            )
                        }
                    }
                    is AndroidAccountDeletionFallbackMarker.State.Corrupt,
                    is AndroidAccountDeletionFallbackMarker.State.Unavailable,
                    -> postFailure(
                        "fallback_marker_unavailable",
                        workerAttempt,
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            effects.keepPrivacyFenceClosed()
        }
    }

    fun continueFromMarker(
        record: AndroidAccountDeletionFallbackMarker.Record,
        workerAttempt: AccountDeletionWorkerAttempt? = null,
    ) {
        val journal = stateMachine().snapshotOrNull() ?: return
        val currentAttempt =
            workerAttempt ?: stages.begin(journal) ?: return
        if (!stages.allowed(currentAttempt)) return
        val identityMatches =
            record.identity.requestId == journal.requestId &&
            record.installationId == journal.installationId &&
            record.gatewayOrigin == journal.gatewayOrigin

        val dispatched = dispatchAndroidAccountDeletionResume(
            journal = journal,
            markerPhase = record.phase,
            identityMatches = identityMatches,
            rejectIdentityConflict = {
                postFailure(
                    "fallback_identity_conflict",
                    currentAttempt,
                )
            },
            replayPreparedRequest = {
                network.refreshStatus(currentAttempt)
            },
            purgeAcceptedLocalData = {
                scheduleAcceptedLocalPurge(record, journal)
            },
            rejectMissingLocalEvidence = {
                postFailure(
                    "local_evidence_missing",
                    currentAttempt,
                )
            },
            submitPendingEvidence = {
                evidenceController.submitPending(record, journal)
            },
            fetchAcknowledgedStatus = {
                network.refreshStatus(currentAttempt)
            },
            cleanupTerminalBinding = {
                scheduleActorBindingCleanup(journal)
            },
        )
        if (!dispatched) {
            effects.applyRuntimeFence()
            effects.postUiRefresh()
        }
    }

    fun scheduleAcceptedLocalPurge(
        record: AndroidAccountDeletionFallbackMarker.Record,
        journal: AccountDeletionJournal,
    ) {
        if (localPurgeInFlightRequestId != null) return
        val workerAttempt = stages.begin(journal) ?: return
        localPurgeInFlightRequestId = journal.requestId
        try {
            cleanupExecutor().execute {
                if (!stages.allowed(workerAttempt)) return@execute
                val purgeSucceeded =
                    stages.durable(workerAttempt) {
                        purge.purgeLocalDataOnWorker()
                    } ?: return@execute
                val completedAt = java.time.Instant.now()
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    .toString()
                val evidenceWithoutHash = DeviceDeletionEvidence(
                    requestId = journal.requestId,
                    tombstoneId = journal.tombstoneId!!,
                    requestReceiptSha256 = journal.requestReceiptSha256!!,
                    installationId = journal.installationId,
                    evidenceId = evidenceController.newEvidenceId(),
                    clientRevision = journal.clientRevision,
                    expectedStatusRevision = journal.serverRevision,
                    result = if (purgeSucceeded) "DELETED" else "FAILED",
                    completedAt = completedAt,
                    evidenceSha256 = "0".repeat(64),
                )
                val evidenceSha256 = deviceDeletionEvidenceSha256(evidenceWithoutHash)
                val evidence = evidenceWithoutHash.copy(evidenceSha256 = evidenceSha256)
                val next = record.copy(
                    phase = AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_PENDING,
                    evidenceId = evidence.evidenceId,
                    evidenceSha256 = evidence.evidenceSha256,
                    evidenceExpectedStatusRevision = evidence.expectedStatusRevision,
                    evidenceResult = evidence.result,
                    evidenceCompletedAt = evidence.completedAt,
                )
                val markerUpdated =
                    stages.durable(workerAttempt) {
                        store.fallbackMarker.update(next)
                    } ?: return@execute
                localPurgeInFlightRequestId = null
                if (
                    !stages.allowed(workerAttempt) ||
                    !markerUpdated ||
                    !stateMachine().recordDeviceEvidence(
                        workerAttempt,
                        evidenceId = evidence.evidenceId,
                        evidenceSha256 = evidence.evidenceSha256,
                        expectedStatusRevision = evidence.expectedStatusRevision,
                        result = evidence.result,
                        completedAt = evidence.completedAt,
                    )
                ) {
                    postFailure(
                        "device_evidence_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                val current = stateMachine().snapshotOrNull()
                    ?: return@execute
                val journalPersisted =
                    stages.durable(workerAttempt) {
                        it == current && store.persistJournal(current)
                    } ?: return@execute
                if (!journalPersisted) {
                    postFailure(
                        "device_evidence_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                evidenceController.submitPendingAllowed(next, current)
            }
        } catch (_: RejectedExecutionException) {
            localPurgeInFlightRequestId = null
            postFailure(
                "cleanup_executor_rejected",
                workerAttempt,
            )
        }
    }

    override fun postFailure(
        reason: String,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        if (!stages.allowed(workerAttempt)) return
        try {
            cleanupExecutor().execute {
                if (!stages.allowed(workerAttempt)) return@execute
                stages.terminal(
                    attempt = workerAttempt,
                    errorCode = reason,
                ) ?: return@execute
                resetController().terminalCleanup.invalidate()
                effects.invalidateSpeechStorage()
                effects.failClosedIntegratedConsent()
                store.setRemoteResumeBlocked(true)
                callbacks.post {
                    if (
                        callbacks.startupInspectionDestroyed ||
                        !stateMachine().activityLeaseIsCurrent(
                            activityLease(),
                        )
                    ) return@post
                    effects.status("accountDeletion=fail_closed:$reason")
                    effects.updateUi()
                }
            }
        } catch (_: RejectedExecutionException) {
            effects.keepPrivacyFenceClosed()
        }
    }

    fun postStartupFailure(reason: String) {
        val expectedLease = activityLease()
        val expectedJournal = stateMachine().snapshotOrNull()
        try {
            cleanupExecutor().execute {
                if (
                    !stateMachine().activityLeaseIsCurrent(
                        expectedLease,
                    ) ||
                    stateMachine().snapshotOrNull() !== expectedJournal ||
                    !stateMachine().failClosed(expectedLease, reason)
                ) return@execute
                val terminalJournal = stateMachine().snapshotOrNull()
                if (
                    stateMachine().snapshotOrNull() !== terminalJournal ||
                    stateMachine().failureReasonOrNull() != reason
                ) return@execute
                if (
                    !resetController().persistTerminalState(
                        terminalJournal,
                        reason,
                    )
                ) {
                    resetController().handleTerminalPersistenceFailure(reason)
                    return@execute
                }
                resetController().terminalCleanup.invalidate()
                effects.invalidateSpeechStorage()
                effects.failClosedIntegratedConsent()
                store.setRemoteResumeBlocked(true)
                effects.postUiRefresh()
            }
        } catch (_: RejectedExecutionException) {
            effects.keepPrivacyFenceClosed()
        }
    }

    fun startFallbackRecoveryAfterRestore() {
        val restoredJournal = stateMachine().snapshotOrNull()
        if (restoredJournal?.phase == AccountDeletionPhase.FAIL_CLOSED) {
            effects.applyRuntimeFence()
            effects.updateUi()
            return
        }
        if (restoredJournal != null && restoredJournal.serverRevision > 0L) {
            actor.completeRev0ReauthenticationAfterAcceptance(restoredJournal)
        }
        when (val state = store.startupFallbackState) {
            is AndroidAccountDeletionFallbackMarker.State.Absent -> Unit
            is AndroidAccountDeletionFallbackMarker.State.Present -> {
                if (
                    stateMachine()
                        .durableConfirmationRecoveryRequired() &&
                    state.record.phase ==
                    AndroidAccountDeletionFallbackMarker.Phase.PREPARED &&
                    !store.remoteResumeBlocked
                ) {
                    effects.applyRuntimeFence()
                    effects.updateUi()
                    return
                }
                val persistedActorHash =
                    sensitivePrefs.getString(
                        PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        null,
                    )
                val exactTuple =
                    restoredJournal?.requestId == state.identity.requestId &&
                        persistedActorHash == state.identity.actorHash &&
                        !store.remoteResumeBlocked
                if (exactTuple) {
                    continueFromMarker(state.record)
                } else {
                    val workerAttempt = restoredJournal
                        ?.let(stages::begin)
                    if (workerAttempt != null) {
                        postFailure(
                            "fallback_identity_conflict",
                            workerAttempt,
                        )
                    } else {
                        postStartupFailure(
                            "fallback_identity_conflict",
                        )
                    }
                }
            }
            is AndroidAccountDeletionFallbackMarker.State.Corrupt,
            is AndroidAccountDeletionFallbackMarker.State.Unavailable,
            -> {
                store.setRemoteResumeBlocked(true)
                effects.applyRuntimeFence()
                effects.status(
                    "accountDeletion=recovery_blocked:fallback_marker_storage",
                )
                effects.updateUi()
            }
        }
    }

    private companion object { const val PREF_ACCOUNT_DELETION_ACTOR_HASH = "account_deletion_actor_hash_v2" }
}
internal interface AccountDeletionProgressCallbacks : AccountDeletionCleanupCallbacks {
    val startupInspectionDestroyed: Boolean
}
internal interface AccountDeletionProgressEffects {
    fun applyRuntimeFence(): Boolean
    fun keepPrivacyFenceClosed()
    fun postUiRefresh()
    fun updateUi()
    fun status(value: String)
    fun invalidateSpeechStorage()
    fun failClosedIntegratedConsent()
}
internal interface AccountDeletionProgressNetwork : AccountDeletionNetworkEntry {
    fun refreshStatus(workerAttempt: AccountDeletionWorkerAttempt?)
}
internal fun interface AccountDeletionLocalDataPurge { fun purgeLocalDataOnWorker(): Boolean }
