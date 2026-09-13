package kr.co.hanium.dreamup.walksafe.depth.unknown

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.*
import java.lang.reflect.Modifier
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import kr.co.hanium.dreamup.walksafe.depth.unknown.baseline.MaskDepthEstimator as Baseline

/** One APK, one thread, immutable fixed data, A/B/B/A. No camera or model execution. */
@RunWith(AndroidJUnit4::class)
class MaskDepthOptimizationDeviceTest {
    @Test fun compareFixedDepthABBA() {
        assumeTrue("Fixed depth benchmark is opt-in", InstrumentationRegistry.getArguments().getString("phase5DepthEnabled") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.filesDir, "phase5-depth")
        val id = InstrumentationRegistry.getArguments().getString("phase5DepthRunId") ?: "depth-${System.currentTimeMillis()}"
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val output = File(folder, "result-$id.json")
        check(!output.exists()) { "Never overwrite a previous device result" }
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 240_000L
        fun alive() = check(SystemClock.elapsedRealtime() < deadline) { "240 second deadline exceeded" }
        val calls = JSONArray()
        val blocks = JSONArray()
        val report = JSONObject().put("runId", id).put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("calls", calls).put("blocks", blocks)
            .put("warmupRoundsPerVariant", 4).put("measuredRoundsPerBlock", 10).put("order", JSONArray(listOf("baseline", "optimized", "optimized", "baseline")))
            .put("allocationBytes", JSONObject.NULL).put("allocationReason", "No reliable per-thread allocation byte counter used on Android")
            .put("thermalBefore", thermal(context)).put("batteryTemperatureBeforeTenthsC", batteryTemperature(context))
            .put("inputSource", "REGISTERED_RGBD_REFERENCE").put("physicalAccuracyClaim", false)
            .put("expectedBaselineSourceSha256", "6850ac234f95131a77da8fd08745e22f285125946c6fac0c8743d5bbdd71da1b")
            .put("expectedOptimizedSourceSha256", "177c5ac539c4c14704f4030fb6b1141cbf3cbb1ee125a3637d6789798b3cb035")
        var checks = 0
        try {
            check(MaskDepthEstimator::class.java.declaredFields.any { it.name == "componentQueue" }) { "Optimized source was not built into this APK" }
            check(Baseline::class.java.declaredFields.none { it.name == "componentQueue" }) { "Test baseline is not the frozen baseline" }
            val manifestFile = File(folder, "manifest.json")
            val manifest = JSONObject(manifestFile.readText())
            check(FixedDepthHarness.sha(manifestFile) == EXPECTED_MANIFEST_SHA) { "Unexpected manifest bytes" }
            val input = File(folder, "fixed-depth-u16-roi.bin")
            check(FixedDepthHarness.sha(input) == EXPECTED_INPUT_SHA && manifest.getString("sha256") == EXPECTED_INPUT_SHA)
            val scenes = FixedDepthHarness.read(input)
            check(scenes.map { it.name } == listOf("OCID_image_0", "OCID_image_1", "OSD_image_0", "OSD_image_1"))
            check(scenes.map { it.masks.size } == listOf(33, 68, 49, 83))
            val harness = FixedDepthHarness(scenes)
            val reference = scenes.indices.map { i ->
                alive()
                val result = harness.associate(false, i)
                val signature = FixedDepthHarness.signature(result)
                check(signature == FixedDepthHarness.signature(harness.associate(true, i))) { "Initial full result parity failed: ${scenes[i].name}" }
                checks += 2
                signature
            }
            check(reference.sumOf { it.components } == 401)
            report.put("inputSha256", EXPECTED_INPUT_SHA).put("manifestSha256", EXPECTED_MANIFEST_SHA)
                .put("totalMasks", reference.sumOf { it.masks }).put("totalComponents", reference.sumOf { it.components })
                .put("sceneReference", JSONArray(reference.mapIndexed { i, s -> JSONObject().put("scene", scenes[i].name)
                    .put("sha256", s.sha256).put("masks", s.masks).put("components", s.components)
                    .put("known", s.known).put("partial", s.partial).put("unknown", s.unknown) }))
            for (optimized in listOf(false, true)) repeat(4) {
                for (i in scenes.indices) {
                    alive()
                    check(FixedDepthHarness.signature(harness.associate(optimized, i)) == reference[i])
                    checks++
                }
            }
            for ((block, optimized) in listOf(false, true, true, false).withIndex()) {
                val variant = if (optimized) "optimized" else "baseline"
                val perScene = scenes.indices.map { mutableListOf<Double>() }
                val thermalBefore = thermal(context)
                repeat(10) { round ->
                    for (i in scenes.indices) {
                        alive()
                        val begin = SystemClock.elapsedRealtimeNanos()
                        val result = harness.associate(optimized, i)
                        val end = SystemClock.elapsedRealtimeNanos()
                        val ms = (end - begin) / 1e6
                        // Hashing runs outside the timed estimator region, on every completed result.
                        check(FixedDepthHarness.signature(result) == reference[i]) { "Output changed in block $block/$round/${scenes[i].name}" }
                        checks++
                        perScene[i].add(ms)
                        calls.put(JSONObject().put("block", block).put("round", round).put("variant", variant)
                            .put("scene", scenes[i].name).put("depthMs", ms).put("parity", true))
                    }
                }
                blocks.put(JSONObject().put("block", block).put("variant", variant).put("thermalBefore", thermalBefore)
                    .put("thermalAfter", thermal(context)).put("scenes", JSONArray(perScene.mapIndexed { i, values ->
                        stats(values).put("scene", scenes[i].name)
                    })).put("aggregate", stats(perScene.flatten())))
            }
            report.put("passed", true)
                .put("timedBoundary", "associate only; fixture parsing, input copies, signatures, JSON and model execution excluded")
        } catch (error: Throwable) {
            report.put("passed", false).put("error", "${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            report.put("parityChecks", checks).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("thermalAfter", thermal(context)).put("batteryTemperatureAfterTenthsC", batteryTemperature(context))
            folder.mkdirs()
            output.writeText(report.toString(2))
        }
    }

    private fun stats(values: List<Double>): JSONObject {
        val sorted = values.sorted()
        fun percentile(q: Double): Double { val position = (sorted.size - 1) * q; val lo = position.toInt(); val hi = kotlin.math.ceil(position).toInt(); return sorted[lo] + (sorted[hi] - sorted[lo]) * (position - lo) }
        return JSONObject().put("count", values.size).put("meanMs", values.average()).put("p50Ms", percentile(.5)).put("p95Ms", percentile(.95))
    }
    private fun thermal(context: Context): Int = if (Build.VERSION.SDK_INT >= 29) (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else -1
    private fun batteryTemperature(context: Context): Int = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra("temperature", -1) ?: -1
    private companion object {
        const val EXPECTED_INPUT_SHA = "caba6c8aeb965fd605e1d03d125afb3e78f295387192a72bbd80bfb511133fd1"
        const val EXPECTED_MANIFEST_SHA = "f5e60c9dc3a36bf7313d545c799d66c33fb1e2d197f86d81eab9ef3bd7a98b9b"
    }
}

/** Host-verifiable bridge reused by the Android test; effective values equal the original MDP2 fixture. */
internal class FixedDepthHarness(val scenes: List<Scene>) {
    class Mask(val id: String, val w: Int, val h: Int, val left: Int, val top: Int, val width: Int, val height: Int,
               val area: Int, private val packed: ByteArray) : MaskDepthEstimator.ImageMask, Baseline.ImageMask {
        override fun imageWidth() = w
        override fun imageHeight() = h
        override fun minX() = left
        override fun minY() = top
        override fun maxXExclusive() = left + width
        override fun maxYExclusive() = top + height
        override fun contains(x: Int, y: Int): Boolean {
            if (x < left || x >= left + width || y < top || y >= top + height) return false
            val index = (y - top) * width + x - left
            return ((packed[index / 8].toInt() and 255) and (1 shl (index % 8))) != 0
        }
        override fun containsImagePoint(x: Double, y: Double) = contains(kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt())
    }
    data class Scene(val name: String, val width: Int, val height: Int, val dw: Int, val dh: Int,
                     val matrix: DoubleArray, val millimeters: IntArray, val masks: List<Mask>)
    private val baseline = Baseline()
    private val optimized = MaskDepthEstimator()
    private val a = scenes.map { scene ->
        val identity = Baseline.FrameIdentity("java-python-parity", scene.name, 0L, "dataset_sample_no_clock")
        val calibration = Baseline.Calibration(identity, scene.width, scene.height, scene.dw, scene.dh, scene.matrix, "registered_rgbd_dataset", null)
        scene.masks.map { Baseline.Detection(it.id, null, identity, it) } to Baseline.DepthFrame.registeredMillimeters(identity, scene.millimeters, calibration)
    }
    private val b = scenes.map { scene ->
        val identity = MaskDepthEstimator.FrameIdentity("java-python-parity", scene.name, 0L, "dataset_sample_no_clock")
        val calibration = MaskDepthEstimator.Calibration(identity, scene.width, scene.height, scene.dw, scene.dh, scene.matrix, "registered_rgbd_dataset", null)
        scene.masks.map { MaskDepthEstimator.Detection(it.id, null, identity, it) } to MaskDepthEstimator.DepthFrame.registeredMillimeters(identity, scene.millimeters, calibration)
    }
    fun associate(useOptimized: Boolean, scene: Int): List<*> = if (useOptimized) optimized.associate(b[scene].first, b[scene].second) else baseline.associate(a[scene].first, a[scene].second)
    data class Signature(val sha256: String, val masks: Int, val components: Int, val known: Int, val partial: Int, val unknown: Int)
    companion object {
        fun read(file: File): List<Scene> = DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            check(input.readInt() == 0x4d445035)
            val count = input.readInt(); check(count == 4)
            val scenes = List(count) {
                val name = input.readUTF(); val w = input.readInt(); val h = input.readInt(); val dw = input.readInt(); val dh = input.readInt()
                check(w in 1..4096 && h in 1..4096 && dw in 1..4096 && dh in 1..4096)
                val matrix = DoubleArray(9) { input.readDouble().also { check(it.isFinite()) } }
                val mm = IntArray(dw * dh) { input.readUnsignedShort() }
                val masks = List(input.readInt().also { check(it in 0..500) }) {
                    val id = input.readUTF(); val left = input.readInt(); val top = input.readInt(); val width = input.readInt(); val height = input.readInt(); val area = input.readInt(); val bytes = input.readInt()
                    check(left >= 0 && top >= 0 && width >= 0 && height >= 0 && left + width <= w && top + height <= h && bytes == (width * height + 7) / 8)
                    val packed = ByteArray(bytes); input.readFully(packed)
                    check(packed.sumOf { Integer.bitCount(it.toInt() and 255) } == area)
                    Mask(id, w, h, left, top, width, height, area, packed)
                }
                Scene(name, w, h, dw, dh, matrix, mm, masks)
            }
            check(input.read() == -1)
            scenes
        }
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return hex(digest.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun signature(results: List<*>): Signature {
            val digest = MessageDigest.getInstance("SHA-256")
            val sink = object : OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }
            // Preserve serialization bytes; batch tiny primitive writes before SHA updates.
            val out = DataOutputStream(BufferedOutputStream(DigestOutputStream(sink, digest), 65536))
            fun emit(value: Any?) {
                when (value) {
                    null -> out.writeByte(0)
                    is String -> { out.writeByte(1); out.writeUTF(value) }
                    is Int -> { out.writeByte(2); out.writeInt(value) }
                    is Long -> { out.writeByte(3); out.writeLong(value) }
                    is Double -> { out.writeByte(4); out.writeLong(value.toRawBits()) }
                    is Boolean -> { out.writeByte(5); out.writeBoolean(value) }
                    is Enum<*> -> { out.writeByte(6); out.writeUTF(value.name) }
                    is IntArray -> { out.writeByte(7); out.writeInt(value.size); value.forEach { out.writeInt(it) } }
                    is DoubleArray -> { out.writeByte(8); out.writeInt(value.size); value.forEach { out.writeLong(it.toRawBits()) } }
                    is List<*> -> { out.writeByte(9); out.writeInt(value.size); value.forEach { emit(it) } }
                    else -> {
                        out.writeByte(10); out.writeUTF(value.javaClass.simpleName)
                        val fields = value.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.sortedBy { it.name }
                        out.writeInt(fields.size)
                        fields.forEach { field -> field.isAccessible = true; out.writeUTF(field.name); emit(field.get(value)) }
                    }
                }
            }
            emit(results); out.flush()
            fun field(value: Any, name: String): Any = value.javaClass.getField(name).get(value)
            val statuses = results.filterNotNull().map { (field(it, "status") as Enum<*>).name }
            return Signature(hex(digest.digest()), results.size, results.filterNotNull().sumOf { (field(it, "components") as List<*>).size },
                statuses.count { it == "KNOWN" }, statuses.count { it == "PARTIAL" }, statuses.count { it == "UNKNOWN" })
        }
    }
}
