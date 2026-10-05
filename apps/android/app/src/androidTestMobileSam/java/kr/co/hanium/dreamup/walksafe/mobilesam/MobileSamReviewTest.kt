package kr.co.hanium.dreamup.walksafe.mobilesam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage
import kr.co.hanium.dreamup.walksafe.inference.ModelRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Same cases and weights for both rounds; reports reproducibility, not new-scene accuracy. */
@RunWith(AndroidJUnit4::class)
class MobileSamReviewTest {
    @Test fun fixedWalkingScenesProduceReviewArtifacts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val round = InstrumentationRegistry.getArguments().getString("reviewRound", "1")
        require(round in setOf("1", "2"))
        val output = File(context.filesDir, "mobilesam-review/round-$round").apply { mkdirs() }
        val cases = JSONObject(instrumentation.context.assets.open("mobilesam_review_cases.json")
            .bufferedReader().use { it.readText() }).getJSONArray("cases")
        val results = JSONArray()
        val startLoad = SystemClock.elapsedRealtime()
        val status = TfliteAndroidFrameDetector.createWithStatus(context, ModelRuntimeOptions(delegate = "cpu", numThreads = 2))
        val yolo = requireNotNull(status.detector) { status.reason }
        val yoloLoad = SystemClock.elapsedRealtime() - startLoad
        val samLoadStart = SystemClock.elapsedRealtime()
        try { MobileSamRunner(context).use { sam ->
            val samLoad = SystemClock.elapsedRealtime() - samLoadStart
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val id = case.getString("id")
                val photo = context.assets.open("mobilesam-sample/$id.jpg").use { BitmapFactory.decodeStream(it) }
                val pixels = IntArray(photo.width * photo.height)
                photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
                val yoloStart = SystemClock.elapsedRealtime()
                val detections = yolo.detectCalibrationImage(ArgbImage(photo.width, photo.height, pixels)).detections
                    .sortedByDescending { it.detectionConfidence }
                val yoloMs = SystemClock.elapsedRealtime() - yoloStart
                val boxes = detections.mapNotNull { d ->
                    val b = d.bboxNorm
                    val x1 = b.x.coerceIn(0f, 1f); val y1 = b.y.coerceIn(0f, 1f)
                    val x2 = (b.x + b.width).coerceIn(0f, 1f); val y2 = (b.y + b.height).coerceIn(0f, 1f)
                    if (x2 <= x1 || y2 <= y1) null else SamBox(x1, y1, x2, y2, d.className)
                }.take(3)
                val autoStart = SystemClock.elapsedRealtime()
                val automatic = sam.segment(photo, boxes)
                val autoMs = SystemClock.elapsedRealtime() - autoStart
                assertEquals(boxes.size, automatic.size)
                val targets = case.getJSONArray("targets")
                val manualBoxes = (0 until targets.length()).map {
                    val target = targets.getJSONObject(it); val b = target.getJSONArray("box")
                    SamBox(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat(), target.getString("name"))
                }
                val manualStart = SystemClock.elapsedRealtime()
                val masks = sam.segment(photo, manualBoxes)
                val manualMs = SystemClock.elapsedRealtime() - manualStart
                val maskResults = JSONArray()
                for (m in masks.indices) {
                    val mask = masks[m]
                    assertEquals(pixels.size, mask.size)
                    val bytes = ByteArray(mask.size) { if (mask[it]) 1 else 0 }
                    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    saveOverlay(photo, listOf(manualBoxes[m]), listOf(mask), File(output, "$id-manual-$m.png"))
                    val binary = Bitmap.createBitmap(IntArray(mask.size) { if (mask[it]) Color.WHITE else Color.BLACK }, photo.width, photo.height, Bitmap.Config.ARGB_8888)
                    File(output, "$id-mask-$m.png").outputStream().use { binary.compress(Bitmap.CompressFormat.PNG, 100, it) }; binary.recycle()
                    maskResults.put(JSONObject().put("target", manualBoxes[m].label).put("box", targets.getJSONObject(m).getJSONArray("box"))
                        .put("pixels", mask.count { it }).put("sha256", sha))
                }
                saveOverlay(photo, boxes, automatic, File(output, "$id-automatic.png"))
                val detectionJson = JSONArray()
                detections.forEach { d -> detectionJson.put(JSONObject().put("class", d.className).put("confidence", d.detectionConfidence.toDouble())) }
                results.put(JSONObject().put("image", id).put("width", photo.width).put("height", photo.height)
                    .put("yolo_ms", yoloMs).put("yolo_detections", detectionJson)
                    .put("tactile_detections", detections.count { it.className in MobileSamGeometry.tactileClasses })
                    .put("automatic_mask_count", automatic.size).put("automatic_nonempty", automatic.count { mask -> mask.any { it } })
                    .put("automatic_sam_ms", autoMs).put("manual_sam_ms", manualMs).put("manual_masks", maskResults))
                photo.recycle()
            }
            File(output, "results.json").writeText(JSONObject().put("round", round).put("version", BuildConfig.VERSION_NAME)
                .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("yolo_load_ms", yoloLoad).put("sam_load_ms", samLoad).put("cpu_threads", 2)
                .put("cases", results).put("note", "Fixed-photo reproducibility; masks are predictions, not ground truth. Timings exclude model loading.").toString(2))
        } } finally { yolo.close() }
    }

    private fun saveOverlay(photo: Bitmap, boxes: List<SamBox>, masks: List<BooleanArray>, file: File) {
        val output = photo.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(0x8000d4ff.toInt(), 0x80ffbc47.toInt(), 0x80db7aff.toInt())
        masks.forEachIndexed { index, mask ->
            val overlay = Bitmap.createBitmap(IntArray(mask.size) { if (mask[it]) colors[index] else 0 }, photo.width, photo.height, Bitmap.Config.ARGB_8888)
            canvas.drawBitmap(overlay, 0f, 0f, null); overlay.recycle()
            paint.color = Color.GREEN; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
            val box = boxes[index]
            canvas.drawRect(box.left * photo.width, box.top * photo.height, box.right * photo.width, box.bottom * photo.height, paint)
        }
        file.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
        output.recycle()
    }
}
