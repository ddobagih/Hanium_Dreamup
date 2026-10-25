package kr.co.hanium.dreamup.walksafe

import java.lang.reflect.InvocationTargetException
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.navigation.start.NativePrewalkController
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.runtime.*
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The Main-created prewalk action calls the production cleanup and transition owners against one
 * real lifecycle. Native cleanup/reporting effects are controlled leaves. This does not execute
 * onCreate, SDK collection, readiness admission, or route installation. */
class MainFreshWalkForegroundBehaviorTest {
    @After fun clearSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun currentForegroundIsPublishedOnlyAfterSuccessfulFreshWalkCleanup() {
        val f = Fixture()
        val previous = f.lifecycle.snapshot()
        f.start()
        val current = f.lifecycle.snapshot()
        assertNotEquals(previous.epoch, current.epoch)
        assertEquals(WalkSessionState.READY, current.state)
        assertTrue("A visible Main must publish foreground again after NewWalkRequested", current.isForeground)
        assertNull(current.readiness)
        assertNull(current.confirmationToken)
        assertFalse(f.lifecycle.isRuntimeEpochCurrent(current.epoch))
        assertEquals(listOf(WalkSessionEvent.EnteredForeground), f.events)
        assertEquals("resetTransientStateForNewWalk", f.cleanupTailAtForeground)
    }

    @Test fun actualContinuousGpsSourceCanPublishFreshEvidenceButCannotReuseTheEndedRequest() {
        val f = Fixture()
        val oldEpoch = f.lifecycle.snapshot().epoch
        val oldRequest = NativePhoneMountingCheckRequest(
            f.base.field("reporterUserId") as String,
            GatewaySessionProcessCoordinator.snapshot().generation, null,
            PhoneMountingCheckRequest(oldEpoch, 1L, f.base.now),
        )
        f.base.set("nativePhoneMountingCheckRequest", oldRequest)
        f.start()
        assertFalse(f.nativeGpsCurrent(oldEpoch))
        assertFalse(f.nativeGpsCurrent(f.lifecycle.snapshot().epoch))
        assertSame("Foreground publication must not rewrite an old request as current", oldRequest,
            f.base.field("nativePhoneMountingCheckRequest"))
        f.base.now += 1L
        f.base.observe(3.0)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.base.evidence?.status)
        assertEquals(f.lifecycle.snapshot().epoch, f.base.evidence?.epoch)
        assertEquals(f.base.now, f.base.evidence?.observedAtElapsedRealtimeMs)
        assertNull(f.lifecycle.snapshot().confirmationToken)
    }

    @Test fun backgroundActivityCannotPublishForegroundOrContinuousGpsEvidence() {
        val f = Fixture()
        f.base.set("isActivityForeground", false)
        f.start()
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
        assertFalse(f.lifecycle.snapshot().isForeground)
        assertTrue(f.events.isEmpty())
        f.base.observe(3.0)
        assertNull(f.base.evidence)
    }

    @Test fun activityLeavingDuringCleanupCannotBeReintroducedAsForeground() {
        val f = Fixture()
        f.afterLocationCleanup = { f.base.set("isActivityForeground", false) }
        f.start()
        assertEquals("resetTransientStateForNewWalk", f.cleanup.events.last())
        assertFalse(f.lifecycle.snapshot().isForeground)
        assertTrue(f.events.isEmpty())
    }

    @Test fun cleanupAdmissionRejectionPreservesEndedOwnerAndDoesNotPublishForeground() {
        val f = Fixture()
        f.cleanup.cleanupBlocked = true
        val before = f.lifecycle.snapshot()
        f.start()
        assertEquals(before, f.lifecycle.snapshot())
        assertTrue(f.events.isEmpty())
        assertFalse(f.cleanup.events.contains("closeActuator"))
        assertFalse(f.cleanup.events.contains("resetTransientStateForNewWalk"))
    }

    @Test fun partialNativeCleanupFailureCannotPublishForegroundOrPretendCleanupCompleted() {
        val f = Fixture()
        f.cleanup.throwVoice = true
        try { f.start(); fail("Expected the native cleanup failure") }
        catch (_: IllegalStateException) { }
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
        assertFalse(f.lifecycle.snapshot().isForeground)
        assertTrue(f.events.isEmpty())
        assertFalse(f.cleanup.events.contains("resetTransientStateForNewWalk"))
        f.base.observe(3.0)
        assertNull(f.base.evidence)
    }

    @Test fun anEndRequestedDuringCleanupCannotBeRevivedByTheEarlierStartAction() {
        val f = Fixture()
        f.afterLocationCleanup = { f.lifecycle.handle(WalkSessionEvent.EndRequested) }
        f.start()
        assertEquals(WalkSessionState.ENDED, f.lifecycle.snapshot().state)
        assertFalse(f.lifecycle.snapshot().isForeground)
        assertTrue(f.events.isEmpty())
    }

    private class Fixture {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        var afterLocationCleanup: () -> Unit = {}
        val cleanup = CleanupFixture { afterLocationCleanup() }
        val lifecycle = cleanup.gateway.lifecycle
        val events = mutableListOf<WalkSessionEvent>()
        var cleanupTailAtForeground: String? = null
        val action: NativePrewalkController.Actions

        init {
            // A unique epoch is produced by the real lifecycle for each new walk.
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            lifecycle.handle(WalkSessionEvent.EndRequested)
            base.set("walkSessionLifecycle", lifecycle)
            delegate("walkSessionCleanupController", cleanup.controller)
            val transition = WalkSessionTransitionController(
                lifecycle = { lifecycle }, authority = cleanup.gateway.authority,
                gateway = cleanup.gateway.controller, gatewaySession = { null },
                effects = cleanupPort<WalkTransitionEffects> { name, _ ->
                    if (name == "beforePublication") cleanupTailAtForeground = cleanup.events.lastOrNull()
                    Unit
                },
                reports = cleanupPort<WalkTransitionReports> { name, args ->
                    if (name == "handleRawCollectionTransition") events += args[1] as WalkSessionEvent
                    Unit
                },
                diagnostics = cleanupPort<WalkTransitionDiagnostics> { name, _ ->
                    if (name == "available") false else Unit
                },
            )
            delegate("walkSessionTransitionController", transition)
            val prewalk = MainActivityControllerTestFixture.controller(base.main, "nativePrewalkController")
            action = prewalk.javaClass.getDeclaredField("actions").apply { isAccessible = true }
                .get(prewalk) as NativePrewalkController.Actions
        }

        fun start() = action.startFreshWalk("native_home_requested_new_walk")
        fun nativeGpsCurrent(epoch: WalkRuntimeEpoch): Boolean = try {
            MainActivity::class.java.getDeclaredMethod("nativeGpsCheckIsCurrent", WalkRuntimeEpoch::class.java)
                .apply { isAccessible = true }.invoke(base.main, epoch) as Boolean
        } catch (e: InvocationTargetException) { throw e.targetException }
        private fun delegate(name: String, owner: Any) {
            MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
                .set(base.main, lazyOf(owner))
        }
    }
}
