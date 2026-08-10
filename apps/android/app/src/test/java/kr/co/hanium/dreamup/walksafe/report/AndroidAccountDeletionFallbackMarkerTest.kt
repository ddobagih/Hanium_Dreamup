package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccountDeletionFallbackMarkerTest {
    private val keyProvider = FakeKeyProvider()

    @Test
    fun recoveryActorIsEncryptedHashBoundAndRestartReadable() =
        withMarkerRoot { root ->
            val actorId = "field.actor-01"
            val record = AndroidAccountDeletionFallbackMarker.Record(
                identity = identity("account_delete_recovery_0001", actorId),
                recoveryActorId = actorId,
                gatewayOrigin = "https://gateway.example.test",
                installationId = "installation-recovery-0001",
                accessSecret = "A".repeat(43),
                clientRevision = 1L,
                phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED,
            )
            assertFalse(
                marker(root).create(
                    record.copy(recoveryActorId = "different.actor"),
                ),
            )
            assertFalse(markerFile(root).exists())
            assertTrue(marker(root).create(record))
            val ciphertext = markerFile(root).readText()
            assertFalse(ciphertext.contains(actorId))
            assertFalse(ciphertext.contains(record.identity.actorHash))
            assertFalse(record.toString().contains(actorId))
            assertFalse(record.toString().contains(record.accessSecret))
            assertTrue(record.toString().contains("accessSecret=redacted"))
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(record),
                marker(root).read(),
            )
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(record),
                marker(root).read(),
            )
        }

    @Test
    fun legacyV3PayloadRemainsCanonicalAndCanBindVerifiedRecoveryActorOnce() =
        withMarkerRoot { root ->
            val actorId = "legacy.actor-01"
            val record = AndroidAccountDeletionFallbackMarker.Record(
                identity = identity("account_delete_legacy_0001", actorId),
                gatewayOrigin = "https://gateway.example.test",
                installationId = "installation-legacy-0001",
                accessSecret = "A".repeat(43),
                clientRevision = 1L,
                phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED,
            )
            val encodedOrigin = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(record.gatewayOrigin.toByteArray(Charsets.UTF_8))
            val legacy = buildString {
                append("walksafe-account-deletion-fallback-v3\n")
                append("request-id:${record.identity.requestId}\n")
                append("actor-sha256:${record.identity.actorHash}\n")
                append("gateway-origin:$encodedOrigin\n")
                append("installation-id:${record.installationId}\n")
                append("access-secret:${record.accessSecret}\n")
                append("client-revision:1\n")
                append("phase:PREPARED\n")
                append("accepted-at:-\n")
                append("account-generation:-\n")
                append("tombstone-id:-\n")
                append("request-receipt-sha256:-\n")
                append("status-revision:0\n")
                append("evidence-id:-\n")
                append("evidence-sha256:-\n")
                append("evidence-expected-status-revision:-\n")
                append("evidence-result:-\n")
                append("evidence-completed-at:-\n")
                append("completion-receipt-sha256:-\n")
            }.toByteArray(Charsets.UTF_8)
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(record),
                marker(root).decodeLegacyPlaintextForTest(legacy),
            )

            val legacyEnvelope = requireNotNull(
                VersionedLocalAead(TEST_KEY_POLICY, keyProvider).seal(
                    plaintext = legacy,
                    domainAad = LEGACY_MARKER_AAD,
                    limits = TEST_MARKER_LIMITS,
                ) as? AeadSealResult.Sealed,
            ).envelope
            markerFile(root).writeText(legacyEnvelope)
            assertFalse(legacyEnvelope.contains(actorId))
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(record),
                marker(root).read(),
            )
            val bound = record.copy(recoveryActorId = actorId)
            assertTrue(marker(root).update(bound))
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(bound),
                marker(root).read(),
            )
            assertFalse(
                marker(root).update(
                    bound.copy(recoveryActorId = "different.actor"),
                ),
            )
        }

    @Test
    fun acceptedTransitionPurgesRecoveryActorAndNeverAllowsRebinding() =
        withMarkerRoot { root ->
            val actorId = "actor-purge-01"
            val prepared = AndroidAccountDeletionFallbackMarker.Record(
                identity = identity("account_delete_actor_purge_0001", actorId),
                recoveryActorId = actorId,
                gatewayOrigin = "https://gateway.example.test",
                installationId = "installation-purge-0001",
                accessSecret = "A".repeat(43),
                clientRevision = 1L,
                phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED,
            )
            val accepted = prepared.copy(
                recoveryActorId = null,
                phase = AndroidAccountDeletionFallbackMarker.Phase.ACCEPTED,
                acceptedAt = "2026-08-10T00:00:00Z",
                accountGeneration = 1L,
                tombstoneId = "tombstone-purge-0001",
                requestReceiptSha256 = "1".repeat(64),
                statusRevision = 1L,
            )
            val marker = marker(root)

            assertTrue(marker.create(prepared))
            assertTrue(marker.update(accepted))
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(accepted),
                marker.read(),
            )
            assertFalse(marker.update(accepted.copy(recoveryActorId = actorId)))
            assertTrue(marker.update(accepted.copy(statusRevision = 2L)))
            assertEquals(
                null,
                (marker.read() as AndroidAccountDeletionFallbackMarker.State.Present)
                    .record.recoveryActorId,
            )
        }

    @Test
    fun exactIdentityIsIdempotentAcrossInstances() =
        withMarkerRoot { root ->
            val identity = identity("delete-request-3d4477ca", "actor-a")
            val first = marker(root)

            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Absent,
                first.read(),
            )
            assertTrue(first.create(identity))
            val rawMarker = markerFile(root).readText()
            assertFalse(rawMarker.contains(identity.requestId))
            assertFalse(rawMarker.contains(identity.actorHash))
            assertTrue(marker(root).create(identity))
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(
                    identity,
                ),
                marker(root).read(),
            )
        }

    @Test
    fun differentRequestOrActorCannotOverwriteEvidence() =
        withMarkerRoot { root ->
            val original =
                identity("delete-request-original", "actor-original")
            val marker = marker(root)
            assertTrue(marker.create(original))
            val evidence = markerFile(root).readBytes()

            assertFalse(
                marker.create(
                    original.copy(requestId = "delete-request-other"),
                ),
            )
            assertFalse(
                marker.create(
                    original.copy(actorHash = actorHash("actor-other")),
                ),
            )
            assertArrayEquals(evidence, markerFile(root).readBytes())
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(
                    original,
                ),
                marker.read(),
            )
        }

    @Test
    fun clearRequiresTheExactIdentity() = withMarkerRoot { root ->
        val original = identity("delete-request-clear", "actor-clear")
        val marker = marker(root)
        assertTrue(marker.create(original))

        assertFalse(
            marker.clear(
                original.copy(requestId = "delete-request-stale"),
            ),
        )
        assertFalse(
            marker.clear(
                original.copy(actorHash = actorHash("actor-stale")),
            ),
        )
        assertEquals(
            AndroidAccountDeletionFallbackMarker.State.Present(original),
            marker.read(),
        )
        assertTrue(marker.clear(original))
        assertEquals(
            AndroidAccountDeletionFallbackMarker.State.Absent,
            marker(root).read(),
        )
    }

    @Test
    fun keyResetPendingReconcilesEveryClearCrashBoundary() {
        repeat(5) { crashBoundary ->
            withMarkerRoot { root ->
                val original = identity("delete-request-crash-$crashBoundary", "actor-crash")
                assertTrue(marker(root).create(original))
                keyResetPendingFile(root).writeText("walksafe-key-reset-pending-v1\n")
                if (crashBoundary >= 1) assertTrue(markerFile(root).delete())
                val aead = VersionedLocalAead(TEST_KEY_POLICY, keyProvider)
                if (crashBoundary >= 2) assertTrue(aead.destroyKnownVersions())
                if (crashBoundary >= 3) assertTrue(aead.createFreshAfterVerifiedPurge())
                if (crashBoundary >= 4) assertTrue(keyResetPendingFile(root).delete())

                assertEquals(
                    AndroidAccountDeletionFallbackMarker.State.Absent,
                    marker(root).read(),
                )
                assertFalse(markerFile(root).exists())
                assertFalse(keyResetPendingFile(root).exists())
                assertTrue(marker(root).clear(original))
                assertTrue(
                    marker(root).create(
                        identity("delete-request-after-$crashBoundary", "actor-after"),
                    ),
                )
            }
        }
    }

    @Test
    fun oversizedOrHardlinkedKeyResetPendingProofFailsClosed() {
        withMarkerRoot { root ->
            keyResetPendingFile(root).writeBytes(ByteArray(4_096) { 'x'.code.toByte() })

            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Unavailable,
                marker(root).read(),
            )
            assertTrue(keyResetPendingFile(root).exists())
        }
        withMarkerRoot { root ->
            val target = File(root, "pending-proof-hardlink-target").apply {
                writeText("walksafe-key-reset-pending-v1\n")
            }
            Files.createLink(keyResetPendingFile(root).toPath(), target.toPath())

            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Unavailable,
                marker(root).read(),
            )
            assertEquals("walksafe-key-reset-pending-v1\n", target.readText())
        }
    }

    @Test
    fun corruptEvidenceIsPreservedAndCannotBeReplacedOrCleared() =
        withMarkerRoot { root ->
            val bytes = byteArrayOf(0xc3.toByte(), 0x28)
            markerFile(root).writeBytes(bytes)
            val marker = marker(root)
            val identity = identity("delete-request-corrupt", "actor-c")

            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Corrupt,
                marker.read(),
            )
            assertFalse(marker.create(identity))
            assertFalse(marker.clear(identity))
            assertArrayEquals(bytes, markerFile(root).readBytes())
        }

    @Test
    fun unicodeSurrogatesControlsAndInvalidActorHashesAreRejected() =
        withMarkerRoot { root ->
            val validHash = actorHash("actor-valid")
            val invalidRequestIds =
                listOf(
                    "",
                    "a".repeat(129),
                    "delete request",
                    "delete-\u2603",
                    "delete-\uD800",
                    "delete-\uFFFD",
                    "delete\nrequest",
                    "delete\u0000request",
                )
            invalidRequestIds.forEach { requestId ->
                assertFalse(
                    marker(root).create(
                        AndroidAccountDeletionFallbackMarker.Identity(
                            requestId,
                            validHash,
                        ),
                    ),
                )
            }
            listOf(
                validHash.uppercase(),
                validHash.dropLast(1),
                "g".repeat(64),
                "actor-plaintext",
            ).forEach { invalidHash ->
                assertFalse(
                    marker(root).create(
                        AndroidAccountDeletionFallbackMarker.Identity(
                            "delete-request-valid",
                            invalidHash,
                        ),
                    ),
                )
            }
            assertFalse(markerFile(root).exists())
        }

    @Test
    fun markerSymlinkFailsClosedWithoutTouchingItsTarget() =
        withMarkerRoot { root ->
            val target = File(root, "outside-evidence").apply {
                writeText("keep")
            }
            Files.createSymbolicLink(
                markerFile(root).toPath(),
                target.toPath(),
            )
            val marker = marker(root)
            val identity = identity("delete-request-link", "actor-link")

            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Corrupt,
                marker.read(),
            )
            assertFalse(marker.create(identity))
            assertFalse(marker.clear(identity))
            assertEquals("keep", target.readText())
            assertTrue(Files.isSymbolicLink(markerFile(root).toPath()))
        }

    @Test
    fun externalLockContentionUsesFiniteSharedBudget() =
        withMarkerRoot { root ->
            val lockFile = lockFile(root)
            FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                val held = channel.lock()
                try {
                    val marker = marker(root, lockTimeoutMillis = 30L)
                    assertEquals(
                        AndroidAccountDeletionFallbackMarker.State
                            .Unavailable,
                        marker.read(),
                    )
                    assertFalse(
                        marker.create(
                            identity(
                                "delete-request-contention",
                                "actor-lock",
                            ),
                        ),
                    )
                } finally {
                    held.release()
                }
            }

            assertTrue(
                marker(root).create(
                    identity(
                        "delete-request-after-lock",
                        "actor-after-lock",
                    ),
                ),
            )
        }

    @Test
    fun twoInstancesSerializeReadCheckWrite() = withMarkerRoot { root ->
        val firstIdentity =
            identity("delete-request-first", "actor-first")
        val secondIdentity =
            identity("delete-request-second", "actor-second")
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val defaults =
            AndroidAccountDeletionFallbackMarker.Operations()
        val first =
            marker(
                root,
                operations =
                    AndroidAccountDeletionFallbackMarker.Operations(
                        writeAndSync = { file, bytes ->
                            enteredWrite.countDown()
                            if (!releaseWrite.await(
                                    2,
                                    TimeUnit.SECONDS,
                                )
                            ) {
                                throw IOException("test timed out")
                            }
                            defaults.writeAndSync(file, bytes)
                        },
                    ),
            )
        val second = marker(root)
        val firstResult = AtomicReference<Boolean?>()
        val secondResult = AtomicReference<Boolean?>()

        val firstThread =
            thread(start = true) {
                firstResult.set(first.create(firstIdentity))
            }
        assertTrue(enteredWrite.await(1, TimeUnit.SECONDS))
        val secondThread =
            thread(start = true) {
                secondResult.set(second.create(secondIdentity))
            }
        releaseWrite.countDown()
        firstThread.join(3_000L)
        secondThread.join(3_000L)

        assertFalse(firstThread.isAlive)
        assertFalse(secondThread.isAlive)
        assertEquals(true, firstResult.get())
        assertEquals(false, secondResult.get())
        assertEquals(
            AndroidAccountDeletionFallbackMarker.State.Present(
                firstIdentity,
            ),
            marker(root).read(),
        )
    }

    @Test
    fun atomicMoveUnavailableNeverFallsBackToNonAtomicReplace() =
        withMarkerRoot { root ->
            val marker =
                marker(
                    root,
                    operations =
                        AndroidAccountDeletionFallbackMarker.Operations(
                            atomicMove = { source, target ->
                                throw AtomicMoveNotSupportedException(
                                    source.path,
                                    target.path,
                                    "test filesystem",
                                )
                            },
                        ),
                )

            assertFalse(
                marker.create(
                    identity(
                        "delete-request-no-atomic",
                        "actor-no-atomic",
                    ),
                ),
            )
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Absent,
                marker(root).read(),
            )
            assertNoTemporaryFiles(root)
        }

    @Test
    fun failedMoveCleansTemporaryFileAndAllowsExactRetry() =
        withMarkerRoot { root ->
            val attempts = AtomicInteger()
            val defaults =
                AndroidAccountDeletionFallbackMarker.Operations()
            val marker =
                marker(
                    root,
                    operations =
                        AndroidAccountDeletionFallbackMarker.Operations(
                            atomicMove = { source, target ->
                                if (attempts.incrementAndGet() == 1) {
                                    throw IOException("move failed")
                                }
                                defaults.atomicMove(source, target)
                            },
                        ),
                )
            val identity =
                identity("delete-request-retry", "actor-retry")

            assertFalse(marker.create(identity))
            assertNoTemporaryFiles(root)
            assertTrue(marker.create(identity))
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(
                    identity,
                ),
                marker.read(),
            )
            assertNoTemporaryFiles(root)
        }

    @Test
    fun existingExactCreateRerunsFileAndDirectoryDurabilityBarriers() =
        withMarkerRoot { root ->
            val defaults = AndroidAccountDeletionFallbackMarker.Operations()
            var directorySyncs = 0
            var fileSyncs = 0
            val marker = marker(
                root,
                operations = AndroidAccountDeletionFallbackMarker.Operations(
                    syncFile = { file ->
                        fileSyncs += 1
                        defaults.syncFile(file)
                    },
                    syncDirectory = { directory ->
                        directorySyncs += 1
                        if (directorySyncs <= 2) throw IOException("sync failed")
                        defaults.syncDirectory(directory)
                    },
                ),
            )
            val identity = identity(
                "delete-request-existing-create",
                "actor-existing-create",
            )

            assertFalse(marker.create(identity))
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(identity),
                marker(root).read(),
            )
            assertFalse(marker.create(identity))
            assertTrue(marker.create(identity))
            assertEquals(3, fileSyncs)
            assertEquals(3, directorySyncs)
        }

    @Test
    fun existingExactUpdateRerunsFileAndDirectoryDurabilityBarriers() =
        withMarkerRoot { root ->
            val actorId = "actor-existing-update"
            val originalIdentity = identity(
                "delete-request-existing-update",
                actorId,
            )
            val initialMarker = marker(root)
            assertTrue(initialMarker.create(originalIdentity))
            val original =
                (initialMarker.read()
                    as AndroidAccountDeletionFallbackMarker.State.Present).record
            val bound = original.copy(recoveryActorId = actorId)
            val defaults = AndroidAccountDeletionFallbackMarker.Operations()
            var directorySyncs = 0
            var fileSyncs = 0
            val retryingMarker = marker(
                root,
                operations = AndroidAccountDeletionFallbackMarker.Operations(
                    syncFile = { file ->
                        fileSyncs += 1
                        defaults.syncFile(file)
                    },
                    syncDirectory = { directory ->
                        directorySyncs += 1
                        if (directorySyncs <= 2) throw IOException("sync failed")
                        defaults.syncDirectory(directory)
                    },
                ),
            )

            assertFalse(retryingMarker.update(bound))
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(bound),
                marker(root).read(),
            )
            assertFalse(retryingMarker.update(bound))
            assertTrue(retryingMarker.update(bound))
            assertEquals(3, fileSyncs)
            assertEquals(3, directorySyncs)
        }

    @Test
    fun pendingEvidenceRevisionRebasePreservesTheLocalDeletionFact() =
        withMarkerRoot { root ->
            val marker = marker(root)
            val completedAt = "2026-07-25T12:05:00Z"
            val original = AndroidAccountDeletionFallbackMarker.Record(
                identity = identity(
                    "account_delete_request_0001",
                    "actor-rebase",
                ),
                gatewayOrigin = "https://gateway.example.test",
                installationId = "installation-rebase-0001",
                accessSecret = "A".repeat(43),
                clientRevision = 1L,
                phase =
                AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_PENDING,
                acceptedAt = "2026-07-25T12:00:00Z",
                accountGeneration = 1L,
                tombstoneId = "tombstone-rebase-0001",
                requestReceiptSha256 = "1".repeat(64),
                statusRevision = 1L,
                evidenceId = "device-evidence-0001",
                evidenceSha256 = "2".repeat(64),
                evidenceExpectedStatusRevision = 1L,
                evidenceResult = "DELETED",
                evidenceCompletedAt = completedAt,
            )
            assertTrue(marker.create(original))

            val rebased = original.copy(
                statusRevision = 2L,
                evidenceId = "device-evidence-0002",
                evidenceSha256 = "3".repeat(64),
                evidenceExpectedStatusRevision = 2L,
            )
            assertTrue(marker.update(rebased))
            assertEquals(
                AndroidAccountDeletionFallbackMarker.State.Present(rebased),
                marker.read(),
            )
            assertFalse(
                marker.update(
                    rebased.copy(
                        statusRevision = 3L,
                        evidenceId = "device-evidence-0003",
                        evidenceSha256 = "4".repeat(64),
                        evidenceExpectedStatusRevision = 3L,
                        evidenceResult = "NOT_FOUND",
                    ),
                ),
            )
            assertFalse(
                marker.update(
                    rebased.copy(
                        statusRevision = 3L,
                        evidenceId = "device-evidence-0003",
                        evidenceSha256 = "4".repeat(64),
                        evidenceExpectedStatusRevision = 4L,
                    ),
                ),
            )
        }

    private fun marker(
        root: File,
        operations: AndroidAccountDeletionFallbackMarker.Operations =
            AndroidAccountDeletionFallbackMarker.Operations(),
        lockTimeoutMillis: Long =
            AndroidAccountDeletionFallbackMarker.LOCK_TIMEOUT_MILLIS,
    ): AndroidAccountDeletionFallbackMarker =
        AndroidAccountDeletionFallbackMarker(
            markerFile = markerFile(root),
            operations = operations,
            lockTimeoutNanos =
                TimeUnit.MILLISECONDS.toNanos(lockTimeoutMillis),
            aead = VersionedLocalAead(TEST_KEY_POLICY, keyProvider),
        )

    private fun identity(
        requestId: String,
        actor: String,
    ): AndroidAccountDeletionFallbackMarker.Identity =
        AndroidAccountDeletionFallbackMarker.Identity(
            requestId = requestId,
            actorHash = actorHash(actor),
        )

    private fun actorHash(actor: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(actor.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun markerFile(root: File): File =
        File(
            root,
            AndroidAccountDeletionFallbackMarker.MARKER_FILE_NAME,
        )

    private fun lockFile(root: File): File =
        File(
            root,
            AndroidAccountDeletionFallbackMarker.LOCK_FILE_NAME,
        )

    private fun keyResetPendingFile(root: File): File = File(
        root,
        AndroidAccountDeletionFallbackMarker.KEY_RESET_PENDING_FILE_NAME,
    )

    private fun assertNoTemporaryFiles(root: File) {
        assertNull(
            root.listFiles()?.firstOrNull { file ->
                file.name.startsWith(
                    AndroidAccountDeletionFallbackMarker.MARKER_FILE_NAME,
                ) && file.name.endsWith(".new")
            },
        )
    }

    private fun withMarkerRoot(block: (File) -> Unit) {
        val root =
            Files.createTempDirectory(
                "walksafe-account-deletion-fallback",
            ).toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class FakeKeyProvider : LocalAeadKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        private val generationTombstones = mutableSetOf<String>()

        @Synchronized
        override fun getOrCreate(alias: String): SecretKey? {
            if (alias !in keys && alias in generationTombstones) return null
            generationTombstones += alias
            return keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }

        @Synchronized
        override fun getExisting(alias: String): SecretKey? = keys[alias]

        @Synchronized
        override fun delete(alias: String): Boolean {
            generationTombstones += alias
            keys.remove(alias)
            return true
        }

        override fun createFreshAfterVerifiedPurge(alias: String): SecretKey {
            generationTombstones += alias
            return keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }
    }

    private companion object {
        val TEST_KEY_POLICY = AeadKeyPolicy(
            aliasPrefix = "walksafe.test.deletion_fallback.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        )
        val LEGACY_MARKER_AAD =
            (
                "kr.co.hanium.dreamup.walksafe|USER|no-backup|" +
                    "account-deletion-fallback|schema=4"
                ).toByteArray(Charsets.UTF_8)
        val TEST_MARKER_LIMITS = AeadLimits(
            maxPlaintextBytes = 4_096,
            maxCiphertextBytes = 4_112,
            maxEnvelopeChars = 8_192,
        )
    }
}
