package kr.co.hanium.dreamup.walksafe

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.support.readTransferMarker
import kr.co.hanium.dreamup.walksafe.support.writeTransferMarker
import kr.co.hanium.dreamup.walksafe.testing.IsolatedBoundaryContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Production journal/coordinator, synthetic participants and a real test-owned AES key. */
@RunWith(AndroidJUnit4::class)
class DeletionResetRestartDeviceTest {
    @Test fun forcedProcessDeathReplaysEveryResetStep() {
        val args = InstrumentationRegistry.getArguments()
        val mode = requireNotNull(args.getString("resetMode"))
        require(mode in setOf("write", "read")) { "resetMode=write/read is required" }
        val stopPhase = AccountDeletionResetPhase.valueOf(requireNotNull(args.getString("resetPhase")))
        val token = requireNotNull(args.getString("resetToken"))
        val context = IsolatedBoundaryContext.create("deletion-reset", token, fresh = mode == "write")
        val root = context.root
        val policy = AeadKeyPolicy("walksafe.instrumentation.reset.$token.v", 1, setOf(1))
        val aead = AndroidKeyStoreAead(policy)
        val delegate = FileAccountDeletionResetJournal(File(root, "journal"))
        val marker = File(root, "writer.pid")
        var retain = false
        try {
            if (mode == "write") {
                listOf("gateway", "sensitive", "plain", "field", "fence").forEach {
                    File(root, it).writeText("synthetic-fixture-state")
                }
                assertTrue(aead.seal("fixture-only".toByteArray(), "reset".toByteArray(),
                    AeadLimits(1024, 2048, 4096)) is AeadSealResult.Sealed)
                writeTransferMarker(marker, Process.myPid().toString())
            } else {
                val writer = readTransferMarker(marker).toInt()
                assertTrue(writer > 0)
                assertNotEquals("Reader requires a different Android process", writer, Process.myPid())
                assertEquals(AccountDeletionResetJournalState.Pending(stopPhase), delegate.read())
            }
            val journal = object : AccountDeletionResetJournal by delegate {
                override fun write(phase: AccountDeletionResetPhase): Boolean {
                    val durable = delegate.write(phase)
                    // A test interruption after the verified durable phase; no real account reset.
                    return durable && !(mode == "write" && phase == stopPhase)
                }
            }
            val actions = mutableListOf<String>()
            fun purge(name: String): Boolean {
                actions += name
                val file = File(root, name)
                return (!file.exists() || file.delete()) && !file.exists()
            }
            val coordinator = AccountDeletionResetCoordinator(journal,
                storeLegacyIntent = { actions += "legacy"; File(root, "legacy").writeText("pending"); true },
                resetGateway = { purge("gateway") },
                resetSensitivePreferences = { purge("sensitive") && aead.destroyKnownVersions() },
                resetPlainPreferences = { purge("plain") }, resetFieldStorage = { purge("field") },
                clearDeletionIntentFence = { purge("fence") })
            if (mode == "write") {
                assertEquals(AccountDeletionResetResult.BLOCKED, coordinator.beginAndReconcile())
                assertEquals(AccountDeletionResetJournalState.Pending(stopPhase), delegate.read())
                assertTrue(coordinator.hasPendingOrCorruptJournal())
                retain = true
            } else {
                assertEquals(AccountDeletionResetResult.COMPLETED, coordinator.reconcilePending())
                assertEquals(listOf("legacy", "gateway", "sensitive", "plain", "field", "fence"), actions)
                assertEquals(AccountDeletionResetJournalState.Absent, delegate.read())
                assertEquals(AccountDeletionResetResult.NO_PENDING, coordinator.reconcilePending())
                listOf("gateway", "sensitive", "plain", "field", "fence").forEach { assertFalse(File(root, it).exists()) }
                assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(policy.alias(1)))
            }
        } finally {
            if (!retain) {
                check(aead.destroyKnownVersions())
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(policy.alias(1) + ".generation") }
                context.dispose()
            }
        }
    }
}
