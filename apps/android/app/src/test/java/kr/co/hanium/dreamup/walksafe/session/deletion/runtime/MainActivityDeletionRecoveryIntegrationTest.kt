package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import android.content.SharedPreferences
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Proxy
import java.nio.file.Files
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.network.AccountDeletionRev0RecoveryBinding
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.AccountDeletionResetStorage
import org.junit.Assert.*
import org.junit.Test

/** Uses the proposed Main's actual constructors/providers rather than replacing its controllers. */
class MainActivityDeletionRecoveryIntegrationTest {
    @Test fun actualMainFactoriesKeepOneProcessStateMachineAndOneRecoveryStoreWithoutEarlyMarkerIo() {
        val main = MainActivity()
        val store = field(main, "accountDeletionRecoveryStore") as AccountDeletionRecoveryStore
        val actor = field(main, "accountDeletionRecoveryActorController") as AccountDeletionRecoveryActorController
        val stages = field(main, "accountDeletionWorkerStages") as AccountDeletionWorkerStages
        val machine = call(main, "getAccountDeletionStateMachine", AccountDeletionStateMachine::class.java)
        for (owner in listOf(store, actor, stages)) {
            val provider = field(owner, "stateMachine") as Function0<*>
            assertSame(machine, provider.invoke())
        }
        assertSame(store, field(actor, "store"))
        assertSame(stages, field(store, "workerStages"))
        assertSame(stages, field(actor, "workerStages"))
        assertFalse((field(store, "fallbackMarker\$delegate") as Lazy<*>).isInitialized())
        assertNull(call(main, "accountDeletionRev0RecoveryBindingOrNull", AccountDeletionRev0RecoveryBinding::class.java,
            arrayOf(Boolean::class.javaPrimitiveType!!), true))
    }

    @Test fun resetStorageCapturedFromTheActualMainReadsTheSameBoundDualAuthority() {
        val main = MainActivity()
        val store = field(main, "accountDeletionRecoveryStore") as AccountDeletionRecoveryStore
        val resetStorage = field(main, "accountDeletionResetStorage") as AccountDeletionResetStorage
        var fenced = false
        store.bindIntentFence(AccountDeletionIntentFence({ fenced }, { fenced = true; true }, { fenced = false; true }))
        val root = Files.createTempDirectory("walksafe-main-recovery-authority-").toFile()
        try {
            store.bindFileAuthority(FileAccountDeletionIntentAuthority(File(root, "authority")))
            val provider = field(resetStorage, "dualAuthority") as Function0<*>
            assertSame(store.dualAuthority, provider.invoke())
            assertSame(store.dualAuthority, call(main, "getAccountDeletionDualAuthority", AccountDeletionDualAuthority::class.java))
            assertSame(store.intentFence, call(main, "getAccountDeletionIntentFence", AccountDeletionIntentFence::class.java))
        } finally { assertTrue(root.deleteRecursively()) }
    }

    @Test fun observedAuthorityAndRemoteBlockAreTheRealOwnersFactsWithNoMainBackingCopies() {
        val main = MainActivity()
        val store = field(main, "accountDeletionRecoveryStore") as AccountDeletionRecoveryStore
        val snapshot = AccountDeletionAuthoritySnapshot(AccountDeletionIntentFenceState.PRESENT,
            AccountDeletionIntentAuthorityState.Confirmed, AccountDeletionAggregateAuthorityState.CONFIRMED)
        store.observeAuthority(snapshot)
        assertSame(snapshot, call(main, "getAccountDeletionAuthorityAtStartup", AccountDeletionAuthoritySnapshot::class.java))
        store.setRemoteResumeBlocked(true)
        assertEquals(true, call(main, "getAccountDeletionRemoteResumeBlocked", Boolean::class.javaPrimitiveType!!))
        store.setRemoteResumeBlocked(false)
        assertEquals(false, call(main, "getAccountDeletionRemoteResumeBlocked", Boolean::class.javaPrimitiveType!!))
        val names = MainActivity::class.java.declaredFields.map { it.name }
        for (name in listOf("accountDeletionAuthorityAtStartup", "accountDeletionRemoteResumeBlocked",
            "accountDeletionStartupFallbackState", "accountDeletionFallbackPresentAtStartup",
            "accountDeletionRev0ReauthenticationRequestId", "accountDeletionLegacyRev0GeneralReauthenticationRequestId")) {
            assertFalse(name, name in names)
        }
    }

    @Test fun actualMainJournalDelegateWritesTheSameSensitiveStoreAndActorReadinessIsQueriedLate() =
        DeletionRecoveryFixture().use { f ->
            val main = MainActivity()
            val actor = field(main, "accountDeletionRecoveryActorController") as AccountDeletionRecoveryActorController
            val ready = field(actor, "storageReady") as Function0<*>
            assertEquals(false, ready.invoke())
            set(main, "sensitivePrefs", f.preferences)
            val plain = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, _ -> error("Unexpected preference read: ${method.name}") } as SharedPreferences
            set(main, "stepLengthPrefs", plain)
            assertEquals(true, ready.invoke())
            val journal = AccountDeletionJournal.pending("https://gateway.example.test", "installation_0001",
                "account_delete_core_0001", "2026-10-06T00:00:00Z")
            assertEquals(true, call(main, "persistAccountDeletionJournal", Boolean::class.javaPrimitiveType!!,
                arrayOf(AccountDeletionJournal::class.java), journal))
            assertEquals(AccountDeletionJournalCodec.encode(journal), f.preferences.getString("account_deletion_journal_v2", null))
            assertFalse(f.disk.values.toString().contains(journal.requestId))
        }

    private fun field(receiver: Any, name: String): Any? = receiver.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(receiver)
    private fun set(receiver: Any, name: String, value: Any) = receiver.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(receiver, value)
    private fun call(main: MainActivity, name: String, result: Class<*>, arguments: Array<Class<*>> = emptyArray(), vararg values: Any?): Any? =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(result, arguments.toList()))
            .bindTo(main).invokeWithArguments(values.toList())
}
