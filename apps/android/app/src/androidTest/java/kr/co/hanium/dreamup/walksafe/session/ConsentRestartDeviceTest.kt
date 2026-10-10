package kr.co.hanium.dreamup.walksafe.session

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import kr.co.hanium.dreamup.walksafe.security.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Production consent codec/state machine and Android encrypted preferences across two processes.
 * Synthetic account/consent only; no real consent setting or server request is changed. */
class ConsentRestartDeviceTest {
    @Test fun pendingDenialAndCasSuccessorSurviveProcessRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("consentRestartPhase") in setOf("write", "read"))
        val phase = requireNotNull(args.getString("consentRestartPhase"))
        val variant = requireNotNull(args.getString("consentRestartVariant"))
        val run = requireNotNull(args.getString("consentRestartRun"))
        require(variant in setOf("pending", "cas") && run.matches(Regex("[a-z0-9]{16}")))
        val context = instrumentation.targetContext
        val name = "consent-restart-$run"
        val root = File(context.noBackupFilesDir, name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val policy = AeadKeyPolicy("walksafe.instrumentation.consent.$run.v", 1, setOf(1))
        val spec = SensitivePreferenceSpec("cipher", "blocked", "reset", name.toByteArray(), policy, setOf("pending", "journal"))
        val store = AndroidSensitivePreferenceStore(prefs, spec)
        val initial = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
            INTEGRATED_CONSENT_POLICY_VERSION, "consent-restart-installation", "consent_restart_original_0001",
            IntegratedConsentItemVersions(), 1, 1, IntegratedConsentSelections(true,true,true,true),
            "2026-10-10T00:00:00.000Z", "a".repeat(64), "b".repeat(64), "d".repeat(64))
        val choices = initial.selections.copy(rawSourceCollection=false)
        val request = "consent_" + hash("FP013|${initial.installationId}|2|${initial.backendConsentReceiptSha256}|$choices")
        val original = PendingIntegratedConsentMutation(initial.installationId, "a".repeat(64), request,
            INTEGRATED_CONSENT_POLICY_VERSION, 2, 1, initial.backendConsentReceiptSha256,
            choices, setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION), 1000)
        val backendChoices = initial.selections.copy(automaticReporting=false)
        val successor = original.successorAfterRejectedCas(backendChoices, "c".repeat(64), 2, 3,
            original.withdrawalItems, 2000)
        val journal = RejectedConsentCasRecoveryJournal.next(null,
            RejectedConsentCasSupersession(original, backendChoices, "c".repeat(64), 2, successor))
        val expected = if (variant == "cas") successor else original
        var keep = false
        try {
            assertFalse(store.isBlocked())
            if (phase == "write") {
                assertFalse(root.exists()); assertTrue(prefs.all.isEmpty()); assertTrue(root.mkdir())
                val raw = RejectedConsentCasJournalCodec.pendingJson(expected)
                val edit = store.edit().putString("pending", raw)
                if (variant == "cas") edit.putString("journal", RejectedConsentCasJournalCodec.journalJson(journal))
                assertTrue(edit.commit())
                val envelope = requireNotNull(prefs.getString("cipher", null))
                assertFalse(envelope.contains(expected.requestId)); assertFalse(envelope.contains("withdrawal_items"))
                File(root,"marker.json").writeText(JSONObject().put("pid",Process.myPid()).put("hash",hash(raw)).toString())
                keep = true
            } else {
                val marker = JSONObject(File(root,"marker.json").readText())
                assertNotEquals(marker.getInt("pid"),Process.myPid())
                val raw = requireNotNull(store.getString("pending",null))
                assertEquals(marker.getString("hash"),hash(raw))
                val pending = requireNotNull(RejectedConsentCasJournalCodec.pendingOrNull(raw))
                assertEquals(expected,pending)
                val recoveredJournal = store.getString("journal",null)?.let { RejectedConsentCasJournalCodec.journalOrNull(it,pending) }
                assertEquals(if (variant == "cas") journal else null, recoveredJournal)
                assertTrue(RejectedConsentCasJournalCodec.restoredPendingPreservesConfirmation(pending,initial,"a".repeat(64),pending.clientRevision,recoveredJournal))
                assertFalse(RejectedConsentCasJournalCodec.restoredPendingPreservesConfirmation(pending,initial,"e".repeat(64),pending.clientRevision,recoveredJournal))
                val state = IntegratedConsentSession()
                assertTrue(state.restoreCurrentConfirmation(initial))
                assertTrue(state.restorePendingMutation(pending,retry=true))
                assertFalse(state.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
                state.applyConfirmation(initial)
                assertEquals(pending,state.pendingMutationOrNull())
                assertFalse(state.isAllowed(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
                assertFalse(state.applyExactPendingConfirmation(initial.copy(requestId="consent_restart_wrong_0002",clientRevision=pending.clientRevision,
                    revision=3,selections=pending.desiredSelections)))
                assertEquals(pending,state.pendingMutationOrNull())
                assertTrue(state.applyExactPendingConfirmation(initial.copy(requestId=pending.requestId,clientRevision=pending.clientRevision,
                    revision=3,selections=pending.desiredSelections)))
                assertNull(state.pendingMutationOrNull())
                assertEquals(PurposeConsentSyncState.CONFIRMED_DENIED,state.status(IntegratedConsentItem.RAW_SOURCE_COLLECTION))
            }
            instrumentation.sendStatus(0,Bundle().apply { putString("stream","CONSENT_RESTART phase=$phase variant=$variant native_encrypted_preferences=true account_and_receipts=synthetic actual_server_or_Main_consent=NOT_TESTED\n") })
        } finally {
            if (!keep) {
                context.deleteSharedPreferences(name)
                if(root.exists()) assertTrue(root.deleteRecursively())
                assertTrue(AndroidKeyStoreAead(policy).destroyKnownVersions())
                KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(policy.alias(1)+".generation")}
            }
        }
    }
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
}
