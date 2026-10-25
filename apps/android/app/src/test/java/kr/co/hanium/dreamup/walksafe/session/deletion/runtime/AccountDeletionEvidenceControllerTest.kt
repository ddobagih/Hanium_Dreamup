package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.network.deviceDeletionEvidenceSha256
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionEvidenceControllerTest {
    @Test fun rebasePreservesFailedResultAndCompletionTimeWhileIssuingNewIdentityAndHash() = DeletionProgressFixture().use { f ->
        val original = f.installEvidence("FAILED")
        f.advanceStatus(2)
        f.evidence.scheduleRebase(f.journal)
        f.runWorker()
        val packet = f.packets.single()
        assertEquals(original.result, packet.result)
        assertEquals(original.completedAt, packet.completedAt)
        assertNotEquals(original.evidenceId, packet.evidenceId)
        assertNotEquals(original.evidenceSha256, packet.evidenceSha256)
        assertEquals(2L, packet.expectedStatusRevision)
        assertEquals(deviceDeletionEvidenceSha256(packet.copy(evidenceSha256 = "0".repeat(64))), packet.evidenceSha256)
        assertEquals(packet.evidenceId, f.journal.deviceEvidenceId)
        assertEquals(AccountDeletionJournalCodec.encode(f.journal), f.base.preferences.getString("account_deletion_journal_v2", null))
    }

    @Test fun equalOrOlderRevisionCannotRebaseExistingPendingEvidence() = DeletionProgressFixture().use { f ->
        val original = f.installEvidence()
        f.evidence.scheduleRebase(f.journal)
        f.runWorker()
        assertTrue(f.packets.isEmpty())
        assertEquals(original.evidenceId, f.journal.deviceEvidenceId)
        f.runWorker()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.base.machine.phase())
        assertEquals("device_evidence_rebase_linkage", f.base.machine.failureReasonOrNull())
    }

    @Test fun exactPendingTupleIsReplayedWithoutChangingItsHashOrResult() = DeletionProgressFixture().use { f ->
        val original = f.installEvidence()
        val before = f.journal
        f.evidence.submitPendingAllowed(f.record, f.journal)
        assertEquals(before, f.journal)
        assertEquals(original, f.packets.single())
        assertTrue(f.worker.isEmpty())
    }

    @Test fun alteredResultForTheSameEvidenceTupleCannotBeSubmitted() = DeletionProgressFixture().use { f ->
        f.installEvidence()
        val tampered = f.record.copy(evidenceResult = "FAILED")
        f.evidence.submitPendingAllowed(tampered, f.journal)
        assertTrue(f.packets.isEmpty())
        f.runWorker()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.base.machine.phase())
        assertEquals("device_evidence_linkage", f.base.machine.failureReasonOrNull())
    }

    @Test fun missingTrustedOriginDoesNotSubmitOtherwiseExactEvidence() = DeletionProgressFixture().use { f ->
        f.installEvidence()
        f.base.origin = null
        f.evidence.submitPendingAllowed(f.record, f.journal)
        assertTrue(f.packets.isEmpty())
        f.runWorker()
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.base.machine.phase())
        assertEquals("device_evidence_linkage", f.base.machine.failureReasonOrNull())
    }

    @Test fun retiredActivityCannotPublishRebasedMarkerJournalOrPacket() = DeletionProgressFixture().use { f ->
        f.installEvidence(); f.advanceStatus(2)
        val beforeMarker = f.base.marker.read()
        val beforeJournal = f.base.preferences.getString("account_deletion_journal_v2", null)
        f.evidence.scheduleRebase(f.journal)
        f.base.machine.detachActivity(f.base.lease)
        f.runWorker()
        assertEquals(beforeMarker, f.base.marker.read())
        assertEquals(beforeJournal, f.base.preferences.getString("account_deletion_journal_v2", null))
        assertTrue(f.packets.isEmpty())
    }

    @Test fun malformed409StatusDoesNotApplyOrScheduleAnyEvidenceChanges() = DeletionProgressFixture().use { f ->
        f.installEvidence()
        val before = f.journal
        assertFalse(f.evidence.handleRevisionConflict("{\"bad\":true}", requireNotNull(f.base.stages.begin(f.journal))))
        assertSame(before, f.journal)
        assertTrue(f.worker.isEmpty() && f.packets.isEmpty())
    }
}
