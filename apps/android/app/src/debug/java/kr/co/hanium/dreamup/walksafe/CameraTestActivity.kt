package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
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
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.FullDepthTiming
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicy
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicyConfig
import kr.co.hanium.dreamup.walksafe.depth.MessageRateLimitConfig
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.ObjectTracker
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadTiming
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadPhase
import kr.co.hanium.dreamup.walksafe.depth.UnknownObjectFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.device.AndroidFixedFixtureRuntimeSelector
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
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Local debug camera and hazard feedback; no account, route, walk lease or report is opened. */
class CameraTestActivity : Activity(), GLSurfaceView.Renderer {
    private lateinit var preview: CameraPreviewLayout
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
        val trackingScope: CameraUnknownMaterialPointScope,
        val regions: List<CameraObjectPresentation.Region>,
        val boxes: List<DebugBboxOverlayView.DebugOverlayBox>,
        val predictionDeadlinesMs: List<Long?>,
        val rawCount: Int,
        val candidateCount: Int,
        val detail: String,
    )
    private val feedbackHandler = Handler(Looper.getMainLooper())
    private val primaryUiPresentation = CameraUiPresentationCoordinator<List<Int>>(
        { callback, delayMs -> feedbackHandler.postDelayed(callback, delayMs) },
        { callback -> feedbackHandler.removeCallbacks(callback) },
    )
    private val unknownUiPresentation = CameraUiPresentationCoordinator<Pair<Map<Int, Int>, List<Int>>>(
        { callback, delayMs -> feedbackHandler.postDelayed(callback, delayMs) },
        { callback -> feedbackHandler.removeCallbacks(callback) },
    )
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
    private val auxiliaryStartup = CameraAuxiliaryStartupCoordinator()
    private var auxiliaryRetryRequested = false
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
        val depth: FrozenUnknownDepthCapture, val trackingScope: CameraUnknownMaterialPointScope,
        val imageQuarterTurns: Int, val trackingFlight: CameraUnknownMaterialPointFlight,
        val feedbackPolicy: UnknownObjectFeedbackPolicy)
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val cameraWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "WalkSafeCameraSetup") }
    private val busy = AtomicBoolean(false)
    private val primaryInferencePacing = AdaptiveInferencePacingPolicy()
    @Volatile private var foreground = false
    @Volatile private var generation = 0
    @Volatile private var session: Session? = null
    private var cameraSessionEpoch: Long? = null
    @Volatile private var provider: ArCoreFrameProvider? = null
    @Volatile private var detector: TfliteAndroidFrameDetector? = null
    private val cameraRelease = CameraSessionRelease()
    private val closingCamera get() = cameraRelease.blocksStart
    private var startingCamera = false
    private var installRequested = false
    private var background = CameraBackgroundRenderer()
    private var texture = 0
    private var width = 0
    private var height = 0
    private var cameraDisplayRotation: Int? = null // GL thread display geometry.
    private val previewRequests = CameraPreviewGeometry.Requests()
    @Volatile private var previewViewport: CameraPreviewGeometry.Viewport? = null
    private var lastFrame = 0L
    @Volatile private var lastNoticeMs = 0L
    private val firstInferenceLog = CameraFirstInferenceTimingLog() // Accessed only by worker.
    private val unknownGeometryGeneration = AtomicLong()
    private val unknownMaterialPoints = CameraUnknownMaterialPointOwner(enqueueOwner = { action ->
        worker.execute { action() }
    })

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
                setOnClickListener {
                    startCamera()
                    if (detector != null) {
                        auxiliaryRetryRequested = true
                        resumeUnknownAfterPrimaryLiveAttempt()
                    }
                }
            })
            addView(Button(this@CameraTestActivity).apply {
                text = "카메라 닫기"
                setOnClickListener { finish() }
            })
        }
        setContentView(FrameLayout(this).apply {
            preview = CameraPreviewLayout(this@CameraTestActivity).apply {
                onViewportChanged = { viewport ->
                    previewViewport = viewport
                    invalidateUnknownGeometry()
                }
                addView(surface, FrameLayout.LayoutParams(-1, -1))
                addView(boxes, FrameLayout.LayoutParams(-1, -1))
                addView(unknownBoxes, FrameLayout.LayoutParams(-1, -1))
            }
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        val modelQueuedNs = System.nanoTime()
        worker.execute {
            logCameraQueue("model_load_queue", generation, modelQueuedNs)
            val loaded = runCatching { AndroidFixedFixtureRuntimeSelector.load(
                applicationContext, TwoModelRuntimeConfig.load(applicationContext), allowComparison = false,
                isCurrent = { !isDestroyed && !isFinishing },
                onProgress = { message -> runOnUiThread {
                    if (!isDestroyed && !isFinishing) status.text = message
                } },
            ) }.onFailure { Log.e("WalkSafeCameraTest", "Model initialization failed", it) }.getOrNull()
            primaryInferencePacing.reset()
            detector = loaded?.detector
            Log.i("WalkSafeCameraTest", "Model initialization: ${loaded?.reason ?: "exception"}")
            logDetectorTiming("model_load", loaded?.loadTiming)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (detector == null) status.text = "카메라 테스트 · 객체 모델 로드 실패: ${loaded?.reason ?: "exception"}"
                else if (foreground) {
                    status.text = "객체 인식 준비 완료 · 카메라 영상을 기다리는 중"
                    resumeUnknownAfterPrimaryLiveAttempt()
                }
            }
        }
    }

    private fun createUnknownService(epoch: Long): FastSamRuntimeService<UnknownCapture> {
        // Publish fresh tracker/policy state before the new service becomes visible to the GL worker.
        unknownMaterialPoints.invalidate()
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
                    unknownMaterialPoints.invalidate(epoch)
                    unknownRuntime.failed(epoch)
                }
                override fun onDiscard(token: FastSamFrameToken, reason: String) {
                    if (foreground && !isDestroyed && !isFinishing)
                        unknownMaterialPoints.cancelCapture(token) { scope -> unknownTrackingLive(scope) }
                }
            })
    }

    private fun showUnknownRuntimeStatus() {
        if (!foreground || isDestroyed || isFinishing) return
        val state = unknownRuntime.status
        if (state != CameraUnknownRuntime.Status.READY) {
            unknownOverlay = null
            unknownUiPresentation.clear()
            unknownBoxes.clear()
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
        unknownStatus.setTextIfChanged(when (state) {
            CameraUnknownRuntime.Status.IDLE, CameraUnknownRuntime.Status.LOADING -> "이름 없는 영역 · FastSAM 준비 중"
            CameraUnknownRuntime.Status.READY -> "이름 없는 영역 · ${unknownRuntime.backendName ?: "FastSAM"} 준비됨"
            CameraUnknownRuntime.Status.FAILED -> "이름 없는 영역 실패 · 카메라 다시 연결을 눌러 재시도하세요"
            CameraUnknownRuntime.Status.WAITING_RELEASE -> "이름 없는 영역 · 이전 모델 종료를 기다리는 중"
            CameraUnknownRuntime.Status.RELEASE_FAILED -> "이름 없는 영역 · 이전 모델 해제 미확인으로 재시작할 수 없습니다"
        })
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        auxiliaryStartup.begin(generation)
        startCamera()
        resumeUnknownAfterPrimaryLiveAttempt()
    }

    private fun resumeUnknownAfterPrimaryLiveAttempt() {
        if (!foreground || isDestroyed || isFinishing || detector == null ||
            !auxiliaryStartup.mayStart(generation, busy.get())
        ) return
        unknownRuntime.resume()
        if (auxiliaryRetryRequested) {
            auxiliaryRetryRequested = false
            unknownRuntime.retry()
        }
    }

    private fun startCamera() {
        if (!foreground || isDestroyed || isFinishing) return
        if (cameraRelease.failure != null) {
            status.text = "이전 카메라 해제가 확인되지 않아 다시 연결할 수 없습니다. 화면을 종료해 주세요."
            return
        }
        if (!foreground || isDestroyed || isFinishing || startingCamera || closingCamera || session != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        try {
            if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }
            startingCamera = true
            val startGeneration = generation
            status.text = "카메라 연결 중 · 성능 재시험 없음"
            cameraWorker.execute {
                val sessionEpoch = nextCameraSessionEpoch.incrementAndGet()
                var owned: Session? = null
                var ownedSource: ArCoreFrameProvider? = null
                val prepared = runCatching {
                    val camera = measureCameraStep("session_create", sessionEpoch) { Session(applicationContext) }
                    owned = camera
                    val source = ArCoreFrameProvider(camera)
                    ownedSource = source
                    Triple(camera, source, source.configureDepthMode())
                }
                runOnUiThread {
                    startingCamera = false
                    val value = prepared.getOrNull()
                    val current = foreground && !isDestroyed && !isFinishing && generation == startGeneration
                    if (value == null || !current) {
                        releasePreparedCamera(owned, ownedSource, sessionEpoch, retry = value != null && !current)
                        if (value == null && current)
                            status.text = "카메라 시작 실패: ${prepared.exceptionOrNull()?.javaClass?.simpleName}"
                        return@runOnUiThread
                    }
                    try {
                        // Setup is off UI; resume/pause retain Android lifecycle ordering.
                        measureCameraStep("session_resume", sessionEpoch) { value.first.resume() }
                        generation++
                        primaryInferencePacing.reset()
                        auxiliaryStartup.begin(generation)
                        unknownMaterialPoints.invalidate()
                        unknownPipeline = UnknownObjectDepthPipeline(SystemClock::elapsedRealtime)
                        unknownFeedbackPolicy = UnknownObjectFeedbackPolicy()
                        primaryDepth = PrimaryDepthState(generation)
                        objectPresentation.clear()
                        primaryUiPresentation.clear()
                        unknownUiPresentation.clear()
                        primaryOverlayCapture = null
                        unknownOverlay = null
                        startFeedback(generation)
                        lastFrame = 0L
                        provider = value.second
                        cameraSessionEpoch = sessionEpoch
                        session = value.first
                        surface.onResume()
                        status.text = if (value.third) "Depth 지원 · 실제 프레임을 기다리는 중"
                            else "Depth 미지원 · 카메라와 객체 인식만 표시합니다."
                    } catch (error: Exception) {
                        val published = session === value.first
                        if (published) {
                            // Revoke the published camera before release; drain any Images already queued.
                            session = null
                            provider = null
                            cameraSessionEpoch = null
                            runCatching { surface.onPause() }
                        }
                        runCatching { value.first.pause() }
                        queueCameraRelease(value.first, value.second, sessionEpoch,
                            if (published) worker else cameraWorker, retry = false)
                        status.text = "카메라 시작 실패: ${error.javaClass.simpleName}"
                    }
                }
            }
        } catch (error: Exception) {
            startingCamera = false
            status.text = "카메라 시작 실패: ${error.javaClass.simpleName}\n카메라를 사용하는 다른 앱과 AR 서비스 설치 상태를 확인하세요."
        }
    }

    private fun releasePreparedCamera(camera: Session?, source: ArCoreFrameProvider?, epoch: Long, retry: Boolean) {
        queueCameraRelease(camera, source, epoch, cameraWorker, retry)
    }

    private fun queueCameraRelease(
        camera: Session?, source: ArCoreFrameProvider?, epoch: Long?,
        lane: java.util.concurrent.Executor, retry: Boolean,
    ) {
        val attempt = cameraRelease.begin(epoch,
            closeProvider = { source?.let { measureCameraStep("provider_close", epoch) { it.close() } } },
            closeSession = { camera?.let { measureCameraStep("session_close", epoch) { it.close() } } },
        )
        val closingEpoch = generation
        val closeQueuedNs = System.nanoTime()
        try {
            lane.execute {
                logCameraQueue("camera_close_queue", closingEpoch, closeQueuedNs, epoch)
                val released = cameraRelease.execute(attempt)
                runOnUiThread {
                    if (isDestroyed || isFinishing) cameraWorker.shutdown()
                    else if (!released) status.text = "이전 카메라 해제가 확인되지 않아 다시 연결할 수 없습니다. 화면을 종료해 주세요."
                    else if (foreground && retry) startCamera()
                }
            }
        } catch (failure: RuntimeException) {
            cameraRelease.schedulingFailed(attempt, failure)
            if (!isDestroyed && !isFinishing) status.text = "카메라 종료 작업을 시작하지 못했습니다. 다시 연결할 수 없습니다."
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
        primaryInferencePacing.reset()
        auxiliaryStartup.clear()
        auxiliaryRetryRequested = false
        unknownMaterialPoints.invalidate()
        unknownRuntime.pauseAndRelease()
        stopFeedback()
        surface.onPause() // Stop Session.update before pausing or closing the camera.
        val camera = session
        val source = provider
        source?.invalidateRecognitionCameraImageTimestamps()
        val closingSessionEpoch = cameraSessionEpoch
        session = null
        provider = null
        cameraSessionEpoch = null
        objectPresentation.clear()
        primaryUiPresentation.clear()
        unknownUiPresentation.clear()
        primaryOverlayCapture = null
        unknownOverlay = null
        boxes.clear()
        unknownBoxes.clear()
        if (camera != null) {
            runCatching { measureCameraStep("session_pause", closingSessionEpoch) { camera.pause() } }
            // This lane runs after queued inference has returned its Image.
            queueCameraRelease(camera, source, closingSessionEpoch, worker, retry = true)
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopFeedback()
        foreground = false
        generation++
        primaryInferencePacing.reset()
        unknownMaterialPoints.invalidate()
        unknownRuntime.destroy()
        val closingEpoch = generation
        val closeQueuedNs = System.nanoTime()
        worker.execute {
            logCameraQueue("detector_close_queue", closingEpoch, closeQueuedNs)
            val model = detector
            try { model?.close(); detector = null }
            finally { logDetectorTiming("detector_close", model?.lifecycleTiming()) }
        }
        worker.shutdown()
        if (!startingCamera && (!closingCamera || cameraRelease.failure != null)) cameraWorker.shutdown()
        super.onDestroy()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        previewRequests.reset()
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
        if (this.width != width || this.height != height) {
            provider?.invalidateRecognitionCameraImageTimestamps()
            invalidateUnknownGeometry()
        }
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
            val displayRotation = windowManager.defaultDisplay.rotation
            if (cameraDisplayRotation != displayRotation) {
                source.invalidateRecognitionCameraImageTimestamps()
                invalidateUnknownGeometry()
                cameraDisplayRotation = displayRotation
            }
            camera.setDisplayGeometry(displayRotation, width, height)
            val frame = camera.update()
            updatePreviewGeometry(frame, camera, epoch, displayRotation)
            val previewRequest = previewRequests.current(epoch, displayRotation) ?: return
            if (!CameraPreviewGeometry.matches(previewViewport, width, height, previewRequest.revision)) return
            background.draw(frame, texture)
            if (frame.timestamp <= lastFrame) return
            lastFrame = frame.timestamp
            val model = detector ?: return
            if (!busy.compareAndSet(false, true)) return
            val pacingTicket = primaryInferencePacing.tryStart(SystemClock.elapsedRealtime()) ?: run {
                busy.set(false)
                return
            }
            var transferred = false
            var image: android.media.Image? = null
            try {
                val capture = captureCameraTestFrame(source, frame, profileFullDepth = true) ?: return
                image = capture.image
                val ownedImage = image
                val snapshot = capture.snapshot
                val capturedSnapshot = snapshot
                val basis = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0.5f, 0.5f)
                val view = FloatArray(10)
                val depth = FloatArray(10)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, basis, Coordinates2d.VIEW, view)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, basis, Coordinates2d.TEXTURE_NORMALIZED, depth)
                val frameId = frame.timestamp
                val capturedMs = SystemClock.elapsedRealtime()
                val trackingGeometryGeneration = unknownGeometryGeneration.get()
                val tracking = frame.camera.trackingState.name
                val trackingFailure = frame.camera.trackingFailureReason.name
                val turns = UprightCameraImage.quarterTurns(view) ?: return
                val geometryId = "camera-test:$epoch:${ownedImage.width}x${ownedImage.height}:$turns:$trackingGeometryGeneration"
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
                    var detectorInvoked = false
                    var inferenceDurationMs: Long? = null
                    try {
                        if (!foreground || epoch != generation || model !== detector) return@execute
                        val qualifiedUnknownFrame = unknownMaterialPoints.qualifyFrame(capturedSnapshot.cameraImageTimestampNs) {
                            foreground && epoch == generation && trackingGeometryGeneration == unknownGeometryGeneration.get()
                        }
                        // RGB-only captures must not read an unqualified clock for FastSAM tokens.
                        if (qualifiedUnknownFrame && auxiliary != null && foreground && epoch == generation &&
                            unknownRuntime.accepts(auxiliary.sessionEpoch)) {
                            val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,
                                auxiliary.sessionEpoch, frameId, capturedSnapshot.frameTimestampNs, ownedImage.timestamp,
                                capturedMs * 1_000_000L, geometryId, ownedImage.width, ownedImage.height)
                            val trackingScope = unknownMaterialPoints.acquireScope(epoch, auxiliary.sessionEpoch,
                                trackingGeometryGeneration, pipeline, geometryId) {
                                foreground && epoch == generation && trackingGeometryGeneration == unknownGeometryGeneration.get() &&
                                    unknownRuntime.accepts(auxiliary.sessionEpoch)
                            }
                            if (trackingScope != null) runCatching {
                                unknownMaterialPoints.offerImage(trackingScope, token, turns, ownedImage,
                                    SystemClock.elapsedRealtime()) { unknownTrackingLive(trackingScope) }
                            }.onFailure { Log.w("WalkSafeCameraTest", "Unknown tracking frame admission failed", it) }
                            val state = auxiliary.stats()
                            if (trackingScope != null && unknownTrackingLive(trackingScope) &&
                                !state.inflight && !state.pending && !state.stopping) {
                                val h = frozenTransform?.imagePixelsToDepthUvMatrix(ownedImage.width, ownedImage.height)
                                val capture = FrozenUnknownDepthCapture.freeze(token,
                                    capturedSnapshot, h, frameId,
                                    imageQuarterTurns = turns)
                                if (capture != null) {
                                    val flight = unknownMaterialPoints.reserveCapture(trackingScope, token, turns,
                                        SystemClock.elapsedRealtime()) { unknownTrackingLive(trackingScope) }
                                    if (flight != null) runCatching {
                                        if (!unknownTrackingLive(trackingScope) || !flight.accepts) {
                                            unknownMaterialPoints.cancelFlight(trackingScope, flight) { unknownTrackingLive(trackingScope) }
                                        } else {
                                            val admission = auxiliary.submit(ownedImage, token, UnknownCapture(epoch, view, capture,
                                                trackingScope, turns, flight, auxiliaryFeedbackPolicy))
                                            if (admission != FastSamRuntimeService.Admission.ACCEPTED &&
                                                admission != FastSamRuntimeService.Admission.REPLACED_PENDING) {
                                                unknownMaterialPoints.cancelFlight(trackingScope, flight) { unknownTrackingLive(trackingScope) }
                                            }
                                        }
                                    }.onFailure {
                                        unknownMaterialPoints.cancelFlight(trackingScope, flight) { unknownTrackingLive(trackingScope) }
                                        Log.w("WalkSafeCameraTest", "FastSAM frame submission failed", it)
                                    }
                                }
                            }
                        }
                        val meanLuma = meanLuma(ownedImage)
                        val result = try {
                            detectorInvoked = true
                            model.detectOriented(ownedImage, frameId / 1_000_000L, turns)
                        }
                        finally {
                            if (firstInferenceLog.pending) {
                                logDetectorTiming("first_inference", firstInferenceLog.take(model.lifecycleTiming()))
                            }
                        }
                        inferenceDurationMs = result.timing.modelInferenceMs
                        val outputs = if (mapper != null && tracking == "TRACKING") {
                            val primaryPipeline = primary.forGeometry(geometryId)
                            primary.sourceGate.qualify(capturedSnapshot)?.let { qualified -> primaryPipeline.process(
                                snapshot = qualified, frameId = frameId, timestampMs = frameId / 1_000_000L,
                                detections = result.detections, detectionSequenceId = frameId,
                                detectionCompleted = !result.partial, mapper = mapper, mapperFrameId = mapper.frameId,
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
                            (capture.fullDepthTiming?.let { cameraFullDepthTimingDiagnostic(it) + "\n" } ?: "") +
                            (if (tracking != "TRACKING") "깊이 추적 대기: $trackingFailure\n" else "") +
                            (capture.depthError?.let { "깊이 오류: ${it.javaClass.simpleName} · 객체 추론은 계속합니다.\n" } ?: "") +
                            (if (meanLuma < 25) "입력 영상이 매우 어둡습니다. 렌즈 가림과 조명을 확인하세요.\n"
                                else if (result.detections.isEmpty()) "모델 실행 정상 · 학습한 21개 클래스 중 검출 없음\n" else "")
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNoticeMs > 2000) {
                            lastNoticeMs = now
                            Log.i("WalkSafeCameraTest", "inferenceMs=${result.timing.modelInferenceMs} objects=${mapped.size} meanLuma=$meanLuma tracking=$tracking/$trackingFailure depthError=${capture.depthError?.javaClass?.simpleName} " +
                                (capture.fullDepthTiming?.let { cameraFullDepthTimingDiagnostic(it) } ?: "Full 요청 없음"))
                        }
                        runOnUiThread {
                            if (foreground && epoch == generation && trackingGeometryGeneration == unknownGeometryGeneration.get()) {
                                if (!objectPresentation.recordPrimary(presentationCapture, presentationRegions)) return@runOnUiThread
                                if (!primaryUiPresentation.begin(presentationCapture)) return@runOnUiThread
                                primaryOverlayCapture = presentationCapture
                                fun renderPrimaryDepth() {
                                    if (!foreground || epoch != generation || trackingGeometryGeneration != unknownGeometryGeneration.get() ||
                                        primaryOverlayCapture != presentationCapture) return
                                    val displayNow = SystemClock.elapsedRealtime()
                                    val expiredIndices = predictionDeadlines.indices.filter { index ->
                                        predictionDeadlines[index]?.let { displayNow > it } == true
                                    }
                                    if (primaryUiPresentation.shouldRender(presentationCapture, expiredIndices)) {
                                        val currentBoxes = mapped.mapIndexed { index, box ->
                                            if (index in expiredIndices)
                                                box.copy(label = "분류 ${result.detections[index].className} · 거리 미확인") else box
                                        }
                                        boxes.updateMapped(currentBoxes, frameId, capturedMs, sourceComplete = true, animate = false)
                                        status.setTextIfChanged(message + currentBoxes.take(4).joinToString("\n") { it.label })
                                    }
                                    primaryUiPresentation.scheduleExpiry(presentationCapture, predictionDeadlines, displayNow,
                                        { foreground && epoch == generation && trackingGeometryGeneration == unknownGeometryGeneration.get() &&
                                            primaryOverlayCapture == presentationCapture },
                                        ::renderPrimaryDepth)
                                }
                                renderPrimaryDepth()
                                val displayNow = SystemClock.elapsedRealtime()
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
                        try {
                            ownedImage.close()
                        } finally {
                            try {
                                // Include capture, queueing, preprocessing and publication, not just native inference.
                                if (detectorInvoked && foreground && epoch == generation && model === detector) {
                                    primaryInferencePacing.complete(pacingTicket, SystemClock.elapsedRealtime(), inferenceDurationMs)
                                } else primaryInferencePacing.cancel(pacingTicket)
                            } finally {
                                busy.set(false)
                                if (foreground && epoch == generation) runOnUiThread {
                                    if (foreground && epoch == generation && !isDestroyed && !isFinishing) {
                                        auxiliaryStartup.complete(epoch)
                                        // A newer primary flight may have started before this UI callback.
                                        // Its completion gets another chance to start the auxiliary model.
                                        resumeUnknownAfterPrimaryLiveAttempt()
                                    }
                                }
                            }
                        }
                    }
                }
                transferred = true
            } finally {
                try {
                    releaseUntransferredCameraTestFrame(source, image, busy, transferred)
                } finally {
                    if (!transferred) {
                        try { primaryInferencePacing.cancel(pacingTicket) }
                        finally { busy.set(false) }
                    }
                }
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
            primaryUiPresentation.clear()
            primaryOverlayCapture = null
            boxes.clear()
            renderUnknownObjects(SystemClock.elapsedRealtime())
        }
    }

    private fun logDetectorTiming(event: String, timing: AndroidDetectorLoadTiming?) {
        if (timing == null) return
        Log.i("WalkSafeCameraTest", "$event loadGeneration=${timing.loadGeneration} loadMs=${timing.loadElapsedMs} " +
            "available=${timing.detectorAvailable} requestThread=${timing.requestThreadId}/${timing.requestThreadName}")
        timing.phases.forEach { step ->
            Log.i("WalkSafeCameraTest", "$event loadGeneration=${timing.loadGeneration} phase=${step.phase} " +
                "ms=${step.elapsedMs} outcome=${step.outcome} model=${step.modelKey} delegate=${step.delegate} " +
                "thread=${step.threadId}/${step.threadName} failure=${step.failureType}")
        }
    }

    private fun logCameraQueue(phase: String, epoch: Int, queuedNs: Long, sessionEpoch: Long? = null) {
        Log.i("WalkSafeCameraTest", "cameraGeneration=$epoch cameraSessionEpoch=$sessionEpoch phase=$phase ms=${(System.nanoTime() - queuedNs) / 1_000_000.0} " +
            "outcome=STARTED thread=${Thread.currentThread().id}/${Thread.currentThread().name}")
    }

    private fun <T> measureCameraStep(phase: String, sessionEpoch: Long?, block: () -> T): T {
        val startedNs = System.nanoTime()
        var failure: Throwable? = null
        try { return block() }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            Log.i("WalkSafeCameraTest", "cameraSessionEpoch=$sessionEpoch phase=$phase ms=${(System.nanoTime() - startedNs) / 1_000_000.0} " +
                "outcome=${if (failure == null) "SUCCEEDED" else "FAILED"} failure=${failure?.javaClass?.simpleName} " +
                "thread=${Thread.currentThread().id}/${Thread.currentThread().name}")
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
        discardRetiredUnknownOverlay()
        val delivery = feedback.next(now)
        if (delivery != null) {
            if (!feedback.claim(delivery, now)) feedback.reject(delivery)
            else {
                var vibrationAccepted = false
                val dispatch = actuator.emit(
                    action = delivery.action,
                    isStillValidAtStart = {
                        discardRetiredUnknownOverlay()
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
        feedbackStatus.setTextIfChanged(diagnostic + if (lastFeedbackEvent.isBlank()) "" else "\n$lastFeedbackEvent")
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

    private fun updatePreviewGeometry(frame: com.google.ar.core.Frame, camera: Session, epoch: Int, rotation: Int) {
        if (frame.timestamp <= 0L || !previewRequests.shouldRequest(epoch, rotation)) return
        // GPU dimensions are unrotated. The live IMAGE -> VIEW axis supplies rotation parity;
        // CPU image dimensions are deliberately not used to size the displayed texture.
        val dimensions = frame.camera.textureIntrinsics.imageDimensions
        if (dimensions.size < 2 || dimensions[0] <= 0 || dimensions[1] <= 0) return
        val axis = FloatArray(4)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, floatArrayOf(0f, 0f, 1f, 0f), Coordinates2d.VIEW, axis)
        val turns = UprightCameraImage.quarterTurns(axis) ?: return
        val request = previewRequests.request(epoch, rotation) ?: return
        invalidateUnknownGeometry()
        runOnUiThread {
            @Suppress("DEPRECATION")
            val currentRotation = windowManager.defaultDisplay.rotation
            if (foreground && epoch == generation && session === camera && currentRotation == rotation &&
                previewRequests.isCurrent(request)
            ) preview.setImageSize(dimensions[0], dimensions[1], turns, request.revision)
            else previewRequests.reject(request)
        }
    }

    private fun invalidateUnknownGeometry() {
        val geometry = unknownGeometryGeneration.incrementAndGet()
        unknownMaterialPoints.invalidate()
        runOnUiThread {
            if (geometry != unknownGeometryGeneration.get()) return@runOnUiThread
            primaryOverlayCapture = null
            unknownOverlay = null
            primaryUiPresentation.clear()
            unknownUiPresentation.clear()
            objectPresentation.clear()
            boxes.clear()
            unknownBoxes.clear()
            feedback.clear(CameraTestFeedbackCoordinator.Source.PRIMARY)
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
    }

    private fun unknownTrackingLive(scope: CameraUnknownMaterialPointScope): Boolean =
        scope.accepts && foreground && scope.cameraEpoch == generation &&
            scope.geometryGeneration == unknownGeometryGeneration.get() && unknownRuntime.accepts(scope.serviceEpoch)

    private fun showUnknownResult(result: FastSamResult<UnknownCapture>) {
        val callbackStartedNs = System.nanoTime()
        val capture = result.attachment
        if (!unknownTrackingLive(capture.trackingScope) || !capture.trackingFlight.accepts) {
            unknownMaterialPoints.cancelFlight(capture.trackingScope, capture.trackingFlight) { unknownTrackingLive(capture.trackingScope) }
            return
        }
        val consumed = unknownMaterialPoints.process(capture.trackingScope, capture.depth, result.token,
            capture.imageQuarterTurns, result.masks, SystemClock.elapsedRealtime(), callbackStartedNs,
            capture.trackingFlight) {
            unknownTrackingLive(capture.trackingScope)
        } ?: return
        val processed = consumed.depth.forDisplayAt(SystemClock.elapsedRealtime())
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
            currentEpoch = cameraEpoch.takeIf { unknownTrackingLive(capture.trackingScope) && capture.trackingFlight.accepts },
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
            if (unknownTrackingLive(capture.trackingScope) && capture.trackingFlight.accepts) {
                val presentationCapture = CameraObjectPresentation.Capture(capture.epoch, result.token.frameId,
                    result.token.capturedElapsedNs / 1_000_000L, result.token.geometryId)
                if (primaryOverlayCapture?.let {
                        it.geometryId != presentationCapture.geometryId && it.frameId >= presentationCapture.frameId
                    } == true || unknownOverlay?.capture?.frameId?.let { it >= result.token.frameId } == true
                ) return@runOnUiThread
                val measured = processed.observations.count { CameraUnknownObservation.distanceM(it) != null }
                val waiting = processed.observations.firstOrNull { CameraUnknownObservation.distanceM(it) == null }?.depth?.reason
                val publishedOverlay = UnknownOverlay(presentationCapture, capture.trackingScope, selected.map { candidate ->
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
                if (!unknownUiPresentation.begin(presentationCapture)) return@runOnUiThread
                unknownOverlay = publishedOverlay
                val displayNow = SystemClock.elapsedRealtime()
                renderUnknownObjects(displayNow)
                if (!offerCameraUnknownFeedbackAfterRender(capture.trackingScope, capture.trackingFlight,
                        { unknownOverlay === publishedOverlay }, ::unknownTrackingLive) {
                    feedback.offer(CameraTestFeedbackCoordinator.Sample(
                        CameraTestFeedbackCoordinator.Source.UNKNOWN, capture.epoch, result.token.frameId,
                        result.token.capturedElapsedNs / 1_000_000L, completedMs, result.token.geometryId,
                        admitted?.outputs ?: emptyList()),
                        retainedUnknownTrackIds = processed.retainedProximityRegionIds,
                        nowMs = SystemClock.elapsedRealtime())
                }) return@runOnUiThread
                updateFeedback()
            }
        }
    }

    /** Recompute presentation when either model finishes; the risk queue retains both sources. */
    private fun renderUnknownObjects(nowMs: Long) {
        discardRetiredUnknownOverlay()
        val observation = unknownOverlay ?: return
        if (observation.capture.epoch != generation ||
            nowMs - observation.capture.capturedAtMs !in 0L..UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS ||
            primaryOverlayCapture?.let { it.geometryId != observation.capture.geometryId } == true
        ) {
            unknownOverlay = null
            unknownUiPresentation.clear()
            unknownBoxes.clear()
            return
        }
        val merged = objectPresentation.mergedUnknownIndices(observation.capture, observation.regions, nowMs)
        val visibleIndices = observation.boxes.indices.filter { index -> index !in merged &&
            observation.predictionDeadlinesMs[index]?.let { nowMs <= it } != false }.take(6)
        if (unknownUiPresentation.shouldRender(observation.capture, merged to visibleIndices)) {
            unknownBoxes.updateMapped(visibleIndices.map { observation.boxes[it] }, observation.capture.frameId,
                observation.capture.capturedAtMs, sourceComplete = true, animate = false)
            unknownStatus.setTextIfChanged("FastSAM ${observation.rawCount}개 → 3m 내 후보 ${observation.candidateCount}개 · " +
                "학습 객체와 통합 ${merged.size}개 · 추가 표시 ${visibleIndices.size}개\n" + observation.detail)
        }
        unknownUiPresentation.scheduleExpiry(observation.capture,
            observation.predictionDeadlinesMs + (observation.capture.capturedAtMs + UnknownObjectFeedbackPolicy.MAX_SOURCE_AGE_MS),
            nowMs, { unknownOverlay === observation && unknownTrackingLive(observation.trackingScope) }) {
            renderUnknownObjects(SystemClock.elapsedRealtime())
        }
    }

    private fun discardRetiredUnknownOverlay() {
        discardRetiredCameraUnknownOverlay(unknownOverlay?.trackingScope, ::unknownTrackingLive) {
            unknownOverlay = null
            unknownUiPresentation.clear()
            unknownBoxes.clear()
            feedback.clear(CameraTestFeedbackCoordinator.Source.UNKNOWN)
        }
    }

    private fun TextView.setTextIfChanged(value: String) {
        if (!value.contentEquals(text)) text = value
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

    private companion object { val nextCameraSessionEpoch = AtomicLong() }
}

/** Shared with direct tests of the render-clear-feedback-offer ordering in the UI callback. */
internal fun offerCameraUnknownFeedbackAfterRender(
    scope: CameraUnknownMaterialPointScope,
    flight: CameraUnknownMaterialPointFlight,
    hasOriginalOverlay: () -> Boolean,
    isCurrent: (CameraUnknownMaterialPointScope) -> Boolean,
    offer: () -> Unit,
): Boolean {
    if (!scope.accepts || !flight.accepts || !isCurrent(scope) || !hasOriginalOverlay()) return false
    offer()
    return true
}

/** A preprocessing failure must not consume the one log for the first actual native attempt. */
internal class CameraFirstInferenceTimingLog {
    private var emitted = false
    val pending: Boolean get() = !emitted

    fun take(timing: AndroidDetectorLoadTiming): AndroidDetectorLoadTiming? {
        if (emitted || timing.phases.none { it.phase == AndroidDetectorLoadPhase.FIRST_INFERENCE }) return null
        emitted = true
        return timing
    }
}

/** Owns the image until return; the GL caller then owns transfer or close in its existing finally. */
internal data class CameraTestFrameCapture(
    val image: android.media.Image,
    val snapshot: DepthFrameSnapshot,
    val depthError: Throwable?,
    val fullDepthTiming: FullDepthTiming? = null,
)

internal fun captureCameraTestFrame(
    source: ArCoreFrameProvider,
    frame: Frame,
    profileFullDepth: Boolean = false,
): CameraTestFrameCapture? {
    // Select once before acquisition: an unsuccessful TRACKING packet never retries as RGB-only.
    val untracked = frame.camera.trackingState != TrackingState.TRACKING
    val recognition = if (untracked) null else {
        source.acquireRecognitionCameraImageOrNull(frame) ?: return null
    }
    val image = if (untracked) {
        source.acquireUntrackedRecognitionCameraImageOrNull(frame) ?: return null
    } else requireNotNull(recognition).image
    return try {
        if (recognition == null) CameraTestFrameCapture(image, DepthFrameSnapshot(frame.timestamp, null, null, null), null)
        else {
            // Depth is optional evidence; its failure must not discard a usable RGB frame.
            // Synchronous local collection belongs only to this capture/receipt, never a latest frame.
            var fullTiming: FullDepthTiming? = null
            val timingObserver: ((FullDepthTiming) -> Unit)? = if (profileFullDepth) {
                { timing -> fullTiming = timing }
            } else null
            val depthResult = runCatching {
                source.acquireDepthSnapshot(frame, cameraImageTimestampEvidence = recognition.timestampEvidence,
                    includeFullDepthWhenRawAvailable = true, fullDepthTimingObserver = timingObserver)
            }
            val snapshot = depthResult.getOrElse { DepthFrameSnapshot(frame.timestamp, null, null, null) }
            CameraTestFrameCapture(image, snapshot, depthResult.exceptionOrNull(), fullTiming)
        }
    } catch (error: Throwable) {
        source.invalidateRecognitionCameraImageTimestamps()
        image.close()
        throw error
    }
}

/** Debug request costs only; null phases must not masquerade as measured zero milliseconds. */
internal fun cameraFullDepthTimingDiagnostic(timing: FullDepthTiming): String {
    fun phase(durationNs: Long?, succeeded: Boolean?): String {
        if (succeeded == null) return "미관측"
        val duration = durationNs?.let { "${it / 1_000_000.0}ms" } ?: "시간 미관측"
        return "$duration ${if (succeeded) "성공" else "실패"}"
    }
    return "Full 요청 ${timing.purpose} · 획득 ${phase(timing.acquisitionDurationNs, timing.acquisitionSucceeded)} · " +
        "변환 ${phase(timing.conversionDurationNs, timing.conversionSucceeded)} · " +
        "종료 ${phase(timing.closeDurationNs, timing.closeSucceeded)}"
}

/** Accepted work keeps its image; only a capture that failed to transfer is released by GL. */
internal fun releaseUntransferredCameraTestFrame(
    source: ArCoreFrameProvider,
    image: android.media.Image?,
    busy: AtomicBoolean,
    transferred: Boolean,
) {
    if (!transferred) {
        if (image != null) source.invalidateRecognitionCameraImageTimestamps()
        image?.close()
        busy.set(false)
    }
}
