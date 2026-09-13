package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.YuvPreprocessingStrategy
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Local debug viewer: no account, route, walk lease, speech, report, or device-check state. */
class CameraTestActivity : Activity(), GLSurfaceView.Renderer {
    private lateinit var surface: GLSurfaceView
    private lateinit var boxes: DebugBboxOverlayView
    private lateinit var unknownBoxes: DebugBboxOverlayView
    private lateinit var unknownStatus: TextView
    private var unknownService: FastSamRuntimeService<UnknownCapture>? = null
    private var unknownPipeline = UnknownObjectDepthPipeline(SystemClock::elapsedRealtime)
    private data class UnknownCapture(val epoch: Int, val view: FloatArray,
        val depth: FrozenUnknownDepthCapture, val pipeline: UnknownObjectDepthPipeline)
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var foreground = false
    @Volatile private var generation = 0
    @Volatile private var session: Session? = null
    @Volatile private var provider: ArCoreFrameProvider? = null
    @Volatile private var detector: TfliteAndroidFrameDetector? = null
    private var closingCamera = false
    private var installRequested = false
    private var background = CameraBackgroundRenderer()
    private var texture = 0
    private var width = 0
    private var height = 0
    private var lastFrame = 0L
    @Volatile private var lastNoticeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@CameraTestActivity)
        }
        boxes = DebugBboxOverlayView(this)
        unknownBoxes = DebugBboxOverlayView(this)
        unknownStatus = TextView(this).apply {
            text = "이름 없는 영역 · FastSAM 준비 중"
            textSize = 15f
            setTextColor(Color.YELLOW)
            setBackgroundColor(0xcc000000.toInt())
        }
        status = TextView(this).apply {
            text = "테스트 카메라 · 모델 준비 중\n안내 시작 없이 실행합니다."
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xcc000000.toInt())
            setPadding(18, 12, 18, 12)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(unknownStatus)
            addView(Button(this@CameraTestActivity).apply {
                text = "카메라 다시 연결"
                setOnClickListener { startCamera() }
            })
            addView(Button(this@CameraTestActivity).apply {
                text = "카메라 닫기"
                setOnClickListener { finish() }
            })
        }
        setContentView(FrameLayout(this).apply {
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            addView(boxes, FrameLayout.LayoutParams(-1, -1))
            addView(unknownBoxes, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        worker.execute {
            val loaded = runCatching { TfliteAndroidFrameDetector.createWithStatus(
                applicationContext, preprocessingStrategy = YuvPreprocessingStrategy.FUSED_YUV_TO_TENSOR,
            ) }.onFailure { Log.e("WalkSafeCameraTest", "Model initialization failed", it) }.getOrNull()
            detector = loaded?.detector
            Log.i("WalkSafeCameraTest", "Model initialization: ${loaded?.reason ?: "exception"}")
            if (detector == null) runOnUiThread {
                status.text = "카메라 테스트 · 객체 모델 로드 실패: ${loaded?.reason ?: "exception"}"
            }
        }
        val auxiliary = FastSamRuntimeService(applicationContext, 1L,
            FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.GPU,
                minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1), true,
                SystemClock.elapsedRealtimeNanos() + 60_000_000_000L, 1_000L, false, false, true),
            object : FastSamRuntimeService.Listener<UnknownCapture> {
                override fun onResult(result: FastSamResult<UnknownCapture>) = showUnknownResult(result)
                override fun onError(error: Throwable) {
                    Log.e("WalkSafeCameraTest", "FastSAM failed", error)
                    runOnUiThread { if (!isDestroyed) unknownStatus.text = "이름 없는 영역 실패: ${error.javaClass.simpleName}" }
                }
            })
        unknownService = auxiliary
        auxiliary.start().whenComplete { info, error -> runOnUiThread {
            if (!isDestroyed) unknownStatus.text = if (error == null) "이름 없는 영역 · ${info.actualBackend} 준비됨"
                else "이름 없는 영역 모델 준비 실패"
        } }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        startCamera()
    }

    private fun startCamera() {
        if (!foreground || isDestroyed || isFinishing || closingCamera || session != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        var owned: Session? = null
        try {
            if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }
            owned = Session(this)
            val source = ArCoreFrameProvider(owned)
            val depthSupported = source.configureDepthMode()
            owned.resume()
            generation++
            unknownPipeline = UnknownObjectDepthPipeline(SystemClock::elapsedRealtime)
            lastFrame = 0L
            provider = source
            session = owned
            surface.onResume()
            status.text = if (depthSupported) "Depth 지원 · 실제 프레임을 기다리는 중"
                else "Depth 미지원 · 카메라와 객체 인식만 표시합니다."
        } catch (error: Exception) {
            owned?.let { camera -> worker.execute { runCatching { camera.close() } } }
            status.text = "카메라 시작 실패: ${error.javaClass.simpleName}\n카메라를 사용하는 다른 앱과 AR 서비스 설치 상태를 확인하세요."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else status.text = "실시간 영상을 보려면 카메라 권한이 필요합니다."
    }

    override fun onPause() {
        foreground = false
        generation++
        surface.onPause() // Stop Session.update before pausing or closing the camera.
        val camera = session
        val source = provider
        session = null
        provider = null
        boxes.clear()
        unknownBoxes.clear()
        unknownService?.discardPending("camera_test_paused")
        if (camera != null) {
            closingCamera = true
            runCatching { camera.pause() }
            worker.execute {
                // Queued inference returns its Image before native camera resources are released.
                runCatching { source?.close() }
                runCatching { camera.close() }
                runOnUiThread {
                    closingCamera = false
                    if (foreground && !isDestroyed && !isFinishing) startCamera()
                }
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        unknownService?.closeAsync()
        unknownService = null
        worker.execute { detector?.close(); detector = null }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        background = CameraBackgroundRenderer().also { it.createOnGlThread() }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val camera = session ?: return
        val source = provider ?: return
        if (!foreground || width <= 0 || height <= 0) return
        val epoch = generation
        try {
            camera.setCameraTextureName(texture)
            @Suppress("DEPRECATION")
            camera.setDisplayGeometry(windowManager.defaultDisplay.rotation, width, height)
            val frame = camera.update()
            background.draw(frame, texture)
            if (frame.timestamp <= lastFrame) return
            lastFrame = frame.timestamp
            val model = detector ?: return
            if (!busy.compareAndSet(false, true)) return
            var transferred = false
            var image: android.media.Image? = null
            try {
                // Depth is optional evidence; its failure must not discard a usable RGB frame.
                val depthResult = runCatching {
                    source.acquireDepthSnapshot(frame, includeFullDepthWhenRawAvailable = true)
                }
                val snapshot = depthResult.getOrElse {
                    DepthFrameSnapshot(frame.timestamp, null, null, null)
                }
                image = source.acquireCameraImageOrNull(frame) ?: return
                val ownedImage = image
                val basis = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
                val view = FloatArray(6)
                val depth = FloatArray(6)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, basis, Coordinates2d.VIEW, view)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, basis, Coordinates2d.TEXTURE_NORMALIZED, depth)
                val frameId = frame.timestamp
                val capturedMs = SystemClock.elapsedRealtime()
                val tracking = frame.camera.trackingState.name
                val trackingFailure = frame.camera.trackingFailureReason.name
                val turns = UprightCameraImage.quarterTurns(view)
                val auxiliary = unknownService
                val pipeline = unknownPipeline
                worker.execute {
                    try {
                        if (auxiliary != null && foreground && epoch == generation) {
                            val state = auxiliary.stats()
                            if (!state.inflight && !state.pending && !state.stopping) {
                                val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,
                                    auxiliary.sessionEpoch, frameId, frameId, ownedImage.timestamp,
                                    capturedMs * 1_000_000L, "camera-test:$epoch", ownedImage.width, ownedImage.height)
                                val h = doubleArrayOf(
                                    (depth[2] - depth[0]).toDouble() / ownedImage.width,
                                    (depth[4] - depth[0]).toDouble() / ownedImage.height, depth[0].toDouble(),
                                    (depth[3] - depth[1]).toDouble() / ownedImage.width,
                                    (depth[5] - depth[1]).toDouble() / ownedImage.height, depth[1].toDouble(),
                                    0.0, 0.0, 1.0)
                                val capture = FrozenUnknownDepthCapture.freeze(token,
                                    snapshot.copy(cameraImageTimestampNs = ownedImage.timestamp), h, frameId,
                                    imageQuarterTurns = turns)
                                if (capture != null) runCatching {
                                    auxiliary.submit(ownedImage, token, UnknownCapture(epoch, view, capture, pipeline))
                                }.onFailure { Log.w("WalkSafeCameraTest", "FastSAM frame submission failed", it) }
                            }
                        }
                        val meanLuma = meanLuma(ownedImage)
                        val result = model.detectOriented(ownedImage, frameId / 1_000_000L, turns)
                        val mapped = result.detections.map { item ->
                            val rect = item.bboxNorm
                            val corners = listOf(point(view, rect.x, rect.y),
                                point(view, rect.x + rect.width, rect.y),
                                point(view, rect.x, rect.y + rect.height),
                                point(view, rect.x + rect.width, rect.y + rect.height))
                            val uv = point(depth, rect.x + rect.width / 2, rect.y + rect.height / 2)
                            val distance = centerDepth(snapshot, uv.first, uv.second)
                            val label = "분류 ${item.className} ${String.format(Locale.US, "%.0f%%", item.detectionConfidence * 100)} · " +
                                (distance?.let { String.format(Locale.US, "중앙 깊이 %.2fm", it) } ?: "깊이 대기")
                            DebugBboxOverlayView.DebugOverlayBox(RectF(corners.minOf { it.first },
                                corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second }),
                                label, false, item.className)
                        }
                        val message = "테스트 카메라 · ARCore $tracking\n" +
                            "객체 ${result.detections.size}개 · 추론 ${result.timing.modelInferenceMs}ms\n" +
                            "센서 영상 → 화면 방향 보정 ${turns * 90}° (휴대폰을 돌리라는 뜻이 아닙니다)\n" +
                            "Raw Depth ${snapshot.hasMetricRawDepth} · Full Depth ${snapshot.hasFullDepth}\n" +
                            (if (tracking != "TRACKING") "깊이 추적 대기: $trackingFailure\n" else "") +
                            (depthResult.exceptionOrNull()?.let { "깊이 오류: ${it.javaClass.simpleName} · 객체 추론은 계속합니다.\n" } ?: "") +
                            (if (meanLuma < 25) "입력 영상이 매우 어둡습니다. 렌즈 가림과 조명을 확인하세요.\n"
                                else if (result.detections.isEmpty()) "모델 실행 정상 · 학습한 21개 클래스 중 검출 없음\n" else "") +
                            mapped.take(4).joinToString("\n") { it.label }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNoticeMs > 2000) {
                            lastNoticeMs = now
                            Log.i("WalkSafeCameraTest", "inferenceMs=${result.timing.modelInferenceMs} objects=${mapped.size} meanLuma=$meanLuma tracking=$tracking/$trackingFailure depthError=${depthResult.exceptionOrNull()?.javaClass?.simpleName}")
                        }
                        runOnUiThread {
                            if (foreground && epoch == generation) {
                                boxes.updateMapped(mapped, frameId, capturedMs, sourceComplete = true, animate = false)
                                status.text = message
                            }
                        }
                    } catch (error: Exception) {
                        Log.e("WalkSafeCameraTest", "Object inference failed", error)
                        showError(epoch, "객체 인식 실패: ${error.javaClass.simpleName}")
                    } finally {
                        ownedImage.close()
                        busy.set(false)
                    }
                }
                transferred = true
            } finally {
                if (!transferred) { image?.close(); busy.set(false) }
            }
        } catch (error: Exception) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNoticeMs > 1000) {
                lastNoticeMs = now
                Log.w("WalkSafeCameraTest", "Camera frame failed", error)
                showError(epoch, "카메라 프레임 대기: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun showError(epoch: Int, message: String) = runOnUiThread {
        if (foreground && epoch == generation) { status.text = message; boxes.clear() }
    }

    private fun showUnknownResult(result: FastSamResult<UnknownCapture>) {
        val capture = result.attachment
        if (!foreground || capture.epoch != generation) return
        val processed = capture.pipeline.process(capture.depth, result.token, result.masks, SystemClock.elapsedRealtime())
        val candidates = processed.observations.filter { it.walkingSelection?.show == true }
            .sortedWith(compareBy({ it.depth.axialDepthM == null }, { it.depth.axialDepthM ?: Double.MAX_VALUE }))
        // Cap only this diagnostic display. Warning admission uses all eligible candidates in the shared pipeline.
        val selected = candidates.filterIndexed { index, observation ->
            candidates.take(index).none { earlier ->
                observation.mask.iou(earlier.mask) >= 0.70f &&
                    observation.depth.axialDepthM?.let { z -> earlier.depth.axialDepthM?.let {
                        kotlin.math.abs(z - it) <= 0.30
                    } } == true
            }
        }.take(6)
        val mapped = selected.map { observation ->
            val index = observation.sourceDetectionIndex
            val mask = result.masks[index]
            val view = capture.view
            val corners = listOf(point(view, mask.bboxLeft() / mask.imageWidth(), mask.bboxTop() / mask.imageHeight()),
                point(view, mask.bboxRight() / mask.imageWidth(), mask.bboxTop() / mask.imageHeight()),
                point(view, mask.bboxLeft() / mask.imageWidth(), mask.bboxBottom() / mask.imageHeight()),
                point(view, mask.bboxRight() / mask.imageWidth(), mask.bboxBottom() / mask.imageHeight()))
            val depth = observation.depth
            val output = processed.objects.firstOrNull { it.trackId == observation.trackId }
            val distance = depth.axialDepthM
            val kind = if (depth.source == "ARCORE_FULL_DEPTH") "Full 추정" else "Raw 측정"
            val alert = when (output?.userFacing?.messageLevel) {
                kr.co.hanium.dreamup.walksafe.depth.MessageLevel.STOP -> " · 정지 경고 후보"
                kr.co.hanium.dreamup.walksafe.depth.MessageLevel.WARNING -> " · 주의 경고 후보"
                else -> ""
            }
            val label = "전방 영역 · " + (distance?.let { String.format(Locale.US, "%s %.2fm", kind, it) }
                ?: "거리 미확인") + alert
            DebugBboxOverlayView.DebugOverlayBox(RectF(corners.minOf { it.first }, corners.minOf { it.second },
                corners.maxOf { it.first }, corners.maxOf { it.second }), label, true, "unnamed-obstacle")
        }
        runOnUiThread {
            if (foreground && capture.epoch == generation) {
                unknownBoxes.updateMapped(mapped, result.token.frameId, result.token.capturedElapsedNs / 1_000_000L,
                    sourceComplete = true, animate = false)
                val measured = processed.observations.count { it.depth.axialDepthM != null }
                val waiting = processed.observations.firstOrNull { it.depth.axialDepthM == null }?.depth?.reason
                unknownStatus.text = "FastSAM ${result.masks.size}개 → 전방 후보 ${candidates.size}개 · 표시 ${mapped.size}개\n" +
                    "거리 확인 ${measured}개 · 추론 ${result.inferenceMs.toLong()}ms\n" +
                    "테스트 화면: 음성 없음 · 실제 안내 중 경고 조건 충족 시 음성/진동\n" +
                    (processed.rejectionReason?.let { "깊이 대기: $it" }
                        ?: if (measured == 0) "거리 대기: ${waiting ?: "인식 영역 없음"}" else "")
            }
        }
    }

    private fun point(basis: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        Pair(basis[0] + x * (basis[2] - basis[0]) + y * (basis[4] - basis[0]),
            basis[1] + x * (basis[3] - basis[1]) + y * (basis[5] - basis[1]))

    /** Sparse read of the actual model input image; does not retain or upload camera pixels. */
    private fun meanLuma(image: android.media.Image): Int {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val base = buffer.position()
        var sum = 0L
        var count = 0
        for (y in 0 until image.height step maxOf(1, image.height / 16)) {
            for (x in 0 until image.width step maxOf(1, image.width / 16)) {
                val index = base + y * plane.rowStride + x * plane.pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xff
                    count++
                }
            }
        }
        return if (count > 0) (sum / count).toInt() else 0
    }

    /** Diagnostic center patch only; no claim of whole-object distance or motion. */
    private fun centerDepth(snapshot: DepthFrameSnapshot, u: Float, v: Float): Double? {
        if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val depth = snapshot.fullDepth?.takeIf { snapshot.hasFreshFullDepth } ?: return null
        val x = (u * depth.width).toInt().coerceIn(0, depth.width - 1)
        val y = (v * depth.height).toInt().coerceIn(0, depth.height - 1)
        val values = (-1..1).flatMap { dy -> (-1..1).mapNotNull { dx ->
            val px = x + dx; val py = y + dy
            if (px !in 0 until depth.width || py !in 0 until depth.height) null
            else depth.millimeters[py * depth.width + px].takeIf { it > 0 }
        } }.sorted()
        return values.takeIf { it.size >= 3 }?.let { it[it.size / 2] / 1000.0 }
    }
}
