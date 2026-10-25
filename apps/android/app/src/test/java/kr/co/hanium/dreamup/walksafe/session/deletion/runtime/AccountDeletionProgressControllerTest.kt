package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.network.deviceDeletionEvidenceSha256
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionProgressControllerTest {
    @Test fun acknowledgedStatusPersistsItsMarkerWithoutPollingAgainAndStartupStillRefreshesOnce() {
        for (revision in listOf(1L, 2L)) DeletionProgressFixture().use { f ->
            f.installEvidence()
            val received = f.status(revision)
            val attempt = requireNotNull(f.base.stages.begin(f.journal))
            assertEquals(if (revision == 1L) AccountDeletionApplyResult.DUPLICATE else AccountDeletionApplyResult.APPLIED,
                f.base.machine.apply(attempt, received, acknowledgesDeviceEvidence = true))
            assertTrue(f.base.store.persistJournal(f.journal))
            assertTrue(f.journal.deviceEvidenceAcknowledged)
            f.progress.scheduleAfterStatus(f.journal)
            f.runWorker()
            f.runUi()
            val marker = (f.base.marker.read() as AndroidAccountDeletionFallbackMarker.State.Present).record
            assertEquals(AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_ACKED, marker.phase)
            assertEquals(revision, marker.statusRevision)
            assertEquals(f.journal.deviceEvidenceSha256, marker.evidenceSha256)
            assertEquals(0, f.refreshCalls)
            assertEquals(listOf("ui"), f.events)
            assertTrue(f.worker.isEmpty() && f.ui.isEmpty() && f.packets.isEmpty())
            assertTrue(f.base.preferences.getBoolean("account_deletion_fail_closed_v1", false))

            f.progress.resumeFromMarker()
            f.runWorker()
            assertEquals(1, f.refreshCalls)
            assertTrue(f.worker.isEmpty() && f.ui.isEmpty())
        }
    }

    @Test fun constructorsKeepTheSinglePurgeSlotEmptyAndStartNoWorkers() = DeletionProgressFixture().use { f ->
        assertNull(f.progress.localPurgeRequestId)
        assertEquals(0, f.purgeCalls)
        assertTrue(f.worker.isEmpty() && f.ui.isEmpty())
        assertTrue(f.events.isEmpty() && f.packets.isEmpty())
    }

    @Test fun actualPurgeSuccessPersistsCanonicalEvidenceBeforeTheOnlySubmission() = DeletionProgressFixture().use { f ->
        f.progress.scheduleAcceptedLocalPurge(f.record, f.journal)
        assertEquals(f.journal.requestId, f.progress.localPurgeRequestId)
        assertTrue(f.packets.isEmpty())
        f.runWorker()
        assertNull(f.progress.localPurgeRequestId)
        assertEquals(1, f.purgeCalls)
        val evidence = f.packets.single()
        assertEquals("DELETED", evidence.result)
        assertEquals(deviceDeletionEvidenceSha256(evidence.copy(evidenceSha256 = "0".repeat(64))), evidence.evidenceSha256)
        assertEquals(evidence.evidenceId, f.journal.deviceEvidenceId)
        assertEquals(AccountDeletionJournalCodec.encode(f.journal), f.base.preferences.getString("account_deletion_journal_v2", null))
        assertEquals(AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_PENDING,
            (f.base.marker.read() as AndroidAccountDeletionFallbackMarker.State.Present).record.phase)
    }

    @Test fun oneFailedPurgeResultCanOnlyProduceFailedEvidence() = DeletionProgressFixture().use { f ->
        f.purgeResult = false
        f.progress.scheduleAcceptedLocalPurge(f.record, f.journal)
        f.runWorker()
        assertEquals(1, f.purgeCalls)
        assertEquals("FAILED", f.packets.single().result)
        assertEquals("FAILED", f.journal.deviceEvidenceResult)
        assertFalse(f.journal.deviceEvidenceAcknowledged)
        assertNull(f.progress.localPurgeRequestId)
    }

    @Test fun repeatedAcceptedDispatchCannotStartASecondLocalPurgeWhileItsRequestOwnsTheSlot() = DeletionProgressFixture().use { f ->
        f.progress.scheduleAcceptedLocalPurge(f.record, f.journal)
        f.progress.scheduleAcceptedLocalPurge(f.record, f.journal)
        assertEquals(1, f.worker.size)
        f.runWorker()
        assertEquals(1, f.purgeCalls)
        assertEquals(1, f.packets.size)
    }

    @Test fun retiredWorkerCannotPurgeOrInventDeviceEvidence() = DeletionProgressFixture().use { f ->
        val before = f.base.preferences.getString("account_deletion_journal_v2", null)
        f.progress.scheduleAcceptedLocalPurge(f.record, f.journal)
        f.base.machine.detachActivity(f.base.lease)
        f.runWorker()
        assertEquals(0, f.purgeCalls)
        assertTrue(f.packets.isEmpty())
        assertNull(f.journal.deviceEvidenceId)
        assertEquals(before, f.base.preferences.getString("account_deletion_journal_v2", null))
        // Preserve the original stale-worker retirement behavior; do not make a stale write.
        assertEquals(f.journal.requestId, f.progress.localPurgeRequestId)
    }

    @Test fun deniedPositionScopeFenceDoesNotStartPurgeOrResumeTheMarker() = DeletionProgressFixture().use { f ->
        f.runtimeFence = false
        f.progress.applyFenceAndPurgeLocal()
        assertEquals(0, f.purgeCalls)
        assertTrue(f.worker.isEmpty())
        assertTrue(f.events.contains("status:accountDeletion=blocked:position_field_purge"))
    }

    @Test fun destroyedActivityCannotContinueFromTheAcceptedStatusCallback() = DeletionProgressFixture().use { f ->
        f.progress.scheduleAfterStatus(f.journal)
        f.runWorker()
        assertEquals(1, f.ui.size)
        f.destroyed = true
        f.runUi()
        assertEquals(0, f.purgeCalls)
        assertTrue(f.worker.isEmpty() && f.packets.isEmpty())
    }

    @Test fun terminalMarkerAndActorBindingAreBothVerifiedAbsentBeforeResetIsEnabled() = DeletionProgressFixture().use { f ->
        f.installEvidence(); f.advanceStatus(2, complete = true)
        f.progress.scheduleActorBindingCleanup(f.journal)
        assertFalse(f.base.resetController.terminalCleanup.isResetEnabled(f.journal))
        f.runWorker()
        assertTrue(f.base.marker.read() is AndroidAccountDeletionFallbackMarker.State.Absent)
        assertFalse(f.base.preferences.contains("account_deletion_actor_hash_v2"))
        assertFalse(f.base.resetController.terminalCleanup.isResetEnabled(f.journal))
        f.runUi()
        assertTrue(f.base.resetController.terminalCleanup.isResetEnabled(f.journal))
    }

    @Test fun anAlreadyAbsentTerminalMarkerStillRequiresDurableActorBindingRemoval() = DeletionProgressFixture().use { f ->
        f.installEvidence(); f.advanceStatus(2, complete = true)
        assertTrue(f.base.marker.clear(f.record.identity))
        assertTrue(f.base.preferences.contains("account_deletion_actor_hash_v2"))
        f.progress.scheduleActorBindingCleanup(f.journal)
        f.runWorker()
        assertFalse(f.base.preferences.contains("account_deletion_actor_hash_v2"))
        f.runUi()
        assertTrue(f.base.resetController.terminalCleanup.isResetEnabled(f.journal))
    }

    @Test fun wrongActorHashCannotRemoveAnotherMarkerOrEnableReset() = DeletionProgressFixture().use { f ->
        f.installEvidence(); f.advanceStatus(2, complete = true)
        assertTrue(f.base.preferences.edit().putString("account_deletion_actor_hash_v2", "f".repeat(64)).commit())
        f.progress.scheduleActorBindingCleanup(f.journal)
        f.runWorker(); f.runUi()
        assertTrue(f.base.marker.read() is AndroidAccountDeletionFallbackMarker.State.Present)
        assertTrue(f.base.preferences.contains("account_deletion_actor_hash_v2"))
        assertFalse(f.base.resetController.terminalCleanup.isResetEnabled(f.journal))
        f.runWorker()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.base.machine.phase())
        assertEquals("terminal_marker_cleanup", f.base.machine.failureReasonOrNull())
    }

    @Test fun lateTerminalCleanupCallbackCannotEnableResetAfterActivityRetirement() = DeletionProgressFixture().use { f ->
        f.installEvidence(); f.advanceStatus(2, complete = true)
        f.progress.scheduleActorBindingCleanup(f.journal)
        f.runWorker()
        f.base.machine.detachActivity(f.base.lease)
        f.runUi()
        assertFalse(f.base.resetController.terminalCleanup.isResetEnabled(f.journal))
        assertFalse(f.events.contains("ui"))
    }

    @Test fun staleStartupFailureDoesNotOverwriteNewTerminalAuthority() = DeletionProgressFixture().use { f ->
        val before = f.base.preferences.getString("account_deletion_journal_v2", null)
        f.progress.postStartupFailure("obsolete_startup_failure")
        assertTrue(f.base.machine.enforceFailClosedAuthority(f.base.lease, "new_authority_reason"))
        f.runWorker()
        assertEquals("new_authority_reason", f.base.machine.failureReasonOrNull())
        assertEquals(before, f.base.preferences.getString("account_deletion_journal_v2", null))
        assertTrue(f.events.isEmpty())
    }
}
