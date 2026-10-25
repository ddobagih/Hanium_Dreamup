package kr.co.hanium.dreamup.walksafe.session.deletion.reset

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.AccountDeletionResetCoordinator
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import org.junit.Assert.*
import org.junit.Test

/** Actual Main delegates; no device/real account storage initialization is performed. */
class MainActivityInstallationResetIntegrationTest {
    @Test fun actualMainResetDelegateUsesOneReservationCoordinatorAndCapturedProvider() {
        val f = ResetFixture()
        val main = mainWith(f)
        f.restoreCompleted()
        call(main, "resetAfterConfirmedAccountDeletion", Void.TYPE)
        assertEquals(AccountDeletionPhase.RESET_PENDING, f.machine.phase())
        f.worker.runNext()
        assertEquals(AccountDeletionPhase.IDLE, f.machine.phase())
        assertSame(f.controller.resetCoordinator,
            call(main, "getAccountDeletionResetCoordinator", AccountDeletionResetCoordinator::class.java))
        f.runUi()
        assertEquals(listOf("runtime-reset", "publish-fresh"), f.events.takeLast(2))
    }

    @Test fun actualMainFailureDelegateRequiresCallbackAuthorityAndBlocksRealSensitiveStore() {
        val f = ResetFixture()
        val main = mainWith(f)
        f.callbackAllowed = false
        assertEquals(false, call(main, "blockForAccountDeletionResetFailure", java.lang.Boolean.TYPE))
        assertFalse(f.sensitive.isBlocked())
        f.callbackAllowed = true
        assertEquals(true, call(main, "blockForAccountDeletionResetFailure", java.lang.Boolean.TYPE))
        assertTrue(f.sensitive.isBlocked())
        assertEquals(AccountDeletionPhase.FAIL_CLOSED, f.machine.phase())
    }

    @Test fun mainLegacyEnumAdapterKeepsStrictDecodeForTheSamePreferenceValue() {
        val f = ResetFixture()
        val main = mainWith(f)
        val type = Class.forName("kr.co.hanium.dreamup.walksafe.MainActivity\$LegacyResetIntentState")
        assertEquals("ABSENT", (call(main, "legacyAccountDeletionResetIntentState", type) as Enum<*>).name)
        f.plain.values["account_deletion_reset_required_v1"] = "true"
        assertEquals("CORRUPT", (call(main, "legacyAccountDeletionResetIntentState", type) as Enum<*>).name)
        f.plain.values["account_deletion_reset_required_v1"] = true
        assertEquals("EXACT", (call(main, "legacyAccountDeletionResetIntentState", type) as Enum<*>).name)
    }

    @Test fun mainAssemblyPreservesAuthorityAndJournalObservationBeforeSensitiveRestore() {
        val mainSourceFile = sourceFile("MainActivity.kt")
        val source = mainSourceFile.readText()
        val authorityBinding = source.indexOf("accountDeletionRecoveryStore.bindFileAuthority(fileAccountDeletionIntentAuthority)")
        val initialization = source.indexOf("accountDeletionInstallationResetController.initialize(noBackupRoot)")
        val authority = source.indexOf("accountDeletionRecoveryStore.observeAuthority(accountDeletionDualAuthority.startupState())", initialization)
        val observation = source.indexOf("accountDeletionInstallationResetController.observeJournalAtStartup()", authority)
        val sensitive = source.indexOf("sensitivePrefs = AndroidSensitivePreferenceStore(", observation)
        assertTrue(authorityBinding >= 0 && initialization > authorityBinding && authority > initialization && observation > authority && sensitive > observation)
        assertTrue(source.contains("get() = accountDeletionRecoveryStore.dualAuthority"))
        assertTrue(source.contains("get() = accountDeletionRecoveryStore.authorityAtStartup"))
        assertFalse(Regex("private var accountDeletionAuthorityAtStartup\\b").containsMatchIn(source))
        val recovery = sourceFile("session/deletion/runtime/AccountDeletionRecoveryStore.kt").readText()
        assertTrue(recovery.contains("dualAuthority = AccountDeletionDualAuthority(preferenceFence = intentFence, fileAuthority = authority)"))
        assertTrue(recovery.contains("fun observeAuthority(snapshot: AccountDeletionAuthoritySnapshot) { authorityAtStartup = snapshot }"))
        val reset = sourceFile("session/deletion/reset/AccountDeletionInstallationResetController.kt").readText()
        assertTrue(reset.contains("fun observeJournalAtStartup() { startupJournalState = coordinator.journalState() }"))
        val sensitiveRestore = source.substring(sensitive).substringBefore("gatewaySessionStore =")
        assertTrue(sensitiveRestore.contains("accountDeletionResetJournalStateAtStartup !is"))
        assertTrue(sensitiveRestore.contains("AccountDeletionResetJournalState.Absent"))
        assertTrue(sensitiveRestore.contains("legacyResetIntentAtStartup != LegacyResetIntentState.ABSENT"))
        assertTrue(source.contains("accountDeletionInstallationResetController.restoreStartupHandoffAfterResourcesPrepared()"))
        assertTrue(source.contains("accountDeletionInstallationResetController.inspectStartupReset(speechOutputPurgeCompleted)"))
        val resetAssembly = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.composition(source, "accountDeletionInstallationResetController")
        assertTrue(resetAssembly.contains("get() = PrivacyStartupInspectionController.accountDeletionStartupResetHandoffPending"))
        assertTrue(resetAssembly.contains("override fun captureParticipants(): PreparedPrivacyStartupReset = captureInstallationResetParticipants()"))
        assertTrue(source.contains("private fun captureInstallationResetParticipants(): PreparedPrivacyStartupReset = privacyAuthority.captureInstallationResetParticipants()"))
        val graph = sourceFile("session/privacy/PrivacyAuthorityComposition.kt").readText()
        val captured = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.member(graph, "captureInstallationResetParticipants")
        for (binding in listOf("prepareSpeech = { speechOutputStorageCoordinator.prepareAccountPurgeOnWorker() }", "prepareRoute = { routeArchive().prepareRouteSnapshotAccountPurge() }",
            "awaitSpeech = { preparation -> speechOutputStorageCoordinator.awaitPreparedAccountPurge(preparation) }", "awaitRoute = { preparation -> routeArchive().awaitRouteSnapshotAccountPurgeOnWorker(preparation) }",
            "speechIsCurrent = { preparation -> speechOutputStorageCoordinator.resetPreparationIsCurrent(preparation) }", "routeIsCurrent = { preparation -> routeArchive().routeSnapshotResetPreparationIsCurrent(preparation) }")) {
            assertTrue(binding, captured.contains(binding))
        }
    }

    private fun sourceFile(path: String): File {
        val relative = "src/main/java/kr/co/hanium/dreamup/walksafe/$path"
        return File(relative).takeIf(File::isFile) ?: File("apps/android/app/$relative")
    }

    private fun mainWith(f: ResetFixture) = MainActivity().also { main ->
        MainActivity::class.java.getDeclaredField("accountDeletionInstallationResetController")
            .apply { isAccessible = true }.set(main, f.controller)
    }
    private fun call(main: MainActivity, name: String, returnType: Class<*>): Any? =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(returnType)).bindTo(main).invokeWithArguments()
}
