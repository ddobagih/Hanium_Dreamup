package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionModelHandle
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeCodec
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeOptions
import org.json.JSONObject

internal object PrimaryTensorRuntime {
    fun decode(json: String, handle: VisionModelHandle? = null): AndroidDetectorRuntime {
        val value = VisionRuntimeCodec.objectJson(VisionRuntimeCodec.validateRuntimeJson(json))
        fun optionalText(name: String): String? = if (!value.has(name) || value.isNull(name)) null else value.getString(name)
        val pid = value.getLong("modelOwnerPid").also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
        val active = value.getString("activeDelegate")
        val precision = if (active == "gpu") value.getBoolean("gpuPrecisionLossAllowed") else {
            require(value.isNull("gpuPrecisionLossAllowed") || !value.getBoolean("gpuPrecisionLossAllowed"))
            null // The public primary DTO preserves CPU/NNAPI's existing not-applicable value.
        }
        return AndroidDetectorRuntime(value.getString("requestedDelegate"), value.getString("activeDelegate"),
            value.getInt("numThreads"), value.getBoolean("fallbackUsed"), optionalText("fallbackReason"),
            precision,
            optionalText("gpuSerializationCacheStatus"), optionalText("gpuSerializationCacheToken"),
            optionalText("gpuSerializationCacheFailureReason"), pid, value.getLong("modelOwnerThreadId"),
            handle?.modelInstanceId, handle?.cohortId, value.getDouble("loadMs"), optionalText("nativeRuntimeVersion"))
    }

    /** The native opener supplies both its applied handle and its explicitly decoded options.
     * Missing wire fields are never filled from requested settings by a parent consumer. */
    fun encodeObserved(value: AndroidDetectorRuntime, options: VisionRuntimeOptions, gpuDelegateAttached: Boolean): String {
        require(value.requestedDelegate == options.delegate && value.activeDelegate == options.delegate &&
            value.numThreads == options.numThreads && !value.fallbackUsed && !options.fallbackToCpu &&
            gpuDelegateAttached == (value.activeDelegate == "gpu"))
        val appliedPrecision = if (gpuDelegateAttached) requireNotNull(value.gpuPrecisionLossAllowed).also {
            require(it == options.gpuPrecisionLossAllowed)
        } else { require(value.gpuPrecisionLossAllowed != true); false }
        return VisionRuntimeCodec.validateRuntimeJson(JSONObject()
            .put("requestedDelegate", value.requestedDelegate).put("activeDelegate", value.activeDelegate)
            .put("numThreads", value.numThreads).put("fallbackUsed", value.fallbackUsed)
            .put("fallbackReason", value.fallbackReason ?: JSONObject.NULL)
            .put("gpuDelegateAttached", gpuDelegateAttached)
            .put("gpuPrecisionLossRequested", options.gpuPrecisionLossAllowed)
            .put("gpuPrecisionLossAllowed", appliedPrecision)
            .put("gpuSerializationCacheRequested", options.gpuSerializationCacheEnabled)
            .put("gpuSerializationCacheConfigured", gpuDelegateAttached && value.gpuSerializationCacheStatus == "configured")
            .put("gpuSerializationCacheStatus", value.gpuSerializationCacheStatus ?: JSONObject.NULL)
            .put("gpuSerializationCacheToken", value.gpuSerializationCacheToken ?: JSONObject.NULL)
            .put("gpuSerializationCacheFailureReason", value.gpuSerializationCacheFailureReason ?: JSONObject.NULL)
            .put("modelOwnerPid", value.modelOwnerPid).put("modelOwnerThreadId", value.modelOwnerThreadId)
            .put("nativeRuntimeVersion", value.nativeRuntimeVersion ?: JSONObject.NULL)
            .put("loadMs", value.nativeLoadMs).toString())
    }
}
