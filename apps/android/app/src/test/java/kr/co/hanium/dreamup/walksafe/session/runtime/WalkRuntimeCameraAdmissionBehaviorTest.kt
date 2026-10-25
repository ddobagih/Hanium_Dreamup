package kr.co.hanium.dreamup.walksafe.session.runtime

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class WalkRuntimeCameraAdmissionBehaviorTest {
    @Test fun constructionDoesNotReadOrStartAnyPlatformOwner() {
        val f = CameraAdmissionFixture()
        assertTrue(f.trace.isEmpty())
    }
    @Test fun inactiveWalkCancelsOnlyCapturedHandoffWithoutEvenQueryingEpoch() {
        val f = CameraAdmissionFixture(); f.active = false
        f.controller.startWalkSessionRuntimeAfterCameraRelease(f.epoch)
        assertEquals(listOf("runtimeActive", "cancelHandoff"), f.trace)
        assertSame(f.epoch, f.cancelled.single())
    }
    @Test fun staleGenerationCannotStartSensorsOrCompleteNewResume() {
        val f = CameraAdmissionFixture(); val old = f.epoch; f.epoch = old.copy(recoveryGeneration = 1)
        f.controller.startWalkSessionRuntimeAfterCameraRelease(old)
        assertEquals(listOf("runtimeActive", "isRuntimeEpochCurrent", "cancelHandoff"), f.trace)
        assertSame(old, f.cancelled.single()); assertTrue(f.completed.isEmpty())
    }
    @Test fun releaseCompletionRechecksSameCapturedEpochAndDoesNotResumeReplacementWalk() {
        val f = CameraAdmissionFixture(); f.cleanup = true
        f.controller.startWalkSessionRuntimeAfterCameraRelease(f.epoch)
        assertEquals(listOf("runtimeActive", "isRuntimeEpochCurrent", "fallbackCleanupPending", "releaseFallback"), f.trace)
        assertNotNull(f.released); val old = f.epoch; f.epoch = WalkRuntimeEpoch("replacement", 0)
        f.cleanup = false; f.trace.clear(); f.released!!()
        assertSame(old, f.cancelled.single()); assertTrue(f.completed.isEmpty())
        assertFalse(f.trace.contains("resumeCameraLifecycleIfInitialized"))
    }
    @Test fun cameraDisabledKeepsCompassAndSpeechBeforeResumeCompletionWithoutCameraQueries() {
        val f = CameraAdmissionFixture(); f.camera = false
        f.controller.startWalkSessionRuntimeAfterCameraRelease(f.epoch)
        assertEquals(listOf("runtimeActive", "isRuntimeEpochCurrent", "fallbackCleanupPending", "cameraEnabled",
            "cancelHandoff", "pauseSurfaceIfInitialized", "retainLocationCompassOrStop", "startNavigation",
            "startHandsFreeVoice", "syncScreenPolicy", "refreshReadinessUi", "completeResumeActivation"), f.trace)
        assertNull(f.cancelled.single()); assertSame(f.epoch, f.completed.single())
    }
    @Test fun releaseFailureCancelsBeforeSafetyStopAndNeverStartsAnyRuntime() {
        val f = CameraAdmissionFixture(); f.blocked = true
        f.controller.startWalkSessionRuntimeAfterCameraRelease(f.epoch)
        assertEquals(listOf("cancelHandoff", "safetyStop"), f.trace.takeLast(2))
        assertEquals("camera_release_timed_out", f.safetyStopReason)
        assertTrue(f.completed.isEmpty()); assertFalse(f.trace.contains("startNavigation"))
    }
    @Test fun newFallbackBindingKeepsNativeEffectsBeforeNavigationAndCompletesLast() {
        val f = CameraAdmissionFixture(); f.fallback = true
        f.controller.startWalkSessionRuntimeAfterCameraRelease(f.epoch)
        assertEquals(listOf("resumeCameraLifecycleIfInitialized", "resumeOrPauseSurfaceForCurrentArSession",
            "startOrientationIfInitialized", "syncScreenPolicy", "fallbackRequested", "fallbackRunning",
            "bindFallback", "startNavigation", "startHandsFreeVoice", "refreshReadinessUi", "completeResumeActivation"), f.trace.drop(5))
        assertFalse(f.trace.contains("arSessionMissing")); assertSame(f.epoch, f.completed.single())
    }
    @Test fun alreadyRunningFallbackAvoidsBindAndArSessionQueries() {
        val f = CameraAdmissionFixture(); f.fallback = true; f.running = true
        f.controller.startWalkSessionRuntimeAfterCameraRelease(f.epoch)
        assertFalse(f.trace.contains("bindFallback")); assertFalse(f.trace.contains("arSessionMissing"))
        assertEquals("completeResumeActivation", f.trace.last())
    }
    @Test fun missingArSessionClearsPendingInstallBeforePermissionsThenStartsNavigation() {
        val f = CameraAdmissionFixture(); f.arMissing = true
        f.controller.startWalkSessionRuntimeAfterCameraRelease(f.epoch)
        assertTrue(f.trace.indexOf("clearPendingInstallStartIfSet") < f.trace.indexOf("ensurePermissionsThenStart"))
        assertTrue(f.trace.indexOf("ensurePermissionsThenStart") < f.trace.indexOf("startNavigation"))
        assertFalse(f.trace.contains("fallbackRunning")); assertFalse(f.trace.contains("bindFallback"))
    }
    @Test fun nativeEffectFailureDoesNotInventResumeCompletionOrContinueLaterEffects() {
        val f = CameraAdmissionFixture(); f.throwOn = "resumeOrPauseSurfaceForCurrentArSession"
        assertThrows(IllegalStateException::class.java) { f.controller.startWalkSessionRuntimeAfterCameraRelease(f.epoch) }
        assertFalse(f.trace.contains("startOrientationIfInitialized")); assertTrue(f.completed.isEmpty())
    }
}

private class CameraAdmissionFixture {
    var epoch = WalkRuntimeEpoch("walk", 0)
    var active = true; var cleanup = false; var camera = true; var blocked = false
    var fallback = false; var running = false; var arMissing = false; var throwOn: String? = null
    val trace = mutableListOf<String>(); val cancelled = mutableListOf<WalkRuntimeEpoch?>(); val completed = mutableListOf<WalkRuntimeEpoch>()
    var released: (() -> Unit)? = null; var safetyStopReason: String? = null
    private inline fun <reified T> port(crossinline result: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            trace += method.name; if (method.name == throwOn) throw IllegalStateException("native boundary")
            result(method.name, args.orEmpty())
        } as T
    val controller = WalkRuntimeCameraAdmissionController(
        port<WalkRuntimeCameraAdmission> { name, args -> when(name) {
            "runtimeActive" -> active
            "isRuntimeEpochCurrent" -> args[0] == epoch
            "fallbackCleanupPending" -> cleanup
            "cameraEnabled" -> camera
            "cameraReleaseBlocked" -> blocked
            "fallbackRequested" -> fallback
            "fallbackRunning" -> running
            "arSessionMissing" -> arMissing
            else -> error(name)
        } },
        port<WalkRuntimeCameraEffects> { name, args -> when(name) {
            "cancelHandoff" -> { cancelled += args[0] as WalkRuntimeEpoch?; Unit }
            "completeResumeActivation" -> { completed += args[0] as WalkRuntimeEpoch; Unit }
            "releaseFallback" -> { @Suppress("UNCHECKED_CAST") val callback = args[0] as () -> Unit; released = callback; Unit }
            "safetyStop" -> { safetyStopReason = args[0] as String; Unit }
            "resumeCameraLifecycleIfInitialized", "resumeOrPauseSurfaceForCurrentArSession", "startOrientationIfInitialized",
            "syncScreenPolicy", "bindFallback", "clearPendingInstallStartIfSet", "ensurePermissionsThenStart", "startNavigation",
            "startHandsFreeVoice", "refreshReadinessUi", "retainLocationCompassOrStop", "pauseSurfaceIfInitialized" -> Unit
            else -> error(name)
        } },
    )
}
