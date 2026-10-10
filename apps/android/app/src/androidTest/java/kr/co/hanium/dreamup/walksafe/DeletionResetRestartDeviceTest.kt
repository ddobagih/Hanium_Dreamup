package kr.co.hanium.dreamup.walksafe

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** S1617-40: host kills ART after a durable journal phase; a fresh process replays all steps.
 * Participants are isolated test files and a real test-owned Keystore key, not the user's account.
 * This verifies the coordinator/journal component, not MainActivity's complete deletion inventory.
 */
class DeletionResetRestartDeviceTest {
    @Test fun forcedProcessDeathReplaysEveryResetStep() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("resetMode")
        assumeTrue(mode in listOf("cut", "recover"))
        val phase = AccountDeletionResetPhase.valueOf(requireNotNull(args.getString("resetPhase")))
        val token = requireNotNull(args.getString("resetToken"))
        require(token.matches(Regex("[A-Za-z0-9-]{8,64}")))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.noBackupFilesDir.canonicalFile, "reset-probe-$token")
        val policy = AeadKeyPolicy("walksafe.instrumentation.reset.$token.v", 1, setOf(1))
        val aead = AndroidKeyStoreAead(policy)
        val delegate = FileAccountDeletionResetJournal(File(root, "journal"))
        if (mode == "cut") {
            assertFalse(root.exists())
            assertTrue(root.mkdirs())
            listOf("gateway", "sensitive", "plain", "field", "fence").forEach {
                File(root, it).writeText("synthetic private state")
            }
            assertTrue(aead.seal("test-only".toByteArray(), "test".toByteArray(), AeadLimits(1024, 2048, 4096)) is AeadSealResult.Sealed)
            File(root, "writer-pid").writeText(Process.myPid().toString())
        }
        val journal = object : AccountDeletionResetJournal by delegate {
            override fun write(next: AccountDeletionResetPhase): Boolean {
                val written = delegate.write(next)
                if (written && mode == "cut" && next == phase) {
                    File(root, "ready").writeText(next.name)
                    check(CountDownLatch(1).await(30, TimeUnit.SECONDS)) { "Host did not stop the process at the requested phase" }
                }
                return written
            }
        }
        val actions = mutableListOf<String>()
        fun purge(name: String): Boolean {
            actions.add(name)
            val file = File(root, name)
            return (!file.exists() || file.delete()) && !file.exists()
        }
        val coordinator = AccountDeletionResetCoordinator(journal,
            storeLegacyIntent = { actions.add("legacy"); File(root, "legacy").writeText("pending"); true },
            resetGateway = { purge("gateway") },
            resetSensitivePreferences = { purge("sensitive") && aead.destroyKnownVersions() },
            resetPlainPreferences = { purge("plain") },
            resetFieldStorage = { purge("field") },
            clearDeletionIntentFence = { purge("fence") })
        if (mode == "cut") {
            coordinator.beginAndReconcile()
            fail("Host must terminate this invocation")
        } else try {
            assertNotEquals(File(root, "writer-pid").readText().toInt(), Process.myPid())
            assertEquals(AccountDeletionResetJournalState.Pending(phase), delegate.read())
            assertTrue(coordinator.hasPendingOrCorruptJournal())
            assertEquals(AccountDeletionResetResult.COMPLETED, coordinator.reconcilePending())
            assertEquals(listOf("legacy", "gateway", "sensitive", "plain", "field", "fence"), actions)
            assertEquals(AccountDeletionResetJournalState.Absent, delegate.read())
            assertEquals(AccountDeletionResetResult.NO_PENDING, coordinator.reconcilePending())
            listOf("gateway", "sensitive", "plain", "field", "fence").forEach { assertFalse(File(root, it).exists()) }
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(policy.alias(1)))
        } finally {
            aead.destroyKnownVersions()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(policy.alias(1) + ".generation") }
            assertTrue(root.deleteRecursively())
        }
    }
}
