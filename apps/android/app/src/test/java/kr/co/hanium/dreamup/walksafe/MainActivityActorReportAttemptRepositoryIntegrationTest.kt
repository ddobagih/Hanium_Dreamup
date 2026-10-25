package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.report.*
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournalCodec
import org.junit.Assert.*
import org.junit.Test

/** Executes Main's narrow delegates with the real repository and sensitive preference store. */
class MainActivityActorReportAttemptRepositoryIntegrationTest {
    @Test fun actualMainAttemptDelegatesUseOneOwnedRepositoryAndPersistTheSameBackoff() {
        val f = Fixture()
        assertTrue(f.call("ensureReportAttemptStateActor", arrayOf(String::class.java), "actor-a") as Boolean)
        val key = f.preferences.key()
        assertEquals(2_000L, f.call("persistTransientReportAttemptFailure",
            arrayOf(String::class.java, java.lang.Long.TYPE, Integer::class.java, java.lang.Long::class.java), key, f.preferences.now, null, null))
        assertEquals("reportCandidate=backoff_persisted remainingMs=2000", f.call("persistedAutomaticReportBlockStatus",
            arrayOf(String::class.java, java.lang.Long.TYPE), key, f.preferences.now))
        val restarted = f.preferences.newRepository()
        assertTrue(restarted.ensureActor("actor-a"))
        assertEquals(f.owner.automaticBlockStatus(key, f.preferences.now), restarted.automaticBlockStatus(key, f.preferences.now))
        assertNull(f.call("persistedAutomaticReportBlockStatus", arrayOf(String::class.java, java.lang.Long.TYPE), key, f.preferences.now + 2_000L))
    }

    @Test fun actualMainActorSwitchAndCommittedClearRetireOnlyTheApplicableState() {
        val f = Fixture()
        assertTrue(f.call("ensureReportAttemptStateActor", arrayOf(String::class.java), "actor-a") as Boolean)
        f.owner.blockStorageForActor("actor-a")
        assertTrue(f.call("clearPersistedReportAttemptState", arrayOf(String::class.java), f.preferences.key()) as Boolean)
        assertNull(f.owner.automaticBlockStatus(f.preferences.key(), f.preferences.now))
        assertTrue(f.call("persistTerminalReportAttemptState", arrayOf(String::class.java, Integer::class.java, java.lang.Long.TYPE),
            f.preferences.key(), 422, f.preferences.now) as Boolean)
        assertTrue(f.call("ensureReportAttemptStateActor", arrayOf(String::class.java), "actor-b") as Boolean)
        assertNull(f.preferences.preferences.getString(ActorReportAttemptRepository.PREF_REPORT_ATTEMPT_STATE_V2, null))
        assertNull(f.owner.automaticBlockStatus(f.preferences.key(), f.preferences.now))
    }

    @Test fun actualMainCooldownCommitAndRestoreUseTheSameSensitiveStore() {
        val f = Fixture()
        f.set("reportAttemptStore", f.preferences.attemptStore())
        val cooldown = AndroidReportSuccessfulCooldown(f.preferences.scope(), f.preferences.now,
            f.preferences.now, AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS, 1)
        assertTrue(f.call("persistReportCooldownForSuccess", arrayOf(AndroidReportSuccessfulCooldown::class.java), cooldown) as Boolean)
        val attempts = f.preferences.attemptStore()
        f.preferences.newRepository().restoreCooldowns(attempts)
        assertEquals(AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN,
            (attempts.acquire(f.preferences.scope(), f.preferences.now, false) as AndroidReportAttemptResult.Blocked).reason)
        f.preferences.storage.commitsSucceed = false
        // An identical durable edit needs no commit; a changed success must still fail closed.
        val changedCooldown = cooldown.copy(lastUploadedAtMs = cooldown.lastUploadedAtMs + 1L)
        assertFalse(f.call("persistReportCooldownForSuccess", arrayOf(AndroidReportSuccessfulCooldown::class.java), changedCooldown) as Boolean)
    }

    @Test fun actualMainJournalWriterAndReaderShareTheStrictCodec() {
        val f = Fixture()
        val journal = AccountDeletionJournal.pending("https://gateway.example.test", "installation_0001",
            "account_delete_request_0001", "2026-10-06T00:00:00Z")
        val encoded = f.call("accountDeletionJournalJson", arrayOf(AccountDeletionJournal::class.java), journal) as String
        assertEquals(AccountDeletionJournalCodec.encode(journal), encoded)
        assertEquals(journal, f.call("accountDeletionJournalOrNull", arrayOf(String::class.java), encoded))
        assertNull(f.call("accountDeletionJournalOrNull", arrayOf(String::class.java), encoded.replace("\"client_revision\":1", "\"client_revision\":\"1\"")))
    }

    @Test fun actualMainAccountDeletionFenceKeepsTheOriginalResetAndSafetyOrder() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val assembly = MainActivityRefactorSource.composition(source, "accountDeletionRuntimeFenceController")
        assertTrue(assembly.contains("reports = ports.reports"))
        assertTrue(assembly.contains("stopActiveWalk = ports.stopActiveWalk"))
        val fence = MainActivityRefactorSource.factory(source, "createPrivacyRuntimeFence")
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionRuntimeFenceController.kt").readText()
        val apply = MainActivityRefactorSource.member(owner, "apply")
        assertTrue(MainActivityRefactorSource.member(source, "applyAccountDeletionRuntimeFence")
            .contains("accountDeletionRuntimeFenceController.apply()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(apply,
            "reports.advanceUploadAuthority()", "reports.clearAttemptState()", "stopActiveWalk()"))
        val uploadFence = fence.indexOf("automaticReportUploadSafetyGeneration += 1L")
        val priorAttemptReset = fence.indexOf("reportAttemptStore = AndroidReportAttemptStore(bootCountProvider = ::reportCooldownBootCount)")
        val ownedReset = fence.indexOf("actorReportAttemptRepository.clearForAccountDeletion()")
        val safetyStop = fence.indexOf("enterWalkSessionSafetyStopAndCancelOutputs(")
        assertTrue(uploadFence >= 0)
        assertTrue(priorAttemptReset > uploadFence)
        assertTrue(ownedReset > priorAttemptReset)
        assertTrue(safetyStop > ownedReset)
        assertFalse(fence.contains("reportAttemptStateLock"))
        assertFalse(fence.contains("persistedReportAttemptStates"))
    }

    private class Fixture {
        val preferences = ActorReportPreferenceFixture()
        val main = MainActivity().also { setField(it, "sensitivePrefs", preferences.preferences) }
        val owner = field("actorReportAttemptRepository") as ActorReportAttemptRepository
        fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(main)
        fun set(name: String, value: Any) = setField(main, name, value)
        fun call(name: String, types: Array<Class<*>>, vararg arguments: Any?): Any? {
            val returnType = when (name) {
                "ensureReportAttemptStateActor", "clearPersistedReportAttemptState",
                "persistTerminalReportAttemptState", "persistReportCooldownForSuccess" -> java.lang.Boolean.TYPE
                "persistTransientReportAttemptFailure" -> java.lang.Long.TYPE
                "persistedAutomaticReportBlockStatus", "accountDeletionJournalJson" -> String::class.java
                "accountDeletionJournalOrNull" -> AccountDeletionJournal::class.java
                else -> error("Missing actual Main method signature: $name")
            }
            // getDeclaredMethod resolves unrelated GL10 signatures absent from the host JVM.
            // Resolve this real Main method only, as the existing Main admission fixtures do.
            return MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, name, MethodType.methodType(returnType, types.toList()))
                .bindTo(main).invokeWithArguments(arguments.toList())
        }

        companion object {
            fun setField(main: MainActivity, name: String, value: Any) {
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(main, value)
            }
        }
    }
}
