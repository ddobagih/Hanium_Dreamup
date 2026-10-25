package kr.co.hanium.dreamup.walksafe.feedback

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pure production factory/account-storage native layer, pinned to Field3eff/Provider1c7.
 * Only the Root-approved separate applicationId/UID may run this test. No Activity, Main listener,
 * login/network, privacy override, fake crypto, copied/new key alias or physical speech is used.
 * Synthetic fixture events use existing enums and their original SystemClock value. All I/O is on
 * the JUnit worker. A failed guard precedes every factory/key access and performs no cleanup I/O.
 */
@RunWith(AndroidJUnit4::class)
class FieldSpeechOutputStorageProviderAndroidTest {
    @Test(timeout = 60_000L)
    fun actualFactoryStoresFixedEventsAndVerifiesAccountKeyPurgeAndFreshReset() {
        val guard = guardIsolatedTargetBeforeAnyFactoryOrKeyAccess()
        val initialAliases = keyAliases()
        assertTrue("A newly installed fixture UID must have no keystore entries", initialAliases.isEmpty())
        var provider: FieldSpeechOutputStorageProvider? = null
        var failure: Throwable? = null
        try {
            val original = FieldSpeechOutputStorageProvider.create(guard.context.filesDir)
            provider = original
            assertTrue(original.aead is AndroidKeyStoreAead)
            val events = listOf(
                SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STARTED,
                    SystemClock.elapsedRealtime()),
                SpeechOutputEvent(SpeechOutputKind.OTHER, SpeechOutputOutcome.ENGINE_ERROR,
                    SystemClock.elapsedRealtime(), -3),
            )
            val saved = events.map { appendFixtureEvent(original, it) }
            val files = recordFiles(guard.root)
            assertEquals(1, files.size)
            assertTrue(files.single().parentFile!!.name.matches(Regex("window-[0-9]{16}")))
            assertEquals("records-0001.jsonl", files.single().name)
            assertEquals(saved.map(SavedRecord::envelope), files.single().readLines())
            assertEquals(setOf("speech-output"), guard.root.listFiles()!!.map(File::getName).toSet())
            assertFalse(guard.root.walkTopDown().any {
                it.name == "manifest.json" || it.name == "active_session.txt" })
            saved.forEachIndexed { index, record ->
                assertFixedPayload(original, record, events[index])
                emit(guard.instrumentation, JSONObject().put("stage", "fixed_file_decrypted")
                    .put("clock", events[index].atElapsedRealtimeMs)
                    .put("metric", events[index].kind.name).put("state", events[index].outcome.name)
                    .put("result", events[index].errorCode ?: "NONE")
                    .put("envelope", record.envelope).put("fields", 4))
            }
            val createdAliases = keyAliases() - initialAliases
            assertEquals(2, createdAliases.size)
            val store = androidKeyStore()
            createdAliases.forEach { alias ->
                val key = store.getKey(alias, null)
                assertTrue(key is SecretKey)
                key as SecretKey
                assertEquals("AES", key.algorithm)
                assertNull("AndroidKeyStore key material must not be exported", key.encoded)
            }

            assertTrue("Actual key/root/directory-sync/marker purge must succeed", original.purgeAll())
            assertVerifiedDatasetPurge(guard.root)
            val retainedMetadata = keyAliases()
            val deletedLiveAliases = createdAliases - retainedMetadata
            assertEquals(1, deletedLiveAliases.size)
            assertEquals(1, retainedMetadata.size)
            assertTrue(retainedMetadata.all { it in createdAliases })
            assertKeyMissingAndSealCannotRecreate(original, saved.first())
            assertFalse(original.appendEncrypted(saved.first().envelope))
            assertFalse("Full account purge is not a raw-only reset", original.resetRawSourceAfterConfirmedConsent())
            emit(guard.instrumentation, JSONObject().put("stage", "verified_account_purge")
                .put("datasetRecords", 0).put("deletedLiveKeys", deletedLiveAliases.size)
                .put("remainingGenerationMetadata", retainedMetadata.size).put("verifiedMarker", true))

            // Each failure uses this fixture's real root and real account reset. A fresh explicit
            // actual purge prepares the next trial; no fabricated proof or automatic retry is used.
            val residual = File(guard.root, "speech-output")
            assertTrue(residual.mkdir())
            assertFalse("A residual is never exempt from enrollment reset", original.resetForNewEnrollment())
            assertKeyMissingAndSealCannotRecreate(original, saved.first())
            emitResetRejection(guard, "speech_residual", retainedMetadata)
            assertTrue(original.purgeAll())
            assertVerifiedDatasetPurge(guard.root)

            File(guard.root, VERIFIED_MARKER).writeText("corrupt\n")
            assertFalse("Corrupt marker cannot authorize fresh key", original.resetForNewEnrollment())
            assertKeyMissingAndSealCannotRecreate(original, saved.first())
            emitResetRejection(guard, "corrupt_marker", retainedMetadata)
            assertTrue(original.purgeAll())
            assertVerifiedDatasetPurge(guard.root)

            assertTrue(File(guard.root, VERIFIED_MARKER).delete())
            assertFalse("Missing marker cannot authorize fresh key", original.resetForNewEnrollment())
            assertKeyMissingAndSealCannotRecreate(original, saved.first())
            emitResetRejection(guard, "missing_marker", retainedMetadata)
            assertTrue(original.purgeAll())
            assertVerifiedDatasetPurge(guard.root)

            assertTrue("Verified reset must generate fresh key and durably clear marker/fences",
                original.resetForNewEnrollment())
            assertTrue(guard.root.listFiles()!!.isEmpty())
            assertEquals(createdAliases, keyAliases())
            assertFalse("An old provider never revives", original.appendEncrypted(saved.first().envelope))
            val fresh = FieldSpeechOutputStorageProvider.create(guard.context.filesDir)
            provider = fresh
            val freshEvent = SpeechOutputEvent(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE,
                SystemClock.elapsedRealtime())
            val freshRecord = appendFixtureEvent(fresh, freshEvent)
            assertFixedPayload(fresh, freshRecord, freshEvent)
            assertEquals(1, recordFiles(guard.root).sumOf { it.readLines().size })
            assertEquals(AeadOpenResult.Blocked(AeadBlockReason.AUTHENTICATION_FAILED),
                fresh.aead.open(saved.first().envelope, saved.first().aad, fresh.limits))
            emit(guard.instrumentation, JSONObject().put("stage", "verified_fresh_reset")
                .put("freshRecordClock", freshEvent.atElapsedRealtimeMs).put("datasetRecords", 1)
                .put("oldCiphertextAuthenticated", false).put("resetRejections", 3)
                .put("sameProductionAliasSet", true).put("mainListenerConnected", false)
                .put("physicalSpeechRequests", 0))
        } catch (problem: Throwable) {
            failure = problem
            emit(guard.instrumentation, JSONObject().put("stage", "trial_failed")
                .put("failureType", problem.javaClass.simpleName))
            throw problem
        } finally {
            // Only this already-guarded fixture's actual production purge is used for cleanup.
            // Generation/tombstone keys intentionally remain; NativeTester owns fixture uninstall.
            val cleanup = runCatching { provider?.purgeAll() ?: true }.getOrElse { problem ->
                if (failure == null) throw problem
                false
            }
            emit(guard.instrumentation, JSONObject().put("stage", "cleanup")
                .put("verifiedPurge", cleanup).put("datasetRecords", recordFiles(guard.root).size)
                .put("remainingKeyMetadata", runCatching { keyAliases().size }.getOrDefault(-1)))
            if (failure == null) {
                assertTrue(cleanup)
                assertVerifiedDatasetPurge(guard.root)
                assertEquals(1, keyAliases().size)
            }
        }
    }

    private data class Guard(val instrumentation: Instrumentation, val context: Context, val root: File)
    private data class SavedRecord(val envelope: String, val aad: ByteArray, val event: SpeechOutputEvent)

    @Suppress("DEPRECATION")
    private fun guardIsolatedTargetBeforeAnyFactoryOrKeyAccess(): Guard {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Filesystem/key I/O must be on the JUnit worker", Looper.myLooper() != Looper.getMainLooper())
        assertEquals(FIXTURE_PACKAGE, context.packageName)
        assertEquals(context.applicationInfo.uid, Process.myUid())
        assertNull(context.packageManager.getPackageInfo(context.packageName, 0).sharedUserId)
        // Read-only package metadata query, with exact original package matching. It needs no
        // manifest visibility change, permission grant, original-app launch or original-app I/O.
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "cmd package list packages -U $ORIGINAL_PACKAGE")
        val packageLines = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readLines() }
        val originalUids = packageLines.mapNotNull { line ->
            val match = Regex("^package:([A-Za-z0-9_.]+)\\s+uid:([0-9]+)\\s*$").matchEntire(line.trim())
            match?.takeIf { it.groupValues[1] == ORIGINAL_PACKAGE }?.groupValues?.get(2)?.toIntOrNull()
        }
        assertEquals("Actual original USER package must be present once", 1, originalUids.size)
        assertEquals(ORIGINAL_UID, originalUids.single())
        assertTrue("Fixture must never share original USER UID", Process.myUid() != originalUids.single())
        val data = File(context.applicationInfo.dataDir).canonicalFile
        val files = context.filesDir.canonicalFile
        val root = File(files, "field_sessions")
        assertEquals(FIXTURE_PACKAGE, data.name)
        assertEquals(data, files.parentFile)
        assertEquals("files", files.name)
        assertEquals(Process.myUid(), Os.stat(data.path).st_uid)
        assertEquals(Process.myUid(), Os.stat(files.path).st_uid)
        assertEquals(files, root.canonicalFile.parentFile)
        assertFalse(Files.isSymbolicLink(root.toPath()))
        assertFalse("Fresh fixture field root required before key trial",
            Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        emit(instrumentation, JSONObject().put("stage", "guard_passed")
            .put("targetPackage", context.packageName).put("contextUid", context.applicationInfo.uid)
            .put("processUid", Process.myUid()).put("originalUserUid", originalUids.single())
            .put("sharedUserId", "NONE").put("rootOwned", true).put("factoryCallsBeforeGuard", 0)
            .put("keyAccessBeforeGuard", 0))
        return Guard(instrumentation, context, root)
    }

    private fun appendFixtureEvent(provider: FieldSpeechOutputStorageProvider, event: SpeechOutputEvent): SavedRecord {
        assertTrue(event.kind.name.length <= 160 && event.outcome.name.length <= 160)
        val plaintext = encodeFixedEvent(event)
        val aad = provider.associatedData()
        val sealed = try { provider.aead.seal(plaintext, aad, provider.limits) }
            finally { plaintext.fill(0) }
        assertTrue("Actual AndroidKeyStore seal must succeed: $sealed", sealed is AeadSealResult.Sealed)
        val envelope = (sealed as AeadSealResult.Sealed).envelope
        assertTrue(provider.appendEncrypted(envelope))
        return SavedRecord(envelope, aad, event)
    }

    private fun assertFixedPayload(provider: FieldSpeechOutputStorageProvider, saved: SavedRecord,
        event: SpeechOutputEvent) {
        val envelope = JSONObject(saved.envelope)
        assertEquals(setOf("envelope_version", "key_version", "iv", "ciphertext"),
            envelope.keys().asSequence().toSet())
        assertFalse(saved.envelope.contains("\"metric\""))
        val opened = provider.aead.open(saved.envelope, saved.aad, provider.limits)
        assertTrue("Actual AndroidKeyStore open must succeed: $opened", opened is AeadOpenResult.Opened)
        opened as AeadOpenResult.Opened
        try {
            assertEquals(envelope.getInt("key_version"), opened.keyVersion)
            val payload = JSONObject(String(opened.plaintext, Charsets.UTF_8))
            assertEquals(setOf("metric", "state", "elapsed_realtime_ms", "result"),
                payload.keys().asSequence().toSet())
            assertEquals(event.kind.name, payload.getString("metric"))
            assertEquals(event.outcome.name, payload.getString("state"))
            assertEquals(event.atElapsedRealtimeMs, payload.getLong("elapsed_realtime_ms"))
            assertEquals(event.errorCode ?: "NONE", payload.get("result"))
        } finally { opened.plaintext.fill(0) }
    }

    private fun assertKeyMissingAndSealCannotRecreate(provider: FieldSpeechOutputStorageProvider, saved: SavedRecord) {
        assertEquals(AeadOpenResult.Blocked(AeadBlockReason.KEY_MISSING),
            provider.aead.open(saved.envelope, saved.aad, provider.limits))
        val plaintext = encodeFixedEvent(saved.event)
        try {
            assertEquals(AeadSealResult.Blocked(AeadBlockReason.KEY_MISSING),
                provider.aead.seal(plaintext, saved.aad, provider.limits))
        } finally { plaintext.fill(0) }
    }

    private fun assertVerifiedDatasetPurge(root: File) {
        assertTrue(recordFiles(root).isEmpty())
        assertEquals(setOf(VERIFIED_MARKER, "account_deletion_blocked.txt"),
            root.listFiles()!!.map(File::getName).toSet())
        assertEquals("verified\n", File(root, VERIFIED_MARKER).readText())
    }

    private fun emitResetRejection(guard: Guard, reason: String, retainedMetadata: Set<String>) {
        assertEquals(retainedMetadata, keyAliases())
        emit(guard.instrumentation, JSONObject().put("stage", "reset_rejected")
            .put("reason", reason).put("freshKeyCreated", false))
    }

    private fun recordFiles(root: File): List<File> = if (!root.exists()) emptyList() else root.walkTopDown()
        .filter { it.isFile && it.name.matches(Regex("records-[0-9]{4}\\.jsonl")) }.toList()
    private fun encodeFixedEvent(event: SpeechOutputEvent): ByteArray = JSONObject()
        .put("metric", event.kind.name).put("state", event.outcome.name)
        .put("elapsed_realtime_ms", event.atElapsedRealtimeMs).put("result", event.errorCode ?: "NONE")
        .toString().toByteArray(Charsets.UTF_8)
    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun keyAliases(): Set<String> = java.util.Collections.list(androidKeyStore().aliases()).toSet()
    private fun emit(instrumentation: Instrumentation, value: JSONObject) {
        Log.i(TAG, value.toString())
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", value.toString() + "\n") })
    }

    private companion object {
        const val TAG = "SpeechStoreFixture"
        const val FIXTURE_PACKAGE = "kr.co.hanium.dreamup.walksafe.speechstorefixture"
        const val ORIGINAL_PACKAGE = "kr.co.hanium.dreamup.walksafe"
        const val ORIGINAL_UID = 10209
        const val VERIFIED_MARKER = "field_key_purge_verified.txt"
    }
}
