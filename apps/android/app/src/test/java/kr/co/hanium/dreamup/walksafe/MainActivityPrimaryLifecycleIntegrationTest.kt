package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Main facades, actual lifecycle gate and the same native slot; no removed-field stand-ins. */
class MainActivityPrimaryLifecycleIntegrationTest {
    @Test fun realLifecycleConstructionLeavesSdkContextWatchdogFrameAndProfileStoreLazy() {
        val main = MainActivity()
        val watchdog = field(main, "detectorWatchdogHandler\$delegate") as Lazy<*>
        val frame = field(main, "frameSynthesisHolder") as Lazy<*>
        val profiles = field(main, "deviceTuningStore\$delegate") as Lazy<*>
        assertFalse(watchdog.isInitialized()); assertFalse(frame.isInitialized()); assertFalse(profiles.isInitialized())
        val owner = lifecycle(main)
        assertFalse(owner.metadata().loadAttempted)
        assertEquals("detector=load_pending_after_camera_gate", owner.metadata().statusText)
        assertFalse(watchdog.isInitialized()); assertFalse(frame.isInitialized()); assertFalse(profiles.isInitialized())
    }
    @Test fun actualBeginAndRecordFacadeVerifyOnlyTheSameNativeObservationAndKeepBooleansClosed() {
        val main = MainActivity(); val observation = main.captureRunningDetectorObservation(); val identity = identity(observation.generation)
        main.beginRunningDetectorVerification(observation.instance, 0L)
        assertEquals(RunningDetectorVerificationState.PENDING, lifecycle(main).verificationState(observation.instance))
        assertEquals(RunningDetectorVerificationStep.VERIFIED, main.recordRunningDetectorVerification(observation.instance,
            identity, RunningDetectorLoadEvidence(true, true, true), observation.generation) { 1L })
        assertSame(identity, lifecycle(main).verifiedIdentity())
        assertSame(observation.instance, main.captureRunningDetectorObservation().instance)
        assertFalse(bool(main, "runningDetectorVerifiedForOutput"))
    }
    @Test fun actualStaleRecordFacadeDoesNotEvaluateClockOrReplaceTheVerifiedIdentity() {
        val main = MainActivity(); val observation = main.captureRunningDetectorObservation(); val identity = identity(observation.generation)
        main.beginRunningDetectorVerification(observation.instance, 0L)
        main.recordRunningDetectorVerification(observation.instance, identity, RunningDetectorLoadEvidence(true, true, true), observation.generation) { 1L }
        assertEquals(RunningDetectorVerificationStep.STALE, main.recordRunningDetectorVerification(observation.instance,
            identity(observation.generation + 1L), RunningDetectorLoadEvidence(true, true, true), observation.generation + 1L) { error("stale clock") })
        assertSame(identity, lifecycle(main).verifiedIdentity())
    }
    @Test fun actualFailureReadinessFacadeUsesOwnedGateAndNoRemovedReflectionField() {
        val main = MainActivity(); val observation = main.captureRunningDetectorObservation()
        main.beginRunningDetectorVerification(observation.instance, 0L)
        repeat(3) { attempt -> main.recordRunningDetectorVerification(observation.instance, null,
            RunningDetectorLoadEvidence(true, true, false), observation.generation) { attempt.toLong() } }
        assertEquals(RunningDetectorVerificationState.FAILED, lifecycle(main).verificationState(observation.instance))
        assertTrue(bool(main, "runningDetectorBlocksWalkEntry"))
        assertFalse(bool(main, "runningDetectorVerifiedForOutput"))
        assertFalse(MainActivity::class.java.declaredFields.any { it.name == "runningDetectorVerification" })
    }
    companion object {
        private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        private fun lifecycle(main: MainActivity) = (field(main, "primaryDetectorLifecycleController\$delegate") as Lazy<*>).value as PrimaryDetectorLifecycleController<*>
        private fun bool(main: MainActivity, name: String) = MethodHandles.privateLookupIn(MainActivity::class.java,
            MethodHandles.lookup()).findVirtual(MainActivity::class.java, name, MethodType.methodType(Boolean::class.javaPrimitiveType!!))
            .bindTo(main).invokeWithArguments() as Boolean
        private fun identity(generation: Long) = RunningDetectorIdentity("r7", "unified_walksafe", mapOf("unified_walksafe" to "a".repeat(64)),
            "b".repeat(64), "c".repeat(64), "cpu", false, generation)
    }
}
