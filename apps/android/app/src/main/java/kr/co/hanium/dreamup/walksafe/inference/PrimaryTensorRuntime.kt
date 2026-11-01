package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionModelHandle
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeCodec

internal object PrimaryTensorRuntime {
    fun decode(json: String, handle: VisionModelHandle? = null): AndroidDetectorRuntime {
        val value = VisionRuntimeCodec.objectJson(VisionRuntimeCodec.validateRuntimeJson(json))
        fun optionalText(name: String): String? = if (!value.has(name) || value.isNull(name)) null else value.getString(name)
        val pid = value.getLong("modelOwnerPid").also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
        return AndroidDetectorRuntime(value.getString("requestedDelegate"), value.getString("activeDelegate"),
            value.getInt("numThreads"), value.getBoolean("fallbackUsed"), optionalText("fallbackReason"),
            if (value.isNull("gpuPrecisionLossAllowed")) null else value.getBoolean("gpuPrecisionLossAllowed"),
            optionalText("gpuSerializationCacheStatus"), optionalText("gpuSerializationCacheToken"),
            optionalText("gpuSerializationCacheFailureReason"), pid, value.getLong("modelOwnerThreadId"),
            handle?.modelInstanceId, handle?.cohortId, value.getDouble("loadMs"), optionalText("nativeRuntimeVersion"))
    }
}
