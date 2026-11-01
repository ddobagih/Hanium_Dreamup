package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeCodec
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrimaryNativeRuntimeMetadataTest {
    private fun observed(delegate: String) = AndroidDetectorRuntime(delegate, delegate, 1,
        gpuPrecisionLossAllowed = false.takeIf { delegate == "gpu" }, modelOwnerPid = 321,
        modelOwnerThreadId = 41, nativeLoadMs = 1.0, nativeRuntimeVersion = "observed-runtime")

    @Test fun theRealNativeEncoderPublishesCpuNonApplicationAndPreservesThePrimaryDtosNull() {
        val json = PrimaryTensorRuntime.encodeObserved(observed("cpu"), VisionRuntimeOptions("cpu", 1, false, false, true), false)
        val value = JSONObject(json)
        assertFalse(value.getBoolean("gpuDelegateAttached")); assertFalse(value.getBoolean("gpuPrecisionLossRequested"))
        assertFalse(value.getBoolean("gpuPrecisionLossAllowed")); assertTrue(value.getBoolean("gpuSerializationCacheRequested"))
        assertFalse(value.getBoolean("gpuSerializationCacheConfigured"))
        assertNull(PrimaryTensorRuntime.decode(json).gpuPrecisionLossAllowed)
        assertEquals("observed-runtime", PrimaryTensorRuntime.decode(json).nativeRuntimeVersion)
        val requestedButNotApplied = JSONObject(PrimaryTensorRuntime.encodeObserved(observed("cpu"),
            VisionRuntimeOptions("cpu", 1, false, true, true), false))
        assertTrue(requestedButNotApplied.getBoolean("gpuPrecisionLossRequested"))
        assertFalse(requestedButNotApplied.getBoolean("gpuPrecisionLossAllowed"))
    }

    @Test fun gpuAppliedFlagsNeedTheSuccessfulNativeHandleAndCannotBeInventedFromRequestedOptions() {
        val request = VisionRuntimeOptions("gpu", 1, false, false, true)
        assertTrue(runCatching { PrimaryTensorRuntime.encodeObserved(observed("gpu"), request, false) }.isFailure)
        assertTrue(runCatching { PrimaryTensorRuntime.encodeObserved(observed("gpu").copy(gpuPrecisionLossAllowed = null), request, true) }.isFailure)
        assertTrue(runCatching { PrimaryTensorRuntime.encodeObserved(observed("gpu").copy(fallbackUsed = true), request, true) }.isFailure)
        val value = JSONObject(PrimaryTensorRuntime.encodeObserved(observed("gpu").copy(gpuSerializationCacheStatus = "disabled"), request, true))
        assertTrue(value.getBoolean("gpuDelegateAttached")); assertFalse(value.getBoolean("gpuPrecisionLossAllowed"))
        assertTrue(value.getBoolean("gpuSerializationCacheRequested")); assertFalse(value.getBoolean("gpuSerializationCacheConfigured"))
    }

    @Test fun missingGpuPrecisionAndImpossibleCpuPrecisionAreNotDefaultedToSafeValues() {
        val request = VisionRuntimeOptions("gpu", 1, false, false, true)
        val value = JSONObject(PrimaryTensorRuntime.encodeObserved(observed("gpu"), request, true))
        value.remove("gpuPrecisionLossAllowed")
        assertTrue(runCatching { PrimaryTensorRuntime.decode(value.toString()) }.isFailure)
        val cpu = JSONObject(PrimaryTensorRuntime.encodeObserved(observed("cpu"), request.copy(delegate = "cpu"), false))
            .put("gpuPrecisionLossAllowed", true)
        assertTrue(runCatching { PrimaryTensorRuntime.decode(cpu.toString()) }.isFailure)
    }
}
