package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture
import kr.co.hanium.dreamup.walksafe.network.AndroidPrivacyDeletionClient
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.AccountDeletionInstallationResetController
import org.junit.Assert.*
import org.junit.Test

/** Actual Main factories and thin event adapters, alongside the real durable pipeline tests. */
class MainActivityDeletionPreparationIntegrationTest {
    @Test fun actualMainFactoryReusesEveryPromotedOwnerAndDefersResetAndStorageQueries() {
        val main = MainActivity()
        val controller = owner(main)
        for ((parameter, mainField) in listOf(
            "operation" to "accountDeletionOperationAuthority", "store" to "accountDeletionRecoveryStore",
            "actor" to "accountDeletionRecoveryActorController", "stages" to "accountDeletionWorkerStages",
            "client" to "accountDeletionClient",
        )) assertSame(parameter, field(main, mainField), field(controller, parameter))
        val machineProvider = field(controller, "stateMachine") as Function0<*>
        assertSame(call(main, "getAccountDeletionStateMachine", AccountDeletionStateMachine::class.java), machineProvider.invoke())
        val resetProvider = field(controller, "resetController") as Function0<*>
        assertSame(field(main, "accountDeletionInstallationResetController"), resetProvider.invoke())
        assertTrue(resetProvider.invoke() is AccountDeletionInstallationResetController)
        assertTrue(field(controller, "client") is AndroidPrivacyDeletionClient)
        val store = field(controller, "store") as AccountDeletionRecoveryStore
        assertFalse((field(store, "fallbackMarker\$delegate") as Lazy<*>).isInitialized())
    }

    @Test fun actualCallbackPortReadsTheOriginalActivityLifetimeAtTheTimeOfTheCallback() {
        val main = MainActivity()
        val callbacks = field(owner(main), "callbacks") as AccountDeletionCleanupCallbacks
        assertEquals(field(main, "reportCleanupActivityToken"), callbacks.activityToken)
        assertFalse(callbacks.destroyed)
        set(main, "reportCleanupDestroyed", true)
        assertTrue(callbacks.destroyed)
        set(main, "reportCleanupDestroyed", false)
        assertFalse(callbacks.destroyed)
        assertEquals(field(main, "reportCleanupActivityToken"), callbacks.activityToken)
    }

    @Test fun actualMainConfirmAdapterUsesTheSharedPhaseGuardBeforeAnyStorageOrNetworkAccess() {
        val main = MainActivity()
        val machine = call(main, "getAccountDeletionStateMachine", AccountDeletionStateMachine::class.java) as AccountDeletionStateMachine
        assertEquals(AccountDeletionPhase.IDLE, machine.phase())
        call(main, "onAccountDeletionConfirmClicked", Void.TYPE)
        assertEquals(AccountDeletionPhase.IDLE, machine.phase())
        assertFalse((field(main, "accountDeletionOperationAuthority") as AccountDeletionOperationAuthority).requestInFlight)
        val store = field(main, "accountDeletionRecoveryStore") as AccountDeletionRecoveryStore
        assertFalse((field(store, "fallbackMarker\$delegate") as Lazy<*>).isInitialized())
        assertFalse((field(main, "reportCleanupCallbackHandler\$delegate") as Lazy<*>).isInitialized())
    }

    @Test fun actualMainAdaptersAndFactoryKeepTheExactNetworkEntryAndOriginalPreparationOwners() {
        val source = File(System.getProperty("walksafe.deletion.preparation.main.source")
            ?: "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val mappings = mapOf(
            "onAccountDeletionPrimaryClicked" to "onPrimaryClicked",
            "onAccountDeletionCancelClicked" to "onCancelClicked",
            "onAccountDeletionConfirmClicked" to "onConfirmClicked",
            "startAccountDeletionConfirmationPreparation" to "startConfirmationPreparation",
            "postAccountDeletionRecoveryMarkerPreparation" to "postRecoveryMarkerPreparation",
            "startPreparedAccountDeletionAuthorityConfirmation" to "startPreparedAuthorityConfirmation",
            "startAccountDeletionPreparation" to "startPreparation",
            "postAccountDeletionPendingJournalReady" to "postPendingJournalReady",
            "postAccountDeletionPreparationFailure" to "postPreparationFailure",
            "retainAccountDeletionPreparationRecovery" to "retainPreparationRecovery",
        )
        for ((entry, ownerMethod) in mappings) {
            val body = source.substringAfter("private fun $entry(").substringBefore("\n    private ")
            assertTrue(entry, body.contains("accountDeletionPreparationController.$ownerMethod("))
            assertFalse(entry, body.contains(".execute {"))
        }
        assertFalse(source.contains("private enum class AccountDeletionCallPurpose"))
        assertTrue(source.contains("import kr.co.hanium.dreamup.walksafe.session.deletion.runtime.AccountDeletionCallPurpose"))
        val assembly = MainActivityRefactorSource.composition(source, "accountDeletionPreparationController")
        assertTrue(assembly.contains("accountDeletionNetworkController.value.startCall(callFactory, purpose)"))
        MainActivityRefactorSource.composition(source, "accountDeletionNetworkController")
        assertTrue(source.indexOf("private val accountDeletionRecoveryActorController =") <
            source.indexOf("private val accountDeletionPreparationController ="))
        val relative = "src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionPreparationController.kt"
        val controllerFile = System.getProperty("walksafe.deletion.preparation.owner.source")?.let(::File)
            ?: File(relative).takeIf(File::isFile) ?: File("apps/android/app/$relative")
        val ownerSource = controllerFile.readText()
        assertEquals(2, Regex("val generation = operation.beginPreparation\\(\\)").findAll(ownerSource).count())
        assertTrue(ownerSource.contains("client.requestDeletionCall("))
        assertTrue(ownerSource.contains("purpose = AccountDeletionCallPurpose.INITIAL"))
    }

    private fun owner(main: MainActivity) = field(main, "accountDeletionPreparationController") as AccountDeletionPreparationController
    private fun field(receiver: Any, name: String): Any? =
        if (receiver is MainActivity) MainActivityControllerTestFixture.field(name).get(receiver)
        else receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(receiver)
    private fun set(receiver: Any, name: String, value: Any) {
        if (receiver is MainActivity) MainActivityControllerTestFixture.field(name).set(receiver, value)
        else receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(receiver, value)
    }
    private fun call(main: MainActivity, name: String, result: Class<*>): Any? =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(result)).bindTo(main).invokeWithArguments()
}
