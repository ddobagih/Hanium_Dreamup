package kr.co.hanium.dreamup.walksafe.inference

import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionNativeEngine
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose

/** Compatibility entry for the disposable fixture child; production parents always use remote(). */
internal class LocalPrimaryTensorFactory(private val context: Context) : PrimaryTensorEngineFactory {
    private val release = PrimaryTensorRelease()
    override val actualReleasedFuture get() = release.actualReleasedFuture
    override val requiresOwnerThread = true
    private var closed = false
    private val models = mutableListOf<LocalEngine>()

    override fun open(model: ModelRuntimeConfig, delegate: String, recorder: DetectorLoadTimingRecorder,
        modelKey: String): PrimaryTensorEngine {
        check(!closed) { "local primary factory is closed" }
        val slot = release.beforeOpen()
        try {
            val engine = LocalEngine(PrimaryTensorEngines.createLocal(context, PrimaryTensorEngines.spec(model, delegate, modelKey)))
            release.follow(slot, engine.released)
            models += engine
            return engine
        } catch (error: Throwable) {
            // createLocal's Interpreter cleanup preserves an uncertain close as this typed failure.
            if (error !is DetectorNativeCleanupFailure) release.follow(slot, CompletableFuture.completedFuture(Unit))
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        models.forEach {
            try { it.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        release.seal()
        failure?.let { throw it }
    }

    private class LocalEngine(private val native: VisionNativeEngine) : PrimaryTensorEngine {
        val released = CompletableFuture<Unit>()
        private var closed = false
        override val runtime get() = PrimaryTensorRuntime.decode(native.runtimeJson)
        override fun invoke(input: ByteBuffer, output: ByteBuffer, width: Int, height: Int,
            purpose: VisionPurpose): PrimaryTensorResult {
            check(!closed) { "local primary model is closed" }
            val result = native.invoke(input, arrayOf(output), width, height)
            return PrimaryTensorResult(PrimaryTensorRuntime.decode(result.runtimeJson), result.inferenceMs.toLong(), result.outputReadMs.toLong())
        }
        override fun close() {
            if (closed) return
            closed = true
            native.close()
            released.complete(Unit)
        }
    }
}
