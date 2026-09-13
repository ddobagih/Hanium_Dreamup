package kr.co.hanium.dreamup.walksafe.fieldtest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kr.co.hanium.dreamup.walksafe.fieldlog.PositionFieldSessionLease
import kr.co.hanium.dreamup.walksafe.fieldlog.PositionFieldSessionRecorder
import kr.co.hanium.dreamup.walksafe.fieldlog.PositionFieldSessionStatus
import kr.co.hanium.dreamup.walksafe.fieldlog.PositionFieldSessionSummary
import kr.co.hanium.dreamup.walksafe.fieldlog.RecorderBinding
import kr.co.hanium.dreamup.walksafe.navigation.AndroidEarthOrientationTracker
import kr.co.hanium.dreamup.walksafe.navigation.AndroidStepTracker
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.AndroidGnssObservationSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualityObserver
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualitySnapshot
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead

/** Foreground-only debug diagnostic; it owns no production account, lease or profile. */
class OutdoorPositionTestActivity : Activity() {
    private data class GpsHeading(val timeMs: Long, val speed: WalkingSpeedObservation?, val course: HeadingObservation?)
    private data class ExportRequest(val sessionId: String, val rawFile: File?, val title: String)

    private val handler = Handler(Looper.getMainLooper())
    private val exportExecutor = Executors.newSingleThreadExecutor()
    private val binding: RecorderBinding by lazy {
        val preferences = getSharedPreferences("outdoor_position_test", MODE_PRIVATE)
        val existing = preferences.getString("local_scope_uuid", null)?.takeIf { value ->
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        }
        val scope = existing ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("local_scope_uuid", it).commit())
        }
        RecorderBinding(scope, 0L)
    }
    private val calibration = TimestampAlignedWalkingCalibration()
    private val gpsHistory = ArrayDeque<GpsHeading>()
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var orientation: AndroidEarthOrientationTracker
    private lateinit var recorder: PositionFieldSessionRecorder
    private var coordinator = PositioningCoordinator()
    private var stepTracker: AndroidStepTracker? = null
    private var motionTracker: AndroidPedestrianMotionTracker? = null
    private var qualityObserver: GnssQualityObserver? = null
    private var rawRecorder: OutdoorRawGnssRecorder? = null
    private var rawStatus: OutdoorRawGnssRecorder.Status? = null
    private var rawSessionId: String? = null
    private var locationCallback: LocationCallback? = null
    private var generation = 0
    private var collecting = false
    private var stopping = false
    private var storageReady = false
    private var lease: PositionFieldSessionLease? = null
    private var pendingFinalization: PositionFieldSessionLease? = null
    private var completedSessionId: String? = null
    private var completedRawFile: File? = null
    private var completedSessions = emptyList<PositionFieldSessionSummary>()
    private var pendingExport: ExportRequest? = null
    private var exporting = false
    private var latestLocation: Location? = null
    private var latestSnapshot: PositioningSnapshot? = null
    private var latestQuality: GnssQualitySnapshot? = null
    private var stationary = PositionStationaryState.UNKNOWN
    private var stationarySinceMs = 0L
    private var motionObservedAtMs = 0L
    private var lastTraceNs = 0L
    private var utcAnchorNs: Long? = null
    private var utcAnchorEpochMs: Long? = null
    private var totalSteps = 0
    private var gnssCount = 0L
    private var traceCount = 0L
    private var checkpointOrdinal = 0
    private var message = "정밀 위치·신체 활동 권한과 아래 두 항목을 확인하세요."
    private lateinit var statusText: TextView
    private lateinit var mountConsent: CheckBox
    private lateinit var storageConsent: CheckBox
    private lateinit var previewButton: Button
    private lateinit var startButton: Button
    private lateinit var checkpointButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportTraceButton: Button
    private lateinit var exportRawButton: Button
    private lateinit var completedButton: Button
    private lateinit var retryFinishButton: Button

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!collecting) return
            val nowMs = SystemClock.elapsedRealtime()
            if (lease != null) {
                val freshStationary = stationary == PositionStationaryState.STATIONARY &&
                    nowMs - motionObservedAtMs in 0L..1_500L
                val snapshot = if (freshStationary) coordinator.observeZupt(ZuptObservation(nowMs)) else null
                if (snapshot != null) latestSnapshot = snapshot
                appendMotion(snapshot, nowMs, stepDetected = false, zuptApplied =
                    snapshot?.motionUpdate?.disposition == PedestrianMotionUpdateDisposition.APPLIED)
            }
            renderStatus()
            if (collecting) handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orientation = AndroidEarthOrientationTracker(this)
        storageReady = runCatching {
            val root = File(noBackupFilesDir, "outdoor_position_test")
            check(root.isDirectory || root.mkdirs())
            check(binding.localAccountScopeId.isNotBlank())
            recorder = PositionFieldSessionRecorder(
                noBackupRootDirectory = root,
                aead = AndroidKeyStoreAead(AeadKeyPolicy(
                    aliasPrefix = "walksafe.outdoor-test.aead.v", currentVersion = 1, readableVersions = setOf(1),
                )),
                elapsedRealtimeNsClock = SystemClock::elapsedRealtimeNanos,
                maxStorageBytes = 256L * 1_024L * 1_024L,
            )
            refreshCompletedSessions()
        }.isSuccess
        savedInstanceState?.getString("pending_export_session")?.let { sessionId ->
            if (completedSessions.any { it.sessionId == sessionId }) {
                val raw = savedInstanceState.getBoolean("pending_export_raw")
                val file = rawFileFor(sessionId).takeIf { raw && it.isFile }
                if (!raw || file != null) {
                    completedSessionId = sessionId
                    completedRawFile = rawFileFor(sessionId).takeIf { it.isFile }
                    pendingExport = ExportRequest(sessionId, file, exportTitle(sessionId, raw))
                }
            }
        }
        buildUi()
        if (!storageReady) message = "시험 저장 공간을 준비하지 못했습니다. 기록을 시작할 수 없습니다."
        renderStatus()
    }

    private fun buildUi() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
            setBackgroundColor(Color.WHITE)
        }
        fun label(value: String, size: Float = 17f): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.BLACK)
            setPadding(0, dp(8), 0, dp(8)); column.addView(this)
        }
        fun button(value: String, action: () -> Unit): Button = Button(this).apply {
            text = value; textSize = 17f; minHeight = dp(56)
            setOnClickListener { action() }; column.addView(this)
        }
        label("위치 야외 테스트", 25f)
        label("왕복 경로의 실제 위치·걸음·위성 원시 데이터를 휴대폰에 기록합니다. 별도 시험 프로필을 사용하며 경로 매칭은 적용하지 않습니다.")
        label("화면을 켜 둔 채 사용하세요. 화면을 끄거나 다른 앱으로 이동하면 기록이 종료됩니다. 돌아와서 자동 재시작하지 않습니다.")
        button("1. 권한 확인") { requestRequiredPermissions() }
        mountConsent = CheckBox(this).apply {
            text = "휴대폰을 세로·상단 위·화면 몸쪽·뒷면 바깥으로 가슴 중앙에 고정했습니다."
            textSize = 17f; setTextColor(Color.BLACK); column.addView(this)
            setOnCheckedChangeListener { _, _ -> renderStatus() }
        }
        storageConsent = CheckBox(this).apply {
            text = "이동 좌표와 위성 원시 파일을 이 기기에 저장하고 선택한 위치로 내보내는 데 동의합니다."
            textSize = 17f; setTextColor(Color.BLACK); column.addView(this)
            setOnCheckedChangeListener { _, _ -> renderStatus() }
        }
        previewButton = button("2. 신호 확인 · 저장 안 함") { startSignalCheck() }
        startButton = button("3. 기록 시작") { requestStartRecording() }
        checkpointButton = button("기준점 표시 · 같은 지점에서 3초 정지") { markCheckpoint() }
        stopButton = button("기록 종료 / 신호 확인 종료") { stopEverything("기록을 종료했습니다. 완료 파일을 내보낼 수 있습니다.") }
        retryFinishButton = button("오류 기록 종료 저장 재시도") { retryFinalization() }
        completedButton = button("완료 기록 선택 · 다시 내보내기") { selectCompletedSession() }
        exportTraceButton = button("위치 trace JSONL 저장") { requestExport(raw = false) }
        exportRawButton = button("GNSS Raw TXT 저장") { requestExport(raw = true) }
        statusText = label("", 17f)
        label("표시되는 위치 정확도는 추정 반경이며 실제 오차가 아닙니다. Raw 수신·ADR 유효 값이 있어도 PPK 정답 생성이나 정확도 개선을 보장하지 않습니다.", 15f)
        setContentView(ScrollView(this).apply { addView(column, ViewGroup.LayoutParams(-1, -2)) })
    }

    private fun permissionsGranted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) permissions += Manifest.permission.ACTIVITY_RECOGNITION
        requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        message = if (permissionsGranted()) "권한이 허용되었습니다. 장착과 저장 동의를 확인한 뒤 신호를 확인하세요."
        else "정밀 위치 또는 신체 활동 권한이 거부되었습니다. 휴대폰 앱 설정에서 허용한 뒤 다시 확인하세요."
        renderStatus()
    }

    private fun startSignalCheck() {
        if (collecting || pendingFinalization != null || !permissionsGranted() || !mountConsent.isChecked || !storageConsent.isChecked) return
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            message = "휴대폰 위치 기능을 켠 뒤 신호 확인을 다시 누르세요."
            renderStatus()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        collecting = true
        val owner = ++generation
        latestLocation = null
        latestSnapshot = null
        latestQuality = null
        coordinator = PositioningCoordinator()
        gnssCount = 0L
        stationary = PositionStationaryState.UNKNOWN
        gpsHistory.clear()
        orientation.start()
        qualityObserver = GnssQualityObserver(
            source = AndroidGnssObservationSource(locationManager, Executor { handler.post(it) }),
            onSnapshot = { quality -> if (collecting && generation == owner) latestQuality = quality },
        ).also { it.start() }
        motionTracker = AndroidPedestrianMotionTracker(this) { decision ->
            if (collecting && generation == owner && decision.accepted) {
                val nowMs = SystemClock.elapsedRealtime()
                stationary = when (decision.state) {
                    PedestrianStationaryState.MOVING -> PositionStationaryState.MOVING
                    PedestrianStationaryState.CANDIDATE -> PositionStationaryState.CANDIDATE
                    PedestrianStationaryState.STATIONARY -> PositionStationaryState.STATIONARY
                }
                motionObservedAtMs = nowMs
                stationarySinceMs = decision.candidateSinceElapsedRealtimeMs ?: nowMs
            }
        }.also { it.start() }
        stepTracker = AndroidStepTracker(
            context = this,
            onStepCountChanged = { if (collecting && generation == owner) totalSteps = it },
            onTrackingStarted = { calibration.reset() },
            onStepEvent = { event -> if (collecting && generation == owner) onStep(event) },
        ).also { it.start() }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!collecting || generation != owner) return
                result.locations.forEach { onLocation(it) }
            }
        }
        locationCallback = callback
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L).setMaxUpdateAgeMillis(0L)
            .setGranularity(Granularity.GRANULARITY_FINE).build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnSuccessListener { if (!collecting || generation != owner) fused.removeLocationUpdates(callback) }
                .addOnFailureListener { if (collecting && generation == owner) stopEverything("위치 수신 등록에 실패했습니다. 권한과 위치 설정을 확인하세요.") }
        } catch (_: SecurityException) {
            stopEverything("위치 권한이 해제되었습니다. 권한 확인 후 다시 시작하세요.")
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        message = "신호 확인 중입니다. 실외에서 위치 수신을 기다리세요. 아직 파일에 저장하지 않습니다."
        handler.post(heartbeat)
        renderStatus()
    }

    private fun fixReady(): Boolean = latestLocation?.let {
        val ageMs = SystemClock.elapsedRealtime() - it.elapsedRealtimeNanos / NANOS_PER_MS
        ageMs in 0L..5_000L && !it.isFromMockProvider && it.latitude.isFinite() && it.longitude.isFinite() &&
            it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy in 0f..30f
    } == true

    private fun requestStartRecording() {
        if (!collecting || lease != null || !fixReady() || !storageReady) return
        val rawObserved = latestQuality?.let { !it.isStale && it.finiteSignalCount > 0 } == true
        if (!rawObserved) {
            AlertDialog.Builder(this).setTitle("위성 원시 신호 미확인")
                .setMessage("위치 기록은 가능하지만 위성 원시 데이터가 없으면 PPK 비교 정답을 만들기 어려울 수 있습니다. 실외에서 더 기다리거나 이 제한을 알고 기록하세요.")
                .setNegativeButton("더 기다리기", null)
                .setPositiveButton("제한을 알고 기록") { _, _ -> startRecording() }.show()
        } else startRecording()
    }

    private fun startRecording() {
        if (!collecting || lease != null || pendingFinalization != null || !fixReady() || !permissionsGranted()) return
        coordinator = PositioningCoordinator()
        calibration.reset()
        gpsHistory.clear()
        latestSnapshot = null
        lastTraceNs = 0L
        utcAnchorNs = null
        utcAnchorEpochMs = null
        gnssCount = 0L
        traceCount = 0L
        checkpointOrdinal = 0
        val started = recorder.start(PositioningTraceStartMetadata(
            routeId = UUID.randomUUID().toString(), scenario = "outdoor_roundtrip_local_profile",
            environment = "outdoor_unverified", direction = "roundtrip",
            deviceModel = Build.MODEL.replace(Regex("[^A-Za-z0-9._+() -]"), "_").take(64).ifEmpty { "Android" },
            androidApi = Build.VERSION.SDK_INT, mount = POSITION_TRACE_MOUNT,
            sourceKind = POSITION_TRACE_SOURCE_KIND, syntheticContractOnly = false, timebase = POSITION_TRACE_TIMEBASE,
        ), binding)
        if (started == null) { stopEverything("위치 기록 파일을 열지 못했습니다. 저장 공간을 확인하세요."); return }
        lease = started
        completedSessionId = null
        completedRawFile = null
        rawStatus = null
        rawSessionId = started.sessionId
        val rawFile = rawFileFor(started.sessionId)
        rawRecorder = OutdoorRawGnssRecorder(this, rawFile) { status ->
            if (rawSessionId != started.sessionId) return@OutdoorRawGnssRecorder
            rawStatus = status
            if (status.state == OutdoorRawGnssRecorder.State.ERROR && lease != null && !stopping) {
                stopEverything("Raw 저장 오류로 기록을 종료했습니다: ${status.errorMessage ?: "저장 실패"}")
            } else renderStatus()
        }
        if (rawRecorder?.start() != true) {
            stopEverything("Raw 기록을 시작하지 못해 시험 기록을 종료했습니다. 권한·위치·저장 공간을 확인하세요.")
            return
        }
        stepTracker?.resetForNewWalk()
        stepTracker?.start()
        message = "기록 중입니다. 출발 전 같은 기준점에서 3초 정지 후 기준점 표시를 누르세요."
        renderStatus()
    }

    private fun onLocation(location: Location) {
        val receivedAtMs = SystemClock.elapsedRealtime()
        val observedAtMs = location.elapsedRealtimeNanos / NANOS_PER_MS
        val validReference = coordinate(location.latitude, location.longitude) != null && !location.isFromMockProvider &&
            receivedAtMs - observedAtMs in 0L..5_000L
        val speed = location.speed.toDouble().takeIf { location.hasSpeed() && it.isFinite() && it >= 0.0 }
        val course = location.bearing.toDouble().takeIf { location.hasBearing() && it.isFinite() && it in 0.0..<360.0 }
        val gps = GpsHeading(observedAtMs,
            speed?.let { WalkingSpeedObservation(it, location.speedAccuracyMetersPerSecond.toDouble().takeIf { location.hasSpeedAccuracy() }, observedAtMs) },
            course?.let { HeadingObservation(it, location.bearingAccuracyDegrees.toDouble().takeIf { location.hasBearingAccuracy() }, observedAtMs) })
        gnssCount++
        val quality = qualityObserver?.snapshot(SystemClock.elapsedRealtimeNanos()) ?: latestQuality
        val reportedAccuracy = location.accuracy.toDouble().takeIf { location.hasAccuracy() && it.isFinite() && it >= 0.0 }
        val effectiveAccuracy = reportedAccuracy?.times(sqrt(quality?.measurementNoiseMultiplier ?: 1.0))
        var snapshot = coordinator.observeGnss(GnssPositionObservation(location.latitude, location.longitude,
            effectiveAccuracy, observedAtMs, receivedAtMs, location.isFromMockProvider))
        if (quality != null) snapshot = snapshot.copy(gnssQuality = quality,
            raw = snapshot.raw?.copy(reportedHorizontalAccuracyM = reportedAccuracy, effectiveHorizontalAccuracyM = effectiveAccuracy))
        latestSnapshot = snapshot
        val disposition = snapshot.gnssUpdate?.disposition
        val accepted = disposition == GnssObservationDisposition.INITIALIZED ||
            disposition == GnssObservationDisposition.REINITIALIZED || disposition == GnssObservationDisposition.APPLIED
        if (accepted && validReference && observedAtMs > (latestLocation?.elapsedRealtimeNanos?.div(NANOS_PER_MS) ?: -1L)) {
            latestLocation = Location(location)
            orientation.updateGeomagneticReference(location.latitude, location.longitude,
                location.altitude.takeIf { location.hasAltitude() && it.isFinite() } ?: 0.0,
                location.time.takeIf { it > 0L } ?: System.currentTimeMillis())
        }
        if (lease == null) { renderStatus(); return }
        if (disposition == GnssObservationDisposition.REINITIALIZED) calibration.reset()
        if (accepted && validReference && (gpsHistory.lastOrNull()?.timeMs ?: -1L) < observedAtMs) {
            gpsHistory.addLast(gps)
            while (gpsHistory.size > 64 || gpsHistory.firstOrNull()?.let { observedAtMs - it.timeMs > 5_000L } == true) gpsHistory.removeFirst()
        }
        calibration.onFix(RawWalkingCalibrationFix(location.latitude, location.longitude, effectiveAccuracy,
            observedAtMs, receivedAtMs, speed,
            location.speedAccuracyMetersPerSecond.toDouble().takeIf { location.hasSpeedAccuracy() }, course,
            location.bearingAccuracyDegrees.toDouble().takeIf { location.hasBearingAccuracy() },
            trustedRawFix = accepted && !location.isFromMockProvider && stationary == PositionStationaryState.MOVING &&
                receivedAtMs - motionObservedAtMs in 0L..2_000L,
        )).forEach { coordinator.calibrateProfile(it) }
        appendGnss(snapshot, location)
        renderStatus()
    }

    private fun onStep(event: StepEvent) {
        motionTracker?.recordStep(event.timestampMs)
        if (lease == null) return
        calibration.onStep(event.timestampMs, event.deltaSteps,
            event.source == StepEventSource.STEP_DETECTOR && event.confidence == StepEventConfidence.HIGH,
            SystemClock.elapsedRealtime()).forEach { coordinator.calibrateProfile(it) }
        val gps = gpsHistory.lastOrNull { it.timeMs <= event.timestampMs }
        val chest = orientation.chestMountedHeadingAt(event.timestampMs)
        val snapshot = coordinator.observeStep(event.deltaSteps, PedestrianHeadingInput(
            nowElapsedRealtimeMs = event.timestampMs, speed = gps?.speed, gpsCourse = gps?.course,
            magneticTrueHeading = chest, phoneForwardMounted = chest != null,
            magneticTrueHeadingAtGpsCourse = gps?.course?.let { orientation.chestMountedHeadingAt(it.elapsedRealtimeMs, 100L) },
        ), when (event.confidence) {
            StepEventConfidence.HIGH -> PdrStepQuality.HIGH
            StepEventConfidence.MEDIUM -> PdrStepQuality.MEDIUM
            StepEventConfidence.LOW -> PdrStepQuality.LOW
        }, receivedAtElapsedRealtimeMs = SystemClock.elapsedRealtime())
        latestSnapshot = snapshot
        appendMotion(snapshot, event.timestampMs, stepDetected = true, zuptApplied = false)
    }

    private fun appendGnss(snapshot: PositioningSnapshot, location: Location) {
        val measurementNs = location.elapsedRealtimeNanos
        if (measurementNs < 0L || measurementNs > SystemClock.elapsedRealtimeNanos() || location.time < 0L) return
        if (utcAnchorNs == null) { utcAnchorNs = measurementNs / NANOS_PER_MS * NANOS_PER_MS; utcAnchorEpochMs = location.time }
        val filtered = snapshot.filteredAtGnssMeasurement
            ?: snapshot.filtered?.takeIf { it.elapsedRealtimeMs == measurementNs / NANOS_PER_MS }
        val quality = snapshot.gnssQuality
        append(PositioningTraceRecord(
            elapsedRealtimeNs = nextTraceNs(), measurementElapsedRealtimeNs = measurementNs,
            measurementUtcEpochMs = location.time, source = PositionTraceSource.GNSS,
            rawPosition = coordinate(location.latitude, location.longitude),
            filteredPosition = filtered?.coordinate?.let { coordinate(it.latitude, it.longitude) },
            matchedPosition = null, routeMatchEvaluated = true,
            accuracyMeters = location.accuracy.toDouble().takeIf { location.hasAccuracy() && it.isFinite() && it >= 0.0 },
            speedMetersPerSecond = location.speed.toDouble().takeIf { location.hasSpeed() && it.isFinite() && it >= 0.0 },
            bearingDegrees = location.bearing.toDouble().takeIf { location.hasBearing() && it.isFinite() && it in 0.0..<360.0 },
            gnss = PositionGnssTrace(
                l5Available = when (quality.frequencySummary.state.name) {
                    "DUAL_FREQUENCY_OBSERVED" -> true; "SINGLE_FREQUENCY_OBSERVED" -> false; else -> null
                }, meanCn0DbHz = quality.medianCn0DbHz,
                risk = when (quality.signalEnvironmentRisk.name) {
                    "LOW" -> PositionGnssRisk.LOW; "MEDIUM" -> PositionGnssRisk.MEDIUM
                    "HIGH" -> PositionGnssRisk.HIGH; else -> PositionGnssRisk.UNAVAILABLE
                }, measurementNoiseMeters = snapshot.raw?.effectiveHorizontalAccuracyM,
            ), stepProfile = stepTrace(false), stationary = stationaryTrace(false),
        ))
        snapshot.filtered?.takeIf { it.elapsedRealtimeMs > measurementNs / NANOS_PER_MS }?.let {
            appendMotion(snapshot, it.elapsedRealtimeMs, stepDetected = false, zuptApplied = false)
        }
    }

    private fun appendMotion(snapshot: PositioningSnapshot?, measurementMs: Long, stepDetected: Boolean, zuptApplied: Boolean) {
        if (lease == null || measurementMs < 0L || measurementMs > SystemClock.elapsedRealtime()) return
        val measurementNs = measurementMs * NANOS_PER_MS
        val utcMs = utcFor(measurementNs)
        val heading = snapshot?.selectedHeading
        append(PositioningTraceRecord(
            elapsedRealtimeNs = nextTraceNs(), measurementElapsedRealtimeNs = measurementNs,
            measurementUtcEpochMs = utcMs,
            source = if (utcMs == null) PositionTraceSource.SENSOR_MONOTONIC_ONLY else PositionTraceSource.SENSOR_GNSS_ANCHORED,
            filteredPosition = snapshot?.filtered?.takeIf { it.elapsedRealtimeMs == measurementMs }?.coordinate?.let { coordinate(it.latitude, it.longitude) },
            matchedPosition = null, routeMatchEvaluated = true,
            stepProfile = stepTrace(stepDetected), stationary = stationaryTrace(zuptApplied),
            heading = PositionHeadingTrace(heading?.headingDegreesTrueNorth, when (heading?.source) {
                PedestrianHeadingSource.GPS_COURSE -> PositionHeadingSource.GNSS_COURSE
                PedestrianHeadingSource.MAGNETIC_TRUE -> PositionHeadingSource.MAGNETIC_ROTATION_VECTOR
                null -> PositionHeadingSource.NONE
            }),
        ))
    }

    private fun stepTrace(detected: Boolean): PositionStepProfileTrace {
        val profile = coordinator.currentProfile()
        return PositionStepProfileTrace(totalSteps.toLong().coerceAtLeast(0L), detected, profile.stepLengthM,
            profile.stepLengthM, profile.acceptedSampleCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun stationaryTrace(zupt: Boolean) = PositionStationaryTrace(
        stationary == PositionStationaryState.STATIONARY, stationary, zupt,
    )

    private fun coordinate(latitude: Double, longitude: Double): PositionTraceCoordinate? =
        if (latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
            PositionTraceCoordinate(latitude, longitude) else null

    private fun nextTraceNs(): Long = maxOf(SystemClock.elapsedRealtimeNanos(), lastTraceNs + 1L).also { lastTraceNs = it }

    private fun utcFor(measurementNs: Long): Long? {
        val anchorNs = utcAnchorNs ?: return null
        val anchorUtc = utcAnchorEpochMs ?: return null
        return runCatching { Math.addExact(anchorUtc, Math.floorDiv(Math.subtractExact(measurementNs, anchorNs), NANOS_PER_MS)) }
            .getOrNull()?.takeIf { it >= 0L }
    }

    private fun append(record: PositioningTraceRecord) {
        val active = lease ?: return
        if (runCatching { recorder.append(active, record) }.getOrDefault(false)) traceCount++
        else stopEverything("위치 파일 저장 오류로 기록을 종료했습니다. 저장 공간과 내보내기 상태를 확인하세요.")
    }

    private fun markCheckpoint() {
        val active = lease ?: return
        val nowMs = SystemClock.elapsedRealtime()
        if (stationary != PositionStationaryState.STATIONARY || nowMs - motionObservedAtMs !in 0L..1_500L ||
            nowMs - stationarySinceMs < 1_500L) {
            message = "기준점에서 휴대폰을 움직이지 않고 3초 이상 기다린 뒤 다시 누르세요."
            renderStatus(); return
        }
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val utcMs = utcFor(nowNs)
        val ordinal = checkpointOrdinal + 1
        val checkpoint = PositioningTraceCheckpoint(nextTraceNs(), nowNs, utcMs,
            if (utcMs == null) PositionTraceSource.CHECKPOINT_MONOTONIC_ONLY else PositionTraceSource.CHECKPOINT_GNSS_ANCHORED,
            "CP${ordinal.toString().padStart(3, '0')}", ordinal, stationary, nowMs - stationarySinceMs)
        if (runCatching { recorder.markCheckpoint(active, checkpoint) }.getOrDefault(false)) {
            checkpointOrdinal = ordinal
            message = "기준점 CP${ordinal.toString().padStart(3, '0')} 저장됨. 같은 표식을 복귀 때 다시 기록하세요."
        } else stopEverything("기준점 저장 실패로 기록을 종료했습니다.")
        renderStatus()
    }

    private fun stopEverything(reason: String) {
        if (stopping) return
        stopping = true
        val active = lease
        lease = null
        collecting = false
        generation++
        handler.removeCallbacks(heartbeat)
        locationCallback?.let { runCatching { fused.removeLocationUpdates(it) } }
        locationCallback = null
        stepTracker?.stop(); stepTracker = null
        motionTracker?.close(); motionTracker = null
        qualityObserver?.close(); qualityObserver = null
        orientation.stop()
        calibration.reset()
        gpsHistory.clear()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        var finalMessage = reason
        if (active != null) {
            val raw = rawRecorder
            val rawStopped = runCatching { raw?.stop() == true }.getOrDefault(false)
            val traceStopped = runCatching { recorder.stop(active) }.getOrDefault(false)
            if (traceStopped) completedSessionId = active.sessionId
            else pendingFinalization = active
            if (rawStopped) completedRawFile = raw?.status?.file
            if (!traceStopped || !rawStopped) finalMessage += " 일부 파일 완료 처리에 실패했습니다. 저장 가능 버튼과 오류 상태를 확인하세요."
            refreshCompletedSessions()
        }
        rawRecorder = null
        message = finalMessage
        stopping = false
        renderStatus()
    }

    private fun retryFinalization() {
        val unfinished = pendingFinalization ?: return
        if (runCatching { recorder.stop(unfinished) }.getOrDefault(false)) {
            pendingFinalization = null
            completedSessionId = unfinished.sessionId
            refreshCompletedSessions()
            message = "위치 기록 종료 저장이 완료되었습니다. 완료 파일을 내보내세요. Raw 오류가 있었다면 사후 완전성 검사가 필요합니다."
        } else {
            message = "종료 저장이 아직 실패합니다. 기기 저장 공간을 확보한 뒤 다시 누르세요. 실패가 계속되면 수집을 시작하지 말고 보존된 파일을 점검하세요."
        }
        renderStatus()
    }

    private fun requestExport(raw: Boolean) {
        if (lease != null || exporting || pendingExport != null) return
        val sessionId = completedSessionId ?: return
        val file = if (raw) completedRawFile ?: return else null
        val title = exportTitle(sessionId, raw)
        pendingExport = ExportRequest(sessionId, file, title)
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (raw) "text/plain" else "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, title)
        }, EXPORT_REQUEST)
    }

    private fun rawFileFor(sessionId: String) = File(noBackupFilesDir, "outdoor_position_test/raw_$sessionId.txt")

    private fun exportTitle(sessionId: String, raw: Boolean) =
        "walksafe_outdoor_${sessionId.take(8)}.${if (raw) "txt" else "jsonl"}"

    private fun refreshCompletedSessions() {
        completedSessions = runCatching { recorder.list(binding) }.getOrDefault(emptyList())
            .filter { it.status == PositionFieldSessionStatus.COMPLETED }
        if (completedSessions.none { it.sessionId == completedSessionId }) {
            completedSessionId = completedSessions.firstOrNull()?.sessionId
        }
        completedRawFile = completedSessionId?.let(::rawFileFor)?.takeIf { it.isFile }
    }

    private fun selectCompletedSession() {
        if (lease != null || exporting) return
        refreshCompletedSessions()
        if (completedSessions.isEmpty()) { message = "완료된 기록이 없습니다."; renderStatus(); return }
        val choices = completedSessions.toList()
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
        val labels = choices.map { "${format.format(Date(it.startedAtEpochMs))} · ${it.recordCount}개 · ${it.sessionId.take(8)}" }
        AlertDialog.Builder(this).setTitle("완료 기록 선택").setItems(labels.toTypedArray()) { _, index ->
            val selected = choices[index]
            completedSessionId = selected.sessionId
            completedRawFile = rawFileFor(selected.sessionId).takeIf { it.isFile }
            rawSessionId = selected.sessionId
            rawStatus = null
            message = "완료 기록 ${selected.sessionId.take(8)} 선택됨. 위치·Raw 파일을 다시 내보낼 수 있습니다. Raw 완전성은 사후 검사하세요."
            renderStatus()
        }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingExport?.let {
            outState.putString("pending_export_session", it.sessionId)
            outState.putBoolean("pending_export_raw", it.rawFile != null)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST) return
        val request = pendingExport ?: return
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            message = "내보내기를 취소했습니다. 완료 파일을 다시 저장할 수 있습니다."
            renderStatus(); return
        }
        exporting = true
        message = "${request.title} 저장 중…"
        renderStatus()
        exportExecutor.execute {
            val success = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    if (request.rawFile != null) {
                        request.rawFile.inputStream().use { it.copyTo(output) }; output.flush(); true
                    } else recorder.export(request.sessionId, binding, output).success
                } == true
            }.getOrDefault(false)
            val partialRemoved = success || runCatching { contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            runOnUiThread {
                exporting = false
                message = if (success) "저장 완료: ${request.title}\n저장 위치: $uri"
                else "저장 실패. 완료된 기기 내 원본은 유지됩니다." + if (partialRemoved) " 다시 내보내세요." else " 선택한 폴더의 불완전 파일을 확인하세요."
                if (!isDestroyed) renderStatus()
            }
        }
    }

    override fun onPause() {
        if (collecting || lease != null) stopEverything("화면을 벗어나 기록을 종료했습니다. 완료 파일을 내보내거나 명시적으로 새 기록을 시작하세요.")
        super.onPause()
    }

    override fun onResume() { super.onResume(); if (::statusText.isInitialized) renderStatus() }

    override fun onDestroy() {
        if (collecting || lease != null) stopEverything("화면 종료로 기록을 종료했습니다.")
        exportExecutor.shutdown()
        super.onDestroy()
    }

    private fun renderStatus() {
        if (!::statusText.isInitialized) return
        val active = lease != null
        val raw = rawStatus
        val ready = fixReady()
        val profile = coordinator.currentProfile()
        val accuracy = latestLocation?.accuracy?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: "미확인"
        val signal = when { !collecting -> "수신 중지"; ready -> "출발 가능 · 추정 반경 ${accuracy}m"; else -> "아직 출발하지 마세요 · 실외 위치 수신 대기" }
        val rawWarning = when {
            (raw?.rawMeasurementCount ?: 0L) == 0L -> "Raw 미수신: PPK 정답 자료가 부족할 수 있습니다."
            (raw?.adrValidCount ?: 0L) == 0L -> "기록 가능 · ADR 유효 자료 없음: PPK 정답이 부족할 수 있습니다."
            else -> "Raw·ADR 관측 있음 · PPK 가능 여부는 사후 검사 필요"
        }
        statusText.text = "${if (active) "● 기록 중" else "기록 안 함"}\n$signal\n" +
            "GNSS $gnssCount · Raw ${raw?.rawMeasurementCount ?: 0L} · epoch ${raw?.epochCount ?: 0L} · ADR valid ${raw?.adrValidCount ?: 0L}\n" +
            "걸음 $totalSteps · trace $traceCount · 기준점 $checkpointOrdinal\n" +
            "정지 상태 ${stationary.name} · 시험 보폭 ${String.format(Locale.US, "%.2f", profile.stepLengthM)}m · 학습 ${profile.acceptedSampleCount}\n" +
            "$rawWarning\n${raw?.warningMessage ?: ""}\n${raw?.errorMessage ?: ""}\n\n$message"
        mountConsent.isEnabled = !collecting && !exporting
        storageConsent.isEnabled = !collecting && !exporting
        previewButton.isEnabled = !collecting && !exporting && pendingFinalization == null && storageReady && permissionsGranted() && mountConsent.isChecked && storageConsent.isChecked
        startButton.isEnabled = collecting && !active && ready && !exporting
        checkpointButton.isEnabled = active
        stopButton.isEnabled = collecting || active
        exportTraceButton.isEnabled = !active && !exporting && pendingExport == null && completedSessionId != null
        exportRawButton.isEnabled = exportTraceButton.isEnabled && completedRawFile != null
        completedButton.isEnabled = !active && !exporting && pendingExport == null && completedSessions.isNotEmpty()
        retryFinishButton.isEnabled = pendingFinalization != null && !exporting
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PERMISSION_REQUEST = 481
        const val EXPORT_REQUEST = 482
        const val NANOS_PER_MS = 1_000_000L
    }
}
