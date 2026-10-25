package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.device.RuntimeArSessionCleanupOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main close-action tests plus source wiring; ARCore Session/native close is not executed. */
class MainActivityArStartCandidateCleanupRegressionTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun failedCandidateCloseRetainsItsExactActionAndFirstCauseWithoutThrowing() {
        val main = isolatedMain()
        val failure = IllegalStateException("candidate close failed")
        var closeCalls = 0
        val closeAction: () -> Unit = {
            closeCalls += 1
            throw failure
        }

        val result = runCatching { main.closeRuntimeArStartCandidate(closeAction) }

        assertEquals(1, closeCalls)
        assertOnlyInjectedFailureOrBooleanResult(result, failure)
        assertNull("A failed candidate cleanup must let its caller continue", result.exceptionOrNull())
        assertEquals(false, result.getOrNull())
        assertTrue(main.runtimeArSessionCleanupPending())
        assertSame(closeAction, retainedAction(main))
        assertSame(failure, ownership(main).failureFor(ownerToken(main)))
        assertSame(failure, field(main, "lastRuntimeArSessionCloseFailure"))
    }

    @Test
    fun catchStyleCleanupCannotReplayTheSameUncertainCandidateOrReplaceItsFirstCause() {
        val main = isolatedMain()
        val firstFailure = IllegalStateException("first candidate close failed")
        val replayFailure = IllegalStateException("candidate close was replayed")
        var closeCalls = 0
        val closeAction: () -> Unit = {
            closeCalls += 1
            throw if (closeCalls == 1) firstFailure else replayFailure
        }
        val first = runCatching { main.closeRuntimeArStartCandidate(closeAction) }
        assertEquals(1, closeCalls)
        // The old branch propagates this exact native cause into its surrounding catch.
        assertOnlyInjectedFailureOrBooleanResult(first, firstFailure)

        val caughtCleanup = runCatching { main.closeRuntimeArStartCandidate(closeAction) }

        assertEquals("The surrounding catch must not call an uncertain native close twice", 1, closeCalls)
        assertNull(caughtCleanup.exceptionOrNull())
        assertEquals(false, caughtCleanup.getOrNull())
        assertTrue(main.runtimeArSessionCleanupPending())
        assertSame(closeAction, retainedAction(main))
        assertSame(firstFailure, ownership(main).failureFor(ownerToken(main)))
        assertSame(firstFailure, field(main, "lastRuntimeArSessionCloseFailure"))
    }

    @Test
    fun acknowledgedCandidateCloseExecutesOnceAndAllowsAnIndependentClose() {
        val main = isolatedMain()
        var firstCloseCalls = 0
        var independentCloseCalls = 0

        assertTrue(main.closeRuntimeArStartCandidate { firstCloseCalls += 1 })

        assertEquals(1, firstCloseCalls)
        assertFalse(main.runtimeArSessionCleanupPending())
        assertNull(retainedAction(main))
        assertNull(field(main, "lastRuntimeArSessionCloseFailure"))
        assertTrue(main.closeRuntimeArStartCandidate { independentCloseCalls += 1 })
        assertEquals(1, firstCloseCalls)
        assertEquals(1, independentCloseCalls)
        assertFalse(main.runtimeArSessionCleanupPending())
    }

    @Test
    fun sourceOnlyEveryUnpublishedCandidateCloseUsesTheActualMainCloseHelper() {
        val transactions = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeArLifecycleTransactions.kt").readText()
        val start = MainActivityRefactorSource.member(transactions, "start")
        val lifecycle = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeCameraLifecycleCoordinator.kt").readText()
        assertTrue(MainActivityRefactorSource.member(lifecycle, "continueDepthSessionStart").contains("transactions.start("))
        assertTrue(MainActivityRefactorSource.property(lifecycle, "transactions").contains("RuntimeArLifecycleTransactions(metricStateLock, arOwner"))
        val mainBinding = MainActivityRefactorSource.composition(source, "runtimeCameraLifecycle")
        assertTrue("The lifecycle must receive Main's same metric/frame locks and AR owner",
            mainBinding.contains("RuntimeCameraLifecycleCoordinator(runtimeMetricStateLock, frameStateLock, arSessionOwner,"))
        assertTrue(MainActivityRefactorSource.property(source, "arSessionOwner").contains("stateLock = runtimeMetricStateLock"))
        val detectorRejected = start.substringAfter("if (!admission.detectorAvailable()) {").substringBefore("if (!admission.depthSupported) {")
        val depthRejected = start.substringAfter("if (!admission.depthSupported) {").substringBefore("val runtimeStartedAtMs")
        listOf(detectorRejected, depthRejected).forEach { branch ->
            assertTrue(branch.contains("arOwner.closeCandidate { platform.closeSession(newSession) }"))
        }
        listOf("catch (_: UnavailableDeviceNotCompatibleException)", "catch (error: Exception)").forEach { marker ->
            val caughtCleanup = braceBlock(start, start.indexOf(marker))
            assertTrue("The catch passes its original candidate to the same actual close owner",
                caughtCleanup.contains("candidateSession?.let { candidate -> arOwner.closeCandidate { platform.closeSession(candidate) } }"))
            assertTrue(caughtCleanup.contains("runCatching"))
        }
        assertEquals(4, Regex("arOwner\\.closeCandidate\\s*\\{").findAll(start).count())
        assertTrue(MainActivityRefactorSource.property(source, "arSessionOwner").contains("closeSession = { it.close() }"))
        val platform = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/AndroidRuntimeArPlatform.kt").readText()
        assertTrue(MainActivityRefactorSource.member(platform, "closeSession").contains("session.close()"))
    }

    @Test
    fun sourceOnlyPublishingTheSessionTransfersCandidateOwnershipInsideTheSameLock() {
        val transactions = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeArLifecycleTransactions.kt").readText()
        val start = MainActivityRefactorSource.member(transactions, "start")
        val mainBinding = MainActivityRefactorSource.composition(source, "runtimeCameraLifecycle")
        assertTrue("The lifecycle must receive Main's same metric/frame locks and AR owner",
            mainBinding.contains("RuntimeCameraLifecycleCoordinator(runtimeMetricStateLock, frameStateLock, arSessionOwner,"))
        assertTrue(MainActivityRefactorSource.property(source, "arSessionOwner").contains("stateLock = runtimeMetricStateLock"))
        val resumed = start.indexOf("val textureBound = platform.resumeBeforePublish(newSession)")
        assertTrue(resumed >= 0)
        val lockStart = start.indexOf("synchronized(metricStateLock)", resumed)
        assertTrue(lockStart > resumed)
        val publish = braceBlock(start, lockStart)
        val activePublish = publish.indexOf("arOwner.publish(newSession, provider, runtimePurpose)")
        val candidateHandoff = publish.indexOf("candidateSession = null")
        assertTrue("Runtime receives the same candidate", activePublish >= 0)
        assertTrue("Candidate ownership transfers inside the publication lock", candidateHandoff > activePublish)
        val arSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/ar/RuntimeArSessionOwner.kt").readText()
        val actualPublish = MainActivityRefactorSource.member(arSource, "publish")
        assertTrue(actualPublish.indexOf("session = newSession") < actualPublish.indexOf("commit(publishedGeneration)"))
        val rendererFinally = start.indexOf("renderer.resumeAfterClose(resumeRenderer)", lockStart)
        assertTrue(rendererFinally > lockStart + publish.length)
    }

    private fun assertOnlyInjectedFailureOrBooleanResult(result: Result<Boolean>, failure: RuntimeException) {
        val observed = result.exceptionOrNull()
        if (observed != null) assertSame("Only the original injected native close failure is expected", failure, observed)
        else assertEquals(false, result.getOrNull())
    }

    private fun isolatedMain(): MainActivity = MainActivity().also { main ->
        MainActivityControllerTestFixture.field("runtimeArSessionCleanupOwnership")
            .set(main, RuntimeArSessionCleanupOwnership())
    }

    private fun ownership(main: MainActivity): RuntimeArSessionCleanupOwnership =
        field(main, "runtimeArSessionCleanupOwnership") as RuntimeArSessionCleanupOwnership

    private fun retainedAction(main: MainActivity): Any? =
        RuntimeArSessionCleanupOwnership::class.java.getDeclaredField("retainedAction").run {
            isAccessible = true
            get(ownership(main))
        }

    private fun ownerToken(main: MainActivity): Any = field(main, "runtimeArSessionCleanupOwnerToken")!!

    private fun field(main: MainActivity, name: String): Any? =
        MainActivityControllerTestFixture.field(name).get(main)

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        return source.substring(start, start + braceBlock(source, start).length)
    }

    private fun braceBlock(text: String, start: Int): String {
        check(start >= 0) { "Missing source marker" }
        val opening = text.indexOf('{', start)
        check(opening >= 0) { "Missing source block" }
        var depth = 0
        for (index in opening until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("Unclosed source block")
    }
}
