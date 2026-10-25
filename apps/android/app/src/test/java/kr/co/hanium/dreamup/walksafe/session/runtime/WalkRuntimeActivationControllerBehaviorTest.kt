package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class WalkRuntimeActivationControllerBehaviorTest {
    @Test fun constructorDoesNotReadRuntimeOrStartNativeServices() {
        val f = ActivationFixture(); assertTrue(f.events.isEmpty())
    }
    @Test fun currentWalkStartsNavigationBeforeCameraAndQueuesNativeAfterActualRelease() {
        val f = ActivationFixture(); f.gateway.active()
        assertTrue(f.controller.activateWalkSessionRuntime())
        assertTrue(f.events.indexOf("startNavigation") < f.events.indexOf("beginHandoff"))
        assertFalse(f.events.contains("startRuntimeAfterRelease")); assertNull(f.queued)
        f.released!!(); assertNotNull(f.queued); assertFalse(f.events.contains("startRuntimeAfterRelease"))
        f.queued!!(); assertEquals(f.gateway.lifecycle.snapshot().epoch, f.startedEpoch)
    }
    @Test fun failedInterruptionPersistenceCannotStartResourceOrCameraAndNeedsUserRetry() {
        val f = ActivationFixture(); f.gateway.active(); f.persisted = false
        assertFalse(f.controller.activateWalkSessionRuntime())
        assertTrue(f.resume.controller.retryRequiresUserAction)
        assertFalse(f.events.contains("ensureResourceMonitoring")); assertNull(f.released)
        assertTrue(f.events.contains("foregroundRecheck:interruption_marker_write_failed"))
    }
    @Test fun unsafeDetectorRejectsBeforePersistenceAndOnlyCurrentEpochCanStop() {
        val f = ActivationFixture(); f.gateway.active(); f.rejectDetector = true
        assertFalse(f.controller.activateWalkSessionRuntime())
        assertFalse(f.events.contains("persistInterruptionMarker"))
        val old = f.gateway.lifecycle.snapshot().epoch.copy(recoveryGeneration = 999L)
        f.detectorStop!!(old); assertFalse(f.events.contains("safetyStop:detector_stalled"))
        f.detectorStop!!(f.gateway.lifecycle.snapshot().epoch)
        assertTrue(f.events.contains("safetyStop:detector_stalled"))
    }
    @Test fun latchedSafetyEpochPreventsAllResourceAndCameraStarts() {
        val f = ActivationFixture(); f.gateway.active(); f.latched = true
        assertFalse(f.controller.activateWalkSessionRuntime())
        assertFalse(f.events.contains("ensureResourceMonitoring")); assertNull(f.released)
    }
    @Test fun failedResourceMonitorTriggersForegroundRecheckAndExplicitRetry() {
        val f = ActivationFixture(); f.gateway.active(); f.monitorBlocked = true
        assertFalse(f.controller.activateWalkSessionRuntime())
        assertTrue(f.events.contains("foregroundRecheck:resource_monitoring_unavailable"))
        assertTrue(f.resume.controller.retryRequiresUserAction)
        assertFalse(f.events.contains("observeResourceSafety"))
    }
    @Test fun qualitativeEnvironmentFailurePreventsNavigationAndCamera() {
        val f = ActivationFixture(); f.gateway.active(); f.environmentReady = false
        assertFalse(f.controller.activateWalkSessionRuntime())
        assertFalse(f.events.contains("activatePhoneMounting")); assertFalse(f.events.contains("startNavigation"))
        assertNull(f.released)
    }
    @Test fun noCameraModeCancelsHandoffButStillAwaitsPreflightRelease() {
        val f = ActivationFixture(); f.gateway.active(); f.cameraEnabled = false
        assertTrue(f.controller.activateWalkSessionRuntime())
        assertTrue(f.events.contains("cancelHandoff")); assertFalse(f.events.contains("beginHandoff"))
        assertNotNull(f.released); assertFalse(f.events.contains("startRuntimeAfterRelease"))
    }
}

internal class ActivationFixture {
    val gateway = GatewayFixture(); val resume = ResumeFixture(); val events = mutableListOf<String>()
    var persisted = true; var rejectDetector = false; var latched = false; var monitorBlocked = false
    var environmentReady = true; var cameraEnabled = true
    var detectorStop: ((WalkRuntimeEpoch) -> Unit)? = null; var released: (() -> Unit)? = null
    var queued: (() -> Unit)? = null; var startedEpoch: WalkRuntimeEpoch? = null
    val controller = WalkRuntimeActivationController(
        admission = cleanupPort<WalkActivationAdmission> { name, args -> events += name; when(name) {
            "runtimeActive" -> gateway.lifecycle.snapshot().state == WalkSessionState.ACTIVE
            "runtimeEpochOrNull" -> gateway.lifecycle.currentRuntimeEpochOrNull()
            "snapshot" -> gateway.lifecycle.snapshot()
            "isRuntimeEpochCurrent" -> gateway.lifecycle.isRuntimeEpochCurrent(args[0] as WalkRuntimeEpoch)
            "rejectUnsafeDetector" -> { @Suppress("UNCHECKED_CAST")
                detectorStop = args[2] as (WalkRuntimeEpoch) -> Unit; rejectDetector }
            "beginSafetyEpoch" -> WalkRuntimeSafetyDecision(if (latched) WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED else WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT, args[0] as WalkRuntimeEpoch)
            "stopCleanupExhausted", "developmentBypassEnabled", "permissionBlocksAutomaticStart" -> false
            "isForeground", "navigationOutputsAllowed" -> true
            "nowMs" -> gateway.now
            else -> error(name)
        } },
        readiness = object : WalkResumeReadiness {
            override fun decision() = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "ready")
            override fun blockReason(decision: WalkSafeStartupCapabilityDecision): String? = null
            override fun capture(decision: WalkSafeStartupCapabilityDecision, action: WalkSessionAction, recordResourceDiagnostics: Boolean) = gateway.readiness()
            override fun isConfirmed() = true
        },
        effects = cleanupPort<WalkActivationEffects> { name, args -> events += if (name in setOf("safetyStop", "foregroundRecheck")) "$name:${args[0]}" else name
            if (name == "persistInterruptionMarker") persisted else Unit },
        services = cleanupPort<WalkActivationServices> { name, _ -> events += name; when(name) {
            "resourceMonitoringBlocksReadiness" -> monitorBlocked
            "observeResourceSafety", "activatePhoneMounting" -> true
            "activateOfficialEnvironment" -> environmentReady
            else -> Unit
        } },
        camera = cleanupPort<WalkActivationCamera> { name, args -> events += name; when(name) {
            "analysisFeaturesEnabled" -> cameraEnabled
            "stopEnvironmentPreflight" -> {
                assertEquals(false, args[0])
                @Suppress("UNCHECKED_CAST") val callback = args[1] as () -> Unit
                released = callback
                Unit
            }
            "postAfterRelease" -> {
                @Suppress("UNCHECKED_CAST") val callback = args[0] as () -> Unit
                queued = callback
                Unit
            }
            "startRuntimeAfterRelease" -> { startedEpoch = args[0] as WalkRuntimeEpoch; Unit }
            else -> Unit
        } }, gateway = gateway.controller, resume = resume.controller,
    )
}
