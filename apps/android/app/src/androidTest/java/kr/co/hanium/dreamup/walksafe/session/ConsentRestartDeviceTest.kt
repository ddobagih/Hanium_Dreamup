package kr.co.hanium.dreamup.walksafe.session

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.testing.IsolatedBoundaryContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact production codec/state with isolated ciphertext and a genuinely different reader PID. */
@RunWith(AndroidJUnit4::class)
class ConsentRestartDeviceTest {
    @Test
    fun pendingDenialAndCasSuccessorSurviveProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        val phase = requireNotNull(args.getString("consentRestartPhase"))
        require(phase in setOf("write", "read"))
        val variant = requireNotNull(args.getString("consentRestartVariant"))
        require(variant in setOf("pending", "cas"))
        val run = requireNotNull(args.getString("consentRestartRun"))
        val context = IsolatedBoundaryContext.create("consent", run, fresh = phase == "write")
        val name = "walksafe_native_consent_$run"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val policy = AeadKeyPolicy("walksafe.instrumentation.consent.$run.v", 1, setOf(1))
        val spec = SensitivePreferenceSpec("cipher", "blocked", "reset", name.toByteArray(), policy, setOf("pending", "journal"))
        val store = AndroidSensitivePreferenceStore(prefs, spec)
        val initial = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
            INTEGRATED_CONSENT_POLICY_VERSION, "consent-restart-installation", "consent_restart_original_0001",
            IntegratedConsentItemVersions(), 1, 1, IntegratedConsentSelections(true, true, true, true),
            "2026-10-10T00:00:00.000Z", "a".repeat(64), "b".repeat(64), "d".repeat(64))
        val desired = initial.selections.copy(rawSourceCollection = false)
        val request = "consent_" + hash("FP013|${initial.installationId}|2|${initial.backendConsentReceiptSha256}|$desired")
        val original = PendingIntegratedConsentMutation(initial.installationId, "a".repeat(64), request,
            INTEGRATED_CONSENT_POLICY_VERSION, 2, 1, initial.backendConsentReceiptSha256,
            desired, setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION), 1_000)
        val backend = initial.selections.copy(automaticReporting = false)
        val successor = original.successorAfterRejectedCas(backend, "c".repeat(64), 2, 3, original.withdrawalItems, 2_000)
        val journal = RejectedConsentCasRecoveryJournal.next(null,
            RejectedConsentCasSupersession(original, backend, "c".repeat(64), 2, successor))
        val expected = if (variant == "cas") successor else original
        val markerFile = File(context.root, "writer.json")
        var retain = false
        try {
            assertFalse(store.isBlocked())
            if (phase == "write") {
                assertTrue("Existing probe preferences must not be replaced", prefs.all.isEmpty())
                val raw = RejectedConsentCasJournalCodec.pendingJson(expected)
                val edit = store.edit().putString("pending", raw)
                if (variant == "cas") edit.putString("journal", RejectedConsentCasJournalCodec.journalJson(journal))
                assertTrue(edit.commit())
                val xml = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").readText()
                assertFalse(xml.contains(expected.requestId))
                assertFalse(xml.contains("withdrawal_items"))
                markerFile.writeText(JSONObject().put("pid", Process.myPid()).put("hash", hash(raw)).toString())
                retain = true
            } else {
                val marker = JSONObject(markerFile.readText())
                assertTrue(marker.getInt("pid") > 0)
                assertNotEquals("Reader must run in a new Android process", marker.getInt("pid"), Process.myPid())
                val raw = requireNotNull(store.getString("pending", null))
                assertEquals(marker.getString("hash"), hash(raw))
                val restored = requireNotNull(RejectedConsentCasJournalCodec.pendingOrNull(raw))
                assertEquals(expected, restored)
                val history = store.getString("journal", null)?.let { RejectedConsentCasJournalCodec.journalOrNull(it, restored) }
                assertEquals(if (variant == "cas") journal else null, history)
                assertTrue(RejectedConsentCasJournalCodec.restoredPendingPreservesConfirmation(restored, initial,
                    "a".repeat(64), restored.clientRevision, history))
                assertFalse(RejectedConsentCasJournalCodec.restoredPendingPreservesConfirmation(restored, initial,
                    "e".repeat(64), restored.clientRevision, history))
                val state = IntegratedConsentSession()
                assertTrue(state.restoreCurrentConfirmation(initial))
                assertTrue(state.restorePendingMutation(restored, retry = true))
                assertFalse(state.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
                state.applyConfirmation(initial)
                assertEquals(restored, state.pendingMutationOrNull())
                assertFalse(state.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
                assertFalse(state.applyExactPendingConfirmation(initial.copy(requestId = "consent_restart_wrong_0002",
                    clientRevision = restored.clientRevision, revision = 3, selections = restored.desiredSelections)))
                assertEquals(restored, state.pendingMutationOrNull())
                assertTrue(state.applyExactPendingConfirmation(initial.copy(requestId = restored.requestId,
                    clientRevision = restored.clientRevision, revision = 3, selections = restored.desiredSelections)))
                assertNull(state.pendingMutationOrNull())
                assertEquals(PurposeConsentSyncState.CONFIRMED_DENIED, state.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
            }
        } finally {
            if (!retain) {
                assertTrue(context.deleteSharedPreferences(name))
                assertTrue(AndroidKeyStoreAead(policy).destroyKnownVersions())
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(policy.alias(1) + ".generation") }
                context.dispose()
            }
        }
    }

    private fun hash(raw: String) = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
