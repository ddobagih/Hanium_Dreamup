package kr.co.hanium.dreamup.walksafe.inference.composite

import java.util.concurrent.CancellationException
import kr.co.hanium.dreamup.walksafe.inference.*
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class AndroidJointRuntimeLoaderTest {
    @Test fun unsavedChoiceUsesItsExactRuntimeAndVerifiesTheAllocatedInstance() {
        val f = Fixture()
        val prepared = f.prepare()
        assertSame(f.choice, prepared.choice)
        assertSame(f.instance, prepared.load.detector)
        assertEquals("DeviceCheckJointRuntimeChoice", prepared.load.reason)
        assertEquals("gpu", f.options!!.delegate)
        assertEquals(3, f.options!!.numThreads)
        assertFalse(f.options!!.fallbackToCpu)
        assertFalse(f.options!!.gpuPrecisionLossAllowed)
        assertTrue(f.options!!.gpuSerializationCacheEnabled)
        assertEquals(1, f.creates); assertEquals(1, f.samples); assertEquals(0, f.closes)
    }

    @Test fun changedBindingOrInvalidEvidenceCannotStartNativePreparation() {
        val f = Fixture()
        assertTrue(runCatching { f.prepare(binding = "b".repeat(64)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { f.prepare(binding = null) }.exceptionOrNull() is IllegalArgumentException)
        val invalid = f.choice.copy(evidence = f.choice.evidence.copy(fallbackUsed = true))
        assertTrue(runCatching { f.prepare(choice = invalid) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, f.creates); assertEquals(0, f.samples); assertEquals(0, f.closes)
    }

    @Test fun cancellationBeforeOrAfterAllocationCannotAdoptOrVerifyTheRetiredChoice() {
        val before = Fixture(); before.current = false
        assertTrue(runCatching { before.prepare() }.exceptionOrNull() is CancellationException)
        assertEquals(0, before.creates)
        val after = Fixture(); after.onCreate = { after.current = false }
        assertTrue(runCatching { after.prepare() }.exceptionOrNull() is CancellationException)
        assertEquals(1, after.creates); assertEquals(0, after.samples); assertEquals(1, after.closes)
        val sampled = Fixture(); sampled.onSample = { sampled.current = false }
        assertTrue(runCatching { sampled.prepare() }.exceptionOrNull() is CancellationException)
        assertEquals(1, sampled.samples); assertEquals(1, sampled.closes)
    }

    @Test fun actualExecutionMismatchClosesTheOwnedModelWithoutAnUnmeasuredFallback() {
        val f = Fixture()
        f.sampleRuntime = f.sampleRuntime.copy(numThreads = 2)
        assertTrue(runCatching { f.prepare() }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, f.creates); assertEquals(1, f.samples); assertEquals(1, f.closes)
        val unavailable = Fixture(); unavailable.available = false
        assertTrue(runCatching { unavailable.prepare() }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, unavailable.creates); assertEquals(0, unavailable.samples); assertEquals(1, unavailable.closes)
    }

    @Test fun failedNativeCloseRemainsFencedBeforeAnotherMemoryPreparation() {
        val f = Fixture()
        f.sampleRuntime = f.sampleRuntime.copy(activeDelegate = "cpu")
        val closeFailure = IllegalStateException("native close")
        f.closeFailure = closeFailure
        val failure = runCatching { f.prepare() }.exceptionOrNull()
        assertTrue(failure is DetectorNativeCleanupFailure)
        assertSame(closeFailure, failure!!.cause!!.suppressed.single())
        assertTrue(runCatching { f.prepare() }.exceptionOrNull() is DetectorNativeCleanupFailure)
        assertEquals(1, f.creates); assertEquals(1, f.closes)
    }

    @Test fun failedReleaseOfACancelledAllocationStillRaisesThePhysicalCleanupFailure() {
        val f = Fixture(); f.onCreate = { f.current = false }
        f.closeFailure = IllegalStateException("cancelled native close")
        val failure = runCatching { f.prepare() }.exceptionOrNull()
        assertTrue(failure is DetectorNativeCleanupFailure)
        assertTrue(failure!!.cause is CancellationException)
        assertTrue(runCatching { f.prepare() }.exceptionOrNull() is DetectorNativeCleanupFailure)
        assertEquals(1, f.creates); assertEquals(1, f.closes)
    }

    private class Fixture {
        val config = JointRuntimeTestFixtures.config
        val choice = JointRuntimeTestFixtures.choice(JointRuntimeTestFixtures.candidate.copy(
            primary = RuntimeCandidate(RuntimeBackend.GPU, 3)))
        val instance = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
        val fence = JointRuntimeReleaseFence()
        var current = true; var available = true
        var creates = 0; var samples = 0; var closes = 0
        var options: ModelRuntimeOptions? = null
        var onCreate: (() -> Unit)? = null; var onSample: (() -> Unit)? = null
        var closeFailure: Throwable? = null
        var sampleRuntime = AndroidDetectorRuntime("gpu", "gpu", 3)
        fun prepare(choice: ValidatedJointRuntimeChoice = this.choice, binding: String? = choice.bindingHash) =
            AndroidJointRuntimeLoader.prepare(config, choice, { current }, binding, 8, true,
                create = { selected, requested ->
                    creates++; options = requested
                    assertNull(selected.fallbackModelKey); assertNull(selected.customTactile); assertNull(selected.cocoGeneral)
                    onCreate?.invoke()
                    AndroidDetectorLoadResult(instance, true, available, config.primaryModelKey, false, "created")
                }, sample = { detector ->
                    assertSame(instance, detector); samples++; onSample?.invoke()
                    AndroidDetectionResult(emptyList(), AndroidDetectorTiming(modelKey = config.primaryModelKey,
                        completedModels = listOf(config.primaryModelKey), modelRuntime = sampleRuntime))
                }, close = { detector -> assertSame(instance, detector); closes++; closeFailure?.let { throw it } }, fence = fence)
    }

    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    }
}
