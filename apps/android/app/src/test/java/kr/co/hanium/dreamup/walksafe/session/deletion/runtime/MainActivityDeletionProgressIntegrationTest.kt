package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine
import org.junit.Assert.*
import org.junit.Test

class MainActivityDeletionProgressIntegrationTest {
    @Test fun actualFactoriesShareCoreStateEvidenceAndTheSameResetCoordinatorWithoutStartingIo() {
        val main = MainActivity()
        val lazy = field(main, "accountDeletionProgressController\$delegate") as Lazy<*>
        assertFalse(lazy.isInitialized())
        val progress = progress(main)
        val evidence = evidence(main)
        assertSame(evidence, field(progress, "evidenceController"))
        assertSame(progress, progress(main))
        for ((parameter, mainField) in listOf("operation" to "accountDeletionOperationAuthority", "store" to "accountDeletionRecoveryStore",
            "actor" to "accountDeletionRecoveryActorController", "stages" to "accountDeletionWorkerStages")) {
            assertSame(parameter, field(main, mainField), field(progress, parameter))
        }
        assertSame(field(main, "accountDeletionRecoveryStore"), field(evidence, "store"))
        assertSame(field(main, "accountDeletionWorkerStages"), field(evidence, "stages"))
        assertSame(field(main, "accountDeletionClient"), field(evidence, "client"))
        val resetProvider = field(progress, "resetController") as Function0<*>
        assertSame(field(main, "accountDeletionInstallationResetController"), resetProvider.invoke())
        val machine = call(main, "getAccountDeletionStateMachine", AccountDeletionStateMachine::class.java)
        for (owner in listOf(progress, evidence)) assertSame(machine, (field(owner, "stateMachine") as Function0<*>).invoke())
        val store = field(main, "accountDeletionRecoveryStore") as AccountDeletionRecoveryStore
        assertFalse((field(store, "fallbackMarker\$delegate") as Lazy<*>).isInitialized())
        assertFalse((field(main, "reportCleanupCallbackHandler\$delegate") as Lazy<*>).isInitialized())
    }

    @Test fun mainAndNetworkReadTheSameActualPurgeSlotWithoutAMainBackingCopy() {
        val main = MainActivity()
        assertFalse(MainActivity::class.java.declaredFields.any { it.name == "accountDeletionLocalPurgeInFlightRequestId" })
        val progress = progress(main)
        val network = call(main, "getAccountDeletionNetworkController", AccountDeletionNetworkController::class.java)!!
        val queries = field(network, "queries") as AccountDeletionNetworkQueries
        assertNull(queries.localPurgeRequestId())
        set(progress, "localPurgeInFlightRequestId", "progress_request_0001")
        assertEquals("progress_request_0001", queries.localPurgeRequestId())
        assertEquals("progress_request_0001", call(main, "getAccountDeletionLocalPurgeInFlightRequestId", String::class.java))
        set(progress, "localPurgeInFlightRequestId", null)
        assertNull(queries.localPurgeRequestId())
    }

    @Test fun callbackPortsKeepCleanupRetirementSeparateFromTheSamePrivacyStartupOwner() {
        val main = MainActivity()
        val callbacks = field(progress(main), "callbacks") as AccountDeletionProgressCallbacks
        assertEquals(field(main, "reportCleanupActivityToken"), callbacks.activityToken)
        assertFalse(callbacks.destroyed)
        assertFalse(callbacks.startupInspectionDestroyed)
        val startup = field(main, "privacyStartupInspectionController")!!
        set(startup, "privacyStartupInspectionDestroyed", true)
        assertTrue(callbacks.startupInspectionDestroyed)
        assertFalse(callbacks.destroyed)
        set(main, "reportCleanupDestroyed", true)
        assertTrue(callbacks.destroyed)
    }

    @Test fun allActualMainEventAdaptersDelegateAndTerminalCleanupUsesTheSameResetOwner() {
        val source = File(System.getProperty("walksafe.deletion.progress.main.source")
            ?: "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val mappings = mapOf("handleAccountDeletionEvidenceRevisionConflict" to "accountDeletionEvidenceController.handleRevisionConflict",
            "scheduleAccountDeletionEvidenceRebase" to "accountDeletionEvidenceController.scheduleRebase",
            "newAccountDeletionEvidenceId" to "accountDeletionEvidenceController.newEvidenceId",
            "submitPendingDeviceDeletionEvidence" to "accountDeletionEvidenceController.submitPending",
            "submitPendingDeviceDeletionEvidenceAllowed" to "accountDeletionEvidenceController.submitPendingAllowed",
            "scheduleAccountDeletionActorBindingCleanup" to "accountDeletionProgressController.scheduleActorBindingCleanup",
            "applyAccountDeletionFenceAndPurgeLocal" to "accountDeletionProgressController.applyFenceAndPurgeLocal",
            "scheduleAccountDeletionProgressAfterStatus" to "accountDeletionProgressController.scheduleAfterStatus",
            "resumeAccountDeletionFromMarker" to "accountDeletionProgressController.resumeFromMarker",
            "continueAccountDeletionFromMarker" to "accountDeletionProgressController.continueFromMarker",
            "scheduleAcceptedAccountDeletionLocalPurge" to "accountDeletionProgressController.scheduleAcceptedLocalPurge",
            "postAccountDeletionProgressFailure" to "accountDeletionProgressController.postFailure",
            "postAccountDeletionStartupFailure" to "accountDeletionProgressController.postStartupFailure",
            "startAccountDeletionFallbackRecoveryAfterRestore" to "accountDeletionProgressController.startFallbackRecoveryAfterRestore")
        for ((entry, method) in mappings) {
            val helper = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
            val body = if (entry in setOf("handleAccountDeletionEvidenceRevisionConflict", "scheduleAccountDeletionProgressAfterStatus", "postAccountDeletionProgressFailure")) {
                val binding = helper.composition(source, "accountDeletionNetworkController")
                assertTrue(binding.contains("progress = object : AccountDeletionNetworkProgress"))
                assertTrue(binding.contains("override fun postFailure(errorCode: String, workerAttempt: AccountDeletionWorkerAttempt)"))
                assertTrue(binding.contains("accountDeletionProgressController.value.postFailure(errorCode, workerAttempt)"))
                assertTrue(binding.contains("override fun handleEvidenceRevisionConflict(responseBody: String, workerAttempt: AccountDeletionWorkerAttempt) ="))
                assertTrue(binding.contains("accountDeletionEvidenceController.value.handleRevisionConflict(responseBody, workerAttempt)"))
                assertTrue(binding.contains("override fun scheduleAfterStatus(journal: AccountDeletionJournal) { accountDeletionProgressController.value.scheduleAfterStatus(journal) }"))
                binding
            } else helper.member(source, entry)
            val target = if (entry in setOf("handleAccountDeletionEvidenceRevisionConflict", "scheduleAccountDeletionProgressAfterStatus", "postAccountDeletionProgressFailure"))
                method.replace("Controller.", "Controller.value.") else method
            assertTrue(entry, body.contains("$target("))
            assertFalse(entry, body.contains(".execute {"))
        }
        assertTrue(source.contains("get() = accountDeletionProgressController.localPurgeRequestId"))
        val progressAssembly = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.composition(source, "accountDeletionProgressController")
        assertTrue(progressAssembly.contains("evidenceController = accountDeletionEvidenceController.value"))
        assertTrue(progressAssembly.contains("purge = AccountDeletionLocalDataPurge { accountDeletionLocalDataPurger.value.purgeLocalDataOnWorker() }"))
        kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.composition(source, "accountDeletionLocalDataPurger")
        val ownerFile = System.getProperty("walksafe.deletion.progress.owner.source")?.let(::File)
            ?: File("src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionProgressController.kt")
        val owner = ownerFile.readText()
        assertTrue(owner.contains("resetController().terminalCleanup.begin(journal)"))
        assertTrue(owner.contains("resetController().terminalCleanup.complete("))
        assertTrue(owner.contains("markerCleanup = markerCleanup"))
        assertTrue(owner.contains("actorBindingAbsent = actorBindingAbsent"))
    }

    private fun progress(main: MainActivity) = call(main, "getAccountDeletionProgressController", AccountDeletionProgressController::class.java) as AccountDeletionProgressController
    private fun evidence(main: MainActivity) = call(main, "getAccountDeletionEvidenceController", AccountDeletionEvidenceController::class.java) as AccountDeletionEvidenceController
    private fun field(receiver: Any, name: String): Any? =
        if (receiver is MainActivity) MainActivityControllerTestFixture.field(name).get(receiver)
        else receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(receiver)
    private fun set(receiver: Any, name: String, value: Any?) {
        if (receiver is MainActivity) MainActivityControllerTestFixture.field(name).set(receiver, value)
        else receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(receiver, value)
    }
    private fun call(main: MainActivity, name: String, result: Class<*>): Any? = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
        .findVirtual(MainActivity::class.java, name, MethodType.methodType(result)).bindTo(main).invokeWithArguments()
}
