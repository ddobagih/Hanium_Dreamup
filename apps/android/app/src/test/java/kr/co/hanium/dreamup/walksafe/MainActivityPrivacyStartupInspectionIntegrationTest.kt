package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.startup.PrivacyStartupFixture
import kr.co.hanium.dreamup.walksafe.startup.PrivacyStartupInspectionController
import kr.co.hanium.dreamup.walksafe.startup.PrivacyStartupInspectionResult
import org.junit.Assert.*
import org.junit.Test

class MainActivityPrivacyStartupInspectionIntegrationTest {
    @Test fun actualMainDelegatePreservesForegroundReadinessAndTheControllerStateOwner() {
        val f = PrivacyStartupFixture()
        val main = MainActivity()
        val field = MainActivity::class.java.getDeclaredField("privacyStartupInspectionController").apply { isAccessible = true }
        assertTrue(field.get(main) is PrivacyStartupInspectionController)
        field.set(main, f.controller)
        val prepare: () -> PrivacyStartupInspectionResult? = { null }
        val inspect: (Boolean?) -> PrivacyStartupInspectionResult = { PrivacyStartupInspectionResult.READY }
        val ready: () -> Unit = { f.events += "ready" }
        method(main, "startPrivacyStartupInspection", Void.TYPE,
            kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function1::class.java,
            kotlin.jvm.functions.Function0::class.java).invokeWithArguments(prepare, inspect, ready)
        f.foreground = false
        f.worker.runNext(); f.runUi()
        assertFalse(f.controller.isComplete)
        f.foreground = true
        method(main, "completePrivacyStartupReadyIfForeground", Void.TYPE).invokeWithArguments()
        assertEquals(listOf("ready", "voice", "runtime"), f.events)
        assertTrue(f.controller.isComplete)
        assertEquals(true, method(main, "getPrivacyStartupInspectionComplete", java.lang.Boolean.TYPE).invokeWithArguments())
    }

    @Test fun actualMainCloseDelegateUsesTheCoalescedOwnerInsteadOfDuplicatingState() {
        val f = PrivacyStartupFixture()
        val main = MainActivity()
        MainActivity::class.java.getDeclaredField("privacyStartupInspectionController").apply { isAccessible = true }.set(main, f.controller)
        val close = method(main, "schedulePrivacyStartupResourcesClose", Void.TYPE)
        repeat(3) { close.invokeWithArguments() }
        assertEquals(1, f.cleanup.size)
        f.cleanup.runNext()
        assertEquals(listOf("speech-drain", "legacy-close"), f.events)
    }

    @Test fun mainAdapterCapturesBothOriginalParticipantsBeforeWaitingAndNeverRecreatesThem() {
        val source = mainSource()
        val capture = source.substringAfter("private fun capturePrivacyStartupResetPreparation(): PreparedPrivacyStartupReset")
            .substringBefore("private fun drainPrivacyStartupSpeechOutputOnWorker()")
        assertTrue(capture.contains("PreparedPrivacyStartupReset.capture("))
        for (binding in listOf("prepareSpeech = ::prepareSpeechOutputAccountPurgeOnWorker",
            "prepareRoute = ::prepareRouteSnapshotAccountPurge", "awaitSpeech = ::awaitPreparedSpeechOutputAccountPurge",
            "awaitRoute = ::awaitRouteSnapshotAccountPurgeOnWorker", "speechIsCurrent = ::speechOutputResetPreparationIsCurrent",
            "routeIsCurrent = ::routeSnapshotResetPreparationIsCurrent")) {
            assertEquals(binding, 1, Regex(Regex.escape(binding)).findAll(capture).count())
        }
        val factory = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/PrivacyStartupInspectionController.kt")
            .readText().substringAfter("fun <S, R> capture(").substringBefore("internal interface PrivacyStartupResetPreparation")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(factory, "val speech = prepareSpeech()",
            "val route = runCatching(prepareRoute).getOrNull()", "awaitSpeech(speech)", "awaitRoute(route)",
            "return speechPurgedAndDrained && routePurged"))
        assertTrue(factory.contains("speechIsCurrent(speech) && route?.let(routeIsCurrent) == true"))
        assertFalse(capture.contains("synchronized("))
    }

    @Test fun mainDestroyFencesGenerationBeforeLeaseDetachAndThenClearsTheReadyAction() {
        val source = mainSource()
        val destroy = source.substringAfter("override fun onDestroy()")
        val fence = destroy.indexOf("privacyStartupInspectionController.destroy()")
        val readyClear = destroy.indexOf("privacyStartupInspectionController.clearPendingReady()")
        val earlyBranch = destroy.indexOf("if (!privacyStartupInspectionComplete)")
        val detach = destroy.indexOf("accountDeletionProcessCoordinator.detach(accountDeletionActivityLease)")
        val close = destroy.indexOf("schedulePrivacyStartupResourcesClose()")
        assertTrue(fence >= 0 && readyClear > fence && earlyBranch > readyClear && detach > earlyBranch && close > detach)
        assertFalse(source.contains("private val privacyStartupInspectionLock"))
        assertFalse(source.contains("private val privacyStartupResourceLock"))
        assertFalse(source.contains("var accountDeletionStartupResetHandoffPending ="))
        val reset = MainActivityRefactorSource.composition(source, "accountDeletionInstallationResetController")
        assertTrue(reset.contains("PrivacyStartupInspectionController.accountDeletionStartupResetHandoffPending"))
    }

    private fun method(main: MainActivity, name: String, result: Class<*>, vararg types: Class<*>) =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(result, types.toList())).bindTo(main)

    private fun mainSource() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
}
