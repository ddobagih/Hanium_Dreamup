package kr.co.hanium.dreamup.walksafe

import java.lang.reflect.InvocationTargetException
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.postlogin.AndroidPostLoginDeviceProbes
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.runtime.metric.RuntimeMetricSessionController
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Calls Main's actual new-walk reset with its current metric/device owners. Native collections
 * are absent in this host fixture; no depth frame, admission or SDK support is fabricated. */
class MainMetricNewWalkRetentionBehaviorTest {
    @After fun clearSession() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun savedFullCannotPromoteCurrentUnsupportedOrUnknownCapability() {
        for ((state, previous, expected) in listOf(
            Triple(PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED, false, false),
            Triple(PostLoginMetricDepthState.UNKNOWN, null, null),
            Triple(PostLoginMetricDepthState.SUPPORTED, false, null),
            Triple(PostLoginMetricDepthState.SUPPORTED, true, true),
        )) {
            val f = Fixture(); f.metric.restoreCapability(previous); f.probes.restoreMetricDepth(state)
            assertEquals(PostLoginDeviceCheckState.FULL, f.snapshot.state)
            f.reset()
            assertEquals(expected, f.metric.capabilityOverride)
            assertFalse(f.metric.outputAllowed)
        }
    }

    @Test fun changedAccountOrPendingRefreshCannotRetainEarlierSupport() {
        for (changedAccount in listOf(true, false)) {
            val f = Fixture(); f.metric.restoreCapability(true); f.probes.restoreMetricDepth(PostLoginMetricDepthState.SUPPORTED)
            if (changedAccount) {
                val previousDevice = f.snapshot
                val actor = "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                val onboarding = (f.base.field("firstRunOnboardingSnapshot") as FirstRunOnboardingSnapshot)
                    .copy(verifiedActorBinding = FirstRunOpaqueActorBinding.fromProvider(actor))
                val session = GatewayFieldSession.verified("https://gateway.example.test", actor,
                    "walksafe_field_session=v2.metric-retention", Long.MAX_VALUE)
                val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
                assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
                f.base.set("reporterUserId", actor)
                f.base.set("firstRunOnboardingSnapshot", onboarding)
                assertSame(previousDevice, f.snapshot)
                assertNotEquals(previousDevice.bindingOrNull?.sessionGeneration,
                    GatewaySessionProcessCoordinator.snapshot().generation)
            }
            else f.base.set("storedMetricDepthRefreshPending", true)
            f.reset(); assertNull(if (changedAccount) "new actor/process binding" else "pending support refresh",
                f.metric.capabilityOverride)
        }
    }

    @Test fun deferredCameraChecksRemainRestrictedThroughANewWalk() {
        val f = Fixture(); f.metric.restoreCapability(true); f.probes.restoreMetricDepth(PostLoginMetricDepthState.SUPPORTED)
        f.base.set("postLoginCameraDependentChecksDeferred", true)
        f.reset(); assertEquals(false, f.metric.capabilityOverride)
    }

    private class Fixture {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val snapshot get() = base.field("postLoginDeviceCheckSnapshot") as PostLoginDeviceCheckSnapshot
        @Suppress("UNCHECKED_CAST")
        val metric = MainActivityControllerTestFixture.controller(base.main, "runtimeMetricSession") as RuntimeMetricSessionController<Any, Any, Any>
        init { MainActivityControllerTestFixture.installIdleNativeProbeOwner(base.main) }
        val probes = MainActivity::class.java.getDeclaredMethod("getPostLoginDeviceProbes").apply { isAccessible = true }
            .invoke(base.main) as AndroidPostLoginDeviceProbes
        init {
            base.set("positioningCollectionBinding", null)
            base.set("locationCallback", null)
            base.set("isActivityForeground", false)
        }
        fun reset() {
            try { MainActivity::class.java.getDeclaredMethod("resetWalkTransientStateForNewWalk").apply { isAccessible = true }.invoke(base.main) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
    }
}
