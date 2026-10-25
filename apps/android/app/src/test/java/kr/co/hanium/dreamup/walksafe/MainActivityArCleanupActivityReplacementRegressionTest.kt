package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.device.RuntimeArSessionCleanupOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two actual Main objects model Activity replacement and invoke the existing cleanup/guard APIs.
 * Activity onDestroy/recreation, ARCore Session and OS/native close are not executed.
 */
class MainActivityArCleanupActivityReplacementRegressionTest {
    @Test
    fun unresolvedOldMainCloseBlocksCameraStartInTheReplacementMain() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val oldMain = isolatedMain(ownership)
        val originalFailure = IllegalStateException("old Activity AR close remains uncertain")
        var originalCloseCalls = 0
        val originalClose: () -> Unit = { originalCloseCalls += 1; throw originalFailure }
        assertObservedCloseFailure(oldMain, originalClose, originalFailure)
        assertTrue(oldMain.runtimeArSessionCleanupPending())
        assertTrue(cameraStartBlocked(oldMain))
        val replacementMain = isolatedMain(ownership)
        assertNotSame(oldMain, replacementMain)

        val replacementBlocked = cameraStartBlocked(replacementMain)

        assertEquals(1, originalCloseCalls)
        assertSame(originalClose, field(oldMain, "pendingRuntimeArSessionCloseAction"))
        assertSame(originalFailure, field(oldMain, "lastRuntimeArSessionCloseFailure"))
        assertTrue("Replacing Main must not clear an unresolved native AR owner", replacementBlocked)
    }

    @Test
    fun replacementMainCannotCloseAnIndependentActionWhileTheOriginalOwnerIsUncertain() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val oldMain = isolatedMain(ownership)
        val originalFailure = IllegalStateException("original Activity still owns uncertain AR cleanup")
        var originalCloseCalls = 0
        val originalClose: () -> Unit = { originalCloseCalls += 1; throw originalFailure }
        assertObservedCloseFailure(oldMain, originalClose, originalFailure)
        val replacementMain = isolatedMain(ownership)
        assertNotSame(oldMain, replacementMain)
        var independentCloseCalls = 0

        val acknowledged = replacementMain.closeRuntimeArSession { independentCloseCalls += 1 }

        assertTrue(oldMain.runtimeArSessionCleanupPending())
        assertEquals(1, originalCloseCalls)
        assertSame(originalClose, field(oldMain, "pendingRuntimeArSessionCloseAction"))
        assertSame(originalFailure, field(oldMain, "lastRuntimeArSessionCloseFailure"))
        assertTrue(
            "An independent replacement close must be rejected: acknowledged=$acknowledged independentCloseCalls=$independentCloseCalls",
            !acknowledged && independentCloseCalls == 0,
        )
    }

    @Test
    fun acknowledgedOldCloseLeavesTheReplacementUnblockedAndAllowsItsIndependentClose() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val oldMain = isolatedMain(ownership)
        var originalCloseCalls = 0
        assertTrue(oldMain.closeRuntimeArSession { originalCloseCalls += 1 })
        assertFalse(oldMain.runtimeArSessionCleanupPending())
        assertNull(field(oldMain, "pendingRuntimeArSessionCloseAction"))
        assertNull(field(oldMain, "lastRuntimeArSessionCloseFailure"))
        val replacementMain = isolatedMain(ownership)
        assertNotSame(oldMain, replacementMain)
        var independentCloseCalls = 0

        assertFalse(cameraStartBlocked(replacementMain))
        assertTrue(replacementMain.closeRuntimeArSession { independentCloseCalls += 1 })

        assertEquals(1, originalCloseCalls)
        assertEquals(1, independentCloseCalls)
        assertFalse(replacementMain.runtimeArSessionCleanupPending())
        assertFalse(cameraStartBlocked(replacementMain))
        assertNull(field(replacementMain, "pendingRuntimeArSessionCloseAction"))
        assertNull(field(replacementMain, "lastRuntimeArSessionCloseFailure"))
    }

    @Test
    fun defaultMainObjectsReadTheSameProductionProcessOwnershipWithoutMutatingIt() {
        val first = MainActivity()
        val second = MainActivity()

        assertNotSame(first, second)
        assertSame(RuntimeArSessionCleanupOwnership.processSingleton, field(first, "runtimeArSessionCleanupOwnership"))
        assertSame(field(first, "runtimeArSessionCleanupOwnership"), field(second, "runtimeArSessionCleanupOwnership"))
    }

    @Test
    fun lateCleanupFromTheAcknowledgedOldMainCannotExecuteTheCurrentMainsCloseOwner() {
        val ownership = RuntimeArSessionCleanupOwnership()
        val oldMain = isolatedMain(ownership)
        val currentMain = isolatedMain(ownership)
        var oldCloseCalls = 0
        var currentCloseCalls = 0
        var oldBeforeCloseCalls = 0
        assertTrue(oldMain.closeRuntimeArSession { oldCloseCalls += 1 })
        val oldDiagnostic = IllegalStateException("old Activity local tail diagnostic")
        MainActivityControllerTestFixture.field("lastRuntimeArSessionCloseFailure").apply {
            isAccessible = true
        }.set(oldMain, oldDiagnostic)
        val currentClose: () -> Unit = { currentCloseCalls += 1 }
        assertTrue(currentMain.retainRuntimeArSessionCloseOwner(currentClose))

        assertFalse(oldMain.closeRetainedRuntimeArSession { oldBeforeCloseCalls += 1 })

        assertEquals(1, oldCloseCalls)
        assertEquals(0, oldBeforeCloseCalls)
        assertEquals(0, currentCloseCalls)
        assertSame(currentClose, retainedAction(ownership))
        assertTrue(ownership.isPending)
        assertSame(oldDiagnostic, field(oldMain, "lastRuntimeArSessionCloseFailure"))
        assertNull(field(currentMain, "lastRuntimeArSessionCloseFailure"))
        assertTrue(currentMain.closeRetainedRuntimeArSession())
        assertEquals(1, currentCloseCalls)
        assertFalse(ownership.isPending)
        assertNull(retainedAction(ownership))
        assertSame(oldDiagnostic, field(oldMain, "lastRuntimeArSessionCloseFailure"))
    }

    private fun assertObservedCloseFailure(
        main: MainActivity,
        action: () -> Unit,
        expectedFailure: RuntimeException,
    ) {
        val result = runCatching { main.closeRuntimeArSession(action) }
        assertNull("Unexpected exceptions must not be mistaken for the injected close failure", result.exceptionOrNull())
        assertEquals(false, result.getOrNull())
        assertSame(expectedFailure, field(main, "lastRuntimeArSessionCloseFailure"))
    }

    private fun cameraStartBlocked(main: MainActivity): Boolean {
        val method = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(
                MainActivity::class.java,
                "runtimeCameraStartBlockedByAnotherOwner",
                MethodType.methodType(Boolean::class.javaPrimitiveType),
            )
        return method.bindTo(main).invokeWithArguments(emptyList<Any>()) as Boolean
    }

    private fun isolatedMain(ownership: RuntimeArSessionCleanupOwnership): MainActivity = MainActivity().also { main ->
        MainActivityControllerTestFixture.installIdleNativeProbeOwner(main)
        MainActivityControllerTestFixture.field("runtimeArSessionCleanupOwnership").apply {
            isAccessible = true
        }.set(main, ownership)
    }

    private fun field(main: MainActivity, name: String): Any? {
        val ownership = MainActivityControllerTestFixture.field("runtimeArSessionCleanupOwnership").run {
            isAccessible = true
            get(main) as RuntimeArSessionCleanupOwnership
        }
        return when (name) {
            "pendingRuntimeArSessionCloseAction" -> retainedAction(ownership)
            else -> MainActivityControllerTestFixture.field(name).run {
                isAccessible = true
                get(main)
            }
        }
    }

    private fun retainedAction(ownership: RuntimeArSessionCleanupOwnership): Any? =
        RuntimeArSessionCleanupOwnership::class.java.getDeclaredField("retainedAction").run {
            isAccessible = true
            get(ownership)
        }
}
