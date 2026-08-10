package kr.co.hanium.dreamup.walksafe.session

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountDeletionIntentAuthorityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun confirmedPayloadIsExactAndSurvivesRestart() {
        val directory = temporaryFolder.newFolder("authority-confirmed")
        val authority = FileAccountDeletionIntentAuthority(directory)

        assertEquals(AccountDeletionIntentAuthorityState.Absent, authority.read())
        assertTrue(authority.confirm())
        assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
        assertEquals(
            CONFIRMED_PAYLOAD,
            authorityFile(directory).readText(StandardCharsets.UTF_8),
        )
        assertFalse(temporaryAuthorityFile(directory).exists())

        val restarted = FileAccountDeletionIntentAuthority(directory)
        assertEquals(AccountDeletionIntentAuthorityState.Confirmed, restarted.read())
        assertTrue(restarted.confirm())
    }

    @Test
    fun firstFailClosedReasonWinsAndUsesCanonicalBoundedPayload() {
        val directory = temporaryFolder.newFolder("authority-fail-closed")
        val authority = FileAccountDeletionIntentAuthority(directory)
        val firstReason =
            "terminal_conflict:account_deletion_installation_inventory_missing"

        assertTrue(authority.confirm())
        assertTrue(authority.failClosed(firstReason))
        assertTrue(authority.failClosed("second_terminal_reason"))
        assertEquals(
            AccountDeletionIntentAuthorityState.FailClosed(firstReason),
            authority.read(),
        )
        val encodedReason = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(firstReason.toByteArray(StandardCharsets.UTF_8))
        assertEquals(
            "$PAYLOAD_PREFIX|FAIL_CLOSED|$encodedReason\n",
            authorityFile(directory).readText(StandardCharsets.UTF_8),
        )
        assertEquals(
            AccountDeletionIntentAuthorityState.FailClosed(firstReason),
            FileAccountDeletionIntentAuthority(directory).read(),
        )
    }

    @Test
    fun invalidTerminalReasonsNeverReplaceExistingAuthority() {
        val directory = temporaryFolder.newFolder("authority-invalid-reason")
        val authority = FileAccountDeletionIntentAuthority(directory)
        assertTrue(authority.confirm())

        listOf(
            "",
            "Uppercase",
            "contains space",
            "x".repeat(97),
        ).forEach { reason ->
            assertFalse(authority.failClosed(reason))
            assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
        }
    }

    @Test
    fun corruptPayloadBlocksMutationButVerifiedClearRemovesIt() {
        val directory = temporaryFolder.newFolder("authority-corrupt")
        authorityFile(directory).writeText("not-an-authority")
        val authority = FileAccountDeletionIntentAuthority(directory)

        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, authority.read())
        assertFalse(authority.confirm())
        assertFalse(authority.failClosed("storage_corrupt"))
        assertTrue(authority.clear())
        assertEquals(AccountDeletionIntentAuthorityState.Absent, authority.read())
    }

    @Test
    fun filesystemInspectionFailureIsUnavailableRatherThanAbsent() {
        val invalidPath = File(temporaryFolder.root, "authority\u0000unavailable")
        val authority = FileAccountDeletionIntentAuthority(invalidPath)

        assertEquals(AccountDeletionIntentAuthorityState.Unavailable, authority.read())
        assertFalse(authority.confirm())
        assertFalse(authority.failClosed("storage_unavailable"))
        assertFalse(authority.clear())
    }

    @Test
    fun failedOrThrowingAtomicMoveLeavesTempCorruptAcrossRestartUntilClear() {
        listOf<(java.nio.file.Path, java.nio.file.Path) -> Boolean>(
            { _, _ -> false },
            { _, _ -> error("move failed") },
        ).forEachIndexed { index, move ->
            val directory = temporaryFolder.newFolder("authority-move-failure-$index")
            val authority = FileAccountDeletionIntentAuthority(
                directory = directory,
                atomicMove = move,
            )

            assertFalse(authority.confirm())
            assertFalse(authorityFile(directory).exists())
            assertTrue(temporaryAuthorityFile(directory).isFile)
            assertEquals(AccountDeletionIntentAuthorityState.Corrupt, authority.read())

            val restarted = FileAccountDeletionIntentAuthority(directory)
            assertEquals(AccountDeletionIntentAuthorityState.Corrupt, restarted.read())
            assertFalse(restarted.confirm())
            assertTrue(restarted.clear())
            assertEquals(AccountDeletionIntentAuthorityState.Absent, restarted.read())
        }
    }

    @Test
    fun preparedRecoveryPromotesOnlyExactConfirmedTempWithFullBarrier() {
        val noBackupDirectory = temporaryFolder.newFolder("prepared-exact")
        val directory = File(noBackupDirectory, "authority").apply { assertTrue(mkdir()) }
        temporaryAuthorityFile(directory).writeText(CONFIRMED_PAYLOAD)
        val calls = mutableListOf<String>()
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            atomicMove = { source, target ->
                calls += "move"
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                true
            },
            fileSync = {
                calls += "file"
                true
            },
            directorySync = {
                calls += if (it == directory) "authority-directory" else "no-backup-directory"
                true
            },
        )
        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, authority.read())

        assertEquals(
            PreparedConfirmationRecoveryResult.RECOVERED_CONFIRMED,
            authority.recoverPreparedConfirmation(),
        )
        assertEquals(
            listOf("move", "file", "authority-directory", "no-backup-directory"),
            calls,
        )
        assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
        assertFalse(temporaryAuthorityFile(directory).exists())
    }

    @Test
    fun preparedRecoveryRetriesAtomicMoveWithoutTreatingTempAsGenericAuthority() {
        val directory = temporaryFolder.newFolder("prepared-move-retry")
        temporaryAuthorityFile(directory).writeText(CONFIRMED_PAYLOAD)
        var moves = 0
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            atomicMove = { source, target ->
                moves += 1
                if (moves == 1) {
                    false
                } else {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                    true
                }
            },
        )

        assertEquals(
            PreparedConfirmationRecoveryResult.RETRY_REQUIRED,
            authority.recoverPreparedConfirmation(),
        )
        assertTrue(temporaryAuthorityFile(directory).isFile)
        assertEquals(
            PreparedConfirmationRecoveryResult.RECOVERED_CONFIRMED,
            authority.recoverPreparedConfirmation(),
        )
        assertEquals(2, moves)
        assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
    }

    @Test
    fun preparedRecoveryRetriesEveryPostMoveBarrierKillPoint() {
        listOf("file", "authority-directory", "no-backup-directory")
            .forEachIndexed { index, killPoint ->
                val noBackupDirectory = temporaryFolder.newFolder("prepared-barrier-$index")
                val directory = File(noBackupDirectory, "authority").apply {
                    assertTrue(mkdir())
                }
                temporaryAuthorityFile(directory).writeText(CONFIRMED_PAYLOAD)
                var killed = false
                val calls = mutableListOf<String>()
                val authority = FileAccountDeletionIntentAuthority(
                    directory = directory,
                    fileSync = {
                        calls += "file"
                        if (killPoint == "file" && !killed) {
                            killed = true
                            false
                        } else {
                            true
                        }
                    },
                    directorySync = {
                        val step = if (it == directory) {
                            "authority-directory"
                        } else {
                            "no-backup-directory"
                        }
                        calls += step
                        if (step == killPoint && !killed) {
                            killed = true
                            false
                        } else {
                            true
                        }
                    },
                )

                assertEquals(
                    PreparedConfirmationRecoveryResult.RETRY_REQUIRED,
                    authority.recoverPreparedConfirmation(),
                )
                assertTrue(killed)
                assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
                calls.clear()

                assertEquals(
                    PreparedConfirmationRecoveryResult.RECOVERED_CONFIRMED,
                    authority.recoverPreparedConfirmation(),
                )
                assertEquals(
                    listOf("file", "authority-directory", "no-backup-directory"),
                    calls,
                )
            }
    }

    @Test
    fun preparedRecoveryDeletesOnlyCanonicalPartialTempThenRequiresReconfirm() {
        val confirmedBytes = CONFIRMED_PAYLOAD.toByteArray(StandardCharsets.UTF_8)
        listOf(0, 1, confirmedBytes.size - 1).forEachIndexed { index, length ->
            val directory = temporaryFolder.newFolder("prepared-partial-$index")
            Files.write(
                temporaryAuthorityFile(directory).toPath(),
                confirmedBytes.copyOf(length),
            )
            val authority = FileAccountDeletionIntentAuthority(directory)

            assertEquals(
                PreparedConfirmationRecoveryResult.RECONFIRM_REQUIRED,
                authority.recoverPreparedConfirmation(),
            )
            assertFalse(temporaryAuthorityFile(directory).exists())
            assertEquals(AccountDeletionIntentAuthorityState.Absent, authority.read())
            assertTrue(authority.confirm())
            assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
        }
    }

    @Test
    fun preparedPartialCleanupBarrierFailureMustBeRetriedBeforeReconfirm() {
        val noBackupDirectory = temporaryFolder.newFolder("prepared-partial-retry")
        val directory = File(noBackupDirectory, "authority").apply { assertTrue(mkdir()) }
        Files.write(
            temporaryAuthorityFile(directory).toPath(),
            CONFIRMED_PAYLOAD.toByteArray(StandardCharsets.UTF_8).copyOf(5),
        )
        var authoritySyncs = 0
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = {
                if (it == directory) {
                    authoritySyncs += 1
                    authoritySyncs > 1
                } else {
                    true
                }
            },
        )

        assertEquals(
            PreparedConfirmationRecoveryResult.RETRY_REQUIRED,
            authority.recoverPreparedConfirmation(),
        )
        assertFalse(temporaryAuthorityFile(directory).exists())
        assertEquals(
            PreparedConfirmationRecoveryResult.RECONFIRM_REQUIRED,
            authority.recoverPreparedConfirmation(),
        )
        assertEquals(2, authoritySyncs)
    }

    @Test
    fun preparedRecoveryRejectsMismatchFailClosedAndAmbiguousTempPayloads() {
        val mismatchDirectory = temporaryFolder.newFolder("prepared-mismatch")
        temporaryAuthorityFile(mismatchDirectory).writeText("not-confirmed")
        val mismatch = FileAccountDeletionIntentAuthority(mismatchDirectory)
        assertEquals(
            PreparedConfirmationRecoveryResult.REJECTED,
            mismatch.recoverPreparedConfirmation(),
        )
        assertTrue(temporaryAuthorityFile(mismatchDirectory).exists())

        val failClosedDirectory = temporaryFolder.newFolder("prepared-fail-closed-temp")
        val failedMoveWriter = FileAccountDeletionIntentAuthority(
            directory = failClosedDirectory,
            atomicMove = { _, _ -> false },
        )
        assertFalse(failedMoveWriter.failClosed("terminal_failure"))
        assertEquals(
            PreparedConfirmationRecoveryResult.REJECTED,
            FileAccountDeletionIntentAuthority(failClosedDirectory)
                .recoverPreparedConfirmation(),
        )

        val ambiguousDirectory = temporaryFolder.newFolder("prepared-ambiguous")
        val ambiguous = FileAccountDeletionIntentAuthority(ambiguousDirectory)
        assertTrue(ambiguous.confirm())
        temporaryAuthorityFile(ambiguousDirectory).writeText(CONFIRMED_PAYLOAD)
        assertEquals(
            PreparedConfirmationRecoveryResult.REJECTED,
            ambiguous.recoverPreparedConfirmation(),
        )
    }

    @Test
    fun preparedRecoveryRejectsLinkedTempAndExistingFailClosedFinal() {
        listOf("symlink", "hardlink").forEachIndexed { index, linkType ->
            val directory = temporaryFolder.newFolder("prepared-linked-$index")
            val target = temporaryFolder.newFile("prepared-linked-target-$index")
            target.writeText(CONFIRMED_PAYLOAD)
            if (linkType == "symlink") {
                Files.createSymbolicLink(
                    temporaryAuthorityFile(directory).toPath(),
                    target.toPath(),
                )
            } else {
                Files.createLink(
                    temporaryAuthorityFile(directory).toPath(),
                    target.toPath(),
                )
            }
            val authority = FileAccountDeletionIntentAuthority(directory)

            assertEquals(
                PreparedConfirmationRecoveryResult.REJECTED,
                authority.recoverPreparedConfirmation(),
            )
            assertTrue(temporaryAuthorityFile(directory).exists())
        }

        val terminalDirectory = temporaryFolder.newFolder("prepared-terminal-final")
        val terminal = FileAccountDeletionIntentAuthority(terminalDirectory)
        assertTrue(terminal.failClosed("terminal_failure"))
        assertEquals(
            PreparedConfirmationRecoveryResult.REJECTED,
            terminal.recoverPreparedConfirmation(),
        )
        assertEquals(
            AccountDeletionIntentAuthorityState.FailClosed("terminal_failure"),
            terminal.read(),
        )
    }

    @Test
    fun preparedRecoveryRejectsOversizedTempWithoutMutatingIt() {
        val directory = temporaryFolder.newFolder("prepared-oversized-temp")
        val oversized = ByteArray(4_096) { index -> (index and 0xff).toByte() }
        temporaryAuthorityFile(directory).writeBytes(oversized)
        val authority = FileAccountDeletionIntentAuthority(directory)

        assertEquals(
            PreparedConfirmationRecoveryResult.REJECTED,
            authority.recoverPreparedConfirmation(),
        )
        assertTrue(temporaryAuthorityFile(directory).readBytes().contentEquals(oversized))
        assertFalse(authorityFile(directory).exists())
    }

    @Test
    fun preparedRecoveryRejectsLinkedFinalWithoutMutatingTheTarget() {
        listOf("symlink", "hardlink").forEachIndexed { index, linkType ->
            val directory = temporaryFolder.newFolder("prepared-final-link-$index")
            val target = temporaryFolder.newFile("prepared-final-target-$index")
            val original = CONFIRMED_PAYLOAD.toByteArray(StandardCharsets.UTF_8)
            target.writeBytes(original)
            if (linkType == "symlink") {
                Files.createSymbolicLink(
                    authorityFile(directory).toPath(),
                    target.toPath(),
                )
            } else {
                Files.createLink(
                    authorityFile(directory).toPath(),
                    target.toPath(),
                )
            }
            val authority = FileAccountDeletionIntentAuthority(directory)

            assertEquals(
                PreparedConfirmationRecoveryResult.REJECTED,
                authority.recoverPreparedConfirmation(),
            )
            assertTrue(target.readBytes().contentEquals(original))
            assertTrue(Files.exists(authorityFile(directory).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Test
    fun preparedRecoveryRejectsInvalidAuthorityDirectoryWithoutMutation() {
        val invalidDirectory = temporaryFolder.newFile("prepared-invalid-directory")
        val original = "not-a-directory".toByteArray(StandardCharsets.UTF_8)
        invalidDirectory.writeBytes(original)
        val authority = FileAccountDeletionIntentAuthority(invalidDirectory)

        assertEquals(
            PreparedConfirmationRecoveryResult.REJECTED,
            authority.recoverPreparedConfirmation(),
        )
        assertTrue(invalidDirectory.readBytes().contentEquals(original))
    }

    @Test
    fun preparedRecoveryOfExactAbsenceRequiresBarrierBeforeReconfirm() {
        val noBackupDirectory = temporaryFolder.newFolder("prepared-absent")
        val directory = File(noBackupDirectory, "authority")
        val calls = mutableListOf<File>()
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = {
                calls += it
                true
            },
        )

        assertEquals(
            PreparedConfirmationRecoveryResult.RECONFIRM_REQUIRED,
            authority.recoverPreparedConfirmation(),
        )
        assertEquals(listOf(noBackupDirectory, directory, noBackupDirectory), calls)
        assertEquals(AccountDeletionIntentAuthorityState.Absent, authority.read())
    }

    @Test
    fun staleExactTempAlwaysBlocksInsteadOfBeingPromoted() {
        val directory = temporaryFolder.newFolder("authority-stale-temp")
        temporaryAuthorityFile(directory).writeText(CONFIRMED_PAYLOAD)
        val authority = FileAccountDeletionIntentAuthority(directory)

        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, authority.read())
        assertFalse(authority.confirm())
        assertFalse(authorityFile(directory).exists())
        assertTrue(temporaryAuthorityFile(directory).exists())
    }

    @Test
    fun directorySyncFailureNeverReportsConfirmedDespiteVisibleFinalReadback() {
        val directory = temporaryFolder.newFolder("authority-sync-failure")
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = { false },
        )

        assertFalse(authority.confirm())
        assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
        assertFalse(temporaryAuthorityFile(directory).exists())
    }

    @Test
    fun newRecordEndsWithFileAuthorityDirectoryAndNoBackupDirectoryBarrier() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-barrier")
        val directory = File(noBackupDirectory, "authority")
        val calls = mutableListOf<String>()
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = {
                calls += "directory:${it.name}"
                true
            },
            fileSync = {
                calls += "file:${it.name}"
                true
            },
        )

        assertTrue(authority.confirm())
        assertEquals(
            listOf(
                "file:$AUTHORITY_FILE_NAME",
                "directory:${directory.name}",
                "directory:${noBackupDirectory.name}",
            ),
            calls.takeLast(3),
        )
        assertTrue(calls.first() == "directory:${noBackupDirectory.name}")
    }

    @Test
    fun exactExistingRecordRerunsWholeDurabilityBarrier() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-existing")
        val directory = File(noBackupDirectory, "authority").apply { assertTrue(mkdir()) }
        assertTrue(FileAccountDeletionIntentAuthority(directory).confirm())
        val calls = mutableListOf<String>()
        val restarted = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = {
                calls += "directory:${it.name}"
                true
            },
            fileSync = {
                calls += "file:${it.name}"
                true
            },
        )

        assertTrue(restarted.confirm())
        assertEquals(
            listOf(
                "file:$AUTHORITY_FILE_NAME",
                "directory:${directory.name}",
                "directory:${noBackupDirectory.name}",
            ),
            calls,
        )
    }

    @Test
    fun failedPostMoveDirectoryBarrierCannotBeBypassedByExistingReadback() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-post-move")
        val directory = File(noBackupDirectory, "authority").apply { assertTrue(mkdir()) }
        var authorityDirectorySyncs = 0
        var fileSyncs = 0
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = {
                if (it == directory) {
                    authorityDirectorySyncs += 1
                    authorityDirectorySyncs > 1
                } else {
                    true
                }
            },
            fileSync = {
                fileSyncs += 1
                true
            },
        )

        assertFalse(authority.confirm())
        assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
        assertTrue(authority.confirm())
        assertEquals(2, authorityDirectorySyncs)
        assertEquals(2, fileSyncs)
    }

    @Test
    fun everyBarrierKillPointBlocksSuccessAndRetryRunsTheWholeBarrier() {
        listOf("file", "authority-directory", "no-backup-directory")
            .forEachIndexed { index, killPoint ->
                val noBackupDirectory = temporaryFolder.newFolder("no-backup-kill-$index")
                val directory = File(noBackupDirectory, "authority").apply {
                    assertTrue(mkdir())
                }
                assertTrue(FileAccountDeletionIntentAuthority(directory).confirm())
                val calls = mutableListOf<String>()
                var killed = false
                val authority = FileAccountDeletionIntentAuthority(
                    directory = directory,
                    directorySync = {
                        val step = if (it == directory) {
                            "authority-directory"
                        } else {
                            "no-backup-directory"
                        }
                        calls += step
                        if (step == killPoint && !killed) {
                            killed = true
                            false
                        } else {
                            true
                        }
                    },
                    fileSync = {
                        calls += "file"
                        if (killPoint == "file" && !killed) {
                            killed = true
                            false
                        } else {
                            true
                        }
                    },
                )

                assertFalse(authority.confirm())
                assertTrue(killed)
                calls.clear()

                assertTrue(authority.confirm())
                assertEquals(
                    listOf("file", "authority-directory", "no-backup-directory"),
                    calls,
                )
            }
    }

    @Test
    fun failedMkdirParentBarrierMustSucceedOnRetryBeforeWriting() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-mkdir")
        val directory = File(noBackupDirectory, "authority")
        var parentSyncs = 0
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = {
                if (it == noBackupDirectory) {
                    parentSyncs += 1
                    parentSyncs > 1
                } else {
                    true
                }
            },
        )

        assertFalse(authority.confirm())
        assertTrue(directory.isDirectory)
        assertFalse(authorityFile(directory).exists())
        assertTrue(authority.confirm())
        assertEquals(2, parentSyncs)
        assertEquals(AccountDeletionIntentAuthorityState.Confirmed, authority.read())
    }

    @Test
    fun lyingAtomicMoveCannotBypassExactFinalReadback() {
        val directory = temporaryFolder.newFolder("authority-readback")
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            atomicMove = { source, target ->
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                Files.write(target, "wrong".toByteArray(StandardCharsets.UTF_8))
                true
            },
        )

        assertFalse(authority.confirm())
        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, authority.read())
    }

    @Test
    fun finalAndTemporarySymlinksAreRejectedAndNeverCleared() {
        listOf(AUTHORITY_FILE_NAME, "$AUTHORITY_FILE_NAME.tmp").forEachIndexed { index, name ->
            val directory = temporaryFolder.newFolder("authority-symlink-$index")
            val target = temporaryFolder.newFile("authority-symlink-target-$index")
            target.writeText(CONFIRMED_PAYLOAD)
            Files.createSymbolicLink(File(directory, name).toPath(), target.toPath())
            val authority = FileAccountDeletionIntentAuthority(directory)

            assertEquals(AccountDeletionIntentAuthorityState.Corrupt, authority.read())
            assertFalse(authority.confirm())
            assertFalse(authority.clear())
            assertTrue(Files.isSymbolicLink(File(directory, name).toPath()))
        }
    }

    @Test
    fun finalAndTemporaryHardlinksAreRejectedAndNeverCleared() {
        val finalDirectory = temporaryFolder.newFolder("authority-hardlink-final")
        val finalAuthority = FileAccountDeletionIntentAuthority(finalDirectory)
        assertTrue(finalAuthority.confirm())
        val finalLink = temporaryFolder.root.resolve("authority-final-link")
        Files.createLink(finalLink.toPath(), authorityFile(finalDirectory).toPath())
        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, finalAuthority.read())
        assertFalse(finalAuthority.clear())

        val tempDirectory = temporaryFolder.newFolder("authority-hardlink-temp")
        val tempAuthority = FileAccountDeletionIntentAuthority(
            directory = tempDirectory,
            atomicMove = { _, _ -> false },
        )
        assertFalse(tempAuthority.confirm())
        val tempLink = temporaryFolder.root.resolve("authority-temp-link")
        Files.createLink(tempLink.toPath(), temporaryAuthorityFile(tempDirectory).toPath())
        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, tempAuthority.read())
        assertFalse(tempAuthority.clear())
    }

    @Test
    fun symlinkAuthorityDirectoryIsRejected() {
        val targetDirectory = temporaryFolder.newFolder("authority-directory-target")
        val symlinkDirectory = temporaryFolder.root.resolve("authority-directory-link")
        Files.createSymbolicLink(symlinkDirectory.toPath(), targetDirectory.toPath())
        val authority = FileAccountDeletionIntentAuthority(symlinkDirectory)

        assertEquals(AccountDeletionIntentAuthorityState.Corrupt, authority.read())
        assertFalse(authority.confirm())
        assertFalse(authority.clear())
    }

    @Test
    fun clearValidatesBothFilesFsyncsDirectoryAndReadsBackAbsent() {
        val directory = temporaryFolder.newFolder("authority-clear")
        var syncCount = 0
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = {
                syncCount += 1
                true
            },
        )
        assertTrue(authority.failClosed("confirmed_reset"))
        syncCount = 0

        assertTrue(authority.clear())
        assertEquals(2, syncCount)
        assertFalse(authorityFile(directory).exists())
        assertFalse(temporaryAuthorityFile(directory).exists())
        assertEquals(AccountDeletionIntentAuthorityState.Absent, authority.read())
    }

    @Test
    fun clearNeverReportsSuccessWhenDirectoryFsyncFails() {
        val directory = temporaryFolder.newFolder("authority-clear-sync-failure")
        assertTrue(FileAccountDeletionIntentAuthority(directory).confirm())
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = { false },
        )

        assertFalse(authority.clear())
        assertEquals(AccountDeletionIntentAuthorityState.Absent, authority.read())
    }

    @Test
    fun clearRetryAndAlreadyAbsentBothRequireDirectoryBarriers() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-clear-retry")
        val directory = File(noBackupDirectory, "authority").apply { assertTrue(mkdir()) }
        assertTrue(FileAccountDeletionIntentAuthority(directory).confirm())
        var authorityDirectorySyncs = 0
        var parentDirectorySyncs = 0
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = {
                when (it) {
                    directory -> {
                        authorityDirectorySyncs += 1
                        authorityDirectorySyncs > 1
                    }
                    noBackupDirectory -> {
                        parentDirectorySyncs += 1
                        true
                    }
                    else -> false
                }
            },
        )

        assertFalse(authority.clear())
        assertEquals(AccountDeletionIntentAuthorityState.Absent, authority.read())
        assertTrue(authority.clear())
        assertEquals(2, authorityDirectorySyncs)
        assertEquals(1, parentDirectorySyncs)

        assertTrue(authority.clear())
        assertEquals(3, authorityDirectorySyncs)
        assertEquals(2, parentDirectorySyncs)
    }

    @Test
    fun clearOfNeverCreatedAuthorityCreatesAndSyncsEmptyAuthorityDirectory() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-clear-missing")
        val directory = File(noBackupDirectory, "authority")
        val calls = mutableListOf<File>()
        val authority = FileAccountDeletionIntentAuthority(
            directory = directory,
            directorySync = {
                calls += it
                true
            },
        )

        assertTrue(authority.clear())
        assertTrue(directory.isDirectory)
        assertEquals(
            listOf(noBackupDirectory, directory, noBackupDirectory),
            calls,
        )
        assertEquals(AccountDeletionIntentAuthorityState.Absent, authority.read())
    }

    @Test
    fun realFilesystemSupportsWriteRetryAndClearRetryBarriers() {
        val noBackupDirectory = temporaryFolder.newFolder("no-backup-real-fs")
        val directory = File(noBackupDirectory, "authority")
        val authority = FileAccountDeletionIntentAuthority(directory)

        assertTrue(authority.confirm())
        assertTrue(authority.confirm())
        assertTrue(authority.clear())
        assertTrue(authority.clear())
        assertEquals(AccountDeletionIntentAuthorityState.Absent, authority.read())
    }

    private fun authorityFile(directory: File): File = File(directory, AUTHORITY_FILE_NAME)

    private fun temporaryAuthorityFile(directory: File): File =
        File(directory, "$AUTHORITY_FILE_NAME.tmp")

    private companion object {
        const val AUTHORITY_FILE_NAME = "account_deletion_intent_authority_v1"
        const val PAYLOAD_PREFIX = "walksafe.account-deletion-intent-authority.v1"
        const val CONFIRMED_PAYLOAD = "$PAYLOAD_PREFIX|CONFIRMED\n"
    }
}
