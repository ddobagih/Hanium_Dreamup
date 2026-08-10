package kr.co.hanium.dreamup.walksafe.session

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountDeletionDualAuthorityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cleanPreferenceAndFileFailuresRemainIdleAfterRestart() {
        listOf<() -> Boolean>(
            { false },
            { error("preference commit failed") },
            { true },
        ).forEach { preferenceStore ->
            val cleanFileFailure = object : AccountDeletionIntentAuthority {
                override fun read(): AccountDeletionIntentAuthorityState =
                    AccountDeletionIntentAuthorityState.Absent

                override fun confirm(): Boolean = false

                override fun failClosed(reason: String): Boolean = false

                override fun clear(): Boolean = true
            }
            val result = AccountDeletionDualAuthority(
                preferenceFence = AccountDeletionIntentFence(
                    read = { false },
                    store = preferenceStore,
                ),
                fileAuthority = cleanFileFailure,
            ).confirm()

            assertEquals(AccountDeletionConfirmationDecision.NOT_ACCEPTED, result.decision)
            assertEquals(
                AccountDeletionAggregateAuthorityState.ABSENT,
                result.snapshot.aggregateState,
            )
            assertFalse(
                accountDeletionStartupAuthorityDecision(
                    snapshot = result.snapshot,
                    journalPresent = false,
                ).blocksPrivacy,
            )
        }
    }

    @Test
    fun preferenceFailureAndFileSuccessIsAcceptedAfterBothAttempts() {
        var preferenceStores = 0
        val preferenceFence = AccountDeletionIntentFence(
            read = { false },
            store = {
                preferenceStores += 1
                false
            },
        )
        val fileDirectory = temporaryFolder.newFolder("dual-file-success")
        val dual = AccountDeletionDualAuthority(
            preferenceFence,
            FileAccountDeletionIntentAuthority(fileDirectory),
        )

        val result = dual.confirm()

        assertEquals(1, preferenceStores)
        assertEquals(AccountDeletionConfirmationDecision.ACCEPTED, result.decision)
        assertFalse(result.preferenceWriteVerified)
        assertTrue(result.fileWriteVerified)
        assertEquals(AccountDeletionIntentFenceState.ABSENT, result.snapshot.preferenceState)
        assertEquals(
            AccountDeletionIntentAuthorityState.Confirmed,
            result.snapshot.fileState,
        )
        assertEquals(
            AccountDeletionAggregateAuthorityState.CONFIRMED,
            result.snapshot.aggregateState,
        )
    }

    @Test
    fun fileFailureAndPreferenceSuccessIsNotAcceptedAfterBothAttempts() {
        var preferenceStored = false
        val preferenceFence = AccountDeletionIntentFence(
            read = { preferenceStored },
            store = {
                preferenceStored = true
                true
            },
        )
        val fileDirectory = temporaryFolder.newFolder("dual-file-failure")
        val fileAuthority = FileAccountDeletionIntentAuthority(
            directory = fileDirectory,
            atomicMove = { _, _ -> false },
        )
        val result = AccountDeletionDualAuthority(preferenceFence, fileAuthority).confirm()

        assertEquals(AccountDeletionConfirmationDecision.NOT_ACCEPTED, result.decision)
        assertTrue(result.preferenceWriteVerified)
        assertFalse(result.fileWriteVerified)
        assertEquals(AccountDeletionIntentFenceState.PRESENT, result.snapshot.preferenceState)
        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, result.snapshot.fileState)
        assertEquals(
            AccountDeletionAggregateAuthorityState.UNAVAILABLE,
            result.snapshot.aggregateState,
        )
        assertTrue(result.snapshot.blocksPrivacy)
    }

    @Test
    fun durableFileSuccessIsAcceptedAndOverridesUnavailablePreference() {
        val preferenceFence = AccountDeletionIntentFence(
            read = { null },
            store = { false },
        )
        val fileDirectory = temporaryFolder.newFolder("dual-preference-unavailable")
        val result = AccountDeletionDualAuthority(
            preferenceFence,
            FileAccountDeletionIntentAuthority(fileDirectory),
        ).confirm()

        assertEquals(AccountDeletionConfirmationDecision.ACCEPTED, result.decision)
        assertFalse(result.preferenceWriteVerified)
        assertTrue(result.fileWriteVerified)
        assertEquals(
            AccountDeletionIntentAuthorityState.Confirmed,
            result.snapshot.fileState,
        )
        assertEquals(
            AccountDeletionAggregateAuthorityState.CONFIRMED,
            result.snapshot.aggregateState,
        )
    }

    @Test
    fun bothWriteFailuresAreNotAcceptedAndEachReadbackStateIsPreserved() {
        var preferenceStored: Boolean? = false
        val preferenceFence = AccountDeletionIntentFence(
            read = { preferenceStored },
            store = {
                preferenceStored = true
                false
            },
        )
        val fileDirectory = temporaryFolder.newFolder("dual-both-fail")
        val fileAuthority = FileAccountDeletionIntentAuthority(
            directory = fileDirectory,
            atomicMove = { _, _ -> false },
        )
        val result = AccountDeletionDualAuthority(preferenceFence, fileAuthority).confirm()

        assertEquals(AccountDeletionConfirmationDecision.NOT_ACCEPTED, result.decision)
        assertFalse(result.preferenceWriteVerified)
        assertFalse(result.fileWriteVerified)
        assertEquals(AccountDeletionIntentFenceState.PRESENT, result.snapshot.preferenceState)
        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, result.snapshot.fileState)
        assertEquals(
            AccountDeletionAggregateAuthorityState.UNAVAILABLE,
            result.snapshot.aggregateState,
        )
    }

    @Test
    fun startupAggregateBlocksForEitherConfirmationAndForUnavailableAuthority() {
        val absentFileDirectory = temporaryFolder.newFolder("dual-aggregate-absent")
        val absent = AccountDeletionDualAuthority(
            fence(read = { false }),
            FileAccountDeletionIntentAuthority(absentFileDirectory),
        ).startupState()
        assertEquals(AccountDeletionAggregateAuthorityState.ABSENT, absent.aggregateState)
        assertFalse(absent.blocksPrivacy)

        val preferenceConfirmed = AccountDeletionDualAuthority(
            fence(read = { true }),
            FileAccountDeletionIntentAuthority(absentFileDirectory),
        ).startupState()
        assertEquals(
            AccountDeletionAggregateAuthorityState.CONFIRMED,
            preferenceConfirmed.aggregateState,
        )
        assertTrue(preferenceConfirmed.blocksPrivacy)

        val fileDirectory = temporaryFolder.newFolder("dual-aggregate-file")
        val fileAuthority = FileAccountDeletionIntentAuthority(fileDirectory)
        assertTrue(fileAuthority.confirm())
        val fileConfirmed = AccountDeletionDualAuthority(
            fence(read = { false }),
            fileAuthority,
        ).startupState()
        assertEquals(
            AccountDeletionAggregateAuthorityState.CONFIRMED,
            fileConfirmed.aggregateState,
        )
        assertTrue(fileConfirmed.blocksPrivacy)

        val unavailablePreference = AccountDeletionDualAuthority(
            fence(read = { null }),
            fileAuthority,
        ).startupState()
        assertEquals(
            AccountDeletionAggregateAuthorityState.CONFIRMED,
            unavailablePreference.aggregateState,
        )
        assertTrue(unavailablePreference.blocksPrivacy)
    }

    @Test
    fun confirmAttemptsFileBeforePreferenceAndAcceptsExactlyFileSuccess() {
        val calls = mutableListOf<String>()
        val fileAuthority = object : AccountDeletionIntentAuthority {
            override fun read(): AccountDeletionIntentAuthorityState =
                AccountDeletionIntentAuthorityState.Confirmed

            override fun confirm(): Boolean {
                calls += "file"
                return true
            }

            override fun failClosed(reason: String): Boolean = false

            override fun clear(): Boolean = false
        }
        val result = AccountDeletionDualAuthority(
            preferenceFence = AccountDeletionIntentFence(
                read = { null },
                store = {
                    calls += "preference"
                    false
                },
            ),
            fileAuthority = fileAuthority,
        ).confirm()

        assertEquals(listOf("file", "preference"), calls)
        assertEquals(AccountDeletionConfirmationDecision.ACCEPTED, result.decision)
        assertTrue(result.fileWriteVerified)
        assertFalse(result.preferenceWriteVerified)
        assertEquals(
            AccountDeletionAggregateAuthorityState.CONFIRMED,
            result.snapshot.aggregateState,
        )
    }

    @Test
    fun confirmStillAttemptsPreferenceAfterFileBarrierFailureButNeverAccepts() {
        val calls = mutableListOf<String>()
        val fileAuthority = object : AccountDeletionIntentAuthority {
            override fun read(): AccountDeletionIntentAuthorityState =
                AccountDeletionIntentAuthorityState.Confirmed

            override fun confirm(): Boolean {
                calls += "file"
                return false
            }

            override fun failClosed(reason: String): Boolean = false

            override fun clear(): Boolean = false
        }
        val result = AccountDeletionDualAuthority(
            preferenceFence = AccountDeletionIntentFence(
                read = { true },
                store = {
                    calls += "preference"
                    true
                },
            ),
            fileAuthority = fileAuthority,
        ).confirm()

        assertEquals(listOf("file", "preference"), calls)
        assertEquals(AccountDeletionConfirmationDecision.NOT_ACCEPTED, result.decision)
        assertFalse(result.fileWriteVerified)
        assertTrue(result.preferenceWriteVerified)
        assertEquals(
            AccountDeletionAggregateAuthorityState.CONFIRMED,
            result.snapshot.aggregateState,
        )
    }

    @Test
    fun failClosedHasPriorityOverUnavailablePreferenceAndPreservesReason() {
        val fileDirectory = temporaryFolder.newFolder("dual-fail-closed")
        val fileAuthority = FileAccountDeletionIntentAuthority(fileDirectory)
        assertTrue(fileAuthority.failClosed("first_failure"))
        assertTrue(fileAuthority.failClosed("second_failure"))

        val snapshot = AccountDeletionDualAuthority(
            fence(read = { null }),
            fileAuthority,
        ).startupState()

        assertEquals(
            AccountDeletionIntentAuthorityState.FailClosed("first_failure"),
            snapshot.fileState,
        )
        assertEquals(
            AccountDeletionAggregateAuthorityState.FAIL_CLOSED,
            snapshot.aggregateState,
        )
        assertTrue(snapshot.blocksPrivacy)
        val decision = accountDeletionStartupAuthorityDecision(
            snapshot = snapshot,
            journalPresent = false,
        )
        assertEquals("first_failure", decision.failClosedReason)
        assertFalse(decision.durableConfirmationRecoveryRequired)
    }

    @Test
    fun pureStartupDecisionRequestsRecoveryForDurableFileWithoutJournal() {
        val snapshot = AccountDeletionAuthoritySnapshot(
            preferenceState = AccountDeletionIntentFenceState.UNAVAILABLE,
            fileState = AccountDeletionIntentAuthorityState.Confirmed,
            aggregateState = AccountDeletionAggregateAuthorityState.CONFIRMED,
        )

        val decision = accountDeletionStartupAuthorityDecision(
            snapshot = snapshot,
            journalPresent = false,
        )

        assertTrue(decision.blocksPrivacy)
        assertEquals(null, decision.failClosedReason)
        assertTrue(decision.durableConfirmationRecoveryRequired)
    }

    @Test
    fun pureStartupDecisionDoesNotRequestRecoveryWhenJournalAlreadyExists() {
        val snapshot = AccountDeletionAuthoritySnapshot(
            preferenceState = AccountDeletionIntentFenceState.UNAVAILABLE,
            fileState = AccountDeletionIntentAuthorityState.Confirmed,
            aggregateState = AccountDeletionAggregateAuthorityState.CONFIRMED,
        )

        val decision = accountDeletionStartupAuthorityDecision(
            snapshot = snapshot,
            journalPresent = true,
        )

        assertTrue(decision.blocksPrivacy)
        assertEquals(null, decision.failClosedReason)
        assertFalse(decision.durableConfirmationRecoveryRequired)
    }

    @Test
    fun corruptFileAuthorityAggregatesUnavailableEvenWhenPreferenceIsConfirmed() {
        val fileDirectory = temporaryFolder.newFolder("dual-corrupt")
        File(fileDirectory, AUTHORITY_FILE_NAME).writeText("corrupt")

        val snapshot = AccountDeletionDualAuthority(
            fence(read = { true }),
            FileAccountDeletionIntentAuthority(fileDirectory),
        ).startupState()

        assertEquals(AccountDeletionIntentFenceState.PRESENT, snapshot.preferenceState)
        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, snapshot.fileState)
        assertEquals(
            AccountDeletionAggregateAuthorityState.UNAVAILABLE,
            snapshot.aggregateState,
        )
        assertTrue(snapshot.blocksPrivacy)
    }

    @Test
    fun failClosedAttemptsPreferenceAfterDurableFileAndUsesExactReadback() {
        var preferenceStores = 0
        val preferenceFence = AccountDeletionIntentFence(
            read = { false },
            store = {
                preferenceStores += 1
                false
            },
        )
        val fileDirectory = temporaryFolder.newFolder("dual-fail-closed-success")
        val result = AccountDeletionDualAuthority(
            preferenceFence,
            FileAccountDeletionIntentAuthority(fileDirectory),
        ).failClosed("terminal_conflict:account_deletion_installation_inventory_missing")

        assertEquals(1, preferenceStores)
        assertFalse(result.preferenceWriteVerified)
        assertTrue(result.fileWriteVerified)
        assertTrue(result.durableSuccess)
        assertEquals(
            AccountDeletionIntentAuthorityState.FailClosed(
                "terminal_conflict:account_deletion_installation_inventory_missing",
            ),
            result.snapshot.fileState,
        )
        assertEquals(
            AccountDeletionAggregateAuthorityState.FAIL_CLOSED,
            result.snapshot.aggregateState,
        )
    }

    @Test
    fun failClosedMakesFileDurableBeforePreferenceMirrorKillPoint() {
        val calls = mutableListOf<String>()
        val fileAuthority = object : AccountDeletionIntentAuthority {
            override fun read(): AccountDeletionIntentAuthorityState =
                AccountDeletionIntentAuthorityState.FailClosed("terminal_failure")

            override fun confirm(): Boolean = false

            override fun failClosed(reason: String): Boolean {
                calls += "file"
                return true
            }

            override fun clear(): Boolean = false
        }
        val result = AccountDeletionDualAuthority(
            preferenceFence = AccountDeletionIntentFence(
                read = { null },
                store = {
                    calls += "preference"
                    error("killed after durable file")
                },
            ),
            fileAuthority = fileAuthority,
        ).failClosed("terminal_failure")

        assertEquals(listOf("file", "preference"), calls)
        assertTrue(result.fileWriteVerified)
        assertFalse(result.preferenceWriteVerified)
        assertTrue(result.durableSuccess)
        assertEquals(
            AccountDeletionAggregateAuthorityState.FAIL_CLOSED,
            result.snapshot.aggregateState,
        )
    }

    @Test
    fun failClosedFileKillPointStillAttemptsPreferenceMirrorButIsNotDurable() {
        val calls = mutableListOf<String>()
        val fileAuthority = object : AccountDeletionIntentAuthority {
            override fun read(): AccountDeletionIntentAuthorityState =
                AccountDeletionIntentAuthorityState.Absent

            override fun confirm(): Boolean = false

            override fun failClosed(reason: String): Boolean {
                calls += "file"
                error("killed before durable file")
            }

            override fun clear(): Boolean = false
        }
        val result = AccountDeletionDualAuthority(
            preferenceFence = AccountDeletionIntentFence(
                read = { true },
                store = {
                    calls += "preference"
                    true
                },
            ),
            fileAuthority = fileAuthority,
        ).failClosed("terminal_failure")

        assertEquals(listOf("file", "preference"), calls)
        assertFalse(result.fileWriteVerified)
        assertTrue(result.preferenceWriteVerified)
        assertFalse(result.durableSuccess)
    }

    @Test
    fun failClosedFileFailureStillEngagesPreferenceButIsNotDurable() {
        var preferenceStored = false
        val preferenceFence = AccountDeletionIntentFence(
            read = { preferenceStored },
            store = {
                preferenceStored = true
                true
            },
        )
        val fileDirectory = temporaryFolder.newFolder("dual-fail-closed-file-failure")
        val fileAuthority = FileAccountDeletionIntentAuthority(
            directory = fileDirectory,
            atomicMove = { _, _ -> false },
        )
        val result = AccountDeletionDualAuthority(
            preferenceFence,
            fileAuthority,
        ).failClosed("terminal_storage_failure")

        assertTrue(result.preferenceWriteVerified)
        assertFalse(result.fileWriteVerified)
        assertFalse(result.durableSuccess)
        assertEquals(AccountDeletionIntentFenceState.PRESENT, result.snapshot.preferenceState)
        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, result.snapshot.fileState)
        assertEquals(
            AccountDeletionAggregateAuthorityState.UNAVAILABLE,
            result.snapshot.aggregateState,
        )
    }

    @Test
    fun clearRequiresBothAuthoritiesAndBothAbsentReadbacks() {
        var preferenceStored: Boolean? = true
        val preferenceFence = AccountDeletionIntentFence(
            read = { preferenceStored },
            store = { true },
            clear = {
                preferenceStored = false
                true
            },
        )
        val fileDirectory = temporaryFolder.newFolder("dual-clear-success")
        val fileAuthority = FileAccountDeletionIntentAuthority(fileDirectory)
        assertTrue(fileAuthority.confirm())

        val result = AccountDeletionDualAuthority(preferenceFence, fileAuthority).clear()

        assertTrue(result.preferenceClearVerified)
        assertTrue(result.fileClearVerified)
        assertTrue(result.cleared)
        assertEquals(AccountDeletionIntentFenceState.ABSENT, result.snapshot.preferenceState)
        assertEquals(AccountDeletionIntentAuthorityState.Absent, result.snapshot.fileState)
        assertEquals(
            AccountDeletionAggregateAuthorityState.ABSENT,
            result.snapshot.aggregateState,
        )
    }

    @Test
    fun preferenceClearFailureDoesNotShortCircuitVerifiedFileClear() {
        var preferenceClearAttempts = 0
        val preferenceFence = AccountDeletionIntentFence(
            read = { true },
            store = { true },
            clear = {
                preferenceClearAttempts += 1
                false
            },
        )
        val fileDirectory = temporaryFolder.newFolder("dual-clear-preference-failure")
        val fileAuthority = FileAccountDeletionIntentAuthority(fileDirectory)
        assertTrue(fileAuthority.confirm())

        val result = AccountDeletionDualAuthority(preferenceFence, fileAuthority).clear()

        assertEquals(1, preferenceClearAttempts)
        assertFalse(result.preferenceClearVerified)
        assertTrue(result.fileClearVerified)
        assertFalse(result.cleared)
        assertEquals(AccountDeletionIntentFenceState.PRESENT, result.snapshot.preferenceState)
        assertEquals(AccountDeletionIntentAuthorityState.Absent, result.snapshot.fileState)
    }

    @Test
    fun fileClearFailureDoesNotShortCircuitVerifiedPreferenceClear() {
        var preferenceStored: Boolean? = true
        val preferenceFence = AccountDeletionIntentFence(
            read = { preferenceStored },
            store = { true },
            clear = {
                preferenceStored = false
                true
            },
        )
        val fileDirectory = temporaryFolder.newFolder("dual-clear-file-failure")
        val fileAuthority = FileAccountDeletionIntentAuthority(fileDirectory)
        assertTrue(fileAuthority.confirm())
        val finalFile = File(fileDirectory, AUTHORITY_FILE_NAME)
        Files.createLink(
            temporaryFolder.root.resolve("dual-clear-file-hardlink").toPath(),
            finalFile.toPath(),
        )

        val result = AccountDeletionDualAuthority(preferenceFence, fileAuthority).clear()

        assertTrue(result.preferenceClearVerified)
        assertFalse(result.fileClearVerified)
        assertFalse(result.cleared)
        assertEquals(AccountDeletionIntentFenceState.ABSENT, result.snapshot.preferenceState)
        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, result.snapshot.fileState)
    }

    private fun fence(read: () -> Boolean?): AccountDeletionIntentFence =
        AccountDeletionIntentFence(
            read = read,
            store = { false },
        )

    private companion object {
        const val AUTHORITY_FILE_NAME = "account_deletion_intent_authority_v1"
    }
}
