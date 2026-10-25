package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import kr.co.hanium.dreamup.walksafe.runtime.vision.composition.VisionPrimaryComposition
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.*
import org.junit.Assert.*
import org.junit.Test

/** Actual private composed Main, actual unique Lazy references; no fake deleted fields. */
class MainActivityVisionPrimaryCompositionTest {
    @Test fun actualMainConstructionLeavesLateLockControllersHandlersFrameAndProfileStoreCold() {
        val main = MainActivity(); val composition = field(main, "visionPrimaryComposition") as VisionPrimaryComposition
        assertFalse(composition.detector.isInitialized()); assertFalse(composition.calibration.isInitialized())
        assertFalse(composition.scheduling.isInitialized()); assertFalse(composition.invocation.isInitialized())
        for (name in listOf("detectorWatchdogHandler\$delegate", "cameraReleaseHandler\$delegate", "deviceTuningStore\$delegate", "frameSynthesisHolder"))
            assertFalse(name, (field(main, name) as Lazy<*>).isInitialized())
    }
    @Test fun actualMainFacadeDelegatesAreExactActualHoldersAndShareTheOriginalLockAndNativeOwner() {
        val main = MainActivity(); val composition = field(main, "visionPrimaryComposition") as VisionPrimaryComposition
        assertSame(composition.detector, delegate(main, "PrimaryDetectorOwner"))
        assertSame(composition.calibration, delegate(main, "PrimaryRuntimeCalibrationOwner"))
        assertSame(composition.scheduling, delegate(main, "PrimaryDetectorFrameSchedulingOwner"))
        assertSame(composition.invocation, delegate(main, "PrimaryDetectorInvocationWorker"))
        val native = composition.detector.value
        assertSame(field(main, "frameStateLock"), field(native, "stateLock"))
        assertSame(native, field(composition.calibration.value, "detectorOwner"))
        assertSame(native, field(composition.scheduling.value, "detectorOwner"))
        assertSame(native, field(composition.invocation.value, "detectorOwner"))
    }
    @Test fun actualLifecycleFirstReadReusesCompositionAndDoesNotInitializeSdkFactoryHandlersOrFrame() {
        val main = MainActivity(); val composition = field(main, "visionPrimaryComposition") as VisionPrimaryComposition
        val life = (field(main, "primaryDetectorLifecycleController\$delegate") as Lazy<*>).value as PrimaryDetectorLifecycleController<*>
        assertFalse(life.metadata().loadAttempted); assertTrue(composition.detector.isInitialized())
        assertTrue(composition.calibration.isInitialized()); assertFalse(composition.invocation.isInitialized())
        for (name in listOf("visionCalibrationSessionFactory\$delegate", "detectorWatchdogHandler\$delegate", "cameraReleaseHandler\$delegate", "frameSynthesisHolder"))
            assertFalse(name, (field(main, name) as Lazy<*>).isInitialized())
    }
    companion object {
        private fun delegate(main: MainActivity, name: String): Any? {
            val property = name.replaceFirstChar { it.lowercase() }
            val backing = try { MainActivity::class.java.getDeclaredField(property + "\$delegate") } catch (_: NoSuchFieldException) { null }
            if (backing != null) return backing.apply { isAccessible = true }.get(main)
            return MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findStatic(MainActivity::class.java, "get" + name + "\$delegate",
                    MethodType.methodType(Any::class.java, MainActivity::class.java)).invokeWithArguments(main)
        }
        private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) }
}
