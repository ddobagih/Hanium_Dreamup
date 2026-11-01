package kr.co.hanium.dreamup.walksafe.inference

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.RemoteVisionClient
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionModelSpec
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionNativeEngine
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRole
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeProtocol
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeCodec
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeOptions
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionRuntimeContext

/** The local entry point is only for authenticated child native owners and the temporary fixture child. */
object PrimaryTensorEngines {
    internal fun remote(context: Context, deadlineMs: Long): PrimaryTensorEngineFactory {
        val ownerId = VisionRuntimeContext.ownerId(context)
        return RemotePrimaryTensorFactory(AndroidPrimaryTensorRemotePort(RemoteVisionClient.forMainProcess(context), ownerId),
            SystemClock::elapsedRealtime, deadlineMs)
    }

    internal fun local(context: Context): PrimaryTensorEngineFactory {
        requireLocalAuthority(context)
        return LocalPrimaryTensorFactory(context.applicationContext)
    }

    internal fun spec(model: ModelRuntimeConfig, delegate: String, modelKey: String): VisionModelSpec = VisionModelSpec(
        VisionRole.PRIMARY, modelKey, model.asset, requireNotNull(model.artifactSha256) { "primary model SHA-256 is required" },
        VisionRuntimeCodec.encodeOptions(VisionRuntimeOptions(delegate, model.runtime.numThreads, false,
            model.runtime.gpuPrecisionLossAllowed, model.runtime.gpuSerializationCacheEnabled)),
        intArrayOf(1, model.inputSize, model.inputSize, 3), arrayOf(model.outputTensorShape()))
        .also(VisionRuntimeProtocol::validateSpec)

    @JvmStatic fun createLocal(context: Context, spec: VisionModelSpec): VisionNativeEngine {
        requireLocalAuthority(context)
        VisionRuntimeProtocol.validateSpec(spec)
        require(spec.role == VisionRole.PRIMARY && spec.outputShapes.size == 1) { "primary_role_or_output_contract" }
        val config = TwoModelRuntimeConfig.load(context)
        val model = listOfNotNull(config.unifiedWalksafe, config.customTactile, config.cocoGeneral, config.personSupplement)
            .singleOrNull { it.key == spec.modelKey } ?: error("primary_model_not_pinned")
        require(model.enabled && model.asset == spec.assetPath && model.artifactSha256 == spec.assetSha256 &&
            spec.inputShape.contentEquals(intArrayOf(1, model.inputSize, model.inputSize, 3)) &&
            spec.outputShapes.single().contentEquals(model.outputTensorShape())) { "primary_model_contract_differs" }
        return LocalPrimaryTensorEngine(context.applicationContext, spec)
    }

    internal fun requireLocalAuthority(context: Context) = PrimaryTensorProcessPolicy.requireLocal(
        Build.VERSION.SDK_INT, Application.getProcessName(), context.packageName)
}

internal object PrimaryTensorProcessPolicy {
    fun requireLocal(sdk: Int, processName: String, packageName: String) {
        check(sdk >= 31) { "vision_requires_api31" }
        check(processName == packageName + VisionRuntimeProtocol.PROCESS_SUFFIX ||
            processName == "$packageName:fixture_runtime") { "primary_native_creation_requires_child_process" }
    }
}
