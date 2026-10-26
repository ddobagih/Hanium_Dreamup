package kr.co.hanium.dreamup.walksafe.rawcollection

import android.os.Bundle
import android.os.Process
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Two actual app processes, native permission failures, isolated encrypted synthetic data only. */
class RawFailureRestartDeviceTest {
    @Test fun failedCleanupCannotReselectRetiredCollectionAfterRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("rawRestartPhase")
        assumeTrue(phase in setOf("write", "read"))
        val variant = requireNotNull(args.getString("rawRestartVariant"))
        val run = requireNotNull(args.getString("rawRestartRun"))
        require(run.matches(Regex("[a-z0-9]{16}")))
        require(variant in setOf("revocation", "revocation_double", "consent_rejected", "walk_rejected", "account_deleted"))
        val root = File(instrumentation.targetContext.noBackupFilesDir.canonicalFile, "raw-restart-$run")
        val policy = AeadKeyPolicy("walksafe.instrumentation.rawrestart.$run.v", 1, setOf(1))
        val cipher = AndroidKeyStoreAead(policy)
        val store = RawCollectionStore(root, cipher, { NOW })
        val storage = FileRawCollectionStorage(root)
        val collectionDir = root.resolve("raw_collections_v2/collections/$COLLECTION")
        val fences = root.resolve("raw_collections_v2/fences")
        val marker = root.resolve("restart-marker.json")
        var keep = false
        fun retire(): Boolean = when (variant) {
            "revocation", "revocation_double" -> store.onConsentRevoked(CONSENT)
            "consent_rejected" -> store.discardConsentRejected(OWNER, COLLECTION)
            "walk_rejected" -> store.discardWalkRejected(OWNER, COLLECTION)
            else -> store.onAccountDeleted()
        }
        fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "RAW_FAILURE_RESTART phase=$phase variant=$variant $value synthetic_owner=true real_server_and_Main_consent=NOT_TESTED\n")
        })
        try {
            if (phase == "write") {
                assertFalse(root.exists()); assertTrue(root.mkdir())
                assertTrue(store.open(COLLECTION, OWNER, WALK, CONSENT, rawConsentGranted=true, walkState=RawWalkState.ACTIVE))
                assertNotNull(store.appendBatch(OWNER, WALK, true, RawWalkState.ACTIVE,
                    RAW_RUNTIME_CHUNK_TYPE_ORDER.map { RawPlaintextBatchItem(it, NOW, "isolated-test-metadata".toByteArray()) }))
                assertNotNull(store.sealActiveSegment(OWNER, WALK))
                assertEquals(listOf(COLLECTION), store.readyManifests(OWNER).map { it.collectionId })
                assertTrue(storage.writeFenceAtomically("test-sentinel", "SENTINEL"))
                val before = requireNotNull(storage.readManifest(COLLECTION))
                Os.chmod(collectionDir.absolutePath, 0b101000000)
                if (variant in setOf("revocation_double", "account_deleted")) Os.chmod(fences.absolutePath, 0b101000000)
                assertFalse(collectionDir.canWrite())
                assertFalse("Cleanup must report real storage failure", retire())
                assertEquals(before, storage.readManifest(COLLECTION))
                marker.writeText(JSONObject().put("pid", Process.myPid()).put("variant", variant).toString())
                keep = true
                report("actual_permission_denial=true cleanup_result=false ciphertext_preserved=true pid=${Process.myPid()}")
            } else {
                val saved = JSONObject(marker.readText())
                assertEquals(variant, saved.getString("variant"))
                assertNotEquals("Host must stop the previous process", saved.getInt("pid"), Process.myPid())
                // Recover filesystem access after the crash; no cleanup or consent callback is replayed yet.
                Os.chmod(collectionDir.absolutePath, 0b111000000)
                Os.chmod(fences.absolutePath, 0b111000000)
                val ready = store.readyManifests(OWNER).map { it.collectionId }
                val direct = store.uploadReadyManifest(COLLECTION, OWNER) != null
                report("different_process=true selectable_after_restart=${ready.size} direct_upload_ready=$direct pid=${Process.myPid()}")
                // Also verify that an explicit cleanup retry repairs the isolated store.
                assertTrue("Explicit cleanup retry must recover", retire())
                assertTrue(store.readyManifests(OWNER).isEmpty())
                report("explicit_cleanup_retry=true queue_empty=true")
                assertTrue("Retired collection became selectable after a process restart: $variant", ready.isEmpty())
                assertFalse("Retired collection is directly uploadable after restart", direct)
            }
        } finally {
            if (!keep) {
                if (collectionDir.exists()) Os.chmod(collectionDir.absolutePath, 0b111000000)
                if (fences.exists()) Os.chmod(fences.absolutePath, 0b111000000)
                if (root.exists()) assertTrue(root.deleteRecursively())
                assertTrue(cipher.destroyKnownVersions())
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(policy.alias(1) + ".generation") }
            }
        }
    }
    private companion object {
        const val NOW = 10_000L
        const val COLLECTION = "123e4567-e89b-42d3-a456-426614174000"
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
        val CONSENT = "c".repeat(64)
        val OWNER = RawCollectionOwner("123e4567-e89b-42d3-a456-426614174002", 1L, "device-installation-00000001")
    }
}
