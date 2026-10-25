package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Executes Main's shared frame assigner and ordinary-loss handler with typed camera evidence.
 * The native lease and already-authorized app admission are synthetic observations; the real
 * lifecycle, epoch, foreground, capability and frame guards remain in the production methods.
 * This does not instantiate ARCore, onboarding, Gateway, a route, or Android output channels.
 */
class MainActivityNavigationDepthContinuityBehaviorTest {
    @Test fun qualificationTimeoutSuppressesMetersAndRetiresOnlyTheQualifier() {
        val f = Fixture()
        val qualifier = f.qualifier

        f.timeout()

        assertEquals(RuntimeMetricPreflightReason.TIMEOUT, qualifier.result().reason)
        assertFalse(f.metersAllowed)
        assertNull(f.activation)
        f.assertWalkRetained()
    }

    @Test fun oneActualMetricSampleAfterTimeoutRecoversInTheSameEpochAndNativeGeneration() {
        val f = Fixture()
        f.timeout()

        f.frame(f.deadline + 1L, samples = 1)

        assertTrue(f.metersAllowed)
        assertEquals(f.deadline + 1L, f.lastValidAt)
        assertNull(f.activation)
        f.assertWalkRetained()
    }

    @Test fun moreZeroDistanceFramesNeverInventMetricEvidenceOrEndNavigation() {
        val f = Fixture()
        f.timeout()
        listOf(f.deadline + 1L, f.deadline + 3_000L).forEach { at ->
            f.frame(at, samples = 0, metricAvailable = false)
            assertFalse(f.metersAllowed)
            assertEquals(0L, f.lastValidAt)
            assertNull(f.activation)
            f.assertWalkRetained()
        }
    }

    @Test fun ordinaryDistanceLossAfterQualificationStillAllowsALaterRealFrameToRecover() {
        val f = Fixture()
        f.frame(1_010L, samples = 1)
        assertTrue(f.metersAllowed)
        assertNull(f.activation)

        f.frame(4_000L, samples = 0, metricAvailable = false)
        assertFalse(f.metersAllowed)
        f.assertWalkRetained()

        f.frame(4_001L, samples = 1)
        assertTrue(f.metersAllowed)
        assertEquals(4_001L, f.lastValidAt)
        f.assertWalkRetained()
    }

    @Test fun repeatedFrameCannotRenewTheLastRealMetricObservation() {
        val f = Fixture()
        val cameraFrame = 10_000_000_000L
        f.frame(1_010L, samples = 1, frameTimestampNs = cameraFrame)
        assertTrue(f.metersAllowed)

        f.frame(1_110L, samples = 1, frameTimestampNs = cameraFrame)
        assertTrue(f.metersAllowed)
        assertEquals(1_010L, f.lastValidAt)

        f.frame(4_000L, samples = 1, frameTimestampNs = cameraFrame)
        assertFalse(f.metersAllowed)
        assertEquals(1_010L, f.lastValidAt)
        f.assertWalkRetained()
    }

    @Test fun changedNativeGenerationCannotRecoverTheOldWalkFrame() {
        val f = Fixture()
        f.timeout()
        f.nativeObservation = f.nativeObservation.copy(generation = f.generation + 1L)

        f.frame(f.deadline + 1L, samples = 1)

        assertFalse(f.metersAllowed)
        assertEquals(0L, f.lastValidAt)
    }

    @Test fun foreignEvidenceGenerationCannotRecoverDespiteACurrentNativeLease() {
        val f = Fixture()
        f.timeout()

        f.frame(f.deadline + 1L, samples = 1, evidenceGeneration = f.generation + 1L)

        assertFalse(f.metersAllowed)
        assertEquals(0L, f.lastValidAt)
    }

    @Test fun explicitUserEndCannotBeUndoneByALateValidDistanceFrame() {
        val f = Fixture()
        f.timeout()
        f.lifecycle.handle(WalkSessionEvent.EndRequested)

        f.frame(f.deadline + 1L, samples = 1)

        assertEquals(WalkSessionState.ENDED, f.lifecycle.snapshot().state)
        assertFalse(f.metersAllowed)
        assertEquals(0L, f.lastValidAt)
    }

    @Test fun backgroundEntryCannotRecoverMetricOutputFromALateFrame() {
        val f = Fixture()
        f.timeout()
        f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        f.set("isActivityForeground", false)

        f.frame(f.deadline + 1L, samples = 1)

        assertEquals(WalkSessionState.PAUSED, f.lifecycle.snapshot().state)
        assertFalse(f.metersAllowed)
    }

    @Test fun recheckEpochCannotAcceptTheOldFrameOrOldLossCallback() {
        val f = Fixture()
        f.timeout()
        f.lifecycle.handle(WalkSessionEvent.RecheckRequested)
        assertNotEquals(f.epoch, f.lifecycle.snapshot().epoch)

        f.frame(f.deadline + 1L, samples = 1)

        assertFalse(f.metersAllowed)
        assertFalse(f.retainLoss(f.deadline))
    }

    @Test fun lateOrdinaryLossCannotEraseANewerValidFrame() {
        val f = Fixture()
        f.timeout()
        f.frame(f.deadline + 1L, samples = 1)

        assertFalse(f.retainLoss(f.deadline))

        assertTrue(f.metersAllowed)
        assertEquals(f.deadline + 1L, f.lastValidAt)
        f.assertWalkRetained()
    }

    @Test fun expiredQualifierReadBeforeRecoveryCannotSuppressTheNewerValidFrame() {
        val f = Fixture()
        val expiredQualifier = f.qualifier
        f.timeout()
        f.frame(f.deadline + 1L, samples = 1)

        // The timer read its old qualifier before GL recovered, but executes its loss
        // action afterwards. A newer callback time must not bypass ownership checks.
        assertFalse(f.retainLoss(f.deadline + 2L, expectedActivation = expiredQualifier))

        assertTrue(f.metersAllowed)
        assertEquals(f.deadline + 1L, f.lastValidAt)
        f.assertWalkRetained()
    }

    @Test fun revokedStartupConfirmationCannotAuthorizeRecovery() {
        val f = Fixture()
        f.timeout()
        f.set("confirmedStartupCapabilityDecision", null)

        f.frame(f.deadline + 1L, samples = 1)

        assertFalse(f.metersAllowed)
        assertFalse(f.retainLoss(f.deadline + 1L))
    }

    private class Fixture {
        val main = MainActivity()
        val generation = 7L
        private val startedAt = 1_000L
        val profile = RuntimeMetricPreflightPolicy.testCandidateProfile
        val deadline = startedAt + profile.maximumDurationMs
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "metric-continuity" },
            clock = { startedAt }, elapsedRealtimeClock = { startedAt })
        val epoch: WalkRuntimeEpoch
        var nativeObservation = MainActivity.RuntimeArObservation(Any(), Any(),
            MainActivity.ArSessionPurpose.RUNTIME, generation)
        val qualifier = RuntimeMetricPreflightSession(generation, startedAt,
            RuntimeMetricDepthSupport.SUPPORTED, profile)
        private val decision = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL,
            emptyList(), emptyList(), "synthetic current startup capability")

        init {
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            epoch = lifecycle.snapshot().epoch
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val collector = WalkSessionReadinessCollector(epoch, plan)
            plan.requiredRequirements.forEach { requirement ->
                collector.record(WalkSessionReadinessObservation(epoch, requirement, WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(startedAt)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            check(lifecycle.snapshot().state == WalkSessionState.ACTIVE)
            set("walkSessionLifecycle", lifecycle)
            set("isActivityForeground", true)
            set("startupCapabilityDecision", decision)
            set("confirmedStartupCapabilityDecision", decision)
            set("arSessionGeneration", generation)
            set("arSessionPurpose", MainActivity.ArSessionPurpose.RUNTIME)
            set("runtimeMetricActivationSession", qualifier)
            set("runtimeMetricInitialNavigationStartPending", false)
            set("isRouteActive", true)
        }

        val metersAllowed: Boolean get() = get("runtimeMetricOutputAllowed") as Boolean
        val lastValidAt: Long get() = get("runtimeMetricLastValidFrameAtMs") as Long
        val activation: Any? get() = get("runtimeMetricActivationSession")

        private fun admission(): Boolean = lifecycle.snapshot().let { walk ->
            walk.state == WalkSessionState.ACTIVE && walk.isForeground &&
                get("isActivityForeground") == true &&
                get("confirmedStartupCapabilityDecision") == get("startupCapabilityDecision")
        }

        fun timeout() = frame(deadline, samples = 0, metricAvailable = false)

        fun frame(at: Long, samples: Int, metricAvailable: Boolean = true,
            frameTimestampNs: Long = at * 1_000_000L, evidenceGeneration: Long = generation) {
            main.updateRuntimeMetricOutputGate(RuntimeMetricFrameEvidence(evidenceGeneration,
                frameTimestampNs, at, tracking = true, metricDepthAvailable = metricAvailable,
                validMetricSamplesInRange = samples), generation, epoch,
                observeRuntimeAr = { nativeObservation }, observeRuntimeAdmission = ::admission)
        }

        fun retainLoss(at: Long, expectedActivation: RuntimeMetricPreflightSession? = null): Boolean =
            main.retainRuntimeMetricSessionAfterOrdinaryLoss(generation, epoch, at,
                observeRuntimeAr = { nativeObservation }, observeRuntimeAdmission = ::admission,
                expectedActivation = expectedActivation)

        fun assertWalkRetained() {
            assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
            assertEquals(WalkSessionMode.FULL, lifecycle.snapshot().mode)
            assertEquals(epoch, lifecycle.snapshot().epoch)
            assertEquals(generation, get("arSessionGeneration"))
            assertEquals(MainActivity.ArSessionPurpose.RUNTIME, get("arSessionPurpose"))
            assertEquals(true, get("isRouteActive"))
        }

        fun set(name: String, value: Any?) = field(name).set(main, value)
        private fun get(name: String): Any? = field(name).get(main)
        private fun field(name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }
    }
}
