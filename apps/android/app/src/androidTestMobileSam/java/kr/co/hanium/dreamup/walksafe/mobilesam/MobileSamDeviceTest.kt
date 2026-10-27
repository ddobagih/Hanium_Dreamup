package kr.co.hanium.dreamup.walksafe.mobilesam

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage
import kr.co.hanium.dreamup.walksafe.inference.ModelRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class MobileSamDeviceTest {
    @Test fun modelsAndPhotoScreenUseTheSameImageCoordinates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = context.assets.open("mobilesam-sample/japan_crosswalk.jpg").use { BitmapFactory.decodeStream(it) }
        val reference = instrumentation.context.assets.open("mobilesam_expected_mask.bin").use { it.readBytes() }
        assertEquals(bitmap.width * bitmap.height, reference.size)
        val initStart = SystemClock.elapsedRealtime()
        var mobileSamMs = 0L
        var maskIou = 0.0
        MobileSamRunner(context).use { runner ->
            val start = SystemClock.elapsedRealtime()
            val mask = runner.segment(bitmap, listOf(SamBox(.4f, .5f, .7f, .95f, "수동 영역"))).single()
            mobileSamMs = SystemClock.elapsedRealtime() - start
            var intersection = 0; var union = 0
            for (i in mask.indices) {
                val expected = reference[i] != 0.toByte()
                if (mask[i] && expected) intersection++
                if (mask[i] || expected) union++
            }
            assertTrue("Reference mask must have foreground", union > 100)
            maskIou = intersection.toDouble() / union
            assertTrue("Android/Desktop mask IoU=$maskIou", maskIou >= .98)
        }
        val initAndRunMs = SystemClock.elapsedRealtime() - initStart
        val yolo = TfliteAndroidFrameDetector.createWithStatus(context, ModelRuntimeOptions(delegate = "cpu", numThreads = 2))
        assertNotNull("YOLO load: ${yolo.reason}", yolo.detector)
        val detector = yolo.detector!!
        val detections = try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            detector.detectCalibrationImage(ArgbImage(bitmap.width, bitmap.height, pixels)).detections
        } finally { detector.close() }
        assertTrue("YOLO sample must have some objects", detections.isNotEmpty())
        bitmap.recycle()

        val activity = instrumentation.startActivitySync(Intent(context, MobileSamTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MobileSamTestActivity
        fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
            (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
        val all = views(activity.window.decorView)
        val preview = all.filterIsInstance<SamPreview>().single()
        fun waitFor(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (SystemClock.elapsedRealtime() < deadline) {
                var done = false
                instrumentation.runOnMainSync { done = condition() }
                if (done) return
                SystemClock.sleep(100)
            }
            fail("Timeout: $description; " + all.filterIsInstance<TextView>().joinToString { it.text })
        }
        try {
            waitFor("sample loaded") { preview.photo != null && all.filterIsInstance<Button>().all { it.isEnabled } }
            instrumentation.runOnMainSync {
                all.filterIsInstance<Button>().first { it.text == "YOLO → MobileSAM" }.performClick()
            }
            waitFor("default tactile filter") { all.filterIsInstance<Button>().all { it.isEnabled } }
            val tactileCount = detections.count { it.className in MobileSamGeometry.tactileClasses }
            assertEquals(min(3, tactileCount), preview.layers.size)
            if (tactileCount == 0) assertTrue(all.filterIsInstance<TextView>().any { it.text.contains("분할 대상 0개") })
            instrumentation.runOnMainSync {
                all.filterIsInstance<CheckBox>().first { it.text == "점자블록만 분할" }.isChecked = false
                all.filterIsInstance<Button>().first { it.text == "YOLO → MobileSAM" }.performClick()
            }
            waitFor("YOLO to MobileSAM") { preview.layers.size == min(3, detections.size) }
            assertTrue(preview.layers.all { it.area > 0 })
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot().use { screenshot ->
                File(context.filesDir, "mobilesam-automatic.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            instrumentation.runOnMainSync {
                all.filterIsInstance<Button>().first { it.text == "영역 직접 지정" }.performClick()
                val photo = preview.photo!!
                val scale = min(preview.width.toFloat() / photo.width, preview.height.toFloat() / photo.height)
                val left = (preview.width - photo.width * scale) / 2
                val top = (preview.height - photo.height * scale) / 2
                val now = SystemClock.uptimeMillis()
                fun touch(action: Int, x: Float, y: Float) {
                    MotionEvent.obtain(now, now, action, left + x * photo.width * scale, top + y * photo.height * scale, 0).also {
                        preview.dispatchTouchEvent(it); it.recycle()
                    }
                }
                touch(MotionEvent.ACTION_DOWN, .4f, .5f)
                touch(MotionEvent.ACTION_UP, .7f, .95f)
            }
            waitFor("manual segmentation") { preview.layers.singleOrNull()?.box?.label == "수동 영역" }
            assertTrue(preview.layers.single().area > 100)
            val manualPixels = IntArray(reference.size)
            preview.layers.single().bitmap.getPixels(manualPixels, 0, preview.photo!!.width, 0, 0,
                preview.photo!!.width, preview.photo!!.height)
            var manualIntersection = 0; var manualUnion = 0
            for (i in manualPixels.indices) {
                val actual = manualPixels[i] ushr 24 != 0
                val expected = reference[i] != 0.toByte()
                if (actual && expected) manualIntersection++
                if (actual || expected) manualUnion++
            }
            val manualIou = manualIntersection.toDouble() / manualUnion
            assertTrue("Touch rectangle / reference mask IoU=$manualIou", manualIou >= .98)
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot().use { screenshot ->
                File(context.filesDir, "mobilesam-manual.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            File(context.filesDir, "mobilesam-device-result.json").writeText(JSONObject().apply {
                put("androidDesktopMaskIoU", maskIou)
                put("mobileSamInferenceMs", mobileSamMs)
                put("mobileSamInitAndRunMs", initAndRunMs)
                put("yoloDetections", detections.size)
                put("tactileDetections", tactileCount)
                put("manualTouchMaskIoU", manualIou)
                put("automaticUi", "passed")
                put("manualUi", "passed")
                put("device", android.os.Build.MODEL)
                put("note", "Emulator measurements are not physical-phone latency or segmentation accuracy.")
            }.toString(2))
            for (sample in listOf("어두운 점자블록", "차량 진출입로")) {
                instrumentation.runOnMainSync {
                    all.filterIsInstance<Button>().first { it.text == "예제 사진" }.performClick()
                }
                instrumentation.waitForIdleSync()
                val item = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(sample)
                    .first { it.text?.toString() == sample }
                assertTrue(item.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("sample picker: $sample") {
                    all.filterIsInstance<TextView>().any { it.text == "예제: $sample" } && all.filterIsInstance<Button>().all { it.isEnabled }
                }
                assertTrue("New photo clears old masks", preview.layers.isEmpty())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}

private inline fun <T> Bitmap.use(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }
