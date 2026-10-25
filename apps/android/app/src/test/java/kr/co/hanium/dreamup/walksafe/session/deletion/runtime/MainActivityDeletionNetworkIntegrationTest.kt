package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStatus
import org.junit.Assert.*
import org.junit.Test

class MainActivityDeletionNetworkIntegrationTest {
    @Test fun actualLazyFactoryReusesAllPromotedOwnersAndTheOriginalClientWithoutEarlyIo() {
        val main = MainActivity()
        val lazy = field(main, "accountDeletionNetworkController\$delegate") as Lazy<*>
        assertFalse(lazy.isInitialized())
        val controller = owner(main)
        assertTrue(lazy.isInitialized())
        assertSame(controller, owner(main))
        for ((parameter, mainField) in listOf("operation" to "accountDeletionOperationAuthority",
            "store" to "accountDeletionRecoveryStore", "actor" to "accountDeletionRecoveryActorController",
            "stages" to "accountDeletionWorkerStages", "client" to "accountDeletionClient")) {
            assertSame(parameter, field(main, mainField), field(controller, parameter))
        }
        val provider = field(controller, "stateMachine") as Function0<*>
        assertSame(call(main, "getAccountDeletionStateMachine", AccountDeletionStateMachine::class.java), provider.invoke())
        val prep = field(main, "accountDeletionPreparationController") as AccountDeletionPreparationController
        assertSame(field(prep, "client"), field(controller, "client"))
        val store = field(main, "accountDeletionRecoveryStore") as AccountDeletionRecoveryStore
        assertFalse((field(store, "fallbackMarker\$delegate") as Lazy<*>).isInitialized())
    }

    @Test fun actualAllowedAdapterCancelsBeforeAdmissionWhenNoCurrentJournalExists() {
        val main = MainActivity()
        var cancelled = 0
        val call = CancellableNetworkCall<AccountDeletionStatus>(
            executeBlock = { error("No real SDK invocation in this fixture") }, cancelBlock = { cancelled++ })
        invoke(main, "startAccountDeletionCallAllowed", Void.TYPE,
            arrayOf(CancellableNetworkCall::class.java, AccountDeletionCallPurpose::class.java), call, AccountDeletionCallPurpose.STATUS)
        assertEquals(1, cancelled)
        assertTrue(call.isCancelled())
        assertFalse((field(main, "accountDeletionOperationAuthority") as AccountDeletionOperationAuthority).requestInFlight)
        assertFalse((field(main, "reportCleanupCallbackHandler\$delegate") as Lazy<*>).isInitialized())
    }

    @Test fun actualLocalPurgeQueryIsReadLateFromTheSingleExistingProgressSlot() {
        val main = MainActivity()
        val query = field(owner(main), "queries") as AccountDeletionNetworkQueries
        assertNull(query.localPurgeRequestId())
        MainActivityControllerTestFixture.field("accountDeletionLocalPurgeInFlightRequestId")
            .set(main, "network_request_0001")
        assertEquals("network_request_0001", query.localPurgeRequestId())
    }

    @Test fun actualEventAdaptersEnterTheSameOwnerAndKeepSdkAndCompletionOrdersInTheOwner() {
        val source = File(System.getProperty("walksafe.deletion.network.main.source")
            ?: "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val mappings = mapOf("refreshAccountDeletionStatus" to "refreshStatus",
            "refreshAccountDeletionStatusAllowed" to "refreshStatusAllowed", "startAccountDeletionCall" to "startCall",
            "startAccountDeletionCallAllowed" to "startCallAllowed", "scheduleAccountDeletionNetworkCompletion" to "scheduleCompletion",
            "markAccountDeletionRetry" to "markRetry")
        for ((entry, method) in mappings) {
            val body = source.substringAfter("private fun $entry(").substringBefore("\n    private ")
            assertTrue(entry, body.contains("accountDeletionNetworkController.$method("))
            assertFalse(entry, body.contains(".execute {"))
        }
        assertEquals(2, Regex("private fun refreshAccountDeletionStatus\\(").findAll(source).count())
        val preparation = MainActivityRefactorSource.composition(source, "accountDeletionPreparationController")
        assertTrue(preparation.contains("accountDeletionNetworkController.value.startCall(callFactory, purpose)"))
        val network = MainActivityRefactorSource.composition(source, "accountDeletionNetworkController")
        assertTrue(network.contains("client = deletionClient()"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrivacyAuthorityComposition")
            .contains("deletionClient = { accountDeletionClient }"))
        val relative = "src/main/java/kr/co/hanium/dreamup/walksafe/session/deletion/runtime/AccountDeletionNetworkController.kt"
        val ownerFile = System.getProperty("walksafe.deletion.network.owner.source")?.let(::File)
            ?: File(relative).takeIf(File::isFile) ?: File("apps/android/app/$relative")
        val owner = ownerFile.readText()
        val entry = owner.substringAfter("override fun startCall(").substringBefore("fun startCallAllowed(")
        assertTrue(entry.indexOf("dispatchAccountDeletionNetworkEntry(") < entry.indexOf("callFactory()"))
        assertTrue(owner.indexOf("registerNetworkCall(") < owner.indexOf("operation.admitCall(call)"))
        assertTrue(owner.indexOf("operation.admitCall(call)") < owner.indexOf("beginNetworkCall(networkLease)"))
        assertTrue(owner.indexOf("beginNetworkCall(networkLease)") < owner.indexOf("executeAccountDeletionCall(call)"))
        assertTrue(owner.contains("store.persistJournal(snapshot)"))
        assertTrue(owner.contains("progress.scheduleAfterStatus(snapshot)"))
    }

    private fun owner(main: MainActivity) = call(main, "getAccountDeletionNetworkController", AccountDeletionNetworkController::class.java) as AccountDeletionNetworkController
    private fun field(receiver: Any, name: String): Any? = receiver.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(receiver)
    private fun call(main: MainActivity, name: String, result: Class<*>): Any? = invoke(main, name, result, emptyArray())
    private fun invoke(main: MainActivity, name: String, result: Class<*>, types: Array<Class<*>>, vararg values: Any?): Any? =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(result, types.toList()))
            .bindTo(main).invokeWithArguments(values.toList())
}
