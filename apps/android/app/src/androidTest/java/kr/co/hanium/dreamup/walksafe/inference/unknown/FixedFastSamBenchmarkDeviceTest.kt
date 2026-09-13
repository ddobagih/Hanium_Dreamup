package kr.co.hanium.dreamup.walksafe.inference.unknown

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed exhaustive images. No camera/session or real-time capacity claim; all inputs must complete. */
@RunWith(AndroidJUnit4::class)
class FixedFastSamBenchmarkDeviceTest {
    @Test fun fixedEightImagesCompleteWithoutDrop() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("fixedFastSamBenchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "phase5-fixed-data")
        val manifestBytes = File(root, "manifest.json").readBytes()
        check(sha(manifestBytes) == args.getString("fixedManifestSha256"))
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        check(manifest.getInt("inputSize") == 768)
        val images = manifest.getJSONArray("images")
        val fixtures = (0 until images.length()).map { i ->
            val row = images.getJSONObject(i)
            val bytes = File(root, row.getString("rgb")).readBytes()
            check(sha(bytes) == row.getString("rgbSha256"))
            val w = row.getInt("width"); val h = row.getInt("height")
            check(bytes.size == w * h * 3)
            Fixture(row.getString("id"), w, h, IntArray(w * h) { p ->
                val o = p * 3
                (0xff shl 24) or ((bytes[o].toInt() and 255) shl 16) or
                    ((bytes[o+1].toInt() and 255) shl 8) or (bytes[o+2].toInt() and 255)
            })
        }
        check(fixtures.size == 8)
        val backend = FastSamRuntimeOptions.Backend.valueOf(args.getString("benchmarkBackend") ?: "GPU")
        val precision = args.getString("benchmarkPrecisionLoss") == "true"
        val cache = args.getString("benchmarkSerialization") == "true"
        val started = SystemClock.elapsedRealtimeNanos()
        val deadline = started + TimeUnit.SECONDS.toNanos(240)
        val serviceEpoch = started
        val options = if (precision || cache) {
            FastSamRuntimeOptions::class.java.getConstructor(FastSamRuntimeOptions.Backend::class.java,
                Integer.TYPE, java.lang.Boolean.TYPE, java.lang.Long.TYPE, java.lang.Long.TYPE,
                java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
                .newInstance(backend, 1, false, deadline - TimeUnit.SECONDS.toNanos(10), 5000L,
                    false, precision, cache)
        } else FastSamRuntimeOptions(backend, 1, false, deadline - TimeUnit.SECONDS.toNanos(10), 5000L, false)
        val expected = AtomicReference<CompletableFuture<FastSamResult<String>>?>()
        val error = AtomicReference<Throwable?>()
        val rows = JSONArray()
        val power = context.getSystemService(PowerManager::class.java)
        val summary = JSONObject().put("schema", "walksafe.fixed-fastsam-benchmark.v1")
            .put("device", Build.MODEL).put("sdkInt", Build.VERSION.SDK_INT)
            .put("manifestSha256", sha(manifestBytes)).put("backendRequested", backend.name)
            .put("precisionRequested", precision).put("serializationRequested", cache)
            .put("warmupCycles", 1).put("measuredCycles", 3).put("expectedMeasuredInputs", 24)
            .put("thermalBefore", power.currentThermalStatus).put("rows", rows)
            .put("cameraMeasured", false).put("depthMeasured", false).put("pacedCapacityMeasured", false)
        val directory = File(context.filesDir, "phase5-benchmark/${UUID.randomUUID()}")
        check(directory.mkdirs())
        val service = FastSamRuntimeService(context, serviceEpoch, options,
            object : FastSamRuntimeService.Listener<String> {
                override fun onResult(result: FastSamResult<String>) {
                    check(requireNotNull(expected.get()).complete(result))
                }
                override fun onError(failure: Throwable) { error.set(failure); expected.get()?.completeExceptionally(failure) }
                override fun onDiscard(token: FastSamFrameToken, reason: String) {
                    val failure = IllegalStateException("Exhaustive input discarded: $reason")
                    error.set(failure); expected.get()?.completeExceptionally(failure)
                }
            })
        fun <T> await(f: CompletableFuture<T>): T = f.get(
            (deadline - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
        try {
            val info = await(service.start())
            check(info.actualBackend == backend && !info.fallbackUsed)
            summary.put("loadMs", info.loadMs).put("backendActual", info.actualBackend.name)
                .put("precisionActual", info.gpuPrecisionLossAllowed).put("modelSha256", info.modelSha256)
            for (field in listOf("gpuSerializationCacheStatus", "gpuSerializationCacheToken", "gpuInitializationAttempts")) {
                summary.put(field, runCatching { info.javaClass.getField(field).get(info) }.getOrNull() ?: JSONObject.NULL)
            }
            var frame = 0L
            repeat(4) { cycle ->
                for (fixture in fixtures) {
                    val future = CompletableFuture<FastSamResult<String>>()
                    expected.set(future)
                    val token = FastSamFrameToken(FastSamFrameToken.Source.CALIBRATION_FIXTURE,
                        serviceEpoch, ++frame, 0, 0, SystemClock.elapsedRealtimeNanos(),
                        "fixed:${fixture.id}", fixture.width, fixture.height)
                    check(service.submitArgb(fixture.width, fixture.height, fixture.pixels, token, fixture.id) ==
                        FastSamRuntimeService.Admission.ACCEPTED)
                    val result = await(future)
                    check(result.token === token && result.attachment == fixture.id)
                    if (cycle > 0) {
                        val masks = JSONArray()
                        for (mask in result.masks) {
                            val bits = mask.copyPackedBits()
                            val row = JSONObject().put("anchor", mask.anchorIndex()).put("score", mask.score().toDouble())
                                .put("left", mask.maskLeft()).put("top", mask.maskTop())
                                .put("right", mask.maskRightExclusive()).put("bottom", mask.maskBottomExclusive())
                                .put("area", mask.area()).put("bitsSha256", sha(bits))
                            if (cycle == 1) row.put("bitsBase64", Base64.encodeToString(bits, Base64.NO_WRAP))
                            masks.put(row)
                        }
                        rows.put(JSONObject().put("cycle", cycle).put("id", fixture.id)
                            .put("preprocessingMs", result.preprocessingMs).put("inferenceMs", result.inferenceMs)
                            .put("outputReadMs", result.outputReadMs).put("decodeMs", result.decodeMs)
                            .put("queueMs", result.queueMs).put("cpuPostWaitMs", result.cpuPostWaitMs)
                            .put("completeMs", result.captureToDecodeCompleteMs).put("masks", masks))
                    }
                    while (service.stats().inflight) {
                        check(SystemClock.elapsedRealtimeNanos() < deadline)
                        SystemClock.sleep(1)
                    }
                }
            }
            error.get()?.let { throw it }
            check(rows.length() == 24)
            summary.put("status", "PASS").put("measuredInputs", rows.length())
        } catch (failure: Throwable) {
            summary.put("status", "FAIL").put("failure", failure.toString())
            throw failure
        } finally {
            service.closeAsync()
            try {
                await(service.releasedFuture())
                while (service.stats().modelOwnerAlive || service.stats().cpuPostOwnerAlive) {
                    check(SystemClock.elapsedRealtimeNanos() < deadline) { "Owner termination timeout" }
                    SystemClock.sleep(1)
                }
                summary.put("nativeReleaseConfirmed", service.stats().nativeReleaseConfirmed)
            } catch (cleanup: Throwable) {
                summary.put("status", "FAIL").put("cleanupFailure", cleanup.toString())
                throw cleanup
            } finally {
                summary.put("nativeReleaseConfirmed", service.stats().nativeReleaseConfirmed)
                    .put("modelOwnerAlive", service.stats().modelOwnerAlive)
                    .put("cpuPostOwnerAlive", service.stats().cpuPostOwnerAlive)
                summary.put("thermalAfter", power.currentThermalStatus)
                    .put("elapsedMs", (SystemClock.elapsedRealtimeNanos() - started) / 1e6)
                File(directory, "summary.json").writeText(summary.toString())
                InstrumentationRegistry.getInstrumentation().sendStatus(0,
                    android.os.Bundle().apply { putString("fixedBenchmarkResult", File(directory, "summary.json").absolutePath) })
            }
        }
    }
    private data class Fixture(val id: String, val width: Int, val height: Int, val pixels: IntArray)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
