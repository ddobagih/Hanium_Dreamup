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
import android.os.Handler
import android.os.Looper
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
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicy
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicyConfig
import kr.co.hanium.dreamup.walksafe.depth.MessageRateLimitConfig
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.ObjectTracker
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.YuvPreprocessingStrategy
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeService
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamRuntimeOptions
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.DepthPredictionPresentation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Local debug camera and hazard feedback; no account, route, walk lease or report is opened. */
class CameraTestActivity : Activity(), GLSurfaceView.Renderer {
    private lateinit var surface: GLSurfaceView
    private lateinit var boxes: DebugBboxOverlayView
    private lateinit var unknownBoxes: DebugBboxOverlayView
    private lateinit var unknownStatus: TextView
    private lateinit var feedbackStatus: TextView
    private val feedback = CameraTestFeedbackCoordinator()
    private val objectPresentation = CameraObjectPresentation()
    private var primaryOverlayCapture: CameraObjectPresentation.Capture? = null
    private var unknownOverlay: UnknownOverlay? = null
    private data class UnknownOverlay(
        val capture: CameraObjectPresentation.Capture,
        val regions: List<CameraObjectPresentation.Region>,
        val boxes: List<DebugBboxOverlayView.DebugOverlayBox>,
        val predictionDeadlinesMs: List<Long?>,
        val rawCount: Int,
        val candidateCount: Int,
        val detail: String,
    )
    private val feedbackHandler = Handler(Looper.getMainLooper())
    private var feedbackActuator: AndroidFeedbackActuator? = null
    private var speechReadiness = "한국어 음성 준비 중"
    private var lastFeedbackEvent = ""
    private var lastFeedbackLog = ""
    private var lastFeedbackLogMs = 0L
    private var unknownFeedbackPolicy = UnknownObjectFeedbackPolicy()
    private val unknownRuntime = CameraUnknownRuntime<UnknownCapture>(
        create = ::createUnknownService,
        dispatch = { action -> runOnUiThread { action() } },
        changed = ::showUnknownRuntimeStatus,
    )
    private var unknownPipeline = UnknownObjectDepthPipeline(SystemClock::elapsedRealtime)
    private var primaryDepth = PrimaryDepthState(0)
    private class PrimaryDepthState(private val epoch: Int) {
        private var pipelineGeneration = 0
        var geometryId: String? = null
        var pipeline = createPipeline()
        var sourceGate = CameraTestDepthSourceGate()
        fun forGeometry(id: String): ObjectDepthRuntimePipeline {
            if (geometryId != id) { reset(); geometryId = id }
            return pipeline
        }
        fun reset() {
            geometryId = null
            pipelineGeneration++
            pipeline = createPipeline()
            sourceGate = CameraTestDepthSourceGate()
        }
        private fun createPipeline() = ObjectDepthRuntimePipeline(
            tracker = ObjectTracker(trackIdPrefix = "camera-$epoch-primary-$pipelineGeneration-"),
            rawDepthMotionOnly = true,
            messagePolicy = MessagePolicy(config = MessagePolicyConfig(
                rateLimit = MessageRateLimitConfig(0L, 0L, 0L))),
        )
    }
    private data class UnknownCapture(val epoch: Int, val view: FloatArray,
        val depth: FrozenUnknownDepthCapture, val pipeline: UnknownObjectDepthPipeline,
        val feedbackPolicy: UnknownObjectFeedbackPolicy)
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
        feedbackStatus = TextView(this).apply {
            text = "위험 조건 충족 시 음성·진동 · 안내 시작 불필요"
            textSize = 14f
            setTextColor(Color.WHITE)
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
            addView(feedbackStatus)
            addView(Button(this@CameraTestActivity).apply {
                text = "카메라 다시 연결"
                setOnClickListener { startCamera(); unknownRuntime.retry() }
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
    }

    private fun createUnknownService(epoch: Long): FastSamRuntimeService<UnknownCapture> {
        // Publish fresh tracker/policy state before the new service becomes visible to the GL worker.
        unknownPipeline = UnknownObjectDepthPipeline(SystemClock::elapsedRealtime)
        unknownFeedbackPolicy = UnknownObjectFeedbackPolicy()
        return FastSamRuntimeService(applicationContext, epoch,
            FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.GPU,
                minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1), true,
                SystemClock.elapsedRealtimeNanos() + 60_000_000_000L, 1_000L, false, false, true),
            object : FastSamRuntimeService.Listener<UnknownCapture> {
                override fun onResult(result: FastSamResult<UnknownCapture>) = showUnknownResult(result)
                override fun onError(error: Throwable) {
                    Log.e("WalkSafeCameraTest", "FastSAM failed", error)
                    unknownRuntime.failed(epoch)
                }
            })
    }

    private fun showUnknownRuntimeStatus() {
        if (!foreground || isDestroyed || isFinishing) return
        val state = unknownRuntime.status
        if (state != CameraUnknownRuntime.Status.READY) {
            unknownOverlay = null
            unknownBoxes.clear()
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
        unknownStatus.text = when (state) {
            CameraUnknownRuntime.Status.IDLE, CameraUnknownRuntime.Status.LOADING -> "이름 없는 영역 · FastSAM 준비 중"
            CameraUnknownRuntime.Status.READY -> "이름 없는 영역 · ${unknownRuntime.backendName ?: "FastSAM"} 준비됨"
            CameraUnknownRuntime.Status.FAILED -> "이름 없는 영역 실패 · 카메라 다시 연결을 눌러 재시도하세요"
            CameraUnknownRuntime.Status.WAITING_RELEASE -> "이름 없는 영역 · 이전 모델 종료를 기다리는 중"
            CameraUnknownRuntime.Status.RELEASE_FAILED -> "이름 없는 영역 · 이전 모델 해제 미확인으로 재시작할 수 없습니다"
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        startCamera()
        unknownRuntime.resume()
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
            unknownFeedbackPolicy = UnknownObjectFeedbackPolicy()
            primaryDepth = PrimaryDepthState(generation)
            objectPresentation.clear()
            primaryOverlayCapture = null
            unknownOverlay = null
            startFeedback(generation)
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
        unknownRuntime.pause()
        stopFeedback()
        surface.onPause() // Stop Session.update before pausing or closing the camera.
        val camera = session
        val source = provider
        session = null
        provider = null
        objectPresentation.clear()
        primaryOverlayCapture = null
        unknownOverlay = null
        boxes.clear()
        unknownBoxes.clear()
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
        stopFeedback()
        foreground = false
        generation++
        unknownRuntime.destroy()
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
                val capturedSnapshot = snapshot.copy(cameraImageTimestampNs = ownedImage.timestamp)
                val basis = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0.5f, 0.5f)
                val view = FloatArray(10)
                val depth = FloatArray(10)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, basis, Coordinates2d.VIEW, view)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, basis, Coordinates2d.TEXTURE_NORMALIZED, depth)
                val frameId = frame.timestamp
                val capturedMs = SystemClock.elapsedRealtime()
                val tracking = frame.camera.trackingState.name
                val trackingFailure = frame.camera.trackingFailureReason.name
                val turns = UprightCameraImage.quarterTurns(view)
                val geometryId = "camera-test:$epoch:${ownedImage.width}x${ownedImage.height}:$turns"
                val frozenTransform = FrozenImageToDepthTransform.create(frameId,
                    depth.toList().chunked(2).take(4).map { Point2(it[0], it[1]) }, Point2(depth[8], depth[9]))
                val grid = capturedSnapshot.rawDepth ?: capturedSnapshot.fullDepth
                // With no depth pixels, the current normalized transform still lets the tracked
                // object reach the nonmetric gap estimator. The 1x1 size is never sampled.
                val mapper = if (frozenTransform != null) FrozenImageToTextureCoordinateMapper(
                    frameId, frozenTransform, ImageSize(grid?.width ?: 1, grid?.height ?: 1)) else null
                val auxiliary = unknownRuntime.service
                val pipeline = unknownPipeline
                val auxiliaryFeedbackPolicy = unknownFeedbackPolicy
                val primary = primaryDepth
                worker.execute {
                    try {
                        if (auxiliary != null && foreground && epoch == generation &&
                            unknownRuntime.accepts(auxiliary.sessionEpoch)) {
                            val state = auxiliary.stats()
                            if (!state.inflight && !state.pending && !state.stopping) {
                                val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,
                                    auxiliary.sessionEpoch, frameId, frameId, ownedImage.timestamp,
                                    capturedMs * 1_000_000L, geometryId, ownedImage.width, ownedImage.height)
                                val h = frozenTransform?.imagePixelsToDepthUvMatrix(ownedImage.width, ownedImage.height)
                                val capture = FrozenUnknownDepthCapture.freeze(token,
                                    capturedSnapshot, h, frameId,
                                    imageQuarterTurns = turns)
                                if (capture != null) runCatching {
                                    auxiliary.submit(ownedImage, token, UnknownCapture(epoch, view, capture, pipeline,
                                        auxiliaryFeedbackPolicy))
                                }.onFailure { Log.w("WalkSafeCameraTest", "FastSAM frame submission failed", it) }
                            }
                        }
                        val meanLuma = meanLuma(ownedImage)
                        val result = model.detectOriented(ownedImage, frameId / 1_000_000L, turns)
                        val outputs = if (mapper != null && tracking == "TRACKING") {
                            val primaryPipeline = primary.forGeometry(geometryId)
                            primary.sourceGate.qualify(capturedSnapshot)?.let { qualified -> primaryPipeline.process(
                                snapshot = qualified, frameId = frameId, timestampMs = frameId / 1_000_000L,
                                detections = result.detections, detectionSequenceId = frameId,
                                detectionCompleted = !result.partial, mapper = mapper,
                                imageQuarterTurns = turns,
                            ) } ?: emptyList()
                        } else { primary.reset(); emptyList() }
                        val mapped = result.detections.map { item ->
                            val rect = item.bboxNorm
                            val corners = listOf(point(view, rect.x, rect.y),
                                point(view, rect.x + rect.width, rect.y),
                                point(view, rect.x, rect.y + rect.height),
                                point(view, rect.x + rect.width, rect.y + rect.height))
                            val output = outputs.firstOrNull { it.className == item.className && it.bboxNorm == rect }
                            val label = "분류 ${item.className} ${String.format(Locale.US, "%.0f%%", item.detectionConfidence * 100)} · " +
                                (output?.riskDistanceM?.let { String.format(Locale.US, "위험 거리 %.2fm", it) }
                                    ?: output?.prediction?.let { String.format(Locale.US, "예측 %.2fm ±%.2fm · 공백 %dms",
                                        it.distanceM, it.errorBoundM, it.predictionAgeMs) }
                                    ?: if (output?.depthAvailability == kr.co.hanium.dreamup.walksafe.depth.DepthAvailability.TEMPORARILY_UNAVAILABLE)
                                        "깊이 일시 공백" else "거리 미확인")
                            DebugBboxOverlayView.DebugOverlayBox(RectF(corners.minOf { it.first },
                                corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second }),
                                label, false, item.className)
                        }
                        val presentationCapture = CameraObjectPresentation.Capture(epoch, frameId, capturedMs, geometryId)
                        val presentationRegions = result.detections.map { detection ->
                            val output = outputs.firstOrNull {
                                it.className == detection.className && it.bboxNorm == detection.bboxNorm
                            }
                            CameraObjectPresentation.Region(detection.bboxNorm, output?.trackId,
                                output.trustedPresentationDistanceM(), detection.detectionConfidence)
                        }
                        val predictionDeadlines = result.detections.map { item ->
                            outputs.firstOrNull { it.className == item.className && it.bboxNorm == item.bboxNorm }
                                ?.prediction?.let { DepthPredictionPresentation.validUntilMs(it, capturedMs) ?: capturedMs - 1L }
                        }
                        val message = "테스트 카메라 · ARCore $tracking\n" +
                            "객체 ${result.detections.size}개 · 추론 ${result.timing.modelInferenceMs}ms\n" +
                            "센서 영상 → 화면 방향 보정 ${turns * 90}° (휴대폰을 돌리라는 뜻이 아닙니다)\n" +
                            "Raw Depth ${snapshot.hasMetricRawDepth} · Full Depth ${snapshot.hasFullDepth}\n" +
                            (if (tracking != "TRACKING") "깊이 추적 대기: $trackingFailure\n" else "") +
                            (depthResult.exceptionOrNull()?.let { "깊이 오류: ${it.javaClass.simpleName} · 객체 추론은 계속합니다.\n" } ?: "") +
                            (if (meanLuma < 25) "입력 영상이 매우 어둡습니다. 렌즈 가림과 조명을 확인하세요.\n"
                                else if (result.detections.isEmpty()) "모델 실행 정상 · 학습한 21개 클래스 중 검출 없음\n" else "")
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNoticeMs > 2000) {
                            lastNoticeMs = now
                            Log.i("WalkSafeCameraTest", "inferenceMs=${result.timing.modelInferenceMs} objects=${mapped.size} meanLuma=$meanLuma tracking=$tracking/$trackingFailure depthError=${depthResult.exceptionOrNull()?.javaClass?.simpleName}")
                        }
                        runOnUiThread {
                            if (foreground && epoch == generation) {
                                if (!objectPresentation.recordPrimary(presentationCapture, presentationRegions)) return@runOnUiThread
                                primaryOverlayCapture = presentationCapture
                                fun renderPrimaryDepth() {
                                    if (!foreground || epoch != generation || primaryOverlayCapture != presentationCapture) return
                                    val displayNow = SystemClock.elapsedRealtime()
                                    val currentBoxes = mapped.mapIndexed { index, box ->
                                        if (predictionDeadlines[index]?.let { displayNow > it } == true)
                                            box.copy(label = "분류 ${result.detections[index].className} · 거리 미확인") else box
                                    }
                                    boxes.updateMapped(currentBoxes, frameId, capturedMs, sourceComplete = true, animate = false)
                                    status.text = message + currentBoxes.take(4).joinToString("\n") { it.label }
                                }
                                renderPrimaryDepth()
                                val displayNow = SystemClock.elapsedRealtime()
                                predictionDeadlines.filterNotNull().filter { it >= displayNow }.distinct().forEach { deadline ->
                                    feedbackHandler.postDelayed({ renderPrimaryDepth() }, deadline - displayNow + 1L)
                                }
                                renderUnknownObjects(displayNow)
                                feedback.offer(CameraTestFeedbackCoordinator.Sample(
                                    CameraTestFeedbackCoordinator.Source.PRIMARY, epoch, frameId,
                                    capturedMs, now, geometryId, outputs))
                                updateFeedback()
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
        if (foreground && epoch == generation) {
            status.text = message
            objectPresentation.clear()
            primaryOverlayCapture = null
            boxes.clear()
            renderUnknownObjects(SystemClock.elapsedRealtime())
        }
    }

    private fun feedbackLive(epoch: Int): Boolean =
        foreground && epoch == generation && !isDestroyed && !isFinishing

    private val feedbackTick = object : Runnable {
        override fun run() {
            if (!feedbackLive(generation) || feedbackActuator == null) return
            renderUnknownObjects(SystemClock.elapsedRealtime())
            updateFeedback()
            feedbackHandler.postDelayed(this, 200L)
        }
    }

    private fun startFeedback(epoch: Int) {
        stopFeedback()
        feedback.start(epoch)
        speechReadiness = "한국어 음성 준비 중"
        lastFeedbackEvent = ""
        feedbackActuator = AndroidFeedbackActuator(
            context = applicationContext,
            speechAllowed = { feedbackLive(epoch) },
            hapticAllowed = { feedbackLive(epoch) },
            onOfflineKoreanSpeechReady = {
                runOnUiThread { if (feedbackLive(epoch)) speechReadiness = "한국어 음성 준비됨" }
            },
            onOfflineKoreanSpeechUnavailable = {
                runOnUiThread { if (feedbackLive(epoch)) speechReadiness = "한국어 TTS 사용 불가 · 진동 경고 유지" }
            },
        )
        feedbackHandler.post(feedbackTick)
    }

    private fun stopFeedback() {
        feedbackHandler.removeCallbacksAndMessages(null)
        feedback.stop()
        feedbackActuator?.close() // Stops pending/on-going TTS and vibration on pause/close.
        feedbackActuator = null
    }

    private fun updateFeedback() {
        val epoch = generation
        if (!feedbackLive(epoch)) return
        val actuator = feedbackActuator ?: return
        val now = SystemClock.elapsedRealtime()
        val delivery = feedback.next(now)
        if (delivery != null) {
            if (!feedback.claim(delivery, now)) feedback.reject(delivery)
            else {
                var vibrationAccepted = false
                val dispatch = actuator.emit(
                    action = delivery.action,
                    isStillValidAtStart = {
                        feedbackLive(epoch) && feedback.isDeliverable(delivery, SystemClock.elapsedRealtime())
                    },
                    onSpeechCompleted = {
                        feedbackHandler.post {
                            if (feedbackLive(epoch)) {
                                if (feedback.complete(delivery, SystemClock.elapsedRealtime())) {
                                    lastFeedbackEvent = "음성 완료: ${delivery.action.message}"
                                }
                            }
                        }
                    },
                    onSpeechFailed = {
                        feedbackHandler.post {
                            if (feedbackLive(epoch)) resolveFailedSpeech(delivery, vibrationAccepted, now)
                        }
                    },
                )
                vibrationAccepted = dispatch.vibrationAccepted
                lastFeedbackEvent = if (dispatch.speech == NavigationSpeechDispatchResult.ACCEPTED)
                    "음성 요청: ${delivery.action.message}"
                else "음성 ${dispatch.speech} · 진동 ${if (vibrationAccepted) "요청 수락" else "사용 불가"}"
                if (dispatch.speech != NavigationSpeechDispatchResult.ACCEPTED) {
                    resolveFailedSpeech(delivery, vibrationAccepted, now)
                }
            }
        }
        val diagnostic = "$speechReadiness\n${feedback.diagnostic(now)}"
        feedbackStatus.text = diagnostic + if (lastFeedbackEvent.isBlank()) "" else "\n$lastFeedbackEvent"
        if (diagnostic != lastFeedbackLog && now - lastFeedbackLogMs >= 2_000L) {
            lastFeedbackLog = diagnostic
            lastFeedbackLogMs = now
            Log.i("WalkSafeCameraTest", "feedback=${diagnostic.replace('\n', ' ')}")
        }
    }

    private fun resolveFailedSpeech(delivery: CameraTestFeedbackCoordinator.Delivery,
                                    vibrationAccepted: Boolean, startedAtMs: Long) {
        if (!vibrationAccepted) { feedback.reject(delivery); return }
        // A vibration request is not physical completion. Hold the shared queue for its duration
        // before consuming cooldown, mirroring the production haptic fallback.
        val duration = delivery.action.vibrationPatternMs?.sum() ?: 0L
        feedbackHandler.postDelayed({
            if (feedbackLive(delivery.epoch)) {
                if (feedback.complete(delivery, SystemClock.elapsedRealtime())) {
                    lastFeedbackEvent = "음성 미완료 · 진동 요청 수락"
                }
            }
        }, (startedAtMs + duration - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
    }

    private fun showUnknownResult(result: FastSamResult<UnknownCapture>) {
        val capture = result.attachment
        if (!foreground || capture.epoch != generation || !unknownRuntime.accepts(result.token.sessionEpoch)) return
        val processed = capture.pipeline.process(capture.depth, result.token, result.masks, SystemClock.elapsedRealtime())
            .forDisplayAt(SystemClock.elapsedRealtime())
        val completedMs = SystemClock.elapsedRealtime()
        val cameraEpoch = WalkRuntimeEpoch("local-camera-test", capture.epoch.toLong())
        val allOutputs = processed.objects + processed.proximityObjects
        val admitted = capture.feedbackPolicy.admit(
            outputs = allOutputs,
            sourceFrameId = result.token.frameId,
            sourceTimestampMs = result.token.cameraTimestampNs / 1_000_000L,
            sourceCapturedAtElapsedRealtimeNs = result.token.capturedElapsedNs,
            completedAtElapsedRealtimeMs = completedMs,
            sourceEpoch = cameraEpoch,
            currentEpoch = cameraEpoch.takeIf { foreground && capture.epoch == generation &&
                unknownRuntime.accepts(result.token.sessionEpoch) },
            nowElapsedRealtimeMs = completedMs,
        )
        val candidates = processed.observations.filter { it.walkingSelection?.show == true }
        // Rank display patches and separate measured warnings together, then apply the display cap.
        val selected = CameraUnknownObservation.selectForDisplay(candidates, allOutputs,
            admitted?.outputs ?: emptyList(), limit = Int.MAX_VALUE)
        val mapped = selected.map { candidate ->
            val rect = candidate.bboxNorm
            val corners = listOf(point(capture.view, rect.x, rect.y),
                point(capture.view, rect.x + rect.width, rect.y),
                point(capture.view, rect.x, rect.y + rect.height),
                point(capture.view, rect.x + rect.width, rect.y + rect.height))
            val observation = candidate.observation
            val output = candidate.output
            val label = if (observation == null) {
                // A separate warning retains its own measured distance and bounds.
                val warning = requireNotNull(output)
                val kind = if (warning.source == kr.co.hanium.dreamup.walksafe.depth.DepthSource.ARCORE_FULL_DEPTH)
                    "Full 추정" else "Raw 측정"
                val alert = if (warning.userFacing.messageLevel == MessageLevel.STOP) "정지 경고 후보" else "주의 경고 후보"
                String.format(Locale.US, "근접 경고 영역 · %s %.2fm · %s", kind, candidate.distanceM, alert)
            } else {
                val distance = output?.rayDistanceM?.toDouble() ?: candidate.distanceM
                val kind = if (output?.prediction != null) "단기 예측" else if (observation.depth.source == "ARCORE_FULL_DEPTH") "Full 추정" else "Raw 측정"
                val alert = when (output?.userFacing?.messageLevel) {
                    MessageLevel.STOP -> " · 정지 경고 후보"
                    MessageLevel.WARNING -> " · 주의 경고 후보"
                    else -> ""
                }
                (if (observation.proximityDistanceM != null) "근접 표면 · " else "전방 영역 · ") + (distance?.let {
                    String.format(Locale.US, "%s %.2fm", kind, it)
                } ?: "거리 미확인") + (observation.metricExtent?.takeIf {
                    observation.proximityDistanceM == null && distance != null
                }?.let {
                    String.format(Locale.US, " · 관측 폭 %.2fm/높이 %.2fm", it.widthM, it.heightM)
                } ?: "") + (output?.prediction?.let {
                    String.format(Locale.US, " ±%.2fm · 공백 %dms", it.errorBoundM, it.predictionAgeMs)
                } ?: "") + alert
            }
            DebugBboxOverlayView.DebugOverlayBox(RectF(corners.minOf { it.first }, corners.minOf { it.second },
                corners.maxOf { it.first }, corners.maxOf { it.second }), label, true, "unnamed-obstacle")
        }
        runOnUiThread {
            if (foreground && capture.epoch == generation && unknownRuntime.accepts(result.token.sessionEpoch)) {
                val presentationCapture = CameraObjectPresentation.Capture(capture.epoch, result.token.frameId,
                    result.token.capturedElapsedNs / 1_000_000L, result.token.geometryId)
                if (primaryOverlayCapture?.let {
                        it.geometryId != presentationCapture.geometryId && it.frameId >= presentationCapture.frameId
                    } == true || unknownOverlay?.capture?.frameId?.let { it >= result.token.frameId } == true
                ) return@runOnUiThread
                val measured = processed.observations.count { CameraUnknownObservation.distanceM(it) != null }
                val waiting = processed.observations.firstOrNull { CameraUnknownObservation.distanceM(it) == null }?.depth?.reason
                unknownOverlay = UnknownOverlay(presentationCapture, selected.map { candidate ->
                    CameraObjectPresentation.Region(candidate.bboxNorm, candidate.feedbackId,
                        candidate.output.trustedPresentationDistanceM()?.takeIf { distance ->
                            candidate.distanceM?.let { kotlin.math.abs(it - distance) <= 0.30 } == true
                        }, candidate.output?.detectionConfidence ?: 0f)
                }, mapped, selected.map { candidate -> candidate.output?.prediction?.let {
                    DepthPredictionPresentation.validUntilMs(it, presentationCapture.capturedAtMs)
                        ?: presentationCapture.capturedAtMs - 1L
                } }, result.masks.size, candidates.size,
                    "거리 확인 ${measured}개 · 추론 ${result.inferenceMs.toLong()}ms\n" +
                    "3m 이내 보행 위험 조건 충족 시 음성·진동\n" +
                    (processed.rejectionReason?.let { "깊이 대기: $it" }
                        ?: if (measured == 0) "거리 대기: ${waiting ?: "인식 영역 없음"}" else ""))
                val displayNow = SystemClock.elapsedRealtime()
                renderUnknownObjects(displayNow)
                unknownOverlay?.predictionDeadlinesMs?.filterNotNull()?.filter { it >= displayNow }?.distinct()?.forEach { deadline ->
                    feedbackHandler.postDelayed({
                        if (unknownOverlay?.capture == presentationCapture) renderUnknownObjects(SystemClock.elapsedRealtime())
                    }, deadline - displayNow + 1L)
                }
                feedback.offer(CameraTestFeedbackCoordinator.Sample(
                    CameraTestFeedbackCoordinator.Source.UNKNOWN, capture.epoch, result.token.frameId,
                    result.token.capturedElapsedNs / 1_000_000L, completedMs, result.token.geometryId,
                    admitted?.outputs ?: emptyList()),
                    retainedUnknownTrackIds = processed.retainedProximityRegionIds,
                    nowMs = SystemClock.elapsedRealtime())
                updateFeedback()
            }
        }
    }

    /** Recompute presentation when either model finishes; the risk queue retains both sources. */
    private fun renderUnknownObjects(nowMs: Long) {
        val observation = unknownOverlay ?: return
        if (observation.capture.epoch != generation ||
            nowMs - observation.capture.capturedAtMs !in 0L..UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS ||
            primaryOverlayCapture?.let { it.geometryId != observation.capture.geometryId } == true
        ) {
            unknownOverlay = null
            unknownBoxes.clear()
            return
        }
        val merged = objectPresentation.mergedUnknownIndices(observation.capture, observation.regions, nowMs)
        val visible = observation.boxes.filterIndexed { index, _ -> index !in merged &&
            observation.predictionDeadlinesMs[index]?.let { nowMs <= it } != false }.take(6)
        unknownBoxes.updateMapped(visible, observation.capture.frameId, observation.capture.capturedAtMs,
            sourceComplete = true, animate = false)
        unknownStatus.text = "FastSAM ${observation.rawCount}개 → 3m 내 후보 ${observation.candidateCount}개 · " +
            "학습 객체와 통합 ${merged.size}개 · 추가 표시 ${visible.size}개\n" + observation.detail
    }

    private fun TrackedObjectDepth?.trustedPresentationDistanceM(): Double? = this?.takeIf {
        it.source.metric && it.confidence.hardGate > 0f && it.confidence.freshnessQuality > 0f
    }?.riskDistanceM?.takeIf { it.isFinite() && it > 0f }?.toDouble()

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

}
