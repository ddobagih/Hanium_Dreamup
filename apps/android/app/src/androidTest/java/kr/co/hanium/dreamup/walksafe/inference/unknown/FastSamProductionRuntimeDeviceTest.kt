package kr.co.hanium.dreamup.walksafe.inference.unknown

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCalibrationFixtureKind
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCalibrationFixtureRole
import kr.co.hanium.dreamup.walksafe.inference.RuntimeCalibrationFixtures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in packaged-model correctness probe. No Activity, camera, account or voice operation.
 * Reported times are this background fixture probe's component times, not foreground capacity.
 * Futures obey a240s total deadline; a stalled native call cannot be forcibly interrupted safely. */
@RunWith(AndroidJUnit4::class)
class FastSamProductionRuntimeDeviceTest {
    @Test
    fun packagedCpuAndGpuRunAndRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires -e fastSamProductionOptIn true",
            InstrumentationRegistry.getArguments().getString("fastSamProductionOptIn") == "true")
        val context = instrumentation.targetContext.applicationContext
        val started = SystemClock.elapsedRealtimeNanos()
        val deadline = started + TimeUnit.SECONDS.toNanos(240)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)),
            "fastsam-production-device-test/${UUID.randomUUID()}")
        check(directory.mkdirs()) { "Could not create isolated test result directory" }
        val backends = JSONArray()
        val summary = JSONObject().put("schema", "fastsam-production-device-v1")
            .put("modelId", FastSamModelContract.MODEL_ID).put("modelSha256", FastSamModelContract.SHA256)
            .put("modelAsset", FastSamModelContract.ASSET).put("inputSize", 768)
            .put("device", Build.MODEL).put("sdkInt", Build.VERSION.SDK_INT).put("budgetMs", 240000)
            .put("activityLaunched", false).put("imagesSaved", false).put("rawOutputsSaved", false)
            .put("cameraAcquired", false).put("fieldDepthValidated", false).put("approachValidated", false)
            .put("qualityComparisonValidated", false).put("nativeHardTimeout", false).put("backends", backends)
        var failure: Throwable? = null
        try {
            val fixtures = RuntimeCalibrationFixtures(context)
            val manifest = fixtures.loadManifest()
            val selected = listOf(
                manifest.fixtures.first { RuntimeCalibrationFixtureRole.POSITIVE in it.roles &&
                    it.kind == RuntimeCalibrationFixtureKind.JPEG },
                manifest.fixtures.single { it.kind == RuntimeCalibrationFixtureKind.SOLID_BLACK },
            )
            summary.put("fixtureVersion", manifest.version).put("fixtureManifestSha256", manifest.sha256)
                .put("fixtureIds", JSONArray(selected.map { it.id }))
                .put("fixtureRolesArePrimaryAnnotations", true)
            for (backend in listOf(FastSamRuntimeOptions.Backend.CPU, FastSamRuntimeOptions.Backend.GPU)) {
                check(remainingNs(deadline) > TimeUnit.SECONDS.toNanos(10)) { "No remaining load/cleanup budget" }
                val rows = JSONArray()
                val row = JSONObject().put("requestedBackend", backend.name).put("numThreads", 1)
                    .put("fixtureResults", rows)
                backends.put(row)
                val expected = AtomicReference<CompletableFuture<FastSamResult<String>>?>()
                val callbackError = AtomicReference<Throwable?>()
                val epoch = SystemClock.elapsedRealtimeNanos()
                val options = FastSamRuntimeOptions(backend, 1,
                    backend == FastSamRuntimeOptions.Backend.GPU, deadline - TimeUnit.SECONDS.toNanos(8),
                    5000, true)
                val service = FastSamRuntimeService(context, epoch, options,
                    object : FastSamRuntimeService.Listener<String> {
                        override fun onResult(result: FastSamResult<String>) {
                            check(requireNotNull(expected.get()).complete(result)) { "Duplicate/unexpected fixture result" }
                        }
                        override fun onError(error: Throwable) {
                            callbackError.compareAndSet(null, error)
                            expected.get()?.completeExceptionally(error)
                        }
                        override fun onDiscard(token: FastSamFrameToken, reason: String) {
                            expected.get()?.completeExceptionally(IllegalStateException("Fixture discarded: $reason"))
                        }
                    })
                var backendFailure: Throwable? = null
                try {
                    val info = await(service.start(), deadline)
                    check(info.modelSha256 == FastSamModelContract.SHA256 && info.numThreads == 1)
                    check(info.requestedBackend == backend)
                    if (backend == FastSamRuntimeOptions.Backend.CPU) {
                        check(info.actualBackend == FastSamRuntimeOptions.Backend.CPU && !info.fallbackUsed)
                    } else if (info.actualBackend == FastSamRuntimeOptions.Backend.CPU) {
                        check(info.fallbackUsed && !info.fallbackReason.isNullOrBlank())
                    } else check(info.actualBackend == FastSamRuntimeOptions.Backend.GPU && !info.fallbackUsed)
                    check(info.gpuDelegateAttached == (info.actualBackend == FastSamRuntimeOptions.Backend.GPU))
                    check(!info.gpuPrecisionLossAllowed)
                    row.put("actualBackend", info.actualBackend.name).put("fallbackUsed", info.fallbackUsed)
                        .put("fallbackReason", info.fallbackReason ?: JSONObject.NULL)
                        .put("gpuDelegateAttached", info.gpuDelegateAttached).put("gpuPrecisionLossAllowed", false)
                        .put("modelOwnerThreadId", info.modelOwnerThreadId).put("loadMs", info.loadMs)
                        .put("gpuAllOperationsConfirmed", false)
                    for ((index, fixture) in selected.withIndex()) {
                        val argb = fixtures.loadArgb(fixture)
                        val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,
                            epoch, index + 1L, 0L, 0L, SystemClock.elapsedRealtimeNanos(),
                            "fixture:${manifest.sha256}:${fixture.id}", argb.width, argb.height)
                        val resultFuture = CompletableFuture<FastSamResult<String>>()
                        expected.set(resultFuture)
                        val admission = service.submitArgb(argb.width, argb.height, argb.pixels, token, fixture.id)
                        check(admission == FastSamRuntimeService.Admission.ACCEPTED) { "Unexpected admission $admission" }
                        val result = await(resultFuture, deadline)
                        check(result.token === token && result.attachment == fixture.id)
                        check(result.token.source == FastSamFrameToken.Source.CALIBRATION_FIXTURE)
                        check(result.runtime.modelOwnerThreadId == info.modelOwnerThreadId)
                        check(result.cpuPostOwnerThreadId != info.modelOwnerThreadId)
                        check(result.completedElapsedNs >= token.capturedElapsedNs)
                        val raw = requireNotNull(result.rawOutputs) { "Fixture raw output unavailable" }
                        val detectionCount = requireFinite(raw.detections(), FastSamModelContract.DETECTION_FLOATS)
                        val prototypeCount = requireFinite(raw.prototypes(), FastSamModelContract.PROTOTYPE_FLOATS)
                        check(result.masks.size <= 100 && result.masks.all {
                            it.imageWidth() == argb.width && it.imageHeight() == argb.height && it.area() > 0
                        })
                        rows.put(JSONObject().put("fixtureId", fixture.id).put("source", token.source.name)
                            .put("width", argb.width).put("height", argb.height).put("maskCount", result.masks.size)
                            .put("allRawFinite", true).put("detectionFloatCount", detectionCount)
                            .put("prototypeFloatCount", prototypeCount).put("cpuPostOwnerThreadId", result.cpuPostOwnerThreadId)
                            .put("copyMs", result.copyMs).put("queueMs", result.queueMs)
                            .put("preprocessingMs", result.preprocessingMs).put("inferenceMs", result.inferenceMs)
                            .put("outputReadMs", result.outputReadMs).put("cpuPostWaitMs", result.cpuPostWaitMs)
                            .put("decodeMs", result.decodeMs).put("captureToDecodeCompleteMs", result.captureToDecodeCompleteMs))
                        expected.set(null)
                    }
                    callbackError.get()?.let { throw it }
                    row.put("status", if (info.fallbackUsed) "CPU_FALLBACK_EXECUTED" else "${info.actualBackend.name}_EXECUTED")
                } catch (error: Throwable) {
                    backendFailure = error
                    row.put("status", "ERROR").put("error", error.toString())
                    throw error
                } finally {
                    service.closeAsync()
                    try {
                        await(service.releasedFuture(), deadline)
                        await(service.closeAsync(), deadline)
                        while (service.stats().modelOwnerAlive || service.stats().cpuPostOwnerAlive) {
                            check(remainingNs(deadline) > 0) { "Owner thread termination timeout" }
                            SystemClock.sleep(2)
                        }
                        check(service.stats().nativeReleaseConfirmed)
                        row.put("nativeReleaseConfirmed", true).put("ownerThreadsTerminated", true)
                    } catch (error: Throwable) {
                        row.put("nativeReleaseConfirmed", service.stats().nativeReleaseConfirmed)
                            .put("ownerThreadsTerminated", !service.stats().modelOwnerAlive && !service.stats().cpuPostOwnerAlive)
                            .put("cleanupError", error.toString())
                        if (backendFailure == null) throw error else backendFailure.addSuppressed(error)
                    }
                }
            }
            summary.put("status", "PASS")
        } catch (error: Throwable) {
            failure = error
            summary.put("status", "ERROR").put("error", error.toString())
        } finally {
            summary.put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
            val result = File(directory, "summary.json")
            result.writeText(summary.toString(2))
            Log.i("FastSamProductionTest", summary.toString())
            instrumentation.sendStatus(0, Bundle().apply { putString("fastsamProductionResult", result.absolutePath) })
        }
        failure?.let { throw it }
    }

    private fun remainingNs(deadline: Long): Long = deadline - SystemClock.elapsedRealtimeNanos()
    private fun <T> await(future: CompletableFuture<T>, deadline: Long): T =
        future.get(remainingNs(deadline).coerceAtLeast(1L), TimeUnit.NANOSECONDS)
    private fun requireFinite(buffer: FloatBuffer, expected: Int): Int {
        check(buffer.isReadOnly && buffer.remaining() == expected)
        val copy = FloatArray(expected)
        buffer.get(copy)
        check(copy.all { it.isFinite() }) { "Nonfinite actual model output" }
        return copy.size
    }
}
