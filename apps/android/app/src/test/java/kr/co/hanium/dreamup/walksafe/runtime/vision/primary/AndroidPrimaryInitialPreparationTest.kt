package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.util.concurrent.CancellationException
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.composite.AndroidJointRuntimeLoader
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeTestFixtures
import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeReleaseFence
import org.junit.Assert.*
import org.junit.Test

class AndroidPrimaryInitialPreparationTest {
    private val config = JointRuntimeTestFixtures.config
    private fun initialLoad() = AndroidDetectorLoadResult(null, true, true,
        TwoModelRuntimeConfig.UNIFIED_MODEL_KEY, false, "native-created")

    @Test fun evenExplicitComparisonOnlyPreparesCpuUntilTheIsolatedJointCheckHasAChoice() {
        for (compare in listOf(false, true)) {
            val requested = mutableListOf<ModelRuntimeOptions>()
            var restores = 0
            val platform = AndroidPrimaryLifecyclePlatform({ error("SDK access must stay injected") },
                { _, current -> assertTrue(current()); restores++; null },
                { actualConfig, options -> assertSame(config, actualConfig); requested += options; initialLoad() }, { 8 })
            val result = platform.select(config, compare, { true }, {})
            assertEquals(if (compare) 0 else 1, restores)
            assertEquals(1, requested.size)
            assertEquals("cpu", requested.single().delegate)
            assertEquals(2, requested.single().numThreads)
            assertFalse(requested.single().fallbackToCpu)
            assertFalse(requested.single().gpuSerializationCacheEnabled)
            assertEquals(AndroidPrimaryLifecyclePlatform.SAFE_CPU_PREPARATION_REASON, result.reason)
            assertNotEquals("DeviceCheckJointRuntimeChoice", result.reason)
        }
    }

    @Test fun measuredChoiceIsReturnedWithoutStartingAnotherCpuOrGpuSearch() {
        val choice = JointRuntimeTestFixtures.choice()
        val native = initialLoad().copy(reason = "DeviceCheckJointRuntimeChoice")
        val platform = AndroidPrimaryLifecyclePlatform({ error("SDK access must stay injected") },
            { _, _ -> AndroidJointRuntimeLoader.Prepared(native, choice) },
            { _, _ -> error("A measured joint choice must not start another search") }, { 8 })
        assertSame(native, platform.select(config, false, { true }, {}))
    }

    @Test fun explicitDeviceRecheckSkipsEvenAnExistingMeasuredGpuChoiceBeforeIsolatedTesting() {
        val measured = AndroidJointRuntimeLoader.Prepared(
            initialLoad().copy(reason = "DeviceCheckJointRuntimeChoice"), JointRuntimeTestFixtures.choice())
        var restoreCalls = 0
        val requested = mutableListOf<ModelRuntimeOptions>()
        val platform = AndroidPrimaryLifecyclePlatform({ error("SDK access must stay injected") },
            { _, _ -> restoreCalls++; measured }, { _, options -> requested += options; initialLoad() }, { 8 })
        val load = platform.select(config, true, { true }, {})
        assertEquals(0, restoreCalls)
        assertEquals("cpu", requested.single().delegate)
        assertEquals(2, requested.single().numThreads)
        assertFalse(requested.single().fallbackToCpu)
        assertEquals(AndroidPrimaryLifecyclePlatform.SAFE_CPU_PREPARATION_REASON, load.reason)
    }

    @Test fun failedMeasuredRestoreNeverFallsThroughToAnUnmeasuredRuntime() {
        val failure = IllegalStateException("native release unconfirmed")
        val platform = AndroidPrimaryLifecyclePlatform({ error("SDK access must stay injected") },
            { _, _ -> throw failure }, { _, _ -> error("Failure must retain the native fence") }, { 8 })
        assertSame(failure, runCatching { platform.select(config, false, { true }, {}) }.exceptionOrNull())
    }

    @Test fun cleanupFailureResultBecomesTypedAndBlocksEveryLaterAllocationOnTheSameFence() {
        val fence = JointRuntimeReleaseFence()
        var allocations = 0
        var restores = 0
        val failedLoad = AndroidDetectorLoadResult(null, false, false, null, false, "DetectorNativeCleanupFailure")
        val platform = AndroidPrimaryLifecyclePlatform({ error("SDK access must stay injected") },
            { _, _ -> restores++; null }, { _, _ -> allocations++; failedLoad }, { 8 }, fence)
        val first = runCatching { platform.loadDefault() }.exceptionOrNull()
        assertTrue(first is DetectorNativeCleanupFailure)
        assertEquals(1, allocations)
        assertTrue(runCatching { platform.select(config, true, { true }, {}) }.exceptionOrNull() is DetectorNativeCleanupFailure)
        assertEquals(0, restores)
        assertEquals(1, allocations)

        val nextOwner = AndroidPrimaryLifecyclePlatform({ error("SDK access must stay injected") },
            { _, _ -> error("The next owner must wait for actual native cleanup") },
            { _, _ -> error("A failed previous cleanup cannot be retried as CPU") }, { 8 }, fence)
        assertTrue(runCatching { nextOwner.loadDefault() }.exceptionOrNull() is DetectorNativeCleanupFailure)
    }

    @Test fun typedInitialCleanupFailureAlsoRemainsFencedBeforeRetry() {
        val fence = JointRuntimeReleaseFence()
        var allocations = 0
        val failure = DetectorNativeCleanupFailure(IllegalStateException("native close failed"))
        val platform = AndroidPrimaryLifecyclePlatform({ error("SDK access must stay injected") },
            { _, _ -> null }, { _, _ -> allocations++; throw failure }, { 8 }, fence)
        assertSame(failure, runCatching { platform.loadDefault() }.exceptionOrNull())
        assertTrue(runCatching { platform.loadDefault() }.exceptionOrNull() is DetectorNativeCleanupFailure)
        assertEquals(1, allocations)
    }

    @Test fun ordinaryUnavailableResultDoesNotInventAnUnconfirmedCleanupFence() {
        val fence = JointRuntimeReleaseFence()
        var allocations = 0
        val failure = AndroidDetectorLoadResult(null, true, false, null, false, "ModelUnavailable")
        val platform = AndroidPrimaryLifecyclePlatform({ error("SDK access must stay injected") },
            { _, _ -> null }, { _, _ -> allocations++; failure }, { 8 }, fence)
        assertSame(failure, platform.loadDefault())
        assertSame(failure, platform.loadDefault())
        assertEquals(2, allocations)
    }

    @Test fun retiredRequestCannotStartInitialPreparationAndUnknownConfigStillUsesBoundedCpu() {
        val requested = mutableListOf<ModelRuntimeOptions>()
        val platform = AndroidPrimaryLifecyclePlatform({ error("SDK access must stay injected") },
            { _, _ -> null }, { actualConfig, options ->
                assertNull(actualConfig); requested += options; initialLoad()
            }, { 1 })
        assertTrue(runCatching { platform.select(config, true, { false }, {}) }.exceptionOrNull() is CancellationException)
        assertTrue(requested.isEmpty())
        platform.loadDefault()
        assertEquals("cpu", requested.single().delegate)
        assertEquals(1, requested.single().numThreads)
        assertFalse(requested.single().fallbackToCpu)
    }
}
