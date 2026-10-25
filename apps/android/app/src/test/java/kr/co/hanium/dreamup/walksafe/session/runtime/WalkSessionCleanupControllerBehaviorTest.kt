package kr.co.hanium.dreamup.walksafe.session.runtime

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeStopCleanupCheckpoints
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationCancellation
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

internal inline fun <reified T> cleanupPort(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        invoke(method.name, args.orEmpty())
    } as T

class WalkSessionCleanupControllerBehaviorTest {
    @Test fun noNativeOrMachineEffectsDuringConstruction() {
        val f = CleanupFixture(); assertTrue(f.events.isEmpty())
    }
    @Test fun normalCancellationKeepsSpeechAndFrameFenceAheadOfNativeCloseAndLocationRestartLast() {
        val f = CleanupFixture(); f.controller.cancelWalkSessionOutputs("pause")
        fun before(a: String, b: String) = assertTrue("$a before $b", f.events.indexOf(a) < f.events.indexOf(b))
        before("advanceGenerationAndResetReferences", "stopVoiceService")
        before("cancelRecognition", "cancelPrivacyUploads")
        before("cancelRequestsForPause", "cancelWalkSessionRouteState")
        before("invalidateFrameState", "closeActuator")
        before("cancelPendingDeliveries", "cancelTalkBack")
        before("cancelTerminal", "closeActuator")
        before("closeActuator", "moveCameraToCreated")
        assertEquals("startLocationUpdatesIfAllowed", f.events.last())
        assertSame(f.cancellation, f.routeCancellation)
    }
    @Test fun ordinaryNativeExceptionPropagatesWithoutPretendingTailRan() {
        val f = CleanupFixture(); f.throwVoice = true
        try { f.controller.cancelWalkSessionOutputs("pause"); fail("expected native failure") } catch (_: IllegalStateException) { }
        assertFalse(f.events.contains("cancelRecognition")); assertFalse(f.events.contains("closeActuator"))
    }
    @Test fun checkpointFailureContinuesIndependentCleanupAndDoesNotRepeatUncertainNativeStop() {
        val f = CleanupFixture(); f.voiceStops = false; val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        assertFalse(f.controller.cancelWalkSessionOutputsForSafetyStop("stop", checkpoints))
        assertTrue(f.events.contains("closeActuator")); assertTrue(f.events.contains("pauseSurface"))
        val once = f.events.toList(); f.voiceStops = true
        assertFalse(f.controller.cancelWalkSessionOutputsForSafetyStop("stop", checkpoints))
        assertEquals(1, f.events.count { it == "stopVoiceService" })
        assertEquals(1, f.events.count { it == "advanceGenerationAndResetReferences" })
        assertEquals(1, f.events.count { it == "closeActuator" })
        assertTrue(once.isNotEmpty())
    }
    @Test fun retainedLocationOwnerSkipsNativeStopButPreservesTailRestart() {
        val f = CleanupFixture(); f.locationOwned = true
        f.controller.cancelWalkSessionOutputs("pause")
        assertFalse(f.events.contains("stopLocationUpdates"))
        assertEquals("startLocationUpdatesIfAllowed", f.events.last())
    }
    @Test fun safetyStopEndsHeldLeaseAndAwaitsExactCameraCallbackWithoutRepeatingDispatch() {
        val f = CleanupFixture(); val token = f.gateway.active(); val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val camera = mutableListOf<Boolean>()
        assertTrue(f.controller.performWalkSessionSafetyStopCleanup("camera", true, checkpoints, camera::add))
        assertEquals(WalkSessionState.SAFE_STOP, f.gateway.lifecycle.snapshot().state)
        assertTrue(camera.isEmpty()); assertEquals(1, f.cameraCalls)
        f.cameraDone!!(); f.cameraDone!!()
        assertEquals(listOf(true), camera)
        assertTrue(f.controller.performWalkSessionSafetyStopCleanup("camera", true, checkpoints, camera::add))
        assertEquals(1, f.cameraCalls); assertEquals(listOf(true, true), camera)
        f.gateway.runBackground()
        assertEquals(token.epoch.walkSessionId, f.gateway.ends.single().second.walkId)
    }
    @Test fun uncertainCameraReleaseIsReportedButNeverDispatchedTwice() {
        val f = CleanupFixture(); val checkpoints = WalkRuntimeStopCleanupCheckpoints(); val camera = mutableListOf<Boolean>()
        f.controller.performWalkSessionSafetyStopCleanup("camera", false, checkpoints, camera::add)
        f.cameraFailed!!(); f.cameraDone!!()
        f.controller.performWalkSessionSafetyStopCleanup("camera", false, checkpoints, camera::add)
        assertEquals(listOf(false, false), camera); assertEquals(1, f.cameraCalls)
    }
    @Test fun interruptedMarkerIsOnlyIdempotentRetryAndCannotHideFailedDepthClose() {
        val f = CleanupFixture(); f.persisted = false; f.depthStops = false; val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        assertFalse(f.controller.performWalkSessionSafetyStopCleanup("stop", true, checkpoints, null))
        f.persisted = true; f.depthStops = true
        assertFalse(f.controller.performWalkSessionSafetyStopCleanup("stop", true, checkpoints, null))
        assertEquals(2, f.events.count { it == "persistInterruptionMarker" })
        assertEquals(1, f.events.count { it == "stopDepthSession" })
    }
    @Test fun cleanupPendingBlocksFreshWalkWithoutTouchingItsEpochOrNativeResources() {
        val f = CleanupFixture(); val epoch = f.gateway.lifecycle.snapshot().epoch; f.cleanupBlocked = true
        f.controller.startFreshWalk("tap")
        assertEquals(epoch, f.gateway.lifecycle.snapshot().epoch)
        assertFalse(f.events.contains("invalidateOfficialEnvironment"))
        assertFalse(f.events.contains("closeActuator"))
    }
    @Test fun newWalkInvalidatesEvidenceThenCancelsBeforeResettingTransientState() {
        val f = CleanupFixture(); val epoch = f.gateway.lifecycle.snapshot().epoch
        f.controller.startFreshWalk("tap")
        assertNotEquals(epoch, f.gateway.lifecycle.snapshot().epoch)
        assertTrue(f.events.indexOf("invalidateOfficialEnvironment") < f.events.indexOf("advanceGenerationAndResetReferences"))
        assertEquals("resetTransientStateForNewWalk", f.events.last())
    }
}

internal class CleanupFixture(private val startLocationAction: () -> Unit = {}) {
    val gateway = GatewayFixture(); val resume = ResumeFixture()
    val events = mutableListOf<String>()
    var voiceStops = true; var throwVoice = false; var locationOwned = false; var depthStops = true
    var persisted = true; var cleanupBlocked = false; var cameraCalls = 0
    var cameraDone: (() -> Unit)? = null; var cameraFailed: (() -> Unit)? = null
    val cancellation = AndroidNavigationCancellation(true, true)
    var routeCancellation: AndroidNavigationCancellation? = null
    val walk = object : WalkSessionRuntimeAccess {
        override fun available() = true
        override fun snapshot() = gateway.lifecycle.snapshot()
        override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = gateway.lifecycle.isRuntimeEpochCurrent(epoch)
        override fun transition(event: WalkSessionEvent) = gateway.lifecycle.handle(event)
    }
    val controller = WalkSessionCleanupController(walk, gateway.authority, gateway.controller, { gateway.account }, resume.controller,
        cleanupPort<WalkCleanupAdmission> { name, _ -> events += name; when(name) {
            "developmentBypassEnabled", "stopCleanupExhausted" -> false
            "stopCleanupBlocksNewWalk" -> cleanupBlocked
            "persistInterruptionMarker" -> persisted
            else -> Unit
        } },
        cleanupPort<WalkCleanupNative> { name, args -> events += name; when(name) {
            "stopVoiceService" -> if (throwVoice) throw IllegalStateException("voice") else voiceStops
            "stopDepthSession" -> depthStops
            "lastDepthCloseFailure" -> IllegalStateException("depth")
            "stopCameraFallbackSession" -> { cameraCalls++; @Suppress("UNCHECKED_CAST")
                cameraDone = args[1] as (() -> Unit)?; @Suppress("UNCHECKED_CAST")
                cameraFailed = args[2] as (() -> Unit)?; Unit }
            else -> Unit
        } },
        cleanupPort<WalkCleanupNavigation> { name, args -> events += name; when(name) {
            "cancelRequestsForPause" -> cancellation
            "cancelWalkSessionRouteState" -> { routeCancellation = args[2] as AndroidNavigationCancellation; Unit }
            "stopPositionFieldSession", "locationCleanupSucceeded" -> true
            "locationCollectionAllowsWork" -> locationOwned
            "startLocationUpdatesIfAllowed" -> { startLocationAction(); Unit }
            "runLocationStopCleanupCheckpoint" -> {
                val c = args[0] as WalkRuntimeStopCleanupCheckpoints
                @Suppress("UNCHECKED_CAST") val stop = args[2] as () -> Boolean
                c.runStage("controlled-location-tail") { check(stop()) }
            }
            else -> Unit
        } },
        cleanupPort<WalkCleanupFeedback> { name, _ -> events += name; Unit },
        cleanupPort<WalkCleanupReports> { name, _ -> events += name; Unit }, false)
}
