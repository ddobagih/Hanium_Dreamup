package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.*
import org.json.JSONObject
import org.junit.Assert.*
import sun.misc.Unsafe
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Actual Java sample/decode/consumer service, with only the native and Android SDK effects supplied. */
internal class JointRuntimeSampleTestFixture(val name: String,
    val ledger: JointRuntimeMemoryLedger = JointRuntimeMemoryLedger(),
    val bundle: JointRuntimeSampleFixtures = sampleFixtures()) : JointRuntimeSamplePorts<Unit>, Closeable {
    val clock = AtomicLong(1_000L)
    val config = JointRuntimeTestFixtures.config
    val primaryPhysical = CompletableFuture<Unit>()
    val nativePhysical = CompletableFuture<Unit>()
    val auxiliaryFactory = JointPairTestAuxiliaryFactory()
    val detector = unsafe.allocateInstance(TfliteAndroidFrameDetector::class.java) as TfliteAndroidFrameDetector
    val lease = VisionCohortLease(hash(name), "sample-$name", 1)
    val primaryId = "primary-$name"
    val auxiliaryId = "auxiliary-$name"
    var optionsPrimary = ModelRuntimeOptions("cpu", 1, false)
    var optionsAuxiliary: FastSamRuntimeOptions? = null
    val primaryCalls = AtomicInteger()
    var primaryCreates = 0
    var primaryCloses = 0
    var begins = 0
    var exits = 0
    var nativeCurrent = true
    var current = true
    var holdNative = false
    var holdPrimary = false
    var primaryLoadFailure: RuntimeException? = null
    var beginFailure: Throwable? = null
    var expectUnconfirmedBegin = false
    var primaryMismatch = false
    var corruptHandle = false
    var omitPrimaryRuntimeField: String? = null
    var referenceDelayMs = 0L
    var inputDigest = "b".repeat(64)
    var corruptFixture: String? = null
    var onPrimarySample: (() -> Unit)? = null
    val primaryRequests = CopyOnWriteArrayList<Pair<VisionPurpose, Long>>()
    var auxiliary: FastSamRuntimeService<Unit>? = null
    private val hosts = mutableListOf<JointRuntimeSampleHost<Unit>>()
    val liveResults = AtomicInteger()
    var heapObservation: () -> JointRuntimeManagedHeapSnapshot = {
        JointRuntimeManagedHeapSnapshot(512L * 1024 * 1024, 128L * 1024 * 1024, 96L * 1024 * 1024)
    }

    init { auxiliaryFactory.cohortId = lease.cohortId; auxiliaryFactory.modelId = auxiliaryId }
    fun budget(phase: JointRuntimeSearchPhase, amountMs: Long = 90_000L) =
        JointRuntimePhaseBudget(phase, clock.get(), clock.get() + amountMs)
    fun host(): JointRuntimeSampleHost<Unit> = JointRuntimeSampleHost(config, JointRuntimeTestFixtures.binding, 8,
        bundle, ledger, 7, object : FastSamRuntimeService.Listener<Unit> {
            override fun onResult(result: FastSamResult<Unit>) { liveResults.incrementAndGet() }
        }, this, clock::get, { amount -> clock.addAndGet(amount); Thread.sleep(1) },
        heapSnapshot = { heapObservation() }).also(hosts::add)
    override fun begin(absoluteDeadlineMs: Long): CompletionStage<Unit> { begins++; beginFailure?.let { throw it }; return nativePhysical }
    override fun ownerBinding() = DeviceCheckAuthorityBinding("f".repeat(64), "e".repeat(64), JointRuntimeTestFixtures.binding, "b".repeat(64))
    override fun createPrimary(config: TwoModelRuntimeConfig, options: ModelRuntimeOptions, deadlineMs: Long): AndroidDetectorLoadResult {
        primaryCreates++; primaryLoadFailure?.let { throw it }; optionsPrimary = options
        assertFalse(options.fallbackToCpu); assertNull(config.personSupplement)
        return AndroidDetectorLoadResult(detector, true, true, config.primaryModelKey, false, "created", actualReleasedFuture = primaryPhysical)
    }
    fun primaryRuntime() = AndroidDetectorRuntime(optionsPrimary.delegate, optionsPrimary.delegate, optionsPrimary.numThreads,
        gpuPrecisionLossAllowed = false, modelOwnerPid = 321, modelOwnerThreadId = 41,
        modelInstanceId = if (primaryMismatch) "unexpected-primary" else primaryId, cohortId = lease.cohortId)
    override fun primarySample(model: TfliteAndroidFrameDetector, fixture: JointRuntimeSampleFixture,
        purpose: VisionPurpose, deadlineMs: Long, raw: Boolean): JointPairPrimarySample {
        assertSame(detector, model); primaryCalls.incrementAndGet(); primaryRequests += purpose to deadlineMs
        onPrimarySample?.invoke()
        clock.addAndGet(if (purpose == VisionPurpose.REFERENCE && referenceDelayMs > 0) referenceDelayMs else 20)
        return JointPairPrimarySample(AndroidCalibrationDetectionResult(AndroidDetectionResult(emptyList(),
            AndroidDetectorTiming(modelKey = config.primaryModelKey, completedModels = listOf(config.primaryModelKey),
                modelRuntime = primaryRuntime(), modelInferenceMs = 20, modelParseMs = 0)),
            if (raw) FloatArray(1800).also { if (fixture.definition.id == corruptFixture) it[0] = 100f } else null), inputDigest)
    }
    override fun createAuxiliary(epoch: Long, options: FastSamRuntimeOptions, listener: FastSamRuntimeService.Listener<Unit>): FastSamRuntimeService<Unit> {
        optionsAuxiliary = options
        return auxiliaryFactory.create(epoch, options, listener, { clock.get() * 1_000_000L }).also { auxiliary = it }
    }
    override fun closePrimary(model: TfliteAndroidFrameDetector) {
        assertSame(detector, model); primaryCloses++; if (!holdPrimary) primaryPhysical.complete(Unit)
    }
    override fun retire(reason: String) { exits++; nativeCurrent = false; if (!holdNative) nativePhysical.complete(Unit) }
    override fun samplingDrained() {}
    override fun thermal() = 0
    private fun runtimeJson(primary: Boolean): String {
        val delegate = if (primary) optionsPrimary.delegate else requireNotNull(optionsAuxiliary).requestedBackend.name.lowercase()
        val threads = if (primary) optionsPrimary.numThreads else requireNotNull(optionsAuxiliary).numThreads
        val json = if (primary) JSONObject(PrimaryTensorRuntime.encodeObserved(primaryRuntime().copy(
            gpuSerializationCacheStatus = if (delegate == "gpu") "configured" else null,
            gpuSerializationCacheToken = if (delegate == "gpu") "native-test-token" else null,
            nativeLoadMs = 1.0, nativeRuntimeVersion = "test-runtime"),
            VisionRuntimeOptions(delegate, threads, false, false, true), delegate == "gpu"))
        else JSONObject().put("requestedDelegate", delegate).put("activeDelegate", delegate)
            .put("numThreads", threads).put("fallbackUsed", false).put("gpuPrecisionLossRequested", false).put("gpuPrecisionLossAllowed", false)
            .put("gpuSerializationCacheRequested", true).put("gpuDelegateAttached", delegate == "gpu")
            .put("gpuSerializationCacheStatus", if (delegate == "gpu") "configured" else "not_applicable")
            .put("modelOwnerPid", 321).put("modelOwnerThreadId", 42).put("loadMs", 1.0)
        if (corruptHandle) json.put("activeDelegate", if (delegate == "gpu") "cpu" else "gpu")
        if (primary) omitPrimaryRuntimeField?.let(json::remove)
        return json.toString()
    }
    override fun validatePair(primary: AndroidDetectorRuntime, auxiliary: FastSamRuntimeInfo) = VisionPairSnapshot(lease,
        VisionModelHandle(lease.cohortId, primaryId, 1, VisionRole.PRIMARY, config.primaryModelKey, runtimeJson(true)),
        VisionModelHandle(lease.cohortId, auxiliaryId, 1, VisionRole.FASTSAM, FastSamModelContract.MODEL_ID, runtimeJson(false)), 321)
    override fun modelRef(snapshot: VisionPairSnapshot, primary: Boolean): DeviceCheckNativeModelRef {
        val handle = if (primary) snapshot.primaryHandle else snapshot.auxiliaryHandle
        val runtime = VisionRuntimeCodec.objectJson(handle.runtimeJson)
        val spec = DeviceCheckNativeSpec(handle.role.name, handle.modelKey,
            if (primary) requireNotNull(config.unifiedWalksafe?.artifactSha256) else FastSamModelContract.SHA256,
            runtime.getString("activeDelegate"), runtime.getInt("numThreads"), VisionRuntimeCodec.optionsHash(VisionRuntimeCodec.encodeOptions(
                VisionRuntimeOptions(runtime.getString("activeDelegate"), runtime.getInt("numThreads"), false, false, true))))
        return DeviceCheckNativeModelRef(if (primary) "load-primary-$name" else "load-auxiliary-$name",
            DeviceCheckNativeLease(lease.cohortId, lease.ownerId, 1), spec, handle.modelInstanceId, VisionRuntimeCodec.runtimeHash(handle.runtimeJson), "test-authority")
    }
    override fun validator(model: TfliteAndroidFrameDetector, auxiliary: FastSamRuntimeService<Unit>, snapshot: VisionPairSnapshot,
        primaryRef: DeviceCheckNativeModelRef, auxiliaryRef: DeviceCheckNativeModelRef): () -> Boolean =
        { nativeCurrent && current && !primaryPhysical.isDone && auxiliary.isReady }
    override fun close() {
        current = false
        hosts.forEach { it.cancel(); it.finish(false) }
        primaryPhysical.complete(Unit); auxiliaryFactory.physical.complete(null); nativePhysical.complete(Unit)
        auxiliary?.let { service -> runCatching { service.closeAsync().get(3, TimeUnit.SECONDS) }
            service.releasedFuture().get(3, TimeUnit.SECONDS) }
        hosts.forEach { host ->
            host.samplingDrainedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
            if (!expectUnconfirmedBegin) host.actualReleasedFuture.toCompletableFuture().get(3, TimeUnit.SECONDS)
        }
        bundle.close()
    }
    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun sampleFixtures(): JointRuntimeSampleFixtures = JointRuntimeSampleFixtures.forTest(JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS.map { id ->
            val image = ArgbImage(2, 2, IntArray(4) { -16777216 })
            JointRuntimeSampleFixture(RuntimeCalibrationFixture(id, RuntimeCalibrationFixtureKind.SOLID_BLACK, null,
                2, 2, "a".repeat(64), true, setOf(RuntimeCalibrationFixtureRole.EMPTY), emptyList()), image, jointPairArgbDigest(image))
        })
    }
}
