package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.location.Location
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.Session
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.CameraIntrinsics
import kr.co.hanium.dreamup.walksafe.depth.CoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Vec3
import kr.co.hanium.dreamup.walksafe.depth.debugSummaryText
import kr.co.hanium.dreamup.walksafe.depth.routeBearingAlignmentQuality
import kr.co.hanium.dreamup.walksafe.depth.toSnapshotAndClose
import kr.co.hanium.dreamup.walksafe.debuglog.DebugFrameCaptureUploaderFactory
import kr.co.hanium.dreamup.walksafe.debuglog.DebugMetadataLogUploaderFactory
import kr.co.hanium.dreamup.walksafe.debuglog.FrameCaptureUploader
import kr.co.hanium.dreamup.walksafe.debuglog.MetadataLogUploader
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorTiming
import kr.co.hanium.dreamup.walksafe.inference.AndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage
import kr.co.hanium.dreamup.walksafe.inference.NoopAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.YuvImagePreprocessor
import kr.co.hanium.dreamup.walksafe.navigation.AndroidStepTracker
import kr.co.hanium.dreamup.walksafe.navigation.BackendWalkingRouteClient
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.haversineMeters
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.StepLengthEstimator
import kr.co.hanium.dreamup.walksafe.navigation.StepCalibrationSample
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteRequest
import kr.co.hanium.dreamup.walksafe.navigation.bearingDegrees
import kr.co.hanium.dreamup.walksafe.navigation.formatDestinationDistance
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCandidateInput
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCandidate
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCandidatePolicy
import kr.co.hanium.dreamup.walksafe.report.AndroidReportUploader
import kr.co.hanium.dreamup.walksafe.report.ReportUploadHttpException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.json.JSONArray
import org.json.JSONObject

private fun DetectionCandidate.isTactileDetection(): Boolean {
    return className.lowercase(Locale.US).contains("tactile") || className.contains("점자")
}

class MainActivity : Activity(), GLSurfaceView.Renderer {
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var actionButton: Button
    private lateinit var debugUploadButton: Button
    private lateinit var debugFrameCaptureButton: Button
    private lateinit var routeButton: Button
    private lateinit var backendUrlInput: EditText
    private lateinit var destinationQueryInput: EditText
    private lateinit var destinationSearchButton: Button
    private lateinit var destinationCancelButton: Button
    private lateinit var destinationResetButton: Button
    private lateinit var destinationMoreButton: Button
    private lateinit var progressBeepToggleButton: Button
    private lateinit var progressBeepVolumeButton: Button
    private lateinit var destinationSearchResultsContainer: LinearLayout
    private lateinit var destinationLatInput: EditText
    private lateinit var destinationLngInput: EditText
    private lateinit var navigationStatusText: TextView
    private lateinit var debugBboxOverlay: DebugBboxOverlayView
    private lateinit var loginUserIdInput: EditText
    private lateinit var loginSaveButton: Button
    private lateinit var explicitReportButton: Button
    private lateinit var voiceReportButton: Button

    private var installRequested = false
    private var session: Session? = null
    private var frameProvider: ArCoreFrameProvider? = null
    private val objectDepthPipeline = ObjectDepthRuntimePipeline()
    private val backgroundRenderer = CameraBackgroundRenderer()
    private var frameDetector: AndroidFrameDetector = NoopAndroidFrameDetector()
    private val captureLog = MetadataCaptureLog()
    private val tactileOverlayStabilizer = TactileOverlayStabilizer()
    private var feedbackActuator: AndroidFeedbackActuator? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceRecognitionActive = false
    private val feedbackPolicy = WalkSafeFeedbackPolicy()
    private var lastRiskAnnouncementMs = 0L
    private var lastRiskAnnouncement = ""
    private var lastNavigationAnnouncementMs = 0L
    private var lastNavigationAnnouncement = ""
    private var progressBeepEnabled = true
    private var progressBeepVolumePercent = 20
    private var lastProgressBeepAtMs = 0L
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var stepTracker: AndroidStepTracker
    private val stepLengthEstimator = StepLengthEstimator()
    private lateinit var stepLengthPrefs: SharedPreferences
    private val walkingRouteClient = BackendWalkingRouteClient()
    private val reportUploader = AndroidReportUploader()
    private val routeNavigator = RouteNavigator()
    private val reportUploaderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-report-uploader").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val reportCandidatePolicy = AndroidReportCandidatePolicy()
    private val routeRequestInFlight = AtomicBoolean(false)
    private var latestTrustedLocation: TrustedLocation? = null
    private var currentDestination: RoutePoint? = null
    @Volatile
    private var latestStepCount: Int = 0
    @Volatile
    private var latestDeviceGateAllowsSpeech = false
    @Volatile
    private var latestReportCandidateStatus = "reportCandidate=blocked"
    @Volatile
    private var latestHeadingDeg: Float? = null
    @Volatile
    private var reporterUserId: String? = null
    @Volatile
    private var latestExplicitReportOutput: TrackedObjectDepth? = null
    @Volatile
    private var latestExplicitReportImage: ByteArray? = null
    @Volatile
    private var latestExplicitReportGateState: DeviceGateState? = null
    @Volatile
    private var latestExplicitReportCapturedAtMs: Long = 0L
    private var isRouteActive = false
    private var navigationPermissionsRequestedForRoute = false
    private var navigationPermissionsRequestedForReport = false
    private var destinationSearchInFlight = false
    private var destinationSearchGeneration = 0
    private var destinationSearchQuery = ""
    private var destinationSearchPage = 1
    private val destinationSearchResults = mutableListOf<DestinationSearchResult>()
    private var routeRequestGeneration = 0
    private val routeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-route-client").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private var reportRuntimeConfig: TwoModelRuntimeConfig? = null
    private var reportModelConfigSha256: String? = null
    private var reportApkSha256: String? = null
    private val reportUploadStates = ConcurrentHashMap<String, ReportUploadState>()
    private lateinit var metadataLogUploader: MetadataLogUploader
    private lateinit var frameCaptureUploader: FrameCaptureUploader
    private val frameCapturePreprocessor = YuvImagePreprocessor()
    private val detectorExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-tflite-detector").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val detectionInFlight = AtomicBoolean(false)
    private val frameCaptureRequested = AtomicBoolean(false)

    private var cameraTextureId = 0
    private var cameraTextureBound = false
    private var lastUiUpdateMs = 0L
    private var lastOverlayUpdateMs = 0L
    @Volatile
    private var lastDetectionRunMs = 0L
    @Volatile
    private var latestDetectionSnapshot = DetectionSnapshot.empty()
    @Volatile
    private var latestTactileOverlaySnapshot = DetectionSnapshot.empty()
    @Volatile
    private var lastNonEmptyOverlaySnapshot = DetectionSnapshot.empty()
    @Volatile
    private var detectorGeneration = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var pendingSessionStartAfterInstall = false
    private var actionMode = ActionMode.START
    private var arCoreSupported = false
    private var depthSupported = false
    private var detectorAvailable = false
    private var detectorConfigLoaded = false
    private var detectorModelKeyForReports: String? = null
    private var detectorLoadedModelKey: String? = null
    private var detectorModelFallbackUsed: Boolean = false
    private var detectorLoadReason: String? = null
    private var detectorLoadAttempted = false
    private var detectorStatusText = "detector=load_pending_after_depth_gate"
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleLocationUpdate(it) }
        }
    }
    private var lastCalibrationLocation: TrustedLocation? = null
    private var lastCalibrationStepCount: Int = 0
    private var lastCalibrationAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        metadataLogUploader = DebugMetadataLogUploaderFactory.create(this)
        frameCaptureUploader = DebugFrameCaptureUploaderFactory.create(this)
        stepLengthPrefs = getSharedPreferences("walksafe", MODE_PRIVATE)
        restoreStepLengthFromPrefs()
        restoreProgressBeepPrefs()
        restoreReporterUserFromPrefs()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        stepTracker = AndroidStepTracker(this) { steps -> latestStepCount = steps }
        setContentView(buildContentView())
        prepareReportMetadataContext()
        updateRouteButtonText()
        updateStatus(
            status = "ARCore Depth 대기",
            detail = "목적지 없이 위험 인식 모드를 시작할 수 있습니다. TFLite는 권한/ARCore/Depth 확인 후 로드합니다.",
        )
    }

    private fun restoreStepLengthFromPrefs() {
        val savedStepLength = stepLengthPrefs.getFloat(PREF_STEP_LENGTH_KEY, StepLengthEstimator.DEFAULT_STEP_LENGTH_M)
        stepLengthEstimator.setStepLengthM(savedStepLength)
        objectDepthPipeline.setUserStepLength(savedStepLength)
    }

    private fun persistStepLength() {
        stepLengthPrefs.edit().putFloat(PREF_STEP_LENGTH_KEY, stepLengthEstimator.stepLengthM).apply()
    }

    private fun restoreProgressBeepPrefs() {
        progressBeepEnabled = stepLengthPrefs.getBoolean(PREF_PROGRESS_BEEP_ENABLED_KEY, true)
        progressBeepVolumePercent = stepLengthPrefs.getInt(PREF_PROGRESS_BEEP_VOLUME_KEY, DEFAULT_PROGRESS_BEEP_VOLUME_PERCENT)
            .coerceIn(0, 100)
    }

    private fun persistProgressBeepPrefs() {
        stepLengthPrefs.edit()
            .putBoolean(PREF_PROGRESS_BEEP_ENABLED_KEY, progressBeepEnabled)
            .putInt(PREF_PROGRESS_BEEP_VOLUME_KEY, progressBeepVolumePercent)
            .apply()
    }

    private fun restoreReporterUserFromPrefs() {
        reporterUserId = stepLengthPrefs.getString(PREF_REPORTER_USER_ID_KEY, null)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun persistReporterUserFromInput() {
        val nextUserId = loginUserIdInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
        reporterUserId = nextUserId
        stepLengthPrefs.edit().putString(PREF_REPORTER_USER_ID_KEY, nextUserId.orEmpty()).apply()
        updateLoginButtonText()
        updateNavigationStatus(if (nextUserId == null) "login=required" else "login=ready reporter=$nextUserId")
    }

    private fun prepareReportMetadataContext() {
        reportApkSha256 = computeApkSha256()
        reportRuntimeConfig = loadReportRuntimeConfig()
    }

    private fun loadReportRuntimeConfig(): TwoModelRuntimeConfig? {
        return try {
            val runtimeJson = assets.open("model-config/two_model_runtime.json").bufferedReader().use { it.readText() }
            reportModelConfigSha256 = sha256Hex(runtimeJson.toByteArray())
            TwoModelRuntimeConfig.parse(runtimeJson)
        } catch (_: Exception) {
            null
        }
    }

    private fun computeApkSha256(): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(applicationInfo.sourceDir).use { inputStream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            sha256Hex(digest.digest())
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun resolveReportSourceModel(modelKey: String?): String? {
        val runtimeSourceModel = reportRuntimeConfig?.sourceModelForReportModel(modelKey)
        return runtimeSourceModel ?: modelKey?.let { "android/$it" }
    }

    private fun resolveReportThreshold(modelKey: String?, className: String): Float? {
        return reportRuntimeConfig?.thresholdForReportClass(modelKey, className)
    }

    override fun onResume() {
        super.onResume()
        surfaceView.onResume()
        if (session == null && pendingSessionStartAfterInstall) {
            pendingSessionStartAfterInstall = false
            ensurePermissionsThenStart()
            return
        }
        if (
            session == null &&
            actionMode == ActionMode.OPEN_SETTINGS &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
            updateStatus("ARCore Depth 대기", "카메라 권한이 허용되었습니다. ARCore Depth를 시작할 수 있습니다.")
        }
        session?.let { currentSession ->
            try {
                currentSession.resume()
                startNavigationServicesIfNeeded()
            } catch (error: CameraNotAvailableException) {
                updateStatus("카메라 사용 불가", error.message ?: "ARCore session resume 실패")
                stopDepthSession(closeSession = true)
            }
        }
    }

    override fun onPause() {
        stopLocationUpdates()
        stopStepTracking()
        feedbackActuator?.close()
        feedbackActuator = null
        surfaceView.onPause()
        session?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        stopDepthSession(closeSession = true)
        if (::metadataLogUploader.isInitialized) {
            metadataLogUploader.close()
        }
        if (::frameCaptureUploader.isInitialized) {
            frameCaptureUploader.close()
        }
        feedbackActuator?.close()
        feedbackActuator = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        routeExecutor.shutdownNow()
        reportUploaderExecutor.shutdownNow()
        closeDetectorAsync()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> handleCameraPermissionResult()
            NAVIGATION_PERMISSION_REQUEST -> handleNavigationPermissionResult()
            VOICE_PERMISSION_REQUEST -> handleVoicePermissionResult()
            else -> Unit
        }
    }

    private fun handleCameraPermissionResult() {
        if (hasCameraPermission()) {
            setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
            startDepthSession()
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                setActionButton("카메라 권한 다시 요청", ActionMode.START, enabled = true)
                updateStatus("카메라 권한 필요", "ARCore Depth를 시작하려면 카메라 권한을 허용해야 합니다.")
            } else {
                setActionButton("앱 설정 열기", ActionMode.OPEN_SETTINGS, enabled = true)
                updateStatus("카메라 권한 차단됨", "Android 앱 설정에서 카메라 권한을 허용한 뒤 다시 시작하세요.")
            }
        }
    }

    private fun handleNavigationPermissionResult() {
        if (hasLocationPermission()) {
            if (isRouteActive || navigationPermissionsRequestedForRoute || navigationPermissionsRequestedForReport) {
                startLocationUpdatesIfAllowed(forceRestart = true)
            }
        } else if (!hasLocationPermission()) {
            updateNavigationStatus("navigation=gps_permission_missing")
        }
        if (hasActivityRecognitionPermission()) {
            if (isRouteActive || navigationPermissionsRequestedForRoute) {
                startStepTrackingIfAllowed()
            }
        } else if (isRouteActive || navigationPermissionsRequestedForRoute) {
            updateNavigationStatus("navigation=activity_recognition_permission_missing step_fallback_wait")
        }
    }

    private fun handleVoicePermissionResult() {
        if (hasRecordAudioPermission()) {
            startVoiceReportRecognition()
        } else {
            updateNavigationStatus("voice=record_audio_permission_missing")
            speakNavigation("음성 신고를 사용하려면 마이크 권한이 필요합니다.")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        backgroundRenderer.createOnGlThread()
        cameraTextureId = createExternalCameraTexture()
        bindCameraTextureIfReady()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        updateDisplayGeometryIfReady()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return
        bindCameraTextureIfReady()
        if (!cameraTextureBound) return

        val frame = try {
            currentSession.update()
        } catch (error: CameraNotAvailableException) {
            runOnUiThread { updateStatus("카메라 사용 불가", error.message ?: "ARCore frame update 실패") }
            return
        } catch (error: RuntimeException) {
            runOnUiThread { updateStatus("ARCore frame 대기", error.message ?: "frame update 준비 중") }
            return
        }
        if (frame.hasDisplayGeometryChanged()) {
            updateDisplayGeometryIfReady()
        }

        val provider = frameProvider ?: return
        backgroundRenderer.draw(frame, cameraTextureId)
        val timestampMs = frame.timestamp / 1_000_000L
        val nowMs = System.currentTimeMillis()
        val detectionSnapshot = latestDetectionSnapshot
        val overlaySelection = selectOverlayDetections(detectionSnapshot, nowMs, timestampMs)
        val overlaySnapshot = overlaySelection.mappingSnapshot
        val overlayDetections = overlaySelection.detections
        var overlayDebugState = overlaySelection.debugState
        val depthDetections = detectionSnapshot.freshDetections(
            nowMs = nowMs,
            currentFrameTimestampMs = timestampMs,
            maxSourceAgeMs = MAX_DEPTH_DETECTION_SOURCE_AGE_MS,
            maxFrameDeltaMs = MAX_DEPTH_DETECTION_FRAME_DELTA_MS,
        )
        scheduleDetectionIfDue(provider, frame, timestampMs, nowMs)
        val snapshot = provider.acquireDepthBundle(frame).toSnapshotAndClose()
        val depthWidth = snapshot.rawDepth?.width ?: snapshot.fullDepth?.width
        val depthHeight = snapshot.rawDepth?.height ?: snapshot.fullDepth?.height
        val depthMapper = createDepthMapper(frame, detectionSnapshot, depthWidth, depthHeight)
        val depthInputDetections = if (depthMapper == null) emptyList() else depthDetections
        val outputs = objectDepthPipeline.process(
            snapshot = snapshot,
            frameId = frame.timestamp,
            timestampMs = timestampMs,
            detections = depthInputDetections,
            mapper = depthMapper,
            motionContext = buildDepthMotionContext(SystemClock.elapsedRealtime()),
        )
        val bestOutput = AndroidRiskSelectionPolicy.selectBestOutput(outputs)
        val reportOutput = AndroidRiskSelectionPolicy.selectReportOutput(outputs)
        if (nowMs - lastOverlayUpdateMs >= OVERLAY_UPDATE_INTERVAL_MS || overlayDetections.isEmpty()) {
            lastOverlayUpdateMs = nowMs
            val overlayBoxes = buildDebugOverlayBoxes(frame, overlayDetections, bestOutput, overlaySnapshot)
            val stabilizedOverlay = tactileOverlayStabilizer.update(overlayBoxes, nowMs)
            overlayDebugState = overlayDebugState.withStabilizer(stabilizedOverlay)
            runOnUiThread {
                debugBboxOverlay.updateMapped(stabilizedOverlay.boxes)
            }
        }
        val captureLogEntry = buildCaptureLogEntry(
            frameTimestampMs = timestampMs,
            nowMs = nowMs,
            detectionSnapshot = detectionSnapshot,
            overlayDebugState = overlayDebugState,
            detectionsUsedForDepth = depthInputDetections,
            snapshot = snapshot,
            bestOutput = bestOutput,
            depthTransformPath = if (depthMapper == null) "identity" else "arcore_image_to_texture_normalized",
            depthFallbackReason = when {
                depthMapper == null -> "arcore_depth_mapper_unavailable"
                depthDetections.isNotEmpty() && depthInputDetections.isEmpty() -> "depth_input_suppressed"
                else -> null
            },
        )
        captureLog.append(captureLogEntry)
        metadataLogUploader.enqueue(captureLogEntry)
        val deviceGateState = buildDeviceGateState(
            bestOutput = bestOutput,
            staleReason = captureLogEntry.staleReason,
        )
        latestDeviceGateAllowsSpeech = deviceGateState.actuatorsAllowed
        latestReportCandidateStatus = prepareReportCandidate(
            reportOutput = reportOutput,
            gateState = deviceGateState,
            reportImage = detectionSnapshot.reportImage,
            nowMs = nowMs,
            capturedAtMs = detectionSnapshot.capturedAtMs ?: nowMs,
        )
        latestExplicitReportOutput = reportOutput
        latestExplicitReportImage = detectionSnapshot.reportImage
        latestExplicitReportGateState = deviceGateState
        latestExplicitReportCapturedAtMs = detectionSnapshot.capturedAtMs ?: nowMs
        feedbackPolicy.evaluate(
            candidate = bestOutput?.toFeedbackCandidate(stale = captureLogEntry.staleReason != null),
            deviceGateAllowsAlerts = deviceGateState.alertsAllowed,
            nowMs = nowMs,
        )?.let { action ->
            emitFeedbackAction(action)
        }
        if (nowMs - lastUiUpdateMs < UI_UPDATE_INTERVAL_MS) return
        lastUiUpdateMs = nowMs

        runOnUiThread {
            val gateText = deviceGateState.statusText()
            if (bestOutput == null) {
                updateStatus(
                    status = if (depthSupported) "카메라/Depth 수신 중" else "Depth 미지원 fallback 대기",
                    detail = "${snapshot.statusTextForNoDetection()}\n" +
                        "${detectionSnapshot.debugStatusText(nowMs, timestampMs, MAX_OVERLAY_DETECTION_SOURCE_AGE_MS, MAX_OVERLAY_DETECTION_FRAME_DELTA_MS)}\n" +
                        "$gateText · ${feedbackActuatorStatusText()} · $latestReportCandidateStatus · steps=$latestStepCount stepLength=${meters(stepLengthEstimator.stepLengthM)}\n" +
                        "$detectorStatusText\n" +
                        "${metadataLogUploader.statusText()}\n" +
                        captureLog.summaryText(),
                )
            } else {
                updateStatus(
                    status = "객체별 depth 수신 중",
                    detail = "${bestOutput.debugSummaryText()}\n" +
                        "${detectionSnapshot.debugStatusText(nowMs, timestampMs, MAX_OVERLAY_DETECTION_SOURCE_AGE_MS, MAX_OVERLAY_DETECTION_FRAME_DELTA_MS)}\n" +
                        "${bestOutput.debugGeometryText()}\n" +
                        "$gateText · ${feedbackActuatorStatusText()} · $latestReportCandidateStatus · steps=$latestStepCount stepLength=${meters(stepLengthEstimator.stepLengthM)}\n" +
                        "$detectorStatusText\n" +
                        "${metadataLogUploader.statusText()}\n" +
                        captureLog.summaryText(),
                )
            }
            updateDebugUploadButton()
        }
    }

    private fun buildContentView(): FrameLayout {
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }

        statusText = TextView(this).apply {
            textSize = 20f
            setTextColor(0xffffffff.toInt())
            contentDescription = "WalkSafe 상태"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        detailText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xffd7e0ff.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        navigationStatusText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xffd7ffd9.toInt())
            text = "navigation=destination_none hazard_only"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        loginUserIdInput = EditText(this).apply {
            hint = "로그인 user id"
            textSize = 12f
            setSingleLine(true)
            setText(reporterUserId.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        loginSaveButton = Button(this).apply {
            setOnClickListener { persistReporterUserFromInput() }
        }
        updateLoginButtonText()
        actionButton = Button(this).apply {
            text = "ARCore Depth 시작"
            setOnClickListener {
                when (actionMode) {
                    ActionMode.START -> ensurePermissionsThenStart()
                    ActionMode.OPEN_SETTINGS -> openAppSettings()
                }
            }
        }
        debugUploadButton = Button(this).apply {
            setOnClickListener {
                metadataLogUploader.setEnabled(!metadataLogUploader.isEnabled())
                updateDebugUploadButton()
            }
        }
        updateDebugUploadButton()
        debugFrameCaptureButton = Button(this).apply {
            text = "이미지 1장 캡쳐"
            setOnClickListener {
                frameCaptureRequested.set(true)
                updateFrameCaptureButton()
            }
        }
        updateFrameCaptureButton()
        explicitReportButton = Button(this).apply {
            text = "신고 요청"
            setOnClickListener { requestExplicitReport() }
        }
        voiceReportButton = Button(this).apply {
            text = "음성 신고"
            setOnClickListener { ensureVoicePermissionThenListen() }
        }
        debugBboxOverlay = DebugBboxOverlayView(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        backendUrlInput = EditText(this).apply {
            setText(DEFAULT_BACKEND_BASE_URL)
            hint = "Backend URL"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        destinationQueryInput = EditText(this).apply {
            hint = "목적지 검색"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        destinationSearchButton = Button(this).apply {
            text = "목적지 검색"
            setOnClickListener {
                performDestinationSearch(reset = true)
            }
        }
        destinationCancelButton = Button(this).apply {
            text = "검색 취소"
            isEnabled = false
            setOnClickListener {
                cancelDestinationSearch()
            }
        }
        destinationSearchResultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        destinationMoreButton = Button(this).apply {
            text = "결과 더보기"
            isEnabled = false
            visibility = View.GONE
            setOnClickListener {
                performDestinationSearch(reset = false)
            }
        }
        destinationLatInput = EditText(this).apply {
            hint = "목적지 위도"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        destinationLngInput = EditText(this).apply {
            hint = "목적지 경도"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        routeButton = Button(this).apply {
            text = "경로 시작"
            setOnClickListener { onRouteButtonClicked() }
        }
        destinationResetButton = Button(this).apply {
            text = "경로 초기화"
            setOnClickListener { resetRouteState() }
        }
        progressBeepToggleButton = Button(this).apply {
            setOnClickListener {
                progressBeepEnabled = !progressBeepEnabled
                persistProgressBeepPrefs()
                updateProgressBeepButtons()
            }
        }
        progressBeepVolumeButton = Button(this).apply {
            setOnClickListener {
                progressBeepVolumePercent = nextProgressBeepVolume(progressBeepVolumePercent)
                persistProgressBeepPrefs()
                updateProgressBeepButtons()
            }
        }
        updateProgressBeepButtons()

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(32, 48, 32, 32)
            setBackgroundColor(0x66000000)
            addView(statusText)
            addView(detailText)
            addView(navigationStatusText)
            addView(loginUserIdInput)
            addView(loginSaveButton)
            addView(actionButton)
            addView(debugUploadButton)
            addView(debugFrameCaptureButton)
            addView(explicitReportButton)
            addView(voiceReportButton)
            addView(backendUrlInput)
            addView(destinationQueryInput)
            addView(destinationSearchButton)
            addView(destinationCancelButton)
            addView(destinationSearchResultsContainer)
            addView(destinationMoreButton)
            addView(destinationLatInput)
            addView(destinationLngInput)
            addView(routeButton)
            addView(destinationResetButton)
            addView(progressBeepToggleButton)
            addView(progressBeepVolumeButton)
        }

        return FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(debugBboxOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                overlay,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
            )
        }
    }

    private fun ensurePermissionsThenStart() {
        if (!requireReporterUserId("login_required_depth_start")) return
        val missing = missingCameraPermissions()
        if (missing.isEmpty()) {
            startDepthSession()
            return
        }
        requestPermissions(missing.toTypedArray(), CAMERA_PERMISSION_REQUEST)
    }

    private fun currentReporterUserId(): String? {
        val inputUserId = if (::loginUserIdInput.isInitialized) {
            loginUserIdInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
        } else {
            null
        }
        val current = inputUserId ?: reporterUserId?.trim()?.takeIf { it.isNotBlank() }
        reporterUserId = current
        return current
    }

    private fun requireReporterUserId(reason: String): Boolean {
        if (currentReporterUserId() != null) return true
        updateNavigationStatus("login=required reason=$reason")
        speakNavigation("로그인이 필요합니다.")
        return false
    }

    private fun ensureNavigationPermissions(requireActivityRecognition: Boolean) {
        val missing = mutableListOf<String>()
        if (!hasLocationPermission()) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && requireActivityRecognition && !hasActivityRecognitionPermission()) {
            missing += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (missing.isEmpty()) return
        requestPermissions(missing.toTypedArray(), NAVIGATION_PERMISSION_REQUEST)
    }

    private fun ensureNavigationPermissionForRouteOrStep(): Boolean {
        navigationPermissionsRequestedForRoute = true
        if (isNavigationPermissionReady()) return true
        ensureNavigationPermissions(requireActivityRecognition = true)
        return false
    }

    private fun ensureNavigationPermissionForReport(): Boolean {
        navigationPermissionsRequestedForReport = true
        if (hasLocationPermission()) return true
        ensureNavigationPermissions(requireActivityRecognition = false)
        return false
    }

    private fun startNavigationServicesIfNeeded() {
        if (isRouteActive || navigationPermissionsRequestedForRoute || navigationPermissionsRequestedForReport) {
            startLocationUpdatesIfAllowed()
            if (isRouteActive || navigationPermissionsRequestedForRoute) {
                startStepTrackingIfAllowed()
            }
        }
    }

    private fun missingCameraPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA.takeIf { !hasCameraPermission() },
        )
        return permissions.filterNotNull()
    }

    private fun isNavigationPermissionReady(): Boolean {
        return hasLocationPermission() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasActivityRecognitionPermission())
    }

    private fun hasCameraPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecordAudioPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startDepthSession() {
        if (session != null) {
            updateStatus("ARCore Depth 실행 중", "이미 session이 시작돼 있습니다.")
            return
        }
        if (!ensureArCoreSupportedOrExplain()) {
            arCoreSupported = false
            return
        }
        arCoreSupported = true

        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    pendingSessionStartAfterInstall = true
                    setActionButton("ARCore 설치 후 돌아오기", ActionMode.START, enabled = true)
                    updateStatus("ARCore 설치 필요", "Google Play Services for AR 설치 후 다시 시작합니다.")
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val newSession = Session(this)
            val provider = ArCoreFrameProvider(newSession)
            depthSupported = provider.configureDepthMode()
            loadDetectorAfterDepthGate()
            session = newSession
            frameProvider = provider
            bindCameraTextureIfReady()
            updateDisplayGeometryIfReady()
            newSession.resume()
            startNavigationServicesIfNeeded()
            setActionButton("ARCore Depth 실행 중", ActionMode.START, enabled = false)
            updateStatus(
                status = if (depthSupported) "ARCore Depth 시작됨" else "ARCore Depth 미지원",
                detail = if (depthSupported) {
                    if (detectorAvailable) {
                        "TFLite detector 결과를 객체별 depth pipeline에 연결하고 Raw Depth 부족 시 Full Depth로 fallback합니다."
                    } else {
                        "TFLite detector asset이 없어 객체 안내는 대기하고 Raw/Full Depth만 확인합니다."
                    }
                } else {
                    "이 기기는 ARCore DepthMode.AUTOMATIC을 지원하지 않아 metric depth가 제한됩니다."
                },
            )
        } catch (error: Exception) {
            updateStatus("ARCore 시작 실패", error.message ?: error::class.java.simpleName)
            stopDepthSession(closeSession = true)
        }
    }

    private fun stopDepthSession(closeSession: Boolean = false) {
        session?.pause()
        if (closeSession) {
            session?.close()
        }
        session = null
        frameProvider = null
        cameraTextureBound = false
        arCoreSupported = false
        depthSupported = false
        latestDeviceGateAllowsSpeech = false
        latestReportCandidateStatus = "reportCandidate=blocked"
        detectorGeneration += 1
        latestDetectionSnapshot = DetectionSnapshot.empty()
        latestTactileOverlaySnapshot = DetectionSnapshot.empty()
        lastNonEmptyOverlaySnapshot = DetectionSnapshot.empty()
        tactileOverlayStabilizer.clear()
        lastDetectionRunMs = 0L
        captureLog.clear()
        if (::metadataLogUploader.isInitialized) {
            metadataLogUploader.setEnabled(false)
        }
        if (::debugBboxOverlay.isInitialized) {
            runOnUiThread { debugBboxOverlay.clear() }
        }
        if (::actionButton.isInitialized) setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
        if (::debugUploadButton.isInitialized) updateDebugUploadButton()
        stopLocationUpdates()
        stopStepTracking()
    }

    private fun bindCameraTextureIfReady() {
        val currentSession = session ?: return
        if (cameraTextureId == 0 || cameraTextureBound) return
        currentSession.setCameraTextureNames(intArrayOf(cameraTextureId))
        cameraTextureBound = true
    }

    private fun updateDisplayGeometryIfReady() {
        val currentSession = session ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        currentSession.setDisplayGeometry(displayRotation(), surfaceWidth, surfaceHeight)
    }

    private fun displayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun ensureArCoreSupportedOrExplain(): Boolean {
        return when (ArCoreApk.getInstance().checkAvailability(this)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            -> true
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                setActionButton("ARCore 미지원", ActionMode.START, enabled = false)
                updateStatus("ARCore 미지원 기기", "이 기기는 ARCore를 지원하지 않아 native depth APK를 실행할 수 없습니다.")
                false
            }
            ArCoreApk.Availability.UNKNOWN_CHECKING -> {
                setActionButton("ARCore 다시 확인", ActionMode.START, enabled = true)
                updateStatus("ARCore 지원 확인 중", "잠시 후 다시 시도하세요.")
                false
            }
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            -> {
                setActionButton("ARCore 다시 확인", ActionMode.START, enabled = true)
                updateStatus("ARCore 지원 확인 실패", "네트워크 또는 Google Play Services for AR 상태를 확인한 뒤 다시 시도하세요.")
                false
            }
        }
    }

    private fun scheduleDetectionIfDue(
        provider: ArCoreFrameProvider,
        frame: Frame,
        timestampMs: Long,
        nowMs: Long,
    ) {
        if (!detectorAvailable) return
        if (nowMs - lastDetectionRunMs < DETECTION_INTERVAL_MS) return
        if (!detectionInFlight.compareAndSet(false, true)) return
        val cameraImage = provider.acquireCameraImageOrNull(frame)
        if (cameraImage == null) {
            lastDetectionRunMs = nowMs
            detectionInFlight.set(false)
            return
        }
        lastDetectionRunMs = nowMs
        val generation = detectorGeneration
        val imageWidth = cameraImage.width
        val imageHeight = cameraImage.height
        val capturedAtMs = nowMs
        val shouldCaptureFrame = frameCaptureRequested.getAndSet(false)
        try {
            detectorExecutor.execute {
                val reportJpeg = encodeReportFrameJpeg(cameraImage)
                val captureJpeg = if (shouldCaptureFrame) {
                    encodeDebugFrameJpeg(cameraImage) ?: reportJpeg
                } else {
                    null
                }
                val startedAtMs = System.currentTimeMillis()
                var completedAtMs = startedAtMs
                val result = try {
                    cameraImage.use { image ->
                        frameDetector.detect(
                            image,
                            timestampMs = timestampMs,
                        ) { partialResult ->
                            publishDetectionSnapshot(
                                result = partialResult,
                                generation = generation,
                                frameTimestampMs = timestampMs,
                                capturedAtMs = capturedAtMs,
                                startedAtMs = startedAtMs,
                                completedAtMs = System.currentTimeMillis(),
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
                                reportImageJpeg = reportJpeg,
                            )
                        }
                    }
                } catch (_: RuntimeException) {
                    AndroidDetectionResult.empty()
                } finally {
                    completedAtMs = System.currentTimeMillis()
                    detectionInFlight.set(false)
                }
                if (generation == detectorGeneration) {
                    publishDetectionSnapshot(
                        result = result,
                        generation = generation,
                        frameTimestampMs = timestampMs,
                        capturedAtMs = capturedAtMs,
                        startedAtMs = startedAtMs,
                        completedAtMs = completedAtMs,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        reportImageJpeg = reportJpeg,
                    )
                    if (captureJpeg != null) {
                        frameCaptureUploader.upload(
                            imageJpeg = captureJpeg,
                            metadataJson = buildFrameCaptureMetadataJson(
                                frameTimestampMs = timestampMs,
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
                                detectDurationMs = completedAtMs - startedAtMs,
                                result = result,
                                forcedCustom = shouldCaptureFrame,
                            ),
                        )
                    }
                    if (shouldCaptureFrame) {
                        runOnUiThread { updateFrameCaptureButton() }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            cameraImage.close()
            detectionInFlight.set(false)
        }
    }

    private fun encodeDebugFrameJpeg(cameraImage: android.media.Image): ByteArray? {
        return try {
            val argb = frameCapturePreprocessor.decode(cameraImage)
            argb.toScaledJpeg(maxDimension = DEBUG_FRAME_CAPTURE_MAX_DIMENSION, quality = DEBUG_FRAME_CAPTURE_JPEG_QUALITY)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun encodeReportFrameJpeg(cameraImage: android.media.Image): ByteArray? {
        return try {
            val argb = frameCapturePreprocessor.decode(cameraImage)
            argb.toScaledJpeg(
                maxDimension = REPORT_FRAME_CAPTURE_MAX_DIMENSION,
                quality = REPORT_FRAME_CAPTURE_JPEG_QUALITY,
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun publishDetectionSnapshot(
        result: AndroidDetectionResult,
        generation: Int,
        frameTimestampMs: Long,
        capturedAtMs: Long,
        startedAtMs: Long,
        completedAtMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        reportImageJpeg: ByteArray?,
    ) {
        if (generation != detectorGeneration) return
        val snapshot = DetectionSnapshot(
            detections = result.detections,
            frameTimestampMs = frameTimestampMs,
            capturedAtMs = capturedAtMs,
            startedAtMs = startedAtMs,
            completedAtMs = completedAtMs,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            reportImage = reportImageJpeg,
            detectDurationMs = result.timing.totalMs ?: (completedAtMs - startedAtMs),
            detectorTiming = result.timing,
            partial = result.partial,
        )
        latestDetectionSnapshot = snapshot
        if (snapshot.hasTactileDetection()) {
            latestTactileOverlaySnapshot = snapshot.copy(detections = snapshot.tactileDetections())
        }
    }

    private fun selectOverlayDetections(
        detectionSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
    ): OverlayDetectionSelection {
        val overlaySourceAgeLimitMs = if (detectionSnapshot.hasTactileDetection()) {
            MAX_TACTILE_OVERLAY_SOURCE_AGE_MS
        } else {
            MAX_OVERLAY_DETECTION_SOURCE_AGE_MS
        }
        val overlayFrameDeltaLimitMs = if (detectionSnapshot.hasTactileDetection()) {
            MAX_TACTILE_OVERLAY_FRAME_DELTA_MS
        } else {
            MAX_OVERLAY_DETECTION_FRAME_DELTA_MS
        }
        val freshOverlayDetections = detectionSnapshot.freshDetections(
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxSourceAgeMs = overlaySourceAgeLimitMs,
            maxFrameDeltaMs = overlayFrameDeltaLimitMs,
        )
        val latestOverlayStaleReason = detectionSnapshot.staleReason(
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxSourceAgeMs = overlaySourceAgeLimitMs,
            maxFrameDeltaMs = overlayFrameDeltaLimitMs,
        )
        if (freshOverlayDetections.isNotEmpty()) {
            lastNonEmptyOverlaySnapshot = detectionSnapshot
        }

        val tactileOverlaySnapshot = latestTactileOverlaySnapshot
        val heldTactileDetections = tactileOverlaySnapshot.freshDetections(
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxSourceAgeMs = MAX_TACTILE_OVERLAY_HOLD_SOURCE_AGE_MS,
            maxFrameDeltaMs = MAX_TACTILE_OVERLAY_HOLD_FRAME_DELTA_MS,
        ).filter { it.isTactileDetection() }
        val mergedFreshDetections = mergeOverlayDetections(
            currentDetections = freshOverlayDetections,
            heldTactileDetections = heldTactileDetections,
        )
        if (mergedFreshDetections.isNotEmpty()) {
            val mappingSnapshot = when {
                detectionSnapshot.hasImageSize() -> detectionSnapshot
                tactileOverlaySnapshot.hasImageSize() -> tactileOverlaySnapshot
                else -> lastNonEmptyOverlaySnapshot
            }
            return OverlayDetectionSelection(
                detections = mergedFreshDetections,
                mappingSnapshot = mappingSnapshot,
                debugState = buildOverlayDebugState(
                    selection = if (freshOverlayDetections.size == mergedFreshDetections.size) {
                        "fresh_latest"
                    } else {
                        "fresh_plus_held_tactile"
                    },
                    detections = mergedFreshDetections,
                    mappingSnapshot = mappingSnapshot,
                    nowMs = nowMs,
                    currentFrameTimestampMs = currentFrameTimestampMs,
                    staleReason = latestOverlayStaleReason,
                    holdApplied = freshOverlayDetections.size != mergedFreshDetections.size,
                ),
            )
        }

        val overlaySnapshot = lastNonEmptyOverlaySnapshot
        val holdSourceAgeLimitMs = if (overlaySnapshot.hasTactileDetection()) {
            MAX_TACTILE_OVERLAY_HOLD_SOURCE_AGE_MS
        } else {
            MAX_OVERLAY_HOLD_SOURCE_AGE_MS
        }
        val holdFrameDeltaLimitMs = if (overlaySnapshot.hasTactileDetection()) {
            MAX_TACTILE_OVERLAY_HOLD_FRAME_DELTA_MS
        } else {
            MAX_OVERLAY_HOLD_FRAME_DELTA_MS
        }
        val heldOverlayDetections = overlaySnapshot.freshDetections(
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxSourceAgeMs = holdSourceAgeLimitMs,
            maxFrameDeltaMs = holdFrameDeltaLimitMs,
        )
        return OverlayDetectionSelection(
            detections = heldOverlayDetections,
            mappingSnapshot = overlaySnapshot,
            debugState = buildOverlayDebugState(
                selection = if (heldOverlayDetections.isEmpty()) "empty" else "hold_last_non_empty",
                detections = heldOverlayDetections,
                mappingSnapshot = overlaySnapshot,
                nowMs = nowMs,
                currentFrameTimestampMs = currentFrameTimestampMs,
                staleReason = latestOverlayStaleReason ?: "latest_empty",
                holdApplied = heldOverlayDetections.isNotEmpty(),
            ),
        )
    }

    private fun buildOverlayDebugState(
        selection: String,
        detections: List<DetectionCandidate>,
        mappingSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
        staleReason: String?,
        holdApplied: Boolean,
    ): OverlayDebugState {
        return OverlayDebugState(
            selection = selection,
            detectionCount = detections.size,
            tactileDetectionCount = detections.count { it.isTactileDetection() },
            sourceAgeMs = mappingSnapshot.sourceAgeMs(nowMs),
            frameDeltaMs = mappingSnapshot.frameDeltaMs(currentFrameTimestampMs),
            staleReason = staleReason,
            holdApplied = holdApplied && detections.isNotEmpty(),
            snapshotPartial = mappingSnapshot.partial,
        )
    }

    private fun mergeOverlayDetections(
        currentDetections: List<DetectionCandidate>,
        heldTactileDetections: List<DetectionCandidate>,
    ): List<DetectionCandidate> {
        if (heldTactileDetections.isEmpty()) return currentDetections
        if (currentDetections.any { it.isTactileDetection() }) return currentDetections
        return currentDetections + heldTactileDetections
    }

    private fun ArgbImage.toScaledJpeg(maxDimension: Int, quality: Int): ByteArray? {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return null
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val maxSide = maxOf(width, height).coerceAtLeast(1)
        val scale = (maxDimension / maxSide.toFloat()).coerceAtMost(1f)
        val outputBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            ByteArrayOutputStream().use { output ->
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)
                output.toByteArray()
            }
        } finally {
            if (outputBitmap !== bitmap) outputBitmap.recycle()
            bitmap.recycle()
        }
    }

    private fun buildFrameCaptureMetadataJson(
        frameTimestampMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        detectDurationMs: Long,
        result: AndroidDetectionResult,
        forcedCustom: Boolean,
    ): String {
        val detections = result.detections
        val top = detections.maxByOrNull { it.detectionConfidence }
        val json = JSONObject()
            .put("frame_timestamp_ms", frameTimestampMs)
            .put("camera_image_width", imageWidth)
            .put("camera_image_height", imageHeight)
            .put("detect_duration_ms", detectDurationMs)
            .put("detection_count", detections.size)
            .put("capture_policy", "manual_jpeg_no_gps_no_depth_raw_full_detector")
            .put("force_custom_tactile", forcedCustom)
            .put("detector_completed_models", JSONArray(result.timing.completedModels))
            .put("detector_skipped_models", JSONArray(result.timing.skippedModels))
            .put("detector_partial", result.partial)
            .put("detector_loaded_model_key", detectorLoadedModelKey)
            .put("detector_model_fallback_used", detectorModelFallbackUsed)
            .put("detector_model_load_reason", detectorLoadReason)
        result.timing.customInferenceMs?.let { json.put("detector_custom_inference_ms", it) }
        result.timing.cocoInferenceMs?.let { json.put("detector_coco_inference_ms", it) }
        result.timing.modelKey?.let { json.put("detector_model_key", it) }
        result.timing.modelPreprocessMs?.let { json.put("detector_model_preprocess_ms", it) }
        result.timing.modelInferenceMs?.let { json.put("detector_model_inference_ms", it) }
        result.timing.modelParseMs?.let { json.put("detector_model_parse_ms", it) }
        json.put(
            "detections",
            JSONArray(
                detections
                    .sortedByDescending { it.detectionConfidence }
                    .take(MAX_FRAME_CAPTURE_DETECTIONS)
                    .map { detection ->
                        JSONObject()
                            .put("class_name", detection.className)
                            .put("confidence", detection.detectionConfidence)
                            .put("bbox", detection.bboxNorm.toJson())
                    },
            ),
        )
        if (top != null) {
            json.put("top_detection_class_name", top.className)
                .put("top_detection_confidence", top.detectionConfidence)
                .put("top_detection_bbox", top.bboxNorm.toJson())
            resolveReportThreshold(detectorModelKeyForReports, top.className)?.let { threshold ->
                json.put("top_detection_threshold_used", threshold)
            }
        }
        return json.toString()
    }

    private fun RectNorm.toJson(): JSONObject {
        return JSONObject()
            .put("x", x)
            .put("y", y)
            .put("width", width)
            .put("height", height)
    }

    private fun buildDebugOverlayBoxes(
        frame: Frame,
        detections: List<DetectionCandidate>,
        bestOutput: TrackedObjectDepth?,
        detectionSnapshot: DetectionSnapshot,
    ): List<DebugBboxOverlayView.DebugOverlayBox> {
        val imageWidth = detectionSnapshot.imageWidth
        val imageHeight = detectionSnapshot.imageHeight
        if (imageWidth == null || imageHeight == null) {
            return emptyList()
        }
        val mappedDetectionBoxes = detections
            .sortedByDescending { it.detectionConfidence }
            .take(MAX_OVERLAY_DETECTION_BOXES)
            .mapNotNull { detection ->
                val rect = detection.bboxNorm.toViewRect(frame, imageWidth, imageHeight) ?: return@mapNotNull null
                DebugBboxOverlayView.DebugOverlayBox(
                    rectPx = rect,
                    label = "${detection.className} ${percent(detection.detectionConfidence)}",
                    best = false,
                    className = detection.className,
                )
            }
        val bestBox = bestOutput?.let { output ->
            output.bboxNorm.toViewRect(frame, imageWidth, imageHeight)?.let { rect ->
                DebugBboxOverlayView.DebugOverlayBox(
                    rectPx = rect,
                    label = buildString {
                        append(output.className)
                        append(" ")
                        append(percent(output.detectionConfidence))
                        append(" · ")
                        append(output.source.name)
                        output.riskDistanceM?.let { distance -> append(" · ${meters(distance)}") }
                        append(" · samples ")
                        append(output.validSampleCount)
                    },
                    best = true,
                    className = output.className,
                )
            }
        }
        val boxes = if (bestBox == null) mappedDetectionBoxes else listOf(bestBox) + mappedDetectionBoxes
        return boxes
    }

    private fun createDepthMapper(
        frame: Frame,
        detectionSnapshot: DetectionSnapshot,
        depthWidth: Int?,
        depthHeight: Int?,
    ): CoordinateMapper? {
        val imageWidth = detectionSnapshot.imageWidth ?: return null
        val imageHeight = detectionSnapshot.imageHeight ?: return null
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (depthWidth == null || depthHeight == null || depthWidth <= 0 || depthHeight <= 0) return null
        return FrameImageToTextureCoordinateMapper(
            frame = frame,
            imageSize = ImageSize(imageWidth, imageHeight),
            depthSize = ImageSize(depthWidth, depthHeight),
        )
    }

    private fun RectNorm.toViewRect(frame: Frame, imageWidth: Int, imageHeight: Int): RectF? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val left = x.coerceIn(0f, 1f) * imageWidth
        val top = y.coerceIn(0f, 1f) * imageHeight
        val right = (x + width).coerceIn(0f, 1f) * imageWidth
        val bottom = (y + height).coerceIn(0f, 1f) * imageHeight
        val imageCorners = floatArrayOf(
            left, top,
            right, top,
            right, bottom,
            left, bottom,
        )
        val viewCorners = FloatArray(imageCorners.size)
        return try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                imageCorners,
                Coordinates2d.VIEW,
                viewCorners,
            )
            val xs = floatArrayOf(viewCorners[0], viewCorners[2], viewCorners[4], viewCorners[6])
            val ys = floatArrayOf(viewCorners[1], viewCorners[3], viewCorners[5], viewCorners[7])
            val maxWidth = surfaceWidth.takeIf { it > 0 }?.toFloat() ?: return null
            val maxHeight = surfaceHeight.takeIf { it > 0 }?.toFloat() ?: return null
            val mappedLeft = xs.minOrNull()?.coerceIn(0f, maxWidth) ?: return null
            val mappedTop = ys.minOrNull()?.coerceIn(0f, maxHeight) ?: return null
            val mappedRight = xs.maxOrNull()?.coerceIn(mappedLeft, maxWidth) ?: return null
            val mappedBottom = ys.maxOrNull()?.coerceIn(mappedTop, maxHeight) ?: return null
            RectF(mappedLeft, mappedTop, mappedRight, mappedBottom)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun setActionButton(text: String, mode: ActionMode, enabled: Boolean) {
        actionMode = mode
        if (::actionButton.isInitialized) {
            actionButton.text = text
            actionButton.isEnabled = enabled
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun loadDetectorAfterDepthGate() {
        if (detectorLoadAttempted) return
        detectorLoadAttempted = true
        val runtimeConfig = reportRuntimeConfig ?: loadReportRuntimeConfig().also { reportRuntimeConfig = it }
        val detectorLoad = runtimeConfig
            ?.let { TfliteAndroidFrameDetector.createWithStatus(this, it) }
            ?: TfliteAndroidFrameDetector.createWithStatus(this)
        detectorConfigLoaded = detectorLoad.configLoaded
        detectorAvailable = detectorLoad.detectorAvailable
        detectorLoadedModelKey = detectorLoad.modelKey
        detectorModelFallbackUsed = detectorLoad.fallbackUsed
        detectorLoadReason = detectorLoad.reason
        detectorModelKeyForReports = detectorLoad.modelKey?.toReportModelKey()
        detectorStatusText = "detector=${detectorLoad.reason} model=${detectorLoad.modelKey ?: "-"} fallback=${detectorLoad.fallbackUsed}"
        detectorLoad.detector?.let { detector -> frameDetector = detector }
    }

    private fun emitFeedbackAction(action: FeedbackAction) {
        announceForTalkBack(message = action.message, isRisk = true)
        val actuator = ensureFeedbackActuator()
        if (isScreenReaderActive()) {
            actuator.vibrateRiskOnly(action)
        } else {
            actuator.emit(action)
        }
    }

    private fun speakNavigation(message: String) {
        announceForTalkBack(message = message, isRisk = false)
        if (!isScreenReaderActive()) {
            ensureFeedbackActuator().speakNavigation(message)
        }
    }

    private fun isScreenReaderActive(): Boolean {
        val accessibilityManager = getSystemService(AccessibilityManager::class.java) ?: return false
        return accessibilityManager.isEnabled && accessibilityManager.isTouchExplorationEnabled
    }

    private fun announceForTalkBack(message: String, isRisk: Boolean) {
        if (message.isBlank()) return
        val nowMs = System.currentTimeMillis()
        if (isRisk) {
            if (message == lastRiskAnnouncement && nowMs - lastRiskAnnouncementMs < 1_000L) return
            lastRiskAnnouncement = message
            lastRiskAnnouncementMs = nowMs
            if (::statusText.isInitialized) {
                runOnUiThread {
                    statusText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
                    statusText.announceForAccessibility(message)
                }
            }
            return
        }
        if (message == lastNavigationAnnouncement && nowMs - lastNavigationAnnouncementMs < 2_000L) return
        lastNavigationAnnouncement = message
        lastNavigationAnnouncementMs = nowMs
        if (::statusText.isInitialized) {
            runOnUiThread {
                statusText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                statusText.announceForAccessibility(message)
            }
        }
    }

    private fun ensureFeedbackActuator(): AndroidFeedbackActuator {
        val current = feedbackActuator
        if (current != null) return current
        return AndroidFeedbackActuator(this).also { feedbackActuator = it }
    }

    private fun feedbackActuatorStatusText(): String {
        return feedbackActuator?.statusText() ?: "tts=off_until_device_gate"
    }

    private fun buildDeviceGateState(bestOutput: TrackedObjectDepth?, staleReason: String?): DeviceGateState {
        return DeviceGateState(
            cameraPermissionGranted = hasCameraPermission(),
            arCoreSupported = arCoreSupported,
            depthSupported = depthSupported,
            tfliteConfigLoaded = detectorConfigLoaded,
            detectorAvailable = detectorAvailable,
            arSessionRunning = session != null,
            freshDepthObject = bestOutput != null &&
                bestOutput.source.metric &&
                bestOutput.confidence.finalScore >= FEEDBACK_MIN_DEPTH_CONFIDENCE,
            staleReason = staleReason,
        )
    }

    private fun DeviceGateState.statusText(): String {
        val state = if (actuatorsAllowed) "deviceGate=READY" else "deviceGate=WAIT:${blockedReason()}"
        val alertState = if (alertsAllowed) "alertGate=PASS" else "alertGate=WAIT:${blockedReason()}"
        return "$state $alertState startupReady=$startupReady depth=$depthSupported detector=$detectorAvailable"
    }

    private fun prepareReportCandidate(
        reportOutput: TrackedObjectDepth?,
        gateState: DeviceGateState,
        reportImage: ByteArray?,
        nowMs: Long,
        capturedAtMs: Long,
        trigger: String = "auto",
        explicitRequest: Boolean = false,
    ): String {
        val reporterId = currentReporterUserId() ?: return "reportCandidate=blocked:login_required"
        val output = reportOutput ?: return "reportCandidate=blocked:no_depth_object"
        if (output.className != AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK) {
            return "reportCandidate=blocked:not_reportable_class"
        }
        val modelKey = detectorModelKeyForReports ?: return "reportCandidate=blocked:model_unavailable"
        if (modelKey !in AndroidReportCandidatePolicy.ALLOWED_MODEL_KEYS) {
            return "reportCandidate=blocked:model_not_allowed"
        }
        val sourceModel = resolveReportSourceModel(modelKey) ?: return "reportCandidate=blocked:source_model_missing"
        val threshold = resolveReportThreshold(modelKey, output.className) ?: return "reportCandidate=blocked:threshold_missing"
        if (!gateState.reportCandidatesAllowed) {
            return "reportCandidate=blocked:device_gate"
        }
        if (latestTrustedLocation == null) {
            ensureNavigationPermissionForReport()
            startNavigationServicesIfNeeded()
            if (explicitRequest) {
                speakNavigation("위치 정보가 필요합니다.")
            }
            return "reportCandidate=blocked:gps_missing"
        }
        val candidate = reportCandidatePolicy.prepare(
            AndroidReportCandidateInput(
                depth = output,
                location = latestTrustedLocation,
                modelKey = modelKey,
                sourceModel = sourceModel,
                runtimeMode = "android",
                modelConfigSha256 = reportModelConfigSha256,
                modelVersion = BuildConfig.VERSION_NAME,
                threshold = threshold,
                capturedAtMs = capturedAtMs,
                trigger = trigger,
                reporterUserId = reporterId,
                traceId = "${output.frameId}-${output.trackId}",
                deviceGateAllowsReports = gateState.reportCandidatesAllowed,
                depthSampleCount = output.validSampleCount,
                depthValidSampleRatio = output.validSampleRatio,
                detectionAgeMs = (nowMs - capturedAtMs).coerceAtLeast(0L),
                coordinateGateStatus = if (latestTrustedLocation == null) "gps_missing" else "pass",
                heading = latestHeadingDeg,
                apkSha256 = reportApkSha256,
                fallbackUsed = detectorModelFallbackUsed,
                loadedModelKey = detectorLoadedModelKey,
                modelLoadReason = detectorLoadReason,
            ),
        )
            return if (candidate == null) {
                "reportCandidate=blocked"
            } else {
                processReportCandidate(candidate, output, reportImage, nowMs, explicitRequest = explicitRequest)
            }
        }

    private fun processReportCandidate(
        candidate: AndroidReportCandidate,
        output: TrackedObjectDepth,
        reportImage: ByteArray?,
        nowMs: Long,
        explicitRequest: Boolean = false,
    ): String {
        val key = reportCooldownKey(output)
        val imageJpeg = reportImage ?: return "reportCandidate=blocked:no_report_image key=$key"
        val state = reportUploadStates.compute(key) { _, current ->
            current ?: ReportUploadState()
        } ?: ReportUploadState()
        state.lastAttemptCount += 1
        val cooldownRemainingMs = REPORT_UPLOAD_COOLDOWN_MS - (nowMs - state.lastUploadedAtMs)
        if (state.inFlight) {
            if (explicitRequest) speakNavigation("이미 신고가 된 상태입니다.")
            return "reportCandidate=duplicate_inflight key=$key count=${state.lastAttemptCount}"
        }
        if (state.lastUploadedAtMs > 0 && cooldownRemainingMs > 0) {
            if (explicitRequest) speakNavigation("이미 신고가 된 상태입니다.")
            return "reportCandidate=cooldown_${key} remainingMs=$cooldownRemainingMs count=${state.lastAttemptCount}"
        }
        state.inFlight = true
        val preparedStatus = "reportCandidate=prepared key=$key count=${state.lastAttemptCount}"
        runOnUiThread {
            latestReportCandidateStatus = preparedStatus
        }
        val metadataJson = candidate.metadata.toString()
        reportUploaderExecutor.execute {
            try {
                runOnUiThread {
                    latestReportCandidateStatus = "reportCandidate=uploading key=$key count=${state.lastAttemptCount}"
                }
                val response = reportUploader.upload(
                    baseUrl = backendUrlInput.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_BACKEND_BASE_URL },
                    metadataJson = metadataJson,
                    imageJpeg = imageJpeg,
                )
                val duplicateReportIds = response.duplicateReportIds()
                state.lastUploadedAtMs = System.currentTimeMillis()
                runOnUiThread {
                    latestReportCandidateStatus = if (duplicateReportIds.isEmpty()) {
                        "reportCandidate=succeeded key=$key count=${state.lastAttemptCount}"
                    } else {
                        "reportCandidate=succeeded_duplicate key=$key duplicateIds=${duplicateReportIds.joinToString("|")} count=${state.lastAttemptCount}"
                    }
                    if (explicitRequest) {
                        speakNavigation(if (duplicateReportIds.isEmpty()) "신고를 접수했습니다." else "이미 신고가 된 상태입니다.")
                    }
                }
            } catch (error: ReportUploadHttpException) {
                val httpStatusCode = error.error.statusCode
                val httpErrorBody = error.error.errorBody.toStatusToken(maxLength = 96)
                runOnUiThread {
                    latestReportCandidateStatus = "reportCandidate=failed_http status=$httpStatusCode body=$httpErrorBody key=$key count=${state.lastAttemptCount}"
                    if (explicitRequest) speakNavigation("신고 전송에 실패했습니다.")
                }
            } catch (_: RuntimeException) {
                runOnUiThread {
                    latestReportCandidateStatus = "reportCandidate=failed key=$key count=${state.lastAttemptCount}"
                    if (explicitRequest) speakNavigation("신고 전송에 실패했습니다.")
                }
            } finally {
                state.inFlight = false
            }
        }
        return preparedStatus
    }

    private fun requestExplicitReport() {
        if (!requireReporterUserId("login_required_explicit_report")) return
        val gateState = latestExplicitReportGateState
        val output = latestExplicitReportOutput
        if (latestTrustedLocation == null) {
            ensureNavigationPermissionForReport()
            startNavigationServicesIfNeeded()
            latestReportCandidateStatus = "reportCandidate=blocked:gps_missing trigger=voice"
            updateNavigationStatus(latestReportCandidateStatus)
            speakNavigation("위치 정보가 필요합니다.")
            return
        }
        val status = prepareReportCandidate(
            reportOutput = output,
            gateState = gateState ?: buildDeviceGateState(bestOutput = output, staleReason = null),
            reportImage = latestExplicitReportImage,
            nowMs = System.currentTimeMillis(),
            capturedAtMs = latestExplicitReportCapturedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            trigger = "voice",
            explicitRequest = true,
        )
        latestReportCandidateStatus = status
        updateNavigationStatus(status)
        if (status == "reportCandidate=blocked:no_depth_object" || status.contains("blocked:not_reportable_class")) {
            speakNavigation("신고할 손상 점자블록이 없습니다.")
        }
    }

    private fun ensureVoicePermissionThenListen() {
        if (!requireReporterUserId("login_required_voice_report")) return
        if (!hasRecordAudioPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), VOICE_PERMISSION_REQUEST)
            return
        }
        startVoiceReportRecognition()
    }

    private fun startVoiceReportRecognition() {
        if (voiceRecognitionActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNavigationStatus("voice=recognizer_unavailable")
            speakNavigation("음성 인식을 사용할 수 없습니다.")
            return
        }
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(buildVoiceReportRecognitionListener())
            speechRecognizer = it
        }
        voiceRecognitionActive = true
        updateVoiceReportButton(active = true)
        updateNavigationStatus("voice=listening report_command")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer.startListening(intent)
    }

    private fun buildVoiceReportRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onError(error: Int) {
                voiceRecognitionActive = false
                updateVoiceReportButton(active = false)
                updateNavigationStatus("voice=recognition_failed code=$error")
                speakNavigation("신고 요청을 인식하지 못했습니다.")
            }

            override fun onResults(results: Bundle?) {
                voiceRecognitionActive = false
                updateVoiceReportButton(active = false)
                val phrases = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                handleVoiceReportPhrases(phrases)
            }
        }
    }

    private fun handleVoiceReportPhrases(phrases: List<String>) {
        val recognized = phrases.firstOrNull { it.isNotBlank() }.orEmpty()
        if (phrases.any { isExplicitReportPhrase(it) }) {
            updateNavigationStatus("voice=report_command_recognized")
            requestExplicitReport()
            return
        }
        updateNavigationStatus("voice=report_command_unmatched phrase=${recognized.toStatusToken(maxLength = 48)}")
        speakNavigation("신고 요청을 인식하지 못했습니다.")
    }

    private fun isExplicitReportPhrase(text: String): Boolean {
        val normalized = text.lowercase(Locale.KOREAN).replace("\\s+".toRegex(), "")
        return normalized.contains("신고") && (
            normalized.contains("해줘") ||
                normalized.contains("해주세요") ||
                normalized.contains("해") ||
                normalized.contains("접수")
            )
    }

    private fun updateVoiceReportButton(active: Boolean = voiceRecognitionActive) {
        if (!::voiceReportButton.isInitialized) return
        voiceReportButton.text = if (active) "음성 듣는 중" else "음성 신고"
        voiceReportButton.isEnabled = !active
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesIfAllowed(forceRestart: Boolean = false) {
        if (!::fusedLocationClient.isInitialized || !hasLocationPermission()) {
            updateNavigationStatus("navigation=gps_permission_missing hazard_only")
            return
        }
        if (forceRestart) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun reportCooldownKey(output: TrackedObjectDepth): String {
        return "${output.trackId}:${output.className}:${output.source}"
    }

    private fun startStepTrackingIfAllowed() {
        if (!::stepTracker.isInitialized) return
        if (!hasActivityRecognitionPermission()) {
            updateNavigationStatus("navigation=activity_recognition_permission_missing step_fallback_wait")
            return
        }
        stepTracker.start()
    }

    private fun stopStepTracking() {
        if (::stepTracker.isInitialized) stepTracker.stop()
    }

    private fun handleLocationUpdate(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val elapsedMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.elapsedRealtimeNanos / 1_000_000L
        } else {
            SystemClock.elapsedRealtime()
        }
        val previousTrusted = latestTrustedLocation
        val trusted = LocationTrustPolicy.trustedOrNull(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = accuracy,
            elapsedRealtimeMs = elapsedMs,
            previous = latestTrustedLocation,
        )
        if (trusted == null) {
            updateNavigationStatus("navigation=gps_untrusted accuracy=${accuracy?.toInt() ?: "null"}m")
            return
        }
        latestTrustedLocation = trusted
        latestHeadingDeg = updateHeadingFromLocation(previous = previousTrusted, location = location, trusted = trusted)
        attemptStepCalibration(trusted)
        updateNavigationStatus(
            "navigation=gps_trusted accuracy=${trusted.accuracyM.toInt()}m steps=$latestStepCount heading=${latestHeadingDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "null"}",
        )
        updateRouteGuidance(trusted)
    }

    private fun updateHeadingFromLocation(
        previous: TrustedLocation?,
        location: Location,
        trusted: TrustedLocation,
    ): Float? {
        if (location.hasBearing() && !location.bearing.isNaN()) {
            return location.bearing.takeIf { it >= 0f && it <= 360f }
        }
        return if (previous == null) null else bearingDegrees(
            fromLat = previous.latitude,
            fromLon = previous.longitude,
            toLat = trusted.latitude,
            toLon = trusted.longitude,
        )
    }

    private fun buildDepthMotionContext(elapsedRealtimeMs: Long): MotionContext {
        val routeAlignment = if (isRouteActive) {
            routeBearingAlignmentQuality(latestHeadingDeg, routeNavigator.currentBearingDeg())
        } else {
            1f
        }
        val freshness = latestTrustedLocation?.let { trusted ->
            val ageMs = elapsedRealtimeMs - trusted.elapsedRealtimeMs
            when {
                ageMs < 0L -> 0.8f
                ageMs <= 2_500L -> 1f
                ageMs <= 7_500L -> 0.75f
                else -> 0.45f
            }
        } ?: 0.7f
        return MotionContext(
            motionQuality = routeAlignment,
            freshnessQuality = freshness,
            routeAlignmentQuality = routeAlignment,
        )
    }

    private fun attemptStepCalibration(trusted: TrustedLocation) {
        if (lastCalibrationLocation == null || lastCalibrationStepCount == 0) {
            lastCalibrationLocation = trusted
            lastCalibrationStepCount = latestStepCount
            lastCalibrationAtMs = trusted.elapsedRealtimeMs
            return
        }
        val deltaSteps = latestStepCount - lastCalibrationStepCount
        if (deltaSteps < 0) {
            lastCalibrationLocation = trusted
            lastCalibrationStepCount = latestStepCount
            lastCalibrationAtMs = trusted.elapsedRealtimeMs
            return
        }
        val elapsedMs = trusted.elapsedRealtimeMs - lastCalibrationAtMs
        val distanceM = haversineMeters(
            lastCalibrationLocation!!.latitude,
            lastCalibrationLocation!!.longitude,
            trusted.latitude,
            trusted.longitude,
        ).toFloat()
        if (deltaSteps >= MIN_STEP_CALIBRATION_STEPS && distanceM >= MIN_STEP_CALIBRATION_DISTANCE_M && elapsedMs >= MIN_STEP_CALIBRATION_DURATION_MS) {
            val calibrated = stepLengthEstimator.calibrate(
                StepCalibrationSample(
                    distanceM = distanceM,
                    steps = deltaSteps,
                    durationMs = elapsedMs,
                ),
            )
            if (calibrated) {
                persistStepLength()
                objectDepthPipeline.setUserStepLength(stepLengthEstimator.stepLengthM)
                val stepLengthText = String.format(Locale.US, "%.2f", stepLengthEstimator.stepLengthM)
                updateNavigationStatus("navigation=step_length_updated=${stepLengthText}m")
            }
            lastCalibrationLocation = trusted
            lastCalibrationStepCount = latestStepCount
            lastCalibrationAtMs = trusted.elapsedRealtimeMs
        } else if (elapsedMs >= MAX_STEP_CALIBRATION_GAP_MS) {
            lastCalibrationLocation = trusted
            lastCalibrationStepCount = latestStepCount
            lastCalibrationAtMs = trusted.elapsedRealtimeMs
        }
    }

    private fun onRouteButtonClicked() {
        if (!requireReporterUserId("login_required_route")) return
        if (routeRequestInFlight.get()) {
            cancelActiveRouteRequest()
            return
        }
        if (isRouteActive) {
            resetRouteState()
            return
        }
        if (!isNavigationPermissionReady()) {
            if (!ensureNavigationPermissionForRouteOrStep()) {
                return
            }
        }
        if (!navigationPermissionsRequestedForRoute) {
            navigationPermissionsRequestedForRoute = true
        }
        startNavigationServicesIfNeeded()
        if (!isNavigationPermissionReady()) {
            updateNavigationStatus("navigation=gps_permission_missing")
            return
        }
        val destination = parseDestinationInput()
        if (destination == null) {
            updateNavigationStatus("navigation=destination_missing")
            return
        }
        currentDestination = destination
        isRouteActive = true
        updateRouteButtonText()
        requestRoute(destination, reason = "user_destination")
    }

    private fun resetRouteState() {
        isRouteActive = false
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
        currentDestination = null
        navigationPermissionsRequestedForRoute = false
        routeNavigator.clear()
        destinationSearchResults.clear()
        updateDestinationSearchUi()
        updateRouteButtonText()
        updateNavigationStatus("navigation=destination_none hazard_only")
    }

    private fun cancelActiveRouteRequest() {
        if (!routeRequestInFlight.get()) {
            return
        }
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
        isRouteActive = false
        currentDestination = null
        navigationPermissionsRequestedForRoute = false
        routeNavigator.clear()
        updateRouteButtonText()
        updateNavigationStatus("navigation=route_request_cancelled hazard_only")
    }

    private fun parseDestinationInput(): RoutePoint? {
        val latText = destinationLatInput.text?.toString()?.trim().orEmpty()
        val lngText = destinationLngInput.text?.toString()?.trim().orEmpty()
        if (latText.isBlank() || lngText.isBlank()) return null
        val latitude = latText.toDoubleOrNull() ?: return null
        val longitude = lngText.toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return RoutePoint(latitude = latitude, longitude = longitude, name = "목적지")
    }

    private fun performDestinationSearch(reset: Boolean) {
        if (!requireReporterUserId("login_required_destination_search")) return
        if (destinationSearchInFlight) return
        if (!isNavigationPermissionReady() && !ensureNavigationPermissionForRouteOrStep()) {
            return
        }
        val query = destinationQueryInput.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            updateNavigationStatus("navigation=destination_query_missing")
            return
        }
        val requestId = destinationSearchGeneration + 1
        destinationSearchGeneration = requestId
        if (reset) {
            destinationSearchPage = 1
            destinationSearchResults.clear()
            destinationSearchQuery = query
            updateDestinationSearchUi()
        } else if (destinationSearchQuery != query) {
            destinationSearchPage = 1
            destinationSearchResults.clear()
            destinationSearchQuery = query
            updateDestinationSearchUi()
        } else {
            destinationSearchPage += 1
        }
        val baseUrl = backendUrlInput.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_BACKEND_BASE_URL }
        val queryLimit = DESTINATION_SEARCH_PAGE_SIZE * destinationSearchPage
        destinationSearchInFlight = true
        destinationSearchButton.isEnabled = false
        destinationMoreButton.isEnabled = false
        destinationCancelButton.isEnabled = true
        try {
            routeExecutor.execute {
                try {
                    val origin = latestTrustedLocation?.let { RoutePoint(it.latitude, it.longitude, "현재 위치") }
                    val result = walkingRouteClient.searchDestinations(
                        baseUrl = baseUrl,
                        query = query,
                        limit = queryLimit,
                        origin = origin,
                    )
                    runOnUiThread {
                        if (requestId != destinationSearchGeneration) return@runOnUiThread
                        destinationSearchInFlight = false
                        destinationSearchButton.isEnabled = true
                        destinationCancelButton.isEnabled = false
                        val merged = if (reset) result.results else {
                            destinationSearchResults + result.results
                        }
                        destinationSearchResults.clear()
                        destinationSearchResults.addAll(merged.distinctBy { "${it.id}:${it.point.latitude}:${it.point.longitude}" })
                        updateDestinationSearchUi()
                        updateNavigationStatus("navigation=search_results query=${result.query} count=${result.results.size}")
                    }
                } catch (error: RuntimeException) {
                    runOnUiThread {
                        if (requestId != destinationSearchGeneration) return@runOnUiThread
                        destinationSearchInFlight = false
                        destinationSearchButton.isEnabled = true
                        destinationCancelButton.isEnabled = false
                        updateDestinationSearchUi()
                        updateNavigationStatus("navigation=destination_search_failed ${error::class.java.simpleName}")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            destinationSearchInFlight = false
            destinationSearchButton.isEnabled = true
            destinationCancelButton.isEnabled = false
            destinationSearchResults.removeAll { true }
            updateDestinationSearchUi()
            updateNavigationStatus("navigation=destination_search_failed executor_rejected")
        }
    }

    private fun cancelDestinationSearch() {
        if (!destinationSearchInFlight) return
        destinationSearchGeneration += 1
        destinationSearchInFlight = false
        destinationSearchButton.isEnabled = true
        destinationCancelButton.isEnabled = false
        destinationMoreButton.isEnabled = false
        destinationMoreButton.visibility = View.GONE
        updateDestinationSearchUi()
        updateNavigationStatus("navigation=destination_search_cancelled")
    }

    private fun onDestinationSelected(result: DestinationSearchResult) {
        if (!requireReporterUserId("login_required_route_select")) return
        destinationLatInput.setText(result.point.latitude.toString())
        destinationLngInput.setText(result.point.longitude.toString())
        currentDestination = result.point
        isRouteActive = true
        updateRouteButtonText()
        updateNavigationStatus("navigation=destination_selected ${result.name} ${formatDestinationDistance(result.distanceM)}")
        requestRoute(result.point, reason = "user_destination")
    }

    private fun updateDestinationSearchUi() {
        if (!::destinationSearchResultsContainer.isInitialized) return
        destinationSearchResultsContainer.removeAllViews()
        if (destinationSearchResults.isEmpty()) {
            destinationSearchResultsContainer.addView(
                TextView(this).apply {
                    text = if (destinationSearchQuery.isBlank()) "검색어를 입력하세요." else "검색 결과 없음"
                    textSize = 12f
                    setTextColor(0xffd7d7ff.toInt())
                },
            )
            destinationMoreButton.visibility = View.GONE
            return
        }
        destinationSearchResults.forEach { result ->
            destinationSearchResultsContainer.addView(
                Button(this).apply {
                    text = "${result.name} · ${result.address ?: "주소 없음"} · ${formatDestinationDistance(result.distanceM)}"
                    textSize = 11f
                    setOnClickListener {
                        onDestinationSelected(result)
                    }
                },
            )
        }
        val canLoadMore = destinationSearchResults.size >= destinationSearchPage * DESTINATION_SEARCH_PAGE_SIZE &&
            destinationSearchResults.size < DESTINATION_SEARCH_MAX_RESULTS
        destinationMoreButton.visibility = if (canLoadMore) View.VISIBLE else View.GONE
        destinationMoreButton.isEnabled = !destinationSearchInFlight
        destinationCancelButton.isEnabled = destinationSearchInFlight
    }

    private fun updateRouteButtonText() {
        if (!::routeButton.isInitialized) return
        routeButton.text = when {
            routeRequestInFlight.get() -> "경로 취소"
            isRouteActive -> "경로 정지"
            else -> "경로 시작"
        }
    }

    private fun requestRoute(destination: RoutePoint, reason: String) {
        val origin = latestTrustedLocation
        if (origin == null) {
            isRouteActive = false
            currentDestination = null
            updateRouteButtonText()
            updateNavigationStatus("navigation=route_blocked trusted_gps_missing")
            return
        }
        if (!routeRequestInFlight.compareAndSet(false, true)) {
            updateNavigationStatus("navigation=route_blocked request_in_flight")
            return
        }
        val requestId = ++routeRequestGeneration
        val baseUrl = backendUrlInput.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_BACKEND_BASE_URL }
        updateNavigationStatus("navigation=route_requesting reason=$reason priority=STAIR_AVOID")
        updateRouteButtonText()
        try {
            routeExecutor.execute {
                try {
                    val route = walkingRouteClient.fetchRoute(
                        baseUrl = baseUrl,
                        request = WalkingRouteRequest(
                            origin = RoutePoint(origin.latitude, origin.longitude, "현재 위치"),
                            destination = destination,
                        ),
                    )
                    runOnUiThread {
                        if (requestId != routeRequestGeneration) return@runOnUiThread
                        if (!isRouteActive) return@runOnUiThread
                        routeNavigator.setRoute(route)
                        routeRequestInFlight.set(false)
                        updateRouteButtonText()
                        updateNavigationStatus(
                            "navigation=route_ready distance=${route.summary.distanceM}m priority=${route.priority}",
                        )
                    }
                } catch (error: RuntimeException) {
                    runOnUiThread {
                        if (requestId != routeRequestGeneration) return@runOnUiThread
                        isRouteActive = false
                        currentDestination = null
                        routeRequestInFlight.set(false)
                        updateRouteButtonText()
                        updateNavigationStatus("navigation=route_failed ${error::class.java.simpleName}")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            routeRequestInFlight.set(false)
            isRouteActive = false
            currentDestination = null
            updateRouteButtonText()
            updateNavigationStatus("navigation=route_failed executor_rejected")
        }
    }

    private fun updateRouteGuidance(location: TrustedLocation) {
        val nowMs = System.currentTimeMillis()
        val update = routeNavigator.update(
            location = location,
            nowMs = nowMs,
            requestInFlight = routeRequestInFlight.get(),
        )
        if (update.shouldReroute) {
            currentDestination?.let { destination -> requestRoute(destination, reason = "off_route") }
        }
        if (update.arrived) {
            isRouteActive = false
            updateRouteButtonText()
            navigationPermissionsRequestedForRoute = false
        }
        maybePlayProgressBeep(nowMs, offRoute = update.offRoute, arrived = update.arrived)
        val instruction = update.instruction ?: return
        updateNavigationStatus("navigation=${update.reason} offRoute=${update.offRoute} arrived=${update.arrived}")
        if (latestDeviceGateAllowsSpeech && feedbackPolicy.canSpeakNavigation(nowMs)) {
            speakNavigation(instruction)
        }
    }

    private fun updateNavigationStatus(text: String) {
        if (::navigationStatusText.isInitialized) {
            navigationStatusText.text = text
        }
    }

    private fun updateProgressBeepButtons() {
        if (!::progressBeepToggleButton.isInitialized || !::progressBeepVolumeButton.isInitialized) return
        progressBeepToggleButton.text = if (progressBeepEnabled) "진행음 켜짐" else "진행음 꺼짐"
        progressBeepVolumeButton.text = "진행음 볼륨 ${progressBeepVolumePercent}%"
    }

    private fun updateLoginButtonText() {
        if (!::loginSaveButton.isInitialized) return
        val userId = currentReporterUserId()
        loginSaveButton.text = if (userId == null) "로그인 ID 저장 필요" else "로그인 ID 저장됨"
    }

    private fun nextProgressBeepVolume(current: Int): Int {
        return when {
            current < 20 -> 20
            current < 40 -> 40
            current < 60 -> 60
            else -> 0
        }
    }

    private fun maybePlayProgressBeep(nowMs: Long, offRoute: Boolean, arrived: Boolean) {
        if (!progressBeepEnabled || progressBeepVolumePercent <= 0) return
        if (!isRouteActive || offRoute || arrived) return
        if (!latestDeviceGateAllowsSpeech || !feedbackPolicy.canSpeakNavigation(nowMs)) return
        if (nowMs - lastProgressBeepAtMs < PROGRESS_BEEP_INTERVAL_MS) return
        ensureFeedbackActuator().playProgressBeep(progressBeepVolumePercent)
        lastProgressBeepAtMs = nowMs
    }

    private fun updateDebugUploadButton() {
        if (!::debugUploadButton.isInitialized || !::metadataLogUploader.isInitialized) return
        debugUploadButton.text = if (metadataLogUploader.isEnabled()) {
            "서버 로그 끄기"
        } else {
            "서버 로그 켜기"
        }
        debugUploadButton.isEnabled = true
    }

    private fun updateFrameCaptureButton() {
        if (!::debugFrameCaptureButton.isInitialized) return
        if (!BuildConfig.DEBUG) {
            debugFrameCaptureButton.visibility = View.GONE
            debugFrameCaptureButton.isEnabled = false
            return
        }
        debugFrameCaptureButton.text = if (frameCaptureRequested.get()) {
            "이미지 캡쳐 대기 중"
        } else {
            "이미지 1장 캡쳐"
        }
        debugFrameCaptureButton.isEnabled = true
    }

    private fun closeDetectorAsync() {
        val closeableDetector = frameDetector as? Closeable
        if (closeableDetector != null && !detectorExecutor.isShutdown) {
            try {
                detectorExecutor.execute { closeableDetector.close() }
            } catch (_: RejectedExecutionException) {
                closeableDetector.close()
            }
        }
        detectorExecutor.shutdown()
    }

    private fun createExternalCameraTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textureId
    }

    private fun updateStatus(status: String, detail: String) {
        statusText.text = status
        detailText.text = detail
    }

    private fun kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot.statusTextForNoDetection(): String {
        return when {
            hasMetricRawDepth && detectorAvailable -> "카메라 preview와 Raw Depth를 수신 중입니다. detector 결과가 없으면 객체 안내는 내보내지 않습니다."
            hasMetricRawDepth -> "카메라 preview와 Raw Depth를 수신 중입니다. detector asset 연결 전이라 객체 안내는 대기합니다."
            hasFullDepth -> "카메라 preview와 Full Depth를 수신 중입니다. Raw Depth가 sparse하거나 아직 준비되지 않았습니다."
            depthSupported -> "카메라 preview는 실행 중입니다. ARCore tracking 초기화/feature point 부족으로 depth를 기다리는 중입니다."
            else -> "이 기기는 ARCore DepthMode.AUTOMATIC 미지원으로 metric depth 안내가 제한됩니다."
        }
    }

    private data class OverlayDetectionSelection(
        val detections: List<DetectionCandidate>,
        val mappingSnapshot: DetectionSnapshot,
        val debugState: OverlayDebugState,
    )

    private data class OverlayDebugState(
        val selection: String,
        val detectionCount: Int,
        val tactileDetectionCount: Int,
        val boxCount: Int = 0,
        val tactileBoxCount: Int = 0,
        val heldTactileBoxCount: Int = 0,
        val smoothedTactileBoxCount: Int = 0,
        val holdApplied: Boolean,
        val snapshotPartial: Boolean,
        val sourceAgeMs: Long?,
        val frameDeltaMs: Long?,
        val staleReason: String?,
    ) {
        fun withStabilizer(result: TactileOverlayStabilizer.Result): OverlayDebugState {
            return copy(
                boxCount = result.boxes.size,
                tactileBoxCount = result.tactileBoxCount,
                heldTactileBoxCount = result.heldTactileBoxCount,
                smoothedTactileBoxCount = result.smoothedTactileBoxCount,
                holdApplied = holdApplied || result.heldTactileBoxCount > 0,
            )
        }
    }

    private class TactileOverlayStabilizer {
        private val tracks = mutableListOf<Track>()

        fun update(
            boxes: List<DebugBboxOverlayView.DebugOverlayBox>,
            nowMs: Long,
        ): Result {
            val output = mutableListOf<DebugBboxOverlayView.DebugOverlayBox>()
            val matchedTracks = mutableSetOf<Track>()
            var smoothedCount = 0

            for (box in boxes) {
                if (!box.best && box.isTactileOverlayBox()) {
                    val track = findBestTrack(box)
                    if (track == null) {
                        val newTrack = Track(
                            className = box.className,
                            rectPx = RectF(box.rectPx),
                            label = box.label,
                            lastSeenMs = nowMs,
                        )
                        tracks += newTrack
                        matchedTracks += newTrack
                        output += box
                    } else {
                        val smoothedRect = smoothRect(track.rectPx, box.rectPx)
                        track.rectPx = RectF(smoothedRect)
                        track.label = box.label
                        track.lastSeenMs = nowMs
                        matchedTracks += track
                        smoothedCount += 1
                        output += box.copy(rectPx = smoothedRect, smoothed = true)
                    }
                } else {
                    output += box
                }
            }

            val heldBoxes = tracks
                .filter { it !in matchedTracks }
                .mapNotNull { track ->
                    val ageMs = nowMs - track.lastSeenMs
                    if (ageMs > TACTILE_OVERLAY_VISUAL_HOLD_MS) {
                        null
                    } else {
                        DebugBboxOverlayView.DebugOverlayBox(
                            rectPx = RectF(track.rectPx),
                            label = track.label,
                            best = false,
                            className = track.className,
                            held = true,
                            ageMs = ageMs,
                        )
                    }
                }
            tracks.removeAll { nowMs - it.lastSeenMs > TACTILE_OVERLAY_VISUAL_HOLD_MS }

            val stabilizedBoxes = output + heldBoxes
            return Result(
                boxes = stabilizedBoxes,
                tactileBoxCount = stabilizedBoxes.count { it.isTactileOverlayBox() },
                heldTactileBoxCount = heldBoxes.size,
                smoothedTactileBoxCount = smoothedCount,
            )
        }

        fun clear() {
            tracks.clear()
        }

        private fun DebugBboxOverlayView.DebugOverlayBox.isTactileOverlayBox(): Boolean {
            val name = className ?: label
            return name.lowercase(Locale.US).contains("tactile") || name.contains("점자")
        }

        private fun smoothRect(previous: RectF, current: RectF): RectF {
            val alpha = TACTILE_OVERLAY_SMOOTHING_ALPHA
            val inverse = 1f - alpha
            return RectF(
                previous.left * inverse + current.left * alpha,
                previous.top * inverse + current.top * alpha,
                previous.right * inverse + current.right * alpha,
                previous.bottom * inverse + current.bottom * alpha,
            )
        }

        private fun findBestTrack(box: DebugBboxOverlayView.DebugOverlayBox): Track? {
            return tracks
                .filter { it.className == box.className }
                .maxByOrNull { track -> track.matchScore(box.rectPx) }
                ?.takeIf { it.matchScore(box.rectPx) >= TACTILE_OVERLAY_MIN_MATCH_SCORE }
        }

        private fun Track.matchScore(rect: RectF): Float {
            val iou = rectPx.iou(rect)
            val distance = centerDistance(rectPx, rect)
            val maxSide = maxOf(rectPx.width(), rectPx.height(), rect.width(), rect.height()).coerceAtLeast(1f)
            val distanceScore = (1f - (distance / maxSide)).coerceIn(0f, 1f)
            return maxOf(iou, distanceScore)
        }

        private fun RectF.iou(other: RectF): Float {
            val left = maxOf(left, other.left)
            val top = maxOf(top, other.top)
            val right = minOf(right, other.right)
            val bottom = minOf(bottom, other.bottom)
            val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
            val union = width() * height() + other.width() * other.height() - intersection
            return if (union <= 0f) 0f else intersection / union
        }

        private fun centerDistance(first: RectF, second: RectF): Float {
            val dx = first.centerX() - second.centerX()
            val dy = first.centerY() - second.centerY()
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        private class Track(
            val className: String?,
            var rectPx: RectF,
            var label: String,
            var lastSeenMs: Long,
        )

        data class Result(
            val boxes: List<DebugBboxOverlayView.DebugOverlayBox>,
            val tactileBoxCount: Int,
            val heldTactileBoxCount: Int,
            val smoothedTactileBoxCount: Int,
        )
    }

    private data class DetectionSnapshot(
        val detections: List<DetectionCandidate>,
        val frameTimestampMs: Long?,
        val capturedAtMs: Long?,
        val startedAtMs: Long?,
        val completedAtMs: Long?,
        val imageWidth: Int?,
        val imageHeight: Int?,
        val detectDurationMs: Long?,
        val detectorTiming: AndroidDetectorTiming?,
        val reportImage: ByteArray? = null,
        val partial: Boolean,
    ) {
        fun freshDetections(
            nowMs: Long,
            currentFrameTimestampMs: Long,
            maxSourceAgeMs: Long,
            maxFrameDeltaMs: Long,
        ): List<DetectionCandidate> {
            return if (staleReason(nowMs, currentFrameTimestampMs, maxSourceAgeMs, maxFrameDeltaMs) == null) {
                detections
            } else {
                emptyList()
            }
        }

        fun sourceAgeMs(nowMs: Long): Long? = capturedAtMs?.let { nowMs - it }

        fun completedAgeMs(nowMs: Long): Long? = completedAtMs?.let { nowMs - it }

        fun hasTactileDetection(): Boolean {
            return detections.any { it.isTactileDetection() }
        }

        fun tactileDetections(): List<DetectionCandidate> = detections.filter { it.isTactileDetection() }

        fun hasImageSize(): Boolean {
            return imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0
        }

        fun frameDeltaMs(currentFrameTimestampMs: Long): Long? {
            val frameDelta = rawFrameDeltaMs(currentFrameTimestampMs) ?: return null
            return frameDelta.takeIf { it >= 0L }
        }

        fun ageMs(nowMs: Long): Long? = sourceAgeMs(nowMs)

        fun staleReason(
            nowMs: Long,
            currentFrameTimestampMs: Long,
            maxSourceAgeMs: Long,
            maxFrameDeltaMs: Long,
        ): String? {
            val frameDelta = rawFrameDeltaMs(currentFrameTimestampMs)
            if (frameDelta != null) {
                if (frameDelta < -FRAME_TIMESTAMP_TOLERANCE_MS) return "frame_delta_negative"
                if (frameDelta > maxFrameDeltaMs) return "frame_delta>${maxFrameDeltaMs}ms"
            }
            val sourceAge = sourceAgeMs(nowMs) ?: return null
            return if (sourceAge > maxSourceAgeMs) "source_age>${maxSourceAgeMs}ms" else null
        }

        fun debugStatusText(
            nowMs: Long,
            currentFrameTimestampMs: Long,
            maxSourceAgeMs: Long,
            maxFrameDeltaMs: Long,
        ): String {
            if (completedAtMs == null) {
                return "detector: 아직 완료된 TFLite 결과 없음"
            }
            val sourceAgeMs = sourceAgeMs(nowMs) ?: 0L
            val completedAgeMs = completedAgeMs(nowMs) ?: 0L
            val frameDeltaMs = frameDeltaMs(currentFrameTimestampMs)
            val stale = staleReason(nowMs, currentFrameTimestampMs, maxSourceAgeMs, maxFrameDeltaMs)?.let { " · stale=$it depth입력 제외" } ?: ""
            val partialText = if (partial) "partial" else "complete"
            val top = detections.maxByOrNull { it.detectionConfidence }
            val topText = if (top == null) {
                "top=none"
            } else {
                val bbox = top.bboxNorm
                "top=${top.className} ${formatPercent(top.detectionConfidence)} " +
                    "c=(${formatDecimal(bbox.center.x)},${formatDecimal(bbox.center.y)}) " +
                    "wh=(${formatDecimal(bbox.width)},${formatDecimal(bbox.height)})"
            }
            val duration = detectDurationMs?.let { " · detect=${it}ms" } ?: ""
            val models = detectorTiming?.completedModels?.takeIf { it.isNotEmpty() }?.joinToString("+")
                ?.let { " · models=$it" } ?: ""
            val imageSize = if (imageWidth != null && imageHeight != null) " · image=${imageWidth}x$imageHeight" else ""
            val frameDeltaText = frameDeltaMs?.let { " frameDelta=${it}ms" } ?: ""
            return "detector: ${detections.size}개 $partialText · sourceAge=${sourceAgeMs}ms completedAge=${completedAgeMs}ms$frameDeltaText$duration$models$stale · frameTs=${frameTimestampMs ?: "-"}$imageSize · $topText"
        }

        companion object {
            fun empty(): DetectionSnapshot = DetectionSnapshot(emptyList(), null, null, null, null, null, null, null, null, null, false)

            private fun formatPercent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100f)

            private fun formatDecimal(value: Float): String = String.format(Locale.US, "%.2f", value)
        }

        private fun rawFrameDeltaMs(currentFrameTimestampMs: Long): Long? {
            val detectorFrameTimestamp = frameTimestampMs ?: return null
            return currentFrameTimestampMs - detectorFrameTimestamp
        }
    }

    private fun buildCaptureLogEntry(
        frameTimestampMs: Long,
        nowMs: Long,
        detectionSnapshot: DetectionSnapshot,
        overlayDebugState: OverlayDebugState,
        detectionsUsedForDepth: List<DetectionCandidate>,
        snapshot: DepthFrameSnapshot,
        bestOutput: TrackedObjectDepth?,
        depthTransformPath: String,
        depthFallbackReason: String?,
    ): MetadataCaptureLogEntry {
        val top = detectionSnapshot.detections.maxByOrNull { it.detectionConfidence }
        val depthWidth = snapshot.rawDepth?.width ?: snapshot.fullDepth?.width
        val depthHeight = snapshot.rawDepth?.height ?: snapshot.fullDepth?.height
        return MetadataCaptureLogEntry(
            frameTimestampMs = frameTimestampMs,
            detectorFrameTimestampMs = detectionSnapshot.frameTimestampMs,
            detectorAgeMs = detectionSnapshot.ageMs(nowMs),
            detectorSourceAgeMs = detectionSnapshot.sourceAgeMs(nowMs),
            detectorCompletedAgeMs = detectionSnapshot.completedAgeMs(nowMs),
            detectorFrameDeltaMs = detectionSnapshot.frameDeltaMs(frameTimestampMs),
            detectDurationMs = detectionSnapshot.detectDurationMs,
            detectorYuvDecodeMs = detectionSnapshot.detectorTiming?.yuvDecodeMs,
            detectorModelKey = detectionSnapshot.detectorTiming?.modelKey,
            detectorLoadedModelKey = detectorLoadedModelKey,
            detectorModelFallbackUsed = detectorModelFallbackUsed,
            detectorModelLoadReason = detectorLoadReason,
            detectorModelPreprocessMs = detectionSnapshot.detectorTiming?.modelPreprocessMs,
            detectorModelInferenceMs = detectionSnapshot.detectorTiming?.modelInferenceMs,
            detectorModelParseMs = detectionSnapshot.detectorTiming?.modelParseMs,
            detectorCocoPreprocessMs = detectionSnapshot.detectorTiming?.cocoPreprocessMs,
            detectorCocoInferenceMs = detectionSnapshot.detectorTiming?.cocoInferenceMs,
            detectorCocoParseMs = detectionSnapshot.detectorTiming?.cocoParseMs,
            detectorCustomPreprocessMs = detectionSnapshot.detectorTiming?.customPreprocessMs,
            detectorCustomInferenceMs = detectionSnapshot.detectorTiming?.customInferenceMs,
            detectorCustomParseMs = detectionSnapshot.detectorTiming?.customParseMs,
            detectorCompletedModels = detectionSnapshot.detectorTiming?.completedModels ?: emptyList(),
            detectorSkippedModels = detectionSnapshot.detectorTiming?.skippedModels ?: emptyList(),
            detectorPartial = detectionSnapshot.partial,
            detectionCount = detectionSnapshot.detections.size,
            detectionsUsedForDepth = detectionsUsedForDepth.isNotEmpty(),
            overlaySelection = overlayDebugState.selection,
            overlayDetectionCount = overlayDebugState.detectionCount,
            overlayTactileDetectionCount = overlayDebugState.tactileDetectionCount,
            overlayBoxCount = overlayDebugState.boxCount,
            overlayTactileBoxCount = overlayDebugState.tactileBoxCount,
            overlayHeldTactileBoxCount = overlayDebugState.heldTactileBoxCount,
            overlaySmoothedTactileBoxCount = overlayDebugState.smoothedTactileBoxCount,
            overlayHoldApplied = overlayDebugState.holdApplied,
            overlaySnapshotPartial = overlayDebugState.snapshotPartial,
            overlaySourceAgeMs = overlayDebugState.sourceAgeMs,
            overlayFrameDeltaMs = overlayDebugState.frameDeltaMs,
            overlayStaleReason = overlayDebugState.staleReason,
            staleReason = detectionSnapshot.staleReason(
                nowMs = nowMs,
                currentFrameTimestampMs = frameTimestampMs,
                maxSourceAgeMs = MAX_DEPTH_DETECTION_SOURCE_AGE_MS,
                maxFrameDeltaMs = MAX_DEPTH_DETECTION_FRAME_DELTA_MS,
            ),
            topDetectionClassName = top?.className,
            topDetectionConfidence = top?.detectionConfidence,
            topDetectionBbox = top?.bboxNorm?.toMetadataRect(),
            bestDepthClassName = bestOutput?.className,
            bestDepthTrackId = bestOutput?.trackId,
            bestDepthSource = bestOutput?.source?.name,
            bestDepthDetectionConfidence = bestOutput?.detectionConfidence,
            bestDepthConfidenceScore = bestOutput?.confidence?.finalScore,
            bestDepthMedianM = bestOutput?.depthMedianM,
            bestDepthP20M = bestOutput?.depthP20M,
            bestDepthRiskDistanceM = bestOutput?.riskDistanceM,
            bestDepthIqrM = bestOutput?.depthIqrM,
            bestDepthValidSampleCount = bestOutput?.validSampleCount,
            bestDepthValidSampleRatio = bestOutput?.validSampleRatio,
            bestDepthBbox = bestOutput?.bboxNorm?.toMetadataRect(),
            previewWidth = surfaceWidth.takeIf { it > 0 },
            previewHeight = surfaceHeight.takeIf { it > 0 },
            cameraImageWidth = detectionSnapshot.imageWidth,
            cameraImageHeight = detectionSnapshot.imageHeight,
            displayRotation = displayRotation(),
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            overlayTransformPath = "arcore_image_to_view",
            depthTransformPath = depthTransformPath,
            transformPath = depthTransformPath,
            fallbackReason = depthFallbackReason,
        )
    }

    private fun RectNorm.toMetadataRect(): MetadataRect {
        return MetadataRect(x = x, y = y, width = width, height = height)
    }

    private fun kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth.debugGeometryText(): String {
        return "bbox: c=(${decimal(centerNorm.x)},${decimal(centerNorm.y)}) " +
            "wh=(${decimal(bboxNorm.width)},${decimal(bboxNorm.height)}) · " +
            "samples=$validSampleCount ratio=${percent(validSampleRatio)} median=${meters(depthMedianM)}"
    }

    private fun percent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100f)

    private fun decimal(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun meters(value: Float?): String = value?.let { String.format(Locale.US, "%.2fm", it) } ?: "-"

    private fun String.toReportModelKey(): String? {
        return when (this) {
            "unified_walksafe" -> "unified_walksafe"
            "legacy_two_model",
            "custom_tactile",
            -> "custom_tactile"
            else -> null
        }
    }

    private fun String.toStatusToken(maxLength: Int): String {
        return trim()
            .replace(Regex("\\s+"), "_")
            .take(maxLength)
            .ifBlank { "-" }
    }

    private companion object {
        const val CAMERA_PERMISSION_REQUEST = 3201
        const val NAVIGATION_PERMISSION_REQUEST = 3202
        const val VOICE_PERMISSION_REQUEST = 3203
        const val PREF_STEP_LENGTH_KEY = "step_length_m"
        const val PREF_PROGRESS_BEEP_ENABLED_KEY = "progress_beep_enabled"
        const val PREF_PROGRESS_BEEP_VOLUME_KEY = "progress_beep_volume_percent"
        const val PREF_REPORTER_USER_ID_KEY = "reporter_user_id"
        const val DEFAULT_PROGRESS_BEEP_VOLUME_PERCENT = 20
        const val PROGRESS_BEEP_INTERVAL_MS = 3_000L
        const val REPORT_UPLOAD_COOLDOWN_MS = 10_000L
        const val MIN_STEP_CALIBRATION_STEPS = 8
        const val MIN_STEP_CALIBRATION_DISTANCE_M = 4.0f
        const val MIN_STEP_CALIBRATION_DURATION_MS = 8_000L
        const val MAX_STEP_CALIBRATION_GAP_MS = 45_000L
        const val DESTINATION_SEARCH_PAGE_SIZE = 5
        const val DESTINATION_SEARCH_MAX_RESULTS = 10
        const val UI_UPDATE_INTERVAL_MS = 500L
        const val OVERLAY_UPDATE_INTERVAL_MS = 100L
        const val DETECTION_INTERVAL_MS = 250L
        const val LOCATION_UPDATE_INTERVAL_MS = 1_500L
        const val LOCATION_FASTEST_INTERVAL_MS = 700L
        const val FEEDBACK_MIN_DEPTH_CONFIDENCE = 0.55f
        const val DEFAULT_BACKEND_BASE_URL = "http://127.0.0.1:8000"
        const val MAX_OVERLAY_DETECTION_SOURCE_AGE_MS = 1_200L
        const val MAX_DEPTH_DETECTION_SOURCE_AGE_MS = 800L
        const val MAX_OVERLAY_DETECTION_FRAME_DELTA_MS = 1_200L
        const val MAX_DEPTH_DETECTION_FRAME_DELTA_MS = 800L
        const val MAX_OVERLAY_HOLD_SOURCE_AGE_MS = 1_500L
        const val MAX_OVERLAY_HOLD_FRAME_DELTA_MS = 1_500L
        const val MAX_TACTILE_OVERLAY_SOURCE_AGE_MS = 2_300L
        const val MAX_TACTILE_OVERLAY_FRAME_DELTA_MS = 2_300L
        const val MAX_TACTILE_OVERLAY_HOLD_SOURCE_AGE_MS = 3_000L
        const val MAX_TACTILE_OVERLAY_HOLD_FRAME_DELTA_MS = 3_000L
        const val TACTILE_OVERLAY_VISUAL_HOLD_MS = 700L
        const val TACTILE_OVERLAY_SMOOTHING_ALPHA = 0.65f
        const val TACTILE_OVERLAY_MIN_MATCH_SCORE = 0.20f
        const val FRAME_TIMESTAMP_TOLERANCE_MS = 100L
        const val MAX_OVERLAY_DETECTION_BOXES = 5
        const val MAX_FRAME_CAPTURE_DETECTIONS = 20
        const val DEBUG_FRAME_CAPTURE_MAX_DIMENSION = 640
        const val DEBUG_FRAME_CAPTURE_JPEG_QUALITY = 90
        const val REPORT_FRAME_CAPTURE_MAX_DIMENSION = 320
        const val REPORT_FRAME_CAPTURE_JPEG_QUALITY = 80
    }

    private enum class ActionMode {
        START,
        OPEN_SETTINGS,
    }

    private data class ReportUploadState(
        var lastUploadedAtMs: Long = 0L,
        var lastAttemptCount: Int = 0,
        var inFlight: Boolean = false,
    )
}

private class FrameImageToTextureCoordinateMapper(
    private val frame: Frame,
    private val imageSize: ImageSize,
    private val depthSize: ImageSize,
) : CoordinateMapper {
    override fun modelToImage(point: Point2): Point2 = normalize(point)

    override fun imageToModel(point: Point2): Point2 = normalize(point)

    override fun imageToDepth(point: Point2): Point2? {
        if (imageSize.width <= 0 || imageSize.height <= 0 || depthSize.width <= 0 || depthSize.height <= 0) return null
        val imagePoint = normalize(point)
        val input = floatArrayOf(
            imagePoint.x * imageSize.width,
            imagePoint.y * imageSize.height,
        )
        val output = FloatArray(2)
        return try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                input,
                Coordinates2d.TEXTURE_NORMALIZED,
                output,
            )
            val x = output[0]
            val y = output[1]
            if (!x.isFinite() || !y.isFinite()) return null
            if (x !in 0f..1f || y !in 0f..1f) return null
            Point2(x, y)
        } catch (_: RuntimeException) {
            null
        }
    }

    override fun depthToImage(point: Point2): Point2? {
        if (imageSize.width <= 0 || imageSize.height <= 0 || depthSize.width <= 0 || depthSize.height <= 0) return null
        val depthPoint = normalize(point)
        val input = floatArrayOf(depthPoint.x, depthPoint.y)
        val output = FloatArray(2)
        return try {
            frame.transformCoordinates2d(
                Coordinates2d.TEXTURE_NORMALIZED,
                input,
                Coordinates2d.IMAGE_NORMALIZED,
                output,
            )
            val x = output[0]
            val y = output[1]
            if (!x.isFinite() || !y.isFinite()) return null
            if (x !in 0f..1f || y !in 0f..1f) return null
            Point2(x, y)
        } catch (_: RuntimeException) {
            null
        }
    }

    override fun imagePolygonToDepthPolygon(polygon: List<Point2>): List<Point2> {
        return polygon.map { point ->
            imageToDepth(point) ?: return emptyList()
        }
    }

    override fun depthPixelToCameraPoint(x: Int, y: Int, zM: Float, intrinsics: CameraIntrinsics): Vec3? {
        return kr.co.hanium.dreamup.walksafe.depth.depthPixelToCameraPoint(x, y, zM, intrinsics)
    }

    private fun normalize(point: Point2): Point2 {
        return Point2(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
    }
}
