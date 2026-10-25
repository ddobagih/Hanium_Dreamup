package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.device.RuntimeArSessionCleanupOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main cleanup callbacks and process ownership; Activity/ARCore/native close is not run. */
class MainActivityArInvalidationCleanupRegressionTest {
    @Test
    fun nativeCloseFailureKeepsTheOriginalOwnerAndLetsTheIndependentTailRun() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val failure = IllegalStateException("original invalidation native close failed")
        var closeCalls = 0
        var tailCalls = 0
        val originalClose: () -> Unit = { closeCalls += 1; throw failure }
        assertTrue(main.retainRuntimeArSessionCloseOwner(originalClose))

        val result = runCatching {
            main.completeRuntimeArInvalidationCleanup(originalClose, {}, { tailCalls += 1 })
        }

        assertEquals("Independent cleanup must run after the native close failure", 1, tailCalls)
        assertNull("The invalidation caller must be able to continue its own cleanup", result.exceptionOrNull())
        assertEquals(false, result.getOrNull())
        assertEquals(1, closeCalls)
        assertSame(originalClose, retainedAction(ownership))
        assertSame(failure, closeFailure(main))
        assertTrue(cameraStartBlocked(isolatedMain(ownership)))
    }

    @Test
    fun providerOrCancellationOrRendererFailureKeepsTheTargetWithoutCallingNativeClose() {
        listOf("provider", "evaluator cancellation", "renderer").forEach { stage ->
            val ownership = RuntimeArSessionCleanupOwnership()
            val main = isolatedMain(ownership)
            val failure = IllegalStateException("$stage preparation failed")
            var closeCalls = 0
            var tailCalls = 0
            val originalClose: () -> Unit = { closeCalls += 1 }
            assertTrue(main.retainRuntimeArSessionCloseOwner(originalClose))

            val result = runCatching {
                main.completeRuntimeArInvalidationCleanup(originalClose, { throw failure }, { tailCalls += 1 })
            }

            assertEquals("The $stage failure must not prevent independent cleanup", 1, tailCalls)
            assertNull(result.exceptionOrNull())
            assertEquals(false, result.getOrNull())
            assertEquals(0, closeCalls)
            assertSame(originalClose, retainedAction(ownership))
            assertSame(failure, closeFailure(main))
            assertFalse(main.closeRetainedRuntimeArSession())
            assertEquals("An uncertain preparation must not be automatically replayed", 0, closeCalls)
        }
    }

    @Test
    fun repeatedInvalidationDoesNotReplayAnUncertainNativeCloseOrReplaceItsFirstCause() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val firstFailure = IllegalStateException("first native close failure")
        val laterFailure = IllegalStateException("later renderer failure")
        var closeCalls = 0
        var laterPreparationCalls = 0
        var tailCalls = 0
        val originalClose: () -> Unit = { closeCalls += 1; throw firstFailure }
        assertTrue(main.retainRuntimeArSessionCloseOwner(originalClose))
        runCatching { main.completeRuntimeArInvalidationCleanup(originalClose, {}, { tailCalls += 1 }) }

        val result = runCatching {
            main.completeRuntimeArInvalidationCleanup(
                originalClose,
                { laterPreparationCalls += 1; throw laterFailure },
                { tailCalls += 1 },
            )
        }

        assertEquals("The original native close can be attempted only once", 1, closeCalls)
        assertEquals("The already attempted close preparation cannot be replayed", 0, laterPreparationCalls)
        assertEquals(2, tailCalls)
        assertNull(result.exceptionOrNull())
        assertEquals(false, result.getOrNull())
        assertSame(firstFailure, closeFailure(main))
        assertSame(originalClose, retainedAction(ownership))
    }

    @Test
    fun noActiveSessionDoesNotAcknowledgeAPreviousUncertainClose() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val failure = IllegalStateException("previous invalidation close uncertain")
        var closeCalls = 0
        var tailCalls = 0
        val originalClose: () -> Unit = { closeCalls += 1; throw failure }
        assertFalse(main.closeRuntimeArSession(originalClose))

        val result = main.completeRuntimeArInvalidationCleanup(null, {}, { tailCalls += 1 })

        assertFalse("Clearing active references is not a close acknowledgement", result)
        assertEquals(1, closeCalls)
        assertEquals(1, tailCalls)
        assertSame(failure, closeFailure(main))
        assertSame(originalClose, retainedAction(ownership))
        assertTrue(cameraStartBlocked(isolatedMain(ownership)))
    }

    @Test
    fun acknowledgedCloseReleasesTheProcessOwnerAndLeavesTheNormalTailIntact() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val sequence = mutableListOf<String>()
        val originalClose: () -> Unit = { sequence += "close" }
        assertTrue(main.retainRuntimeArSessionCloseOwner(originalClose))

        assertTrue(main.completeRuntimeArInvalidationCleanup(
            originalClose,
            { sequence += "prepare" },
            { sequence += "tail" },
        ))

        assertEquals(listOf("prepare", "close", "tail"), sequence)
        assertFalse(main.runtimeArSessionCleanupPending())
        assertNull(retainedAction(ownership))
        assertNull(closeFailure(main))
        assertFalse(cameraStartBlocked(isolatedMain(ownership)))
    }

    @Test
    fun tailFailureDoesNotHideTheOriginalNativeFailure() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val nativeFailure = IllegalStateException("native failure")
        val tailFailure = IllegalStateException("tail failure")
        val originalClose: () -> Unit = { throw nativeFailure }
        assertTrue(main.retainRuntimeArSessionCloseOwner(originalClose))
        var tailCalls = 0

        val result = runCatching {
            main.completeRuntimeArInvalidationCleanup(originalClose, {}, { tailCalls += 1; throw tailFailure })
        }

        assertEquals(1, tailCalls)
        assertNull(result.exceptionOrNull())
        assertEquals(false, result.getOrNull())
        assertSame(nativeFailure, closeFailure(main))
        assertTrue(nativeFailure.suppressed.any { it === tailFailure })
        assertSame(originalClose, retainedAction(ownership))
    }

    @Test
    fun noSessionAndNoPendingOwnerKeepsTheNormalCleanupOrder() {
        val main = isolatedMain(RuntimeArSessionCleanupOwnership())
        val sequence = mutableListOf<String>()

        assertTrue(main.completeRuntimeArInvalidationCleanup(null, { sequence += "prepare" }, { sequence += "tail" }))

        assertEquals(listOf("prepare", "tail"), sequence)
        assertFalse(main.runtimeArSessionCleanupPending())
        assertNull(closeFailure(main))
    }

    @Test
    fun independentPreparationStagesRunAfterFailureAndPreserveTheFirstFailure() {
        val main = isolatedMain(RuntimeArSessionCleanupOwnership())
        val firstFailure = IllegalStateException("provider failed")
        val secondFailure = IllegalStateException("sensor failed")
        val sequence = mutableListOf<String>()

        val result = runCatching {
            main.runRuntimeArInvalidationCleanupStages(
                { sequence += "provider"; throw firstFailure },
                { sequence += "cancel" },
                { sequence += "sensor"; throw secondFailure },
                { sequence += "feedback" },
                { sequence += "renderer" },
            )
        }

        assertEquals(listOf("provider", "cancel", "sensor", "feedback", "renderer"), sequence)
        assertSame(firstFailure, result.exceptionOrNull())
        assertEquals(listOf(secondFailure), firstFailure.suppressed.toList())
    }

    @Test
    fun preparationFailureAndIndependentTailUseTheSameMainInvalidationCleanup() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val failure = IllegalStateException("provider preparation failed")
        val sequence = mutableListOf<String>()
        val originalClose: () -> Unit = { sequence += "native close" }
        assertTrue(main.retainRuntimeArSessionCloseOwner(originalClose))

        assertFalse(main.completeRuntimeArInvalidationCleanup(
            originalClose,
            {
                main.runRuntimeArInvalidationCleanupStages(
                    { sequence += "provider"; throw failure },
                    { sequence += "cancel" },
                    { sequence += "renderer" },
                )
            },
            {
                main.runRuntimeArInvalidationCleanupStages(
                    { sequence += "sensor tail" },
                    { sequence += "feedback tail" },
                    { sequence += "detector tail" },
                )
            },
        ))

        assertEquals(listOf("provider", "cancel", "renderer", "sensor tail", "feedback tail", "detector tail"), sequence)
        assertSame(failure, closeFailure(main))
        assertSame(originalClose, retainedAction(ownership))
        assertTrue(main.runtimeArSessionCleanupPending())
    }

    @Test
    fun destroyVoiceCancellationFailureStillReachesIndependentResourcesAndTheCallerSuperTail() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val firstFailure = IllegalStateException("invalidation voice cancellation failed")
        val sequence = mutableListOf<String>()
        var nativeCloseCalls = 0
        var superCalls = 0
        val originalClose: () -> Unit = { nativeCloseCalls += 1 }
        assertTrue(main.retainRuntimeArSessionCloseOwner(originalClose))
        assertFalse(main.completeRuntimeArInvalidationCleanup(originalClose, { throw firstFailure }, {}))

        val result = runCatching {
            val completed = main.completeDestroyedRuntimeCleanup(
                { sequence += "voice"; throw firstFailure },
                { sequence += "speech recognizer" },
                { sequence += "startup probe" },
                { sequence += "resource probe" },
                { sequence += "detector" },
            )
            superCalls += 1
            completed
        }

        assertEquals(listOf("voice", "speech recognizer", "startup probe", "resource probe", "detector"), sequence)
        assertEquals("The actual onDestroy caller must continue after its cleanup helper", 1, superCalls)
        assertNull(result.exceptionOrNull())
        assertEquals(false, result.getOrNull())
        assertEquals(0, nativeCloseCalls)
        assertSame(firstFailure, closeFailure(main))
        assertSame(originalClose, retainedAction(ownership))
        assertTrue(cameraStartBlocked(isolatedMain(ownership)))
    }

    @Test
    fun destroyScreenFailureKeepsItsCauseAndDoesNotSkipSensorFeedbackOrDetectorTail() {
        val main = isolatedMain(RuntimeArSessionCleanupOwnership())
        val failure = IllegalStateException("screen policy cleanup failed")
        val sequence = mutableListOf<String>()

        val result = runCatching {
            main.completeDestroyedRuntimeCleanup(
                { sequence += "screen"; throw failure },
                { sequence += "sensor" },
                { sequence += "feedback" },
                { sequence += "detector" },
            )
        }

        assertEquals(listOf("screen", "sensor", "feedback", "detector"), sequence)
        assertNull(result.exceptionOrNull())
        assertEquals(false, result.getOrNull())
        assertSame(failure, closeFailure(main))
    }

    @Test
    fun destroyCannotAcknowledgeAnUncertainCloseEvenWhenItsIndependentTailSucceeds() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val failure = IllegalStateException("native close remains uncertain at destroy")
        var nativeCloseCalls = 0
        var detectorCalls = 0
        val originalClose: () -> Unit = { nativeCloseCalls += 1; throw failure }
        assertFalse(main.closeRuntimeArSession(originalClose))

        val completed = main.completeDestroyedRuntimeCleanup(
            { main.closeRetainedRuntimeArSession(); Unit },
            { detectorCalls += 1 },
        )

        assertFalse("The destroy tail is not a native close acknowledgement", completed)
        assertEquals(1, nativeCloseCalls)
        assertEquals(1, detectorCalls)
        assertSame(failure, closeFailure(main))
        assertSame(originalClose, retainedAction(ownership))
    }

    @Test
    fun destroyChecksNativeOwnershipCreatedByItsOwnCleanupActionsBeforeAcknowledging() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val failure = IllegalStateException("first native close fails inside destroy")
        var nativeCloseCalls = 0
        var detectorCalls = 0
        val originalClose: () -> Unit = { nativeCloseCalls += 1; throw failure }
        assertFalse(main.runtimeArSessionCleanupPending())

        val completed = main.completeDestroyedRuntimeCleanup(
            { assertFalse(main.closeRuntimeArSession(originalClose)) },
            { detectorCalls += 1 },
        )

        assertFalse("Completion must inspect the native owner after actual destroy actions", completed)
        assertEquals(1, nativeCloseCalls)
        assertEquals(1, detectorCalls)
        assertSame(failure, closeFailure(main))
        assertSame(originalClose, retainedAction(ownership))
        assertTrue(cameraStartBlocked(isolatedMain(ownership)))
    }

    @Test
    fun normalDestroyCleanupKeepsOrderAndItsActualSourceCallerReachesSuper() {
        val main = isolatedMain(RuntimeArSessionCleanupOwnership())
        val sequence = mutableListOf<String>()
        assertTrue(main.completeDestroyedRuntimeCleanup(
            { sequence += "invalidate" },
            { sequence += "screen" },
            { sequence += "sensor" },
            { sequence += "feedback" },
            { sequence += "voice" },
            { sequence += "detector" },
        ))
        assertEquals(listOf("invalidate", "screen", "sensor", "feedback", "voice", "detector"), sequence)
        assertNull(closeFailure(main))

        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val destroy = source.substringAfter("override fun onDestroy()").substringBefore("internal fun completeDestroyedRuntimeCleanup")
        val cleanup = destroy.indexOf("completeDestroyedRuntimeCleanup(")
        assertTrue("SOURCE_ONLY: actual onDestroy uses the same cleanup helper", cleanup >= 0)
        assertTrue(destroy.indexOf("runtimeMetricInvalidation.invalidate(\"app_destroyed\")", cleanup) > cleanup)
        val voice = destroy.indexOf("cancelVoiceCommandRecognition()", cleanup)
        val detector = destroy.indexOf("closeDetectorAsync()", cleanup)
        val superTail = destroy.lastIndexOf("super.onDestroy()")
        assertTrue("SOURCE_ONLY: voice cleanup, detector and superclass keep their original order", voice > cleanup && detector > voice && superTail > detector)
    }

    @Test
    fun destroyFrontFailureRetainsItsCloseTargetAndStillReachesTheIndependentTail() {
        listOf("navigation cancellation", "render mode", "surface pause").forEach { stage ->
            val ownership = RuntimeArSessionCleanupOwnership()
            val main = isolatedMain(ownership)
            val failure = IllegalStateException("$stage failed before invalidation")
            var nativeCloseCalls = 0
            var detectorCalls = 0
            var superCalls = 0
            var nativeAcknowledged: Boolean? = null
            val originalClose: () -> Unit = { nativeCloseCalls += 1 }
            setField(main, "runtimeMetricOutputAllowed", true)

            val result = runCatching {
                val completed = main.completeDestroyedRuntimePreparation(
                    closeAction = originalClose,
                    preparation = { throw failure },
                    cleanupTail = {
                        main.completeDestroyedRuntimeCleanup(
                            { nativeAcknowledged = main.closeRuntimeArSession(originalClose) },
                            { detectorCalls += 1 },
                        )
                    },
                )
                superCalls += 1
                completed
            }

            assertEquals("$stage must not skip independent detector cleanup", 1, detectorCalls)
            assertEquals(1, superCalls)
            assertNull(result.exceptionOrNull())
            assertEquals(false, result.getOrNull())
            assertEquals("An uncertain renderer preparation cannot permit native close", 0, nativeCloseCalls)
            assertEquals(false, nativeAcknowledged)
            assertEquals(false, field(main, "runtimeMetricOutputAllowed"))
            assertSame(failure, closeFailure(main))
            assertSame(originalClose, retainedAction(ownership))
            assertTrue(cameraStartBlocked(isolatedMain(ownership)))
        }
    }

    @Test
    fun acknowledgedDestroyPreparationKeepsItsOriginalOrderAndNormalCloseHandoff() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val sequence = mutableListOf<String>()
        val originalClose: () -> Unit = { sequence += "native close" }
        setField(main, "runtimeMetricOutputAllowed", true)

        assertTrue(main.completeDestroyedRuntimePreparation(
            originalClose,
            {
                sequence += "navigation"
                sequence += "render mode"
                sequence += "surface pause"
            },
            {
                main.completeDestroyedRuntimeCleanup(
                    { assertTrue(main.closeRuntimeArSession(originalClose)) },
                    { sequence += "detector" },
                )
            },
        ))

        assertEquals(listOf("navigation", "render mode", "surface pause", "native close", "detector"), sequence)
        assertFalse(main.runtimeArSessionCleanupPending())
        assertNull(retainedAction(ownership))
        assertNull(closeFailure(main))
    }

    @Test
    fun destroyFrontFailureCannotReplaceOrReplayAnAlreadyUncertainNativeOwner() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val main = isolatedMain(ownership)
        val originalFailure = IllegalStateException("original native close uncertain")
        val preparationFailure = IllegalStateException("later renderer pause failed")
        var originalCloseCalls = 0
        var replacementCloseCalls = 0
        var tailCalls = 0
        val originalClose: () -> Unit = { originalCloseCalls += 1; throw originalFailure }
        val replacementClose: () -> Unit = { replacementCloseCalls += 1 }
        assertFalse(main.closeRuntimeArSession(originalClose))

        val result = runCatching {
            main.completeDestroyedRuntimePreparation(
                replacementClose,
                { throw preparationFailure },
                { main.completeDestroyedRuntimeCleanup({ tailCalls += 1 }) },
            )
        }

        assertEquals(1, tailCalls)
        assertNull(result.exceptionOrNull())
        assertEquals(false, result.getOrNull())
        assertEquals(1, originalCloseCalls)
        assertEquals(0, replacementCloseCalls)
        assertSame(originalFailure, closeFailure(main))
        assertSame(originalClose, retainedAction(ownership))
    }

    @Test
    fun sourceOnlyDestroyCapturesTheOriginalSessionBeforeItsFrontPreparationAndCleanup() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val destroy = source.substringAfter("override fun onDestroy()").substringBefore("internal fun completeDestroyedRuntimePreparation")
        val capture = destroy.indexOf("val ownedSession = arSessionOwner.observe().session")
        val preparation = destroy.indexOf("completeDestroyedRuntimePreparation(")
        val navigation = destroy.indexOf("cancelNavigationRequestsForDestroy()", preparation)
        val renderMode = destroy.indexOf("syncGlSurfaceRenderMode(forceFrame = true)", navigation)
        val surfacePause = destroy.indexOf("surfaceView.onPause()", renderMode)
        val invalidation = destroy.indexOf("runtimeMetricInvalidation.invalidate(\"app_destroyed\")", surfacePause)
        assertTrue("SOURCE_ONLY: native close target capture precedes every fallible front stage", capture >= 0 && preparation > capture)
        assertTrue("SOURCE_ONLY: original navigation/renderer/invalidation order is preserved", navigation > preparation && renderMode > navigation && surfacePause > renderMode && invalidation > surfacePause)
    }

    @Test
    fun sourceOnlyOriginalCloseTargetIsRetainedBeforeActiveReferencesAreCleared() {
        val invalidation = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricInvalidationController.kt").readText()
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/ar/RuntimeArSessionOwner.kt").readText()
        val capture = MainActivityRefactorSource.member(owner, "captureInvalidation")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(capture, "val observed = observe()",
            "val target = observed.session", "val action:", "retainClose(action)"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(invalidation, "synchronized(stateLock)",
            "native().captureInvalidation", "metric().invalidateEvidence()", "native().detachInvalidated(captured)",
            "native().completeInvalidationCleanup("))
        val detach = MainActivityRefactorSource.member(owner, "detachInvalidated")
        assertTrue(detach.contains("!invalidation.closeOwnerRetained"))
        assertTrue(detach.contains("session !== captured.session"))
        assertFalse(detach.contains("closeProvider"))
        assertTrue(detach.contains("clearActive()"))
    }

    private fun sourceFunction(signature: String): String {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val start = source.indexOf(signature)
        check(start >= 0)
        return source.substring(start, source.indexOf("\n    internal fun completeRuntimeArInvalidationCleanup", start))
    }

    private fun isolatedMain(ownership: RuntimeArSessionCleanupOwnership): MainActivity = MainActivity().also { main ->
        MainActivityControllerTestFixture.installIdleNativeProbeOwner(main)
        MainActivityControllerTestFixture.field("runtimeArSessionCleanupOwnership").apply { isAccessible = true }
            .set(main, ownership)
    }

    private fun retainedAction(ownership: RuntimeArSessionCleanupOwnership): Any? =
        RuntimeArSessionCleanupOwnership::class.java.getDeclaredField("retainedAction").run { isAccessible = true; get(ownership) }

    private fun closeFailure(main: MainActivity): Any? = MainActivityControllerTestFixture.field("lastRuntimeArSessionCloseFailure").run { isAccessible = true; get(main) }

    private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name).run { isAccessible = true; get(main) }

    private fun setField(main: MainActivity, name: String, value: Any) {
        MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
    }

    private fun cameraStartBlocked(main: MainActivity): Boolean = MethodHandles
        .privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
        .findVirtual(MainActivity::class.java, "runtimeCameraStartBlockedByAnotherOwner", MethodType.methodType(Boolean::class.javaPrimitiveType))
        .bindTo(main).invokeWithArguments(emptyList<Any>()) as Boolean
}
