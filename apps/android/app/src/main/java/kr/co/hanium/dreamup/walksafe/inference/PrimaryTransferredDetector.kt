package kr.co.hanium.dreamup.walksafe.inference

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*

/** One-use data only: no facade, frame, Context, listener, executor or physical-release future. */
class PrimaryTransferredDetector internal constructor(
    val oldLease: VisionCohortLease,
    specs: List<VisionModelSpec>, handles: List<VisionModelHandle>,
) {
    private val pinnedSpecs = specs.map(::copySpec)
    val oldHandles: List<VisionModelHandle> = Collections.unmodifiableList(handles.toList())
    private val consumed = AtomicBoolean()
    val isConsumed get() = consumed.get()

    internal fun boundModels(config: TwoModelRuntimeConfig): List<ModelRuntimeConfig> = pinnedSpecs.map { spec ->
        val model = listOfNotNull(config.unifiedWalksafe, config.personSupplement, config.customTactile, config.cocoGeneral)
            .single { it.key == spec.modelKey }.also { require(it.enabled) }
        val options = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
        model.copy(runtime = ModelRuntimeOptions(options.delegate, options.numThreads, false,
            options.gpuPrecisionLossAllowed, options.gpuSerializationCacheEnabled)).also {
            require(sameSpec(spec, PrimaryTensorEngines.spec(it, options.delegate, spec.modelKey)))
        }
    }

    init {
        require(pinnedSpecs.size in 1..2 && oldHandles.size == pinnedSpecs.size)
        require(pinnedSpecs.map { it.modelKey }.toSet().size == pinnedSpecs.size)
        require(oldHandles.map { it.modelInstanceId }.toSet().size == oldHandles.size)
        require(oldHandles.map { it.modelKey }.toSet() == pinnedSpecs.map { it.modelKey }.toSet())
        oldHandles.forEach { handle ->
            require(handle.role == VisionRole.PRIMARY && handle.cohortId == oldLease.cohortId &&
                handle.ownerEpoch == oldLease.ownerEpoch)
            val spec = pinnedSpecs.single { it.modelKey == handle.modelKey }
            require(spec.role == VisionRole.PRIMARY)
            requireRuntime(spec, handle)
        }
    }

    internal fun claimForAttach(expected: List<VisionModelSpec>, nextLease: VisionCohortLease,
        nextHandles: List<VisionModelHandle>): PrimaryTransferredAttachment {
        require(nextLease.cohortId == oldLease.cohortId && oldLease.ownerEpoch < Long.MAX_VALUE &&
            nextLease.ownerEpoch == oldLease.ownerEpoch + 1L) { "primary transfer requires the next epoch" }
        val primary = nextHandles.filter { it.role == VisionRole.PRIMARY }
        require(primary.size == oldHandles.size && primary.map { it.modelKey }.toSet() == oldHandles.map { it.modelKey }.toSet() &&
            primary.map { it.modelInstanceId }.toSet().size == primary.size)
        require(expected.size == pinnedSpecs.size && expected.map { it.modelKey }.toSet() == pinnedSpecs.map { it.modelKey }.toSet())
        expected.forEach { spec -> require(sameSpec(pinnedSpecs.single { it.modelKey == spec.modelKey }, spec)) }
        primary.forEach { handle ->
            val old = oldHandles.single { it.modelKey == handle.modelKey }
            require(handle.cohortId == nextLease.cohortId && handle.ownerEpoch == nextLease.ownerEpoch &&
                handle.modelInstanceId == old.modelInstanceId) { "primary transfer changed model identity" }
            require(PrimaryTensorRuntime.decode(handle.runtimeJson, handle) == PrimaryTensorRuntime.decode(old.runtimeJson, old)) {
                "primary transfer changed its actual runtime"
            }
            requireRuntime(pinnedSpecs.single { it.modelKey == handle.modelKey }, handle)
        }
        check(consumed.compareAndSet(false, true)) { "primary transfer token already consumed" }
        return PrimaryTransferredAttachment(nextLease, pinnedSpecs.map(::copySpec), primary.toList())
    }

    private fun requireRuntime(spec: VisionModelSpec, handle: VisionModelHandle) {
        val options = VisionRuntimeCodec.decodeOptions(spec.runtimeJson)
        PrimaryTensorContract.observedRuntime(PrimaryTensorRuntime.decode(handle.runtimeJson, handle),
            AndroidDetectorRuntime(options.delegate, options.delegate, options.numThreads,
                gpuPrecisionLossAllowed = options.gpuPrecisionLossAllowed.takeIf { options.delegate == "gpu" }))
    }

    private fun sameSpec(a: VisionModelSpec, b: VisionModelSpec) = a.role == b.role && a.modelKey == b.modelKey &&
        a.assetPath == b.assetPath && a.assetSha256 == b.assetSha256 &&
        VisionRuntimeCodec.decodeOptions(a.runtimeJson) == VisionRuntimeCodec.decodeOptions(b.runtimeJson) &&
        a.inputShape.contentEquals(b.inputShape) && a.outputShapes.size == b.outputShapes.size &&
        a.outputShapes.indices.all { a.outputShapes[it].contentEquals(b.outputShapes[it]) }

    companion object {
        private fun copySpec(spec: VisionModelSpec) = spec.copy(inputShape = spec.inputShape.copyOf(),
            outputShapes = spec.outputShapes.map { it.copyOf() }.toTypedArray())
    }
}

internal data class PrimaryTransferredAttachment(val lease: VisionCohortLease,
    val specs: List<VisionModelSpec>, val handles: List<VisionModelHandle>)
