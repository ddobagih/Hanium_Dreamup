package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.runtime.vision.composition.VisionUnknownComposition
import android.Manifest
import android.content.pm.PackageManager
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.UnknownWalkingSource
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MainActivityVisionUnknownCompositionTest {
    @Test fun coldMainKeepsOriginalUnknownHoldersNativeSdkFramesAndCalibrationCold() {
        val main = MainActivity(); val composition = field(main, "visionUnknownComposition") as VisionUnknownComposition<*>
        assertSame(composition.services, field(main, "unknownServiceOwnerLazy"))
        assertSame(composition.capture, field(main, "unknownCaptureCoordinatorLazy"))
        assertFalse(composition.services.isInitialized()); assertFalse(composition.capture.isInitialized())
        for (name in listOf("frameSynthesisHolder", "frameRecognitionHolder", "deviceTuningStore\$delegate", "cameraReleaseHandler\$delegate"))
            assertFalse(name, (field(main, name) as Lazy<*>).isInitialized())
    }
    @Test fun actualUnknownServiceFirstMaterializesSameNativeLanesWithoutScopeSdkOrCaptureRead() {
        val main = MainActivity(); val composition = field(main, "visionUnknownComposition") as VisionUnknownComposition<*>
        val service = composition.services.value
        assertSame(service, (field(main, "unknownServiceOwnerLazy") as Lazy<*>).value)
        assertFalse(composition.capture.isInitialized())
        assertSame(field(main, "unknownCaptureClock"), field(service, "clock"))
        for (name in listOf("frameSynthesisHolder", "frameRecognitionHolder", "deviceTuningStore\$delegate", "cameraReleaseHandler\$delegate"))
            assertFalse(name, (field(main, name) as Lazy<*>).isInitialized())
    }
    @Test fun actualUnknownCaptureFirstUsesOriginalLockClockAndExactServiceWithoutAuthoritySnapshot() {
        val main = MainActivity(); val composition = field(main, "visionUnknownComposition") as VisionUnknownComposition<*>
        val capture = composition.capture.value
        assertSame(field(main, "frameStateLock"), field(capture, "frameStateLock"))
        assertSame(field(main, "unknownCaptureClock"), field(capture, "clock"))
        assertSame(composition.services.value, field(capture, "services"))
        assertSame(capture, (field(main, "unknownCaptureCoordinatorLazy") as Lazy<*>).value)
        assertFalse((field(main, "frameSynthesisHolder") as Lazy<*>).isInitialized())
    }
    @After fun clearSyntheticSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun actualMainAutomaticEntryKeepsAuxiliaryColdWhenNoRuntimeArSourceExists() {
        val f = WalkingFixture()
        f.invoke("startUnknownRuntimeForWalk")
        assertFalse((field(f.main, "unknownServiceOwnerLazy") as Lazy<*>).isInitialized())
        assertNull(f.capture())
        assertTrue(f.lifecycle.isRuntimeEpochCurrent(f.lifecycle.snapshot().epoch))
    }

    @Test fun actualMainCapturedSourceRechecksPermissionForegroundEpochAndNativeIdentity() {
        for (retire in listOf("permission", "foreground", "epoch", "ar_generation", "cleanup", "primary")) {
            val f = WalkingFixture()
            f.publish()
            val source = requireNotNull(f.capture())
            assertTrue(source.isCurrent())
            when (retire) {
                "permission" -> f.main.cameraGranted = false
                "foreground" -> f.set("isActivityForeground", false)
                "epoch" -> f.lifecycle.handle(WalkSessionEvent.EnteredBackground)
                "ar_generation" -> f.publish()
                "cleanup" -> assertTrue(f.ar.retainClose {})
                "primary" -> f.set("detectorAvailable", false)
            }
            assertFalse(retire, source.isCurrent())
            if (retire != "ar_generation") assertNull(retire, f.capture())
            else assertFalse(source.sameOwner(requireNotNull(f.capture())))
        }
    }

    @Test fun currentArResourceDoesNotRequireTemporaryTrackingOrMetricOutputSuccess() {
        val f = WalkingFixture()
        f.publish()
        // No metric activation or visual tracking evidence was published by this fixture.
        assertNotNull(f.capture())
        val source = requireNotNull(f.capture())
        assertTrue(source.sameOwner(requireNotNull(f.capture())))
        f.ar.publish(f.session, f.provider, MainActivity.ArSessionPurpose.PREFLIGHT)
        assertNull(f.capture())
        assertFalse(source.isCurrent())
    }

    @Test fun actualMainLifecycleFactoriesBindTheResourceChangeToTheAutomaticEntry() {
        val source = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        for (factory in listOf("runtimeCameraLifecycle", "runtimeMetricInvalidation")) {
            val code = ReportStaticSourceInspector.maskNonCode(MainActivityRefactorSource.property(source, factory))
            assertEquals(factory, 1, Regex("sourceChanged\\s*=\\s*::startUnknownRuntimeForWalk").findAll(code).count())
        }
        val start = ReportStaticSourceInspector.maskNonCode(
            ReportStaticSourceInspector.functionBlock(source, "private fun startUnknownRuntimeForWalk"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(start, "captureAutomaticUnknownWalkingSource()",
            "if (source == null && !unknownServiceOwnerLazy.isInitialized()) return",
            "unknownServiceOwner.startForWalk(source,"))
    }

    private class PermissionMain : MainActivity() {
        var cameraGranted = true
        override fun isDestroyed() = false
        override fun checkSelfPermission(permission: String): Int =
            if (permission != Manifest.permission.CAMERA || cameraGranted) PackageManager.PERMISSION_GRANTED
            else PackageManager.PERMISSION_DENIED
    }

    /** Actual Main source factory and owner checks; native resource handles/SDK facts are controlled. */
    private class WalkingFixture {
        val main = PermissionMain()
        val lifecycle = WalkSessionLifecycle(clock = { 1_000L }, elapsedRealtimeClock = { 1_000L })
        private val authority = GatewayWalkAuthorityController(elapsedClock = { 1_000L })
        private val lock = field(main, "runtimeMetricStateLock")!!
        val ar = RuntimeArSessionOwner<Any, Any, MainActivity.ArSessionPurpose>(lock,
            MainActivity.ArSessionPurpose.NONE, {}, {}, RuntimeArSessionCleanupOwnership())
        val session = Any()
        val provider = Any()

        init {
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            val actor = "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            val onboarding = FirstRunOnboardingPolicy.initialEmailAccount().copy(
                stage = FirstRunOnboardingStage.COMPLETE, ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
                completedReceiptHashes = listOf(FirstRunOnboardingStage.VERIFIED_LOGIN,
                    FirstRunOnboardingStage.PURPOSE_AND_SAFETY, FirstRunOnboardingStage.FP004_TRAINING)
                    .associateWith { FirstRunReceiptHash.fromSha256Hex("a".repeat(64)) },
                verifiedActorBinding = FirstRunOpaqueActorBinding.fromProvider(actor))
            val session = GatewayFieldSession.verified("https://gateway.example.test", actor,
                "walksafe_field_session=v2.unknown-source", Long.MAX_VALUE)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(
                requireNotNull(GatewaySessionProcessCoordinator.beginOperation()), session, onboarding))
            set("reporterUserId", actor)
            set("firstRunOnboardingSnapshot", onboarding)
            set("postLoginDeviceCheckSnapshot", PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,
                actor, GatewaySessionProcessCoordinator.snapshot().generation, 1_000L))
            set("isActivityForeground", true)
            set("walkSessionLifecycle", lifecycle)
            set("detectorAvailable", true)
            MainActivity::class.java.getDeclaredField("arSessionOwner\$delegate").apply { isAccessible = true }.set(main, lazyOf(ar))
            MainActivity::class.java.getDeclaredField("gatewayWalkAuthorityController").apply { isAccessible = true }.set(main, authority)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = lifecycle.snapshot().epoch
            val ready = WalkSessionReadinessCollector(epoch, WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            ready.plan.requiredRequirements.forEach {
                ready.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(ready.build(1_000L)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeStart(
                requireNotNull(authority.beginStart(epoch)), GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "GRANTED", actor, "test-device", epoch.walkSessionId, "test-lease", 1L,
                    1_000L, 100_000L, 1_000L, 100_000L))))
        }
        fun set(name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        fun publish() { ar.publish(session, provider, MainActivity.ArSessionPurpose.RUNTIME) }
        fun invoke(name: String): Any? = try {
            MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(main)
        } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
        fun capture() = invoke("captureAutomaticUnknownWalkingSource") as UnknownWalkingSource?
    }

    companion object {
        private fun field(target: Any, name: String) =
            (if (target is MainActivity) MainActivity::class.java else target.javaClass)
                .getDeclaredField(name).apply { isAccessible = true }.get(target)
    }
}
