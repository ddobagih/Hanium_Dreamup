package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.io.IOException
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionRecoveryStoreTest {
    @Test fun constructionAndAuthorityBindingDoNotOpenTheMarkerOrQueryInstallation() = DeletionRecoveryFixture().use { f ->
        assertEquals(0, f.markerFactoryCalls)
        assertEquals(0, f.installationQueries)
        assertEquals(AccountDeletionAggregateAuthorityState.UNAVAILABLE, f.store.authorityAtStartup.aggregateState)
        assertEquals(AndroidAccountDeletionFallbackMarker.State.Absent, f.store.startupFallbackState)
        assertFalse(f.store.fallbackPresentAtStartup)
        assertTrue(f.events.isEmpty())
    }

    @Test fun evidenceSignerActorComesOnlyFromTheExactDurableTupleAndPreservedActorBinding() = DeletionProgressFixture().use { f ->
        val evidence = f.installEvidence()
        val journal = f.journal
        val record = (f.base.marker.read() as AndroidAccountDeletionFallbackMarker.State.Present).record
        assertEquals(record.identity.actorHash, f.base.store.evidenceActorHashOrNull(journal, evidence.evidenceSha256))
        assertNull(f.base.store.evidenceActorHashOrNull(journal, "f".repeat(64)))
        assertNull(f.base.store.evidenceActorHashOrNull(journal.copy(accountGeneration = journal.accountGeneration!! + 1L), evidence.evidenceSha256))
        assertNull(f.base.store.evidenceActorHashOrNull(journal.copy(installationId = "different-installation"), evidence.evidenceSha256))
        assertTrue(f.base.preferences.edit().putString("account_deletion_actor_hash_v2", "f".repeat(64)).commit())
        assertNull(f.base.store.evidenceActorHashOrNull(journal, evidence.evidenceSha256))
    }

    @Test fun actorOrOriginMismatchIsRejectedBeforeAnyDurableStoreOrInstallationQuery() = DeletionRecoveryFixture().use { f ->
        assertSame(AccountDeletionRecoveryMarkerPreparation.Blocked, f.store.prepareRecoveryMarker(requireNotNull(f.origin), f.session, "other_actor"))
        assertSame(AccountDeletionRecoveryMarkerPreparation.Blocked, f.store.prepareRecoveryMarker("https://different.example.test", f.session, f.actor))
        assertEquals(0, f.markerFactoryCalls)
        assertEquals(0, f.installationQueries)
    }

    @Test fun preparedMarkerIsVerifiedEncryptedAndReusedOnlyForTheSameActorOriginInstallation() = DeletionRecoveryFixture().use { f ->
        val record = f.prepared()
        assertEquals(f.actor, record.recoveryActorId)
        assertEquals(sha(f.actor), record.identity.actorHash)
        assertEquals(AndroidAccountDeletionFallbackMarker.State.Present(record), f.marker.read())
        assertEquals(AndroidAccountDeletionFallbackMarker.State.Present(record), f.store.startupFallbackState)
        assertTrue(f.store.fallbackPresentAtStartup)
        val ciphertext = f.root.resolve(AndroidAccountDeletionFallbackMarker.MARKER_FILE_NAME).readText()
        assertFalse(ciphertext.contains(f.actor))
        assertFalse(ciphertext.contains(record.accessSecret))
        assertEquals(record, f.prepared())
        val other = GatewayFieldSession.verified(requireNotNull(f.origin), "actor_other_0001", "walksafe_field_session=v2.other", Long.MAX_VALUE)
        assertSame(AccountDeletionRecoveryMarkerPreparation.Blocked,
            f.store.prepareRecoveryMarker(requireNotNull(f.origin), other, other.actorId))
        assertEquals(AndroidAccountDeletionFallbackMarker.State.Present(record), f.marker.read())
    }

    @Test fun presentReadbackWithFailedDirectorySyncRemainsRetryableAndDoesNotClaimDurability() =
        DeletionRecoveryFixture(AndroidAccountDeletionFallbackMarker.Operations(syncDirectory = { throw IOException("controlled sync failure") })).use { f ->
            val result = f.store.prepareRecoveryMarker(requireNotNull(f.origin), f.session, f.actor)
            assertTrue(result is AccountDeletionRecoveryMarkerPreparation.RetryablePresent)
            val record = (result as AccountDeletionRecoveryMarkerPreparation.RetryablePresent).record
            assertEquals(AndroidAccountDeletionFallbackMarker.State.Present(record), f.marker.read())
            assertEquals(AndroidAccountDeletionFallbackMarker.State.Absent, f.store.startupFallbackState)
            assertFalse(f.store.fallbackPresentAtStartup)
        }

    @Test fun pendingJournalIsCommittedReadBackAndCanonicallyReusedWithoutAnotherWrite() = DeletionRecoveryFixture().use { f ->
        val marker = f.prepared()
        val ready = f.store.preparePendingJournal(requireNotNull(f.origin), f.session, marker) as AccountDeletionPendingJournalPreparation.Ready
        val raw = f.preferences.getString("account_deletion_journal_v2", null)
        assertEquals(AccountDeletionJournalCodec.encode(ready.journal), raw)
        assertTrue(f.preferences.getBoolean("account_deletion_fail_closed_v1", false))
        assertEquals(marker.identity.actorHash, f.preferences.getString("account_deletion_actor_hash_v2", null))
        assertFalse(f.preferences.contains("account_deletion_fail_closed_reason_v1"))
        assertFalse(f.disk.values.toString().contains(marker.identity.actorHash))
        val writes = f.disk.commitCount
        assertEquals(ready, f.store.preparePendingJournal(requireNotNull(f.origin), f.session, marker))
        assertEquals(writes, f.disk.commitCount)
    }

    @Test fun conflictingActorHashDoesNotOverwriteAnExistingCanonicalJournal() = DeletionRecoveryFixture().use { f ->
        val marker = f.prepared()
        val ready = f.store.preparePendingJournal(requireNotNull(f.origin), f.session, marker) as AccountDeletionPendingJournalPreparation.Ready
        assertTrue(f.preferences.edit().putString("account_deletion_actor_hash_v2", "f".repeat(64)).commit())
        val writes = f.disk.commitCount
        assertSame(AccountDeletionPendingJournalPreparation.Conflict, f.store.preparePendingJournal(requireNotNull(f.origin), f.session, marker))
        assertEquals(AccountDeletionJournalCodec.encode(ready.journal), f.preferences.getString("account_deletion_journal_v2", null))
        assertEquals(writes, f.disk.commitCount)
    }

    @Test fun noncanonicalOrTerminalJournalCannotBeReusedAsPending() = DeletionRecoveryFixture().use { f ->
        val marker = f.prepared()
        val ready = f.store.preparePendingJournal(requireNotNull(f.origin), f.session, marker) as AccountDeletionPendingJournalPreparation.Ready
        assertTrue(f.preferences.edit().putString("account_deletion_journal_v2", " " + AccountDeletionJournalCodec.encode(ready.journal)).commit())
        assertSame(AccountDeletionPendingJournalPreparation.Conflict, f.store.preparePendingJournal(requireNotNull(f.origin), f.session, marker))
        assertTrue(f.preferences.edit().putString("account_deletion_journal_v2", AccountDeletionJournalCodec.encode(ready.journal))
            .putString("account_deletion_fail_closed_reason_v1", "existing_terminal_reason").commit())
        assertSame(AccountDeletionPendingJournalPreparation.Conflict, f.store.preparePendingJournal(requireNotNull(f.origin), f.session, marker))
    }

    @Test fun pendingCommitFailureIsRetryAndNeverReturnsReady() = DeletionRecoveryFixture().use { f ->
        val marker = f.prepared()
        f.disk.commitsSucceed = false
        assertSame(AccountDeletionPendingJournalPreparation.Retry, f.store.preparePendingJournal(requireNotNull(f.origin), f.session, marker))
    }

    @Test fun preparedAuthorityRecoveryBlocksSpeechBeforeConfirmingBothOriginalAuthorities() = DeletionRecoveryFixture().use { f ->
        f.prepared()
        val result = f.store.prepareIntentAuthority()
        assertEquals(listOf("speech-block"), f.events)
        assertTrue(result.confirmed)
        assertTrue(result.retryable)
        assertNull(result.failureReason)
        assertEquals(AccountDeletionIntentAuthorityState.Confirmed, f.store.authorityAtStartup.fileState)
        assertEquals(AccountDeletionIntentFenceState.PRESENT, f.store.intentFence.state())
    }

    @Test fun existingFailClosedAuthorityKeepsItsDurableReasonAndCannotReconfirm() = DeletionRecoveryFixture().use { f ->
        assertTrue(f.store.dualAuthority.failClosed("durable_original_reason").durableSuccess)
        val result = f.store.prepareIntentAuthority()
        assertFalse(result.confirmed)
        assertFalse(result.retryable)
        assertEquals("durable_original_reason", result.failureReason)
        assertEquals(listOf("speech-block"), f.events)
    }

    @Test fun startupPreparedMarkerAllowsOnlyExactOriginAndKeepsTheRuntimeFenceClosed() = DeletionRecoveryFixture().use { f ->
        val marker = f.prepared()
        f.store.readFallbackAtStartup()
        f.store.reconcileFallbackAtStartup()
        assertEquals(listOf("runtime-fence"), f.events)
        assertFalse(f.store.remoteResumeBlocked)
        assertEquals(marker, f.store.preparedRecoveryRecordOrNull())
        f.origin = "https://other.example.test"
        assertNull(f.store.preparedRecoveryCandidateOrNull())
        f.store.reconcileFallbackAtStartup()
        assertTrue(f.store.remoteResumeBlocked)
    }

    @Test fun staleActivityCannotPerformAuthorityMigrationOrTerminalDurableState() = DeletionRecoveryFixture().use { f ->
        f.callbackAllowed = false
        assertFalse(f.store.reconcileAuthorityAfterJournalRestore())
        f.machine.attachActivity()
        assertFalse(f.store.forceAuthorityFailClosed("stale_attempt"))
        assertEquals(AccountDeletionIntentAuthorityState.Absent, f.store.dualAuthority.startupState().fileState)
        assertFalse(f.preferences.contains("account_deletion_fail_closed_reason_v1"))
        assertTrue(f.events.isEmpty())
    }

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
