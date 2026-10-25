package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.MainActivity
import org.junit.Assert.*
import org.junit.Test

class MainActivityDeletionCleanupIntegrationTest {
    @Test fun actualLifetimeFactoryKeepsOriginalPureTokenCreationAndOneReadonlyMainFactSet() {
        val main = MainActivity()
        val queue = field(main, "legacyPendingReportQueuePurgeController") as LegacyPendingReportQueuePurgeController
        assertEquals(queue.activityToken, call(main, "getReportCleanupActivityToken", String::class.java))
        assertEquals(0L, call(main, "getReportCleanupGeneration", Long::class.javaPrimitiveType!!))
        assertEquals(false, call(main, "getReportCleanupDestroyed", Boolean::class.javaPrimitiveType!!))
        val names = MainActivity::class.java.declaredFields.map { it.name }
        for (name in listOf("legacyPendingReportQueuePurgeLock", "legacyPendingReportQueuePurgeInFlight",
            "legacyPendingReportQueuePurgeSucceeded", "legacyPendingReportQueuePurgeWaiters",
            "reportCleanupActivityToken", "reportCleanupGeneration", "reportCleanupDestroyed")) assertFalse(name, name in names)
        assertFalse((field(main, "reportCleanupCallbackHandler\$delegate") as Lazy<*>).isInitialized())
    }

    @Test fun actualPreparationAndProgressCallbacksReadTheSameRetiredCleanupLifetime() {
        val main = MainActivity()
        val queue = field(main, "legacyPendingReportQueuePurgeController") as LegacyPendingReportQueuePurgeController
        val preparation = field(main, "accountDeletionPreparationController")!!
        val progress = call(main, "getAccountDeletionProgressController", AccountDeletionProgressController::class.java)!!
        val prepCallbacks = field(preparation, "callbacks") as AccountDeletionCleanupCallbacks
        val progressCallbacks = field(progress, "callbacks") as AccountDeletionProgressCallbacks
        assertEquals(queue.activityToken, prepCallbacks.activityToken)
        assertEquals(queue.activityToken, progressCallbacks.activityToken)
        assertFalse(prepCallbacks.destroyed || progressCallbacks.destroyed)
        queue.destroy()
        assertTrue(prepCallbacks.destroyed && progressCallbacks.destroyed)
        assertEquals(1L, call(main, "getReportCleanupGeneration", Long::class.javaPrimitiveType!!))
        assertFalse(progressCallbacks.startupInspectionDestroyed)
    }

    @Test fun actualPurgeFactorySharesTheSameQueueAndReadsTheRealSensitiveProviderLate() = DeletionRecoveryFixture().use { f ->
        val main = MainActivity()
        val purger = call(main, "getAccountDeletionLocalDataPurger", AccountDeletionLocalDataPurger::class.java)!!
        assertSame(field(main, "legacyPendingReportQueuePurgeController"), field(purger, "legacyQueue"))
        val provider = field(purger, "sensitivePreferences") as Function0<*>
        set(main, "sensitivePrefs", f.preferences)
        assertSame(f.preferences, provider.invoke())
        assertFalse((field(main, "reportCleanupCallbackHandler\$delegate") as Lazy<*>).isInitialized())
    }

    @Test fun actualRuntimeReportFenceAdvancesTheOriginalTwoUploadGenerationsOnly() {
        val main = MainActivity()
        val fence = call(main, "getAccountDeletionRuntimeFenceController", AccountDeletionRuntimeFenceController::class.java)!!
        val reports = field(fence, "reports") as AccountDeletionReportFenceActions
        val first = MainActivity::class.java.getDeclaredField("reportUploadSafetyGeneration").apply { isAccessible = true }
        val automatic = MainActivity::class.java.getDeclaredField("automaticReportUploadSafetyGeneration").apply { isAccessible = true }
        val before = first.getLong(main)
        val beforeAutomatic = automatic.getLong(main)
        reports.advanceUploadAuthority()
        assertEquals(before + 1L, first.getLong(main))
        assertEquals(beforeAutomatic + 1L, automatic.getLong(main))
        assertFalse((field(main, "reportCleanupCallbackHandler\$delegate") as Lazy<*>).isInitialized())
    }

    @Test fun actualMainRetirementAndPurgeBindingsPreserveOriginalParticipantsAndSerialProfileBarrier() {
        val source = File(System.getProperty("walksafe.deletion.cleanup.main.source")
            ?: "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val destroy = source.substringAfter("override fun onDestroy()").substringBefore("\n    private ")
        assertEquals(1, Regex("legacyPendingReportQueuePurgeController.destroy\\(\\)").findAll(destroy).count())
        assertTrue(destroy.indexOf("reportCleanupExecutor.shutdown()") < destroy.indexOf("legacyPendingReportQueuePurgeController.destroy()"))
        assertTrue(destroy.indexOf("legacyPendingReportQueuePurgeController.destroy()") < destroy.indexOf("reportCleanupExecutor.shutdownNow()"))
        assertTrue(destroy.indexOf("reportCleanupExecutor.shutdownNow()") < destroy.indexOf("accountDeletionOperationAuthority.destroy()"))
        val purgeAssembly = MainActivityRefactorSource.composition(source, "accountDeletionLocalDataPurger")
        assertTrue(purgeAssembly.contains("legacyQueue = legacyPendingReportQueuePurgeController"))
        assertTrue(purgeAssembly.contains("participants = participants({ speechOutputStorageCoordinator }, routeArchive)"))
        val purger = MainActivityRefactorSource.factory(source, "createPrivacyLocalPurge")
        assertTrue(purger.contains("speech().awaitAccountPurgeOnWorker()"))
        assertTrue(purger.contains("::rawCollectionRuntimeCoordinator.isInitialized"))
        assertTrue(purger.contains("speech().collectionOrNull()?.provider?.purgeAll()"))
        assertTrue(purger.contains("SerialPedestrianProfileWriter.shared.clearPendingAndRun"))
        assertTrue(purger.contains("pedestrianProfileStore ?: runCatching { AndroidPedestrianProfileStore(stepLengthPrefs) }"))
        MainActivityRefactorSource.composition(source, "accountDeletionRuntimeFenceController")
        val fence = MainActivityRefactorSource.factory(source, "createPrivacyRuntimeFence")
        assertTrue(fence.contains("synchronized(reportUploadSafetyLock)"))
        assertTrue(fence.contains("automaticReportUploadSafetyGeneration += 1L"))
        assertTrue(fence.contains("persistInterruptionMarker = false"))
        assertTrue(fence.contains("actorReportAttemptRepository.clearForAccountDeletion()"))
    }

    private fun field(receiver: Any, name: String): Any? = receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(receiver)
    private fun set(receiver: Any, name: String, value: Any?) = receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(receiver, value)
    private fun call(main: MainActivity, name: String, result: Class<*>): Any? = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
        .findVirtual(MainActivity::class.java, name, MethodType.methodType(result)).bindTo(main).invokeWithArguments()
}
