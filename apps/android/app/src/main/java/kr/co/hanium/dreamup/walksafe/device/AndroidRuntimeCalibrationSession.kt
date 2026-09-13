package kr.co.hanium.dreamup.walksafe.device

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kr.co.hanium.dreamup.walksafe.CameraBackgroundRenderer
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.RuntimeComparisonEnvironment
import kr.co.hanium.dreamup.walksafe.inference.RuntimeFeatureScope
import kr.co.hanium.dreamup.walksafe.inference.tracking.GrayTrackingFrame
import kr.co.hanium.dreamup.walksafe.inference.tracking.AdaptiveVisualTrackingBudget
import kr.co.hanium.dreamup.walksafe.inference.tracking.InterFrameDetectionTracker
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Owns one private camera and tracker, never the activity's navigation/risk/feedback pipeline.
 * The caller grants the camera lease before start and releases it ONLY from onClosed. Cancellation
 * stops admissions immediately; onClosed waits for borrowed inference, image return and camera
 * closure. Neither a deadline nor a disappearing window is evidence that native work has drained.
 */
class AndroidRuntimeCalibrationSession(
    private val activity: Activity,
    private val bindingHash: String,
    private val featureScope: RuntimeFeatureScope,
    private val speechActive: () -> Boolean,
    private val runnerFactory: (
        environmentProvider: () -> RuntimeComparisonEnvironment,
        onProgress: (RuntimeCalibrationProgress) -> Unit,
        onFinished: (RuntimeCalibrationResult) -> Unit,
        onLiveDetection: (AndroidDetectionResult, RuntimeCalibrationLiveEvidence) -> Unit,
    ) -> RuntimeCalibrationWorkController,
    private val onClosed: (RuntimeCalibrationResult?) -> Unit,
    private val cadenceMs: Long = AdaptiveInferencePacingPolicy.INITIAL_INTERVAL_MS,
    private val budgetMs: Long = AndroidRuntimeCalibrationRunner.MAXIMUM_BUDGET_MS,
    private val startRunnerBeforeCameraReady: Boolean = false,
) {
    init {
        require(bindingHash.isNotBlank() && cadenceMs > 0L)
        require(budgetMs in 1L..AndroidRuntimeCalibrationRunner.MAXIMUM_BUDGET_MS)
    }

    private val main = Handler(Looper.getMainLooper())
    private val cameraWorker = Executors.newSingleThreadExecutor { Thread(it, "WalkSafeCalibrationCamera") }
    private val deadlineWorker = ScheduledThreadPoolExecutor(1) {
        Thread(it, "WalkSafeCalibrationCameraDeadline")
    }.apply {
        // A successful comparison can finish before the 300-second deadline. Shutdown must
        // discard that pending timer, while allowing an already-running callback to return.
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        removeOnCancelPolicy = true
    }
    private val closing = AtomicBoolean(false)
    private val uiProbePending = AtomicBoolean(false)
    private val trackerLock = Any()
    private val tracker = InterFrameDetectionTracker(VisualTrackingConfig(allowSimilarityTransform = true))
    private val trackingBudget = AdaptiveVisualTrackingBudget()
    private val frameKeys = LinkedHashMap<Long, VisualFrameKey>()
    private var latestSource: LiveSource? = null
    private var geometryVersion = 1L
    private var sourceDimensions: Pair<Int, Int>? = null
    private var lastCameraTimestampNs = 0L
    private var trackerInitialized = false
    private var lastStage: RuntimeCalibrationStage? = null
    @Volatile private var uiDelayMs = Double.NaN
    @Volatile private var uiProbePostedAtUptimeMs = 0L
    @Volatile private var cameraState = "STARTING"
    @Volatile private var arCoreState = "STARTING"
    @Volatile private var runner: RuntimeCalibrationWorkController? = null
    private var runnerDrained = true
    private var runnerStartRequested = false
    private var result: RuntimeCalibrationResult? = null
    private var started = false
    private var closed = false
    private var startedAtMs = 0L
    private var cameraCleanupStarted = false
    private var dialog: Dialog? = null
    private lateinit var progressText: TextView
    private lateinit var remainingText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cancelButton: Button
    private lateinit var previewContainer: FrameLayout
    private var glView: GLSurfaceView? = null
    private var arSession: Session? = null
    private var arProvider: ArCoreFrameProvider? = null
    private var arSetupPending = false
    @Volatile private var arResumed = false
    private var arTexture = 0
    private var arTextureBound = false
    private var backgroundRenderer = CameraBackgroundRenderer()
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastDisplayRotation = -1
    private val cameraLifecycle = CalibrationLifecycleOwner()
    private var cameraXProvider: ProcessCameraProvider? = null
    private var cameraXCamera: Camera? = null
    private var cameraXInfo: CameraInfo? = null
    private var cameraXAnalysis: ImageAnalysis? = null
    private var cameraXPreview: Preview? = null

    /** Main thread entry; no permission, AR installation, account or walk flow is started here. */
    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || closed) return
        started = true
        startedAtMs = nowMs()
        if (closing.get() || activity.isFinishing || activity.isDestroyed) {
            cancel("activity_unavailable")
            return
        }
        try {
            deadlineWorker.schedule({ cancel("deadline") }, budgetMs, TimeUnit.MILLISECONDS)
            showDialog()
            main.post(tick)
            if (startRunnerBeforeCameraReady) requestRunnerStart()
            if (featureScope == RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH) startArCore()
            else startCameraX()
        } catch (_: Exception) {
            cancel("camera_setup_unavailable")
        }
    }

    /** May be called from any thread, including activity pause/destroy. Never interrupts native work. */
    fun cancel(reason: String = "cancelled") {
        closing.set(true)
        runner?.cancel(reason)
        onMain {
            if (closed) return@onMain
            showDraining()
            cameraXAnalysis?.clearAnalyzer()
            val activeRunner = runner
            if (activeRunner == null) {
                runnerDrained = true
                closeCameraWhenDrained()
            } else {
                activeRunner.whenDrained {
                    onMain { runnerDrained = true; closeCameraWhenDrained() }
                }
            }
        }
    }

    private fun showDialog() {
        val padding = dp(20)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(activity).apply {
            text = "휴대폰 성능 점검"
            textSize = 22f
            setTextColor(Color.BLACK)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        content.addView(TextView(activity).apply {
            text = "안전한 곳에 멈춰 주변 물체를 향해 카메라를 들어 주세요. " +
                "최대 5분 동안 점검하며 언제든 취소할 수 있습니다."
            textSize = 17f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, dp(12))
        })
        previewContainer = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        content.addView(previewContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        progressText = TextView(activity).apply {
            text = "카메라 준비 중"
            textSize = 18f
            setTextColor(Color.BLACK)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(progressText)
        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = ((budgetMs + 999L) / 1_000L).toInt()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(progressBar)
        remainingText = TextView(activity).apply { textSize = 16f; setTextColor(Color.DKGRAY) }
        content.addView(remainingText)
        cancelButton = Button(activity).apply {
            text = "점검 취소"
            minHeight = dp(48)
            setOnClickListener { cancel("user_cancelled") }
        }
        content.addView(cancelButton)
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setContentView(content)
            setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) cancel("user_cancelled")
                    true
                } else false
            }
            setOnDismissListener { if (!closed) cancel("window_dismissed") }
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            show()
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            val elapsed = (nowMs() - startedAtMs).coerceAtLeast(0L)
            val seconds = ((budgetMs - elapsed).coerceAtLeast(0L) + 999L) / 1_000L
            if (::remainingText.isInitialized) {
                remainingText.text = if (closing.get()) "진행 중인 작업과 카메라를 정리하고 있습니다."
                else "남은 점검 시간 ${seconds / 60}분 ${seconds % 60}초"
                progressBar.progress = (elapsed / 1_000L).toInt().coerceAtMost(progressBar.max)
            }
            if (elapsed >= budgetMs && !closing.get()) cancel("deadline")
            main.postDelayed(this, 1_000L)
        }
    }

    private fun startArCore() {
        if (ArCoreApk.getInstance().checkAvailability(activity) != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            cancel("metric_camera_unavailable")
            return
        }
        glView = GLSurfaceView(activity).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = false
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    backgroundRenderer = CameraBackgroundRenderer()
                    backgroundRenderer.createOnGlThread()
                    val textures = IntArray(1)
                    GLES20.glGenTextures(1, textures, 0)
                    arTexture = textures[0]
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, arTexture)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    arTextureBound = false
                    resetTracking()
                }

                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    GLES20.glViewport(0, 0, width, height)
                    viewWidth = width
                    viewHeight = height
                    lastDisplayRotation = -1
                    resetTracking()
                }

                override fun onDrawFrame(gl: GL10?) = drawArFrame()
            })
        }
        previewContainer.addView(glView, FrameLayout.LayoutParams(-1, -1))
        arSetupPending = true
        cameraWorker.execute {
            var prepared = false
            try {
                if (!closing.get()) {
                    val session = Session(activity.applicationContext)
                    arSession = session
                    val provider = ArCoreFrameProvider(session)
                    arProvider = provider
                    prepared = provider.configureDepthMode()
                    if (prepared) {
                        session.configure(session.config.apply { updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE })
                    }
                }
            } catch (_: Exception) {
                prepared = false
            } finally {
                onMain {
                    arSetupPending = false
                    if (!prepared || closing.get()) cancel("metric_camera_unavailable")
                    else try {
                        arSession?.resume()
                        arResumed = true
                    } catch (_: Exception) { cancel("camera_resume_unavailable") }
                }
            }
        }
    }

    private fun drawArFrame() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (closing.get() || !arResumed || viewWidth <= 0 || viewHeight <= 0) return
        val session = arSession ?: return
        val provider = arProvider ?: return
        try {
            if (!arTextureBound) {
                session.setCameraTextureName(arTexture)
                arTextureBound = true
            }
            val rotation = displayRotation()
            if (lastDisplayRotation != rotation) {
                session.setDisplayGeometry(rotation, viewWidth, viewHeight)
                lastDisplayRotation = rotation
                resetTracking()
            }
            val frame = session.update()
            if (frame.hasDisplayGeometryChanged()) resetTracking()
            backgroundRenderer.draw(frame, arTexture)
            if (frame.timestamp <= 0L) return
            cameraState = "ACTIVE"
            arCoreState = frame.camera.trackingState.name
            val capturedAt = nowMs()
            val snapshot = provider.acquireDepthSnapshot(frame, includeFullDepthWhenRawAvailable = true)
            val pose = frame.camera.pose
            val poseValid = frame.camera.trackingState == TrackingState.TRACKING &&
                pose.translation.all { it.isFinite() } && pose.rotationQuaternion.all { it.isFinite() } &&
                snapshot.cameraPoseEvidence != null
            val image = provider.acquireCameraImageOrNull(frame) ?: return
            val imageToDepth = runCatching {
                val w = image.width.toFloat()
                val h = image.height.toFloat()
                val input = floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h, w / 2f, h / 2f)
                val output = FloatArray(10)
                frame.transformCoordinates2d(com.google.ar.core.Coordinates2d.IMAGE_PIXELS, input,
                    com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED, output)
                val points = output.toList().chunked(2).map { kr.co.hanium.dreamup.walksafe.depth.Point2(it[0], it[1]) }
                kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform.create(
                    frame.timestamp, points.take(4), points[4],
                )?.imagePixelsToDepthUvMatrix(image.width, image.height)
            }.getOrNull()
            offerImage(image, frame.timestamp, capturedAt, snapshot, poseValid, image::close,
                imageToDepth, frame.timestamp)
        } catch (_: Exception) {
            cancel("camera_frame_unavailable")
        }
    }

    private fun startCameraX() {
        val previewView = PreviewView(activity).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        previewContainer.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (closing.get() || closed) return@addListener
            try {
                val provider = future.get()
                cameraXProvider = provider
                // Keep a close-state witness even if bind throws after starting camera work.
                cameraXInfo = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                ).build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setTargetRotation(displayRotation())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setOutputImageRotationEnabled(true)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                cameraXAnalysis = analysis
                val preview = Preview.Builder().setResolutionSelector(resolution)
                    .setTargetRotation(displayRotation()).build()
                cameraXPreview = preview
                preview.setSurfaceProvider(previewView.surfaceProvider)
                analysis.setAnalyzer(cameraWorker, ::analyzeCameraXFrame)
                cameraLifecycle.moveTo(Lifecycle.State.RESUMED)
                cameraXCamera = provider.bindToLifecycle(cameraLifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                cancel("camera_setup_unavailable")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyzeCameraXFrame(proxy: ImageProxy) {
        if (closing.get()) { proxy.close(); return }
        val image = proxy.image
        if (image == null) { proxy.close(); return }
        cameraState = "ACTIVE"
        arCoreState = "CAMERA_TRACKING"
        // CameraX has no AR pose or metric depth. These values must remain unverified in this scope.
        // Retaining one proxy matches the production fallback's serial analyzer. Timestamp gaps
        // measure delivered analysis frames, including inference backpressure, not preview FPS.
        offerImage(image, image.timestamp, nowMs(), null, false, proxy::close)
    }

    /** The only transfer boundary. False admission and every pre-transfer exception return ownership. */
    private fun offerImage(
        image: Image,
        sourceTimestampNs: Long,
        capturedAt: Long,
        depth: DepthFrameSnapshot?,
        poseValid: Boolean,
        releaseImage: () -> Unit,
        imageToDepthUv: DoubleArray? = null,
        mapperFrameId: Long? = null,
    ) {
        var transferred = false
        try {
            if (closing.get() || image.timestamp <= 0L || nowMs() - startedAtMs >= budgetMs) return
            val trackingStarted = System.nanoTime()
            val interval: Double
            val gray: GrayTrackingFrame
            synchronized(trackerLock) {
                if (image.timestamp <= lastCameraTimestampNs) return
                interval = if (lastCameraTimestampNs > 0L) (image.timestamp - lastCameraTimestampNs) / 1_000_000.0 else Double.NaN
                lastCameraTimestampNs = image.timestamp
                val dimensions = image.width to image.height
                if (sourceDimensions != null && sourceDimensions != dimensions) resetTrackingLocked()
                sourceDimensions = dimensions
                val plane = image.planes.firstOrNull() ?: return
                gray = GrayTrackingFrame.copyFromLuma(
                    VisualFrameKey(1L, sourceTimestampNs, image.timestamp, capturedAt, geometryVersion),
                    image.width, image.height, plane.buffer, plane.rowStride, plane.pixelStride,
                )
                if (!trackerInitialized) {
                    trackerInitialized = tracker.initialize()
                    if (!trackerInitialized) { cancel("tracking_unavailable"); return }
                }
                val admission = tracker.offerFrame(gray)
                if (!admission.accepted) return
                if (admission.reset) latestSource = null
                frameKeys[image.timestamp] = gray.key
                while (frameKeys.size > 32) frameKeys.remove(frameKeys.keys.first())
                val workBudgetNs = trackingBudget.budgetForFrame(gray.key)
                latestSource?.let { source ->
                    tracker.trackFrom(source.key, source.result.detections, gray.key,
                        completed = true, observedAtElapsedRealtimeMs = nowMs(), executionBudgetNs = workBudgetNs)
                }
            }
            val trackingMs = (System.nanoTime() - trackingStarted) / 1_000_000.0
            probeUiDelay()
            val alignedDepth = depth?.takeIf { it.cameraImageTimestampNs == image.timestamp }
            val freshDepth = alignedDepth?.let { it.hasFreshFullDepth || it.hasFreshMetricRawDepth } == true
            val samples = if (freshDepth) alignedDepth?.validMetricSampleCount(
                minimumRawConfidence = 0.5, minimumDistanceMeters = 0.1,
                maximumDistanceMeters = 10.0, requireFreshRaw = true,
            ) ?: 0 else 0
            val metricReady = featureScope != RuntimeFeatureScope.CAMERA_TRACKING_METRIC_DEPTH ||
                (poseValid && freshDepth && samples > 0 && arCoreState == "TRACKING")
            if (metricReady && interval.isFinite() && uiDelayMs.isFinite()) requestRunnerStart()
            val evidence = RuntimeCalibrationLiveEvidence(
                capturedAtElapsedRealtimeMs = capturedAt,
                cameraTimestampNs = image.timestamp,
                sourceFrameTimestampNs = sourceTimestampNs,
                environment = environment(),
                cameraFrameIntervalMs = interval,
                trackingCostMs = trackingMs,
                uiFrameDelayMs = currentUiDelayMs(),
                depthTimestampFresh = freshDepth,
                positiveDepthSamples = samples,
                poseValid = poseValid,
                speechActive = speechActive(),
                depthSnapshot = alignedDepth,
                imageToDepthUv = imageToDepthUv?.copyOf(),
                mapperFrameId = mapperFrameId,
            )
            // A phase/geometry reset deliberately has no predecessor. Do not submit that missing
            // interval as a zero or let it invalidate an otherwise measurable live window.
            if (!closing.get() && interval.isFinite() && interval > 0.0 && evidence.uiFrameDelayMs.isFinite() &&
                nowMs() - startedAtMs < budgetMs) {
                transferred = runner?.offerLiveImage(image, evidence, releaseImage) == true
            }
        } catch (_: Exception) {
            cancel("camera_tracking_unavailable")
        } finally {
            if (!transferred) releaseImage()
        }
    }

    private fun requestRunnerStart() = onMain {
        if (closing.get() || runnerStartRequested || nowMs() - startedAtMs >= budgetMs) return@onMain
        runnerStartRequested = true
        try {
            val preparedRunner = runnerFactory(::environment, ::receiveProgress, ::receiveFinished, ::receiveDetection)
            runner = preparedRunner
            runnerDrained = false
            if (closing.get() || nowMs() - startedAtMs >= budgetMs) {
                cancel("deadline")
            } else if (!preparedRunner.start()) {
                // Factory must return a new runner; its own drain still governs any existing work.
                cancel("runner_start_unavailable")
            }
        } catch (_: Exception) { cancel("runner_setup_unavailable") }
    }

    private fun environment(): RuntimeComparisonEnvironment {
        val power = activity.getSystemService(PowerManager::class.java)
        return RuntimeComparisonEnvironment(
            bindingHash, featureScope, Runtime.getRuntime().availableProcessors(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus ?: -1 else -1,
            power?.isPowerSaveMode ?: false, cameraState, arCoreState, cadenceMs,
            "private_camera_tracking_v1",
        )
    }

    private fun receiveProgress(progress: RuntimeCalibrationProgress) {
        // Serialize the phase boundary with image history, including callbacks from a worker.
        synchronized(trackerLock) {
            if (progress.stage != lastStage && progress.stage in listOf(
                    RuntimeCalibrationStage.LIVE_BASELINE, RuntimeCalibrationStage.LIVE_CANDIDATE,
                )) resetTrackingLocked()
            lastStage = progress.stage
        }
        onMain {
            if (closing.get() || closed) return@onMain
            progressText.text = when (progress.stage) {
                RuntimeCalibrationStage.PREPARING -> "실행 구성 준비 중"
                RuntimeCalibrationStage.OUTPUT_CHECK -> "인식 결과 확인 중"
                RuntimeCalibrationStage.COMPARING -> "처리 속도 비교 중"
                RuntimeCalibrationStage.LIVE_BASELINE -> "기준 구성으로 실제 카메라 점검 중"
                RuntimeCalibrationStage.LIVE_CANDIDATE -> "후보 구성으로 실제 카메라 점검 중"
                RuntimeCalibrationStage.SELECTING -> "점검 결과 적용 중"
                RuntimeCalibrationStage.DRAINING -> "점검 종료 · 카메라 정리 중"
            }
        }
    }

    private fun receiveDetection(detection: AndroidDetectionResult, evidence: RuntimeCalibrationLiveEvidence) {
        if (closing.get() || detection.partial) return
        synchronized(trackerLock) {
            val key = frameKeys[evidence.cameraTimestampNs] ?: return
            if (key.frameId != evidence.sourceFrameTimestampNs || key.geometryVersion != geometryVersion) return
            latestSource = LiveSource(key, detection)
        }
    }

    private fun receiveFinished(completed: RuntimeCalibrationResult) = onMain {
        result = completed
        runnerDrained = true
        closing.set(true)
        showDraining()
        cameraXAnalysis?.clearAnalyzer()
        closeCameraWhenDrained()
    }

    private fun probeUiDelay() {
        if (!uiProbePending.compareAndSet(false, true)) return
        val posted = SystemClock.uptimeMillis()
        uiProbePostedAtUptimeMs = posted
        main.post {
            uiDelayMs = (SystemClock.uptimeMillis() - posted).coerceAtLeast(0L).toDouble()
            uiProbePending.set(false)
        }
    }

    private fun currentUiDelayMs(): Double = if (uiProbePending.get() && uiDelayMs.isFinite()) {
        maxOf(uiDelayMs, (SystemClock.uptimeMillis() - uiProbePostedAtUptimeMs).coerceAtLeast(0L).toDouble())
    } else uiDelayMs

    private fun resetTracking() = synchronized(trackerLock) { resetTrackingLocked() }

    private fun resetTrackingLocked() {
        geometryVersion += 1L
        tracker.clear()
        trackingBudget.reset()
        latestSource = null
        frameKeys.clear()
        sourceDimensions = null
        lastCameraTimestampNs = 0L
    }

    private fun closeCameraWhenDrained() {
        if (closed || cameraCleanupStarted || !runnerDrained || arSetupPending) return
        cameraCleanupStarted = true
        cameraState = "CLOSING"
        cameraXAnalysis?.clearAnalyzer()
        if (glView != null) {
            // onPause is the GL drain barrier: no Frame/provider call survives its return.
            glView?.onPause()
            arResumed = false
            try {
                arProvider?.close()
                arSession?.pause()
            } catch (_: Exception) { showCleanupBlocked(); return }
            cameraWorker.execute {
                try {
                    arSession?.close()
                    arSession = null
                    arProvider = null
                    resetTracking()
                    onMain { finishClosed() }
                } catch (_: Exception) { onMain { showCleanupBlocked() } }
            }
        } else {
            // clearAnalyzer plus a serial executor barrier drains in-progress callbacks. Runner
            // already returned every transferred ImageProxy before this barrier is queued.
            cameraWorker.execute {
                resetTracking()
                onMain {
                    try {
                        cameraXAnalysis?.let { cameraXProvider?.unbind(it) }
                        cameraXPreview?.let { cameraXProvider?.unbind(it) }
                        cameraLifecycle.moveTo(Lifecycle.State.DESTROYED)
                        awaitCameraXClosed()
                    } catch (_: Exception) { showCleanupBlocked() }
                }
            }
        }
    }

    private fun awaitCameraXClosed() {
        if (closed) return
        if (cameraXInfo == null || cameraXInfo?.cameraState?.value?.type == CameraState.Type.CLOSED) {
            cameraXCamera = null
            cameraXInfo = null
            cameraXAnalysis = null
            cameraXPreview = null
            cameraXProvider = null
            finishClosed()
        } else {
            // Do not convert a timeout into camera release. Keep the lease until actual CLOSED.
            main.postDelayed(::awaitCameraXClosed, 100L)
        }
    }

    private fun showDraining() {
        if (!::progressText.isInitialized) return
        progressText.text = "점검 종료 · 진행 중인 작업을 정리하고 있습니다."
        cancelButton.isEnabled = false
        cancelButton.text = "정리 중"
    }

    private fun showCleanupBlocked() {
        if (::progressText.isInitialized) progressText.text = "카메라 정리를 확인하지 못했습니다. 앱을 다시 시작해 주세요."
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.removeCallbacks(tick)
        deadlineWorker.shutdown()
        cameraWorker.shutdown()
        // No onClosed: a failed native close must not unlock another camera owner.
    }

    private fun finishClosed() {
        if (closed) return
        closed = true
        cameraState = "CLOSED"
        main.removeCallbacks(tick)
        cameraWorker.shutdown()
        deadlineWorker.shutdown()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching { dialog?.dismiss() }
        dialog = null
        onClosed(result)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = activity.windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
    private fun nowMs(): Long = SystemClock.elapsedRealtime()
    private fun dp(value: Int): Int = (activity.resources.displayMetrics.density * value).toInt()
    private data class LiveSource(val key: VisualFrameKey, val result: AndroidDetectionResult)

    private class CalibrationLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) {
            if (registry.currentState == Lifecycle.State.INITIALIZED && state == Lifecycle.State.DESTROYED) {
                registry.currentState = Lifecycle.State.CREATED
            }
            registry.currentState = state
        }
    }
}
