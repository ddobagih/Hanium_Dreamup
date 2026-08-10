package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountDeletionResetCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun everyParticipantCrashBoundaryReplaysTheWholeResetAndAllowsFreshEnrollment() {
        repeat(6) { crashBeforeStep ->
            val journal = FakeJournal()
            val domain = FakeOldAccountDomain()
            val first = coordinator(journal, domain, crashBeforeStep)

            assertEquals(AccountDeletionResetResult.BLOCKED, first.beginAndReconcile())
            assertTrue(journal.state is AccountDeletionResetJournalState.Pending)

            val restarted = coordinator(journal, domain, crashBeforeStep = null)
            assertEquals(AccountDeletionResetResult.COMPLETED, restarted.reconcilePending())
            assertEquals(AccountDeletionResetJournalState.Absent, journal.state)
            assertFalse(domain.gatewayCiphertextPresent)
            assertFalse(domain.sensitiveCiphertextPresent)
            assertFalse(domain.plainAccountArtifactsPresent)
            assertFalse(domain.fieldCiphertextPresent)
            assertFalse(domain.legacyIntentPresent)
            assertTrue(domain.gatewayFreshGeneration > 0)
            assertTrue(domain.sensitiveFreshGeneration > 0)
            assertTrue(domain.fieldFreshGeneration > 0)
            assertTrue(domain.canEnrollFreshAccount())
        }
    }

    @Test
    fun crashAfterAllParticipantsBeforeJournalClearIsReplayed() {
        val journal = FakeJournal(failClearOnce = true)
        val domain = FakeOldAccountDomain()

        assertEquals(
            AccountDeletionResetResult.BLOCKED,
            coordinator(journal, domain, crashBeforeStep = null).beginAndReconcile(),
        )
        assertTrue(journal.state is AccountDeletionResetJournalState.Pending)

        assertEquals(
            AccountDeletionResetResult.COMPLETED,
            coordinator(journal, domain, crashBeforeStep = null).reconcilePending(),
        )
        assertTrue(domain.canEnrollFreshAccount())
        assertEquals(AccountDeletionResetJournalState.Absent, journal.state)
    }

    @Test
    fun failedFenceClearAfterFieldResetKeepsJournalAndRestartCompletesFenceClear() {
        val journal = FakeJournal()
        var fieldResetCount = 0
        var fencePresent = true
        var allowFenceClear = false

        fun coordinator(): AccountDeletionResetCoordinator =
            AccountDeletionResetCoordinator(
                journal = journal,
                storeLegacyIntent = { true },
                resetGateway = { true },
                resetSensitivePreferences = { true },
                resetPlainPreferences = { true },
                resetFieldStorage = {
                    fieldResetCount += 1
                    true
                },
                clearDeletionIntentFence = {
                    if (!allowFenceClear) {
                        false
                    } else {
                        fencePresent = false
                        true
                    }
                },
            )

        assertEquals(AccountDeletionResetResult.BLOCKED, coordinator().beginAndReconcile())
        assertEquals(
            AccountDeletionResetJournalState.Pending(AccountDeletionResetPhase.FIELD_RESET),
            journal.state,
        )
        assertEquals(1, fieldResetCount)
        assertTrue(fencePresent)

        allowFenceClear = true
        assertEquals(AccountDeletionResetResult.COMPLETED, coordinator().reconcilePending())
        assertEquals(AccountDeletionResetJournalState.Absent, journal.state)
        assertEquals(2, fieldResetCount)
        assertFalse(fencePresent)
    }

    @Test
    fun corruptJournalNeverRunsStartupActionsButTrustedConfirmedRetryRepairsRegularTemp() {
        val directory = temporaryFolder.newFolder("reset-journal-corrupt-temp")
        File(directory, "confirmed_account_deletion_reset_v1.journal.tmp")
            .writeText("partial")
        val journal = FileAccountDeletionResetJournal(directory)
        var actions = 0
        val coordinator = AccountDeletionResetCoordinator(
            journal = journal,
            storeLegacyIntent = { actions += 1; true },
            resetGateway = { actions += 1; true },
            resetSensitivePreferences = { actions += 1; true },
            resetPlainPreferences = { actions += 1; true },
            resetFieldStorage = { actions += 1; true },
            clearDeletionIntentFence = { actions += 1; true },
        )

        assertEquals(AccountDeletionResetResult.BLOCKED, coordinator.reconcilePending())
        assertEquals(0, actions)

        assertEquals(AccountDeletionResetResult.COMPLETED, coordinator.beginAndReconcile())
        assertEquals(6, actions)
        assertEquals(AccountDeletionResetJournalState.Absent, journal.read())
    }

    @Test
    fun validFinalJournalSurvivesAPartialTemporaryPhaseWrite() {
        val directory = temporaryFolder.newFolder("reset-journal-valid-final")
        val journal = FileAccountDeletionResetJournal(directory)
        assertTrue(journal.write(AccountDeletionResetPhase.GATEWAY_RESET))
        File(directory, "confirmed_account_deletion_reset_v1.journal.tmp")
            .writeText("partial")

        assertEquals(
            AccountDeletionResetJournalState.Pending(AccountDeletionResetPhase.GATEWAY_RESET),
            journal.read(),
        )
        val coordinator = AccountDeletionResetCoordinator(
            journal = journal,
            storeLegacyIntent = { true },
            resetGateway = { true },
            resetSensitivePreferences = { true },
            resetPlainPreferences = { true },
            resetFieldStorage = { true },
            clearDeletionIntentFence = { true },
        )
        assertEquals(AccountDeletionResetResult.COMPLETED, coordinator.reconcilePending())
    }

    @Test
    fun failedFinalDirectorySyncRestoresVisibleAuthorityForRestart() {
        val directory = temporaryFolder.newFolder("reset-journal-clear-sync")
        var failDirectorySync = false
        val journal = FileAccountDeletionResetJournal(
            directory = directory,
            directorySync = { !failDirectorySync },
        )
        assertTrue(journal.write(AccountDeletionResetPhase.FIELD_RESET))
        failDirectorySync = true

        assertFalse(journal.clear())
        assertEquals(
            AccountDeletionResetJournalState.Pending(
                AccountDeletionResetPhase.INTENT_FENCE_CLEARED,
            ),
            journal.read(),
        )

        failDirectorySync = false
        val restarted = AccountDeletionResetCoordinator(
            journal = journal,
            storeLegacyIntent = { true },
            resetGateway = { true },
            resetSensitivePreferences = { true },
            resetPlainPreferences = { true },
            resetFieldStorage = { true },
            clearDeletionIntentFence = { true },
        )
        assertEquals(AccountDeletionResetResult.COMPLETED, restarted.reconcilePending())
        assertEquals(AccountDeletionResetJournalState.Absent, journal.read())
    }

    @Test
    fun oversizedAndSymlinkJournalsAreCorrupt() {
        val oversizedDirectory = temporaryFolder.newFolder("reset-journal-oversized")
        File(oversizedDirectory, "confirmed_account_deletion_reset_v1.journal")
            .writeBytes(ByteArray(129) { 'x'.code.toByte() })
        assertEquals(
            AccountDeletionResetJournalState.Corrupt,
            FileAccountDeletionResetJournal(oversizedDirectory).read(),
        )

        val symlinkDirectory = temporaryFolder.newFolder("reset-journal-symlink")
        val target = temporaryFolder.newFile("reset-journal-target")
        Files.createSymbolicLink(
            File(symlinkDirectory, "confirmed_account_deletion_reset_v1.journal").toPath(),
            target.toPath(),
        )
        assertEquals(
            AccountDeletionResetJournalState.Corrupt,
            FileAccountDeletionResetJournal(symlinkDirectory).read(),
        )
    }

    @Test
    fun staleCoordinatorCannotReplayAfterAnotherCoordinatorCompletes() {
        val journal = FakeJournal()
        var freshEnrollmentDataPresent = false
        var staleActions = 0
        val staleCoordinator = AccountDeletionResetCoordinator(
            journal = journal,
            storeLegacyIntent = { staleActions += 1; true },
            resetGateway = { staleActions += 1; freshEnrollmentDataPresent = false; true },
            resetSensitivePreferences = { staleActions += 1; freshEnrollmentDataPresent = false; true },
            resetPlainPreferences = { staleActions += 1; freshEnrollmentDataPresent = false; true },
            resetFieldStorage = { staleActions += 1; freshEnrollmentDataPresent = false; true },
            clearDeletionIntentFence = {
                staleActions += 1
                freshEnrollmentDataPresent = false
                true
            },
        )
        val completingCoordinator = AccountDeletionResetCoordinator(
            journal = journal,
            storeLegacyIntent = { true },
            resetGateway = { true },
            resetSensitivePreferences = { true },
            resetPlainPreferences = { true },
            resetFieldStorage = { true },
            clearDeletionIntentFence = { true },
        )
        val firstDone = CountDownLatch(1)
        val allowStale = CountDownLatch(1)
        val firstResult = AtomicReference<AccountDeletionResetResult>()
        val staleResult = AtomicReference<AccountDeletionResetResult>()
        val first = thread {
            firstResult.set(completingCoordinator.beginAndReconcile())
            firstDone.countDown()
        }
        val delayed = thread {
            assertTrue(allowStale.await(2, TimeUnit.SECONDS))
            staleResult.set(staleCoordinator.beginAndReconcile())
        }
        assertTrue(firstDone.await(2, TimeUnit.SECONDS))
        freshEnrollmentDataPresent = true
        allowStale.countDown()
        first.join(2_000L)
        delayed.join(2_000L)

        assertEquals(AccountDeletionResetResult.COMPLETED, firstResult.get())
        assertEquals(AccountDeletionResetResult.COMPLETED_ELSEWHERE, staleResult.get())
        assertEquals(
            AccountDeletionResetResult.COMPLETED_ELSEWHERE,
            staleCoordinator.beginAndReconcile(),
        )
        assertEquals(0, staleActions)
        assertTrue(freshEnrollmentDataPresent)
        assertEquals(AccountDeletionResetJournalState.Absent, journal.state)
    }

    @Test
    fun staleCoordinatorCannotReplayANewPendingResetAfterAnotherCoordinatorCompletes() {
        val journal = FakeJournal()
        var staleActions = 0
        val staleCoordinator = AccountDeletionResetCoordinator(
            journal = journal,
            storeLegacyIntent = { staleActions += 1; true },
            resetGateway = { staleActions += 1; true },
            resetSensitivePreferences = { staleActions += 1; true },
            resetPlainPreferences = { staleActions += 1; true },
            resetFieldStorage = { staleActions += 1; true },
            clearDeletionIntentFence = { staleActions += 1; true },
        )
        val completingCoordinator = AccountDeletionResetCoordinator(
            journal = journal,
            storeLegacyIntent = { true },
            resetGateway = { true },
            resetSensitivePreferences = { true },
            resetPlainPreferences = { true },
            resetFieldStorage = { true },
            clearDeletionIntentFence = { true },
        )

        assertEquals(
            AccountDeletionResetResult.COMPLETED,
            completingCoordinator.beginAndReconcile(),
        )
        assertEquals(
            AccountDeletionResetResult.COMPLETED_ELSEWHERE,
            staleCoordinator.reconcilePending(),
        )
        assertEquals(AccountDeletionResetJournalState.Absent, journal.state)
        assertTrue(journal.write(AccountDeletionResetPhase.STARTED))
        assertEquals(
            AccountDeletionResetResult.COMPLETED_ELSEWHERE,
            staleCoordinator.beginAndReconcile(),
        )
        assertEquals(0, staleActions)
        assertEquals(
            AccountDeletionResetJournalState.Pending(AccountDeletionResetPhase.STARTED),
            journal.state,
        )
    }

    private fun coordinator(
        journal: FakeJournal,
        domain: FakeOldAccountDomain,
        crashBeforeStep: Int?,
    ): AccountDeletionResetCoordinator {
        var nextStep = 0
        fun run(action: () -> Unit): Boolean {
            if (crashBeforeStep == nextStep) return false
            nextStep += 1
            action()
            return true
        }
        return AccountDeletionResetCoordinator(
            journal = journal,
            storeLegacyIntent = { run { domain.legacyIntentPresent = true } },
            resetGateway = {
                run {
                    domain.gatewayCiphertextPresent = false
                    domain.gatewayFreshGeneration += 1
                }
            },
            resetSensitivePreferences = {
                run {
                    domain.sensitiveCiphertextPresent = false
                    domain.sensitiveFreshGeneration += 1
                }
            },
            resetPlainPreferences = {
                run {
                    domain.plainAccountArtifactsPresent = false
                    domain.legacyIntentPresent = false
                }
            },
            resetFieldStorage = {
                run {
                    domain.fieldCiphertextPresent = false
                    domain.fieldFreshGeneration += 1
                }
            },
            clearDeletionIntentFence = {
                run { domain.deletionIntentFencePresent = false }
            },
        )
    }

    private class FakeJournal(
        private var failClearOnce: Boolean = false,
    ) : AccountDeletionResetJournal {
        var state: AccountDeletionResetJournalState = AccountDeletionResetJournalState.Absent

        override fun read(): AccountDeletionResetJournalState = state

        override fun write(phase: AccountDeletionResetPhase): Boolean {
            state = AccountDeletionResetJournalState.Pending(phase)
            return true
        }

        override fun clear(): Boolean {
            if (failClearOnce) {
                failClearOnce = false
                return false
            }
            state = AccountDeletionResetJournalState.Absent
            return true
        }
    }

    private class FakeOldAccountDomain {
        var legacyIntentPresent = false
        var gatewayCiphertextPresent = true
        var sensitiveCiphertextPresent = true
        var plainAccountArtifactsPresent = true
        var fieldCiphertextPresent = true
        var deletionIntentFencePresent = true
        var gatewayFreshGeneration = 0
        var sensitiveFreshGeneration = 0
        var fieldFreshGeneration = 0

        fun canEnrollFreshAccount(): Boolean =
            !legacyIntentPresent &&
                !gatewayCiphertextPresent &&
                !sensitiveCiphertextPresent &&
                !plainAccountArtifactsPresent &&
                !fieldCiphertextPresent &&
                !deletionIntentFencePresent &&
                gatewayFreshGeneration > 0 &&
                sensitiveFreshGeneration > 0 &&
                fieldFreshGeneration > 0
    }
}
