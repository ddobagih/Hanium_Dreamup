package kr.co.hanium.dreamup.walksafe.mobilesam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage
import kr.co.hanium.dreamup.walksafe.inference.ModelRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** ponytail: frozen photos first; add live frames after device latency and alignment are measured. */
class MobileSamTestActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var detector: TfliteAndroidFrameDetector? = null
    private var sam: MobileSamRunner? = null
    private var pendingCapture: Uri? = null
    private lateinit var preview: SamPreview
    private lateinit var status: TextView
    private lateinit var photoCaption: TextView
    private lateinit var tactileOnly: CheckBox
    private val controls = mutableListOf<View>()
    private var busy = false
    private var photoRequest = 0

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadPhoto { decodePhoto(it) } }
    }
    private val capturePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture
        pendingCapture = null
        if (saved && uri != null) loadPhoto { decodePhoto(uri) }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else status.text = "촬영 권한이 없습니다. 사진 불러오기를 사용할 수 있습니다."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCapture = savedInstanceState?.getString("capture")?.let(Uri::parse)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 24, 39))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(12), dp(8) + insets.systemWindowInsetTop, dp(12), dp(8) + insets.systemWindowInsetBottom)
            insets
        }
        root.addView(text("WalkMate · MobileSAM 시험 v1.1", 21f))
        root.addView(text("${BuildConfig.VERSION_NAME} · 사진 분할 비교 · 경로 안내 없음", 12f))
        fun row(): LinearLayout = LinearLayout(this).also { root.addView(it) }
        fun button(row: LinearLayout, title: String, action: () -> Unit) {
            val button = Button(this).apply { text = title; textSize = 12f; setOnClickListener { action() } }
            controls += button
            row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        row().let { row ->
            button(row, "사진 불러오기") { pickPhoto.launch("image/*") }
            button(row, "촬영") { takePhoto() }
            button(row, "예제 사진") {
                android.app.AlertDialog.Builder(this).setTitle("비교할 예제 사진")
                    .setItems(arrayOf("점자블록과 횡단보도", "어두운 점자블록", "차량 진출입로")) { _, index ->
                        loadSample(index)
                    }.show()
            }
        }
        tactileOnly = CheckBox(this).apply {
            text = "점자블록만 분할"; isChecked = true; setTextColor(Color.WHITE); textSize = 13f
        }
        controls += tactileOnly
        row().let { row ->
            row.addView(tactileOnly, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(CheckBox(this).apply {
                text = "마스크 표시"; isChecked = true; setTextColor(Color.WHITE); textSize = 13f
                setOnCheckedChangeListener { _, checked -> preview.showMasks = checked; preview.invalidate() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        row().let { row ->
            button(row, "YOLO → MobileSAM") { runAutomatic() }
            button(row, "영역 직접 지정") {
                if (preview.photo != null) {
                    preview.selecting = true
                    status.text = "사진에서 원하는 대상을 감싸도록 드래그하세요.\n수동 영역에는 객체 이름을 자동으로 붙이지 않습니다."
                }
            }
        }
        root.addView(text("초록: YOLO 상자 · 색칠: MobileSAM 분할 · 최대 3개", 12f))
        photoCaption = text("", 12f).also { root.addView(it) }
        preview = SamPreview(this).apply { onBox = { box -> runManual(box) } }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        status = text("사진을 불러오는 중…", 13f).apply { setTextIsSelectable(true); setPadding(0, dp(6), 0, 0) }
        root.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(-1, dp(116)))
        root.addView(text("예제 사진 출처 보기 · Wikimedia Commons · 축소본", 10f).apply {
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MobileSamTestActivity).setTitle("예제 사진 출처")
                    .setMessage(assets.open("mobilesam-sample/ATTRIBUTION.txt").bufferedReader().use { it.readText() })
                    .setPositiveButton("닫기", null).show()
            }
            contentDescription = "예제 사진 출처 자세히 보기"
        })
        setContentView(root)
        loadSample()
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun setBusy(value: Boolean, message: String) {
        busy = value; controls.forEach { it.isEnabled = !value }
        preview.selecting = false; status.text = message
    }
    private fun loadSample(index: Int = 0) {
        val names = arrayOf("japan_crosswalk", "tactile_crosswalk", "driveway")
        val labels = arrayOf("점자블록과 횡단보도", "어두운 점자블록", "차량 진출입로")
        loadPhoto("예제: ${labels[index]}") {
            assets.open("mobilesam-sample/${names[index]}.jpg").use {
                requireNotNull(BitmapFactory.decodeStream(it)) { "예제 사진을 읽지 못했습니다." }
            }
        }
    }
    private fun loadPhoto(source: String = "선택한 사진", read: () -> Bitmap) {
        val request = ++photoRequest
        setBusy(true, "사진을 불러오는 중…")
        worker.execute {
            try {
                val bitmap = read()
                runOnUiThread {
                    if (!isDestroyed && request == photoRequest) {
                        preview.replacePhoto(bitmap)
                        photoCaption.text = source
                        setBusy(false, "YOLO → MobileSAM을 누르세요.\n점자블록이 탐지되지 않으면 ‘영역 직접 지정’으로 분할만 비교할 수 있습니다.")
                    } else bitmap.recycle()
                }
            } catch (error: Exception) {
                runOnUiThread { if (request == photoRequest && !isDestroyed) showError(error) }
            }
        }
    }

    private fun runAutomatic() {
        val photo = preview.photo ?: return
        if (busy) return
        val onlyTactile = tactileOnly.isChecked
        preview.clearResults()
        setBusy(true, "YOLO로 객체를 찾는 중… (처음에는 모델을 불러옵니다)")
        worker.execute {
            try {
                val yolo = detector ?: TfliteAndroidFrameDetector.createWithStatus(applicationContext,
                    ModelRuntimeOptions(delegate = "cpu", numThreads = 2)).let {
                    requireNotNull(it.detector) { "YOLO 로드 실패: ${it.reason}" }.also { model -> detector = model }
                }
                val pixels = IntArray(photo.width * photo.height)
                photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
                val start = SystemClock.elapsedRealtime()
                val detections = yolo.detectCalibrationImage(ArgbImage(photo.width, photo.height, pixels)).detections
                    .sortedByDescending { it.detectionConfidence }
                val yoloMs = SystemClock.elapsedRealtime() - start
                val pairs = detections.mapNotNull { detection ->
                    val r = detection.bboxNorm
                    val left = r.x.coerceIn(0f, 1f); val top = r.y.coerceIn(0f, 1f)
                    val right = (r.x + r.width).coerceIn(0f, 1f); val bottom = (r.y + r.height).coerceIn(0f, 1f)
                    if (right <= left || bottom <= top || !listOf(left, top, right, bottom).all { it.isFinite() }) null
                    else detection to SamBox(left, top, right, bottom,
                        "${detection.className} ${(detection.detectionConfidence * 100).toInt()}%")
                }
                val selected = pairs.filter { !onlyTactile || it.first.className in MobileSamGeometry.tactileClasses }.take(3).map { it.second }
                runOnUiThread { if (!isDestroyed) status.text = "YOLO ${pairs.size}개 탐지 · ${selected.size}개 영역 분할 중…" }
                val samStart = SystemClock.elapsedRealtime()
                val coldSam = sam == null && selected.isNotEmpty()
                val masks = segment(photo, selected)
                val samMs = SystemClock.elapsedRealtime() - samStart
                val message = if (selected.isEmpty()) "분할 대상 0개. YOLO 미탐지를 MobileSAM이 자동으로 보완하지는 않습니다.\n점자블록 필터 해제 또는 수동 지정을 사용하세요."
                    else "YOLO ${pairs.size}개 중 ${selected.size}개 분할 · ${masks.count { layer -> layer.area > 0 }}개 마스크\n" + selected.joinToString("\n") { it.label }
                runOnUiThread { if (!isDestroyed) {
                    preview.boxes = selected; preview.layers = masks; preview.invalidate()
                    setBusy(false, "YOLO ${yoloMs}ms · MobileSAM ${samMs}ms${if (coldSam) " (모델 로드 포함)" else ""}\n$message")
                } }
            } catch (error: Exception) { showError(error) }
        }
    }

    private fun runManual(box: SamBox) {
        val photo = preview.photo ?: return
        if (busy) return
        preview.clearResults()
        setBusy(true, "수동으로 지정한 영역을 MobileSAM으로 분할하는 중…")
        worker.execute {
            try {
                val start = SystemClock.elapsedRealtime()
                val coldSam = sam == null
                val masks = segment(photo, listOf(box))
                val elapsed = SystemClock.elapsedRealtime() - start
                runOnUiThread { if (!isDestroyed) {
                    preview.layers = masks; preview.invalidate()
                    setBusy(false, "수동 영역 · MobileSAM ${elapsed}ms${if (coldSam) " (모델 로드 포함)" else ""}\n마스크 ${masks.first().area}픽셀 · YOLO 자동 탐지 결과가 아닙니다.\n색칠은 영역 분할이며 인도·차도 의미나 거리 판정은 아닙니다.")
                } }
            } catch (error: Exception) { showError(error) }
        }
    }

    private fun segment(photo: Bitmap, boxes: List<SamBox>): List<MaskLayer> {
        if (boxes.isEmpty()) return emptyList()
        val engine = sam ?: MobileSamRunner(applicationContext).also { sam = it }
        val colors = intArrayOf(0x8000d4ff.toInt(), 0x80ffbc47.toInt(), 0x80db7aff.toInt())
        return engine.segment(photo, boxes).mapIndexed { index, mask ->
            MaskLayer(boxes[index], Bitmap.createBitmap(IntArray(mask.size) { if (mask[it]) colors[index] else 0 },
                photo.width, photo.height, Bitmap.Config.ARGB_8888), mask.count { it })
        }
    }

    private fun showError(error: Exception) {
        android.util.Log.e("MobileSamTest", "Photo test failed", error)
        runOnUiThread { if (!isDestroyed) setBusy(false, "실행 실패: ${error.message ?: error.javaClass.simpleName}\n다른 사진으로 다시 시도할 수 있습니다.") }
    }

    private fun takePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        try {
            val directory = File(cacheDir, "mobilesam-captures").apply { mkdirs() }
            val uri = FileProvider.getUriForFile(this, "$packageName.mobilesam.files", File(directory, "photo.jpg"))
            pendingCapture = uri
            capturePhoto.launch(uri)
        } catch (error: Exception) { pendingCapture = null; showError(error) }
    }

    private fun decodePhoto(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = min(1.0, 1280.0 / max(info.size.width, info.size.height))
                decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원하지 않는 사진입니다." }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null,
            BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("사진을 읽지 못했습니다.")
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCapture?.let { outState.putString("capture", it.toString()) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        // Let the single in-flight native call finish before releasing its sessions/bitmap.
        worker.execute { sam?.close(); detector?.close() }
        worker.shutdown()
        super.onDestroy()
    }
}

data class MaskLayer(val box: SamBox, val bitmap: Bitmap, val area: Int)

class SamPreview(context: android.content.Context) : View(context) {
    var photo: Bitmap? = null; private set
    var boxes = emptyList<SamBox>()
    var layers = emptyList<MaskLayer>()
    var showMasks = true
    var selecting = false
    var onBox: (SamBox) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var start: PointF? = null
    private var end: PointF? = null

    init { contentDescription = "사진과 YOLO 상자, MobileSAM 영역 비교. 수동 지정 모드에서는 드래그하세요." }
    fun clearResults() {
        layers.forEach { it.bitmap.recycle() }; layers = emptyList(); boxes = emptyList()
        start = null; end = null; invalidate()
    }
    fun replacePhoto(bitmap: Bitmap) {
        clearResults(); photo?.recycle(); photo = bitmap; invalidate()
    }
    private fun imageRect(): RectF {
        val bitmap = photo ?: return RectF()
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = photo ?: return
        val rect = imageRect()
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(bitmap, null, rect, paint)
        if (showMasks) layers.forEach { canvas.drawBitmap(it.bitmap, null, rect, paint) }
        canvas.save(); canvas.clipRect(rect)
        fun box(box: SamBox, color: Int) {
            val x = rect.left + box.left * rect.width(); val y = rect.top + box.top * rect.height()
            paint.color = color; paint.strokeWidth = 2.5f; paint.style = Paint.Style.STROKE
            canvas.drawRect(x, y, rect.left + box.right * rect.width(), rect.top + box.bottom * rect.height(), paint)
            paint.style = Paint.Style.FILL; paint.textSize = 11 * resources.displayMetrics.scaledDensity
            canvas.drawText(box.label, x + 2, max(rect.top + paint.textSize, y - 3), paint)
        }
        boxes.forEach { box(it, Color.GREEN) }
        layers.filter { it.box.label == "수동 영역" }.forEach { box(it.box, Color.CYAN) }
        val a = start; val b = end
        if (a != null && b != null) {
            paint.style = Paint.Style.STROKE; paint.color = Color.CYAN; paint.strokeWidth = 3f
            canvas.drawRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), paint)
        }
        paint.style = Paint.Style.FILL; canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selecting || photo == null) return false
        val rect = imageRect()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!rect.contains(event.x, event.y)) return false
                start = PointF(event.x, event.y); end = start; parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> end = PointF(event.x.coerceIn(rect.left, rect.right), event.y.coerceIn(rect.top, rect.bottom))
            MotionEvent.ACTION_UP -> {
                val a = start ?: return false
                val b = PointF(event.x.coerceIn(rect.left, rect.right), event.y.coerceIn(rect.top, rect.bottom))
                start = null; end = null; parent.requestDisallowInterceptTouchEvent(false); performClick()
                if (abs(a.x - b.x) >= 12 && abs(a.y - b.y) >= 12) {
                    selecting = false
                    onBox(SamBox((min(a.x, b.x) - rect.left) / rect.width(), (min(a.y, b.y) - rect.top) / rect.height(),
                        (max(a.x, b.x) - rect.left) / rect.width(), (max(a.y, b.y) - rect.top) / rect.height(), "수동 영역"))
                }
            }
            MotionEvent.ACTION_CANCEL -> { start = null; end = null; parent.requestDisallowInterceptTouchEvent(false) }
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
