package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStatus
import org.junit.Assert.*
import org.junit.Test

/** Compiled against the actual proposed Main, with its real admission-owner initializer. */
class MainActivityDeletionOperationIntegrationTest {
    @Test fun actualMainPreparationDelegatesRetireOnlyTheRealOwnersCurrentTicket() {
        val main = MainActivity()
        val owner = owner(main)
        val stale = owner.beginPreparation()
        val current = owner.beginPreparation()
        assertEquals(false, call(main, "isCurrentAccountDeletionPreparation", Boolean::class.javaPrimitiveType!!,
            arrayOf(Long::class.javaPrimitiveType!!), stale))
        assertEquals(false, call(main, "completeAccountDeletionPreparation", Boolean::class.javaPrimitiveType!!,
            arrayOf(Long::class.javaPrimitiveType!!), stale))
        assertTrue(owner.requestInFlight)
        assertEquals(true, call(main, "isCurrentAccountDeletionPreparation", Boolean::class.javaPrimitiveType!!,
            arrayOf(Long::class.javaPrimitiveType!!), current))
        assertEquals(true, call(main, "completeAccountDeletionPreparation", Boolean::class.javaPrimitiveType!!,
            arrayOf(Long::class.javaPrimitiveType!!), current))
        assertFalse(owner.requestInFlight)
    }

    @Test fun actualMainNetworkCompletionRequiresTheSameCallAndGeneration() {
        val main = MainActivity()
        val owner = owner(main)
        val actual = CancellableNetworkCall<AccountDeletionStatus>({ error("No HTTP execution in this fixture") })
        val other = CancellableNetworkCall<AccountDeletionStatus>({ error("No HTTP execution in this fixture") })
        val generation = owner.admitCall(actual)
        val signature = arrayOf(Long::class.javaPrimitiveType!!, CancellableNetworkCall::class.java)
        assertEquals(false, call(main, "completeCurrentAccountDeletionCall", Boolean::class.javaPrimitiveType!!,
            signature, generation, other))
        assertTrue(owner.requestInFlight)
        assertEquals(true, call(main, "completeCurrentAccountDeletionCall", Boolean::class.javaPrimitiveType!!,
            signature, generation, actual))
        assertFalse(owner.requestInFlight)
    }

    @Test fun actualMainUiFactReadsTheSameSlotWithoutAnotherMutableBackingField() {
        val main = MainActivity()
        assertFalse(MainActivity::class.java.declaredFields.any { it.name == "accountDeletionRequestInFlight" })
        assertEquals(false, call(main, "getAccountDeletionRequestInFlight", Boolean::class.javaPrimitiveType!!, emptyArray()))
        val owner = owner(main)
        val ticket = owner.beginPreparation()
        assertEquals(true, call(main, "getAccountDeletionRequestInFlight", Boolean::class.javaPrimitiveType!!, emptyArray()))
        assertTrue(owner.completePreparation(ticket))
        assertEquals(false, call(main, "getAccountDeletionRequestInFlight", Boolean::class.javaPrimitiveType!!, emptyArray()))
    }

    @Test fun proposedMainBothPreparationEntriesNetworkAdmissionAndDestroyUseThisSingleOwner() {
        val source = File(System.getProperty("walksafe.deletion.operation.main.source")
            ?: "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        assertEquals(1, Regex("private val accountDeletionOperationAuthority = privacyAuthority.createAccountDeletionOperationAuthority\\(\\)").findAll(source).count())
        val operationBinding = MainActivityRefactorSource.composition(source, "accountDeletionOperationAuthority")
        assertEquals(1, Regex("\\bAccountDeletionOperationAuthority\\(\\)").findAll(operationBinding).count())
        assertTrue(operationBinding.contains("also { accountDeletionOperationAuthority = it }"))
        val preparation = sourceFile("session/deletion/runtime/AccountDeletionPreparationController.kt").readText()
        val network = sourceFile("session/deletion/runtime/AccountDeletionNetworkController.kt").readText()
        assertEquals(2, Regex("val generation = operation.beginPreparation\\(\\)").findAll(preparation).count())
        assertEquals(1, Regex("val generation = operation.admitCall\\(call\\)").findAll(network).count())
        for ((entry, method) in listOf("startAccountDeletionConfirmationPreparation" to "startConfirmationPreparation",
            "startAccountDeletionPreparation" to "startPreparation")) {
            val adapter = source.substringAfter("private fun $entry(").substringBefore("\n    private ")
            assertTrue(entry, adapter.contains("accountDeletionPreparationController.$method("))
        }
        val networkAdapter = source.substringAfter("private fun startAccountDeletionCallAllowed(").substringBefore("\n    private ")
        assertTrue(networkAdapter.contains("accountDeletionNetworkController.startCallAllowed(call, purpose)"))
        for (property in listOf("accountDeletionPreparationController", "accountDeletionNetworkController")) {
            val assembly = MainActivityRefactorSource.composition(source, property)
            assertTrue(property, assembly.contains("operation = accountDeletionOperationAuthority"))
        }
        assertEquals(1, Regex("accountDeletionOperationAuthority.destroy\\(\\)").findAll(source).count())
        val destroy = source.substringAfter("override fun onDestroy()")
        val consentCancellation = destroy.indexOf("integratedConsentController.cancelIntegratedConsentControlCall()")
        assertTrue(consentCancellation >= 0 && consentCancellation < destroy.indexOf("accountDeletionOperationAuthority.destroy()"))
        val consent = sourceFile("session/consent/IntegratedConsentController.kt").readText()
            .substringAfter("fun cancelIntegratedConsentControlCall()").substringBefore("private fun ")
        assertTrue(consent.contains("synchronized(integratedConsentLock)"))
        assertTrue(consent.indexOf("integratedConsentRequestGeneration += 1L") < consent.indexOf("integratedConsentCall?.cancel()"))
        assertTrue(consent.indexOf("integratedConsentCall?.cancel()") < consent.indexOf("integratedConsentCall = null"))
        val detach = destroy.indexOf("privacyStartupResources.releaseOnUiThread(gatewaySessionController)")
        assertTrue(detach >= 0 && destroy.indexOf("accountDeletionOperationAuthority.destroy()") < detach)
        val relative = "src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/GatewaySessionController.kt"
        val gateway = (File(relative).takeIf(File::isFile) ?: File("apps/android/app/$relative")).readText()
        assertTrue(gateway.contains("fun detach() = GatewaySessionProcessCoordinator.detach(owner)"))
        for (field in listOf("accountDeletionLock", "accountDeletionRequestGeneration", "accountDeletionCall")) {
            assertFalse(field, Regex("\\b$field\\b").containsMatchIn(source))
        }
    }

    private fun sourceFile(path: String): File {
        val relative = "src/main/java/kr/co/hanium/dreamup/walksafe/$path"
        return File(relative).takeIf(File::isFile) ?: File("apps/android/app/$relative")
    }

    private fun owner(main: MainActivity) = MainActivity::class.java.getDeclaredField("accountDeletionOperationAuthority")
        .apply { isAccessible = true }.get(main) as AccountDeletionOperationAuthority
    private fun call(main: MainActivity, name: String, result: Class<*>, arguments: Array<Class<*>>, vararg values: Any?): Any? =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(result, arguments.toList()))
            .bindTo(main).invokeWithArguments(values.toList())

    @Test
    fun acquiredGatewayRetiresItsOriginalObserverWithoutClearingSession() {
        val source = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val create = MainActivityRefactorSource.member(source, "onCreate")
        assertTrue(create.contains("{ gatewaySessionController }, { try { it.detach() } finally { it.unregisterGatewayCapacityNetworkObserver() } }"))
        val resources = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/PrivacyStartupResources.kt").readText()
        val release = MainActivityRefactorSource.member(resources, "release")
        assertTrue(release.contains("it.resource === resource"))
        assertTrue(release.indexOf("lane.entries.removeAt(index)") < release.indexOf("entry?.release?.invoke()"))
        assertFalse(release.contains("clearGatewaySession"))
    }

}
