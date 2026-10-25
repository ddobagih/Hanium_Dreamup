package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.start.NativeGuidanceCalibrationSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Runs the calibration owner constructed by Main, retaining its production Scope adapter.
 * Handler, clock, speech terminal and effect outputs are controlled leaves. The account/device
 * and location owner are supplied by the existing Main fixture. This verifies queued callback
 * admission against Main's actual state, not onCreate, physical readiness, speech or walk start.
 */
class MainNativePreparationOwnershipJourneyTest {
    @After fun clearSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun registeredRecoveryWaitsForItsDeadlineAndRunsOnlyOnceForTheCurrentMainRequest() {
        val f = Fixture()
        repeat(3) { f.session.scheduleRecovery(f.request) }
        assertEquals(1, f.queue.size)
        assertEquals(3_000L, f.queue.values.single())
        f.tick(2_999L)
        assertTrue(f.effects.isEmpty())
        f.tick(3_000L)
        assertEquals(listOf("recover", "continue"), f.effects)
        f.tick(6_000L)
        assertEquals(2, f.effects.size)
    }

    @Test fun actualMainOwnerChangesRejectAlreadyRegisteredPromptAndRecoveryCallbacks() {
        val changes: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "actor" to { it.base.set("reporterUserId", "actor_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") },
            "gateway" to { GatewaySessionProcessCoordinator.beginOperation() },
            "activity_foreground" to { it.base.set("isActivityForeground", false) },
            "walk_foreground" to { it.base.lifecycle.handle(WalkSessionEvent.EnteredBackground) },
            "epoch" to { it.base.lifecycle.handle(WalkSessionEvent.EndRequested) },
            "destination" to { it.base.set("pendingUiDestination", it.destination.copy(id = "replacement")) },
            "request_identity" to { it.base.set("nativePhoneMountingCheckRequest", it.request.copy()) },
            "device_binding" to {
                val old = it.base.field("postLoginDeviceCheckSnapshot") as PostLoginDeviceCheckSnapshot
                it.base.set("postLoginDeviceCheckSnapshot", old.copy(sessionGeneration = requireNotNull(old.sessionGeneration) + 1))
            },
            "initial_permission_exit" to { it.base.set("initialAppPermissionExitRequired", true) },
        )
        for ((name, change) in changes) {
            val f = Fixture()
            assertFalse(f.session.advance(f.request))
            f.session.scheduleRecovery(f.request)
            val prompt = f.prompts.single()
            assertTrue(name, prompt.current())
            change(f)
            assertFalse(name, prompt.current())
            f.tick(2_000L)
            f.tick(3_000L)
            prompt.done()
            prompt.failed()
            assertEquals(name, emptyList<String>(), f.effects)
            assertTrue(name, f.queue.isEmpty())
        }
    }

    @Test fun locationPermissionLossBlocksPromptButRetainsTheExistingCorrectionTimer() {
        val f = Fixture()
        f.session.advance(f.request)
        f.session.scheduleRecovery(f.request)
        val recovery = f.queue.entries.single { it.value == 3_000L }.key
        f.base.locationPermissionGranted = false
        assertFalse(f.prompts.single().current())
        f.session.cancelPrompt()
        f.session.scheduleRecovery(f.request)
        assertEquals(1, f.queue.size)
        assertSame(recovery, f.queue.keys.single())
        assertEquals("No prompt is admitted without actual Main location permission", 1, f.prompts.size)
        // A correction opportunity is not an approval: only the sink is observed here.
        f.base.locationPermissionGranted = true
        f.tick(3_000L)
        assertEquals(listOf("recover", "continue"), f.effects)
        assertFalse(f.base.lifecycle.isRuntimeEpochCurrent(f.base.epoch))
    }

    @Test fun pendingSpeechDoesNotStartTheObservationWindowAndOnlyDoneStartsThreeSeconds() {
        val f = Fixture()
        f.busy = true
        assertFalse(f.session.advance(f.request))
        assertTrue(f.prompts.isEmpty())
        f.tick(2_000L)
        f.busy = false
        assertFalse(f.session.advance(f.request))
        val prompt = f.prompts.single()
        f.tick(9_000L)
        assertFalse("Elapsed time alone is not native DONE", f.session.advance(f.request))
        prompt.done()
        f.now = 11_999L
        assertFalse(f.session.advance(f.request))
        f.now = 12_000L
        assertTrue(f.session.advance(f.request))
        assertEquals("DIRECTION_CALIBRATION_READY", f.stages.last())
    }

    @Test fun cancelRemovesBothRealOwnerRegistrationsAndLateCallbacksCannotReviveThem() {
        val f = Fixture()
        f.session.advance(f.request)
        f.session.scheduleRecovery(f.request)
        val posted = f.queue.keys.toList()
        assertEquals(2, posted.size)
        val prompt = f.prompts.single()
        f.session.cancel()
        assertEquals(1, f.cancelled)
        assertTrue(f.queue.isEmpty())
        posted.forEach(Runnable::run)
        prompt.done()
        prompt.failed()
        assertFalse(prompt.current())
        assertTrue(f.effects.isEmpty())
        assertTrue(f.queue.isEmpty())
    }

    private data class Prompt(val current: () -> Boolean, val done: () -> Unit, val failed: () -> Unit)
    private class Fixture {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val destination = DestinationSearchResult("queued-owner", "시험 목적지", RoutePoint(37.0, 127.0), null, null, null, null)
        val request: NativePhoneMountingCheckRequest
        val session: NativeGuidanceCalibrationSession
        var now = 1_000L
        var busy = false
        var generation = 0L
        var cancelled = 0
        val queue = linkedMapOf<Runnable, Long>()
        val prompts = mutableListOf<Prompt>()
        val effects = mutableListOf<String>()
        val stages = mutableListOf<String>()

        init {
            base.nativeCheck()
            request = (base.field("nativePhoneMountingCheckRequest") as NativePhoneMountingCheckRequest)
                .copy(destination = destination)
            base.set("pendingUiDestination", destination)
            base.set("nativePhoneMountingCheckRequest", request)
            val lazy = MainActivity::class.java.getDeclaredField("nativeGuidanceCalibrationSession\$delegate")
                .apply { isAccessible = true }.get(base.main) as Lazy<*>
            session = lazy.value as NativeGuidanceCalibrationSession
            // Keep Main's actual Scope object. Replace only side-effect/runtime leaves.
            replace("elapsedRealtimeMs", { now })
            replace("callbacks", object : NativeGuidanceCalibrationSession.Callbacks {
                override fun postDelayed(action: Runnable, delayMs: Long) { queue[action] = now + delayMs }
                override fun remove(action: Runnable) { queue.remove(action) }
            })
            replace("speech", object : NativeGuidanceCalibrationSession.Speech {
                override fun pending() = busy
                override fun commandGeneration() = generation
                override fun submit(message: String, current: () -> Boolean, completed: () -> Unit, failed: () -> Unit): Boolean {
                    generation++
                    prompts += Prompt(current, completed, failed)
                    return true
                }
                override fun cancel() { cancelled++ }
            })
            replace("effects", object : NativeGuidanceCalibrationSession.Effects {
                override fun recordStage(stage: String) { stages += stage }
                override fun reportVisibleFailure(message: String) { effects += "failure" }
                override fun visibleFailure(): String? = null
                override fun clearVisibleFailure() { effects += "clear_failure" }
                override fun render() { effects += "render" }
                override fun continuePrewalk() { effects += "continue" }
                override fun recoverPreparation(request: NativePhoneMountingCheckRequest) {
                    assertSame(this@Fixture.request, request)
                    effects += "recover"
                }
            })
        }

        private fun replace(name: String, value: Any) {
            NativeGuidanceCalibrationSession::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(session, value)
        }
        fun tick(at: Long) {
            now = at
            val due = queue.filterValues { it <= now }.keys.toList()
            due.forEach { queue.remove(it); it.run() }
        }
    }
}
