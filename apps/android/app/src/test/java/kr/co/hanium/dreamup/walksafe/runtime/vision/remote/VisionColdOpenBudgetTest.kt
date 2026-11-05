package kr.co.hanium.dreamup.walksafe.runtime.vision.remote
import org.junit.Assert.*
import org.junit.Test
class VisionColdOpenBudgetTest {
    private fun spec(backend: String = "gpu") = VisionModelSpec(VisionRole.FASTSAM,
        kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract.MODEL_ID,
        kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract.ASSET,
        kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamModelContract.SHA256,
        VisionRuntimeCodec.encodeOptions(VisionRuntimeOptions(backend, 1)),
        intArrayOf(1,768,768,3), arrayOf(intArrayOf(1,37,12096), intArrayOf(1,192,192,32)))
    @Test fun diagnosticAuxGpuParentClampAllowsFiniteColdExperiment() {
        assertEquals(60_100L, VisionRuntimeProtocol.deadline(300_000, 100, VisionRuntimeProtocol.openBudget(spec(), true)))
    }
    @Test fun nonDebugCpuPrimaryAndNonOptInRetainTwentySeconds() {
        assertEquals(20_000L, VisionRuntimeProtocol.openBudget(spec()))
        assertEquals(20_000L, VisionRuntimeProtocol.openBudget(spec(), false))
        assertEquals(20_000L, VisionRuntimeProtocol.openBudget(spec("cpu"), true))
        assertEquals(20_000L, VisionRuntimeProtocol.openBudget(spec("nnapi"), true))
        assertEquals(20_000L, VisionRuntimeProtocol.openBudget(spec().copy(role = VisionRole.PRIMARY), true))
    }
    @Test fun unpinnedAuxGpuNeverGetsAnExpandedBudget() {
        for (s in listOf(spec().copy(modelKey="wrong"), spec().copy(assetPath="models/wrong.tflite"),
            spec().copy(assetSha256="a".repeat(64)), spec().copy(inputShape=intArrayOf(1,1,1,3)))) {
            assertThrows(IllegalArgumentException::class.java) { VisionRuntimeProtocol.openBudget(s, true) }
        }
    }
    @Test fun callerAndGlobalDeadlineAreNeverExtendedByEitherClamp() {
        val b = VisionRuntimeProtocol.openBudget(spec(),true)
        assertEquals(123L,VisionRuntimeProtocol.deadline(123,100,b))
        val parent = VisionRuntimeProtocol.deadline(300_000,100,b)
        assertEquals(parent,VisionRuntimeProtocol.deadline(parent,200,b))
        assertEquals(300_000L,VisionRuntimeProtocol.deadline(300_000,299_990,b))
        assertEquals(Long.MAX_VALUE,VisionRuntimeProtocol.deadline(Long.MAX_VALUE,Long.MAX_VALUE-1,b))
        assertThrows(IllegalArgumentException::class.java) { VisionRuntimeProtocol.deadline(100,100,b) }
        assertEquals(20_000L,VisionRuntimeProtocol.OPEN_MS)
        assertEquals(2_000L,VisionRuntimeProtocol.operationBudget(VisionPurpose.BENCHMARK))
        assertEquals(15_000L,VisionRuntimeProtocol.operationBudget(VisionPurpose.WARMUP))
        assertEquals(10_000L,VisionRuntimeProtocol.operationBudget(VisionPurpose.REFERENCE))
    }
}
