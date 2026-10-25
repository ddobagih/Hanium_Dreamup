package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*

/** Real isolated marker/journal/actor authority and both actual progress/evidence owners. */
internal class DeletionProgressFixture : AutoCloseable {
    val base = DeletionRecoveryFixture()
    val operation = AccountDeletionOperationAuthority()
    val worker = ArrayDeque<Runnable>()
    val ui = ArrayDeque<() -> Unit>()
    val events = mutableListOf<String>()
    val packets = mutableListOf<DeviceDeletionEvidence>()
    var rejected = false
    var destroyed = false
    var startupDestroyed = false
    var runtimeFence = true
    var purgeResult = true
    var purgeCalls = 0
    var refreshCalls = 0
    val prepared = base.prepared()
    var record: AndroidAccountDeletionFallbackMarker.Record
        private set
    val journal get() = requireNotNull(base.machine.snapshotOrNull())
    private val executor = Executor { if (rejected) throw RejectedExecutionException(); worker.addLast(it) }
    init {
        assertTrue(base.store.prepareIntentAuthority().confirmed)
        val pending = (base.store.preparePendingJournal(requireNotNull(base.origin), base.session, prepared) as AccountDeletionPendingJournalPreparation.Ready).journal
        assertTrue(base.machine.restore(pending, base.lease))
        assertEquals(AccountDeletionApplyResult.APPLIED, base.machine.apply(requireNotNull(base.stages.begin(pending)), status(1)))
        assertTrue(base.store.persistJournal(journal))
        record = prepared.copy(recoveryActorId = null, phase = AndroidAccountDeletionFallbackMarker.Phase.ACCEPTED,
            acceptedAt = journal.acceptedAt, accountGeneration = journal.accountGeneration,
            tombstoneId = journal.tombstoneId, requestReceiptSha256 = journal.requestReceiptSha256, statusRevision = journal.serverRevision)
        assertTrue(base.marker.update(record))
        base.store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(record))
    }
    val actor = AccountDeletionRecoveryActorController(base.store, { base.preferences }, { true }, { base.origin }, { base.actor },
        object : AccountDeletionEnrollmentActorReader {
            override fun onboardingInitialized() = false
            override fun onboardingComplete(): Boolean = error("Unexpected onboarding read")
            override fun onboardingActorId(): String? = error("Unexpected onboarding read")
            override fun priorityPolicyInitialized(): Boolean = error("Unexpected priority read")
            override fun reporterActorId() = base.actor
            override fun priorityActorId(): String? = error("Unexpected priority read")
            override fun priorityAccountUnblocked(): Boolean = error("Unexpected priority read")
        }, { base.machine }, { base.lease }, base.stages, object : AccountDeletionRecoveryActorEffects {
            override fun clearGatewaySession(expectedSession: GatewayFieldSession, expectedActivityLease: AccountDeletionActivityLease) { events += "clear-session" }
            override fun invalidateSpeechStorage() { events += "invalidate-speech" }
            override fun failClosedIntegratedConsent() { events += "fail-consent" }
            override fun postUiRefresh() { events += "post-ui" }
            override fun status(value: String) { events += "status:$value" }
            override fun speak(value: String) { events += "speech:$value" }
        })
    private val network = object : AccountDeletionProgressNetwork {
        override fun refreshStatus(workerAttempt: AccountDeletionWorkerAttempt?) { refreshCalls++ }
        override fun startCall(callFactory: () -> CancellableNetworkCall<AccountDeletionStatus>, purpose: AccountDeletionCallPurpose) {
            assertEquals(AccountDeletionCallPurpose.EVIDENCE, purpose)
            assertEquals(AccountDeletionJournalCodec.encode(journal), base.preferences.getString("account_deletion_journal_v2", null))
            val evidenceFields = callFactory.javaClass.declaredFields.filter { it.type == DeviceDeletionEvidence::class.java }
            assertEquals(1, evidenceFields.size)
            val evidence = evidenceFields.single().apply { isAccessible = true }.get(callFactory) as DeviceDeletionEvidence
            assertEquals(journal.deviceEvidenceId, evidence.evidenceId)
            assertEquals(journal.deviceEvidenceSha256, evidence.evidenceSha256)
            val marker = (base.marker.read() as AndroidAccountDeletionFallbackMarker.State.Present).record
            assertEquals(marker.evidenceSha256, evidence.evidenceSha256)
            packets += evidence
        }
    }
    val evidence: AccountDeletionEvidenceController = AccountDeletionEvidenceController(base.store, base.stages, { base.machine }, { executor },
        object : AccountDeletionEvidenceQueries { override fun trustedOrigin() = base.origin },
        object : AccountDeletionEvidenceEffects {
            override fun keepPrivacyFenceClosed() { events += "keep-fence" }
            override fun invalidateSpeechStorage() { events += "invalidate-speech" }
            override fun failClosedIntegratedConsent() { events += "fail-consent" }
            override fun postUiRefresh() { events += "post-ui" }
        }, object : AccountDeletionEvidenceProgress {
            override fun scheduleAfterStatus(journal: AccountDeletionJournal) { progress.scheduleAfterStatus(journal) }
            override fun postFailure(reason: String, workerAttempt: AccountDeletionWorkerAttempt) { progress.postFailure(reason, workerAttempt) }
        }, network, AndroidPrivacyDeletionClient())
    val progress: AccountDeletionProgressController = AccountDeletionProgressController(operation, base.store, actor, base.stages, { base.machine }, { base.lease },
        { base.resetController }, { base.preferences }, { executor },
        object : AccountDeletionProgressCallbacks {
            override val activityToken = "progress_activity_0001"
            override val destroyed get() = this@DeletionProgressFixture.destroyed
            override val startupInspectionDestroyed get() = startupDestroyed
            override fun post(callback: () -> Unit): Boolean { ui.addLast(callback); return true }
        }, object : AccountDeletionProgressEffects {
            override fun applyRuntimeFence(): Boolean { events += "runtime-fence"; return runtimeFence }
            override fun keepPrivacyFenceClosed() { events += "keep-fence" }
            override fun postUiRefresh() { events += "post-ui" }
            override fun updateUi() { events += "ui" }
            override fun status(value: String) { events += "status:$value" }
            override fun invalidateSpeechStorage() { events += "invalidate-speech" }
            override fun failClosedIntegratedConsent() { events += "fail-consent" }
        }, network, evidence, AccountDeletionLocalDataPurge { purgeCalls++; purgeResult })

    fun installEvidence(result: String = "DELETED", resultAt: String = "2026-10-06T00:00:00Z"): DeviceDeletionEvidence {
        val raw = DeviceDeletionEvidence(journal.requestId, journal.tombstoneId!!, journal.requestReceiptSha256!!,
            journal.installationId, "device_evidence_fixture_0001", journal.clientRevision, journal.serverRevision, result, resultAt, "0".repeat(64))
        val value = raw.copy(evidenceSha256 = deviceDeletionEvidenceSha256(raw))
        val attempt = requireNotNull(base.stages.begin(journal))
        assertTrue(base.machine.recordDeviceEvidence(attempt, value.evidenceId, value.evidenceSha256, value.expectedStatusRevision, value.result, value.completedAt))
        assertTrue(base.store.persistJournal(journal))
        record = record.copy(phase = AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_PENDING,
            evidenceId = value.evidenceId, evidenceSha256 = value.evidenceSha256,
            evidenceExpectedStatusRevision = value.expectedStatusRevision, evidenceResult = value.result, evidenceCompletedAt = value.completedAt)
        assertTrue(base.marker.update(record))
        base.store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(record))
        return value
    }
    fun advanceStatus(revision: Long, complete: Boolean = false) {
        assertEquals(AccountDeletionApplyResult.APPLIED,
            base.machine.apply(requireNotNull(base.stages.begin(journal)), status(revision, complete), acknowledgesDeviceEvidence = complete))
        assertTrue(base.store.persistJournal(journal))
        if (complete) {
            record = record.copy(phase = AndroidAccountDeletionFallbackMarker.Phase.TERMINAL_RECEIPT,
                statusRevision = journal.serverRevision, completionReceiptSha256 = journal.receiptSha256)
            assertTrue(base.marker.update(record))
            base.store.observeFallbackState(AndroidAccountDeletionFallbackMarker.State.Present(record))
        }
    }
    fun status(revision: Long, complete: Boolean = false): AccountDeletionStatus {
        val accepted = Instant.parse(base.machine.snapshotOrNull()?.acceptedAt ?: "2026-10-06T00:00:00Z")
        val at = accepted.plusSeconds(revision).toString()
        val evidenceHash = if (complete) journal.deviceEvidenceSha256!! else null
        return AccountDeletionStatus(ACCOUNT_DELETION_STATUS_SCHEMA_VERSION, prepared.installationId, prepared.identity.requestId,
            revision, accepted.toString(), at, DeletionInventoryItem.entries.associateWith { item ->
                DeletionItemStatus(item, if (complete) DeletionItemState.COMPLETED else DeletionItemState.IN_PROGRESS,
                    accepted.plusMillis(item.maximumSlaMs).toString(), itemRevision = revision, updatedAt = at,
                    evidenceSha256 = evidenceHash, terminalAt = at.takeIf { complete })
            }, "c".repeat(64).takeIf { complete }, tombstoneId = "progress_tombstone_0001", requestReceiptSha256 = "a".repeat(64))
    }
    fun runWorker() = worker.removeFirst().run()
    fun runUi() = ui.removeFirst().invoke()
    override fun close() = base.close()
}
