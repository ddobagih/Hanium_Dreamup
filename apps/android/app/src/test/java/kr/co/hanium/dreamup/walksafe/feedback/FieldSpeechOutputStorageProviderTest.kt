package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.fieldlog.FieldSessionDeviceInfo
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog.SpeechStorageAccess
import kr.co.hanium.dreamup.walksafe.fieldlog.TestFieldAead
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FieldSpeechOutputStorageProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun approvedFactoryIsLazyAndAccessorUsesActualFieldRootProfileAndCryptoClass() {
        val files = temporary.newFolder()
        val provider = FieldSpeechOutputStorageProvider.create(files)
        assertFalse(File(files, "field_sessions").exists())
        provider.blockNewProcessingForAccountDeletion()
        assertTrue(provider.associatedData().isEmpty())
        assertFalse(provider.appendEncrypted("untrusted"))
        assertFalse(File(files, "field_sessions").exists())
        // Construction only: AndroidKeyStore seal/open/delete/fresh APIs are NOT executed here.
        val access = SpeechStorageAccess.create(files)
        assertTrue(access.aead is AndroidKeyStoreAead)
        assertEquals(File(files, "field_sessions"), access.rootDirectory)
        assertEquals(4L * 1_024 * 1_024, access.profile.maxSegmentBytes)
        assertEquals(32L * 1_024 * 1_024, access.profile.maxWindowBytes)
        assertEquals(20, access.profile.maxRetainedWindows)
        assertEquals(14L * 24 * 60 * 60 * 1_000, access.profile.maxWindowAgeMs)
        assertEquals(access.profile.maxWindowAgeMs, access.profile.maxActiveWindowAgeMs)
        assertEquals(AeadLimits(512 * 1_024, 512 * 1_024 + 16, 704 * 1_024), access.recordLimits)
        assertTrue(access.rootDirectory.listFiles()!!.isEmpty())
    }

    @Test fun storeWritesAuthenticatedFixedFieldsAndOriginalClockWithoutArchiveMetadata() {
        val f = fixture()
        val accepted = CountDownLatch(2)
        val storage = object : SpeechOutputEncryptedStorage by f.provider {
            override fun appendEncrypted(envelope: String): Boolean =
                f.provider.appendEncrypted(envelope).also { if (it) accepted.countDown() }
        }
        val binding = binding()
        val store = ConsentBoundSpeechOutputStore(storage) { it === binding }
        assertTrue(store.publish(binding))
        store.enqueue(binding, SpeechOutputEvent(SpeechOutputKind.values().first(),
            SpeechOutputOutcome.ENGINE_ERROR, 202L, -3))
        store.enqueue(binding, SpeechOutputEvent(SpeechOutputKind.values().last(),
            SpeechOutputOutcome.values().first(), 99L, null))
        assertTrue(accepted.await(10, TimeUnit.SECONDS))
        val records = read(f)
        assertEquals(listOf(202L, 99L), records.map { it.getLong("elapsed_realtime_ms") })
        assertEquals(listOf(-3, "NONE"), records.map { it.get("result") })
        records.forEach { assertEquals(setOf("metric", "state", "elapsed_realtime_ms", "result"),
            it.keys().asSequence().toSet()) }
        val file = recordFiles(f).single()
        assertFalse(file.readText().contains("elapsed_realtime_ms"))
        assertEquals(setOf("speech-output"), f.root.listFiles()!!.map(File::getName).toSet())
        assertFalse(f.root.walkTopDown().any { it.name == "manifest.json" || it.name == "active_session.txt" })
        assertEquals(0L, store.droppedEventCount())
        System.getenv("WALKSAFE_SPEECH_PROVIDER_EVIDENCE")?.let { destination ->
            val directory = File(destination).apply { mkdirs() }
            file.copyTo(File(directory, "encrypted-fixed-events.jsonl"), overwrite = false)
            File(directory, "decrypted-fixed-events.jsonl").writeText(
                records.joinToString("\n", postfix = "\n") { it.toString() })
            File(directory, "proof.txt").writeText("${file.parentFile!!.name}; clocks=202,99; result=-3,NONE; fields=4\n")
        }
        store.close()
    }

    @Test fun validSpeechArtifactCoexistsWithNormalDebugArchiveAndNormalRestore() {
        val f = fixture()
        val debug = debug(f, "debug-existing")
        assertEquals("debug-existing", debug.start())
        debug.stop()
        assertTrue(write(f.provider))
        val resumed = debug(f, "debug-next")
        assertEquals("debug-next", resumed.start())
        assertTrue(resumed.isActive())
        resumed.stop()
        assertEquals(1, read(f).size)
        assertEquals("completed", f.crypto.readManifest(f.root, "debug-existing").getString("status"))
        assertTrue(f.crypto.readRecords(File(f.root, "debug-existing")).any {
            it.optString("event_name") == "session_started" })
    }

    @Test fun privacyOnlyAccessorDoesNotRestoreOrAppendMetadataToExistingActiveDebugSession() {
        val f = fixture()
        val original = debug(f, "debug-active")
        assertEquals("debug-active", original.start())
        val directory = File(f.root, "debug-active")
        val before = f.crypto.readRecords(directory).map(JSONObject::toString)
        val privacyAccess = SpeechStorageAccess.create(f.files, aead = f.crypto.aead,
            nowMillis = { f.now })
        assertEquals(before, f.crypto.readRecords(directory).map(JSONObject::toString))
        assertTrue(write(FieldSpeechOutputStorageProvider(privacyAccess)))
        assertEquals(before, f.crypto.readRecords(directory).map(JSONObject::toString))
        assertTrue(original.isActive())
        // The ordinary default initialization still resumes the DEBUG session alongside valid A.
        val resumed = debug(f, "unused-new-id")
        assertEquals("debug-active", resumed.activeSessionId())
        assertTrue(f.crypto.readRecords(directory).any {
            it.optString("event_name") == "session_resumed_after_app_restart" })
        resumed.stop()
    }

    @Test fun initialAccountAndRawAuthorityFencesCreateNoSpeechArtifact() {
        listOf("account", "raw").forEach { boundary ->
            val files = temporary.newFolder()
            val crypto = TestFieldAead()
            val access = SpeechStorageAccess.create(files, aead = crypto.aead,
                initiallyBlockedForAccountDeletion = boundary == "account",
                initiallyBlockedForRawSourceCollection = boundary == "raw")
            val provider = FieldSpeechOutputStorageProvider(access)
            assertFalse(write(provider))
            assertFalse(File(access.rootDirectory, "speech-output").exists())
            assertFalse(crypto.hasKey())
            val marker = if (boundary == "account") "account_deletion_blocked.txt"
                else "raw_source_collection_blocked.txt"
            assertTrue(File(access.rootDirectory, marker).isFile)
        }
    }

    @Test fun speechAndDebugArchivesConsumeOneSharedRetentionSet() {
        val f = fixture()
        val debug = debug(f, "debug-oldest")
        debug.start(); debug.stop()
        repeat(f.access.profile.maxRetainedWindows) {
            f.now += 1_000L
            assertTrue(write(FieldSpeechOutputStorageProvider(f.access), it.toLong()))
        }
        assertFalse(File(f.root, "debug-oldest").exists())
        val artifact = File(f.root, "speech-output")
        assertEquals(20, artifact.listFiles()!!.size)
        f.now += 1_000L
        val nextDebug = debug(f, "debug-newest")
        nextDebug.start(); nextDebug.stop()
        // Original field behavior prunes before start; trigger its next shared prune explicitly.
        assertTrue(f.access.prepareExistingArchives())
        assertEquals(19, artifact.listFiles()!!.size)
        assertTrue(File(f.root, "debug-newest").isDirectory)
        assertEquals(20, artifact.listFiles()!!.size + 1)
    }

    @Test fun appendPreservesWindowCreationAgeAndExpiryDoesNotReviveOldWindow() {
        val f = fixture()
        val created = f.now
        assertTrue(write(f.provider, 1L))
        val window = recordFiles(f).single().parentFile!!
        f.now = created + f.access.profile.maxActiveWindowAgeMs - 1L
        assertTrue(write(f.provider, 2L))
        assertEquals(created, window.lastModified())
        f.now += 2L
        assertFalse(write(f.provider, 3L))
        assertFalse(window.exists())
        assertFalse(write(f.provider, 4L))
        assertTrue(write(FieldSpeechOutputStorageProvider(f.access), 5L))
        assertEquals(listOf(5L), read(f).map { it.getLong("elapsed_realtime_ms") })
    }

    @Test fun unknownOrMalformedArtifactsFailClosedForSpeechAndDebugStart() {
        listOf("unknown-root", "artifact-file", "empty-artifact", "empty-window", "unknown-segment",
            "truncated", "wrong-aad", "extra-field", "segment-link", "window-link", "hard-link")
            .forEach { defect ->
                val f = fixture()
                if (defect == "unknown-root") File(f.root, "unknown").mkdir()
                else if (defect == "artifact-file") File(f.root, "speech-output").writeText("bad")
                else if (defect == "empty-artifact") File(f.root, "speech-output").mkdir()
                else if (defect == "empty-window") File(f.root, "speech-output/window-0000000000000001").mkdirs()
                else {
                    assertTrue(write(f.provider))
                    val file = recordFiles(f).single()
                    when (defect) {
                        "unknown-segment" -> File(file.parentFile, "unknown.txt").writeText("bad")
                        "truncated" -> RandomAccessFile(file, "rw").use { it.setLength(file.length() - 1L) }
                        "wrong-aad" -> file.writeText(seal(f, record(), byteArrayOf(1)) + "\n")
                        "extra-field" -> file.writeText(seal(f, record().put("user", "forbidden"),
                            SpeechStorageAccess.recordAad(file.parentFile!!.name.removePrefix("window-").toLong(), 0L)) + "\n")
                        "segment-link" -> { file.delete(); Files.createSymbolicLink(file.toPath(),
                            File(f.files, "missing").toPath()) }
                        "window-link" -> { val window = file.parentFile!!; window.deleteRecursively()
                            Files.createSymbolicLink(window.toPath(), File(f.files, "missing").toPath()) }
                        "hard-link" -> Files.createLink(File(f.files, "other-link").toPath(), file.toPath())
                    }
                }
                val next = FieldSpeechOutputStorageProvider(f.access)
                assertFalse("speech defect=$defect", write(next))
                assertTrue("crypto defect=$defect", File(f.root, "field_log_crypto_blocked.txt").isFile)
                assertNull("debug defect=$defect", debug(f, "debug-denied").start())
                assertFalse(File(f.root, "debug-denied").exists())
            }
    }

    @Test fun actualSegmentAndWindowBoundsRejectOversizedArtifactsWithoutLargeCopies() {
        listOf("segment", "window").forEach { boundary ->
            val f = fixture()
            assertTrue(write(f.provider))
            val first = recordFiles(f).single()
            if (boundary == "segment") RandomAccessFile(first, "rw").use {
                it.setLength(f.access.profile.maxSegmentBytes + 1L)
            } else {
                // Sparse segments exercise actual aggregate bound; no 32 MiB payload copies.
                for (index in 1..9) RandomAccessFile(File(first.parentFile,
                    "records-%04d.jsonl".format(index)), "rw").use {
                    it.setLength(f.access.profile.maxSegmentBytes)
                }
            }
            assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
            assertTrue(File(f.root, "field_log_crypto_blocked.txt").exists())
        }
        val f = fixture()
        assertFalse(f.provider.appendEncrypted("x".repeat((f.access.profile.maxSegmentBytes + 1).toInt())))
        assertTrue(recordFiles(f).isEmpty())
    }

    @Test fun realProfileRotatesSegmentsAndStopsAtSharedWindowCapacity() {
        val f = fixture()
        // Test-only JSON whitespace keeps the same four enum/primitive fields. Larger authenticated
        // records exercise the real 4/32 MiB profile with tens of records, not a copied tiny policy.
        val plaintext = (record().toString() + " ".repeat(500 * 1_024)).toByteArray()
        var accepted = 0
        while (true) {
            val sealed = f.provider.aead.seal(plaintext, f.provider.associatedData(), f.provider.limits)
                as AeadSealResult.Sealed
            if (!f.provider.appendEncrypted(sealed.envelope)) break
            accepted++
            assertTrue(accepted < 100)
        }
        plaintext.fill(0)
        val files = recordFiles(f)
        assertTrue(files.size > 1)
        assertTrue(files.all { it.length() <= f.access.profile.maxSegmentBytes })
        val bytes = files.sumOf(File::length)
        assertTrue(bytes <= f.access.profile.maxWindowBytes)
        assertEquals(accepted, read(f).size)
        assertFalse(File(f.root, "field_log_crypto_blocked.txt").exists())
        // The rejected padded record consumes no ordinal: a small fixed event can use the remainder.
        assertTrue(write(f.provider, 777L))
        assertEquals(777L, read(f).last().getLong("elapsed_realtime_ms"))
        System.getenv("WALKSAFE_SPEECH_PROVIDER_EVIDENCE")?.let { destination ->
            File(destination, "capacity-proof.txt").writeText(
                "accepted=$accepted; segments=${files.size}; bytes=$bytes; " +
                    "segmentLimit=${f.access.profile.maxSegmentBytes}; windowLimit=${f.access.profile.maxWindowBytes}\n")
        }
    }

    @Test fun verifiedPurgeDeletesSharedRootAndKeyAndRequiresFreshReset() {
        val f = fixture()
        val debug = debug(f, "debug-delete"); debug.start(); debug.stop()
        assertTrue(write(f.provider))
        assertTrue(f.crypto.hasKey())
        assertTrue(f.provider.purgeAll())
        assertEquals(setOf("field_key_purge_verified.txt", "account_deletion_blocked.txt"),
            f.root.listFiles()!!.map(File::getName).toSet())
        assertFalse(f.crypto.hasKey())
        assertTrue(f.crypto.aead.seal(byteArrayOf(1), byteArrayOf(1), f.access.recordLimits)
            !is AeadSealResult.Sealed)
        assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
        assertFalse(f.provider.resetRawSourceAfterConfirmedConsent())
        assertTrue(f.provider.resetForNewEnrollment())
        assertTrue(f.crypto.hasKey())
        assertTrue(f.root.listFiles()!!.isEmpty())
        assertFalse(write(f.provider))
        assertTrue(write(FieldSpeechOutputStorageProvider(f.access), 8L))
        assertEquals(listOf(8L), read(f).map { it.getLong("elapsed_realtime_ms") })
    }

    @Test fun failedKeyDeletionOrDurabilityNeverProducesVerifiedPurgeOrSuccessfulReset() {
        listOf("key", "directory-sync").forEach { failure ->
            val syncFails = AtomicBoolean()
            val f = fixture(sync = { !syncFails.get() })
            assertTrue(write(f.provider))
            if (failure == "key") f.crypto.failNextDelete() else syncFails.set(true)
            assertFalse("purge failure=$failure", f.provider.purgeAll())
            assertFalse(File(f.root, "field_key_purge_verified.txt").exists())
            assertTrue(File(f.root, "field_log_crypto_blocked.txt").exists())
            assertFalse(f.provider.resetForNewEnrollment())
            assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
        }
    }

    @Test fun freshKeyFailureIsObservableAndOnlyExplicitVerifiedResetCanRecover() {
        val f = fixture()
        assertTrue(write(f.provider)); assertTrue(f.provider.purgeAll())
        f.crypto.failNextFreshCreate()
        assertFalse(f.provider.resetForNewEnrollment())
        assertFalse(f.crypto.hasKey())
        assertTrue(File(f.root, "field_key_purge_verified.txt").exists())
        assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
        assertTrue(f.provider.resetForNewEnrollment())
        assertTrue(f.crypto.hasKey())
        assertFalse(write(f.provider))
        assertTrue(write(FieldSpeechOutputStorageProvider(f.access)))
    }

    @Test fun enrollmentResetDoesNotExemptSpeechResidualOrUnknownArtifactOrCorruptMarker() {
        listOf("speech", "unknown", "marker").forEach { residual ->
            val f = fixture()
            assertTrue(write(f.provider)); assertTrue(f.provider.purgeAll())
            when (residual) {
                "speech" -> File(f.root, "speech-output/window-0000000000000001").mkdirs()
                "unknown" -> File(f.root, "unknown.txt").writeText("residual")
                "marker" -> File(f.root, "field_key_purge_verified.txt").writeText("unverified")
            }
            assertFalse(f.provider.resetForNewEnrollment())
            assertFalse(f.crypto.hasKey())
            assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
        }
    }

    @Test fun actualPersistentPrivacyFencesBlockAndRawResetNeverRevivesOldProvider() {
        val f = fixture()
        assertTrue(write(f.provider))
        assertTrue(f.provider.blockForRawSourceWithdrawal())
        assertTrue(File(f.root, "raw_source_collection_blocked.txt").exists())
        assertFalse(write(f.provider)); assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
        assertTrue(f.provider.resetRawSourceAfterConfirmedConsent())
        assertFalse(write(f.provider))
        val fresh = FieldSpeechOutputStorageProvider(f.access)
        assertTrue(write(fresh))
        assertTrue(fresh.blockForAccountDeletion())
        assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
        assertFalse(fresh.resetRawSourceAfterConfirmedConsent())
    }

    @Test fun serialPurgeAfterInFlightAppendRemovesAllDataAndCloseCannotRecreateIt() {
        val f = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = object : SpeechOutputEncryptedStorage by f.provider {
            override fun appendEncrypted(envelope: String): Boolean {
                // This task has passed the last privacy check and actually entered provider I/O.
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return f.provider.appendEncrypted(envelope)
            }
        }
        val binding = binding()
        val store = ConsentBoundSpeechOutputStore(storage) { it === binding }
        assertTrue(store.publish(binding))
        store.enqueue(binding, event(1L))
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        store.enqueue(binding, event(2L))
        val purged = store.invalidateAndPurge()
        store.close()
        assertFalse(purged.isDone)
        release.countDown()
        assertTrue(purged.get(10, TimeUnit.SECONDS))
        assertTrue(recordFiles(f).isEmpty())
        assertFalse(File(f.root, "speech-output").exists())
        assertFalse(f.crypto.hasKey())
        assertEquals(1L, store.droppedEventCount())
        assertFalse(store.publish(binding()))
        store.enqueue(binding, event(3L))
        assertTrue(recordFiles(f).isEmpty())
        assertFalse(write(f.provider))
    }

    @Test fun twoProvidersReserveBeforeFirstAppendAndBothPreserveNormalWindowsAndDebug() {
        val f = fixture()
        val a = f.provider
        val b = FieldSpeechOutputStorageProvider(f.access)
        // Exact public API ordering from independent review, same approved root/key/profile.
        val aadA = a.associatedData()
        val aadB = b.associatedData()
        val firstA = a.aead.seal(record(101L).toString().toByteArray(), aadA, a.limits)
            as AeadSealResult.Sealed
        val acceptedA = a.appendEncrypted(firstA.envelope)
        val firstB = b.aead.seal(record(202L).toString().toByteArray(), aadB, b.limits)
            as AeadSealResult.Sealed
        val acceptedB = b.appendEncrypted(firstB.envelope)
        val followupA = write(a, 303L)
        val followupB = write(b, 404L)
        val debug = debug(f, "debug-after-reservations")
        val debugStarted = debug.start()
        if (debugStarted != null) debug.stop()
        val records = read(f)
        val cryptoBlocked = File(f.root, "field_log_crypto_blocked.txt").exists()
        val windows = File(f.root, "speech-output").listFiles()!!.map(File::getName).sorted()
        val clocks = records.map { it.getLong("elapsed_realtime_ms") }
        // Preserve every actual observation before assertions, including on the original RED.
        System.getenv("WALKSAFE_SPEECH_PROVIDER_EVIDENCE")?.let { destination ->
            val directory = File(destination).apply { mkdirs() }
            File(directory, "reservation-counter.json").writeText(JSONObject()
                .put("sameAad", aadA.contentEquals(aadB)).put("acceptedA", acceptedA)
                .put("acceptedB", acceptedB).put("followupA", followupA).put("followupB", followupB)
                .put("debugStarted", debugStarted ?: "NONE").put("cryptoBlocked", cryptoBlocked)
                .put("windows", org.json.JSONArray(windows)).put("clocks", org.json.JSONArray(clocks))
                .toString() + "\n")
        }
        assertTrue(acceptedA)
        assertTrue("normal B first append must succeed", acceptedB)
        assertTrue(followupA)
        assertTrue(followupB)
        assertEquals("debug-after-reservations", debugStarted)
        assertFalse(cryptoBlocked)
        assertFalse(aadA.contentEquals(aadB))
        assertEquals(2, windows.size)
        assertEquals(listOf(101L, 303L, 202L, 404L), clocks)
    }

    @Test fun authenticatedDiskHighWatermarkIsHonoredAlongsideProcessReservations() {
        val f = fixture()
        assertTrue(write(f.provider, 1L))
        val originalFile = recordFiles(f).single()
        val originalWindow = originalFile.parentFile!!
        val oldNumber = originalWindow.name.removePrefix("window-").toLong()
        // Simulate an authenticated retained window from a prior process above this process scalar.
        val persistedNumber = oldNumber + 10L
        val persisted = File(originalWindow.parentFile, "window-%016d".format(persistedNumber))
        assertTrue(originalWindow.renameTo(persisted))
        File(persisted, originalFile.name).writeText(seal(f, record(1L),
            SpeechStorageAccess.recordAad(persistedNumber, 0L)) + "\n")
        assertTrue(persisted.setLastModified(f.now))
        val b = FieldSpeechOutputStorageProvider(f.access)
        val c = FieldSpeechOutputStorageProvider(f.access)
        b.associatedData(); c.associatedData()
        assertTrue(write(b, 2L)); assertTrue(write(c, 3L))
        val numbers = File(f.root, "speech-output").listFiles()!!.map {
            it.name.removePrefix("window-").toLong() }.sorted()
        assertEquals(listOf(persistedNumber, persistedNumber + 1L, persistedNumber + 2L), numbers)
        assertEquals(listOf(1L, 2L, 3L), read(f).map { it.getLong("elapsed_realtime_ms") })
        assertFalse(File(f.root, "field_log_crypto_blocked.txt").exists())
    }

    @Test fun reservedNumbersAreNotReusedAfterSerialPurgeAndVerifiedFreshReset() {
        val f = fixture()
        val a = f.provider
        val b = FieldSpeechOutputStorageProvider(f.access)
        a.associatedData()
        val reservedB = b.associatedData()
        assertTrue(write(a, 1L))
        // Actual Main must fence every participant before cleanup; this test models that boundary.
        b.blockNewProcessingForAccountDeletion()
        assertTrue(a.purgeAll())
        assertTrue(a.resetForNewEnrollment())
        val c = FieldSpeechOutputStorageProvider(f.access)
        val freshC = c.associatedData()
        assertFalse(reservedB.contentEquals(freshC))
        assertFalse(write(a)); assertFalse(write(b))
        assertTrue(write(c, 3L))
        assertEquals(listOf(3L), read(f).map { it.getLong("elapsed_realtime_ms") })
        assertFalse(File(f.root, "field_log_crypto_blocked.txt").exists())
    }

    @Test fun sameAccountRawPurgeAndReconsentCreatesFreshWritableWindow() {
        val f = fixture()
        val debug = debug(f, "debug-before-raw")
        assertEquals("debug-before-raw", debug.start()); debug.stop()
        assertTrue(write(f.provider, 201L))
        assertTrue(f.provider.blockForRawSourceWithdrawal())
        val purged = f.provider.purgeForRawSourceWithdrawal()
        val accountBlocked = File(f.root, "account_deletion_blocked.txt").exists()
        val verified = File(f.root, "field_key_purge_verified.txt").exists()
        val filesAfterPurge = recordFiles(f).size
        val keyAfterPurge = f.crypto.hasKey()
        val writable = f.provider.resetAfterConfirmedRawConsent { false }
        val fresh = FieldSpeechOutputStorageProvider(f.access)
        val appended = write(fresh, 303L)
        val debugNext = debug(f, "debug-after-raw")
        val started = debugNext.start()
        if (started != null) debugNext.stop()
        val clocks = read(f).map { it.getLong("elapsed_realtime_ms") }
        System.getenv("WALKSAFE_SPEECH_PROVIDER_EVIDENCE")?.let { destination ->
            val directory = File(destination).apply { mkdirs() }
            File(directory, "raw-counter.json").writeText(JSONObject()
                .put("purged", purged).put("accountBlocked", accountBlocked)
                .put("verified", verified).put("filesAfterPurge", filesAfterPurge)
                .put("keyAfterPurge", keyAfterPurge).put("writable", writable)
                .put("appended", appended).put("debugStarted", started ?: "NONE")
                .put("clocks", org.json.JSONArray(clocks)).toString() + "\n")
        }
        assertTrue(purged)
        assertFalse("RAW withdrawal must not create an account deletion fence", accountBlocked)
        assertTrue(verified)
        assertEquals(0, filesAfterPurge); assertFalse(keyAfterPurge)
        assertTrue("same-account confirmed reconsent must restore actual writable storage", writable)
        assertTrue(appended)
        assertEquals("debug-after-raw", started)
        assertEquals(listOf(303L), clocks)
        assertFalse(write(f.provider))
    }

    @Test fun rawAdapterReportsFirstHealthyGrantWritableAndPreservesLegacyChangedBoolean() {
        val f = fixture()
        assertFalse(f.provider.resetRawSourceAfterConfirmedConsent())
        assertTrue(f.provider.resetAfterConfirmedRawConsent { false })
        assertTrue(write(f.provider))
        assertTrue(f.provider.blockForRawSourceWithdrawal())
        assertTrue(f.provider.resetRawSourceAfterConfirmedConsent())
        assertFalse(f.provider.resetRawSourceAfterConfirmedConsent())
        assertTrue(f.provider.resetAfterConfirmedRawConsent { false })
        assertFalse(write(f.provider))
        assertTrue(write(FieldSpeechOutputStorageProvider(f.access)))
    }

    @Test fun rawPurgePreservesExistingMemoryAndPersistentAccountFences() {
        listOf("memory", "persistent", "authority").forEach { boundary ->
            val f = fixture()
            assertTrue(write(f.provider))
            when (boundary) {
                "memory" -> f.provider.blockNewProcessingForAccountDeletion()
                "persistent" -> assertTrue(debug(f, "account-owner").blockForAccountDeletion())
            }
            assertTrue(f.provider.purgeForRawSourceWithdrawal())
            assertFalse(f.provider.resetAfterConfirmedRawConsent { boundary == "authority" })
            assertTrue(File(f.root, "account_deletion_blocked.txt").isFile)
            assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
            assertEquals(0, recordFiles(f).size)
        }
    }

    @Test fun accountIntentArrivingDuringRawPurgeIsNotDowngradedByQueuedRawCleanup() {
        val f = fixture()
        assertTrue(write(f.provider))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val access = SpeechStorageAccess.create(f.files, aead = object : LocalAead by f.crypto.aead {
            override fun destroyKnownVersions(): Boolean {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                return f.crypto.aead.destroyKnownVersions()
            }
        })
        val provider = FieldSpeechOutputStorageProvider(access)
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        val worker = Thread { try { result.complete(provider.purgeForRawSourceWithdrawal()) }
            catch (failure: Throwable) { result.completeExceptionally(failure) } }
        worker.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            provider.blockNewProcessingForAccountDeletion() // Memory-only while PROCESS_LOCK is held.
            release.countDown()
            assertTrue(result.get(10, TimeUnit.SECONDS))
            assertTrue(File(f.root, "account_deletion_blocked.txt").isFile)
            assertFalse(provider.resetAfterConfirmedRawConsent { false })
            assertFalse(write(FieldSpeechOutputStorageProvider(access)))
        } finally { release.countDown(); worker.join(10_000L) }
    }

    @Test fun rawFreshResetRejectsMissingCorruptLinkedProofAndEveryResidualDataset() {
        listOf("missing", "corrupt", "proof-link", "proof-hardlink", "speech", "debug", "unknown")
            .forEach { defect ->
                val f = fixture()
                assertTrue(write(f.provider)); assertTrue(f.provider.purgeForRawSourceWithdrawal())
                val marker = File(f.root, "field_key_purge_verified.txt")
                when (defect) {
                    "missing" -> assertTrue(marker.delete())
                    "corrupt" -> marker.writeText("unverified")
                    "proof-link" -> {
                        val outside = File(f.files, "outside-proof").apply { writeText("verified\n") }
                        marker.delete(); Files.createSymbolicLink(marker.toPath(), outside.toPath())
                    }
                    "proof-hardlink" -> Files.createLink(File(f.files, "outside-proof").toPath(), marker.toPath())
                    "speech" -> File(f.root, "speech-output/window-0000000000000001").mkdirs()
                    "debug" -> File(f.root, "debug-residue").mkdir()
                    "unknown" -> File(f.root, "unknown.txt").writeText("residual")
                }
                assertFalse("reset defect=$defect", f.provider.resetAfterConfirmedRawConsent { false })
                assertFalse(f.crypto.hasKey())
                assertTrue(File(f.root, "field_log_crypto_blocked.txt").exists())
                assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
                // A restarted accessor also cannot treat a lost proof as a legacy raw-only fence.
                val restarted = FieldSpeechOutputStorageProvider(SpeechStorageAccess.create(f.files,
                    aead = f.crypto.aead))
                assertFalse(restarted.resetAfterConfirmedRawConsent { false })
            }
    }

    @Test fun rawKeyDeletionAndPurgeSyncFailureDoNotAuthorizeFreshReset() {
        listOf("key", "sync").forEach { failure ->
            val failing = AtomicBoolean()
            val f = fixture(sync = { !failing.get() })
            assertTrue(write(f.provider))
            if (failure == "key") f.crypto.failNextDelete() else failing.set(true)
            assertFalse(f.provider.purgeForRawSourceWithdrawal())
            assertFalse(File(f.root, "field_key_purge_verified.txt").exists())
            assertFalse(f.provider.resetAfterConfirmedRawConsent { false })
            assertFalse(File(f.root, "account_deletion_blocked.txt").exists())
            assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
        }
    }

    @Test fun rawFreshKeyAndMarkerCleanupFailureStayBlockedUntilExplicitVerifiedRecovery() {
        listOf("fresh-key", "cleanup-sync").forEach { failure ->
            val failing = AtomicBoolean()
            val f = fixture(sync = { directory -> !failing.get() ||
                directory.name != "field_sessions" ||
                File(directory, "field_key_purge_verified.txt").exists() })
            assertTrue(write(f.provider)); assertTrue(f.provider.purgeForRawSourceWithdrawal())
            if (failure == "fresh-key") f.crypto.failNextFreshCreate() else failing.set(true)
            assertFalse(f.provider.resetAfterConfirmedRawConsent { false })
            assertTrue(File(f.root, "field_log_crypto_blocked.txt").exists())
            assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
            failing.set(false)
            // Explicit caller action; no internal retry or false reset result is promoted to success.
            assertTrue(f.provider.purgeForRawSourceWithdrawal())
            assertTrue(f.provider.resetAfterConfirmedRawConsent { false })
            assertFalse(write(f.provider))
            assertTrue(write(FieldSpeechOutputStorageProvider(f.access), 707L))
            assertEquals(listOf(707L), read(f).map { it.getLong("elapsed_realtime_ms") })
        }
    }

    @Test fun rawResetRechecksAccountAuthorityAroundFreshKeyAndFinalSync() {
        listOf("fresh-key", "final-sync", "callback-failure").forEach { boundary ->
            val deleting = AtomicBoolean(); val signalSync = AtomicBoolean()
            val f = fixture(sync = { if (signalSync.get()) deleting.set(true); true })
            assertTrue(write(f.provider)); assertTrue(f.provider.purgeForRawSourceWithdrawal())
            val aead = object : LocalAead by f.crypto.aead {
                override fun createFreshAfterVerifiedPurge(): Boolean {
                    val created = f.crypto.aead.createFreshAfterVerifiedPurge()
                    if (boundary == "fresh-key") deleting.set(true)
                    if (boundary == "final-sync") signalSync.set(true)
                    return created
                }
            }
            val provider = FieldSpeechOutputStorageProvider(SpeechStorageAccess.create(f.files,
                aead = aead, directorySync = { if (signalSync.get()) deleting.set(true); true }))
            assertFalse(provider.resetAfterConfirmedRawConsent {
                if (boundary == "callback-failure") throw IllegalStateException("authority unavailable")
                deleting.get()
            })
            if (boundary != "callback-failure") assertTrue(File(f.root, "account_deletion_blocked.txt").exists())
            assertFalse(write(FieldSpeechOutputStorageProvider(f.access)))
            assertEquals(0, recordFiles(f).size)
        }
    }

    @Test fun inFlightActualAppendFollowedBySerialRawPurgeCannotRecreateDataset() {
        val f = fixture()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val once = AtomicBoolean(true)
        val access = SpeechStorageAccess.create(f.files, aead = object : LocalAead by f.crypto.aead {
            override fun open(envelope: String, associatedData: ByteArray, limits: AeadLimits): AeadOpenResult {
                if (once.compareAndSet(true, false)) {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                }
                return f.crypto.aead.open(envelope, associatedData, limits)
            }
        })
        val provider = FieldSpeechOutputStorageProvider(access)
        val appended = AtomicBoolean()
        val purged = java.util.concurrent.CompletableFuture<Boolean>()
        val worker = Thread {
            try {
                appended.set(write(provider, 808L))
                purged.complete(provider.purgeForRawSourceWithdrawal())
            } catch (failure: Throwable) { purged.completeExceptionally(failure) }
        }
        worker.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertFalse(purged.isDone)
            release.countDown()
            assertTrue(purged.get(10, TimeUnit.SECONDS)); assertTrue(appended.get())
            assertEquals(0, recordFiles(f).size); assertFalse(f.crypto.hasKey())
            assertFalse(write(provider)); assertFalse(write(FieldSpeechOutputStorageProvider(access)))
            assertTrue(provider.resetAfterConfirmedRawConsent { false })
            assertFalse(write(provider))
            assertTrue(write(FieldSpeechOutputStorageProvider(access), 909L))
        } finally { release.countDown(); worker.join(10_000L) }
    }

    private data class Fixture(val files: File, val root: File, val crypto: TestFieldAead,
        val access: SpeechStorageAccess, val provider: FieldSpeechOutputStorageProvider,
        var now: Long = 1_000L)

    private fun fixture(sync: (File) -> Boolean = { true }): Fixture {
        val files = temporary.newFolder()
        val crypto = TestFieldAead()
        var fixture: Fixture? = null
        val access = SpeechStorageAccess.create(files, aead = crypto.aead,
            nowMillis = { fixture?.now ?: 1_000L }, directorySync = sync)
        return Fixture(files, access.rootDirectory, crypto, access,
            FieldSpeechOutputStorageProvider(access)).also { fixture = it }
    }

    private fun debug(f: Fixture, id: String) = PersistentFieldSessionLog(f.root,
        FieldSessionDeviceInfo("Test", "16", "test", "0123456789abcdef0123456789abcdef01234567"),
        nowMillis = { f.now }, idFactory = { id }, aead = f.crypto.aead)

    private fun binding() = SpeechOutputStorageBinding(Any(), Any(), Any(), Any(), 1L,
        Any(), Any(), Any(), Any())
    private fun event(clock: Long) = SpeechOutputEvent(SpeechOutputKind.values().first(),
        SpeechOutputOutcome.values().first(), clock, null)
    private fun record(clock: Long = 42L) = JSONObject().put("metric", SpeechOutputKind.values().first().name)
        .put("state", SpeechOutputOutcome.values().first().name).put("elapsed_realtime_ms", clock)
        .put("result", "NONE")
    private fun seal(f: Fixture, record: JSONObject, aad: ByteArray) =
        (f.crypto.aead.seal(record.toString().toByteArray(), aad, f.access.recordLimits)
            as AeadSealResult.Sealed).envelope
    private fun write(provider: FieldSpeechOutputStorageProvider, clock: Long = 42L): Boolean {
        val sealed = provider.aead.seal(record(clock).toString().toByteArray(),
            provider.associatedData(), provider.limits) as? AeadSealResult.Sealed ?: return false
        return provider.appendEncrypted(sealed.envelope)
    }
    private fun recordFiles(f: Fixture) = f.root.walkTopDown()
        .filter { it.isFile && it.name.startsWith("records-") && it.parentFile?.parentFile?.name == "speech-output" }
        .toList().sortedBy(File::getPath)
    private fun read(f: Fixture): List<JSONObject> {
        val artifact = File(f.root, "speech-output")
        val windows = FieldSpeechOutputStorageProvider.readValidWindows(artifact, f.crypto.aead,
            f.access.recordLimits, f.access.profile) ?: return emptyList()
        return windows.sortedBy { it.number }.flatMap { window ->
            var ordinal = 0L
            window.directory.listFiles()!!.sortedBy(File::getName).flatMap { file ->
                file.readLines().map { envelope ->
                    val opened = f.crypto.aead.open(envelope,
                        SpeechStorageAccess.recordAad(window.number, ordinal++), f.access.recordLimits)
                        as AeadOpenResult.Opened
                    try { JSONObject(String(opened.plaintext)) } finally { opened.plaintext.fill(0) }
                }
            }
        }
    }
}
